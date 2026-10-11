package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.felp.frontcomp.AchievementsTab
import com.felp.frontcomp.BatteryHealth
import com.felp.frontcomp.Cards
import com.felp.frontcomp.CatalogLoader
import com.felp.frontcomp.CharacterTab
import com.felp.frontcomp.Detail
import com.felp.frontcomp.GameView
import com.felp.frontcomp.GamesTab
import com.felp.frontcomp.LocalCalmSelection
import com.felp.frontcomp.LocalTheme
import com.felp.frontcomp.LogStats
import com.felp.frontcomp.Logbook
import com.felp.frontcomp.MenuBody
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.MetricTab
import com.felp.frontcomp.MissionsTab
import com.felp.frontcomp.OverviewTab
import com.felp.frontcomp.PanelDivider
import com.felp.frontcomp.Prefs
import com.felp.frontcomp.SessionsTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * El Companion de Ludolog en el PC.
 *
 * Las pestañas son las de Ludolog, compiladas tal cual (CharacterTab, MetaTabs, StatsTabs) y con
 * sus mismas cuentas (LogStats). Lo de aqui es el marco, que en Ludolog es StatsWindow.kt y alli
 * va con mando: pestañas que se cambian con los gatillos y una pila que se deshace con B. Aqui,
 * pestañas que se tocan y un boton de volver. La lista de pestañas, las medidas de cada ranking
 * y sus notas son las de StatsWindow.kt: si cambian alli, cambian aqui.
 *
 * Pinta ConsoleEntry.book (lo llena CompanionReader). El grafico y los rankings abren el cuaderno
 * aparte, con Logbook(ctx, withOthers = true): ese abre el de la consola a la que apunta ahora
 * CompanionReader.point (la elegida, ver AppState.select), no por fuerza la de [e].
 */

private val PAGES = listOf(
    "OVERVIEW", "ACHIEVEMENTS", "MISSIONS", "STATISTICS", "GAMES", "SESSIONS", "BATTERY", "THERMAL", "SPEED", "POWER",
)

private val METRICS = mapOf(
    "BATTERY" to listOf(LogStats.Metric.BATTERY_PCT_H, LogStats.Metric.BATTERY_MAH_H),
    "THERMAL" to listOf(
        LogStats.Metric.TEMP_MEAN, LogStats.Metric.TEMP_MAX, LogStats.Metric.GPU_TEMP_MEAN, LogStats.Metric.GPU_TEMP_MAX,
    ),
    "SPEED" to listOf(
        LogStats.Metric.FPS_MEAN, LogStats.Metric.FPS_MIN, LogStats.Metric.CPU_MHZ, LogStats.Metric.GPU_MHZ,
        LogStats.Metric.CPU_MIN_MHZ,
    ),
    "POWER" to listOf(LogStats.Metric.POWER_MEAN, LogStats.Metric.POWER_MAX),
)

private val NOTES = mapOf(
    "BATTERY" to "Charger sessions excluded.",
    "THERMAL" to "Both chip sensors. Averages weighted by playtime.",
    "SPEED" to "Frames shown on screen. Averages weighted by playtime.",
    "POWER" to "Draw from the battery, or from the charger.",
)

/** Donde se esta dentro del Companion, por encima de la pestaña. Como el `View` de StatsWindow. */
private sealed interface Level {
    data class Console(val id: String) : Level
    data class Game(val title: String, val system: String) : Level
    data class Session(val entry: LogStats.Entry) : Level
}

