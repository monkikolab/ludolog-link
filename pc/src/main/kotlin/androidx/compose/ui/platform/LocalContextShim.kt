package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * LocalContext de Android, que en escritorio no existe. Las pestanas del Companion de Ludolog lo
 * leen para abrir el cuaderno (Logbook(ctx, withOthers = true)); en el PC, el Logbook de
 * escritorio sabe solo cual abrir. Ver com.felp.frontcomp.Logbook.
 */
val LocalContext = staticCompositionLocalOf { android.content.Context() }
