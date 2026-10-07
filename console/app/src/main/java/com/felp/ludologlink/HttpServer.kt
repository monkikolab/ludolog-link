package com.felp.ludologlink

import android.content.Context
import com.felp.ludolog.kit.Protocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Lo que el servidor le cuenta al servicio sobre las transferencias. */
interface TransferHooks {
    /** Una peticion; [fromPc]: de un PC (no de otra consola), lo que mantiene PC Link encendido. */
    fun activity(fromPc: Boolean)
    fun begin(name: String, size: Long, outgoing: Boolean)
    fun progress(done: Long)
    fun end(name: String, ok: Boolean, outgoing: Boolean)
}

/**
 * Servidor HTTP minimo. Una peticion por conexion; las subidas se escriben por
 * trozos directamente a disco, sin pasar el archivo entero por memoria.
 *
 *   GET  /ping                     sin token: quien soy, si estas emparejado y si PC Link esta puesto
 *   POST /pair/request?pc=[&device=1]   la consola muestra un codigo (device: la pide otra consola)
 *   POST /pair/confirm?code=&pc=   devuelve un token
 *
 * Al PC solo se le atiende con PC Link puesto (Prefs.pcLink), y a otra consola solo compartiendo
 * con los devices (Prefs.syncDevices): si no, 403 con el motivo, que cada lado enseña tal cual.
 *   GET  /info                     bateria, almacenamiento, carpeta de ROMs
 *   GET  /roms                     lista de ROMs por sistema
 *   GET  /systems                  carpetas de sistema y extensiones que aceptan
 *   GET  /check/<sistema>/<archivo>?size=&overwrite=   ¿se puede subir? (sin cuerpo)
 *   PUT  /roms/<sistema>/<archivo>?mtime=&overwrite=   subir un ROM
 *   GET  /roms/<sistema>/<archivo>                      descargar (admite Range)
 *   POST /rename?system=&from=&to=                      renombrar (su .sbi va con el)
 *   DELETE /roms/<sistema>/<archivo>                     borrar (su .sbi va con el)
 *   GET  /ludolog/manifest                              los archivos de la carpeta de datos de Ludolog
 *   POST /ludolog/config   (JSON)                       ajustes nuevos para Ludolog: los aplica ella (ver Ludolog.queueConfig)
 *   GET  /ludolog/art                                   que juegos tienen arte y video, donde los busca Ludolog
 *   GET  /ludolog/media/list                            cada archivo de arte y video, con su carpeta y su dueño
 *   GET  /ludolog/media/file?path=<ruta>                uno de ellos
 *   POST /ludolog/media/delete  (JSON {paths})          borrar los elegidos (solo dentro de las carpetas de medios)
 *   PUT  /pc/catalog  (JSON {games})                    la lista del catalogo del PC, para pedir de ella (ver PcRequests)
 *   GET  /pc/requests                                   lo pedido de ese catalogo que aun no esta aqui
 *   PUT  /ludolog/media?path=media/<consola>/<tipo>/<archivo>   una imagen de arte desde el PC
 *   GET  /ludolog/file?path=...                         uno de ellos (solo lectura; un cuaderno, por copia coherente)
 *   GET  /log?since=<ms>                                lo apuntado en la actividad desde entonces (ver LinkLog)
 *   GET  /meta/edits?since=<seq>                        correcciones de juegos y consolas desde entonces (ver MetaEdits)
 *   POST /meta/edits  (JSON {entries})                  correcciones de otra consola
 *   POST /meta/edit  (JSON {kind, system, path, field, value, t[, keys]})   una correccion del PC (path: el juego aqui)
 *   GET  /peers                                         (solo devices) con que devices esta emparejado
 *   POST /pair/introduce?id=&name=&host=&port=[&token=]  (solo devices) otro device presentado por este (ver Introduce)
 *   POST /pair/introduce/complete?id=&token=            (solo devices) la clave del presentado
 *
 * /info lleva ademas "ludolog": si esta instalado, su version y su aspecto (tema, luz, acento).
 */
