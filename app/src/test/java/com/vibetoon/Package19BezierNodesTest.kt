package com.vibetoon

import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBezier
import com.vibetoon.domain.vector.VectorBool
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 19: узлы Безье (ручки кривых) у контуров пера. */
class Package19BezierNodesTest {
    // ломаная 0,0 -> 30,0 -> 30,30
    private val poly = VectorShape(VecKind.POLY, listOf(0f, 0f, 30f, 0f, 30f, 30f), 0xFF000000L, 0L, 3f)
    private val square = VectorShape(VecKind.POLYGON, listOf(0f, 0f, 30f, 0f, 30f, 30f, 0f, 30f), 0xFF000000L, 0xFFFF0000L, 3f)

    @Test fun smoothNodeFollowsNeighborDirection() {
        val s = VectorBezier.smoothNode(poly, 1)!!
        assertEquals(12, s.handles.size)
        // направление от 0,0 к 30,30 - диагональ; длины - треть расстояния до соседей (10)
        val d = 10f / kotlin.math.sqrt(2f)
        assertEquals(-d, s.handles[4], 1e-4f); assertEquals(-d, s.handles[5], 1e-4f)
        assertEquals(d, s.handles[6], 1e-4f); assertEquals(d, s.handles[7], 1e-4f)
        assertTrue(VectorBezier.nodeHasHandles(s, 1))
        assertFalse(VectorBezier.nodeHasHandles(s, 0))
        assertEquals(poly.pts, s.pts)
    }

    @Test fun cornerRemovesHandlesAndEmptyMeansStraight() {
        val s = VectorBezier.smoothNode(poly, 1)!!
        val c = VectorBezier.cornerNode(s, 1)!!
        assertTrue(c.handles.isEmpty())
        assertEquals(poly, c)
        assertFalse(VectorBezier.hasHandles(c))
    }

    @Test fun endpointsAndClosedContoursUseNeighbors() {
        val end = VectorBezier.smoothNode(poly, 0)!!
        // у начала ломаной есть только сосед справа: ручка выхода по направлению к нему, входа - напротив
        assertTrue(end.handles[2] > 0f); assertEquals(0f, end.handles[3], 1e-4f)
        // у замкнутого первый узел видит последний
        val sq = VectorBezier.smoothNode(square, 0)!!
        assertTrue(sq.handles[2] > 0f && sq.handles[3] < 0f)
        assertNull(VectorBezier.smoothNode(VectorShape(VecKind.RECT, listOf(0f, 0f, 9f, 9f)), 0))
        assertNull(VectorBezier.smoothNode(VectorShape(VecKind.POLY, listOf(5f, 5f, 5f, 5f)), 0))
        val all = VectorBezier.smoothAll(square)!!
        assertTrue((0 until 4).all { VectorBezier.nodeHasHandles(all, it) })
    }

    @Test fun handlesMoveWithNodeAndMirror() {
        val s = VectorBezier.smoothNode(poly, 1)!!
        val moved = VectorMath.moveNode(listOf(s), 0, 1, 40f, 10f)!![0]
        assertEquals(s.handles, moved.handles)
        val (hx, hy) = VectorBezier.handleAt(moved, 1, VectorBezier.OUT)!!
        assertEquals(40f + s.handles[6], hx, 1e-4f); assertEquals(10f + s.handles[7], hy, 1e-4f)
        // ручка выхода вправо на 20 - вход поворачивается влево, длина входа сохраняется (10)
        val dragged = VectorBezier.moveHandle(listOf(s), 0, 1, VectorBezier.OUT, 50f, 0f)!![0]
        assertEquals(20f, dragged.handles[6], 1e-4f); assertEquals(0f, dragged.handles[7], 1e-4f)
        assertEquals(-10f, dragged.handles[4], 1e-3f); assertEquals(0f, dragged.handles[5], 1e-3f)
        assertNull(VectorBezier.moveHandle(listOf(s), 0, 1, VectorBezier.OUT, Float.NaN, 0f))
        assertNull(VectorBezier.moveHandle(listOf(s), 0, 7, VectorBezier.OUT, 1f, 0f))
        // перетаскивание ручки у узла без ручек создаёт одну ручку
        val one = VectorBezier.moveHandle(listOf(poly), 0, 0, VectorBezier.OUT, 5f, 5f)!![0]
        assertEquals(listOf(0f, 0f, 5f, 5f), one.handles.subList(0, 4))
    }

    @Test fun cubicsAreStraightWithoutHandles() {
        val segs = VectorBezier.cubics(poly)
        assertEquals(2, segs.size)
        assertEquals(listOf(0f, 0f, 0f, 0f, 30f, 0f, 30f, 0f), segs[0].toList())
        assertEquals(4, VectorBezier.cubics(square).size)
        val s = VectorBezier.smoothNode(poly, 1)!!
        val c = VectorBezier.cubics(s)[0]
        assertEquals(30f + s.handles[4], c[4], 1e-4f); assertEquals(0f + s.handles[5], c[5], 1e-4f)
    }

    @Test fun deletingNodeDropsItsHandles() {
        val s = VectorBezier.smoothAll(square)!!
        val d = VectorBezier.withoutNode(s, 1)
        assertEquals(6, d.pts.size)
        assertEquals(12, d.handles.size)
        assertEquals(s.handles.subList(0, 4), d.handles.subList(0, 4))
        assertEquals(s.handles.subList(8, 16), d.handles.subList(4, 12))
        assertTrue(VectorBezier.withoutNode(poly, 1).handles.isEmpty())
    }

    @Test fun booleanPartsKeepHandles() {
        val curved = VectorBezier.smoothAll(square)!!
        val other = VectorShape(VecKind.ELLIPSE, listOf(20f, 20f, 50f, 50f))
        val c = VectorBool.combine(listOf(curved, other), 1, VectorBool.UNION).shapes
        val moved = VectorMath.moveNode(c, 0, 0, 5f, 5f)!!
        val parts = VectorBool.parts(moved[0])
        assertEquals(curved.handles, parts[0].handles)
        assertEquals(5f, parts[0].pts[0], 0f)
    }

    @Test fun handlesSurviveJsonAndBadDataIsDropped() {
        val s = VectorBezier.smoothAll(square)!!
        assertEquals(s, ProjectJson.vectorOf(ProjectJson.vectorJson(s)))
        assertFalse(ProjectJson.vectorJson(square).containsKey("h"))
        val broken = ProjectJson.vectorJson(square).toMutableMap().also { it["h"] = listOf(1.0, 2.0) }
        assertEquals(square, ProjectJson.vectorOf(broken))
    }
}
