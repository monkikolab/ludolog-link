package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import java.util.zip.ZipFile

/**
 * Devolver a la consola lo de una instantanea (Backups), por partes.
 *
 * Link no escribe en la carpeta de Ludolog: deja los archivos en espera y Ludolog los pone en su
 * sitio al reiniciarse, sin partida en curso (LinkRestore en Ludolog; docs/ludolog-link.md).
 * Los ajustes van como diferencia con el config.xml de hoy, por el mismo camino que "Sync to
 * device". Antes de nada se guarda una instantanea de como esta: restaurar se puede deshacer.
 *
 * Aqui estan las partes, la diferencia de ajustes (configDiff, configChanges, que usa tambien
 * AppState.syncConfig) y el dialogo de Overview (RestoreDialog). El envio lo hace AppState.restore,
 * con DELETE, PUT y POST a /ludolog/restore.
 */
enum class RestorePart(val title: String, val what: String) {
    SETTINGS("Settings", "Options, favorites, play counts, names and emulators."),
    LOGBOOKS("Logbooks", "Sessions, achievements, missions. Later play is lost."),
    GAME_INFO("Game info", "Your edits to game info."),
    SYSTEMS("Consoles", "Consoles you added or changed, and TV settings.");

    fun has(rel: String) = when (this) {
        SETTINGS -> rel == "config.xml"
        LOGBOOKS -> rel.startsWith("companion/") && rel.endsWith(".db") && rel.count { it == '/' } == 1
        GAME_INFO -> rel.startsWith("dossiers/")
        SYSTEMS -> rel == "systems.toml" || rel == "tvs.toml" || rel.startsWith("systems/")
    }

    companion object {
        fun of(rel: String) = entries.firstOrNull { it.has(rel) }
    }
}

/** Una instantanea en el PC: su zip y cuando se hizo. */
class Snapshot(val file: File) {
    val at: Long = runCatching { SimpleDateFormat("yyyy-MM-dd_HHmmss").parse(file.name.take(17)).time }
        .getOrDefault(file.lastModified())
    /** Guardada sola, justo antes de restaurar otra: para deshacer. */
    val beforeRestore = file.name.endsWith("-before-restore.zip")

    /** Lo que trae de cada parte, en bytes. Lee el indice del zip. */
    fun sizes(): Map<RestorePart, Long> = ZipFile(file).use { z ->
        z.entries().asSequence().filter { !it.isDirectory }
            .mapNotNull { e -> RestorePart.of(e.name)?.let { it to e.size } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
    }
}

/** Lo que nunca se manda a Ludolog: credenciales e identidad de la consola. Igual que LinkConfig. */
private fun fromOutside(key: String) = !key.startsWith("art.") && key != "log.console.id" && key != "log.console.owner"

/** Lo que hay que cambiar para que los ajustes de hoy ([now]) vuelvan a ser [then]. Nulo: quitar. */
internal fun configDiff(then: Map<String, Any?>, now: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in then) if (fromOutside(k) && v != null && now[k] != v) out[k] = v
    for (k in now.keys) if (fromOutside(k) && k !in then) out[k] = null
    return out
}

/** Cambios de ajustes en el formato de config-update.json (ver LinkConfig de Ludolog). */
internal fun configChanges(changes: Map<String, Any?>): org.json.JSONObject {
    val set = org.json.JSONObject()
    val remove = org.json.JSONArray()
    for ((k, v) in changes) {
        if (v == null) { remove.put(k); continue }
        val (t, js) = when (v) {
            is String -> "string" to v
            is Int -> "int" to v
            is Long -> "long" to v
            is Float -> "float" to v.toDouble()
            is Boolean -> "boolean" to v
            is Set<*> -> "set" to org.json.JSONArray(v.map { it.toString() })
            else -> continue
        }
        set.put(k, org.json.JSONObject().put("t", t).put("v", js))
    }
    return org.json.JSONObject().put("set", set).put("remove", remove)
}

