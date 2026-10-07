package com.felp.ludologlink.pc

import org.json.JSONObject
import java.io.File
import java.net.InetAddress

/** Una consola emparejada, recordada entre sesiones. */
data class Known(val id: String, val name: String, val model: String, val host: String, val port: Int, val token: String)

/**
 * %APPDATA%\LudologLink\config.json. Distinta de la del ROM Renamer
 * (%APPDATA%\ROMManager): son apps distintas y no comparten nada.
 */
object Config {
    private val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "LudologLink")
    private val file = File(dir, "config.json")
    private var data = load()

    /**
     * Si no se pudo leer un config.json que existe (bloqueado por un antivirus o OneDrive, o roto):
     * entonces no se guarda encima, que borraria todos los emparejamientos. Lo de esta sesion queda
     * en config.json.tmp.
     */
    private var unreadable = false

    private fun load(): JSONObject {
        for (f in listOf(file, File(dir, "config.json.tmp"))) {
            if (!f.isFile) continue
            runCatching { return JSONObject(f.readText()) }
        }
        unreadable = file.isFile
        return JSONObject()
    }

    @Synchronized
    private fun save() {
        dir.mkdirs()
        val tmp = File(dir, "config.json.tmp")
        tmp.writeText(data.toString(2))
        if (unreadable) return
        // De una vez: borrar y luego renombrar dejaba sin archivo si algo fallaba entre medio.
        java.nio.file.Files.move(tmp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    /** Como se presenta este PC en la consola ("PCs emparejados"). */
    val pcName: String by lazy {
        System.getenv("COMPUTERNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    }

    private fun consoles(): JSONObject =
        data.optJSONObject("consoles") ?: JSONObject().also { data.put("consoles", it) }

    @Synchronized
    fun known(): List<Known> {
        val c = consoles()
        return c.keys().asSequence().map { id ->
            val j = c.getJSONObject(id)
            Known(id, j.optString("name"), j.optString("model"), j.optString("host"),
                j.optInt("port", com.felp.ludolog.kit.Protocol.HTTP_PORT), j.optString("token"))
        }.filter { it.token.isNotEmpty() }.sortedBy { it.name.lowercase() }.toList()
    }

    @Synchronized
    fun remember(k: Known) {
        consoles().put(k.id, JSONObject().put("name", k.name).put("model", k.model)
            .put("host", k.host).put("port", k.port).put("token", k.token))
        save()
    }

    @Synchronized
    fun forget(id: String) {
        consoles().remove(id)
        save()
    }

    /** El aspecto: "console" (el de la consola elegida) o el id de un tema de Ludolog. */
    var look: String
        @Synchronized get() = data.optString("look", "console").let { com.felp.frontcomp.LEGACY_THEME_IDS[it] ?: it }
        @Synchronized set(v) { data.put("look", v); save() }

    /**
     * Donde van los respaldos: la copia de la carpeta de Ludolog de cada consola y sus
     * instantaneas. Por defecto en Documentos, donde uno busca sus copias; se cambia en Settings.
     */
    var backupRoot: File
        @Synchronized get() = data.optString("backup_root").takeIf { it.isNotEmpty() }?.let(::File)
            ?: File(System.getProperty("user.home"), "Documents/Ludolog Link")
        @Synchronized set(v) { data.put("backup_root", v.absolutePath); save() }

    /** Lo de cada consola en este PC: su copia de la carpeta de datos de Ludolog y sus instantaneas. */
    fun consoleDir(id: String) = File(backupRoot, id)

    /** El barrido del tema de fosforo en este PC: "subtle", "console" o "off". */

    /** Donde se guardan las letras de los temas bajadas de la consola. */
    fun themeDir(id: String) = File(dir, "themes/$id")

    @Synchronized
    fun folder(key: String): File? =
        data.optString(key).takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }

    /** La carpeta guardada aunque ahora no este (un disco desconectado): ver PcCatalog. */
    @Synchronized
    fun path(key: String): String? = data.optString(key).takeIf { it.isNotEmpty() }

    @Synchronized
    fun setFolder(key: String, f: File) {
        data.put(key, f.absolutePath)
        save()
    }

    /** Mirar una vez al dia si hay version nueva en GitHub (ver PcUpdates). */
    var updateCheck: Boolean
        @Synchronized get() = data.optBoolean("update_check", true)
        @Synchronized set(v) { data.put("update_check", v); save() }

    /** Cuando se pregunto a GitHub por ultima vez, haya contestado o no. */
    var updateAt: Long
        @Synchronized get() = data.optLong("update_at", 0L)
        @Synchronized set(v) { data.put("update_at", v); save() }

    /** La ultima version publicada que se conoce, y aquella de la que ya se aviso. */
    var updateLatest: String?
        @Synchronized get() = data.optString("update_latest").takeIf { it.isNotEmpty() }
        @Synchronized set(v) { data.put("update_latest", v.orEmpty()); save() }

    var updateNotified: String?
        @Synchronized get() = data.optString("update_notified").takeIf { it.isNotEmpty() }
        @Synchronized set(v) { data.put("update_notified", v.orEmpty()); save() }

    /** config.json para el diagnostico: igual, pero sin el token de cada consola emparejada. */
    @Synchronized
    fun redacted(): String {
        val copy = JSONObject(data.toString())
        copy.optJSONObject("consoles")?.let { cs -> cs.keys().forEach { id -> cs.optJSONObject(id)?.put("token", "<hidden>") } }
        return copy.toString(2)
    }
}
