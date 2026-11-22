package com.vibetoon.domain.anim

/**
 * Дорожки анимации одного слоя: сам слой (трансформация), точки Warp, вершины Mesh, углы Perspective, параметры
 * Turbulent и пины Puppet. Ключ «слой + кадр» на таймлайне означает все ключи этих дорожек на кадре: так копируется,
 * двигается и удаляется вся анимация слоя (в том числе деформация сеткой) пачкой.
 */
object TrackGroups {
    /** Id всех дорожек слоя, у которых есть ключи. */
    fun ids(layerId: Int, tracks: Map<Int, AnimationTrack>): List<Int> {
        val out = ArrayList<Int>()
        fun add(id: Int) { if (tracks[id]?.isEmpty() == false) out.add(id) }
        add(layerId)
        for (k in 0 until WarpMath.POINTS) add(WarpMath.pointId(layerId, k))
        val lo = MeshMath.vertexId(layerId, 0)
        for (k in 0 until MeshMath.MAX_VERTS) add(lo + k)
        for (k in 0 until PerspectiveMath.CORNERS) add(PerspectiveMath.pointId(layerId, k))
        for (k in 0 until TurbMath.PARAMS) add(TurbMath.paramId(layerId, k))
        for (k in 0 until PuppetMath.MAX_PINS) add(PuppetMath.pinId(layerId, k))
        if (layerId in 0..EffectTracks.MAX_LAYER) for (slot in 0 until EffectTracks.SLOTS)
            for (param in 0 until EffectTracks.PARAMS) add(EffectTracks.id(layerId, slot, param))
        return out
    }

    /** Кадры, на которых у слоя есть хотя бы один ключ на любой из его дорожек. */
    fun frames(layerId: Int, tracks: Map<Int, AnimationTrack>): Set<Int> {
        val fs = HashSet<Int>()
        for (id in ids(layerId, tracks)) tracks[id]?.keys?.mapTo(fs) { it.frame }
        return fs
    }

    /** Тот же «слот» дорожки на другом слое [to] (трансформация, точка Warp, вершина, угол, параметр, пин) или null. */
    fun remap(id: Int, from: Int, to: Int): Int? {
        if (id == from) return to
        if (EffectTracks.belongs(id, from) && to in 0..EffectTracks.MAX_LAYER) {
            val index = ((-1L - id) % (EffectTracks.SLOTS * EffectTracks.PARAMS)).toInt()
            return EffectTracks.id(to, index / EffectTracks.PARAMS, index % EffectTracks.PARAMS)
        }
        val w0 = WarpMath.pointId(from, 0)
        if (id in w0 until w0 + WarpMath.POINTS) return WarpMath.pointId(to, id - w0)
        val m0 = MeshMath.vertexId(from, 0)
        if (id in m0 until m0 + MeshMath.MAX_VERTS) return MeshMath.vertexId(to, id - m0)
        val p0 = PerspectiveMath.pointId(from, 0)
        if (id in p0 until p0 + PerspectiveMath.CORNERS) return PerspectiveMath.pointId(to, id - p0)
        val t0 = TurbMath.paramId(from, 0)
        if (id in t0 until t0 + TurbMath.PARAMS) return TurbMath.paramId(to, id - t0)
        if (PuppetMath.isPinId(id) && PuppetMath.layerOf(id) == from) return PuppetMath.pinId(to, PuppetMath.indexOf(id))
        return null
    }
}

/** Скопированный ключ дорожки: сдвиг по кадрам относительно самого раннего скопированного ключа. */
data class KeyClipEntry(val trackId: Int, val rel: Int, val key: Keyframe, val layer: Int)
