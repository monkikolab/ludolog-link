package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.AllThemes
import com.felp.frontcomp.bright
import com.felp.frontcomp.accentHex
import com.felp.frontcomp.accentPresets
import com.felp.frontcomp.LEGACY_THEME_IDS
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.themeById
import com.felp.ludolog.kit.Format
import kotlinx.coroutines.launch

/** Una cifra con su rotulo, a lo ancho: el rotulo a la izquierda, el valor a la derecha. */
@Composable
private fun Fact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(150.dp), style = MaterialTheme.typography.bodyMedium, color = MenuDim)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MenuInk,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ overview

@Composable
internal fun OverviewView(app: AppState, e: ConsoleEntry) {
    val i = e.info
    val b = e.book
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pane("Device", Modifier.weight(1f).fillMaxHeight()) {
            Fact("Name", e.name)
            Fact("Model", listOfNotNull(i?.manufacturer, e.model).joinToString(" "))
            Fact("Android", i?.android)
            Fact("Battery", i?.battery?.let { "$it %" + if (i.charging) " · charging" else "" })
            Fact("Free space", if (i?.free != null && i.total != null) "${Format.size(i.free)} of ${Format.size(i.total)}" else null)
            val g = e.games
            Fact("ROMs", if (g.isEmpty()) null else
                "${files(g.size)} in ${g.map { it.system }.distinct().size} consoles · ${Format.size(g.sumOf { it.size })}")
            Fact("ROM folder", i?.romsRoot)
            Fact("Ludolog Link", i?.appVersion)
            Fact("Ludolog", i?.ludolog?.version ?: "not installed")
            i?.ludolog?.let { l ->
                val t = themeById(l.theme)
                Fact("Look", t.name + (if (l.bright) " · light" else "") + (l.accent?.let { " · accent $it" } ?: ""))
            }
            Spacer(Modifier.height(10.dp))
            LOutlinedButton(onClick = { app.globalTab = "games" }) { Text("Games") }
        }
        Pane("Companion", Modifier.weight(1f).fillMaxHeight()) {
            if (b == null) {
                Text(
                    if (i?.ludolog == null) "Ludolog isn't installed on this device." else e.companionNote ?: "Reading…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val o = b.overview
                Fact("Level", b.character.level.toString())
                Fact("Played", com.felp.frontcomp.span(o.totalMs))
                Fact("Sessions", o.sessions.toString())
                Fact("Games", o.games.toString())
                Fact("Devices", if (b.devices.size > 1) "${b.devices.size} counted" else b.devices.firstOrNull())
                b.recent.firstOrNull()?.let { Fact("Last played", "${it.title} · ${ago(System.currentTimeMillis() - it.startedAt)}") }
                if (o.bestStreakDays > 0) Fact("Streak", "${o.streakDays} d · best ${o.bestStreakDays}")
                Fact("Achievements", "${b.achievements.count { it.earned }} of ${b.achievements.size}")
                Spacer(Modifier.height(10.dp))
                LOutlinedButton(onClick = { e.tab = "companion" }) { Text("Open Companion") }
            }
        }
        Pane("Ludolog data on this PC", Modifier.weight(1f).fillMaxHeight()) {
            val dir = Mirror.dir(e.id)
            // Recorre la copia entera (con arte y videos tras un respaldo completo): en segundo plano.
            val bytes by androidx.compose.runtime.produceState(0L, e.syncedAt) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                }
            }
            val last = remember(e.syncedAt, e.backupProgress) {
                Backups.snapshots(e.id).listFiles { f -> f.name.endsWith(".zip") }.orEmpty().maxByOrNull { it.lastModified() }
            }
            Fact("Copy", if (bytes > 0) Format.size(bytes) else "none yet")
            Fact("Updated", e.syncedAt?.let { ago(System.currentTimeMillis() - it) })
            Fact("Last backup", last?.let { ago(System.currentTimeMillis() - it.lastModified()) } ?: "never")
            Fact("Where", Config.consoleDir(e.id).absolutePath)
            Text(
                "Copied each time you connect. The Companion here reads it.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MenuDim,
            )
            Spacer(Modifier.height(10.dp))
            var backingUp by remember { mutableStateOf(false) }
            var restoring by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LButton(onClick = { backingUp = true }, enabled = i?.ludolog != null) { Text("Back up now…") }
                LOutlinedButton(onClick = { restoring = true }, enabled = !e.restoring) { Text(if (e.restoring) "Restoring…" else "Restore…") }
                LOutlinedButton(onClick = { app.refreshCompanion(e) }, enabled = i?.ludolog != null && e.companionNote != "syncing") {
                    Text(if (e.companionNote == "syncing") "Syncing…" else "Sync now")
                }
            }
            if (backingUp) BackupDialog(app, e) { backingUp = false }
            if (restoring) RestoreDialog(app, e) { restoring = false }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
