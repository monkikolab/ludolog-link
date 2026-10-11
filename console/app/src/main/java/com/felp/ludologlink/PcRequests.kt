package com.felp.ludologlink

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pedir juegos del catalogo del PC (su pestaña CATALOG), en cola: el PC deja aqui la lista de su
 * catalogo al conectarse (PUT /pc/catalog) y lee los pedidos (GET /pc/requests) mientras esta abierto,
 * para mandarlos con su arte por su cola de subidas. Un pedido se cumple cuando el archivo esta aqui;
 * hecho con el PC apagado, llega la proxima vez que se abra. Ver RomsScreen y, en el PC, CatalogRequests.
 * Se guarda en `LudologLink/state/pc-catalog.json` y `state/pc-requests.json` (ver Saves.home).
 */
object PcRequests {

    /** Un juego del catalogo del PC: su carpeta de consola y su archivo, como iran aqui. */
    class Item(val system: String, val name: String, val size: Long, val title: String, val art: Boolean, val video: Boolean) {
        val key get() = "$system/$name"
        fun json(): JSONObject = JSONObject().put("system", system).put("name", name).put("size", size).put("title", title)
            .put("art", art).put("video", video)

        companion object {
            fun of(j: JSONObject) = Item(j.getString("system"), j.getString("name"), j.optLong("size"),
                j.optString("title").ifEmpty { j.getString("name") }, j.optBoolean("art"), j.optBoolean("video"))
        }
    }

    /** La lista del catalogo que dejo el PC: de que PC, cuando, y sus juegos. */
    class Catalog(val pc: String, val at: Long, val items: List<Item>)

    private fun file(name: String) = File(Saves.home(), "state/$name")

    private fun write(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "." + f.name + Saves.PART)
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** La lista ya leida, mientras el archivo no cambie: puede ser grande y se pide en cada vistazo. */
    private var cached: Pair<Long, Catalog>? = null

    @Synchronized
    fun catalog(): Catalog? = runCatching {
        val f = file("pc-catalog.json")
        if (!f.isFile) return@runCatching null
        cached?.let { (at, c) -> if (at == f.lastModified()) return@runCatching c }
        val j = JSONObject(f.readText())
        val a = j.getJSONArray("games")
        Catalog(j.optString("pc"), j.optLong("at"), (0 until a.length()).map { Item.of(a.getJSONObject(it)) })
            .also { cached = f.lastModified() to it }
    }.getOrNull()

    /** Lo ultimo que llego, para no reescribir ni avisar si el PC manda lo mismo (lo manda a menudo). */
    private var lastSent: String? = null

    /** Lo que manda el PC: {pc, games:[...]}. Lo de antes se reemplaza entero. */
    @Synchronized
    fun setCatalog(pc: String, games: JSONArray) {
        val sent = pc + "\n" + games.toString()
        if (sent == lastSent && file("pc-catalog.json").isFile) return
        lastSent = sent
        write(file("pc-catalog.json"), JSONObject().put("pc", pc).put("at", System.currentTimeMillis()).put("games", games).toString())
        LinkState.post { LinkState.pcChanged.intValue++ }
    }

    /** Los pedidos que siguen en pie (los que ya llegaron se quitan solos: ver [prune]). */
    @Synchronized
    fun requests(): List<Item> = runCatching {
        val a = JSONArray(file("pc-requests.json").readText())
        (0 until a.length()).map { Item.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(list: List<Item>) {
        write(file("pc-requests.json"), JSONArray(list.map { it.json() }).toString())
        LinkState.post { LinkState.pcChanged.intValue++ }
    }

    @Synchronized
    fun request(i: Item) { if (requests().none { it.key == i.key }) save(requests() + i) }

    @Synchronized
    fun cancel(key: String) = save(requests().filterNot { it.key == key })

    /** Si ya esta aqui (el archivo en su carpeta de consola). */
    fun arrived(ctx: Context, i: Item): Boolean =
        RomStore.root(ctx)?.let { RomStore.resolve(it, i.system, i.name) }?.isFile == true

    /** Quita los pedidos que ya llegaron. Devuelve los que quedan. */
    @Synchronized
    fun prune(ctx: Context): List<Item> {
        val all = requests()
        val left = all.filterNot { arrived(ctx, it) }
        if (left.size != all.size) save(left)
        return left
    }
}
