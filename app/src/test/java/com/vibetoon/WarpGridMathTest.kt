package com.vibetoon

import com.vibetoon.domain.anim.WarpGridMath
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class WarpGridMathTest {
    @Test fun cubicToHigherDegreePreservesDeformationAcrossEntireSurface() {
        val original = FloatArray(32) { i -> kotlin.math.sin(i * .73).toFloat() * .8f }
        val initial = original.copyOf()
        val elevated = WarpGridMath.elevate(original, 4)!!
        val again = WarpGridMath.elevate(elevated, 5)!!
        for (x in 0..20) for (y in 0..20) {
            val u = x / 20f; val v = y / 20f
            val a = WarpGridMath.evaluate(original, 4, u, v)!!
            val b = WarpGridMath.evaluate(elevated, 5, u, v)!!
            val c = WarpGridMath.evaluate(again, 6, u, v)!!
            assertEquals(a.first, b.first, .000001f); assertEquals(a.second, b.second, .000001f)
            assertEquals(a.first, c.first, .000001f); assertEquals(a.second, c.second, .000001f)
        }
        assertArrayEquals(initial, original, 0f)
    }
    @Test fun rejectsInvalidGridAndLeavesLegacyWarpProjectUnchanged() {
        assertNull(WarpGridMath.elevate(FloatArray(31), 4))
        assertNull(WarpGridMath.elevate(FloatArray(32) { Float.NaN }, 4))
        assertNull(WarpGridMath.elevate(FloatArray(162), 9))
        assertNull(WarpGridMath.evaluate(FloatArray(32), 4, -1f, 0f))
        val id = WarpMath.pointId(7, 5)
        val old = ProjectData(ProjectMeta("p", "test", 100, 100), base = mapOf(id to LayerTransform(dx = .3f)), warps = setOf(7))
        val loaded = ProjectJson.decode(ProjectJson.encode(old)).getOrThrow()
        assertEquals(old.base, loaded.base)
        assertEquals(old.warps, loaded.warps)
        assertEquals(id, WarpMath.pointId(7, 5))
    }
    @Test fun reductionOnlyAcceptsRepresentableSurfaces() {
        val original = FloatArray(32) { kotlin.math.sin(it * .41).toFloat() }
        val elevated = WarpGridMath.elevate(original, 4)!!
        val reduced = WarpGridMath.reduce(elevated, 5)!!
        assertArrayEquals(original, reduced, 0.00001f)
        val changed = elevated.copyOf().also { it[13] += .1f }
        assertNull(WarpGridMath.reduce(changed, 5))
        assertNull(WarpGridMath.reduce(elevated, 5, Float.NaN))
        assertNull(WarpGridMath.reduce(FloatArray(8), 2))
    }

}
