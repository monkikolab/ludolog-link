package com.felp.ludologlink.pc

import com.felp.ludolog.kit.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Error con el motivo que da la consola. status 0 = no hubo respuesta. */
class LinkError(val status: Int, message: String) : Exception(message) {
    companion object {
        /**
         * Un fallo de ESTE PC (un archivo que no se puede leer, un disco que se saco, un nombre que
         * Windows no admite), no de la red: no se reintenta. Con el 0 de un corte se reintentaba
         * tres horas un archivo que no iba a aparecer.
         */
        const val LOCAL = -1
    }
}

/** Una consola que contesto a la busqueda. */
/** [pcLink]: si la consola tiene PC Link puesto; sin el contesta, pero no atiende al PC. */
data class Found(val id: String, val name: String, val model: String, val host: String, val port: Int, val version: String,
                 val pcLink: Boolean = true)

/** Un respaldo de partidas de la consola: emulador (paquete y nombre), archivo .bak y tamaño. */
data class SaveBak(val pkg: String, val name: String, val size: Long, val app: String)

data class RomFile(val system: String, val name: String, val size: Long, val mtime: Long) {
    val key get() = "$system/$name"
}

/**
 * Un archivo de arte o video de la consola, donde lo busca Ludolog: `<raiz>/<sys>/<kind>/<name>`
 * (kind vacio: suelto en la carpeta de la consola). [owner]: "theme" (la del tema), "ludolog" (la
 * de datos) u "other" (ES-DE y demas programas).
 */
data class MediaFile(val path: String, val owner: String, val sys: String, val kind: String, val name: String, val size: Long)

/** Un archivo de la carpeta de datos de Ludolog en la consola. Ver Mirror. */
data class DataFile(val path: String, val size: Long, val mtime: Long, val essential: Boolean)

data class SystemDir(val folder: String, val fullName: String, val exts: List<String>, val count: Int)

data class ConsoleInfo(
    val name: String,
    val model: String,
    val manufacturer: String,
    val android: String,
    val battery: Int?,
    val charging: Boolean,
    val romsRoot: String?,
    val free: Long?,
    val total: Long?,
    val storageAccess: Boolean,
    val appVersion: String,
    /** Ludolog en esa consola, si esta: con que aspecto, para que el PC se vea igual. */
    val ludolog: LudologInfo?,
)

data class LudologInfo(
    val version: String, val theme: String?, val bright: Boolean, val accent: String?,
    /** Si sabe poner en su sitio un respaldo devuelto desde el PC (ver Restore.kt). */
    val restore: Boolean = false,
    /** El cuaderno propio de la consola («Modelo-1a2b.db»), nulo si aun no tiene. Ver CompanionSync. */
    val logbook: String? = null,
    /** Si este Link de consola dice cual es su cuaderno: sin eso no se comparte nada con ella. */
    val knowsLogbook: Boolean = false,
)

object Discovery {
    /** Broadcast de cada red local (y el general): la consola contesta directo a este PC. */
    private fun targets(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (a in nic.interfaceAddresses) {
                    if (a.address is Inet4Address) a.broadcast?.let(out::add)
                }
            }
        }
        return out
    }

    fun search(timeoutMs: Long = 1500): List<Found> {
        val found = linkedMapOf<String, Found>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 200
            val query = Protocol.DISCOVERY_QUERY.toByteArray()
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            while (System.currentTimeMillis() < deadline) {
                if (System.currentTimeMillis() >= nextSend) {     // repetir: el UDP se pierde
                    for (t in targets()) {
                        runCatching { s.send(DatagramPacket(query, query.size, t, Protocol.DISCOVERY_PORT)) }
                    }
                    nextSend = System.currentTimeMillis() + 500
                }
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val j = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                if (j.optString("app") != Protocol.APP || j.optString("id").isEmpty()) continue
                found[j.getString("id")] = Found(
                    id = j.getString("id"),
                    name = j.optString("name", j.optString("model")),
                    model = j.optString("model"),
                    host = p.address.hostAddress,
                    port = j.optInt("port", Protocol.HTTP_PORT),
                    version = j.optString("version"),
                    // Las de antes no lo dicen: atendian siempre.
                    pcLink = j.optBoolean("pcLink", true),
                )
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }
}

/** Una consola concreta: todas las llamadas son bloqueantes (llamar desde Dispatchers.IO). */
class Link(val host: String, val port: Int, var token: String?) {

