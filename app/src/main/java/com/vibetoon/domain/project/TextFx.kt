package com.vibetoon.domain.project

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Процедурные эффекты текстового слоя. Всё считается чистыми функциями от времени [t] (секунды
 * проекта), поэтому экран и экспорт MP4 дают один и тот же кадр. Нулевые значения = эффект выключен.
 */
data class TextFx(
    /** Печать: буквы появляются по одной за [progressDur] секунд. */
    val progress: Boolean = false,
    val progressDur: Float = 2f,
    /** Счётчик: число (или таймкод) бежит от [countFrom] до [countTo] за [countDur] секунд. «#» в тексте - место числа. */
    val counter: Boolean = false,
    val countFrom: Float = 0f,
    val countTo: Float = 12500f,
    val countDur: Float = 2f,
    val timecode: Boolean = false,
    /** Тряска: амплитуда в долях размера шрифта, 0..1 (0 - выкл), частота в Гц. */
    val shake: Float = 0f,
    val shakeHz: Float = 12f,
    /** Текст по контуру: изгиб дуги -100..100 (0 - прямая строка). */
    val pathBend: Float = 0f,
    /** Толщина шрифта плавно идёт от [weightFrom] к [weightTo] (300..800) за [weightDur] секунд. */
    val weight: Boolean = false,
    val weightFrom: Int = 300,
    val weightTo: Int = 800,
    val weightDur: Float = 2f,
    /** Рандомизатор: перед проявлением буквы перебирают случайные символы, всё проявляется за [randomDur] секунд. */
    val random: Boolean = false,
    val randomDur: Float = 1.5f
) {
    val active get() = progress || counter || shake > 0f || pathBend != 0f || weight || random

    /** Зависит ли картинка от времени (если нет - один раз нарисованная текстура годится для всех кадров). */
    val animated get() = progress || counter || shake > 0f || weight || random

    /** Ключ состояния на момент [t]: пока он не поменялся, перерисовывать текст не нужно. */
    fun stateKey(t: Float, fps: Int): String {
        val frame = floor(t * fps).toInt()
        return if (!animated) "static" else "f$frame"
    }
}

object TextFxMath {
    const val SCRAMBLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@%&?!"
    const val WEIGHT_MIN = 300
    const val WEIGHT_MAX = 800

    private fun ramp(t: Float, dur: Float) = if (dur <= 0f) 1f else (t / dur).coerceIn(0f, 1f)

    /** Число с разделителем тысяч: 12500 -> «12,500». */
    fun formatNumber(v: Float): String {
        val n = Math.round(v).toLong()
        val s = abs(n).toString()
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return (if (n < 0) "-" else "") + sb
    }

    /** Таймкод «ММ:СС:КК» (минуты, секунды, кадры) из секунд. */
    fun formatTimecode(sec: Float, fps: Int): String {
        val f = max(1, fps)
        val total = floor(max(0f, sec) * f).toLong()
        val frames = total % f
        val s = (total / f) % 60
        val m = total / f / 60
        return "%02d:%02d:%02d".format(m, s, frames)
    }

    /** Значение счётчика на момент [t]: линейно от [TextFx.countFrom] к [TextFx.countTo]. */
    fun counterValue(fx: TextFx, t: Float): Float {
        val k = ramp(t, fx.countDur)
        return fx.countFrom + (fx.countTo - fx.countFrom) * k
    }

    fun counterText(template: String, fx: TextFx, t: Float, fps: Int): String {
        val body = if (fx.timecode) formatTimecode(counterValue(fx, t), fps) else formatNumber(counterValue(fx, t))
        return if (template.contains('#')) template.replace("#", body) else body
    }

    /** Самая длинная возможная строка счётчика: по ней считается размер блока, чтобы он не прыгал. */
    fun counterWidest(template: String, fx: TextFx, fps: Int): String {
        val a = counterText(template, fx, 0f, fps)
        val b = counterText(template, fx, fx.countDur + 1000f, fps)
        return if (b.length >= a.length) b else a
    }

    /** Сколько букв из [n] видно при печати на момент [t]. */
    fun visibleCount(n: Int, fx: TextFx, t: Float): Int =
        if (!fx.progress) n else min(n, floor(n * ramp(t, fx.progressDur) + 1e-4f).toInt())

