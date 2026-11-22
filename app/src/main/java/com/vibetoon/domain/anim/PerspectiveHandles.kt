package com.vibetoon.domain.anim

/** Восемь ручек, но только четыре сохраняемые точки: боковая ручка двигает оба конца стороны. */
object PerspectiveHandles {
    const val CORNERS = 4
    const val HANDLES = 8
    private val edges = arrayOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)

    /** Первые четыре пары — углы экрана, следующие четыре — середины сторон по часовой стрелке. */
    fun screenHandles(corners: FloatArray): FloatArray? {
        if (corners.size != 8 || corners.any { !it.isFinite() }) return null
        val out = corners.copyOf(HANDLES * 2)
        for (edge in edges.indices) {
            val (a, b) = edges[edge]
            out[(edge + CORNERS) * 2] = (corners[a * 2] + corners[b * 2]) * .5f
            out[(edge + CORNERS) * 2 + 1] = (corners[a * 2 + 1] + corners[b * 2 + 1]) * .5f
        }
        return out.takeIf { it.all(Float::isFinite) }
    }

    fun edgeCorners(handle: Int): Pair<Int, Int>? = edges.getOrNull(handle - CORNERS)

    /** Общий дельта-сдвиг обоих углов, без разрыва стороны при упоре в лимит. */
    fun moveEdge(original: FloatArray, handle: Int, dx: Float, dy: Float, limit: Float = 1.5f): FloatArray? {
        val (a, b) = edgeCorners(handle) ?: return null
        if (original.size != 8 || original.any { !it.isFinite() || kotlin.math.abs(it) > limit } ||
            !dx.isFinite() || !dy.isFinite() || !limit.isFinite() || limit <= 0f) return null
        val deltaX = dx.coerceIn(maxOf(-limit - original[a * 2], -limit - original[b * 2]), minOf(limit - original[a * 2], limit - original[b * 2]))
        val deltaY = dy.coerceIn(maxOf(-limit - original[a * 2 + 1], -limit - original[b * 2 + 1]), minOf(limit - original[a * 2 + 1], limit - original[b * 2 + 1]))
        return original.copyOf().also { out ->
            out[a * 2] += deltaX; out[b * 2] += deltaX
            out[a * 2 + 1] += deltaY; out[b * 2 + 1] += deltaY
        }
    }
}
