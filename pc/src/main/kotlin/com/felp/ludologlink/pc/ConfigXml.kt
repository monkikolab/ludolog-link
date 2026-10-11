package com.felp.ludologlink.pc

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * config.xml de Ludolog leido en el PC: el XML de SharedPreferences de Android (string, int,
 * long, float, boolean y set de strings). Solo lectura.
 */
object ConfigXml {
    fun read(file: File): Map<String, Any?> = if (file.isFile) parse { it.parse(file) } else emptyMap()

    /** El de una instantanea, sin sacarlo del zip. */
    fun read(bytes: ByteArray): Map<String, Any?> = parse { it.parse(bytes.inputStream()) }

    /**
     * Sin DOCTYPE ni entidades (revision de seguridad, 07-10-2026): el config.xml lo manda la consola,
     * y con los valores por defecto de Java un DOCTYPE con entidades externas haria a este PC leer
     * sus propios ficheros o pedir una URL. El de Android (SharedPreferences) nunca trae DOCTYPE.
     */
    private fun factory() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }

    private fun parse(open: (javax.xml.parsers.DocumentBuilder) -> org.w3c.dom.Document): Map<String, Any?> {
        val doc = runCatching { open(factory().newDocumentBuilder()) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Any?>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name").ifEmpty { continue }
            val value = e.getAttribute("value")
            out[name] = when (e.tagName) {
                "string" -> e.textContent
                "int" -> value.toIntOrNull()
                "long" -> value.toLongOrNull()
                "float" -> value.toFloatOrNull()
                "boolean" -> value == "true"
                "set" -> e.getElementsByTagName("string").let { s -> (0 until s.length).map { s.item(it).textContent }.toSet() }
                else -> continue
            }
        }
        return out
    }
}

/**
 * Diagnostico: "Ludolog Link.exe" --companion <copia de la carpeta Ludolog> <modelo>. Imprime lo que
 * veria el Companion.
 */
internal fun companionTest(dir: File, model: String) {
    val config = ConfigXml.read(File(dir, "config.xml"))
    val t0 = System.currentTimeMillis()
    val b = CompanionReader.read(dir, model, config["log.console.id"] as? String, config)
    val ms = System.currentTimeMillis() - t0
    val c = b.character
    println("read in $ms ms")
    println("overview: ${b.overview}")
    println("devices: ${b.devices}")
    println("character: level=${c.level} progress=${"%.2f".format(c.progress)} vertices=${c.vertexLevels.mapValues { "%.1f".format(it.value) }}")
    println("consoles: " + b.bySystem.take(8).joinToString { "${it.label}=${it.totalMs / 60000}min" })
    println("top games: " + b.ranking.take(5).joinToString { "${it.title} [${it.system}]" })
    println("recent: " + b.recent.take(3).joinToString { "${it.title} @${it.startedAt}" })
    println("achievements earned: ${b.achievements.count { it.earned }} / ${b.achievements.size}")
    println("missions done: ${b.missionsDone.size}  completions: ${b.completions.size}  lines: ${b.lines}")
}
