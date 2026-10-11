package com.felp.ludologlink.pc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Los pedidos de los devices al catalogo del PC, mientras Link esta abierto aqui: a cada device
 * conectado se le deja la lista del catalogo (cuando cambia) y se le leen sus pedidos; los que faltan
 * se le mandan con su caratula y su video por la cola de subidas (AppState.romFromCatalog). En el
 * device: PcRequests. Sin carpeta de catalogo (o con su disco desconectado) no se hace nada.
 *
 * Lo arranca AppState al crearse ([start]) y da una vuelta cada 8 s. Rutas de la consola:
 * PUT /pc/catalog (la lista, ver [indexOf]) y GET /pc/requests (lo pedido).
 */
object CatalogRequests {

    private var scan: PcCatalog.Scan? = null
    private var scannedAt = 0L
    private var index: ByteArray? = null
    private var indexHash = 0
    /** El hash de la lista que ya tiene cada device: no se le vuelve a mandar igual. */
    private val pushed = HashMap<String, Int>()
    /** De que lectura del catalogo es [index]: con la misma, no se rehace. */
    private var indexedScan: PcCatalog.Scan? = null
    /**
     * Los pedidos ya atendidos en esta sesion ("device|consola/archivo"). Uno que fallo o se cancelo
     * no vuelve a la cola cada 8 s: solo si el device lo deja de pedir y lo pide otra vez.
     */
    private val handled = HashSet<String>()
    /** Vueltas seguidas sin pedidos de cada device: se le pregunta menos (deja dormir su Wi-Fi). */
    private val quiet = HashMap<String, Int>()
    private var round = 0L

    fun start(app: AppState, scope: CoroutineScope) = scope.launch {
        delay(15_000)
        while (true) {
            runCatching { tick(app) }
            delay(8_000)
        }
    }

    private suspend fun tick(app: AppState) {
        val devices = app.consoles.filter { it.paired && it.online == true && it.info != null }
        if (devices.isEmpty()) return
        val now = System.currentTimeMillis()
        val s = withContext(Dispatchers.IO) {
            val dir = PcCatalog.dir ?: return@withContext null
            // El catalogo se relee cada minuto (o al volver su disco), no en cada vuelta.
            if (scan?.dir != dir || now - scannedAt > 60_000) {
                PcCatalog.catalogs = app.consoles.mapNotNull { it.catalog }
                scan = PcCatalog.latest(dir, 30_000); scannedAt = now
                // La lista para los devices solo si la lectura es otra (la de la tabla se comparte).
                if (scan !== indexedScan) {
                    indexedScan = scan
                    index = scan?.let(::indexOf); indexHash = index?.contentHashCode() ?: 0
                }
            }
            // Cada diez minutos, mandar la lista otra vez aunque no cambio: otro PC pudo dejar la suya.
            if (++round % 75 == 0L) pushed.clear()
            scan
        } ?: return
        for (e in devices) withContext(Dispatchers.IO) {
            val l = e.link()
            // La lista, si cambio desde la ultima vez (un device sin la ruta, viejo, no la pide de nuevo).
            if (pushed[e.id] != indexHash) {
                index?.let { runCatching { l.putPcCatalog(it) }.onSuccess { pushed[e.id] = indexHash }
                    .onFailure { if (it is LinkError && it.status == 404) pushed[e.id] = indexHash } }
            }
            // Sin pedidos desde hace rato: se pregunta una vez de cada cuatro (cada ~30 s).
            val q = quiet[e.id] ?: 0
            if (q > 3 && round % 4 != 0L) return@withContext
            val wanted = runCatching { l.pcRequests() }.getOrDefault(emptyList())
            quiet[e.id] = if (wanted.isEmpty()) q + 1 else 0
            // Lo que el device ya no pide se olvida: si lo vuelve a pedir, se atiende otra vez.
            val asked = wanted.mapTo(HashSet()) { (system, name) -> "${e.id}|$system/$name" }
            handled.removeAll { it.startsWith("${e.id}|") && it !in asked }
            for ((system, name) in wanted) {
                val key = "${e.id}|$system/$name"
                // Ya atendido (en camino, hecho, fallido o cancelado): no se manda otra vez.
                if (key in handled) continue
                if (app.transfers.items.any { it.upload && it.consoleId == e.id && it.system == system && it.name == name && !it.finished }) {
                    handled += key; continue
                }
                val f = s.roms.firstOrNull { it.system == system && it.name == name } ?: continue
                val keys = PcCatalog.artKeys(s, f)
                handled += key
                withContext(Dispatchers.Main) {
                    app.romFromCatalog(s, f, e, keys.firstNotNullOfOrNull { s.covers[it] }, keys.firstNotNullOfOrNull { s.videos[it] })
                }
            }
        }
    }

    /** La lista que se deja en el device: consola (carpeta), archivo, tamaño, titulo y si tiene arte y video. */
    private fun indexOf(s: PcCatalog.Scan): ByteArray {
        val games = JSONArray()
        for (f in s.roms) {
            val keys = PcCatalog.artKeys(s, f)
            val title = PcCatalog.game(s, f)?.title?.takeIf { it.isNotBlank() }
                ?: com.felp.ludolog.kit.Protocol.stemOf(f.name.substringAfterLast('/'))
            games.put(JSONObject().put("system", f.system).put("name", f.name).put("size", f.size).put("title", title)
                .put("art", keys.any { it in s.covers }).put("video", keys.any { it in s.videos }))
        }
        return JSONObject().put("games", games).toString().toByteArray(Charsets.UTF_8)
    }
}
