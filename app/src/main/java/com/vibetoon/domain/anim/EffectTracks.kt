package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Отдельные дорожки параметров; стабильный slot живёт вместе с экземпляром эффекта. */
object EffectTracks {
    const val SLOTS = 8
    const val PARAMS = 8
    const val MAX_LAYER = (Int.MAX_VALUE - 64) / (SLOTS * PARAMS)

    fun id(layer: Int, slot: Int, param: Int): Int {
        require(layer in 0..MAX_LAYER && slot in 0 until SLOTS && param in 0 until PARAMS)
        return -1 - (layer * SLOTS * PARAMS + slot * PARAMS + param)
    }
    fun belongs(id: Int, layer: Int): Boolean = id < 0 && layer in 0..MAX_LAYER && (-1L - id) / (SLOTS * PARAMS) == layer.toLong()
    fun ids(layer: Int, slot: Int): List<Int> = (0 until PARAMS).map { id(layer, slot, it) }

    /** Исправляет отсутствующие/повторные слоты старых и повреждённых файлов. */
    fun assignSlots(effects: List<Effect>): List<Effect> {
        val used = HashSet<Int>()
        return effects.take(SLOTS).map { e ->
            val slot = e.slot.takeIf { it in 0 until SLOTS && it !in used }
                ?: (0 until SLOTS).first { it !in used }
            used += slot
            e.copy(slot = slot)
        }
    }

    fun at(layer: Int, effects: List<Effect>, anim: Animation, frame: Float): List<Effect> = effects.map { e ->
        if (layer !in 0..MAX_LAYER || e.slot !in 0 until SLOTS) e else e.copy(p = e.p.mapIndexed { i, value ->
            if (i >= PARAMS) value else anim.tracks[id(layer, e.slot, i)]?.evaluate(frame)?.dx ?: value
        })
    }

    /** Интеграл скорости Spin: изменение скорости не должно скачком менять угол. */
    fun spinAngle(layer: Int, effects: List<Effect>, anim: Animation, frame: Int, fps: Int): Float {
        val end = frame.coerceAtLeast(0).toFloat()
        var degreeFrames = 0.0
        for (effect in effects.filter { it.type == EffectMath.SPIN }) {
            val track = if (layer in 0..MAX_LAYER && effect.slot in 0 until SLOTS) anim.tracks[id(layer, effect.slot, 0)] else null
            if (track == null || track.keys.isEmpty()) {
                degreeFrames += effect.p.getOrElse(0) { 0f } * end
                continue
            }
            val boundaries = (listOf(0f, end) + track.keys.map { it.frame.toFloat().coerceIn(0f, end) }).distinct().sorted()
            for ((a, b) in boundaries.zipWithNext()) {
                val n = 32
                val step = (b - a) / n
                repeat(n) { i -> degreeFrames += track.evaluate(a + (i + 0.5f) * step)!!.dx * step }
            }
        }
        return Math.toRadians(degreeFrames / fps.coerceAtLeast(1)).toFloat()
    }

    fun snapshot(layer: Int, effects: List<Effect>, anim: Animation, frame: Int): Animation {
        if (layer !in 0..MAX_LAYER) return anim
        val current = at(layer, effects, anim, frame.toFloat())
        var result = anim
        for (e in current) if (e.slot in 0 until SLOTS) for (i in e.p.indices.take(PARAMS)) {
            result = result.withKey(id(layer, e.slot, i), frame, LayerTransform(dx = e.p[i]))
        }
        return result
    }
}
