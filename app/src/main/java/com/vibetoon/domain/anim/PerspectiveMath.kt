package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Perspective: 4 угловые точки слоя (0 - верх-лево, 1 - верх-право, 2 - низ-право, 3 - низ-лево). Смещение угла
 * (доли размера слоя) хранится как LayerTransform под своим id, поэтому ключи, Undo и сохранение работают как у Warp.
 * Слой рисуется через гомографию 3x3: единичный квадрат -> четырёхугольник из сдвинутых углов.
 */
object PerspectiveMath {
    const val CORNERS = 4
    const val ID_BASE = 300_000_000

    fun pointId(layerId: Int, k: Int) = ID_BASE + layerId * CORNERS + k
    fun isPointId(id: Int) = id >= ID_BASE

    fun cornerX(k: Int) = if (k == 1 || k == 2) 1f else 0f
    fun cornerY(k: Int) = if (k >= 2) 1f else 0f

    /** 8 чисел (dx, dy по 4 углам) на кадре; [fx] = false - только форма привязки. */
    fun offsets(layerId: Int, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        val out = FloatArray(CORNERS * 2)
        for (k in 0 until CORNERS) {
            val id = pointId(layerId, k)
            var dx = base[id]?.dx ?: 0f
            var dy = base[id]?.dy ?: 0f
            if (fx) anim.tracks[id]?.evaluate(frame)?.let { dx += it.dx; dy += it.dy }
            out[k * 2] = dx
            out[k * 2 + 1] = dy
        }
        return out
    }

    fun isIdentity(off: FloatArray) = off.all { it == 0f }

    /**
     * Гомография "единичный квадрат -> четырёхугольник" (Heckbert) для углов с учётом смещений [off].
     * Результат - 9 чисел в порядке столбцов (для glUniformMatrix3fv без транспонирования):
     * X = (a u + b v + c) / (g u + h v + 1), Y = (d u + e v + f) / (g u + h v + 1).
     */
    fun matrix(off: FloatArray): FloatArray {
        val x0 = off[0]; val y0 = off[1]
        val x1 = 1f + off[2]; val y1 = off[3]
        val x2 = 1f + off[4]; val y2 = 1f + off[5]
        val x3 = off[6]; val y3 = 1f + off[7]
        val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
        val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
        val a: Float; val b: Float; val d: Float; val e: Float; var g = 0f; var h = 0f
        val den = dx1 * dy2 - dx2 * dy1
        if ((dx3 == 0f && dy3 == 0f) || kotlin.math.abs(den) < 1e-9f) {
            a = x1 - x0; b = x3 - x0; d = y1 - y0; e = y3 - y0
        } else {
            g = (dx3 * dy2 - dx2 * dy3) / den
            h = (dx1 * dy3 - dx3 * dy1) / den
            a = x1 - x0 + g * x1; b = x3 - x0 + h * x3
            d = y1 - y0 + g * y1; e = y3 - y0 + h * y3
        }
        return floatArrayOf(a, d, g, b, e, h, x0, y0, 1f)
    }

    /** Применяет матрицу (см. [matrix]) к точке (u, v). */
    fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val w = m[2] * u + m[5] * v + m[8]
        return ((m[0] * u + m[3] * v + m[6]) / w) to ((m[1] * u + m[4] * v + m[7]) / w)
    }
}
