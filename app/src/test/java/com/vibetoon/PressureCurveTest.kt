package com.vibetoon

import com.vibetoon.domain.input.PressureCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stylus sprint: кривая чувствительности пера - сплайн по узлам, ограничение [0, 1], пресеты, строка настроек. */
class PressureCurveTest {
    private val pt = { x: Float, y: Float -> PressureCurve.Point(x, y) }

    @Test fun linearIsIdentityAndClamped() {
        val c = PressureCurve.LINEAR
        for (i in 0..20) assertEquals(i / 20f, c.map(i / 20f), 1e-4f)
        assertEquals(0f, c.map(-3f), 0f)
        assertEquals(1f, c.map(7f), 0f)
        assertEquals("NaN - безопасный полный нажим", 1f, c.map(Float.NaN), 0f)
    }

    @Test fun presetsGoFromSoftToFirmAndStayMonotone() {
        val at = 0.3f
        val values = PressureCurve.Preset.entries.map { it.curve.map(at) }
        for (i in 1 until values.size) assertTrue("пресеты упорядочены от мягкой к жёсткой: $values", values[i] < values[i - 1])
        for (pr in PressureCurve.Preset.entries) {
            var prev = -1f
            for (i in 0..100) {
                val y = pr.curve.map(i / 100f)
                assertTrue(y in 0f..1f)
                assertTrue("${pr.id} монотонна", y >= prev - 1e-5f)
                prev = y
            }
            assertEquals(0f, pr.curve.map(0f), 1e-5f); assertEquals(1f, pr.curve.map(1f), 1e-5f)
        }
    }

    @Test fun splinePassesNodesWithoutOvershoot() {
        val c = PressureCurve.of(listOf(pt(0f, 0.1f), pt(0.3f, 0.9f), pt(0.6f, 0.2f), pt(1f, 0.8f)))
        assertEquals(0.9f, c.map(0.3f), 1e-4f)
        assertEquals(0.2f, c.map(0.6f), 1e-4f)
        // между узлами значения не выходят за пределы соседних узлов (немонотонная кривая не «звенит»)
        for (i in 0..30) { val y = c.map(0.3f + 0.3f * i / 30f); assertTrue("$y", y in 0.2f - 1e-4f..0.9f + 1e-4f) }
        for (i in 0..100) assertTrue(c.map(i / 100f) in 0f..1f)
    }

    @Test fun normalizationKeepsEndsAndLimitsNodes() {
        val c = PressureCurve.of(listOf(pt(0.5f, 2f), pt(-1f, -1f), pt(0.51f, 0.3f), pt(0.2f, 0.4f), pt(0.3f, 0.5f), pt(0.4f, 0.5f), pt(0.7f, 0.7f), pt(0.8f, 0.8f), pt(0.9f, 0.9f)))
        assertEquals(0f, c.points.first().x, 0f); assertEquals(1f, c.points.last().x, 0f)
        assertTrue(c.points.size <= PressureCurve.MAX_POINTS)
        for (i in 1 until c.points.size) assertTrue("x возрастает с зазором", c.points[i].x - c.points[i - 1].x >= PressureCurve.MIN_GAP - 1e-6f)
        assertTrue(c.points.all { it.y in 0f..1f })
    }

    @Test fun editingMovesInsertsAndRemoves() {
        val soft = PressureCurve.Preset.SOFT.curve
        val moved = soft.move(1, 2f, 0.9f)
        assertTrue("узел не перескакивает конец", moved.points[1].x <= 1f - PressureCurve.MIN_GAP + 1e-6f)
        assertEquals(0.9f, moved.points[1].y, 1e-6f)
        assertEquals("конец двигается только по вертикали", 0f, soft.move(0, 0.5f, 0.2f).points[0].x, 0f)
        val (ins, at) = PressureCurve.LINEAR.insert(0.5f, 0.8f)
        assertEquals(1, at); assertEquals(3, ins.points.size); assertEquals(0.8f, ins.map(0.5f), 1e-4f)
        assertEquals(PressureCurve.LINEAR, ins.remove(1))
        assertEquals("концы не удаляются", ins, ins.remove(0))
        var full = PressureCurve.LINEAR
        for (x in listOf(0.2f, 0.4f, 0.6f, 0.8f, 0.9f)) full = full.insert(x, x).first
        assertEquals(PressureCurve.MAX_POINTS, full.points.size)
        assertEquals(-1, full.insert(0.5f, 0.5f).second)
        assertEquals(1, ins.nearest(0.52f, 0.79f, 0.1f)); assertEquals(-1, ins.nearest(0.3f, 0.1f, 0.05f))
    }

    @Test fun encodeDecodeRoundTripAndSafeFallback() {
        for (pr in PressureCurve.Preset.entries) {
            val back = PressureCurve.decode(pr.curve.encode())
            assertEquals(pr, back.preset())
        }
        assertEquals("0,0;1,1", PressureCurve.DEFAULT_ENCODED)
        assertEquals(PressureCurve.Preset.FIRM, PressureCurve.decode("firm").preset())
        for (bad in listOf(null, "", "garbage", "0,0", "a,b;c,d", "NaN,1;1,1;0,Infinity")) assertEquals(PressureCurve.LINEAR, PressureCurve.decode(bad))
        val custom = PressureCurve.decode("0,0.1;0.5,0.7;1,0.9")
        assertEquals(null, custom.preset())
        assertEquals(custom, PressureCurve.decode(custom.encode()))
    }
}
