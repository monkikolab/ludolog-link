package com.felp.ludologlink.pc

import com.felp.frontcomp.CatalogLoader
import com.felp.frontcomp.Detector
import com.felp.frontcomp.Dossiers
import com.felp.frontcomp.Game
import com.felp.frontcomp.GameDb
import com.felp.ludolog.kit.Protocol

// Names.kt: que es cada archivo de un device, con las reglas de Ludolog. Shortcuts dice lo que no
// es un juego (y que Steam y DoomForge son entradas bloqueadas); Names, que juego es (Detector de
// Ludolog sobre el catalogo de ese device, con GameCache delante), como se llama alli y si tiene
// arte o video. Lo usa casi todo: AppState, la tabla de Games, el scraper y el Companion del PC.

/**
 * Lo que Games no enseña (ConsoleEntry.games lo quita), porque no son juegos. Los accesos
 * directos, que Ludolog trata igual: los del PC (Steam, GameHub...), los de apps de Android, los
 * perfiles de DoomForge y la carpeta de emuladores (que su Scanner salta). Y los archivos de
 * acompañamiento: los .txt que dicen que poner en cada carpeta ("Put doom.wad here") y los .sbi,
 * que van con su .cue/.bin y se renombran y borran con el.
 */
object Shortcuts {
    private val EXT = setOf("steam", "epic", "gog", "amazon", "pcgame", "gamehub", "desktop", "shortcut", "app", "doomforge")
    /**
     * Los que si se listan, pero bloqueados: juegos de Steam (`.steam`, `.shortcut`) y de DoomForge
     * (`.doomforge`). Son entradas de otra app, no ROMs: se les busca arte y se les cambia el nombre
     * en Ludolog, pero su archivo no se renombra, ni se borra, ni se copia o descarga.
     */
    private val LOCKED = setOf("steam", "shortcut", "doomforge")
    private val COMPANIONS = setOf("txt", "sbi")
    private val DIRS = setOf("emulators", "androidapps", "androidgames", "android")

    fun isShortcut(f: RomFile): Boolean {
        val ext = Protocol.extOf(f.name).removePrefix(".").lowercase()
        if (ext in LOCKED && f.system.lowercase() !in DIRS) return false
        return f.system.lowercase() in DIRS || ext in EXT || ext in COMPANIONS
    }

    /**
     * Carpetas de los devices que no son consolas: accesos directos a apps y emuladores, y las de
     * tiendas de PC (Steam, Epic...), que guardan entradas de otra app, no ROMs. El catalogo del PC
     * no las crea (ver PcCatalog.choose).
     */
    fun isShortcutFolder(name: String): Boolean = name.lowercase().let {
        it in DIRS || it.startsWith("applauncher") || it in setOf("steam", "epic", "gog", "amazon")
    }

    /** Ver [LOCKED]. */
    fun isLocked(f: RomFile): Boolean =
        Protocol.extOf(f.name).removePrefix(".").lowercase() in LOCKED && f.system.lowercase() !in DIRS
}

/**
 * Que juego es una ruta, segun un catalogo: siempre lo mismo para el mismo catalogo y la misma ruta,
 * asi que se guarda. Se preguntaba hasta cinco veces por ROM (nombre, clave, arte, video...) y cada
 * una pasaba por las expresiones de Detector; con miles de ROMs, la tabla del catalogo se congelaba.
 */
internal object GameCache {
    private val NONE = Any()
    private val detectors = java.util.Collections.synchronizedMap(java.util.WeakHashMap<com.felp.frontcomp.Catalog, Detector>())
    private val games = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun gameFor(cat: com.felp.frontcomp.Catalog, path: String): Game? {
        val key = System.identityHashCode(cat).toString() + "|" + path
        games[key]?.let { return if (it === NONE) null else it as Game }
        val d = detectors.getOrPut(cat) { Detector(cat) }
        val g = runCatching { d.gameFor(path) }.getOrNull()
        if (games.size > 200_000) games.clear()
        games[key] = g ?: NONE
        return g
    }
}

/**
 * Como se llama un juego en Ludolog, con su misma regla (LibraryViewModel.displayTitle): el
 * nombre puesto a mano; si no, el del catalogo cuando la ficha lo identifico con seguridad, sin
 * sus etiquetas; y si no, el que sale del fichero. Con Detector y Dossiers de Ludolog, compilados
 * tal cual, sobre la copia de su carpeta de datos.
 */
internal object Names {

    /** La ruta con que Ludolog conoce el juego: la clave de todo lo suyo (`name.game.<ruta>`...). */
    fun path(e: ConsoleEntry, f: RomFile): String? = e.info?.romsRoot?.let { "$it/${f.system}/${f.name}" }

    fun game(e: ConsoleEntry, f: RomFile): Game? {
        val cat = e.catalog ?: CatalogLoader.current ?: return null
        val p = path(e, f) ?: return null
        return GameCache.gameFor(cat, p)
    }

    /** El nombre puesto a mano, contando lo que espera a sincronizarse. */
    fun custom(e: ConsoleEntry, f: RomFile): String? {
        val key = "name.game.${path(e, f) ?: return null}"
        if (key in e.pending) return e.pending[key] as? String
        return e.ludologConfig[key] as? String
    }

    fun display(e: ConsoleEntry, f: RomFile): String {
        custom(e, f)?.let { return it }
        val g = game(e, f)
        g?.let { Dossiers.get(it) }?.takeIf { it.exact }?.name?.let(GameDb::base)?.takeIf { it.isNotBlank() }?.let { return it }
        return g?.title ?: Protocol.stemOf(f.name)
    }

    /** Si tiene arte o video, donde los busca Ludolog (ver Ludolog.artIndex en la consola). */
    fun hasArt(e: ConsoleEntry, f: RomFile) = key(e, f).any { it in e.art }
    fun hasVideo(e: ConsoleEntry, f: RomFile) = key(e, f).any { it in e.video }

    /** Por la consola de Ludolog y por la carpeta: el arte de ES-DE va por carpeta. */
    private fun key(e: ConsoleEntry, f: RomFile): List<String> {
        val stem = Protocol.stemOf(f.name).lowercase()
        return listOfNotNull(game(e, f)?.systemId?.lowercase(), f.system.lowercase()).distinct().map { "$it/$stem" }
    }
}
