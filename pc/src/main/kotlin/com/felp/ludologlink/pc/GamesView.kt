package com.felp.ludologlink.pc

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.Protocol
import com.felp.ludolog.kit.ui.KitIcons
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// GamesView.kt: la pestaña Games (AppState.globalTab = "games"; la elige ConsoleView). Solo
// interfaz: las filas salen de catalogRows y los iconos de PartIcon (CatalogView.kt), y todo lo que
// hace llama a AppState. Mapa del archivo:
//   GamesView           estado (filtros, seleccion, dialogos); el catalogo del PC releido si su
//                       disco esta (PcCatalog.latest); la barra de arriba; la tabla, una columna por
//                       sitio; abajo la barra de un sitio (placeSel) o la de lo marcado; los dialogos.
//   GamePanel           el panel derecho del juego elegido: arte, GameInfoSection y "Where it is".
//   lockedKind, LockedTag   Steam y DoomForge, entradas sin archivo (ver Shortcuts.isLocked).
//   ScrapeAndVideoBars  el progreso del scraper y de un video en cada device.
//   Choice, More, Toggle    controles pequeños de esta pestaña.

private enum class GameSort(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date") }

private val COL_CONSOLE = 96.dp
private val COL_EXT = 84.dp
private val COL_SIZE = 72.dp

/**
 * Games: los juegos de todos los sitios en una tabla, fila = juego y columna = sitio (el catalogo
 * del PC y cada device), con la consola de la pestaña primero. Junta lo que eran las pestañas ROMs
 * (los archivos de un device) y Catalog (que hay en cada sitio): "On <device>" es la de ROMs.
 *
 * Elegir un juego abre su panel a la derecha: arte, nombre y donde esta, con lo que se puede hacer
 * en cada sitio. Con varios marcados, la barra de abajo: lo de siempre, sobre los archivos de la
 * consola de la pestaña (Supr borra, F2 renombra, como antes).
 */
@Composable
fun GamesView(app: AppState, window: java.awt.Window) {
    // Todos los conectados, cada uno con su columna. Elegir uno en la barra lateral aqui solo cambia
    // el tema (con «Same as the device»): la tabla es la misma.
    val devices = app.consoles.filter { it.paired && it.online == true && it.info != null }
    fun fullName(folder: String) = devices.firstNotNullOfOrNull { d -> d.consoleDirs.firstOrNull { it.folder.equals(folder, true) }?.fullName } ?: folder
    // El catalogo puede estar en un disco externo: se mira cada pocos segundos si esta, y al volver
    // se lee de nuevo; cada minuto se relee por lo que cambie fuera de Link. Sin el, siguen los devices.
    var present by remember { mutableStateOf(PcCatalog.dir != null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var ticks = 0
        while (true) {
            val now = withContext(Dispatchers.IO) { PcCatalog.dir != null }
            if (now != present || (now && ++ticks % 6 == 0)) { present = now; version++ }
            delay(10_000)
        }
    }
    val catPath = PcCatalog.configured.path.lowercase()
    val downloads = app.transfers.items.count { !it.upload && it.state == TState.DONE && it.local.path.lowercase().startsWith(catPath) }
    var lastVersion by remember { mutableIntStateOf(-1) }
    val scan by produceState<PcCatalog.Scan?>(null, present, version, downloads) {
        // Una tanda de descargas no relee la carpeta una vez por archivo.
        if (lastVersion == version && value != null) delay(1_500)
        val forced = lastVersion != version
        lastVersion = version
        value = withContext(Dispatchers.IO) { PcCatalog.dir?.let { PcCatalog.latest(it, if (forced) 5_000 else 0) } }
    }
    // Las filas, fuera del hilo de la pantalla: con miles de ROMs se notaba al teclear y al marcar.
    val rowKey = devices.map { listOf(it.id, it.roms, it.art, it.video, it.ludologConfig, it.catalog, it.pending.toMap(), it.info) }
    val rows by produceState(emptyList<CatalogRow>(), rowKey, scan) {
        value = withContext(Dispatchers.Default) { catalogRows(devices, scan, withLocked = devices) }
    }
    val columns: List<Pair<String, String>> = (if (scan != null) listOf(CatalogRow.PC to "PC catalog") else emptyList()) +
        devices.map { it.id to it.name }

    var filter by remember { mutableStateOf("") }
    var console by remember { mutableStateOf(devices.firstOrNull()?.viewSystem) }
    // El filtro de device: (sitio, esta) — «On X» o «Not on X»; nulo, todos.
    var place by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var onlyConflicts by remember { mutableStateOf(false) }
    var onlyGaps by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(GameSort.NAME) }
    val selection = remember { mutableStateListOf<String>() }
    // "Esta marcada" con un conjunto: con miles de filas, una lista se recorria entera por fila.
    val picked by remember { derivedStateOf { selection.toHashSet() } }
    var anchor by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Pair<ConsoleEntry, RomFile>?>(null) }
    var deleting by remember { mutableStateOf<Pair<ConsoleEntry, List<RomFile>>?>(null) }
    /** Del catalogo del PC: a la Papelera (ver AppState.deleteFromCatalog). */
    var deletingPc by remember { mutableStateOf<List<RomFile>?>(null) }
    var orphans by remember { mutableStateOf<ConsoleEntry?>(null) }
    /**
     * El sitio elegido con un clic en su nombre, arriba de su columna: se marca todo lo que hay alli y
     * la barra de abajo es la de ese sitio (buscar arte, pasarlo a otro sitio, borrarlo). Nulo: la
     * barra de siempre. Marcar o desmarcar a mano lo deja en nulo (10-10-2026).
     */
    var placeSel by remember { mutableStateOf<String?>(null) }
    /** «Fill missing», calculado y esperando a que se confirme (ver AppState.planFill). */
    var filling by remember { mutableStateOf<AppState.FillPlan?>(null) }
    var deletingAll by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // La subida de ROMs toma la consola elegida como carpeta por defecto (ver UploadPlan).
    LaunchedEffect(console) { devices.forEach { it.viewSystem = console } }

    /** El archivo de la fila que se enseña: del primer device que lo tenga, si no del catalogo. */
    fun anyFile(r: CatalogRow) = devices.firstNotNullOfOrNull { r.cells[it.id]?.rom } ?: r.cells[CatalogRow.PC]?.rom
    /** Los archivos de [d] en las filas [rs] que si son archivos (sin Steam ni DoomForge). */
    fun filesOn(d: ConsoleEntry, rs: List<CatalogRow>) = rs.mapNotNull { r -> r.cells[d.id]?.rom }.filterNot(Shortcuts::isLocked)
    /**
     * Lo que no dice lo mismo en todos los sitios: el nombre o la descripcion (el punto amarillo, ver
     * GameInfo), o el archivo con otro nombre en otro sitio (ver «Rename to …» en el panel).
     */
    fun conflicted(r: CatalogRow) = GameInfo.mixed(devices, r) ||
        r.cells.values.mapNotNull { c -> c.rom?.name?.substringAfterLast('/')?.let(Protocol::stemOf) }.distinct().size > 1
    // Steam y DoomForge: solo cuenta lo suyo, arte y video; en los demas sitios no pueden estar.
    fun gaps(r: CatalogRow) = if (r.locked) r.cells.values.any { it.rom != null && (!it.art || !it.video) }
        else columns.any { (id, _) -> val c = r.cells[id]; c?.rom == null || !c.art || !c.video }

    val consoleCounts = remember(rows) { rows.groupingBy { it.console }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER) }
    val visible = remember(rows, filter, console, place, onlyGaps, onlyConflicts, sort, columns) {
        val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
        val base = rows.filter { r ->
            (console == null || r.console.equals(console, true)) &&
                place.let { pl -> pl == null || ((r.cells[pl.first]?.rom != null) == pl.second &&
                    // Steam y DoomForge no pueden estar en el catalogo del PC: «Not on PC catalog» no los cuenta.
                    !(r.locked && pl.first == CatalogRow.PC)) } &&
                (!onlyConflicts || conflicted(r)) &&
                (!onlyGaps || gaps(r)) &&
                words.all { w -> r.title.lowercase().contains(w) || r.console.lowercase().contains(w) ||
                    r.cells.values.any { it.rom?.name?.lowercase()?.contains(w) == true } }
        }
        when (sort) {
            GameSort.NAME -> base
            GameSort.SIZE -> base.sortedByDescending { anyFile(it)?.size ?: 0L }
            GameSort.DATE -> base.sortedByDescending { anyFile(it)?.mtime ?: 0L }
        }
    }
    // Lo que deja de verse deja de estar marcado.
    LaunchedEffect(visible) {
        val keys = visible.mapTo(HashSet()) { it.key }
        selection.retainAll { it in keys }
    }
    // Una consola que se quedo sin juegos en ningun sitio sale de la lista: si era la elegida, todas.
    LaunchedEffect(consoleCounts) {
        val c = console
        if (rows.isNotEmpty() && c != null && consoleCounts.keys.none { it.equals(c, true) }) console = null
    }
    val chosen = visible.filter { it.key in picked }
    // Lo que si son archivos, por device: Steam y DoomForge no se copian, ni se bajan, ni se borran.
    val filesBy = devices.associateWith { filesOn(it, chosen) }.filterValues { it.isNotEmpty() }
    /** Los del catalogo del PC entre lo marcado. */
    val pcFiles = if (scan == null) emptyList() else chosen.filterNot { it.locked }.mapNotNull { it.cells[CatalogRow.PC]?.rom }
    val single = chosen.singleOrNull()
    /** Para Supr y F2: el unico device que tenga archivos de lo marcado (con varios, el panel o la barra). */
    val keyTarget = filesBy.keys.singleOrNull()
    /** Borrar desde un icono de la tabla o el panel: de un device, o del catalogo del PC. */
    fun askDelete(where: String, f: RomFile) {
        if (where == CatalogRow.PC) deletingPc = listOf(f)
        else devices.firstOrNull { it.id == where }?.let { deleting = it to listOf(f) }
    }

    /** Todo lo que hay en [id] entre lo que se ve: su barra de abajo. Otro clic en el mismo, nada. */
    fun selectPlace(id: String) {
        selection.clear()
        if (placeSel == id) { placeSel = null; return }
        placeSel = id
        selection += visible.filter { it.cells[id]?.rom != null }.map { it.key }
    }

    fun click(r: CatalogRow, ctrl: Boolean, shift: Boolean) {
        placeSel = null
        focus.requestFocus()
        val a = anchor
        if (shift && a != null) {
            val i = visible.indexOfFirst { it.key == a }
            val j = visible.indexOf(r)
            if (i >= 0 && j >= 0) {
                if (!ctrl) selection.clear()
                visible.subList(minOf(i, j), maxOf(i, j) + 1).forEach { if (it.key !in picked) selection += it.key }
                return
            }
        }
        if (ctrl) { if (!selection.remove(r.key)) selection += r.key }
        else { selection.clear(); selection += r.key }
        anchor = r.key
    }

    /**
     * Bajar al PC: cada archivo desde un device que lo tiene, a la carpeta de juegos bajados (Settings,
     * This PC). Antes se preguntaba cada vez; ahora va alli directo y el aviso dice donde.
     */
    fun downloadAll(groups: Map<ConsoleEntry, List<RomFile>>) {
        if (groups.values.all { it.isEmpty() }) return
        val dir = Config.downloadDir.apply { mkdirs() }
        for ((d, files) in groups) if (files.isNotEmpty()) app.download(d, files, dir)
    }

    fun download(d: ConsoleEntry, files: List<RomFile>) = downloadAll(mapOf(d to files))

    /** Lo marcado que le falta a [dst], desde un device que lo tiene (o el catalogo del PC). */
    fun copyTo(dst: ConsoleEntry) {
        val missing = chosen.filter { !it.locked && it.cells[dst.id]?.rom == null }
        val bySource = LinkedHashMap<ConsoleEntry, MutableList<RomFile>>()
        for (r in missing) {
            val src = devices.firstOrNull { it.id != dst.id && r.cells[it.id]?.rom != null }
            if (src != null) bySource.getOrPut(src) { mutableListOf() } += r.cells[src.id]!!.rom!!
            else r.cells[CatalogRow.PC]?.let { c -> if (scan != null && c.rom != null) app.romFromCatalog(scan!!, c.rom, dst, c.artFile, c.videoFile) }
        }
        for ((src, files) in bySource) app.copyTo(src, dst, files)
    }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Barra: buscar, consola, donde, lo que falta, orden; y lo de la consola de la pestaña (scraper, huerfanos).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, shape = Look.shape,
                placeholder = { Text("Search") }, modifier = Modifier.widthIn(max = 220.dp),
                leadingIcon = { Icon(KitIcons.Search, null, Modifier.size(18.dp)) },
                trailingIcon = if (filter.isEmpty()) null else {
                    { IconButton(onClick = { filter = "" }) { Icon(KitIcons.Close, "Clear search", Modifier.size(16.dp)) } }
                })
            Choice(console?.let(::fullName) ?: "All consoles",
                listOf<Pair<String, () -> Unit>>("All consoles · ${rows.size}" to { console = null }) +
                    consoleCounts.map { (c, n) -> "${fullName(c)} · $n" to { console = c } })
            Choice(place?.let { (p, on) -> columns.firstOrNull { it.first == p }?.second?.let { if (on) "On $it" else "Not on $it" } } ?: "All devices",
                listOf<Pair<String, () -> Unit>>("All devices" to { place = null }) +
                    columns.map { (id, name) -> "On $name" to { place = id to true } } +
                    columns.map { (id, name) -> "Not on $name" to { place = id to false } })
            Toggle("Missing", onlyGaps) { onlyGaps = !onlyGaps }
            Toggle("Conflicts", onlyConflicts) { onlyConflicts = !onlyConflicts }
            Choice(sort.label, GameSort.entries.map { s -> s.label to { sort = s } })
            Box(Modifier.weight(1f))
            Text("${visible.size} games", style = MaterialTheme.typography.bodySmall, color = MenuDim)
            IconButton(onClick = { version++; devices.forEach(app::load) }) {
                Icon(KitIcons.Refresh, "Reload", Modifier.size(18.dp), tint = MenuDim)
            }
            // El scraper, en cada device con Ludolog: lo marcado, o todo lo que se ve.
            val withLudolog = devices.filter { it.info?.ludolog != null }
            if (withLudolog.isNotEmpty()) More(buildList {
                val rs = chosen.ifEmpty { visible }
                fun scrape(mode: ScrapeMode) = withLudolog.filter { it.scrapeState == null }.forEach { d ->
                    val target = rs.mapNotNull { it.cells[d.id]?.rom }
                    if (target.isNotEmpty()) app.scrape(d, target, mode)
                }
                if (withLudolog.any { it.scrapeState == null }) {
                    add("Scrape missing art and video" to { scrape(ScrapeMode.MISSING) })
                    add("Scrape missing covers" to { scrape(ScrapeMode.COVERS) })
                    add("Scrape missing videos" to { scrape(ScrapeMode.VIDEOS) })
                    add("Rescrape everything" to { scrape(ScrapeMode.ALL) })
                }
                for (d in withLudolog) add((if (withLudolog.size > 1) "Orphan art and video on ${d.name}…" else "Find orphan art and video…") to { orphans = d })
            })
        }
        // Donde esta el catalogo del PC, si no se ve.
        val configured = PcCatalog.configured
        if (scan == null) Text("PC catalog not found: ${configured.absolutePath}",
            style = MaterialTheme.typography.bodySmall, color = Look.warn)

        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pane(null, Modifier.weight(1f).fillMaxHeight(), padding = 2) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = visible.isNotEmpty() && chosen.size == visible.size, enabled = visible.isNotEmpty(),
                            onCheckedChange = { on -> placeSel = null; selection.clear(); if (on) selection += visible.map { it.key } })
                        Text("Game · file", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MenuDim)
                        Text("Console", Modifier.width(COL_CONSOLE), maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MenuDim)
                        Text("Ext", Modifier.width(COL_EXT), maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MenuDim)
                        Text("Size", Modifier.width(COL_SIZE), textAlign = TextAlign.End, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = MenuDim)
                        Box(Modifier.width(12.dp))
                        // Clic en el nombre de un sitio: todo lo suyo, con su barra (ver placeSel).
                        for ((id, name) in columns) {
                            val on = placeSel == id
                            Box(Modifier.width(112.dp).selectedRow(on).clickable { selectPlace(id) }.padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center) {
                                Text(name, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge, color = if (on) MenuInk else MenuDim)
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val state = rememberLazyListState()
                    Box(Modifier.weight(1f).fillMaxWidth().focusRequester(focus).focusable().onKeyEvent { k ->
                        if (k.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when {
                            k.key == Key.Delete && keyTarget != null -> { deleting = keyTarget to filesBy.getValue(keyTarget); true }
                            // Solo en el catalogo del PC: de alli.
                            k.key == Key.Delete && filesBy.isEmpty() && pcFiles.isNotEmpty() -> { deletingPc = pcFiles; true }
                            k.key == Key.F2 && single != null && keyTarget != null -> { renaming = keyTarget to filesBy.getValue(keyTarget).first(); true }
                            k.key == Key.A && k.isCtrlPressed -> { placeSel = null; selection.clear(); selection += visible.map { it.key }; true }
                            k.key == Key.Escape -> { placeSel = null; selection.clear(); true }
                            else -> false
                        }
                    }) {
                        if (visible.isEmpty()) Text(if (rows.isEmpty()) "No games yet." else "Nothing to show.",
                            Modifier.align(Alignment.Center), color = MenuDim)
                        LazyColumn(Modifier.fillMaxSize(), state = state) {
                            items(visible, key = { it.key }) { r ->
                                val on = r.key in picked
                                // Elegido un sitio por su columna: se marcan la casilla y su columna, no la fila
                                // entera (pedido del usuario, 10-10-2026): lo que se toca es lo de ese sitio.
                                val lit = on && placeSel == null
                                Row(Modifier.fillMaxWidth().selectedRow(lit).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RowContent(lit) {
                                        Checkbox(checked = on, onCheckedChange = { placeSel = null; if (!selection.remove(r.key)) selection += r.key; anchor = r.key })
                                        // Clic en el juego: elegirlo (Ctrl y Shift para varios), como en un explorador.
                                        Column(Modifier.weight(1f).pointerInput(r.key) {
                                            awaitEachGesture {
                                                awaitFirstDown()
                                                val mods = currentEvent.keyboardModifiers
                                                if (waitForUpOrCancellation() != null) click(r, mods.isCtrlPressed, mods.isShiftPressed)
                                            }
                                        }.padding(vertical = 4.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(r.title, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MenuInk)
                                                if (conflicted(r)) MixedDot()
                                            }
                                            // Debajo, el archivo (la consola tiene su columna).
                                            Text(anyFile(r)?.name?.substringAfterLast('/') ?: "", style = MaterialTheme.typography.bodySmall,
                                                color = MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(r.console, Modifier.width(COL_CONSOLE), style = MaterialTheme.typography.bodySmall, color = MenuDim,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(anyFile(r)?.let { f -> Protocol.extOf(f.name).removePrefix(".") } ?: "", Modifier.width(COL_EXT),
                                            style = MaterialTheme.typography.bodySmall, color = MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        // El tamaño del ROM; Steam y DoomForge no son archivos.
                                        Text(if (r.locked) "" else anyFile(r)?.let { Format.size(it.size) } ?: "", Modifier.width(COL_SIZE),
                                            textAlign = TextAlign.End, style = MaterialTheme.typography.bodySmall, color = MenuDim, maxLines = 1)
                                        Box(Modifier.width(12.dp))
                                        for ((id, _) in columns) Row(Modifier.width(112.dp)
                                            .then(if (on && placeSel == id) Modifier.background(Look.accent.copy(alpha = .22f), Look.shape) else Modifier)
                                            .padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            // Steam y DoomForge: sin archivo; su arte y su video donde esta la entrada, y en el catalogo del PC.
                                            if (!r.locked) for (p in Part.entries) PartIcon(app, devices, scan, r, id, p, window, ::askDelete) { version++ }
                                            else if (r.cells[id]?.rom != null || id == CatalogRow.PC) {
                                                r.cells[id]?.rom?.let { LockedTag(it) } ?: Box(Modifier.width(30.dp))
                                                for (p in listOf(Part.ART, Part.VIDEO)) PartIcon(app, devices, scan, r, id, p, window) { version++ }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
            // Un juego elegido: su panel.
            single?.let { r ->
                GamePanel(app, null, devices, scan, r, columns, Modifier.width(300.dp).fillMaxHeight(), window,
                    onRename = { d, f -> renaming = d to f }, onDelete = ::askDelete,
                    onDownload = { d, f -> download(d, listOf(f)) }, changed = { version++ }, onClose = { selection.clear() })
            }
        }

        for (d in devices) ScrapeAndVideoBars(app, d)
        // Buscando o guardando la caratula o el video de un juego (ver ArtPicker).
        app.picker.looking?.takeIf { app.picker.open == null }?.let { what ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$what…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(Modifier.width(160.dp))
                LTextButton(onClick = { app.picker.cancel() }) { Text("Cancel") }
            }
        }

        val ps = placeSel
        // Todo lo de un sitio (clic en su columna): buscar su arte, pasarlo a otro sitio o borrarlo de alli.
        if (ps != null && chosen.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val pName = columns.firstOrNull { it.first == ps }?.second ?: ps
            val pDev = devices.firstOrNull { it.id == ps }
            // Lo que de verdad es un archivo alli (Steam y DoomForge no se pasan ni se borran).
            val here = chosen.mapNotNull { r -> r.cells[ps]?.rom?.takeIf { !Shortcuts.isLocked(it) }?.let { r to it } }
            Text("${chosen.size} on $pName · ${Format.size(here.sumOf { it.second.size })}", Modifier.weight(1f), color = MenuDim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pDev != null && pDev.info?.ludolog != null && pDev.scrapeState == null)
                LTextButton(onClick = { app.scrape(pDev, chosen.mapNotNull { it.cells[ps]?.rom }, ScrapeMode.MISSING) }) { Text("Scrape art") }
            if (ps == CatalogRow.PC && scan != null && here.isNotEmpty() && devices.any { it.info?.ludolog != null && it.scrapeState == null })
                LTextButton(onClick = { app.scrapeCatalog(here.map { it.second }, devices, scan!!) { version++ } }) { Text("Scrape art") }
            // A otro sitio: lo que le falte alli, desde este.
            val targets = buildList<Pair<String, () -> Unit>> {
                if (ps != CatalogRow.PC && pDev != null && scan != null) {
                    val missing = here.filter { it.first.cells[CatalogRow.PC]?.rom == null }
                    if (missing.isNotEmpty()) add("PC catalog · ${missing.size}" to {
                        missing.forEach { (_, f) -> app.romToCatalog(pDev, f) }
                        app.toCatalog(missing.map { it.first }, withRom = false) { version++ }
                    })
                }
                for (d in devices) if (d.id != ps) {
                    val missing = here.filter { it.first.cells[d.id]?.rom == null }
                    if (missing.isNotEmpty()) add("${d.name} · ${missing.size}" to {
                        if (ps == CatalogRow.PC) scan?.let { s ->
                            missing.forEach { (r, f) -> app.romFromCatalog(s, f, d, r.cells[CatalogRow.PC]?.artFile, r.cells[CatalogRow.PC]?.videoFile) }
                        } else if (pDev != null) app.copyTo(pDev, d, missing.map { it.second })
                    })
                }
            }
            if (targets.isNotEmpty()) Choice("Transfer to", targets)
            if (here.isNotEmpty()) LTextButton(onClick = {
                if (ps == CatalogRow.PC) deletingPc = here.map { it.second } else if (pDev != null) deleting = pDev to here.map { it.second }
            }, colors = ButtonDefaults.textButtonColors(contentColor = Look.danger)) { Text("Delete from $pName") }
            LTextButton(onClick = { placeSel = null; selection.clear() }) { Text("Clear") }
        }
        // Con varios marcados: copiar, al catalogo, bajar y borrar, eligiendo el device donde haga falta.
        else if (chosen.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${chosen.size} selected", Modifier.weight(1f), color = MenuDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Lo que le falta a cada sitio, de donde este o del scraper (ver AppState.planFill).
            LTextButton(onClick = {
                val plan = app.planFill(chosen, devices, scan)
                if (plan.empty) app.notify("Nothing missing: every place already has these games and their art.") else filling = plan
            }) { Text("Fill missing…") }
            // A un device: lo que le falte de lo marcado, desde donde este.
            val canCopy = chosen.any { !it.locked && it.cells.values.any { c -> c.rom != null } }
            val dests = devices.filter { d -> chosen.any { !it.locked && it.cells[d.id]?.rom == null } }
            if (canCopy && dests.isNotEmpty()) Choice("Copy to", dests.map { d -> d.name to { copyTo(d) } })
            if (scan != null && chosen.any { !it.locked }) {
                LTextButton(onClick = { busy = true; app.toCatalog(chosen, withRom = true) { busy = false; version++ } }, enabled = !busy) {
                    Text("To PC catalog")
                }
                LTextButton(onClick = { busy = true; app.toCatalog(chosen, withRom = false) { busy = false; version++ } }, enabled = !busy) {
                    Text("Art to PC")
                }
            }
            if (filesBy.isNotEmpty()) {
                // Cada juego una vez, del primer device que lo tenga.
                LTextButton(onClick = {
                    val seen = HashSet<String>()
                    downloadAll(devices.associateWith { d ->
                        chosen.filter { !it.locked && it.cells[d.id]?.rom != null && seen.add(it.key) }.map { it.cells[d.id]!!.rom!! }
                    })
                }) { Text("Download") }
            }
            // Borrar es de un sitio (un device o el catalogo del PC), y se dice de cual.
            val wheres: List<Triple<String, Int, () -> Unit>> = filesBy.map { (d, f) -> Triple(d.name, f.size) { deleting = d to f } } +
                (if (pcFiles.isNotEmpty()) listOf(Triple("PC catalog", pcFiles.size) { deletingPc = pcFiles }) else emptyList())
            if (wheres.size == 1) LTextButton(onClick = wheres[0].third, colors = ButtonDefaults.textButtonColors(contentColor = Look.danger)) {
                Text("Delete from ${wheres[0].first}")
            }
            else if (wheres.size > 1) Choice("Delete from", wheres.map { (name, n, act) -> "$name · $n" to act } +
                ("Everywhere · ${wheres.sumOf { it.second }}" to { deletingAll = true }))
            LTextButton(onClick = { selection.clear() }) { Text("Clear") }
        } else Text("Click a game for its details · Ctrl/Shift+click to select several · Del deletes, F2 renames",
            style = MaterialTheme.typography.bodySmall, color = MenuDim)
    }

    renaming?.let { (d, f) ->
        RenameDialog(d, f, Names.display(d, f), Names.custom(d, f), onClose = { renaming = null }) { newFile, newDisplay ->
            renaming = null
            app.renameBoth(d, f, newFile, newDisplay)
        }
    }
    deleting?.let { (d, l) ->
        DeleteDialog(d.name, l, onClose = { deleting = null }) { deleting = null; selection.clear(); app.delete(d, l) }
    }
    deletingPc?.let { l ->
        DeleteDialog("PC catalog", l, onClose = { deletingPc = null }, recycle = true) {
            deletingPc = null; selection.clear()
            scan?.let { s -> app.deleteFromCatalog(s, l) { version++ } }
        }
    }
    if (deletingAll) {
        val places = filesBy.map { (d, f) -> "${d.name} · ${f.size} ${if (f.size == 1) "file" else "files"}, ${Format.size(f.sumOf { it.size })}" } +
            (if (pcFiles.isNotEmpty()) listOf("PC catalog · ${pcFiles.size} ${if (pcFiles.size == 1) "file" else "files"}, ${Format.size(pcFiles.sumOf { it.size })}") else emptyList())
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingAll = false },
            title = { Text("Delete from every place?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Permanent on the devices, with each .sbi. From the PC catalog, to the Recycle Bin.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { LTextButton(onClick = {
                deletingAll = false; selection.clear()
                app.deleteEverywhere(filesBy, pcFiles, scan) { version++ }
            }) { Text("Delete everywhere", color = Look.danger) } },
            dismissButton = { LTextButton(onClick = { deletingAll = false }) { Text("Cancel") } },
        )
    }
    filling?.let { plan ->
        fun nameOf(id: String) = columns.firstOrNull { it.first == id }?.second ?: id
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { filling = null },
            title = { Text("Fill what's missing?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (plan.roms.isNotEmpty()) Text("Copy ${plan.roms.size} ${if (plan.roms.size == 1) "game" else "games"} (${Format.size(plan.bytes)}): " +
                        plan.roms.groupingBy { it.to }.eachCount().entries.joinToString(", ") { (to, n) -> "$n to ${nameOf(to)}" })
                    if (plan.media.isNotEmpty()) Text("Copy ${plan.media.size} covers and videos from the places that have them: " +
                        plan.media.groupingBy { it.to }.eachCount().entries.joinToString(", ") { (to, n) -> "$n to ${nameOf(to)}" })
                    if (plan.scrape.isNotEmpty()) Text("Search online for the rest: " +
                        plan.scrape.entries.joinToString(", ") { (id, l) -> "${l.size} on ${nameOf(id)}" })
                    Text("Games go through the transfer queue; their art follows them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { LTextButton(onClick = {
                filling = null; selection.clear()
                app.fill(plan, devices, scan) { version++ }
            }) { Text("Fill") } },
            dismissButton = { LTextButton(onClick = { filling = null }) { Text("Cancel") } },
        )
    }
    orphans?.let { d -> OrphanMediaDialog(app, d) { orphans = null } }
    ArtPickDialog(app.picker)
}

/**
 * El panel del juego elegido: su arte, su nombre aqui y donde esta, con lo que se puede hacer en
 * cada sitio (en un menu por sitio: renombrar, descargar, borrar, arte; o de donde traerlo).
 */
@Composable
private fun GamePanel(
    app: AppState, pref: ConsoleEntry?, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, r: CatalogRow,
    columns: List<Pair<String, String>>, modifier: Modifier, window: java.awt.Window,
    onRename: (ConsoleEntry, RomFile) -> Unit, onDelete: (String, RomFile) -> Unit,
    onDownload: (ConsoleEntry, RomFile) -> Unit, changed: () -> Unit, onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clock = remember { SimpleDateFormat("d MMM yyyy", Locale.US) }
    fun dev(id: String) = devices.firstOrNull { it.id == id }
    var video by remember(r.key) { mutableStateOf(false) }
    var pick by remember(r.key) { mutableStateOf<String?>(null) }
    var spreading by remember { mutableStateOf(false) }
    Pane(null, modifier, padding = 10) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = MenuInk)
                    Text(r.console, style = MaterialTheme.typography.bodySmall, color = MenuDim)
                }
                IconButton(onClick = onClose) { Icon(KitIcons.Close, "Close", Modifier.size(16.dp), tint = MenuDim) }
            }
            // El arte: el del sitio elegido; si no, de esta consola si lo tiene, o del primero que lo tenga.
            val wantVideo = video
            val withMedia = (listOfNotNull(pref?.id) + r.cells.keys).distinct().filter { id ->
                val c = r.cells[id] ?: return@filter false
                if (wantVideo) c.video else c.art
            }
            val src = pick?.takeIf { it in withMedia } ?: withMedia.firstOrNull()
            val hasVideo = r.cells.values.any { it.video }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val c = src?.let { r.cells[it] }
                when {
                    c == null -> Text(if (wantVideo) "No video" else "No cover", color = MenuDim, modifier = Modifier.padding(24.dp))
                    src == CatalogRow.PC -> (if (wantVideo) c.videoFile else c.artFile)?.let { FilePreview(it, wantVideo) }
                    else -> dev(src)?.let { d -> c.rom?.let { MediaPreview(d, it, wantVideo) } }
                }
            }
            if (hasVideo) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Toggle("Cover", !video) { video = false }
                Toggle("Video", video) { video = true }
            }
            // De que sitio es lo que se ve, si hay varios; y ponerlo en todos los que tienen el juego.
            val names = columns.toMap()
            val withGame = r.cells.count { (id, c) -> c.rom != null && (id == CatalogRow.PC || dev(id)?.info?.ludolog != null) }
            if (src != null && (withMedia.size > 1 || withGame > 1)) Row(verticalAlignment = Alignment.CenterVertically) {
                if (withMedia.size > 1) Choice(names[src] ?: src, withMedia.map { id -> (names[id] ?: id) to { pick = id } })
                else Text(names[src] ?: src, style = MaterialTheme.typography.bodySmall, color = MenuDim, modifier = Modifier.padding(start = 12.dp))
                Box(Modifier.weight(1f))
                if (withGame > 1) LTextButton(onClick = { spreading = true; app.mediaEverywhere(r, src, devices, wantVideo) { spreading = false; changed() } },
                    enabled = !spreading) { Text("Use everywhere") }
            }

            GameInfoSection(app, pref?.id, devices, r)

            Text("Where it is", style = MaterialTheme.typography.labelLarge, color = MenuDim, modifier = Modifier.padding(top = 4.dp))
            for ((id, name) in columns) {
                val c = r.cells[id]
                val f = c?.rom
                val d = dev(id)
                // Steam y DoomForge solo estan en su device: los demas sitios no vienen al caso.
                if (r.locked && f == null) continue
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = if (f != null) MenuInk else MenuDim)
                        Text(f?.let { if (Shortcuts.isLocked(it)) lockedKind(it) else "${it.name.substringAfterLast('/')} · ${Format.size(it.size)}" +
                            if (it.mtime > 0) " · ${clock.format(Date(it.mtime))}" else "" } ?: "Not here",
                            style = MaterialTheme.typography.bodySmall, color = MenuDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val actions: List<Pair<String, () -> Unit>> = buildList {
                        // Buscar en internet, como en Ludolog (ver ArtPicker): reemplaza lo que tenga.
                        val canFetch = f != null && !Shortcuts.isLocked(f) && (if (d != null) d.info?.ludolog != null && d.scrapeState == null
                            else scan != null && devices.any { it.info?.ludolog != null && it.scrapeState == null })
                        if (f == null) addAll(fetchOptions(app, devices, scan, r, id, Part.ROM, changed))
                        else if (d == null) {
                            // En el catalogo del PC.
                            scan?.let { s -> add("Show in folder" to { app.showInFolder(PcCatalog.file(s, f)) }) }
                            if (canFetch) {
                                add("Fetch box art…" to { app.picker.fetch(r, id, devices, scan, video = false, changed) })
                                add("Fetch video…" to { app.picker.fetch(r, id, devices, scan, video = true, changed) })
                            }
                            add("Delete…" to { onDelete(id, f) })
                        } else {
                            val lockedEntry = Shortcuts.isLocked(f)
                            // Steam y DoomForge: su nombre se cambia en Info, que llega a los demas devices.
                            if (!lockedEntry) add("Rename…" to { onRename(d, f) })
                            // Con otro nombre en otro sitio: igualarlo (con su extension de aqui).
                            if (!lockedEntry) {
                                val mine = Protocol.stemOf(f.name.substringAfterLast('/'))
                                val ext = f.name.substringAfterLast('/').substring(mine.length)
                                r.cells.values.mapNotNull { it.rom?.name?.substringAfterLast('/')?.let(Protocol::stemOf) }
                                    .distinct().filter { it != mine }
                                    .forEach { other -> add("Rename to $other$ext" to { app.rename(d, f, other + ext) }) }
                            }
                            if (!lockedEntry) add("Download…" to { onDownload(d, f) })
                            if (d.info?.ludolog != null) {
                                if (canFetch) {
                                    add("Fetch box art…" to { app.picker.fetch(r, id, devices, scan, video = false, changed) })
                                    add("Fetch video…" to { app.picker.fetch(r, id, devices, scan, video = true, changed) })
                                }
                                add("Add art…" to { scope.launch { chooseImage(window)?.let { app.importArt(d, f, it) } } })
                                add("Add video…" to { scope.launch { chooseVideo(window)?.let { app.importVideo(d, f, it) } } })
                                if (c.art) add("Remove art" to { app.removeMedia(d, f, video = false) })
                                if (c.video) add("Remove video" to { app.removeMedia(d, f, video = true) })
                                // Lo que le falta de arte, de donde traerlo.
                                if (!c.art) addAll(fetchOptions(app, devices, scan, r, id, Part.ART, changed).map { (t, a) -> "Cover: $t" to a })
                                if (!c.video) addAll(fetchOptions(app, devices, scan, r, id, Part.VIDEO, changed).map { (t, a) -> "Video: $t" to a })
                            }
                            if (!lockedEntry) add("Delete…" to { onDelete(d.id, f) })
                        }
                    }
                    when {
                        actions.isEmpty() -> {}
                        f == null && actions.size == 1 -> LTextButton(onClick = actions[0].second) {
                            Text(actions[0].first, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> More(actions)
                    }
                }
            }
        }
    }
}

