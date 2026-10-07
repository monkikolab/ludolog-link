package com.felp.ludologlink.pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.use
import com.felp.ludolog.kit.ui.KitIcons
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Para empaquetar: dibuja el icono del kit en PNG de 256 px (de ahi sale el .ico).
 * Uso: "Ludolog Link.exe" --export-icon salida.png
 */
fun exportIcon(out: File) {
    ImageComposeScene(256, 256) {
        Image(rememberVectorPainter(KitIcons.App), null, Modifier.fillMaxSize())
    }.use { scene ->
        val png = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("couldn't encode")
        out.writeBytes(png.bytes)
    }
}