@Composable
fun RestoreDialog(app: AppState, e: ConsoleEntry, onClose: () -> Unit) {
    val snapshots by produceState<List<Snapshot>?>(null, e.id) {
        value = withContext(Dispatchers.IO) { Backups.list(e.id).map(::Snapshot) }
    }
    var chosen by remember { mutableStateOf<Snapshot?>(null) }
    val pick = chosen ?: snapshots?.firstOrNull { !it.beforeRestore } ?: snapshots?.firstOrNull()
    val sizes by produceState<Map<RestorePart, Long>>(emptyMap(), pick) {
        value = pick?.let { p -> withContext(Dispatchers.IO) { runCatching { p.sizes() }.getOrDefault(emptyMap()) } } ?: emptyMap()
    }
    var parts by remember { mutableStateOf(RestorePart.entries.toSet()) }
    val running = e.restoring
    val canRestore = e.online == true && e.info?.ludolog?.restore == true
    val fmt = remember { SimpleDateFormat("EEE d MMM yyyy · HH:mm") }

    AlertDialog(
        onDismissRequest = { if (!running) onClose() },
        title = { Text("Restore ${e.name}") },
        text = {
            Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = snapshots
                when {
                    list == null -> Text("Looking for backups…", color = MenuDim)
                    list.isEmpty() -> Text("No backups of this device yet.", color = MenuDim)
                    else -> {
                        Text("Backup", style = MaterialTheme.typography.titleSmall, color = MenuDim)
                        Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                            for (s in list) {
                                val on = s.file == pick?.file
                                Row(Modifier.fillMaxWidth().selectedRow(on).clickable(enabled = !running) { chosen = s }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    RowContent(on) {
                                        Text(fmt.format(Date(s.at)) + if (s.beforeRestore) "  ·  saved before a restore" else "",
                                            style = MaterialTheme.typography.bodyMedium, color = if (on) MenuInk else MenuDim,
                                            modifier = Modifier.weight(1f))
                                        Text(Format.size(s.file.length()), style = MaterialTheme.typography.bodySmall,
                                            color = if (on) MenuInk else MenuDim)
                                    }
                                }
                            }
                        }
                        Text("What to bring back", style = MaterialTheme.typography.titleSmall, color = MenuDim)
                        for (p in RestorePart.entries) {
                            val size = sizes[p]
                            val on = p in parts && size != null
                            Column(Modifier.fillMaxWidth().selectedRow(on)
                                .clickable(enabled = !running && size != null) { parts = if (p in parts) parts - p else parts + p }
                                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                                RowContent(on) {
                                    Text((if (on) "■  " else "□  ") + p.title + (size?.let { " · ${Format.size(it)}" } ?: " · not in this backup"),
                                        style = MaterialTheme.typography.bodyMedium, color = if (on) MenuInk else MenuDim)
                                    Text(p.what, style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                                }
                            }
                        }
                        Text("The device's current data is saved here first. Ludolog restarts to apply it.",
                            style = MaterialTheme.typography.bodySmall, color = MenuDim)
                        if (!canRestore) Text(
                            if (e.online != true) "The device isn't connected."
                            else "Update Ludolog on the device to restore.",
                            style = MaterialTheme.typography.bodySmall, color = Look.warn)
                    }
                }
            }
        },
        confirmButton = {
            LTextButton(
                // Se cierra ya: el resultado llega como aviso, y el boton de Overview dice "Restoring…".
                onClick = { pick?.let { app.restore(e, it, parts.filter { p -> p in sizes }.toSet()) }; onClose() },
                enabled = canRestore && !running && pick != null && parts.any { it in sizes },
                colors = ButtonDefaults.textButtonColors(contentColor = Look.danger),
            ) { Text(if (running) "Restoring…" else "Restore") }
        },
        dismissButton = { if (!running) LTextButton(onClick = onClose) { Text("Close") } },
    )
}
