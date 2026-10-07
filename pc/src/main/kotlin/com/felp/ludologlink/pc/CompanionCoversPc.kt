package com.felp.ludologlink.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Las caratulas pequeñas del Companion en el PC: el cuaderno solo trae el nombre del juego, y en el
 * PC no esta la biblioteca de la consola. Se buscan, por ese nombre (letras y numeros en minusculas,
 * la misma clave que CompanionCovers en Ludolog y Link):
 *
 * 1. en las miniaturas que Ludolog Link deja en cada device (`companion/covers/`), en las copias de
 *    sus datos en el PC: las de los juegos de otra consola;
 * 2. en las que el PC ya guardo (`%APPDATA%/LudologLink/covers/`);
 * 3. y si no, se le pide a un device conectado que tenga el juego (su caratula, como al pasar el
 *    raton en Games), se reduce y se guarda para la proxima, tambien sin conexion.
 *
 * [find] no espera: si la trae despues, sube [revision] y la pantalla la vuelve a pedir.
 */
internal object CompanionCoversPc {
    private val dir = File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "LudologLink"), "covers").apply { mkdirs() }
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Sube cuando llega una caratula: quien la pidio la vuelve a buscar. */
    var revision by mutableIntStateOf(0)
        private set

    fun key(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    fun find(app: AppState, title: String): File? {
        revision
        val k = key(title)
        if (k.isEmpty()) return null
        // Las miniaturas de Link en las copias de cada device.
        for (c in app.consoles) File(Mirror.dir(c.id), "companion/covers/$k.jpg").takeIf { it.isFile }?.let { return it }
        File(dir, "$k.jpg").takeIf { it.isFile }?.let { return it }
        if (asked.add(k)) scope.launch { fetch(app, title, k) }
        return null
    }

    /** De un device conectado que tenga ese juego (por su nombre en Ludolog, o su IWAD en DoomForge). */
    private suspend fun fetch(app: AppState, title: String, k: String) {
        for (d in app.consoles.filter { it.online == true && it.info?.ludolog != null }) {
            val f = d.games.firstOrNull { f ->
                key(Names.display(d, f)) == k || Names.game(d, f)?.playsAs?.let(::key) == k
            } ?: continue
            val img = Previews.file(d, f, video = false) ?: continue
            val ok = runCatching {
                val src = javax.imageio.ImageIO.read(img) ?: return@runCatching false
                // Pequeña, como las de Link: 160 px de alto.
                val h = 160
                val w = (src.width * h / src.height.coerceAtLeast(1)).coerceAtLeast(1)
                val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                out.createGraphics().apply {
                    setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    drawImage(src, 0, 0, w, h, null)
                    dispose()
                }
                val tmp = File(dir, "$k.part")
                javax.imageio.ImageIO.write(out, "jpg", tmp)
                tmp.renameTo(File(dir, "$k.jpg"))
            }.getOrDefault(false)
            if (ok) { revision++; return }
        }
        // Sin device que lo tenga ahora: se vuelve a intentar la proxima vez que se abra.
        asked.remove(k)
    }
}
