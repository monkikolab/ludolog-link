package com.felp.ludologlink

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.ludolog.kit.ui.KitIcons
import com.felp.ludolog.kit.ui.LOutlinedButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import com.felp.ludolog.kit.ui.Pane
import com.felp.ludolog.kit.ui.RowContent
import com.felp.ludolog.kit.ui.selectedRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Traer ROMs de otro device emparejado, sin el PC: su biblioteca (por defecto, solo lo que falta
 * aqui) y un "Get" por juego, que trae el ROM y despues su caratula y su video. De a uno: traer
 * todo lo que falta llenaria la tarjeta. Ver RomTransfer.
 */
@Composable
fun RomsScreen(ctx: Context) {
    val peersVersion by LinkState.peersChanged
    val changed by LinkState.pcChanged
    val peers = remember(peersVersion) { Peers.all(ctx) }
    // La lista del catalogo del PC, si alguno la dejo aqui (ver PcRequests).
    val pcCatalog = remember(changed) { PcRequests.catalog() }
    if (peers.isEmpty() && pcCatalog == null) {
        Pane("ROM transfer", Modifier.fillMaxWidth()) {
            Text("Pair a device, or connect the PC with a catalog folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    var sourceId by remember { mutableStateOf(peers.firstOrNull()?.id ?: PC) }
    if ((sourceId == PC && pcCatalog == null) || (sourceId != PC && peers.none { it.id == sourceId }))
        sourceId = peers.firstOrNull()?.id ?: PC
    if (sourceId == PC) {
        PcCatalogScreen(ctx, peers, pcCatalog!!) { sourceId = it }
        return
    }
    val peer = peers.first { it.id == sourceId }
    var reload by remember { mutableIntStateOf(0) }
    var lib by remember(peer.id) { mutableStateOf<RomTransfer.Library?>(null) }
    var error by remember(peer.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val done = RomTransfer.jobs.count { it.state.value == RomTransfer.State.DONE }

    // Su biblioteca, y lo que ya hay aqui (para no ofrecer lo que se tiene).
    LaunchedEffect(peer.id, reload) {
        loading = true; error = null
        withContext(Dispatchers.IO) {
            val r = runCatching { RomTransfer.library(ctx, peer) }
            LinkState.post { r.onSuccess { lib = it }.onFailure { error = it.message ?: "Couldn't read its ROMs" }; loading = false }
        }
    }
    val here = remember(done, reload) { localKeys(ctx) }
    val free = remember(done, reload) { RomStore.root(ctx)?.usableSpace }

    var filter by remember { mutableStateOf("") }
    var console by remember(peer.id) { mutableStateOf<String?>(null) }
    var onlyMissing by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Pane("ROM transfer", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Games come with their cover and video.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("From  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SourceChips(peers, pcCatalog != null, sourceId) { sourceId = it }
                    Box(Modifier.weight(1f))
                    LTextButton(onClick = { reload++ }, enabled = !loading) { Text(if (loading) "Reading…" else "Reload") }
                }
                Text("This device: ${free?.let { RomTransfer.size(it) } ?: "?"} free", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = Look.warn) }
            }
        }

        // Lo que se esta trayendo.
        val jobs = RomTransfer.jobs.toList()
        if (jobs.isNotEmpty()) Pane("Transfers", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (j in jobs.takeLast(8).reversed()) {
                    val st = j.state.value
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(j.game.stem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when (st) {
                                RomTransfer.State.QUEUED -> "Waiting · from ${j.peer.name}"
                                RomTransfer.State.RUNNING -> "${(j.progress.floatValue * 100).toInt()} % of ${RomTransfer.size(j.game.size)} · from ${j.peer.name}"
                                RomTransfer.State.DONE -> "Done" + (j.note.value?.let { " · $it" } ?: "")
                                RomTransfer.State.FAILED -> "Failed: ${j.note.value ?: "?"}"
                                RomTransfer.State.CANCELLED -> "Cancelled"
                            }, style = MaterialTheme.typography.bodySmall,
                                color = if (st == RomTransfer.State.FAILED) Look.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (st == RomTransfer.State.RUNNING) LinearProgressIndicator(progress = { j.progress.floatValue },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        if (st == RomTransfer.State.QUEUED || st == RomTransfer.State.RUNNING)
                            LTextButton(onClick = { RomTransfer.cancel(j) }) { Text("Cancel") }
                        if (st == RomTransfer.State.FAILED) LTextButton(onClick = { RomTransfer.get(ctx, j.peer, j.game) }) { Text("Retry") }
                    }
                }
                if (jobs.any { it.state.value != RomTransfer.State.QUEUED && it.state.value != RomTransfer.State.RUNNING })
                    LTextButton(onClick = { RomTransfer.clearFinished() }) { Text("Clear finished") }
            }
        }

        val l = lib ?: return@Column
        Pane("On ${peer.name}", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, shape = Look.shape,
                        placeholder = { Text("Search") }, modifier = Modifier.widthIn(max = 320.dp))
                    Chip("Only missing here", onlyMissing) { onlyMissing = !onlyMissing }
                }
                // Las consolas que tiene alla, para mirar una.
                val consoles = l.games.map { it.system }.distinct().sortedBy { (l.consoles[it] ?: it).lowercase() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip("All consoles", console == null) { console = null }
                    for (c in consoles) Chip(l.consoles[c]?.takeIf { it.isNotBlank() } ?: c, console == c) { console = c }
                }
                val shown = remember(l, filter, console, onlyMissing, here) {
                    val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
                    l.games.filter { g ->
                        (console == null || g.system == console) && (!onlyMissing || !isHere(g, here)) &&
                            words.all { w -> g.name.lowercase().contains(w) || g.system.lowercase().contains(w) }
                    }.sortedWith(compareBy({ it.system }, { it.stem.lowercase() }))
                }
                Text("${shown.size} games" + if (shown.size > LIMIT) " · showing $LIMIT" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                for (g in shown.take(LIMIT)) GameRow(ctx, peer, l, g, isHere(g, here))
            }
        }
    }
}

