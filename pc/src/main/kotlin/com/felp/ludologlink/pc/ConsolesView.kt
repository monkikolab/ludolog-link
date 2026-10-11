package com.felp.ludologlink.pc

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow

/**
 * Consoles: las consolas de todos los devices conectados, una fila cada una (por su id en Ludolog),
 * con cuantos juegos tiene en cada device. Elegir una abre lo suyo que va a todos: el nombre y la
 * descripcion (los de Ludolog, `name.sys.<id>` y `desc.sys.<id>`), que viajan con Ludolog Link como
 * los de un juego. Vacio: lo del catalogo de consolas.
 *
 * Es una pestaña de todos (AppState.globalTab = "consoles", ver ConsoleView). Guardar llama a
 * AppState.saveConsoleInfo, que lo manda a cada device con POST /meta/edit.
 */
@Composable
fun ConsolesView(app: AppState) {
    val devices = app.consoles.filter { it.paired && it.online == true && it.info != null && it.info?.ludolog != null }

    /** Una consola: su id en Ludolog, su nombre de catalogo y, por device, su carpeta y sus juegos. */
    class Sys(val id: String, val catalogName: String, val where: Map<String, SystemDir>)

    val systems = remember(devices.map { listOf(it.id, it.consoleDirs, it.roms) }) {
        val by = LinkedHashMap<String, MutableMap<String, SystemDir>>()
        val names = HashMap<String, String>()
        for (d in devices) for (dir in d.consoleDirs) {
            if (Shortcuts.isShortcutFolder(dir.folder) || dir.count == 0) continue
            // El id de Ludolog: el de sus juegos en esa carpeta; si no hay, la carpeta.
            val id = d.roms.firstOrNull { it.system.equals(dir.folder, true) }?.let { Names.game(d, it)?.systemId } ?: dir.folder
            by.getOrPut(id.lowercase()) { LinkedHashMap() }[d.id] = dir
            names.putIfAbsent(id.lowercase(), dir.fullName)
        }
        by.map { (id, w) -> Sys(id, names[id] ?: id, w) }.sortedBy { it.catalogName.lowercase() }
    }
    fun own(d: ConsoleEntry, s: Sys, field: String): String {
        val k = "$field.sys.${s.id}"
        return ((if (k in d.pending) d.pending[k] else d.ludologConfig[k]) as? String)?.trim().orEmpty()
    }
    fun where(s: Sys) = devices.filter { it.id in s.where }
    // Lo que de verdad se ve: un nombre puesto igual al del catalogo no es un conflicto.
    fun differs(s: Sys, field: String) = where(s).map { own(it, s, field).ifEmpty { if (field == "name") s.catalogName else "" } }.distinct().size > 1

    var chosen by remember { mutableStateOf<String?>(null) }
    val sel = systems.firstOrNull { it.id == chosen }

    Row(Modifier.fillMaxSize().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pane(null, Modifier.weight(1f).fillMaxHeight(), padding = 2) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Console · name in Ludolog", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MenuDim)
                    for (d in devices) Text(d.name, Modifier.width(112.dp), textAlign = TextAlign.Center, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, color = MenuDim)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (systems.isEmpty()) Text(if (devices.isEmpty()) "No device with Ludolog connected." else "No consoles yet.",
                        Modifier.align(Alignment.Center), color = MenuDim)
                    val state = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        items(systems, key = { it.id }) { s ->
                            val on = s.id == chosen
                            val custom = where(s).map { own(it, s, "name") }.firstOrNull { it.isNotEmpty() }
                            Row(Modifier.fillMaxWidth().selectedRow(on).clickable { chosen = if (on) null else s.id }
                                .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                RowContent(on) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(custom ?: s.catalogName, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MenuInk)
                                            if (differs(s, "name") || differs(s, "desc")) MixedDot()
                                        }
                                        Text(s.id + if (custom != null) " · ${s.catalogName}" else "", style = MaterialTheme.typography.bodySmall,
                                            color = MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    for (d in devices) Text(s.where[d.id]?.let { "${it.count}" } ?: "–", Modifier.width(112.dp),
                                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                                        color = if (d.id in s.where) MenuInk else MenuDim)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        }
        sel?.let { s -> ConsolePanelInfo(app, s.id, s.catalogName, where(s), { d, f -> own(d, s, f) }, Modifier.width(340.dp).fillMaxHeight()) { chosen = null } }
    }
}

/** El panel de una consola: su nombre y su descripcion, para todos los devices que la tienen. */
@Composable
private fun ConsolePanelInfo(app: AppState, id: String, catalogName: String, targets: List<ConsoleEntry>,
                             own: (ConsoleEntry, String) -> String, modifier: Modifier, onClose: () -> Unit) {
    val fields = listOf("name" to "Name", "desc" to "Description")
    val initial = remember(id, targets.map { own(it, "name") + "|" + own(it, "desc") }) {
        fields.associate { (k, _) -> k to (targets.map { own(it, k) }.firstOrNull { it.isNotEmpty() }.orEmpty()) }
    }
    var draft by remember(id, initial) { mutableStateOf(initial) }
    Pane(null, modifier, padding = 10) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(draft["name"].orEmpty().ifEmpty { catalogName }, style = MaterialTheme.typography.titleMedium, color = MenuInk)
                    Text(id, style = MaterialTheme.typography.bodySmall, color = MenuDim)
                }
                LTextButton(onClick = onClose) { Text("Close") }
            }
            for ((k, label) in fields) {
                OutlinedTextField(value = draft[k].orEmpty(), onValueChange = { v -> draft = draft + (k to v) },
                    label = { Text(label) }, placeholder = if (k == "name") ({ Text(catalogName) }) else null,
                    singleLine = k == "name", minLines = if (k == "desc") 5 else 1, maxLines = if (k == "desc") 12 else 1,
                    shape = Look.shape, modifier = Modifier.fillMaxWidth())
                // Lo que se ve en cada device, si no es lo mismo: un clic lo toma.
                fun eff(d: ConsoleEntry) = own(d, k).ifEmpty { if (k == "name") catalogName else "" }
                val versions = targets.groupBy(::eff).map { (shown, ds) ->
                    val isDefault = ds.any { own(it, k).isEmpty() }
                    Version(if (isDefault) "" else own(ds.first(), k), shown, isDefault, ds.map { it.name })
                }
                if (versions.size > 1) Variants(versions) { v -> draft = draft + (k to v) }
            }
            Text("Goes to every device with this console. Empty: default.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
            // Lo cambiado, y lo que no dice lo mismo en todos.
            val changes = fields.map { it.first }.filter { k ->
                draft[k].orEmpty().trim() != initial[k].orEmpty().trim() ||
                    targets.map { own(it, k).ifEmpty { if (k == "name") catalogName else "" } }.distinct().size > 1
            }.associateWith { draft[it].orEmpty().trim() }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LButton(onClick = { app.saveConsoleInfo(targets.map { it to id }, changes) }, enabled = changes.isNotEmpty() && targets.isNotEmpty()) {
                    Text("Save")
                }
                LTextButton(onClick = { draft = initial }, enabled = draft != initial) { Text("Undo") }
            }
        }
    }
}
