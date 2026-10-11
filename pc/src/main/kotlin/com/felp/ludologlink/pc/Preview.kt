package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.Look
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Ver la caratula o el video de un juego pasando el raton por ART o VID en la lista de ROMs.
 *
 * La consola da el archivo de donde lo encontraria Ludolog (tambien de ES-DE): ver
 * GET /ludolog/preview. Se guarda en la carpeta temporal mientras dure la sesion; cuando el arte
 * de esa consola cambia (ver AppState.loadArt), se olvida.
 *
 * La lista de ROMs ya no existe: hoy la piden los iconos de caratula y video de la tabla de Games
 * (PartIcon, en CatalogView.kt) y el panel del juego, y CompanionCoversPc para sus miniaturas.
 * Lo que ya esta en el PC (el catalogo) se ve con FilePreview, sin pasar por la consola.
 */
object Previews {
    private val dir = File(System.getProperty("java.io.tmpdir"), "ludolog-link-preview").apply { mkdirs() }
    private val got = ConcurrentHashMap<String, File>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    private fun key(e: ConsoleEntry, f: RomFile, video: Boolean) = "${e.id}|${f.system}|${f.name}|$video"

    suspend fun file(e: ConsoleEntry, f: RomFile, video: Boolean): File? = withContext(Dispatchers.IO) {
        val k = key(e, f, video)
        got[k]?.takeIf { it.isFile }?.let { return@withContext it }
        if (k in missing) return@withContext null
        val systems = listOfNotNull(Names.game(e, f)?.systemId, f.system).distinct()
        val dest = File(dir, "${k.hashCode().toUInt()}" + if (video) ".mp4" else ".img")
        val ok = runCatching { e.link().preview(systems, com.felp.ludolog.kit.Protocol.stemOf(f.name), video, dest) }.getOrDefault(false)
        if (ok) got[k] = dest else missing += k
        dest.takeIf { ok }
    }

    /** El arte de esa consola cambio: lo visto ya no vale. */
    fun forget(consoleId: String) {
        got.keys.filter { it.startsWith("$consoleId|") }.forEach { got.remove(it)?.delete() }
        missing.removeIf { it.startsWith("$consoleId|") }
    }
}

/** La ventanita: la caratula entera o el video en bucle, sin sonido. */
@Composable
fun MediaPreview(e: ConsoleEntry, f: RomFile, video: Boolean) {
    val file by produceState<File?>(null, e.id, f.key, video) { value = Previews.file(e, f, video) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(e.id, f.key, video) { delay(6_000); if (file == null) failed = true }
    PreviewFrame(file, video, failed)
}

/** Lo mismo con un archivo que ya esta en este PC (el catalogo). */
@Composable
fun FilePreview(file: File, video: Boolean) = PreviewFrame(file, video, failed = false)

@Composable
private fun PreviewFrame(file: File?, video: Boolean, failed: Boolean) {
    Surface(shape = Look.shape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, Look.shape)) {
        Box(Modifier.size(if (video) 320.dp else 260.dp, 240.dp), contentAlignment = Alignment.Center) {
            val fl = file
            when {
                fl != null && video -> VideoLoop(fl)
                fl != null -> CoverImage(fl)
                failed -> Text("Couldn't load it", style = MaterialTheme.typography.bodySmall)
                else -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CoverImage(file: File) {
    val bmp by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        ?: Text("Loading…", style = MaterialTheme.typography.bodySmall)
}

/** El video con FFmpeg, fotograma a fotograma a su ritmo, una y otra vez mientras se mire. */
@Composable
private fun VideoLoop(file: File) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            val conv = Java2DFrameConverter()
            while (isActive) {
                val g = FFmpegFrameGrabber(file)
                try {
                    g.start()
                    val stepMs = (1000.0 / (g.frameRate.takeIf { it.isFinite() && it > 0 } ?: 30.0)).toLong()
                    while (isActive) {
                        val t0 = System.currentTimeMillis()
                        val fr = g.grabImage() ?: break
                        conv.convert(fr)?.let { frame = it.toComposeImageBitmap() }
                        delay((stepMs - (System.currentTimeMillis() - t0)).coerceAtLeast(1))
                    }
                } catch (x: Exception) {
                    break
                } finally {
                    runCatching { g.release() }
                }
                // Al volver a empezar: un respiro. Uno sin fotogramas legibles volvia en el acto y se
                // comia un nucleo entero mientras el raton estaba encima.
                delay(250)
            }
        }
    }
    frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        ?: Text("Loading…", style = MaterialTheme.typography.bodySmall)
}