    companion object {
        private const val CHUNK = 1 shl 20

        /** Las listas guardadas con su huella (ETag), por consola y ruta: ver [listing]. */
        private val listings = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

        fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        fun romPath(system: String, name: String) =
            "/roms/" + (listOf(system) + name.split('/')).joinToString("/") { enc(it) }
    }

    private fun open(method: String, path: String, query: Map<String, Any?> = emptyMap(), readTimeout: Int = 15_000): HttpURLConnection {
        val q = query.filterValues { it != null }.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value.toString())}" }
        val url = URI("http://$host:$port$path" + if (q.isEmpty()) "" else "?$q").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4_000
            this.readTimeout = readTimeout
            useCaches = false
            instanceFollowRedirects = false
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
    }

    /** Cuerpo JSON de la respuesta; lanza LinkError con el motivo si es un error. */
    private fun body(c: HttpURLConnection): JSONObject {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val j = runCatching { if (text.isBlank()) JSONObject() else JSONObject(text) }
            .getOrElse { JSONObject().put("error", text.take(200)) }
        if (code >= 400) throw LinkError(code, j.optString("error").ifBlank { "HTTP $code" })
        return j
    }

    private fun call(method: String, path: String, query: Map<String, Any?> = emptyMap(), readTimeout: Int = 15_000): JSONObject {
        val c = open(method, path, query, readTimeout)
        try {
            if (method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            return body(c)
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "no connection to $host: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    /**
     * Una lista (ROMs, sistemas, arte) que la consola manda con su huella: si no cambio desde la
     * ultima vez contesta 304 sin cuerpo y se usa la guardada. Ni la consola la vuelve a armar ni viaja.
     */
    private fun listing(path: String, readTimeout: Int = 60_000): JSONObject {
        val key = "$host:$port$path"
        val cached = listings[key]
        val c = open("GET", path, emptyMap(), readTimeout)
        try {
            cached?.let { c.setRequestProperty("If-None-Match", it.first) }
            if (cached != null && c.responseCode == 304) return JSONObject(cached.second)
            val j = body(c)
            c.getHeaderField("ETag")?.let { listings[key] = it to j.toString() }
            return j
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "no connection to $host: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    // ------------------------------------------------------------ consultas

    fun ping(): JSONObject = call("GET", "/ping", readTimeout = 4_000)

    /** Lo que la consola apunto en su actividad despues de [since] (ms). Ver PcLog. */
    fun log(since: Long, source: String): List<LogEntry> {
        val a = call("GET", "/log", mapOf("since" to since)).optJSONArray("entries") ?: return emptyList()
        return (0 until a.length()).map { LogEntry.of(a.getJSONObject(it), source) }
    }

    fun pairRequest(pc: String) { call("POST", "/pair/request", mapOf("pc" to pc)) }

    fun pairConfirm(code: String, pc: String): String {
        val t = call("POST", "/pair/confirm", mapOf("code" to code, "pc" to pc)).getString("token")
        token = t
        return t
    }

    fun info(): ConsoleInfo {
        val j = call("GET", "/info")
        val bat = j.optJSONObject("battery")
        return ConsoleInfo(
            name = j.optString("name"),
            model = j.optString("model"),
            manufacturer = j.optString("manufacturer"),
            android = j.optString("android"),
            battery = bat?.takeIf { !it.isNull("level") }?.optInt("level"),
            charging = bat?.optBoolean("charging") ?: false,
            romsRoot = j.optString("roms_root").takeIf { !j.isNull("roms_root") && it.isNotEmpty() },
            free = if (j.isNull("roms_free")) null else j.optLong("roms_free"),
            total = if (j.isNull("roms_total")) null else j.optLong("roms_total"),
            storageAccess = j.optBoolean("storage_access", true),
            appVersion = j.optString("app_version"),
            ludolog = j.optJSONObject("ludolog")?.takeIf { it.optBoolean("installed") }?.let { l ->
                LudologInfo(
                    version = l.optString("version"),
                    theme = l.optString("theme").ifEmpty { null },
                    bright = l.optBoolean("bright"),
                    accent = if (l.isNull("accent")) null else l.optString("accent").ifEmpty { null },
                    restore = l.optBoolean("restore"),
                    logbook = if (l.isNull("logbook")) null else l.optString("logbook").ifEmpty { null },
                    knowsLogbook = l.has("logbook"),
                )
            },
        )
    }

    fun roms(): List<RomFile> {
        val systems = listing("/roms").getJSONObject("systems")
        val out = mutableListOf<RomFile>()
        for (sys in systems.keys()) {
            val arr = systems.getJSONArray(sys)
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                out += RomFile(sys, f.getString("name"), f.optLong("size"), f.optLong("mtime"))
            }
        }
        return out
    }

    fun systems(): List<SystemDir> {
        val arr: JSONArray = listing("/systems", 15_000).getJSONArray("systems")
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            val exts = s.optJSONArray("exts")
            SystemDir(
                folder = s.getString("folder"),
                fullName = s.optString("name", s.getString("folder")),
                exts = (0 until (exts?.length() ?: 0)).map { exts!!.getString(it) },
                count = s.optInt("count"),
            )
        }
    }

    /** Renombra dentro de la misma carpeta; su .sbi va con el. Devuelve los nombres nuevos. */
    fun rename(system: String, from: String, to: String): List<String> {
        val arr = call("POST", "/rename", mapOf("system" to system, "from" to from, "to" to to)).optJSONArray("renamed")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    }

    /** Borra el archivo (y su .sbi). Devuelve lo borrado. */
    fun delete(system: String, name: String): List<String> {
        val arr = call("DELETE", romPath(system, name)).optJSONArray("deleted")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    }

    /** Ajustes nuevos para Ludolog: la consola los deja y Ludolog los aplica ella misma. */
    /** Una correccion de un juego o una consola (ver MetaEdits en la consola): la aplica y la pasa a las demas. */
    fun metaEdit(kind: String, system: String, path: String, field: String, value: String, t: Long, keys: String = "") =
        sendBytes("POST", "/meta/edit", emptyMap(), JSONObject().put("kind", kind).put("system", system).put("path", path)
            .put("field", field).put("value", value).put("t", t).put("keys", keys).toString().toByteArray(Charsets.UTF_8))

    fun sendConfig(changes: JSONObject) = sendBytes("POST", "/ludolog/config", emptyMap(), changes.toString().toByteArray(Charsets.UTF_8))

    /** Que juegos tienen arte y video en la consola: claves «consola/nombre» en minusculas. */
    fun art(): Pair<Set<String>, Set<String>> {
        val j = listing("/ludolog/art")
        fun set(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } } ?: emptySet()
        return set("art") to set("video")
    }

    /** Una imagen de arte, a `media/<consola>/<tipo>/<archivo>` de Ludolog. */
    fun putMedia(path: String, bytes: ByteArray) = sendBytes("PUT", "/ludolog/media", mapOf("path" to path), bytes)

    /** Cada archivo de arte y video de la consola, con su carpeta y su dueño (ver [MediaFile]). */
    fun mediaList(): List<MediaFile> {
        val a = listing("/ludolog/media/list").optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            MediaFile(it.getString("path"), it.optString("owner"), it.getString("sys"), it.optString("kind"),
                it.getString("name"), it.optLong("size"))
        }
    }

    /** Uno de ellos, a [dest]. Falso si ya no esta. */
    fun mediaFile(path: String, dest: File): Boolean =
        fetchTo("/ludolog/media/file", mapOf("path" to path), dest, readTimeout = 60_000)

    /**
     * Un archivo de la consola a [dest], por un `.part` que solo se pone en su sitio entero. Falso si
     * la consola contesta 404 y [missingOk]; con [keepMtime], la fecha del original (X-Mtime).
     */
    private fun fetchTo(path: String, query: Map<String, Any?>, dest: File, readTimeout: Int,
                        missingOk: Boolean = true, keepMtime: Boolean = false): Boolean {
        val c = open("GET", path, query, readTimeout = readTimeout)
        val tmp = File(dest.path + ".part")
        try {
            if (missingOk && c.responseCode == 404) return false
            if (c.responseCode != 200) body(c)
            dest.parentFile?.mkdirs()
            c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (keepMtime) c.getHeaderField("X-Mtime")?.toLongOrNull()?.let { dest.setLastModified(it) }
            return true
        } catch (e: IOException) {
            tmp.delete()
            throw LinkError(0, "no connection to $host: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    /** Borra esos archivos de medios (la consola solo acepta los de sus carpetas de medios). Devuelve los borrados. */
    fun deleteMedia(paths: List<String>): List<String> {
        val body = org.json.JSONObject().put("paths", org.json.JSONArray(paths)).toString().toByteArray(Charsets.UTF_8)
        val arr = sendBytes("POST", "/ludolog/media/delete", emptyMap(), body).optJSONArray("deleted")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    }

    /**
     * Quita el arte o el video de un juego de la carpeta de medios de Ludolog (nada de otras apps).
     * [systems]: la consola de Ludolog y la carpeta del ROM. Devuelve los nombres borrados.
     */
    fun removeMedia(systems: List<String>, stem: String, video: Boolean): List<String> {
        val arr = call("DELETE", "/ludolog/media", mapOf("systems" to systems.joinToString(","), "stem" to stem,
            "kind" to if (video) "video" else "art")).optJSONArray("deleted")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    }

    /** El cuaderno de otra consola, al lado del de esta (ver CompanionSync). La consola rechaza el suyo. */
    fun putLogbook(name: String, bytes: ByteArray) = sendBytes("PUT", "/ludolog/companion", mapOf("name" to name), bytes)

    /** Los respaldos de partidas guardadas de la consola (ver SaveBackups): emulador, nombre, tamaño. */
    fun saveBackups(): List<SaveBak> {
        val a = call("GET", "/saves/backups", readTimeout = 30_000).optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { j ->
            SaveBak(j.getString("pkg"), j.getString("name"), j.optLong("size"), j.optString("app").ifEmpty { j.getString("pkg") })
        } }
    }

    /** Como estan las partidas de ese emulador en la consola: si tiene carpeta, si se puede, si hay juego abierto. */
    fun savesState(pkg: String): JSONObject = call("GET", "/saves/state", mapOf("pkg" to pkg))

    /** Antes de escribir partidas: la consola respalda lo que tiene (lo llama [why]). Falla si hay un juego abierto. */
    fun savesBegin(pkg: String, why: String) { call("POST", "/saves/begin", mapOf("pkg" to pkg, "why" to why)) }

    /** Un archivo de partida, a su ruta dentro de la carpeta del emulador en la consola. */
    fun putSaveFile(pkg: String, path: String, bytes: ByteArray) =
        sendBytes("PUT", "/saves/file", mapOf("pkg" to pkg, "path" to path), bytes)

    fun savesEnd(pkg: String) { call("POST", "/saves/end", mapOf("pkg" to pkg)) }

    /** La lista del catalogo del PC, para que el device pida de ella (ver CatalogRequests). */
    fun putPcCatalog(index: ByteArray) = sendBytes("PUT", "/pc/catalog", emptyMap(), index)

    /** Lo pedido del catalogo que aun no esta en el device: consola (carpeta) y archivo. */
    fun pcRequests(): List<Pair<String, String>> {
        val a = call("GET", "/pc/requests").optJSONArray("requests") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> j.getString("system") to j.getString("name") } }
    }

    /** Un respaldo de partidas a [dest]. */
    fun saveBackup(pkg: String, name: String, dest: File) {
        fetchTo("/saves/backup", mapOf("pkg" to pkg, "name" to name), dest, readTimeout = 120_000, missingOk = false, keepMtime = true)
    }

    /** Restaurar (ver Restore.kt): vaciar la espera, dejar en ella cada archivo y darla por entera. */
    fun clearRestore() { call("DELETE", "/ludolog/restore") }
    fun putRestore(path: String, bytes: ByteArray) = sendBytes("PUT", "/ludolog/restore", mapOf("path" to path), bytes)
    fun commitRestore(config: JSONObject?) =
        sendBytes("POST", "/ludolog/restore", emptyMap(), config?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))

    private fun sendBytes(method: String, path: String, query: Map<String, Any?>, bytes: ByteArray): JSONObject {
        val c = open(method, path, query, readTimeout = 60_000)
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            return body(c)
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "no connection to $host: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    /** Los archivos de la carpeta de datos de Ludolog en la consola. */
    fun manifest(): List<DataFile> {
        val arr = call("GET", "/ludolog/manifest", readTimeout = 60_000).getJSONArray("files")
        return (0 until arr.length()).map { i ->
            val f = arr.getJSONObject(i)
            DataFile(f.getString("path"), f.optLong("size"), f.optLong("mtime"), f.optBoolean("essential"))
        }
    }

    /** El arte o el video de un juego, de donde lo veria Ludolog (ver Previews). Falso si no hay. */
    fun preview(systems: List<String>, stem: String, video: Boolean, dest: File): Boolean =
        fetchTo("/ludolog/preview", mapOf("systems" to systems.joinToString(","), "stem" to stem,
            "kind" to if (video) "video" else "art"), dest, readTimeout = 30_000)

    /**
     * Un archivo de la carpeta de datos de Ludolog en la consola, a [dest]. Falso si no existe. Con la
     * fecha del original, no la de la copia: es como se sabe despues si cambio.
     */
    fun ludologFile(path: String, dest: File): Boolean =
        fetchTo("/ludolog/file", mapOf("path" to path), dest, readTimeout = 30_000, keepMtime = true)

    // ------------------------------------------------------- transferencias

    /**
     * ¿Se puede subir? Lanza con el motivo (ya existe, no cabe...) sin enviar nada. Y cuanto tiene
     * ya la consola de un envio cortado con ese [id] (`partial`).
     */
    fun check(system: String, name: String, size: Long, overwrite: Boolean, id: String? = null): JSONObject =
        call("GET", "/check/" + romPath(system, name).removePrefix("/roms/"),
            mapOf("size" to size, "overwrite" to if (overwrite) "1" else null, "id" to id))

    fun upload(
        local: File, system: String, name: String, overwrite: Boolean,
        progress: (Long) -> Unit, cancelled: () -> Boolean,
    ) {
        // Antes que nada, que el archivo este: en un disco que se saco, length() da 0 y se mandaba un
        // ROM vacio (y con "reemplazar", encima del bueno).
        if (!local.isFile || local.length() == 0L) throw LinkError(LinkError.LOCAL, "${local.name} can't be read: is its drive connected?")
        val size = local.length()
        // De que envio es lo que la consola pueda tener a medias: este archivo, tal como esta ahora.
        val id = "u-$size-${local.lastModified()}"
        // Si la consola rechazara a mitad de envio, Windows tiraria su respuesta
        // y solo veriamos "conexion cortada". Preguntando antes, llega el motivo (y cuanto tiene ya).
        val start = check(system, name, size, overwrite, id).optLong("partial").coerceIn(0, size)
        try {
            uploadFrom(local, system, name, overwrite, id, start, progress, cancelled)
        } catch (e: LinkError) {
            // Lo de alla ya no casa (otro envio lo piso): desde cero.
            if (start > 0 && e.status == 409) uploadFrom(local, system, name, overwrite, id, 0, progress, cancelled) else throw e
        }
    }

    private fun uploadFrom(
        local: File, system: String, name: String, overwrite: Boolean, id: String, start: Long,
        progress: (Long) -> Unit, cancelled: () -> Boolean,
    ) {
        val size = local.length()
        // El archivo se abre ANTES que la conexion: abrir el envio ya manda la peticion, y si despues
        // no se podia leer, la consola recibia un archivo vacio.
        val inp = try {
            local.inputStream().also { if (start > 0) it.skipNBytes(start) }
        } catch (e: IOException) {
            throw LinkError(LinkError.LOCAL, "couldn't read ${local.name}: ${e.message}")
        }
        // Lectura larga: la consola sincroniza a disco un archivo grande antes de contestar.
        val c = try {
            open("PUT", romPath(system, name), mapOf("mtime" to local.lastModified(),
                "overwrite" to if (overwrite) "1" else null, "id" to id, "offset" to start), readTimeout = 300_000)
        } catch (e: Exception) {
            inp.close(); throw e
        }
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(size - start)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            inp.use {
                c.outputStream.use { out ->
                    val buf = ByteArray(CHUNK)
                    var done = start
                    while (true) {
                        if (cancelled()) throw LinkError(0, "cancelled")
                        val n = try { inp.read(buf) } catch (e: IOException) {
                            throw LinkError(LinkError.LOCAL, "couldn't read ${local.name}: ${e.message}")
                        }
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                    if (done != size) throw LinkError(LinkError.LOCAL, "${local.name} changed while sending")
                }
            }
            body(c)
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "transfer interrupted: ${e.message}")
        } finally {
            c.disconnect()   // a medias: la consola guarda lo recibido para seguir (ver check)
        }
    }

    /**
     * Copia un ROM de [src] a esta consola sin pasar por el disco del PC: lo que llega de una se
     * manda a la otra segun llega.
     */
    fun copyFrom(
        src: Link, system: String, name: String, size: Long, overwrite: Boolean,
        progress: (Long) -> Unit, cancelled: () -> Boolean,
        /** De que copia es lo que el destino tenga a medias: el device de origen y la fecha del ROM alli. */
        srcKey: String = "",
    ) {
        // Solo con nombre y tamaño, otro ROM del mismo nombre y tamaño (otra version, de otro device)
        // seguia encima de lo que quedo de este, y salia uno roto dado por bueno.
        val id = "c-" + "$srcKey-$size".replace(Regex("[^A-Za-z0-9._-]"), "_").take(70)
        var start = check(system, name, size, overwrite, id).optLong("partial").coerceIn(0, size)
        val g = src.open("GET", romPath(system, name), readTimeout = 120_000)
        if (start > 0) g.setRequestProperty("Range", "bytes=$start-")
        try {
            val code = g.responseCode
            if (code != 200 && code != 206) src.body(g)             // lanza con el motivo
            if (code == 200) start = 0                              // el origen no retoma: desde cero
            val rest = g.contentLengthLong.takeIf { it >= 0 } ?: (size - start)
            val total = start + rest
            val mtime = g.getHeaderField("X-Mtime")?.toLongOrNull()
            val c = open("PUT", romPath(system, name), mapOf("mtime" to mtime,
                "overwrite" to if (overwrite) "1" else null, "id" to id, "offset" to start), readTimeout = 300_000)
            try {
                c.doOutput = true
                c.setFixedLengthStreamingMode(rest)
                c.setRequestProperty("Content-Type", "application/octet-stream")
                c.outputStream.use { out ->
                    g.inputStream.use { inp ->
                        val buf = ByteArray(CHUNK)
                        var done = start
                        while (true) {
                            if (cancelled()) throw LinkError(0, "cancelled")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            progress(done)
                        }
                        if (done != total) throw LinkError(0, "the source device stopped sending at $done of $total bytes")
                    }
                }
                body(c)
            } finally {
                c.disconnect()   // a medias, el destino guarda lo recibido para seguir
            }
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "copy interrupted: ${e.message}")
        } finally {
            g.disconnect()
        }
    }

    /**
     * Descarga a dest. Si existe dest.part de ESTE mismo archivo ([key]: consola, nombre, tamaño y
     * fecha alla), retoma desde ahi; uno de otro (otra version, otro ROM con el mismo nombre) se tira.
     */
    fun download(
        system: String, name: String, dest: File,
        progress: (done: Long, total: Long) -> Unit, cancelled: () -> Boolean,
        key: String = "$system/$name",
    ) {
        val part = File(dest.path + ".part")
        val tag = File(dest.path + ".part.id")
        val sameOne = part.isFile && tag.isFile && runCatching { tag.readText() }.getOrNull() == key
        if (!sameOne) { part.delete(); runCatching { dest.parentFile?.mkdirs(); tag.writeText(key) } }
        var start = if (part.isFile) part.length() else 0L
        val c = open("GET", romPath(system, name), readTimeout = 120_000)
        try {
            if (start > 0) c.setRequestProperty("Range", "bytes=$start-")
            val code = c.responseCode
            if (code != 200 && code != 206) body(c)                // lanza con el motivo
            if (code == 200) start = 0                              // no retoma: desde cero
            val total = start + c.contentLengthLong
            val mtime = c.getHeaderField("X-Mtime")?.toLongOrNull()
            dest.parentFile?.mkdirs()
            val raf = try { RandomAccessFile(part, "rw") } catch (e: IOException) {
                throw LinkError(LinkError.LOCAL, "couldn't write ${dest.name} here: ${e.message}")
            }
            raf.use { raf ->
                raf.setLength(start)
                raf.seek(start)
                c.inputStream.use { inp ->
                    val buf = ByteArray(CHUNK)
                    var done = start
                    while (true) {
                        if (cancelled()) throw LinkError(0, "cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                    if (done != total) throw LinkError(0, "incomplete download: $done of $total bytes")
                }
            }
            try {
                Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                throw LinkError(LinkError.LOCAL, "couldn't save ${dest.name} here: ${e.message}")
            }
            tag.delete()
            mtime?.let { dest.setLastModified(it) }
        } catch (e: LinkError) {
            throw e
        } catch (e: IOException) {
            throw LinkError(0, "download interrupted: ${e.message}")
        } finally {
            c.disconnect()
        }
    }
}