    /** Детерминированный псевдослучайный символ для буквы [i] на кадре [frame]. */
    fun scrambleChar(i: Int, frame: Int): Char {
        var h = i * 73856093 xor frame * 19349663 xor 0x5bd1e995
        h = (h xor (h ushr 13)) * 0x5bd1e995
        h = h xor (h ushr 15)
        return SCRAMBLE[Math.floorMod(h, SCRAMBLE.length)]
    }

    /**
     * Строка, которую надо нарисовать на момент [t]: счётчик подменяет текст, рандомизатор подменяет
     * ещё не проявившиеся буквы, печать обрезает хвост (обрезанные буквы заменены на '\u0000' - они не рисуются,
     * но занимают место, чтобы остальные буквы не ездили).
     */
    fun display(text: String, fx: TextFx, t: Float, fps: Int): String {
        var s = if (fx.counter) counterText(text, fx, t, fps) else text
        if (fx.random) {
            val chars = s.toCharArray()
            val letters = chars.indices.filter { !chars[it].isWhitespace() }
            val frame = floor(t * max(1, fps)).toInt()
            for ((k, i) in letters.withIndex()) {
                val reveal = fx.randomDur * (k + 1) / letters.size
                if (t < reveal) chars[i] = scrambleChar(i, frame)
            }
            s = String(chars)
        }
        if (fx.progress) {
            val letters = s.count { it != '\n' }
            val show = visibleCount(letters, fx, t)
            var seen = 0
            val chars = s.toCharArray()
            for (i in chars.indices) {
                if (chars[i] == '\n') continue
                if (seen >= show) chars[i] = '\u0000'
                seen++
            }
            s = String(chars)
        }
        return s
    }

    /** Толщина шрифта на момент [t], 300..800. */
    fun weightAt(fx: TextFx, t: Float): Int {
        if (!fx.weight) return 400
        val k = ramp(t, fx.weightDur)
        val w = fx.weightFrom + (fx.weightTo - fx.weightFrom) * k
        return Math.round(w).coerceIn(WEIGHT_MIN, WEIGHT_MAX)
    }

    /** Смещение буквы [i] при тряске на момент [t]: [dx, dy] в пикселях; на одном кадре смещение не меняется. */
    fun shakeOffset(i: Int, fx: TextFx, t: Float, size: Float, out: FloatArray = FloatArray(2)): FloatArray {
        if (fx.shake <= 0f) { out[0] = 0f; out[1] = 0f; return out }
        val a = fx.shake * size * 0.25f
        val step = floor(t * max(1f, fx.shakeHz))   // шум меняется шагами с частотой shakeHz
        out[0] = a * noise(i * 2, step)
        out[1] = a * noise(i * 2 + 1, step)
        return out
    }

    /** Значение в -1..1 от (индекс, шаг): сглаживать не нужно, тряска ступенчатая. */
    private fun noise(i: Int, step: Float): Float {
        var h = i * 374761393 + step.toInt() * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return ((h and 0xFFFF) / 65535f) * 2f - 1f
    }

    /**
     * Положение и угол буквы на дуге: строка шириной [width] изгибается так, что середина
     * приподнята (bend > 0) или опущена на bend/100 * [width] * 0.35. Возвращает [x, y, угол в радианах]
     * для центра буквы с центром на расстоянии [s] от начала строки (0..width).
     */
    fun onArc(s: Float, width: Float, bend: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val lift = bend / 100f * width * 0.35f
        val u = if (width <= 0f) 0.5f else (s / width).coerceIn(0f, 1f)
        // квадратичная кривая Безье: концы на прямой, середина смещена на 2*lift (в середине подъём = lift)
        val x = u * width
        val y = -4f * lift * u * (1f - u)
        val dy = -4f * lift * (1f - 2f * u) / width.coerceAtLeast(1f)
        out[0] = x; out[1] = y; out[2] = atan2(dy, 1f)
        return out
    }

    /** Запас высоты блока под изгиб: |подъём| с обеих сторон. */
    fun pathPad(width: Float, bend: Float) = abs(bend) / 100f * width * 0.35f
}
