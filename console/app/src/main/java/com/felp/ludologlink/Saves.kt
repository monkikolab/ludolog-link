package com.felp.ludologlink

import android.content.Context
import android.os.Environment
import com.felp.ludolog.kit.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Las partidas guardadas de cada emulador —por app: RetroArch es uno con todos sus nucleos—, que
 * Link sincroniza con las consolas emparejadas (ver SaveSync) y respalda.
 *
 * Cada emulador necesita su carpeta, puesta a mano (el explorador de Link llega tambien a
 * `Android/data`). Sin carpeta, ese emulador no se toca.
 *
 * Para decidir NO se usan las fechas de los archivos: que cambio lo dice su contenido (una huella
 * SHA-1), y quien manda lo dice cuando se jugo cada juego en cada consola: lo que apunta Ludolog
 * (`<datos>/link/played.tsv`, ver LinkBridge.gameClosed) y el cuaderno del Companion (sesiones con
 * emulador y archivo), que tiene la historia de antes.
 *
 * Todo lo de Link (ajustes de partidas, bases, conflictos, respaldos) vive fuera de la app, en
 * `<memoria interna>/LudologLink/`: se ve, y sobrevive a reinstalar.
 */
data class EmuSaves(
    val pkg: String,
    /** La carpeta de las partidas en ESTA consola. Vacia: no se sincroniza ni se respalda. */
    val path: String = "",
    /** Cada cuanto se respalda solo: off, hourly, daily, weekly. */
    val every: String = "daily",
    /** Cuantos respaldos se guardan. */
    val keep: Int = 7,
    /** Cuando se cerro por ultima vez un juego suyo aqui. */
    val played: Long = 0,
    val lastBackup: Long = 0,
    val lastSync: Long = 0,
    val note: String = "",
) {
    val configured get() = path.isNotEmpty()
    val folder get() = File(path)

    fun json(): JSONObject = JSONObject().put("pkg", pkg).put("path", path).put("every", every).put("keep", keep)
        .put("played", played).put("lastBackup", lastBackup).put("lastSync", lastSync).put("note", note)

    companion object {
        fun of(j: JSONObject) = EmuSaves(j.getString("pkg"), j.optString("path"), j.optString("every", "daily"),
            j.optInt("keep", 7), j.optLong("played"), j.optLong("lastBackup"), j.optLong("lastSync"), j.optString("note"))

        val PERIODS = linkedMapOf("off" to 0L, "hourly" to 3_600_000L, "daily" to 86_400_000L, "weekly" to 7 * 86_400_000L)
    }
}

/**
 * Un juego cuyas partidas no se pueden decidir solas: cambiaron en las dos consolas y se jugo en
 * las dos (o en ninguna consta). [mine]/[theirs]: tamaño de cada archivo de ese lado; [minePlayed]/
 * [theirsPlayed]: cuando se jugo ese juego por ultima vez en cada consola (0 si no consta).
 */
data class SaveConflict(
    val peerId: String,
    val peerName: String,
    val pkg: String,
    val game: String,
    val files: List<String>,
    val mine: Map<String, Long>,
    val theirs: Map<String, Long>,
    val minePlayed: Long,
    val theirsPlayed: Long,
    val found: Long,
) {
    val key get() = "$peerId|$pkg|$game"

    /** El juego como se ve: el nombre de su primer archivo, sin extensiones de partida ni numero de tarjeta. */
    val title: String get() = files.firstOrNull()?.let(Saves::saveTitle) ?: game
}

