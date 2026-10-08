package com.felp.ludologlink

import com.felp.ludolog.kit.Protocol

/**
 * Link Dev: la version de desarrollo, para probar al lado de la oficial firmada (07-10-2026).
 *
 * Es otro paquete (`.dev`, ver build.gradle.kts) y le habla a Ludolog Dev y no a la oficial: el
 * paquete de Ludolog, su carpeta de datos y el permiso del puente salen de BuildConfig. Aqui, lo
 * demas que tiene que ser suyo en el mismo aparato: los puertos, corridos Protocol.DEV_PORT_OFFSET
 * (las dos escuchan a la vez, y cada una solo encuentra a las de su clase), la carpeta de respaldos
 * de partidas y el nombre con que se presenta.
 */
object Dev {
    const val ON = BuildConfig.DEV

    val httpPort = Protocol.HTTP_PORT + if (ON) Protocol.DEV_PORT_OFFSET else 0
    val discoveryPort = Protocol.DISCOVERY_PORT + if (ON) Protocol.DEV_PORT_OFFSET else 0

    /** Como se llama en la notificacion, la cabecera y el panel rapido. */
    val name = if (ON) "Ludolog Link Dev" else "Ludolog Link"

    /** Su carpeta en la memoria interna (respaldos de partidas): ver Saves.home. */
    val folder = if (ON) "LudologLinkDev" else "LudologLink"
}
