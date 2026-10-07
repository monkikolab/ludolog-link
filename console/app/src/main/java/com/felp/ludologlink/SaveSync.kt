package com.felp.ludologlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Sincronizar las partidas guardadas de un emulador con las consolas emparejadas, archivo a archivo,
 * sin fiarse de las fechas de los archivos.
 *
 * - Que cambio lo dice el CONTENIDO (huella SHA-1) contra la base: como quedo cada archivo la
 *   ultima vez que las dos consolas lo tuvieron igual (Saves.base).
 * - Si cambio en un solo lado, ese lado manda.
 * - Si cambio en los dos (o es la primera vez y son distintos), decide CUANDO SE JUGO ese juego en
 *   cada consola (Saves.gamePlays: lo apuntado por Ludolog y el cuaderno del Companion):
 *   - con base: si solo en una se jugo desde la ultima sincronizacion, esa manda; si en las dos, se
 *     pregunta;
 *   - la primera vez: manda la que lo jugo mas recientemente; si no consta en ninguna, se pregunta.
 * - Nada se borra en el otro lado. Antes de escribir, la que recibe respalda su carpeta. Con un juego
 *   abierto en ese emulador, no se le escribe: se intenta la siguiente vez.
 * Lo que se pregunta va agrupado por juego (Saves.saveKey) y se elige en la pestaña SAVES.
 */
object SaveSync {
    private val busy = AtomicBoolean(false)

    /**
     * [pkgs] nulo: todos los que tengan carpeta. [force]: lo de aqui manda, sin preguntar (lo pidio la
     * persona). Falso si no empezo porque ya habia otra pasada: quien llama decide si reintenta.
     */
    fun syncAll(ctx: Context, why: String, pkgs: Set<String>? = null, force: Boolean = false): Boolean {
        val app = ctx.applicationContext
        // Compartir apagado: nada viaja (lo jugado queda apuntado para cuando se encienda).
        if (Peers.all(app).isEmpty() || !Prefs.syncDevices(app)) return true
        if (!busy.compareAndSet(false, true)) return false
        thread(name = "save-sync", isDaemon = true) {
            try {
                LinkState.post { LinkState.savesSyncing.value = true }
                for (e in Saves.all(app).filter { it.configured && (pkgs == null || it.pkg in pkgs) }) {
                    val here = Here.of(app, e)
                    val note = Peers.all(app).joinToString(" · ") { p ->
                        "${p.name}: " + runCatching { syncOne(app, e.pkg, p, force, here) }.getOrElse { "couldn't: ${it.message}" }
                    }
                    Saves.update(app, e.pkg) { it.copy(lastSync = System.currentTimeMillis(), note = note) }
                    LinkState.addLog("Saves of ${Saves.appName(app, e.pkg)} ($why): $note", "saves", error = note.contains("couldn't"))
                }
                notifyConflicts(app)
            } finally {
                busy.set(false)
                LinkState.post { LinkState.savesSyncing.value = false }
            }
        }
        return true
    }

    /**
     * Un emulador con un device, ya y en este hilo: lo pide Ludolog antes de abrir un juego (ver
     * SaveCheck). Si hay otra pasada en curso se espera un poco; nunca dos a la vez.
     */
    fun syncNow(ctx: Context, pkg: String, peer: Peer): String {
        val app = ctx.applicationContext
        if (!Prefs.syncDevices(app)) return "sharing is off"
        val until = System.currentTimeMillis() + 6_000
        while (!busy.compareAndSet(false, true)) {
            if (System.currentTimeMillis() > until) return "busy"
            Thread.sleep(200)
        }
        return try {
            val note = runCatching { syncOne(app, pkg, peer, false) }.getOrElse { "couldn't: ${it.message}" }
            Saves.update(app, pkg) { it.copy(lastSync = System.currentTimeMillis(), note = "${peer.name}: $note") }
            notifyConflicts(app)
            note
        } finally {
            busy.set(false)
        }
    }