object Saves {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)

    // ----------------------------------------------------- carpeta de Link

    /** `<memoria interna>/LudologLink`: lo de Link, a la vista y fuera de la app. */
    fun home(): File = File(Environment.getExternalStorageDirectory(), "LudologLink")

    private fun state(name: String) = File(home(), "state/$name")

    private fun readJson(f: File): Any? = runCatching {
        val t = f.readText().trim()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }.getOrNull()

    /** Escrito entero o nada: por un temporal que se renombra. */
    private fun writeJson(f: File, v: Any) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "." + f.name + PART)
        tmp.writeText(v.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ---------------------------------------------------------------- ajustes

    @Synchronized
    fun all(@Suppress("UNUSED_PARAMETER") ctx: Context): List<EmuSaves> {
        val a = readJson(state("saves.json")) as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { runCatching { EmuSaves.of(a.getJSONObject(it)) }.getOrNull() }
    }

    fun get(ctx: Context, pkg: String) = all(ctx).firstOrNull { it.pkg == pkg }

    @Synchronized
    fun save(ctx: Context, e: EmuSaves) {
        val list = all(ctx).filter { it.pkg != e.pkg } + e
        writeJson(state("saves.json"), JSONArray(list.map { it.json() }))
        LinkState.post { LinkState.savesChanged.intValue++ }
    }

    @Synchronized
    fun update(ctx: Context, pkg: String, f: (EmuSaves) -> EmuSaves) = save(ctx, f(get(ctx, pkg) ?: EmuSaves(pkg)))

    // ------------------------------------------------------- base y conflictos

    private fun baseFile(peerId: String, pkg: String) = state("base/$peerId/$pkg.json")

    /**
     * Como quedo cada archivo (tamaño y huella) la ultima vez que esta consola y [peerId] lo
     * tuvieron igual, y cuando fue eso en el reloj de ESTA consola.
     */
    @Synchronized
    fun base(peerId: String, pkg: String): Pair<Long, Map<String, JSONObject>> {
        val o = readJson(baseFile(peerId, pkg)) as? JSONObject ?: return 0L to emptyMap()
        val files = o.optJSONObject("files") ?: JSONObject()
        return o.optLong("syncedAt") to files.keys().asSequence().associateWith { k -> files.getJSONObject(k).put("path", k) }
    }

    @Synchronized
    fun setBase(peerId: String, pkg: String, files: JSONArray) {
        val o = readJson(baseFile(peerId, pkg)) as? JSONObject ?: JSONObject()
        val map = o.optJSONObject("files") ?: JSONObject()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            map.put(f.getString("path"), JSONObject().put("size", f.optLong("size")).put("sha1", f.optString("sha1")))
        }
        writeJson(baseFile(peerId, pkg), JSONObject().put("syncedAt", System.currentTimeMillis()).put("files", map))
    }

    @Synchronized
    fun conflicts(@Suppress("UNUSED_PARAMETER") ctx: Context): List<SaveConflict> {
        val a = readJson(state("conflicts.json")) as? JSONArray ?: return emptyList()
        fun sizes(j: JSONObject?) = j?.keys()?.asSequence()?.associateWith { k -> j.getLong(k) }.orEmpty()
        return (0 until a.length()).mapNotNull { i ->
            runCatching {
                val j = a.getJSONObject(i)
                SaveConflict(j.getString("peer"), j.optString("peerName"), j.getString("pkg"), j.getString("game"),
                    j.getJSONArray("files").let { f -> (0 until f.length()).map { f.getString(it) } },
                    sizes(j.optJSONObject("mine")), sizes(j.optJSONObject("theirs")),
                    j.optLong("minePlayed"), j.optLong("theirsPlayed"), j.optLong("found"))
            }.getOrNull()
        }
    }

    private fun writeConflicts(list: List<SaveConflict>) {
        val a = JSONArray(list.map { c ->
            JSONObject().put("peer", c.peerId).put("peerName", c.peerName).put("pkg", c.pkg).put("game", c.game)
                .put("files", JSONArray(c.files)).put("mine", JSONObject(c.mine)).put("theirs", JSONObject(c.theirs))
                .put("minePlayed", c.minePlayed).put("theirsPlayed", c.theirsPlayed).put("found", c.found)
        })
        writeJson(state("conflicts.json"), a)
        LinkState.post { LinkState.savesChanged.intValue++ }
    }

    /** Lo que quedo por elegir con [peer] en ese emulador, segun la ultima pasada: reemplaza lo anterior. */
    @Synchronized
    fun setConflicts(ctx: Context, peer: Peer, pkg: String, list: List<SaveConflict>) =
        writeConflicts(conflicts(ctx).filterNot { it.peerId == peer.id && it.pkg == pkg } + list)

    @Synchronized
    fun dropConflict(ctx: Context, c: SaveConflict) = writeConflicts(conflicts(ctx).filterNot { it.key == c.key })

    /** Una consola que se olvido: fuera sus conflictos y sus bases. */
    @Synchronized
    fun forgetPeer(ctx: Context, peerId: String) {
        writeConflicts(conflicts(ctx).filterNot { it.peerId == peerId })
        if (peerId.isNotEmpty() && '/' !in peerId && !peerId.startsWith(".")) state("base/$peerId").deleteRecursively()
    }

    @Synchronized
    fun dropConflicts(ctx: Context, peerId: String, pkg: String, paths: Set<String>) =
        writeConflicts(conflicts(ctx).filterNot { it.peerId == peerId && it.pkg == pkg && it.files.all { f -> f in paths } })

    // ----------------------------------------------------------- lo jugado

    /** Una linea de played.tsv: S (se abrio) o E (se cerro). */
    data class Played(val kind: Char, val at: Long, val pkg: String, val system: String, val file: String)

    /** played.tsv leido: se vuelve a leer solo si cambio (tamaño o fecha). Se consulta mucho por pasada. */
    private class PlayedCache(val path: String, val size: Long, val mtime: Long, val rows: List<Played>)
    @Volatile private var playedCache: PlayedCache? = null

    private fun playedLog(ctx: Context): List<Played> {
        val dir = Ludolog.dataDir(ctx) ?: return emptyList()
        val f = File(dir, "link/played.tsv")
        if (!f.isFile) return emptyList()
        val size = f.length()
        val mtime = f.lastModified()
        playedCache?.let { c -> if (c.path == f.path && c.size == size && c.mtime == mtime) return c.rows }
        val rows = runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 5) null else Played(p[0].firstOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null,
                p[2], p[3], p[4])
        }
        playedCache = PlayedCache(f.path, size, mtime, rows)
        return rows
    }

    /** El juego de un ROM: su nombre sin extension, en minusculas. */
    fun romKey(file: String) = file.substringAfterLast('/').substringBeforeLast('.').lowercase().trim()

    private val SAVE_EXT = Regex("""^(srm|sav|sram|state\d*|auto|png|mcd|mcr|ps2|dsv|eep|fla|rtc|mpk|bin|dat|gci|raw)$""", RegexOption.IGNORE_CASE)

    /**
     * El juego de un archivo de partida: el nombre sin sus extensiones de partida ni el numero de
     * tarjeta. `states/Castlevania.state1` y `Castlevania.srm` -> `castlevania`;
     * `Final Fantasy VII (USA) (Disc 1)_1.mcd` -> `final fantasy vii (usa) (disc 1)`.
     */
    fun saveKey(path: String): String = saveTitle(path).lowercase().trim()

    private val CARD_NUMBER = Regex("""_\d+$""")

    /** El juego de un archivo de partida tal como se escribe (sin pasar a minusculas). Ver [saveKey]. */
    fun saveTitle(path: String): String {
        var n = path.substringAfterLast('/')
        while ('.' in n && SAVE_EXT.matches(n.substringAfterLast('.'))) n = n.substringBeforeLast('.')
        return n.replace(CARD_NUMBER, "")
    }

    /**
     * Cuando se cerro por ultima vez cada juego de ese emulador en esta consola: lo que apunto
     * Ludolog y, de antes, el cuaderno del Companion (sus sesiones guardan emulador y archivo).
     */
    fun gamePlays(ctx: Context, pkg: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun note(file: String, at: Long) { val k = romKey(file); if (k.isNotEmpty() && at > (out[k] ?: 0L)) out[k] = at }
        playedLog(ctx).filter { it.kind == 'E' && it.pkg == pkg }.forEach { note(it.file, it.at) }
        // El cuaderno, leido directo (antes se copiaba entero en cada consulta).
        Ludolog.dataDir(ctx)?.let { dir ->
            Ludolog.ownLogbook(dir)?.let { own -> Ludolog.sessionPlays(File(dir, "companion/$own"), pkg).forEach { (t, at) -> note(t, at) } }
        }
        return out
    }

    /** Cuando se jugo el juego de ese archivo de partida, segun [plays] (ver [gamePlays]). 0 si no consta. */
    fun playedOf(savePath: String, plays: Map<String, Long>): Long = playedOfKey(saveKey(savePath), plays)

    /** Lo mismo con el juego ya en clave: exacto, o por prefijo si el nombre lleva algo mas (o menos). */
    fun playedOfKey(k: String, plays: Map<String, Long>): Long {
        plays[k]?.let { return it }
        return plays.filterKeys { r -> r.length >= 4 && (k.startsWith(r) || r.startsWith(k)) }.values.maxOrNull() ?: 0L
    }

    /** Pone al dia "cuando se jugo por ultima vez" de cada emulador. Devuelve los que tienen algo nuevo. */
    fun catchUp(ctx: Context): Set<String> {
        val last = playedLog(ctx).filter { it.kind == 'E' }.groupBy { it.pkg }.mapValues { (_, l) -> l.maxOf { it.at } }
        val changed = HashSet<String>()
        for ((pkg, at) in last) {
            val cur = get(ctx, pkg)
            if (cur == null || at > cur.played) { update(ctx, pkg) { it.copy(played = maxOf(it.played, at)) }; changed += pkg }
        }
        return changed
    }

    /** Un cierre que llego en vivo (GAME_CLOSED). */
    fun played(ctx: Context, pkg: String, at: Long) = update(ctx, pkg) { it.copy(played = maxOf(it.played, at)) }

    /** Si ese emulador tiene un juego abierto ahora (un S sin su E, de hace menos de 12 h). */
    fun inUse(ctx: Context, pkg: String): Boolean {
        val mine = playedLog(ctx).filter { it.pkg == pkg }
        val lastOpen = mine.lastOrNull { it.kind == 'S' } ?: return false
        val closedAfter = mine.any { it.kind == 'E' && it.at >= lastOpen.at }
        return !closedAfter && System.currentTimeMillis() - lastOpen.at < 12 * 3_600_000L
    }

    /** Los emuladores que se ven en lo apuntado por Ludolog, para ofrecerlos aunque no esten puestos. */
    fun seen(ctx: Context): Set<String> = playedLog(ctx).map { it.pkg }.toSet()

    // -------------------------------------------------------- los archivos

    /**
     * Los archivos de la carpeta: ruta relativa, tamaño y huella SHA-1 del contenido. La huella se
     * guarda por (tamaño, fecha local) para no leer otra vez lo que no cambio: la fecha solo sirve
     * de atajo aqui, nunca para decidir.
     */
    /**
     * Un candado por emulador para lo largo (huellas, respaldos). Con el de todo Saves, una huella de
     * una carpeta grande dejaba esperando a la pantalla y al aviso de "juego cerrado".
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private fun lockFor(pkg: String) = locks.getOrPut(pkg) { Any() }

    fun manifest(e: EmuSaves): JSONArray = synchronized(lockFor(e.pkg)) { manifestLocked(e) }

    private fun manifestLocked(e: EmuSaves): JSONArray {
        val out = JSONArray()
        val root = e.folder
        if (!root.isDirectory) return out
        val cacheFile = state("hash/${e.pkg}.json")
        val cache = readJson(cacheFile) as? JSONObject ?: JSONObject()
        val fresh = JSONObject()
        var changed = false
        val base = root.absolutePath.length + 1
        root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }.filter { it.isFile }.forEach { f ->
            if (f.name.startsWith(".") || f.name.endsWith(PART)) return@forEach
            val rel = f.absolutePath.substring(base).replace(File.separatorChar, '/')
            val c = cache.optJSONObject(rel)
            val hit = c != null && c.optLong("size") == f.length() && c.optLong("mtime") == f.lastModified()
            if (!hit) changed = true
            val sha = if (hit) c!!.getString("sha1") else sha1(f)
            // Uno que esta app no puede leer (su emulador lo creo privado): esta, pero no se sabe que
            // tiene. Se lista como tal para que nadie lo pise ni lo de por ausente. Ver SaveSync.plan.
            if (sha == null) {
                out.put(JSONObject().put("path", rel).put("size", f.length()).put("unreadable", true))
                return@forEach
            }
            fresh.put(rel, JSONObject().put("size", f.length()).put("mtime", f.lastModified()).put("sha1", sha))
            out.put(JSONObject().put("path", rel).put("size", f.length()).put("sha1", sha))
        }
        // Solo si cambio algo: casi siempre es la misma, y se pedia en cada pasada.
        if (changed || fresh.length() != cache.length()) runCatching { writeJson(cacheFile, fresh) }
        return out
    }

    /**
     * Si Link puede sincronizar ese emulador en esta consola: leer todos sus archivos y escribir en
     * todas sus carpetas. No se puede cuando el emulador los deja privados (DuckStation en Android 15)
     * o sus carpetas sin escritura para otras apps (ARMSX3). Ver docs/limitations.md.
     */
    fun supported(e: EmuSaves): Boolean {
        val root = e.folder
        if (!root.isDirectory) return true
        return root.walkTopDown().onEnter { !it.name.startsWith(".") || it == root }
            .filter { it == root || !it.name.startsWith(".") }
            .all { if (it.isDirectory) it.canWrite() else it.name.endsWith(PART) || it.canRead() }
    }

    fun sha1(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Un archivo dentro de la carpeta del emulador, con ruta segura. Nulo si se sale. */
    fun fileIn(e: EmuSaves, rel: String): File? {
        if (!e.configured || rel.isEmpty() || rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val f = File(e.folder, rel)
        return f.takeIf { it.canonicalPath.startsWith(e.folder.canonicalPath + File.separator) }
    }

    const val PART = Protocol.PART_SUFFIX

    // ---------------------------------------------------------- respaldos

    /** Donde van los respaldos: en la carpeta de Link, a la vista. */
    fun backupRoot(@Suppress("UNUSED_PARAMETER") ctx: Context): File = File(home(), "save-backups")

    fun backupsOf(ctx: Context, pkg: String): List<File> =
        File(backupRoot(ctx), pkg).listFiles { f -> f.isFile && f.name.endsWith(".bak") }.orEmpty().sortedByDescending { it.name }

    /**
     * La carpeta entera en un `.bak` (un zip): `<fecha>-<motivo>.bak`. Se guardan los [EmuSaves.keep]
     * mas nuevos, nunca [protect] (el que se esta restaurando). Nulo si no hay nada que guardar.
     *
     * Si no se puede hacer (sin sitio, un error al escribir) LANZA: quien va a escribir encima tiene
     * que enterarse y no escribir. Antes devolvia nulo y se sobrescribia igual, sin respaldo.
     */
    fun backup(ctx: Context, e: EmuSaves, why: String, protect: File? = null): File? = synchronized(lockFor(e.pkg)) {
        val root = e.folder
        if (!e.configured || !root.isDirectory) return null
        // Solo lo que se puede leer: uno privado de su emulador no se puede copiar (y no tumba el respaldo).
        val files = root.walkTopDown().filter { it.isFile && !it.name.endsWith(PART) && it.canRead() }.toList()
        if (files.isEmpty()) return null
        val dir = File(backupRoot(ctx), e.pkg).apply { mkdirs() }
        // Nunca dejar la tarjeta sin sitio: lo que ocupa y [MIN_FREE] de margen.
        val needed = files.sumOf { it.length() }
        if (dir.usableSpace - needed < MIN_FREE) {
            LinkState.addLog("Saves of ${appName(ctx, e.pkg)} not backed up: no space", "saves", error = true)
            throw IOException("not enough free space for a backup")
        }
        val name = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + "-$why.bak"
        val tmp = File(dir, ".$name$PART")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                val base = root.absolutePath.length + 1
                for (f in files) {
                    zip.putNextEntry(ZipEntry(f.absolutePath.substring(base).replace(File.separatorChar, '/')).apply { time = f.lastModified() })
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (x: Exception) {
            tmp.delete()
            throw IOException("couldn't write the backup: ${x.message}")
        }
        val out = File(dir, name)
        if (!tmp.renameTo(out)) { tmp.delete(); throw IOException("couldn't save the backup") }
        backupsOf(ctx, e.pkg).filter { it != protect }.drop(e.keep.coerceIn(1, MAX_KEEP)).forEach { it.delete() }
        enforceCap(ctx, protect)
        update(ctx, e.pkg) { it.copy(lastBackup = System.currentTimeMillis()) }
        LinkState.addLog("Saves of ${appName(ctx, e.pkg)} backed up ($why)", "saves")
        return out
    }

    /** Lo que se deja libre, ademas de lo que ocupa el respaldo: con menos, no se respalda. */
    const val MIN_FREE = 64L shl 20

    /** Lo mas que se guarda de cada emulador. */
    const val MAX_KEEP = 30

    /** Las opciones del tope total de los respaldos, en MB. */
    val CAPS = listOf(500L, 1024L, 2048L, 5120L, 10240L)

    /** Lo mas que ocupan entre todos los respaldos de partidas, en MB. 2 GB por defecto. */
    fun capMb(ctx: Context) = prefs(ctx).getLong("saves_cap_mb", 2048L)
    fun setCapMb(ctx: Context, mb: Long) {
        prefs(ctx).edit().putLong("saves_cap_mb", mb).apply()
        runCatching { enforceCap(ctx) }
        LinkState.post { LinkState.savesChanged.intValue++ }
    }

    /** Lo que ocupan ahora todos los respaldos de partidas. */
    fun usedBytes(ctx: Context): Long =
        backupRoot(ctx).walkTopDown().filter { it.isFile && it.name.endsWith(".bak") }.sumOf { it.length() }

    /**
     * Si entre todos pasan del tope, fuera los mas viejos, de cualquier emulador. Nunca el ultimo de
     * cada uno: es el que puede hacer falta, y uno solo no llena nada que el tope no deje.
     */
    @Synchronized
    fun enforceCap(ctx: Context, protect: File? = null) {
        val cap = capMb(ctx) shl 20
        val all = backupRoot(ctx).listFiles().orEmpty().filter { it.isDirectory }.flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.endsWith(".bak") }.orEmpty().sortedByDescending { it.name }.drop(1)
        }.filter { it != protect }
        var used = usedBytes(ctx)
        // Por fecha (el nombre empieza por ella), los mas viejos primero.
        for (f in all.sortedBy { it.name }) {
            if (used <= cap) break
            val size = f.length()
            if (f.delete()) {
                used -= size
                LinkState.addLog("Removed old backup ${f.parentFile?.name}/${f.name} (over ${capMb(ctx)} MB)", "saves")
            }
        }
    }

    /**
     * Los respaldos que tocan segun cada cuanto los quiere cada emulador. Solo si la carpeta cambio
     * desde el ultimo (aqui la fecha local basta: solo dice si vale la pena hacer otro).
     */
    fun scheduled(ctx: Context) {
        val now = System.currentTimeMillis()
        for (e in all(ctx).filter { it.configured }) {
            val period = EmuSaves.PERIODS[e.every] ?: continue
            if (period <= 0 || now - e.lastBackup < period) continue
            val newest = e.folder.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: continue
            if (newest <= e.lastBackup) continue
            runCatching { backup(ctx, e, "auto") }.onFailure { LinkState.addLog("Automatic backup of ${appName(ctx, e.pkg)} failed: ${it.message}", "saves", error = true) }
        }
    }

    /** El nombre de la app del emulador, o el paquete si no se ve. */
    fun appName(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
