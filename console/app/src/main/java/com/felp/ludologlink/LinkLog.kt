package com.felp.ludologlink

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * El registro de lo que hace Link, guardado. Es lo que se ve en "Activity" (LinkState.log) y lo que
 * lee la pestaña Log del PC (GET /log). Antes solo vivia en memoria: 40 lineas que se perdian al
 * reiniciarse Link.
 *
 * Una linea JSON por entrada en `<archivos de la app>/activity.jsonl`: `t` (ms), `kind`, `text` y
 * `error` si algo fallo. Se queda en las ultimas [KEEP]. Se escribe en su propio hilo: quien apunta
 * no espera al disco.
 *
 * Los `kind`, los mismos que usa el PC en lo suyo (PcLog): saves, companion, roms, files, art,
 * settings, backup, ludolog, pairing, link.
 */
object LinkLog {
    private const val KEEP = 2000

    data class Entry(val t: Long, val kind: String, val text: String, val error: Boolean = false) {
        fun json(): JSONObject = JSONObject().put("t", t).put("kind", kind).put("text", text)
            .apply { if (error) put("error", true) }

        companion object {
            fun of(j: JSONObject) = Entry(j.getLong("t"), j.optString("kind", "link"), j.optString("text"), j.optBoolean("error"))
        }
    }

    @Volatile private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "link-log").apply { isDaemon = true } }
    /** Lineas escritas desde el ultimo recorte (solo en el hilo [io]). */
    private var lines = 0

    /** Al arrancar el proceso (SaveCheckProvider.onCreate, lo primero que corre). */
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "activity.jsonl")
        file = f
        // Va primero en la cola: lo que se apunte despues no esta aun en el archivo.
        io.execute {
            val all = readFile(f)
            lines = all.size
            // Lo ultimo, a la pantalla de Link: antes empezaba vacia en cada arranque.
            val last = all.takeLast(40).asReversed().map(LinkState::line)
            LinkState.post {
                LinkState.log.addAll(last)
                while (LinkState.log.size > 40) LinkState.log.removeAt(LinkState.log.size - 1)
            }
        }
    }

    fun add(e: Entry) {
        val f = file ?: return
        io.execute {
            runCatching { f.appendText(e.json().toString() + "\n") }
            if (++lines > KEEP + KEEP / 4) trim(f)
        }
    }

    /** Las entradas posteriores a [since] (ms), de la mas vieja a la mas nueva. */
    fun since(since: Long): List<Entry> {
        val f = file ?: return emptyList()
        // Por el mismo hilo que escribe: nunca se lee una linea a medias.
        return runCatching { io.submit<List<Entry>> { readFile(f).filter { it.t > since } }.get() }.getOrDefault(emptyList())
    }

    private fun readFile(f: File): List<Entry> = if (!f.isFile) emptyList() else
        f.readLines().mapNotNull { l -> runCatching { Entry.of(JSONObject(l)) }.getOrNull() }

    private fun trim(f: File) {
        val keep = readFile(f).takeLast(KEEP)
        val tmp = File(f.path + ".part")
        runCatching {
            tmp.writeText(keep.joinToString("") { it.json().toString() + "\n" })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        lines = keep.size
    }
}