class HttpServer(
    private val ctx: Context,
    private val port: Int,
    private val deviceId: String,
    private val hooks: TransferHooks,
) {
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(6)
    @Volatile private var running = false
    /** Las conexiones abiertas: al parar se cierran, para que una subida a medias no siga sola. */
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port), 16)
        server = s
        running = true
        thread(name = "link-http", isDaemon = true) {
            while (running) {
                val c = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                open += c
                try {
                    pool.execute {
                        try {
                            handle(c)
                        } catch (_: Exception) {
                            // una conexion rota no tumba el servidor
                        } finally {
                            open -= c
                            try { c.close() } catch (_: IOException) {}
                        }
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // Llego justo al parar: se cierra, sin tumbar el hilo.
                    open -= c
                    try { c.close() } catch (_: IOException) {}
                }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        for (c in open.toList()) try { c.close() } catch (_: IOException) {}
        pool.shutdownNow()
    }

    // --------------------------------------------------------------- HTTP

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val remote: String,
    )

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("header too long")
        }
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun handle(sock: Socket) {
        // Corto mientras llegan las cabeceras: quien las mande gota a gota no se queda con un hilo.
        sock.soTimeout = 15_000
        val inp = BufferedInputStream(sock.getInputStream(), 256 * 1024)
        val out = BufferedOutputStream(sock.getOutputStream())
        val line = readLine(inp) ?: return
        val parts = line.split(' ')
        if (parts.size < 2) return respond(out, 400, err("malformed request"))

        // Con tope, en numero y en total: esto se lee antes de mirar el token.
        val headers = mutableMapOf<String, String>()
        var count = 0
        var bytes = 0
        while (true) {
            val h = readLine(inp) ?: break
            if (h.isEmpty()) break
            bytes += h.length
            if (++count > 100 || bytes > 32_768) return respond(out, 431, err("headers too large"))
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        sock.soTimeout = 60_000
        val target = parts[1]
        val req = Request(
            method = parts[0].uppercase(),
            path = target.substringBefore('?'),
            query = if ('?' in target) parseQuery(target.substringAfter('?')) else emptyMap(),
            headers = headers,
            body = inp,
            remote = sock.inetAddress?.hostAddress ?: "",
        )
        route(req, out)
        out.flush()
    }

    private fun route(r: Request, out: OutputStream) {
        val token = r.headers["authorization"]?.removePrefix("Bearer")?.trim()
        val pc = r.query["pc"]?.take(40)?.ifBlank { null } ?: r.remote
        // Quien llama: otra consola (con el token que le dimos, o emparejandose) o un PC.
        val caller = Peers.byBackToken(ctx, token)
        val fromDevice = if (token.isNullOrEmpty()) r.query["device"] == "1" || r.query["peer_id"] != null else caller != null
        caller?.let {
            PeerState.seen(it.id)
            // Esta sincronizando partidas con este: lo que se sabia de lo suyo puede cambiar.
            if (r.path.startsWith("/saves/") && r.method != "GET") PeerState.invalidate(it.id)
        }
        // Lo que el PC pregunta solo (si la consola sigue ahi, el catalogo y sus pedidos, CatalogRequests)
        // no cuenta como uso: con la app del PC abierta todo el dia, PC Link no se apagaria nunca.
        val background = r.path == "/ping" || r.path == "/pc/requests" || r.path == "/pc/catalog" || r.path == "/log"
        hooks.activity(fromPc = !fromDevice && !background)
        val closed = when {
            r.path == "/ping" -> null
            fromDevice && !Prefs.syncDevices(ctx) -> "Sharing is off on ${Prefs.deviceName(ctx)}"
            !fromDevice && !Prefs.pcLink(ctx) -> "PC Link is off on ${Prefs.deviceName(ctx)}"
            else -> null
        }
        when {
            r.method == "GET" && r.path == "/ping" -> respond(out, 200, JSONObject()
                .put("app", Protocol.APP).put("version", BuildInfo.VERSION).put("id", deviceId)
                .put("name", Prefs.deviceName(ctx)).put("paired", Pairing.isValid(ctx, token))
                .put("pcLink", Prefs.pcLink(ctx))
                .also { j -> pingProof(r)?.let { j.put("proof", it) } })

            closed != null -> respond(out, 403, err(closed))

            r.method == "POST" && r.path == "/pair/request" -> {
                Pairing.request(ctx, pc)
                respond(out, 200, JSONObject().put("ok", true))
            }

            r.method == "POST" && r.path == "/pair/confirm" -> {
                val t = Pairing.confirm(ctx, r.query["code"] ?: "", pc)
                if (t != null) {
                    // Otra consola (no un PC): nos da tambien un token suyo, para llamarla nosotros
                    // a ella cuando juguemos aqui. Ver Peers y CompanionShare.
                    // Solo un id con forma de id: se usa como carpeta (Saves.baseFile).
                    val peerId = r.query["peer_id"]?.takeIf { it.matches(SAFE_ID) }
                    val back = r.query["back"]
                    if (!peerId.isNullOrEmpty() && !back.isNullOrEmpty()) {
                        Peers.save(ctx, Peer(peerId, pc, r.remote, r.query["peer_port"]?.toIntOrNull() ?: Protocol.HTTP_PORT, back, t))
                        LinkService.ensure(ctx)
                        LinkState.addLog("Paired with $pc", "pairing")
                    }
                    respond(out, 200, JSONObject().put("token", t).put("id", deviceId))
                } else respond(out, 403, err("wrong or expired code"))
            }

            !Pairing.isValid(ctx, token) -> respond(out, 401, err("not paired"))

            // Otra consola emparejada solo llega a lo que las consolas se piden entre si (revision de
            // seguridad, 07-10-2026): antes su token servia para todo lo del PC, de borrar ROMs a
            // restaurar un respaldo o cambiar los ajustes de Ludolog. Ver [peerMayAsk].
            caller != null && !peerMayAsk(r) -> respond(out, 403, err("not available to other devices"))

            // Emparejar entre si a los devices de un device (Introduce): solo lo pide un device emparejado.
            r.path == "/peers" || r.path.startsWith("/pair/introduce") -> {
                val from = caller ?: return respond(out, 403, err("only a paired device can ask this"))
                when {
                    r.method == "GET" && r.path == "/peers" ->
                        respond(out, 200, JSONObject().put("peers", org.json.JSONArray(Peers.all(ctx).map { it.id })))
                    r.method == "POST" && r.path == "/pair/introduce" -> {
                        val id = r.query["id"]?.takeIf { it.matches(SAFE_ID) } ?: return respond(out, 400, err("bad id"))
                        if (id == deviceId) return respond(out, 400, err("that's this device"))
                        val port = r.query["port"]?.toIntOrNull() ?: Protocol.HTTP_PORT
                        val t = runCatching {
                            Introduce.accept(ctx, from, id, r.query["name"]?.take(40).orEmpty().ifBlank { "Device" },
                                r.query["host"].orEmpty(), port, r.query["token"]?.ifBlank { null })
                        }.getOrElse { return respond(out, 409, err(it.message ?: "refused")) }
                        respond(out, 200, JSONObject().put("token", t))
                    }
                    r.method == "POST" && r.path == "/pair/introduce/complete" -> {
                        val ok = Introduce.complete(ctx, from, r.query["id"].orEmpty(), r.query["token"].orEmpty())
                        if (ok) respond(out, 200, JSONObject().put("ok", true)) else respond(out, 404, err("not introduced"))
                    }
                    else -> respond(out, 404, err("not found"))
                }
            }

            r.method == "GET" && r.path == "/meta/edits" ->
                respond(out, 200, MetaEdits.since(ctx, r.query["since"]?.toLongOrNull() ?: 0L))

            r.method == "POST" && r.path == "/meta/edits" -> {
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8_000_000 }
                    ?: return respond(out, 411, err("missing body"))
                val (buf, got) = readBody(r, n)
                val a = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)).getJSONArray("entries") }.getOrNull()
                    ?: return respond(out, 400, err("bad body"))
                val list = (0 until a.length()).mapNotNull { runCatching { MetaEdits.Entry.of(a.getJSONObject(it)) }.getOrNull() }
                respond(out, 200, JSONObject().put("changed", MetaEdits.merge(ctx, list)))
            }

            r.method == "POST" && r.path == "/meta/edit" -> {
                // En el cuerpo: una descripcion larga no cabe en la linea de la peticion.
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..200_000 }
                    ?: return respond(out, 411, err("missing body"))
                val (buf, got) = readBody(r, n)
                val o = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)) }.getOrNull() ?: return respond(out, 400, err("bad body"))
                val kind = o.optString("kind").takeIf { it == "game" || it == "sys" } ?: return respond(out, 400, err("bad kind"))
                val field = o.optString("field").takeIf { it in setOf("name", "desc", "genre") } ?: return respond(out, 400, err("bad field"))
                val system = o.optString("system").takeIf { it.matches(SAFE_ID) } ?: return respond(out, 400, err("bad system"))
                val path = o.optString("path")
                if (kind == "game" && path.isEmpty()) return respond(out, 400, err("missing path"))
                val changed = MetaEdits.fromPc(ctx, Pairing.pcFor(ctx, token), kind, system, path, field,
                    o.optString("value"), o.optLong("t").takeIf { it > 0 } ?: System.currentTimeMillis(), o.optString("keys"))
                // Y a las otras consolas, en un rato (se juntan varias seguidas).
                if (changed) CompanionShare.syncAll(ctx, "game info from PC")
                respond(out, 200, JSONObject().put("changed", changed))
            }

            r.method == "GET" && r.path == "/log" -> {
                val since = r.query["since"]?.toLongOrNull() ?: 0L
                respond(out, 200, JSONObject().put("now", System.currentTimeMillis())
                    .put("entries", org.json.JSONArray(LinkLog.since(since).map { it.json() })))
            }

            r.method == "GET" && r.path == "/info" -> respond(out, 200, RomStore.info(ctx).put("ludolog", Ludolog.info(ctx)))

            r.method == "GET" && r.path == "/roms" -> {
                val root = RomStore.root(ctx)
                    ?: return respond(out, 409, err("the device has no ROM folder"))
                // Solo si la lista cambio desde la ultima vez que la pidio (ETag): si no, ni se apunta.
                if (respondListing(r, out, JSONObject().put("root", root.absolutePath).put("systems", RomStore.list(root))))
                    LinkState.addLog("${Pairing.pcFor(ctx, token)} read the ROM list", "roms")
            }

            // Mismas comprobaciones que la subida, sin cuerpo. El PC pregunta
            // antes de enviar: si la consola rechazara a mitad de un envio, el
            // PC veria una conexion cortada en vez del motivo.
            r.method == "GET" && r.path.startsWith("/check/") -> {
                val length = r.query["size"]?.toLongOrNull()
                    ?: return respond(out, 400, err("missing size"))
                when (val v = validate(r.path.removePrefix("/check/"), length, r.query["overwrite"] == "1")) {
                    is Rejected -> respond(out, v.code, err(v.message))
                    is Accepted -> respond(out, 200, JSONObject().put("ok", true)
                        .put("free", v.dest.parentFile?.usableSpace ?: 0)
                        // Lo que ya llego de ese mismo envio (mismo [id]): el PC sigue desde ahi.
                        .put("partial", partialOf(v.dest, r.query["id"])))
                }
            }

            r.method == "GET" && r.path == "/systems" -> {
                val root = RomStore.root(ctx)
                    ?: return respond(out, 409, err("the device has no ROM folder"))
                respondListing(r, out, JSONObject().put("systems", RomStore.systems(root)))
            }

            r.method == "PUT" && r.path.startsWith("/roms/") -> upload(r, out)

            r.method == "GET" && r.path.startsWith("/roms/") -> download(r, out)

            r.method == "POST" && r.path == "/rename" -> rename(r, out, Pairing.pcFor(ctx, token))

            r.method == "DELETE" && r.path.startsWith("/roms/") -> delete(r, out, Pairing.pcFor(ctx, token))

            r.method == "GET" && r.path == "/ludolog/file" -> ludologFile(r, out)

            r.method == "POST" && r.path == "/ludolog/config" -> {
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..1_000_000 }
                    ?: return respond(out, 411, err("missing body"))
                val (buf, got) = readBody(r, n)
                val changes = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)) }.getOrNull()
                    ?: return respond(out, 400, err("invalid JSON"))
                when (Ludolog.queueConfig(ctx, changes)) {
                    null -> respond(out, 404, err("Ludolog isn't installed on this device"))
                    else -> {
                        LinkState.addLog("${Pairing.pcFor(ctx, token)} sent new settings for Ludolog", "settings")
                        respond(out, 200, JSONObject().put("ok", true))
                    }
                }
            }

            r.method == "GET" && r.path == "/ludolog/art" -> respondListing(r, out, Ludolog.artIndex(ctx))

            // Los archivos de medios, para los huerfanos y el arte entre consolas. Ver Ludolog.mediaList.
            r.method == "GET" && r.path == "/ludolog/media/list" -> respondListing(r, out, JSONObject().put("files", Ludolog.mediaList(ctx)))
            r.method == "GET" && r.path == "/ludolog/media/file" -> {
                val f = Ludolog.mediaFile(ctx, r.query["path"] ?: "") ?: return respond(out, 404, err("not found"))
                sendFile(out, f)
            }
            r.method == "POST" && r.path == "/ludolog/media/delete" -> {
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4_000_000 }
                    ?: return respond(out, 411, err("missing body"))
                val (buf, got) = readBody(r, n)
                val paths = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)).getJSONArray("paths") }.getOrNull()
                    ?: return respond(out, 400, err("invalid JSON"))
                val bases = Ludolog.mediaBases(ctx)
                val gone = (0 until paths.length()).mapNotNull { Ludolog.mediaFile(bases, paths.getString(it)) }.filter { it.delete() }
                if (gone.isNotEmpty()) {
                    LinkState.addLog("${Pairing.pcFor(ctx, token)} removed ${gone.size} orphan art and video files", "art")
                    Ludolog.mediaChanged(ctx, gone)
                }
                respond(out, 200, JSONObject().put("deleted", org.json.JSONArray(gone.map { it.absolutePath })))
            }

            // El arte o el video de un juego, para verlo en el PC: ?systems=a,b&stem=<nombre>&kind=art|video
            r.method == "GET" && r.path == "/ludolog/preview" -> {
                val systems = (r.query["systems"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val f = Ludolog.findMedia(ctx, systems, r.query["stem"] ?: "", video = r.query["kind"] == "video")
                    ?: return respond(out, 404, err("not found"))
                sendFile(out, f, "X-Name: ${java.net.URLEncoder.encode(f.name, "UTF-8")}")
            }

            r.method == "PUT" && r.path == "/ludolog/media" -> {
                val dest = Ludolog.mediaTarget(ctx, r.query["path"] ?: "") ?: return respond(out, 400, err("invalid art path"))
                val video = dest.extension.equals("mp4", ignoreCase = true)
                if (!receive(r, out, dest, if (video) 2L shl 30 else 64L shl 20, if (video) "video" else "art")) return
                LinkState.addLog("${Pairing.pcFor(ctx, token)} added ${if (video) "a video" else "art"}: ${dest.name}", "art")
                Ludolog.mediaChanged(ctx, listOf(dest))
                respond(out, 200, JSONObject().put("ok", true))
            }

            // Quitar el arte o el video de un juego: ?systems=a,b&stem=<nombre sin extension>&kind=art|video
            r.method == "DELETE" && r.path == "/ludolog/media" -> {
                val kind = r.query["kind"]
                if (kind != "art" && kind != "video") return respond(out, 400, err("kind must be art or video"))
                val systems = (r.query["systems"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val gone = Ludolog.removeMedia(ctx, systems, r.query["stem"] ?: "", video = kind == "video")
                if (gone.isNotEmpty()) {
                    LinkState.addLog("${Pairing.pcFor(ctx, token)} removed ${if (kind == "video") "a video" else "art"}: ${gone.joinToString { it.name }}", "art")
                    Ludolog.mediaChanged(ctx, gone)
                }
                respond(out, 200, JSONObject().put("deleted", org.json.JSONArray(gone.map { it.name })))
            }

            // El cuaderno de otra consola, para el Companion de esta. Nunca el suyo. Ver Ludolog.logbookTarget.
            r.method == "PUT" && r.path == "/ludolog/companion" -> {
                val name = r.query["name"] ?: ""
                if (Ludolog.isOwnLogbook(ctx, name)) return respond(out, 409, err("that is this device's own logbook"))
                val dest = Ludolog.logbookTarget(ctx, name) ?: return respond(out, 400, err("invalid logbook name"))
                // Con punto delante mientras llega: Ludolog no mira los que empiezan por punto.
                val incoming = File(dest.parentFile, ".in-" + dest.name)
                if (!receive(r, out, incoming, 1L shl 30, "logbook")) return
                Ludolog.installLogbook(ctx, name, incoming)?.let { why -> incoming.delete(); return respond(out, 400, err(why)) }
                LinkState.addLog("${Pairing.pcFor(ctx, token)} shared a logbook: ${dest.name}", "companion")
                respond(out, 200, JSONObject().put("ok", true))
            }

            // ---- Pedidos al catalogo del PC (ver PcRequests): su lista, que deja el PC, y lo que se pidio aqui.
            r.method == "PUT" && r.path == "/pc/catalog" -> {
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..16_000_000 }
                    ?: return respond(out, 411, err("missing body"))
                val (buf, got) = readBody(r, n)
                val games = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)).getJSONArray("games") }.getOrNull()
                    ?: return respond(out, 400, err("invalid JSON"))
                PcRequests.setCatalog(Pairing.pcFor(ctx, token), games)
                respond(out, 200, JSONObject().put("ok", true))
            }
            r.method == "GET" && r.path == "/pc/requests" ->
                respond(out, 200, JSONObject().put("requests", org.json.JSONArray(PcRequests.prune(ctx).map { it.json() })))

            // ---- Partidas guardadas (ver Saves y SaveSync): de otra consola emparejada, o del PC.
            r.path.startsWith("/saves/") -> savesRoute(r, out, token)

            // La miniatura de la caratula de un juego de aqui, para el Companion de otra consola. Ver CompanionCovers.
            r.method == "GET" && r.path == "/ludolog/companion/cover" -> {
                val bytes = CompanionCovers.thumbnail(ctx, r.query["system"] ?: "", r.query["file"] ?: "", r.query["name"] ?: "")
                    ?: return respond(out, 404, err("no cover"))
                val head = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII)); out.write(bytes); out.flush()
            }

            // Los cuadernos de aqui y lo lejos que llega cada uno: para compartir el Companion. Ver CompanionShare.
            r.method == "GET" && r.path == "/ludolog/companion" -> respond(out, 200, Ludolog.companionList(ctx))

            // Restaurar: los archivos esperan en link/restore/ hasta el "commit". Ver Ludolog.restoreTarget.
            r.method == "PUT" && r.path == "/ludolog/restore" -> {
                val dest = Ludolog.restoreTarget(ctx, r.query["path"] ?: "") ?: return respond(out, 400, err("invalid backup path"))
                if (!receive(r, out, dest, 2L shl 30, "file", min = 0)) return
                respond(out, 200, JSONObject().put("ok", true))
            }

            r.method == "DELETE" && r.path == "/ludolog/restore" ->
                if (Ludolog.clearRestore(ctx)) respond(out, 200, JSONObject().put("ok", true))
                else respond(out, 404, err("Ludolog isn't installed on this device"))

            r.method == "POST" && r.path == "/ludolog/restore" -> {
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 0..1_000_000 } ?: 0
                val (buf, got) = readBody(r, n)
                val config = if (got == 0) null else runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)) }.getOrNull()
                    ?: return respond(out, 400, err("invalid JSON"))
                when (Ludolog.commitRestore(ctx, config)) {
                    null -> respond(out, 404, err("Ludolog isn't installed on this device"))
                    else -> {
                        LinkState.addLog("${Pairing.pcFor(ctx, token)} restored a backup for Ludolog", "backup")
                        respond(out, 200, JSONObject().put("ok", true))
                    }
                }
            }

            r.method == "GET" && r.path == "/ludolog/manifest" -> {
                val dir = Ludolog.dataDir(ctx) ?: return respond(out, 404, err("Ludolog isn't installed on this device"))
                respond(out, 200, JSONObject().put("files", Ludolog.manifest(dir)))
            }

            else -> respond(out, 404, err("not found"))
        }
    }

    /** Un archivo como respuesta. [extra]: su cabecera propia (por defecto, la fecha del archivo). */
    private fun sendFile(out: OutputStream, f: File, extra: String = "X-Mtime: ${f.lastModified()}") {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: ${f.length()}\r\n$extra\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        f.inputStream().use { it.copyTo(out, 1 shl 16) }
        out.flush()
    }

    private fun savesRoute(r: Request, out: OutputStream, token: String?) {
        val who = Pairing.pcFor(ctx, token)
        if (r.path == "/saves/backups" && r.method == "GET") {
            val files = org.json.JSONArray()
            for (e in Saves.all(ctx)) for (b in Saves.backupsOf(ctx, e.pkg)) {
                files.put(JSONObject().put("pkg", e.pkg).put("app", Saves.appName(ctx, e.pkg)).put("name", b.name)
                    .put("size", b.length()).put("mtime", b.lastModified()))
            }
            return respond(out, 200, JSONObject().put("files", files))
        }
        // Un paquete de Android y nada mas: va en rutas (los respaldos, la base).
        val pkg = r.query["pkg"]?.takeIf { it.matches(SAFE_ID) } ?: return respond(out, 400, err("missing pkg"))
        if (r.path == "/saves/backup" && r.method == "GET") {
            val name = r.query["name"] ?: ""
            if (name.isEmpty() || '/' in name || name.startsWith(".")) return respond(out, 400, err("invalid name"))
            val f = Saves.backupsOf(ctx, pkg).firstOrNull { it.name == name } ?: return respond(out, 404, err("not found"))
            return sendFile(out, f)
        }
        val e = Saves.get(ctx, pkg)?.takeIf { it.configured }
        when {
            r.method == "GET" && r.path == "/saves/state" -> {
                // Para decidir sin fechas de archivo: cuando se jugo aqui cada juego, y desde cuando
                // (en el reloj de aqui) esta consola y la que llama tienen la base comun.
                val caller = Peers.byBackToken(ctx, token)
                respond(out, 200, JSONObject()
                    .put("configured", e != null).put("supported", e == null || Saves.supported(e))
                    .put("played", e?.played ?: 0L).put("inUse", Saves.inUse(ctx, pkg))
                    .put("games", JSONObject(Saves.gamePlays(ctx, pkg) as Map<*, *>))
                    .put("syncedAt", caller?.let { Saves.base(it.id, pkg).first } ?: 0L))
            }
            e == null -> respond(out, 409, err("no saves folder set for that emulator here"))
            r.method == "GET" && r.path == "/saves/manifest" -> respond(out, 200, JSONObject().put("files", Saves.manifest(e)))
            r.method == "GET" && r.path == "/saves/file" -> {
                val f = Saves.fileIn(e, r.query["path"] ?: "")?.takeIf { it.isFile } ?: return respond(out, 404, err("not found"))
                sendFile(out, f)
            }
            r.method == "POST" && r.path == "/saves/begin" -> {
                if (Saves.inUse(ctx, pkg)) return respond(out, 409, err("a game is open in that emulator here"))
                // Antes de que llegue nada: como estaba, por si acaso. [why]: before-sync (otra consola)
                // o before-restore (el PC devuelve un respaldo).
                val why = r.query["why"]?.takeIf { it.matches(Regex("[a-z-]{1,24}")) } ?: "before-sync"
                // Sin respaldo no se escribe: si no se pudo hacer, se dice y la otra no manda nada.
                try {
                    Saves.backup(ctx, e, why)
                } catch (x: IOException) {
                    return respond(out, 507, err("couldn't back up the saves here first: ${x.message}"))
                }
                writing[writeKey(token, pkg)] = System.currentTimeMillis()
                respond(out, 200, JSONObject().put("ok", true))
            }
            r.method == "PUT" && r.path == "/saves/file" -> {
                // Solo dentro de una escritura abierta con /saves/begin por quien llama: es lo que hace
                // el respaldo de antes. Quien se lo saltaba pisaba partidas sin copia (revision de
                // seguridad, 07-10-2026). Y nunca con un juego abierto en ese emulador.
                val key = writeKey(token, pkg)
                val since = writing[key]
                if (since == null || System.currentTimeMillis() - since > WRITE_WINDOW_MS)
                    return respond(out, 409, err("start with /saves/begin"))
                if (Saves.inUse(ctx, pkg)) return respond(out, 409, err("a game is open in that emulator here"))
                writing[key] = System.currentTimeMillis()
                val dest = Saves.fileIn(e, r.query["path"] ?: "") ?: return respond(out, 400, err("invalid path"))
                if (!receive(r, out, dest, 4L shl 30, "save", min = 0)) return
                respond(out, 200, JSONObject().put("ok", true))
            }
            r.method == "POST" && r.path == "/saves/end" -> {
                writing.remove(writeKey(token, pkg))
                val played = r.query["played"]?.toLongOrNull() ?: 0L
                // Lo que quedo igual en las dos: la base con la consola que llama (la que tiene el token que le dimos).
                val n = r.headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..8_000_000 } ?: 0
                if (n > 0) {
                    val (buf, got) = readBody(r, n)
                    val files = runCatching { JSONObject(String(buf, 0, got, Charsets.UTF_8)).optJSONArray("files") }.getOrNull()
                    val peer = Peers.byBackToken(ctx, token)
                    if (files != null && peer != null) {
                        Saves.setBase(peer.id, pkg, files)
                        // Lo que ya es igual deja de estar en conflicto con esa consola.
                        Saves.dropConflicts(ctx, peer.id, pkg, (0 until files.length()).map { files.getJSONObject(it).getString("path") }.toSet())
                    }
                }
                Saves.update(ctx, pkg) { it.copy(played = maxOf(it.played, played), lastSync = System.currentTimeMillis(),
                    note = "from $who") }
                LinkState.addLog("$who synced saves of ${Saves.appName(ctx, pkg)}", "saves")
                respond(out, 200, JSONObject().put("ok", true))
            }
            else -> respond(out, 404, err("not found"))
        }
    }

    /**
     * El cuerpo de [r] a [dest], por un .part que solo se renombra entero. Falso si ya se contesto
     * con un error.
     */
    private fun receive(r: Request, out: OutputStream, dest: File, max: Long, what: String, min: Long = 1): Boolean {
        val n = r.headers["content-length"]?.toLongOrNull()?.takeIf { it in min..max }
            ?: run { respond(out, 411, err("missing body")); return false }
        dest.parentFile?.mkdirs()
        // Uno por envio: dos que lleguen a la vez al mismo archivo no escriben en el mismo temporal.
        val tmp = File(dest.parentFile, "." + dest.name + "." + System.nanoTime() + Protocol.PART_SUFFIX)
        // Uno grande (un video, un respaldo) cuenta como transferencia: la consola no se duerme ni
        // Link se apaga por inactividad a mitad.
        val big = n > (8L shl 20)
        if (big) hooks.begin(dest.name, n, outgoing = false)
        var ok = false
        try {
            tmp.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var left = n
                var last = 0L
                while (left > 0) {
                    val k = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (k < 0) break
                    o.write(buf, 0, k); left -= k
                    if (big && System.currentTimeMillis() - last > 500) { last = System.currentTimeMillis(); hooks.progress(n - left) }
                }
                if (left > 0) throw IOException("connection cut")
            }
            // renameTo reemplaza: borrar antes dejaba sin nada si el renombre fallaba.
            if (!tmp.renameTo(dest)) throw IOException("couldn't save the $what")
            ok = true
        } catch (e: IOException) {
            tmp.delete(); respond(out, 500, err(e.message ?: "couldn't save the $what")); return false
        } finally {
            if (big) hooks.end(dest.name, ok, outgoing = false)
        }
        return true
    }

    // -------------------------------------------------------------- subida

    private sealed interface Verdict
    private class Accepted(val dest: File) : Verdict
    private class Rejected(val code: Int, val message: String) : Verdict

    private fun validate(rawPath: String, length: Long, overwrite: Boolean): Verdict {
        val root = RomStore.root(ctx) ?: return Rejected(409, "the device has no ROM folder")
        val segs = rawPath.split('/').map { URLDecoder.decode(it, "UTF-8") }
        if (segs.size < 2) return Rejected(400, "incomplete path")
        val dest = RomStore.resolve(root, segs[0], segs.drop(1).joinToString("/"))
            ?: return Rejected(400, "invalid path")
        if (dest.exists() && !overwrite) return Rejected(409, "already exists on the device")
        val dir = dest.parentFile ?: return Rejected(400, "invalid path")
        // Si la carpeta aun no existe, el espacio se mira en la mas cercana que si.
        var probe: File = dir
        while (!probe.exists()) probe = probe.parentFile ?: return Rejected(400, "invalid path")
        val margin = 64L * 1024 * 1024
        if (length + margin > probe.usableSpace) {
            val missing = (length + margin - probe.usableSpace) / (1024 * 1024)
            return Rejected(507, "not enough space: $missing MB more needed")
        }
        return Accepted(dest)
    }

    /** El temporal de una subida a [dest], y la marca de que envio es (para retomar solo ese). */
    private fun partOf(dest: File) = File(dest.parentFile, ".${dest.name}${RomStore.PART_SUFFIX}")
    private fun partIdOf(dest: File) = File(dest.parentFile, ".${dest.name}${RomStore.PART_SUFFIX}.id")

    /** Cuanto hay ya de [dest] de un envio cortado con ese [id]; 0 si nada (o es de otro envio). */
    private fun partialOf(dest: File, id: String?): Long {
        if (id.isNullOrEmpty()) return 0
        val tmp = partOf(dest)
        val mark = partIdOf(dest)
        return if (tmp.isFile && mark.isFile && runCatching { mark.readText() }.getOrNull() == id) tmp.length() else 0
    }

    /**
     * Un ROM del PC (o de otro device por el PC). Con `id` (un PC que sabe retomar), lo recibido se
     * guarda si se corta, y con `offset` se sigue desde ahi: el cuerpo es solo lo que falta. Sin `id`,
     * como antes: un corte borra el temporal.
     */
    private fun upload(r: Request, out: OutputStream) {
        val length = r.headers["content-length"]?.toLongOrNull()
            ?: return respond(out, 411, err("missing Content-Length"))
        val id = r.query["id"]?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
        val offset = r.query["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0L
        val dest = when (val v = validate(r.path.removePrefix("/roms/"), length, r.query["overwrite"] == "1")) {
            is Rejected -> return respond(out, v.code, err(v.message))
            is Accepted -> v.dest
        }
        val dir = dest.parentFile ?: return respond(out, 400, err("invalid path"))
        dir.mkdirs()

        // Se escribe con nombre oculto y se renombra al final: el frontend nunca
        // ve un juego a medias, y un corte no deja basura con nombre de ROM.
        val tmp = partOf(dest)
        val mark = partIdOf(dest)
        // Seguir solo si lo que hay es de este mismo envio y llega justo hasta [offset].
        if (offset > 0 && (id == null || partialOf(dest, id) != offset))
            return respond(out, 409, err("can't resume: start again"))
        if (offset == 0L) { tmp.delete(); if (id != null) runCatching { mark.writeText(id) } else mark.delete() }
        val total = offset + length
        // Un ROM vacio no es un ROM: es un archivo que no se pudo leer en el PC (un disco que se
        // saco). Aceptarlo pisaria el bueno si venia con "reemplazar".
        if (total == 0L) return respond(out, 400, err("empty file"))
        hooks.begin(dest.name, total, outgoing = false)
        var ok = false
        try {
            FileOutputStream(tmp, offset > 0).use { fo ->
                val buf = ByteArray(1 shl 20)
                var left = length
                while (left > 0) {
                    val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("the PC closed the connection")
                    fo.write(buf, 0, n)
                    left -= n
                    hooks.progress(total - left)
                }
                fo.fd.sync()
            }
            mark.delete()
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("couldn't rename")
            r.query["mtime"]?.toLongOrNull()?.let { dest.setLastModified(it) }
            ok = true
            Ludolog.libraryChanged(ctx)
            respond(out, 200, JSONObject().put("ok", true).put("size", dest.length()))
        } catch (e: IOException) {
            // Un PC que sabe retomar (con id): lo recibido se queda para seguir. Si no, fuera.
            if (id == null) { tmp.delete(); mark.delete() }
            try { respond(out, 500, err(e.message ?: "write error")) } catch (_: IOException) {}
        } finally {
            hooks.end(dest.name, ok, outgoing = false)
        }
    }

    // ----------------------------------------------------------- descarga

    private fun download(r: Request, out: OutputStream) {
        val root = RomStore.root(ctx)
            ?: return respond(out, 409, err("the device has no ROM folder"))
        val segs = r.path.removePrefix("/roms/").split('/').map { URLDecoder.decode(it, "UTF-8") }
        if (segs.size < 2) return respond(out, 400, err("incomplete path"))
        val f = RomStore.resolve(root, segs[0], segs.drop(1).joinToString("/"))
            ?: return respond(out, 400, err("invalid path"))
        if (!f.isFile) return respond(out, 404, err("not found"))

        // Range "bytes=N-": el PC puede retomar una descarga cortada.
        val size = f.length()
        val start = r.headers["range"]?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.takeIf { it in 0 until size } ?: 0L
        val partial = start > 0
        val head = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: application/octet-stream\r\n")
            .append("Content-Length: ${size - start}\r\n")
            .apply { if (partial) append("Content-Range: bytes $start-${size - 1}/$size\r\n") }
            .append("X-Mtime: ${f.lastModified()}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))

        hooks.begin(f.name, size, outgoing = true)
        var ok = false
        try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(1 shl 20)
                var sent = start
                while (true) {
                    val n = raf.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    sent += n
                    hooks.progress(sent)
                }
            }
            out.flush()
            ok = true
        } catch (_: IOException) {
            // el PC cerro la conexion: lo reintentara con Range
        } finally {
            hooks.end(f.name, ok, outgoing = true)
        }
    }

    // ------------------------------------------------------------ Ludolog

    /**
     * Un archivo de la carpeta de datos de Ludolog, solo lectura: la letra del tema, los cuadernos
     * para el Companion, todo para un respaldo. Nada fuera de esa carpeta.
     */
    private fun ludologFile(r: Request, out: OutputStream) {
        val dir = Ludolog.dataDir(ctx) ?: return respond(out, 404, err("Ludolog isn't installed on this device"))
        val rel = r.query["path"] ?: return respond(out, 400, err("missing path"))
        val parts = rel.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return respond(out, 403, err("path not allowed"))
        val f = File(dir, rel)
        if (!f.canonicalPath.startsWith(dir.canonicalPath + File.separator) || !f.isFile) {
            return respond(out, 404, err("not found"))
        }
        // Un cuaderno, por copia coherente: Ludolog puede estar apuntando una partida ahora mismo.
        val send = if (rel.startsWith("companion/") && rel.endsWith(".db")) {
            try { Ludolog.snapshot(ctx, f) } catch (e: Exception) {
                return respond(out, 503, err("the logbook is busy, try again: ${e.message}"))
            }
        } else f
        try {
            // La copia, con la fecha del original: es como el PC sabe despues si cambio.
            sendFile(out, send, "X-Mtime: ${f.lastModified()}")
        } finally {
            if (send !== f) send.delete()
        }
    }

    // ---------------------------------------------------------- renombrar

    private fun rename(r: Request, out: OutputStream, pc: String) {
        val root = RomStore.root(ctx)
            ?: return respond(out, 409, err("the device has no ROM folder"))
        val system = r.query["system"] ?: return respond(out, 400, err("missing console"))
        val newName = (r.query["to"] ?: "").substringAfterLast('/')
        Protocol.nameProblem(newName)?.let { return respond(out, 400, err("invalid name: $it")) }
        val src = RomStore.resolve(root, system, r.query["from"] ?: "")
            ?: return respond(out, 400, err("invalid source path"))
        val dst = RomStore.resolve(root, system, r.query["to"] ?: "")
            ?: return respond(out, 400, err("invalid new name"))
        if (!src.isFile) return respond(out, 404, err("not found"))
        if (src.parentFile?.canonicalPath != dst.parentFile?.canonicalPath) {
            return respond(out, 400, err("files can only be renamed within the same folder"))
        }
        val sameFile = src.name.equals(dst.name, ignoreCase = true)
        if (dst.exists() && !sameFile) return respond(out, 409, err("a file with that name already exists"))

        try {
            val renamed = mutableListOf(dst.name)
            val oldStem = src.nameWithoutExtension
            move(src, dst)
            // Lo de ese juego en Ludolog (favorito, veces jugado, ficha, arte) cuelga de la ruta.
            Ludolog.moved(ctx, listOf(src.absolutePath), listOf(dst.absolutePath))
            // El .sbi se llama igual que el juego: si no lo sigue, el emulador lo pierde.
            if (!src.extension.equals("sbi", ignoreCase = true)) {
                src.parentFile?.listFiles()?.firstOrNull {
                    it.isFile && it.extension.equals("sbi", ignoreCase = true) &&
                        it.nameWithoutExtension.equals(oldStem, ignoreCase = true)
                }?.let { sbi ->
                    val target = File(sbi.parentFile, dst.nameWithoutExtension + "." + sbi.extension)
                    // En un sistema de archivos sin mayusculas, "existe" puede ser el mismo .sbi.
                    if (sbi.name != target.name &&
                        (!target.exists() || target.name.equals(sbi.name, ignoreCase = true))) {
                        move(sbi, target)
                        renamed += target.name
                    }
                }
            }
            LinkState.addLog("$pc renamed ${src.name} → ${dst.name}", "roms")
            respond(out, 200, JSONObject().put("ok", true).put("renamed", org.json.JSONArray(renamed)))
        } catch (e: IOException) {
            respond(out, 500, err(e.message ?: "couldn't rename"))
        }
    }

    // -------------------------------------------------------------- borrar

    private fun delete(r: Request, out: OutputStream, pc: String) {
        val root = RomStore.root(ctx)
            ?: return respond(out, 409, err("the device has no ROM folder"))
        val segs = r.path.removePrefix("/roms/").split('/').map { URLDecoder.decode(it, "UTF-8") }
        if (segs.size < 2) return respond(out, 400, err("incomplete path"))
        val f = RomStore.resolve(root, segs[0], segs.drop(1).joinToString("/"))
            ?: return respond(out, 400, err("invalid path"))
        if (!f.isFile) return respond(out, 404, err("not found"))
        if (!f.delete()) return respond(out, 500, err("couldn't delete"))

        val deleted = mutableListOf(f.name)
        // Un .sbi sin su juego no sirve de nada: se va con el.
        if (!f.extension.equals("sbi", ignoreCase = true)) {
            f.parentFile?.listFiles()?.filter {
                it.isFile && it.extension.equals("sbi", ignoreCase = true) &&
                    it.nameWithoutExtension.equals(f.nameWithoutExtension, ignoreCase = true)
            }?.forEach { if (it.delete()) deleted += it.name }
        }
        // Subcarpeta de multidisco que se queda vacia: fuera. La del sistema, nunca.
        val parent = f.parentFile
        val systemDir = File(root, segs[0])
        if (parent != null && parent.canonicalPath != systemDir.canonicalPath && parent.listFiles()?.isEmpty() == true) {
            parent.delete()
        }
        LinkState.addLog("$pc deleted ${f.name}", "roms")
        Ludolog.libraryChanged(ctx)
        respond(out, 200, JSONObject().put("ok", true).put("deleted", org.json.JSONArray(deleted)))
    }

    /** Renombrar, tambien si solo cambian mayusculas (la SD en exFAT no distingue). */
    private fun move(a: File, b: File) {
        if (a.name.equals(b.name, ignoreCase = true) && a.name != b.name) {
            val tmp = File(a.parentFile, ".romlink-rename-${System.nanoTime()}")
            if (!a.renameTo(tmp) || !tmp.renameTo(b)) throw IOException("couldn't rename")
        } else if (!a.renameTo(b)) {
            throw IOException("couldn't rename")
        }
    }

    // ---------------------------------------------------------- respuestas

    private fun err(msg: String) = JSONObject().put("error", msg)

    /** El cuerpo de [r], hasta [n] bytes: lo leido y cuanto llego (menos si se corto). */
    private fun readBody(r: Request, n: Int): Pair<ByteArray, Int> {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) { val k = r.body.read(buf, got, n - got); if (k < 0) break; got += k }
        return buf to got
    }

    /** Un id o un paquete que se puede usar como carpeta: sin `/`, y sin empezar por punto (ni `..`). */
    private val SAFE_ID = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,119}")

    /**
     * Lo que una consola emparejada puede pedir a otra: exactamente lo que piden Peers, SaveSync,
     * CompanionShare, CompanionCovers, MetaEdits, RomTransfer, LogUi e Introduce. Lo demas (subir,
     * borrar o renombrar ROMs, ajustes y respaldos de Ludolog, arte, catalogo del PC) es del PC.
     * /ping y /pair/request|confirm se atienden antes, sin token.
     */
    private fun peerMayAsk(r: Request): Boolean {
        val p = r.path
        return when (r.method) {
            "GET" -> p in PEER_GET || p.startsWith("/roms/") ||
                // De la carpeta de Ludolog, solo cuadernos del Companion: es lo unico que se piden.
                (p == "/ludolog/file" && r.query["path"].orEmpty().matches(LOGBOOK_PATH))
            "PUT" -> p == "/ludolog/companion" || p == "/saves/file"
            "POST" -> p == "/meta/edits" || p == "/saves/begin" || p == "/saves/end" || p.startsWith("/pair/introduce")
            else -> false
        }
    }

    private val PEER_GET = setOf(
        "/peers", "/log", "/roms", "/systems", "/ludolog/art", "/ludolog/media/list", "/ludolog/media/file",
        "/ludolog/companion", "/ludolog/companion/cover", "/meta/edits", "/saves/state", "/saves/manifest", "/saves/file",
    )
    private val LOGBOOK_PATH = Regex("""companion/[^/\\]+\.db""")

    /** Escrituras de partidas abiertas con /saves/begin: quien (su token) y que emulador → cuando. */
    private val writing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun writeKey(token: String?, pkg: String) = token.orEmpty() + "|" + pkg
    /** Sin noticias de una escritura en este tiempo, se da por abandonada. */
    private val WRITE_WINDOW_MS = 2 * 60 * 60_000L

    /**
     * La prueba de /ping para la consola [from]: que esta conoce la clave que comparten, sin decirla.
     * Con ella la otra comprueba que quien contesta en una direccion nueva es de verdad esta (ver
     * Peers.reach), en vez de fiarse del id, que cualquiera en la red puede repetir.
     */
    private fun pingProof(r: Request): String? {
        val from = r.query["from"]?.takeIf { it.matches(SAFE_ID) } ?: return null
        val nonce = r.query["nonce"]?.takeIf { it.matches(Regex("[0-9a-f]{16,64}")) } ?: return null
        val peer = Peers.everyone(ctx).firstOrNull { it.id == from && it.backToken.isNotEmpty() } ?: return null
        return Peers.proof(peer.backToken, nonce, deviceId)
    }

    /**
     * Una lista (ROMs, sistemas, arte) con su huella (ETag). Si quien pide ya tiene esa misma (la
     * manda en If-None-Match), contesta 304 sin cuerpo: el PC usa la que guardo. Verdadero si mando
     * la lista. La huella es un CRC32 del texto: microsegundos, nada que ver con comprimir.
     */
    private fun respondListing(r: Request, out: OutputStream, body: JSONObject): Boolean {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val tag = "\"" + java.util.zip.CRC32().apply { update(bytes) }.value.toString(16) + "-" + bytes.size + "\""
        if (r.headers["if-none-match"] == tag) {
            out.write("HTTP/1.1 304 Not Modified\r\nETag: $tag\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return false
        }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nETag: $tag\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
        return true
    }

    private fun respond(out: OutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 411 -> "Length Required"
            507 -> "Insufficient Storage"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
