package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Backup.kt: lo que el PC guarda de cada consola, en Config.consoleDir(<id>) (por defecto
// <carpeta de datos>/backups/<id>):
//   Ludolog/                       la copia de su carpeta de datos de Ludolog (Mirror)
//   snapshots/<fecha>-<tag>.zip    instantaneas de lo esencial (Backups; Restore.kt las devuelve)
//   saves/<paquete>/               sus respaldos de partidas, y en .app el nombre del emulador
//                                  (SaveBackups; los lee SaveManager.kt)
//   console.txt                    nombre y modelo, que escribe AppState.backup
// Y el dialogo "Back up now…" de Overview (BackupDialog), que llama a AppState.backup.

/**
 * Respaldos de la carpeta de datos de Ludolog de una consola.
 *
 * La copia de esa carpeta en el PC (Mirror) es el respaldo: lo esencial se pone al dia en cada
 * conexion, y un respaldo completo le añade el arte, los videos, el catalogo y los temas, solo lo
 * que cambio. Cada respaldo guarda ademas una instantanea fechada de lo esencial —pocos MB—, para
 * poder volver a como estaba un dia concreto. Se quedan las ultimas [KEEP].
 */
object Backups {
    private const val KEEP = 30

    fun snapshots(consoleId: String) = File(Config.consoleDir(consoleId), "snapshots")

    /** Las instantaneas de esa consola, la mas nueva primero. */
    fun list(consoleId: String): List<File> =
        snapshots(consoleId).listFiles { f -> f.name.endsWith(".zip") }.orEmpty().sortedByDescending { it.name }

    /**
     * Una instantanea fechada de lo esencial de la copia. Devuelve el .zip.
     *
     * [protect]: una que no sale por la rotacion (la que se esta restaurando). Se escribe como .part y
     * se renombra entera: una a medias (un corte, el disco lleno) no se lista ni cuenta.
     */
    fun snapshot(consoleId: String, tag: String = "essential", protect: File? = null): File {
        val root = Mirror.dir(consoleId)
        val dir = snapshots(consoleId).apply { mkdirs() }
        val out = File(dir, SimpleDateFormat("yyyy-MM-dd_HHmmss").format(Date()) + "-$tag.zip")
        val part = File(out.path + ".part")
        try {
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                for (top in Mirror.ESSENTIAL_ROOTS) {
                    val start = File(root, top)
                    if (!start.exists()) continue
                    start.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(root).invariantSeparatorsPath
                        if (!Mirror.isEssential(rel)) return@forEach
                        zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            java.nio.file.Files.move(part.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (x: Exception) {
            part.delete()
            throw LinkError(LinkError.LOCAL, "couldn't write the snapshot: ${x.message}")
        }
        dir.listFiles { f -> f.name.endsWith(".zip") }.orEmpty().filter { it != protect }
            .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
        return out
    }
}

/**
 * Los respaldos de partidas guardadas que Link hace en la consola (`.bak`, uno por emulador y
 * fecha), copiados al PC en `<respaldos>/<consola>/saves/<emulador>/`. Solo se bajan los que
 * faltan, y aqui no se borra ninguno aunque la consola ya lo haya rotado: el PC es el archivo.
 */
object SaveBackups {
    fun dir(consoleId: String) = File(Config.consoleDir(consoleId), "saves")

    /** Devuelve cuantos bajo. Una consola con un Link sin partidas guardadas: ninguno. */
    fun pull(link: Link, consoleId: String): Int {
        val list = runCatching { link.saveBackups() }.getOrElse { if (it is LinkError && it.status == 404) return 0 else throw it }
        var n = 0
        for ((pkg, name, size, app) in list) {
            if (pkg.isEmpty() || '/' in pkg || '\\' in pkg || pkg.startsWith(".")) continue
            // El nombre viene de la red: un archivo dentro de su carpeta y nada mas.
            if (name.isEmpty() || '/' in name || '\\' in name || ':' in name || name.startsWith(".")) continue
            // El nombre del emulador, para el Save manager (aqui solo esta el paquete).
            runCatching { File(dir(consoleId), pkg).mkdirs(); File(File(dir(consoleId), pkg), ".app").writeText(app) }
            val dest = File(File(dir(consoleId), pkg), name)
            if (dest.isFile && dest.length() == size) continue
            link.saveBackup(pkg, name, dest)
            n++
        }
        return n
    }
}

/** Lo que pesa cada clase de respaldo y lo que falta por bajar, de la lista de la consola. */
data class BackupSizes(val essential: Long, val complete: Long, val toFetchEssential: Long, val toFetchComplete: Long)

@Composable
fun BackupDialog(app: AppState, e: ConsoleEntry, onClose: () -> Unit) {
    val sizes by produceState<BackupSizes?>(null, e.id, e.syncedAt, e.backupProgress == null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val files = e.link().manifest()
                val root = Mirror.dir(e.id)
                fun missing(f: DataFile) = !Mirror.same(File(root, f.path), f)
                BackupSizes(
                    essential = files.filter { it.essential }.sumOf { it.size },
                    complete = files.sumOf { it.size },
                    toFetchEssential = files.filter { it.essential && missing(it) }.sumOf { it.size },
                    toFetchComplete = files.filter { missing(it) }.sumOf { it.size },
                )
            }.getOrNull()
        }
    }
    var complete by remember { mutableStateOf(false) }
    val running = e.backupProgress != null

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Back up ${e.name}") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = sizes
                if (s == null) {
                    Text("Reading…", color = MenuDim)
                } else {
                    for ((full, title, size, fetch, what) in listOf(
                        Quint(false, "Essential", s.essential, s.toFetchEssential,
                            "Settings, logbooks, game edits and consoles."),
                        Quint(true, "Complete", s.complete, s.toFetchComplete,
                            "Also art, videos, catalog and themes. Only changes are copied."),
                    )) {
                        val on = complete == full
                        Column(Modifier.fillMaxWidth().selectedRow(on).clickable(enabled = !running) { complete = full }
                            .padding(horizontal = 12.dp, vertical = 8.dp)) {
                            RowContent(on) {
                                Text("$title · ${Format.size(size)}", style = MaterialTheme.typography.titleSmall,
                                    color = if (on) MenuInk else MenuDim)
                                Text(what, style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                                Text(if (fetch > 0) "${Format.size(fetch)} to copy now" else "Up to date on this PC",
                                    style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                            }
                        }
                    }
                }
                e.backupProgress?.let { (done, total) ->
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    Text("${Format.size(done)} of ${Format.size(total)}", style = MaterialTheme.typography.bodySmall, color = MenuDim)
                }
                Text("Saved in ${Config.consoleDir(e.id).absolutePath}", style = MaterialTheme.typography.bodySmall, color = MenuDim)
            }
        },
        confirmButton = {
            if (running) LTextButton(onClick = { e.backupCancel = true }) { Text("Cancel") }
            else LTextButton(onClick = { app.backup(e, complete); }, enabled = sizes != null) { Text("Back up") }
        },
        dismissButton = { if (!running) LTextButton(onClick = onClose) { Text("Close") } },
    )
}

private data class Quint(val full: Boolean, val title: String, val size: Long, val fetch: Long, val what: String)
