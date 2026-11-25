package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/** Actual independent control grids, limited to degrees representable by the legacy cubic shader. */
object CubicWarpGrid {
    data class Size(val width: Int, val height: Int) {
        fun valid() = width in 2..4 && height in 2..4
    }
    val DEFAULT = Size(4, 4)
    data class Migration(val base: Map<Int, LayerTransform>, val animation: Animation, val size: Size)

    fun raw(id: Int, size: Size, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        require(size.valid())
        return FloatArray(size.width * size.height * 2).also { out ->
            for (i in 0 until size.width * size.height) {
                val key = WarpMath.pointId(id, i); val b = base[key]; val a = if (fx) anim.tracks[key]?.evaluate(frame) else null
                out[i * 2] = (b?.dx ?: 0f) + (a?.dx ?: 0f)
                out[i * 2 + 1] = (b?.dy ?: 0f) + (a?.dy ?: 0f)
            }
        }
    }
    /** Exact elevation to 4x4: same GPU shader and group mesh for every supported grid. */
    /** Спринт 43: тотально - нечисловые значения повреждённого проекта считаются нулём, неверный размер - 4x4. */
    fun cubic(id: Int, size: Size, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        val s = if (size.valid()) size else DEFAULT
        val r = raw(id, s, frame, base, anim, fx)
        for (i in r.indices) if (!r[i].isFinite()) r[i] = 0f
        return RectWarpGridMath.convert(r, s.width, s.height, 4, 4) ?: FloatArray(32)
    }

    fun migrate(id: Int, source: Size, target: Size, base: Map<Int, LayerTransform>, anim: Animation): Migration? {
        if (!source.valid() || !target.valid()) return null
        val ids = (0 until WarpMath.POINTS).map { WarpMath.pointId(id, it) }
        val count = source.width * source.height
        // Never discard data in an inactive slot, even from an unusual or damaged project.
        if (ids.drop(count).any { it in base || anim.hasTrack(it) }) return null
        val grid = FloatArray(count * 2)
        for (i in 0 until count) {
            val t = base[ids[i]] ?: continue
            if (!t.dx.isFinite() || !t.dy.isFinite() || t.scaleX != 1f || t.scaleY != 1f || t.rotation != 0f || t.opacity != 1f) return null
            grid[i * 2] = t.dx; grid[i * 2 + 1] = t.dy
        }
        val keys = ids.take(count).map { WarpGridMigration.PointKeys(anim.tracks[it]?.keys.orEmpty()) }
        val result = WarpGridMigration.convertRect(grid, keys, source.width, source.height, target.width, target.height) ?: return null
        val nextBase = base.toMutableMap(); ids.forEach { nextBase.remove(it) }
        val nextTracks = anim.tracks.toMutableMap(); ids.forEach { nextTracks.remove(it) }
        for (i in result.points.indices) {
            val b = LayerTransform(dx = result.base[i * 2], dy = result.base[i * 2 + 1])
            if (b.dx != 0f || b.dy != 0f) nextBase[ids[i]] = b
            val k = result.points[i].keys
            if (k.isNotEmpty()) nextTracks[ids[i]] = AnimationTrack(ids[i], k)
        }
        return Migration(nextBase, anim.copy(tracks = nextTracks), target)
    }
}