private const val LIMIT = 200

/** Un juego de la otra: nombre, consola y tamaño, si tiene caratula y video alla, y traerlo. */
@Composable
private fun GameRow(ctx: Context, peer: Peer, l: RomTransfer.Library, g: RomTransfer.RemoteGame, here: Boolean) {
    val job = RomTransfer.jobs.lastOrNull { it.game.key == g.key }
    val st = job?.state?.value
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(g.stem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${l.consoles[g.system]?.takeIf { it.isNotBlank() } ?: g.system} · ${RomTransfer.size(g.size)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(KitIcons.Cover, if (l.hasArt(g)) "cover there" else "no cover", Modifier.size(20.dp),
            tint = if (l.hasArt(g)) Look.accent else Look.off)
        Icon(KitIcons.Video, if (l.hasVideo(g)) "video there" else "no video", Modifier.size(20.dp),
            tint = if (l.hasVideo(g)) Look.accent else Look.off)
        Box(Modifier.width(150.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                st == RomTransfer.State.QUEUED -> Text("Waiting", color = MaterialTheme.colorScheme.onSurfaceVariant)
                st == RomTransfer.State.RUNNING -> Text("${(job.progress.floatValue * 100).toInt()} %")
                here || st == RomTransfer.State.DONE -> Text("On this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LOutlinedButton(onClick = { RomTransfer.get(ctx, peer, g) }) { Text("Get") }
            }
        }
    }
}

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.selectedRow(on).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        RowContent(on) { Text(label, color = if (on) MenuInk else MenuDim) }
    }
}

/** Lo que hay aqui: por archivo exacto y por nombre normalizado en cada consola. */
private fun localKeys(ctx: Context): Set<String> {
    val root = RomStore.root(ctx) ?: return emptySet()
    val j = runCatching { RomStore.list(root) }.getOrNull() ?: return emptySet()
    val out = HashSet<String>()
    for (sys in j.keys()) {
        val a = j.getJSONArray(sys)
        for (i in 0 until a.length()) {
            val name = a.getJSONObject(i).getString("name")
            out += "$sys/$name"
            out += "$sys/~" + norm(com.felp.ludolog.kit.Protocol.stemOf(name.substringAfterLast('/')))
        }
    }
    return out
}

private fun norm(s: String) = RomTransfer.norm(s)

private fun isHere(g: RomTransfer.RemoteGame, here: Set<String>) = g.key in here || "${g.system}/~${norm(g.stem)}" in here

private const val PC = "pc"

