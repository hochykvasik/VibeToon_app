package com.vibetoon.domain.anim

/** Tensor-product Bezier control offsets in row-major order. This is not yet the renderer's cell grid. */
object RectWarpGridMath {
    private fun valid(data: FloatArray, w: Int, h: Int) =
        w in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS && h in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS &&
            data.size == w * h * 2 && data.all(Float::isFinite)

    /** Change one degree; reduction is accepted only when all control points round-trip. */
    private fun step(data: FloatArray, w: Int, h: Int, horizontal: Boolean, increase: Boolean, tolerance: Float): FloatArray? {
        if (!valid(data, w, h) || !tolerance.isFinite() || tolerance < 0f) return null
        val n = if (horizontal) w else h
        val next = n + if (increase) 1 else -1
        if (next !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS) return null
        val nw = if (horizontal) next else w
        val nh = if (horizontal) h else next
        val result = FloatArray(nw * nh * 2)
        val lines = if (horizontal) h else w
        fun index(line: Int, pos: Int, width: Int, channel: Int): Int =
            if (horizontal) (line * width + pos) * 2 + channel else (pos * width + line) * 2 + channel
        for (line in 0 until lines) for (c in 0..1) {
            val old = DoubleArray(n) { data[index(line, it, w, c)].toDouble() }
            val out = DoubleArray(next)
            if (increase) {
                out[0] = old[0]; out[next - 1] = old[n - 1]
                for (i in 1 until n) {
                    val f = i.toDouble() / n
                    out[i] = f * old[i - 1] + (1.0 - f) * old[i]
                }
            } else {
                // Рекурсия от одного края усиливает ошибки около противоположного края.
                // Восстанавливаем половины с ближайших концов и проверяем весь ряд ниже.
                val fromLeft = DoubleArray(next); val fromRight = DoubleArray(next)
                fromLeft[0] = old[0]; fromRight[next - 1] = old[n - 1]
                for (i in 1 until next) {
                    val f = i.toDouble() / next
                    fromLeft[i] = (old[i] - f * fromLeft[i - 1]) / (1.0 - f)
                }
                for (i in next - 1 downTo 1) {
                    val f = i.toDouble() / next
                    fromRight[i - 1] = (old[i] - (1.0 - f) * fromRight[i]) / f
                }
                for (i in 0 until next) out[i] = if (i < next / 2) fromLeft[i] else fromRight[i]
            }
            for (i in 0 until next) result[index(line, i, nw, c)] = out[i].toFloat()
        }
        if (!result.all(Float::isFinite)) return null
        if (!increase) {
            val restored = step(result, nw, nh, horizontal, true, tolerance) ?: return null
            if (restored.indices.any { kotlin.math.abs(restored[it].toDouble() - data[it].toDouble()) > tolerance }) return null
        }
        return result
    }

    /** Refuses lossy reductions, preserving the input on failure. */
    fun convert(data: FloatArray, width: Int, height: Int, targetWidth: Int, targetHeight: Int, tolerance: Float = 1e-5f): FloatArray? {
        if (!valid(data, width, height) || targetWidth !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS ||
            targetHeight !in WarpGridMath.MIN_POINTS..WarpGridMath.MAX_POINTS || !tolerance.isFinite() || tolerance < 0f) return null
        var out = data.copyOf(); var w = width; var h = height
        while (w != targetWidth) { val up = w < targetWidth; out = step(out, w, h, true, up, tolerance) ?: return null; w += if (up) 1 else -1 }
        while (h != targetHeight) { val up = h < targetHeight; out = step(out, w, h, false, up, tolerance) ?: return null; h += if (up) 1 else -1 }
        return out
    }

    fun evaluate(data: FloatArray, width: Int, height: Int, u: Float, v: Float): Pair<Float, Float>? {
        if (!valid(data, width, height) || !u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        fun curve(a: DoubleArray, t: Double): Double {
            for (n in a.size - 1 downTo 1) for (i in 0 until n) a[i] = a[i] * (1 - t) + a[i + 1] * t
            return a[0]
        }
        fun channel(c: Int): Float = curve(DoubleArray(height) { y ->
            curve(DoubleArray(width) { x -> data[(y * width + x) * 2 + c].toDouble() }, u.toDouble())
        }, v.toDouble()).toFloat()
        return channel(0) to channel(1)
    }
}
