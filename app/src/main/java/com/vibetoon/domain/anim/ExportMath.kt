package com.vibetoon.domain.anim

/** Арифметика экспорта: размер кадра и соответствие кадров видео кадрам проекта. */
object ExportMath {
    val QUALITIES = listOf(1080, 720, 480)          // короткая сторона кадра
    val FPS_OPTIONS = listOf(24, 30, 60)
    // GIF намеренно ограничен: 1/100 секунды на кадр и большой объём памяти.
    val GIF_QUALITIES = listOf(480, 360)
    val GIF_FPS_OPTIONS = listOf(12, 24, 30)
    val SEQUENCE_FPS_OPTIONS = FPS_OPTIONS

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
