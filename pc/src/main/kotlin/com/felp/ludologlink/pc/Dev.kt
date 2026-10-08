package com.felp.ludologlink.pc

import com.felp.ludolog.kit.Protocol

/**
 * Ludolog Link Dev en el PC: la misma app arrancada con LUDOLOG_LINK_DEV=1 (07-10-2026).
 *
 * Habla con Link Dev de las consolas y no con el oficial: sus puertos van corridos
 * Protocol.DEV_PORT_OFFSET. Y guarda lo suyo aparte (%APPDATA%\LudologLinkDev, ver PcDirs): sus
 * emparejamientos y su carpeta de datos no tocan los de la copia instalada.
 *
 *   LUDOLOG_LINK_DEV=1 "build/compose/binaries/main/app/Ludolog Link/Ludolog Link.exe"
 */
object Dev {
    val ON: Boolean = System.getenv("LUDOLOG_LINK_DEV") == "1" || System.getProperty("ludolog.dev") == "true"

    val httpPort = Protocol.HTTP_PORT + if (ON) Protocol.DEV_PORT_OFFSET else 0
    val discoveryPort = Protocol.DISCOVERY_PORT + if (ON) Protocol.DEV_PORT_OFFSET else 0

    /** El titulo de la ventana y la cabecera. */
    val name = if (ON) "Ludolog Link Dev" else "Ludolog Link"
}
