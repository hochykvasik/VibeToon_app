package com.vibetoon.domain.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Экранная геометрия сетки вида, не часть сцены и экспорта. */
data class CanvasGridLines(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val vertical: List<Float>, val horizontal: List<Float>
)

object CanvasGrid {
    val densityOptions = listOf(2, 4, 8, 16, 32, 64)
    fun density(value: Int): Int = if (value in densityOptions) value else 16

    /** [cellsAcross] — число квадратных ячеек по ширине холста. */
    fun lines(viewW: Float, viewH: Float, canvasW: Int, canvasH: Int, camera: Camera, cellsAcross: Int = 16): CanvasGridLines? {
        if (viewW <= 0 || viewH <= 0 || canvasW <= 0 || canvasH <= 0 ||
            !viewW.isFinite() || !viewH.isFinite() || !camera.zoom.isFinite() || camera.zoom <= 0 ||
            !camera.panX.isFinite() || !camera.panY.isFinite()) return null
        val scale = Camera.fitScale(viewW, viewH, canvasW, canvasH) * camera.zoom
        if (!scale.isFinite() || scale <= 0f) return null
        val x0 = viewW / 2f - canvasW * scale / 2f + camera.panX
        val y0 = viewH / 2f - canvasH * scale / 2f + camera.panY
        val left = max(0f, x0); val top = max(0f, y0)
        val right = min(viewW, x0 + canvasW * scale); val bottom = min(viewH, y0 + canvasH * scale)
        if (left >= right || top >= bottom) return null
        val pixelStep = (canvasW.toFloat() / density(cellsAcross)) * scale
        fun axis(start: Float, lo: Float, hi: Float): List<Float> {
            val first = ceil(((lo - start) / pixelStep).toDouble()).toInt()
            val last = floor(((hi - start) / pixelStep).toDouble()).toInt()
            if (first > last) return emptyList()
            // Защита для экстремально узких холстов: не рисуем сотни тысяч линий.
            val count = last.toLong() - first.toLong() + 1
            val stride = ((count + 2047L) / 2048L).coerceAtLeast(1L)
            return generateSequence(first.toLong()) { it + stride }.takeWhile { it <= last.toLong() }
                .map { start + it.toFloat() * pixelStep }.toList()
        }
        return CanvasGridLines(left, top, right, bottom, axis(x0, left, right), axis(y0, top, bottom))
    }
}
