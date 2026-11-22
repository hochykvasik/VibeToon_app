package com.vibetoon.domain.raster

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Прямоугольник изменений в пикселях слоя. */
class IRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Чистая растровая математика (без Android и OpenGL): заливка, маски выделения, размазывание.
 * Пиксели - RGBA premultiplied по 4 байта, строка 0 - верх; [off] - смещение первого пикселя в массиве (у снимка слоя 8 байт заголовка).
 */
object RasterMath {
    private fun ch(px: ByteArray, i: Int) = px[i].toInt() and 0xFF

    /** Расстояние между пикселем [i] (индекс байта) и цветом [c] (4 канала): максимум по каналам. */
    private fun dist(px: ByteArray, i: Int, c: IntArray): Int =
        max(max(abs(ch(px, i) - c[0]), abs(ch(px, i + 1) - c[1])), max(abs(ch(px, i + 2) - c[2]), abs(ch(px, i + 3) - c[3])))

    /** Расширяет маску (1 - внутри) на [r] пикселей по Чебышеву: два прохода, O(w*h) независимо от r. */
    fun dilate(mask: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        if (r <= 0) return mask
        val tmp = ByteArray(w * h)
        for (y in 0 until h) {
            var last = -1000000
            // слева направо: расстояние до ближайшей единицы слева
            for (x in 0 until w) { if (mask[y * w + x].toInt() != 0) last = x; if (x - last <= r) tmp[y * w + x] = 1 }
            last = 1000000
            for (x in w - 1 downTo 0) { if (mask[y * w + x].toInt() != 0) last = x; if (last - x <= r) tmp[y * w + x] = 1 }
        }
        val out = ByteArray(w * h)
        for (x in 0 until w) {
            var last = -1000000
            for (y in 0 until h) { if (tmp[y * w + x].toInt() != 0) last = y; if (y - last <= r) out[y * w + x] = 1 }
            last = 1000000
            for (y in h - 1 downTo 0) { if (tmp[y * w + x].toInt() != 0) last = y; if (last - y <= r) out[y * w + x] = 1 }
        }
        return out
    }

