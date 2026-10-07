package com.felp.ludologlink.pc

import java.io.File

/**
 * La copia en este PC de la carpeta de datos de Ludolog de una consola.
 *
 * De ella lee el Companion del PC (las mismas cuentas que en la consola, ver CompanionReader), y
 * es la base del respaldo. Lo esencial —ajustes, cuadernos, fichas, catalogo de consolas del
 * usuario— se pone al dia solo al conectar: son pocos megas. El resto (arte, videos, catalogo de
 * juegos, temas) solo en un respaldo completo.
 *
 * Solo baja lo que cambio: mismo tamaño y misma fecha que en la consola es el mismo archivo. Los
 * cuadernos se comparan por fecha, porque su copia coherente no mide lo mismo que el original.
 */
object Mirror {
    /** `<datos del PC>/consoles/<id>/Ludolog`. */
    fun dir(consoleId: String) = File(Config.consoleDir(consoleId), "Ludolog")

    data class Result(val copied: Int, val bytes: Long, val removed: Int, val total: Int)

    fun same(local: File, f: DataFile) =
        local.isFile && local.lastModified() == f.mtime && (f.path.endsWith(".db") || local.length() == f.size)

    /**
     * Pone al dia la copia de [consoleId]. [essentialOnly]: solo lo esencial. [progress] recibe
     * (hechos, total) en bytes de lo que hay que bajar.
     */
    fun sync(
        link: Link, consoleId: String, essentialOnly: Boolean,
        progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false },
    ): Result {
        val root = dir(consoleId)
        // Las rutas vienen de la red: solo las que caen dentro de la copia (sin `..`, ni absolutas).
        val rootPath = root.canonicalPath + File.separator
        val files = link.manifest().filter { !essentialOnly || it.essential }.filter { f ->
            !f.path.split('/').any { it == ".." || it.isEmpty() } && !f.path.contains(':') &&
                runCatching { File(root, f.path).canonicalPath.startsWith(rootPath) }.getOrDefault(false)
        }
        val todo = files.filter { !same(File(root, it.path), it) }
        val total = todo.sumOf { it.size }
        var done = 0L
        var copied = 0
        for (f in todo) {
            if (cancelled()) throw LinkError(0, "cancelled")
            if (link.ludologFile(f.path, File(root, f.path))) copied++
            done += f.size
            progress(done, total)
        }
        // Lo que ya no esta en la consola sale de la copia, dentro de lo que se sincroniza: un
        // cuaderno de otra consola que se quito no debe seguir contando en el Companion.
        //
        // Sin minusculas/mayusculas: en Windows media/GBA y media/gba son la misma carpeta, y se
        // borraba lo recien bajado. Y nunca si la consola no tiene ya sus ajustes (Ludolog
        // reinstalado, la carpeta vacia o sin leer): eso no es "se quito", y se vaciaba la copia.
        val keep = files.mapTo(HashSet()) { it.path.lowercase() }
        var removed = 0
        val trustworthy = files.any { it.path == "config.xml" }
        if (trustworthy && root.isDirectory) {
            val tops = if (essentialOnly) ESSENTIAL_ROOTS.map { File(root, it) }.filter { it.exists() } else listOf(root)
            for (top in tops) top.walkBottomUp().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    val inScope = !essentialOnly || isEssential(rel)
                    if (inScope && rel.lowercase() !in keep && f.delete()) removed++
                } else if (f != root && f.listFiles()?.isEmpty() == true) f.delete()
            }
        }
        return Result(copied, done, removed, files.size)
    }

    /** Donde puede haber algo esencial: para recorrer solo eso y no la copia entera (arte, videos). */
    val ESSENTIAL_ROOTS = listOf("config.xml", "systems.toml", "tvs.toml", "companion", "dossiers", "systems")

    /** Lo mismo que decide la consola (Ludolog.essential en Link), para limpiar sin pedir la lista. */
    fun isEssential(rel: String) =
        rel == "config.xml" || rel == "systems.toml" || rel == "tvs.toml" ||
            (rel.startsWith("companion/") && rel.endsWith(".db")) ||
            rel.startsWith("dossiers/") || rel.startsWith("systems/")
}
