package com.vibetoon

import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorBezier
import com.vibetoon.domain.vector.VectorBrush
import com.vibetoon.domain.vector.VectorMath
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 40: векторные кисти - толщина штриха по узлам, сохранение в проект, правка узлов, контур для растеризации. */
class Package40VectorBrushTest {
    private val line = (0..20).flatMap { listOf(it * 10f, 50f) }
    private fun ramp(n: Int) = List(n) { it / (n - 1f) }

    @Test fun uniformKeepsOldBehaviour() {
        assertTrue(VectorBrush.widths(VectorBrush.Style.UNIFORM, line, ramp(21)).isEmpty())
        val s = VectorBrush.stroke(VectorBrush.Style.UNIFORM, line, ramp(21), 0xFF000000L, 0L, 8f)!!
        assertEquals(VecKind.PATH, s.kind)
        assertTrue(s.widths.isEmpty()); assertEquals("", s.brush)
        // тот же примитив, что и раньше через VectorMath.fromGesture
        assertEquals(VectorMath.fromGesture(VecKind.PATH, line, 0xFF000000L, 0L, 8f), s)
    }

    @Test fun pressureStyleFollowsPressure() {
        val w = VectorBrush.widths(VectorBrush.Style.PRESSURE, line, ramp(21))
        assertEquals(21, w.size)
        assertEquals(VectorBrush.MIN_FACTOR, w.first(), 1e-3f)
        assertEquals(1f, w.last(), 1e-3f)
        for (i in 1 until w.size) assertTrue(w[i] >= w[i - 1])
        // палец (нажим 1) - полная толщина; наклон (> 1) делает шире
        assertTrue(VectorBrush.widths(VectorBrush.Style.PRESSURE, line, List(21) { 1f }).all { it == 1f })
        assertEquals(2f, VectorBrush.widths(VectorBrush.Style.PRESSURE, line, List(21) { 2f })[5], 1e-3f)
    }

    @Test fun inkTapersBothEnds() {
        val w = VectorBrush.widths(VectorBrush.Style.INK, line, List(21) { 1f })
        assertEquals(VectorBrush.TAPER_TIP, w.first(), 1e-3f)
        assertEquals(VectorBrush.TAPER_TIP, w.last(), 1e-3f)
        assertEquals(1f, w[10], 1e-3f)
        assertTrue(w[1] < w[3])
    }

    @Test fun calligraphyDependsOnDirection() {
        // перо под 45°: линия вдоль пера тонкая, поперёк - толстая
        val along = listOf(0f, 0f, 10f, -10f, 20f, -20f)
        val across = listOf(0f, 0f, 10f, 10f, 20f, 20f)
        val a = VectorBrush.widths(VectorBrush.Style.CALLIGRAPHY, along, List(3) { 1f })
        val b = VectorBrush.widths(VectorBrush.Style.CALLIGRAPHY, across, List(3) { 1f })
        assertEquals(VectorBrush.NIB_THIN, a[0], 1e-3f)
        assertEquals(1f, b[0], 1e-3f)
    }

    @Test fun simplifyKeepsWidthsWithTheirNodes() {
        val pts = listOf(0f, 0f, 0.5f, 0f, 1f, 0f, 10f, 0f, 20f, 0f)
        val w = listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val (sp, sw) = VectorBrush.simplify(pts, w, 2f)
        assertEquals(sp.size / 2, sw.size)
        assertEquals(listOf(0f, 0f, 10f, 0f, 20f, 0f), sp)
        assertEquals(listOf(0.1f, 0.4f, 0.5f), sw)
    }

    @Test fun tinyGestureMakesNothing() {
        assertNull(VectorBrush.stroke(VectorBrush.Style.INK, listOf(5f, 5f), listOf(1f), 0xFF000000L, 0L, 8f))
        assertNull(VectorBrush.stroke(VectorBrush.Style.INK, listOf(5f, 5f, 5.5f, 5f), listOf(1f, 1f), 0xFF000000L, 0L, 8f))
    }

