package com.vibetoon

import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBezier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 20: вытягивание ручек при постановке точки пером, предпросмотр кривой. */
class Package20PenHandlesTest {
    private val zeros = List(12) { 0f }

    @Test fun pullSetsMirroredHandlesOfThatPointOnly() {
        val h = VectorBezier.penPull(zeros, 1, 12f, -4f, 5f)
        assertEquals(listOf(-12f, 4f, 12f, -4f), h.subList(4, 8))
        assertTrue(h.subList(0, 4).all { it == 0f } && h.subList(8, 12).all { it == 0f })
        // короткая протяжка (тап) - без ручек, прежние ручки точки сбрасываются
        assertEquals(zeros, VectorBezier.penPull(h, 1, 2f, 1f, 5f))
        // неверный индекс и NaN - без изменений
        assertEquals(h, VectorBezier.penPull(h, 3, 9f, 9f, 1f))
        assertEquals(h, VectorBezier.penPull(h, 1, Float.NaN, 9f, 1f))
    }

    @Test fun fromPenBuildsCurvedOrStraightContour() {
        val pts = listOf(0f, 0f, 30f, 0f, 30f, 30f)
        val curved = VectorBezier.fromPen(false, pts, VectorBezier.penPull(zeros, 1, 10f, 0f, 1f), 0xFF000000L, 0L, 3f)
        assertEquals(VecKind.POLY, curved.kind)
        assertTrue(VectorBezier.hasHandles(curved))
        val closed = VectorBezier.fromPen(true, pts, zeros, 0xFF000000L, 0xFFFF0000L, 3f)
        assertEquals(VecKind.POLYGON, closed.kind)
        assertTrue(closed.handles.isEmpty())   // все нули - контур из прямых, как раньше
        // две точки замкнуть нельзя - разомкнутый
        assertEquals(VecKind.POLY, VectorBezier.fromPen(true, listOf(0f, 0f, 5f, 5f), emptyList(), 0L, 0L, 1f).kind)
        // ручки не той длины отбрасываются
        assertTrue(VectorBezier.fromPen(false, pts, listOf(1f, 2f), 0L, 0L, 1f).handles.isEmpty())
        assertEquals(curved, ProjectJson.vectorOf(ProjectJson.vectorJson(curved)))
    }

    @Test fun flattenKeepsEndpointsAndBends() {
        val pts = listOf(0f, 0f, 30f, 0f)
        val straight = VectorBezier.fromPen(false, pts, emptyList(), 0L, 0L, 1f)
        assertEquals(pts, VectorBezier.flatten(straight, 16))   // прямой сегмент - без лишних точек
        val h = VectorBezier.penPull(List(8) { 0f }, 0, 0f, 20f, 1f)
        val curved = VectorBezier.fromPen(false, pts, h, 0L, 0L, 1f)
        val f = VectorBezier.flatten(curved, 8)
        assertEquals(18, f.size)
        assertEquals(0f, f[0], 0f); assertEquals(0f, f[1], 0f)
        assertEquals(30f, f[16], 1e-4f); assertEquals(0f, f[17], 1e-4f)
        assertTrue(f.filterIndexed { i, _ -> i % 2 == 1 }.any { it > 3f })   // кривая уходит вниз по ручке
        val loop = VectorBezier.fromPen(true, listOf(0f, 0f, 30f, 0f, 30f, 30f), VectorBezier.penPull(zeros, 2, 5f, 5f, 1f), 0L, 0L, 1f)
        val lf = VectorBezier.flatten(loop, 4)
        assertEquals(lf[0], lf[lf.size - 2], 1e-4f); assertEquals(lf[1], lf[lf.size - 1], 1e-4f)   // замкнутый возвращается к началу
        assertFalse(VectorBezier.flatten(loop, 4).isEmpty())
    }
}
