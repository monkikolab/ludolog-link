package com.felp.ludologlink

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.felp.ludolog.kit.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Otras consolas con Ludolog Link, emparejadas con esta para compartir el Companion sin PC.
 *
 * El emparejamiento es el mismo que con el PC (codigo en la notificacion de la otra consola),
 * pero en los dos sentidos de una vez: al confirmar, esta le da a la otra un token suyo, para que
 * la otra tambien pueda llamarla cuando sea ella la que juegue. Ver CompanionShare.
 */
data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** El token que la otra consola nos dio: con el la llamamos. */
    val token: String,
    /** El que le dimos nosotros: se borra al olvidarla. */
    val backToken: String = "",
    val lastSync: Long = 0,
    val note: String = "",
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
        .put("token", token).put("back", backToken).put("last", lastSync).put("note", note)

    companion object {
        fun of(j: JSONObject) = Peer(j.getString("id"), j.optString("name"), j.optString("host"), j.optInt("port", Protocol.HTTP_PORT),
            j.optString("token"), j.optString("back"), j.optLong("last"), j.optString("note"))
    }
}

/** Una consola que contesto en la red. */
data class FoundConsole(val id: String, val name: String, val host: String, val port: Int)

object Peers {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)
    private val rnd = SecureRandom()

    @SuppressLint("HardwareIds")
    fun myId(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "desconocido"

    /**
     * Los devices emparejados con los que se puede hablar. Los que estan a medias (presentados por otro
     * device y aun sin la clave para llamarlos, ver Introduce) no: no se sincroniza con ellos.
     */
    fun all(ctx: Context): List<Peer> = everyone(ctx).filter { it.token.isNotEmpty() }

    /** El device que nos llama con la clave que le dimos, aunque este a medias (Introduce). */
    fun byBackToken(ctx: Context, token: String?): Peer? =
        if (token.isNullOrEmpty()) null else everyone(ctx).firstOrNull { it.backToken == token }

    /** Todos, tambien los que estan a medias. */
    @Synchronized
    fun everyone(ctx: Context): List<Peer> {
        val raw = prefs(ctx).getString("peers", null) ?: return emptyList()
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { runCatching { Peer.of(a.getJSONObject(it)) }.getOrNull() }
    }

    @Synchronized
    fun save(ctx: Context, p: Peer) {
        val list = everyone(ctx).filter { it.id != p.id } + p
        prefs(ctx).edit().putString("peers", JSONArray(list.map { it.json() }).toString()).apply()
        LinkState.post { LinkState.peersChanged.intValue++ }
    }

    @Synchronized
    fun forget(ctx: Context, id: String) {
        val p = everyone(ctx).firstOrNull { it.id == id } ?: return
        // Y los presentados a medias (sin la clave para llamarlos, ver Introduce): no salen en la lista,
        // y su clave seguia valiendo despues de olvidar a quien los presento.
        val half = everyone(ctx).filter { it.id != id && it.token.isEmpty() }
        prefs(ctx).edit().putString("peers", JSONArray(everyone(ctx).filter { it.id != id && it !in half }.map { it.json() }).toString()).apply()
        if (p.backToken.isNotEmpty()) Prefs.forgetToken(ctx, p.backToken)
        for (h in half) if (h.backToken.isNotEmpty()) Prefs.forgetToken(ctx, h.backToken)
        // Lo de partidas con ella: sus conflictos ya no se pueden resolver, y su base no sirve.
        runCatching { Saves.forgetPeer(ctx, id) }
        PeerLogs.forget(ctx, id)
        Pairing.refresh(ctx)
        // Sin devices puede que ya no haya nada que escuchar.
        LinkService.ensure(ctx)
        LinkState.post { LinkState.peersChanged.intValue++ }
    }

    // ---------------------------------------------------------------- red

    /** Las consolas con Link encendido en esta Wi-Fi, menos esta. Bloquea [waitMs]. */
    fun discover(ctx: Context, waitMs: Long = 1500): List<FoundConsole> {
        val me = myId(ctx)
        val found = LinkedHashMap<String, FoundConsole>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 250
            val msg = Protocol.DISCOVERY_DEVICE.toByteArray(Charsets.UTF_8)
            val targets = buildSet {
                add(InetAddress.getByName("255.255.255.255"))
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.forEach { add(it) }
            }
            for (t in targets) runCatching { s.send(DatagramPacket(msg, msg.size, t, Protocol.DISCOVERY_PORT)) }
            val until = System.currentTimeMillis() + waitMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < until) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val j = runCatching { JSONObject(String(p.data, 0, p.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    val id = j.optString("id")
                    if (j.optString("app") != Protocol.APP || id.isEmpty() || id == me) continue
                    found[id] = FoundConsole(id, j.optString("name", "Device"), p.address.hostAddress ?: continue,
                        j.optInt("port", Protocol.HTTP_PORT))
                } catch (_: SocketTimeoutException) {
                }
            }
        }
        return found.values.toList()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun open(host: String, port: Int, method: String, path: String, query: Map<String, String?> = emptyMap(),
             token: String? = null, readTimeout: Int = 15_000, connectTimeout: Int = 4_000): HttpURLConnection {
        val qs = query.filterValues { it != null }.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v!!) }
        val c = URL("http://$host:$port$path" + if (qs.isEmpty()) "" else "?$qs").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeout
        c.readTimeout = readTimeout
        token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        return c
    }

    /** La respuesta como JSON; si no es 200, lanza con el motivo que dio la otra consola. */
    fun json(c: HttpURLConnection): JSONObject {
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val j = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (code != 200) throw IOException(j.optString("error").ifEmpty { "HTTP $code" })
            return j
        } finally {
            c.disconnect()
        }
    }

    /** Que la otra consola enseñe un codigo (en su notificacion). */
    fun requestPair(ctx: Context, f: FoundConsole) {
        json(open(f.host, f.port, "POST", "/pair/request", mapOf("pc" to Prefs.deviceName(ctx), "device" to "1")))
    }

    /**
     * El codigo que enseño la otra: nos da su token, y le damos uno nuestro para que nos llame
     * ella a nosotros (ver HttpServer, /pair/confirm con `peer_id` y `back`).
     */
    fun confirmPair(ctx: Context, f: FoundConsole, code: String): Peer {
        val back = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        Prefs.putToken(ctx, back, f.name)
        try {
            val j = json(open(f.host, f.port, "POST", "/pair/confirm", mapOf(
                "code" to code.filter(Char::isDigit), "pc" to Prefs.deviceName(ctx),
                "peer_id" to myId(ctx), "peer_port" to Protocol.HTTP_PORT.toString(), "back" to back,
            )))
            val p = Peer(f.id, f.name, f.host, f.port, j.getString("token"), back)
            save(ctx, p)
            Pairing.refresh(ctx)
            LinkService.ensure(ctx)
            LinkState.addLog("Paired with ${f.name}", "pairing")
            return p
        } catch (e: Exception) {
            Prefs.forgetToken(ctx, back)
            throw e
        }
    }

    /**
     * Donde esta ahora: la ultima direccion si contesta como ella, y si no, buscandola en la red
     * (el router le puede haber dado otra). Nulo si no esta encendida.
     */
    fun reach(ctx: Context, p: Peer): Peer? {
        if (proven(ctx, p.host, p.port, p, 4_000)) return p
        val f = discover(ctx).firstOrNull { it.id == p.id } ?: return null
        // En una direccion nueva, solo si demuestra ser ella: el id lo repite cualquiera en la red, y
        // antes se le mandaban la clave, las partidas y el cuaderno (revision de seguridad, 07-10-2026).
        if (!proven(ctx, f.host, f.port, p, 4_000)) return null
        return p.copy(host = f.host, port = f.port).also { save(ctx, it) }
    }

    /**
     * Si en [host]:[port] contesta [p] de verdad: con su id y con la prueba de que conoce la clave que
     * nos dio ([Peer.token]), sin que nadie la mande por la red (ver HttpServer.pingProof).
     */
    private fun proven(ctx: Context, host: String, port: Int, p: Peer, timeout: Int, connectTimeout: Int = 4_000): Boolean = runCatching {
        val nonce = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val j = json(open(host, port, "GET", "/ping", mapOf("from" to myId(ctx), "nonce" to nonce),
            readTimeout = timeout, connectTimeout = connectTimeout))
        j.optString("id") == p.id && p.token.isNotEmpty() &&
            java.security.MessageDigest.isEqual(j.optString("proof").toByteArray(), proof(p.token, nonce, p.id).toByteArray())
    }.getOrDefault(false)

    /** La prueba de que se conoce [secret]: HMAC-SHA256 de [nonce] y del id de quien contesta. */
    fun proof(secret: String, nonce: String, id: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$nonce|$id".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * Como [reach], para cuando alguien espera (antes de jugar): esperas cortas y sin buscarla por la
     * red. En la Wi-Fi de casa una consola despierta contesta en menos de 100 ms; si cambio de IP, ya
     * la encontrara la sincronizacion normal.
     */
    fun reachQuick(ctx: Context, p: Peer): Peer? =
        p.takeIf { proven(ctx, p.host, p.port, p, 1_500, connectTimeout = 1_000) }

    /** Un archivo de la otra consola a [dest]. */
    fun download(p: Peer, path: String, dest: File) {
        val c = open(p.host, p.port, "GET", "/ludolog/file", mapOf("path" to path), p.token, 60_000)
        try {
            if (c.responseCode != 200) json(c)
            c.inputStream.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    /** Un cuaderno a la otra consola, al lado del suyo (ella rechaza el propio). */
    fun putLogbook(p: Peer, name: String, file: File) {
        val c = open(p.host, p.port, "PUT", "/ludolog/companion", mapOf("name" to name), p.token, 60_000)
        c.doOutput = true
        c.setFixedLengthStreamingMode(file.length())
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
        json(c)
    }
}