/** Que es una entrada sin archivo: de Steam o un perfil de DoomForge. */
internal fun lockedKind(f: RomFile) = if (Protocol.extOf(f.name) == ".doomforge") "DoomForge profile" else "Steam entry"

/** En la tabla, en vez del icono del ROM: una marca pequeña de que es. */
@Composable
private fun LockedTag(f: RomFile) {
    Text(if (Protocol.extOf(f.name) == ".doomforge") "DF" else "ST", Modifier.padding(horizontal = 4.dp).width(22.dp),
        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = Look.accent)
}

/** Las barras de una pasada del scraper o de un video preparandose en esa consola, y lo que quedo. */
@Composable
private fun ScrapeAndVideoBars(app: AppState, e: ConsoleEntry) {
    e.scrapeState?.let { st ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val p = st.progress
            val sending = st.sending
            val (text, frac) = when {
                sending != null -> "Sending to ${e.name} · ${sending.first} of ${sending.second}" to
                    (if (sending.second > 0) sending.first.toFloat() / sending.second else 1f)
                p == null -> "Scraping ${st.mode.label} · getting the indexes…" to 0f
                else -> "Scraping ${st.mode.label} · ${p.done} of ${p.total} · ${p.current} · ${p.fetched} new" to
                    (if (p.total > 0) p.done.toFloat() / p.total else 0f)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.width(160.dp), drawStopIndicator = {})
            LTextButton(onClick = { app.cancelScrape(e) }) { Text("Cancel") }
        }
    }
    if (e.scrapeState == null) e.scrapeResult?.let { r ->
        var details by remember(r) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Last scrape (${r.mode.label}): ${r.report.summary()} · sent ${r.sent}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MenuDim)
            if (r.report.misses.isNotEmpty()) LTextButton(onClick = { details = true }) { Text("Unresolved (${r.report.misses.size})") }
            LTextButton(onClick = { e.scrapeResult = null }) { Text("Hide") }
        }
        if (details) ScrapeReportDialog(e, r) { details = false }
    }
    e.videoJob?.let { (name, p) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (p >= 1f) "Sending the video of $name…" else "Converting the video of $name · ${(p * 100).toInt()} %",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            LinearProgressIndicator(progress = { p }, modifier = Modifier.width(160.dp), drawStopIndicator = {})
            if (p < 1f) LTextButton(onClick = { e.videoCancel = true }) { Text("Cancel") }
        }
    }
}

/** Un selector que abre un menu: el valor de ahora y las opciones. */
@Composable
private fun Choice(label: String, options: List<Pair<String, () -> Unit>>, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    // Con tope: el nombre largo de una consola empujaba fuera de la ventana lo de la derecha.
    Box(Modifier.widthIn(max = 230.dp)) {
        LTextButton(onClick = { open = true }, enabled = enabled) {
            Text(label, Modifier.weight(1f, fill = false), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(" ▾", maxLines = 1, softWrap = false)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = Look.shape) {
            for ((text, act) in options) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; act() })
        }
    }
}

/** Un menu de "mas": las acciones que no merecen boton propio. */
@Composable
private fun More(options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Text("⋯", color = MenuDim, style = MaterialTheme.typography.titleMedium) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = Look.shape) {
            for ((text, act) in options) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; act() })
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.selectedRow(on).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        RowContent(on) { Text(label, maxLines = 1, color = if (on) MenuInk else MenuDim) }
    }
}
