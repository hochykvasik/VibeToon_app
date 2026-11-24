package com.vibetoon

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.raster.IRect
import com.vibetoon.domain.raster.RasterMath
import com.vibetoon.domain.raster.SelectionOps
import com.vibetoon.domain.raster.SelectionWarp
import com.vibetoon.domain.raster.TransformBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionWarpOpsTest {
    private val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 16, 16, 0f, 0f, 16f, 16f)

    private fun layer(): Pair<ByteArray, ByteArray> {
        val px = ByteArray(16 * 16 * 4); val mask = ByteArray(16 * 16)
        for (y in 2 until 4) for (x in 2 until 6) { val i = y * 16 + x; mask[i] = 1; px[i * 4] = 200.toByte(); px[i * 4 + 3] = 255.toByte() }
        px[(15 * 16 + 15) * 4 + 1] = 77; px[(15 * 16 + 15) * 4 + 3] = 255.toByte()
        return px to mask
    }

    @Test fun identityKeepsPixelsAndTranslationMoves() {
        val (px, mask) = layer()
        val box = TransformBox.initial(RasterMath.bounds(mask, 16, 16)!!)
        val same = SelectionWarp.prepare(media, px, 0, 16, 16, mask, box, Long.MAX_VALUE)!!
        assertArrayEquals(px, same.after.rgba); assertArrayEquals(mask, same.selection)
        val moved = TransformBox.drag(box, TransformBox.Handle.MOVE, TransformBox.Mode.FREE, TransformBox.P(0.0, 0.0), TransformBox.P(6.0, 8.0), false)
        val ch = SelectionWarp.prepare(media, px, 0, 16, 16, mask, moved, Long.MAX_VALUE)!!
        assertEquals(0, ch.after.rgba[(2 * 16 + 2) * 4].toInt())
        assertEquals(200, ch.after.rgba[(10 * 16 + 8) * 4].toInt() and 255)
        assertEquals(8, RasterMath.count(ch.selection))
        assertEquals(77, ch.after.rgba[(15 * 16 + 15) * 4 + 1].toInt())   // невыделенное не тронуто
        assertArrayEquals(px, ch.before.rgba)
    }

    @Test fun scaleSkewDistortAndRejects() {
        val (px, mask) = layer()
        val box = TransformBox.withAnchor(TransformBox.initial(IRect(2, 2, 4, 2)), 0, 0)
        val big = TransformBox.numeric(box, TransformBox.Numeric(scaleX = 2.0, scaleY = 2.0))!!
        val ch = SelectionWarp.prepare(media, px, 0, 16, 16, mask, big, Long.MAX_VALUE)!!
        assertEquals(32, RasterMath.count(ch.selection))
        val skew = TransformBox.numeric(box, TransformBox.Numeric(skewXDeg = 30.0))!!
        assertTrue(RasterMath.count(SelectionWarp.prepare(media, px, 0, 16, 16, mask, skew, Long.MAX_VALUE)!!.selection) in 6..10)
        val ds = box.copy(corners = listOf(TransformBox.P(2.0, 2.0), TransformBox.P(10.0, 1.0), TransformBox.P(9.0, 8.0), TransformBox.P(2.0, 5.0)))
        val dch = SelectionWarp.prepare(media, px, 0, 16, 16, mask, ds, Long.MAX_VALUE)!!
        assertTrue(RasterMath.count(dch.selection) > 20)
        // за границей слоя, малый бюджет, неверная рамка, неподходящий слой
        val out = TransformBox.drag(box, TransformBox.Handle.MOVE, TransformBox.Mode.FREE, TransformBox.P(0.0, 0.0), TransformBox.P(13.0, 0.0), false)
        assertNull(SelectionWarp.prepare(media, px, 0, 16, 16, mask, out, Long.MAX_VALUE))
        assertNull(SelectionWarp.prepare(media, px, 0, 16, 16, mask, big, 0))
        assertNull(SelectionWarp.prepare(media, px, 0, 16, 16, mask, box.copy(corners = List(4) { TransformBox.P(3.0, 3.0) }), Long.MAX_VALUE))
        assertNull(SelectionWarp.prepare(media.copy(vector = true), px, 0, 16, 16, mask, big, Long.MAX_VALUE))
    }

    @Test fun combineModes() {
        val a = byteArrayOf(1, 1, 0, 0); val b = byteArrayOf(0, 1, 1, 0)
        assertArrayEquals(byteArrayOf(0, 1, 1, 0), SelectionOps.combine(a, b, SelectionOps.Combine.REPLACE))
        assertArrayEquals(byteArrayOf(1, 1, 1, 0), SelectionOps.combine(a, b, SelectionOps.Combine.ADD))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), SelectionOps.combine(a, b, SelectionOps.Combine.SUBTRACT))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), SelectionOps.combine(a, b, SelectionOps.Combine.INTERSECT))
        assertArrayEquals(b, SelectionOps.combine(null, b, SelectionOps.Combine.ADD))
        assertEquals(0, RasterMath.count(SelectionOps.combine(null, b, SelectionOps.Combine.SUBTRACT)))
        assertEquals(12, RasterMath.count(SelectionOps.all(3, 4)))
    }

    @Test fun growAndShrink() {
        val w = 21; val h = 21
        val dot = ByteArray(w * h); dot[10 * w + 10] = 1
        val g = SelectionOps.grow(dot, w, h, 3)!!
        assertEquals(1, g[10 * w + 13].toInt()); assertEquals(0, g[10 * w + 14].toInt())
        assertEquals(0, g[13 * w + 13].toInt())   // диагональ ~4.2 > 3: форма близка к кругу
        val back = SelectionOps.grow(g, w, h, -3)!!
        assertEquals(1, back[10 * w + 10].toInt()); assertTrue(RasterMath.count(back) < RasterMath.count(g))
        // край слоя не сужает выделение
        val full = SelectionOps.all(w, h)
        assertEquals(w * h, RasterMath.count(SelectionOps.grow(full, w, h, -5)!!))
        assertArrayEquals(dot, SelectionOps.grow(dot, w, h, 0))
        assertNull(SelectionOps.grow(dot, 5, 5, 1))
    }
}
