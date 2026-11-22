package com.vibetoon.domain.raster

import kotlin.math.hypot

/** Правила завершения одноштрихового лассо в экранных координатах. */
object LassoClosure {
    enum class Result { TAP, OPEN, CLOSE }

    enum class Resume { CLOSE, CONTINUE, MISS }

    /** Приоритет у начала: короткий тап замыкает, иначе продолжение только у конца. */
    fun resume(points: FloatArray, x: Float, y: Float, radius: Float): Resume {
        if (points.size < 4 || points.size % 2 != 0 || points.any { !it.isFinite() } ||
            !x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0f) return Resume.MISS
        if (points.size >= 6 && hypot(x - points[0], y - points[1]) <= radius) return Resume.CLOSE
        if (hypot(x - points[points.size - 2], y - points.last()) <= radius) return Resume.CONTINUE
        return Resume.MISS
    }

    fun classify(points: FloatArray, manual: Boolean, closeRadiusPx: Float): Result {
        if (points.size < 4 || points.size % 2 != 0 || points.any { !it.isFinite() }) return Result.TAP
        val x = points[0]; val y = points[1]
        var maxDistance = 0f
        for (i in 2 until points.size step 2) maxDistance = maxOf(maxDistance, hypot(points[i] - x, points[i + 1] - y))
        if (maxDistance <= 6f) return Result.TAP
        if (manual && (points.size < 6 || !closeRadiusPx.isFinite() || closeRadiusPx <= 0f ||
                    hypot(points[points.size - 2] - x, points.last() - y) > closeRadiusPx)) return Result.OPEN
        return Result.CLOSE
    }
}
