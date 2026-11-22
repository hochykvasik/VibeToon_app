package com.vibetoon

import com.vibetoon.domain.paint.BrushPresets
import com.vibetoon.domain.paint.BrushShape
import com.vibetoon.domain.paint.DabRotation
import com.vibetoon.domain.paint.DabStroker
import com.vibetoon.domain.paint.GrainKind
import com.vibetoon.domain.paint.GrainMath
import com.vibetoon.domain.paint.GrainMode
import com.vibetoon.domain.paint.StrokeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GrainPresetTest {
    private fun px(t: ByteArray, x: Int, y: Int) = t[Math.floorMod(y, 256) * 256 + Math.floorMod(x, 256)].toInt() and 0xFF

    @Test fun texturesAreSeamlessSoRepeatWrapsWithoutAJump() {
        for (k in listOf(GrainKind.PENCIL, GrainKind.PAPER, GrainKind.SCREENTONE)) {
            val t = GrainMath.generate(k)
            assertEquals(256 * 256, t.size)
            // шов (255 -> 0) не больше, чем обычный шаг в середине текстуры
            var seam = 0.0; var inner = 0.0
            for (y in 0 until 256) { seam += abs(px(t, 255, y) - px(t, 256, y)); inner += abs(px(t, 127, y) - px(t, 128, y)) }
            assertTrue("$k: шов $seam, обычный шаг $inner", seam <= inner * 1.6 + 256)
            var seamY = 0.0; var innerY = 0.0
            for (x in 0 until 256) { seamY += abs(px(t, x, 255) - px(t, x, 256)); innerY += abs(px(t, x, 127) - px(t, x, 128)) }
            assertTrue("$k: шов по y $seamY, обычный шаг $innerY", seamY <= innerY * 1.6 + 256)
        }
    }

    @Test fun screentoneIsADotLatticeWithPeriod32AndAbout30PercentCoverage() {
        val t = GrainMath.generate(GrainKind.SCREENTONE)
        val c = GrainMath.coverage(t)
        assertTrue("покрытие $c", c in 0.2f..0.4f)
        for (y in 0 until 256 step 7) for (x in 0 until 256 step 5) {
            assertEquals(px(t, x, y), px(t, x + 32, y))
            assertEquals(px(t, x, y), px(t, x, y + 32))
            assertEquals(px(t, x, y), px(t, x + 16, y + 16))   // вектор решётки (16, 16)
        }
        assertEquals(255, GrainMath.max(t)); assertEquals(0, GrainMath.min(t))
    }

    @Test fun pencilAndPaperKeepPaintDensityInRange() {
        val pen = GrainMath.generate(GrainKind.PENCIL)
        assertTrue("карандаш бывает и плотным, и разреженным: ${GrainMath.min(pen)}..${GrainMath.max(pen)}", GrainMath.min(pen) < 90 && GrainMath.max(pen) > 220)
        val paper = GrainMath.generate(GrainKind.PAPER)
        assertTrue("бумага не обнуляет краску: ${GrainMath.min(paper)}", GrainMath.min(paper) >= 150)
        assertTrue(GrainMath.generate(GrainKind.PENCIL).contentEquals(pen))   // детерминирована
    }

    @Test fun presetLibraryHasThirteenBrushesAndThreeErasers() {
        assertEquals(13, BrushPresets.brushes.size)
        assertEquals(3, BrushPresets.erasers.size)
        val ids = BrushPresets.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (p in BrushPresets.all()) {
            assertTrue(p.id, p.params.size in 1f..1500f && p.params.hardness in 0f..1f && p.params.flow in 0f..1f && p.params.opacity in 0f..1f)
            assertEquals(p.id, p.params.presetId)
        }
        assertTrue(BrushPresets.erasers.all { it.params.mode == StrokeMode.ERASE })
        assertTrue(BrushPresets.brushes.none { it.params.mode == StrokeMode.ERASE })
        assertEquals(BrushShape.SQUARE, BrushPresets.byId("square")!!.params.shape)
        assertEquals(BrushShape.SQUARE, BrushPresets.byId("e_square")!!.params.shape)
        // карандаш: зерно бумаги неподвижно на холсте (CANVAS), штампы не накапливаются (CAP_OPACITY) - фактура видна
        val pencil = BrushPresets.byId("pencil")!!.params
        assertEquals(GrainMode.CANVAS, pencil.grainMode)
        assertEquals(GrainKind.PENCIL, pencil.grain)
        assertEquals(StrokeMode.CAP_OPACITY, pencil.mode)
        for (id in listOf("screentone", "screentone_coarse")) {
            val p = BrushPresets.byId(id)!!.params
            assertEquals(GrainMode.CANVAS, p.grainMode)
            assertTrue(p.grain != GrainKind.NONE)
        }
        // штриховки: лента вдоль траектории
        for (id in listOf("hline", "vline")) {
            val p = BrushPresets.byId(id)!!.params
            assertEquals(GrainMode.MOVING, p.grainMode)
            assertEquals(DabRotation.TRAJECTORY, p.rotation)
        }
    }

    @Test fun previewIsAnSCurveWithPressureRamp() {
        val d = BrushPresets.previewDabs(BrushPresets.byId("pen")!!.params, 256, 64)
        assertTrue("штампов ${d.size / DabStroker.STRIDE}", d.size / DabStroker.STRIDE > 20)
        var minY = 1e9f; var maxY = -1e9f
        for (i in 0 until d.size / DabStroker.STRIDE) { minY = minOf(minY, d[i * DabStroker.STRIDE + 1]); maxY = maxOf(maxY, d[i * DabStroker.STRIDE + 1]) }
        assertTrue("кривая гнётся вверх и вниз: $minY..$maxY", maxY - minY > 20f)
        for (i in 0 until d.size / DabStroker.STRIDE) { assertTrue(d[i * DabStroker.STRIDE] in 0f..256f && d[i * DabStroker.STRIDE + 1] in 0f..64f) }
        val rFirst = d[2]; val rMid = d[(d.size / DabStroker.STRIDE / 2) * DabStroker.STRIDE + 2]
        assertTrue("давление растёт к середине: $rFirst < $rMid", rFirst < rMid)
    }
}
