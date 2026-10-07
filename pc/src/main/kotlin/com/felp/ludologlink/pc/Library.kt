package com.felp.ludologlink.pc

import com.felp.frontcomp.CatalogLoader
import com.felp.frontcomp.SystemDef
import com.felp.ludolog.kit.Protocol

/**
 * El mismo juego en dos consolas, de quien es un archivo de arte, y que arte ya no es de nadie:
 * con la regla con que Ludolog le busca arte a un juego (ArtIndex.find). Ludolog lo busca en
 * `<raiz>/<consola>/...` por el nombre del archivo o por el titulo del juego, ambos con
 * SystemDef.key (solo letras y numeros, en minusculas).
 */
internal object Library {

    private fun norm(s: String) = SystemDef.key(s)

    private fun stem(f: RomFile) = Protocol.stemOf(f.name.substringAfterLast('/'))

    /**
     * Las claves con que Ludolog encontraria arte para ese ROM: su consola en Ludolog y la carpeta,
     * por el nombre del archivo y por el titulo. De mas, no de menos: que nada de un juego que esta
     * se tome por huerfano.
     */
    fun artKeys(e: ConsoleEntry, f: RomFile): Set<String> {
        val g = Names.game(e, f)
        val systems = listOfNotNull(g?.systemId, f.system).map { it.lowercase() }.distinct()
        val names = listOfNotNull(stem(f), g?.title).map(::norm).filter { it.isNotEmpty() }.distinct()
        return systems.flatMapTo(HashSet()) { s -> names.map { "$s/$it" } }
    }

    fun mediaKey(m: MediaFile) = m.sys.lowercase() + "/" + norm(m.name.substringBeforeLast('.'))

    /** El juego, para compararlo entre consolas: su consola en Ludolog y su titulo (o el nombre del archivo). */
    fun gameKey(e: ConsoleEntry, f: RomFile): String {
        val g = Names.game(e, f)
        return (g?.systemId ?: f.system).lowercase() + "/" + norm(g?.title?.takeIf { it.isNotBlank() } ?: stem(f))
    }

    /**
     * El arte y los videos que no son de ningun ROM de la consola. Solo en carpetas de consolas que
     * Ludolog conoce (o que son carpetas de ROMs), y nunca en la del tema: lo suyo son medios del
     * propio tema (sistemas, apps, escenas), no arte de juegos.
     */
    fun orphans(e: ConsoleEntry, media: List<MediaFile>): List<MediaFile> {
        val used = e.roms.flatMapTo(HashSet()) { artKeys(e, it) }
        val systems = (e.catalog ?: CatalogLoader.current)?.systems.orEmpty().map { it.id.lowercase() }.toSet() +
            e.consoleDirs.map { it.folder.lowercase() }
        return media.filter { it.owner != "theme" && it.sys.lowercase() in systems && mediaKey(it) !in used }
    }
}
