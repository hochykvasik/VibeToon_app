package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Дорожка анимации одного слоя. Неизменяемая: ключи отсортированы, на кадр не больше одного. */
class AnimationTrack(val layerId: Int, val keys: List<Keyframe> = emptyList()) {

    fun keyAt(frame: Int): Keyframe? = keys.firstOrNull { it.frame == frame }

    /** Ставит ключ; если на этом кадре уже есть, заменяет. */
    fun upsert(key: Keyframe) =
        AnimationTrack(layerId, (keys.filter { it.frame != key.frame } + key).sortedBy { it.frame })

    fun remove(frame: Int) = AnimationTrack(layerId, keys.filter { it.frame != frame })

    fun isEmpty() = keys.isEmpty()

    /**
     * Трансформация на кадре: null, если ключей нет. До первого ключа и после последнего
     * значение держится (hold). Между ключами линейная интерполяция (Lerp) всех полей;
     * поворот интерполируется как есть, без приведения к диапазону (0 -> 4π = два оборота).
     */
    fun evaluate(frame: Int): LayerTransform? = evaluate(frame.toFloat())

    /** То же для дробного кадра (ретайминг клипа: скорость, зацикливание). */
    fun evaluate(frame: Float): LayerTransform? {
        if (keys.isEmpty()) return null
        if (frame <= keys.first().frame) return keys.first().toTransform()
        if (frame >= keys.last().frame) return keys.last().toTransform()
        // бинарный поиск: keys[lo].frame <= frame < keys[hi].frame
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].frame <= frame) lo = mid else hi = mid
        }
        val a = keys[lo]
        val b = keys[hi]
        // плавность берётся у левого ключа сегмента
        val t = a.ease((frame - a.frame) / (b.frame - a.frame).toFloat())
        return LayerTransform(
            dx = lerp(a.dx, b.dx, t),
            dy = lerp(a.dy, b.dy, t),
            scaleX = lerp(a.scaleX, b.scaleX, t),
            scaleY = lerp(a.scaleY, b.scaleY, t),
            rotation = lerp(a.rotation, b.rotation, t),
            opacity = lerp(a.opacity, b.opacity, t)
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