    @Test fun projectJsonRoundTripsBrushStroke() {
        val s = VectorBrush.stroke(VectorBrush.Style.PRESSURE, line, ramp(21), 0xFF112233L, 0L, 12f)!!
        val back = ProjectJson.vectorOf(ProjectJson.vectorJson(s))!!
        assertEquals(s.pts, back.pts)
        assertEquals("pressure", back.brush)
        assertEquals(s.widths.size, back.widths.size)
        for (i in s.widths.indices) assertEquals(s.widths[i], back.widths[i], 1e-3f)
        // старые проекты без "vw"/"vb" читаются как ровная линия
        val old = ProjectJson.vectorOf(mapOf("k" to "PATH", "p" to listOf(0.0, 0.0, 10.0, 10.0), "w" to 4.0))!!
        assertTrue(old.widths.isEmpty()); assertEquals("", old.brush)
        // повреждённые толщины (не по одной на узел) и неизвестный стиль игнорируются, фигура остаётся
        val bad = ProjectJson.vectorOf(mapOf("k" to "PATH", "p" to listOf(0.0, 0.0, 10.0, 10.0), "vw" to listOf(1.0), "vb" to "zap"))
        assertNotNull(bad); assertTrue(bad!!.widths.isEmpty()); assertEquals("", bad.brush)
        // толщины у не-пути не читаются
        val rect = ProjectJson.vectorOf(mapOf("k" to "RECT", "p" to listOf(0.0, 0.0, 10.0, 10.0), "vw" to listOf(1.0, 1.0)))!!
        assertTrue(rect.widths.isEmpty())
    }

    @Test fun deletingNodeDropsItsWidthAndMovingKeepsThem() {
        val s = VectorShape(VecKind.PATH, listOf(0f, 0f, 10f, 0f, 20f, 0f, 30f, 0f), widths = listOf(0.2f, 0.4f, 0.6f, 0.8f), brush = "pressure")
        val d = VectorBezier.withoutNode(s, 1)
        assertEquals(listOf(0.2f, 0.6f, 0.8f), d.widths)
        assertTrue(VectorBrush.hasWidths(d))
        val moved = VectorMath.moveNode(listOf(s), 0, 2, 25f, 5f)!![0]
        assertEquals(s.widths, moved.widths)
        // Undo/Redo хранят список фигур целиком: копия равна оригиналу вместе с толщинами
        assertEquals(s, s.copy())
    }

    @Test fun outlineIsClosedRibbonWithCaps() {
        val s = VectorShape(VecKind.PATH, listOf(0f, 0f, 50f, 0f, 100f, 0f), stroke = 0xFF000000L, width = 10f, widths = listOf(1f, 1f, 1f))
        val o = VectorBrush.outline(s)
        assertTrue(o.size >= 12)
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        for (i in o.indices step 2) { minX = minOf(minX, o[i]); maxX = maxOf(maxX, o[i]); minY = minOf(minY, o[i + 1]); maxY = maxOf(maxY, o[i + 1]) }
        assertEquals(-5f, minY, 0.05f); assertEquals(5f, maxY, 0.05f)       // половина толщины по сторонам
        assertEquals(-5f, minX, 0.3f); assertEquals(105f, maxX, 0.3f)      // круглые концы
        // переменная толщина: у тонкого конца контур уже
        val v = VectorBrush.outline(s.copy(widths = listOf(0.2f, 1f, 1f)))
        val nearStart = (v.indices step 2).filter { v[it] < 2f && v[it] > -0.5f }.maxOf { kotlin.math.abs(v[it + 1]) }
        assertTrue(nearStart < 2f)
        // без толщин контура нет (рисуется как раньше)
        assertTrue(VectorBrush.outline(s.copy(widths = emptyList())).isEmpty())
    }
}
