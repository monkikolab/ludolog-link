package com.felp.ludologlink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Las correcciones de Ludolog que viajan entre devices: el nombre, la descripcion y el genero de un
 * juego, y la descripcion de una consola. Ver ludolog-front-end/docs/ludolog-link.md.
 *
 * - Ludolog apunta lo que se corrige en `<datos>/link/edits.tsv` (con hora y las claves del fichero)
 *   y avisa (EDITS_CHANGED). Link lo lee ([ingest]) a su registro, `<archivos>/edits.json`.
 * - El mismo juego se reconoce por la clave de su fichero (CRC, de su ficha en Ludolog), no por el
 *   nombre: asi una correccion llega aunque el archivo se llame distinto en cada consola. Sin clave,
 *   por el nombre del archivo normalizado.
 * - De dos correcciones del mismo campo gana la ultima (su hora). Lo que llega mas nuevo de otro lado
 *   se le pasa a Ludolog con la ruta de aqui (`link/edits-in.json`, EDITS_IN), que lo aplica sin
 *   volver a apuntarlo.
 * - Con las consolas se cruza al compartir (CompanionShare.syncOne). El cursor no es la hora de la
 *   correccion sino un contador de aqui ([Entry.seq]): una correccion vieja que llega tarde de una
 *   tercera consola tambien se pasa a las demas.
 * - El PC corrige con POST /meta/edit (ver HttpServer), con las claves del fichero si las sabe (un
 *   ROM que manda de su catalogo y que aqui aun no tiene ficha).
 * - Lo que llega de un juego que aqui aun no esta (o sin ficha todavia) se queda pendiente
 *   ([Store.unplaced]) y se vuelve a intentar al cambiar la biblioteca y al compartir ([place]):
 *   un ROM copiado despues llega con su nombre y su descripcion.
 */
object MetaEdits {
    data class Entry(
        val id: String, val field: String, val value: String, val t: Long, val by: String,
        val kind: String, val system: String, val seq: Long = 0,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("field", field).put("value", value).put("t", t)
            .put("by", by).put("kind", kind).put("system", system).put("seq", seq)

        companion object {
            fun of(o: JSONObject) = Entry(o.getString("id"), o.getString("field"), o.optString("value"), o.getLong("t"),
                o.optString("by"), o.optString("kind", "game"), o.optString("system"), o.optLong("seq"))
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "edits.json")
    private val lock = Any()

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** La identidad de un juego: su consola y la clave de su fichero, o su nombre normalizado. */
    fun gameId(system: String, keys: String, path: String): String {
        val k = keys.split(' ').filter { it.isNotBlank() }
        val key = k.firstOrNull { it.startsWith("c:") } ?: k.sorted().firstOrNull()
        return "game|${system.lowercase()}|" + (key ?: "t:" + norm(com.felp.ludolog.kit.Protocol.stemOf(path.substringAfterLast('/'))))
    }

    private class Store(
        val entries: LinkedHashMap<String, Entry>, var seq: Long, var cursor: Long, val pushed: JSONObject, val pulled: JSONObject,
        /** Lo llegado que aun no encontro su juego aqui: id#campo. */
        val unplaced: MutableSet<String> = LinkedHashSet(),
    )

    private fun load(ctx: Context): Store {
        val o = runCatching { JSONObject(file(ctx).readText()) }.getOrDefault(JSONObject())
        val map = LinkedHashMap<String, Entry>()
        o.optJSONArray("entries")?.let { a -> for (i in 0 until a.length()) runCatching { Entry.of(a.getJSONObject(i)) }.getOrNull()?.let { map["${it.id}#${it.field}"] = it } }
        val unplaced = LinkedHashSet<String>()
        o.optJSONArray("unplaced")?.let { a -> for (i in 0 until a.length()) unplaced += a.getString(i) }
        return Store(map, o.optLong("seq"), o.optLong("cursor"), o.optJSONObject("pushed") ?: JSONObject(), o.optJSONObject("pulled") ?: JSONObject(), unplaced)
    }

    private fun save(ctx: Context, s: Store) {
        // Lo pendiente de algo que ya no esta en el registro, fuera.
        s.unplaced.retainAll(s.entries.keys)
        val o = JSONObject().put("seq", s.seq).put("cursor", s.cursor).put("pushed", s.pushed).put("pulled", s.pulled)
            .put("unplaced", JSONArray(s.unplaced.toList()))
            .put("entries", JSONArray(s.entries.values.map { it.json() }))
        val f = file(ctx)
        val tmp = File(f.path + ".part")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Guarda [e] si es mas nueva que lo que habia. Devuelve la guardada (con su seq) o nula. */
    private fun put(s: Store, e: Entry): Entry? {
        val k = "${e.id}#${e.field}"
        val old = s.entries[k]
        if (old != null && (old.t > e.t || (old.t == e.t && old.by >= e.by))) return null
        if (old != null && old.value == e.value && old.t == e.t) return null
        val stored = e.copy(seq = ++s.seq)
        s.entries[k] = stored
        return stored
    }

    /** Lo nuevo del diario de Ludolog (`link/edits.tsv`) al registro. Verdadero si habia algo. */
    fun ingest(ctx: Context): Boolean = synchronized(lock) {
        val dir = Ludolog.dataDir(ctx) ?: return false
        val log = File(dir, "link/edits.tsv")
        if (!log.isFile) return false
        val s = load(ctx)
        // Ludolog lo recorta: si ahora es mas corto que lo leido, se vuelve a leer entero (put no repite).
        if (log.length() < s.cursor) s.cursor = 0
        if (log.length() == s.cursor) return false
        val me = Peers.myId(ctx)
        var any = false
        log.inputStream().use { inp ->
            inp.skip(s.cursor)
            inp.bufferedReader().lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size < 7) return@forEach
                val t = p[0].toLongOrNull() ?: return@forEach
                val kind = p[1]; val system = p[2]; val path = p[3]; val keys = p[4]; val field = p[5]; val value = p[6]
                val id = if (kind == "sys") "sys|${system.lowercase()}" else gameId(system, keys, path)
                if (put(s, Entry(id, field, value, t, me, kind, system)) != null) any = true
            }
        }
        s.cursor = log.length()
        save(ctx, s)
        if (any) LinkState.addLog("Game info edited here", "ludolog")
        any
    }

