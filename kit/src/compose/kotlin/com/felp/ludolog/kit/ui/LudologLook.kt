package com.felp.ludolog.kit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.felp.frontcomp.Chrome
import com.felp.frontcomp.LocalRowInverted
import com.felp.frontcomp.LocalTheme
import com.felp.frontcomp.PANE_BROW
import com.felp.frontcomp.SelectionStyle
import com.felp.frontcomp.Theme
import com.felp.frontcomp.browLabel
import com.felp.frontcomp.instrumentPane
import com.felp.frontcomp.panelChrome
import com.felp.frontcomp.selectionBar

/**
 * Ludolog Link (la del PC y la de la consola) con el aspecto de Ludolog: el MISMO `Theme` que usa el front-end (ThemeData.kt,
 * compilado tal cual) y sus mismos adornos (Ornaments.kt). Aqui solo se traduce a lo que
 * Material necesita y se ponen los tres gestos que Ludolog repite en todas partes: la caja de
 * cada tema, la fila elegida y el barrido del de fosforo.
 */
@Composable
fun LudologLook(theme: Theme, content: @Composable () -> Unit) {
    // La version simplificada del tema, la que pidio el usuario para Link: sus colores, su letra,
    // sus esquinas, su tipo de caja y como marca lo elegido, y NINGUN efecto. `ornament` apagado
    // es lo mismo que hace Ludolog en el minimalista: fuera rombos, vitrales y latido.
    val t = remember(theme) { theme.copy(ornament = false) }
    val density = LocalDensity.current
    val shape = RoundedCornerShape(t.corner)
    CompositionLocalProvider(
        LocalTheme provides t,
        LocalDensity provides Density(density.density, density.fontScale * t.typeScale),
    ) {
        MaterialTheme(
            colorScheme = schemeFor(t),
            typography = typographyFor(t),
            shapes = Shapes(shape, shape, shape, shape, shape),
        ) {
            // Sin el barrido del de fosforo: es un efecto, y en un monitor cortaba las letras.
            content()
        }
    }
}

private fun schemeFor(t: Theme) = run {
    val line = t.line ?: t.accent
    // Escalones de fondo para las cajas: hacia la tinta, muy poco. En el de fosforo no hay
    // grises (ver MainframeTheme): las cajas son el mismo negro y las marca su marco.
    fun step(f: Float) = if (t.phosphor) t.ground else lerp(t.ground, t.ink, f)
    val base = if (t.light) lightColorScheme() else darkColorScheme()
    base.copy(
        primary = t.accent, onPrimary = t.ground,
        primaryContainer = lerp(t.ground, t.accent, .25f), onPrimaryContainer = t.ink,
        secondary = t.dim, onSecondary = t.ground,
        secondaryContainer = if (t.selection == SelectionStyle.INVERT) t.ink else t.selectionFill,
        onSecondaryContainer = if (t.selection == SelectionStyle.INVERT) t.ground else t.ink,
        tertiary = t.accent,
        background = t.ground, onBackground = t.ink,
        surface = t.ground, onSurface = t.ink,
        surfaceVariant = step(.06f), onSurfaceVariant = t.dim,
        surfaceContainerLowest = t.ground,
        surfaceContainerLow = step(.025f),
        surfaceContainer = step(.04f),
        surfaceContainerHigh = step(.06f),
        surfaceContainerHighest = step(.08f),
        outline = line, outlineVariant = line.copy(alpha = .5f),
        error = Look.dangerOf(t), onError = t.ground,
        inverseSurface = t.ink, inverseOnSurface = t.ground,
    )
}

