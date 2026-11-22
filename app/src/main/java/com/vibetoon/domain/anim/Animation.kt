package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

data class ProjectSettings(val fps: Int = 24, val durationFrames: Int = 120)

/** Анимация проекта: настройки и дорожки по id слоя. Неизменяемая. */
data class Animation(
    val settings: ProjectSettings = ProjectSettings(),
    val tracks: Map<Int, AnimationTrack> = emptyMap()
) {
    fun hasTrack(layerId: Int) = tracks[layerId]?.isEmpty() == false

    fun hasKey(layerId: Int, frame: Int) = tracks[layerId]?.keyAt(frame) != null

    /**
     * Трансформация слоя на кадре. Есть трек с ключами: определяет только он, base игнорируется.
     * Нет трека: base (ручная правка без анимации) или единичная трансформация.
     */
    fun evaluate(layerId: Int, frame: Int, base: LayerTransform?): LayerTransform =
        tracks[layerId]?.evaluate(frame) ?: base ?: LayerTransform()

    /** Значение на дробном кадре (для клипов со скоростью/зацикливанием). */
    fun evaluate(layerId: Int, frame: Float, base: LayerTransform?): LayerTransform =
        tracks[layerId]?.evaluate(frame) ?: base ?: LayerTransform()

    fun withKey(layerId: Int, frame: Int, t: LayerTransform): Animation {
        val track = tracks[layerId] ?: AnimationTrack(layerId)
        // правка жестом или числом не должна сбрасывать выбранную плавность существующего ключа
        val old = track.keyAt(frame)
        return copy(tracks = tracks + (layerId to track.upsert(Keyframe.of(frame, t, old?.easing ?: EasingType.LINEAR).copy(curve = old?.curve))))
    }

    fun easingAt(layerId: Int, frame: Int): EasingType? = tracks[layerId]?.keyAt(frame)?.easing

    fun withEasing(layerId: Int, frame: Int, easing: EasingType): Animation {
        val track = tracks[layerId] ?: return this
        val key = track.keyAt(frame) ?: return this
        return copy(tracks = tracks + (layerId to track.upsert(key.copy(easing = easing, curve = null))))
    }

    /** Своя кривая Безье (4 числа) для ключа; null - вернуть к [easing]. */
    fun withCurve(layerId: Int, frame: Int, curve: List<Float>?): Animation {
        val track = tracks[layerId] ?: return this
        val key = track.keyAt(frame) ?: return this
        return copy(tracks = tracks + (layerId to track.upsert(key.copy(curve = curve))))
    }

    fun curveAt(layerId: Int, frame: Int): List<Float>? = tracks[layerId]?.keyAt(frame)?.curve

    /**
     * Удаляет ключ. Если это был последний ключ, трек исчезает, а второе значение результата -
     * трансформация этого ключа (её кладут в base, чтобы слой остался на месте).
     */
    fun withoutKey(layerId: Int, frame: Int): Pair<Animation, LayerTransform?> {
        val track = tracks[layerId] ?: return this to null
        val removed = track.remove(frame)
        return if (removed.isEmpty()) {
            copy(tracks = tracks - layerId) to track.keyAt(frame)?.toTransform()
        } else {
            copy(tracks = tracks + (layerId to removed)) to null
        }
    }
}

/** Часы воспроизведения: кадр по прошедшему времени (не по числу вызовов), цикл по длительности. */
object PlaybackMath {
    fun frameAt(elapsedNanos: Long, fps: Int, startFrame: Int, durationFrames: Int, loop: Boolean = true): Int {
        val advanced = elapsedNanos * fps / 1_000_000_000L
        return if (loop) ((startFrame + advanced) % durationFrames).toInt()
        else minOf(startFrame + advanced, (durationFrames - 1).toLong()).toInt()   // без цикла: остановка на последнем кадре
    }
}
