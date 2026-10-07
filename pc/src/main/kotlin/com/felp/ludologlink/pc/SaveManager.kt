package com.felp.ludologlink.pc

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Los respaldos de partidas (.bak) que el PC bajo de un device (ver SaveBackups), abiertos: cada
 * uno es un zip con la carpeta de partidas del emulador en un momento. Aqui se ordenan por juego,
 * y por core cuando el emulador guarda en subcarpetas (RetroArch), con sus versiones distintas.
 */
object SaveArchive {

    /** Un .bak: su archivo, su emulador, cuando se hizo (de su nombre) y por que (daily, manual, before-sync...). */
    class Bak(val file: File, val pkg: String, val at: Long, val why: String)

    /** Un archivo de partida dentro de un .bak, con su fecha de guardado y su huella (CRC del zip). */
    class Entry(val path: String, val size: Long, val time: Long, val crc: Long)

    /**
     * Una version de las partidas de un juego: sus archivos tal como estan en [bak] (el respaldo mas
     * nuevo que la tiene). [copies]: en cuantos respaldos esta igual.
     */
    class Version(val bak: Bak, val entries: List<Entry>, val copies: Int) {
        val saved: Long get() = entries.maxOf { it.time }
        val size: Long get() = entries.sumOf { it.size }
    }

    /** Las partidas de un juego en un emulador: [group] es la subcarpeta (el core en RetroArch). */
    class SaveGame(val key: String, val title: String, val group: String, val versions: List<Version>)

    class Emulator(val pkg: String, val app: String, val baks: List<Bak>, val games: List<SaveGame>)

    // Las extensiones de partida que se quitan para llegar al juego: las de Saves.saveKey en la consola.
    private val SAVE_EXT = Regex("""^(srm|sav|sram|state\d*|auto|png|mcd|mcr|ps2|dsv|eep|fla|rtc|mpk|bin|dat|gci|raw)$""", RegexOption.IGNORE_CASE)
    private val STAMP = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

    /** El nombre del juego de un archivo de partida, como lo agrupa la consola (Saves.saveKey), con sus mayusculas. */
    fun title(path: String): String {
        var n = path.substringAfterLast('/')
        while ('.' in n && SAVE_EXT.matches(n.substringAfterLast('.'))) n = n.substringBeforeLast('.')
        return n.replace(CARD_NUMBER, "").trim()
    }

    private val CARD_NUMBER = Regex("""_\d+$""")

    private fun bak(f: File, pkg: String): Bak {
        val base = f.name.removeSuffix(".bak")
        val at = runCatching { STAMP.parse(base.take(17))!!.time }.getOrDefault(f.lastModified())
        return Bak(f, pkg, at, base.drop(18).ifEmpty { "backup" })
    }

    /** Todo lo de ese device en este PC. Los .bak rotos se saltan. */
    fun scan(deviceId: String): List<Emulator> {
        val root = SaveBackups.dir(deviceId)
        return root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val pkg = dir.name
            val baks = dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bak") }.map { bak(it, pkg) }.sortedByDescending { it.at }
            if (baks.isEmpty()) return@mapNotNull null
            // Por juego: en cada respaldo, sus archivos; y las versiones distintas, de la mas nueva a la mas vieja.
            val perGame = LinkedHashMap<String, MutableList<Pair<Bak, List<Entry>>>>()
            val titles = HashMap<String, Pair<String, String>>()
            for (b in baks) {
                val entries = runCatching {
                    ZipFile(b.file).use { z -> z.entries().asSequence().filter { !it.isDirectory }
                        .map { Entry(it.name, it.size, it.time, it.crc) }.toList() }
                }.getOrNull() ?: continue
                for ((key, list) in entries.groupBy { e -> e.path.substringBeforeLast('/', "") + "|" + title(e.path).lowercase() }) {
                    perGame.getOrPut(key) { mutableListOf() } += b to list
                    titles.putIfAbsent(key, title(list.first().path) to list.first().path.substringBeforeLast('/', ""))
                }
            }
            val games = perGame.map { (key, seen) ->
                val versions = ArrayList<Version>()
                // La firma de cada version, calculada una vez (antes, la de todas en cada comparacion).
                val sigs = ArrayList<List<Triple<String, Long, Long>>>()
                for ((b, list) in seen) {
                    val sig = list.map { Triple(it.path, it.size, it.crc) }.sortedBy { it.first }
                    val same = sigs.indexOf(sig)
                    if (same >= 0) versions[same] = Version(versions[same].bak, versions[same].entries, versions[same].copies + 1)
                    else { versions += Version(b, list, 1); sigs += sig }
                }
                val (title, group) = titles.getValue(key)
                SaveGame(key, title, group, versions)
            }.sortedWith(compareBy({ it.group.lowercase() }, { it.title.lowercase() }))
            val app = runCatching { File(dir, ".app").readText().trim() }.getOrNull()?.ifEmpty { null } ?: pkg
            Emulator(pkg, app, baks, games)
        }.sortedBy { it.app.lowercase() }
    }
}

/**
 * El Save manager de un device: sus emuladores, los juegos con partidas en los respaldos que hay
 * en este PC y, de cada uno, sus versiones por fecha, para devolver una a este device o a otro.
 */
