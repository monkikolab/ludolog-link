package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.felp.ludolog.kit.ui.KitIcons
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.PanelDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.awt.datatransfer.DataFlavor
import java.io.File
import io.github.vinceglb.filekit.FileKit

// Main.kt: el arranque de Ludolog Link PC y el marco de la ventana.
//   main     con argumentos, las herramientas sin ventana (--export-icon para empaquetar,
//            --selftest contra una consola, --companion y --video de prueba); si no, la ventana:
//            crea el unico AppState, con un ambito que sobrevive a los fallos, y le pone el tema.
//   Root     barra lateral + ConsoleView + panel de transferencias, el aviso de abajo, soltar
//            archivos para subirlos (UploadPlan) y los dialogos de emparejar y de subir.
//   Sidebar, ConsoleCard, LookPicker, EmptyState, NoticeBar: las piezas de ese marco.

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--export-icon" -> return exportIcon(File(args[1]))
        "--selftest" -> return selfTest(args[1], args[2])
        "--companion" -> return companionTest(File(args[1]), args[2])
        "--video" -> return videoTest(args.drop(1))
    }
    FileKit.init(appId = if (Dev.ON) "LudologLinkDev" else "LudologLink")
    application {
        val windowState = rememberWindowState(size = DpSize(1720.dp, 810.dp), position = WindowPosition(Alignment.Center))
        // Un ambito que sobrevive a un fallo: con el de la composicion, una excepcion que no fuera de
        // la red cancelaba el ambito entero y despues nada mas arrancaba (colas, avisos, catalogo).
        val app = remember {
            var made: AppState? = null
            val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                t.printStackTrace()
                runCatching { made?.notify("Something failed: ${t.message ?: t.javaClass.simpleName}", error = true) }
            }
            AppState(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main + handler))
                .also { made = it }
        }
        var confirmExit by remember { mutableStateOf(false) }

        Window(
            onCloseRequest = { if (app.transfers.active) confirmExit = true else exitApplication() },
            state = windowState,
            title = Dev.name,
            icon = rememberVectorPainter(KitIcons.App),
        ) {
            val theme = app.theme
            LaunchedEffect(theme.ground, theme.light) { titleBar(window, theme.ground, dark = !theme.light) }
            // Una vez al dia, si hay version nueva en GitHub: un aviso, una sola vez por version (ver PcUpdates).
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(15_000)
                val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { PcUpdates.check(force = false).second }.getOrNull()
                }
                msg?.let { app.notify(it) }
            }
            val fonts = rememberThemeFonts(theme.id, app.fontsVersion)
            // Una vez por tema y por letra: se rehacia (familia de letra incluida) en cada redibujo de la ventana.
            val look = remember(fonts, theme) { fonts?.applyTo(theme) ?: theme }
            LudologLook(look) {
                CompositionLocalProvider(
                    LocalScrollbarStyle provides defaultScrollbarStyle().copy(
                        unhoverColor = theme.ink.copy(alpha = 0.16f),
                        hoverColor = theme.ink.copy(alpha = 0.40f),
                        shape = Look.shape,
                    ),
                ) {
                    Root(app, window)
                    if (confirmExit) {
                        AlertDialog(
                            onDismissRequest = { confirmExit = false },
                            title = { Text("Transfers in progress") },
                            text = {
                                Text("Quitting interrupts them. Downloads resume later; partial uploads are lost.")
                            },
                            confirmButton = {
                                LTextButton(onClick = { app.transfers.cancelAll(); exitApplication() }) {
                                    Text("Quit anyway", color = Look.danger)
                                }
                            },
                            dismissButton = { LTextButton(onClick = { confirmExit = false }) { Text("Keep running") } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Root(app: AppState, window: java.awt.Window) {
    var plan by remember { mutableStateOf<UploadPlan?>(null) }
    var dragging by remember { mutableStateOf(false) }

    // Arrastrar archivos o carpetas a la ventana = subirlos a la consola elegida.
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val t = event.awtTransferable
                if (!t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                plan = UploadPlan.start(app, files) ?: return false
                return true
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(app, window) { files -> plan = UploadPlan.start(app, files) }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        val sel = app.selected
                        if (sel == null) EmptyState(app)
                        else ConsoleView(app, sel, window)
                    }
                    if (app.transfers.items.isNotEmpty()) TransfersPanel(app)
                }
            }

            app.notice?.let { n -> NoticeBar(n, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { app.notice = null } }

            if (dragging) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .82f))
                        .padding(24.dp).border(2.dp, Look.accent, Look.shape),
                    contentAlignment = Alignment.Center,
                ) {
                    val target = app.selected?.takeIf { it.paired }?.name
                    Text(if (target != null) "Drop to upload to $target" else "Select a paired device first",
                        style = MaterialTheme.typography.headlineSmall, color = Look.accent)
                }
            }
        }
    }

    app.pairing?.let { PairDialog(app, it) }
    plan?.let { p -> UploadPlanDialog(app, p, onClose = { plan = null }) }
}

