package com.felp.ludologlink.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuFaint
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.SystemDef
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
import java.io.File

// CatalogView.kt: el nombre viene de la antigua pestaña Catalog, que ahora es parte de Games
// (GamesView.kt). Aqui queda lo que la tabla de Games usa por debajo: el modelo de filas
// (CatalogCell, CatalogRow y catalogRows, que junta cada juego de los devices y del catalogo del PC
// en una fila), el icono de cada celda (Part, PartIcon) con sus menus (iconOptions, fileOption,
// fetchOptions: de donde traer lo que falta) y el dialogo de arte huerfano (OrphanMediaDialog).
// Lo que hacen esos menus esta en AppState; el catalogo del PC en si, en PcCatalog.kt.

/** Lo que tiene un device (o el catalogo del PC) de un juego: el ROM, la caratula y el video. */
class CatalogCell(
    val rom: RomFile?, val art: Boolean, val video: Boolean,
    /** En el catalogo, los archivos de caratula y video. */
    val artFile: File? = null, val videoFile: File? = null,
)

/** Un juego del catalogo, con lo que tiene cada columna: el PC ([PC]) y cada device por su id. */
class CatalogRow(val key: String, val title: String, val console: String, val cells: Map<String, CatalogCell>) {
    companion object { const val PC = "pc" }

    /**
     * Una entrada de Steam o DoomForge: no tiene archivo que copiar, solo su arte, su video y su
     * nombre en Ludolog. Una fila suya, del device que se mira (ver catalogRows).
     */
    val locked: Boolean get() = cells.values.any { c -> c.rom?.let(Shortcuts::isLocked) == true }
}

/**
 * Las filas: cada juego que hay en algun device o en el catalogo, una vez, emparejado por consola
 * y titulo (ver Library.gameKey). Las entradas de Steam y DoomForge no: no se copian.
 */
internal fun catalogRows(devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, withLocked: List<ConsoleEntry> = emptyList()): List<CatalogRow> {
    PcCatalog.catalogs = devices.mapNotNull { it.catalog }
    class Acc(var title: String? = null, var console: String? = null, val cells: HashMap<String, CatalogCell> = HashMap(),
              val keys: HashSet<String> = HashSet())
    val acc = LinkedHashMap<String, Acc>()
    for (d in devices) for (f in d.games.filterNot(Shortcuts::isLocked)) {
        val a = acc.getOrPut(Library.gameKey(d, f)) { Acc() }
        if (d.id in a.cells) continue
        a.cells[d.id] = CatalogCell(f, Names.hasArt(d, f), Names.hasVideo(d, f))
        if (a.title == null) a.title = Names.display(d, f)
        if (a.console == null) a.console = f.system
        a.keys += Library.artKeys(d, f)
    }
    if (scan != null) {
        for (f in scan.roms) {
            val g = PcCatalog.game(scan, f)
            val stem = Protocol.stemOf(f.name.substringAfterLast('/'))
            val key = (g?.systemId ?: f.system).lowercase() + "/" + SystemDef.key(g?.title?.takeIf { it.isNotBlank() } ?: stem)
            val a = acc.getOrPut(key) { Acc() }
            if (CatalogRow.PC in a.cells) continue
            val keys = PcCatalog.artKeys(scan, f)
            val art = keys.firstNotNullOfOrNull { scan.covers[it] }
            val vid = keys.firstNotNullOfOrNull { scan.videos[it] }
            a.cells[CatalogRow.PC] = CatalogCell(f, art != null, vid != null, art, vid)
            if (a.title == null) a.title = g?.title?.takeIf { it.isNotBlank() } ?: stem
            if (a.console == null) a.console = f.system
        }
        // Caratula y video traidos al PC sin el ROM: tambien cuentan.
        for (a in acc.values) if (CatalogRow.PC !in a.cells) {
            val art = a.keys.firstNotNullOfOrNull { scan.covers[it] }
            val vid = a.keys.firstNotNullOfOrNull { scan.videos[it] }
            a.cells[CatalogRow.PC] = CatalogCell(null, art != null, vid != null, art, vid)
        }
    }
    // Las de Steam y DoomForge (Games): la misma entrada en varios devices, una fila (por carpeta y
    // nombre del archivo). Sin archivo que copiar, pero su arte va y viene: tambien al catalogo del PC.
    val locked = LinkedHashMap<String, Acc>()
    for (d in withLocked) for (f in d.games.filter(Shortcuts::isLocked)) {
        val a = locked.getOrPut("locked|${f.system.lowercase()}|${SystemDef.key(Protocol.stemOf(f.name.substringAfterLast('/')))}") {
            Acc(Names.display(d, f), f.system)
        }
        if (d.id in a.cells) continue
        a.cells[d.id] = CatalogCell(f, Names.hasArt(d, f), Names.hasVideo(d, f))
        a.keys += Library.artKeys(d, f)
    }
    if (scan != null) for (a in locked.values) {
        val art = a.keys.firstNotNullOfOrNull { scan.covers[it] }
        val vid = a.keys.firstNotNullOfOrNull { scan.videos[it] }
        a.cells[CatalogRow.PC] = CatalogCell(null, art != null, vid != null, art, vid)
    }
    acc.putAll(locked)
    return acc.map { (k, a) -> CatalogRow(k, a.title ?: k, a.console ?: "", a.cells) }
        .sortedWith(compareBy({ it.console.lowercase() }, { it.title.lowercase() }))
}

