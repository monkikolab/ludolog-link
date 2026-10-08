package com.felp.ludologlink.pc

import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.io.File

/**
 * Una linea del registro: cuando, de que va ([kind]), que paso y si fallo. [source]: quien la
 * apunto ("pc", o el id de la consola). [device]: con que consola tuvo que ver lo del PC (nulo: con
 * ninguna en concreto, como sincronizar los Companion de todas).
 */
data class LogEntry(val t: Long, val kind: String, val text: String, val error: Boolean, val source: String, val device: String?) {
    fun json(): JSONObject = JSONObject().put("t", t).put("kind", kind).put("text", text)
        .apply { if (error) put("error", true); device?.let { put("device", it) } }

    companion object {
        fun of(j: JSONObject, source: String) = LogEntry(j.getLong("t"), j.optString("kind", "link"), j.optString("text"),
            j.optBoolean("error"), source, j.optString("device").ifEmpty { null })
    }
}

/**
 * El registro que ensena la pestaña Log de cada consola: lo que hizo este PC con ella (subidas,
 * respaldos, arte, ajustes...) y lo que apunto la consola en su actividad (partidas sincronizadas,
 * Companion compartido, lo que hicieron otras consolas y el PC), que se le pide con GET /log.
 *
 * En `log/` de PcDirs.home: `pc.jsonl`, lo del PC, y `<id>.jsonl`, la copia de lo de cada
 * consola, para verlo tambien sin conexion. Una linea JSON por entrada; quedan las ultimas [KEEP].
 * Los `kind` son los de la consola (LinkLog alli): saves, companion, roms, files, art, settings,
 * backup, ludolog, pairing, link.
 */
object PcLog {
    private const val KEEP = 3000
    private val dir = File(PcDirs.home, "log")
    private val cache = HashMap<String, MutableList<LogEntry>>()

    /** Sube con cada cambio: la pestaña vuelve a leer. */
    val version = mutableIntStateOf(0)

    /** Los registros guardados, del PC y de cada consola: para el diagnostico. */
    fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()

    private fun file(source: String) = File(dir, (if (source == "pc") "pc" else source.filter { it.isLetterOrDigit() }) + ".jsonl")

    private fun entries(source: String): MutableList<LogEntry> = cache.getOrPut(source) {
        val f = file(source)
        if (!f.isFile) mutableListOf()
        else f.readLines().mapNotNullTo(mutableListOf()) { l -> runCatching { LogEntry.of(JSONObject(l), source) }.getOrNull() }
    }

    private fun append(source: String, new: List<LogEntry>) {
        if (new.isEmpty()) return
        val list = entries(source)
        list += new
        runCatching {
            dir.mkdirs()
            val f = file(source)
            if (list.size > KEEP + KEEP / 4) {
                val keep = list.takeLast(KEEP)
                list.clear(); list += keep
                val tmp = File(f.path + ".part")
                tmp.writeText(keep.joinToString("") { it.json().toString() + "\n" })
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } else f.appendText(new.joinToString("") { it.json().toString() + "\n" })
        }
        version.intValue++
    }

    /** Algo que hizo este PC; [device]: con que consola. */
    @Synchronized
    fun add(device: String?, kind: String, text: String, error: Boolean = false) =
        append("pc", listOf(LogEntry(System.currentTimeMillis(), kind, text, error, "pc", device)))

    /** Lo ultimo que se tiene de la consola [id]: desde ahi se le pide lo nuevo. */
    @Synchronized
    fun lastFrom(id: String): Long = entries(id).maxOfOrNull { it.t } ?: 0L

    /** Lo que mando la consola [id], sin repetir lo que ya estaba. */
    @Synchronized
    fun merge(id: String, got: List<LogEntry>) {
        val last = lastFrom(id)
        append(id, got.filter { it.t > last }.sortedBy { it.t })
    }

    /** Lo de la consola [id] y lo del PC con ella (o con ninguna en concreto), de lo mas nuevo a lo mas viejo. */
    @Synchronized
    fun forDevice(id: String): List<LogEntry> =
        (entries(id) + entries("pc").filter { it.device == id || it.device == null }).sortedByDescending { it.t }
}