internal fun SettingsView(app: AppState, e: ConsoleEntry, window: java.awt.Window) {
    var sub by remember { mutableStateOf("pc") }
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // About, lo ultimo: la version y quien lo hace.
            for ((id, label) in listOf("pc" to "This PC", "console" to "Console", "about" to "About")) {
                val on = sub == id
                Box(Modifier.selectedRow(on).clickable { sub = id }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    RowContent(on) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (on) MenuInk else MenuDim)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (sub) {
            "pc" -> PcSettings(app, e, window)
            "about" -> AboutPane(app)
            else -> ConsoleSettings(app, e)
        }
    }
}

/**
 * El banner del README (el de Ludolog, ver build.gradle.kts), la version y quien lo hace; si hay una
 * version nueva (PcUpdates), y el diagnostico para un aviso de fallo (PcDiagnostics).
 */
@Composable
private fun AboutPane(app: AppState) {
    val banner = remember {
        AppState::class.java.getResourceAsStream("/ludolog/about_banner.png")?.use {
            org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
        }
    }
    val version = remember {
        AppState::class.java.getResourceAsStream("/link/version.txt")?.use { it.reader().readText().trim() }.orEmpty()
    }
    Pane("About", Modifier.fillMaxWidth()) {
        if (banner != null) {
            androidx.compose.foundation.Image(
                banner, contentDescription = "Ludolog",
                modifier = Modifier.width(640.dp).padding(bottom = 12.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
            )
        }
        Text("Ludolog Link", style = MaterialTheme.typography.titleMedium, color = MenuInk)
        Spacer(Modifier.height(8.dp))
        val latest = PcUpdates.latest.value
        val newer = latest != null && com.felp.ludolog.kit.ReleaseCheck.newer(latest, version)
        Fact("Version", version + if (newer) " · $latest available" else "")
        Fact("Made by", "monkikolab")
        Spacer(Modifier.height(12.dp))
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf<String?>(null) }
        var saved by remember { mutableStateOf<java.io.File?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (newer) LOutlinedButton(onClick = {
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(com.felp.ludolog.kit.ReleaseCheck.PAGE)) }
            }) { Text("Open release page") }
            else LOutlinedButton(onClick = {
                if (busy) return@LOutlinedButton
                busy = true; saved = null; note = "Checking…"
                scope.launch {
                    val (r, _) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PcUpdates.check(force = true) }
                    note = r?.fold(
                        { if (com.felp.ludolog.kit.ReleaseCheck.newer(it, version)) null else "Up to date." },
                        { "Couldn't check: ${it.message ?: "no answer"}." },
                    )
                    busy = false
                }
            }) { Text("Check for updates") }
            LOutlinedButton(onClick = {
                if (busy) return@LOutlinedButton
                busy = true; saved = null; note = "Saving…"
                scope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PcDiagnostics.export() } }
                    saved = r.getOrNull()
                    note = r.fold({ "Saved to ${it.absolutePath}. Check it before sharing: it can contain folder paths." },
                        { "Couldn't save: ${it.message ?: it.javaClass.simpleName}." })
                    busy = false
                }
            }) { Text("Export diagnostics") }
        }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MenuDim)
        }
        saved?.let { f ->
            LTextButton(onClick = { runCatching { java.awt.Desktop.getDesktop().open(f.parentFile) } }) { Text("Open folder") }
        }
        Spacer(Modifier.height(12.dp))
        // Preguntar solo, una vez al dia: lo unico que sale es la peticion a GitHub.
        var auto by remember { mutableStateOf(Config.updateCheck) }
        Text("Check for updates once a day", style = MaterialTheme.typography.bodySmall, color = MenuDim)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((on, label) in listOf(true to "On", false to "Off")) {
                val chosen = on == auto
                Box(Modifier.selectedRow(chosen).clickable { Config.updateCheck = on; auto = on }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    RowContent(chosen) { Text(label, style = MaterialTheme.typography.bodyMedium, color = if (chosen) MenuInk else MenuDim) }
                }
            }
        }
    }
}

