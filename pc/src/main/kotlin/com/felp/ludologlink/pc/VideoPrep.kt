package com.felp.ludologlink.pc

import com.felp.frontcomp.AllThemes
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.FFmpegLogCallback
import java.io.File
import kotlin.math.roundToInt

/**
 * Un video cualquiera del PC, convertido a lo que Ludolog guardaria si lo hubiera bajado ella.
 *
 * Las reglas son las de Ludolog (ArtVideo.kt / VideoScale.kt, con sus ajustes de Prefs): solo
 * los primeros `look.clip` segundos (15; 0 es entero), a lo sumo `look.vheight` de alto (240; 0
 * deja la del origen), caudal de alto² × 7 (240p ≈ 400 kbps), un fotograma clave por segundo,
 * los fotogramas por segundo del origen entre 10 y 60, y sonido solo si algun tema lo oye
 * (`look.sound.<tema>`). H.264 en .mp4, que es lo que su reproductor y TapeQueue esperan.
 *
 * Ludolog copia el sonido tal cual porque lo que baja ya es .mp4; aqui puede venir cualquier
 * cosa, asi que se pasa a AAC. En la consola no se vuelve a tocar.
 *
 * Lo usan AppState (importVideo, mediaEverywhere, fill: videos del PC o del catalogo a un device) y
 * la imitacion de VideoScale del scraper (LudologScrapeShims.kt). FFmpeg llega por JavaCV.
 */
object VideoPrep {

    data class Rules(val seconds: Int, val maxHeight: Int, val keepAudio: Boolean) {
        override fun toString() =
            (if (seconds > 0) "${seconds}s" else "full length") + ", " +
                (if (maxHeight > 0) "${maxHeight}p" else "original size") + if (keepAudio) ", with sound" else ", no sound"
    }

    /** Las reglas de esa consola, de su config.xml (con los mismos valores por defecto que Prefs). */
    fun rules(config: Map<String, Any?>) = Rules(
        seconds = (config["look.clip"] as? Number)?.toInt() ?: 15,
        maxHeight = (config["look.vheight"] as? Number)?.toInt() ?: 240,
        keepAudio = AllThemes.any { config["look.sound.${it.id}"] as? Boolean ?: true },
    )

    /** Lo que se le pide a FFmpeg para H.264, en este orden. El de Windows primero: viene con el sistema. */
    private val ENCODERS = listOf("h264_mf", "libopenh264", "libx264")

    fun encoder(): String? = ENCODERS.firstOrNull { avcodec.avcodec_find_encoder_by_name(it) != null }

    class Cancelled : Exception("cancelled")

    /**
     * [src] a [dest] con [rules]. [progress] recibe de 0 a 1. Lanza si no se puede leer o escribir;
     * [dest] no queda a medias.
     */
    fun convert(
        src: File, dest: File, rules: Rules,
        progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false },
        encoderName: String? = null,
    ) {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        val codec = encoderName ?: encoder() ?: error("no H.264 encoder available")
        // Primero se mira como es: el tamaño de salida depende del de entrada.
        val probe = FFmpegFrameGrabber(src)
        val srcW: Int
        val srcH: Int
        val sar: Double
        try {
            probe.start()
            srcW = probe.imageWidth
            srcH = probe.imageHeight
            sar = probe.aspectRatio.takeIf { it > 0 } ?: 1.0
        } finally {
            probe.release()
        }
        if (srcW <= 0 || srcH <= 0) error("that file has no video")
        val outH = even(if (rules.maxHeight in 1 until srcH) rules.maxHeight else srcH)
        val outW = even((srcW * sar * outH / srcH).roundToInt())

        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            val g = FFmpegFrameGrabber(src)
            try {
                g.imageWidth = outW
                g.imageHeight = outH
                g.start()
                val channels = if (rules.keepAudio) g.audioChannels.coerceAtMost(2) else 0
                val fps = g.frameRate.takeIf { it.isFinite() && it > 0 }?.coerceIn(10.0, 60.0) ?: 30.0
                val limitUs = if (rules.seconds > 0) rules.seconds * 1_000_000L else Long.MAX_VALUE
                val totalUs = minOf(limitUs, g.lengthInTime.takeIf { it > 0 } ?: limitUs).toDouble()
                var shown = 0f   // el sonido y la imagen llegan intercalados: solo hacia delante
                val r = FFmpegFrameRecorder(tmp, outW, outH, channels)
                try {
                    r.format = "mp4"
                    r.videoCodecName = codec
                    r.pixelFormat = avutil.AV_PIX_FMT_YUV420P
                    r.frameRate = fps
                    r.gopSize = fps.roundToInt()
                    r.videoBitrate = outH * outH * 7
                    // En "cbr" el de Windows se pasa un 50 % en partidas de verdad; con tope de pico cumple.
                    if (codec == "h264_mf") r.setVideoOption("rate_control", "pc_vbr")
                    r.setOption("movflags", "+faststart")
                    if (channels > 0) {
                        r.audioCodec = avcodec.AV_CODEC_ID_AAC
                        r.sampleRate = g.sampleRate.takeIf { it > 0 } ?: 48_000
                        r.audioBitrate = 96_000
                    }
                    r.start()
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val f = g.grabFrame(channels > 0, true, true, false) ?: break
                        if (g.timestamp >= limitUs) break
                        if (f.image == null && f.samples == null) continue
                        if (g.timestamp > r.timestamp) r.timestamp = g.timestamp
                        r.record(f)
                        val p = (g.timestamp / totalUs).toFloat().coerceIn(0f, 1f)
                        if (totalUs.isFinite() && p >= shown + 0.01f) { shown = p; progress(p) }
                    }
                    r.stop()
                } finally {
                    r.release()
                }
            } finally {
                g.release()
            }
            if (tmp.length() < 1_000) error("the converted video came out empty")
            dest.delete()
            if (!tmp.renameTo(dest)) error("couldn't save the converted video")
            progress(1f)
        } finally {
            tmp.delete()
        }
    }

    private fun even(n: Int) = (n / 2 * 2).coerceAtLeast(2)
}

/** Diagnostico: --video encoders | --video convert <origen> <destino> <segundos> <alto> <sonido 0|1> [codificador]. */
internal fun videoTest(args: List<String>) {
    when (args.firstOrNull()) {
        "encoders" -> {
            FFmpegLogCallback.set()
            for (n in listOf("h264_mf", "libopenh264", "libx264", "h264_nvenc", "h264_qsv", "h264_amf"))
                println("$n: ${avcodec.avcodec_find_encoder_by_name(n) != null}")
            println("chosen: ${VideoPrep.encoder()}")
        }
        "convert" -> {
            val rules = VideoPrep.Rules(args[3].toInt(), args[4].toInt(), args[5] == "1")
            val t0 = System.currentTimeMillis()
            var last = -1
            VideoPrep.convert(File(args[1]), File(args[2]), rules, progress = { p ->
                val pc = (p * 10).toInt(); if (pc != last) { last = pc; print("${pc * 10}% ") }
            }, encoderName = args.getOrNull(6))
            println("\ndone in ${System.currentTimeMillis() - t0} ms: ${File(args[2]).length() / 1024} KB ($rules)")
        }
    }
}
