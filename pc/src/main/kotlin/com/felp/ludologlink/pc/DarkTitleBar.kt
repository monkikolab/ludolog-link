package com.felp.ludologlink.pc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.felp.frontcomp.Theme
import java.io.File

// DarkTitleBar.kt: la ventana con el tema de Ludolog, en lo que Compose no llega. La barra de
// titulo de Windows (titleBar, por DWM con JNA) y las letras del tema (rememberThemeFonts y
// ThemeFonts), guardadas en Config.themeDir: `themes/<id>/font/` en PcDirs.home. Los dos los usa
// Main.kt. Al final, `files`, un texto suelto que usa Overview.

/** La barra de titulo de Windows con el fondo del tema (modo oscuro en Windows 10 20H1+; el color, solo Windows 11). */
private interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_CAPTION_COLOR = 35

fun titleBar(window: Window, ground: Color, dark: Boolean) {
    runCatching {
        val dwm = Native.load("dwmapi", DwmApi::class.java)
        val hwnd = Native.getComponentPointer(window) ?: return
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(if (dark) 1 else 0), 4)
        // COLORREF va en 0x00BBGGRR.
        val argb = ground.toArgb()
        val bgr = ((argb and 0xFF) shl 16) or (argb and 0xFF00) or ((argb shr 16) and 0xFF)
        dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(bgr), 4)
    }
}

/**
 * La letra del tema si ya se bajo de la consola (`themes/<id>/font/display.*` y `body.*`, como
 * las busca Ludolog). Sin ella, la del sistema.
 */
@Composable
fun rememberThemeFonts(id: String, version: Int = 0): ThemeFonts? = remember(id, version) {
    val dir = File(Config.themeDir(id), "font")
    // Si no se bajo de ninguna consola, la que trae el programa (ver build.gradle.kts, ludologAssets).
    fun bundled(name: String): File? {
        val res = ThemeFonts::class.java.getResourceAsStream("/ludolog/themes/$id/font/$name") ?: return null
        val out = File(dir, name)
        runCatching { dir.mkdirs(); res.use { r -> out.outputStream().use { r.copyTo(it) } } }
        return out.takeIf { it.isFile }
    }
    fun find(stem: String) = listOf("ttf", "otf").map { File(dir, "$stem.$it") }.firstOrNull { it.isFile }
        ?: listOf("ttf", "otf").firstNotNullOfOrNull { bundled("$stem.$it") }
    val d = find("display")
    val b = find("body")
    if (d == null && b == null) null else ThemeFonts(d, b)
}

/**
 * La letra que trae instalada un tema (`themes/<id>/font/display.*` y `body.*`), bajada de la
 * consola y guardada en el PC. Si no esta, la del sistema, como hace Ludolog.
 */
class ThemeFonts(private val display: File?, private val body: File?) {
    fun applyTo(t: Theme): Theme {
        fun fam(f: File?): FontFamily? = f?.let { runCatching { FontFamily(Font(it)) }.getOrNull() }
        return t.copy(display = fam(display) ?: t.display, body = fam(body) ?: t.body)
    }
}

/** "1 file", "3 files". */
fun files(n: Int) = if (n == 1) "1 file" else "$n files"