@Composable
private fun PcSettings(app: AppState, e: ConsoleEntry, window: java.awt.Window) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pane("Look", Modifier.weight(1f).fillMaxHeight()) {
            Text("Same as the device follows Ludolog's theme on it.",
                style = MaterialTheme.typography.bodySmall, color = MenuDim)
            Spacer(Modifier.height(8.dp))
            val options = listOf("console" to "Same as the device") + AllThemes.map { it.id to it.name }
            for ((id, label) in options) {
                val on = app.look == id
                Box(Modifier.fillMaxWidth().selectedRow(on).clickable { app.chooseLook(id) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    RowContent(on) { Text(label, color = if (on) MenuInk else MenuDim) }
                }
            }

        }
        Pane("This PC", Modifier.weight(1f).fillMaxHeight()) {
            Fact("Name", Config.pcName)
            Text("How devices see this PC.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
            Spacer(Modifier.height(8.dp))
            Fact("Backups", Config.backupRoot.absolutePath)
            LTextButton(onClick = { runCatching { Config.backupRoot.mkdirs(); java.awt.Desktop.getDesktop().open(Config.backupRoot) } }) {
                Text("Open backups folder")
            }
            Fact("Downloads go to", Config.folder("download_dir")?.absolutePath ?: "asked each time")
            Spacer(Modifier.height(8.dp))
            // El catalogo: juegos guardados en este PC, ordenados como en los devices (ver PcCatalog).
            var catalog by remember { mutableStateOf(PcCatalog.configured) }
            val here = remember(catalog) { runCatching { catalog?.isDirectory == true }.getOrDefault(false) }
            Fact("PC catalog", catalog?.absolutePath ?: "not set")
            if (catalog != null && !here) Text("Not found. Is its drive connected?",
                style = MaterialTheme.typography.bodySmall, color = Look.warn)
            Text("Games kept on this PC, one folder per console.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LTextButton(onClick = {
                scope.launch {
                    val d = chooseFolder(window, catalog?.takeIf { it.isDirectory }, "PC catalog folder") ?: return@launch
                    val made = PcCatalog.choose(d, app.consoles.flatMap { c -> c.consoleDirs.map { it.folder } }.distinct())
                    catalog = PcCatalog.configured
                    app.notify(if (made > 0) "PC catalog set. Created $made console folders." else "PC catalog set to ${d.absolutePath}.", about = null, kind = "settings")
                }
            }) { Text(if (catalog == null) "Choose catalog folder…" else "Change catalog folder…") }
            Spacer(Modifier.height(12.dp))
            Text(Look.title(e.name), style = MaterialTheme.typography.titleSmall, color = MenuInk)
            Text("Remove this PC on the device too.",
                style = MaterialTheme.typography.bodySmall, color = MenuDim)
            LTextButton(onClick = { app.forget(e) }) { Text("Forget this device", color = Look.danger) }
        }
    }
}

/**
 * Los ajustes de Ludolog en la consola. Se cambian aqui y quedan pendientes (en amarillo) hasta
 * "Sync to device": entonces Ludolog los aplica ella misma y se refresca. Las claves y sus
 * valores por defecto son los de Prefs.kt de Ludolog; los acentos, sus AccentPresets.
 */
