package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.Protocol
import java.io.File

// UploadPlan.kt: subir ROMs del PC a uno o varios devices. Empieza al soltar archivos o carpetas en
// la ventana, o con "Upload ROMs…" de la ficha del device (las dos cosas en Main.kt y ConsoleView):
// UploadPlan.start arma una fila por archivo y adivina su carpeta (suggest); UploadPlanDialog deja
// revisarlo y lo manda a la cola de AppState.transfers, con una pasada del scraper si se pide.

/** Un archivo del PC y a donde va en la consola. Sistema y nombre se pueden cambiar antes de subir. */
class PlanRow(val file: File, name: String) {
    /** Lo que pesa, leido una vez: se sumaba en cada redibujo, archivo por archivo en el disco. */
    val size: Long = file.length()
    var include by mutableStateOf(true)
    var system by mutableStateOf<String?>(null)
    var name by mutableStateOf(name)
    var overwrite by mutableStateOf(false)
    /** Varias carpetas aceptan esa extension y ninguna pista decide: revisar. */
    var ambiguous by mutableStateOf(false)
}

class UploadPlan(val console: ConsoleEntry, val rows: List<PlanRow>) {

    /**
     * A que consolas va: la que se esta viendo, y las que se marquen de las otras emparejadas y
     * conectadas. Cada fila va a la misma carpeta en todas (si en alguna no existe, se crea).
     */
    val targets = androidx.compose.runtime.mutableStateListOf(console)

    /** Buscar arte y video para lo que se sube, con el scraper de Ludolog en el PC. */
    var scrape by mutableStateOf(true)

    fun exists(r: PlanRow): Boolean = existsOn(console, r)

    private fun key(system: String, name: String) = system + "/" + name.trim().lowercase()

    /**
     * Lo que ya tiene cada consola, como conjunto ("consola/nombre" en minusculas), rehecho solo si su
     * lista cambia. Antes, cada fila recorria todos los ROMs de la consola, varias veces por redibujo.
     */
    private val haveCache = HashMap<String, Pair<List<RomFile>, Set<String>>>()

    private fun have(t: ConsoleEntry): Set<String> {
        val roms = t.roms
        haveCache[t.id]?.let { (list, set) -> if (list === roms) return set }
        return roms.mapTo(HashSet()) { key(it.system, it.name) }.also { haveCache[t.id] = roms to it }
    }

    fun existsOn(t: ConsoleEntry, r: PlanRow): Boolean {
        val s = r.system ?: return false
        return key(s, r.name) in have(t)
    }

    /** Lo que se manda a [t]: lo listo, menos lo que ya tiene, salvo que se reemplace. */
    fun sendTo(t: ConsoleEntry) = rows.filter { it.include && problem(it) == null && (!existsOn(t, it) || it.overwrite) }

    /**
     * Cuantas veces esta cada "consola/nombre" entre lo incluido. Se rehace solo al cambiar una fila
     * (se lee su estado): antes cada fila contaba todas las demas, en cada redibujo y por cada uso.
     */
    private val twins by androidx.compose.runtime.derivedStateOf {
        rows.filter { it.include && it.system != null }.groupingBy { key(it.system!!, it.name) }.eachCount()
    }

    fun problem(r: PlanRow): String? {
        val s = r.system ?: return "choose a console"
        val name = r.name.trim()
        if (Protocol.safeParts(s, name) == null) {
            return name.split('/').firstNotNullOfOrNull { Protocol.nameProblem(it) }
                ?: "at most one subfolder inside the console folder"
        }
        return if (r.include && (twins[key(s, name)] ?: 0) > 1) "duplicated in this list" else null
    }

    /** Lo que se va a enviar de verdad a la consola que se mira (ver [sendTo]). */
    fun toSend() = sendTo(console)

    companion object {
        private val junk = setOf("desktop.ini", "thumbs.db")
        private val archives = setOf(".zip", ".7z")

        /** Archivos sueltos tal cual; de una carpeta, sus archivos como "carpeta/archivo" (multidisco). */
        private fun expand(files: List<File>): List<Pair<File, String>> = files.flatMap { f ->
            when {
                f.isFile -> listOf(f to f.name)
                f.isDirectory -> f.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name.lowercase() }
                    .map { it to "${f.name}/${it.name}" }
                else -> emptyList()
            }
        }.filter { (f, _) -> !f.name.startsWith(".") && f.name.lowercase() !in junk }

