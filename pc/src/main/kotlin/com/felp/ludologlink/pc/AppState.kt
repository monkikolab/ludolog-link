package com.felp.ludologlink.pc

import androidx.compose.runtime.getValue
import com.felp.frontcomp.bright
import com.felp.frontcomp.withAccent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.felp.ludolog.kit.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Una consola en la lista: emparejada (recordada) o recien encontrada en la red. */
class ConsoleEntry(val id: String) {
    var name by mutableStateOf("")
    var model by mutableStateOf("")
    var host by mutableStateOf("")
    var port by mutableStateOf(Dev.httpPort)
    var token by mutableStateOf<String?>(null)
    /** null = sin comprobar todavia. */
    var online by mutableStateOf<Boolean?>(null)
    /** Contesta, pero con PC Link apagado: no atiende al PC hasta que se encienda en la consola. */
    var pcLinkOff by mutableStateOf(false)

    var info by mutableStateOf<ConsoleInfo?>(null)
    var systems by mutableStateOf<List<SystemDir>>(emptyList())
    var roms by mutableStateOf<List<RomFile>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** La pestaña que se esta viendo de esta consola: overview, roms, companion o settings. */
    var tab by mutableStateOf("overview")

    /** El Companion de esta consola, leido de su copia en el PC. Nulo mientras no se ha leido. */
    internal var book by mutableStateOf<com.felp.frontcomp.Book?>(null)
    /** Lo que esta pasando con el Companion: «syncing», el error, o nulo. */
    var companionNote by mutableStateOf<String?>(null)
    /** Cuando se puso al dia la copia esencial por ultima vez. */
    var syncedAt by mutableStateOf<Long?>(null)
    /** config.xml de Ludolog en esta consola, de la copia. */
    var ludologConfig by mutableStateOf<Map<String, Any?>>(emptyMap())

    /**
     * Ajustes de Ludolog cambiados en el PC que esperan a "Sync to device": clave y valor nuevo,
     * o nulo para quitarla. Ludolog no se entera hasta entonces (ver AppState.syncConfig).
     */
    val pending = androidx.compose.runtime.mutableStateMapOf<String, Any?>()

    /** Un respaldo en marcha: bytes hechos y total. Nulo si no hay. Ver AppState.backup. */
    var backupProgress by mutableStateOf<Pair<Long, Long>?>(null)
    @Volatile var backupCancel = false

    /** Una pasada del scraper en marcha (ver AppState.scrape) y lo que dejo la ultima. */
    var scrapeState by mutableStateOf<ScrapeState?>(null)
    var scrapeResult by mutableStateOf<ScrapeResult?>(null)

    /** Un video que se esta preparando para un juego: su nombre y cuanto va (1 = enviando). */
    var videoJob by mutableStateOf<Pair<String, Float>?>(null)
    @Volatile var videoCancel = false

    /** Mandando un respaldo de vuelta a la consola. Ver AppState.restore. */
    var restoring by mutableStateOf(false)

    /** Que juegos tienen arte y video en la consola (claves «consola/nombre»). Ver Names. */
    var art by mutableStateOf<Set<String>>(emptySet())
    var video by mutableStateOf<Set<String>>(emptySet())

    /**
     * El catalogo de consolas de Ludolog EN ESTE DEVICE: el de serie con las consolas que el usuario
     * creo o corrigio alli (`systems.toml` y la carpeta `systems/` de su copia en el PC). Cada device el
     * suyo: dos devices pueden tener consolas propias distintas. Nulo hasta leer su copia.
     */
    internal var catalog by mutableStateOf<com.felp.frontcomp.Catalog?>(null)

    /** Las consolas que el usuario creo en Ludolog en este device (no estan en el catalogo de serie). */
    internal var customSystems by mutableStateOf<List<com.felp.frontcomp.SystemDef>>(emptyList())

    /**
     * Las carpetas de consolas como las ve Ludolog: las de la consola, con nombre y extensiones de su
     * `systeminfo.txt` (ES-DE) o, si no lo tiene, de su catalogo; y las consolas creadas en Ludolog que
     * aun no tienen carpeta, para poder subirles ROMs (la consola crea la carpeta al recibirlos).
     */
    val consoleDirs: List<SystemDir>
        get() {
            val cat = catalog ?: return systems
            val have = systems.map { s ->
                if (s.exts.isNotEmpty()) s
                else cat.forFolder(s.folder)?.let { d -> s.copy(fullName = d.name, exts = cat.extensionsOf(d).toList()) } ?: s
            }
            val known = have.mapNotNull { cat.forFolder(it.folder)?.id }.toSet()
            return have + customSystems.filter { it.id !in known }
                .map { SystemDir(it.id, it.name, cat.extensionsOf(it).toList(), 0) }
        }

    /** Los ROMs sin los accesos directos (apps, emuladores); Steam y DoomForge si, bloqueados. Ver Shortcuts. */
    val games: List<RomFile> get() = roms.filterNot(Shortcuts::isShortcut)

    /** Sistema que se esta viendo (null = todos); tambien es el que se sugiere al subir. */
    var viewSystem by mutableStateOf<String?>(null)

    val paired get() = token != null
    fun link() = Link(host, port, token)

    fun toKnown() = Known(id, name, model, host, port, token ?: "")
}

class Notice(val text: String, val error: Boolean)

class AppState(private val scope: CoroutineScope) {
    val consoles = mutableStateListOf<ConsoleEntry>()
    var selectedId by mutableStateOf<String?>(null)
    val selected: ConsoleEntry? get() = consoles.firstOrNull { it.id == selectedId }
    var searching by mutableStateOf(false)

    /**
     * Una pestaña de todos los conectados a la vez, sea cual sea el device elegido: "games" o
     * "consoles". Nula: las del device elegido.
     */
    var globalTab by mutableStateOf<String?>(null)

    /** Ya termino una busqueda: hasta entonces, «Connecting…» y no «not reachable». */
    var searchedOnce by mutableStateOf(false)
    var notice by mutableStateOf<Notice?>(null)
    /** Consola a la que se le ha pedido un codigo: abre el dialogo de emparejar. */
    var pairing by mutableStateOf<ConsoleEntry?>(null)
    val transfers = Transfers(this, scope)

    /** "console" o el id de un tema fijado a mano. Ver [theme]. */
    var look by mutableStateOf(Config.look)
        private set

    fun chooseLook(v: String) { look = v; Config.look = v }

    /** Sube cuando llega una letra de tema nueva, para volver a leerlas. */
    var fontsVersion by mutableStateOf(0)
        private set

    private val fontsAsked = HashSet<String>()

