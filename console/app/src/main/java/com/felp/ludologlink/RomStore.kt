package com.felp.ludologlink

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.felp.ludolog.kit.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Volume(
    val path: String,
    val label: String,
    val removable: Boolean,
    val free: Long,
    val total: Long,
)

object RomStore {
    const val PART_SUFFIX = Protocol.PART_SUFFIX

    fun volumes(ctx: Context): List<Volume> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            Volume(dir.absolutePath, v.getDescription(ctx), v.isRemovable, dir.usableSpace, dir.totalSpace)
        }
    }

    /** Solo donde esta cada volumen, sin preguntar el espacio: para buscar carpetas. */
    fun volumePaths(ctx: Context): List<String> =
        ctx.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory?.absolutePath }

    fun volumeOf(ctx: Context, path: String): Volume? = volumeOf(volumes(ctx), path)

    /** El volumen de [path] entre unos ya pedidos: preguntar el espacio de todos cuesta. */
    fun volumeOf(all: List<Volume>, path: String): Volume? =
        all.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    fun root(ctx: Context): File? =
        Prefs.romsRoot(ctx)?.let(::File)?.takeIf { it.isDirectory }

    // ------------------------------------------------------------ deteccion

    private val esdeRe = Regex("""name="ROMDirectory"\s+value="([^"]*)"""")

    /**
     * Carpeta de ROMs: la que tenga configurada ES-DE si esta instalado; si no,
     * la carpeta "ROMs" de algun almacenamiento con mas sistemas con juegos.
     */
    fun detect(ctx: Context): String? {
        // La de Ludolog si la fijo: asi las rutas que se le pasan por el puente son las suyas.
        Ludolog.romDirs(ctx).firstOrNull { score(it) > 0 }?.let { return it.absolutePath }
        val vols = volumes(ctx)
        for (v in vols) {
            val f = File(v.path, "ES-DE/settings/es_settings.xml")
            if (!f.isFile) continue
            val p = esdeRe.find(f.readText())?.groupValues?.get(1)
            if (!p.isNullOrBlank() && File(p).isDirectory) return File(p).absolutePath
        }
        var best: File? = null
        var bestScore = 0
        for (v in vols) {
            val candidates = File(v.path).listFiles()
                ?.filter { it.isDirectory && it.name.equals("roms", ignoreCase = true) }
                ?: continue
            for (d in candidates) {
                val s = score(d)
                if (s > bestScore) {
                    best = d
                    bestScore = s
                }
            }
        }
        return best?.absolutePath
    }

    private fun score(root: File): Int =
        root.listFiles()?.count { sys ->
            sys.isDirectory && !sys.name.startsWith(".") && sys.listFiles()?.any(::isRom) == true
        } ?: 0

    /** Borra las subidas a medias (y sus marcas) que nadie retomo en tres dias. Devuelve cuantas. */
    fun dropStaleParts(ctx: Context): Int {
        val root = root(ctx) ?: return 0
        val old = System.currentTimeMillis() - 3 * 86_400_000L
        return root.walkTopDown().maxDepth(3)
            .filter { it.isFile && it.name.startsWith(".") && (it.name.endsWith(PART_SUFFIX) || it.name.endsWith("$PART_SUFFIX.id")) }
            .filter { it.lastModified() < old }.count { it.delete() }
    }

    fun isRom(f: File): Boolean =
        f.isFile && !f.name.startsWith(".") && !f.name.equals("systeminfo.txt", ignoreCase = true) &&
            !f.name.endsWith(PART_SUFFIX)

    // -------------------------------------------------------------- listado

    /** {sistema: [{name, size, mtime}]}. Un nivel de subcarpetas (multidisco). */
    fun list(root: File): JSONObject {
        val out = JSONObject()
        // Del indice de carpetas (DirIndex): solo se vuelve a mirar lo que cambio.
        for (sys in DirIndex.dirs(root).sortedBy { it.name.lowercase() }) {
            val arr = JSONArray()
            DirIndex.list(sys).sortedBy { it.name.lowercase() }.forEach { f ->
                if (isRom(f)) {
                    arr.put(entry(f.name, f))
                } else if (f.isDir && !f.name.startsWith(".")) {
                    DirIndex.list(File(sys, f.name)).filter(::isRom).forEach { g -> arr.put(entry(f.name + "/" + g.name, g)) }
                }
            }
            out.put(sys.name, arr)
        }
        return out
    }

    /** Lo mismo que [isRom], con lo que ya dice el indice (sin mirar el archivo). */
    fun isRom(f: DirIndex.Item): Boolean =
        !f.isDir && !f.name.startsWith(".") && !f.name.equals("systeminfo.txt", ignoreCase = true) &&
            !f.name.endsWith(PART_SUFFIX)

    private fun entry(rel: String, f: DirIndex.Item) =
        JSONObject().put("name", rel).put("size", f.size).put("mtime", f.mtime)

    /** Lo que dice cada systeminfo.txt (nombre y extensiones), por ruta y fecha: se lee una vez. */
    private val infoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Pair<String, List<String>>>>()

    /**
     * Carpetas de sistema con las extensiones que aceptan. Sale de los
     * systeminfo.txt que deja ES-DE: asi el PC sabe a que carpeta va un archivo
     * en esta consola, sea cual sea su frontend o su estructura.
     */
    fun systems(root: File): JSONArray {
        val arr = JSONArray()
        for (d in DirIndex.dirs(root).sortedBy { it.name.lowercase() }) {
            val items = DirIndex.list(d)
            val infoItem = items.firstOrNull { !it.isDir && it.name.equals("systeminfo.txt", ignoreCase = true) }
            var full = ""
            var exts = emptyList<String>()
            if (infoItem != null) {
                val info = File(d, infoItem.name)
                val (name, list) = infoCache[info.path]?.takeIf { it.first == infoItem.mtime }?.second ?: run {
                    val text = info.readText()
                    val parsed = section(text, "Full system name:") to section(text, "Supported file extensions:").split(Regex("\\s+"))
                        .map { it.lowercase() }.filter { it.startsWith(".") }.distinct()
                    infoCache[info.path] = infoItem.mtime to parsed
                    parsed
                }
                full = name; exts = list
            }
            arr.put(JSONObject().put("folder", d.name).put("name", full.ifBlank { d.name })
                .put("exts", JSONArray(exts)).put("count", items.count(::isRom)))
        }
        return arr
    }

    private fun section(text: String, header: String): String {
        val i = text.indexOf(header)
        if (i < 0) return ""
        return text.substring(i + header.length).trimStart().lineSequence().firstOrNull()?.trim() ?: ""
    }

    /**
     * Destino de una subida, solo si queda dentro de la carpeta de ROMs.
     * Como mucho sistema/subcarpeta/archivo; nada de "..", ni rutas absolutas.
     */
    fun resolve(root: File, system: String, rel: String): File? {
        // Las reglas de nombre son las del kit: lo que vale aqui vale en el PC.
        val parts = Protocol.safeParts(system, rel) ?: return null
        val f = File(root, parts.joinToString("/"))
        val rootPath = root.canonicalPath + File.separator
        return if (f.canonicalPath.startsWith(rootPath)) f else null
    }

    // -------------------------------------------------------------- info

    fun info(ctx: Context): JSONObject {
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val vols = JSONArray()
        val all = volumes(ctx)
        for (v in all) {
            vols.put(JSONObject().put("path", v.path).put("label", v.label)
                .put("removable", v.removable).put("free", v.free).put("total", v.total))
        }
        val root = root(ctx)
        val vol = root?.let { volumeOf(all, it.absolutePath) }
        return JSONObject()
            .put("name", Prefs.deviceName(ctx))
            .put("model", Build.MODEL)
            .put("manufacturer", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("battery", JSONObject().put("level", if (level >= 0) level * 100 / scale else JSONObject.NULL)
                .put("charging", plugged))
            .put("volumes", vols)
            .put("roms_root", root?.absolutePath ?: JSONObject.NULL)
            .put("roms_free", vol?.free ?: JSONObject.NULL)
            .put("roms_total", vol?.total ?: JSONObject.NULL)
            .put("storage_access", Environment.isExternalStorageManager())
            .put("app_version", BuildInfo.VERSION)
    }

    // ---------------------------------------------------------- selector

    /** "content://.../tree/XXXX-XXXX:ROMs" -> "/storage/XXXX-XXXX/ROMs". */
    fun treeUriToPath(uri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val vol = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (vol.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$vol"
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

/** La version, la de version.properties de ludolog-front-end (ver build.gradle.kts). */
object BuildInfo {
    const val VERSION = BuildConfig.VERSION_NAME
}
