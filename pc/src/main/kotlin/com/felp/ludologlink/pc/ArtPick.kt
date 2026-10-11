package com.felp.ludologlink.pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.ArtScraper
import com.felp.frontcomp.CoverChoice
import com.felp.frontcomp.CoverHunt
import com.felp.frontcomp.Game
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.ThumbEntry
import com.felp.frontcomp.VideoHunt
import com.felp.ludolog.kit.Protocol
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La caratula o el video de UN juego, buscados y elegidos como en Ludolog («Fetch box art» y «Fetch
 * video» del menu de un juego), con el mismo codigo (ArtScraper.hunt / videoHunt, keep / keepVideo)
 * corriendo en el PC: si sale uno solo y es del mismo nombre se pone sin preguntar; si no, se enseña
 * lo encontrado y se elige. Lo elegido reemplaza lo que tuviera, en el device o en el catalogo del PC.
 *
 * El scraper necesita lo de un device con Ludolog (sus fichas, su catalogo, sus reglas de video): el
 * del destino o, para el catalogo del PC, uno que tenga el juego.
 *
 * Hay uno solo, AppState.picker. Lo llaman los menus de Games (GamePanel e iconOptions en
 * CatalogView.kt); su ventana es ArtPickDialog, mas abajo, que GamesView dibuja siempre.
 */
internal class ArtPicker(private val app: AppState, private val scope: CoroutineScope) {

    /** Lo encontrado, mientras se elige. [target]: la columna (un device o el catalogo del PC). */
    class Open(
        val title: String, val video: Boolean, val target: String, val targetName: String,
        val ctx: ConsoleEntry, val game: Game, val rom: RomFile?, val stem: String, val scraper: ArtScraper,
        val covers: CoverHunt?, val videos: VideoHunt?, val changed: () -> Unit,
    )

    /** La ventana de elegir, si hay algo que elegir. */
    var open by mutableStateOf<Open?>(null)
        private set

    /** Lo que se esta buscando o guardando ahora («Looking for box art for Tetris»), para la barra. */
    var looking by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun cancel() { job?.cancel() }

    /**
     * Busca la caratula (o el video) de [r] para la columna [target]. Uno a la vez: el scraper del PC
     * apunta a las fichas de un device mientras trabaja (ver PcScraper.point).
     */
    fun fetch(r: CatalogRow, target: String, devices: List<ConsoleEntry>, scan: PcCatalog.Scan?, video: Boolean, changed: () -> Unit = {}) {
        if (looking != null || open != null) return app.notify("Another search is running. Try again when it ends.", error = true)
        val what = if (video) "video" else "box art"
        val dev = devices.firstOrNull { it.id == target }
        val ready = devices.filter { it.info?.ludolog != null && it.scrapeState == null }
        val ctx = dev?.takeIf { it in ready } ?: ready.firstOrNull { r.cells[it.id]?.rom != null } ?: ready.firstOrNull()
            ?: return app.notify(if (dev != null) "${dev.name} is busy scraping." else "Connect a device with Ludolog to search.", error = true)
        val pcRom = r.cells[CatalogRow.PC]?.rom
        val rom = if (dev != null) r.cells[dev.id]?.rom else pcRom
        val game = when {
            dev != null -> rom?.let { Names.game(dev, it) }
            else -> (pcRom?.let { p -> scan?.let { PcCatalog.game(it, p) } })
                ?: devices.firstNotNullOfOrNull { d -> r.cells[d.id]?.rom?.let { Names.game(d, it) } }
        } ?: return app.notify("Couldn't tell which game this is.", error = true, kind = "art")
        // El nombre con que se guarda: el del ROM alli; en el catalogo sin ROM, el de un device.
        val named = rom ?: devices.firstNotNullOfOrNull { r.cells[it.id]?.rom } ?: return
        val stem = Protocol.stemOf(named.name.substringAfterLast('/'))
        val targetName = dev?.name ?: "PC catalog"
        looking = "Looking for $what for ${r.title}"
        job = scope.launch {
            try {
                val (scraper, hunt) = withContext(Dispatchers.IO) {
                    PcScraper.point(ctx)
                    val (s, _) = PcScraper.scraper(ctx, if (video) ScrapeMode.VIDEOS else ScrapeMode.COVERS)
                    s to (if (video) s.videoHunt(game) else s.hunt(game))
                }
                val o = Open(r.title, video, target, targetName, ctx, game, rom, stem, scraper,
                    hunt as? CoverHunt, hunt as? VideoHunt, changed)
                val covers = o.covers?.choices.orEmpty()
                val videos = o.videos?.choices.orEmpty()
                when {
                    // Uno solo y del mismo nombre: como en Ludolog, sin preguntar.
                    covers.size == 1 && covers[0].exact -> keepCover(o, covers[0])
                    videos.size == 1 && videos[0].second -> keepVideo(o, videos[0].first)
                    covers.isNotEmpty() || videos.isNotEmpty() -> open = o
                    else -> app.notify("No $what found for ${r.title}: " +
                        ((o.covers?.miss ?: o.videos?.miss)?.detail ?: "nothing turned up"), error = true, kind = "art")
                }
            } catch (x: kotlinx.coroutines.CancellationException) {
                app.notify("Search cancelled.")
            } catch (x: Exception) {
                app.notify("The search stopped: ${x.message ?: x.javaClass.simpleName}", error = true, kind = "art")
            } finally {
                if (open == null) looking = null
            }
        }
    }

    /** La caratula elegida en la ventana. */
    fun pickCover(c: CoverChoice) {
        val o = open ?: return
        open = null
        job = scope.launch { try { keepCover(o, c) } finally { looking = null } }
    }

