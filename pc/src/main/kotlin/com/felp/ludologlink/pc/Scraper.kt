package com.felp.ludologlink.pc

import com.felp.frontcomp.ArtIndex
import com.felp.frontcomp.ArtScraper
import com.felp.frontcomp.Dossiers
import com.felp.frontcomp.Game
import com.felp.frontcomp.GameTdbSource
import com.felp.frontcomp.IgdbSource
import com.felp.frontcomp.LibretroSource
import com.felp.frontcomp.LibretroThumbnails
import com.felp.frontcomp.Prefs
import com.felp.frontcomp.ScrapeProgress
import com.felp.frontcomp.ScrapeReport
import com.felp.frontcomp.SteamSource
import com.felp.frontcomp.VideoSnaps
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import java.io.File
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.ui.LTextButton

/**
 * El scraper de Ludolog, corriendo en el PC: el mismo codigo (Scrape.kt y sus fuentes, compilados
 * tal cual) y las mismas fuentes y en el mismo orden que en la consola —libretro, GameTDB, Steam,
 * Switch, IGDB y los videos de archive.org—. IGDB con las claves del PC (ver PcKeys), que se
 * escriben una vez en cualquier aparato y Link las pasa a todos (10-10-2026). Como en la consola,
 * tambien juego a juego eligiendo entre lo encontrado (ver ArtScraper.hunt y ArtPick.kt).
 *
 * Lo encontrado se queda en el PC, en `<respaldos>/<consola>/scrape/media/<consola>/<tipo>/`, con
 * la misma forma que la carpeta de medios de Ludolog; asi una segunda pasada ya no baja lo que ya
 * tiene. Al acabar se manda a la consola lo que le falta (o todo, si se pidio de nuevo). Los
 * videos se convierten aqui, con las reglas de esa consola (ver VideoPrep).
 *
 * Las pasadas las lanzan AppState.scrape (a un device) y AppState.scrapeCatalog (al catalogo del
 * PC); las busquedas de un solo juego, ArtPicker. Los imitadores de lo que el scraper pide de
 * Android estan en LudologScrapeShims.kt y en pc/src/main/java/android.
 */
enum class ScrapeMode(val label: String) {
    MISSING("missing art & video"),
    COVERS("missing covers"),
    VIDEOS("missing videos"),
    ALL("everything again"),
}

/** Una pasada en marcha: lo que lleva y como pararla. */
class ScrapeState(val mode: ScrapeMode, val games: Int) {
    var progress by mutableStateOf<ScrapeProgress?>(null)
    /** Mandando a la consola: cuantos de cuantos. */
    var sending by mutableStateOf<Pair<Int, Int>?>(null)
    var job: Job? = null
}

/** Lo que quedo de la ultima pasada en una consola, para verlo con calma. */
class ScrapeResult(val mode: ScrapeMode, val report: ScrapeReport, val sent: Int)

internal object PcScraper {

    /** Lo que se bajo para una consola, en su forma de carpeta de medios de Ludolog. */
    fun stash(consoleId: String) = File(Config.consoleDir(consoleId), "scrape/media")

    /** Indices de libretro y listados de archive.org: los mismos para todas las consolas. */
    private val cache = File(PcDirs.cache, "scrape-cache")

    /**
     * Lo que el scraper lee de la consola (sus fichas, su catalogo corregido) sale de su copia en
     * el PC. Es de una consola a la vez: se apunta a esta y se olvida lo de la anterior.
     * Al acabar no se vuelve atras: DataHome.dir sigue en la copia de este device (y Dossiers lee
     * sus fichas, tambien para Names.display) hasta que AppState.select o readCompanion la cambian.
     */
    @Synchronized
    fun point(e: ConsoleEntry) {
        com.felp.frontcomp.DataHome.dir = Mirror.dir(e.id)
        Dossiers.forget()
    }

    fun scraper(e: ConsoleEntry, mode: ScrapeMode): Pair<ArtScraper, Prefs> {
        val prefs = Prefs(e.ludologConfig, videos = if (mode == ScrapeMode.COVERS) false else null)
        val scraper = ArtScraper(
            catalog = CompanionReader.catalog(),
            prefs = prefs,
            sources = listOf(LibretroSource(LibretroThumbnails(cacheDir = cache)), GameTdbSource(), SteamSource(), IgdbSource()),
            mediaRoot = stash(e.id),
            snaps = VideoSnaps(cacheDir = cache, clipSeconds = prefs.clipSeconds, keepAudio = prefs.anyThemeHearsVideo,
                maxHeight = prefs.videoHeight),
        )
        return scraper to prefs
    }

    /**
     * Lo que ya tiene la consola, para no volver a buscarlo. En [ScrapeMode.VIDEOS] las caratulas
     * cuentan todas como puestas; en [ScrapeMode.ALL], nada.
     */
    fun existing(e: ConsoleEntry, mode: ScrapeMode, files: Map<String, RomFile>): ArtIndex? = when (mode) {
        ScrapeMode.ALL -> null
        ScrapeMode.VIDEOS -> ArtIndex({ true }, { g -> files[g.path]?.let { Names.hasVideo(e, it) } == true })
        else -> ArtIndex(
            { g -> files[g.path]?.let { Names.hasArt(e, it) } == true },
            { g -> files[g.path]?.let { Names.hasVideo(e, it) } == true },
        )
    }

    /** Los juegos de esos ROMs, como los ve Ludolog en esa consola (sin accesos directos ni .sbi). */
    fun games(e: ConsoleEntry, roms: List<RomFile>): List<Pair<RomFile, Game>> =
        roms.filterNot(Shortcuts::isShortcut).mapNotNull { f -> Names.game(e, f)?.let { f to it } }
}

/** Lo que no se encontro en la ultima pasada, y por que: como el informe del scraper de Ludolog. */
@Composable
fun ScrapeReportDialog(e: ConsoleEntry, r: ScrapeResult, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Unresolved on ${e.name}") },
        text = {
            LazyColumn(Modifier.width(640.dp).heightIn(max = 460.dp)) {
                items(r.report.misses) { m ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row {
                            Text(m.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(m.systemId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(m.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { LTextButton(onClick = onClose) { Text("Close") } },
    )
}
