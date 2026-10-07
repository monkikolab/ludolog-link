package com.felp.ludologlink

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * La actividad de los devices emparejados, copiada aqui para la pestaña Log: se les pide lo nuevo
 * con GET /log (lo mismo que lee el PC) y se guarda en `<archivos de la app>/peerlog/<id>.jsonl`,
 * para verlo tambien cuando no estan a la vista. Quedan las ultimas [KEEP] de cada uno.
 */
object PeerLogs {
    private const val KEEP = 2000

    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "peerlog/${id.filter { it.isLetterOrDigit() }}.jsonl")

    @Synchronized
    fun read(ctx: Context, id: String): List<LinkLog.Entry> {
        val f = file(ctx, id)
        return if (!f.isFile) emptyList() else f.readLines().mapNotNull { l -> runCatching { LinkLog.Entry.of(JSONObject(l)) }.getOrNull() }
    }

    @Synchronized
    private fun merge(ctx: Context, id: String, got: List<LinkLog.Entry>) {
        val old = read(ctx, id)
        val last = old.maxOfOrNull { it.t } ?: 0L
        val new = got.filter { it.t > last }.sortedBy { it.t }
        if (new.isEmpty()) return
        val f = file(ctx, id).apply { parentFile?.mkdirs() }
        if (old.size + new.size > KEEP + KEEP / 4) {
            val tmp = File(f.path + ".part")
            tmp.writeText((old + new).takeLast(KEEP).joinToString("") { it.json().toString() + "\n" })
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } else f.appendText(new.joinToString("") { it.json().toString() + "\n" })
    }

    /** Al olvidar un device, su copia se va con el. */
    @Synchronized
    fun forget(ctx: Context, id: String) { file(ctx, id).delete() }

    /** Lo nuevo de [p]. Nulo si fue bien; si no, por que (para la pestaña). Bloquea. */
    fun fetch(ctx: Context, p: Peer): String? = try {
        val r = Peers.reach(ctx, p) ?: throw IOException("not reachable")
        val since = read(ctx, p.id).maxOfOrNull { it.t } ?: 0L
        val a = Peers.json(Peers.open(r.host, r.port, "GET", "/log", mapOf("since" to since.toString()), r.token)).optJSONArray("entries")
        merge(ctx, p.id, (0 until (a?.length() ?: 0)).map { LinkLog.Entry.of(a!!.getJSONObject(it)) })
        null
    } catch (e: Exception) {
        if (e.message == "HTTP 404") "${p.name}: update Ludolog Link to see its log."
        else "${p.name} isn't reachable: showing the saved copy."
    }
}

/** Las clases de entrada, en el orden de los filtros, con su nombre a la vista. Ver LinkLog. */
private val KINDS = listOf(
    "saves" to "Saves", "companion" to "Companion", "roms" to "ROMs", "files" to "Files", "art" to "Art",
    "settings" to "Settings", "backup" to "Backups", "ludolog" to "Ludolog", "pairing" to "Pairing", "link" to "Link",
)

private const val HERE = "here"

/**
 * La pestaña Log: lo que apunto este device y lo que apuntaron los emparejados, en una sola lista,
 * lo mas nuevo arriba (como la pestaña Log del PC). Mientras se mira, se pide lo nuevo a los otros
 * cada 15 s; con compartir apagado no se les pregunta nada y se ve la copia.
 */
@Composable
fun LogScreen(ctx: Context) {
    val peers = remember(LinkState.peersChanged.intValue) { Peers.all(ctx) }
    val sync by LinkState.syncDevices
    var source by remember { mutableStateOf("all") }
    var kind by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetched by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(100) }
    val clock = remember { java.text.SimpleDateFormat("d MMM  HH:mm:ss", java.util.Locale.US) }

    LaunchedEffect(peers, sync) {
        while (true) {
            notes = if (!sync) listOf("Sharing is off: showing the saved copy.")
                else withContext(Dispatchers.IO) { peers.mapNotNull { PeerLogs.fetch(ctx, it) } }
            fetched++
            delay(15_000)
        }
    }
    // Lo propio se relee cuando se apunta algo (la lista de la pantalla cambia), lo de los otros al pedirlo.
    val all by produceState(emptyList<Pair<String, LinkLog.Entry>>(), fetched, LinkState.log.firstOrNull(), peers) {
        value = withContext(Dispatchers.IO) {
            (LinkLog.since(0L).map { HERE to it } + peers.flatMap { p -> PeerLogs.read(ctx, p.id).map { p.id to it } })
                .sortedByDescending { it.second.t }
        }
    }
    val names = peers.associate { it.id to it.name } + (HERE to Prefs.deviceName(ctx))
    val shown = all.filter { (source == "all" || it.first == source) && (kind == null || it.second.kind == kind) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Chip("Everything", source == "all") { source = "all" }
            Chip("This device", source == HERE) { source = HERE }
            for (p in peers) Chip(p.name, source == p.id) { source = p.id }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Chip("All", kind == null) { kind = null }
            val present = all.mapTo(HashSet()) { it.second.kind }
            for ((id, label) in KINDS) if (id in present) Chip(label, kind == id) { kind = if (kind == id) null else id }
        }
        for (n in notes) Text(n, style = MaterialTheme.typography.bodySmall, color = Look.warn)
        Text("${shown.size} ${if (shown.size == 1) "entry" else "entries"}", style = MaterialTheme.typography.bodySmall, color = MenuDim)

        Pane(null, Modifier.fillMaxWidth()) {
            Column {
                if (shown.isEmpty()) Text(if (all.isEmpty()) "Nothing yet." else "Nothing with these filters.",
                    Modifier.padding(16.dp), color = MenuDim)
                // La pantalla entera ya se desplaza: una lista perezosa dentro no cabe, asi que se corta.
                for ((src, x) in shown.take(limit)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Text(clock.format(java.util.Date(x.t)), Modifier.width(170.dp), style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace, color = MenuDim)
                        Text(if (src == HERE) "This device" else names[src] ?: src, Modifier.width(170.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            color = if (src == HERE) MenuInk else Look.accent)
                        Text(KINDS.firstOrNull { it.first == x.kind }?.second ?: x.kind, Modifier.width(110.dp),
                            style = MaterialTheme.typography.bodySmall, color = MenuDim)
                        Text(x.text, Modifier.weight(1f), color = if (x.error) Look.danger else MenuInk)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (shown.size > limit) Box(Modifier.fillMaxWidth().padding(8.dp)) {
                    LTextButton(onClick = { limit += 200 }) { Text("Older (${shown.size - limit})") }
                }
            }
        }
    }
}
