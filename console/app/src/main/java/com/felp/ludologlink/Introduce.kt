package com.felp.ludologlink

import android.content.Context
import java.security.SecureRandom

/**
 * Emparejar entre si a los devices emparejados con este, sin codigos: si A esta emparejado con B y
 * con C, A los presenta. Con 4 devices eran 6 emparejamientos a mano; asi basta con emparejar todos
 * con uno y pulsar "Pair them all" en el.
 *
 * Es el mismo cruce de claves que al emparejar con codigo (Peers.confirmPair), con A en medio:
 *  1. A le pide a B una clave para C (`POST /pair/introduce` con los datos de C). B la crea y la
 *     guarda ya, con C a medias (sin la clave para llamarla): asi B reconoce a C como device.
 *  2. A le da a C los datos de B y esa clave (`/pair/introduce` con `token`). C la guarda y crea la
 *     suya para B.
 *  3. A le da a B la clave de C (`/pair/introduce/complete`). B ya puede llamar a C.
 * Solo se acepta de un device ya emparejado y con compartir encendido. Cada paso se puede repetir:
 * si algo falla a medias, volver a pulsar lo arregla.
 */
object Introduce {
    private val rnd = SecureRandom()

    private fun newToken() = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------- lado de B y C (HttpServer)

    /**
     * [from] presenta un device nuevo ([id], [name], en [host]:[port]). Devuelve la clave que le
     * damos para llamarnos. Con [token] (la suya, para llamarlo nosotros) queda completo.
     */
    fun accept(ctx: Context, from: Peer, id: String, name: String, host: String, port: Int, token: String?): String {
        val old = Peers.everyone(ctx).firstOrNull { it.id == id }
        // Revision de seguridad (07-10-2026). Solo devices nuevos, o a medias de un intento anterior:
        // presentar el id de uno ya emparejado le cambiaba la direccion y devolvia su clave, y quien lo
        // pidiera se quedaba con su sitio. La direccion, una IP de la red local, nunca un nombre. Y un
        // tope, para que uno solo no pueda sumar devices sin fin.
        require(old == null || old.token.isEmpty()) { "already paired with $name" }
        require(isLocal(host)) { "not an address on this network" }
        require(old != null || Peers.everyone(ctx).size < MAX_DEVICES) { "too many paired devices" }
        val back = old?.backToken?.ifEmpty { null } ?: newToken().also { Prefs.putToken(ctx, it, name) }
        Peers.save(ctx, Peer(id, name, host, port, token ?: old?.token.orEmpty(), back))
        Pairing.refresh(ctx)
        if (!token.isNullOrEmpty()) done(ctx, name, from)
        return back
    }

    private const val MAX_DEVICES = 16

    /** Una IPv4 privada o de enlace local escrita tal cual: nunca un nombre que haya que resolver. */
    private fun isLocal(host: String): Boolean {
        val n = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host)?.groupValues?.drop(1)
            ?.map { it.toInt() }?.takeIf { it.all { b -> b in 0..255 } } ?: return false
        val (a, b) = n
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }

    /** El paso 3: la clave de [id] para llamarlo. */
    fun complete(ctx: Context, from: Peer, id: String, token: String): Boolean {
        val p = Peers.everyone(ctx).firstOrNull { it.id == id } ?: return false
        // Solo completa uno a medias: uno ya completo no cambia de clave por lo que diga otro device.
        if (p.token.isNotEmpty()) return false
        Peers.save(ctx, p.copy(token = token))
        done(ctx, p.name, from)
        return true
    }

    private fun done(ctx: Context, name: String, from: Peer) {
        LinkState.addLog("Paired with $name, introduced by ${from.name}", "pairing")
        // Ponerse al dia con el recien llegado.
        LinkService.ensure(ctx, catchUp = true)
    }

    // ------------------------------------------------------- lado de A

    /** Con quien esta emparejado [p] (solo los completos), o nulo si no contesta. */
    private fun peersOf(ctx: Context, p: Peer): Set<String>? = runCatching {
        val r = Peers.reachQuick(ctx, p) ?: Peers.reach(ctx, p) ?: return null
        val a = Peers.json(Peers.open(r.host, r.port, "GET", "/peers", token = r.token, readTimeout = 5_000)).getJSONArray("peers")
        (0 until a.length()).mapTo(HashSet()) { a.getString(it) }
    }.getOrNull()

    /** Las parejas de devices emparejados con este que no lo estan entre si (solo de los que contestan). */
    fun missing(ctx: Context): List<Pair<Peer, Peer>> {
        val peers = Peers.all(ctx).filter { it.token.isNotEmpty() }
        if (peers.size < 2) return emptyList()
        val known = peers.associate { it.id to peersOf(ctx, it) }
        val out = ArrayList<Pair<Peer, Peer>>()
        for (i in peers.indices) for (j in i + 1 until peers.size) {
            val a = peers[i]; val b = peers[j]
            val ka = known[a.id] ?: continue
            val kb = known[b.id] ?: continue
            if (b.id !in ka || a.id !in kb) out += a to b
        }
        return out
    }

    /** Presenta cada pareja que falte. Devuelve lo que paso, para la pantalla y el registro. */
    fun connectAll(ctx: Context): String {
        val pairs = missing(ctx)
        if (pairs.isEmpty()) return "All devices are already paired."
        val notes = pairs.map { (a, b) ->
            runCatching { introduce(ctx, a, b); "${a.name} and ${b.name} paired" }
                .getOrElse { "${a.name} and ${b.name}: couldn't (${it.message})" }
        }
        val note = notes.joinToString(" · ")
        LinkState.addLog("Pair them all: $note", "pairing", error = notes.any { "couldn't" in it })
        return note
    }

    private fun introduce(ctx: Context, a: Peer, b: Peer) {
        val ra = Peers.reach(ctx, a) ?: throw java.io.IOException("${a.name} isn't reachable")
        val rb = Peers.reach(ctx, b) ?: throw java.io.IOException("${b.name} isn't reachable")
        fun call(to: Peer, path: String, q: Map<String, String?>) =
            Peers.json(Peers.open(to.host, to.port, "POST", path, q, to.token, 10_000))
        // 1. A "a" se le presenta "b": "a" crea la clave para que "b" la llame.
        val forB = call(ra, "/pair/introduce", mapOf("id" to b.id, "name" to b.name, "host" to rb.host, "port" to rb.port.toString()))
            .getString("token")
        // 2. A "b" se le presenta "a", con la clave para llamarla; "b" crea la suya para "a".
        val forA = call(rb, "/pair/introduce", mapOf("id" to a.id, "name" to a.name, "host" to ra.host,
            "port" to ra.port.toString(), "token" to forB)).getString("token")
        // 3. "a" recibe la clave de "b".
        call(ra, "/pair/introduce/complete", mapOf("id" to b.id, "token" to forA))
    }
}
