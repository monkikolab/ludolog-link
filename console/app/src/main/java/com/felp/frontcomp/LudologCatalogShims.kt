package com.felp.frontcomp

import java.io.File

/**
 * Lo que `Catalog.kt` de Ludolog, compilado aqui tal cual (ver build.gradle.kts), pide de fuera:
 * la carpeta de datos, donde `CatalogLoader` busca las consolas que el usuario anadio o cambio
 * (`systems.toml` y la carpeta `systems`). Link la apunta a la carpeta de Ludolog antes de cargar el
 * catalogo (Saves.catalog), y asi tiene los mismos ids y nombres que Ludolog en esta consola.
 * Como el DataHome del PC (pc/LudologDataShims.kt), con solo lo que hace falta.
 */
object DataHome {
    @Volatile var dir: File = File("/nonexistent")

    fun file(name: String): File = File(dir, name)
}