    /**
     * La letra del tema de Ludolog, de la consola al PC, una vez por tema y sesion: la que traiga
     * en `themes/<id>/font/` (el retro-futurista, IBM Plex Mono). Sin ella, la del sistema.
     */
    private fun fetchFonts(e: ConsoleEntry, themeId: String) {
        if (!fontsAsked.add(themeId)) return
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                var any = false
                for (stem in listOf("display", "body")) for (ext in listOf("ttf", "otf")) {
                    val dest = java.io.File(Config.themeDir(themeId), "font/$stem.$ext")
                    if (dest.isFile) continue
                    if (runCatching { e.link().ludologFile("themes/$themeId/font/$stem.$ext", dest) }.getOrDefault(false)) any = true
                }
                any
            }
            if (got) fontsVersion++
        }
    }

    /**
     * El tema con que se dibuja todo: el de Ludolog en la consola elegida —su aspecto, su luz y
     * su acento—, o el fijado a mano. Sin Ludolog en la consola, el minimalista.
     */
    val theme: com.felp.frontcomp.Theme
        get() {
            if (look != "console") return com.felp.frontcomp.themeById(look)
            val e = selected
            val l = e?.info?.ludolog
            // Sin conexion, lo que diga la copia de su config.xml.
            val c = e?.ludologConfig.orEmpty()
            val id = l?.theme ?: c["look.theme"] as? String
            val base = com.felp.frontcomp.themeById(id)
            val bright = l?.bright ?: (c["theme.bright.${base.id}"] as? Boolean ?: false)
            val accent = if (l != null) l.accent else c["look.accent.${base.id}"] as? String
            val lit = if (bright) base.bright() ?: base else base
            return lit.withAccent(accent)
        }

    private var noticeJob: Job? = null

    init {
        for (k in Config.known()) {
            consoles += ConsoleEntry(k.id).apply {
                name = k.name; model = k.model; host = k.host; port = k.port; token = k.token
            }
        }
        selectedId = consoles.firstOrNull()?.id
        // Lo que ya se copio de cada una: su Companion se ve aunque hoy no este a la vista.
        for (e in consoles) scope.launch { readCompanion(e) }
        search()
        CatalogRequests.start(this, scope)
    }

    /**
     * Un aviso abajo. Con [kind] es tambien el resultado de algo que hizo el PC, y queda en el registro
     * (pestaña Log de [about], o de todas si es nulo). Ver PcLog.
     */
    fun notify(text: String, error: Boolean = false, about: ConsoleEntry? = null, kind: String? = null) {
        if (kind != null) PcLog.add(about?.id, kind, text, error)
        notice = Notice(text, error)
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(if (error) 9000 else 5000)
            notice = null
        }
    }

    /**
     * Lo nuevo de la actividad de [e] al registro del PC. Nulo si fue bien; si no, por que (para
     * ensenarlo en la pestaña). Bloquea: llamar desde Dispatchers.IO.
     */
    fun loadLog(e: ConsoleEntry): String? = try {
        PcLog.merge(e.id, e.link().log(PcLog.lastFrom(e.id), e.id))
        null
    } catch (x: LinkError) {
        if (x.status == 404) "This device's Ludolog Link is older and doesn't keep a log: update it."
        else "Couldn't read the device's log: ${x.message}. Showing what this PC kept."
    }

    // --------------------------------------------------------------- busqueda

    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { Discovery.search() }.getOrDefault(emptyList()) }
            for (f in found) {
                val e = consoles.firstOrNull { it.id == f.id } ?: ConsoleEntry(f.id).also { consoles += it }
                e.pcLinkOff = !f.pcLink
                if (e.pcLinkOff) {
                    // Se ve, pero no atiende: ni se le pregunta nada (contestaria 403).
                    if (e.name.isEmpty()) { e.name = f.name; e.model = f.model }
                    e.online = false
                    continue
                }
                // Una emparejada que contesta desde otra IP: solo se le cree si prueba conocer la clave de
                // este PC. Cualquiera en la Wi-Fi puede contestar con un id (se ve en la busqueda); antes
                // se le mandaba la clave para comprobarlo, y quien se hiciera pasar por ella se la llevaba
                // (09-10-2026). Ver Link.proves y, para las emparejadas antes de la 0.5.3, claim.
                if (e.paired && (f.host != e.host || f.port != e.port)) {
                    val t = e.token
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            val l = Link(f.host, f.port, null)
                            // Una consola con Link anterior a la 0.5.3 no sabe dar la prueba: con ella, como
                            // antes, aceptando la clave en esa IP. Al actualizarla ya no hace falta mandarla.
                            if (older(l.ping().optString("version"), "0.5.3")) {
                                Link(f.host, f.port, t).info(); l.ping().optString("id") == f.id
                            } else l.proves(f.id, Config.pcId, t.orEmpty())
                        }.getOrDefault(false)
                    }
                    if (!ok) continue
                }
                e.name = f.name; e.model = f.model; e.host = f.host; e.port = f.port
                e.online = true
                if (e.paired) Config.remember(e.toKnown())       // la IP puede haber cambiado
            }
            // Las que no contestaron: probar su ultima IP, por si el broadcast no llega.
            val missing = consoles.filter { c -> found.none { it.id == c.id } && c.host.isNotEmpty() }
            withContext(Dispatchers.IO) {
                missing.map { c ->
                    async { c to runCatching { Link(c.host, c.port, null).ping() }.getOrNull() }
                }.awaitAll()
            }.forEach { (c, p) ->
                val same = p != null && p.optString("id") == c.id
                c.pcLinkOff = same && !p!!.optBoolean("pcLink", true)
                c.online = same && !c.pcLinkOff
            }

            val order = consoles.sortedWith(compareBy({ it.online != true }, { !it.paired }, { it.name.lowercase() }))
            consoles.clear()
            consoles.addAll(order)
            searching = false
            searchedOnce = true

            if (selected == null) selectedId = consoles.firstOrNull { it.online == true }?.id
            // Todas las emparejadas que contestan, no solo la elegida: "Sync companions", "Copy to" y
            // subir a varias necesitan saber de cada una sin tener que ir pasando por ellas.
            consoles.filter { it.paired && it.online == true && !it.loading && (it.info == null || it.error != null) }
                .forEach { load(it) }
            val closed = consoles.filter { it.pcLinkOff }
            if (closed.isNotEmpty() && consoles.none { it.online == true }) {
                notify("PC Link is off on ${closed.joinToString { it.name.ifBlank { it.model } }}.")
            } else if (found.isEmpty() && consoles.none { it.online == true }) {
                notify("No devices found. Turn on PC Link on the device.")
            }
        }
    }

    /**
     * La copia esencial de la carpeta de Ludolog al dia, y el Companion leido de ella. Las cuentas
     * son las de Ludolog (ver CompanionReader); la copia es tambien la base del respaldo.
     */
    fun refreshCompanion(e: ConsoleEntry) {
        if (e.companionNote == "syncing") return
        e.companionNote = "syncing"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Mirror.sync(e.link(), e.id, essentialOnly = true)
                    // Y los respaldos de partidas guardadas que la consola hizo desde la ultima vez.
                    runCatching { SaveBackups.pull(e.link(), e.id) }
                }
                e.syncedAt = System.currentTimeMillis()
                readCompanion(e)
                e.companionNote = null
            } catch (x: LinkError) {
                // Sin conexion, se lee la copia de la ultima vez si la hay.
                readCompanion(e)
                e.companionNote = if (e.book != null) "offline copy" else x.message
            }
        }
    }

    /**
     * Que tiene arte y video, segun la consola. [changed]: se acaba de cambiar arte (con el mismo
     * nombre, la lista no lo dice), asi que lo visto al pasar el raton se tira. Al conectar, solo si
     * la lista es otra: antes se tiraba siempre y se volvia a bajar todo por la Wi-Fi.
     */
    fun loadArt(e: ConsoleEntry, changed: Boolean = true) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { e.link().art() } }.onSuccess { (a, v) ->
                if (changed || a != e.art || v != e.video) withContext(Dispatchers.IO) { Previews.forget(e.id) }
                e.art = a; e.video = v
            }
        }
    }

    /** Un ajuste de Ludolog cambiado en el PC, a la espera de "Sync to device". Nulo lo quita. */
    fun stage(e: ConsoleEntry, key: String, value: Any?) {
        val current = e.ludologConfig[key]
        if (value == current || (value == null && key !in e.ludologConfig)) e.pending.remove(key) else e.pending[key] = value
    }

    fun discard(e: ConsoleEntry) = e.pending.clear()

    /**
     * Nombre, genero o descripcion de un juego ([changes]: campo -> valor; vacio, el del catalogo), a
     * cada device que lo tiene, con su ruta alli y la misma hora: asi gana sobre lo de antes en todos.
     * Cada uno lo aplica en Ludolog sin reiniciarla y lo pasa a sus devices al compartir. Si el juego
     * esta en el catalogo del PC ([catalog]), su copia tambien (ver CatalogInfo).
     */
    fun saveGameInfo(targets: List<Pair<ConsoleEntry, RomFile>>, changes: Map<String, String>, catalog: RomFile? = null) {
        if (changes.isEmpty()) return
        val t = System.currentTimeMillis()
        if (catalog != null) runCatching { CatalogInfo.set(catalog, changes, t) }
            .onFailure { notify("Couldn't save in the catalog: ${it.message}", error = true, kind = "ludolog") }
        if (targets.isEmpty()) { notify("Saved in the PC catalog.", kind = "ludolog"); return }
        scope.launch {
            val failed = mutableListOf<String>()
            for ((d, f) in targets) {
                val path = Names.path(d, f) ?: continue
                val system = Names.game(d, f)?.systemId ?: f.system
                try {
                    withContext(Dispatchers.IO) {
                        for ((k, v) in changes) d.link().metaEdit("game", system, path, k, if (k == "genre") GameInfo.genreOut(v) else v, t)
                    }
                    for ((k, v) in changes) {
                        GameInfo.sent(d, f, k, v, t)
                        val key = when (k) { "name" -> "name.game.$path"; "desc" -> "desc.game.$path"; else -> null }
                        if (key != null) d.ludologConfig = d.ludologConfig.toMutableMap().apply { if (v.isEmpty()) remove(key) else put(key, v) }
                    }
                } catch (x: LinkError) {
                    failed += d.name
                }
            }
            val n = targets.size
            if (failed.isEmpty()) notify("Game info sent to $n ${if (n == 1) "device" else "devices"}.", kind = "ludolog")
            else notify("Couldn't reach ${failed.joinToString()}.", error = true, kind = "ludolog")
        }
    }

    /**
     * El nombre o la descripcion de una consola ([changes]: `name`/`desc` -> valor; vacio, el del
     * catalogo) a cada device ([targets]: con el id de la consola en su Ludolog).
     */
    fun saveConsoleInfo(targets: List<Pair<ConsoleEntry, String>>, changes: Map<String, String>) {
        if (targets.isEmpty() || changes.isEmpty()) return
        val t = System.currentTimeMillis()
        scope.launch {
            val failed = mutableListOf<String>()
            for ((d, system) in targets) {
                try {
                    withContext(Dispatchers.IO) { for ((k, v) in changes) d.link().metaEdit("sys", system, "", k, v, t) }
                    d.ludologConfig = d.ludologConfig.toMutableMap().apply {
                        for ((k, v) in changes) { val key = "$k.sys.$system"; if (v.isEmpty()) remove(key) else put(key, v) }
                    }
                } catch (x: LinkError) {
                    failed += d.name
                }
            }
            if (failed.isEmpty()) notify("Console info sent to ${targets.size} ${if (targets.size == 1) "device" else "devices"}.", kind = "ludolog")
            else notify("Couldn't reach ${failed.joinToString()}.", error = true, kind = "ludolog")
        }
    }

    /**
     * Una caratula o un video de un archivo del PC, al catalogo del PC para el juego de [r]: con el
     * nombre de su ROM alli o, si alli no esta (Steam, DoomForge, o solo su arte), el del device.
     */
    fun fileToCatalog(r: CatalogRow, devices: List<ConsoleEntry>, scan: PcCatalog.Scan, file: java.io.File, video: Boolean) {
        val root = PcCatalog.dir ?: return catalogMissing()
        val pc = r.cells[CatalogRow.PC]?.rom
        val dev = devices.firstOrNull { r.cells[it.id]?.rom != null }
        val f = pc ?: dev?.let { r.cells[it.id]?.rom } ?: return
        val sys = ((if (pc != null) PcCatalog.game(scan, pc)?.systemId else dev?.let { Names.game(it, f)?.systemId }) ?: f.system).lowercase()
        val stem = windowsName(com.felp.ludolog.kit.Protocol.stemOf(f.name.substringAfterLast('/')))
        val kind = if (video) "videos" else "covers"
        runCatching {
            val folder = inside(root, java.io.File(root, "${PcCatalog.MEDIA}/${windowsName(sys)}/$kind"))
            folder.mkdirs()
            folder.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.equals(stem, true) }.forEach { it.delete() }
            file.copyTo(java.io.File(folder, "$stem.${file.extension.lowercase()}"), overwrite = true)
        }.onSuccess { notify("Saved in the PC catalog.", kind = "art") }
            .onFailure { notify("Couldn't save it in the PC catalog: ${it.message}", error = true, kind = "art") }
    }

    /** Una caratula o un video del catalogo del PC, fuera. */
    fun removeFromCatalog(file: java.io.File) {
        val ok = runCatching { file.delete() }.getOrDefault(false)
        notify(if (ok) "Removed ${file.name} from the PC catalog." else "Couldn't remove ${file.name}.", error = !ok, kind = "art")
    }

    /** Lo corregido en [src] de ese juego, para el catalogo del PC. */
    private fun ownInfo(src: ConsoleEntry, f: RomFile): Map<String, String> =
        GameInfo.FIELDS.mapNotNull { (k, _) -> GameInfo.own(src, f, k)?.let { k to it } }.toMap()

    /**
     * Lo que el catalogo sabe de un juego que manda a [dst] (ver CatalogInfo), con las claves de su
     * archivo: alli aun no tiene ficha, y Link lo deja pendiente hasta que Ludolog lo lea.
     */
    private fun infoFromCatalog(local: java.io.File, f: RomFile, dst: ConsoleEntry, system: String) {
        val values = CatalogInfo.values(f)
        if (values.isEmpty() || dst.info?.ludolog == null) return
        val path = dst.info?.romsRoot?.let { "$it/${f.system}/${f.name}" } ?: return
        val t = CatalogInfo.time(f).takeIf { it > 0 } ?: System.currentTimeMillis()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val keys = runCatching { com.felp.frontcomp.GameId.of(local).joinToString(" ") }.getOrDefault("")
                    for ((k, v) in values) dst.link().metaEdit("game", system, path, k, if (k == "genre") GameInfo.genreOut(v) else v, t, keys)
                }
            }.onFailure { notify("Couldn't send the game info to ${dst.name}: ${it.message}", error = true, about = dst, kind = "ludolog") }
        }
    }

    /**
     * Manda lo pendiente. La consola lo deja para Ludolog y le avisa; Ludolog lo aplica ella misma
     * y se refresca cuando no hay partida en curso. Solo van las claves cambiadas, no el config.xml
     * entero: asi no se pisa lo que Ludolog apunto mientras tanto.
     */
    fun syncConfig(e: ConsoleEntry) {
        if (e.pending.isEmpty()) return
        val sent = e.pending.toMap()
        val body = configChanges(sent)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { e.link().sendConfig(body) }
                // Se ve ya en el PC; la copia se pone al dia en cuanto Ludolog los aplique.
                e.ludologConfig = e.ludologConfig.toMutableMap().apply {
                    for ((k, v) in sent) if (v == null) remove(k) else put(k, v)
                }
                val n = sent.size
                // Solo lo mandado: lo que se cambio mientras tanto sigue pendiente.
                for ((k, v) in sent) if (e.pending.containsKey(k) && e.pending[k] == v) e.pending.remove(k)
                notify("Sent $n ${if (n == 1) "change" else "changes"}.", about = e, kind = "settings")
                kotlinx.coroutines.delay(8_000)
                refreshCompanion(e)
            } catch (x: LinkError) {
                notify("Couldn't send the changes: ${x.message}", error = true, about = e, kind = "settings")
            }
        }
    }

    /**
     * Renombrar desde la pestaña ROMs: el archivo en la consola (ya, y Ludolog mueve lo suyo por
     * el puente) y/o el nombre que se ve en Ludolog (pendiente de "Sync to device").
     */
    fun renameBoth(e: ConsoleEntry, f: RomFile, newFile: String?, newDisplay: String?) {
        val dir = f.name.substringBeforeLast('/', "")
        val finalName = newFile?.let { if (dir.isEmpty()) it else "$dir/$it" } ?: f.name
        if (newDisplay != null) {
            val p = e.info?.romsRoot?.let { "$it/${f.system}/$finalName" }
            if (p != null) stage(e, "name.game.$p", newDisplay.trim().ifEmpty { null })
        }
        if (newFile != null) rename(e, f, newFile)
        else if (newDisplay != null) notify("Name changed. Sync to device to send it.")
    }

    /**
     * Un respaldo: la copia del PC al dia —lo esencial o todo— y una instantanea fechada de lo
     * esencial. Ver Backups.
     */
    fun backup(e: ConsoleEntry, complete: Boolean) {
        if (e.backupProgress != null) return
        e.backupCancel = false
        e.backupProgress = 0L to 0L
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val r = Mirror.sync(e.link(), e.id, essentialOnly = !complete,
                        progress = { done, total -> e.backupProgress = done to total }, cancelled = { e.backupCancel })
                    java.io.File(Config.consoleDir(e.id), "console.txt").writeText("${e.name}\n${e.model}\n")
                    runCatching { SaveBackups.pull(e.link(), e.id) }
                    Backups.snapshot(e.id)
                    r
                }
                e.syncedAt = System.currentTimeMillis()
                notify("Backed up ${e.name}: ${r.copied} ${if (r.copied == 1) "file" else "files"}, " +
                    "${com.felp.ludolog.kit.Format.size(r.bytes)}.", about = e, kind = "backup")
                readCompanion(e)
            } catch (x: LinkError) {
                notify(if (e.backupCancel) "Backup cancelled. What was already copied stays." else "Backup failed: ${x.message}",
                    error = !e.backupCancel, about = e, kind = "backup")
            } finally {
                e.backupProgress = null
            }
        }
    }

    /**
     * Devuelve a la consola las partes elegidas de una instantanea. Primero guarda como esta hoy
     * (instantanea «before-restore»); luego deja los archivos en espera, los ajustes como
     * diferencia con los de hoy, y lo da por entero. Ludolog lo pone en su sitio al reiniciarse.
     * Ver Restore.kt.
     */
    fun restore(e: ConsoleEntry, snapshot: Snapshot, parts: Set<RestorePart>) {
        if (e.restoring || parts.isEmpty()) return
        e.restoring = true
        scope.launch {
            val link = e.link()
            try {
                val sent = withContext(Dispatchers.IO) {
                    Mirror.sync(link, e.id, essentialOnly = true)
                    // Sin rotar la que se va a devolver: si era la mas vieja, la nueva la sacaba.
                    Backups.snapshot(e.id, "before-restore", protect = snapshot.file)
                    link.clearRestore()
                    var config: org.json.JSONObject? = null
                    var files = 0
                    java.util.zip.ZipFile(snapshot.file).use { z ->
                        for (entry in z.entries().asSequence().filter { !it.isDirectory }) {
                            val part = RestorePart.of(entry.name)?.takeIf { it in parts } ?: continue
                            val bytes = z.getInputStream(entry).use { it.readBytes() }
                            if (part == RestorePart.SETTINGS) {
                                val now = ConfigXml.read(java.io.File(Mirror.dir(e.id), "config.xml"))
                                config = configChanges(configDiff(ConfigXml.read(bytes), now))
                            } else {
                                link.putRestore(entry.name, bytes)
                                files++
                            }
                        }
                    }
                    link.commitRestore(config)
                    files
                }
                // Lo preparado aqui era sobre los ajustes de antes: ya no vale.
                e.pending.clear()
                notify("Backup sent to ${e.name}. Ludolog restarts to apply it.", about = e, kind = "backup")
                // La copia del PC, al dia con lo devuelto, cuando Ludolog ya lo haya puesto.
                scope.launch { kotlinx.coroutines.delay(15_000); refreshCompanion(e) }
            } catch (x: LinkError) {
                withContext(Dispatchers.IO) { runCatching { link.clearRestore() } }
                if (x.status == 0 || x.status == 401) failed(e, x)
                notify("Couldn't restore: ${x.message}", error = true, about = e, kind = "backup")
            } finally {
                e.restoring = false
            }
        }
    }

    /** Una imagen del PC como caratula del juego, donde la busca Ludolog. En PNG. */
    fun importArt(e: ConsoleEntry, f: RomFile, image: java.io.File) {
        val game = Names.game(e, f)
        val system = game?.systemId ?: f.system
        val stem = com.felp.ludolog.kit.Protocol.stemOf(f.name)
        scope.launch {
            try {
                val png = withContext(Dispatchers.IO) {
                    val img = javax.imageio.ImageIO.read(image) ?: throw LinkError(0, "that file isn't an image this PC can read")
                    java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
                }
                withContext(Dispatchers.IO) { e.link().putMedia("media/$system/covers/$stem.png", png) }
                notify("Art added to ${Names.display(e, f)}.", about = e, kind = "art")
                loadArt(e)
            } catch (x: LinkError) {
                notify("Couldn't add the art: ${x.message}", error = true, about = e, kind = "art")
            }
        }
    }

    /**
     * Un video del PC para el juego, en cualquier formato: se convierte AQUI a lo que pide Ludolog
     * en esa consola (duracion, alto, sonido: ver VideoPrep) y se manda ya hecho, a donde lo
     * dejaria su scraper (`media/<consola>/videos/<nombre>.mp4`).
     */
    fun importVideo(e: ConsoleEntry, f: RomFile, video: java.io.File) {
        if (e.videoJob != null) return
        val system = Names.game(e, f)?.systemId ?: f.system
        val stem = com.felp.ludolog.kit.Protocol.stemOf(f.name)
        val name = Names.display(e, f)
        val rules = VideoPrep.rules(e.ludologConfig)
        e.videoCancel = false
        e.videoJob = name to 0f
        scope.launch {
            val out = java.io.File.createTempFile("ludolog-link-video", ".mp4")
            try {
                withContext(Dispatchers.IO) {
                    VideoPrep.convert(video, out, rules, progress = { p -> e.videoJob = name to p.coerceAtMost(0.99f) },
                        cancelled = { e.videoCancel })
                }
                e.videoJob = name to 1f
                val size = out.length()
                withContext(Dispatchers.IO) { e.link().putMedia("media/$system/videos/$stem.mp4", out.readBytes()) }
                notify("Video added to $name ($rules, ${com.felp.ludolog.kit.Format.size(size)}).", about = e, kind = "art")
                loadArt(e)
            } catch (x: VideoPrep.Cancelled) {
                notify("Video cancelled. Nothing was sent.")
            } catch (x: LinkError) {
                notify("Couldn't send the video: ${x.message}", error = true, about = e, kind = "art")
            } catch (x: Exception) {
                notify("Couldn't convert that video: ${x.message ?: x.javaClass.simpleName}", error = true, about = e, kind = "art")
            } finally {
                out.delete()
                e.videoJob = null
            }
        }
    }

    /**
     * Quita el arte o el video del juego, solo de la carpeta de Ludolog: lo que venga de ES-DE u
     * otra app es suyo y no se toca, y entonces se dice.
     */
    fun removeMedia(e: ConsoleEntry, f: RomFile, video: Boolean) {
        val systems = listOfNotNull(Names.game(e, f)?.systemId, f.system).distinct()
        val stem = com.felp.ludolog.kit.Protocol.stemOf(f.name)
        val name = Names.display(e, f)
        val what = if (video) "video" else "art"
        scope.launch {
            try {
                val gone = withContext(Dispatchers.IO) { e.link().removeMedia(systems, stem, video) }
                val (a, v) = withContext(Dispatchers.IO) { e.link().art() }
                e.art = a; e.video = v
                val still = if (video) Names.hasVideo(e, f) else Names.hasArt(e, f)
                notify(when {
                    gone.isNotEmpty() && !still -> "Removed the $what of $name."
                    gone.isNotEmpty() -> "Removed Ludolog's $what of $name. There's more in another app's folder (ES-DE), which Link leaves alone."
                    else -> "The $what of $name isn't in Ludolog's folder: it comes from another app (ES-DE), which Link leaves alone."
                }, error = gone.isEmpty(), about = e, kind = "art")
            } catch (x: LinkError) {
                notify("Couldn't remove the $what: ${x.message}", error = true, about = e, kind = "art")
            }
        }
    }

    /**
     * Copia ROMs de [src] a [dst], de consola a consola por el PC (ver Link.copyFrom), cada uno
     * con su .sbi si lo tiene. Su arte y su video van detras (ver copyMedia). Lo que ya este en
     * el destino no se pisa: esa copia falla diciendolo.
     */
    fun copyTo(src: ConsoleEntry, dst: ConsoleEntry, all: List<RomFile>) {
        // Las entradas de Steam y DoomForge no se copian (ver Shortcuts.isLocked).
        val files = all.filterNot(Shortcuts::isLocked)
        val withSbi = files.flatMap { f ->
            val stem = com.felp.ludolog.kit.Protocol.stemOf(f.name)
            listOf(f) + src.roms.filter {
                it.system == f.system && it.name != f.name && it.name.endsWith(".sbi", ignoreCase = true) &&
                    com.felp.ludolog.kit.Protocol.stemOf(it.name).equals(stem, ignoreCase = true)
            }
        }.distinct()
        transfers.add(withSbi.map { f ->
            Transfer(upload = true, consoleId = dst.id, consoleName = dst.name, system = f.system, name = f.name,
                local = java.io.File(f.name), size = f.size, overwrite = false, from = src.id, fromName = src.name, mtime = f.mtime)
        })
        notify("Copying ${withSbi.size} ${if (withSbi.size == 1) "file" else "files"} from ${src.name} to ${dst.name}.")
    }

    private val manifests = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<DataFile>>>()

    /**
     * El arte y el video que Ludolog tiene en [srcId] para ese ROM, a la misma ruta en [dst]: los
     * de `<datos>/media/<consola>/<tipo>/<nombre>.*`. Lo de ES-DE u otras apps no es de Ludolog y
     * no se copia. Si falla, el ROM ya esta copiado: solo se avisa.
     */
    suspend fun copyMedia(srcId: String, dst: ConsoleEntry, system: String, name: String) {
        val src = consoles.firstOrNull { it.id == srcId } ?: return
        val f = RomFile(system, name, 0, 0)
        val stem = com.felp.ludolog.kit.Protocol.stemOf(name)
        if (name.endsWith(".sbi", ignoreCase = true)) return
        val systems = listOfNotNull(Names.game(src, f)?.systemId, system).map { it.lowercase() }.toSet()
        val exts = setOf("png", "jpg", "jpeg", "webp", "mp4")
        try {
            val n = withContext(Dispatchers.IO) {
                // La lista de la consola de origen, una vez por tanda (un minuto).
                val now = System.currentTimeMillis()
                val files = manifests[srcId]?.takeIf { now - it.first < 60_000 }?.second
                    ?: src.link().manifest().also { manifests[srcId] = now to it }
                val media = files.filter { d ->
                    val p = d.path.split('/')
                    p.size == 4 && p[0] == "media" && p[1].lowercase() in systems &&
                        p[3].substringBeforeLast('.').equals(stem, ignoreCase = true) &&
                        p[3].substringAfterLast('.').lowercase() in exts
                }
                for (d in media) {
                    val tmp = java.io.File.createTempFile("ludolog-link-media", ".bin")
                    try {
                        if (src.link().ludologFile(d.path, tmp)) dst.link().putMedia(d.path, tmp.readBytes())
                    } finally {
                        tmp.delete()
                    }
                }
                media.size
            }
            if (n > 0) loadArt(dst)
        } catch (x: LinkError) {
            notify("Copied $name, but not its art: ${x.message}", error = true, about = dst, kind = "art")
        }
    }

    // ------------------------------------------------- arte entre consolas y huerfanos

    private val mediaLists = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MediaFile>>>()

    /** Los archivos de medios de la consola, guardados un minuto (ver Library). */
    private fun mediaOf(e: ConsoleEntry, fresh: Boolean = false): List<MediaFile> {
        val now = System.currentTimeMillis()
        if (!fresh) mediaLists[e.id]?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        return e.link().mediaList().also { mediaLists[e.id] = now to it }
    }

    /**
     * El arte (o el video) que [fs] tiene en [src], para [fd] en [dst], que no lo tiene: el mismo
     * juego en las dos (ver Library.diff). Se busca donde lo ve Ludolog en [src] (suyo o de ES-DE) y
     * va a la carpeta de medios de Ludolog en [dst], con el nombre del ROM de alla. El arte, un
     * archivo por tipo (caratula, captura...); el video, el primero en .mp4, que es lo que acepta.
     */
    fun copyArt(src: ConsoleEntry, fs: RomFile, dst: ConsoleEntry, fd: RomFile, video: Boolean, done: () -> Unit = {}) {
        val what = if (video) "video" else "art"
        val name = Names.display(dst, fd)
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    val keys = Library.artKeys(src, fs)
                    val found = mediaOf(src).filter { Library.mediaKey(it) in keys && it.kind.equals("videos", true) == video }
                    val pick = if (video) listOfNotNull(found.firstOrNull { it.name.endsWith(".mp4", true) })
                        else found.groupBy { it.kind.ifEmpty { "covers" }.lowercase() }.map { it.value.first() }
                    val sys = (Names.game(dst, fd)?.systemId ?: fd.system).lowercase()
                    val stem = com.felp.ludolog.kit.Protocol.stemOf(fd.name.substringAfterLast('/'))
                    for (m in pick) {
                        val kind = if (video) "videos" else m.kind.ifEmpty { "covers" }
                        val ext = m.name.substringAfterLast('.').lowercase()
                        val tmp = java.io.File.createTempFile("ludolog-link-art", ".$ext")
                        try {
                            if (src.link().mediaFile(m.path, tmp)) dst.link().putMedia("media/$sys/$kind/$stem.$ext", tmp.readBytes())
                        } finally {
                            tmp.delete()
                        }
                    }
                    pick.size
                }
                mediaLists.remove(dst.id)
                loadArt(dst)
                notify(if (n > 0) "Copied the $what of $name from ${src.name} to ${dst.name}."
                    else "${src.name} has no $what for $name that ${dst.name} can use.", error = n == 0, about = dst, kind = "art")
            } catch (x: LinkError) {
                notify("Couldn't copy the $what of $name: ${x.message}", error = true, about = dst, kind = "art")
            } finally {
                done()
            }
        }
    }

    /**
     * La caratula (o el video) de [srcId] —un device o el catalogo del PC— a los demas sitios de la
     * fila que tienen el juego. Lo que tenia cada uno se quita antes (de la carpeta de Ludolog): si no,
     * con otra extension quedaban dos. Del catalogo a un device, el video se convierte a lo que pide
     * su Ludolog, como al añadirlo a mano.
     */
    fun mediaEverywhere(r: CatalogRow, srcId: String, devices: List<ConsoleEntry>, video: Boolean, done: () -> Unit = {}) {
        val what = if (video) "video" else "cover"
        fun dev(id: String) = devices.firstOrNull { it.id == id }
        val src = r.cells[srcId] ?: return done()
        val targets = r.cells.filter { (id, c) -> id != srcId && c.rom != null && (id == CatalogRow.PC || dev(id)?.info?.ludolog != null) }
        if (targets.isEmpty()) return done()
        scope.launch {
            val temps = mutableListOf<java.io.File>()
            var ok = 0; val failed = mutableListOf<String>()
            try {
                // Lo de origen, a archivos de aqui: (tipo, archivo).
                val files: List<Pair<String, java.io.File>> = withContext(Dispatchers.IO) {
                    if (srcId == CatalogRow.PC) listOfNotNull((if (video) src.videoFile else src.artFile)?.let { (if (video) "videos" else "covers") to it })
                    else {
                        val d = dev(srcId) ?: return@withContext emptyList()
                        val fs = src.rom ?: return@withContext emptyList()
                        val keys = Library.artKeys(d, fs)
                        val found = mediaOf(d).filter { Library.mediaKey(it) in keys && it.kind.equals("videos", true) == video }
                        val pick = if (video) listOfNotNull(found.firstOrNull { it.name.endsWith(".mp4", true) })
                            else found.groupBy { it.kind.ifEmpty { "covers" }.lowercase() }.map { it.value.first() }
                        pick.mapNotNull { m ->
                            val tmp = java.io.File.createTempFile("ludolog-link-art", "." + m.name.substringAfterLast('.').lowercase())
                            temps += tmp
                            if (d.link().mediaFile(m.path, tmp)) (if (video) "videos" else m.kind.ifEmpty { "covers" }) to tmp else null
                        }
                    }
                }
                if (files.isEmpty()) { notify("Nothing to copy: no $what there.", error = true, kind = "art"); return@launch }
                for ((id, c) in targets) {
                    val f = c.rom ?: continue
                    val stem = windowsName(com.felp.ludolog.kit.Protocol.stemOf(f.name.substringAfterLast('/')))
                    try {
                        withContext(Dispatchers.IO) {
                            if (id == CatalogRow.PC) {
                                val root = PcCatalog.dir ?: throw java.io.IOException("the PC catalog isn't connected")
                                val sys = windowsName((PcCatalog.latest(root, 60_000)?.let { PcCatalog.game(it, f)?.systemId } ?: f.system).lowercase())
                                for ((kind, file) in files) {
                                    val folder = java.io.File(root, "${PcCatalog.MEDIA}/$sys/$kind")
                                    folder.mkdirs()
                                    folder.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.equals(stem, true) }.forEach { it.delete() }
                                    file.copyTo(java.io.File(folder, "$stem.${file.extension.lowercase()}"), overwrite = true)
                                }
                            } else {
                                val d = dev(id)!!
                                val sys = (Names.game(d, f)?.systemId ?: f.system).lowercase()
                                d.link().removeMedia(listOfNotNull(Names.game(d, f)?.systemId, f.system).distinct(), stem, video)
                                for ((kind, file) in files) {
                                    if (video && srcId == CatalogRow.PC) {
                                        val out = java.io.File.createTempFile("ludolog-link-video", ".mp4")
                                        temps += out
                                        VideoPrep.convert(file, out, VideoPrep.rules(d.ludologConfig))
                                        d.link().putMedia("media/$sys/videos/$stem.mp4", out.readBytes())
                                    } else d.link().putMedia("media/$sys/$kind/$stem.${file.extension.lowercase()}", file.readBytes())
                                }
                                mediaLists.remove(d.id)
                            }
                        }
                        dev(id)?.let { loadArt(it) }
                        ok++
                    } catch (x: Exception) {
                        failed += (dev(id)?.name ?: "PC catalog")
                    }
                }
                if (failed.isEmpty()) notify("Same $what on ${ok + 1} places.", kind = "art")
                else notify("Couldn't update the $what on ${failed.joinToString()}.", error = true, kind = "art")
            } catch (x: LinkError) {
                notify("Couldn't copy the $what: ${x.message}", error = true, kind = "art")
            } finally {
                temps.forEach { it.delete() }
                done()
            }
        }
    }

    // ------------------------------------------------------------- save manager

    /** Sube al cambiar los respaldos de partidas en este PC: el Save manager los vuelve a leer. */
    var savesVersion by mutableStateOf(0)

    /** Los respaldos de partidas nuevos de [e], a este PC (ver SaveBackups). */
    fun fetchSaveBackups(e: ConsoleEntry, done: () -> Unit = {}) {
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) { SaveBackups.pull(e.link(), e.id) }
                notify(if (n == 0) "No new save backups on ${e.name}." else "Copied $n save backups from ${e.name}.", about = e, kind = "saves")
                savesVersion++
            } catch (x: LinkError) {
                notify("Couldn't copy the save backups: ${x.message}", error = true, about = e, kind = "saves")
            } finally {
                done()
            }
        }
    }

    /**
     * Una version de las partidas de un juego (de un .bak de este PC) a [t]: la consola respalda
     * antes lo que tiene ("before-restore") y la rechaza si hay un juego abierto en ese emulador.
     * Solo se escriben los archivos de ese juego; los demas no se tocan.
     */
    fun restoreSave(t: ConsoleEntry, em: SaveArchive.Emulator, g: SaveArchive.SaveGame, v: SaveArchive.Version, done: () -> Unit = {}) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val l = t.link()
                    val st = l.savesState(em.pkg)
                    if (!st.optBoolean("configured")) throw LinkError(0, "${em.app} has no saves folder set on ${t.name} (Saves tab in Ludolog Link)")
                    if (!st.optBoolean("supported", true)) throw LinkError(0, "${em.app} isn't supported on ${t.name}")
                    if (st.optBoolean("inUse")) throw LinkError(0, "a game is open in ${em.app} on ${t.name}: close it first")
                    l.savesBegin(em.pkg, "before-restore")
                    java.util.zip.ZipFile(v.bak.file).use { z ->
                        for (x in v.entries) {
                            val entry = z.getEntry(x.path) ?: throw LinkError(0, "the backup lost ${x.path}")
                            l.putSaveFile(em.pkg, x.path, z.getInputStream(entry).use { it.readBytes() })
                        }
                    }
                    l.savesEnd(em.pkg)
                    // El respaldo que acaba de hacer alla, tambien aqui: para poder volver atras desde el PC.
                    runCatching { SaveBackups.pull(l, t.id) }
                }
                notify("Restored ${g.title} on ${t.name}.", about = t, kind = "saves")
                savesVersion++
            } catch (x: LinkError) {
                notify("Couldn't restore ${g.title}: ${x.message}", error = true, about = t, kind = "saves")
            } catch (x: java.io.IOException) {
                notify("Couldn't read the backup: ${x.message}", error = true, about = t, kind = "saves")
            } finally {
                done()
            }
        }
    }

    // ------------------------------------------------------------- catalogo del PC

    private fun catalogMissing() = notify("Catalog folder not found. Is its drive connected?", error = true)

    /** Un ROM de [src] al catalogo, a `<catalogo>/<consola>/`, por la cola de transferencias. */
    fun romToCatalog(src: ConsoleEntry, f: RomFile) {
        val d = PcCatalog.dir ?: return catalogMissing()
        val folder = java.io.File(d, f.system)
        if (!runCatching { folder.mkdirs() || folder.isDirectory }.getOrDefault(false)) return catalogMissing()
        download(src, listOf(f), folder)
        runCatching { CatalogInfo.fill(f, ownInfo(src, f)) }
    }

    /**
     * Un ROM del catalogo a [dst], por la cola, y con el la caratula y el video del catalogo que
     * [dst] no tenga (el video se convierte a lo que pide Ludolog alla: ver importVideo).
     */
    fun romFromCatalog(s: PcCatalog.Scan, f: RomFile, dst: ConsoleEntry, art: java.io.File?, video: java.io.File?) {
        val local = PcCatalog.file(s, f)
        if (!runCatching { local.isFile }.getOrDefault(false)) return catalogMissing()
        transfers.add(listOf(Transfer(true, dst.id, dst.name, f.system, f.name, local, local.length(), false)))
        infoFromCatalog(local, f, dst, PcCatalog.game(s, f)?.systemId ?: f.system)
        art?.let { importArt(dst, f, it) }
        video?.let { importVideo(dst, f, it) }
        notify("Sending ${f.name} from the catalog to ${dst.name}.")
    }

    /**
     * La caratula (o el video) que [fs] tiene en [src], al catalogo: `media/<consola>/covers|videos/<stem>`.
     * Falso si [src] no tiene o el catalogo no esta.
     */
    private fun mediaToCatalogNow(src: ConsoleEntry, fs: RomFile, video: Boolean, stem: String): Boolean {
        val d = PcCatalog.dir ?: return false
        val keys = Library.artKeys(src, fs)
        val found = mediaOf(src).filter { Library.mediaKey(it) in keys && it.kind.equals("videos", true) == video }
        val m = found.minByOrNull { if (it.kind.isEmpty() || it.kind.equals("covers", true)) 0 else 1 } ?: return false
        val sys = (Names.game(src, fs)?.systemId ?: fs.system).lowercase()
        val ext = m.name.substringAfterLast('.').lowercase()
        val out = java.io.File(d, "${PcCatalog.MEDIA}/$sys/${if (video) "videos" else "covers"}/$stem.$ext")
        out.parentFile.mkdirs()
        val tmp = java.io.File(out.path + ".dl")
        if (!src.link().mediaFile(m.path, tmp)) return false
        java.nio.file.Files.move(tmp.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    fun mediaToCatalog(src: ConsoleEntry, fs: RomFile, video: Boolean, stem: String, done: () -> Unit = {}) {
        val what = if (video) "video" else "cover"
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { mediaToCatalogNow(src, fs, video, stem) }
                if (ok) notify("Saved the $what of ${Names.display(src, fs)} in the catalog.", about = src, kind = "art")
                else if (PcCatalog.dir == null) catalogMissing()
                else notify("${src.name} has no $what for ${Names.display(src, fs)}.", error = true, about = src, kind = "art")
            } catch (x: LinkError) {
                notify("Couldn't bring the $what: ${x.message}", error = true, about = src, kind = "art")
            } catch (x: java.io.IOException) {
                notify("Couldn't save the $what in the catalog: ${x.message}", error = true, about = src, kind = "art")
            } finally {
                done()
            }
        }
    }

    /**
     * Las filas elegidas al catalogo: lo que le falte, de la consola que lo tenga. [withRom]: tambien
     * el ROM (por la cola); si no, solo caratula y video.
     */
    fun toCatalog(rows: List<CatalogRow>, withRom: Boolean, done: () -> Unit = {}) {
        if (PcCatalog.dir == null) { catalogMissing(); done(); return }
        fun entry(id: String) = consoles.firstOrNull { it.id == id && it.online == true }
        scope.launch {
            var roms = 0; var media = 0; var failed = 0
            try {
                // Steam y DoomForge no van al catalogo: no son archivos.
                for (r in rows.filterNot { it.locked }) {
                    val pc = r.cells[CatalogRow.PC]
                    val have = r.cells.filterKeys { it != CatalogRow.PC }.mapNotNull { (id, c) -> entry(id)?.let { e -> c.rom?.let { Triple(e, it, c) } } }
                    if (have.isEmpty()) continue
                    if (withRom && pc?.rom == null) {
                        val (e, f, _) = have.maxBy { (_, _, c) -> (if (c.art) 1 else 0) + (if (c.video) 1 else 0) }
                        romToCatalog(e, f); roms++
                    }
                    // Lo corregido del juego, del primer device que lo tenga.
                    (pc?.rom ?: have.first().second).let { cf ->
                        have.map { (e, f, _) -> ownInfo(e, f) }.firstOrNull { it.isNotEmpty() }?.let { runCatching { CatalogInfo.fill(cf, it) } }
                    }
                    val stem = (pc?.rom ?: have.first().second).let { com.felp.ludolog.kit.Protocol.stemOf(it.name.substringAfterLast('/')) }
                    for (video in listOf(false, true)) {
                        if (if (video) pc?.video == true else pc?.art == true) continue
                        val src = have.firstOrNull { (_, _, c) -> if (video) c.video else c.art } ?: continue
                        val ok = runCatching { withContext(Dispatchers.IO) { mediaToCatalogNow(src.first, src.second, video, stem) } }.getOrDefault(false)
                        if (ok) media++ else failed++
                    }
                }
                notify(buildString {
                    append(if (withRom) "To the catalog: $roms ROMs queued, $media covers and videos saved." else "Saved $media covers and videos in the catalog.")
                    if (failed > 0) append(" $failed couldn't be brought.")
                }, error = failed > 0, about = null, kind = "roms")
            } finally {
                done()
            }
        }
    }

    /** El arte y los videos de [e] que no son de ningun ROM (ver Library.orphans), mirados ahora. */
    suspend fun findOrphans(e: ConsoleEntry): List<MediaFile> = withContext(Dispatchers.IO) {
        val roms = runCatching { e.link().roms() }.getOrNull()
        if (roms.isNullOrEmpty()) {
            withContext(Dispatchers.Main) { notify("Couldn't read ${e.name}'s ROM list.", error = true) }
            return@withContext emptyList()
        }
        withContext(Dispatchers.Main) { e.roms = roms }
        Library.orphans(e, mediaOf(e, fresh = true))
    }

    /** Borra esos archivos de medios de [e]. Devuelve cuantos se borraron. */
    suspend fun deleteMedia(e: ConsoleEntry, files: List<MediaFile>): Int {
        val gone = try {
            withContext(Dispatchers.IO) { e.link().deleteMedia(files.map { it.path }) }
        } catch (x: LinkError) {
            notify("Couldn't delete: ${x.message}", error = true, about = e, kind = "art")
            return 0
        }
        mediaLists.remove(e.id)
        loadArt(e)
        notify("Deleted ${gone.size} of ${files.size} ${if (files.size == 1) "file" else "files"} from ${e.name}.",
            error = gone.size < files.size, about = e, kind = "art")
        return gone.size
    }

    /** Compartiendo el Companion entre las consolas conectadas. Ver syncCompanions. */
    var companionSyncing by mutableStateOf(false)

    /** Las consolas con las que se puede compartir el Companion ahora mismo. */
    val shareable: List<ConsoleEntry>
        get() = consoles.filter { it.paired && it.online == true && it.info?.ludolog != null }

    /**
     * Que cada consola conectada tenga el cuaderno al dia de las demas (ver CompanionSync): las
     * copias del PC al dia, el plan, los envios, y otra vez las copias para verlo aqui.
     */
    fun syncCompanions() {
        if (companionSyncing) return
        val list = shareable
        if (list.size < 2) { notify("Connect two devices with Ludolog first."); return }
        val old = list.filter { it.info?.ludolog?.knowsLogbook != true }
        if (old.isNotEmpty()) {
            notify("Update Ludolog Link on ${old.joinToString { it.name }}.", error = true)
            return
        }
        companionSyncing = true
        scope.launch {
            try {
                val sent = withContext(Dispatchers.IO) {
                    for (e in list) Mirror.sync(e.link(), e.id, essentialOnly = true)
                    val owners = list.mapNotNull { e -> e.info?.ludolog?.logbook?.let { it to e } }.toMap()
                    val plan = CompanionSync.plan(list, owners)
                    for (s in plan) s.to.link().putLogbook(s.name, s.from.file.readBytes())
                    for (e in plan.map { it.to }.distinct()) Mirror.sync(e.link(), e.id, essentialOnly = true)
                    plan
                }
                for (e in list) { e.syncedAt = System.currentTimeMillis(); readCompanion(e) }
                notify(if (sent.isEmpty()) "Companions are already in sync across ${list.size} devices."
                else "Companions synced: " + sent.groupBy { it.to }.entries.joinToString("; ") { (to, l) ->
                    "${to.name} got " + l.joinToString { s -> "${s.name.removeSuffix(".db")} (${s.from.version.sessions} sessions)" }
                }, about = null, kind = "companion")
            } catch (x: LinkError) {
                notify("Couldn't sync the companions: ${x.message}", error = true, about = null, kind = "companion")
            } finally {
                companionSyncing = false
            }
        }
    }

    /**
     * Una pasada del scraper de Ludolog en el PC sobre esos ROMs de [e] (ver Scraper.kt): busca,
     * guarda en el PC y manda a la consola lo que le falta —o todo, en [ScrapeMode.ALL]—.
     */
    fun scrape(e: ConsoleEntry, roms: List<RomFile>, mode: ScrapeMode) {
        if (e.scrapeState != null) { notify("${e.name} is already scraping.", error = true); return }
        if (e.info?.ludolog == null) { notify("Ludolog isn't installed on ${e.name}.", error = true); return }
        val games = PcScraper.games(e, roms)
        if (games.isEmpty()) { notify("Nothing to scrape: no games among those files.", error = true); return }
        val state = ScrapeState(mode, games.size)
        e.scrapeState = state
        state.job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    PcScraper.point(e)
                    val (scraper, prefs) = PcScraper.scraper(e, mode)
                    val gs = games.map { it.second }
                    if (mode == ScrapeMode.ALL) for (g in gs) { scraper.destinationFor(g).delete(); scraper.videoFor(g).delete() }
                    val byPath = games.associate { (f, g) -> g.path to f }
                    val report = scraper.run(gs, PcScraper.existing(e, mode, byPath)) { p -> state.progress = p }
                    // Lo que hay que mandar: lo bajado (ahora o en otra pasada) que la consola no tiene.
                    val send = buildList {
                        for ((f, g) in games) {
                            if (mode != ScrapeMode.VIDEOS) scraper.destinationFor(g).takeIf { it.isFile && (mode == ScrapeMode.ALL || !Names.hasArt(e, f)) }
                                ?.let { add("media/${g.systemId}/covers/${it.name}" to it) }
                            if (mode != ScrapeMode.COVERS && prefs.anyThemePlaysVideo)
                                scraper.videoFor(g).takeIf { it.isFile && it.length() > 0 && (mode == ScrapeMode.ALL || !Names.hasVideo(e, f)) }
                                    ?.let { add("media/${g.systemId}/videos/${it.name}" to it) }
                        }
                    }
                    var sent = 0
                    for ((path, file) in send) {
                        state.sending = sent to send.size
                        e.link().putMedia(path, file.readBytes())
                        sent++
                    }
                    ScrapeResult(mode, report, sent)
                }
                e.scrapeResult = result
                notify("Scrape on ${e.name}: ${result.report.summary()} · sent ${result.sent} " +
                    if (result.sent == 1) "file" else "files", about = e, kind = "art")
                loadArt(e)
            } catch (x: kotlinx.coroutines.CancellationException) {
                notify("Scrape cancelled. Results kept for next time.", about = e, kind = "art")
            } catch (x: LinkError) {
                notify("Couldn't send the art to ${e.name}: ${x.message}", error = true, about = e, kind = "art")
            } catch (x: Exception) {
                notify("The scrape stopped: ${x.message ?: x.javaClass.simpleName}", error = true, about = e, kind = "art")
            } finally {
                e.scrapeState = null
            }
        }
    }

    fun cancelScrape(e: ConsoleEntry) { e.scrapeState?.job?.cancel() }

    /**
     * La caratula (o el video) de un juego, buscada en internet para el catalogo del PC. El scraper
     * de Ludolog necesita lo de un device (sus fichas, su catalogo): el que tiene el juego, o el
     * primero con Ludolog. Lo bajado se queda en el PC, en `media/` del catalogo, y no va a ningun device.
     */
    fun scrapeToCatalog(r: CatalogRow, devices: List<ConsoleEntry>, scan: PcCatalog.Scan, video: Boolean, done: () -> Unit = {}) {
        val root = PcCatalog.dir ?: return catalogMissing()
        val ready = devices.filter { it.info?.ludolog != null && it.scrapeState == null }
        val ctx = ready.firstOrNull { r.cells[it.id]?.rom != null } ?: ready.firstOrNull()
            ?: return notify("Connect a device with Ludolog to scrape.", error = true)
        val pcRom = r.cells[CatalogRow.PC]?.rom
        val devRom = r.cells[ctx.id]?.rom
        val game = devRom?.let { Names.game(ctx, it) } ?: pcRom?.let { PcCatalog.game(scan, it) }
            ?: devices.firstNotNullOfOrNull { d -> r.cells[d.id]?.rom?.let { Names.game(d, it) } }
            ?: return notify("Couldn't tell which game this is.", error = true, kind = "art")
        val named = pcRom ?: devRom ?: devices.firstNotNullOfOrNull { r.cells[it.id]?.rom } ?: return
        val stem = windowsName(com.felp.ludolog.kit.Protocol.stemOf(named.name.substringAfterLast('/')))
        val sys = windowsName(game.systemId.lowercase())
        val mode = if (video) ScrapeMode.VIDEOS else ScrapeMode.COVERS
        val state = ScrapeState(mode, 1)
        ctx.scrapeState = state
        state.job = scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    PcScraper.point(ctx)
                    val (scraper, _) = PcScraper.scraper(ctx, mode)
                    // Solo lo que se pide: lo otro cuenta como puesto.
                    scraper.run(listOf(game), com.felp.frontcomp.ArtIndex({ video }, { !video })) { p -> state.progress = p }
                    val got = (if (video) scraper.videoFor(game) else scraper.destinationFor(game)).takeIf { it.isFile && it.length() > 0 }
                        ?: return@withContext false
                    val folder = java.io.File(root, "${PcCatalog.MEDIA}/$sys/${if (video) "videos" else "covers"}")
                    folder.mkdirs()
                    folder.listFiles().orEmpty().filter { it.isFile && it.nameWithoutExtension.equals(stem, true) }.forEach { it.delete() }
                    got.copyTo(java.io.File(folder, "$stem.${got.extension.lowercase()}"), overwrite = true)
                    true
                }
                val what = if (video) "video" else "cover"
                notify(if (found) "Found a $what for ${r.title}: saved in the PC catalog." else "No $what found for ${r.title}.",
                    error = !found, kind = "art")
            } catch (x: kotlinx.coroutines.CancellationException) {
                notify("Scrape cancelled.", kind = "art")
            } catch (x: Exception) {
                notify("The scrape stopped: ${x.message ?: x.javaClass.simpleName}", error = true, kind = "art")
            } finally {
                ctx.scrapeState = null
                done()
            }
        }
    }

    private val reading = kotlinx.coroutines.sync.Mutex()

    private suspend fun readCompanion(e: ConsoleEntry) {
        val dir = Mirror.dir(e.id)
        if (!java.io.File(dir, "companion").isDirectory) return
        reading.lock()
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = ConfigXml.read(java.io.File(dir, "config.xml"))
                    val book = CompanionReader.read(dir, e.info?.model ?: e.model, config["log.console.id"] as? String, config)
                    // El catalogo que acaba de cargar es el de este device (lo pone read): se guarda con el.
                    Triple(config, book, com.felp.frontcomp.CatalogLoader.current)
                }
            }
            result.onSuccess { (config, b, cat) ->
                e.ludologConfig = config; e.book = b
                e.catalog = cat
                e.customSystems = cat?.systems.orEmpty().filter { it.id !in CompanionReader.builtInIds }
            }
                .onFailure { e.companionNote = "couldn't read the logbook: ${it.message}" }
            // La primera lectura de la consola que se mira la deja apuntada (las demas lecturas vuelven
            // a la que estaba: ver CompanionReader.read).
            if (e.id == selectedId) CompanionReader.point(Mirror.dir(e.id), e.info?.model ?: e.model, e.ludologConfig["log.console.id"] as? String)
        } finally {
            reading.unlock()
        }
    }

    fun select(e: ConsoleEntry) {
        // Las pestañas del Companion abren el cuaderno de la consola que se mira.
        if (e.book != null) CompanionReader.point(Mirror.dir(e.id), e.info?.model ?: e.model, e.ludologConfig["log.console.id"] as? String)
        selectedId = e.id
        if (e.paired && e.online != false && e.info == null && !e.loading) load(e)
    }

    // ------------------------------------------------------------------ datos

    fun load(e: ConsoleEntry) {
        if (!e.paired) return
        e.loading = true
        e.error = null
        scope.launch {
            try {
                val l = e.link()
                val info = withContext(Dispatchers.IO) { l.info() }
                e.info = info
                e.online = true
                claimOnce(e)
                info.ludolog?.theme?.let { fetchFonts(e, it) }
                if (info.ludolog != null) { refreshCompanion(e); loadArt(e, changed = false) }
                if (info.name.isNotBlank() && info.name != e.name) {
                    e.name = info.name
                    Config.remember(e.toKnown())
                }
                if (info.romsRoot == null) {
                    e.systems = emptyList()
                    e.roms = emptyList()
                    e.error = if (!info.storageAccess) "Ludolog Link doesn't have access to the device's files."
                    else "The device has no ROM folder: choose one in Ludolog Link."
                } else {
                    val (systems, roms) = withContext(Dispatchers.IO) {
                        coroutineScope {
                            val s = async { l.systems() }
                            val r = async { l.roms() }
                            s.await() to r.await()
                        }
                    }
                    e.systems = systems
                    e.roms = roms
                }
            } catch (x: LinkError) {
                failed(e, x)
            } finally {
                e.loading = false
            }
        }
    }

    /** Tras subir, borrar o renombrar: lista y espacio libre, sin volver a leer los sistemas. */
    fun refreshFiles(e: ConsoleEntry) {
        scope.launch {
            try {
                val l = e.link()
                val (info, roms) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val i = async { l.info() }
                        val r = async { l.roms() }
                        i.await() to r.await()
                    }
                }
                e.info = info
                e.roms = roms
            } catch (x: LinkError) {
                failed(e, x)
            }
        }
    }

    fun failed(e: ConsoleEntry, x: LinkError) {
        when (x.status) {
            401 -> {
                // La consola olvido este PC (o se reinstalo la app): hay que volver a emparejar.
                e.token = null
                Config.forget(e.id)
                e.error = "The device no longer recognizes this PC. Pair it again."
            }
            0 -> {
                e.online = false
                e.error = "Not responding. Is Ludolog Link open on the device?"
            }
            else -> e.error = x.message
        }
    }

    // ----------------------------------------------------------- emparejado

    /** Si la version [v] («0.5.2») es anterior a [than]. Una que no se entiende, no. */
    private fun older(v: String, than: String): Boolean {
        val a = v.split('.').map { it.toIntOrNull() ?: return false }
        val b = than.split('.').map { it.toInt() }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    /** Las consolas que ya saben el id de este PC, en esta sesion. */
    private val claimed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Le dice el id fijo de este PC a una consola emparejada antes de la 0.5.3, una vez por sesion y
     * en la direccion de siempre (donde ya se le habla con la clave). Desde entonces la consola puede
     * probar quien es en una IP nueva, y olvida los emparejamientos viejos de este PC. Una consola con
     * Link anterior no lo entiende (404): se vuelve a intentar en la siguiente sesion.
     */
    private fun claimOnce(e: ConsoleEntry) {
        if (!e.paired || !claimed.add(e.id)) return
        scope.launch(Dispatchers.IO) {
            runCatching { e.link().claim(Config.pcId) }
                .onSuccess { n -> if (n > 0) notify("${e.name}: forgot $n older pairing${if (n == 1) "" else "s"} of this PC", about = e, kind = "pairing") }
                .onFailure { claimed.remove(e.id) }
        }
    }

    fun startPairing(e: ConsoleEntry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { Link(e.host, e.port, null).pairRequest(Config.pcName) }
                pairing = e
            } catch (x: LinkError) {
                notify("Couldn't request a code: ${x.message}", error = true, about = e, kind = "pairing")
            }
        }
    }

    /** onError recibe el motivo si el codigo no vale. */
    fun confirmPairing(e: ConsoleEntry, code: String, onError: (String) -> Unit) {
        scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { Link(e.host, e.port, null).pairConfirm(code, Config.pcName, Config.pcId) }
                claimed += e.id
                e.token = t
                e.online = true
                Config.remember(e.toKnown())
                pairing = null
                notify("Paired with ${e.name}", about = e, kind = "pairing")
                replaceOld(e)
                load(e)
            } catch (x: LinkError) {
                onError(x.message ?: "error")
            }
        }
    }

    /**
     * La misma consola de antes de reinstalar Link. Al reinstalarlo cambia de identidad, y el PC la
     * ensenaba dos veces: la vieja, «not visible», y la nueva, «not paired» (07-10-2026). Al emparejar
     * la nueva, una emparejada que no contesta, del mismo modelo y en la misma direccion, se da por
     * ella: se olvida, y sus respaldos pasan a la nueva si esta todavia no tiene.
     */
    private fun replaceOld(e: ConsoleEntry) {
        val old = consoles.filter { it !== e && it.id != e.id && it.paired && it.online != true && it.model == e.model && it.host == e.host }
        for (o in old) {
            val from = Config.consoleDir(o.id)
            val to = Config.consoleDir(e.id)
            if (from.isDirectory && !to.exists()) runCatching { from.renameTo(to) }
            Config.forget(o.id)
            consoles.remove(o)
            if (selectedId == o.id) selectedId = e.id
            notify("The old ${o.name.ifBlank { o.model }} was this device before Link was reinstalled: replaced.", about = e, kind = "pairing")
        }
    }

    fun forget(e: ConsoleEntry) {
        Config.forget(e.id)
        e.token = null
        e.info = null
        e.systems = emptyList()
        e.roms = emptyList()
        e.error = null
        if (e.online != true) {
            consoles.remove(e)
            if (selectedId == e.id) selectedId = consoles.firstOrNull()?.id
        }
        notify("Device forgotten. Remove ${Config.pcName} on the device too.", about = e, kind = "pairing")
    }

    // ------------------------------------------------------------ operaciones

    fun rename(e: ConsoleEntry, f: RomFile, newBase: String) {
        val dir = f.name.substringBeforeLast('/', "")
        val to = if (dir.isEmpty()) newBase else "$dir/$newBase"
        scope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { e.link().rename(f.system, f.name, to) }
                val extra = renamed.size - 1
                notify("Renamed to $newBase" + if (extra > 0) " (and its .sbi)" else "", about = e, kind = "roms")
                // Ludolog mueve lo de ese juego a la ruta nueva (su puente); la copia del PC, igual,
                // para no enseñar el juego sin su nombre ni su arte hasta la siguiente copia.
                e.info?.romsRoot?.let { root -> movedHere(e, "$root/${f.system}/${f.name}", "$root/${f.system}/$to") }
                refreshFiles(e)
                kotlinx.coroutines.delay(2_000)
                loadArt(e)
            } catch (x: LinkError) {
                if (x.status == 0 || x.status == 401) failed(e, x)
                notify("Couldn't rename: ${x.message}", error = true, about = e, kind = "roms")
            }
        }
    }

    /** Las claves por juego que Ludolog mueve al renombrar (Prefs.GAME_KEYS), movidas tambien aqui. */
    private fun movedHere(e: ConsoleEntry, from: String, to: String) {
        val keys = listOf("play.at.", "play.count.", "fav.", "done.", "disc.", "emu.game.", "core.game.", "name.game.", "desc.game.")
        val config = e.ludologConfig.toMutableMap()
        for (p in keys) {
            if ("$p$from" in config) config["$p$to"] = config.remove("$p$from")
            if ("$p$from" in e.pending) e.pending["$p$to"] = e.pending.remove("$p$from")
        }
        e.ludologConfig = config
    }

    fun delete(e: ConsoleEntry, all: List<RomFile>) {
        // Ni se borran (ver Shortcuts.isLocked).
        val files = all.filterNot(Shortcuts::isLocked)
        scope.launch {
            var ok = 0
            var extra = 0
            val failedNames = mutableListOf<String>()
            for (f in files) {
                try {
                    val gone = withContext(Dispatchers.IO) { e.link().delete(f.system, f.name) }
                    ok++
                    extra += (gone.size - 1).coerceAtLeast(0)
                } catch (x: LinkError) {
                    // Un .sbi seleccionado junto a su juego ya se fue con el.
                    if (x.status == 404 && Protocol.extOf(f.name) == ".sbi") continue
                    failedNames += "${f.name}: ${x.message}"
                    if (x.status == 0 || x.status == 401) {
                        failed(e, x)
                        break
                    }
                }
            }
            val sbi = if (extra > 0) " (and $extra .sbi)" else ""
            if (failedNames.isEmpty()) notify(if (ok == 1) "Deleted$sbi" else "Deleted $ok files$sbi", about = e, kind = "roms")
            else notify("Deleted $ok; ${failedNames.size} failed. ${failedNames.first()}", error = true, about = e, kind = "roms")
            refreshFiles(e)
        }
    }

    /** Descarga a una carpeta del PC. Lo que ya esta alli igual no se vuelve a bajar. */
    fun download(e: ConsoleEntry, all: List<RomFile>, dir: File) {
        val files = all.filterNot(Shortcuts::isLocked)
        var skipped = 0
        // Lo ya reservado (en este lote y en lo que sigue en la cola): Tetris de dos consolas, o
        // Game y game en Windows, iban al mismo archivo y uno pisaba al otro.
        val taken = transfers.items.filter { !it.upload && !it.finished }.mapTo(HashSet()) { it.local.path.lowercase() }
        var unsafe = 0
        val jobs = files.mapNotNull { f ->
            var dest = runCatching { inside(dir, File(dir, f.name.split('/').joinToString(File.separator) { windowsName(it) })) }
                .getOrElse { unsafe++; return@mapNotNull null }
            dest.parentFile?.mkdirs()
            if (dest.path.lowercase() !in taken && dest.isFile && dest.length() == f.size) {
                skipped++
                return@mapNotNull null
            }
            if (dest.exists() || dest.path.lowercase() in taken) dest = freeName(dest, taken)
            taken += dest.path.lowercase()
            Transfer(false, e.id, e.name, f.system, f.name, dest, f.size, false, mtime = f.mtime)
        }
        transfers.add(jobs)
        if (skipped > 0) notify("$skipped already there: skipped.")
        if (unsafe > 0) notify("$unsafe with a name that can't be saved here: skipped.", error = true)
    }

    private fun freeName(f: File, taken: Set<String> = emptySet()): File {
        val stem = Protocol.stemOf(f.name)
        val ext = Protocol.extOf(f.name).let { if (it.isEmpty()) "" else f.name.takeLast(it.length) }
        var i = 2
        while (true) {
            val c = File(f.parentFile, "$stem ($i)$ext")
            if (!c.exists() && !File(c.path + ".part").exists() && c.path.lowercase() !in taken) return c
            i++
        }
    }

    /**
     * Un nombre que Windows acepta, como UN solo componente de ruta: sin `<>:"|?*`, sin separadores
     * (`/` ni `\`) y sin puntos o espacios al final, asi que nunca «..». Lo manda la consola: con
     * una barra invertida, «..\..\algo» salia de la carpeta elegida (revision de seguridad, 07-10-2026).
     */
    internal fun windowsName(s: String): String =
        s.replace(Regex("""[<>:"|?*\\/\x00-\x1f]"""), "_").trimEnd('.', ' ').ifEmpty { "_" }

    /** [child] si de verdad queda dentro de [base]; si no, error: su nombre venia de fuera. */
    private fun inside(base: File, child: File): File {
        if (!child.canonicalPath.startsWith(base.canonicalPath + File.separator, ignoreCase = true))
            throw java.io.IOException("unsafe file name: ${child.name}")
        return child
    }
}

