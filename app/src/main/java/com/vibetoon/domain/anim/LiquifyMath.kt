package com.vibetoon.domain.anim

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Liquify: деструктивная кисть «толкнуть пиксели». Для каждого пикселя в круге радиуса r
 * цвет берётся из точки, сдвинутой назад на (dx, dy) * falloff(d), то есть содержимое под кистью
 * едет за движением пальца. Пиксели - ARGB (не premultiplied), как у Bitmap.getPixels.
 */
object LiquifyMath {
    /** Мягкий спад от центра кисти: 1 в центре, 0 на границе. */
    fun falloff(d: Float, r: Float): Float {
        if (r <= 0f || d >= r) return 0f
        val t = 1f - d / r
        return t * t * (3f - 2f * t)
    }

    /** Применяет один шаг толчка к [px] размера w x h. Возвращает число изменённых пикселей. */
    fun push(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, dx: Float, dy: Float): Int {
        if (w <= 0 || h <= 0 || r <= 0f || px.size < w * h) return 0
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(w - 1, floor(cx + r).toInt() + 1)
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(h - 1, floor(cy + r).toInt() + 1)
        if (x0 > x1 || y0 > y1) return 0
        val bw = x1 - x0 + 1
        val out = IntArray(bw * (y1 - y0 + 1))
        var changed = 0
        for (y in y0..y1) for (x in x0..x1) {
            val f = falloff(hypot(x + 0.5f - cx, y + 0.5f - cy), r)
            val i = (y - y0) * bw + (x - x0)
            if (f <= 0f) { out[i] = px[y * w + x]; continue }
            out[i] = sample(px, w, h, x + 0.5f - dx * f, y + 0.5f - dy * f)
            changed++
        }
        for (y in y0..y1) System.arraycopy(out, (y - y0) * bw, px, y * w + x0, bw)
        return changed
    }

    /** Билинейная выборка в точке (sx, sy) (центры пикселей на +0.5), с учётом альфы; вне картинки - прозрачно. */
    fun sample(px: IntArray, w: Int, h: Int, sx: Float, sy: Float): Int {
        val fx = sx - 0.5f
        val fy = sy - 0.5f
        val ix = floor(fx).toInt()
        val iy = floor(fy).toInt()
        val tx = fx - ix
        val ty = fy - iy
        var a = 0f; var r = 0f; var g = 0f; var b = 0f
        for (j in 0..1) for (i in 0..1) {
            val x = ix + i
            val y = iy + j
            val wgt = (if (i == 0) 1f - tx else tx) * (if (j == 0) 1f - ty else ty)
            if (x < 0 || y < 0 || x >= w || y >= h || wgt <= 0f) continue
            val c = px[y * w + x]
            val ca = (c ushr 24) / 255f
            a += wgt * ca
            r += wgt * ca * ((c shr 16) and 0xFF)
            g += wgt * ca * ((c shr 8) and 0xFF)
            b += wgt * ca * (c and 0xFF)
        }
        if (a <= 1e-4f) return 0
        val ai = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
        fun ch(v: Float) = (v / a + 0.5f).toInt().coerceIn(0, 255)
        return (ai shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
