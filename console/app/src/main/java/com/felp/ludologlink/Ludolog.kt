package com.felp.ludologlink

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Lo que Link sabe de Ludolog, el front-end de la misma consola, y como le avisa.
 *
 * config.xml NUNCA se escribe: Ludolog lo guarda en memoria y pisaria cualquier cambio. Los ajustes
 * nuevos se le dejan aparte y los aplica ella ([queueConfig]). Lo que si escribe Link en su carpeta de
 * datos, y el aviso que manda despues (broadcast solo al paquete de Ludolog, que su puente, LinkBridge,
 * recibe con permiso de firma):
 * - `link/`: config-update.json (CONFIG_CHANGED), restore/ con su testigo `.ready` (RESTORE) y
 *   edits-in.json (EDITS_IN, ver MetaEdits). De ahi lee tambien lo que deja Ludolog: played.tsv (ver
 *   Saves) y edits.tsv (ver MetaEdits).
 * - `media/`: arte y videos que llegan del PC o de otra consola, o que se quitan (MEDIA_CHANGED, o
 *   LIBRARY_CHANGED si esa Ludolog no la conoce: ver [mediaChanged]). Los huerfanos que borra el PC
 *   pueden estar en cualquier carpeta de medios ([mediaRoots]).
 * - `companion/`: los cuadernos de OTRAS consolas (nunca el propio: ver [installLogbook]) y sus
 *   caratulas en companion/covers/ (CompanionCovers). Sin aviso: Ludolog los lee por su cuenta.
 * Por las carpetas de ROMs, LIBRARY_CHANGED (con espera) y MOVED; STATE al encenderse o apagarse Link.
 * Las claves de arte van por su proveedor (content://<paquete>.keys, ver [artKeys]). El contrato esta
 * en ludolog-front-end/docs/ludolog-link.md.
 */
object Ludolog {
    const val PACKAGE = BuildConfig.LUDOLOG_PACKAGE
    private const val ACTION_LIBRARY_CHANGED = "com.felp.frontcomp.link.LIBRARY_CHANGED"
    private const val ACTION_MOVED = "com.felp.frontcomp.link.MOVED"
    private const val ACTION_CONFIG_CHANGED = "com.felp.frontcomp.link.CONFIG_CHANGED"
    private const val ACTION_RESTORE = "com.felp.frontcomp.link.RESTORE"
    private const val ACTION_MEDIA_CHANGED = "com.felp.frontcomp.link.MEDIA_CHANGED"
    private const val ACTION_STATE = "com.felp.frontcomp.link.STATE"
    private const val ACTION_EDITS_IN = "com.felp.frontcomp.link.EDITS_IN"

    fun version(ctx: Context): String? =
        runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0).versionName }.getOrNull()

    /** `<volumen>/Ludolog`, la que tenga config.xml. Ludolog guarda cual eligio en privado. */
    fun dataDir(ctx: Context): File? =
        RomStore.volumePaths(ctx).map { File(it, BuildConfig.LUDOLOG_DATA) }.firstOrNull { File(it, "config.xml").isFile }

    /** config.xml como mapa. Es el XML de SharedPreferences: string, int, long, float, boolean y set. */
    fun config(dir: File): Map<String, Any> {
        val out = HashMap<String, Any>()
        runCatching {
            File(dir, "config.xml").inputStream().use { input ->
                val p = Xml.newPullParser()
                p.setInput(input, "UTF-8")
                var set: MutableSet<String>? = null
                var setName: String? = null
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.END_TAG && p.name == "set") {
                        setName?.let { out[it] = set.orEmpty() }; set = null; setName = null
                    }
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    val name = p.getAttributeValue(null, "name")
                    val value = p.getAttributeValue(null, "value")
                    when (p.name) {
                        "string" -> if (set != null) set.add(p.nextText()) else if (name != null) out[name] = p.nextText()
                        "int" -> value?.toIntOrNull()?.let { out[name] = it }
                        "long" -> value?.toLongOrNull()?.let { out[name] = it }
                        "float" -> value?.toFloatOrNull()?.let { out[name] = it }
                        "boolean" -> value?.let { out[name] = it == "true" }
                        "set" -> { set = LinkedHashSet(); setName = name }
                    }
                }
            }
        }
        return out
    }

    /** Las carpetas de ROMs que usa Ludolog, si las fijo a mano. */
    @Suppress("UNCHECKED_CAST")
    fun romDirs(ctx: Context): List<File> {
        val dir = dataDir(ctx) ?: return emptyList()
        return (config(dir)["library.romdirs"] as? Set<String>).orEmpty().map(::File).filter { it.isDirectory }
    }

    /** El tema puesto en Ludolog, con su id de ahora aunque el ajuste guarde uno viejo (gothic, retrofuture, minimal). */
    fun themeId(c: Map<String, Any?>): String =
        (c["look.theme"] as? String)?.let { com.felp.frontcomp.LEGACY_THEME_IDS[it] ?: it } ?: "gallery"

    /** Para el PC: si esta, y con que aspecto, para dibujarse igual. */
    fun info(ctx: Context): JSONObject {
        val version = version(ctx) ?: return JSONObject().put("installed", false)
        val out = JSONObject().put("installed", true).put("version", version)
        val dir = dataDir(ctx) ?: return out
        val c = config(dir)
        val theme = themeId(c)
        return out.put("data", dir.absolutePath)
            .put("theme", theme)
            .put("bright", c["theme.bright.$theme"] as? Boolean ?: false)
            .put("accent", c["look.accent.$theme"] as? String ?: JSONObject.NULL)
            .put("restore", canRestore(ctx))
            .put("logbook", ownLogbook(dir, c) ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------- cuadernos

    private val UNSAFE = Regex("""[^A-Za-z0-9 _.-]""")

    /**
     * El cuaderno de ESTA consola, como lo llama Ludolog (Logbooks.fileName): el modelo sin lo que
     * no vale en un nombre, un guion y su ID de `log.console.id`. «Modelo-1a2b.db». Nulo si aun no
     * saco ID. Es el unico que esta consola escribe, y el unico que Link nunca le cambia.
     */
    fun ownLogbook(dir: File): String? = ownLogbook(dir, config(dir))

    /** Lo mismo con la config ya leida (es un XML: no se lee dos veces por peticion). */
    fun ownLogbook(dir: File, c: Map<String, Any>): String? {
        val id = c["log.console.id"] as? String ?: return null
        val model = android.os.Build.MODEL.orEmpty().ifEmpty { "handheld" }
        return model.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "console" } + "-$id.db"
    }

    /** Lo lejos que llega un cuaderno: su ultima partida, cuantas hay y cuantas misiones. Nulo si no se abre. */
    fun logbookVersion(f: File): JSONObject? = runCatching {
        android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            fun long(sql: String) = runCatching { db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } }.getOrDefault(0L)
            JSONObject()
                .put("last", long("SELECT MAX(COALESCE(ended_at, started_at)) FROM sessions"))
                .put("sessions", long("SELECT COUNT(*) FROM sessions"))
                .put("missions", long("SELECT COUNT(*) FROM missions_done"))
        }
    }.getOrNull()

    /**
     * Los cuadernos de esta consola y lo lejos que llega cada uno: el propio por copia coherente
     * (Ludolog puede estar escribiendo), los de las demas tal cual (solo se leen). Ver CompanionShare.
     */
    fun companionList(ctx: Context): JSONObject {
        val dir = dataDir(ctx) ?: return JSONObject().put("files", org.json.JSONArray())
        val own = ownLogbook(dir)
        val files = org.json.JSONArray()
        File(dir, "companion").listFiles { f -> f.isFile && f.name.endsWith(".db") && !f.name.startsWith(".") }.orEmpty().forEach { f ->
            // Tambien el propio, directo: una conexion de solo lectura ve una foto coherente aunque
            // Ludolog este escribiendo (es lo mismo que hace snapshot, sin copiar el archivo entero).
            logbookVersion(f)?.let { files.put(it.put("name", f.name)) }
        }
        return JSONObject().put("own", own ?: JSONObject.NULL).put("files", files)
    }

    /**
     * Pone en su sitio el cuaderno de otra consola que llego a [incoming] (con punto delante, que
     * Ludolog no mira). Devuelve el motivo si no vale; nulo si quedo puesto. Nunca el propio.
     */
    fun installLogbook(ctx: Context, name: String, incoming: File): String? {
        if (isOwnLogbook(ctx, name)) return "that is this device's own logbook"
        val dest = logbookTarget(ctx, name) ?: return "invalid logbook name"
        val head = ByteArray(15)
        val got = runCatching { incoming.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (got < 15 || String(head, Charsets.US_ASCII) != "SQLite format 3") return "that isn't a logbook"
        if (!incoming.renameTo(dest)) {
            dest.delete()
            if (!incoming.renameTo(dest)) return "couldn't save the logbook"
        }
        return null
    }

    /** Si [name] es el cuaderno de esta consola: por su ID, aunque el modelo se escribiera distinto. */
    fun isOwnLogbook(ctx: Context, name: String): Boolean {
        val dir = dataDir(ctx) ?: return false
        val c = config(dir)
        val id = c["log.console.id"] as? String ?: return false
        return name.endsWith("-$id.db", ignoreCase = true) || name.equals(ownLogbook(dir, c), ignoreCase = true)
    }

    /**
     * Donde va el cuaderno de OTRA consola que llega del PC: `<datos>/companion/<nombre>.db`, al
     * lado del de esta, que es como Ludolog comparte el Companion (Logbooks.others: solo los lee,
     * y desde una copia suya). Nulo si el nombre no vale.
     */
    fun logbookTarget(ctx: Context, name: String): File? {
        val dir = dataDir(ctx) ?: return null
        if (name.isEmpty() || name.startsWith(".") || '/' in name || '\\' in name || !name.endsWith(".db")) return null
        val base = File(dir, "companion")
        val f = File(base, name)
        return f.takeIf { it.canonicalPath.startsWith(base.canonicalPath + File.separator) }
    }

    /** Si esta Ludolog sabe poner en su sitio un respaldo (LinkRestore, desde 2026-10). */
    private fun canRestore(ctx: Context) = handles(ctx, ACTION_RESTORE)

    private fun handles(ctx: Context, action: String) = runCatching {
        ctx.packageManager.queryBroadcastReceivers(Intent(action).setPackage(PACKAGE), 0).isNotEmpty()
    }.getOrDefault(false)

    // ------------------------------------------------------------- carpeta de datos

    /**
     * Lo irreemplazable y pequeño: los ajustes, los cuadernos, lo corregido a mano en las fichas y
     * el catalogo de consolas del usuario. Lo demas (arte, videos, catalogo de juegos, temas) se
     * puede volver a bajar, aunque cueste horas: va solo en el respaldo completo.
     */
    private fun essential(rel: String) =
        rel == "config.xml" || rel == "systems.toml" || rel == "tvs.toml" ||
            (rel.startsWith("companion/") && rel.endsWith(".db")) ||
            rel.startsWith("dossiers/") || rel.startsWith("systems/")

    /** Lo que nunca sale: indices que se rehacen solos, temporales y diarios de SQLite. */
    private fun skipped(rel: String) =
        rel.startsWith("cache/") || rel.startsWith("link/") || rel.endsWith(".part") || rel.endsWith("-journal") || rel.endsWith("-wal") ||
            rel.endsWith("-shm") || rel.split('/').any { it.startsWith(".") }

    /** Todo lo de la carpeta de datos: ruta, tamaño, fecha y si es esencial. */
    fun manifest(dir: File): org.json.JSONArray {
        val out = org.json.JSONArray()
        val base = dir.absolutePath.length + 1
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.substring(base).replace(File.separatorChar, '/')
            if (skipped(rel)) return@forEach
            out.put(JSONObject().put("path", rel).put("size", f.length()).put("mtime", f.lastModified())
                .put("essential", essential(rel)))
        }
        return out
    }

    /**
     * Una copia coherente de un cuaderno, aunque Ludolog este escribiendo en el: VACUUM INTO
     * desde una conexion de solo lectura. El original no se toca.
     */
    fun snapshot(ctx: Context, db: File): File {
        val out = File(ctx.cacheDir, "snapshot-${System.nanoTime()}.db")
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { it.execSQL("VACUUM INTO ?", arrayOf(out.path)) }
        } catch (e: Exception) {
            out.delete(); throw e
        }
        return out
    }

    /**
     * Cuando se jugo por ultima vez cada juego con ese emulador segun el cuaderno [db]: titulo ->
     * instante. Directo y de solo lectura (ver companionList). Vacio si no se abre.
     */
    fun sessionPlays(db: File, pkg: String): Map<String, Long> = runCatching {
        val out = HashMap<String, Long>()
        android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT title, MAX(COALESCE(ended_at, started_at)) FROM sessions " +
                "WHERE package_name = ? AND title IS NOT NULL GROUP BY title", arrayOf(pkg)).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
            }
        }
        out as Map<String, Long>
    }.getOrDefault(emptyMap())

    /**
     * Ajustes nuevos para Ludolog, del PC. No se toca config.xml: se dejan en
     * `<datos>/link/config-update.json` —mezclados con los que hubiera pendientes— y se avisa;
     * Ludolog los aplica ella misma (LinkConfig) y se refresca. Si esta cerrada, al arrancar.
     * Devuelve nulo si Ludolog no esta.
     */
    @Synchronized
    fun queueConfig(ctx: Context, changes: JSONObject): Boolean? {
        val dir = dataDir(ctx) ?: return null
        writeConfig(dir, changes)
        send(ctx, Intent(ACTION_CONFIG_CHANGED))
        return true
    }

    private fun writeConfig(dir: File, changes: JSONObject) {
        val linkDir = File(dir, "link").apply { mkdirs() }
        val file = File(linkDir, "config-update.json")
        val merged = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
        val set = merged.optJSONObject("set") ?: JSONObject()
        val remove = (merged.optJSONArray("remove")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()).toMutableSet()
        changes.optJSONObject("set")?.let { s -> for (k in s.keys()) { set.put(k, s.get(k)); remove -= k } }
        changes.optJSONArray("remove")?.let { a -> for (i in 0 until a.length()) { val k = a.getString(i); set.remove(k); remove += k } }
        merged.put("set", set).put("remove", org.json.JSONArray(remove.toList()))
        val tmp = File(linkDir, "config-update.json.part")
        tmp.writeText(merged.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ------------------------------------------------------------- restaurar

    /**
     * Donde espera un archivo de un respaldo devuelto desde el PC: `<datos>/link/restore/<rel>`.
     * Ludolog lo pone en su sitio al reiniciarse (LinkRestore); aqui no se toca la carpeta de
     * datos. Solo lo esencial, menos config.xml, que va por [queueConfig]. Nulo si no vale.
     */
    fun restoreTarget(ctx: Context, rel: String): File? {
        val dir = dataDir(ctx) ?: return null
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it.startsWith(".") }) return null
        val ok = rel == "systems.toml" || rel == "tvs.toml" ||
            (parts.size == 2 && parts[0] == "companion" && rel.endsWith(".db")) ||
            (parts.size >= 2 && (parts[0] == "dossiers" || parts[0] == "systems"))
        if (!ok) return null
        val base = File(dir, "link/restore")
        val f = File(base, rel)
        return f.takeIf { it.canonicalPath.startsWith(base.canonicalPath + File.separator) }
    }

    /** Lo que quedara de un respaldo a medio subir. */
    fun clearRestore(ctx: Context): Boolean {
        val dir = dataDir(ctx) ?: return false
        File(dir, "link/restore").deleteRecursively()
        return true
    }

    /**
     * El respaldo esta entero: sus ajustes a config-update.json —sin aviso propio, se aplican en
     * el mismo reinicio—, el testigo `.ready` y el aviso RESTORE. Nulo si Ludolog no esta.
     */
    @Synchronized
    fun commitRestore(ctx: Context, config: JSONObject?): Boolean? {
        val dir = dataDir(ctx) ?: return null
        if (config != null && ((config.optJSONObject("set")?.length() ?: 0) > 0 || (config.optJSONArray("remove")?.length() ?: 0) > 0))
            writeConfig(dir, config)
        val restore = File(dir, "link/restore").apply { mkdirs() }
        File(restore, ".ready").writeText(System.currentTimeMillis().toString())
        send(ctx, Intent(ACTION_RESTORE))
        return true
    }

    // ------------------------------------------------------------------ arte

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
    private val VIDEO_EXT = setOf("mp4", "mkv", "webm")

    /** Donde busca Ludolog arte y videos (ArtIndex.findRoots), en ese orden. */
    private fun mediaRoots(ctx: Context): List<File> {
        val data = dataDir(ctx)
        val theme = data?.let { themeId(config(it)) } ?: "gallery"
        return listOfNotNull(data?.let { File(it, "themes/$theme/media") }, data?.let { File(it, "media") }) +
            RomStore.volumePaths(ctx).flatMap { v ->
                listOf("ES-DE/downloaded_media", "Emulation/downloaded_media", "media").map { File(v, it) }
            }
    }

    /**
     * El archivo de arte (primero la caratula) o el video de un juego, donde lo encontraria
     * Ludolog: para que el PC lo enseñe al pasar el raton. [systems]: la consola de Ludolog y la
     * carpeta del ROM. Solo lectura.
     */
    fun findMedia(ctx: Context, systems: List<String>, stem: String, video: Boolean): File? {
        val want = systems.map { it.lowercase() }.toSet()
        for (root in mediaRoots(ctx)) {
            for (sys in DirIndex.dirs(root).filter { it.name.lowercase() in want }) {
                val kinds = DirIndex.dirs(sys)
                    .filter { it.name.equals("videos", ignoreCase = true) == video }
                    .sortedBy { if (it.name.equals("covers", ignoreCase = true)) 0 else 1 }
                val exts = if (video) VIDEO_EXT else IMAGE_EXT
                val loose = if (video) emptyList() else listOf(sys)
                for (dir in kinds + loose) {
                    DirIndex.list(dir).firstOrNull {
                        !it.isDir && it.stem.equals(stem, ignoreCase = true) && it.ext in exts
                    }?.let { return File(dir, it.name) }
                }
            }
        }
        return null
    }

    /**
     * Como [findMedia], pero comparando como Ludolog (ArtIndex): solo letras y numeros, en minusculas, y
     * contra varios nombres a la vez (el del archivo y el del juego). Una caratula guardada por el
     * titulo ("Blasphemous") no la encontraba el nombre del ROM ("Blasphemous [0100...]").
     */
    fun findMediaByKey(ctx: Context, systems: List<String>, keys: Set<String>, video: Boolean): File? {
        val want = systems.map { it.lowercase() }.toSet()
        val clean = keys.filter { it.isNotEmpty() }.toSet()
        if (clean.isEmpty()) return null
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        for (root in mediaRoots(ctx)) {
            for (sys in DirIndex.dirs(root).filter { it.name.lowercase() in want }) {
                val kinds = DirIndex.dirs(sys)
                    .filter { it.name.equals("videos", ignoreCase = true) == video }
                    .sortedBy { if (it.name.equals("covers", ignoreCase = true)) 0 else 1 }
                val exts = if (video) VIDEO_EXT else IMAGE_EXT
                val loose = if (video) emptyList() else listOf(sys)
                for (dir in kinds + loose) {
                    DirIndex.list(dir).firstOrNull {
                        !it.isDir && it.ext in exts && key(it.stem) in clean
                    }?.let { return File(dir, it.name) }
                }
            }
        }
        return null
    }

    /**
     * Cada archivo de arte y video de las carpetas de medios, con su dueño: "theme" (la del tema
     * puesto), "ludolog" (la de datos, donde escribe su scraper) u "other" (ES-DE y demas). Para que
     * el PC busque los huerfanos y copie arte entre consolas. Con la estructura de Ludolog:
     * `<raiz>/<consola>/<tipo>/<archivo>` o `<raiz>/<consola>/<archivo>` (tipo vacio).
     */
    fun mediaList(ctx: Context): JSONArray {
        val out = JSONArray()
        val seen = HashSet<String>()
        mediaRoots(ctx).forEachIndexed { i, root ->
            if (!root.isDirectory || !seen.add(runCatching { root.canonicalPath }.getOrDefault(root.path))) return@forEachIndexed
            val owner = when (i) { 0 -> "theme"; 1 -> "ludolog"; else -> "other" }
            fun put(sys: File, kind: String, dir: File, f: DirIndex.Item) {
                if (f.isDir || (f.ext !in IMAGE_EXT && f.ext !in VIDEO_EXT)) return
                out.put(JSONObject().put("path", File(dir, f.name).absolutePath).put("owner", owner).put("sys", sys.name)
                    .put("kind", kind).put("name", f.name).put("size", f.size))
            }
            for (sys in DirIndex.dirs(root)) {
                for (f in DirIndex.list(sys)) {
                    if (f.isDir) File(sys, f.name).let { k -> DirIndex.list(k).forEach { put(sys, f.name, k, it) } }
                    else put(sys, "", sys, f)
                }
            }
        }
        return out
    }

    /**
     * Un archivo de arte o video de una carpeta de medios, por su ruta: solo dentro de una de ellas
     * (ver [mediaRoots]), a uno o dos niveles de la consola y con extension de imagen o video.
     */
    fun mediaFile(ctx: Context, path: String): File? = mediaFile(mediaBases(ctx), path)

    /** Las carpetas de medios ya resueltas (ver [mediaFile]): para una tanda, una vez. */
    fun mediaBases(ctx: Context): List<String> =
        mediaRoots(ctx).filter { it.isDirectory }.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.distinct()

    fun mediaFile(bases: List<String>, path: String): File? {
        val f = File(path)
        val canon = runCatching { f.canonicalPath }.getOrNull() ?: return null
        if (!f.isFile) return null
        val ext = f.extension.lowercase()
        if (ext !in IMAGE_EXT && ext !in VIDEO_EXT) return null
        return f.takeIf {
            bases.any { base ->
                canon.startsWith(base + File.separator) && canon.removePrefix(base + File.separator).split(File.separatorChar).size in 2..3
            }
        }
    }

    /**
     * Que juegos tienen arte y video, buscados donde los busca Ludolog (ArtIndex.findRoots): la
     * carpeta de medios del tema puesto, la de datos y las de ES-DE en cada unidad. Devuelve
     * claves «consola/nombre» en minusculas, con la consola como se llama la carpeta.
     */
    fun artIndex(ctx: Context): JSONObject {
        val roots = mediaRoots(ctx)
        val art = HashSet<String>()
        val video = HashSet<String>()
        // Del indice de carpetas (DirIndex): con la carpeta de ES-DE llena son miles de archivos.
        for (root in roots) {
            for (sys in DirIndex.list(root).filter { it.isDir }) {
                val id = sys.name.lowercase()
                for (f in DirIndex.list(File(root, sys.name))) {
                    if (f.isDir) {
                        val isVideo = f.name.equals("videos", ignoreCase = true)
                        for (g in DirIndex.list(File(File(root, sys.name), f.name))) {
                            if (g.isDir) continue
                            if (isVideo && g.ext in VIDEO_EXT) video += "$id/${g.stem.lowercase()}"
                            else if (!isVideo && g.ext in IMAGE_EXT) art += "$id/${g.stem.lowercase()}"
                        }
                    } else if (f.ext in IMAGE_EXT) art += "$id/${f.stem.lowercase()}"
                }
            }
        }
        return JSONObject().put("art", org.json.JSONArray(art.toList())).put("video", org.json.JSONArray(video.toList()))
    }

    /**
     * Donde va un archivo de arte o un video que llega del PC: `<datos>/media/<consola>/<tipo>/<archivo>`,
     * con ruta segura. Imagenes en cualquier tipo menos `videos`; en `videos`, solo .mp4 (el PC ya lo
     * convirtio a lo que pide Ludolog). Nulo si no vale. Ver [mediaChanged].
     */
    fun mediaTarget(ctx: Context, rel: String): File? {
        val dir = dataDir(ctx) ?: return null
        val parts = rel.split('/')
        if (parts.size != 4 || parts[0] != "media" || parts.any { it.isEmpty() || it.startsWith(".") || it == ".." }) return null
        val ext = parts.last().substringAfterLast('.', "").lowercase()
        if (if (parts[2] == "videos") ext != "mp4" else ext !in IMAGE_EXT) return null
        val f = File(dir, rel)
        return f.takeIf { it.canonicalPath.startsWith(File(dir, "media").canonicalPath + File.separator) }
    }

    /**
     * Quita el arte (o el video) de un juego, solo de la carpeta de medios de Ludolog: lo de ES-DE
     * u otras apps no es suyo. Busca `<stem>.*` en `<datos>/media/<consola>/<tipo>/` para cada
     * consola de [systems] (la de Ludolog y la carpeta del ROM). Devuelve lo borrado.
     */
    fun removeMedia(ctx: Context, systems: List<String>, stem: String, video: Boolean): List<File> {
        val dir = dataDir(ctx) ?: return emptyList()
        fun safe(s: String) = s.isNotEmpty() && !s.startsWith(".") && '/' !in s && '\\' !in s
        if (!safe(stem)) return emptyList()
        val media = File(dir, "media")
        val gone = ArrayList<File>()
        for (sys in systems.filter(::safe).distinct()) {
            for (kind in File(media, sys).listFiles().orEmpty().filter { it.isDirectory }) {
                val isVideo = kind.name.equals("videos", ignoreCase = true)
                if (isVideo != video) continue
                for (ext in if (video) VIDEO_EXT else IMAGE_EXT) {
                    val f = File(kind, "$stem.$ext")
                    if (f.isFile && f.canonicalPath.startsWith(media.canonicalPath + File.separator) && f.delete()) gone += f
                }
            }
        }
        return gone
    }

    /**
     * Arte o videos puestos o quitados: Ludolog olvida lo que sacara de ellos y repasa
     * (MEDIA_CHANGED). Una Ludolog sin esa accion, solo el repaso.
     */
    fun mediaChanged(ctx: Context, files: List<File>) {
        if (files.isEmpty()) return
        DirIndex.clear()
        if (handles(ctx, ACTION_MEDIA_CHANGED))
            send(ctx, Intent(ACTION_MEDIA_CHANGED).putExtra("paths", files.map { it.absolutePath }.toTypedArray()))
        else libraryChanged(ctx)
    }

    /**
     * Link se encendio o se apago (STATE): Ludolog pone su LED en verde o en rojo, en la sala y en
     * su entrada de la lista. Sin entrada en el registro: pasa cada vez que el servicio arranca o
     * para. Una Ludolog sin esa accion no recibe nada, y lo pregunta al volver (SaveCheckProvider).
     */
    fun linkState(ctx: Context, on: Boolean, address: String) {
        if (version(ctx) == null || !handles(ctx, ACTION_STATE)) return
        ctx.sendBroadcast(Intent(ACTION_STATE).setPackage(PACKAGE).putExtra("on", on).putExtra("address", address)
            .putExtra("peers", Peers.all(ctx).size).putExtra("pcLink", Prefs.pcLink(ctx))
            .putExtra("sync", Prefs.syncDevices(ctx)), null)
    }

    // ------------------------------------------------------------------ puente

    private val main = Handler(Looper.getMainLooper())
    private var pendingCtx: Context? = null
    private val changed = Runnable {
        pendingCtx?.let {
            send(it, Intent(ACTION_LIBRARY_CHANGED))
            // Correcciones que esperaban a estos juegos (ver MetaEdits).
            MetaEdits.placeSoon(it)
        }
    }

    /**
     * Algo cambio en las carpetas de ROMs. Con espera: una tanda de cincuenta subidas es UN
     * repaso en Ludolog cuando acaba, no cincuenta.
     */
    fun libraryChanged(ctx: Context) {
        DirIndex.clear()
        pendingCtx = ctx.applicationContext
        main.removeCallbacks(changed)
        main.postDelayed(changed, 4000)
    }

    /**
     * Correcciones de otras consolas en `link/edits-in.json` (ver MetaEdits): Ludolog las aplica. Una
     * Ludolog sin esa accion no se entera; el archivo se queda para cuando la tenga.
     */
    fun editsIn(ctx: Context) {
        if (handles(ctx, ACTION_EDITS_IN)) send(ctx, Intent(ACTION_EDITS_IN))
    }

    /** Donde Ludolog da y toma las claves de sus fuentes de arte (su LinkKeys): solo a este Link. */
    private val KEYS = android.net.Uri.parse("content://$PACKAGE.keys")

    /**
     * Las claves de las fuentes de arte de Ludolog (las de IGDB), en claro y con cuando se puso cada
     * una, para pasarlas a los aparatos emparejados (ver ArtKeys). Nulo si Ludolog no esta o es
     * anterior a la 0.6.2 (sin LinkKeys). Solo en memoria: Link no las guarda en ningun sitio.
     */
    fun artKeys(ctx: Context): Map<String, com.felp.ludolog.kit.KeyBox.Entry>? = runCatching {
        val b = ctx.contentResolver.call(KEYS, "get", null, null) ?: return null
        b.getStringArray("keys").orEmpty().associateWith { k ->
            com.felp.ludolog.kit.KeyBox.Entry(b.getString("v.$k").orEmpty(), b.getLong("t.$k"))
        }
    }.getOrNull()

    /** Claves llegadas de otro aparato: Ludolog toma las mas nuevas y las cifra a su modo. Cuantas tomo, o nulo. */
    fun putArtKeys(ctx: Context, m: Map<String, com.felp.ludolog.kit.KeyBox.Entry>): Int? = runCatching {
        val b = android.os.Bundle().apply {
            putStringArray("keys", m.keys.toTypedArray())
            for ((k, e) in m) { putString("v.$k", e.value); putLong("t.$k", e.at) }
        }
        ctx.contentResolver.call(KEYS, "put", null, b)?.getInt("changed")
    }.getOrNull()

    /** ROMs renombrados: Ludolog mueve lo de cada juego a la ruta nueva. Sin espera. */
    fun moved(ctx: Context, from: List<String>, to: List<String>) {
        if (from.isEmpty()) return
        DirIndex.clear()
        send(ctx, Intent(ACTION_MOVED).putExtra("from", from.toTypedArray()).putExtra("to", to.toTypedArray()))
    }

    private fun send(ctx: Context, intent: Intent) {
        if (version(ctx) == null) return
        // Al paquete de Ludolog y a nadie mas; el permiso lo exige su receptor.
        ctx.sendBroadcast(intent.setPackage(PACKAGE), null)
        // Las correcciones ya las cuenta MetaEdits, con de donde vienen.
        if (intent.action == ACTION_EDITS_IN) return
        LinkState.addLog(when (intent.action) {
            ACTION_MOVED -> "Ludolog: data moved"
            ACTION_CONFIG_CHANGED -> "Ludolog: new settings sent, it will refresh"
            ACTION_RESTORE -> "Ludolog: backup sent, it will restart to restore it"
            ACTION_MEDIA_CHANGED -> "Ludolog: art updated"
            else -> "Ludolog: library updated"
        }, "ludolog")
    }
}
