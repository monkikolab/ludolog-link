package com.felp.ludologlink

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.felp.ludolog.kit.Protocol
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Traer ROMs de otro device emparejado, sin el PC: de a uno, con su caratula y su video. La otra
 * Link los sirve igual que al PC (`/roms`, `/roms/<consola>/<archivo>` con Range, `/ludolog/media/list` y `/ludolog/media/file`).
 * Ver RomsScreen.
 */
object RomTransfer {

    /** Un ROM de la otra: consola (su carpeta) y archivo (con subcarpeta si es multidisco). */
    class RemoteGame(val system: String, val name: String, val size: Long) {
        val key get() = "$system/$name"
        val stem get() = Protocol.stemOf(name.substringAfterLast('/'))
    }

    /** Lo que tiene la otra: sus ROMs, el nombre de cada consola y que juegos tienen arte y video. */
    class Library(val games: List<RemoteGame>, val consoles: Map<String, String>, val art: Set<String>, val video: Set<String>) {
        // Ludolog guarda el arte por SU nombre de consola (gamecube), que no siempre es el de la carpeta
        // de ROMs (gc): se mira por el nombre del juego.
        private val artNames = art.mapTo(HashSet()) { it.substringAfter('/') }
        private val videoNames = video.mapTo(HashSet()) { it.substringAfter('/') }

        /** Si tiene caratula (o video) alla, donde lo busca Ludolog. */
        fun hasArt(g: RemoteGame) = g.stem.lowercase() in artNames
        fun hasVideo(g: RemoteGame) = g.stem.lowercase() in videoNames
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun romPath(system: String, name: String) = "/roms/" + (listOf(system) + name.split('/')).joinToString("/") { enc(it) }

    /** La biblioteca de [p]. Lanza si no contesta o no tiene carpeta de ROMs. */
    fun library(ctx: Context, p: Peer): Library {
        if (!Prefs.syncDevices(ctx)) throw IOException("Sharing is off")
        val peer = Peers.reach(ctx, p) ?: throw IOException("${p.name} isn't reachable")
        fun get(path: String) = Peers.json(Peers.open(peer.host, peer.port, "GET", path, token = peer.token, readTimeout = 60_000))
        val roms = get("/roms").getJSONObject("systems")
        val games = ArrayList<RemoteGame>()
        for (sys in roms.keys()) {
            val arr = roms.getJSONArray(sys)
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { games += RemoteGame(sys, it.getString("name"), it.optLong("size")) }
        }
        val consoles = runCatching {
            val a = get("/systems").getJSONArray("systems")
            (0 until a.length()).associate { a.getJSONObject(it).let { s -> s.getString("folder") to s.optString("name") } }
        }.getOrDefault(emptyMap())
        // Sin Ludolog alla, sin arte: no es un error.
        val (art, video) = runCatching {
            val j = get("/ludolog/art")
            fun set(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } } ?: emptySet()
            set("art") to set("video")
        }.getOrDefault(emptySet<String>() to emptySet())
        return Library(games.filterNot(::isShortcut), consoles, art, video)
    }

    /**
     * Lo que no se trae: accesos directos a apps y emuladores, entradas de tiendas (Steam, DoomForge)
     * y los acompañantes (.txt, .sbi). Como la lista de ROMs del PC (Shortcuts).
     */
    private val SHORTCUT_EXT = setOf("steam", "epic", "gog", "amazon", "pcgame", "gamehub", "desktop", "shortcut", "app",
        "doomforge", "txt", "sbi")
    private val SHORTCUT_DIRS = setOf("emulators", "androidapps", "androidgames", "android")
    private fun isShortcut(g: RemoteGame) = g.system.lowercase().let { it in SHORTCUT_DIRS || it.startsWith("applauncher") } ||
        g.name.substringAfterLast('.', "").lowercase() in SHORTCUT_EXT

    // ---------------------------------------------------------------- la cola

    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    class Job(val peer: Peer, val game: RemoteGame) {
        /** Para la pantalla: se pone desde el hilo principal, un poco despues. */
        val state = mutableStateOf(State.QUEUED)
        val progress = mutableFloatStateOf(0f)
        val note = mutableStateOf<String?>(null)
        @Volatile var cancel = false
        /** El de verdad, para la cola: se cambia con el candado de RomTransfer, al momento. */
        @Volatile var now = State.QUEUED
    }

    val jobs = mutableStateListOf<Job>()

    /** Cuantas van ahora: el servicio no se duerme mientras haya alguna (ver LinkService). */
    @Volatile var active = 0; private set