@Composable
private fun ConsoleSettings(app: AppState, e: ConsoleEntry) {
    if (e.info?.ludolog == null && e.ludologConfig.isEmpty()) {
        Text("Ludolog isn't installed on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // Lo que vale ahora: lo pendiente manda sobre lo copiado de la consola.
    fun value(key: String): Any? = if (key in e.pending) e.pending[key] else e.ludologConfig[key]
    fun bool(key: String, default: Boolean) = value(key) as? Boolean ?: default
    fun int(key: String, default: Int) = (value(key) as? Number)?.toInt() ?: default
    // Con el id de ahora aunque la consola guarde uno viejo (gothic, retrofuture, minimal).
    val theme = (value("look.theme") as? String)?.let { LEGACY_THEME_IDS[it] ?: it } ?: "gallery"

    @Composable
    fun <T> Setting(label: String, key: String, options: List<Pair<T, String>>, current: T, write: (T) -> Any?) {
        val waiting = key in e.pending
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(label + if (waiting) "  •  pending" else "", style = MaterialTheme.typography.bodyMedium,
                color = if (waiting) Look.warn else MenuDim)
            // Las opciones bajan de linea si no caben (si no, la ultima se partia letra a letra).
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((v, name) in options) {
                    val on = v == current
                    Box(Modifier.selectedRow(on).clickable { app.stage(e, key, write(v)) }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                        RowContent(on) { Text(name, style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim) }
                    }
                }
            }
        }
    }

    @Composable
    fun Toggle(label: String, key: String, default: Boolean) =
        Setting(label, key, listOf(true to "On", false to "Off"), bool(key, default)) { it }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Pane("Theme", Modifier.weight(1f).fillMaxHeight()) {
            Section {
                Setting("Appearance", "look.theme", AllThemes.map { it.id to it.name }, theme) { it }
                if (themeById(theme).bright() != null) Toggle("Light mode", "theme.bright.$theme", false)
                val accent = value("look.accent.$theme") as? String
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    val waiting = "look.accent.$theme" in e.pending
                    Text("Accent" + if (waiting) "  •  pending" else "", style = MaterialTheme.typography.bodyMedium,
                        color = if (waiting) Look.warn else MenuDim)
                    for ((name, argb) in accentPresets(theme)) {
                        val hex = accentHex(argb)
                        val on = hex == accent
                        Row(Modifier.fillMaxWidth().selectedRow(on).clickable { app.stage(e, "look.accent.$theme", hex) }
                            .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(argb?.let { androidx.compose.ui.graphics.Color(it.toULong().toLong()) }
                                ?: themeById(theme).accent))
                            Spacer(Modifier.width(10.dp))
                            RowContent(on) { Text(name, style = MaterialTheme.typography.bodySmall, color = if (on) MenuInk else MenuDim) }
                        }
                    }
                }
                Toggle("Gameplay video", "look.video.$theme", true)
                Toggle("Interface sounds", "look.uisound.$theme", true)
                Toggle("Ambience", "look.ambience.$theme", true)
                Toggle("Status bar", "look.statusbar.$theme", true)
                Setting("Frame rate", "look.fps", listOf(30 to "30 fps", 60 to "60 fps"), int("look.fps", 30)) { it }
            }
        }
        Pane("Library", Modifier.weight(1f).fillMaxHeight()) {
            Section {
                Toggle("Game catalog", "catalog.on", true)
                Toggle("Fetch art after a scan", "scrape.onScan", false)
                @Suppress("UNCHECKED_CAST")
                Fact("ROM folders", (value("library.romdirs") as? Set<String>)?.joinToString("\n") ?: "Detected automatically")
                Fact("Favorites", e.ludologConfig.keys.count { it.startsWith("fav.") }.toString())
                Fact("Finished games", e.ludologConfig.keys.count { it.startsWith("done.") }.toString())
            }
        }
        Pane("Companion", Modifier.weight(1f).fillMaxHeight()) {
            Section {
                Toggle("Recording", "log.enabled", true)
                Setting("Shortest session", "log.minsession",
                    listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min", 0 to "Keep all"), int("log.minsession", 60)) { it }
                Toggle("Session card", "log.card", true)
            }
            Spacer(Modifier.height(12.dp))
            Text("Changes are sent with Sync to device.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
        }
    }
}

@Composable
private fun ColumnScope.Section(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
}
