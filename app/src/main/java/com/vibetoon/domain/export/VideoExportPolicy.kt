package com.vibetoon.domain.export

import com.vibetoon.domain.anim.ExportMath
import com.vibetoon.domain.anim.FrameRates

/** Спринт 32: кодек видео в MP4. */
enum class VideoCodec(val mime: String, val label: String) {
    AVC("video/avc", "H.264"),
    HEVC("video/hevc", "HEVC (H.265)")
}

/**
 * Что умеют кодеки устройства (реализация - render/VideoCodecs по MediaCodecList, в тестах - таблица).
 * Все размеры - уже выровненные (кратные 16) ширина и высота кадра.
 */
interface CodecSupport {
    /** Есть аппаратный или программный кодировщик [codec] для кадра [width]×[height]. */
    fun supports(codec: VideoCodec, width: Int, height: Int): Boolean
    /** Максимальная заявленная частота для этого размера; null - кодек не сообщает. */
    fun maxFps(codec: VideoCodec, width: Int, height: Int): Double? = null
    /** Верхний предел битрейта кодировщика; null - неизвестен. */
    fun maxBitrate(codec: VideoCodec): Int? = null
}

/**
 * Итог проверки: что реально будет записано. [notes] - русские строки-шаблоны для окна экспорта и итога
 * (переводятся через L()), пусто - всё как запрошено.
 */
data class VideoPlan(
    val codec: VideoCodec,
    val shortSide: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Частота для KEY_FRAME_RATE (подсказка кодеку): не выше заявленной; метки времени всё равно идут по [fps]. */
    val rateHint: Int,
    val bitrate: Int,
    val notes: List<String>,
    /** false - ни один кодек не берёт даже самый маленький размер: экспорт MP4 невозможен. */
    val possible: Boolean = true
) {
    val downgraded: Boolean get() = notes.isNotEmpty()
}

object VideoExportPolicy {
    const val MIN_BITRATE = 4_000_000
    const val MAX_AVC_BITRATE = 80_000_000
    const val MAX_HEVC_BITRATE = 50_000_000

    /** Ступени отката по размеру: запрошенный и все меньшие пресеты. */
    private fun ladder(shortSide: Int): List<Int> = listOf(shortSide) + ExportMath.QUALITIES.filter { it < shortSide }

    /**
     * Битрейт: H.264 - 0,25 бит на пиксель кадра (720p30 ≈ 6,9 Мбит/с), HEVC при том же качестве ~ 60% от этого.
     * Ограничен снизу [MIN_BITRATE], сверху - пределом формата и кодировщика ([cap]).
     */
    fun bitrate(width: Int, height: Int, fps: Int, codec: VideoCodec = VideoCodec.AVC, cap: Int? = null): Int {
        val avc = width.toLong() * height * fps.coerceAtLeast(1) * 25 / 100
        val raw = if (codec == VideoCodec.HEVC) avc * 6 / 10 else avc
        val max = minOf(if (codec == VideoCodec.HEVC) MAX_HEVC_BITRATE else MAX_AVC_BITRATE, cap?.takeIf { it > 0 } ?: Int.MAX_VALUE)
        return raw.coerceIn(minOf(MIN_BITRATE, max).toLong(), max.toLong()).toInt()
    }

