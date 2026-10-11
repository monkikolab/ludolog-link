package com.felp.ludologlink

import android.content.Context
import java.io.File

/**
 * Donde guarda sus partidas un emulador, adivinado: al agregarlo, el explorador abre ahi y las
 * propone, y la persona mira y confirma o cambia (pedido del usuario, 10-10-2026). Nunca se pone sola.
 *
 * Dos fuentes, en este orden:
 * - las carpetas conocidas de los emuladores habituales ([KNOWN]), tal como estan en las consolas de
 *   prueba (10-10-2026): `RetroArch/saves`, las `memcards` de los de PS2, `nand/user/save/0…0/<usuario>`
 *   de los de Switch, el `title/00040000` de los de 3DS…;
 * - y, para cualquier otro, un repaso de su carpeta de datos (`Android/data/<paquete>/files`, en cada
 *   volumen) buscando carpetas con nombre de partidas (`saves`, `memcards`, `savedata`…).
 *
 * Cada propuesta lleva cuantos archivos tiene y cuando cambio el ultimo: la que tiene partidas y se
 * toco hace poco va primero. Recorre poco (profundidad y numero de carpetas acotados): esto corre al
 * abrir el explorador, fuera del hilo de la pantalla.
 */
object SaveScan {
    data class Guess(val path: String, val files: Int, val changed: Long, val known: Boolean)

    /**
     * Un emulador conocido: sus paquetes (y forks), la consola que juega (el id de Ludolog, para el
     * modo flexible; nulo si juega muchas) y sus carpetas de partidas, relativas a cada volumen.
     * `{pkg}` es el paquete; `*` es una carpeta cualquiera (la del usuario en Switch, los dos ids de
     * la tarjeta en 3DS, la carpeta que se eligio para PPSSPP): salen todas las que haya.
     */
    private class Known(val pkg: Regex, val system: String?, val paths: List<String>)

    /** La «App Folder» de los emuladores EX (antes que [KNOWN], que la usa). */
    private val EX = listOf("Android/data/{pkg}/files/EmuEx/*/saves")

