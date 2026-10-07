package com.felp.ludologlink

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lo que hay en una carpeta, guardado mientras la carpeta no cambie. Leer una lista de ROMs o de arte
 * era mirar cada archivo (su tamaño, su fecha): en la SD ~1 ms por archivo, 5 s con 5.000 ROMs, y el
 * PC la pide a menudo. Ahora cada carpeta se lee una vez y se reusa mientras su fecha sea la misma.
 *
 * Se puede porque crear, renombrar o borrar un archivo cambia la fecha de su carpeta: comprobado en
 * las SD (exFAT) de dos consolas de pruebas (Android 15 con FUSE, Android 13 con sdcardfs). Lo que no
 * la cambia es reescribir un archivo por dentro; con ROMs y arte casi no pasa, y aun asi un listado no
 * vale mas de [MAX_AGE]. Por eso NO se usa para la carpeta de datos de Ludolog (Ludolog.manifest), donde los archivos se reescriben.
 */
object DirIndex {
    /** Un archivo o carpeta de la lista. [size] es 0 en las carpetas. */
    class Item(val name: String, val isDir: Boolean, val size: Long, val mtime: Long) {
        val ext get() = name.substringAfterLast('.', "").lowercase()
        val stem get() = name.substringBeforeLast('.').takeIf { '.' in name } ?: name
    }

    private class Listing(val mtime: Long, val at: Long, val items: List<Item>)

    private const val MAX_AGE = 30 * 60_000L

    /**
     * La fecha de una carpeta va por segundos (o mas gruesa): un cambio en el mismo segundo que la
     * lectura no la moveria. Una carpeta tocada hace menos de esto se vuelve a leer la proxima vez.
     */
    private const val SETTLE = 3_000L

    private val cache = ConcurrentHashMap<String, Listing>()

    /** Lo que hay en [dir] (vacio si no existe). */
    fun list(dir: File): List<Item> {
        val m = dir.lastModified()
        if (m == 0L) { cache.remove(dir.path); return emptyList() }
        val now = System.currentTimeMillis()
        cache[dir.path]?.takeIf { it.mtime == m && now - it.at < MAX_AGE }?.let { return it.items }
        val items = dir.listFiles().orEmpty().map { f ->
            val d = f.isDirectory
            Item(f.name, d, if (d) 0L else f.length(), f.lastModified())
        }
        if (now - m > SETTLE) cache[dir.path] = Listing(m, now, items) else cache.remove(dir.path)
        return items
    }

    /** Las subcarpetas visibles de [dir]. */
    fun dirs(dir: File): List<File> = list(dir).filter { it.isDir && !it.name.startsWith(".") }.map { File(dir, it.name) }

    /** Link escribio, renombro o borro algo: todo se vuelve a leer (es barato, y no hay que adivinar donde). */
    fun clear() = cache.clear()
}
