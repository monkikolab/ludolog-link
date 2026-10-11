package com.felp.frontcomp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/*
 * Lo que Ornaments.kt y Pixel.kt de Ludolog piden de otros archivos de Ludolog que ni la app de la
 * consola ni la del PC compilan (Theme.kt, Modal.kt, MainActivity.kt, Motion.kt; en el PC, ademas, tocan
 * Android). Aqui va una version propia, con el mismo nombre y en el mismo paquete, para que esos
 * archivos compilen tal cual. Esta en el kit: lo compilan las dos apps (ver los build.gradle.kts de
 * console/app y de pc), y la de la consola usa tambien MenuInk y MenuDim en sus pantallas. Si
 * Ornaments.kt empieza a usar otro simbolo de Ludolog, fallan las dos compilaciones: se añade aqui.
 * Ver ludolog-front-end/docs/ludolog-link.md.
 */

/** El tema puesto. En Ludolog vive en Theme.kt, junto a AppTheme. */
val LocalTheme = compositionLocalOf { GalleryTheme }

/** Si la fila esta invertida (seleccion INVERT). En Ludolog, LocalRowInverted de Modal.kt. */
val LocalRowInverted = staticCompositionLocalOf { false }

private val inverted: Boolean @Composable get() = LocalRowInverted.current

// Igual que en MainActivity.kt.
internal val MenuInk: Color
    @Composable get() = if (inverted) LocalTheme.current.ground else LocalTheme.current.ink
internal val MenuDim: Color
    @Composable get() =
        if (inverted) LocalTheme.current.ground.copy(alpha = .72f) else LocalTheme.current.dim
internal val MenuFaint: Color
    @Composable get() =
        if (inverted) LocalTheme.current.ground.copy(alpha = .48f) else LocalTheme.current.faint
internal val MenuGround: Color @Composable get() = LocalTheme.current.ground
internal val MenuLine: Color @Composable get() = LocalTheme.current.let { it.line ?: it.accent }
internal val MenuBody: FontFamily @Composable get() = LocalTheme.current.body
internal val MenuDisplay: FontFamily @Composable get() = LocalTheme.current.display

/**
 * Lo que late, de [low] a 1 y vuelta, con la misma curva coseno que Motion.kt. Late siempre: en el
 * PC no hay reposo de pantalla que respetar, y en la consola esta version tampoco lo mira.
 */
@Composable
internal fun rememberPulse(low: Float, periodMs: Int = 1900): State<Float> {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase = t.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "phase",
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
    )
    return remember(low) {
        derivedStateOf { low + (1f - low) * (0.5f - 0.5f * kotlin.math.cos(phase.value * 2f * Math.PI.toFloat())) }
    }
}