internal enum class Part { ROM, ART, VIDEO }

/**
 * Uno de los tres iconos de una celda. Encendido (color del tema) si esta; apagado si falta, y
 * entonces al pulsarlo, de donde traerlo. La caratula y el video de un device piden su ROM antes.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PartIcon(app: AppState, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, r: CatalogRow, id: String, p: Part,
                     window: java.awt.Window, onDelete: (String, RomFile) -> Unit = { _, _ -> }, changed: () -> Unit) {
    val c = r.cells[id]
    val has = when (p) { Part.ROM -> c?.rom != null; Part.ART -> c?.art == true; Part.VIDEO -> c?.video == true }
    val icon: ImageVector = when (p) { Part.ROM -> KitIcons.Rom; Part.ART -> KitIcons.Cover; Part.VIDEO -> KitIcons.Video }
    val label = when (p) { Part.ROM -> "ROM"; Part.ART -> "cover"; Part.VIDEO -> "video" }
    fun dev(i: String) = devices.firstOrNull { it.id == i }
    val target = if (id == CatalogRow.PC) null else dev(id)

    // Lo que se puede hacer con el: si falta, de donde traerlo o buscarlo en internet; si esta,
    // buscar otro o quitarlo, y el ROM borrarlo. Guardado por fila: se armaba para tres iconos por
    // columna y por fila en cada redibujo.
    val scope = rememberCoroutineScope()
    val options = remember(r, id, p, scan, devices, has) {
        iconOptions(app, devices, scan, r, id, p, has, changed, onDelete) + fileOption(app, devices, scan, r, id, p, has, changed) { pick ->
            scope.launch { (if (p == Part.VIDEO) chooseVideo(window) else chooseImage(window))?.let(pick) }
        }
    }
    var open by remember { mutableStateOf(false) }
    // Dos colores: gris lo que falta, el del tema lo que esta. Con el raton encima de uno que se puede
    // pulsar, un circulo bien visible y la mano.
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val canAct = options.isNotEmpty()
    // Arte de un device que aun no tiene el juego: se dice, en la linea al pasar y arriba del menu.
    val noGame = if (!has && p != Part.ROM && target != null && c?.rom == null) "The game isn't on ${target.name} yet" else null
    val mark = @Composable {
        Icon(icon, if (has) "$label: here" else "$label: missing",
            Modifier.padding(2.dp)
                .then(if (canAct && hovered) Modifier.background(Look.accent.copy(alpha = .30f), androidx.compose.foundation.shape.CircleShape) else Modifier)
                .padding(3.dp).size(22.dp)
                .then(if (canAct) Modifier.hoverable(hover)
                    .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Hand)
                    .clickable { open = true } else Modifier.hoverable(hover)),
            tint = when {
                has -> Look.accent
                canAct && hovered -> MenuInk
                else -> MenuFaint
            })
    }
    // Que se ve al pasar por encima: la caratula o el video si esta, o de donde traerlo si falta.
    val preview: (@Composable () -> Unit)? = when {
        has && p != Part.ROM && id == CatalogRow.PC ->
            (if (p == Part.VIDEO) c?.videoFile else c?.artFile)?.let { f -> { FilePreview(f, p == Part.VIDEO) } }
        has && p != Part.ROM -> target?.let { t -> c?.rom?.let { f -> { MediaPreview(t, f, p == Part.VIDEO) } } }
        !has && (canAct || noGame != null) && !open -> {
            {
                androidx.compose.material3.Surface(shape = Look.shape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 4.dp) {
                    Text(noGame ?: if (options.size == 1) options[0].first else "${options[0].first} · ${options.size - 1} more",
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        else -> null
    }
    Box {
        if (preview == null) mark()
        else androidx.compose.foundation.TooltipArea(
            tooltip = preview,
            delayMillis = if (has) 300 else 150,
            tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(
                offset = androidx.compose.ui.unit.DpOffset(16.dp, 16.dp)),
        ) { mark() }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = Look.shape) {
            Text("${r.title}: $label" + (noGame?.let { " · $it" } ?: ""), Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge, color = MenuDim)
            for ((text, act) in options) DropdownMenuItem(text = { Text(text) }, onClick = { open = false; act() })
        }
    }
}

/**
 * El menu de un icono de la tabla. Lo que falta: de donde traerlo y buscarlo en internet. Lo que
 * esta: buscar otro (reemplaza al de ahora, como «Fetch box art» en Ludolog) o quitarlo; y el ROM,
 * borrarlo de ese sitio ([onDelete] pregunta antes).
 */
