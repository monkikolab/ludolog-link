package com.felp.ludologlink

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Pane

/**
 * El primer arranque de Link: lo que hace falta para que sirva, en orden y de uno en uno.
 *
 * Sale solo la primera vez y en una instalacion sin nada emparejado (ver MainActivity): quien ya lo
 * usaba no lo ve al actualizar. Se puede repetir desde Settings. Todo lo de aqui se cambia despues
 * en la pantalla de siempre; el wizard solo lo pone delante en el orden en que se necesita.
 *
 * Lo que pide a la actividad (permisos, elegir carpeta, el mosaico) llega como funciones: son
 * cosas que solo se hacen con una actividad registrada antes de que exista la pantalla.
 */
@Composable
fun SetupWizard(
    ctx: Context,
    storageOk: Boolean,
    notificationsOk: Boolean,
    batteryOk: Boolean,
    cleanerOk: Boolean?,
    romsRoot: String?,
    romsSummary: String,
    onGrantFiles: () -> Unit,
    onAskNotifications: () -> Unit,
    onPickRoms: () -> Unit,
    onDetectRoms: () -> Unit,
    onAddTile: (() -> Unit)?,
    onDone: () -> Unit,
) {
    var step by remember { mutableStateOf(0) }
    val steps = listOf("Welcome", "Access", "Name", "ROMs", "Pair", "Done")
    val scroll = rememberScrollState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Look.title(Dev.name), style = MaterialTheme.typography.headlineMedium)
            // En un telefono en vertical no caben: se desplazan, sin partirse letra a letra.
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                steps.forEachIndexed { i, s ->
                    Text("${i + 1}  ${s.uppercase()}", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                        color = if (i == step) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (step) {
                    0 -> Welcome(ctx)
                    1 -> Access(ctx, storageOk, notificationsOk, batteryOk, cleanerOk, onGrantFiles, onAskNotifications)
                    2 -> Name(ctx)
                    3 -> Roms(romsRoot, romsSummary, onPickRoms, onDetectRoms)
                    4 -> Pair(ctx)
                    else -> Done(onAddTile)
                }
                // Abajo, siempre igual: volver y seguir. Sin el acceso a archivos no se sigue: sin el
                // no se lee ni la carpeta de Ludolog ni las ROMs.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) LTextButton(onClick = { step-- }) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    val last = step == steps.lastIndex
                    LButton(
                        onClick = { if (last) onDone() else step++ },
                        enabled = step != 1 || storageOk,
                    ) { Text(if (last) "Start" else if (step == 4 && !paired(ctx)) "Later" else "Continue") }
                }
            }
        }
    }
}

private fun paired(ctx: Context) = Peers.all(ctx).isNotEmpty() || Prefs.tokens(ctx).isNotEmpty()

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Welcome(ctx: Context) {
    Pane("Welcome", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sync saves, Companion sessions and ROMs between your devices and your PC.")
            Hint("Over your Wi-Fi: your saves and sessions never go to the internet.")
            if (Ludolog.version(ctx) == null) Text("Ludolog isn't installed. Install it and open it once.",
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Access(ctx: Context, storageOk: Boolean, notificationsOk: Boolean, batteryOk: Boolean, cleanerOk: Boolean?,
                   onGrantFiles: () -> Unit, onAskNotifications: () -> Unit) {
    Pane("Access", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Grant("Files", "ROMs, saves and Ludolog's folder. Required.", storageOk, onGrantFiles)
            Grant("Notifications", "Pairing codes and save conflicts.", notificationsOk, onAskNotifications)
            // Para escuchar a los devices con la pantalla apagada: Android y lo de cada fabricante.
            (ctx as? android.app.Activity)?.let { KeepListeningRows(it, batteryOk, cleanerOk, withBattery = true) }
        }
    }
}

@Composable
private fun Grant(title: String, detail: String, ok: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ok) Text("Allowed", color = Look.ok) else LOutlinedButton(onClick = onGrant) { Text("Allow") }
    }
}

@Composable
private fun Name(ctx: Context) {
    var text by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    Pane("This device", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("How other devices and the PC see it.")
            OutlinedTextField(value = text, singleLine = true, label = { Text("Device name") },
                onValueChange = { text = it.take(40); Prefs.setDeviceName(ctx, text) })
        }
    }
}

@Composable
private fun Roms(romsRoot: String?, romsSummary: String, onPickRoms: () -> Unit, onDetectRoms: () -> Unit) {
    Pane("ROM folder", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("Where incoming ROMs go, one folder per console.")
            Text(romsRoot ?: "Not found", fontFamily = FontFamily.Monospace)
            if (romsSummary.isNotEmpty()) Hint(romsSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOutlinedButton(onClick = onDetectRoms) { Text("Detect") }
                LOutlinedButton(onClick = onPickRoms) { Text("Choose…") }
            }
        }
    }
}

@Composable
private fun Pair(ctx: Context) {
    val version by LinkState.peersChanged
    val peers = remember(version) { Peers.all(ctx) }
    val code by LinkState.pairingCode
    val codePc by LinkState.pairingPc
    var pairing by remember { mutableStateOf(false) }
    Pane("Another device", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("Same Wi-Fi, Ludolog Link open on both.")
            for (p in peers) Text("✓  ${p.name}")
            LOutlinedButton(onClick = { pairing = true }) { Text("Pair a device…") }
        }
    }
    Pane("The PC", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("Turn on PC Link, search from the PC and enter the code shown here.")
            val pcLink by LinkState.pcLink
            if (!pcLink) LButton(onClick = { LinkService.setPcLink(ctx, true) }) { Text("Turn on PC Link") }
            else Text("PC Link on · ${LinkState.address.value}")
            // La lista de permisos guarda tambien los de las devices emparejadas: aqui, solo los PCs.
            for (pc in LinkState.pairedPcs.filter { n -> peers.none { it.name == n } }) Text("✓  $pc")
            code?.let { c ->
                Text("Code for $codePc", style = MaterialTheme.typography.titleMedium)
                Text(c.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
    Hint("You can do this later.")
    if (pairing) PairConsoleDialog(ctx) { pairing = false }
}

@Composable
private fun Done(onAddTile: (() -> Unit)?) {
    Pane("Ready", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paired devices sync on their own after each game.")
            Hint("Next: choose each emulator's saves folder in the Saves tab.")
            if (onAddTile != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Quick settings tile for PC Link.", Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                TileButton(onAddTile)
            }
            LinkState.tileNote.value?.let { Hint(it) }
        }
    }
}

/**
 * El boton del mosaico de PC Link: «Add tile» mientras no este en el panel rapido y «✓ Added»
 * cuando ya esta. Antes seguia diciendo «Add tile» despues de ponerlo, sin decir nada (07-10-2026).
 */
@Composable
internal fun TileButton(onAdd: () -> Unit) {
    if (LinkState.tileAdded.value) {
        Text("✓ Added", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    } else {
        LOutlinedButton(onClick = onAdd) { Text("Add tile") }
    }
}