@Composable
private fun Sidebar(app: AppState, window: java.awt.Window, onUpload: (List<File>) -> Unit) {
    Column(
        Modifier.width(272.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(rememberVectorPainter(KitIcons.App), null, Modifier.size(36.dp), tint = Color.Unspecified)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(Look.title(Dev.name), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("Your devices, from your PC", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Look.title("Devices"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // El mismo boton buscando o no: solo cambia el icono por la rueda, del mismo tamaño y en
            // su sitio. Antes se cambiaba el boton entero por la rueda, con relleno por dentro, y
            // la fila saltaba y la rueda giraba descentrada.
            LTextButton(onClick = { app.search() }, enabled = !app.searching) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (app.searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    else Icon(KitIcons.Refresh, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Search")
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(app.consoles, key = { it.id }) { c -> ConsoleCard(c, c.id == app.selectedId) { app.select(c) } }
            if (app.consoles.isEmpty() && !app.searching) {
                item {
                    Text("None yet", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Con dos o mas consolas: que cada una vea en su Companion lo jugado en las otras.
        if (app.shareable.size >= 2) {
            LOutlinedButton(onClick = { app.syncCompanions() }, enabled = !app.companionSyncing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text(if (app.companionSyncing) "Syncing companions…" else "Sync companions (${app.shareable.size})", maxLines = 1)
            }
        }
        app.selected?.let { sel -> Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { ConsolePanel(app, sel, window, onUpload) } }
        PanelDivider(Modifier.padding(horizontal = 12.dp))
        LookPicker(app)
        Text("This PC: ${Config.pcName}", Modifier.padding(start = 16.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConsoleCard(c: ConsoleEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectedRow(selected).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { RowContent(selected) {
        val dot = when (c.online) { true -> Look.ok; false -> Look.off; null -> Look.warn }
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name.ifBlank { c.model }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MenuInk)
            val sub = buildList {
                if (c.model.isNotBlank() && c.model != c.name) add(c.model)
                add(when {
                    c.pcLinkOff -> "PC Link off"
                    !c.paired -> "not paired"
                    c.online == false -> "not visible"
                    else -> c.host
                })
            }.joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = if (!c.paired) Look.warn else MenuDim)
        }
    } }
}

/** El aspecto de esta ventana: el de la consola elegida, o uno de los tres fijado. */
@Composable
private fun LookPicker(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val label = if (app.look == "console") "Same as the device" else com.felp.frontcomp.themeById(app.look).name
    Box(Modifier.padding(horizontal = 8.dp)) {
        LTextButton(onClick = { open = true }) {
            Text("Look: $label", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Same as the device") }, onClick = { open = false; app.chooseLook("console") })
            for (t in com.felp.frontcomp.AllThemes) {
                DropdownMenuItem(text = { Text(t.name) }, onClick = { open = false; app.chooseLook(t.id) })
            }
        }
    }
}

@Composable
private fun EmptyState(app: AppState) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(rememberVectorPainter(KitIcons.App), null, Modifier.size(96.dp), tint = Color.Unspecified)
        Spacer(Modifier.height(20.dp))
        Text("Turn on PC Link on the device", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Same Wi-Fi as this PC. Then click Search.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        LTextButton(onClick = { app.search() }, enabled = !app.searching) { Text("Find devices") }
    }
}

@Composable
private fun NoticeBar(n: Notice, modifier: Modifier, onClose: () -> Unit) {
    Surface(modifier.width(640.dp), shape = Look.shape,
        color = if (n.error) androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.background, Look.danger, .18f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.text, Modifier.weight(1f).padding(vertical = 8.dp),
                color = if (n.error) Look.danger else MaterialTheme.colorScheme.onSurface)
            IconButton(onClick = onClose) { Icon(KitIcons.Close, "Close", Modifier.size(18.dp)) }
        }
    }
}