internal fun iconOptions(app: AppState, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, r: CatalogRow, id: String, p: Part,
                         has: Boolean, changed: () -> Unit, onDelete: (String, RomFile) -> Unit = { _, _ -> }): List<Pair<String, () -> Unit>> = buildList {
    val c = r.cells[id]
    val d = devices.firstOrNull { it.id == id }
    val f = c?.rom
    val video = p == Part.VIDEO
    // Buscarlo en internet, como en Ludolog: en un device con Ludolog que tenga el juego, o para el
    // catalogo del PC con lo de un device conectado. Ver ArtPicker.
    val canFetch = p != Part.ROM && when {
        d != null -> f != null && d.info?.ludolog != null && d.scrapeState == null
        else -> id == CatalogRow.PC && scan != null && devices.any { it.info?.ludolog != null && it.scrapeState == null }
    }
    fun fetch() = app.picker.fetch(r, id, devices, scan, video, changed)
    if (!has) {
        addAll(fetchOptions(app, devices, scan, r, id, p, changed))
        // Arte para un device que aun no tiene el juego: primero el juego (luego, su arte).
        if (p != Part.ROM && d != null && f == null && !r.locked)
            addAll(fetchOptions(app, devices, scan, r, id, Part.ROM, changed).map { (t, a) -> "First the game: $t" to a })
        if (canFetch) add((if (video) "Fetch video…" else "Fetch box art…") to ::fetch)
    } else if (p != Part.ROM) {
        if (canFetch) add((if (video) "Fetch another video…" else "Fetch other box art…") to ::fetch)
        if (d != null && f != null && d.info?.ludolog != null) add("Remove" to { app.removeMedia(d, f, video) })
        if (id == CatalogRow.PC) (if (video) c?.videoFile else c?.artFile)?.let { file ->
            add("Show in folder" to { app.showInFolder(file) })
            add("Remove from PC catalog" to { app.removeFromCatalog(file); changed() })
        }
    } else if (f != null && !r.locked) {
        if (id == CatalogRow.PC && scan != null) add("Show in folder" to { app.showInFolder(PcCatalog.file(scan, f)) })
        // El ROM: borrarlo de aqui (ver GamesView, que pregunta antes). Steam y DoomForge no se borran.
        add((if (d != null) "Delete from ${d.name}…" else "Delete from PC catalog…") to { onDelete(id, f) })
    }
}

/**
 * Siempre a mano, para que todo icono gris tenga algo que hacer: una caratula o un video de un
 * archivo del PC, al device (si tiene el juego y Ludolog) o al catalogo del PC.
 */
internal fun fileOption(app: AppState, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, r: CatalogRow, id: String, p: Part,
                        has: Boolean, changed: () -> Unit, choose: ((java.io.File) -> Unit) -> Unit): List<Pair<String, () -> Unit>> {
    if (has || p == Part.ROM) return emptyList()
    val video = p == Part.VIDEO
    val c = r.cells[id]
    if (id == CatalogRow.PC) {
        if (scan == null) return emptyList()
        return listOf("Add from file…" to { choose { file -> app.fileToCatalog(r, devices, scan, file, video); changed() } })
    }
    val d = devices.firstOrNull { it.id == id } ?: return emptyList()
    val f = c?.rom ?: return emptyList()
    if (d.info?.ludolog == null) return emptyList()
    return listOf("Add from file…" to { choose { file -> if (video) app.importVideo(d, f, file) else app.importArt(d, f, file) } })
}

/**
 * De donde traer a la columna [id] lo que le falta de [r] ([p]): una accion por sitio que lo tiene.
 * La usan los iconos de la tabla y el panel del juego (Games).
 */