    fun get(ctx: Context, p: Peer, g: RemoteGame) {
        if (!Prefs.syncDevices(ctx)) return
        synchronized(this) {
            if (jobs.any { it.game.key == g.key && it.now in setOf(State.QUEUED, State.RUNNING) }) return
            jobs += Job(p, g)
        }
        kick(ctx.applicationContext)
    }

    /** Uno en cola se quita ya; uno en marcha se para en el proximo trozo. */
    fun cancel(j: Job) {
        val queued = synchronized(this) { (j.now == State.QUEUED).also { if (it) j.now = State.CANCELLED else j.cancel = true } }
        if (queued) j.state.value = State.CANCELLED
    }

    fun clearFinished() = synchronized(this) { jobs.removeAll { it.now != State.QUEUED && it.now != State.RUNNING } }

    private var working = false

    /**
     * Un solo hilo trae de a uno. Tomar el siguiente y, si no hay, dejar de trabajar va con el mismo
     * candado que [kick]: si no, uno pedido justo entre las dos cosas se quedaba en cola para siempre.
     */
    @Synchronized private fun kick(ctx: Context) {
        if (working) return
        working = true
        thread(name = "rom-transfer", isDaemon = true) {
            // Con la pantalla apagada tambien: CPU y Wi-Fi despiertos mientras haya cola.
            val wake = ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ludologlink:pull")
            @Suppress("DEPRECATION")
            val wifi = ctx.getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ludologlink:pull")
            // La lista de medios de cada device, una vez por tanda (antes, una por juego).
            val media = HashMap<String, org.json.JSONArray>()
            var stopped = false
            try {
                wake.acquire(6 * 3_600_000L); wifi.acquire()
                while (true) {
                    val j = synchronized(this) {
                        jobs.firstOrNull { it.now == State.QUEUED }?.also { it.now = State.RUNNING }
                            ?: run { working = false; stopped = true; null }
                    } ?: break
                    active++
                    try { run(ctx, j, media) } finally { active-- }
                }
            } finally {
                runCatching { if (wifi.isHeld) wifi.release() }
                runCatching { if (wake.isHeld) wake.release() }
                // Si salio por un error y no por quedarse sin cola, se suelta aqui.
                if (!stopped) synchronized(this) { working = false }
            }
        }
    }

    private class Failed(msg: String) : Exception(msg)

    private fun set(j: Job, s: State, note: String? = null) {
        j.now = s
        LinkState.post { j.state.value = s; j.note.value = note }
    }

    private fun run(ctx: Context, j: Job, media: HashMap<String, org.json.JSONArray>) {
        val g = j.game
        set(j, State.RUNNING)
        try {
            val root = RomStore.root(ctx) ?: throw Failed("This device has no ROM folder")
            val dest = RomStore.resolve(root, g.system, g.name) ?: throw Failed("Invalid name")
            if (dest.exists()) throw Failed("Already here")
            dest.parentFile?.mkdirs()
            // Lo que ocupa, con margen: nunca llenar la tarjeta del todo.
            val free = (dest.parentFile ?: root).usableSpace
            if (free < g.size + (64L shl 20)) throw Failed("Not enough space: ${size(free)} free, needs ${size(g.size)}")
            val tmp = File(dest.parentFile, "." + dest.name + Protocol.PART_SUFFIX)
            var mtime = 0L
            var tries = 0
            var reached: Peer
            while (true) {
                if (j.cancel) throw Failed("Cancelled")
                reached = Peers.reach(ctx, j.peer) ?: throw Failed("${j.peer.name} isn't reachable")
                try {
                    mtime = pull(reached, g, tmp, j)
                    break
                } catch (x: IOException) {
                    // Se corto: se sigue desde donde quedo (la otra Link admite Range), hasta tres veces.
                    if (j.cancel || ++tries >= 3) throw Failed(x.message ?: "Connection lost")
                    Thread.sleep(2_000)
                }
            }
            if (!tmp.renameTo(dest)) throw Failed("Couldn't save the file")
            if (mtime > 0) dest.setLastModified(mtime)
            LinkState.addLog("Got ${g.name} from ${j.peer.name}", "roms")
            Ludolog.libraryChanged(ctx)
            // Su caratula y su video, si los tiene alla: lo que falle aqui no deshace el ROM.
            val got = runCatching { pullMedia(ctx, j, reached, media) }.getOrDefault(0)
            set(j, State.DONE, if (got > 0) "with art" else null)
        } catch (x: Failed) {
            if (j.cancel) {
                RomStore.root(ctx)?.let { RomStore.resolve(it, g.system, g.name) }?.let { File(it.parentFile, "." + it.name + Protocol.PART_SUFFIX).delete() }
                set(j, State.CANCELLED)
            } else set(j, State.FAILED, x.message)
        } catch (x: Exception) {
            set(j, State.FAILED, x.message ?: x.javaClass.simpleName)
        }
    }

