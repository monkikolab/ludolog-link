package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.ui.KitIcons
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuFaint
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.PanelDivider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope


@Composable
fun ConsoleView(app: AppState, e: ConsoleEntry, window: java.awt.Window) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Games y Consoles son de todos los conectados: se ven igual elija el device que se elija.
        app.globalTab?.let { g ->
            SectionTabs(app, e)
            if (g == "consoles") ConsolesView(app) else GamesView(app, window)
            return@Column
        }
        when {
            // Contesta, pero no atiende al PC: emparejarla o leerla pide PC Link en la consola.
            e.pcLinkOff && (!e.paired || e.book == null) -> MessageCard(
                "PC Link is off on ${e.name.ifBlank { "this device" }}.",
                Look.warn) { LTextButton(onClick = { app.search() }) { Text("Search again") } }
            // Al abrir, o al buscar de nuevo: aun no se sabe si contesta. Ni «no data» ni «not reachable».
            e.paired && e.info == null && (app.searching || e.loading || !app.searchedOnce) -> {
                ConnectingCard(e)
                if (e.book != null) { SectionTabs(app, e); SavedCopyTab(app, e, window) }
            }
            !e.paired -> PairCard(app, e)
            // Sin conexion pero con copia en el PC: el Companion y los ajustes se leen de ella.
            e.info == null && !e.loading && e.book != null -> {
                MessageCard(if (e.pcLinkOff) "PC Link is off: showing the saved copy." else "Not reachable: showing the saved copy.", Look.warn) {
                    LTextButton(onClick = { app.search() }) { Text("Search again") }
                }
                SectionTabs(app, e)
                SavedCopyTab(app, e, window)
            }
            e.info == null -> MessageCard(e.error ?: "No data yet.", Look.warn) {
                LTextButton(onClick = { app.search() }) { Text("Search again") }
                LTextButton(onClick = { app.load(e) }) { Text("Retry") }
            }
            else -> {
                e.error?.let { MessageCard(it, Look.warn) { LTextButton(onClick = { app.load(e) }) { Text("Retry") } } }
                SectionTabs(app, e)
                when (e.tab) {
                    "saves" -> SavesManagerView(app, e)
                    "companion" -> CompanionView(app, e)
                    "settings" -> SettingsView(app, e, window)
                    "log" -> LogView(app, e)
                    else -> OverviewView(app, e)
                }
            }
        }
    }
}

/** Las cuatro secciones de una consola, marcadas como marca el tema la fila elegida. */
@Composable
private fun SectionTabs(app: AppState, e: ConsoleEntry) {
    val tabs = listOf("overview" to "Overview", "games" to "Games", "consoles" to "Consoles", "saves" to "Saves", "companion" to "Companion",
        "settings" to "Settings", "log" to "Log")
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((id, label) in tabs) {
                // Games y Consoles son de todos; las demas, del device elegido.
                val global = id == "games" || id == "consoles"
                val on = app.globalTab?.let { it == id } ?: (e.tab == id)
                Box(Modifier.selectedRow(on).clickable { if (global) app.globalTab = id else { app.globalTab = null; e.tab = id } }.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    RowContent(on) {
                        Text(Look.title(label), style = MaterialTheme.typography.labelLarge,
                            color = if (on) MenuInk else MenuDim)
                    }
                }
            }
        }
        PanelDivider()
    }
}

// ------------------------------------------------------------------ cabecera

/**
 * La consola elegida, en la barra lateral: nombre, aparato, bateria, espacio y subir. Estuvo
 * arriba, a todo lo ancho, y se comia la altura que necesitan las pestañas; a un lado aprovecha el
 * hueco que dejaba la lista de consolas.
 */
