package com.felp.ludologlink.pc

import java.io.File

/**
 * Donde guarda sus cosas Link en este PC (pedido del usuario, 07-10-2026).
 *
 * La copia portable (el .zip) lo guarda todo junto a si misma, en `data/`: se lleva la carpeta y
 * va todo con ella, y borrarla no deja nada. Se sabe portable por `portable.txt`, que va en el .zip
 * y no en el instalador. La instalada, en %APPDATA%\LudologLink, como hasta ahora. Antes las dos
 * leian la de %APPDATA%, y la portable recien bajada salia con las carpetas de otra instalacion.
 *
 * En [home] quedan los ajustes, el registro y las caratulas del Companion. La carpeta de datos
 * (Config.dataRoot: respaldos, juegos bajados, catalogo de ROMs) es por defecto esta misma y se
 * cambia en Settings.
 */
object PcDirs {
    /** La carpeta del programa, la del .exe: el lanzador de jpackage la dice. Nula al arrancar desde Gradle. */
    val app: File? = System.getProperty("jpackage.app-path")?.let { File(it).absoluteFile.parentFile }

    val portable: Boolean = app?.let { File(it, "portable.txt").isFile } == true

    /** Con Dev.ON, lo de la version de desarrollo aparte: «LudologLinkDev», «data-dev». */
    private val suffix = if (Dev.ON) "Dev" else ""

    val home: File = if (portable) File(app, if (Dev.ON) "data-dev" else "data")
        else File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "LudologLink$suffix")

    /** Lo que se puede volver a bajar (indices de libretro y de archive.org): fuera de la copia de seguridad de Windows. */
    val cache: File = if (portable) File(home, "cache")
        else File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "LudologLink$suffix")
}