    /** El ROM a [tmp], siguiendo lo que ya haya en el (Range). Devuelve su fecha original. */
    private fun pull(peer: Peer, g: RemoteGame, tmp: File, j: Job): Long {
        val start = if (tmp.isFile) tmp.length() else 0L
        val c = Peers.open(peer.host, peer.port, "GET", romPath(g.system, g.name), token = peer.token, readTimeout = 60_000)
        if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
        try {
            val code = c.responseCode
            if (code != 200 && code != 206) Peers.json(c)
            val append = code == 206
            val total = g.size.coerceAtLeast(1)
            var done = if (append) start else 0L
            c.inputStream.use { inp ->
                java.io.FileOutputStream(tmp, append).use { o ->
                    val buf = ByteArray(1 shl 16)
                    var last = 0L
                    while (true) {
                        if (j.cancel) throw Failed("Cancelled")
                        val k = inp.read(buf); if (k < 0) break
                        o.write(buf, 0, k); done += k
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; val f = (done.toFloat() / total).coerceAtMost(1f); LinkState.post { j.progress.floatValue = f } }
                    }
                    o.fd.sync()
                }
            }
            if (done < g.size) throw IOException("cut at ${size(done)} of ${size(g.size)}")
            LinkState.post { j.progress.floatValue = 1f }
            return c.getHeaderField("X-Mtime")?.toLongOrNull() ?: 0L
        } finally {
            c.disconnect()
        }
    }

    /** Un nombre para comparar: solo letras y numeros, en minusculas. Lo usa tambien la pestaña ROMs. */
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * La caratula y el video del juego alla, a la carpeta de medios de Ludolog aqui
     * (`media/<consola>/<tipo>/<nombre>`). Se buscan por el nombre del archivo; si el nombre esta en
     * varias consolas, en la de su carpeta. Devuelve cuantos trajo.
     */
    private fun pullMedia(ctx: Context, j: Job, peer: Peer, cache: HashMap<String, org.json.JSONArray>): Int {
        val list = cache[peer.id] ?: (Peers.json(Peers.open(peer.host, peer.port, "GET", "/ludolog/media/list", token = peer.token, readTimeout = 60_000))
            .optJSONArray("files") ?: return 0).also { cache[peer.id] = it }
        val want = norm(j.game.stem)
        val found = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("owner") != "theme" && norm(it.getString("name").substringBeforeLast('.')) == want }
        if (found.isEmpty()) return 0
        val systems = found.map { it.getString("sys") }.distinct()
        val sys = if (systems.size == 1) systems.single() else systems.firstOrNull { it.equals(j.game.system, true) } ?: return 0
        val mine = found.filter { it.getString("sys") == sys }
        val pick = mine.filter { it.optString("kind").equals("videos", true) && it.getString("name").endsWith(".mp4", true) }.take(1) +
            mine.filterNot { it.optString("kind").equals("videos", true) }.groupBy { it.optString("kind").ifEmpty { "covers" }.lowercase() }
                .map { it.value.first() }
        val got = ArrayList<File>()
        for (m in pick) {
            val kind = m.optString("kind").ifEmpty { "covers" }
            val ext = m.getString("name").substringAfterLast('.').lowercase()
            val dest = Ludolog.mediaTarget(ctx, "media/$sys/$kind/${j.game.stem}.$ext") ?: continue
            if (dest.exists()) continue
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "." + dest.name + Protocol.PART_SUFFIX)
            val c = Peers.open(peer.host, peer.port, "GET", "/ludolog/media/file", mapOf("path" to m.getString("path")), peer.token, 60_000)
            try {
                if (c.responseCode != 200) continue
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.renameTo(dest)) got += dest
            } catch (_: IOException) {
                tmp.delete()
            } finally {
                c.disconnect()
            }
        }
        if (got.isNotEmpty()) Ludolog.mediaChanged(ctx, got)
        return got.size
    }

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.US, b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.US, b / (1L shl 20).toDouble())
        else -> "${b / 1024} KB"
    }
}