    /**
     * Sacado del codigo de cada emulador (revision del 11-10-2026: el fichero que pone la carpeta de
     * datos en Android y el que arma la de partidas) y de lo que hay en las consolas de prueba. Los
     * que eligen su carpeta con el selector del sistema (PPSSPP, ePSXe, DraStic, Azahar…) no se pueden
     * adivinar del todo: se prueban los nombres que se suelen elegir.
     */
    private val KNOWN: List<Known> = listOf(
        // RetroArch: la de la web, en la memoria; la de Play Store, en su carpeta de datos (y la que
        // viene, en Android/media). Dentro, una carpeta por nucleo.
        Known(Regex("""com\.retroarch.*"""), null, listOf("RetroArch/saves", "Android/data/{pkg}/files/RetroArch/saves",
            "Android/data/{pkg}/files/saves", "Android/media/{pkg}/RetroArch/saves")),
        Known(Regex("""org\.ppsspp\..*"""), "psp", listOf("PSP/SAVEDATA", "*/PSP/SAVEDATA", "Android/data/{pkg}/files/PSP/SAVEDATA")),
        Known(Regex("""com\.github\.stenzek\.duckstation"""), "psx", listOf("Android/data/{pkg}/files/memcards", "duckstation/memcards")),
        Known(Regex("""com\.epsxe\..*"""), "psx", listOf("epsxe/memcards", "ePSXe/memcards", "Android/data/{pkg}/files/epsxe/memcards")),
        // ARMSX1 es el codigo de ARMSX2 para PS1: las mismas memory cards.
        Known(Regex("""com\.nanodata\.armsx"""), "psx", listOf("Android/data/{pkg}/files/memcards", "ARMSX1/memcards")),
        Known(Regex("""com\.armsx2.*|xyz\.aethersx2\..*"""), "ps2", listOf("Android/data/{pkg}/files/memcards", "ARMSX2/memcards")),
        Known(Regex("""com\.armsx3.*"""), "ps3", listOf("Android/data/{pkg}/files/config/dev_hdd0/home/00000001/savedata")),
        // Dolphin y sus forks: las tarjetas de GameCube; las partidas de Wii van en Wii/title.
        Known(Regex(""".*(dolphin|primehack|ishiiruka).*|org\.mm\..*"""), "gamecube", listOf(
            "Android/data/{pkg}/files/GC", "dolphin-emu/GC", "dolphin-mmjr/GC", "mmjr-revamp/GC",
            "Android/data/{pkg}/files/Wii/title", "dolphin-emu/Wii/title")),
        // melonDS guarda por defecto junto al ROM (eso no se propone: Link copiaria los juegos);
        // sin «Save next to ROM», en su carpeta de datos.
        Known(Regex("""me\.magnum\.melonds"""), "nds", listOf("Android/data/{pkg}/files/saves")),
        Known(Regex("""com\.dsemu\.drastic.*"""), "nds", listOf("DraStic/backup", "Android/data/{pkg}/files/backup")),
        Known(Regex("""com\.flycast\..*"""), "dreamcast", listOf("Android/data/{pkg}/files/data")),
        Known(Regex("""io\.recompiled\.redream"""), "dreamcast", listOf("Android/data/{pkg}/files/saves", "Android/data/{pkg}/files")),
        // Los de yuzu: dentro, una carpeta por usuario. Eden deja mover la de partidas: entonces no se adivina.
        Known(Regex(""".*(eden|citron|yuzu|sudachi|suyu|torzu).*|com\.miHoYo\.Yuanshen"""), "switch",
            listOf("Android/data/{pkg}/files/nand/user/save/0000000000000000/*")),
        Known(Regex("""org\.stratoemu\..*|emu\.skyline.*"""), "switch",
            listOf("Android/data/{pkg}/files/switch/nand/user/save/0000000000000000/*")),
        // Ryujinx y Kenji-NX: carpetas por numero de partida, no por juego; la de todas.
        Known(Regex("""org\.(ryujinx|kenjinx)\..*"""), "switch", listOf("Android/data/{pkg}/files/bis/user/save")),
        // Los de Citra eligen su carpeta con el selector del sistema y sin nombre propuesto: se mira
        // cualquier carpeta de la raiz de cada volumen que tenga la tarjeta dentro (3DS_files,
        // citra-emu, Azahar…). Citra MMJ usa la suya de datos. Los dos ids son siempre ceros.
        Known(Regex(""".*(citra|lime3ds|azahar|mandarine|borked3ds).*"""), "3ds", listOf(
            "*/sdmc/Nintendo 3DS/*/*/title/00040000",
            "Android/data/{pkg}/files/sdmc/Nintendo 3DS/*/*/title/00040000",
            "Android/data/{pkg}/files/citra-emu/sdmc/Nintendo 3DS/*/*/title/00040000",
        )),
        Known(Regex(""".*vita3k.*"""), "psvita", listOf("Android/data/{pkg}/files/vita/ux0/user/00/savedata")),
        Known(Regex("""info\.cemu\..*"""), "wiiu", listOf("Android/data/{pkg}/files/mlc01/usr/save")),
        // M64Plus FZ juega desde su zona privada; solo con «Game data storage» fuera copia ahi.
        Known(Regex("""org\.mupen64plusae\..*"""), "n64", listOf("M64Plus/GameData", "*/GameData")),
        Known(Regex("""org\.devmiyax\.yabasanshi.*"""), "saturn", listOf("Android/data/{pkg}/files/yabause/memory", "yabause/memory")),
        Known(Regex("""com\.seleuco\.mame4d.*"""), "arcade", listOf("Android/data/{pkg}/files/nvram", "Android/media/{pkg}/nvram", "MAME4droid/nvram")),
        Known(Regex("""com\.swordfish\.lemuroid"""), null, listOf("Android/data/{pkg}/files/saves")),
        // Los EX de Robert Broglia guardan junto al ROM salvo con «App Folder».
        Known(Regex("""com\.explusalpha\.Snes9x.*"""), "snes", EX), Known(Regex("""com\.explusalpha\.GbaEmu"""), "gba", EX),
        Known(Regex("""com\.explusalpha\.GbcEmu"""), "gbc", EX), Known(Regex("""com\.explusalpha\.NesEmu"""), "nes", EX),
        Known(Regex("""com\.explusalpha\.MdEmu"""), "megadrive", EX), Known(Regex("""com\.explusalpha\.NgpEmu"""), "ngpc", EX),
        Known(Regex("""com\.explusalpha\.NeoEmu"""), "neogeo", EX), Known(Regex("""com\.explusalpha\.MsxEmu"""), "msx", EX),
        Known(Regex("""com\.explusalpha\.C64Emu"""), "c64", EX), Known(Regex("""com\.explusalpha\.SaturnEmu"""), "saturn", EX),
        Known(Regex("""com\.explusalpha\.SwanEmu"""), "wonderswancolor", EX), Known(Regex("""com\.explusalpha\.LynxEmu"""), "lynx", EX),
        Known(Regex("""com\.explusalpha\.A2600Emu"""), "atari2600", EX), Known(Regex("""com\.PceEmu"""), "pcengine", EX),
    )

