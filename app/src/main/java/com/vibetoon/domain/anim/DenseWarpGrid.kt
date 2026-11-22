package com.vibetoon.domain.anim

import kotlin.math.abs

/** Static bilinear detail over the existing cubic surface. Immutable snapshots for history/project. */
data class DenseWarpGrid(val width: Int, val height: Int, val offsets: List<Float>) {
    fun valid(): Boolean = width in 2..33 && height in 2..33 && (width > 4 || height > 4) &&
        offsets.size == width * height * 2 && offsets.all { it.isFinite() && abs(it) <= 1.5f }
    fun at(u: Float, v: Float): Pair<Float, Float> {
        val x = u.coerceIn(0f, 1f) * (width - 1); val y = v.coerceIn(0f, 1f) * (height - 1)
        val ix = x.toInt().coerceAtMost(width - 2); val iy = y.toInt().coerceAtMost(height - 2)
        val tx = x - ix; val ty = y - iy
        fun c(k: Int): Float {
            fun p(a: Int, b: Int) = offsets[(b * width + a) * 2 + k]
            return (1 - ty) * ((1 - tx) * p(ix, iy) + tx * p(ix + 1, iy)) + ty * ((1 - tx) * p(ix, iy + 1) + tx * p(ix + 1, iy + 1))
        }
        return c(0) to c(1)
    }
    /** Reject resampling if the bilinear surface would change at any old/new grid intersection. */
    fun resized(w: Int, h: Int): DenseWarpGrid? {
        if (!valid() || w !in 2..33 || h !in 2..33 || (w <= 4 && h <= 4)) return null
        val out = ArrayList<Float>(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) { val p = at(x.toFloat() / (w - 1), y.toFloat() / (h - 1)); out.add(p.first); out.add(p.second) }
        val result = DenseWarpGrid(w, h, out)
        if (!result.valid()) return null
        val xs = (0 until width).map { it.toFloat() / (width - 1) } + (0 until w).map { it.toFloat() / (w - 1) }
        val ys = (0 until height).map { it.toFloat() / (height - 1) } + (0 until h).map { it.toFloat() / (h - 1) }
        for (v in ys) for (u in xs) {
            val a = at(u, v); val b = result.at(u, v)
            if (abs(a.first - b.first) > 1e-5f || abs(a.second - b.second) > 1e-5f) return null
        }
        return result
    }
    companion object {
        fun zero(w: Int, h: Int) = DenseWarpGrid(w, h, List(w * h * 2) { 0f })
    }
}
