package com.felp.ludologlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.felp.ludolog.kit.ReleaseCheck
import com.felp.ludolog.kit.ui.LudologLook
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Pane
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import androidx.compose.foundation.clickable
import com.felp.frontcomp.GalleryTheme
import com.felp.frontcomp.Theme
import com.felp.frontcomp.bright
import com.felp.frontcomp.themeById
import com.felp.frontcomp.withAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var storageOk by mutableStateOf(false)
    private var romsRoot by mutableStateOf<String?>(null)
    private var romsSummary by mutableStateOf("")
    private var nickname by mutableStateOf("")
    private var editingName by mutableStateOf(false)
    /** La ventana About, que se abre desde el final de Settings. */
    private var about by mutableStateOf(false)
    /** La ultima version publicada de Link que se conoce (ver ReleaseCheck), y lo que dice About. */
    private var latest by mutableStateOf<String?>(null)
    private var aboutBusy by mutableStateOf(false)
    private var aboutNote by mutableStateOf<String?>(null)
    private var updates by mutableStateOf(true)
    /** La pestaña que se ve: link (lo de siempre), saves (partidas guardadas) o roms (traer ROMs de otro device). */
    private var tab by mutableStateOf("link")
    /** El wizard del primer arranque, en vez de la pantalla (ver SetupWizard). */
    private var setup by mutableStateOf(false)
    private var notificationsOk by mutableStateOf(true)
    /** Lo que deja a Link escuchar con la pantalla apagada (ver Background). */
    private var batteryOk by mutableStateOf(true)
    private var cleanerOk by mutableStateOf<Boolean?>(null)


    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Prefs.setRomsRoot(this, RomStore.treeUriToPath(uri))
            refresh()
        }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("tab") == "saves") tab = "saves"
        if (intent.getBooleanExtra("conflicts", false)) LinkState.conflictsDismissed.value = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updates = Prefs.updateCheck(this)
        latest = Prefs.updateLatest(this)
        // Una vez al dia como mucho, si hay version nueva en GitHub: se dice una vez en la actividad
        // y siempre en About. Fuera del hilo de la pantalla.
        Thread { runCatching { checkUpdates(force = false) } }.start()
        // Desde la notificacion de conflictos: directo a la pestaña de partidas.
        if (intent?.getStringExtra("tab") == "saves") tab = "saves"
        if (intent?.getBooleanExtra("conflicts", false) == true) LinkState.conflictsDismissed.value = null
        // El wizard, la primera vez y solo si no hay nada emparejado: quien ya usaba Link no lo ve
        // al actualizar (y queda como hecho). En el wizard las notificaciones se piden en su paso.
        if (!Prefs.setupDone(this)) {
            if (Peers.all(this).isEmpty() && Prefs.tokens(this).isEmpty()) setup = true
            else Prefs.setSetupDone(this, true)
        }
        if (!setup && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { LudologLook(look) { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        LinkState.uiVisible = true
        // Con la app a la vista, los conflictos salen en su ventana: el aviso sobra.
        SaveSync.clearNotice(this)
        refresh()
        // Con la app a la vista se escucha siempre (para emparejar): si no estaba, arranca.
        LinkService.ensure(this)
    }

    override fun onPause() {
        super.onPause()
        LinkState.uiVisible = false
        // Y al salir, solo si hace falta (devices o PC Link).
        LinkService.ensure(this)
    }

    /**
     * El aspecto de Ludolog en esta consola, en su version simplificada (ver LudologLook): el
     * tema puesto, su luz, su acento y la letra que traiga instalada. Sin Ludolog, el minimalista.
     */
    private var look by mutableStateOf<Theme>(GalleryTheme)

    private fun currentLook(): Theme {
        val chosen = Prefs.look(this)
        val dir = Ludolog.dataDir(this) ?: return if (chosen == "ludolog") GalleryTheme else themeById(chosen)
        val c = Ludolog.config(dir)
        // Uno elegido aqui: ese tema tal cual, con la letra que Ludolog tenga instalada para el.
        val base = themeById(if (chosen == "ludolog") Ludolog.themeId(c) else chosen)
        val t = if (chosen != "ludolog") base else {
            val lit = if (c["theme.bright.${base.id}"] as? Boolean == true) base.bright() ?: base else base
            lit.withAccent(c["look.accent.${base.id}"] as? String)
        }
        fun font(stem: String) = listOf("ttf", "otf").map { java.io.File(dir, "themes/${base.id}/font/$stem.$it") }
            .firstOrNull { it.isFile }
            ?.let { runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(it)) }.getOrNull() }
        return t.copy(display = font("display") ?: t.display, body = font("body") ?: t.body)
    }

    private fun refresh() {
        notificationsOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        nickname = Prefs.deviceName(this)
        batteryOk = Background.batteryOk(this)
        cleanerOk = Background.cleanerOk(this)
        Pairing.refresh(this)
        // Lo que lee disco (el aspecto de Ludolog y sus letras, la carpeta de ROMs), fuera del hilo de
        // la pantalla: en la tarjeta, al volver a la app, se notaba.
        kotlin.concurrent.thread(isDaemon = true) {
            val l = currentLook()
            val ok = Environment.isExternalStorageManager()
            if (ok && RomStore.root(this) == null) RomStore.detect(this)?.let { Prefs.setRomsRoot(this, it) }
            val root = Prefs.romsRoot(this)
            val sum = summary()
            runOnUiThread { look = l; storageOk = ok; romsRoot = root; romsSummary = sum }
        }
    }

    /** El apodo es lo que ve el Ludolog Link del PC. */
    @Composable
    private fun NameDialog() {
        var text by remember { mutableStateOf(nickname) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Device name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Shown on the PC and your other devices.")
                    OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true)
                }
            },
            confirmButton = {
                LTextButton(onClick = {
                    Prefs.setDeviceName(this@MainActivity, text)
                    nickname = Prefs.deviceName(this@MainActivity)
                    editingName = false
                }) { Text("Save") }
            },
            dismissButton = { LTextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }

    /** Cuanto espera PC Link sin que el PC haga nada antes de apagarse, y el mosaico del panel rapido. */
    @Composable
    private fun SettingsSection() {
        var idle by remember { mutableStateOf(Prefs.idleMinutes(this)) }
        var chosen by remember { mutableStateOf(Prefs.look(this)) }
        Section("Settings") {
            // Como el PC: seguir a Ludolog (tema, modo claro y acento) o uno fijo.
            Text("Look")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((id, label) in listOf("ludolog" to "Same as Ludolog") + com.felp.frontcomp.AllThemes.map { it.id to it.name }) {
                    val on = id == chosen
                    Box(Modifier.selectedRow(on).clickable {
                        Prefs.setLook(this@MainActivity, id); chosen = id; look = currentLook()
                    }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        RowContent(on) { Text(label, color = if (on) MenuInk else MenuDim) }
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            Text("PC Link auto-off")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in Prefs.IDLE_CHOICES) {
                    val on = m == idle
                    Box(Modifier.selectedRow(on).clickable { Prefs.setIdleMinutes(this@MainActivity, m); idle = m }
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        RowContent(on) {
                            Text(when { m == 0 -> "Never"; m >= 60 -> "${m / 60} h"; else -> "$m min" },
                                color = if (on) MenuInk else MenuDim)
                        }
                    }
                }
            }
            Text("After this long without PC activity.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("First-launch setup.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LOutlinedButton(onClick = { setup = true }) { Text("Run setup again") }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick settings tile for PC Link.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LOutlinedButton(onClick = { addTile() }) { Text("Add tile") }
                }
            }
            Spacer(Modifier.size(4.dp))
            Text("Check for updates")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((on, label) in listOf(true to "On", false to "Off")) {
                    val chosen = on == updates
                    Box(Modifier.selectedRow(chosen).clickable { Prefs.setUpdateCheck(this@MainActivity, on); updates = on }
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        RowContent(chosen) { Text(label, color = if (chosen) MenuInk else MenuDim) }
                    }
                }
            }
            Text("Once a day, ask GitHub whether there is a newer Ludolog Link. It only tells you; nothing is downloaded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Lo ultimo de Settings: la version y quien lo hace.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val v = latest
                Text("Version ${BuildInfo.VERSION}." + if (v != null && ReleaseCheck.newer(v, BuildInfo.VERSION)) " Version $v is available." else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LOutlinedButton(onClick = { aboutNote = null; about = true }) { Text("About") }
            }
        }
        if (about) AboutDialog()
    }

    /**
     * Pregunta a GitHub por la ultima version: como mucho una vez al dia, o ya si se pide desde
     * About. De una version nueva se avisa una sola vez en la actividad. Bloquea.
     */
    private fun checkUpdates(force: Boolean): Result<String>? {
        // Ni durante el asistente del primer arranque: se pregunta despues de que se haya podido ver el ajuste.
        if (!force && (!Prefs.updateCheck(this) || !Prefs.setupDone(this))) return null
        var result: Result<String>? = null
        if (force || System.currentTimeMillis() - Prefs.updateAt(this) >= ReleaseCheck.DAY_MS) {
            result = ReleaseCheck.fetch("Ludolog Link")
            Prefs.setUpdateAt(this, System.currentTimeMillis())
            result.onSuccess { Prefs.setUpdateLatest(this, it) }
        }
        val v = Prefs.updateLatest(this)
        runOnUiThread { latest = v }
        if (v != null && ReleaseCheck.newer(v, BuildInfo.VERSION) && Prefs.updateNotified(this) != v) {
            Prefs.setUpdateNotified(this, v)
            runOnUiThread { LinkState.addLog("Ludolog Link $v is available: Settings → About") }
        }
        return result
    }

    /** El banner del README, la version y quien lo hace. */
    @Composable
    private fun AboutDialog() {
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Ludolog Link") },
            text = {
                // El banner, bajo, y todo desplazable: en una pantalla apaisada el dialogo es corto, y a
                // todo lo ancho el banner dejaba fuera los botones.
                val scroll = rememberScrollState()
                // La respuesta de un boton queda debajo de todo: sin bajar hasta ella, no se veia.
                LaunchedEffect(aboutNote) { if (aboutNote != null) scroll.animateScrollTo(scroll.maxValue) }
                Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.about_banner),
                        contentDescription = "Ludolog",
                        modifier = Modifier.fillMaxWidth().height(96.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                    val v = latest
                    val newer = v != null && ReleaseCheck.newer(v, BuildInfo.VERSION)
                    Text("Version ${BuildInfo.VERSION}" + if (newer) " · $v available" else "")
                    Text("Made by monkikolab", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Uno debajo del otro: lado a lado, en una consola el segundo quedaba aplastado.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (newer) LOutlinedButton(onClick = {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseCheck.PAGE))) }
                        }) { Text("Open release page") }
                        else LOutlinedButton(onClick = {
                            if (aboutBusy) return@LOutlinedButton
                            aboutBusy = true
                            aboutNote = "Checking…"
                            Thread {
                                val r = runCatching { checkUpdates(force = true) }.getOrNull()
                                runOnUiThread {
                                    aboutNote = r?.fold(
                                        { if (ReleaseCheck.newer(it, BuildInfo.VERSION)) null else "Up to date." },
                                        { "Couldn't check: ${it.message ?: "no answer"}." },
                                    )
                                    aboutBusy = false
                                }
                            }.start()
                        }) { Text("Check for updates") }
                        LOutlinedButton(onClick = {
                            if (aboutBusy) return@LOutlinedButton
                            aboutBusy = true
                            aboutNote = "Saving…"
                            Thread {
                                val r = runCatching { Diagnostics.export(this@MainActivity) }
                                runOnUiThread {
                                    aboutNote = r.fold({ "Saved to $it. Check it before sharing: it can contain folder paths." },
                                        { "Couldn't save: ${it.message ?: it.javaClass.simpleName}." })
                                    aboutBusy = false
                                }
                            }.start()
                        }) { Text("Export diagnostics") }
                    }
                    aboutNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            },
            confirmButton = { LTextButton(onClick = { about = false }) { Text("Close") } },
        )
    }

    /** Pide a Android poner el mosaico en el panel rapido (la persona lo acepta en su ventana). */
    private fun addTile() {
        if (Build.VERSION.SDK_INT < 33) return
        val sb = getSystemService(android.app.StatusBarManager::class.java) ?: return
        sb.requestAddTileService(android.content.ComponentName(this, LinkTileService::class.java), "Ludolog Link",
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile), mainExecutor) { result ->
            LinkState.addLog(when (result) {
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Quick settings tile added"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "The quick settings tile is already there"
                android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile not added"
                else -> "Couldn't add the tile ($result)"
            })
        }
    }

    private fun summary(): String {
        val root = RomStore.root(this) ?: return if (romsRoot != null) "Can't be read" else ""
        val systems = root.listFiles()?.count { s ->
            s.isDirectory && !s.name.startsWith(".") && s.listFiles()?.any(RomStore::isRom) == true
        } ?: 0
        val vol = RomStore.volumeOf(this, root.absolutePath)
        // En GiB, como los muestra Windows: que la consola y el PC den la misma cifra.
        val gib = (1L shl 30).toDouble()
        val space = vol?.let { "%.1f GB free of %.1f GB".format(it.free / gib, it.total / gib) } ?: ""
        return "$systems consoles with games · $space"
    }

    // ------------------------------------------------------------- interfaz

    @Composable
    private fun Section(title: String, content: @Composable () -> Unit) {
        Pane(title, Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }

    @Composable
    private fun Screen() {
        if (setup) {
            SetupWizard(
                ctx = this@MainActivity,
                storageOk = storageOk,
                notificationsOk = notificationsOk,
                batteryOk = batteryOk,
                cleanerOk = cleanerOk,
                romsRoot = romsRoot,
                romsSummary = romsSummary,
                onGrantFiles = {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                },
                onAskNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onPickRoms = { pickFolder.launch(null) },
                onDetectRoms = { RomStore.detect(this@MainActivity)?.let { Prefs.setRomsRoot(this@MainActivity, it) }; refresh() },
                onAddTile = if (Build.VERSION.SDK_INT >= 33) ({ addTile() }) else null,
                onDone = { Prefs.setSetupDone(this@MainActivity, true); setup = false; refresh() },
            )
            return
        }
        val running by LinkState.running
        val address by LinkState.address
        val code by LinkState.pairingCode
        val codePc by LinkState.pairingPc
        val transfer by LinkState.transferName
        val progress by LinkState.transferProgress

        val scroll = rememberScrollState()
        // Si llega un codigo con la pantalla desplazada, subir para que se vea.
        LaunchedEffect(code) { if (code != null) scroll.animateScrollTo(0) }

        // Lo que hay que elegir, encima de todo, en cualquier pestaña.
        ConflictsPopup(this@MainActivity)

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(Look.title("Ludolog Link"), style = MaterialTheme.typography.headlineMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(nickname, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LTextButton(onClick = { editingName = true }) { Text("Rename") }
                }
                if (editingName) NameDialog()

                // Pestañas: lo de siempre, y las partidas guardadas.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((id, label) in listOf("link" to "Link", "saves" to "Saves", "roms" to "ROMs", "log" to "Log")) {
                        val on = tab == id
                        Box(Modifier.selectedRow(on).clickable { tab = id }.padding(horizontal = 18.dp, vertical = 8.dp)) {
                            RowContent(on) { Text(Look.title(label), color = if (on) MenuInk else MenuDim) }
                        }
                    }
                }

                val pcLink by LinkState.pcLink
                val sync by LinkState.syncDevices
                if (tab == "saves") SavesScreen(this@MainActivity, running && sync) else if (tab == "roms") RomsScreen(this@MainActivity)
                else if (tab == "log") LogScreen(this@MainActivity) else {

                if (!storageOk) {
                    Section("File access needed") {
                        Text("Needed to read and write your ROM folder.")
                        LButton(onClick = {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")))
                        }) { Text("Grant access") }
                    }
                }

                // Lo que corta a Link con la pantalla apagada, si se puede saber (el limpiador de procesos del fabricante).
                if (cleanerOk == false) {
                    Section("Keep listening") {
                        KeepListeningRows(this@MainActivity, batteryOk, cleanerOk)
                    }
                }

                code?.let { c ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Code for $codePc", style = MaterialTheme.typography.titleMedium)
                            Text(c.chunked(3).joinToString(" "), style = MaterialTheme.typography.displayLarge,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            // Lo pide un PC u otro device: el nombre lo dice.
                            Text("Enter it on $codePc. Expires in 2 min.")
                        }
                    }
                }

                Section("Status") {
                    // Dos cosas: escuchar a los devices (sola, sin tocarla) y PC Link (a mano, se apaga sola).
                    val devices = remember(LinkState.peersChanged.intValue) { Peers.all(this@MainActivity).size }
                    val listening = running && sync && devices > 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(if (listening) Look.ok else Look.off, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when {
                                devices == 0 -> "No devices paired"
                                !sync -> "Sharing off"
                                listening -> "Listening"
                                else -> "Not listening"
                            })
                            Text(when {
                                devices == 0 -> "Pair a device below."
                                !sync -> "Nothing is shared until you turn sharing on."
                                listening -> "Sessions, saves and game info"
                                else -> "Stopped by Android. Opening Ludolog wakes it."
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(if (pcLink && running) Look.ok else Look.off, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (pcLink) "PC Link on · $address" else "PC Link off")
                            Text("For the PC app",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (pcLink) LOutlinedButton(onClick = { LinkService.setPcLink(this@MainActivity, false) }) { Text("Turn off") }
                        else LButton(onClick = { LinkService.setPcLink(this@MainActivity, true) }) { Text("Turn on") }
                    }
                }

                CompanionShareSection(this@MainActivity, running)

                transfer?.let { name ->
                    Section(if (LinkState.transferOutgoing.value) "Sending to PC" else "Receiving") {
                        Text(name)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()} %")
                    }
                }

                Section("ROM folder") {
                    Text(romsRoot ?: "Not found", fontFamily = FontFamily.Monospace)
                    if (romsSummary.isNotEmpty()) Text(romsSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LOutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Change…") }
                        LOutlinedButton(onClick = {
                            RomStore.detect(this@MainActivity)?.let { Prefs.setRomsRoot(this@MainActivity, it) }
                            refresh()
                        }) { Text("Detect") }
                    }
                }

                Section("Paired PCs") {
                    // Los tokens de las devices emparejadas tambien estan en esa lista: se olvidan en
                    // "Share the companion" (Peers.forget), que quita las dos mitades.
                    val peerNames = remember(LinkState.peersChanged.intValue) { Peers.all(this@MainActivity).map { it.name }.toSet() }
                    val pcs = LinkState.pairedPcs.filter { it !in peerNames }
                    if (pcs.isEmpty()) {
                        Text("None yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    pcs.forEach { pc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(pc, Modifier.weight(1f))
                            LTextButton(onClick = {
                                Prefs.forgetPc(this@MainActivity, pc)
                                Pairing.refresh(this@MainActivity)
                            }) { Text("Forget") }
                        }
                    }
                }

                SettingsSection()

                Section("Activity") {
                    if (LinkState.log.isEmpty()) Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinkState.log.take(12).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                }
            }
        }
    }
}