// ------------------------------------------------------------- transferencias

/** WAITING: se corto (Wi-Fi, consola dormida) y se vuelve a intentar sola en un rato. */
enum class TState { QUEUED, RUNNING, WAITING, DONE, FAILED, CANCELLED }

class Transfer(
    val upload: Boolean,
    val consoleId: String,
    val consoleName: String,
    val system: String,
    val name: String,
    val local: File,
    val size: Long,
    val overwrite: Boolean,
    /** De que consola viene, si es una copia de consola a consola (y su nombre, para enseñarlo). */
    val from: String? = null,
    val fromName: String? = null,
    /** La fecha del archivo en el device de origen (copias y descargas): para retomar solo el mismo. */
    val mtime: Long = 0L,
) {
    var state by mutableStateOf(TState.QUEUED)
    var done by mutableStateOf(0L)
    /** Bytes por segundo, suavizado. */
    var speed by mutableStateOf(0.0)
    var error by mutableStateOf<String?>(null)
    @Volatile var cancel = false
    /** Los intentos seguidos que se cortaron, y desde cuando: para espaciar los reintentos y rendirse. */
    var tries = 0
    var firstFail = 0L

    val finished get() = state == TState.DONE || state == TState.CANCELLED || state == TState.FAILED
}

/** Una transferencia a la vez: con Wi-Fi, en paralelo no va mas rapido y lia el disco de la consola. */
class Transfers(private val app: AppState, private val scope: CoroutineScope) {
    val items = mutableStateListOf<Transfer>()
    private var worker: Job? = null