    /** Lo que tiene este registro despues de [since] (su seq), para otra consola o el PC. */
    fun since(ctx: Context, since: Long): JSONObject = synchronized(lock) {
        val s = load(ctx)
        JSONObject().put("seq", s.seq).put("entries", JSONArray(s.entries.values.filter { it.seq > since }.map { it.json() }))
    }

    /** Lo que llega de otro lado: lo mas nuevo se guarda y se le pasa a Ludolog. Cuantas cambiaron. */
    fun merge(ctx: Context, incoming: List<Entry>): Int {
        val applied = synchronized(lock) {
            val s = load(ctx)
            val got = incoming.mapNotNull { put(s, it.copy(seq = 0)) }
            save(ctx, s)
            got
        }
        if (applied.isNotEmpty()) deliver(ctx, applied)
        return applied.size
    }

    /** Pasarle a Ludolog [entries] y apuntar las que aun no encontraron su juego aqui. */
    private fun deliver(ctx: Context, entries: List<Entry>) {
        val placed = toLudolog(ctx, entries)
        synchronized(lock) {
            val s = load(ctx)
            for (e in entries) {
                val k = "${e.id}#${e.field}"
                if (k in placed) s.unplaced.remove(k) else s.unplaced.add(k)
            }
            save(ctx, s)
        }
    }

    /** Otro intento con lo pendiente: juegos que llegaron despues, fichas que Ludolog ya leyo. */
    fun place(ctx: Context) {
        val pending = synchronized(lock) { load(ctx).let { s -> s.unplaced.mapNotNull { s.entries[it] } } }
        if (pending.isEmpty()) return
        // Sin fichas nuevas desde el ultimo intento no hay nada nuevo que encontrar.
        val dir = Ludolog.dataDir(ctx) ?: return
        val stamp = pending.map { it.system }.distinct().sumOf { File(dir, "dossiers/$it.tsv").lastModified() } * 31 + pending.size
        if (stamp == lastPlaced) return
        lastPlaced = stamp
        deliver(ctx, pending)
    }

    @Volatile private var lastPlaced = 0L

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var placeCtx: Context? = null
    private val placeNow = Runnable { placeCtx?.let { c -> kotlin.concurrent.thread(isDaemon = true) { runCatching { place(c) } } } }

    /**
     * Tras llegar ROMs: Ludolog los repasa y les hace ficha a su ritmo, asi que se intenta al rato
     * y otra vez mas tarde.
     */
    fun placeSoon(ctx: Context) {
        placeCtx = ctx.applicationContext
        main.removeCallbacks(placeNow)
        main.postDelayed(placeNow, 60_000)
        main.postDelayed(placeNow, 300_000)
    }

    /**
     * Una correccion hecha en el PC sobre un juego de esta consola ([path]) o una consola: se guarda
     * como de el, se aplica aqui y se pasa a las otras consolas al compartir.
     */
    fun fromPc(ctx: Context, pc: String, kind: String, system: String, path: String, field: String, value: String, t: Long, sentKeys: String = ""): Boolean {
        val keys = if (kind == "game") dossierKeys(ctx, system)[path]?.takeIf { it.isNotBlank() } ?: sentKeys else ""
        val id = if (kind == "sys") "sys|${system.lowercase()}" else gameId(system, keys, path)
        return merge(ctx, listOf(Entry(id, field, value, t, "pc:$pc", kind, system))) > 0
    }

