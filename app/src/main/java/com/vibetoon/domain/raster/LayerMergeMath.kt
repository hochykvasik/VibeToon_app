package com.vibetoon.domain.raster

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Спринт 50: «Слить вниз» прямо в пиксели нижнего слоя. Верхний слой накладывается своим режимом наложения
 * (те же формулы W3C, что в шейдере progBlend), прозрачностью и обтравкой; нижний слой сохраняет своё место,
 * группу, ключи и свойства. Пиксели - RGBA premultiplied, строка 0 - верх; у снимка слоя 8 байт заголовка.
 */
object LayerMergeMath {
    // Коды как SceneBuilder.BLEND_*
    const val NORMAL = 0; const val MULTIPLY = 1; const val SCREEN = 2; const val OVERLAY = 3; const val PASS = 4
    const val SOFT_LIGHT = 5; const val ADD = 6; const val DARKEN = 7; const val LIGHTEN = 8; const val COLOR_DODGE = 9; const val COLOR_BURN = 10

    /** Смешивание канала (непремультиплицированные cb - подложка, cs - источник), 0..1. */
    fun blend(mode: Int, cb: Float, cs: Float): Float = when (mode) {
        MULTIPLY -> cb * cs
        SCREEN -> cb + cs - cb * cs
        OVERLAY -> if (cb < 0.5f) 2f * cb * cs else { val t = 2f * cb - 1f; cs + t - cs * t }
        ADD -> min(cb + cs, 1f)
        DARKEN -> min(cb, cs)
        LIGHTEN -> max(cb, cs)
        COLOR_DODGE -> if (cb <= 0f) 0f else min(cb / max(1f - cs, 1e-4f), 1f)
        COLOR_BURN -> if (cb >= 1f) 1f else 1f - min((1f - cb) / max(cs, 1e-4f), 1f)
        SOFT_LIGHT -> {
            val dd = if (cb < 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
            if (cs < 0.5f) cb - (1f - 2f * cs) * cb * (1f - cb) else cb + (2f * cs - 1f) * (dd - cb)
        }
        else -> cs   // Normal (и Pass для слоя - как Normal)
    }

    /**
     * Аффинное отображение: непрерывные координаты пикселя нижнего слоя -> непрерывные координаты пикселя верхнего.
     * [bq], [tq] - углы текстур в холсте (8 чисел: левый верх, правый верх, правый низ, левый низ). null - вырождено.
     */
    fun pixelMap(bq: FloatArray, bw: Int, bh: Int, tq: FloatArray, tw: Int, th: Int): FloatArray? {
        if (bq.size < 8 || tq.size < 8 || bw <= 0 || bh <= 0 || tw <= 0 || th <= 0) return null
        // нижний: canvas = b0 + x/bw * bx + y/bh * by
        val bx0 = (bq[2] - bq[0]) / bw; val bx1 = (bq[3] - bq[1]) / bw
        val by0 = (bq[6] - bq[0]) / bh; val by1 = (bq[7] - bq[1]) / bh
        // верхний: canvas = t0 + u * tx + v * ty, u = px / tw
        val tx0 = tq[2] - tq[0]; val tx1 = tq[3] - tq[1]
        val ty0 = tq[6] - tq[0]; val ty1 = tq[7] - tq[1]
        val det = tx0 * ty1 - ty0 * tx1
        if (!det.isFinite() || kotlin.math.abs(det) < 1e-9f) return null
        // обратная к [tx ty]: [u v] = inv * (canvas - t0)
        val i00 = ty1 / det; val i01 = -ty0 / det; val i10 = -tx1 / det; val i11 = tx0 / det
        val ox = bq[0] - tq[0]; val oy = bq[1] - tq[1]
        // u(x, y) = i00 * (ox + x bx0 + y by0) + i01 * (oy + x bx1 + y by1)
        val ua = i00 * bx0 + i01 * bx1; val uc = i00 * by0 + i01 * by1; val ue = i00 * ox + i01 * oy
        val va = i10 * bx0 + i11 * bx1; val vc = i10 * by0 + i11 * by1; val ve = i10 * ox + i11 * oy
        val m = floatArrayOf(ua * tw, va * th, uc * tw, vc * th, ue * tw, ve * th)
        return if (m.all { it.isFinite() }) m else null
    }

    /** Обратная аффинная матрица (a, b, c, d, e, f: x' = a x + c y + e, y' = b x + d y + f). */
    fun invert(m: FloatArray): FloatArray? {
        val det = m[0] * m[3] - m[2] * m[1]
        if (!det.isFinite() || kotlin.math.abs(det) < 1e-12f) return null
        val a = m[3] / det; val b = -m[1] / det; val c = -m[2] / det; val d = m[0] / det
        return floatArrayOf(a, b, c, d, -(a * m[4] + c * m[5]), -(b * m[4] + d * m[5]))
    }

    /** Есть ли у верхнего слоя заметные пиксели вне рамки нижнего (тогда слияние в пиксели нижнего что-то потеряет). */
    fun topOutside(top: ByteArray, tOff: Int, tw: Int, th: Int, map: FloatArray, bw: Int, bh: Int, alphaThreshold: Int = 3): Boolean {
        val inv = invert(map) ?: return true
        for (y in 0 until th) {
            val row = tOff + y * tw * 4
            for (x in 0 until tw) {
                if ((top[row + x * 4 + 3].toInt() and 255) <= alphaThreshold) continue
                val cx = x + 0.5f; val cy = y + 0.5f
                val bx = inv[0] * cx + inv[2] * cy + inv[4]; val by = inv[1] * cx + inv[3] * cy + inv[5]
                if (bx < -0.5f || by < -0.5f || bx > bw + 0.5f || by > bh + 0.5f) return true
            }
        }
        return false
    }

    /** Билинейная выборка premultiplied канала [c] верхнего слоя в непрерывной точке; вне текстуры - прозрачный. */
    private fun sample(top: ByteArray, tOff: Int, tw: Int, th: Int, x: Float, y: Float, out: FloatArray) {
        val fx = x - 0.5f; val fy = y - 0.5f
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val ax = fx - x0; val ay = fy - y0
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f
        for (j in 0..1) for (i in 0..1) {
            val px = x0 + i; val py = y0 + j
            if (px < 0 || py < 0 || px >= tw || py >= th) continue
            val w = (if (i == 0) 1f - ax else ax) * (if (j == 0) 1f - ay else ay)
            if (w <= 0f) continue
            val o = tOff + (py * tw + px) * 4
            for (k in 0..3) out[k] += w * (top[o + k].toInt() and 255) / 255f
        }
    }

    /**
     * Накладывает верхний слой на нижний. [opacity] - итоговая прозрачность верхнего (свойства * трансформация),
     * [clipped] - верхний обтравлен по нижнему (результат не выходит за альфу нижнего), [bottomOpacity] - запекаемая
     * прозрачность нижнего (его свойство Opacity становится 1). null - ничего не изменилось или входные данные неверны.
     */
    fun merge(
        bottom: ByteArray, bOff: Int, bw: Int, bh: Int,
        top: ByteArray, tOff: Int, tw: Int, th: Int,
        map: FloatArray, mode: Int, opacity: Float, clipped: Boolean, bottomOpacity: Float = 1f
    ): RasterMath.FillResult? {
        if (bw <= 0 || bh <= 0 || tw <= 0 || th <= 0 || map.size < 6) return null
        if (bOff < 0 || tOff < 0 || bOff.toLong() + bw.toLong() * bh * 4 > bottom.size || tOff.toLong() + tw.toLong() * th * 4 > top.size) return null
        val op = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 0f
        val bop = if (bottomOpacity.isFinite()) bottomOpacity.coerceIn(0f, 1f) else 1f
        val after = bottom.copyOfRange(bOff, bOff + bw * bh * 4)
        val s = FloatArray(4)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until bh) for (x in 0 until bw) {
            val o = (y * bw + x) * 4
            var dr = (after[o].toInt() and 255) / 255f; var dg = (after[o + 1].toInt() and 255) / 255f
            var db = (after[o + 2].toInt() and 255) / 255f; var da = (after[o + 3].toInt() and 255) / 255f
            if (bop < 1f) { dr *= bop; dg *= bop; db *= bop; da *= bop }
            val cx = x + 0.5f; val cy = y + 0.5f
            sample(top, tOff, tw, th, map[0] * cx + map[2] * cy + map[4], map[1] * cx + map[3] * cy + map[5], s)
            val sa0 = s[3] * op
            val r: Float; val g: Float; val b: Float; val a: Float
            if (sa0 <= 0f) { r = dr; g = dg; b = db; a = da }
            else {
                // straight цвет источника (выборка premultiplied)
                val csr = (s[0] / s[3]).coerceIn(0f, 1f); val csg = (s[1] / s[3]).coerceIn(0f, 1f); val csb = (s[2] / s[3]).coerceIn(0f, 1f)
                val cbr = if (da > 0f) (dr / da).coerceIn(0f, 1f) else 0f
                val cbg = if (da > 0f) (dg / da).coerceIn(0f, 1f) else 0f
                val cbb = if (da > 0f) (db / da).coerceIn(0f, 1f) else 0f
                if (clipped) {
                    // как source-atop с режимом: альфа нижнего не меняется
                    val sa = sa0.coerceIn(0f, 1f)
                    r = da * ((1f - sa) * cbr + sa * blend(mode, cbr, csr))
                    g = da * ((1f - sa) * cbg + sa * blend(mode, cbg, csg))
                    b = da * ((1f - sa) * cbb + sa * blend(mode, cbb, csb))
                    a = da
                } else {
                    val sa = sa0.coerceIn(0f, 1f)
                    r = csr * sa * (1f - da) + cbr * da * (1f - sa) + sa * da * blend(mode, cbr, csr)
                    g = csg * sa * (1f - da) + cbg * da * (1f - sa) + sa * da * blend(mode, cbg, csg)
                    b = csb * sa * (1f - da) + cbb * da * (1f - sa) + sa * da * blend(mode, cbb, csb)
                    a = sa + da * (1f - sa)
                }
            }
            fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            val na = q(a)
            val nr = q(min(r, a)); val ng = q(min(g, a)); val nb = q(min(b, a))
            if (nr != after[o] || ng != after[o + 1] || nb != after[o + 2] || na != after[o + 3]) {
                after[o] = nr; after[o + 1] = ng; after[o + 2] = nb; after[o + 3] = na
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        val rw = maxX - minX + 1; val rh = maxY - minY + 1
        val beforeRect = ByteArray(rw * rh * 4); val afterRect = ByteArray(rw * rh * 4)
        for (yy in 0 until rh) {
            System.arraycopy(bottom, bOff + ((minY + yy) * bw + minX) * 4, beforeRect, yy * rw * 4, rw * 4)
            System.arraycopy(after, ((minY + yy) * bw + minX) * 4, afterRect, yy * rw * 4, rw * 4)
        }
        return RasterMath.FillResult(IRect(minX, minY, rw, rh), beforeRect, afterRect)
    }

    /** Спринт 54: верхний слой для [mergeMany]: пиксели (premultiplied, с отступом [off]), отображение нижний -> верхний, режим. */
    class Top(val px: ByteArray, val off: Int, val w: Int, val h: Int, val map: FloatArray, val mode: Int, val opacity: Float, val clipped: Boolean)

    /**
     * Спринт 54: «Слить в один слой» - несколько верхних слоёв по очереди (снизу вверх) накладываются на пиксели нижнего.
     * Итог - ОДНА пара до/после по общей рамке изменений (одна запись Undo). Прозрачность нижнего запекается один раз.
     * null - ничего не изменилось или данные неверны.
     */
    fun mergeMany(bottom: ByteArray, bOff: Int, bw: Int, bh: Int, tops: List<Top>, bottomOpacity: Float = 1f): RasterMath.FillResult? {
        if (bw <= 0 || bh <= 0 || tops.isEmpty() || bOff < 0 || bOff.toLong() + bw.toLong() * bh * 4 > bottom.size) return null
        val work = bottom.copyOfRange(bOff, bOff + bw * bh * 4)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        tops.forEachIndexed { i, t ->
            val r = merge(work, 0, bw, bh, t.px, t.off, t.w, t.h, t.map, t.mode, t.opacity, t.clipped, if (i == 0) bottomOpacity else 1f) ?: return@forEachIndexed
            val rc = r.rect
            for (yy in 0 until rc.h) System.arraycopy(r.after, yy * rc.w * 4, work, ((rc.y + yy) * bw + rc.x) * 4, rc.w * 4)
            minX = min(minX, rc.x); minY = min(minY, rc.y); maxX = max(maxX, rc.x + rc.w - 1); maxY = max(maxY, rc.y + rc.h - 1)
        }
        if (maxX < 0) return null
        val rw = maxX - minX + 1; val rh = maxY - minY + 1
        val beforeRect = ByteArray(rw * rh * 4); val afterRect = ByteArray(rw * rh * 4)
        for (yy in 0 until rh) {
            System.arraycopy(bottom, bOff + ((minY + yy) * bw + minX) * 4, beforeRect, yy * rw * 4, rw * 4)
            System.arraycopy(work, ((minY + yy) * bw + minX) * 4, afterRect, yy * rw * 4, rw * 4)
        }
        return RasterMath.FillResult(IRect(minX, minY, rw, rh), beforeRect, afterRect)
    }
}
