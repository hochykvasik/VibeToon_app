package com.vibetoon.domain.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Спринт 62: поля «потяни» (Размер, Непр., Жёстк. ...) внутри прокручиваемой строки инструментов на телефоне.
 *
 * Было: горизонтальный свайп по полю сразу менял значение. Строка инструментов в портрете сама прокручивается вбок,
 * и, долистав до полей размера и прозрачности, пользователь продолжал листать - палец попадал на поле и сбивал размер
 * или прозрачность кисти.
 *
 * Стало (режим [Mode.HOLD], телефон): быстрый свайп по полю прокручивает строку, значение не меняется. Чтобы
 * регулировать, палец держат на поле [HOLD_MS] без движения (поле подсвечивается) и ведут - тогда строка не
 * прокручивается. Короткое касание левой или правой трети поля - шаг ‹ › на одно деление. Вертикальная прокрутка
 * значение не трогает. На планшете ([Mode.DIRECT]) - как раньше, протяжка сразу. Чистые функции - Sprint62Test.
 */
object ScrubGesture {
    const val HOLD_MS = 280L
    /** Доля ширины поля слева/справа, где касание - шаг ‹ / ›; середина - без действия. */
    const val STEP_ZONE = 0.34f

    enum class Mode { DIRECT, HOLD }
    enum class Decision { PENDING, SCRUB, SCROLL, TAP }

    /**
     * Что делать с касанием, пока решение не принято: палец сдвинулся на ([dx], [dy]) px за [elapsed] мс, [up] -
     * отпущен. Сдвиг в основном по вертикали - всегда прокрутка (список/панель), значение не трогается.
     * DIRECT: сдвиг вбок дальше порога [slop] - сразу регулировка. HOLD: сдвиг до [HOLD_MS] - прокрутка (отдаём
     * строке), удержание без сдвига - регулировка; отпускание без сдвига - касание (шаг).
     */
    fun decide(mode: Mode, dx: Float, dy: Float, elapsed: Long, up: Boolean, slop: Float): Decision {
        val finite = dx.isFinite() && dy.isFinite()
        val moved = !finite || kotlin.math.hypot(dx, dy) > max(0f, slop)
        val vertical = finite && abs(dy) > abs(dx)
        return when {
            moved && (vertical || !finite) -> Decision.SCROLL
            mode == Mode.DIRECT && moved -> Decision.SCRUB
            moved -> Decision.SCROLL
            up -> Decision.TAP
            mode == Mode.HOLD && elapsed >= HOLD_MS -> Decision.SCRUB
            else -> Decision.PENDING
        }
    }

    /** Режим полей: телефон (короткая сторона окна < 600 dp - строка инструментов прокручивается) - HOLD. */
    fun modeFor(phone: Boolean): Mode = if (phone) Mode.HOLD else Mode.DIRECT

    /** Шаг касания по координате [x] на поле шириной [width]: -1 (левая треть), +1 (правая), 0 (середина). */
    fun tapStep(x: Float, width: Float): Int {
        if (!x.isFinite() || !width.isFinite() || width <= 0f) return 0
        val f = x / width
        return when { f < STEP_ZONE -> -1; f > 1f - STEP_ZONE -> 1; else -> 0 }
    }

    /** Значение после протяжки на [total] px от [start]: [log] - множитель, иначе [perPx] за пиксель; в [min]..[max]. */
    fun dragValue(start: Float, total: Float, min: Float, max: Float, log: Boolean, perPx: Float): Float {
        val s = if (start.isFinite()) start else min
        val t = if (total.isFinite()) total else 0f
        val v = if (log) s * exp(t * 0.008f) else s + t * perPx
        return clampTo(v, min, max)
    }

    /**
     * Значение после шага ‹ › ([dir] = ±1): у логарифмических (размер) - ×1.1, но не меньше 1; у линейных - 1 единица
     * (если диапазон шире 1) или сотая диапазона. Результат в [min]..[max], на краю - без изменения.
     */
    fun stepValue(value: Float, dir: Int, min: Float, max: Float, log: Boolean): Float {
        if (dir == 0) return clampTo(value, min, max)
        val v = clampTo(value, min, max)
        val span = max - min
        val step = when {
            log -> max(1f, abs(v) * 0.1f)
            span > 1f -> max(1f, (span / 100f).roundToInt().toFloat())
            else -> span / 100f
        }
        val next = if (log) (if (dir > 0) max(v + 1f, v * 1.1f) else minOf(v - 1f, v / 1.1f)) else v + step * dir.coerceIn(-1, 1)
        return clampTo(if (log) next else (next / step).roundToInt() * step, min, max)
    }

    private fun clampTo(v: Float, min: Float, max: Float): Float {
        val lo = minOf(min, max); val hi = maxOf(min, max)
        return if (v.isFinite()) v.coerceIn(lo, hi) else lo
    }
}