private fun typographyFor(t: Theme): Typography {
    val base = Typography()
    fun TextStyle.body() = copy(fontFamily = t.body, letterSpacing = t.itemTracking)
    fun TextStyle.title() = copy(fontFamily = t.display, letterSpacing = t.titleTracking, fontWeight = FontWeight.Bold)
    fun TextStyle.caption() = copy(fontFamily = t.body, letterSpacing = t.captionTracking)
    return base.copy(
        displayLarge = base.displayLarge.title(), displayMedium = base.displayMedium.title(),
        displaySmall = base.displaySmall.title(),
        headlineLarge = base.headlineLarge.title(), headlineMedium = base.headlineMedium.title(),
        headlineSmall = base.headlineSmall.title(),
        titleLarge = base.titleLarge.title(), titleMedium = base.titleMedium.body(), titleSmall = base.titleSmall.caption(),
        bodyLarge = base.bodyLarge.body(), bodyMedium = base.bodyMedium.body(), bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.caption(), labelMedium = base.labelMedium.caption(),
        labelSmall = base.labelSmall.caption(),
    )
}

/** Colores con significado. En el de fosforo hay UN color: todo lo que avisa es el acento. */
object Look {
    fun dangerOf(t: Theme) = if (t.phosphor) t.accent else Color(0xFFE5484D)

    val accent: Color @Composable get() = LocalTheme.current.accent
    val ink: Color @Composable get() = LocalTheme.current.ink
    val danger: Color @Composable get() = dangerOf(LocalTheme.current)
    val warn: Color @Composable get() = LocalTheme.current.let { if (it.phosphor) it.ink else Color(0xFFE0A030) }
    val ok: Color @Composable get() = LocalTheme.current.let { if (it.phosphor) it.dim else Color(0xFF4CB782) }
    val off: Color @Composable get() = LocalTheme.current.faint
    val shape @Composable get() = RoundedCornerShape(LocalTheme.current.corner)

    /** Un titulo como los pone el tema: en mayusculas si las usa. */
    @Composable
    fun title(s: String) = if (LocalTheme.current.upperTitles) s.uppercase() else s
}

/**
 * La fila elegida, como la marca Ludolog: barra con su latido (y sus rombos en el gotico),
 * bloque invertido en el de fosforo, o un marco.
 */
@Composable
fun Modifier.selectedRow(selected: Boolean): Modifier {
    if (!selected) return this
    val t = LocalTheme.current
    return when (t.selection) {
        SelectionStyle.BAR -> selectionBar(fill = t.selectionFill, bar = t.accent, ornament = t.ornament)
        // Quieto: el latido es un efecto.
        SelectionStyle.INVERT -> background(t.ink)
        SelectionStyle.OUTLINE -> border(1.dp, t.accent, RoundedCornerShape(t.corner))
    }
}

/** El contenido de una fila elegida con el texto en el color que toca (invertido en el de fosforo). */
@Composable
fun RowContent(selected: Boolean, content: @Composable () -> Unit) {
    val inverted = selected && LocalTheme.current.selection == SelectionStyle.INVERT
    CompositionLocalProvider(LocalRowInverted provides inverted) {
        if (inverted) {
            CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides LocalTheme.current.ground,
                content = content,
            )
        } else content()
    }
}

/**
 * Una caja con titulo, como las de Ludolog en cada tema: filete (minimal), reglas sueltas con
 * rombos (gotico) o caja de instrumento con el titulo DENTRO de la ceja (retro-futurista).
 */
@Composable
fun Pane(
    title: String?,
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    padding: Int = 16,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalTheme.current
    when (t.chrome) {
        Chrome.INSTRUMENT -> Column(
            modifier.background(fill).instrumentPane(ticks = false).browLabel(title)
                .padding(start = (padding).dp, end = padding.dp, top = PANE_BROW + 10.dp, bottom = (padding - 2).dp),
            content = content,
        )
        else -> Column(modifier.panelChrome(fill).padding(padding.dp)) {
            if (title != null) {
                Text(Look.title(title), style = MaterialTheme.typography.titleSmall,
                    color = if (t.chrome == Chrome.RULES) t.ink else t.dim)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
            }
            content()
        }
    }
}



