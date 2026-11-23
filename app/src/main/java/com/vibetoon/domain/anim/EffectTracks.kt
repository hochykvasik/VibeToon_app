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

    /**
     * Интеграл параметра [param] эффекта по кадрам 0..[frame] (значение x кадры). Нужен скоростям (Spin, частота Swing):
     * ключ на скорости не должен скачком менять накопленный угол/фазу.
     */
    private fun integral(layer: Int, effect: Effect, param: Int, anim: Animation, frame: Int): Double {
        val end = frame.coerceAtLeast(0).toFloat()
        val track = if (layer in 0..MAX_LAYER && effect.slot in 0 until SLOTS) anim.tracks[id(layer, effect.slot, param)] else null
        if (track == null || track.keys.isEmpty()) return effect.p.getOrElse(param) { 0f } * end.toDouble()
        var acc = 0.0
        val boundaries = (listOf(0f, end) + track.keys.map { it.frame.toFloat().coerceIn(0f, end) }).distinct().sorted()
        for ((a, b) in boundaries.zipWithNext()) {
            val n = 32
            val step = (b - a) / n
            repeat(n) { i -> acc += track.evaluate(a + (i + 0.5f) * step)!!.dx * step }
        }
        return acc
    }

    /** Интеграл скорости Spin: изменение скорости не должно скачком менять угол. */
    fun spinAngle(layer: Int, effects: List<Effect>, anim: Animation, frame: Int, fps: Int): Float {
        var degreeFrames = 0.0
        for (effect in effects) if (effect.type == EffectMath.SPIN) degreeFrames += integral(layer, effect, 0, anim, frame)
        return Math.toRadians(degreeFrames / fps.coerceAtLeast(1)).toFloat()
    }

    /** Угол Swing (радианы) на кадре: фаза - интеграл частоты (ключ частоты не дёргает слой), углы и волна - с ключей. */
    fun swingAngle(layer: Int, effects: List<Effect>, anim: Animation, frame: Int, fps: Int): Float {
        if (effects.none { it.type == EffectMath.SWING }) return 0f
        val cur = at(layer, effects, anim, frame.toFloat())
        var deg = 0.0
        for ((i, e) in effects.withIndex()) if (e.type == EffectMath.SWING) {
            val cycles = integral(layer, e, 0, anim, frame) / fps.coerceAtLeast(1)
            deg += EffectMath.swingDegrees(cur[i].p, cycles)
        }
        return Math.toRadians(deg).toFloat()
    }

    /** Добавка к повороту слоя от эффектов-трансформаций (Spin + Swing), радианы. */
    fun extraRotation(layer: Int, effects: List<Effect>, anim: Animation, frame: Int, fps: Int): Float =
        spinAngle(layer, effects, anim, frame, fps) + swingAngle(layer, effects, anim, frame, fps)

    /** Линейная часть всех «Осей растяжения» слоя на кадре (в осях слоя, вокруг его опорной точки) или null. */
    fun stretch(layer: Int, effects: List<Effect>, anim: Animation, frame: Int): com.vibetoon.domain.model.Affine2D? {
        if (effects.none { it.type == EffectMath.STRETCH_AXIS }) return null
        var m: com.vibetoon.domain.model.Affine2D? = null
        for (e in at(layer, effects, anim, frame.toFloat())) if (e.type == EffectMath.STRETCH_AXIS) {
            val s = EffectMath.stretchMatrix(e.p.getOrElse(0) { 1f }, e.p.getOrElse(1) { 0f })
            m = m?.times(s) ?: s
        }
        return m
    }

    /** Растяжения всех слоёв с «Осью растяжения» (вход SceneBuilder). */
    fun stretches(fx: Map<Int, List<Effect>>, anim: Animation, frame: Int): Map<Int, com.vibetoon.domain.model.Affine2D> {
        if (fx.isEmpty()) return emptyMap()
        val out = HashMap<Int, com.vibetoon.domain.model.Affine2D>()
        for ((id, l) in fx) stretch(id, l, anim, frame)?.let { out[id] = it }
        return out
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

    /**
     * Пакет 9: копия дорожек параметров эффектов слоя [from] на слой [to] (тот же слот): слой, перенесённый в группу,
     * получает её общую цепочку эффектов вместе с ключами - анимация совпадает с соседями. Прежние дорожки [to] в этих
     * слотах заменяются (или удаляются, если у источника их нет).
     */
    fun copyLayerTracks(anim: Animation, from: Int, to: Int, effects: List<Effect>): Animation {
        if (from !in 0..MAX_LAYER || to !in 0..MAX_LAYER || from == to) return anim
        val tracks = anim.tracks.toMutableMap()
        for (e in effects) {
            if (e.slot !in 0 until SLOTS) continue
            for (p in 0 until PARAMS) {
                val src = anim.tracks[id(from, e.slot, p)]
                val dst = id(to, e.slot, p)
                if (src == null) tracks.remove(dst) else tracks[dst] = AnimationTrack(dst, src.keys)
            }
        }
        return anim.copy(tracks = tracks)
    }
}
