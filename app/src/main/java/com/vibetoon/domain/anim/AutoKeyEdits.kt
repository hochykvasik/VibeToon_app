package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Чистые операции автоключа: не меняют проект и сохраняют easing/кривую через Animation.withKey. */
object AutoKeyEdits {
    fun transform(anim: Animation, id: Int, frame: Int, before: LayerTransform, value: LayerTransform, effects: List<Effect>): Animation {
        // Первый автоключ не должен изменять все предыдущие кадры из-за hold до первого ключа.
        val baseline = if (frame > 0 && !anim.hasTrack(id)) anim.withKey(id, 0, before) else anim
        return EffectTracks.snapshot(id, effects, baseline.withKey(id, frame, value), frame)
    }

    fun effect(anim: Animation, layer: Int, frame: Int, transform: LayerTransform,
               effects: List<Effect>, effect: Effect, changes: Map<Int, Float>): Animation {
        require(layer in 0..EffectTracks.MAX_LAYER && effect.slot in 0 until EffectTracks.SLOTS)
        var result = anim
        if (frame > 0 && !result.hasTrack(layer)) result = result.withKey(layer, 0, transform)
        if (!result.hasKey(layer, frame)) result = result.withKey(layer, frame, transform)
        if (frame > 0) for (param in changes.keys) {
            require(param in effect.p.indices && param < EffectTracks.PARAMS)
            val id = EffectTracks.id(layer, effect.slot, param)
            if (!result.hasTrack(id)) result = result.withKey(id, 0, LayerTransform(dx = effect.p[param]))
        }
        result = EffectTracks.snapshot(layer, effects, result, frame)
        for ((param, value) in changes) {
            require(param in effect.p.indices && param < EffectTracks.PARAMS)
            result = result.withKey(EffectTracks.id(layer, effect.slot, param), frame,
                LayerTransform(dx = EffectMath.clamp(effect.type, param, value)))
        }
        return result
    }

    /** Текущий редактор пишет и показывает ключи во времени проекта; дробный ретайминг пока не согласован с UI. */
    fun canKeyTransform(frame: Int, sourceFrame: Float): Boolean = kotlin.math.abs(frame - sourceFrame) < 0.0001f
}