    /** El video elegido en la ventana. */
    fun pickVideo(e: ThumbEntry) {
        val o = open ?: return
        open = null
        job = scope.launch { try { keepVideo(o, e) } finally { looking = null } }
    }

    /** Cerrar sin elegir: lo que tenia se queda. */
    fun close() { open = null; looking = null }

    private suspend fun keepCover(o: Open, c: CoverChoice) {
        looking = "Saving the box art of ${o.title}"
        val r = withContext(Dispatchers.IO) { o.covers?.let { o.scraper.keep(it, c) } }
        val file = o.scraper.destinationFor(o.game)
        if (r?.misses?.isNotEmpty() == true || !file.isFile) {
            app.notify("Couldn't save the box art: ${r?.misses?.firstOrNull()?.detail ?: "it didn't arrive"}", error = true, kind = "art")
            return
        }
        deliver(o, file)
    }

    private suspend fun keepVideo(o: Open, e: ThumbEntry) {
        looking = "Getting the video of ${o.title}"
        val r = withContext(Dispatchers.IO) { o.videos?.let { o.scraper.keepVideo(it, e) } }
        val file = o.scraper.videoFor(o.game)
        if (r == null || r.videos == 0 || !file.isFile || file.length() == 0L) {
            app.notify("Couldn't get the video: ${r?.misses?.firstOrNull()?.detail ?: "it didn't arrive"}", error = true, kind = "art")
            return
        }
        deliver(o, file)
    }

    /** Lo guardado en el PC, a su sitio: la carpeta de Ludolog en el device, o `media/` del catalogo. */
    private suspend fun deliver(o: Open, file: File) {
        val kind = if (o.video) "videos" else "covers"
        val what = if (o.video) "video" else "box art"
        val dev = app.consoles.firstOrNull { it.id == o.target }
        try {
            withContext(Dispatchers.IO) {
                if (dev != null) {
                    val rom = o.rom ?: throw LinkError(LinkError.LOCAL, "the game isn't on ${dev.name}")
                    // Reemplaza: lo que tuviera en la carpeta de Ludolog con otra extension se quedaria al lado.
                    dev.link().removeMedia(listOfNotNull(o.game.systemId, rom.system).distinct(), o.stem, o.video)
                    dev.link().putMedia("media/${o.game.systemId}/$kind/${file.name}", file.readBytes())
                } else {
                    val root = PcCatalog.dir ?: throw LinkError(LinkError.LOCAL, "the catalog folder isn't connected")
                    val stem = app.windowsName(o.stem)
                    val folder = app.inside(root, File(root, "${PcCatalog.MEDIA}/${app.windowsName(o.game.systemId.lowercase())}/$kind"))
                    folder.mkdirs()
                    folder.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.equals(stem, true) }.forEach { it.delete() }
                    file.copyTo(File(folder, "$stem.${file.extension.lowercase()}"), overwrite = true)
                    PcCatalog.forget()
                }
            }
            app.notify("New $what for ${o.title} on ${o.targetName}.", about = dev, kind = "art")
            if (dev != null) app.loadArt(dev) else o.changed()
        } catch (x: LinkError) {
            app.notify("Couldn't put the $what on ${o.targetName}: ${x.message}", error = true, about = dev, kind = "art")
        } catch (x: java.io.IOException) {
            app.notify("Couldn't save the $what in the PC catalog: ${x.message}", error = true, kind = "art")
        }
    }
}

/**
 * La ventana de elegir: las caratulas en rejilla (se elige mirando, como en Ludolog) o los videos en
 * lista (para enseñarlos habria que bajarlos todos). Abajo de cada una, como se llama y de donde sale.
 */
@Composable
internal fun ArtPickDialog(p: ArtPicker) {
    val o = p.open ?: return
    AlertDialog(
        onDismissRequest = { p.close() },
        // Mas ancha que la de serie (560): con ella cabian tres caratulas por fila.
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(if (o.video) 720.dp else 920.dp),
        title = { Text("${if (o.video) "Video" else "Box art"} for ${o.title} · ${o.targetName}") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val n = o.covers?.choices?.size ?: o.videos?.choices?.size ?: 0
                Text("$n found. The one you choose replaces what's there.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
                o.covers?.let { h ->
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(h.choices) { c -> CoverCell(c) { p.pickCover(c) } }
                    }
                }
                o.videos?.let { h ->
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                        items(h.choices) { (entry, exact) ->
                            Row(Modifier.fillMaxWidth().clickable { p.pickVideo(entry) }.padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(entry.fileName.substringBeforeLast('.'), Modifier.weight(1f), color = MenuInk,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (exact) "same name" else "similar name", style = MaterialTheme.typography.bodySmall,
                                    color = if (exact) Look.accent else MenuDim)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { LTextButton(onClick = { p.close() }) { Text("Cancel") } },
    )
}

@Composable
private fun CoverCell(c: CoverChoice, onPick: () -> Unit) {
    val bmp = remember(c) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(c.preview).toComposeImageBitmap() }.getOrNull() }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onPick)
            .border(2.dp, if (c.exact) Look.accent.copy(alpha = .55f) else androidx.compose.ui.graphics.Color.Transparent, Look.shape)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, c.label, Modifier.fillMaxWidth().aspectRatio(0.72f), contentScale = ContentScale.Fit)
            else Text("?", color = MenuDim)
        }
        Text(c.label, Modifier.fillMaxWidth().padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MenuInk,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(c.source + if (c.exact) " · same name" else "", style = MaterialTheme.typography.labelSmall,
            color = if (c.exact) Look.accent else MenuDim, textAlign = TextAlign.Center)
        androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
    }
}
