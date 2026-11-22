package com.vibetoon.domain.render

import com.vibetoon.domain.anim.DenseWarpGrid
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.WarpGridMath

/** CPU tessellation; mesh UVs remain the original layer coordinates. */
object DenseWarpMesh {
    fun build(grid: DenseWarpGrid, cubic: FloatArray, cells: Int = 32): GroupDeformationMesh.Result? {
        if (!grid.valid() || cubic.size != 32 || cubic.any { !it.isFinite() } || cells !in 1..32) return null
        val side = cells + 1
        val verts = FloatArray(side * side * 2); val offsets = FloatArray(verts.size)
        for (y in 0..cells) for (x in 0..cells) {
            val u = x.toFloat() / cells; val v = y.toFloat() / cells; val i = (y * side + x) * 2
            verts[i] = u; verts[i + 1] = v
            val base = WarpGridMath.evaluate(cubic, 4, u, v) ?: return null
            val detail = grid.at(u, v)
            offsets[i] = base.first + detail.first; offsets[i + 1] = base.second + detail.second
            if (!offsets[i].isFinite() || !offsets[i + 1].isFinite()) return null
        }
        val tris = IntArray(cells * cells * 6); var k = 0
        for (y in 0 until cells) for (x in 0 until cells) {
            val a = y * side + x; val b = a + 1; val c = a + side; val d = c + 1
            tris[k++] = a; tris[k++] = b; tris[k++] = c; tris[k++] = b; tris[k++] = d; tris[k++] = c
        }
        return GroupDeformationMesh.Result(Mesh(verts, tris), offsets)
    }
}
