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

/** Пакет 21: независимые (несимметричные) ручки узла. */
class Package21FreeHandlesTest {
    private val poly = VectorShape(VecKind.POLY, listOf(0f, 0f, 30f, 0f, 30f, 30f), 0xFF000000L, 0L, 3f)
    private val smooth = VectorBezier.smoothNode(poly, 1)!!

    @Test fun freeHandleMovesAlone() {
        val free = VectorBezier.setFree(smooth, 1, true)!!
        assertTrue(VectorBezier.isFree(free, 1))
        assertFalse(VectorBezier.isFree(free, 0))
        assertEquals(smooth.handles, free.handles)
        val moved = VectorBezier.moveHandle(listOf(free), 0, 1, VectorBezier.OUT, 30f, -20f)!![0]
        assertEquals(0f, moved.handles[6], 1e-4f); assertEquals(-20f, moved.handles[7], 1e-4f)
        assertEquals(free.handles.subList(4, 6), moved.handles.subList(4, 6))   // вход не повернулся
        // связанный узел по-прежнему зеркалит
        val linked = VectorBezier.moveHandle(listOf(smooth), 0, 1, VectorBezier.OUT, 30f, -20f)!![0]
        assertEquals(0f, linked.handles[4], 1e-3f); assertTrue(linked.handles[5] > 0f)
    }

    @Test fun linkingRealignsInHandleKeepingLength() {
        val free = VectorBezier.setFree(smooth, 1, true)!!
        val bent = VectorBezier.moveHandle(listOf(free), 0, 1, VectorBezier.OUT, 30f, -20f)!![0]
        val inLen = kotlin.math.hypot(bent.handles[4], bent.handles[5])
        val linked = VectorBezier.setFree(bent, 1, false)!!
        assertFalse(VectorBezier.isFree(linked, 1))
        assertTrue(linked.freeHandles.isEmpty())   // флаги без единого true не хранятся
        assertEquals(0f, linked.handles[4], 1e-3f); assertEquals(inLen, linked.handles[5], 1e-3f)
        assertEquals(bent.handles.subList(6, 8), linked.handles.subList(6, 8))
    }

    @Test fun noHandlesOrWrongShapeGivesNull() {
        assertNull(VectorBezier.setFree(poly, 1, true))
        assertNull(VectorBezier.setFree(smooth, 0, true))   // у крайнего узла ручек нет
        assertNull(VectorBezier.setFree(VectorShape(VecKind.RECT, listOf(0f, 0f, 9f, 9f)), 0, true))
        assertNull(VectorBezier.setFree(smooth, 9, true))
    }

    @Test fun smoothCornerAndDeleteKeepFlagsConsistent() {
        val all = VectorBezier.smoothAll(VectorShape(VecKind.POLYGON, listOf(0f, 0f, 30f, 0f, 30f, 30f, 0f, 30f)))!!
        val f = VectorBezier.setFree(VectorBezier.setFree(all, 1, true)!!, 3, true)!!
        assertEquals(listOf(false, true, false, true), f.freeHandles)
        assertFalse(VectorBezier.isFree(VectorBezier.smoothNode(f, 1)!!, 1))   // «Сгладить узел» связывает
        assertFalse(VectorBezier.isFree(VectorBezier.cornerNode(f, 3)!!, 3))
        val d = VectorBezier.withoutNode(f, 1)
        assertEquals(listOf(false, false, true), d.freeHandles)
        assertTrue(VectorBezier.isFree(d, 2))
        // все ручки убраны - флаги тоже
        var c = f
        for (k in 0 until 4) c = VectorBezier.cornerNode(c, k)!!
        assertTrue(c.handles.isEmpty() && c.freeHandles.isEmpty())
        // узел двигается - флаг остаётся
        assertEquals(f.freeHandles, VectorMath.moveNode(listOf(f), 0, 2, 5f, 5f)!![0].freeHandles)
        val parts = VectorBool.parts(VectorBool.combine(listOf(f, VectorShape(VecKind.ELLIPSE, listOf(0f, 0f, 9f, 9f))), 1, VectorBool.UNION).shapes[0])
        assertEquals(f.freeHandles, parts[0].freeHandles)
    }

    @Test fun flagsSurviveJsonAndBadFlagsDropped() {
        val free = VectorBezier.setFree(smooth, 1, true)!!
        assertEquals(free, ProjectJson.vectorOf(ProjectJson.vectorJson(free)))
        assertFalse(ProjectJson.vectorJson(smooth).containsKey("fh"))
        val wrong = ProjectJson.vectorJson(free).toMutableMap().also { it["fh"] = listOf(1) }
        assertEquals(smooth, ProjectJson.vectorOf(wrong))
        val noHandles = ProjectJson.vectorJson(poly).toMutableMap().also { it["fh"] = listOf(0, 1, 0) }
        assertEquals(poly, ProjectJson.vectorOf(noHandles))
    }
}
