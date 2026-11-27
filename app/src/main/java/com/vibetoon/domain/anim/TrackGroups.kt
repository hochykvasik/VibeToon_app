package com.vibetoon.domain.anim

/**
 * Дорожки анимации одного слоя: сам слой (трансформация), точки Warp, вершины Mesh, углы Perspective, параметры
 * Turbulent и пины Puppet. Ключ «слой + кадр» на таймлайне означает все ключи этих дорожек на кадре: так копируется,
 * двигается и удаляется вся анимация слоя (в том числе деформация сеткой) пачкой.
 */
object TrackGroups {
    /**
     * Спринт 43: владелец дорожки (слой) по её id или null. Нужен индексу: раньше [ids] перебирал ~1170 возможных id
     * на каждый слой (1024 вершины, 64 пина, 64 параметра эффектов...) на каждой отрисовке таймлайна и при скраббинге.
     */
    fun ownerOf(id: Int): Int? = when {
        id < 0 -> ((-1L - id) / (EffectTracks.SLOTS * EffectTracks.PARAMS)).toInt()
        id < WarpMath.ID_BASE -> id
        id < MeshMath.ID_BASE -> (id - WarpMath.ID_BASE) / WarpMath.POINTS
        id < PerspectiveMath.ID_BASE -> (id - MeshMath.ID_BASE) / MeshMath.MAX_VERTS
        id < TurbMath.ID_BASE -> (id - PerspectiveMath.ID_BASE) / PerspectiveMath.CORNERS
        id < PuppetMath.ID_BASE -> (id - TurbMath.ID_BASE) / TurbMath.PARAMS
        id < PathLayerMath.ID_BASE -> PuppetMath.layerOf(id)
        PathLayerMath.isProgressId(id) -> PathLayerMath.layerOf(id)
        else -> null
    }

    private fun owners(id: Int): List<Int> {
        if (id < 0) return listOf(((-1L - id) / (EffectTracks.SLOTS * EffectTracks.PARAMS)).toInt())
        val out = ArrayList<Int>(2)
        out += id
        if (id >= WarpMath.ID_BASE) out += (id - WarpMath.ID_BASE) / WarpMath.POINTS
        if (id >= MeshMath.ID_BASE) out += (id - MeshMath.ID_BASE) / MeshMath.MAX_VERTS
        if (id >= PerspectiveMath.ID_BASE) out += (id - PerspectiveMath.ID_BASE) / PerspectiveMath.CORNERS
        if (id >= TurbMath.ID_BASE) out += (id - TurbMath.ID_BASE) / TurbMath.PARAMS
        if (id >= PuppetMath.ID_BASE) out += PuppetMath.layerOf(id)
        if (id >= PathLayerMath.ID_BASE) out += PathLayerMath.layerOf(id)
        return out
    }

    private class Index(val tracks: Map<Int, AnimationTrack>, val size: Int, val byLayer: Map<Int, IntArray>)
    @Volatile private var index: Index? = null

    /** Индекс «слой -> его дорожки» для неизменяемой карты дорожек [Animation.tracks] (одна запись, по идентичности). */
    private fun indexOf(tracks: Map<Int, AnimationTrack>): Map<Int, IntArray> {
        index?.let { if (it.tracks === tracks && it.size == tracks.size) return it.byLayer }
        val lists = HashMap<Int, ArrayList<Int>>()
        for ((id, tr) in tracks) {
            if (tr.isEmpty()) continue
            // все возможные владельцы (диапазоны id очень больших слоёв могут перекрываться) - лишних отсеет belongs()
            for (owner in owners(id)) lists.getOrPut(owner) { ArrayList(4) }.add(id)
        }
        val by = HashMap<Int, IntArray>(lists.size * 2)
        for ((owner, l) in lists) {
            // порядок как у полного перебора: слой, Warp, вершины, углы, Turbulent, пины, путь, затем эффекты (slot, param)
            val pos = l.filter { it >= 0 }.sorted(); val neg = l.filter { it < 0 }.sortedDescending()
            by[owner] = (pos + neg).distinct().toIntArray()
        }
        index = Index(tracks, tracks.size, by)
        return by
    }

    /** Id всех дорожек слоя, у которых есть ключи. */
    fun ids(layerId: Int, tracks: Map<Int, AnimationTrack>): List<Int> {
        if (layerId >= 0) {
            val cand = indexOf(tracks)[layerId] ?: return emptyList()
            // точная проверка принадлежности (id совпадает с одним из id полного перебора этого слоя)
            return cand.filter { belongs(it, layerId) }
        }
        return idsFull(layerId, tracks)
    }

    /** Принадлежит ли дорожка [id] слою [layerId] (те же правила, что у полного перебора). */
    private fun belongs(id: Int, layerId: Int): Boolean = when {
        id == layerId -> true
        id < 0 -> EffectTracks.belongs(id, layerId)
        id in WarpMath.pointId(layerId, 0) until WarpMath.pointId(layerId, 0) + WarpMath.POINTS -> true
        id in MeshMath.vertexId(layerId, 0) until MeshMath.vertexId(layerId, 0) + MeshMath.MAX_VERTS -> true
        id in PerspectiveMath.pointId(layerId, 0) until PerspectiveMath.pointId(layerId, 0) + PerspectiveMath.CORNERS -> true
        id in TurbMath.paramId(layerId, 0) until TurbMath.paramId(layerId, 0) + TurbMath.PARAMS -> true
        id in PuppetMath.pinId(layerId, 0) until PuppetMath.pinId(layerId, 0) + PuppetMath.MAX_PINS -> true
        id == PathLayerMath.progressId(layerId) -> true
        else -> false
    }

    /** Полный перебор возможных id слоя (эталон для индекса; нужен и для отрицательных id). */
    fun idsFull(layerId: Int, tracks: Map<Int, AnimationTrack>): List<Int> {
        val out = ArrayList<Int>()
        fun add(id: Int) { if (tracks[id]?.isEmpty() == false) out.add(id) }
        add(layerId)
        for (k in 0 until WarpMath.POINTS) add(WarpMath.pointId(layerId, k))
        val lo = MeshMath.vertexId(layerId, 0)
        for (k in 0 until MeshMath.MAX_VERTS) add(lo + k)
        for (k in 0 until PerspectiveMath.CORNERS) add(PerspectiveMath.pointId(layerId, k))
        for (k in 0 until TurbMath.PARAMS) add(TurbMath.paramId(layerId, k))
        for (k in 0 until PuppetMath.MAX_PINS) add(PuppetMath.pinId(layerId, k))
        if (layerId >= 0) add(PathLayerMath.progressId(layerId))   // спринт 38: положение слоя на пути
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
        if (id == PathLayerMath.progressId(from) && to >= 0) return PathLayerMath.progressId(to)
        return null
    }
}

/** Скопированный ключ дорожки: сдвиг по кадрам относительно самого раннего скопированного ключа. */
data class KeyClipEntry(val trackId: Int, val rel: Int, val key: Keyframe, val layer: Int)
