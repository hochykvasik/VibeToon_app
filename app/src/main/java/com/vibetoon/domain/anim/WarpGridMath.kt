package com.vibetoon.domain.anim

/** Подготовка будущей версии Warp: повышение степени поверхности Безье без изменения формы.
 * Пока не подключено к проектному формату, жестам и рендереру; старые 4×4 ключи не меняются. */
object WarpGridMath {
    const val MIN_POINTS = 2
    const val MAX_POINTS = 9

    /** Контрольные смещения, построчно, по два float на точку. */
    fun elevate(offsets: FloatArray, size: Int): FloatArray? {
        if (size !in MIN_POINTS until MAX_POINTS || offsets.size != size * size * 2 || offsets.any { !it.isFinite() }) return null
        val next = size + 1
        val horizontal = FloatArray(size * next * 2)
        for (y in 0 until size) for (x in 0 until next) for (c in 0..1) {
            val value = when (x) {
                0 -> offsets[(y * size) * 2 + c]
                size -> offsets[(y * size + size - 1) * 2 + c]
                else -> {
                    val f = x.toDouble() / size
                    f * offsets[(y * size + x - 1) * 2 + c] + (1 - f) * offsets[(y * size + x) * 2 + c]
                }
            }
            horizontal[(y * next + x) * 2 + c] = value.toFloat()
        }
        val result = FloatArray(next * next * 2)
        for (y in 0 until next) for (x in 0 until next) for (c in 0..1) {
            val value = when (y) {
                0 -> horizontal[x * 2 + c]
                size -> horizontal[((size - 1) * next + x) * 2 + c]
                else -> {
                    val f = y.toDouble() / size
                    f * horizontal[((y - 1) * next + x) * 2 + c] + (1 - f) * horizontal[(y * next + x) * 2 + c]
                }
            }
            result[(y * next + x) * 2 + c] = value.toFloat()
        }
        return result.takeIf { it.all(Float::isFinite) }
    }

    /** Уменьшение степени только когда обратное повышение восстанавливает все контрольные точки
     * с погрешностью не более [tolerance]. Произвольную форму не приближает молча. */
    fun reduce(offsets: FloatArray, size: Int, tolerance: Float = 1e-5f): FloatArray? {
        if (size !in (MIN_POINTS + 1)..MAX_POINTS || offsets.size != size * size * 2 ||
            offsets.any { !it.isFinite() } || !tolerance.isFinite() || tolerance < 0f) return null
        val reduced = size - 1
        fun lower(input: DoubleArray): DoubleArray {
            val out = DoubleArray(reduced)
            out[0] = input[0]
            for (i in 1 until reduced) {
                val f = i.toDouble() / reduced
                out[i] = (input[i] - f * out[i - 1]) / (1.0 - f)
            }
            return out
        }
        val horizontal = Array(size) { Array(reduced) { DoubleArray(2) } }
        for (y in 0 until size) for (c in 0..1) {
            val row = lower(DoubleArray(size) { x -> offsets[(y * size + x) * 2 + c].toDouble() })
            for (x in 0 until reduced) horizontal[y][x][c] = row[x]
        }
        val output = FloatArray(reduced * reduced * 2)
        for (x in 0 until reduced) for (c in 0..1) {
            val col = lower(DoubleArray(size) { y -> horizontal[y][x][c] })
            for (y in 0 until reduced) output[(y * reduced + x) * 2 + c] = col[y].toFloat()
        }
        if (output.any { !it.isFinite() }) return null
        val restored = elevate(output, reduced) ?: return null
        return if (restored.indices.all { kotlin.math.abs(restored[it].toDouble() - offsets[it].toDouble()) <= tolerance }) output else null
    }

    /** Вычисление для тестов и будущей математики жестов; u,v внутри закреплённой области. */
    fun evaluate(offsets: FloatArray, size: Int, u: Float, v: Float): Pair<Float, Float>? {
        if (size !in MIN_POINTS..MAX_POINTS || offsets.size != size * size * 2 || offsets.any { !it.isFinite() } ||
            !u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        fun curve(values: DoubleArray, t: Double): Double {
            for (n in values.size - 1 downTo 1) for (i in 0 until n) values[i] = values[i] * (1.0 - t) + values[i + 1] * t
            return values[0]
        }
        fun channel(c: Int): Float {
            val rows = DoubleArray(size) { y -> curve(DoubleArray(size) { x -> offsets[(y * size + x) * 2 + c].toDouble() }, u.toDouble()) }
            return curve(rows, v.toDouble()).toFloat()
        }
        return channel(0) to channel(1)
    }
}
