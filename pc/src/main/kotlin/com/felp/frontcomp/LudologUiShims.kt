package com.felp.frontcomp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

/*
 * Lo que las pestanas del Companion de Ludolog (StatsTabs.kt, CharacterTab.kt, MetaTabs.kt)
 * piden de archivos que el PC no compila (Modal.kt, Focus.kt, Sfx.kt, Theme.kt, Art.kt), en
 * version de escritorio: raton en vez de mando y sin sonidos. Mismo nombre y paquete, para que
 * esas pestanas compilen tal cual. Ver ludolog-front-end/docs/ludolog-link.md.
 */

/** Los sonidos de la interfaz. El PC no suena. */
object Sfx {
    enum class Cue { MOVE, OPEN, CLOSE, OPTIONS, COMPANION_OPEN, COMPANION_CLOSE }
    fun play(@Suppress("UNUSED_PARAMETER") cue: Cue) = Unit
}

// Igual que en Modal.kt.
internal val LocalCalmSelection = compositionLocalOf { false }
internal fun mutedAccent(accent: Color, ink: Color): Color = lerp(accent, ink, .5f)
internal val ModalRowPadX: Dp
    @Composable get() = if (LocalTheme.current.selection == SelectionStyle.BAR) 14.dp else 10.dp

/**
 * Las filas de una ventana, como ModalRows de Modal.kt pero con raton: un clic elige la fila, y
 * un clic en la ya elegida la abre. La marca de la fila es la misma, por tema.
 */
@Composable
internal fun ModalRows(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    onActivate: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onStep: ((Int) -> Boolean)? = null,
    row: @Composable (Int) -> Unit,
) {
    if (count == 0) return
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { runCatching { listState.animateScrollToItem(selected) } }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(count) { index ->
            val t = LocalTheme.current
            val inverted = t.selection == SelectionStyle.INVERT
            val calm = LocalCalmSelection.current
            val chosen = index == selected
            Box(
                Modifier.fillMaxWidth()
                    .clickable { if (chosen) onActivate() else onSelect(index) }
                    .then(
                        when {
                            chosen && t.selection == SelectionStyle.BAR ->
                                Modifier.selectionBar(t.selectionFill, t.accent, ornament = t.ornament)
                            chosen && inverted && calm -> Modifier.border(2.dp, t.accent)
                            chosen && inverted -> Modifier.background(MenuInk)
                            chosen -> Modifier.border(1.dp, MenuLine)
                            else -> Modifier
                        }
                    )
                    .padding(horizontal = ModalRowPadX, vertical = 9.dp),
            ) {
                CompositionLocalProvider(LocalRowInverted provides (chosen && inverted && !calm)) { row(index) }
            }
        }
    }
}

/** Igual que en Theme.kt: cada imagen, de fosforo en el tema que lo pide. */
@Composable
fun phosphorFilter(): ColorFilter? {
    val t = LocalTheme.current
    if (!t.phosphor) return null
    val c = t.accent
    return remember(c) {
        val g = PHOSPHOR_GAIN
        val r = c.red * g
        val gr = c.green * g
        val b = c.blue * g
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.299f * r, 0.587f * r, 0.114f * r, 0f, 0f,
                    0.299f * gr, 0.587f * gr, 0.114f * gr, 0f, 0f,
                    0.299f * b, 0.587f * b, 0.114f * b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}

/** Lo que se le pasa a la imagen (coil) para un archivo de arte. En el PC, el archivo tal cual. */
internal fun artModel(file: File?): Any? = file

/** Lo que CharacterTab mira de Motion.kt: en el PC no hay salvapantallas ni reposo. */
internal object Motion {
    val idle = androidx.compose.runtime.mutableStateOf(false)
    val away = androidx.compose.runtime.mutableStateOf(false)
    val saver = androidx.compose.runtime.mutableStateOf(false)
    val quiet: Boolean get() = idle.value || saver.value
}

// Igual que en Focus.kt, pero con raton: activar es hacer clic.
val LocalPadEnabled = compositionLocalOf { true }

fun Modifier.padItem(
    onActivate: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    enabled: Boolean = true,
    @Suppress("UNUSED_PARAMETER") scaleWhenFocused: Float = 1.06f,
    @Suppress("UNUSED_PARAMETER") borderColor: Color? = null,
    @Suppress("UNUSED_PARAMETER") borderWidth: Dp = 3.dp,
    @Suppress("UNUSED_PARAMETER") shape: androidx.compose.foundation.shape.RoundedCornerShape =
        androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
): Modifier = this
    .then(if (focusRequester != null) Modifier.focusRequesterOf(focusRequester) else Modifier)
    .clickable(enabled = enabled) { onActivate() }

private fun Modifier.focusRequesterOf(r: androidx.compose.ui.focus.FocusRequester): Modifier =
    this.then(Modifier.focusRequester(r))

/**
 * Igual que en StatsWindow.kt: un color por consola, girando desde el acento en pasos del angulo
 * aureo. Alli con android.graphics.Color (tono en grados); aqui con java.awt.Color (tono de 0 a 1).
 */
internal fun categorical(base: Color, index: Int, light: Boolean = false): Color {
    val argb = base.toArgbInt()
    val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)
    val v = if (light) { if (index % 2 == 0) 0.62f else 0.48f } else { if (index % 2 == 0) 1f else 0.80f }
    val hueDeg = (hsb[0] * 360f + index * 137.508f) % 360f
    val rgb = java.awt.Color.HSBtoRGB(hueDeg / 360f, if (light) 0.85f else 0.70f, v)
    return Color(rgb or (0xFF shl 24))
}

private fun Color.toArgbInt(): Int = this.toArgb()
