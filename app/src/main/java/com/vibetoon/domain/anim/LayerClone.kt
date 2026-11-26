package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Спринт 51: перенос состояния деформеров слоя на его копию («Вырезать/Копировать в новый слой», «Дублировать»).
 * Точки Warp, вершины Mesh, углы Perspective, параметры Turbulent и пины Puppet хранятся в base и дорожках под
 * «виртуальными» id слоя ([TrackGroups.remap] переводит их на другой слой). Их смещения - в долях размера слоя,
 * поэтому копия той же рамки (отделённая часть - холст того же размера) деформируется пиксель в пиксель как источник.
 * Чистые функции - JVM-тесты.
 */
object LayerClone {
    /** id деформера слоя [layer] (не сам слой, не эффекты и не положение на пути). */
    fun isDeformerId(id: Int, layer: Int): Boolean =
        id >= WarpMath.ID_BASE && id < PathLayerMath.ID_BASE && id != layer && TrackGroups.ownerOf(id) == layer

    /** Форма привязки деформеров [from], переведённая на слой [to]. */
    fun base(from: Int, to: Int, base: Map<Int, LayerTransform>): Map<Int, LayerTransform> {
        val out = HashMap<Int, LayerTransform>()
        for ((id, t) in base) if (isDeformerId(id, from)) TrackGroups.remap(id, from, to)?.let { out[it] = t }
        return out
    }

    /** Дорожки (ключи) деформеров [from], переведённые на [to]. */
    fun tracks(from: Int, to: Int, tracks: Map<Int, AnimationTrack>): Map<Int, AnimationTrack> {
        val out = HashMap<Int, AnimationTrack>()
        for (id in TrackGroups.ids(from, tracks)) {
            if (!isDeformerId(id, from)) continue
            val target = TrackGroups.remap(id, from, to) ?: continue
            val tr = tracks[id] ?: continue
            out[target] = AnimationTrack(target, tr.keys)
        }
        return out
    }

    /**
     * «Без анимации»: форма деформеров копии = форма привязки + анимация на кадре [frame] (Warp, Mesh, Perspective и
     * Puppet складывают смещения base и ключа), ключи не переносятся - копия выглядит как источник на этом кадре.
     * Параметры Turbulent ([TurbMath]) берутся из настроек слоя, их ключи пропадают.
     */
    fun baked(from: Int, to: Int, base: Map<Int, LayerTransform>, tracks: Map<Int, AnimationTrack>, frame: Float): Map<Int, LayerTransform> {
        val out = HashMap(LayerClone.base(from, to, base))
        for (id in TrackGroups.ids(from, tracks)) {
            if (!isDeformerId(id, from) || id >= TurbMath.ID_BASE && id < PuppetMath.ID_BASE) continue
            val target = TrackGroups.remap(id, from, to) ?: continue
            val v = tracks[id]?.evaluate(frame) ?: continue
            val b = out[target] ?: LayerTransform()
            out[target] = b.copy(dx = b.dx + v.dx, dy = b.dy + v.dy)
        }
        return out
    }

    /** Есть ли у слоя ключи хоть на одной дорожке (трансформация, деформеры, эффекты, путь). */
    fun animated(layer: Int, tracks: Map<Int, AnimationTrack>): Boolean = TrackGroups.ids(layer, tracks).isNotEmpty()

    /**
     * Деформирует ли слой картинку: ненулевая форма привязки деформера или ключи деформера. Включённый, но не тронутый
     * Warp/Mesh/Perspective/пины рисуют слой как есть - это не деформация (раньше и он запрещал перенос выделения).
     */
    fun deforms(layer: Int, base: Map<Int, LayerTransform>, tracks: Map<Int, AnimationTrack>): Boolean {
        for ((id, t) in base) if (isDeformerId(id, layer) && (t.dx != 0f || t.dy != 0f)) return true
        return TrackGroups.ids(layer, tracks).any { isDeformerId(it, layer) }
    }
}
