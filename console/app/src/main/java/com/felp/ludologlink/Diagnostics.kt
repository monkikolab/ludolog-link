package com.felp.ludologlink

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.felp.ludolog.kit.Redact
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * El diagnostico para adjuntar a un aviso de fallo (07-10-2026): un zip en Download con la version,
 * el aparato, los ajustes de Link, su actividad, su registro reciente y como acabaron sus ultimos
 * procesos.
 *
 * Va a un aviso publico en GitHub: los tokens de emparejamiento no entran nunca (son la llave de
 * cada consola y PC emparejados), y las dos ultimas cifras de cada IP van tapadas.
 */
object Diagnostics {

    /** Escribe el zip en Download y devuelve donde quedo. Bloquea: fuera del hilo de la pantalla. */
    fun export(ctx: Context): String {
        val name = "ludolog-link-diagnostics-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Download is not available")
        ctx.contentResolver.openOutputStream(uri).use { out ->
            ZipOutputStream(out ?: error("Download is not available")).use { z ->
                put(z, "info.txt", info(ctx))
                put(z, "settings.txt", settings(ctx))
                put(z, "activity.jsonl", File(ctx.filesDir, "activity.jsonl").takeIf { it.isFile }?.readText().orEmpty())
                put(z, "log.txt", logcat())
                put(z, "exits.txt", exits(ctx))
            }
        }
        return "Download/$name"
    }

    private fun put(z: ZipOutputStream, name: String, text: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(Redact.text(text).toByteArray())
        z.closeEntry()
    }

    private fun info(ctx: Context): String = buildString {
        appendLine("${Dev.name} ${BuildInfo.VERSION}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), named «${Prefs.deviceName(ctx)}»")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val ludolog = runCatching { ctx.packageManager.getPackageInfo(Ludolog.PACKAGE, 0).versionName }.getOrNull()
        appendLine("Ludolog: ${ludolog ?: "not installed"}")
        appendLine("ROM folder: ${Prefs.romsRoot(ctx) ?: "not set"}")
        appendLine("PC Link: ${if (Prefs.pcLink(ctx)) "on" else "off"}, sync between devices: ${if (Prefs.syncDevices(ctx)) "on" else "off"}")
        appendLine("Paired PCs: ${Prefs.tokens(ctx).size}")
    }

    /** Los ajustes de Link, sin los tokens de emparejamiento (sueltos o dentro de cada consola). */
    private fun settings(ctx: Context): String {
        val token = Regex("\"(token|back)\"\\s*:\\s*\"[^\"]*\"")
        return ctx.getSharedPreferences("link", Context.MODE_PRIVATE).all.toSortedMap().entries.joinToString("\n") { (k, v) ->
            when {
                k.contains("token", ignoreCase = true) -> "$k = <hidden>"
                else -> "$k = " + token.replace(v.toString()) { m -> "\"${m.groupValues[1]}\":\"<hidden>\"" }
            }
        }
    }

    /** El registro de este proceso: Android solo deja a cada app leer el suyo. */
    private fun logcat(): String = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.takeLast(2_000_000)
    }.getOrElse { "logcat failed: ${it.message}" }

    /** Como acabaron los ultimos procesos de Link: un cierre del sistema, un fallo, un ANR. */
    private fun exits(ctx: Context): String = buildString {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return@buildString
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (e in runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10) }.getOrDefault(emptyList())) {
            appendLine("${stamp.format(Date(e.timestamp))}  reason ${e.reason}  importance ${e.importance}  ${e.description.orEmpty()}")
        }
    }
}
