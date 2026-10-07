package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.Protocol

@Composable
fun PairDialog(app: AppState, e: ConsoleEntry) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit() {
        if (code.length != 6 || busy) return
        busy = true
        app.confirmPairing(e, code) { msg -> error = msg; busy = false; code = "" }
    }

    AlertDialog(
        onDismissRequest = { app.pairing = null },
        title = { Text("Pair with ${e.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the code shown on the device.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(6); error = null },
                    singleLine = true,
                    isError = error != null,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, letterSpacing = 6.sp),
                    modifier = Modifier.width(240.dp).focusRequester(focus)
                        .onPreviewKeyEvent { k -> if (k.key == Key.Enter && k.type == KeyEventType.KeyDown) { submit(); true } else false },
                )
                error?.let { Text(it.replaceFirstChar(Char::uppercase), color = Look.danger) }
            }
        },
        confirmButton = { LTextButton(onClick = ::submit, enabled = code.length == 6 && !busy) { Text("Pair") } },
        dismissButton = {
            LTextButton(onClick = { error = null; app.startPairing(e) }) { Text("Request a new code") }
            LTextButton(onClick = { app.pairing = null }) { Text("Cancel") }
        },
    )
}

/**
 * Los dos nombres de un juego: el que se ve en Ludolog y el del archivo. El archivo se renombra
 * en la consola al aceptar; el nombre de Ludolog queda pendiente de "Sync to device", porque es
 * un ajuste de Ludolog y ella lo aplica (ver AppState.syncConfig). Dejarlo vacio vuelve al de
 * siempre: el del catalogo o el del archivo.
 */
@Composable
fun RenameDialog(
    e: ConsoleEntry, f: RomFile, display: String, custom: String?,
    onClose: () -> Unit, onRename: (newFile: String?, newDisplay: String?) -> Unit,
) {
    val base = f.name.substringAfterLast('/')
    val dir = f.name.substringBeforeLast('/', "")
    val ludolog = e.info?.ludolog != null
    var shown by remember { mutableStateOf(TextFieldValue(custom ?: display, TextRange(0, (custom ?: display).length))) }
    // El archivo: seleccionado sin la extension, como en el Explorador.
    var value by remember { mutableStateOf(TextFieldValue(base, TextRange(0, Protocol.stemOf(base).length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val newName = value.text.trim()
    val target = if (dir.isEmpty()) newName else "$dir/$newName"
    val problem = Protocol.nameProblem(newName)
        ?: if (newName != base && e.roms.any { it.system == f.system && it.name.equals(target, ignoreCase = true) } &&
            !newName.equals(base, ignoreCase = true)) "a file with that name already exists" else null
    val extChanged = problem == null && Protocol.extOf(newName) != Protocol.extOf(base)
    val newShown = shown.text.trim()
    val fileChanged = newName != base
    val shownChanged = ludolog && newShown != (custom ?: display)
    val ok = problem == null && (fileChanged || shownChanged)
    fun accept() = onRename(newName.takeIf { fileChanged }, newShown.takeIf { shownChanged })

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Rename on ${e.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ludolog) {
                    Text("Name in Ludolog", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = shown, onValueChange = { shown = it }, singleLine = true,
                        modifier = Modifier.width(520.dp).focusRequester(focus)
                            .onPreviewKeyEvent { k -> if (k.key == Key.Enter && k.type == KeyEventType.KeyDown && ok) { accept(); true } else false },
                    )
                    Text("Empty uses the catalog name.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                Text("File", style = MaterialTheme.typography.labelLarge)
                Text("${f.system}/${f.name}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Una entrada de Steam o DoomForge: el archivo es de esa app y no se toca.
                if (Shortcuts.isLocked(f)) Text("Steam or DoomForge entry: only the name changes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else OutlinedTextField(
                    value = value, onValueChange = { value = it }, singleLine = true, isError = problem != null,
                    modifier = Modifier.width(520.dp).then(if (ludolog) Modifier else Modifier.focusRequester(focus))
                        .onPreviewKeyEvent { k -> if (k.key == Key.Enter && k.type == KeyEventType.KeyDown && ok) { accept(); true } else false },
                )
                if (!Shortcuts.isLocked(f)) when {
                    problem != null -> Text(problem.replaceFirstChar(Char::uppercase), color = Look.danger)
                    extChanged -> Text("The extension changes: the emulator may not open it.", color = Look.warn)
                    Protocol.extOf(base) != ".sbi" -> Text("Its .sbi is renamed too. Ludolog keeps its data.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { LTextButton(onClick = ::accept, enabled = ok) { Text("Rename") } },
        dismissButton = { LTextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDialog(e: ConsoleEntry, files: List<RomFile>, onClose: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (files.size == 1) "Delete from ${e.name}?" else "Delete ${files.size} files from ${e.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                files.take(8).forEach {
                    Text("${it.system}/${it.name}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                if (files.size > 8) Text("and ${files.size - 8} more", style = MaterialTheme.typography.bodySmall)
                Text("Frees ${Format.size(files.sumOf { it.size })}. Permanent, with its .sbi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { LTextButton(onClick = onDelete) { Text("Delete", color = Look.danger) } },
        dismissButton = { LTextButton(onClick = onClose) { Text("Cancel") } },
    )
}
