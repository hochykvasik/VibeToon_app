package com.vibetoon.domain.anim

/** Арифметика экспорта: размер кадра и соответствие кадров видео кадрам проекта. */
object ExportMath {
    /** Короткая сторона кадра; 2160 - 4K (спринт 32), доступность проверяет VideoExportPolicy по кодекам устройства. */
    val QUALITIES = listOf(2160, 1440, 1080, 720, 480)
    const val UHD = 2160
    /** Спринт 32: любые 1–120 fps; в окне экспорта к пресетам добавляется частота проекта (FrameRates.exportOptions). */
    val FPS_OPTIONS = FrameRates.EXPORT_PRESETS
    // GIF намеренно ограничен: 1/100 секунды на кадр (не чаще 50 fps) и большой объём памяти.
    val GIF_QUALITIES = listOf(480, 360)
    val GIF_FPS_OPTIONS = FrameRates.GIF_PRESETS
    val SEQUENCE_FPS_OPTIONS = FPS_OPTIONS

    /** Подпись разрешения: 2160 -> «4K (2160p)», 1440 -> «1440p (2K)». */
    fun qualityLabel(shortSide: Int): String = when (shortSide) {
        UHD -> "4K (2160p)"
        1440 -> "1440p (2K)"
        else -> "${shortSide}p"
    }

    /** Размер видео: короткая сторона = [shortSide], пропорции холста, обе стороны чётные (требование H.264). */
    fun size(canvasW: Int, canvasH: Int, shortSide: Int): Pair<Int, Int> {
        val cw = canvasW.coerceAtLeast(1)
        val ch = canvasH.coerceAtLeast(1)
        val k = shortSide.toDouble() / minOf(cw, ch)
        fun even(v: Double) = (Math.round(v).toInt() / 2 * 2).coerceAtLeast(2)
        return even(cw * k) to even(ch * k)
    }

    /** Размер видео: как [size], но обе стороны кратны 16 (H.264/кодек Surface). */
    fun videoSize(canvasW: Int, canvasH: Int, shortSide: Int): Pair<Int, Int> {
        val cw = canvasW.coerceAtLeast(1)
        val ch = canvasH.coerceAtLeast(1)
        val k = shortSide.toDouble() / minOf(cw, ch)
        fun aligned(v: Double) = (Math.round(v).toInt() / 16 * 16).coerceAtLeast(16)
        return aligned(cw * k) to aligned(ch * k)
    }

    /** Сколько кадров в видео с частотой [outFps] для проекта из [durationFrames] кадров при [projectFps]. */
    fun frameCount(durationFrames: Int, projectFps: Int, outFps: Int): Int =
        Math.round(durationFrames.toDouble() * outFps / projectFps.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    /** Кадр проекта, который виден в момент [index]-го кадра видео. */
    fun projectFrame(index: Int, projectFps: Int, outFps: Int, durationFrames: Int): Int =
        (index.toLong() * projectFps / outFps.coerceAtLeast(1)).toInt().coerceIn(0, (durationFrames - 1).coerceAtLeast(0))
}