    // ----------------------------------------------------------------- reglas

    /** Mismo contenido: mismo tamaño y misma huella. */
    fun same(a: JSONObject?, b: JSONObject?) =
        a != null && b != null && a.optLong("size") == b.optLong("size") && a.optString("sha1") == b.optString("sha1")

    private fun byPath(a: JSONArray) = (0 until a.length()).associate { a.getJSONObject(it).let { j -> j.getString("path") to j } }

    private class Plan(val push: List<String>, val pull: List<String>, val ask: Map<String, List<String>>, val agreed: List<String>)

    private fun plan(
        local: Map<String, JSONObject>, remote: Map<String, JSONObject>,
        base: Map<String, JSONObject>, hasBase: Boolean,
        myPlays: Map<String, Long>, mySince: Long,
        theirPlays: Map<String, Long>, theirSince: Long,
        force: Boolean,
    ): Plan {
        val push = ArrayList<String>()
        val pull = ArrayList<String>()
        val doubt = ArrayList<String>()
        val agreed = ArrayList<String>()
        for (path in (local.keys + remote.keys).sorted()) {
            val l = local[path]
            val r = remote[path]
            val b = base[path]
            if (same(l, r)) { agreed += path; continue }
            // Si alguno de los dos no se puede leer, ese archivo no se toca: ni se manda ni se pisa.
            if (l?.optBoolean("unreadable") == true || r?.optBoolean("unreadable") == true) continue
            if (force) { if (l != null) push += path; continue }
            val lChanged = l != null && !same(l, b)
            val rChanged = r != null && !same(r, b)
            when {
                // Solo en un lado: nuevo alla (o cambiado) se manda; si el otro lo borro, no se borra ni se repone.
                r == null -> if (b == null || lChanged) push += path
                l == null -> if (b == null || rChanged) pull += path
                b != null && lChanged && !rChanged -> push += path
                b != null && rChanged && !lChanged -> pull += path
                else -> doubt += path
            }
        }
        // Lo dudoso, por juego: decide cuando se jugo.
        val ask = HashMap<String, List<String>>()
        for ((game, files) in doubt.groupBy(Saves::saveKey)) {
            val mine = files.maxOf { Saves.playedOf(it, myPlays) }
            val theirs = files.maxOf { Saves.playedOf(it, theirPlays) }
            val winner = if (hasBase) {
                val mineSince = mine > mySince
                val theirsSince = theirs > theirSince
                when {
                    mineSince && !theirsSince -> 1
                    theirsSince && !mineSince -> -1
                    else -> 0
                }
            } else when {
                mine == 0L && theirs == 0L -> 0
                mine > theirs -> 1
                theirs > mine -> -1
                else -> 0
            }
            when (winner) {
                1 -> push += files
                -1 -> pull += files
                else -> ask[game] = files
            }
        }
        // Un juego en duda entra entero en la pregunta: no se mezclan sus partes.
        val asked = ask.keys
        return Plan(push.filter { Saves.saveKey(it) !in asked }, pull.filter { Saves.saveKey(it) !in asked },
            ask.mapValues { (g, files) -> (files + (push + pull).filter { Saves.saveKey(it) == g }).distinct().sorted() }, agreed)
    }

    // ------------------------------------------------------------ una pasada

    private fun state(p: Peer, pkg: String) = Peers.json(Peers.open(p.host, p.port, "GET", "/saves/state", mapOf("pkg" to pkg), p.token))

    private fun plays(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.getLong(it) }.orEmpty()

