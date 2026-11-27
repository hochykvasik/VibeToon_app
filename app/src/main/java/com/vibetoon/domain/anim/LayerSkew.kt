package com.vibetoon.domain.anim

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.tan

/**
 * Спринт 36: наклон (Skew) целого слоя или группы. Наклон хранится там же, где искажение «Перспектива»: смещения
 * 4 углов слоя (доли размера слоя, см. [PerspectiveMath]). Поэтому ключи, переходы, Undo/Redo, сохранение в проект,
 * экспорт и кэш работают как у перспективы, а наклон и свободное искажение углов складываются в одну форму.
 *
 * Наклон X - угол средней линии «верх-низ» от вертикали (в пикселях слоя, с учётом соотношения сторон),
 * наклон Y - угол средней линии «лево-право» от горизонтали. Новое значение задаётся сдвигом относительно центра
 * четырёхугольника: x += s·(y - cy) (или y += t·(x - cx)), поэтому прочие искажения углов сохраняются.
 */
object LayerSkew {
    const val MAX_DEG = 75f
    /** Тот же предел смещения угла, что у перетаскивания углов перспективы. */
    const val LIMIT = 1.5f

    private fun u(off: FloatArray, k: Int) = PerspectiveMath.cornerX(k) + off[k * 2]
    private fun v(off: FloatArray, k: Int) = PerspectiveMath.cornerY(k) + off[k * 2 + 1]

    private fun valid(off: FloatArray, w: Float, h: Float) =
        off.size == PerspectiveMath.CORNERS * 2 && off.all { it.isFinite() } && w.isFinite() && h.isFinite() && w > 0f && h > 0f

    /** Средние линии в пикселях слоя: (dx, dy) «верх -> низ» и (dx, dy) «лево -> право». */
    private fun midlines(off: FloatArray, w: Float, h: Float): FloatArray {
        val tu = (u(off, 0) + u(off, 1)) / 2f; val tv = (v(off, 0) + v(off, 1)) / 2f
        val bu = (u(off, 3) + u(off, 2)) / 2f; val bv = (v(off, 3) + v(off, 2)) / 2f
        val lu = (u(off, 0) + u(off, 3)) / 2f; val lv = (v(off, 0) + v(off, 3)) / 2f
        val ru = (u(off, 1) + u(off, 2)) / 2f; val rv = (v(off, 1) + v(off, 2)) / 2f
        return floatArrayOf((bu - tu) * w, (bv - tv) * h, (ru - lu) * w, (rv - lv) * h)
    }

    /** Текущий наклон (X, Y) в градусах; без смещений углов - (0, 0). null - данные испорчены. */
    fun measure(off: FloatArray, w: Float, h: Float): Pair<Float, Float>? {
        if (!valid(off, w, h)) return null
        val m = midlines(off, w, h)
        val kx = Math.toDegrees(atan2(m[0].toDouble(), m[1].toDouble())).toFloat()
        val ky = Math.toDegrees(atan2(m[3].toDouble(), m[2].toDouble())).toFloat()
        return kx to ky
    }

    /**
     * Смещения углов с новым наклоном: [kxDeg]/[kyDeg] = null - эта ось не меняется. null - угол вне ±75°, слой
     * вырожден (средняя линия нулевой длины) или угол ушёл бы дальше допустимого смещения (форма не меняется).
     */
    fun withSkew(off: FloatArray, w: Float, h: Float, kxDeg: Float?, kyDeg: Float?): FloatArray? {
        if (!valid(off, w, h)) return null
        for (k in listOfNotNull(kxDeg, kyDeg)) if (!k.isFinite() || abs(k) > MAX_DEG) return null
        val o = off.copyOf()
        fun center(): Pair<Float, Float> = (0 until 4).map { u(o, it) }.average().toFloat() to (0 until 4).map { v(o, it) }.average().toFloat()
        if (kxDeg != null) {
            val m = midlines(o, w, h)
            if (m[1] <= 1e-3f) return null
            val s = tan(Math.toRadians(kxDeg.toDouble())).toFloat() - m[0] / m[1]
            val (_, cv) = center()
            for (k in 0 until 4) o[k * 2] += s * (v(o, k) - cv) * h / w
        }
        if (kyDeg != null) {
            val m = midlines(o, w, h)
            if (m[2] <= 1e-3f) return null
            val t = tan(Math.toRadians(kyDeg.toDouble())).toFloat() - m[3] / m[2]
            val (cu, _) = center()
            for (k in 0 until 4) o[k * 2 + 1] += t * (u(o, k) - cu) * w / h
        }
        return if (o.all { it.isFinite() && abs(it) <= LIMIT }) o else null
    }

    /** Убирает наклон, сохраняя прочие искажения (средние линии снова вертикальна/горизонтальна). */
    fun straighten(off: FloatArray, w: Float, h: Float): FloatArray? = withSkew(off, w, h, 0f, 0f)
}
