package com.felp.ludologlink

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Compartir el Companion con las consolas emparejadas, sin PC.
 *
 * Ludolog guarda un cuaderno por consola en `<datos>/companion/` y lee los de las demas, que solo
 * lee (Logbooks.others, desde una copia suya). Asi que compartir es cruzarse archivos, con las
 * mismas reglas que el "Sync companions" del PC:
 * - el cuaderno propio de cada consola lo manda ella, y la otra nunca lo cambia;
 * - el de una tercera consola que no esta aqui, lo mas nuevo (por lo que lleva dentro).
 *
 * Se hace al encender el servicio, al avisar Ludolog de una partida nueva (COMPANION_CHANGED, ver
 * LinkService) y a mano. Cada consola puede hacerlo sola: con el token de la otra, manda y trae.
 */
object CompanionShare {
    private val busy = AtomicBoolean(false)

    data class Version(val last: Long, val sessions: Int, val missions: Int) : Comparable<Version> {
        override fun compareTo(other: Version) = compareValuesBy(this, other, { it.last }, { it.sessions }, { it.missions })

        companion object {
            fun of(j: JSONObject?) = j?.let { Version(it.optLong("last"), it.optInt("sessions"), it.optInt("missions")) }
        }
    }

    /** Con todas las emparejadas, en segundo plano. Si ya hay una pasada en marcha, no se apila otra. */
    fun syncAll(ctx: Context, why: String) {
        val app = ctx.applicationContext
        // Compartir apagado: no sale nada; el cuaderno sigue apuntando y se pone al dia al encenderlo.
        if (Peers.all(app).isEmpty() || !Prefs.syncDevices(app) || !busy.compareAndSet(false, true)) return
        thread(name = "companion-share", isDaemon = true) {
            try {
                LinkState.post { LinkState.sharing.value = true }
                for (p in Peers.all(app)) {
                    val note = runCatching { syncOne(app, p) }.getOrElse { "couldn't sync: ${it.message}" }
                    Peers.all(app).firstOrNull { it.id == p.id }?.let { Peers.save(app, it.copy(lastSync = System.currentTimeMillis(), note = note)) }
                    LinkState.addLog("Companion with ${p.name} ($why): $note", "companion", error = note.startsWith("couldn't"))
                }
            } finally {
                busy.set(false)
                LinkState.post { LinkState.sharing.value = false }
            }
        }
    }

    private fun syncOne(ctx: Context, peer: Peer): String {
        val p = Peers.reach(ctx, peer) ?: return "not reachable"
        val dir = Ludolog.dataDir(ctx) ?: return "Ludolog isn't installed here"
        val remote = Peers.json(Peers.open(p.host, p.port, "GET", "/ludolog/companion", token = p.token, readTimeout = 60_000))
        val theirOwn = remote.optString("own").ifEmpty { null }
        val theirs = remote.optJSONArray("files")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { j -> j.getString("name") to Version.of(j)!! } } }.orEmpty()
        val local = Ludolog.companionList(ctx)
        val myOwn = local.optString("own").ifEmpty { null }
        val mine = local.optJSONArray("files")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { j -> j.getString("name") to Version.of(j)!! } } }.orEmpty()

        var sent = 0
        var got = 0
        for (name in (mine.keys + theirs.keys).sorted()) {
            val m = mine[name]
            val t = theirs[name]
            when {
                // El mio: manda el mio, siempre que el suyo no sea igual.
                name == myOwn -> if (m != null && m != t) { push(ctx, p, name, own = true); sent++ }
                // El suyo: lo traigo si el mio no es igual. Nunca se lo cambio.
                name == theirOwn -> if (t != null && m != t) { pull(ctx, p, name, dir); got++ }
                // De otra consola: lo mas nuevo.
                m != null && (t == null || m > t) -> { push(ctx, p, name, own = false); sent++ }
                t != null && (m == null || t > m) -> { pull(ctx, p, name, dir); got++ }
            }
        }
        // Y las caratulas de los juegos de las otras consolas que aqui no tienen (ver CompanionCovers).
        val covers = runCatching { CompanionCovers.pull(ctx, p, dir, myOwn) }.getOrDefault(0)
        // Y las correcciones de nombres, descripciones y generos (ver MetaEdits).
        val meta = runCatching { MetaEdits.syncWith(ctx, p) }.getOrDefault("")
        val tail = (if (covers > 0) ", $covers ${if (covers == 1) "cover" else "covers"}" else "") +
            (if (meta.isNotEmpty()) ", $meta" else "")
        return when {
            sent == 0 && got == 0 && covers == 0 && meta.isNotEmpty() -> meta
            sent == 0 && got == 0 -> "already in sync$tail"
            else -> "sent $sent, got $got$tail"
        }
    }

    /** El propio va por copia coherente (Ludolog puede estar escribiendo); el de otra, tal cual. */
    private fun push(ctx: Context, p: Peer, name: String, own: Boolean) {
        val dir = Ludolog.dataDir(ctx) ?: return
        val f = File(dir, "companion/$name")
        val send = if (own) Ludolog.snapshot(ctx, f) else f
        try {
            Peers.putLogbook(p, name, send)
        } finally {
            if (send !== f) send.delete()
        }
    }

    private fun pull(ctx: Context, p: Peer, name: String, dir: File) {
        val incoming = File(dir, "companion/.in-$name")
        try {
            Peers.download(p, "companion/$name", incoming)
            Ludolog.installLogbook(ctx, name, incoming)?.let { throw java.io.IOException(it) }
        } finally {
            incoming.delete()
        }
    }
}
