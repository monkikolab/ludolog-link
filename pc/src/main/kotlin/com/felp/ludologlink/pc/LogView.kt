package com.felp.ludologlink.pc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Las clases de entrada, en el orden de los filtros, con su nombre a la vista. Ver PcLog. */
private val KINDS = listOf(
    "saves" to "Saves", "companion" to "Companion", "roms" to "ROMs", "files" to "Files", "art" to "Art",
    "settings" to "Settings", "backup" to "Backups", "ludolog" to "Ludolog", "pairing" to "Pairing", "link" to "Link",
)

/**
 * La pestaña Log de una consola: lo que hizo este PC con ella y lo que apunto ella en su actividad
 * (partidas, Companion, lo que hicieron otras consolas), en una sola lista, lo mas nuevo arriba.
 * Con la consola a la vista se pide lo nuevo cada 10 s; sin ella, se ve la copia de la ultima vez.
 */
@Composable
fun LogView(app: AppState, e: ConsoleEntry) {
    val version by PcLog.version
    var source by remember(e.id) { mutableStateOf("all") }      // all, device, pc
    var kind by remember(e.id) { mutableStateOf<String?>(null) }
    var note by remember(e.id) { mutableStateOf<String?>(null) }
    val clock = remember { SimpleDateFormat("d MMM  HH:mm:ss", Locale.US) }

    // Pedir lo nuevo mientras se mira. Lo pide la pestaña, no un bucle de fondo: no mantiene PC Link encendido.
    LaunchedEffect(e.id, e.online) {
        while (true) {
            note = if (e.paired && e.online == true) withContext(Dispatchers.IO) { app.loadLog(e) }
                else "Not connected: showing the saved copy."
            delay(10_000)
        }
    }
    val all = remember(version, e.id) { PcLog.forDevice(e.id) }
    val shown = all.filter { (source == "all" || (source == "pc") == (it.source == "pc")) && (kind == null || it.kind == kind) }
    val name = e.name.ifBlank { e.model }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, label) in listOf("all" to "Everything", "device" to name, "pc" to "This PC")) Chip(label, source == id) { source = id }
            Box(Modifier.weight(1f))
            Text("${shown.size} ${if (shown.size == 1) "entry" else "entries"}", style = MaterialTheme.typography.bodySmall, color = MenuDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip("All", kind == null) { kind = null }
            val present = all.mapTo(HashSet()) { it.kind }
            for ((id, label) in KINDS) if (id in present) Chip(label, kind == id) { kind = if (kind == id) null else id }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Look.warn) }

        Pane(null, Modifier.fillMaxWidth().weight(1f), padding = 2) {
            if (shown.isEmpty()) Text(if (all.isEmpty()) "Nothing yet." else "Nothing with these filters.",
                Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MenuDim)
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown) { x ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Text(clock.format(Date(x.t)), Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MenuDim)
                        Text(if (x.source == "pc") "PC" else name, Modifier.width(130.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = if (x.source == "pc") Look.accent else MenuInk)
                        Text(KINDS.firstOrNull { it.first == x.kind }?.second ?: x.kind, Modifier.width(90.dp),
                            style = MaterialTheme.typography.bodySmall, color = MenuDim)
                        Text(x.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = if (x.error) Look.danger else MenuInk)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.selectedRow(on).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)) {
        RowContent(on) { Text(label, maxLines = 1, color = if (on) MenuInk else MenuDim) }
    }
}
