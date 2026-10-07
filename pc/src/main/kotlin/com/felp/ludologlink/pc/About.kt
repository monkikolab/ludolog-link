package com.felp.ludologlink.pc

import androidx.compose.runtime.mutableStateOf
import com.felp.ludolog.kit.Redact
import com.felp.ludolog.kit.ReleaseCheck
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Si hay una version nueva de Ludolog Link en GitHub (07-10-2026; ver ReleaseCheck en el kit).
 * Como mucho una vez al dia al arrancar, o ya desde About. Se avisa una sola vez de cada version.
 */
object PcUpdates {
    /** La version de este programa: la de version.properties de ludolog-front-end (ver build.gradle.kts). */
    val current: String by lazy {
        PcUpdates::class.java.getResourceAsStream("/link/version.txt")?.use { it.reader().readText().trim() }.orEmpty()
    }

    /** La ultima version publicada que se conoce. */
    val latest = mutableStateOf(Config.updateLatest)

    /**
     * Pregunta a GitHub: con `force`, ya; si no, solo si esta encendido y paso un dia. Devuelve el
     * resultado de la pregunta (null si no se pregunto) y, aparte, el aviso que hay que dar una vez.
     * Bloquea: fuera del hilo de la pantalla.
     */
    fun check(force: Boolean): Pair<Result<String>?, String?> {
        if (!force && !Config.updateCheck) return null to null
        var result: Result<String>? = null
        if (force || System.currentTimeMillis() - Config.updateAt >= ReleaseCheck.DAY_MS) {
            result = ReleaseCheck.fetch("Ludolog Link (PC)")
            Config.updateAt = System.currentTimeMillis()
            result.onSuccess { Config.updateLatest = it }
        }
        val v = Config.updateLatest
        latest.value = v
        if (v == null || !ReleaseCheck.newer(v, current) || Config.updateNotified == v) return result to null
        Config.updateNotified = v
        return result to "Ludolog Link $v is available: Settings → About."
    }
}

/**
 * El diagnostico para adjuntar a un aviso de fallo (07-10-2026): un zip en Descargas con la version,
 * el sistema, la configuracion y los registros de este PC y de cada consola.
 *
 * Va a un aviso publico en GitHub: los tokens de emparejamiento no entran (ver Config.redacted), las
 * dos ultimas cifras de cada IP y el nombre del usuario de Windows van tapados.
 */
object PcDiagnostics {

    /** Escribe el zip y devuelve el archivo. Bloquea: fuera del hilo de la pantalla. */
    fun export(): File {
        val home = File(System.getProperty("user.home"))
        val dir = File(home, "Downloads").takeIf { it.isDirectory } ?: home
        val out = File(dir, "ludolog-link-pc-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip")
        ZipOutputStream(out.outputStream()).use { z ->
            put(z, "info.txt", info())
            put(z, "config.json", Config.redacted())
            PcLog.files().forEach { f -> put(z, "log/${f.name}", f.readText().takeLast(2_000_000)) }
        }
        return out
    }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(Redact.text(text, System.getProperty("user.name")).toByteArray())
        z.closeEntry()
    }

    private fun info(): String = buildString {
        appendLine("Ludolog Link (PC) ${PcUpdates.current}")
        appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        appendLine("Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        appendLine("Processors: ${Runtime.getRuntime().availableProcessors()}, max memory: ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB")
        appendLine("Paired devices: ${Config.known().size}")
        appendLine("Backups: ${Config.backupRoot.absolutePath}")
        appendLine("PC catalog: ${PcCatalog.configured?.absolutePath ?: "not set"}")
    }
}
