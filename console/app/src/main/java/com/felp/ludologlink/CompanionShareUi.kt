package com.felp.ludologlink

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Pane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compartir el Companion con otras consolas, sin PC: las emparejadas, cuando se sincronizo cada
 * una, y emparejar otra. Ver CompanionShare.
 */
@Composable
fun CompanionShareSection(ctx: Context, running: Boolean) {
    val version by LinkState.peersChanged
    val sharing by LinkState.sharing
    val peers = remember(version) { Peers.all(ctx) }
    val sync by LinkState.syncDevices
    var pairing by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }

    Pane("Paired devices", Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (peers.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (p in peers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(if (p.lastSync > 0) "${clock.format(Date(p.lastSync))} · ${p.note}" else "Not synced yet",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LTextButton(onClick = { Peers.forget(ctx, p.id) }) { Text("Forget") }
                }
            }
            // Devices emparejados con este que no lo estan entre si: este los presenta (ver Introduce).
            if (sync && peers.size >= 2) {
                var missing by remember { mutableStateOf<List<Pair<Peer, Peer>>>(emptyList()) }
                var working by remember { mutableStateOf(false) }
                var note by remember { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(version, working) {
                    if (!working) missing = withContext(Dispatchers.IO) { runCatching { Introduce.missing(ctx) }.getOrDefault(emptyList()) }
                }
                if (missing.isNotEmpty() || note != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(note ?: missing.joinToString("; ") { (a, b) -> "${a.name} and ${b.name} aren't paired" } + ".",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (missing.isNotEmpty()) LOutlinedButton(enabled = !working, onClick = {
                        working = true
                        kotlin.concurrent.thread(isDaemon = true) {
                            val r = runCatching { Introduce.connectAll(ctx) }.getOrElse { "Couldn't: ${it.message}" }
                            LinkState.post { note = r; working = false }
                        }
                    }) { Text(if (working) "Pairing…" else "Pair them all") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LButton(onClick = { CompanionShare.syncAll(ctx, "by hand") }, enabled = running && sync && peers.isNotEmpty() && !sharing) {
                    Text(if (sharing) "Syncing…" else "Sync now")
                }
                LOutlinedButton(onClick = { pairing = true }, enabled = sync) { Text("Pair a device…") }
            }
            // Lo decide la persona y casi nunca se toca (ver Prefs.syncDevices).
            if (peers.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { LinkService.setSyncDevices(ctx, !sync) },
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sharing")
                    Text(if (sync) "Saves, Companion sessions and ROMs." else "Off. What you play is kept and shared when you turn it on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = sync, onCheckedChange = { LinkService.setSyncDevices(ctx, it) })
            }
        }
    }
    if (pairing) PairConsoleDialog(ctx) { pairing = false }
}

/** Buscar otra consola con Link encendido, pedirle un codigo y escribirlo aqui. */
@Composable
internal fun PairConsoleDialog(ctx: Context, onClose: () -> Unit) {
    var found by remember { mutableStateOf<List<FoundConsole>?>(null) }
    var chosen by remember { mutableStateOf<FoundConsole?>(null) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var searchRound by remember { mutableStateOf(0) }

    LaunchedEffect(searchRound) {
        found = null
        found = withContext(Dispatchers.IO) { runCatching { Peers.discover(ctx) }.getOrDefault(emptyList()) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun confirm(c: FoundConsole) {
        if (code.length != 6 || working) return
        working = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Peers.confirmPair(ctx, c, code) } }
            working = false
            r.onSuccess { CompanionShare.syncAll(ctx, "just paired"); onClose() }.onFailure { error = it.message }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(if (chosen == null) "Pair a device" else "Code from ${chosen!!.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = chosen
                if (c == null) {
                    Text("Same Wi-Fi, Ludolog Link open on both.")
                    val list = found
                    when {
                        list == null -> Text("Looking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        list.isEmpty() -> Text("None found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> list.forEach { f ->
                            Text(f.name + "  ·  " + f.host, Modifier.fillMaxWidth().clickable(enabled = !working) {
                                working = true
                                error = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { Peers.requestPair(ctx, f) } }
                                    working = false
                                    r.onSuccess { chosen = f }.onFailure { error = it.message }
                                }
                            }.padding(vertical = 8.dp))
                        }
                    }
                } else {
                    Text("Enter the code shown on ${c.name}.")
                    OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        // El ✓ del teclado empareja, como el boton.
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { confirm(c) }),
                        label = { Text("Code") })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val c = chosen
            if (c == null) LTextButton(onClick = { searchRound++ }, enabled = !working) { Text("Search again") }
            else LTextButton(onClick = { confirm(c) }, enabled = code.length == 6 && !working) { Text("Pair") }
        },
        dismissButton = { LTextButton(onClick = onClose, enabled = !working) { Text("Cancel") } },
    )
}