    /**
     * Выбор кодека и размера: сначала запрошенные, затем HEVC -> H.264 на том же размере, затем меньшие размеры.
     * Частота не понижается никогда (кадры анимации не теряются): если кодек заявляет меньше, это только подсказка
     * [VideoPlan.rateHint] и пояснение в [VideoPlan.notes].
     */
    fun plan(canvasW: Int, canvasH: Int, shortSide: Int, fps: Int, codec: VideoCodec, support: CodecSupport): VideoPlan {
        val f = FrameRates.clamp(fps)
        val codecs = if (codec == VideoCodec.HEVC) listOf(VideoCodec.HEVC, VideoCodec.AVC) else listOf(VideoCodec.AVC)
        val notes = ArrayList<String>()
        var chosen: Triple<VideoCodec, Int, Pair<Int, Int>>? = null
        loop@ for (side in ladder(shortSide)) {
            val size = ExportMath.videoSize(canvasW, canvasH, side)
            for (c in codecs) if (support.supports(c, size.first, size.second)) { chosen = Triple(c, side, size); break@loop }
        }
        if (chosen == null) {
            val (w, h) = ExportMath.videoSize(canvasW, canvasH, shortSide)
            return VideoPlan(codec, shortSide, w, h, f, f, bitrate(w, h, f, codec), listOf("На устройстве нет кодировщика видео для этого размера"), possible = false)
        }
        val (c, side, size) = chosen
        val (w, h) = size
        val (rw, rh) = ExportMath.videoSize(canvasW, canvasH, shortSide)
        if (c != codec) {
            val anyHevc = ladder(shortSide).any { s -> ExportMath.videoSize(canvasW, canvasH, s).let { support.supports(VideoCodec.HEVC, it.first, it.second) } }
            notes += if (anyHevc) "HEVC на этом устройстве не берёт {}×{}: видео будет в H.264".fill(rw, rh)
                else "На устройстве нет кодировщика HEVC: видео будет в H.264"
        }
        if (side != shortSide) {
            notes += "Устройство не кодирует {}×{} ({}): видео будет {}×{}"
                .fill(rw, rh, c.label, w, h)
        }
        val max = support.maxFps(c, w, h)
        val hint = if (max != null && max >= 1.0 && max < f) max.toInt().coerceAtLeast(1) else f
        if (hint < f) notes += "Кодировщик заявляет до {} fps при {}×{}: файл записан с {} fps, но слабые устройства могут показывать его неплавно"
            .fill(hint, w, h, f)
        return VideoPlan(c, side, w, h, f, hint, bitrate(w, h, f, c, support.maxBitrate(c)), notes)
    }

    private fun String.fill(vararg v: Any): String {
        var s = this
        for (x in v) s = s.replaceFirst("{}", x.toString())
        return s
    }

    /**
     * Уровень H.264 (High) по реальным пределам стандарта: макроблоков в кадре, макроблоков в секунду и битрейту.
     * Возвращает номер уровня ×10 (31, 40, 41, 42, 50, 51, 52, 60...): render переводит его в константу MediaCodecInfo.
     * 1080p30 -> 40, 1080p60 -> 42, 4K30 -> 51, 4K60 -> 52, 1080p120 -> 51.
     */
    fun avcLevel(width: Int, height: Int, fps: Int, bitrate: Int = bitrate(width, height, fps)): Int {
        val mbFrame = ((width + 15) / 16).toLong() * ((height + 15) / 16)
        val mbSec = mbFrame * fps.coerceAtLeast(1)
        val kbit = bitrate / 1000L
        for (l in AVC_LEVELS) if (mbFrame <= l.maxFrame && mbSec <= l.maxMbSec && kbit <= l.maxKbitHigh) return l.id
        return AVC_LEVELS.last().id
    }

    private class AvcLevel(val id: Int, val maxMbSec: Long, val maxFrame: Long, val maxKbitHigh: Long)

    // H.264 Table A-1; битрейт для High = 1,25 × MaxBR
    private val AVC_LEVELS = listOf(
        AvcLevel(31, 108_000, 3_600, 17_500),
        AvcLevel(32, 216_000, 5_120, 25_000),
        AvcLevel(40, 245_760, 8_192, 25_000),
        AvcLevel(41, 245_760, 8_192, 62_500),
        AvcLevel(42, 522_240, 8_704, 62_500),
        AvcLevel(50, 589_824, 22_080, 168_750),
        AvcLevel(51, 983_040, 36_864, 300_000),
        AvcLevel(52, 2_073_600, 36_864, 300_000),
        AvcLevel(60, 4_177_920, 139_264, 300_000),
        AvcLevel(61, 8_355_840, 139_264, 600_000),
        AvcLevel(62, 16_711_680, 139_264, 1_000_000)
    )
}
