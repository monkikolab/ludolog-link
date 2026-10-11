package com.felp.ludologlink.pc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.Dossiers
import com.felp.frontcomp.MenuDim
import com.felp.frontcomp.MenuInk
import com.felp.frontcomp.SystemDef
import com.felp.ludolog.kit.Protocol
import com.felp.ludolog.kit.ui.LButton
import com.felp.ludolog.kit.ui.LTextButton
import com.felp.ludolog.kit.ui.Look
import org.json.JSONObject
import java.io.File

// GameInfo.kt: nombre, genero y descripcion de un juego, para que digan lo mismo en todos los
// sitios. GameInfo lee lo de cada device (de su copia en el PC), CatalogInfo lo del catalogo del PC
// (`<catalogo>/media/info.json`), y GameInfoSection es la seccion Info del panel de Games, con
// MixedDot (el punto amarillo de "no dicen lo mismo") y Variants, que tambien usa ConsolesView.
// Guardar llama a AppState.saveGameInfo, que lo manda con POST /meta/edit.

/**
 * Lo corregido a mano de un juego en cada device: su nombre, su genero y su descripcion (lo que
 * Ludolog Link lleva de una consola a otra, ver MetaEdits en la consola). Vacio: lo de siempre (el
 * nombre del archivo o el del catalogo de juegos de Ludolog).
 *
 * El nombre y la descripcion, de la copia del config de Ludolog; el genero, de su ficha en la copia
 * de la carpeta de datos. Lo que se manda desde aqui se ve ya, antes de que vuelva la copia.
 */
internal object GameInfo {
    val FIELDS = listOf("name" to "Name", "genre" to "Genre", "desc" to "Description")

    /** Lo mandado hace poco: device|ruta|campo -> (valor, cuando). Vale hasta que la copia sea mas nueva. */
    private val sent = mutableStateMapOf<String, Pair<String, Long>>()
    private val dossiers = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Map<String, com.felp.frontcomp.Dossier>>>()

    /** Las fichas de una consola de [d], de su copia en el PC (ruta -> ficha), con la fecha del archivo. */
    private fun dossierMap(d: ConsoleEntry, system: String): Pair<Long, Map<String, com.felp.frontcomp.Dossier>> {
        val f = File(Mirror.dir(d.id), "dossiers/$system.tsv")
        val m = f.lastModified()
        dossiers["${d.id}|$system"]?.takeIf { it.first == m }?.let { return it }
        val map = if (!f.isFile) emptyMap() else runCatching { Dossiers.parse(f.readLines().asSequence()) }.getOrDefault(emptyMap())
        return (m to map).also { dossiers["${d.id}|$system"] = it }
    }

    private fun genres(d: ConsoleEntry, system: String): Pair<Long, Map<String, String>> {
        val (m, all) = dossierMap(d, system)
        return m to all.mapNotNull { (p, x) -> x.fields["my.genre"]?.let { p to it } }.toMap()
    }

