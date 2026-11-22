package com.vibetoon.domain.paint

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Вид текстуры зерна кисти (значения совпадают с `grainKind` в C++). */
enum class GrainKind(val gl: Int, val title: String) {
    NONE(0, "Без зерна"),
    PENCIL(1, "Карандаш"),
    SCREENTONE(2, "Скринтон"),
    PAPER(3, "Бумага"),
    SCREENTONE_COARSE(4, "Скринтон крупный"),
    HLINES(5, "Горизонтальные линии"),
    VLINES(6, "Вертикальные линии")
}

/**
 * Привязка зерна: MOVING - зерно едет вместе со штампом; CANVAS - зерно неподвижно в координатах холста (слоя):
 * `grainUV = worldPos / (grainTextureSize * grainScale)`. Для карандаша и скринтона нужен CANVAS, иначе при тонкой
 * линии текстура «мылится» и растягивается вместе со штампом.
 */
enum class GrainMode(val gl: Int) { NONE(0), MOVING(1), CANVAS(2) }

/**
 * Процедурные текстуры зерна: [SIZE] x [SIZE], один байт на пиксель (0 - краска не ложится, 255 - ложится полностью),
 * бесшовные (периодические), чтобы работал GL_REPEAT. Генерируются один раз и передаются в GL как R8.
 */
object GrainMath {
    const val SIZE = 256
    private val cache = HashMap<GrainKind, ByteArray>()

    @Synchronized
    fun texture(kind: GrainKind): ByteArray? = if (kind == GrainKind.NONE) null else cache.getOrPut(kind) { generate(kind) }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    /** Периодический value-noise: решётка [cells] x [cells] с замыканием, сглаженная интерполяция. */
    private fun noise(u: Float, v: Float, cells: Int, seed: Int): Float {
        val x = u * cells; val y = v * cells
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx); val sy = fy * fy * (3f - 2f * fy)
        fun h(i: Int, j: Int) = hash(Math.floorMod(i, cells), Math.floorMod(j, cells), seed)
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }

    private fun fbm(u: Float, v: Float, seed: Int) = noise(u, v, 16, seed) * 0.5f + noise(u, v, 32, seed + 1) * 0.3f + noise(u, v, 64, seed + 2) * 0.2f

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    fun generate(kind: GrainKind, size: Int = SIZE): ByteArray {
        val out = ByteArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val u = x.toFloat() / size; val v = y.toFloat() / size
            val value = when (kind) {
                GrainKind.NONE -> 1f
                // карандаш: зерно бумаги (3 октавы) с резким порогом и мелкой крапинкой
                GrainKind.PENCIL -> {
                    val g = fbm(u, v, 11)
                    val speck = hash(x, y, 5) * 0.25f
                    (0.18f + 0.82f * smooth(0.32f, 0.68f, g + speck - 0.12f)).coerceIn(0f, 1f)
                }
                // бумага: слабая крупная фактура, краска всегда ложится
                GrainKind.PAPER -> 0.72f + 0.28f * fbm(u, v, 23)
                // крупный скринтон: период 64 px, точки около 40% площади (комиксы, манга)
                GrainKind.SCREENTONE_COARSE -> {
                    val s = (x + y) / 64f; val t = (x - y) / 64f
                    val ds = s - Math.round(s); val dt = t - Math.round(t)
                    val dist = 45.255f * sqrt(ds * ds + dt * dt)
                    (COARSE_DOT_RADIUS + 0.5f - dist).coerceIn(0f, 1f)
                }
                // штриховка: линии толщиной 3 px через 8 px (горизонтальные / вертикальные)
                GrainKind.HLINES -> (1.5f + 0.5f - kotlin.math.abs((y % 8) - 3.5f)).coerceIn(0f, 1f)
                GrainKind.VLINES -> (1.5f + 0.5f - kotlin.math.abs((x % 8) - 3.5f)).coerceIn(0f, 1f)
                // скринтон: решётка точек под 45 градусов с периодом 32 px по осям (векторы решётки (16,16) и (16,-16))
                GrainKind.SCREENTONE -> {
                    val s = (x + y) / 32f; val t = (x - y) / 32f
                    val ds = s - Math.round(s); val dt = t - Math.round(t)
                    val dist = 22.627f * sqrt(ds * ds + dt * dt)
                    (DOT_RADIUS + 0.5f - dist).coerceIn(0f, 1f)
                }
            }
            out[y * size + x] = (value * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** Радиус точки скринтона (px): покрытие около 30% площади. */
    const val DOT_RADIUS = 7f
    const val COARSE_DOT_RADIUS = 16f

    /** Доля пикселей с покрытием выше половины - для проверок и отображения. */
    fun coverage(tex: ByteArray): Float = tex.count { (it.toInt() and 0xFF) > 127 }.toFloat() / tex.size

    fun min(tex: ByteArray) = tex.minOf { it.toInt() and 0xFF }
    fun max(tex: ByteArray) = tex.maxOf { it.toInt() and 0xFF }
}
