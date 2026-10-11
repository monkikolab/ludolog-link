package com.felp.ludologlink

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Lo que se sabe ahora de los devices emparejados, para que la pregunta de antes de jugar (SaveCheck)
 * no espere a la red: cuando jugaron con cada emulador (su /saves/state) y quien no contesto hace poco.
 *
 * - Al volver a Ludolog (que pregunta "state"), se les pregunta ya, en segundo plano ([prefetch]). Al
 *   abrir un juego poco despues, la respuesta ya esta.
 * - Lo guardado vale [FRESH_MS]. Si un device sincroniza partidas con este, lo suyo se olvida
 *   ([invalidate]): pudo cambiar.
 * - Un device que no contesto no se vuelve a esperar durante [DOWN_MS]: se usan sus cuadernos y se
 *   avisa al momento. Si llama el, se le vuelve a creer vivo ([seen]).
 * - Con varios devices se pregunta a todos a la vez: las esperas no se suman.
 */
object PeerState {
    private const val FRESH_MS = 120_000L
    private const val DOWN_MS = 120_000L
    private const val PREFETCH_EVERY_MS = 30_000L

    private class Plays(val at: Long, val games: Map<String, Long>)

    private val plays = ConcurrentHashMap<String, Plays>()   // "<id>|<paquete>"
    private val down = ConcurrentHashMap<String, Long>()
    @Volatile private var lastPrefetch = 0L
    private val pool = Executors.newCachedThreadPool { Thread(it, "peer-state").apply { isDaemon = true } }

    fun seen(id: String) { down.remove(id) }

    fun invalidate(id: String) { plays.keys.removeIf { it.startsWith("$id|") } }

    private fun recentlyDown(id: String) = System.currentTimeMillis() - (down[id] ?: 0L) < DOWN_MS

    /**
     * Cuando jugo [p] cada juego de ese emulador: lo guardado si es reciente, y si no, preguntado ya con
     * esperas cortas. Nulo si no contesta (o no contesto hace poco).
     */
    fun plays(ctx: Context, p: Peer, pkg: String): Map<String, Long>? {
        val k = "${p.id}|$pkg"
        plays[k]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }?.let { return it.games }
        if (recentlyDown(p.id)) return null
        val r = Peers.reachQuick(ctx, p) ?: run { down[p.id] = System.currentTimeMillis(); return null }
        return fetch(ctx, r, listOf(pkg))[pkg]
    }

    /** A todos a la vez: devuelve lo de cada uno (nulo si no contesto). Esperas: la del mas lento. */
    fun playsOfAll(ctx: Context, peers: List<Peer>, pkg: String): Map<String, Map<String, Long>?> {
        val jobs = peers.associate { p -> p.id to pool.submit<Map<String, Long>?> { runCatching { plays(ctx, p, pkg) }.getOrNull() } }
        return jobs.mapValues { (_, f) -> runCatching { f.get(6, TimeUnit.SECONDS) }.getOrNull() }
    }

    /** Ludolog volvio a primer plano: preguntar ya, para que abrir un juego no espere. */
    fun prefetch(ctx: Context) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastPrefetch < PREFETCH_EVERY_MS || !Prefs.syncDevices(app)) return
        lastPrefetch = now
        pool.execute {
            val pkgs = runCatching { Saves.all(app).filter { it.configured }.map { it.pkg } }.getOrDefault(emptyList())
            if (pkgs.isEmpty()) return@execute
            for (p in Peers.all(app)) pool.execute {
                if (recentlyDown(p.id)) return@execute
                val r = Peers.reachQuick(app, p) ?: run { down[p.id] = System.currentTimeMillis(); return@execute }
                fetch(app, r, pkgs)
            }
        }
    }

    /** /saves/state de cada emulador, con espera corta; lo guarda. */
    private fun fetch(ctx: Context, r: Peer, pkgs: List<String>): Map<String, Map<String, Long>> {
        val out = HashMap<String, Map<String, Long>>()
        for (pkg in pkgs) {
            val g = runCatching {
                Peers.json(Peers.open(r.host, r.port, "GET", "/saves/state", Saves.stateQuery(ctx, pkg), r.token, 2_500, connectTimeout = 1_000))
                    .optJSONObject("games")
            }.getOrElse { down[r.id] = System.currentTimeMillis(); return out }
            val games = g?.keys()?.asSequence()?.associateWith { g.getLong(it) }.orEmpty()
            plays["${r.id}|$pkg"] = Plays(System.currentTimeMillis(), games)
            out[pkg] = games
        }
        seen(r.id)
        return out
    }
}
