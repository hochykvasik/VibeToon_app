package com.vibetoon.domain.anim

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Спринт 54: скорость клипа 0.10x–3.0x (как в Alight Motion).
 *
 * Раньше скорость анимированного слоя меняла только длину плашки и время дорожки трансформации: ключи сетки, пинов,
 * Warp, эффектов, рисунки покадровки и время символов шли в прежнем темпе, а при замедлении плашка упиралась в конец
 * проекта - анимация просто обрезалась. Теперь у анимированных слоёв (картинки, слои PSD, группы, символы, слои костей)
 * скорость ПЕРЕСЧИТЫВАЕТ время всех ключей и рисунков слоя (и его потомков) от начала плашки, плашка растягивается или
 * сжимается, проект при необходимости продлевается. Холст, Play и экспорт совпадают сами, лишней работы на кадре нет.
 * У видео и звука скорость по-прежнему нативная ([com.vibetoon.domain.project.Clip.speed]). Чистые функции - unit-тест.
 */
object ClipSpeed {
    const val MIN = 0.1f
    const val MAX = 3f
    /** Шаг кнопок точной подстройки − / +. */
    const val STEP = 0.05f
    val PRESETS = listOf(0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f, 3f)

    /** Скорость в границах, с точностью до сотых. */
    fun clamp(speed: Float): Float {
        val s = if (speed.isFinite()) speed.coerceIn(MIN, MAX) else 1f
        return (s * 100f).roundToInt() / 100f
    }

    /** Шаг − / +: от текущего значения к ближайшему кратному [STEP]. */
    fun nudge(speed: Float, up: Boolean): Float {
        val n = speed / STEP
        val k = if (up) kotlin.math.floor(n + 1e-3f) + 1 else kotlin.math.ceil(n - 1e-3f) - 1
        return clamp(k * STEP)
    }

    /** Положение ползунка 0..1 -> скорость (логарифмическая шкала: 1x примерно на 2/3, замедление растянуто). */
    fun fromSlider(pos: Float): Float = clamp(MIN * exp(pos.coerceIn(0f, 1f) * ln(MAX / MIN)))

    fun toSlider(speed: Float): Float = (ln(clamp(speed) / MIN) / ln(MAX / MIN)).coerceIn(0f, 1f)

    /** Подпись: «0.10x», «1x», «2.5x». */
    fun label(speed: Float): String {
        val s = clamp(speed)
        val r = (s * 100f).roundToInt()
        return when {
            r % 100 == 0 -> "${r / 100}x"
            r % 10 == 0 -> "${r / 100}.${(r / 10) % 10}x"
            else -> "${r / 100}.${(r % 100).toString().padStart(2, '0')}x"
        }
    }

    /** Множитель времени ключей при смене скорости [old] -> [new]: 0.5x вдвое растягивает, 2x - вдвое сжимает. */
    fun stretch(old: Float, new: Float): Float = clamp(old) / clamp(new)

    /** Кадр после растяжения от [anchor]; кадры до якоря не двигаются. */
    fun retime(frame: Int, anchor: Int, factor: Float): Int =
        if (frame <= anchor) frame else anchor + ((frame - anchor) * factor).roundToInt()

    /**
     * Кадры ключей одной дорожки (по возрастанию) после растяжения: порядок сохраняется, совпавшие при ускорении
     * ключи раздвигаются на кадр (ни один ключ не теряется), всё в пределах [0, last].
     */
    fun retimeAll(frames: List<Int>, anchor: Int, factor: Float, last: Int): List<Int> {
        val out = ArrayList<Int>(frames.size)
        var prev = Int.MIN_VALUE
        for (f in frames) {
            var n = retime(f, anchor, factor).coerceIn(0, last.coerceAtLeast(0))
            if (n <= prev) n = prev + 1
            out += n
            prev = n
        }
        // упёрлись в конец проекта: сдвигаем хвост назад, сохраняя порядок
        for (i in out.indices.reversed()) {
            val cap = if (i == out.lastIndex) last else out[i + 1] - 1
            if (out[i] > cap) out[i] = cap
        }
        return out
    }

    /** Новая длина плашки (кадров) при растяжении [factor]; не короче 1. */
    fun length(length: Int, factor: Float): Int = (length * factor).roundToInt().coerceAtLeast(1)

    /**
     * Длительность проекта, нужная плашке [start, start + length): не меньше текущей [duration] и не больше [max].
     * Возвращает пару (новая длительность, плашка влезла целиком).
     */
    fun durationFor(start: Int, length: Int, duration: Int, max: Int): Pair<Int, Boolean> {
        val need = start + length
        if (need <= duration) return duration to true
        return need.coerceAtMost(max) to (need <= max)
    }

    /**
     * Ключи времени символа без своих ключей (режимы Цикл / Один раз), чтобы его содержимое тоже шло со скоростью:
     * на каждый проход формы 0..dur-1 - линейный ключ в начале и Hold в конце. [cycles] проходов (не больше 200).
     * Пары (кадр проекта, внутренний кадр, линейно ли).
     */
    fun symbolKeys(start: Int, symDur: Int, factor: Float, clipLength: Int, loop: Boolean): List<Triple<Int, Int, Boolean>> {
        if (symDur <= 1 || clipLength <= 0) return emptyList()
        val pass = ((symDur - 1) * factor).roundToInt().coerceAtLeast(1)
        val period = (symDur * factor).roundToInt().coerceAtLeast(pass + 1)
        val cycles = if (loop) ((clipLength + period - 1) / period).coerceIn(1, 200) else 1
        val out = ArrayList<Triple<Int, Int, Boolean>>(cycles * 2)
        for (c in 0 until cycles) {
            val f0 = start + c * period
            out += Triple(f0, 0, true)
            out += Triple(f0 + pass, symDur - 1, false)
        }
        return out
    }
}
