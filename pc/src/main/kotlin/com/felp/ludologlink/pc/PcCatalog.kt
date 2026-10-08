package com.felp.ludologlink.pc

import com.felp.frontcomp.CatalogLoader
import com.felp.frontcomp.Detector
import com.felp.frontcomp.Game
import com.felp.frontcomp.SystemDef
import com.felp.ludolog.kit.Protocol
import java.io.File

/**
 * La carpeta catalogo del PC: los juegos que hay en este PC, ordenados como en los devices.
 *
 *   <carpeta>/<consola>/<ROMs>                         (gba, psx... como en los devices)
 *   <carpeta>/media/<consola>/covers|videos/<nombre>   (como `media/` de Ludolog)
 *
 * Por defecto `roms` en la carpeta de datos (07-10-2026; antes no habia hasta elegir una), y se
 * cambia en Settings. Ver CatalogView.
 */
object PcCatalog {

    private const val KEY = "catalog_dir"
    const val MEDIA = "media"

    /**
     * La carpeta elegida, este o no ahora. Puede estar en un disco externo: si se desconecta, el
     * catalogo se da por ausente (sin error) y vuelve solo al reconectarlo (CatalogView lo mira
     * cada pocos segundos).
     */
    val configured: File get() = Config.path(KEY)?.let(::File) ?: File(Config.dataRoot, "roms")

    /** La carpeta, solo si esta ahora. La de por defecto se crea al pedirla: es de la app. */
    val dir: File? get() = configured
        .also { if (Config.path(KEY) == null) runCatching { File(it, MEDIA).mkdirs() } }
        .takeIf { runCatching { it.isDirectory }.getOrDefault(false) }

    /**
     * Usar [d] como catalogo. Si esta vacia, se crean las carpetas de las consolas (las de los
     * devices conectados; sin ninguno, las de Ludolog) y la de medios. Devuelve cuantas creo.
     */
    fun choose(d: File, consoles: List<String>): Int {
        Config.setFolder(KEY, d)
        if (d.listFiles().orEmpty().any { !it.name.startsWith(".") }) return 0
        val names = consoles.ifEmpty { CatalogLoader.current?.systems.orEmpty().map { it.id } }.map { it.lowercase() }.distinct()
            .filterNot(Shortcuts::isShortcutFolder).filterNot { it.equals(MEDIA, true) }
        val made = names.count { File(d, it).mkdirs() }
        File(d, MEDIA).mkdirs()
        return made
    }

    class Scan(
        val dir: File,
        val roms: List<RomFile>,
        /** Clave «consola/nombre» (como Library.mediaKey) a su archivo. */
        val covers: Map<String, File>,
        val videos: Map<String, File>,
        /** Lo libre en su disco cuando se leyo. */
        val freeBytes: Long = runCatching { dir.usableSpace }.getOrDefault(0L),
    )

    private val IMAGE = setOf("png", "jpg", "jpeg", "webp")
    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov")

    /** Lo que hay en el catalogo, o nulo si la carpeta no esta (o se fue a mitad de la lectura). */
    fun scanOrNull(d: File): Scan? = runCatching { if (d.isDirectory) scan(d) else null }.getOrNull()
        .also { s -> synchronized(this) { last = s?.let { it to System.currentTimeMillis() } } }

    private var last: Pair<Scan, Long>? = null

    /**
     * La ultima lectura de [d] si tiene menos de [maxAgeMs]; si no, una nueva. La tabla del catalogo y
     * la cola de pedidos leian la carpeta cada una por su lado, y es un disco que puede ser lento.
     */
    fun latest(d: File, maxAgeMs: Long): Scan? {
        synchronized(this) { last }?.let { (s, at) -> if (s.dir == d && System.currentTimeMillis() - at < maxAgeMs) return s }
        return scanOrNull(d)
    }

    private fun scan(d: File): Scan {
        val roms = ArrayList<RomFile>()
        // La de medios no es una consola (con cualquier mayuscula: Windows no las distingue).
        for (sys in d.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") && !it.name.equals(MEDIA, true) }) {
            sys.walkTopDown().filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }.forEach { f ->
                val rel = f.relativeTo(sys).invariantSeparatorsPath
                val r = RomFile(sys.name, rel, f.length(), f.lastModified())
                if (!Shortcuts.isShortcut(r) && !Shortcuts.isLocked(r)) roms += r
            }
        }
        val covers = HashMap<String, File>()
        val videos = HashMap<String, File>()
        val media = d.listFiles().orEmpty().firstOrNull { it.isDirectory && it.name.equals(MEDIA, true) } ?: File(d, MEDIA)
        for (sys in media.listFiles().orEmpty().filter { it.isDirectory }) {
            // Las caratulas primero: si hay de varios tipos, la que se ve es la caratula.
            val kinds = sys.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { if (it.name == "covers") 0 else 1 }
            for (kind in kinds) for (f in kind.listFiles().orEmpty().filter { it.isFile }) {
                val ext = f.extension.lowercase()
                val key = sys.name.lowercase() + "/" + SystemDef.key(f.nameWithoutExtension)
                if (kind.name == "videos") { if (ext in VIDEO) videos.putIfAbsent(key, f) }
                else if (ext in IMAGE) covers.putIfAbsent(key, f)
            }
        }
        return Scan(d, roms, covers, videos)
    }

    /**
     * Los catalogos de consolas con que se reconocen los juegos del catalogo: los de los devices
     * conectados (cada uno con sus consolas propias). Los pone CatalogView.
     */
    @Volatile var catalogs: List<com.felp.frontcomp.Catalog> = emptyList()

    /** El juego de ese ROM del catalogo, como lo reconoceria Ludolog en alguno de los devices. */
    fun game(s: Scan, f: RomFile): Game? {
        val path = File(File(s.dir, f.system), f.name).invariantSeparatorsPath
        return (catalogs.ifEmpty { listOfNotNull(CatalogLoader.current) }).firstNotNullOfOrNull { cat -> GameCache.gameFor(cat, path) }
    }

    fun file(s: Scan, f: RomFile) = File(File(s.dir, f.system), f.name)

    /** Las claves de arte de un ROM del catalogo: su consola en Ludolog y la carpeta, por nombre y por titulo. */
    fun artKeys(s: Scan, f: RomFile): Set<String> {
        val g = game(s, f)
        val systems = listOfNotNull(g?.systemId, f.system).map { it.lowercase() }.distinct()
        val names = listOfNotNull(Protocol.stemOf(f.name.substringAfterLast('/')), g?.title).map { SystemDef.key(it) }
            .filter { it.isNotEmpty() }.distinct()
        return systems.flatMapTo(HashSet()) { sys -> names.map { "$sys/$it" } }
    }
}