/** De donde traer: cada device emparejado y, si dejo su lista aqui, el catalogo del PC. */
@Composable
private fun SourceChips(peers: List<Peer>, hasPc: Boolean, current: String, pick: (String) -> Unit) {
    for (p in peers) Chip(p.name, p.id == current) { pick(p.id) }
    if (hasPc) Chip("PC catalog", current == PC) { pick(PC) }
}

/**
 * Pedir juegos del catalogo del PC, en cola: se marcan aqui y el PC los manda (con su arte) cuando
 * Ludolog Link esta abierto alli; hecho con el PC apagado, llegan la proxima vez. Si no caben, solo
 * se avisa. Ver PcRequests.
 */
@Composable
private fun PcCatalogScreen(ctx: Context, peers: List<Peer>, cat: PcRequests.Catalog, pick: (String) -> Unit) {
    val changed by LinkState.pcChanged
    val requests = remember(changed) { PcRequests.prune(ctx) }
    val here = remember(changed) { localKeys(ctx) }
    val free = remember(changed) { RomStore.root(ctx)?.usableSpace ?: 0L }
    // Lo que ocuparan los pedidos que faltan: el espacio de verdad para uno mas.
    val pending = requests.sumOf { it.size }
    val clock = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.US) }
    var filter by remember { mutableStateOf("") }
    var console by remember { mutableStateOf<String?>(null) }
    var onlyMissing by remember { mutableStateOf(true) }
    fun isHere(i: PcRequests.Item) = i.key in here || "${i.system}/~${norm(com.felp.ludolog.kit.Protocol.stemOf(i.name.substringAfterLast('/')))}" in here

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Pane("ROM transfer", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${cat.pc} sends them when Ludolog Link is open there.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("From  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SourceChips(peers, true, PC, pick)
                }
                Text("This device: ${RomTransfer.size(free)} free" +
                    (if (pending > 0) " · ${RomTransfer.size(pending)} requested" else "") +
                    " · catalog of ${cat.pc}, updated ${clock.format(java.util.Date(cat.at))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (requests.isNotEmpty()) Pane("Requested", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in requests) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.system} · ${RomTransfer.size(r.size)} · waiting for ${cat.pc}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LTextButton(onClick = { PcRequests.cancel(r.key) }) { Text("Cancel") }
                }
            }
        }

        Pane("In the PC catalog", Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, singleLine = true, shape = Look.shape,
                        placeholder = { Text("Search") }, modifier = Modifier.widthIn(max = 320.dp))
                    Chip("Only missing here", onlyMissing) { onlyMissing = !onlyMissing }
                }
                val consoles = cat.items.map { it.system }.distinct().sortedBy { it.lowercase() }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip("All consoles", console == null) { console = null }
                    for (c in consoles) Chip(c, console == c) { console = c }
                }
                val shown = remember(cat, filter, console, onlyMissing, here) {
                    val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
                    cat.items.filter { i ->
                        (console == null || i.system == console) && (!onlyMissing || !isHere(i)) &&
                            words.all { w -> i.title.lowercase().contains(w) || i.name.lowercase().contains(w) || i.system.lowercase().contains(w) }
                    }.sortedWith(compareBy({ it.system }, { it.title.lowercase() }))
                }
                Text("${shown.size} games" + if (shown.size > LIMIT) " · showing $LIMIT" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                for (i in shown.take(LIMIT)) {
                    val requested = requests.any { it.key == i.key }
                    // Cabe si hay sitio para el y para lo ya pedido, con margen.
                    val fits = free - pending >= i.size + (64L shl 20)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(i.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${i.system} · ${RomTransfer.size(i.size)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(KitIcons.Cover, null, Modifier.size(20.dp), tint = if (i.art) Look.accent else Look.off)
                        Icon(KitIcons.Video, null, Modifier.size(20.dp), tint = if (i.video) Look.accent else Look.off)
                        Box(Modifier.width(190.dp), contentAlignment = Alignment.CenterEnd) {
                            when {
                                isHere(i) -> Text("On this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                requested -> Text("Requested", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                !fits -> Text("Needs ${RomTransfer.size(i.size)}", color = Look.warn,
                                    style = MaterialTheme.typography.bodySmall)
                                // Pedir al PC es querer PC Link: se enciende (y se apaga solo despues).
                                else -> LOutlinedButton(onClick = { PcRequests.request(i); LinkService.setPcLink(ctx, true) }) { Text("Request") }
                            }
                        }
                    }
                }
            }
        }
    }
}
