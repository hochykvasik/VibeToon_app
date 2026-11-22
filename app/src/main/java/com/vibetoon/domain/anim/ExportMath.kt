package com.vibetoon.domain.anim

/** Арифметика экспорта: размер кадра и соответствие кадров видео кадрам проекта. */
object ExportMath {
    val QUALITIES = listOf(1080, 720, 480)          // короткая сторона кадра
    val FPS_OPTIONS = listOf(24, 30, 60)

    /**
     * Выравнивание сторон видео. H.264 работает макроблоками 16x16: стороны, не кратные 16 (1080, 1350 ...), часть
     * аппаратных кодеков (MediaTek, Exynos, старые Adreno) кодирует с ошибкой шага строки - зелёные полосы и мусор.
     */
    const val ALIGN = 16

    /** Размер видео: короткая сторона ≈ [shortSide], пропорции холста, обе стороны кратны [ALIGN] (16). */
    fun size(canvasW: Int, canvasH: Int, shortSide: Int): Pair<Int, Int> {
        val cw = canvasW.coerceAtLeast(1)
        val ch = canvasH.coerceAtLeast(1)
        val k = shortSide.toDouble() / minOf(cw, ch)
        return align(cw * k) to align(ch * k)
    }

    /** Ближайшее кратное [a] (не меньше [a]). */
    fun align(v: Double, a: Int = ALIGN): Int = (Math.round(v / a).toInt() * a).coerceAtLeast(a)

    /**
     * Подгонка под возможности кодека устройства: если [supported] (обычно `VideoCapabilities.isSizeSupported`) не
     * принимает размер, он уменьшается шагами по 10% с сохранением пропорций и выравниванием [align]. null - не нашлось.
     */
    fun encoderSize(w: Int, h: Int, align: Int = ALIGN, supported: (Int, Int) -> Boolean): Pair<Int, Int>? {
        val a = align.coerceAtLeast(2)
        var k = 1.0
        repeat(30) {
            val sw = align(w * k, a); val sh = align(h * k, a)
            if (supported(sw, sh)) return sw to sh
            k *= 0.9
        }
        return null
    }

    /** Сколько кадров в видео с частотой [outFps] для проекта из [durationFrames] кадров при [projectFps]. */
    fun frameCount(durationFrames: Int, projectFps: Int, outFps: Int): Int =
        Math.round(durationFrames.toDouble() * outFps / projectFps.coerceAtLeast(1)).toInt().coerceAtLeast(1)

    /** Кадр проекта, который виден в момент [index]-го кадра видео. */
    fun projectFrame(index: Int, projectFps: Int, outFps: Int, durationFrames: Int): Int =
        (index.toLong() * projectFps / outFps.coerceAtLeast(1)).toInt().coerceIn(0, (durationFrames - 1).coerceAtLeast(0))
}
