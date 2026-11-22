package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Ключевой кадр: номер кадра и трансформация слоя (позиция, масштаб, поворот в радианах). */
data class Keyframe(
    val frame: Int,
    val dx: Float,
    val dy: Float,
    val scaleX: Float,
    val scaleY: Float,
    val rotation: Float,
    val opacity: Float = 1f,
    /** Плавность сегмента от этого ключа к следующему. */
    val easing: EasingType = EasingType.LINEAR,
    /**
     * Своя многоузловая кривая сегмента (формат см. [Curve]; старый формат из 4 чисел тоже читается).
     * Если задана, она важнее [easing].
     */
    val curve: List<Float>? = null
) {
    /** Доля пути значения для доли времени [t] этого сегмента. */
    fun ease(t: Float): Float {
        val c = curve?.let { Curve.parse(it) } ?: return easing.apply(t)
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return c.eval(t)
    }

    fun toTransform() = LayerTransform(dx, dy, scaleX, scaleY, rotation, opacity)

    companion object {
        fun of(frame: Int, t: LayerTransform, easing: EasingType = EasingType.LINEAR) =
            Keyframe(frame, t.dx, t.dy, t.scaleX, t.scaleY, t.rotation, t.opacity, easing)
    }
}
