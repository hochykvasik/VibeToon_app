package com.vibetoon.domain.paint

import kotlin.math.roundToInt

/**
 * Спринт 37: точные цветовые модели для окна выбора цвета - RGB (0-255) и HSB (H 0-360°, S и B 0-100 %) рядом с кругом
 * HSV, HEX, пипеткой и историей цветов. Круг, ползунки и поля считают цвет одной математикой (без android.graphics),
 * поэтому значения совпадают и проверяются JVM-тестами. Цвет - ARGB Long (0xFFRRGGBB), как в остальном приложении.
 */
object ColorModels {
    data class Rgb(val r: Int, val g: Int, val b: Int)
    /** [h] - оттенок 0..360, [s] и [v] - 0..1 (HSB = HSV). */
    data class Hsb(val h: Float, val s: Float, val v: Float)

    /** Режимы окна выбора цвета: круг HSV, ползунки RGB, ползунки HSB. */
    const val MODE_WHEEL = 0
    const val MODE_RGB = 1
    const val MODE_HSB = 2
    val MODES = listOf(MODE_WHEEL, MODE_RGB, MODE_HSB)
    fun modeLabel(mode: Int): String = when (mode) { MODE_RGB -> "RGB"; MODE_HSB -> "HSB"; else -> "Круг HSV" }

    fun hsbToRgb(h: Float, s: Float, v: Float): Rgb {
        val hh = (if (h.isFinite()) ((h % 360f) + 360f) % 360f else 0f) / 60f
        val ss = s.coerceIn(0f, 1f); val vv = v.coerceIn(0f, 1f)
        val c = vv * ss
        val x = c * (1f - Math.abs(hh % 2f - 1f))
        val m = vv - c
        val (r, g, b) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Rgb(ch((r + m) * 255f), ch((g + m) * 255f), ch((b + m) * 255f))
    }

    /** RGB -> HSB. У серого (S = 0) и чёрного (V = 0) оттенок не определён: остаётся [keepHue] (ползунок H не прыгает в 0). */
    fun rgbToHsb(r: Int, g: Int, b: Int, keepHue: Float = 0f, keepSat: Float = 0f): Hsb {
        val rf = r.coerceIn(0, 255) / 255f; val gf = g.coerceIn(0, 255) / 255f; val bf = b.coerceIn(0, 255) / 255f
        val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf)
        val d = max - min
        val v = max
        if (max == 0f) return Hsb(keepHue, keepSat, 0f)
        val s = d / max
        if (d == 0f) return Hsb(keepHue, 0f, v)
        var h = when (max) {
            rf -> 60f * (((gf - bf) / d) % 6f)
            gf -> 60f * ((bf - rf) / d + 2f)
            else -> 60f * ((rf - gf) / d + 4f)
        }
        if (h < 0f) h += 360f
        return Hsb(h, s, v)
    }

    fun argb(c: Rgb): Long = 0xFF000000L or (c.r.toLong() shl 16) or (c.g.toLong() shl 8) or c.b.toLong()
    fun argb(h: Float, s: Float, v: Float): Long = argb(hsbToRgb(h, s, v))
    fun rgbOf(argb: Long): Rgb = Rgb(((argb shr 16) and 0xFF).toInt(), ((argb shr 8) and 0xFF).toInt(), (argb and 0xFF).toInt())
    fun hsbOf(argb: Long, keepHue: Float = 0f, keepSat: Float = 0f): Hsb = rgbOf(argb).let { rgbToHsb(it.r, it.g, it.b, keepHue, keepSat) }

    /** «#RRGGBB». */
    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFFL)

    /** HEX: «#RRGGBB», «RRGGBB», «#RGB» (короткая запись); иначе null. */
    fun parseHex(text: String): Long? {
        val d = text.trim().removePrefix("#")
        if (d.any { it.uppercaseChar() !in '0'..'9' && it.uppercaseChar() !in 'A'..'F' }) return null
        val full = when (d.length) { 6 -> d; 3 -> d.map { "$it$it" }.joinToString(""); else -> return null }
        return 0xFF000000L or full.toLong(16)
    }

    /** Значение поля ввода канала: целое в 0..[max] (лишнее обрезается), пусто/мусор - null. */
    fun parseChannel(text: String, max: Int): Int? = text.trim().toIntOrNull()?.coerceIn(0, max)

    /** Показ HSB целыми: H 0..360 (360 показывается как 0), S и B в процентах. */
    fun hsbInts(c: Hsb): Triple<Int, Int, Int> = Triple(c.h.roundToInt() % 360, (c.s * 100f).roundToInt(), (c.v * 100f).roundToInt())

    private fun ch(f: Float): Int = f.roundToInt().coerceIn(0, 255)
}