    /** Cruzar con [p]: traer lo suyo desde la ultima vez y llevarle lo nuevo de aqui. */
    fun syncWith(ctx: Context, p: Peer): String {
        runCatching { place(ctx) }
        val (pulledFrom, pushedTo) = synchronized(lock) { load(ctx).let { it.pulled.optLong(p.id) to it.pushed.optLong(p.id) } }
        val theirs = Peers.json(Peers.open(p.host, p.port, "GET", "/meta/edits", mapOf("since" to pulledFrom.toString()), p.token))
        val got = theirs.optJSONArray("entries")?.let { a -> (0 until a.length()).map { Entry.of(a.getJSONObject(it)) } }.orEmpty()
        val changed = merge(ctx, got)
        val mine = since(ctx, pushedTo)
        val toSend = mine.getJSONArray("entries")
        if (toSend.length() > 0) {
            val c = Peers.open(p.host, p.port, "POST", "/meta/edits", token = p.token, readTimeout = 30_000)
            val body = JSONObject().put("entries", toSend).toString().toByteArray()
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body) }
            Peers.json(c)
        }
        synchronized(lock) {
            val s = load(ctx)
            s.pulled.put(p.id, theirs.optLong("seq"))
            s.pushed.put(p.id, mine.optLong("seq"))
            save(ctx, s)
        }
        return when {
            changed > 0 && toSend.length() > 0 -> "game info: got $changed, sent ${toSend.length()}"
            changed > 0 -> "game info: got $changed"
            toSend.length() > 0 -> "game info: sent ${toSend.length()}"
            else -> ""
        }
    }

    /** Las claves de los ficheros de una consola segun las fichas de Ludolog: ruta -> claves. */
    private fun dossierKeys(ctx: Context, system: String): Map<String, String> {
        val dir = Ludolog.dataDir(ctx) ?: return emptyMap()
        val f = File(dir, "dossiers/$system.tsv")
        if (!f.isFile) return emptyMap()
        return f.readLines().mapNotNull { line ->
            val p = line.split('\t')
            val path = p.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            path to (p.firstOrNull { it.startsWith("keys=") }?.removePrefix("keys=").orEmpty())
        }.toMap()
    }

    /**
     * Lo que gano de otro lado, a Ludolog: con la ruta de cada juego aqui (por su clave o su nombre).
     * Se suma a lo que Ludolog aun no haya aplicado. Devuelve las id#campo que encontraron juego.
     */
    private fun toLudolog(ctx: Context, entries: List<Entry>): Set<String> {
        val dir = Ludolog.dataDir(ctx) ?: return emptySet()
        val registry = synchronized(lock) { load(ctx).entries }
        val out = JSONArray()
        val placed = HashSet<String>()
        for ((system, list) in entries.groupBy { it.system }) {
            val keys by lazy { dossierKeys(ctx, system) }
            for (e in list) {
                if (e.kind == "sys") {
                    out.put(JSONObject().put("kind", "sys").put("system", system).put("field", e.field).put("value", e.value))
                    placed += "${e.id}#${e.field}"
                    continue
                }
                // Todos los ficheros de aqui que son ese juego (pueden ser varios: discos, copias). Si la
                // otra no tenia aun la clave del fichero, vino por el nombre: tambien vale.
                for ((path, k) in keys) {
                    val ids = setOf(gameId(system, k, path), gameId(system, "", path))
                    if (e.id !in ids) continue
                    placed += "${e.id}#${e.field}"
                    // Por la clave y por el nombre pueden ser dos correcciones del mismo fichero: la ultima.
                    val newest = ids.mapNotNull { registry["$it#${e.field}"] }.maxByOrNull { it.t }
                    if (newest != null && newest.t > e.t) continue
                    out.put(JSONObject().put("kind", "game").put("system", system).put("path", path).put("field", e.field).put("value", e.value))
                }
            }
        }
        if (out.length() == 0) return placed
        val f = File(dir, "link/edits-in.json")
        val prev = runCatching { JSONArray(f.readText()) }.getOrNull()
        prev?.let { for (i in 0 until it.length()) out.put(it.get(i)) }
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".part")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        Ludolog.editsIn(ctx)
        val from = if (entries.all { it.by.startsWith("pc:") }) "the PC" else "other devices"
        LinkState.addLog("Game info from $from: ${out.length()}", "ludolog")
        return placed
    }
}
