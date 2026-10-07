package com.felp.ludolog.kit

/**
 * Lo que tienen que acordar Ludolog Link de la consola y Ludolog Link del PC.
 * Este archivo lo compilan las dos apps: si una regla cambia, cambia en las dos.
 */
object Protocol {
    /** Nombre en la respuesta a la busqueda: solo se atiende a quien dice ser esto. */
    const val APP = "ludolog-link"
    const val HTTP_PORT = 53350
    const val DISCOVERY_PORT = 53351
    const val DISCOVERY_MAGIC = "LUDOLOG_LINK_DISCOVER"
    const val DISCOVERY_QUERY = "LUDOLOG_LINK_DISCOVER v1"
    /**
     * La busqueda de otra consola (para emparejarse o encontrarla en otra IP), aparte de la del PC:
     * la consola contesta a esta siempre que comparta con sus devices, y a la del PC solo dice si
     * tiene PC Link encendido (`pcLink` en la respuesta). Las de antes mandaban DISCOVERY_MAGIC a secas.
     */
    const val DISCOVERY_DEVICE = "LUDOLOG_LINK_DISCOVER device"

    /** Sufijo de los archivos a medio recibir: ocultos y nunca listados. */
    const val PART_SUFFIX = ".ludolink-part"

    /** sistema/archivo o sistema/subcarpeta/archivo, nunca mas hondo. */
    const val MAX_DEPTH = 3

    // Prohibidos en Windows y en las SD en FAT/exFAT: un nombre que valga en la
    // consola tiene que poder bajarse al PC tal cual, y al reves.
    private const val FORBIDDEN = "<>:\"|?*\\/"

    /** Motivo por el que un nombre de archivo no vale, o null si vale. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "the name is empty"
        name == "." || name == ".." -> "invalid name"
        name.startsWith(".") -> "can't start with a dot"
        name.any { it in FORBIDDEN || it.code < 32 } -> "can't contain < > : \" | ? * / \\"
        name.endsWith(".") || name.endsWith(" ") -> "can't end with a dot or a space"
        name.length > 200 -> "too long"
        else -> null
    }

    /** Partes de una ruta dentro de la carpeta de ROMs, o null si no es segura. */
    fun safeParts(system: String, rel: String): List<String>? {
        val parts = listOf(system) + rel.split('/')
        if (parts.size > MAX_DEPTH) return null
        if (parts.any { nameProblem(it) != null }) return null
        return parts
    }

    /** Extension en minusculas con el punto (".chd"), o "" si no tiene. */
    fun extOf(name: String): String {
        val base = name.substringAfterLast('/')
        val i = base.lastIndexOf('.')
        return if (i > 0) base.substring(i).lowercase() else ""
    }

    /** Nombre sin extension. */
    fun stemOf(name: String): String {
        val base = name.substringAfterLast('/')
        val i = base.lastIndexOf('.')
        return if (i > 0) base.substring(0, i) else base
    }
}

object Format {
    /** Tamanos como los muestra Windows (1 GB = 1024³ bytes). */
    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "?"
        val units = listOf("TB" to (1L shl 40), "GB" to (1L shl 30), "MB" to (1L shl 20), "KB" to (1L shl 10))
        for ((unit, scale) in units) {
            if (bytes >= scale) return "%.1f %s".format(bytes.toDouble() / scale, unit)
        }
        return "$bytes B"
    }

    fun duration(seconds: Long): String = when {
        seconds < 60 -> "${maxOf(1, seconds)} s"
        seconds < 3600 -> "${(seconds + 30) / 60} min"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}
