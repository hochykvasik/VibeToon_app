package com.vibetoon

import com.vibetoon.domain.raster.RasterMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterMathTest {
    private val W = 40
    private val H = 40

    /** Прозрачный слой с чёрным квадратным контуром 10..29, толщина 2; [gapAt] - вырезает щель в верхней стороне. */
    private fun ring(gap: Int = 0): ByteArray {
        val px = ByteArray(W * H * 4)
        fun set(x: Int, y: Int) { val i = (y * W + x) * 4; px[i + 3] = 0xFF.toByte() }
        for (y in 10..29) for (x in 10..29) {
            val edge = x < 12 || x > 27 || y < 12 || y > 27
            if (edge) set(x, y)
        }
        if (gap > 0) for (x in 18 until 18 + gap) for (y in 10..11) { val i = (y * W + x) * 4; px[i + 3] = 0 }
        return px
    }

    private fun alpha(px: ByteArray, x: Int, y: Int) = px[(y * W + x) * 4 + 3].toInt() and 0xFF

    @Test fun fillStaysInsideClosedContour() {
        val px = ring()
        val region = RasterMath.floodRegion(px, 0, W, H, 20, 20, 16, 0)
        assertEquals(16 * 16, RasterMath.count(region))   // 12..27 внутри
        assertEquals(0, region[5 * W + 5].toInt())        // снаружи не залито
    }

    @Test fun fillLeaksThroughGapUnlessClosed() {
        val px = ring(gap = 3)
        val leaked = RasterMath.floodRegion(px, 0, W, H, 20, 20, 16, 0)
        assertTrue(RasterMath.count(leaked) > 600)   // вытекло наружу
        val closed = RasterMath.floodRegion(px, 0, W, H, 20, 20, 16, 2)
        assertTrue(RasterMath.count(closed) in 1..300)   // щель 3 px закрыта при gap=2
    }

    @Test fun expandCoversEdgeWithoutOverwritingLine() {
        val px = ring()
        val region = RasterMath.floodRegion(px, 0, W, H, 20, 20, 16, 0)
        val res = RasterMath.fillResult(px, 0, W, H, region, 0xFFFF0000.toInt(), expand = 1, gap = 0)!!
        // линия осталась чёрной и непрозрачной: после заливки её пиксель не изменился
        val r = res.rect
        fun after(x: Int, y: Int, c: Int) = res.after[((y - r.y) * r.w + (x - r.x)) * 4 + c].toInt() and 0xFF
        assertEquals(255, after(11, 20, 3))
        assertEquals(0, after(11, 20, 0))      // чёрный по-прежнему
        assertEquals(255, after(20, 20, 0))    // внутри красный
        assertEquals(255, after(20, 20, 3))
    }

    @Test fun fillOnFilledAreaChangesNothing() {
        val px = ring()
        val region = RasterMath.floodRegion(px, 0, W, H, 20, 20, 16, 0)
        val res = RasterMath.fillResult(px, 0, W, H, region, 0, expand = 0, gap = 0)   // прозрачным по прозрачному
        assertNull(res)
    }

    @Test fun maskShapesAndInvert() {
        val r = RasterMath.rectMask(W, H, 5f, 5f, 14f, 14f)
        assertEquals(100, RasterMath.count(r))
        val o = RasterMath.ovalMask(W, H, 0f, 0f, 20f, 20f)
        assertTrue(RasterMath.count(o) in 280..330)       // ~ пи * 10^2
        val tri = RasterMath.polygonMask(W, H, floatArrayOf(0f, 0f, 20f, 0f, 0f, 20f))
        assertTrue(RasterMath.count(tri) in 180..220)      // половина квадрата 20x20
        assertEquals(W * H - 100, RasterMath.count(RasterMath.invert(r)))
    }

    @Test fun wandSelectsBySimilarColorOnly() {
        val px = ring()
        val inside = RasterMath.wandMask(px, 0, W, H, 20, 20, 16)
        assertEquals(256, RasterMath.count(inside))
        val line = RasterMath.wandMask(px, 0, W, H, 10, 20, 16)   // тап по линии выделяет линию
        assertTrue(RasterMath.count(line) in 100..300)
    }

    @Test fun clearAndMoveSelection() {
        val px = ring()
        val mask = RasterMath.rectMask(W, H, 10f, 10f, 29f, 29f)
        val cleared = RasterMath.clearResult(px, 0, W, H, mask)!!
        assertTrue(cleared.after.all { it.toInt() == 0 })
        // перенос на 5 вправо: старый левый край (x=10) пуст, у нового места линия на x=15
        val moved = RasterMath.moveResult(px, 0, W, H, mask, 5, 0)!!
        val r = moved.rect
        fun a(x: Int, y: Int) = moved.after[((y - r.y) * r.w + (x - r.x)) * 4 + 3].toInt() and 0xFF
        assertEquals(0, a(10, 20))
        assertEquals(255, a(15, 20))
        assertNotNull(moved.before)
        assertNull(RasterMath.moveResult(px, 0, W, H, mask, 0, 0))
    }

    @Test fun smudgeDragsPixelsAlongStroke() {
        val px = ByteArray(W * H * 4)
        for (y in 15..24) for (x in 10..14) { val i = (y * W + x) * 4; px[i] = 0xFF.toByte(); px[i + 3] = 0xFF.toByte() }
        // кружок на правом краю пятна тянет цвет вправо
        var any = false
        for (step in 0 until 8) {
            val rect = RasterMath.smudgeStep(px, 0, W, H, 15f + step, 20f, 6f, 1f, 0f, 0.9f)
            if (rect != null) any = true
        }
        assertTrue(any)
        assertTrue(alpha(px, 17, 20) > 0)   // за границей пятна появились пиксели
    }
}
