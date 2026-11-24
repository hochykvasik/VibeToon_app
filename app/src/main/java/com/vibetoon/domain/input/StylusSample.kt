package com.vibetoon.domain.input

import kotlin.math.hypot

/**
 * Stylus sprint: правила чтения пера, общие для холста и тестовой площадки настроек. Чистые функции - StylusSampleTest.
 *  - палец, мышь и перо без датчика нажима рисуют с нажимом 1.0 (безопасный откат);
 *  - нулевой/NaN нажим пера на движении (бывает у первых и последних событий) заменяется последним нормальным;
 *  - исторические точки получают реальный нажим из MotionEvent, а если его нет - интерполяцию между событиями;
 *  - точка отпускания с нулевым нажимом не делает «хвостик», а резкий скачок на отпускании (фантом) отбрасывается.
 */
object StylusSample {
    /** Минимальный нажим кисти: даже самое лёгкое касание оставляет след. */
    const val MIN_PRESSURE = 0.02f
    /** Отпускание дальше этого (dp) от предыдущей точки за одно событие считается фантомной точкой. */
    const val PHANTOM_RELEASE_DP = 48f

    /** Нажим для кисти: перо - сырой нажим (или [last] при сбое), остальное - 1.0. */
    fun pressure(raw: Float, stylus: Boolean, last: Float = 1f): Float {
        if (!stylus) return 1f
        if (!raw.isFinite() || raw <= 0f) return last.takeIf { it.isFinite() && it > 0f }?.coerceIn(MIN_PRESSURE, 1f) ?: 1f
        return raw.coerceIn(MIN_PRESSURE, 1f)
    }

    /** Нажим для [count] исторических точек между [previous] и [current] (без самой текущей). */
    fun interpolate(previous: Float, current: Float, count: Int): FloatArray =
        FloatArray(count.coerceAtLeast(0)) { i -> previous + (current - previous) * (i + 1) / (count + 1).toFloat() }

    /** Реальные исторические нажимы, если их число совпадает с числом исторических точек; иначе интерполяция. */
    fun historical(real: FloatArray?, previous: Float, current: Float, count: Int, stylus: Boolean): FloatArray {
        if (!stylus) return FloatArray(count.coerceAtLeast(0)) { 1f }
        if (real != null && real.size == count) {
            var last = previous
            return FloatArray(count) { i -> pressure(real[i], true, last).also { last = it } }
        }
        return interpolate(previous, current, count)
    }

    /** Брать ли точку отпускания: фантомный скачок пера при подъёме отбрасывается. */
    fun keepReleasePoint(lastX: Float, lastY: Float, x: Float, y: Float, density: Float): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        return hypot(x - lastX, y - lastY) <= PHANTOM_RELEASE_DP * (if (density > 0f) density else 1f)
    }

    /** Нажим точки отпускания: у многих перьев он 0 - берём последний нормальный, без тонкого «хвостика». */
    fun releasePressure(raw: Float, stylus: Boolean, last: Float): Float =
        if (!stylus) 1f else if (raw.isFinite() && raw >= last) raw.coerceIn(MIN_PRESSURE, 1f) else pressure(last, true, last)

    /** Наклон пера в градусах от вертикали (AXIS_TILT в радианах), null - датчика нет. */
    fun tiltDegrees(tiltRadians: Float): Int? =
        if (!tiltRadians.isFinite() || tiltRadians <= 0f) null else Math.round(Math.toDegrees(tiltRadians.toDouble()).toFloat().coerceIn(0f, 90f))
}
