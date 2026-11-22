package com.vibetoon

import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ластик и палец по векторным контурам (Only Erase/Smudge Selected Path Layers). */
class VectorPathToolsTest {
    private val line = VectorShape(VecKind.PATH, listOf(0f, 0f, 100f, 0f), width = 2f)

    @Test fun eraserCutsPathInTwo() {
        val out = VectorMath.erase(listOf(line), listOf(50f, -20f, 50f, 20f), 5f)
        assertEquals(2, out.size)
        assertTrue(out.all { it.kind == VecKind.PATH })
        assertTrue(out[0].pts.last { true } <= 1f)               // y ~ 0
        assertTrue(VectorMath.bounds(out[0].pts)[2] < 50f)      // левый кусок кончается до ластика
        assertTrue(VectorMath.bounds(out[1].pts)[0] > 50f)      // правый начинается после
    }

    @Test fun eraserMissLeavesShapes() {
        val list = listOf(line)
        assertEquals(list, VectorMath.erase(list, listOf(50f, 40f, 60f, 40f), 5f))
    }

    @Test fun closedShapesAreRemovedWhole() {
        val rect = VectorShape(VecKind.RECT, listOf(0f, 0f, 100f, 100f))
        val ell = VectorShape(VecKind.ELLIPSE, listOf(200f, 0f, 300f, 100f), fill = 0xFF00FF00L)
        assertEquals(listOf(ell), VectorMath.erase(listOf(rect, ell), listOf(100f, 50f), 4f))
        // залитый эллипс стирается касанием внутри
        assertEquals(emptyList<VectorShape>(), VectorMath.erase(listOf(ell), listOf(250f, 50f), 2f))
        // незалитый - только по контуру
        assertEquals(1, VectorMath.erase(listOf(ell.copy(fill = 0L)), listOf(250f, 50f), 2f).size)
    }

    @Test fun lineBecomesPathPieces() {
        val l = VectorShape(VecKind.LINE, listOf(0f, 0f, 0f, 100f))
        val out = VectorMath.erase(listOf(l), listOf(0f, 0f), 10f)
        assertEquals(1, out.size); assertEquals(VecKind.PATH, out[0].kind)
        assertTrue(VectorMath.bounds(out[0].pts)[1] > 9f)
    }

    @Test fun smudgeBendsNearbyPointsOnly() {
        val out = VectorMath.smudge(listOf(line), 50f, 0f, 0f, 20f, 15f, 1f)
        val pts = out[0].pts
        val b = VectorMath.bounds(pts)
        assertEquals(20f, b[3], 1.2f)                // середина ушла вниз почти на 20 (точки контура дискретны)
        assertEquals(0f, pts[1], 1e-4f)              // концы на месте
        assertEquals(0f, pts[pts.size - 1], 1e-4f)
        // далеко - без изменений
        val far = VectorMath.smudge(listOf(line), 50f, 80f, 0f, 20f, 15f, 1f)
        assertEquals(line, far[0])
        // прямоугольник не гнётся
        val rect = VectorShape(VecKind.RECT, listOf(0f, 0f, 100f, 100f))
        assertEquals(rect, VectorMath.smudge(listOf(rect), 0f, 0f, 5f, 5f, 30f, 1f)[0])
    }

    @Test fun densifyAndDistance() {
        val d = VectorMath.densify(listOf(0f, 0f, 10f, 0f), 2f)
        assertEquals(12, d.size)
        assertEquals(5f, VectorMath.distToPolyline(5f, 5f, listOf(0f, 0f, 10f, 0f)), 1e-4f)
        assertFalse(VectorMath.touches(line, listOf(50f, 30f), 5f))
        assertTrue(VectorMath.touches(line, listOf(50f, 5f), 5f))
    }
}