    /**
     * Заливка: область, связная с точкой (sx, sy), где цвет отличается от цвета точки не больше [threshold] (0..255).
     * [gap] - закрытие зазоров: линии (пиксели вне допуска) утолщаются на [gap] px, и заливка не протекает через щели не шире 2*gap.
     * Возвращает маску области (после закрытия зазоров она отступает от линий на [gap], это компенсирует [fillResult] расширением).
     */
    fun floodRegion(px: ByteArray, off: Int, w: Int, h: Int, sx: Int, sy: Int, threshold: Int, gap: Int): ByteArray {
        val region = ByteArray(w * h)
        if (sx !in 0 until w || sy !in 0 until h) return region
        val si = off + (sy * w + sx) * 4
        val seed = intArrayOf(ch(px, si), ch(px, si + 1), ch(px, si + 2), ch(px, si + 3))
        // барьер: пиксели, непохожие на цвет заливаемой области
        var barrier = ByteArray(w * h)
        for (k in 0 until w * h) if (dist(px, off + k * 4, seed) > threshold) barrier[k] = 1
        if (gap > 0) barrier = dilate(barrier, w, h, gap)
        if (barrier[sy * w + sx].toInt() != 0) return region   // точка попала на саму линию (или щель закрыта вплотную)
        // построчная заливка со стеком
        val stack = IntArray(w * h + 1)
        var sp = 0
        stack[sp++] = sy * w + sx
        while (sp > 0) {
            val p = stack[--sp]
            val y = p / w
            var x0 = p % w
            if (region[p].toInt() != 0 || barrier[p].toInt() != 0) continue
            var x1 = x0
            while (x0 > 0 && region[y * w + x0 - 1].toInt() == 0 && barrier[y * w + x0 - 1].toInt() == 0) x0--
            while (x1 < w - 1 && region[y * w + x1 + 1].toInt() == 0 && barrier[y * w + x1 + 1].toInt() == 0) x1++
            for (x in x0..x1) region[y * w + x] = 1
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny !in 0 until h) continue
                var inRun = false
                for (x in x0..x1) {
                    val q = ny * w + x
                    val free = region[q].toInt() == 0 && barrier[q].toInt() == 0
                    if (free && !inRun && sp < stack.size) { stack[sp++] = q; inRun = true } else if (!free) inRun = false
                }
            }
        }
        return region
    }

    /** Результат заливки: изменённый прямоугольник, пиксели «до» и «после» (только внутри прямоугольника); null - ничего не изменилось. */
    class FillResult(val rect: IRect, val before: ByteArray, val after: ByteArray)

    /**
     * Применяет заливку цветом [colorArgb] (straight ARGB) к [region]. Область расширяется на [expand] + [gap] пикселей, чтобы не оставалось
     * каймы у контура; в расширенной части заливка ложится ПОД существующие пиксели (линия остаётся поверх), внутри области - заменяет.
     */
    fun fillResult(px: ByteArray, off: Int, w: Int, h: Int, region: ByteArray, colorArgb: Int, expand: Int, gap: Int): FillResult? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (region[y * w + x].toInt() != 0) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (maxX < 0) return null
        val grow = max(0, expand) + max(0, gap)
        val wide = if (grow > 0) dilate(region, w, h, grow) else region
        val x0 = max(0, minX - grow); val y0 = max(0, minY - grow)
        val x1 = min(w - 1, maxX + grow); val y1 = min(h - 1, maxY + grow)
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val before = ByteArray(rw * rh * 4)
        val after = ByteArray(rw * rh * 4)
        val fa = (colorArgb ushr 24) and 0xFF
        val fr = ((colorArgb shr 16) and 0xFF) * fa / 255
        val fg = ((colorArgb shr 8) and 0xFF) * fa / 255
        val fb = (colorArgb and 0xFF) * fa / 255
        var changed = false
        for (yy in 0 until rh) for (xx in 0 until rw) {
            val k = (y0 + yy) * w + (x0 + xx)
            val si = off + k * 4
            val di = (yy * rw + xx) * 4
            for (c in 0..3) { before[di + c] = px[si + c]; after[di + c] = px[si + c] }
            if (region[k].toInt() != 0) {
                after[di] = fr.toByte(); after[di + 1] = fg.toByte(); after[di + 2] = fb.toByte(); after[di + 3] = fa.toByte()
            } else if (wide[k].toInt() != 0) {
                // существующий пиксель поверх цвета заливки: result = old + fill * (1 - old.a)
                val oa = ch(px, si + 3)
                val inv = 255 - oa
                after[di] = min(255, ch(px, si) + fr * inv / 255).toByte()
                after[di + 1] = min(255, ch(px, si + 1) + fg * inv / 255).toByte()
                after[di + 2] = min(255, ch(px, si + 2) + fb * inv / 255).toByte()
                after[di + 3] = min(255, oa + fa * inv / 255).toByte()
            }
            if (!changed) for (c in 0..3) if (before[di + c] != after[di + c]) { changed = true; break }
        }
        return if (changed) FillResult(IRect(x0, y0, rw, rh), before, after) else null
    }

    // ------------------------------------------------------------------------------------------------ выделение

    fun rectMask(w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float): ByteArray {
        val m = ByteArray(w * h)
        val ax = max(0, min(x0, x1).toInt()); val bx = min(w - 1, max(x0, x1).toInt())
        val ay = max(0, min(y0, y1).toInt()); val by = min(h - 1, max(y0, y1).toInt())
        for (y in ay..by) for (x in ax..bx) m[y * w + x] = 1
        return m
    }

    fun ovalMask(w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float): ByteArray {
        val m = ByteArray(w * h)
        val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
        val rx = abs(x1 - x0) / 2f; val ry = abs(y1 - y0) / 2f
        if (rx < 0.5f || ry < 0.5f) return m
        val ay = max(0, (cy - ry).toInt()); val by = min(h - 1, (cy + ry).toInt())
        val ax = max(0, (cx - rx).toInt()); val bx = min(w - 1, (cx + rx).toInt())
        for (y in ay..by) for (x in ax..bx) {
            val dx = (x + 0.5f - cx) / rx; val dy = (y + 0.5f - cy) / ry
            if (dx * dx + dy * dy <= 1f) m[y * w + x] = 1
        }
        return m
    }

    /** Лассо: заполнение многоугольника (чётно-нечётное правило); [pts] - x0, y0, x1, y1, ... в пикселях слоя. */
    fun polygonMask(w: Int, h: Int, pts: FloatArray): ByteArray {
        val m = ByteArray(w * h)
        val n = pts.size / 2
        if (n < 3) return m
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minY = min(minY, pts[i * 2 + 1]); maxY = max(maxY, pts[i * 2 + 1]) }
        val ys = max(0, minY.toInt()); val ye = min(h - 1, maxY.toInt())
        val xs = FloatArray(n)
        for (y in ys..ye) {
            val sy = y + 0.5f
            var c = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ay = pts[i * 2 + 1]; val by = pts[j * 2 + 1]
                if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) xs[c++] = pts[i * 2] + (sy - ay) / (by - ay) * (pts[j * 2] - pts[i * 2])
            }
            java.util.Arrays.sort(xs, 0, c)
            var k = 0
            while (k + 1 < c) {
                val a = max(0, Math.ceil((xs[k] - 0.5f).toDouble()).toInt()); val b = min(w - 1, Math.floor((xs[k + 1] - 0.5f).toDouble()).toInt())
                for (x in a..b) m[y * w + x] = 1
                k += 2
            }
        }
        return m
    }

    /** Волшебная палочка: связная область с похожим цветом (тот же алгоритм, что у заливки, без закрытия зазоров). */
    fun wandMask(px: ByteArray, off: Int, w: Int, h: Int, sx: Int, sy: Int, threshold: Int): ByteArray = floodRegion(px, off, w, h, sx, sy, threshold, 0)

    fun invert(mask: ByteArray): ByteArray = ByteArray(mask.size) { if (mask[it].toInt() == 0) 1 else 0 }

    fun count(mask: ByteArray): Int { var n = 0; for (b in mask) if (b.toInt() != 0) n++; return n }

    /** Границы маски; null - пустая. */
    fun bounds(mask: ByteArray, w: Int, h: Int): IRect? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x].toInt() != 0) {
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        return if (maxX < 0) null else IRect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Очистка выделенного: пиксели под маской становятся прозрачными. null - под маской и так пусто. */
    fun clearResult(px: ByteArray, off: Int, w: Int, h: Int, mask: ByteArray): FillResult? {
        val b = bounds(mask, w, h) ?: return null
        val before = ByteArray(b.w * b.h * 4)
        val after = ByteArray(b.w * b.h * 4)
        var changed = false
        for (yy in 0 until b.h) for (xx in 0 until b.w) {
            val k = (b.y + yy) * w + (b.x + xx)
            val di = (yy * b.w + xx) * 4
            for (c in 0..3) { before[di + c] = px[off + k * 4 + c]; after[di + c] = before[di + c] }
            if (mask[k].toInt() != 0) {
                if (before[di + 3].toInt() != 0 || before[di].toInt() != 0 || before[di + 1].toInt() != 0 || before[di + 2].toInt() != 0) changed = true
                after[di] = 0; after[di + 1] = 0; after[di + 2] = 0; after[di + 3] = 0
            }
        }
        return if (changed) FillResult(IRect(b.x, b.y, b.w, b.h), before, after) else null
    }

    /**
     * Перенос содержимого выделения на (dx, dy) пикселей: пиксели под маской вырезаются и ставятся на новое место поверх остального.
     * Возвращает один прямоугольник (охват старого и нового положения) с «до» и «после» для Undo.
     */
    /** Нельзя уничтожать пиксели на краю: вызывающий код должен отдельно перенести фрагмент. */
    fun moveFits(b: IRect, w: Int, h: Int, dx: Int, dy: Int): Boolean =
        b.x.toLong() + dx >= 0 && b.y.toLong() + dy >= 0 &&
            b.x.toLong() + b.w + dx <= w && b.y.toLong() + b.h + dy <= h

    fun moveResult(px: ByteArray, off: Int, w: Int, h: Int, mask: ByteArray, dx: Int, dy: Int): FillResult? {
        val b = bounds(mask, w, h) ?: return null
        if ((dx == 0 && dy == 0) || !moveFits(b, w, h, dx, dy)) return null
        val x0 = max(0, min(b.x, b.x + dx)); val y0 = max(0, min(b.y, b.y + dy))
        val x1 = min(w - 1, max(b.x + b.w - 1, b.x + b.w - 1 + dx)); val y1 = min(h - 1, max(b.y + b.h - 1, b.y + b.h - 1 + dy))
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        if (rw <= 0 || rh <= 0) return null
        val before = ByteArray(rw * rh * 4)
        val after = ByteArray(rw * rh * 4)
        for (yy in 0 until rh) for (xx in 0 until rw) {
            val k = (y0 + yy) * w + (x0 + xx); val di = (yy * rw + xx) * 4
            for (c in 0..3) { before[di + c] = px[off + k * 4 + c]; after[di + c] = before[di + c] }
            if (mask[k].toInt() != 0) { for (c in 0..3) after[di + c] = 0 }   // вырезали
        }
        for (y in b.y until b.y + b.h) for (x in b.x until b.x + b.w) {
            val k = y * w + x
            if (mask[k].toInt() == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx !in x0..x1 || ny !in y0..y1) continue
            val si = off + k * 4
            val di = ((ny - y0) * rw + (nx - x0)) * 4
            val sa = ch(px, si + 3)
            val inv = 255 - sa
            for (c in 0..2) after[di + c] = min(255, ch(px, si + c) + (after[di + c].toInt() and 0xFF) * inv / 255).toByte()
            after[di + 3] = min(255, sa + (after[di + 3].toInt() and 0xFF) * inv / 255).toByte()
        }
        return FillResult(IRect(x0, y0, rw, rh), before, after)
    }

    // ------------------------------------------------------------------------------------------------ смазка

    /**
     * Смазка: на каждом шаге штриха пиксели круга радиуса [r] вокруг (cx, cy) подтягиваются из точки, сдвинутой назад по штриху
     * на (dx, dy), с силой [strength] (0..1) и спадом к краю круга. [src] - снимок слоя на начало штриха, [dst] - рабочая копия.
     */
    fun smudgeStep(dst: ByteArray, off: Int, w: Int, h: Int, cx: Float, cy: Float, r: Float, dx: Float, dy: Float, strength: Float): IRect? {
        if (r < 1f) return null
        val ax = max(0, (cx - r).toInt()); val bx = min(w - 1, (cx + r).toInt())
        val ay = max(0, (cy - r).toInt()); val by = min(h - 1, (cy + r).toInt())
        if (bx < ax || by < ay) return null
        val rw = bx - ax + 1; val rh = by - ay + 1
        val tmp = ByteArray(rw * rh * 4)
        for (y in ay..by) for (x in ax..bx) {
            val ddx = x + 0.5f - cx; val ddy = y + 0.5f - cy
            val d = Math.sqrt((ddx * ddx + ddy * ddy).toDouble()).toFloat() / r
            val ti = ((y - ay) * rw + (x - ax)) * 4
            val di = off + (y * w + x) * 4
            if (d >= 1f) { for (c in 0..3) tmp[ti + c] = dst[di + c]; continue }
            val sx = (x - dx).coerceIn(0f, (w - 1).toFloat()).toInt()
            val sy = (y - dy).coerceIn(0f, (h - 1).toFloat()).toInt()
            val si = off + (sy * w + sx) * 4
            val k = strength * (1f - d * d)
            for (c in 0..3) {
                val a = dst[di + c].toInt() and 0xFF; val b = dst[si + c].toInt() and 0xFF
                tmp[ti + c] = (a + (b - a) * k).toInt().coerceIn(0, 255).toByte()
            }
        }
        for (y in ay..by) for (x in ax..bx) {
            val ti = ((y - ay) * rw + (x - ax)) * 4
            val di = off + (y * w + x) * 4
            for (c in 0..3) dst[di + c] = tmp[ti + c]
        }
        return IRect(ax, ay, rw, rh)
    }

    /**
     * Границы содержимого слоя по карте прозрачности: [minU, minV, maxU, maxV] в долях слоя (0..1); пустой слой - пустой массив.
     * [alpha] - по байту на пиксель, [off] - смещение первого пикселя; пиксель с альфой не меньше [min] считается содержимым.
     */
    fun alphaBox(alpha: ByteArray, off: Int, w: Int, h: Int, min: Int = 8): FloatArray {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val row = off + y * w
            var first = -1; var last = -1
            for (x in 0 until w) if ((alpha[row + x].toInt() and 0xFF) >= min) { if (first < 0) first = x; last = x }
            if (first < 0) continue
            if (first < x0) x0 = first; if (last > x1) x1 = last
            if (y < y0) y0 = y; y1 = y
        }
        if (x1 < 0) return FloatArray(0)
        return floatArrayOf(x0.toFloat() / w, y0.toFloat() / h, (x1 + 1).toFloat() / w, (y1 + 1).toFloat() / h)
    }

    /** Подчетырёхугольник слоя [q] (левый верхний, правый верхний, правый нижний, левый нижний: 8 чисел) для рамки содержимого [box]. */
    fun subQuad(q: FloatArray, box: FloatArray): FloatArray {
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        fun px(u: Float, v: Float) = q[0] + u * ex + v * fx
        fun py(u: Float, v: Float) = q[1] + u * ey + v * fy
        val (u0, v0, u1, v1) = box
        return floatArrayOf(px(u0, v0), py(u0, v0), px(u1, v0), py(u1, v0), px(u1, v1), py(u1, v1), px(u0, v1), py(u0, v1))
    }

    /** Объединение прямоугольников. */
    fun union(a: IRect?, b: IRect): IRect {
        if (a == null) return b
        val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
        val x1 = max(a.x + a.w, b.x + b.w); val y1 = max(a.y + a.h, b.y + b.h)
        return IRect(x0, y0, x1 - x0, y1 - y0)
    }
}
