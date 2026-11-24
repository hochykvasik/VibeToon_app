package com.vibetoon.domain.input

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stylus sprint: стабилизация штриха 1–10 (как «Коррекция» в ibisPaint / StreamLine в Procreate).
 * Точки в экранных пикселях. Два фильтра подряд:
 *  1) «нить» (pulled string): опорная точка стоит, пока перо не отойдёт дальше радиуса нити, потом тянется за ним;
 *  2) экспоненциальное сглаживание по расстоянию: вход дробится на шаги ~[STEP_DP] dp, поэтому сила не зависит
 *     от частоты экрана (60/90/120 Гц) и числа исторических точек.
 * Уровень 1 - сырой штрих без задержки. При отпускании [finish] догоняет хвост до последней точки пера,
 * поэтому конец линии не теряется; на каждом событии без движения фильтр тоже подтягивается - линия не «гонится» бесконечно.
 * Нажим не тянется нитью (иначе толщина отстаёт от руки), а лишь слегка сглаживается.
 * Чистый класс - unit-тест StrokeStabilizerTest.
 */
class StrokeStabilizer(level: Int, private val density: Float = 1f) {
    data class Sample(val x: Float, val y: Float, val pressure: Float)

    val level: Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
    private val unit = if (density.isFinite() && density > 0f) density else 1f
    /** Радиус нити, px: 0 на уровне 1, ~11 dp на уровне 10. */
    val ropeRadius: Float = (this.level - 1) * ROPE_DP_PER_LEVEL * unit
    /** Вес новой точки на шаге STEP_DP: 1 = без сглаживания, 0.2 на уровне 10. */
    val alpha: Float = 1f - (this.level - 1) / 9f * (1f - MIN_ALPHA)
    private val step = STEP_DP * unit
    private val pressureAlpha = max(alpha, 0.5f)

    private var started = false
    private var rawX = 0f; private var rawY = 0f; private var rawP = 1f
    private var anchorX = 0f; private var anchorY = 0f
    private var outX = 0f; private var outY = 0f; private var outP = 1f

    val passthrough: Boolean get() = level == MIN_LEVEL

    fun begin(x: Float, y: Float, pressure: Float): Sample {
        started = true
        rawX = x; rawY = y; rawP = pressure
        anchorX = x; anchorY = y
        outX = x; outY = y; outP = pressure
        return Sample(x, y, pressure)
    }

    /** Новая точка пера (в том числе историческая); ответ - сглаженные точки для кисти (может быть пустым). */
    fun add(x: Float, y: Float, pressure: Float): List<Sample> {
        if (!started) return listOf(begin(x, y, pressure))
        if (!x.isFinite() || !y.isFinite()) return emptyList()
        val px = rawX; val py = rawY; val pp = rawP
        rawX = x; rawY = y; rawP = pressure
        if (passthrough) { outX = x; outY = y; outP = pressure; anchorX = x; anchorY = y; return listOf(Sample(x, y, pressure)) }
        val dist = hypot(x - px, y - py)
        val parts = ceil(dist / step).toInt().coerceIn(1, MAX_SUBSTEPS)
        val out = ArrayList<Sample>(parts)
        for (k in 1..parts) {
            val t = k / parts.toFloat()
            val ix = px + (x - px) * t; val iy = py + (y - py) * t; val ip = pp + (pressure - pp) * t
            // нить: опорная точка подтягивается только на то, на что перо вышло за радиус
            val dx = ix - anchorX; val dy = iy - anchorY
            val d = hypot(dx, dy)
            if (d > ropeRadius) {
                val pull = (d - ropeRadius) / d
                anchorX += dx * pull; anchorY += dy * pull
            }
            val nx = outX + (anchorX - outX) * alpha
            val ny = outY + (anchorY - outY) * alpha
            outP += (ip - outP) * pressureAlpha
            if (hypot(nx - outX, ny - outY) < MIN_MOVE_PX && k < parts) continue
            outX = nx; outY = ny
            out += Sample(outX, outY, outP)
        }
        return out
    }

    /**
     * Перо поднято: хвост догоняет последнюю точку пера по тому же сглаживанию (плавно, без «ступеньки»),
     * не более [MAX_TAIL] шагов, последняя точка - ровно точка отпускания.
     */
    fun finish(): List<Sample> {
        if (!started || passthrough) return emptyList()
        val out = ArrayList<Sample>()
        val catchUp = max(alpha, TAIL_ALPHA)
        var guard = 0
        while (hypot(rawX - outX, rawY - outY) > TAIL_DONE_DP * unit && guard < MAX_TAIL) {
            outX += (rawX - outX) * catchUp
            outY += (rawY - outY) * catchUp
            outP += (rawP - outP) * catchUp
            out += Sample(outX, outY, outP)
            guard++
        }
        if (outX != rawX || outY != rawY || out.isNotEmpty()) {
            outX = rawX; outY = rawY; outP = rawP
            out += Sample(rawX, rawY, rawP)
        }
        anchorX = rawX; anchorY = rawY
        started = false
        return out
    }

    /** Текущая задержка линии от пера, px (для теста «не гонится бесконечно»). */
    fun lag(): Float = hypot(rawX - outX, rawY - outY)

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 10
        const val DEFAULT_LEVEL = 1
        const val ROPE_DP_PER_LEVEL = 1.2f
        const val MIN_ALPHA = 0.2f
        const val STEP_DP = 2f
        const val MAX_SUBSTEPS = 96
        const val MAX_TAIL = 48
        const val TAIL_ALPHA = 0.35f
        const val TAIL_DONE_DP = 0.75f
        private const val MIN_MOVE_PX = 0.05f
    }
}