internal fun fetchOptions(app: AppState, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, r: CatalogRow, id: String, p: Part,
                          changed: () -> Unit): List<Pair<String, () -> Unit>> = buildList {
    val c = r.cells[id]
    fun dev(i: String) = devices.firstOrNull { it.id == i }
    val target = if (id == CatalogRow.PC) null else dev(id)
    for ((srcId, sc) in r.cells) {
        if (srcId == id) continue
        // Steam y DoomForge: su archivo no se copia; su arte y su video si.
        if (p == Part.ROM && sc.rom != null && Shortcuts.isLocked(sc.rom)) continue
        val srcHas = when (p) { Part.ROM -> sc.rom != null; Part.ART -> sc.art; Part.VIDEO -> sc.video }
        if (!srcHas) continue
        val srcDev = if (srcId == CatalogRow.PC) null else dev(srcId) ?: continue
        when {
            // Al catalogo del PC, desde un device.
            id == CatalogRow.PC && srcDev != null -> {
                val f = sc.rom ?: continue
                if (p == Part.ROM) add("Download from ${srcDev.name}" to { app.romToCatalog(srcDev, f) })
                else {
                    val stem = Protocol.stemOf((c?.rom ?: f).name.substringAfterLast('/'))
                    add("Download from ${srcDev.name}" to { app.mediaToCatalog(srcDev, f, p == Part.VIDEO, stem, changed) })
                }
            }
            // A un device, desde el catalogo.
            target != null && srcDev == null && scan != null -> {
                if (p == Part.ROM) add("Copy from PC catalog" to {
                    app.romFromCatalog(scan, sc.rom!!, target, sc.artFile, sc.videoFile)
                }) else {
                    val fd = c?.rom ?: continue
                    val file = (if (p == Part.VIDEO) sc.videoFile else sc.artFile) ?: continue
                    add("Copy from PC catalog" to { if (p == Part.VIDEO) app.importVideo(target, fd, file) else app.importArt(target, fd, file) })
                }
            }
            // De device a device.
            target != null && srcDev != null -> {
                val fs = sc.rom ?: continue
                if (p == Part.ROM) add("Copy from ${srcDev.name}" to { app.copyTo(srcDev, target, listOf(fs)) })
                else {
                    val fd = c?.rom ?: continue
                    add("Copy from ${srcDev.name}" to { app.copyArt(srcDev, fs, target, fd, p == Part.VIDEO) })
                }
            }
        }
    }
}

/**
 * El arte y los videos de la consola que ya no son de ningun ROM (ver Library.orphans): con lo
 * que ocupan, para borrar solo lo que se elija.
 */
@Composable
fun OrphanMediaDialog(app: AppState, e: ConsoleEntry, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<MediaFile>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(e.id) {
        list = try { app.findOrphans(e) } catch (x: LinkError) { failed = x.message; emptyList() }
    }
    val items = list.orEmpty().sortedWith(compareBy({ it.sys.lowercase() }, { it.name.lowercase() }))
    val picked = items.filter { it.path in chosen }
    fun where(m: MediaFile) = when {
        m.owner == "ludolog" -> "Ludolog"
        "/ES-DE/" in m.path -> "ES-DE"
        else -> "other app"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Orphan art & video on ${e.name}") },
        text = {
            Column(Modifier.width(780.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Art and videos whose game isn't on the device.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
                when {
                    list == null -> Text("Looking…", color = MenuDim)
                    failed != null -> Text("Couldn't read the media folders: $failed", color = Look.danger)
                    items.isEmpty() -> Text("No orphan art or video.", color = MenuDim)
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = picked.size == items.size, onCheckedChange = { on ->
                                chosen.clear(); if (on) chosen += items.map { it.path }
                            })
                            Text("${items.size} ${if (items.size == 1) "file" else "files"} · ${Format.size(items.sumOf { it.size })}", color = MenuDim)
                        }
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            items(items, key = { it.path }) { m ->
                                Row(Modifier.fillMaxWidth().selectedRow(m.path in chosen)
                                    .clickable { if (!chosen.remove(m.path)) chosen += m.path }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RowContent(m.path in chosen) {
                                        Checkbox(checked = m.path in chosen, onCheckedChange = { if (!chosen.remove(m.path)) chosen += m.path })
                                        Column(Modifier.weight(1f)) {
                                            Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${m.sys}${if (m.kind.isNotEmpty()) "/${m.kind}" else ""} · ${where(m)} folder",
                                                style = MaterialTheme.typography.bodySmall, color = MenuDim)
                                        }
                                        Text(Format.size(m.size), Modifier.width(84.dp), textAlign = TextAlign.End,
                                            style = MaterialTheme.typography.bodySmall, color = MenuDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            LTextButton(onClick = {
                busy = true
                scope.launch {
                    if (app.deleteMedia(e, picked) > 0) list = try { app.findOrphans(e) } catch (x: LinkError) { list }
                    chosen.clear()
                    busy = false
                }
            }, enabled = picked.isNotEmpty() && !busy, colors = ButtonDefaults.textButtonColors(contentColor = Look.danger)) {
                Text(if (busy) "Deleting…" else "Delete ${picked.size} · ${Format.size(picked.sumOf { it.size })}")
            }
        },
        dismissButton = { LTextButton(onClick = onClose, enabled = !busy) { Text("Close") } },
    )
}