    /**
     * Lo de esta consola para un emulador: si se puede, sus archivos y lo jugado. No depende de con
     * que device se sincronice, asi que se calcula una vez por emulador; los archivos se vuelven a
     * leer solo despues de traer algo ([stale]).
     */
    private class Here(val mine: EmuSaves, val supported: Boolean, val plays: Map<String, Long>) {
        private var cached: Map<String, JSONObject>? = null
        var stale = false
        fun local(): Map<String, JSONObject> {
            if (cached == null || stale) { cached = byPath(Saves.manifest(mine)); stale = false }
            return cached!!
        }
        companion object {
            fun of(ctx: Context, e: EmuSaves) = Here(e, Saves.supported(e), Saves.gamePlays(ctx, e.pkg))
        }
    }

    private fun syncOne(ctx: Context, pkg: String, peer: Peer, force: Boolean, given: Here? = null): String {
        val p = Peers.reach(ctx, peer) ?: return "not reachable"
        val here = given ?: Here.of(ctx, Saves.get(ctx, pkg)?.takeIf { it.configured } ?: return "no folder here")
        val mine = here.mine
        if (!here.supported) return "not supported here"
        val st = state(p, pkg)
        if (!st.optBoolean("configured")) return "no folder set there"
        if (!st.optBoolean("supported", true)) return "not supported there"
        val remote = byPath(remoteManifest(p, pkg))
        val local = here.local()
        val (since, base) = Saves.base(p.id, pkg)
        val myPlays = here.plays
        val theirPlays = plays(st.optJSONObject("games"))
        val plan = plan(local, remote, base, base.isNotEmpty(), myPlays, since, theirPlays, st.optLong("syncedAt"), force)

        val notes = ArrayList<String>()
        var push = plan.push
        var pull = plan.pull
        if (push.isNotEmpty() && st.optBoolean("inUse")) { notes += "open there, later"; push = emptyList() }
        if (pull.isNotEmpty() && Saves.inUse(ctx, pkg)) { notes += "open here, later"; pull = emptyList() }
        send(p, mine, push)
        fetch(ctx, p, mine, pull, remote)
        if (pull.isNotEmpty()) here.stale = true
        // La base es lo que se comparo o se mando, no lo que haya ahora: si el emulador guardo a
        // mitad, ese archivo no queda como igual (ver agree).
        agree(ctx, p, mine, plan.agreed.associateWith { local.getValue(it) } + push.associateWith { local.getValue(it) } +
            pull.associateWith { remote.getValue(it) }, recheck = push.isNotEmpty() || pull.isNotEmpty())
        Saves.setConflicts(ctx, p, pkg, plan.ask.map { (game, files) ->
            SaveConflict(p.id, p.name, pkg, game, files,
                files.mapNotNull { f -> local[f]?.let { f to it.optLong("size") } }.toMap(),
                files.mapNotNull { f -> remote[f]?.let { f to it.optLong("size") } }.toMap(),
                files.maxOf { Saves.playedOf(it, myPlays) }, files.maxOf { Saves.playedOf(it, theirPlays) },
                System.currentTimeMillis())
        })
        if (push.isNotEmpty()) notes += "sent ${push.size}"
        if (pull.isNotEmpty()) notes += "got ${pull.size}"
        if (plan.ask.isNotEmpty()) notes += "${plan.ask.size} to choose"
        // Lo que no se pudo leer, dicho: si no, "in sync" mentiria.
        val hereLocked = local.values.filter { it.optBoolean("unreadable") }.map { it.getString("path") }
        val thereLocked = remote.values.filter { it.optBoolean("unreadable") }.map { it.getString("path") }
        if (hereLocked.isNotEmpty()) notes += "can't read here: ${hereLocked.joinToString()}"
        if (thereLocked.isNotEmpty()) notes += "can't read there: ${thereLocked.joinToString()}"
        return notes.joinToString(", ").ifEmpty { "in sync" }
    }

    private fun remoteManifest(p: Peer, pkg: String) =
        Peers.json(Peers.open(p.host, p.port, "GET", "/saves/manifest", mapOf("pkg" to pkg), p.token, 300_000)).optJSONArray("files") ?: JSONArray()

