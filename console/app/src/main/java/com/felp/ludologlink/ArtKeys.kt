package com.felp.ludologlink

import android.content.Context
import com.felp.ludolog.kit.KeyBox
import org.json.JSONObject
import java.security.KeyPair

/**
 * Las claves de las fuentes de arte de Ludolog (IGDB) entre aparatos (10-10-2026, pedido del
 * usuario: escribirlas en uno basta). Ludolog las da y las toma por su LinkKeys; aqui solo pasan, en
 * memoria, y en la red van cifradas (ver KeyBox). Gana la puesta mas tarde, en todos.
 *
 *   GET  /ludolog/keys?pub=<la suya>          las fechas de las de aqui y, cerradas para quien pregunta, las claves
 *   POST /ludolog/keys  (JSON {pub, to, box}) las de otro aparato, cerradas para nuestra publica [to]
 *
 * Los dos los piden el PC y las consolas emparejadas. Entre consolas se hace en cada sincronizacion
 * (CompanionShare), que tambien corre al emparejar y cuando Ludolog avisa de una clave nueva.
 */
object ArtKeys {
    /**
     * Nuestro par para lo que nos mandan: dura un rato y se guarda el anterior, para que un envio que
     * pidio la publica justo antes del cambio aun se abra. Nunca va a disco.
     */
    private var current: Pair<KeyPair, Long>? = null
    private var previous: KeyPair? = null
    private const val PAIR_MS = 10 * 60_000L

    @Synchronized
    private fun mine(): KeyPair {
        val now = System.currentTimeMillis()
        current?.takeIf { now - it.second < PAIR_MS }?.let { return it.first }
        previous = current?.first
        return KeyBox.pair().also { current = it to now }
    }

    @Synchronized
    private fun find(pub: String): KeyPair? =
        listOfNotNull(current?.first, previous).firstOrNull { KeyBox.pub(it) == pub }

    /** GET /ludolog/keys. Nulo si Ludolog no esta (o no sabe darlas). */
    fun offer(ctx: Context, token: String, theirPub: String?): JSONObject? {
        val local = Ludolog.artKeys(ctx) ?: return null
        val me = mine()
        val j = JSONObject().put("pub", KeyBox.pub(me)).put("stamps", KeyBox.stamps(local))
        val theirs = KeyBox.parse(theirPub)
        if (theirs != null && local.isNotEmpty()) j.put("box", KeyBox.seal(me, theirs, token, local))
        return j
    }

    /** Lo que no se pudo tomar de un POST, con su codigo HTTP. */
    class Refused(val code: Int, message: String) : Exception(message)

    /** POST /ludolog/keys: cuantas tomo Ludolog. */
    fun accept(ctx: Context, token: String, who: String, body: JSONObject): Int {
        val me = find(body.optString("to")) ?: throw Refused(409, "that key expired; ask again")
        val theirs = KeyBox.parse(body.optString("pub")) ?: throw Refused(400, "bad key")
        val entries = KeyBox.open(me, theirs, token, body.optString("box")) ?: throw Refused(400, "couldn't open the keys")
        val n = Ludolog.putArtKeys(ctx, entries) ?: throw Refused(404, "Ludolog can't take them on this device")
        if (n > 0) LinkState.addLog("$who sent the art source keys", "art")
        return n
    }

    /**
     * Con otra consola emparejada: se trae lo suyo si es mas nuevo y se le da lo nuestro si lo es.
     * Una consola con Link anterior no las conoce (404): sin nada que decir. Para el registro, que paso.
     */
    fun syncWith(ctx: Context, p: Peer): String {
        val local = Ludolog.artKeys(ctx) ?: return ""
        val me = KeyBox.pair()
        val j = runCatching {
            Peers.json(Peers.open(p.host, p.port, "GET", "/ludolog/keys", mapOf("pub" to KeyBox.pub(me)), p.token))
        }.getOrNull() ?: return ""
        val theirPub = KeyBox.parse(j.optString("pub"))
        val theirs = theirPub?.let { pub -> j.optString("box").takeIf { it.isNotEmpty() }?.let { KeyBox.open(me, pub, p.token, it) } }.orEmpty()
        val take = KeyBox.newer(local, theirs)
        val got = if (take.isEmpty()) 0 else Ludolog.putArtKeys(ctx, take) ?: 0
        val give = KeyBox.ahead(local, KeyBox.stampsOf(j.optJSONObject("stamps")))
        var sent = false
        if (give.isNotEmpty() && theirPub != null) {
            val body = JSONObject().put("pub", KeyBox.pub(me)).put("to", j.optString("pub"))
                .put("box", KeyBox.seal(me, theirPub, p.token, give)).toString().toByteArray(Charsets.UTF_8)
            val c = Peers.open(p.host, p.port, "POST", "/ludolog/keys", token = p.token)
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json")
            sent = runCatching { c.outputStream.use { it.write(body) }; Peers.json(c).optInt("changed") > 0 }.getOrDefault(false)
        }
        return when {
            got > 0 && sent -> "art source keys swapped"
            got > 0 -> "got the art source keys"
            sent -> "sent the art source keys"
            else -> ""
        }
    }
}