        fun start(app: AppState, files: List<File>): UploadPlan? {
            val e = app.selected
            if (e == null || !e.paired) {
                app.notify("Select a paired device first.", error = true)
                return null
            }
            if (e.consoleDirs.isEmpty()) {
                app.notify(if (e.loading) "Wait for the device's list to load." else
                    "The device has no ROM folder.", error = true)
                return null
            }
            val entries = expand(files)
            if (entries.isEmpty()) {
                app.notify("No files to upload.", error = true)
                return null
            }
            val plan = UploadPlan(e, entries.map { (f, n) -> PlanRow(f, n) })
            plan.suggest()
            return plan
        }
    }

    /**
     * A que carpeta va cada archivo, segun las extensiones que acepta cada
     * sistema EN ESTA CONSOLA (sus systeminfo.txt). Por orden:
     * una sola carpeta la acepta > la que se esta viendo > la que se llama
     * como la extension (.gbc -> gbc) > la que mas juegos tiene (y se marca para revisar).
     */
    private fun suggest() {
        val current = console.viewSystem
        for (r in rows) {
            val ext = Protocol.extOf(r.name)
            if (ext == ".sbi") continue
            val cands = if (ext.isEmpty()) emptyList() else console.consoleDirs.filter { ext in it.exts }
            val byName = cands.firstOrNull { it.folder.equals(ext.drop(1), ignoreCase = true) }
            when {
                cands.size == 1 -> r.system = cands[0].folder
                cands.isEmpty() -> { r.system = current; r.ambiguous = true }
                current != null && cands.any { it.folder == current } -> r.system = current
                byName != null -> r.system = byName.folder
                // Un .zip/.7z lo acepta media consola: sin pista, mejor que elijas tu.
                ext in archives -> { r.system = null; r.ambiguous = true }
                else -> { r.system = cands.maxBy { it.count }.folder; r.ambiguous = true }
            }
        }
        // Un .sbi va donde su juego: el de esta tanda, o el que ya este en la consola.
        for (r in rows.filter { Protocol.extOf(it.name) == ".sbi" }) {
            val stem = Protocol.stemOf(r.name)
            val twin = rows.firstOrNull { it !== r && Protocol.extOf(it.name) != ".sbi" &&
                Protocol.stemOf(it.name).equals(stem, ignoreCase = true) }?.system
                ?: console.roms.firstOrNull { Protocol.extOf(it.name) != ".sbi" &&
                    Protocol.stemOf(it.name).equals(stem, ignoreCase = true) }?.system
            r.system = twin ?: console.consoleDirs.firstOrNull { it.folder == "psx" }?.folder ?: current
            r.ambiguous = twin == null
        }
    }
}