    /** Lo de aqui alla: alla se respalda antes (begin). */
    private fun send(p: Peer, mine: EmuSaves, paths: List<String>) {
        if (paths.isEmpty()) return
        Peers.json(Peers.open(p.host, p.port, "POST", "/saves/begin", mapOf("pkg" to mine.pkg), p.token, 300_000))
        for (path in paths) {
            val f = Saves.fileIn(mine, path) ?: continue
            val c = Peers.open(p.host, p.port, "PUT", "/saves/file", mapOf("pkg" to mine.pkg, "path" to path), p.token, 120_000)
            c.doOutput = true
            c.setFixedLengthStreamingMode(f.length())
            c.outputStream.use { o -> f.inputStream().use { it.copyTo(o) } }
            Peers.json(c)
        }
    }

    /** Lo de alla aqui: se respalda lo de aqui antes. Llega entero y con su huella, o no se pone. */
    private fun fetch(ctx: Context, p: Peer, mine: EmuSaves, paths: List<String>, remote: Map<String, JSONObject>) {
        if (paths.isEmpty()) return
        Saves.backup(ctx, mine, "before-sync")
        for (path in paths) {
            val r = remote[path] ?: continue
            val dest = Saves.fileIn(mine, path) ?: continue
            dest.parentFile?.mkdirs()
            // Uno por descarga: dos pasadas a la vez no escriben en el mismo temporal.
            val tmp = File(dest.parentFile, "." + dest.name + "." + System.nanoTime() + Saves.PART)
            try {
                val c = Peers.open(p.host, p.port, "GET", "/saves/file", mapOf("pkg" to mine.pkg, "path" to path), p.token, 120_000)
                try {
                    if (c.responseCode != 200) Peers.json(c)
                    c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                } finally {
                    c.disconnect()
                }
                if (tmp.length() != r.optLong("size") || Saves.sha1(tmp) != r.optString("sha1")) throw IOException("$path arrived damaged")
                // renameTo reemplaza: borrar antes dejaba sin ninguno si fallaba.
                if (!tmp.renameTo(dest)) throw IOException("couldn't save $path")
            } finally {
                tmp.delete()
            }
        }
    }