    /**
     * Lo de siempre en [d] para [field], sin lo puesto a mano: el nombre del catalogo (o el del
     * archivo), el genero del catalogo o su sinopsis. Como lo ve Ludolog alli.
     */
    fun default(d: ConsoleEntry, f: RomFile, field: String): String? {
        val g = Names.game(d, f)
        val p = Names.path(d, f) ?: return null
        val x = g?.systemId?.let { dossierMap(d, it).second[p] }
        return when (field) {
            "name" -> x?.takeIf { it.exact }?.name?.let(com.felp.frontcomp.GameDb::base)?.takeIf { it.isNotBlank() }
                ?: g?.title ?: com.felp.ludolog.kit.Protocol.stemOf(f.name.substringAfterLast('/'))
            "genre" -> x?.catalogGenres?.let(com.felp.frontcomp.Genres::canon)?.firstOrNull()
            "desc" -> x?.fields?.get("syn")
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Para comparar: el genero, el primero que conoce Ludolog; lo demas, sin espacios de sobra. */
    fun norm(field: String, v: String): String =
        if (field == "genre") com.felp.frontcomp.Genres.canon(listOf(v)).firstOrNull() ?: v.trim() else v.trim()

    /** Lo puesto a mano en [d] para [field], o nulo. */
    fun own(d: ConsoleEntry, f: RomFile, field: String): String? {
        val p = Names.path(d, f) ?: return null
        val recent = sent["${d.id}|$p|$field"]
        val v = when (field) {
            "name" -> recent?.first ?: Names.custom(d, f)
            "desc" -> recent?.first ?: ((if ("desc.game.$p" in d.pending) d.pending["desc.game.$p"] else d.ludologConfig["desc.game.$p"]) as? String)
            "genre" -> {
                val system = Names.game(d, f)?.systemId
                val (m, map) = system?.let { genres(d, it) } ?: (0L to emptyMap())
                if (recent != null && recent.second > m) recent.first else map[p]?.replace("|", ", ")
            }
            else -> null
        }
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Los devices del juego que tienen Ludolog: donde se corrige. */
    fun targets(devices: List<ConsoleEntry>, r: CatalogRow): List<Pair<ConsoleEntry, RomFile>> =
        devices.filter { it.info?.ludolog != null }.mapNotNull { d -> r.cells[d.id]?.rom?.let { d to it } }

    /**
     * Un sitio con lo suyo de este juego: un device, o el catalogo del PC. [get]: lo puesto a mano;
     * [eff]: lo que de verdad se ve alli (lo puesto, o lo de siempre).
     */
    class Place(val id: String, val name: String, val get: (String) -> String?, val eff: (String) -> String)

    fun places(devices: List<ConsoleEntry>, r: CatalogRow): List<Place> {
        val t = targets(devices, r)
        // El catalogo del PC no tiene «lo de siempre» propio: el de los devices.
        fun fallback(k: String) = t.firstNotNullOfOrNull { (d, f) -> default(d, f, k) }.orEmpty()
        return t.map { (d, f) -> Place(d.id, d.name, { k -> own(d, f, k) }, { k -> norm(k, own(d, f, k) ?: default(d, f, k).orEmpty()) }) } +
            listOfNotNull(r.cells[CatalogRow.PC]?.rom?.let { f ->
                Place(CatalogRow.PC, "PC catalog", { k -> CatalogInfo.get(f, k) }, { k -> norm(k, CatalogInfo.get(f, k) ?: fallback(k)) })
            })
    }

    /**
     * Si [field] no dice lo mismo en todos, en lo que de verdad se ve: un nombre puesto a mano igual
     * al de siempre no es un conflicto.
     */
    fun differs(places: List<Place>, field: String) = places.map { it.eff(field) }.distinct().size > 1

    /** Las versiones de un campo, juntando los sitios que ven lo mismo. */
    fun versions(places: List<Place>, field: String): List<Version> =
        places.groupBy { it.eff(field) }.map { (shown, ps) ->
            val isDefault = ps.any { it.get(field) == null }
            Version(if (isDefault) "" else ps.first().get(field).orEmpty(), shown, isDefault, ps.map { it.name })
        }

    /** Para la tabla: el nombre o la descripcion distintos en algun sitio (sin leer fichas). */
    fun mixed(devices: List<ConsoleEntry>, r: CatalogRow): Boolean {
        val p = places(devices, r)
        return p.size > 1 && (differs(p, "name") || differs(p, "desc"))
    }

    /** Lo mandado: se ve ya. */
    fun sent(d: ConsoleEntry, f: RomFile, field: String, value: String, at: Long) {
        val p = Names.path(d, f) ?: return
        sent["${d.id}|$p|$field"] = value to at
    }

    /** El genero como lo guarda Ludolog: separado por barras. */
    fun genreOut(v: String) = v.split(',', '|').map(String::trim).filter(String::isNotEmpty).joinToString("|")
}

/**
 * La copia del catalogo del PC: nombre, genero y descripcion de sus juegos, en
 * `<catalogo>/media/info.json` ({"consola/nombre": {name, desc, genre, t}}, la clave como la de sus
 * caratulas). Se llena al traer un juego al catalogo y al corregirlo aqui, y va con el juego cuando
 * se manda del catalogo a un device.
 */
internal object CatalogInfo {
    private var cache: Triple<String, Long, MutableMap<String, JSONObject>>? = null
    /** Sube al cambiar: la pantalla lo lee. */
    private var revision by mutableIntStateOf(0)

    fun key(f: RomFile) = f.system.lowercase() + "/" + SystemDef.key(Protocol.stemOf(f.name.substringAfterLast('/')))

    private fun file(): File? = PcCatalog.dir?.let { File(it, "${PcCatalog.MEDIA}/info.json") }

    @Synchronized
    private fun all(): MutableMap<String, JSONObject> {
        val f = file() ?: return mutableMapOf()
        val m = f.lastModified()
        cache?.let { (p, at, map) -> if (p == f.path && at == m) return map }
        val map = runCatching {
            val o = JSONObject(f.readText())
            o.keySet().associateWithTo(LinkedHashMap()) { o.getJSONObject(it) }
        }.getOrDefault(LinkedHashMap())
        cache = Triple(f.path, m, map)
        return map
    }

    @Synchronized
    private fun save(map: Map<String, JSONObject>) {
        val f = file() ?: return
        f.parentFile.mkdirs()
        val o = JSONObject()
        for ((k, v) in map) if (v.keySet().any { it != "t" }) o.put(k, v)
        val tmp = File(f.path + ".part")
        tmp.writeText(o.toString(1))
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        cache = Triple(f.path, f.lastModified(), map.toMutableMap())
        revision++
    }

    fun get(f: RomFile, field: String): String? {
        revision
        return all()[key(f)]?.optString(field)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun values(f: RomFile): Map<String, String> =
        GameInfo.FIELDS.mapNotNull { (k, _) -> get(f, k)?.let { k to it } }.toMap()

    fun time(f: RomFile): Long = all()[key(f)]?.optLong("t") ?: 0L

    /** Lo corregido en el PC ([changes]: vacio, se quita). */
    fun set(f: RomFile, changes: Map<String, String>, t: Long) {
        if (file() == null) return
        val map = all().toMutableMap()
        val o = map[key(f)] ?: JSONObject()
        for ((k, v) in changes) if (v.isEmpty()) o.remove(k) else o.put(k, v)
        o.put("t", t)
        map[key(f)] = o
        save(map)
    }

    /** Lo que trae un juego que llega al catalogo, sin pisar lo que ya tenga. */
    fun fill(f: RomFile, from: Map<String, String>) {
        if (from.isEmpty() || file() == null) return
        val map = all().toMutableMap()
        val o = map[key(f)] ?: JSONObject()
        var any = false
        for ((k, v) in from) if (!o.has(k)) { o.put(k, v); any = true }
        if (!any) return
        if (!o.has("t")) o.put("t", 0L)
        map[key(f)] = o
        save(map)
    }
}

/** Un punto amarillo: lo mismo dice cosas distintas en cada sitio. */
@Composable
internal fun MixedDot(modifier: Modifier = Modifier) =
    Box(modifier.size(7.dp).background(Look.warn, CircleShape))

/**
 * Lo del juego que va a todos los devices: nombre, genero y descripcion. Si no dicen lo mismo en
 * todos (ni en el catalogo del PC), un punto amarillo; al editar, cada version se puede tomar con
 * un clic. Guardar lo manda a cada device con el juego, y ellos a los suyos; y al catalogo del PC.
 */
@Composable
internal fun GameInfoSection(app: AppState, prefId: String?, devices: List<ConsoleEntry>, r: CatalogRow) {
    val targets = GameInfo.targets(devices, r)
    val places = GameInfo.places(devices, r)
    if (places.isEmpty()) return
    // La de la pestaña manda al empezar a editar.
    val first = places.firstOrNull { it.id == prefId } ?: places.first()
    var editing by remember(r.key) { mutableStateOf<Map<String, String>?>(null) }
    var initial by remember(r.key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val diff = GameInfo.FIELDS.associate { (k, _) -> k to GameInfo.differs(places, k) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp)) {
        Text("Info", style = MaterialTheme.typography.labelLarge, color = MenuDim)
        if (diff.values.any { it }) MixedDot()
        Box(Modifier.weight(1f))
        if (editing == null) LTextButton(onClick = {
            initial = GameInfo.FIELDS.associate { (k, _) -> k to first.get(k).orEmpty() }
            editing = initial
        }) { Text("Edit") }
    }

    val draft = editing
    if (draft == null) {
        val shown = GameInfo.FIELDS.mapNotNull { (k, label) -> first.get(k)?.let { Triple(k, label, it) } }
        if (shown.isEmpty()) Text("Default", style = MaterialTheme.typography.bodySmall, color = MenuDim)
        for ((k, label, v) in shown) Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MenuDim)
                if (diff[k] == true) MixedDot()
            }
            Text(v, color = MenuInk, maxLines = if (k == "desc") 6 else 2, overflow = TextOverflow.Ellipsis)
        }
        return
    }

    for ((k, label) in GameInfo.FIELDS) {
        // El genero, de la lista de Ludolog (la de su menu): cada uno va a uno o dos vertices del Companion.
        if (k == "genre") GenreChoice(draft[k].orEmpty()) { v -> editing = draft + (k to v) }
        else OutlinedTextField(value = draft[k].orEmpty(), onValueChange = { v -> editing = draft + (k to v) },
            label = { Text(label) }, singleLine = k != "desc", minLines = if (k == "desc") 3 else 1, maxLines = if (k == "desc") 8 else 1,
            shape = Look.shape, modifier = Modifier.fillMaxWidth())
        // Lo que dice cada sitio, si no es lo mismo: un clic lo toma.
        if (diff[k] == true) Variants(GameInfo.versions(places, k)) { v -> editing = draft + (k to v) }
    }
    Text("Goes to every device with this game. Empty: default.", style = MaterialTheme.typography.bodySmall, color = MenuDim)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Lo cambiado, y lo que no dice lo mismo en todos: asi queda igual en todos.
        val changes = GameInfo.FIELDS.map { it.first }.filter { k -> draft[k].orEmpty().trim() != initial[k].orEmpty().trim() || diff[k] == true }
            .associateWith { draft[it].orEmpty().trim() }
        LButton(onClick = { app.saveGameInfo(targets, changes, r.cells[CatalogRow.PC]?.rom); editing = null }, enabled = changes.isNotEmpty()) {
            Text("Save")
        }
        LTextButton(onClick = { editing = null }) { Text("Cancel") }
    }
}

