package com.felp.ludologlink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File

/**
 * Antes de abrir un juego, Ludolog pregunta (si Link esta instalado): ¿hay en otro device una
 * partida de este juego mas nueva que la de aqui? Es la unica pregunta que Ludolog le hace a Link y
 * espera respuesta; sin Link, o si no contesta a tiempo, Ludolog lanza como siempre. Ver
 * docs/ludolog-link.md (Ludolog) y LinkSaveCheck.kt alli.
 *
 * Respuestas (`result`): ok (nada mas nuevo), synced (lo habia y se trajo), stale (lo hay pero ese
 * device no contesta: `peer`, `playedAt`), conflict (cambio en los dos: se elige en Link), off (lo
 * hay, pero compartir con los devices esta apagado aqui: no se trae, Ludolog avisa y ofrece
 * encenderlo, metodo "sync_on").
 *
 * Con compartir apagado solo se pregunta al otro device CUANDO jugo (/saves/state, unas fechas): sin
 * eso no se sabria lo jugado alla mientras este no escuchaba. Ninguna partida viaja.
 */
object SaveCheck {

    const val OK = "ok"
    const val SYNCED = "synced"
    const val STALE = "stale"
    const val CONFLICT = "conflict"
    const val OFF = "off"

    private fun answer(result: String, peer: String? = null, playedAt: Long = 0L) = Bundle().apply {
        putString("result", result); peer?.let { putString("peer", it) }; putLong("playedAt", playedAt)
    }

    fun check(ctx: Context, pkg: String?, file: String?, title: String?): Bundle {
        if (pkg.isNullOrEmpty() || file.isNullOrEmpty() || !Prefs.saveCheck(ctx)) return answer(OK)
        // Solo si este emulador se sincroniza aqui: con carpeta y que se pueda.
        val e = Saves.get(ctx, pkg)?.takeIf { it.configured && it.folder.isDirectory } ?: return answer(OK)
        if (!Saves.supported(e)) return answer(OK)
        val peers = Peers.all(ctx)
        if (peers.isEmpty()) return answer(OK)
        val keys = listOfNotNull(Saves.romKey(file), title?.let { Saves.romKey(it) }).filter { it.isNotEmpty() }.distinct()
        fun latest(plays: Map<String, Long>) = keys.maxOf { k -> Saves.playedOfKey(k, plays) }
        val mine = latest(Saves.gamePlays(ctx, pkg))
        // Si no contesta: lo jugado en los otros devices segun sus cuadernos compartidos (Companion).
        val shared by lazy { otherLogbooks(ctx, pkg) }
        val sync = Prefs.syncDevices(ctx)
        // Lo que dice cada device, a todos a la vez y con lo ya preguntado al volver a Ludolog (PeerState).
        // Nulo: no contesta (o alli compartir esta apagado), y entonces valen sus cuadernos.
        val told = PeerState.playsOfAll(ctx, peers, pkg).mapValues { (_, g) -> g?.let { latest(it) } }
        for (p in peers) {
            val since = Saves.base(p.id, pkg).first
            val theirs = told[p.id] ?: latest(shared)
            // Jugado alla despues del ultimo sync con el y despues de la ultima vez aqui: su partida puede ser mas nueva.
            if (theirs <= since || theirs <= mine) continue
            if (!sync) return answer(OFF, p.name, theirs)
            val reached = if (told[p.id] == null) null else Peers.reachQuick(ctx, p)
            if (reached == null) return answer(STALE, p.name, theirs)
            val note = SaveSync.syncNow(ctx, pkg, reached)
            PeerState.invalidate(p.id)
            LinkState.addLog("Before playing, saves of ${Saves.appName(ctx, pkg)} with ${p.name}: $note", "saves")
            val stillOpen = Saves.conflicts(ctx).any { c -> c.pkg == pkg && c.peerId == p.id &&
                keys.any { k -> c.game.startsWith(k) || k.startsWith(c.game) } }
            return answer(if (stillOpen) CONFLICT else SYNCED, p.name, theirs)
        }
        return answer(OK)
    }

    /** Lo jugado con ese emulador segun los cuadernos de los otros devices que hay aqui (companion/, sin el propio). */
    private fun otherLogbooks(ctx: Context, pkg: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        val dir = Ludolog.dataDir(ctx) ?: return out
        val own = Ludolog.ownLogbook(dir)
        // Solo los escribe Link, y solo se leen: directo, sin copiarlos.
        for (db in File(dir, "companion").listFiles { f -> f.name.endsWith(".db") && f.name != own }.orEmpty()) {
            for ((t, at) in Ludolog.sessionPlays(db, pkg)) { val k = Saves.romKey(t); if (at > (out[k] ?: 0L)) out[k] = at }
        }
        return out
    }
}

/**
 * Por donde pregunta Ludolog (ContentResolver.call, metodo "check"). Solo quien tenga el permiso de
 * firma de Ludolog: call() no lo comprueba solo, asi que se mira aqui.
 */
class SaveCheckProvider : ContentProvider() {
    // Lo primero que corre al arrancar el proceso, venga quien venga: aqui se abre el registro.
    override fun onCreate(): Boolean {
        context?.let { LinkLog.init(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (ctx.checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Ludolog only")
        // Si Link esta escuchando, para el LED de su radio en Ludolog (verde o rojo). Ludolog lo
        // pregunta al volver a primer plano, y con `needed` y sin `on` despierta el servicio: Link
        // no puede desde aqui (Android 15 se lo niega en segundo plano), Ludolog a la vista si.
        if (method == "state") {
            // Y de paso, preguntar ya a los otros devices: abrir un juego despues no espera a la red.
            PeerState.prefetch(ctx)
            return Bundle().apply {
                putBoolean("on", LinkState.running.value)
                putBoolean("needed", LinkService.needed(ctx))
                putString("address", LinkState.address.value)
                putInt("peers", Peers.all(ctx).size)
                putBoolean("pcLink", Prefs.pcLink(ctx))
                putBoolean("sync", Prefs.syncDevices(ctx))
            }
        }
        // El aviso "off" de antes de jugar: la persona eligio volver a compartir desde Ludolog.
        if (method == "sync_on") {
            LinkService.setSyncDevices(ctx, true)
            return Bundle().apply { putBoolean("sync", true) }
        }
        if (method != "check" || extras == null) return null
        return SaveCheck.check(ctx, extras.getString("pkg"), extras.getString("file"), extras.getString("title"))
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val PERMISSION = "com.felp.frontcomp.permission.LINK"
    }
}