@Composable
fun SavesManagerView(app: AppState, e: ConsoleEntry) {
    var version by remember { mutableIntStateOf(0) }
    val emulators by produceState<List<SaveArchive.Emulator>?>(null, e.id, version, app.savesVersion) {
        value = withContext(Dispatchers.IO) { runCatching { SaveArchive.scan(e.id) }.getOrDefault(emptyList()) }
    }
    var pkg by remember(e.id) { mutableStateOf<String?>(null) }
    var gameKey by remember(e.id) { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf<Pair<SaveArchive.Emulator, Pair<SaveArchive.SaveGame, SaveArchive.Version>>?>(null) }
    var fetching by remember { mutableStateOf(false) }
    val clock = remember { SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US) }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val list = emulators
        val all = list.orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                list == null -> "Reading the backups on this PC…"
                all.isEmpty() -> "No save backups of ${e.name} yet."
                else -> "${all.sumOf { it.baks.size }} backups of ${e.name} on this PC · ${Format.size(all.sumOf { em -> em.baks.sumOf { it.file.length() } })}"
            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MenuDim)
            if (e.online == true && e.paired) LTextButton(onClick = {
                fetching = true
                app.fetchSaveBackups(e) { fetching = false; version++ }
            }, enabled = !fetching) { Text(if (fetching) "Copying…" else "Copy new backups from ${e.name}") }
        }
        if (all.isEmpty()) return@Column
        val em = all.firstOrNull { it.pkg == pkg } ?: all.first()
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Los emuladores.
            Pane("Emulators", Modifier.width(240.dp).fillMaxHeight(), padding = 2) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(all, key = { it.pkg }) { x ->
                        val on = x.pkg == em.pkg
                        Box(Modifier.fillMaxWidth().selectedRow(on).clickable { pkg = x.pkg; gameKey = null }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            RowContent(on) {
                                Column {
                                    Text(x.app, color = if (on) MenuInk else MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${x.games.size} games · ${x.baks.size} backups", style = MaterialTheme.typography.bodySmall, color = MenuDim)
                                }
                            }
                        }
                    }
                }
            }
            // Sus juegos (por core si guarda en subcarpetas).
            val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
            val games = em.games.filter { g -> words.all { w -> g.title.lowercase().contains(w) || g.group.lowercase().contains(w) } }
            val game = em.games.firstOrNull { it.key == gameKey } ?: games.firstOrNull()
            Pane("Games", Modifier.weight(1f).fillMaxHeight(), padding = 2) {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, shape = Look.shape,
                        placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth().padding(8.dp))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(games, key = { it.key }) { g ->
                            val on = g.key == game?.key
                            Box(Modifier.fillMaxWidth().selectedRow(on).clickable { gameKey = g.key }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                RowContent(on) {
                                    Column {
                                        Text(g.title, color = if (on) MenuInk else MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(g.group.ifEmpty { null }, "${g.versions.size} ${if (g.versions.size == 1) "version" else "versions"}",
                                            "last saved ${clock.format(Date(g.versions.first().saved))}").joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MenuDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Sus versiones, de la mas nueva a la mas vieja.
            Pane(game?.title ?: "Versions", Modifier.weight(1.2f).fillMaxHeight(), padding = 2) {
                if (game == null) Text("Pick a game.", Modifier.padding(16.dp), color = MenuDim)
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(game.versions) { v ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Saved ${clock.format(Date(v.saved))}", color = MenuInk)
                                Text("${Format.size(v.size)} · ${v.entries.size} ${if (v.entries.size == 1) "file" else "files"} · " +
                                    "backup ${clock.format(Date(v.bak.at))} (${v.bak.why})" + if (v.copies > 1) " · same in ${v.copies} backups" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MenuDim)
                            }
                            LOutlinedButton(onClick = { restoring = em to (game to v) }) { Text("Restore…") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    restoring?.let { (em, gv) ->
        val (g, v) = gv
        RestoreSaveDialog(app, e, em, g, v, clock) { restoring = null; version++ }
    }
}

/** A donde devolver una version: este device u otro conectado con ese emulador; antes alla se respalda lo que hay. */
@Composable
private fun RestoreSaveDialog(app: AppState, e: ConsoleEntry, em: SaveArchive.Emulator, g: SaveArchive.SaveGame,
                              v: SaveArchive.Version, clock: SimpleDateFormat, onClose: () -> Unit) {
    val targets = (listOf(e) + app.consoles.filter { it.id != e.id }).filter { it.paired && it.online == true && it.info != null }
    var target by remember { mutableStateOf(targets.firstOrNull()?.id) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Restore ${g.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${em.app}${if (g.group.isNotEmpty()) " · ${g.group}" else ""} · saved ${clock.format(Date(v.saved))}")
                Text(v.entries.joinToString("\n") { it.path }, style = MaterialTheme.typography.bodySmall, color = MenuDim)
                if (targets.isEmpty()) Text("Connect a device to restore to.", color = Look.warn)
                else {
                    Text("Restore to", color = MenuDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in targets) {
                            val on = t.id == target
                            Box(Modifier.selectedRow(on).clickable { target = t.id }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                RowContent(on) { Text(t.name, color = if (on) MenuInk else MenuDim) }
                            }
                        }
                    }
                }
                Text("The current saves are backed up first. Close the game on the device.",
                    style = MaterialTheme.typography.bodySmall, color = MenuDim)
            }
        },
        confirmButton = {
            LButton(onClick = {
                val t = targets.firstOrNull { it.id == target } ?: return@LButton
                busy = true
                app.restoreSave(t, em, g, v) { busy = false; onClose() }
            }, enabled = !busy && targets.isNotEmpty()) { Text(if (busy) "Restoring…" else "Restore") }
        },
        dismissButton = { LTextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}