    val active get() = items.any { it.state == TState.QUEUED || it.state == TState.RUNNING || it.state == TState.WAITING }

    fun add(list: List<Transfer>) {
        if (list.isEmpty()) return
        items += list
        kick()
    }

    private fun kick() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val t = items.firstOrNull { it.state == TState.QUEUED } ?: break
                run(t)
            }
        }
    }

    /** Una transferencia, y al acabar (bien o mal) su linea en el registro de esa consola. */
    private suspend fun run(t: Transfer) {
        try {
            runOnce(t)
        } finally {
            if (t.state == TState.DONE || t.state == TState.FAILED) {
                val what = "${t.system}/${t.name}"
                val text = when {
                    t.from != null -> "Copied $what from ${t.fromName ?: "another device"} to ${t.consoleName}"
                    t.upload -> "Uploaded $what (${com.felp.ludolog.kit.Format.size(t.size)})"
                    else -> "Downloaded $what to ${t.local.parentFile?.path ?: "this PC"}"
                }
                val failed = t.state == TState.FAILED
                PcLog.add(t.consoleId, "roms", if (failed) "$text: failed, ${t.error ?: "unknown error"}" else text, failed)
            }
        }
    }

    private suspend fun runOnce(t: Transfer) {
        val e = app.consoles.firstOrNull { it.id == t.consoleId }
        if (e == null || !e.paired) {
            t.state = TState.FAILED
            t.error = "the device isn't paired"
            return
        }
        t.state = TState.RUNNING
        t.cancel = false
        t.error = null
        t.speed = 0.0
        val meter = Meter(t)
        try {
            withContext(Dispatchers.IO) {
                val l = e.link()
                if (t.from != null) {
                    val src = app.consoles.firstOrNull { it.id == t.from && it.paired }
                        ?: throw LinkError(0, "${t.fromName ?: "the source device"} isn't connected")
                    l.copyFrom(src.link(), t.system, t.name, t.size, t.overwrite, meter::update, { t.cancel }, srcKey = "${t.from}-${t.mtime}")
                } else if (t.upload) {
                    l.upload(t.local, t.system, t.name, t.overwrite, meter::update) { t.cancel }
                } else {
                    l.download(t.system, t.name, t.local, { d, _ -> meter.update(d) }, { t.cancel },
                        key = "${t.consoleId}/${t.system}/${t.name}|${t.size}|${t.mtime}")
                }
            }
            t.done = t.size
            t.state = TState.DONE
            // Si se habia dado por perdida (un corte, dormida), contesto: a la vista otra vez.
            if (t.tries > 0 || e.online != true) app.load(e)
            t.tries = 0
            // Una copia de consola a consola se lleva tambien su arte y su video.
            if (t.from != null) app.copyMedia(t.from, e, t.system, t.name)
        } catch (x: kotlinx.coroutines.CancellationException) {
            throw x
        } catch (x: Exception) {
            // Lo que no es un LinkError (un nombre que Windows no admite, un disco lleno...): fallida, con
            // el motivo. Antes se escapaba y la transferencia se quedaba "en marcha" para siempre.
            if (x !is LinkError) { t.state = TState.FAILED; t.error = x.message ?: x.javaClass.simpleName; return }
            when {
                t.cancel -> t.state = TState.CANCELLED
                // Ya esta alla al reintentar: el intento anterior llego entero y solo se perdio la respuesta.
                x.status == 409 && t.tries > 0 && (t.upload || t.from != null) -> {
                    t.done = t.size; t.state = TState.DONE; t.error = null; t.tries = 0
                }
                // Un corte (Wi-Fi, consola dormida o fuera de alcance): se reintenta sola, cada vez mas
                // espaciado, durante unas horas. Lo ya enviado no se repite (ver Link.upload/copyFrom).
                retryable(x) && (t.firstFail == 0L || System.currentTimeMillis() - t.firstFail < GIVE_UP_MS) -> {
                    if (t.firstFail == 0L) t.firstFail = System.currentTimeMillis()
                    t.tries++
                    val wait = minOf(5_000L shl minOf(t.tries - 1, 5), 120_000L)
                    t.state = TState.WAITING
                    t.error = "connection lost: trying again in ${wait / 1000} s"
                    // Tras varios cortes, a buscar de nuevo: tras dormir, la consola puede haber cambiado de IP.
                    if (t.tries % 3 == 0) app.search()
                    scope.launch {
                        delay(wait)
                        if (t.state == TState.WAITING) { t.state = TState.QUEUED; kick() }
                    }
                }
                else -> {
                    t.state = TState.FAILED
                    t.error = x.message
                    if (x.status == 401) app.failed(e, x)
                }
            }
        }
        // Al acabar la tanda de subidas a esa consola, ver lo que hay ahora.
        if (t.upload && items.none { it.upload && it.consoleId == t.consoleId && it.state == TState.QUEUED }) {
            app.refreshFiles(e)
        }
        trimFinished()
    }

    /**
     * Lo terminado bien (hecho o cancelado) no se acumula: quedan los ultimos [KEEP_DONE]. Con cientos
     * en la lista, el panel la recorria entera varias veces por redibujo, y se redibuja varias veces
     * por segundo mientras algo se transfiere. Los fallidos se quedan: son los que hay que mirar.
     */
    private fun trimFinished() {
        val finished = items.filter { it.state == TState.DONE || it.state == TState.CANCELLED }
        if (finished.size <= KEEP_DONE) return
        val gone = finished.dropLast(KEEP_DONE).toHashSet()
        items.removeAll { it in gone }
    }

    fun cancel(t: Transfer) {
        if (t.state == TState.QUEUED || t.state == TState.WAITING) t.state = TState.CANCELLED else t.cancel = true
    }

    /** Un corte de conexion o un fallo de la consola al escribir: vale reintentar. No un rechazo (espacio, ya existe...). */
    private fun retryable(x: LinkError) = x.status == 0 || x.status == 500 || x.status == 502 || x.status == 503

    private companion object {
        /** Cuanto se sigue reintentando una transferencia cortada: lo que dura una consola dormida un rato. */
        const val GIVE_UP_MS = 3 * 3_600_000L

        /** Cuantas terminadas bien se siguen viendo en el panel (ver trimFinished). */
        const val KEEP_DONE = 30
    }

    fun cancelAll() = items.filter { !it.finished }.forEach(::cancel)

    fun retry(t: Transfer) {
        t.state = TState.QUEUED
        t.error = null
        t.tries = 0
        t.firstFail = 0L
        kick()
    }

    fun clearFinished() {
        items.removeAll { it.state == TState.DONE || it.state == TState.CANCELLED }
    }

    /** Progreso y velocidad sin inundar la interfaz: como mucho ~6 veces por segundo. */
    private class Meter(val t: Transfer) {
        private var lastT = 0L
        private var lastDone = -1L

        fun update(done: Long) {
            val now = System.nanoTime()
            if (lastDone < 0) {
                lastT = now
                lastDone = done
                t.done = done
                return
            }
            val dt = (now - lastT) / 1e9
            if (dt < 0.15) return
            val inst = (done - lastDone) / dt
            t.speed = if (t.speed == 0.0) inst else t.speed * 0.7 + inst * 0.3
            t.done = done
            lastT = now
            lastDone = done
        }
    }
}