/**
 * Una version de un campo: lo que se ve ([shown]), lo que se pone al elegirla ([use]: vacio, volver
 * a lo de siempre), si es la de siempre en alguno de sus sitios, y esos sitios.
 */
internal class Version(val use: String, val shown: String, val isDefault: Boolean, val names: List<String>)

/** Las versiones de un campo, cada una con su «Use». */
@Composable
internal fun Variants(versions: List<Version>, use: (String) -> Unit) {
    // Lo de siempre primero; cada version en su bloque: que dice, y debajo, en pequeño, de donde viene.
    for (v in versions.sortedBy { !it.isDefault }) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MixedDot(Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            if (v.isDefault) Text("DEFAULT", style = MaterialTheme.typography.labelSmall, color = MenuDim)
            Text(v.shown.ifEmpty { "From the catalog" }, color = MenuInk, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(v.names.joinToString(), style = MaterialTheme.typography.labelSmall, color = MenuDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        LTextButton(onClick = { use(v.use) }) { Text("Use") }
    }
}

/** A que vertices del Companion va un genero: «REFLEX», «POWER + SOUL». Ver Metagame.weights. */
internal fun vertexOf(genre: String): String =
    com.felp.frontcomp.Metagame.weights(genre)?.keys?.joinToString(" + ") { it.label }.orEmpty()

/**
 * El genero de un juego: «Automatic» (el del catalogo) o uno de la lista de Ludolog, con el vertice
 * del Companion al que suma. Uno solo, como en su menu.
 */
@Composable
private fun GenreChoice(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Lo guardado puede ser una lista antigua («Action, RPG»): cuenta el primero que conoce Ludolog.
    val current = value.takeIf { it.isNotBlank() }?.let { com.felp.frontcomp.Genres.canon(listOf(it)).firstOrNull() ?: it }
    Column(Modifier.fillMaxWidth()) {
        Text("Genre", style = MaterialTheme.typography.bodySmall, color = MenuDim)
        Box {
            LTextButton(onClick = { open = true }) {
                Text((current?.let { g -> vertexOf(g).let { v -> if (v.isEmpty()) g else "$g · $v" } } ?: "Automatic") + " ▾", maxLines = 1)
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = Look.shape) {
                androidx.compose.material3.DropdownMenuItem(text = { Text("Automatic") }, onClick = { open = false; onPick("") })
                for (g in com.felp.frontcomp.Genres.all) androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Row(Modifier.width(260.dp)) {
                            Text(g, Modifier.weight(1f))
                            Text(vertexOf(g), style = MaterialTheme.typography.bodySmall, color = MenuDim)
                        }
                    },
                    onClick = { open = false; onPick(g) })
            }
        }
    }
}