@Composable
internal fun CompanionView(app: AppState, e: ConsoleEntry) {
    val b = e.book
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when {
                    e.info?.ludolog == null -> "Ludolog isn't installed on this device."
                    e.companionNote == "syncing" -> "Reading the Companion…"
                    else -> e.companionNote ?: "No Companion data yet."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val t = LocalTheme.current
    var page by remember(e.id) { mutableIntStateOf(0) }
    var stack by remember(e.id) { mutableStateOf<List<Level>>(emptyList()) }
    var grain by remember { mutableStateOf(LogStats.Grain.DAY) }
    var metricAt by remember { mutableStateOf(mapOf<String, LogStats.Metric>()) }
    var groupBy by remember { mutableStateOf(LogStats.GroupBy.SYSTEM) }
    val top = stack.lastOrNull()
    val tab = PAGES[page]
    val metrics = METRICS[tab].orEmpty()
    val metric = metricAt[tab] ?: metrics.firstOrNull()

    // Lo que Ludolog lee aparte del Book: el grafico de la portada y el ranking de la medida elegida.
    val buckets by produceState<List<LogStats.Slice>>(emptyList(), b, grain) {
        value = withContext(Dispatchers.IO) {
            runCatching { Logbook(android.content.Context(), withOthers = true).use { LogStats(it).buckets(grain) } }
                .getOrDefault(emptyList())
        }
    }
    val ranked by produceState<List<LogStats.Row>?>(null, b, metric, groupBy) {
        val m = metric
        value = if (m == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching { Logbook(android.content.Context(), withOthers = true).use { LogStats(it).rank(m, groupBy) } }.getOrNull()
        }
    }

    val rank = remember(b) { b.bySystem.map { it.label } }
    val catalog = remember(b) { CatalogLoader.current }
    // Como en Ludolog (LibraryViewModel.displayName): el nombre puesto a mano, el del catalogo o el id.
    fun name(id: String) = (e.ludologConfig["name.sys.$id"] as? String) ?: catalog?.byId?.get(id)?.name ?: id
    // Las caratulas viven en las consolas: ver CompanionCoversPc.
    fun cover(title: String): File? = CompanionCoversPc.find(app, title)
    val prefs = remember(e.ludologConfig) { Prefs(e.ludologConfig) }

    CompositionLocalProvider(LocalCalmSelection provides true) {
        Row(Modifier.fillMaxSize()) {
            // La columna fija: el personaje y las cifras del registro entero.
            Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                // En Ludolog aqui gira el aparato; el personaje ya esta en la portada.
                Text(
                    (e.info?.model ?: e.model).let { if (t.upperTitles) it.uppercase() else it },
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    color = MenuInk, fontFamily = t.display, fontSize = 15.sp, letterSpacing = t.titleTracking,
                )
                Cards(b)
                Spacer(Modifier.height(10.dp))
                Text(
                    e.syncedAt?.let { "Copied ${ago(System.currentTimeMillis() - it)}" }
                        ?: (e.companionNote ?: ""),
                    color = MenuDim, fontSize = 10.sp, fontFamily = MenuBody,
                )
                LTextButton(onClick = { app.refreshCompanion(e) }, enabled = e.companionNote != "syncing") {
                    Text(if (e.companionNote == "syncing") "Syncing…" else "Sync now", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(26.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (top == null) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PAGES.forEachIndexed { i, p ->
                            Box(Modifier.selectedRow(i == page).clickable { page = i }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                RowContent(i == page) {
                                    Text(p, color = if (i == page) MenuInk else MenuDim, fontFamily = t.display,
                                        fontSize = 11.sp, letterSpacing = t.captionTracking)
                                }
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LTextButton(onClick = { stack = stack.dropLast(1) }) { Text("‹ Back") }
                        Text(
                            when (top) {
                                is Level.Console -> name(top.id)
                                is Level.Game -> top.title
                                is Level.Session -> top.entry.title
                            }.let { if (t.upperTitles) it.uppercase() else it },
                            color = MenuDim, fontFamily = t.display, fontSize = 11.sp, letterSpacing = t.captionTracking,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                PanelDivider()
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    when (top) {
                        is Level.Session -> Detail(top.entry, rank, t.accent, ::name, armed = false)
                        is Level.Game -> GameView(top.title, top.system, rank, t.accent, ::cover, ::name) {
                            stack = stack + Level.Session(it)
                        }
                        is Level.Console -> com.felp.frontcomp.ConsoleView(top.id, name(top.id), t.accent, ::cover) {
                            stack = stack + Level.Game(it, top.id)
                        }
                        null -> when (tab) {
                            "OVERVIEW" -> CharacterTab(b, rank, t.accent, ::name, ::cover) { stack = stack + Level.Session(it) }
                            "ACHIEVEMENTS" -> AchievementsTab(b, t.accent)
                            "MISSIONS" -> MissionsTab(b, prefs, t.accent) {}
                            "STATISTICS" -> OverviewTab(
                                b, buckets, grain, rank, t.accent, ::name, ::cover,
                                onGrain = { grain = it },
                                onOpen = { stack = stack + Level.Console(it) },
                                onSession = { stack = stack + Level.Session(it) },
                                onAllSessions = { page = PAGES.indexOf("SESSIONS") },
                            )
                            "GAMES" -> GamesTab(b, rank, t.accent, ::cover, ::name, b::feedsOf) {
                                stack = stack + Level.Game(it.title, it.system ?: "?")
                            }
                            "SESSIONS" -> SessionsTab(b.recent, rank, t.accent, ::cover, ::name, b::feedsOf) {
                                stack = stack + Level.Session(it)
                            }
                            else -> MetricTab(
                                rows = ranked, metrics = metrics, metric = metric ?: metrics.first(), groupBy = groupBy,
                                rank = rank, accent = t.accent, name = ::name, note = NOTES[tab].orEmpty(),
                                onlyHere = groupBy != LogStats.GroupBy.DEVICE && b.devices.size > 1,
                                onMetric = { metricAt = metricAt + (tab to metrics[it]) },
                                onGroupBy = { groupBy = it },
                                onOpen = { row ->
                                    when (groupBy) {
                                        LogStats.GroupBy.GAME -> stack = stack + Level.Game(row.label, row.system ?: "?")
                                        LogStats.GroupBy.SYSTEM -> stack = stack + Level.Console(row.label)
                                        LogStats.GroupBy.DEVICE -> Unit
                                    }
                                },
                                footer = if (tab == "BATTERY") ({ BatteryHealth(b) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Hace cuanto, en la unidad que se lee de un vistazo. Como `ago` de StatsWindow. */
internal fun ago(ms: Long): String {
    val m = ms / 60_000L
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "yesterday"
        else -> "${m / (24 * 60)} days ago"
    }
}

