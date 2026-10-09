package com.felp.ludologlink.pc

import com.felp.ludolog.kit.Protocol
import org.json.JSONObject
import java.io.File
import java.net.InetAddress

/** Una consola emparejada, recordada entre sesiones. */
data class Known(val id: String, val name: String, val model: String, val host: String, val port: Int, val token: String)

/**
 * config.json en PcDirs.home: %APPDATA%\LudologLink en la instalada, data\ junto al .exe en la
 * portable. Distinta de la del ROM Renamer (%APPDATA%\ROMManager): son apps distintas y no
 * comparten nada.
 */
object Config {
    private val dir = PcDirs.home
    private val file = File(dir, "config.json")
    private var data = load().also(::keepOldBackups)

    /**
     * Los respaldos iban por defecto a Documentos\Ludolog Link. Ahora van a la carpeta de datos
     * (07-10-2026); en un PC que ya los tenia alli, esa carpeta se queda apuntada como la suya, para
     * que no desaparezcan de la vista. La portable empieza siempre en la suya.
     */
    private fun keepOldBackups(j: JSONObject) {
        if (PcDirs.portable || Dev.ON || j.optString("backup_root").isNotEmpty()) return
        val old = File(System.getProperty("user.home"), "Documents/Ludolog Link")
        if (old.listFiles().orEmpty().isNotEmpty()) j.put("backup_root", old.absolutePath)
    }

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

    /**
     * El id fijo de este PC (Protocol.PC_ID), creado la primera vez (09-10-2026). Va al emparejar, para
     * que la consola olvide los emparejamientos viejos de este mismo PC, y con el la consola prueba
     * quien es en una IP nueva sin que el PC le mande su clave. Cada copia de Link PC (la instalada, la
     * portable, la Dev) tiene el suyo, porque cada una tiene su carpeta de datos.
     */
    val pcId: String by lazy {
        data.optString("pc_id").takeIf { it.matches(Protocol.PC_ID) } ?: synchronized(this) {
            val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            data.put("pc_id", id)
            save()
            id
        }
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
                j.optInt("port", Dev.httpPort), j.optString("token"))
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
     * La carpeta de datos: donde van, si no se eligio otro sitio para cada uno, los respaldos, los
     * juegos bajados y el catalogo de ROMs del PC. Por defecto la del programa (PcDirs.home); se
     * cambia en Settings (pedido del usuario, 07-10-2026). Lo que ya habia en la de antes se queda alli.
     */
    var dataRoot: File
        @Synchronized get() = data.optString("data_dir").takeIf { it.isNotEmpty() }?.let(::File) ?: PcDirs.home
        @Synchronized set(v) { data.put("data_dir", v.absolutePath); save() }

    /**
     * Donde van los respaldos: la copia de la carpeta de Ludolog de cada consola y sus
     * instantaneas. En la carpeta de datos (antes, en Documentos: ver keepOldBackups).
     */
    var backupRoot: File
        @Synchronized get() = data.optString("backup_root").takeIf { it.isNotEmpty() }?.let(::File)
            ?: File(dataRoot, "backups")
        @Synchronized set(v) { data.put("backup_root", v.absolutePath); save() }

    /**
     * Donde van los juegos que se bajan de un device: en la carpeta de datos, salvo que se elija
     * otra en Settings. Antes se preguntaba en cada descarga y Settings solo lo ensenaba.
     */
    var downloadDir: File
        @Synchronized get() = data.optString("download_dir").takeIf { it.isNotEmpty() }?.let(::File)
            ?: File(dataRoot, "downloads")
        @Synchronized set(v) { data.put("download_dir", v.absolutePath); save() }

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
