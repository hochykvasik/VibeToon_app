package com.vibetoon.domain.anim

import kotlin.math.abs

/** Тип плавности сегмента между ключом и следующим ключом. */
enum class EasingType(
    /** Контрольные точки кубической кривой Безье (0,0)-(x1,y1)-(x2,y2)-(1,1), как cubic-bezier() в CSS. */
    val x1: Float, val y1: Float, val x2: Float, val y2: Float
) {
    LINEAR(0f, 0f, 1f, 1f),
    EASE_IN(0.42f, 0f, 1f, 1f),          // плавный разгон
    EASE_OUT(0f, 0f, 0.58f, 1f),         // плавное торможение
    EASE_IN_OUT(0.42f, 0f, 0.58f, 1f),   // плавно и в начале, и в конце
    /** Hold / Step: значение держится до следующего ключа и прыгает на нём (резкая покадровая перекладка). */
    STEP(0f, 0f, 1f, 1f);

    /** t в [0,1] (доля времени между ключами) -> доля пути значения. */
    fun apply(t: Float): Float {
        if (this == LINEAR) return t
        if (this == STEP) return if (t >= 1f) 1f else 0f
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return Bezier.y(x1, y1, x2, y2, t)
    }
}

object Bezier {
    // B(s) = 3(1-s)^2 s a + 3(1-s) s^2 b + s^3  (первая и последняя опорные точки: 0 и 1)
    private fun at(a: Float, b: Float, s: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * a + 3f * u * s * s * b + s * s * s
    }

    private fun slope(a: Float, b: Float, s: Float): Float {
        val u = 1f - s
        return 3f * u * u * a + 6f * u * s * (b - a) + 3f * s * s * (1f - b)
    }

    /** y кривой при заданном x: решаем x(s) = t (Ньютон, при неудаче бисекция), затем считаем y(s). */
    fun y(x1: Float, y1: Float, x2: Float, y2: Float, t: Float): Float {
        var s = t
        for (i in 0 until 8) {
            val err = at(x1, x2, s) - t
            if (abs(err) < 1e-6f) return at(y1, y2, s)
            val d = slope(x1, x2, s)
            if (abs(d) < 1e-6f) break
            s -= err / d
        }
        var lo = 0f
        var hi = 1f
        s = t
        for (i in 0 until 40) {
            val x = at(x1, x2, s)
            if (abs(x - t) < 1e-6f) break
            if (x < t) lo = s else hi = s
            s = (lo + hi) / 2f
        }
        return at(y1, y2, s)
    }
}
