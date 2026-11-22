package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Одинаковые независимые дорожки FX дочерних слоёв. Не переносит ключи трансформации. */
object GroupEffectKeys {
    fun synchronized(ids: List<Int>, effects: List<Effect>, anim: Animation): Boolean {
        if (ids.isEmpty() || ids.any { it !in 0..EffectTracks.MAX_LAYER }) return false
        val first = ids.first()
        for (slot in 0 until EffectTracks.SLOTS) for (param in 0 until EffectTracks.PARAMS) {
            val reference = anim.tracks[EffectTracks.id(first, slot, param)]?.keys.orEmpty()
            if (ids.drop(1).any { anim.tracks[EffectTracks.id(it, slot, param)]?.keys.orEmpty() != reference }) return false
            val allowed = effects.any { it.slot == slot && param in it.p.indices }
            if (!allowed && reference.isNotEmpty()) return false
        }
        return true
    }

    fun hasKeys(ids: List<Int>, anim: Animation): Boolean = ids.any { id ->
        anim.tracks.keys.any { EffectTracks.belongs(it, id) }
    }

    /** Снимок всех параметров FX на кадре; первый ключ после нуля сохраняет исходное значение на нуле. */
    fun key(ids: List<Int>, effects: List<Effect>, animation: Animation, frame: Int,
            index: Int? = null, changes: Map<Int, Float> = emptyMap()): Animation? {
        if (frame !in 0 until animation.settings.durationFrames || !synchronized(ids, effects, animation)) return null
        if (effects.isEmpty() || effects.map { it.slot }.toSet().size != effects.size || effects.any {
                it.slot !in 0 until EffectTracks.SLOTS || it.p.size > EffectTracks.PARAMS || it.p.any { v -> !v.isFinite() }
            }) return null
        val target = index?.let { effects.getOrNull(it) ?: return null }
        if (changes.isNotEmpty() && (target == null || changes.keys.any { it !in target.p.indices || !changes.getValue(it).isFinite() })) return null
        var result = animation
        for (id in ids) {
            if (frame > 0) for (effect in effects) for (param in effect.p.indices) {
                val trackId = EffectTracks.id(id, effect.slot, param)
                if (!result.hasTrack(trackId)) result = result.withKey(trackId, 0, LayerTransform(dx = effect.p[param]))
            }
            result = EffectTracks.snapshot(id, effects, result, frame)
            if (target != null) for ((param, value) in changes) {
                result = result.withKey(EffectTracks.id(id, target.slot, param), frame,
                    LayerTransform(dx = EffectMath.clamp(target.type, param, value)))
            }
        }
        return result
    }
    /** Перенос всех FX-ключей одного кадра, без изменений трансформаций и чужих кадров. */
    fun move(ids: List<Int>, effects: List<Effect>, anim: Animation, frame: Int, delta: Int): Animation? {
        val destination = frame.toLong() + delta.toLong()
        if (delta == 0 || frame !in 0 until anim.settings.durationFrames || destination !in 0 until anim.settings.durationFrames.toLong() ||
            !synchronized(ids, effects, anim)) return null
        val keys = anim.tracks.filterKeys { id -> ids.any { EffectTracks.belongs(id, it) } }
        val moving = keys.filterValues { it.keyAt(frame) != null }
        if (moving.isEmpty() || moving.values.any { it.keyAt(destination.toInt()) != null }) return null
        var updated = anim.tracks
        for ((id, track) in moving) {
            val key = track.keyAt(frame) ?: return null
            updated = updated + (id to track.remove(frame).upsert(key.copy(frame = destination.toInt())))
        }
        return anim.copy(tracks = updated)
    }

    data class Deletion(val animation: Animation, val effects: List<Effect>)

    /** Сохраняет последнее значение в статической цепочке, как обычное удаление FX-ключа. */
    fun delete(ids: List<Int>, effects: List<Effect>, anim: Animation, frame: Int): Deletion? {
        if (frame !in 0 until anim.settings.durationFrames || !synchronized(ids, effects, anim)) return null
        if (ids.isEmpty()) return null
        var changed = false
        val first = ids.first()
        val updatedEffects = effects.map { effect ->
            effect.copy(p = effect.p.mapIndexed { param, initial ->
                if (effect.slot !in 0 until EffectTracks.SLOTS || param >= EffectTracks.PARAMS) initial else {
                    val track = anim.tracks[EffectTracks.id(first, effect.slot, param)]
                    val key = track?.keyAt(frame)
                    if (key != null && track.keys.size == 1) key.dx else initial
                }
            })
        }
        var result = anim
        for (id in ids) for (effect in effects) {
            if (effect.slot !in 0 until EffectTracks.SLOTS) return null
            for (param in effect.p.indices) {
                if (param >= EffectTracks.PARAMS) return null
                val tid = EffectTracks.id(id, effect.slot, param)
                if (result.hasKey(tid, frame)) { result = result.withoutKey(tid, frame).first; changed = true }
            }
        }
        return if (changed) Deletion(result, updatedEffects) else null
    }

}
