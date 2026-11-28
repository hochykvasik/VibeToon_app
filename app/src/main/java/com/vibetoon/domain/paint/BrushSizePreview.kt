package com.vibetoon.domain.paint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Спринт 62: кружок размера кисти на холсте, пока меняется размер (поле «Размер», шаги ‹ ›, клавиши [ ]).
 *
 * Размер кисти задан в пикселях картинки слоя, поэтому кружок строится через экранные оси слоя: [Axes] - сколько
 * экранных пикселей даёт один пиксель картинки вдоль её осей X и Y. В них уже входят масштаб холста (zoom), поворот
 * и отражение холста, масштаб и поворот самого слоя: контур совпадает со штампом, который ляжет на холст. Сплющенный
 * штамп (roundness < 1, «Плоское перо», маркер) - эллипс вдоль осей слоя. Чистые функции - Sprint62Test.
 */
object BrushSizePreview {
    const val SEGMENTS = 48
    /** Сколько кружок держится после шага ‹ › или клавиши (без удержания пальца), мс. */
    const val PULSE_MS = 700L
    /** Меньше 2 px кружок не рисуется - он бы пропал под линией контура. */
    const val MIN_RADIUS_PX = 2f

    /** Экранный вектор одного пикселя картинки слоя вдоль её оси X ([ux], [uy]) и оси Y ([vx], [vy]). */
    data class Axes(val ux: Float, val uy: Float, val vx: Float, val vy: Float) {
        /** Отражение (холста или слоя): оси меняют ориентацию. */
        val mirrored: Boolean get() = ux * vy - uy * vx < 0f
    }

    private fun ok(vararg v: Float) = v.all { it.isFinite() }

    /**
     * Оси по экранным векторам сторон картинки слоя ([exX], [exY] - верхняя сторона, [eyX], [eyY] - левая) и её
     * размеру в пикселях. null - слой вырожден (нулевая площадь, NaN), рисовать нечего.
     */
    fun axes(exX: Float, exY: Float, eyX: Float, eyY: Float, texW: Int, texH: Int): Axes? {
        if (texW <= 0 || texH <= 0 || !ok(exX, exY, eyX, eyY)) return null
        val a = Axes(exX / texW, exY / texW, eyX / texH, eyY / texH)
        return if (abs(a.ux * a.vy - a.uy * a.vx) < 1e-9f) null else a
    }

    /** Оси холста без слоя: один пиксель документа = [scale] экранных, поворот холста [rotation] (радианы), отражения. */
    fun canvasAxes(scale: Float, rotation: Float, flipX: Boolean, flipY: Boolean): Axes? {
        if (!ok(scale, rotation) || scale <= 0f) return null
        val c = cos(rotation) * scale; val s = sin(rotation) * scale
        val fx = if (flipX) -1f else 1f; val fy = if (flipY) -1f else 1f
        // экран = R(поворот) · F(отражение) · холст
        return Axes(c * fx, s * fx, -s * fy, c * fy)
    }

    /** Наибольший экранный радиус кружка (для подписи и проверки «помещается ли»). */
    fun radiusPx(size: Float, roundness: Float, a: Axes): Float {
        if (!ok(size) || size <= 0f) return 0f
        val r = size / 2f
        val k = roundnessOf(roundness)
        return max(hypot(a.ux, a.uy), hypot(a.vx, a.vy) * k) * r
    }

    fun roundnessOf(r: Float): Float = if (r.isFinite()) r.coerceIn(0.05f, 1f) else 1f

    /**
     * Контур кружка вокруг ([cx], [cy]) - экранные точки x0, y0, x1, y1... ([segments] штук). Пустой массив - размер
     * не задан или кружок меньше [MIN_RADIUS_PX].
     */
    fun outline(cx: Float, cy: Float, size: Float, roundness: Float, a: Axes, segments: Int = SEGMENTS): FloatArray {
        if (!ok(cx, cy, size) || size <= 0f || radiusPx(size, roundness, a) < MIN_RADIUS_PX) return FloatArray(0)
        val n = segments.coerceIn(8, 256)
        val r = size / 2f
        val k = roundnessOf(roundness)
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = 2.0 * Math.PI * i / n
            val u = (cos(t) * r).toFloat(); val v = (sin(t) * r * k).toFloat()
            out[i * 2] = cx + u * a.ux + v * a.vx
            out[i * 2 + 1] = cy + u * a.uy + v * a.vy
        }
        return out
    }

    /** Центр кружка - середина видимой области холста (в режиме левши и в портрете она уже без панелей). */
    fun center(viewW: Float, viewH: Float): Pair<Float, Float>? =
        if (!ok(viewW, viewH) || viewW <= 0f || viewH <= 0f) null else viewW / 2f to viewH / 2f

    /**
     * Виден ли кружок: палец держит поле размера ([holding]) или после шага не прошло [PULSE_MS] ([pulseAt] - время
     * шага, [now] - сейчас; время назад - не виден, чтобы кружок не «залипал»).
     */
    fun visible(holding: Boolean, pulseAt: Long, now: Long): Boolean =
        holding || (pulseAt > 0L && now >= pulseAt && now - pulseAt < PULSE_MS)
}
