package com.felp.ludologlink

import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Devolver partidas desde los respaldos de este device (los .bak de Saves.backup), sin el PC: lo
 * mismo que el Save manager del PC (SaveManager.kt), con los respaldos de aqui. Cada .bak es un zip
 * con la carpeta de partidas del emulador; se ordena por juego (y por core, si guarda en subcarpetas).
 */
object SaveRestore {

    class Entry(val path: String, val size: Long, val time: Long, val crc: Long)

    /** Una version de las partidas de un juego, tal como esta en [bak] (el respaldo mas nuevo que la tiene). */
    class Version(val bak: File, val at: Long, val why: String, val entries: List<Entry>, val copies: Int) {
        val saved: Long get() = entries.maxOf { it.time }
        val size: Long get() = entries.sumOf { it.size }
    }

    class SaveGame(val key: String, val title: String, val group: String, val versions: List<Version>)

    private val STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

    /** El juego de un archivo de partida, como se escribe: la misma regla que Saves.saveKey. */
    fun title(path: String): String = Saves.saveTitle(path).trim()

    /** Los juegos con partidas en los respaldos de [pkg] aqui, con sus versiones distintas (la mas nueva primero). */
    fun games(ctx: Context, pkg: String): List<SaveGame> {
        val perGame = LinkedHashMap<String, MutableList<Version>>()
        val titles = HashMap<String, Pair<String, String>>()
        for (b in Saves.backupsOf(ctx, pkg)) {
            val base = b.name.removeSuffix(".bak")
            val at = runCatching { STAMP.parse(base.take(17))!!.time }.getOrDefault(b.lastModified())
            val why = base.drop(18).ifEmpty { "backup" }
            val entries = runCatching {
                ZipFile(b).use { z -> z.entries().asSequence().filter { !it.isDirectory }.map { Entry(it.name, it.size, it.time, it.crc) }.toList() }
            }.getOrNull() ?: continue
            for ((key, list) in entries.groupBy { it.path.substringBeforeLast('/', "") + "|" + title(it.path).lowercase() }) {
                titles.putIfAbsent(key, title(list.first().path) to list.first().path.substringBeforeLast('/', ""))
                val versions = perGame.getOrPut(key) { mutableListOf() }
                val sig = list.map { Triple(it.path, it.size, it.crc) }.sortedBy { it.first }
                val same = versions.indexOfFirst { v -> v.entries.map { Triple(it.path, it.size, it.crc) }.sortedBy { it.first } == sig }
                if (same >= 0) versions[same].let { versions[same] = Version(it.bak, it.at, it.why, it.entries, it.copies + 1) }
                else versions += Version(b, at, why, list, 1)
            }
        }
        return perGame.map { (key, v) -> titles.getValue(key).let { (t, g) -> SaveGame(key, t, g, v) } }
            .sortedWith(compareBy({ it.group.lowercase() }, { it.title.lowercase() }))
    }

    /**
     * Devuelve [v] a la carpeta del emulador: antes respalda lo que hay ("before-restore"); no con un
     * juego abierto ni si el emulador no se puede sincronizar aqui. Solo esos archivos; los demas no se
     * tocan. Las otras consolas lo reciben en su proximo sync. Nulo si fue bien; si no, el motivo.
     */
    fun restore(ctx: Context, e: EmuSaves, v: Version): String? = try {
        when {
            !e.configured || !e.folder.isDirectory -> "Link can't open this emulator's saves folder"
            !Saves.supported(e) -> "Emulator not supported on this device"
            Saves.inUse(ctx, e.pkg) -> "A game is open in ${Saves.appName(ctx, e.pkg)}: close it first"
            else -> {
                // Sin rotar [v.bak]: si era el mas viejo, el respaldo nuevo lo sacaba y se perdia.
                Saves.backup(ctx, e, "before-restore", protect = v.bak)
                ZipFile(v.bak).use { z ->
                    for (x in v.entries) {
                        val entry = z.getEntry(x.path) ?: throw IOException("the backup lost ${x.path}")
                        val dest = Saves.fileIn(e, x.path) ?: throw IOException("invalid path ${x.path}")
                        dest.parentFile?.mkdirs()
                        val tmp = File(dest.parentFile, "." + dest.name + "." + System.nanoTime() + Saves.PART)
                        try {
                            z.getInputStream(entry).use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                            // renameTo reemplaza: sin borrar antes, un fallo no deja sin ninguno.
                            if (!tmp.renameTo(dest)) throw IOException("couldn't write ${dest.name}")
                        } finally {
                            tmp.delete()
                        }
                        // La fecha del guardado original, no la de ahora.
                        if (x.time > 0) dest.setLastModified(x.time)
                    }
                }
                LinkState.addLog("Restored ${title(v.entries.first().path)} (${Saves.appName(ctx, e.pkg)}) from a backup", "saves")
                null
            }
        }
    } catch (x: Exception) {
        x.message ?: x.javaClass.simpleName
    }
}
