package com.vibetoon.domain.input

import kotlin.math.hypot

/** Двойной тап должен быть одним пальцем в нейтральном режиме; рамка выбирает центры слоёв. */
object MarqueePolicy {
    fun secondTap(firstUp: Long, secondDown: Long, x0: Float, y0: Float, x: Float, y: Float, slop: Float): Boolean =
        firstUp >= 0 && secondDown >= firstUp && secondDown - firstUp <= 300 &&
            x0.isFinite() && y0.isFinite() && x.isFinite() && y.isFinite() && slop.isFinite() && slop > 0f &&
            hypot(x - x0, y - y0) <= slop * 2f

    fun containsCenter(q: FloatArray, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        if (q.size < 8 || q.take(8).any { !it.isFinite() } || listOf(x0, y0, x1, y1).any { !it.isFinite() }) return false
        val x = (q[0] + q[2] + q[4] + q[6]) / 4f
        val y = (q[1] + q[3] + q[5] + q[7]) / 4f
        return x in minOf(x0, x1)..maxOf(x0, x1) && y in minOf(y0, y1)..maxOf(y0, y1)
    }
}