    /** La consola de un emulador conocido, para el modo flexible; nula si no se sabe o juega muchas. */
    fun systemOf(pkg: String): String? = KNOWN.firstOrNull { it.pkg.matches(pkg) }?.system

    /** Si es un emulador de los conocidos: van primero en «Add emulator…», tras los que ya abrio Ludolog. */
    fun known(pkg: String): Boolean = KNOWN.any { it.pkg.matches(pkg) }

    /** Nombres de carpeta de partidas, para los que no estan en [KNOWN]. */
    private val SAVE_DIR = Regex("""(?i)^(saves?|savedata|save_data|savegames?|memcards?|memory ?cards?|sav|battery|backup)$""")

    /** Lo que nunca es una carpeta de partidas aunque cuelgue de una. */
    private val SKIP = Regex("""(?i)^(cache|shaders?|shadercache|textures?|logs?|cheats?|screenshots?|covers?|thumbnails?|\..*)$""")

    private const val MAX_DIRS = 3_000
    private const val MAX_DEPTH = 7

    /** Las propuestas para [pkg], la mejor primero; vacia si no hay ninguna. */
    fun guess(ctx: Context, pkg: String): List<Guess> {
        val volumes = runCatching { RomStore.volumePaths(ctx) }.getOrDefault(emptyList()).map(::File)
        val found = LinkedHashMap<String, Boolean>()
        for (rel in KNOWN.filter { it.pkg.matches(pkg) }.flatMap { it.paths }) {
            for (v in volumes) for (d in expand(v, rel.replace("{pkg}", pkg))) found.putIfAbsent(d.absolutePath, true)
        }
        for (v in volumes) {
            val data = File(v, "Android/data/$pkg/files")
            if (data.isDirectory) for (d in walk(data)) found.putIfAbsent(d.absolutePath, false)
        }
        val all = found.map { (path, known) ->
            val (n, at) = contents(File(path))
            Guess(path, n, at, known)
        }
            // Una conocida sale aunque este vacia (un emulador recien instalado); una del repaso, solo con algo.
            .filter { it.known || it.files > 0 }
        // Si una conocida tiene partidas, solo las conocidas: el repaso de Eden traia tambien
        // `nand/system/save` y la carpeta de encima de la del usuario, que nunca son la buena.
        val shown = if (all.any { it.known && it.files > 0 }) all.filter { it.known } else all
        return shown.sortedWith(compareByDescending<Guess> { it.files > 0 }.thenByDescending { it.known }.thenByDescending { it.changed })
            .take(4)
    }

    /** Las carpetas que casan con [rel] dentro de [root]; `*` es cualquier subcarpeta. */
    private fun expand(root: File, rel: String): List<File> {
        var level = listOf(root)
        for (part in rel.split('/').filter { it.isNotEmpty() }) {
            level = level.flatMap { dir ->
                if (part == "*") dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
                else listOf(File(dir, part)).filter { it.isDirectory }
            }
            if (level.isEmpty()) return emptyList()
        }
        return level
    }

    /** Las carpetas con nombre de partidas dentro de [root], sin entrar en ellas ni en las que no lo son ([SKIP]). */
    private fun walk(root: File): List<File> {
        val out = ArrayList<File>()
        var seen = 0
        fun go(dir: File, depth: Int) {
            if (depth > MAX_DEPTH || seen > MAX_DIRS) return
            for (d in dir.listFiles()?.filter { it.isDirectory }.orEmpty()) {
                if (++seen > MAX_DIRS) return
                if (SKIP.matches(d.name)) continue
                if (SAVE_DIR.matches(d.name)) out += d else go(d, depth + 1)
            }
        }
        go(root, 0)
        return out
    }

    /** Cuantos archivos hay dentro (hasta tres niveles, y como mucho 500) y cuando cambio el ultimo. */
    private fun contents(dir: File): Pair<Int, Long> {
        var n = 0
        var at = 0L
        fun go(d: File, depth: Int) {
            for (f in d.listFiles().orEmpty()) {
                if (n >= 500) return
                if (f.isDirectory) { if (depth < 3) go(f, depth + 1) }
                else { n++; at = maxOf(at, f.lastModified()) }
            }
        }
        go(dir, 0)
        return n to at
    }
}
