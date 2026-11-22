package com.vibetoon.domain.render

import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.WarpGridMath
import com.vibetoon.domain.model.Affine2D
import kotlin.math.abs

/** Подготовка общей деформации в мировых координатах для одного листа группы.
 * Пока только вызываемый API: привязка области к проекту и подключение UI/экспорта не выполнены. */
object GroupDeformationMesh {
    data class Bounds(val x: Float, val y: Float, val w: Float, val h: Float)
    data class Result(val mesh: Mesh, val offsets: FloatArray)

    /** [warp] — смещения 4×4, [perspective] — гомография 3×3 (column-major). */
    fun build(bounds: Bounds, world: Affine2D, warp: FloatArray?, perspective: FloatArray?, cells: Int = 24): Result? {
        if (!bounds.x.isFinite() || !bounds.y.isFinite() || !bounds.w.isFinite() || !bounds.h.isFinite() ||
            bounds.w <= 0f || bounds.h <= 0f || cells !in 1..30 ||
            listOf(world.a, world.b, world.c, world.d, world.tx, world.ty).any { !it.isFinite() } ||
            (warp != null && (warp.size != 32 || warp.any { !it.isFinite() })) ||
            (perspective != null && (perspective.size != 9 || perspective.any { !it.isFinite() }))) return null
        val inverse = world.inverse() ?: return null
        // The perspective denominator after cubic Warp is itself a bicubic Bezier
        // surface. Its values are bounded by its 16 control coefficients. Requiring
        // all coefficients to stay on one side of zero proves there is no pole
        // anywhere in the fixed domain, not merely at sampled mesh vertices.
        if (perspective != null) {
            var sign = 0
            for (y in 0..3) for (x in 0..3) {
                val i = (y * 4 + x) * 2
                val u = x.toDouble() / 3.0 + (warp?.get(i) ?: 0f).toDouble()
                val v = y.toDouble() / 3.0 + (warp?.get(i + 1) ?: 0f).toDouble()
                val z = perspective[2].toDouble() * u + perspective[5].toDouble() * v + perspective[8].toDouble()
                if (!z.isFinite() || abs(z) < 1e-6) return null
                val current = if (z > 0.0) 1 else -1
                if (sign != 0 && sign != current) return null
                sign = current
            }
        }
        val side = cells + 1
        val verts = FloatArray(side * side * 2)
        val offsets = FloatArray(verts.size)
        val denominators = if (perspective != null) FloatArray(side * side) else null
        for (y in 0..cells) for (x in 0..cells) {
            val i = y * side + x
            val u = x.toFloat() / cells; val v = y.toFloat() / cells
            verts[i * 2] = u; verts[i * 2 + 1] = v
            val wx = world.applyX(u, v); val wy = world.applyY(u, v)
            val gu = (wx - bounds.x) / bounds.w; val gv = (wy - bounds.y) / bounds.h
            // Область должна включать всю исходную геометрию, в том числе монтажный стол.
            if (!gu.isFinite() || !gv.isFinite() || gu < -1e-5f || gu > 1.00001f || gv < -1e-5f || gv > 1.00001f) return null
            val cu = gu.coerceIn(0f, 1f); val cv = gv.coerceIn(0f, 1f)
            val displacement = warp?.let { WarpGridMath.evaluate(it, 4, cu, cv) ?: return null } ?: (0f to 0f)
            var a = gu + displacement.first; var b = gv + displacement.second
            if (perspective != null) {
                val z = perspective[2] * a + perspective[5] * b + perspective[8]
                if (!z.isFinite() || abs(z) < 1e-6f) return null
                denominators!![i] = z
                val px = (perspective[0] * a + perspective[3] * b + perspective[6]) / z
                val py = (perspective[1] * a + perspective[4] * b + perspective[7]) / z
                a = px; b = py
            }
            val targetX = bounds.x + a * bounds.w; val targetY = bounds.y + b * bounds.h
            val du = inverse.applyX(targetX, targetY) - u
            val dv = inverse.applyY(targetX, targetY) - v
            if (!du.isFinite() || !dv.isFinite()) return null
            offsets[i * 2] = du; offsets[i * 2 + 1] = dv
        }
        // Check again in floating-point arithmetic at the sampled mesh vertices.
        if (denominators != null) {
            val positive = denominators[0] > 0f
            if (denominators.any { (it > 0f) != positive }) return null
        }
        val tris = IntArray(cells * cells * 6)
        var p = 0
        for (y in 0 until cells) for (x in 0 until cells) {
            val a = y * side + x; val b = a + 1; val c = a + side; val d = c + 1
            tris[p++] = a; tris[p++] = b; tris[p++] = c
            tris[p++] = b; tris[p++] = d; tris[p++] = c
        }
        return Result(Mesh(verts, tris), offsets)
    }
}
