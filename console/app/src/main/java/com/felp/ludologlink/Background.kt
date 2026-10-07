package com.felp.ludologlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.Look

/**
 * Lo que necesita Link para seguir escuchando a los devices con la pantalla apagada, y que solo
 * puede conceder la persona: Android sin restricciones de bateria para Link, y lo de cada fabricante
 * que cierra apps por su cuenta (el limpiador de procesos de algunas consolas, el inicio automatico de
 * algunos telefonos). Ver docs/limitations.md.
 */
object Background {
    /** La app de ajustes del fabricante donde esta su limpiador de procesos (nombre de paquete real). */
    private const val CLEANER_SETTINGS = "com.rp.settings"

    /** Android no le aplica a Link el ahorro de bateria (puede seguir en segundo plano y con red). */
    fun batteryOk(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** La ventana de Android que lo pide (la persona acepta ahi); si no la hay, la lista de apps. */
    fun askBattery(a: Activity) {
        runCatching {
            @Suppress("BatteryLife") // Link escucha a los devices todo el rato: es justo su caso.
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        }.onFailure { runCatching { a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    /** Una consola con el limpiador de procesos del fabricante (tiene su app de ajustes). */
    fun hasCleaner(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(CLEANER_SETTINGS, 0); true }.getOrDefault(false)

    /**
     * Si el limpiador de procesos del fabricante deja a Link en paz: apagado, o Link en sus
     * excepciones. Nulo si no se puede saber (la consola no lo tiene, o Android no deja leer ese ajuste).
     */
    fun cleanerOk(ctx: Context): Boolean? {
        if (!hasCleaner(ctx)) return null
        val cr = ctx.contentResolver
        val on = runCatching { Settings.System.getString(cr, "auto_clean_processes") }.getOrNull() ?: return null
        if (on != "1") return true
        val list = runCatching { Settings.System.getString(cr, "auto_clean_ignored_packages") }.getOrNull() ?: return null
        return list.split(',').any { it.trim() == ctx.packageName }
    }

    /** Un fabricante cuyos telefonos bloquean el inicio automatico de las apps (valor real de Build.MANUFACTURER). */
    val hasAutostart get() = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)

    fun openCleanerSettings(a: Activity) {
        a.packageManager.getLaunchIntentForPackage(CLEANER_SETTINGS)?.let { runCatching { a.startActivity(it) } }
    }

    /** La pagina de Link en los ajustes de Android (en algunos telefonos, ahi esta "Inicio automatico"). */
    fun openAppSettings(a: Activity) {
        runCatching { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
    }

}

/**
 * Las filas de [Background], cada una con su estado o su boton. [batteryOk] y [cleanerOk] los lee la
 * actividad al volver (refresh), como el acceso a archivos.
 *
 * La de bateria solo en el wizard ([withBattery]), y como recomendada: Link escucha sin ella (es un
 * servicio en primer plano, con red aun en reposo). Sirve para que vuelva a arrancar solo si algo lo
 * para (Android 15 no se lo deja desde segundo plano) y para que no lo restrinja con el tiempo.
 */
@Composable
fun KeepListeningRows(a: Activity, batteryOk: Boolean, cleanerOk: Boolean?, withBattery: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (withBattery) BgRow("Battery: unrestricted (recommended)", "Lets Link restart on its own.",
            if (batteryOk) "Allowed" else null) { Background.askBattery(a) }
        if (Background.hasCleaner(a)) BgRow("Process cleaner",
            "Some handhelds close apps when the screen turns off: add Ludolog Link to its exceptions." +
                if (cleanerOk == null) " Can't be checked." else "",
            if (cleanerOk == true) "Allowed" else null, "Open") { Background.openCleanerSettings(a) }
        if (Background.hasAutostart) BgRow("Autostart",
            "Some phones block apps from starting on their own: allow it here. Can't be checked.",
            null, "Open") { Background.openAppSettings(a) }
    }
}

@Composable
private fun BgRow(title: String, detail: String, ok: String?, button: String = "Allow", onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok != null) Text(ok, color = Look.ok) else LOutlinedButton(onClick = onClick) { Text(button) }
    }
}
