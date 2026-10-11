package com.felp.frontcomp

import android.media.MediaFormat
import com.felp.ludologlink.pc.VideoPrep
import java.io.File

/*
 * Lo que el scraper de Ludolog (Scrape.kt, ArtSources.kt, ArtFree.kt, ArtVideo.kt, Match.kt,
 * compilados tal cual) pide de archivos que el PC no compila. Ver docs/ludolog-link.md.
 *
 * Imitan VideoScale, VideoTrim, TapeQueue, ArtRevisions, ArtKind (de Art.kt) y ArtIndex. Los
 * archivos que se compilan tal cual estan en ludologSources, en build.gradle.kts: si en Ludolog
 * empiezan a pedir algo nuevo de fuera, aqui va su imitacion o el PC deja de compilar.
 */

/**
 * VideoScale de Ludolog, que en la consola recodifica con MediaCodec. Aqui todo pasa por FFmpeg
 * (VideoPrep) con las mismas reglas —segundos, alto, caudal alto² × 7, sonido—, que son las que
 * le llegan del scraper desde los ajustes de esa consola. Siempre convierte: lo que baja de
 * archive.org viene como lo subio cada cual, y asi llega a la consola igual que lo dejaria ella.
 */
internal object VideoScale {
    fun videoFormat(src: File): MediaFormat? = MediaFormat()

    @Suppress("UNUSED_PARAMETER")
    fun worthIt(format: MediaFormat, maxHeight: Int, maxBitrate: Int, fileBitrate: Int): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun fileBitrate(src: File, format: MediaFormat): Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun convert(src: File, dest: File, maxSeconds: Int, maxHeight: Int, bitrate: Int, keepAudio: Boolean): Boolean =
        runCatching { VideoPrep.convert(src, dest, VideoPrep.Rules(maxSeconds, maxHeight, keepAudio)); true }
            .onFailure { android.util.Log.w("Ludolog", "video ${dest.name}: ${it.message}") }
            .getOrDefault(false)
}

/** VideoTrim de Ludolog: aqui tambien por FFmpeg, sin cambiar el alto. */
internal object VideoTrim {
    @Suppress("UNUSED_PARAMETER")
    fun shrink(src: File, dest: File, maxSeconds: Int, keepAudio: Boolean = true, audioFrom: File? = null): Boolean =
        VideoScale.convert(src, dest, maxSeconds, 0, 0, keepAudio)
}

/** El sonido que Ludolog saca de cada video lo hace la consola: en el PC no hay nada que olvidar. */
internal object TapeQueue {
    @Suppress("UNUSED_PARAMETER")
    fun forget(video: File) = Unit
}

/** En el PC nadie esta pintando lo que se baja: el aviso a la consola lo da MEDIA_CHANGED. */
internal object ArtRevisions {
    @Suppress("UNUSED_PARAMETER")
    fun bump(file: File) = Unit
}

/** Las carpetas de arte de Ludolog (Art.kt), igual que alli. */
enum class ArtKind(val folder: String) {
    COVER("covers"),
    BOX3D("3dboxes"),
    MIXIMAGE("miximages"),
    TITLESCREEN("titlescreens"),
    SCREENSHOT("screenshots"),
    MARQUEE("marquees"),
    FANART("fanart"),
}

/**
 * Lo que la consola ya tiene, para que el scraper no lo busque otra vez: el ArtIndex de Ludolog
 * se arma con sus carpetas; aqui, con la lista que da Link (ver Names.hasArt / hasVideo).
 */
internal class ArtIndex(private val art: (Game) -> Boolean, private val vid: (Game) -> Boolean) {
    fun has(game: Game): Boolean = art(game)

    /** El scraper solo mira si el video "es de verdad" (VideoRemnants.real): este lo es. */
    fun video(game: Game): File? = if (vid(game)) Present else null

    private object Present : File("present.mp4") {
        private fun readResolve(): Any = Present
        override fun isFile() = true
        override fun length() = Long.MAX_VALUE
    }
}