    /**
     * Lo que ya es igual en las dos queda como base, aqui y alla: [expected] es como quedo cada
     * archivo en las dos (lo comparado, lo mandado, lo traido). Con [recheck] (paso tiempo mandando o
     * trayendo) se mira que aqui siga igual: uno que el emulador guardo a mitad no entra, y la proxima
     * pasada lo ve cambiado aqui en vez de traer encima la version de alla.
     */
    private fun agree(ctx: Context, p: Peer, mine: EmuSaves, expected: Map<String, JSONObject>, recheck: Boolean) {
        val now = if (recheck) byPath(Saves.manifest(mine)) else null
        val files = JSONArray()
        for ((path, want) in expected) {
            if (now != null && !same(now[path], want)) continue
            files.put(JSONObject().put("path", path).put("size", want.optLong("size")).put("sha1", want.optString("sha1")))
        }
        Saves.setBase(p.id, mine.pkg, files)
        Saves.dropConflicts(ctx, p.id, mine.pkg, (0 until files.length()).map { files.getJSONObject(it).getString("path") }.toSet())
        val c = Peers.open(p.host, p.port, "POST", "/saves/end", mapOf("pkg" to mine.pkg), p.token)
        val body = JSONObject().put("files", files).toString().toByteArray(Charsets.UTF_8)
        c.doOutput = true
        c.setFixedLengthStreamingMode(body.size)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body) }
        Peers.json(c)
    }

    // ----------------------------------------------------------- conflictos

    /**
     * La persona eligio: [keepHere] la version de esta consola, si no la de [conflict.peerName].
     * Se respalda lo que se va a pisar, y queda como base en las dos.
     */
    fun resolve(ctx: Context, conflict: SaveConflict, keepHere: Boolean, done: (String?) -> Unit) =
        resolveMany(ctx, listOf(conflict), keepHere, done)

    /** Lo mismo para varios juegos de una vez (mismo emulador y misma consola). */
    fun resolveMany(ctx: Context, list: List<SaveConflict>, keepHere: Boolean, done: (String?) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "save-resolve", isDaemon = true) {
            // Nunca a la vez que una pasada: escribirian los mismos archivos.
            val until = System.currentTimeMillis() + 60_000
            while (!busy.compareAndSet(false, true)) {
                if (System.currentTimeMillis() > until) { LinkState.post { done("a sync is running: try again in a moment") }; return@thread }
                Thread.sleep(300)
            }
            val err = try { runCatching {
                val first = list.firstOrNull() ?: return@runCatching
                val peer = Peers.all(app).firstOrNull { it.id == first.peerId } ?: throw IOException("that device isn't paired any more")
                val p = Peers.reach(app, peer) ?: throw IOException("${peer.name} isn't reachable")
                val mine = Saves.get(app, first.pkg)?.takeIf { it.configured } ?: throw IOException("no folder here")
                val st = state(p, first.pkg)
                val remote = byPath(remoteManifest(p, first.pkg))
                val local = byPath(Saves.manifest(mine))
                val files = list.flatMap { it.files }.distinct()
                val expected: Map<String, JSONObject>
                if (keepHere) {
                    if (st.optBoolean("inUse")) throw IOException("a game is open in that emulator on ${p.name}")
                    val sent = files.filter { local[it] != null }
                    send(p, mine, sent)
                    expected = sent.associateWith { local.getValue(it) }
                } else {
                    if (Saves.inUse(app, first.pkg)) throw IOException("a game is open in that emulator here")
                    val got = files.filter { remote[it] != null }
                    fetch(app, p, mine, got, remote)
                    expected = got.associateWith { remote.getValue(it) }
                }
                agree(app, p, mine, expected, recheck = true)
                list.forEach { Saves.dropConflict(app, it) }
                LinkState.addLog("Saves of ${Saves.appName(app, first.pkg)}: kept ${if (keepHere) "this device's" else "${p.name}'s"} " +
                    list.joinToString { it.title }, "saves")
            }.exceptionOrNull()?.message } finally { busy.set(false) }
            notifyConflicts(app)
            LinkState.post { done(err) }
        }
    }

    // Canal propio de importancia alta: aparece encima de Ludolog o del juego. (El viejo "saves"
    // era de importancia normal y Android no deja subirla a un canal ya creado.)
    private const val CHANNEL = "save-conflicts"
    private const val NOTIF_ID = 3
    /** Los conflictos ya avisados: no se repite el aviso por los mismos tras cada sincronizacion. */
    @Volatile private var noticed = ""

    /** Quitar el aviso: la pantalla de Link esta a la vista y muestra su ventana. */
    fun clearNotice(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /**
     * Si hay que elegir algo: con Link a la vista, su ventana sale sola (ConflictsPopup); si no,
     * una notificacion que lleva a esa ventana.
     */
    private fun notifyConflicts(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val list = Saves.conflicts(ctx)
        val key = list.joinToString("|") { it.key }
        if (list.isEmpty()) { noticed = ""; nm.cancel(NOTIF_ID); return }
        if (LinkState.uiVisible || key == noticed) return
        noticed = key
        nm.deleteNotificationChannel("saves")
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Save conflicts", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("tab", "saves")
            .putExtra("conflicts", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val games = list.take(6).joinToString(", ") { it.title } + if (list.size > 6) "…" else ""
        runCatching {
            nm.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(if (list.size == 1) "Choose which save to keep" else "${list.size} saves to choose")
                .setContentText("Played on two devices since the last sync: $games")
                .setStyle(Notification.BigTextStyle().bigText("Played on two devices since the last sync: $games. Open Ludolog Link to choose."))
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