@Composable
fun UploadPlanDialog(app: AppState, plan: UploadPlan, onClose: () -> Unit) {
    val e = plan.console
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(1080.dp).fillMaxHeight(0.88f), shape = Look.shape,
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val send = plan.toSend()
                val total = send.sumOf { it.size }
                val free = e.info?.free

                // Con mas consolas emparejadas y conectadas: a cual (o a cuales) va.
                val candidates = app.consoles.filter { it.paired && it.online == true }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Upload to ${plan.targets.joinToString(" and ") { it.name }.ifEmpty { "…" }}",
                            style = MaterialTheme.typography.headlineSmall)
                        Text("Check each file's folder and name.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Console for all:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    SystemPicker(e, null, null, label = "Choose…") { s ->
                        plan.rows.forEach { if (it.include) { it.system = s; it.ambiguous = false } }
                    }
                }
                if (candidates.size > 1) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Send to", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    for (c in candidates) {
                        val on = c in plan.targets
                        Box(Modifier.selectedRow(on).clickable {
                            if (on) plan.targets.remove(c) else {
                                plan.targets.add(c)
                                // Para saber que tiene ya y cuanto le cabe.
                                if (c.info == null && !c.loading) app.load(c)
                            }
                        }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            RowContent(on) { Text((if (on) "■  " else "□  ") + c.name, color = if (on) MenuInk else MenuDim) }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                val state = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = state,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(plan.rows) { _, r -> PlanRowView(plan, r) }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(state), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val blocked = plan.rows.count { it.include && plan.problem(it) != null }
                    val skipped = plan.rows.count { it.include && plan.problem(it) == null && plan.exists(it) && !it.overwrite }
                    // Cada consola con lo suyo: lo que le llega y si le cabe.
                    val perTarget = plan.targets.map { t -> Triple(t, plan.sendTo(t), t.info?.free) }
                    val tooBig = perTarget.any { (_, l, f) -> f != null && l.sumOf { it.size } > f }
                    val count = perTarget.sumOf { it.second.size }
                    Column(Modifier.weight(1f)) {
                        if (plan.targets.size <= 1) Text("${files(send.size)} · ${Format.size(total)}" +
                            (free?.let { " · ${Format.size(it)} free on the device" } ?: ""),
                            color = if (tooBig) Look.danger else MaterialTheme.colorScheme.onSurface)
                        else for ((t, l, f) in perTarget) {
                            val bytes = l.sumOf { it.size }
                            Text("${t.name}: ${files(l.size)} · ${Format.size(bytes)}" + (f?.let { " · ${Format.size(it)} free" } ?: ""),
                                color = if (f != null && bytes > f) Look.danger else MaterialTheme.colorScheme.onSurface)
                        }
                        val notes = buildList {
                            if (blocked > 0) add("$blocked need fixing")
                            if (skipped > 0) add("$skipped already there, skipped")
                            if (tooBig) add("not everything fits")
                        }
                        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = if (blocked > 0 || tooBig) Look.danger else Look.warn)
                    }
                    // Antes de subir, el arte y el video de esos juegos: el scraper de Ludolog en el PC.
                    if (plan.targets.any { it.info?.ludolog != null }) {
                        Row(Modifier.selectedRow(plan.scrape).clickable { plan.scrape = !plan.scrape }
                            .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RowContent(plan.scrape) {
                                Text((if (plan.scrape) "■  " else "□  ") + "Scrape art & video", color = if (plan.scrape) MenuInk else MenuDim)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    LTextButton(onClick = onClose) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    LButton(
                        onClick = {
                            if (plan.scrape) for ((t, l, _) in perTarget) {
                                if (t.info?.ludolog == null || l.isEmpty()) continue
                                app.scrape(t, l.map { RomFile(it.system!!, it.name.trim(), it.file.length(), it.file.lastModified()) },
                                    ScrapeMode.MISSING)
                            }
                            app.transfers.add(perTarget.flatMap { (t, l, _) ->
                                l.map { Transfer(true, t.id, t.name, it.system!!, it.name.trim(), it.file, it.file.length(), it.overwrite) }
                            })
                            onClose()
                        },
                        enabled = count > 0 && blocked == 0 && !tooBig,
                    ) {
                        Text(when {
                            plan.targets.size > 1 -> "Upload to ${plan.targets.size} devices"
                            count == 1 -> "Upload 1 file"
                            else -> "Upload $count files"
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRowView(plan: UploadPlan, r: PlanRow) {
    val problem = if (r.include) plan.problem(r) else null
    val exists = r.include && problem == null && plan.exists(r)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = r.include, onCheckedChange = { r.include = it })
        Column(Modifier.width(250.dp).padding(end = 10.dp)) {
            Text(r.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                color = if (r.include) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Format.size(r.size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SystemPicker(plan.console, r.system, Protocol.extOf(r.name), warn = r.ambiguous && r.include) { s ->
            r.system = s
            r.ambiguous = false
        }
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(
            value = r.name, onValueChange = { r.name = it }, singleLine = true, enabled = r.include,
            isError = problem != null && problem != "choose a console",
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(190.dp).padding(start = 10.dp)) {
            when {
                !r.include -> Text("won't be uploaded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                problem != null -> Text(problem.replaceFirstChar(Char::uppercase), color = Look.danger,
                    style = MaterialTheme.typography.bodySmall)
                exists -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = r.overwrite, onCheckedChange = { r.overwrite = it })
                    Text(if (r.overwrite) "already there: will be replaced" else "already there: replace",
                        color = Look.warn, style = MaterialTheme.typography.bodySmall)
                }
                r.ambiguous -> Text("Several possible folders: check it", color = Look.warn,
                    style = MaterialTheme.typography.bodySmall)
                else -> Text("Ready", color = Look.ok, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Boton con el sistema; el menu pone primero los que aceptan la extension del archivo. */
@Composable
private fun SystemPicker(
    e: ConsoleEntry, current: String?, ext: String?, warn: Boolean = false, label: String = "Choose console",
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        LOutlinedButton(onClick = { open = true }, Modifier.width(200.dp), shape = Look.shape) {
            Text(current ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace,
                color = if (warn) Look.warn else if (current == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val fits = if (ext.isNullOrEmpty()) emptyList() else e.consoleDirs.filter { ext in it.exts }
            val rest = e.systems.filter { it !in fits }
            if (fits.isNotEmpty()) {
                Text("Accepting $ext", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = Look.accent)
                fits.forEach { s -> SystemMenuItem(s) { open = false; onPick(s.folder) } }
                HorizontalDivider()
                Text("Other folders", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rest.forEach { s -> SystemMenuItem(s) { open = false; onPick(s.folder) } }
        }
    }
}

@Composable
private fun SystemMenuItem(s: SystemDir, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(s.folder, fontFamily = FontFamily.Monospace)
                if (s.fullName != s.folder) Text(s.fullName, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        onClick = onClick,
    )
}
