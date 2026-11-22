package com.vibetoon.domain.project

import kotlin.math.ceil

/** Непрозрачные границы в ARGB-изображении; пустое изображение не кадрируется. */
object ThumbnailBounds {
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)
    fun padded(argb: IntArray, width: Int, height: Int): Rect? {
        if (width <= 0 || height <= 0 || width.toLong() * height > argb.size) return null
        var x0 = width; var y0 = height; var x1 = -1; var y1 = -1
        for (y in 0 until height) for (x in 0 until width) {
            if ((argb[y * width + x] ushr 24) == 0) continue
            x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
        }
        if (x1 < 0) return null
        val pad = ceil(maxOf(x1 - x0 + 1, y1 - y0 + 1) * .08).toInt().coerceAtLeast(1)
        x0 = (x0 - pad).coerceAtLeast(0); y0 = (y0 - pad).coerceAtLeast(0)
        x1 = (x1 + pad).coerceAtMost(width - 1); y1 = (y1 + pad).coerceAtMost(height - 1)
        return Rect(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
