package com.felp.ludolog.kit.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Iconos del kit, dibujados en codigo para que sean identicos en la consola y en el PC. */
object KitIcons {

    /**
     * El de la app: la gema con el ojo de Ludolog y dos ondas a cada lado, Ludolog hablando por
     * Wi-Fi. Es el mismo dibujo que ic_launcher_foreground.xml de la consola (capa de 108), aqui
     * recortado a su centro de 72 y sobre un cuadrado redondeado, para la ventana y el .exe.
     */
    val App: ImageVector by lazy {
        val b = ImageVector.Builder("ludolog_link_app", 72.dp, 72.dp, 72f, 72f)
        b.addPath(addPathNodes("M14,0h44a14,14 0 0 1 14,14v44a14,14 0 0 1 -14,14h-44a14,14 0 0 1 -14,-14v-44a14,14 0 0 1 14,-14z"),
            fill = SolidColor(Color(0xFF151515)))
        b.addGroup(translationX = -18f, translationY = -18f)
        linkMark(b)
        b.clearGroup()
        b.build()
    }

    /**
     * La gema y sus ondas en la capa de 108 del icono adaptativo. La gema es la de Ludolog
     * (ic_launcher_foreground.xml de ludolog-front-end, coordenadas de companion.svg, 601 x 1046)
     * a 48 dp de alto en vez de 56, para dejar sitio a las ondas dentro del circulo de 66 que la
     * mascara siempre deja ver.
     */
    private fun linkMark(b: ImageVector.Builder) {
        b.addGroup(translationX = 40.21f, translationY = 30f, scaleX = 0.04589f, scaleY = 0.04589f)
        fun face(color: Long, path: String) = b.addPath(addPathNodes(path), fill = SolidColor(Color(color)))
        face(0xFFA3232A, "M300,0 L301,1043 L601,416 Z")
        face(0xFFE25A5E, "M301,1043 L0,416 L300,0 Z")
        face(0xFF4E0D10, "M300.4,592.7 L600.5,416.5 L300.5,1045.5 Z")
        face(0xFFA3232A, "M300.6,593.7 L0.5,417.5 L300.5,1046.5 Z")
        face(0xFFC83A40, "M300.36,591.59 L497.86,474.7 L298.35,337.66 L88.75,468.65 Z")
        face(0xFF1C0507, "M365.5,474.2 L346.5,500.7 L300.5,511.7 L254.5,500.7 L235.5,474.2 L254.5,447.7 L300.5,436.7 L346.5,447.7 Z")
        b.clearGroup()
        // Las ondas: arcos de 70 grados centrados en la gema, la cercana clara y la lejana apagada.
        fun wave(color: Long, path: String) = b.addPath(addPathNodes(path), stroke = SolidColor(Color(color)),
            strokeLineWidth = 3.5f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round)
        wave(0xFFE25A5E, "M36.8,41.96 A21,21 0 0,0 36.8,66.04 M71.2,41.96 A21,21 0 0,1 71.2,66.04")
        wave(0xFFA3232A, "M31.06,37.94 A28,28 0 0,0 31.06,70.06 M76.94,37.94 A28,28 0 0,1 76.94,70.06")
    }

    // Iconos de 24 dp (trazos de Material); el color lo pone Icon(tint = ...).
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
            .build()

    /** Para el catalogo: el archivo del juego (un cartucho), su caratula y su video. */
    val Rom by lazy {
        icon("rom", "M18,2h-8L4.02,8 4,20c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,8h-2V4h2v4zM15,8h-2V4h2v4zM18,8h-2V4h2v4z")
    }
    val Cover by lazy {
        icon("cover", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }
    val Video by lazy {
        icon("video", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    }

    val Upload by lazy { icon("upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z") }
    val Refresh by lazy {
        icon("refresh", "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 " +
            "6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 " +
            "3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z")
    }
    val Close by lazy {
        icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    }
    val Search by lazy {
        icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 " +
            "5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14" +
            "C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    }
    val Folder by lazy {
        icon("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8" +
            "c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val ArrowUp by lazy { icon("arrow_up", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z") }
    val ArrowDown by lazy { icon("arrow_down", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z") }
    val More by lazy {
        icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 " +
            "2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    }
}
