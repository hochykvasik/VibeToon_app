package com.vibetoon.domain.anim

import com.vibetoon.domain.model.Affine2D

/** Геометрия опорной точки; STEP — разрыв, не непрерывное движение. */
data class PathPoint(val x: Float, val y: Float)
data class MotionPath(val segments: List<List<PathPoint>>, val keys: List<PathPoint>)

object MotionPathMath {
    const val MAX_KEYS = 128
    private const val SAMPLES_PER_SEGMENT = 16

    /** Ограничиваем объём работы; результат — только экранный оверлей, не данные проекта. */
    fun build(track: AnimationTrack, pivotX: Float, pivotY: Float, parent: Affine2D): MotionPath? {
        val keys = track.keys.sortedBy { it.frame }
        if (keys.size < 2 || keys.size > MAX_KEYS) return null
        fun point(dx: Float, dy: Float): PathPoint {
            val x = pivotX + dx
            val y = pivotY + dy
            return PathPoint(parent.applyX(x, y), parent.applyY(x, y))
        }
        val marks = keys.map { point(it.dx, it.dy) }
        val segments = keys.zipWithNext().mapNotNull { (a, b) ->
            if (a.curve == null && a.easing == EasingType.STEP) return@mapNotNull null
            (0..SAMPLES_PER_SEGMENT).map { i ->
                val f = a.frame + (b.frame - a.frame) * (i.toFloat() / SAMPLES_PER_SEGMENT)
                val t = track.evaluate(f)!!
                point(t.dx, t.dy)
            }
        }
        if ((marks + segments.flatten()).any { !it.x.isFinite() || !it.y.isFinite() }) return null
        return MotionPath(segments, marks)
    }
}
