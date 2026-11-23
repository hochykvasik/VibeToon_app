package com.vibetoon

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBool
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 18: булевы операции векторных фигур (объединить, вычесть, пересечь, исключить, разобрать). */
class Package18VectorBooleanTest {
    private val rect = VectorShape(VecKind.RECT, listOf(10f, 10f, 60f, 60f), 0xFF000000L, 0xFFFF0000L, 4f)
    private val oval = VectorShape(VecKind.ELLIPSE, listOf(40f, 40f, 90f, 90f), 0xFF0000FFL, 0L, 2f)
    private val line = VectorShape(VecKind.LINE, listOf(0f, 0f, 5f, 5f))

    @Test fun combineMergesSelectedWithShapeBelow() {
        val r = VectorBool.combine(listOf(line, rect, oval), 2, VectorBool.SUBTRACT)
        assertNull(r.error)
        assertEquals(2, r.shapes.size)
        assertEquals(1, r.index)
        val c = r.shapes[1]
        assertEquals(VecKind.BOOL, c.kind)
        assertEquals(VectorBool.SUBTRACT, c.op)
        assertEquals(listOf(rect, oval), c.parts)
        assertEquals(rect.pts + oval.pts, c.pts)
        assertEquals(rect.stroke, c.stroke); assertEquals(rect.fill, c.fill); assertEquals(rect.width, c.width, 0f)
        assertEquals(line, r.shapes[0])
    }

    @Test fun fillFallsBackToUpperShape() {
        val r = VectorBool.combine(listOf(oval, rect), 1, VectorBool.UNION)
        assertEquals(rect.fill, r.shapes.single().fill)
        assertEquals(oval.stroke, r.shapes.single().stroke)
    }

    @Test fun errorsLeaveListUntouched() {
        val list = listOf(line, rect, oval)
        assertEquals(VectorBool.Error.NO_SHAPE_BELOW, VectorBool.combine(list, 0, VectorBool.UNION).error)
        assertEquals(VectorBool.Error.NO_SHAPE, VectorBool.combine(list, 5, VectorBool.UNION).error)
        assertEquals(VectorBool.Error.NOT_AREA, VectorBool.combine(list, 1, VectorBool.UNION).error)
        assertEquals(VectorBool.Error.BAD_OP, VectorBool.combine(list, 2, "melt").error)
        val poly = VectorShape(VecKind.POLY, listOf(0f, 0f, 5f, 5f, 9f, 0f))
        assertEquals(VectorBool.Error.NOT_AREA, VectorBool.combine(listOf(rect, poly), 1, VectorBool.UNION).error)
        assertEquals(list, VectorBool.combine(list, 1, VectorBool.UNION).shapes)
        assertEquals(VectorBool.Error.NOT_COMPOUND, VectorBool.release(list, 1).error)
    }

    @Test fun releaseReturnsPartsWithEditedNodes() {
        val c = VectorBool.combine(listOf(rect, oval), 1, VectorBool.INTERSECT).shapes
        // узел 2 (первый узел эллипса) сдвинут обычной правкой узлов
        val moved = VectorMath.moveNode(c, 0, 2, 30f, 35f)!!
        assertEquals(VectorBool.INTERSECT, moved[0].op)
        val back = VectorBool.release(moved, 0)
        assertNull(back.error)
        assertEquals(listOf(rect, oval.copy(pts = listOf(30f, 35f, 90f, 90f))), back.shapes)
        assertEquals(1, back.index)
    }

    @Test fun nestedCompoundAndExclude() {
        val tri = VectorShape(VecKind.POLYGON, listOf(0f, 0f, 20f, 0f, 10f, 20f), 0xFF000000L, 0xFF00FF00L, 1f)
        val first = VectorBool.combine(listOf(rect, oval), 1, VectorBool.UNION).shapes
        assertTrue(VectorBool.isArea(first[0]))
        val second = VectorBool.combine(first + tri, 1, VectorBool.EXCLUDE)
        assertNull(second.error)
        val c = second.shapes.single()
        assertEquals(VectorBool.EXCLUDE, c.op)
        assertEquals(VecKind.BOOL, c.parts[0].kind)
        assertEquals(listOf(first[0], tri), VectorBool.parts(c))
    }

    @Test fun compoundSurvivesJsonAndBadEntriesAreSkipped() {
        val c = VectorBool.combine(listOf(rect, oval), 1, VectorBool.SUBTRACT).shapes
        val item = MediaItem(100001, MediaKind.IMAGE, "vector", "assets/a.png", 100, 100, 0f, 0f, 100f, 100f, vector = true, vectors = c + line)
        val saved = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "n", 100, 100), media = listOf(item)))).getOrThrow()
        assertEquals(c + line, saved.media.single().vectors)
        // BOOL без частей (повреждённый файл) пропускается, остальное читается
        assertNull(ProjectJson.vectorOf(mapOf("k" to "BOOL", "p" to listOf(1.0, 2.0, 3.0, 4.0), "op" to "union")))
        assertNull(ProjectJson.vectorOf(mapOf("k" to "BOOL", "p" to listOf(1.0, 2.0, 3.0, 4.0), "op" to "zap",
            "parts" to listOf(ProjectJson.vectorJson(rect), ProjectJson.vectorJson(oval)))))
        // старые фигуры без новых полей читаются как раньше
        assertEquals(rect, ProjectJson.vectorOf(mapOf("k" to "RECT", "p" to listOf(10.0, 10.0, 60.0, 60.0), "s" to "#FF000000", "f" to "#FFFF0000", "w" to 4.0)))
        assertFalse(ProjectJson.vectorJson(rect).containsKey("op"))
        assertFalse(ProjectJson.vectorJson(rect).containsKey("parts"))
    }
}