@Composable
fun ConsolePanel(app: AppState, e: ConsoleEntry, window: java.awt.Window, onUpload: (List<File>) -> Unit) {
    Pane(null, Modifier.fillMaxWidth(), fill = MaterialTheme.colorScheme.surfaceContainer, padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.name.ifBlank { e.model }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.paired) {
                IconButton(onClick = { app.load(e) }, enabled = !e.loading, modifier = Modifier.size(32.dp)) {
                    if (e.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(KitIcons.Refresh, "Refresh", Modifier.size(18.dp))
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(KitIcons.More, "More", Modifier.size(18.dp)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (e.paired) {
                        DropdownMenuItem(text = { Text("Forget this device") }, onClick = { menu = false; app.forget(e) })
                    } else {
                        DropdownMenuItem(text = { Text("Pair") }, onClick = { menu = false; app.startPairing(e) })
                    }
                }
            }
        }
        val i = e.info
        val model = listOfNotNull(i?.manufacturer?.takeIf { it.isNotBlank() }, e.model.ifBlank { null }).joinToString(" ")
        val lines = listOfNotNull(
            model.takeIf { it.isNotBlank() && it != e.name },
            listOfNotNull(i?.android?.takeIf { it.isNotBlank() }?.let { "Android $it" }, e.host.ifBlank { null }).joinToString(" · ").ifBlank { null },
            i?.battery?.let { "Battery $it %" + if (i.charging) " · charging" else "" },
        )
        for (l in lines) Text(l, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (i?.free != null && i.total != null && i.total > 0) {
            Spacer(Modifier.height(10.dp))
            val used = 1f - i.free.toFloat() / i.total
            Text("${Format.size(i.free)} free", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { used }, modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (used > 0.95f) Look.danger else if (used > 0.85f) Look.warn else Look.accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {},
            )
            Text("of ${Format.size(i.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.paired) {
            Spacer(Modifier.height(10.dp))
            val scope = rememberCoroutineScope()
            var picking by remember { mutableStateOf(false) }   // un selector a la vez
            LButton(
                onClick = {
                    picking = true
                    scope.launch { try { chooseFiles(window)?.let(onUpload) } finally { picking = false } }
                },
                enabled = e.consoleDirs.isNotEmpty() && !picking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(KitIcons.Upload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Upload ROMs…")
            }
        }
        // Lo cambiado aqui para Ludolog (nombres, ajustes) no llega hasta que se manda: Ludolog lo
        // aplica ella misma y se refresca. Ver AppState.syncConfig.
        if (e.pending.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val n = e.pending.size
            Text("$n ${if (n == 1) "change" else "changes"} waiting for Ludolog",
                style = MaterialTheme.typography.bodySmall, color = Look.warn)
            Spacer(Modifier.height(4.dp))
            // Uno bajo el otro: lado a lado, en lo ancho del panel, "Sync to device" se partia en dos.
            LButton(onClick = { app.syncConfig(e) }, enabled = e.online == true, modifier = Modifier.fillMaxWidth()) {
                Text("Sync to device", maxLines = 1)
            }
            LTextButton(onClick = { app.discard(e) }, modifier = Modifier.fillMaxWidth()) { Text("Discard") }
        }
    }
}

@Composable
private fun PairCard(app: AppState, e: ConsoleEntry) {
    Pane("Pair", Modifier.fillMaxWidth(), padding = 20) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pair this PC with ${e.name}", style = MaterialTheme.typography.titleMedium)
            Text("The device shows a 6-digit code. Only once.")
            LButton(onClick = { app.startPairing(e) }, enabled = e.online == true) { Text("Pair") }
            if (e.online != true) {
                Text("Not visible. Turn on PC Link on the device and search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ConnectingCard(e: ConsoleEntry) {
    Pane(null, Modifier.fillMaxWidth(), padding = 10) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connecting to ${e.name.ifBlank { "the device" }}…", color = MenuDim)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Sin conexion, lo que se lee de la copia del PC. */
@Composable
private fun SavedCopyTab(app: AppState, e: ConsoleEntry, window: java.awt.Window) {
    when (e.tab) {
        "companion" -> CompanionView(app, e)
        "settings" -> SettingsView(app, e, window)
        "log" -> LogView(app, e)
        "saves" -> SavesManagerView(app, e)
        else -> OverviewView(app, e)
    }
}

@Composable
private fun MessageCard(text: String, color: Color, actions: @Composable () -> Unit) {
    Pane(null, Modifier.fillMaxWidth(), padding = 10) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = color)
            actions()
        }
    }
}

// ------------------------------------------------------- sistemas y archivos

internal suspend fun chooseImage(window: java.awt.Window): File? =
    FileKit.openFilePicker(
        type = io.github.vinceglb.filekit.dialogs.FileKitType.Image,
        directory = Config.folder("art_dir")?.let(::PlatformFile),
        dialogSettings = FileKitDialogSettings(title = "Choose the box art", parent = FileKitDialogParent.awt(window)),
    )?.file?.also { it.parentFile?.let { d -> Config.setFolder("art_dir", d) } }

/** Un video para el juego, en cualquier formato que lea FFmpeg: se convierte antes de mandarlo. */
internal suspend fun chooseVideo(window: java.awt.Window): File? =
    FileKit.openFilePicker(
        type = io.github.vinceglb.filekit.dialogs.FileKitType.File(setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "3gp", "ogv", "gif")),
        directory = Config.folder("video_dir")?.let(::PlatformFile),
        dialogSettings = FileKitDialogSettings(title = "Choose a gameplay video", parent = FileKitDialogParent.awt(window)),
    )?.file?.also { it.parentFile?.let { d -> Config.setFolder("video_dir", d) } }

// Los dialogos modernos de Windows (IFileOpenDialog), no los de AWT/Swing.
private suspend fun chooseFiles(window: java.awt.Window): List<File>? {
    val picked = FileKit.openFilePicker(
        mode = FileKitMode.Multiple(),
        directory = Config.folder("upload_dir")?.let(::PlatformFile),
        dialogSettings = FileKitDialogSettings(title = "Upload ROMs to the device", parent = FileKitDialogParent.awt(window)),
    ) ?: return null
    val files = picked.map { it.file }
    if (files.isEmpty()) return null
    files.first().parentFile?.let { Config.setFolder("upload_dir", it) }
    return files
}

internal suspend fun chooseFolder(window: java.awt.Window, start: File?, title: String = "Download to…"): File? =
    FileKit.openDirectoryPicker(
        directory = start?.let(::PlatformFile),
        dialogSettings = FileKitDialogSettings(title = title, parent = FileKitDialogParent.awt(window)),
    )?.file

/** El color del fondo sobre el que va la marca de la casilla: invertido en una fila de fosforo. */
@Composable
private fun MenuGroundOf(selected: Boolean) =
    if (selected && com.felp.frontcomp.LocalTheme.current.selection == com.felp.frontcomp.SelectionStyle.INVERT)
        com.felp.frontcomp.LocalTheme.current.ink else com.felp.frontcomp.LocalTheme.current.ground
