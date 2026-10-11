package com.felp.ludologlink

import kotlinx.coroutines.ensureActive
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * La pestaña de partidas guardadas: la carpeta de cada emulador, sus respaldos y su sincronizacion
 * con las consolas emparejadas. Ver Saves y SaveSync.
 * Aqui tambien: el explorador de carpetas (FolderBrowser, que abre en lo que propone SaveScan), el
 * modo flexible (RelaxedRow), devolver un respaldo (RestoreDialog, ver SaveRestore) y los conflictos,
 * en su lista y en la ventana que MainActivity pone sobre cualquier pestaña (ConflictsPopup).
 */
@Composable
fun SavesScreen(ctx: Context, running: Boolean) {
    val version by LinkState.savesChanged
    val syncing by LinkState.savesSyncing
    val peersVersion by LinkState.peersChanged
    val peers = remember(peersVersion) { Peers.all(ctx) }
    // Los puestos, y los que Ludolog ya vio usar aunque no tengan carpeta. Ordenados aqui dentro: el
    // nombre de cada app es una pregunta al sistema, y fuera se repetia en cada redibujo.
    val seen = remember(version) { Saves.seen(ctx) }
    val list = remember(version) {
        val set = Saves.all(ctx)
        (set + (seen - set.map { it.pkg }.toSet()).map { EmuSaves(it) })
            .map { it to Saves.appName(ctx, it.pkg).lowercase() }.sortedBy { it.second }.map { it.first }
    }
    var adding by remember { mutableStateOf(false) }
    // El emulador cuya carpeta se esta eligiendo con el explorador de Link.
    var browsing by remember { mutableStateOf<String?>(null) }
    val pickFolder: (String) -> Unit = { browsing = it }

    // Las otras consolas, preguntadas en segundo plano: si contestan, y como esta cada emulador
    // alla: SET, UNSET o UNSUPPORTED (sin entrada: no se pudo preguntar). Se repite al cambiar algo aqui.
    val online = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val remote = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    // Se pregunta otra vez solo si cambian las devices o los emuladores: no con cada cambio de aqui
    // (una pasada sube el contador varias veces, y cada vez se volvia a preguntar todo).
    val peerKey = peers.map { "${it.id}@${it.host}" }
    val pkgKey = list.map { it.pkg }
    androidx.compose.runtime.LaunchedEffect(peerKey, pkgKey) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Compartir apagado: tampoco se pregunta a los otros devices.
            if (!Prefs.syncDevices(ctx)) return@withContext
            for (p in peers) {
                ensureActive()
                val reached = runCatching { Peers.reach(ctx, p) }.getOrNull()
                LinkState.post { online[p.id] = reached != null }
                if (reached == null) { LinkState.post { list.forEach { remote.remove("${p.id}|${it.pkg}") } }; continue }
                for (e in list) {
                    ensureActive()
                    val st = runCatching {
                        Peers.json(Peers.open(reached.host, reached.port, "GET", "/saves/state", Saves.stateQuery(ctx, e.pkg), reached.token))
                    }.getOrNull()
                    LinkState.post { if (st == null) remote.remove("${p.id}|${e.pkg}") else remote["${p.id}|${e.pkg}"] = when {
                        !st.optBoolean("configured") -> UNSET
                        !st.optBoolean("supported", true) -> UNSUPPORTED
                        else -> SET
                    } }
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Con quien se sincroniza: esta consola y las emparejadas, con si contestan ahora.
        Pane("Paired devices", Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Led(Look.ok, Prefs.deviceName(ctx) + " (this one)")
                for (p in peers) Led(when (online[p.id]) { true -> Look.ok; false -> Look.off; null -> Look.warn },
                    p.name + when (online[p.id]) { true -> ""; false -> " · not reachable"; null -> " · checking…" })
                if (peers.isEmpty()) Text("None yet. Pair one in the Link tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Lo que hay que elegir: jugado en dos consolas desde la ultima vez.
        val conflicts = remember(version) { Saves.conflicts(ctx) }
        if (conflicts.isNotEmpty()) Pane("Choose which save to keep", Modifier.fillMaxWidth()) {
            ConflictList(ctx, conflicts)
        }
        Pane("Save sync", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saves sync after each game. Set a saves folder for each emulator.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Backups: ${Saves.home().absolutePath}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Ludolog pregunta antes de abrir un juego (ver SaveCheck): se puede apagar.
                var check by remember { mutableStateOf(Prefs.saveCheck(ctx)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Switch(checked = check, onCheckedChange = { check = it; Prefs.setSaveCheck(ctx, it) })
                    Column(Modifier.weight(1f)) {
                        Text("Check before playing")
                        Text("Get the newest save before a game starts.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LButton(onClick = { SaveSync.syncAll(ctx, "by hand") }, enabled = running && peers.isNotEmpty() && !syncing) {
                        Text(if (syncing) "Syncing…" else "Sync all")
                    }
                    LOutlinedButton(onClick = { adding = true }) { Text("Add emulator…") }
                }
            }
        }
        // Solo los que tienen carpeta en alguna consola: aqui, o en una emparejada (para ponerla aqui).
        // Los que no estan puestos en ninguna se agregan con "Add emulator…".
        val shown = list.filter { e ->
            e.configured || peers.any { p -> remote["${p.id}|${e.pkg}"].let { it == SET || it == UNSUPPORTED } }
        }
        if (shown.isEmpty()) Text("No emulators yet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (e in shown) EmulatorCard(ctx, e, running && peers.isNotEmpty() && !syncing, pickFolder, peers,
            online, peers.associate { it.id to remote["${it.id}|${e.pkg}"] })

        // El espacio de los respaldos, despues de las carpetas.
        // Recorre la carpeta de respaldos: fuera del hilo de la pantalla.
        val used by androidx.compose.runtime.produceState(0L, version) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Saves.usedBytes(ctx) }
        }
        val cap = remember(version) { Saves.capMb(ctx) }
        Pane("Backup space", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${"%.1f".format(Locale.US, used / 1048576.0)} MB used. Oldest go first over the limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Limit  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (mb in Saves.CAPS) {
                        val on = mb == cap
                        Box(Modifier.selectedRow(on).clickable { Saves.setCapMb(ctx, mb) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            RowContent(on) { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", color = if (on) MenuInk else MenuDim) }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddEmulatorDialog(ctx, list.filter { it.configured }.map { it.pkg }.toSet(), seen,
        onPick = { pkg -> Saves.update(ctx, pkg) { it }; adding = false; pickFolder(pkg) }) { adding = false }
    browsing?.let { pkg ->
        val start = remember(pkg) { Saves.get(ctx, pkg)?.path.orEmpty() }
        FolderBrowser(ctx, pkg, start, onPick = { path ->
            Saves.update(ctx, pkg) { it.copy(path = path) }
            browsing = null
        }, onClose = { browsing = null })
    }
}

@Composable
private fun EmulatorCard(
    ctx: Context, e: EmuSaves, canSync: Boolean, pickFolder: (String) -> Unit,
    peers: List<Peer>, online: Map<String, Boolean>, remoteSet: Map<String, String?>,
) {
    val clock = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }
    var backingUp by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    if (restoring) RestoreDialog(ctx, e) { restoring = false }
    Pane(Saves.appName(ctx, e.pkg), Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (e.configured) e.path else "No folder",
                fontFamily = if (e.configured) FontFamily.Monospace else null,
                color = if (e.configured) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (e.configured && !e.folder.isDirectory) Text("Can't open this folder.",
                style = MaterialTheme.typography.bodySmall, color = Look.warn)
            // Si el emulador deja sus archivos fuera del alcance de Link (privados, o sin escribir en
            // sus carpetas), no se sincroniza: mirado fuera del hilo de la pantalla.
            val supported by androidx.compose.runtime.produceState(true, e.path, e.lastSync, e.note) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Saves.supported(e) }
            }
            // Donde esta listo: verde puesto, rojo falta ponerlo o no se puede, gris no se pudo preguntar.
            val here = e.configured && e.folder.isDirectory
            val unsupported = buildList {
                if (here && !supported) add("this device")
                peers.filter { remoteSet[it.id] == UNSUPPORTED }.forEach { add(it.name) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Led(if (here && supported) Look.ok else Look.danger, "This device" + if (here && !supported) " · not supported" else "")
                for (p in peers) {
                    val set = remoteSet[p.id]
                    Led(when (set) { SET -> Look.ok; UNSET, UNSUPPORTED -> Look.danger; else -> Look.off },
                        p.name + when {
                            set == UNSUPPORTED -> " · not supported"
                            set == null && online[p.id] == false -> " · offline"
                            else -> ""
                        })
                }
            }
            val missing = buildList {
                if (!here) add("this device")
                peers.filter { remoteSet[it.id] == UNSET }.forEach { add(it.name) }
            }
            val ready = unsupported.isEmpty() && missing.isEmpty() && peers.isNotEmpty() && peers.all { remoteSet[it.id] == SET }
            Text(when {
                unsupported.isNotEmpty() -> "Emulator not supported" + if (unsupported != listOf("this device")) " on ${unsupported.joinToString(" and ")}" else ""
                peers.isEmpty() -> "Backups only."
                missing.isNotEmpty() -> "Set the folder on ${missing.joinToString(" and ")}."
                ready -> "Ready."
                else -> "Checking other devices…"
            }, style = MaterialTheme.typography.bodySmall,
                color = when {
                    unsupported.isNotEmpty() -> Look.warn
                    ready -> Look.ok
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOutlinedButton(onClick = { pickFolder(e.pkg) }) { Text(if (e.configured) "Change folder…" else "Set folder…") }
                if (e.configured) LTextButton(onClick = { Saves.update(ctx, e.pkg) { it.copy(path = "") } }) { Text("Clear") }
            }
            if (e.configured) {
                RelaxedRow(ctx, e)
                // Respaldo: cada cuanto y cuantos.
                Text("Backups" + if (e.lastBackup > 0) " · last ${clock.format(Date(e.lastBackup))}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (k in EmuSaves.PERIODS.keys) {
                        val on = e.every == k
                        Box(Modifier.selectedRow(on).clickable { Saves.update(ctx, e.pkg) { it.copy(every = k) } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)) {
                            RowContent(on) { Text(k.replaceFirstChar(Char::uppercase), color = if (on) MenuInk else MenuDim) }
                        }
                    }
                    Text("  keep", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LTextButton(onClick = { Saves.update(ctx, e.pkg) { it.copy(keep = (it.keep - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text("${e.keep}")
                    LTextButton(onClick = { Saves.update(ctx, e.pkg) { it.copy(keep = (it.keep + 1).coerceAtMost(Saves.MAX_KEEP)) } }) { Text("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LOutlinedButton(onClick = {
                        backingUp = true
                        thread(isDaemon = true) {
                            runCatching { Saves.backup(ctx, e, "manual") }
                                .onFailure { LinkState.addLog("Backup of ${Saves.appName(ctx, e.pkg)} failed: ${it.message}", "saves", error = true) }
                            LinkState.post { backingUp = false }
                        }
                    }, enabled = !backingUp) { Text(if (backingUp) "Backing up…" else "Back up now") }
                    // Devolver una partida desde los respaldos de aqui (ver SaveRestore).
                    val backups = remember(e.lastBackup, e.lastSync, backingUp) { Saves.backupsOf(ctx, e.pkg).size }
                    LOutlinedButton(onClick = { restoring = true }, enabled = backups > 0) { Text("Restore…") }
                    LOutlinedButton(onClick = { SaveSync.syncAll(ctx, "by hand", setOf(e.pkg)) }, enabled = canSync && supported) { Text("Sync now") }
                    // Para empezar: lo de esta consola manda, aunque no sea lo ultimo jugado.
                    LTextButton(onClick = { SaveSync.syncAll(ctx, "sent from here", setOf(e.pkg), force = true) }, enabled = canSync && supported) {
                        Text("Send this device's")
                    }
                }
                Text(listOfNotNull(
                    e.played.takeIf { it > 0 }?.let { "Last played here ${clock.format(Date(it))}" },
                    e.lastSync.takeIf { it > 0 }?.let { "synced ${clock.format(Date(it))}: ${e.note}" },
                ).joinToString(" · ").ifEmpty { "Not synced yet" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Una consola para el modo flexible: su id (el `group`), su nombre y si Ludolog la abrio con esta app. */
private class GroupOption(val id: String, val name: String, val used: Boolean)

/**
 * El modo flexible de un emulador (EmuSaves.relaxed): sincronizar tambien con otra app de la misma
 * consola en otro device, si alla tambien esta en flexible. Se ve una sola linea con la consola
 * puesta, que al encenderlo es la mas probable (ver Saves.likelySystem), y «Change…» abre la lista
 * (pedido del usuario: una lista, no una tira de botones siempre a la vista).
 */
@Composable
private fun RelaxedRow(ctx: Context, e: EmuSaves) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Switch(checked = e.relaxed, onCheckedChange = { on ->
            Saves.update(ctx, e.pkg) { it.copy(relaxed = on, group = it.group.ifEmpty { if (on) Saves.likelySystem(ctx, e.pkg).orEmpty() else "" }) }
        })
        Column(Modifier.weight(1f)) {
            Text("Relaxed matching")
            Text("Also sync with a different app for the same console on another device, if it's set to relaxed there too.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (!e.relaxed) return
    // Las consolas para la lista: las que Ludolog abrio con esta app primero, despues las carpetas de
    // ROMs, con el nombre de su systeminfo.txt. Fuera del hilo de la pantalla: lee la tarjeta.
    val options by androidx.compose.runtime.produceState(emptyList<GroupOption>(), e.pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Con los ids y nombres del catalogo de Ludolog: una carpeta `n3ds` es la consola `3ds`, y
            // las dos consolas tienen que elegir la misma (ver Saves.canonicalSystem).
            val cat = Saves.catalog(ctx)
            fun id(name: String) = Saves.canonicalSystem(ctx, name)
            val folders = runCatching {
                RomStore.root(ctx)?.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.mapNotNull { f -> cat?.canonicalId(f.name) }
            }.getOrNull().orEmpty()
            val used = Saves.systemsOf(ctx, e.pkg).map(::id)
            val likely = listOfNotNull(Saves.likelySystem(ctx, e.pkg)?.let(::id))
            fun nameOf(s: String) = cat?.byId?.get(s)?.name ?: s.uppercase()
            (likely + used + folders.sortedBy { nameOf(it).lowercase() }).distinct()
                .map { s -> GroupOption(s, nameOf(s), s in used) }
        }
    }
    var choosing by remember { mutableStateOf(false) }
    val name = options.firstOrNull { it.id == Saves.canonicalSystem(ctx, e.group) }?.name ?: e.group.uppercase()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Console", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (e.group.isEmpty()) "None" else name, Modifier.weight(1f, fill = false))
        LOutlinedButton(onClick = { choosing = true }) { Text("Change…") }
    }
    Text(if (e.group.isEmpty()) "Choose the console it's used for. The other device needs the same one."
        else "Saves from a different app may not be compatible. Check a game after the first sync: what's replaced is backed up first.",
        style = MaterialTheme.typography.bodySmall, color = Look.warn)
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text("Console for ${Saves.appName(ctx, e.pkg)}") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((i, o) in options.withIndex()) {
                    val on = o.id == Saves.canonicalSystem(ctx, e.group)
                    Box(Modifier.fillMaxWidth().selectedRow(on)
                        .clickable { Saves.update(ctx, e.pkg) { it.copy(group = o.id) }; choosing = false }
                        .padding(horizontal = 10.dp, vertical = 8.dp)) {
                        RowContent(on) {
                            Column {
                                Text(o.name, color = if (on) MenuInk else MenuDim)
                                val hint = listOfNotNull(o.id, "most likely".takeIf { i == 0 }, "used with this app in Ludolog".takeIf { o.used })
                                Text(hint.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { LTextButton(onClick = { choosing = false }) { Text("Close") } },
    )
}

/** Una app instalada para poner su carpeta de partidas. */
@Composable
private fun AddEmulatorDialog(ctx: Context, already: Set<String>, seen: Set<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in already && it.first != ctx.packageName }
            // Primero los que Ludolog ya abrio, que son los emuladores de verdad, y despues los
            // conocidos (SaveScan): entre treinta apps del sistema habia que buscarlos.
            .distinctBy { it.first }.sortedWith(compareBy({ it.first !in seen }, { !SaveScan.known(it.first) }, { it.second.lowercase() }))
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add emulator") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for ((pkg, label) in apps) {
                    // Al elegirlo se abre el explorador para poner su carpeta.
                    Column(Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 8.dp)) {
                        Text(label)
                        Text(pkg + when { pkg in seen -> " · used in Ludolog"; SaveScan.known(pkg) -> " · emulator"; else -> "" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { LTextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Un juego en conflicto: lo de esta consola o lo de la otra, con cuando se jugo y cuanto ocupa en cada lado. */
@Composable
private fun ConflictRow(ctx: Context, c: SaveConflict) {
    val clock = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun describe(side: Map<String, Long>, played: Long): String {
        if (side.isEmpty()) return "no save"
        val bytes = side.values.sum()
        return (if (played > 0) "played ${clock.format(Date(played))}" else "no play recorded") + " · " +
            (if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB") +
            if (side.size < c.files.size) " · ${side.size} of ${c.files.size} files" else ""
    }
    val hereNewer = c.minePlayed >= c.theirsPlayed
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${Saves.appName(ctx, c.pkg)} · ${c.title}", style = MaterialTheme.typography.titleSmall)
        Text(c.files.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fun choose(here: Boolean) {
            working = true
            error = null
            SaveSync.resolve(ctx, c, here) { err -> working = false; error = err }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // El jugado mas recientemente, destacado: suele ser el bueno, pero decide la persona.
            if (hereNewer) {
                LButton(onClick = { choose(true) }, enabled = !working) { Text("This device · ${describe(c.mine, c.minePlayed)}") }
                LOutlinedButton(onClick = { choose(false) }, enabled = !working) { Text("${c.peerName} · ${describe(c.theirs, c.theirsPlayed)}") }
            } else {
                LButton(onClick = { choose(false) }, enabled = !working) { Text("${c.peerName} · ${describe(c.theirs, c.theirsPlayed)}") }
                LOutlinedButton(onClick = { choose(true) }, enabled = !working) { Text("This device · ${describe(c.mine, c.minePlayed)}") }
            }
        }
        if (working) Text("Copying…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Look.danger) }
    }
}

/**
 * El explorador de carpetas de Link, como el de FolderSync: rutas de verdad, tambien dentro de
 * `Android/data` (el selector del sistema no deja entrar ahi, pero con acceso a todos los archivos
 * estas consolas si). Con atajo a la carpeta de datos del emulador.
 */
@Composable
private fun FolderBrowser(ctx: Context, pkg: String, start: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val internal = android.os.Environment.getExternalStorageDirectory()
    val appData = java.io.File(internal, "Android/data/$pkg")
    var dir by remember { mutableStateOf(java.io.File(start).takeIf { it.isDirectory } ?: appData.takeIf { it.isDirectory } ?: internal) }
    var typed by remember(dir) { mutableStateOf(dir.absolutePath) }
    val listing = remember(dir) { dir.listFiles().orEmpty() }
    val subdirs = remember(listing) { listing.filter { it.isDirectory }.sortedBy { it.name.lowercase() } }
    val files = remember(listing) { listing.count { it.isFile } }
    val volumes = remember { RomStore.volumePaths(ctx).map { java.io.File(it) } }
    // Donde suele guardar ese emulador (SaveScan): sin carpeta puesta, se abre en la mejor, y se
    // proponen todas arriba. La persona mira y elige; no se pone sola.
    val guesses by androidx.compose.runtime.produceState<List<SaveScan.Guess>?>(null, pkg) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { SaveScan.guess(ctx, pkg) }.getOrDefault(emptyList())
        }
    }
    val opened = remember { dir }
    androidx.compose.runtime.LaunchedEffect(guesses) {
        // Solo si no se movio entretanto: el repaso puede tardar un momento en una tarjeta lenta.
        if (start.isEmpty() && dir == opened) guesses?.firstOrNull()?.let { dir = java.io.File(it.path) }
    }
    val clock = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }
    // Una ruta como los botones de arriba la llaman: "Emulator's data/…", "Internal/…" o el volumen.
    // Y las tiras de ceros (el usuario 0…0 de Switch, los ids de la tarjeta de 3DS), cortas.
    fun shortPath(p: String): String {
        val data = java.io.File(internal, "Android/data/$pkg/files").absolutePath + "/"
        val root = internal.absolutePath + "/"
        val named = when {
            p.startsWith(data) -> "Emulator's data/" + p.removePrefix(data)
            p.startsWith(root) -> "Internal/" + p.removePrefix(root)
            else -> volumes.firstOrNull { p.startsWith(it.absolutePath + "/") }?.let { v -> v.name + "/" + p.removePrefix(v.absolutePath + "/") } ?: p
        }
        return named.replace(Regex("0{16,}"), "0…0")
    }
    // Una ventana grande, no un AlertDialog: en una pantalla apaisada de consola el de sistema es
    // estrecho y se comia la ruta y la lista.
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.92f), shape = Look.shape,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saves folder · ${Saves.appName(ctx, pkg)}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LOutlinedButton(onClick = { dir = internal }) { Text("Internal") }
                    if (appData.isDirectory) LOutlinedButton(onClick = { dir = appData }) { Text("Emulator's data") }
                    volumes.filter { it != internal && it.isDirectory }.forEach { v ->
                        LOutlinedButton(onClick = { dir = v }) { Text(v.name) }
                    }
                }
                androidx.compose.material3.OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Path · $files ${if (files == 1) "file" else "files"} here") },
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                        java.io.File(typed.trim()).takeIf { it.isDirectory }?.let { dir = it }
                    }),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    // Las propuestas, arriba de la lista y dentro de lo que se desplaza: fuera, cuatro rutas
                    // largas se comian la lista y los botones de abajo.
                    when (val g = guesses) {
                        null -> Text("Looking for its saves…", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> if (g.isNotEmpty()) {
                            Text("Likely saves folders. Check before using one.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (x in g) {
                                val on = x.path == dir.absolutePath
                                Box(Modifier.fillMaxWidth().selectedRow(on).clickable { dir = java.io.File(x.path) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                    RowContent(on) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(shortPath(x.path), Modifier.weight(1f), fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                                            Text(if (x.files == 0) "empty" else "${x.files}${if (x.files >= 500) "+" else ""} " +
                                                "${if (x.files == 1) "file" else "files"} · ${clock.format(Date(x.changed))}",
                                                style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim)
                                        }
                                    }
                                }
                            }
                        } else Text("No likely saves folder found: look for it below.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    dir.parentFile?.takeIf { it.canRead() }?.let { up ->
                        Text("..  (up)", Modifier.fillMaxWidth().clickable { dir = up }.padding(vertical = 10.dp))
                    }
                    for (d in subdirs) Text(d.name + "/", Modifier.fillMaxWidth().clickable { dir = d }.padding(vertical = 10.dp),
                        fontFamily = FontFamily.Monospace)
                    if (subdirs.isEmpty()) Text("No folders inside", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    LTextButton(onClick = onClose) { Text("Cancel") }
                    LButton(onClick = { onPick(dir.absolutePath) }) { Text("Use this folder") }
                }
            }
        }
    }
}

/** Un indicador: un punto de color y su nombre. */
@Composable
internal fun Led(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, androidx.compose.foundation.shape.CircleShape))
        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Como esta un emulador en otra consola, segun su `/saves/state`. */
private const val SET = "set"
private const val UNSET = "unset"
private const val UNSUPPORTED = "unsupported"

/** Lo que hay que elegir, con la explicacion y, si son varios del mismo emulador y consola, todos de una. */
@Composable
private fun ConflictList(ctx: Context, conflicts: List<SaveConflict>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Changed on two devices. Pick the one to keep; the other is backed up.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Muchos de un mismo emulador y consola (tipico la primera vez): todos de una.
        for ((key, group) in conflicts.groupBy { it.peerId to it.pkg }) {
            if (group.size < 2) continue
            var working by remember(key) { mutableStateOf(false) }
            // El porque si no se pudo: se perdia, y el boton parecia no hacer nada.
            var error by remember(key) { mutableStateOf<String?>(null) }
            fun all(here: Boolean) { working = true; error = null; SaveSync.resolveMany(ctx, group, here) { err -> working = false; error = err } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Saves.appName(ctx, key.second)} · ${group.size} games:", Modifier.weight(1f, fill = false))
                LOutlinedButton(onClick = { all(true) }, enabled = !working) { Text("All from this device") }
                LOutlinedButton(onClick = { all(false) }, enabled = !working) { Text("All from ${group.first().peerName}") }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Look.danger) }
        }
        for (c in conflicts) ConflictRow(ctx, c)
    }
}

/**
 * Los conflictos en una ventana encima de la app, en cualquier pestaña: sale sola cuando aparecen
 * y se va al resolverlos. "Later" la cierra hasta que cambien (o se abra desde su notificacion).
 */
@Composable
fun ConflictsPopup(ctx: Context) {
    val version by LinkState.savesChanged
    val conflicts = remember(version) { Saves.conflicts(ctx) }
    val key = conflicts.joinToString("|") { it.key }
    val dismissed by LinkState.conflictsDismissed
    if (conflicts.isEmpty() || dismissed == key) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { LinkState.conflictsDismissed.value = key },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth(0.92f), shape = Look.shape,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (conflicts.size == 1) "Choose which save to keep" else "${conflicts.size} saves to choose",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    LTextButton(onClick = { LinkState.conflictsDismissed.value = key }) { Text("Later") }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) { ConflictList(ctx, conflicts) }
            }
        }
    }
}

/**
 * Devolver partidas desde los respaldos de este device: los juegos que hay en ellos y, de cada uno,
 * sus versiones por fecha. Antes de reemplazar se respalda lo que hay. Ver SaveRestore.
 */
@Composable
private fun RestoreDialog(ctx: Context, e: EmuSaves, onClose: () -> Unit) {
    val clock = remember { SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US) }
    var reload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val games by androidx.compose.runtime.produceState<List<SaveRestore.SaveGame>?>(null, reload) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { SaveRestore.games(ctx, e.pkg) }.getOrDefault(emptyList()) }
    }
    var gameKey by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<SaveRestore.Version?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var filter by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) onClose() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f), shape = Look.shape,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Restore saves · ${Saves.appName(ctx, e.pkg)}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    LTextButton(onClick = onClose, enabled = !busy) { Text("Close") }
                }
                Text("The current saves are backed up first.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                result?.let { (ok, msg) -> Text(msg, color = if (ok) Look.ok else Look.warn) }
                val list = games
                if (list == null) { Text("Reading the backups…"); return@Column }
                if (list.isEmpty()) { Text("No backups yet."); return@Column }
                val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
                val shown = list.filter { g -> words.all { g.title.lowercase().contains(it) || g.group.lowercase().contains(it) } }
                val game = list.firstOrNull { it.key == gameKey } ?: shown.firstOrNull()
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Los juegos.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        androidx.compose.material3.OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true,
                            shape = Look.shape, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            for (g in shown) {
                                val on = g.key == game?.key
                                Box(Modifier.fillMaxWidth().selectedRow(on).clickable { gameKey = g.key; confirm = null }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    RowContent(on) {
                                        Column {
                                            Text(g.title, color = if (on) MenuInk else MenuDim, maxLines = 1)
                                            Text(listOfNotNull(g.group.ifEmpty { null }, "${g.versions.size} ${if (g.versions.size == 1) "version" else "versions"}")
                                                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MenuDim)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Sus versiones, de la mas nueva a la mas vieja.
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (game == null) Text("Pick a game.", color = MenuDim)
                        else for (v in game.versions) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Saved ${clock.format(Date(v.saved))}")
                                    Text((if (v.size < 1024) "${v.size} B" else "${v.size / 1024} KB") + " · backup ${clock.format(Date(v.at))} (${v.why})" +
                                        (if (v.copies > 1) " · same in ${v.copies} backups" else ""),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (confirm === v) LButton(onClick = {
                                    busy = true; result = null
                                    thread(isDaemon = true) {
                                        val err = SaveRestore.restore(ctx, e, v)
                                        LinkState.post {
                                            busy = false; confirm = null; reload++
                                            result = if (err == null) true to "Restored ${game.title} (saved ${clock.format(Date(v.saved))})."
                                                else false to "Couldn't restore: $err"
                                        }
                                    }
                                }, enabled = !busy) { Text(if (busy) "Restoring…" else "Confirm restore") }
                                else LOutlinedButton(onClick = { confirm = v }, enabled = !busy) { Text("Restore") }
                            }
                            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}
