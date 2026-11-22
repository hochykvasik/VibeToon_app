package com.vibetoon

import com.vibetoon.domain.project.*
import com.vibetoon.domain.raster.*
import org.junit.Assert.*
import org.junit.Test

class SelectionMirrorTest {
    @Test fun mirrorsOnlySelectionAndKeepsFullUndoSnapshot() {
        val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 3, 2, 0f, 0f, 3f, 2f)
        val px = ByteArray(24)
        fun set(k: Int, r: Int, a: Int) { px[k * 4] = r.toByte(); px[k * 4 + 3] = a.toByte() }
        set(0, 80, 255); set(2, 20, 255); set(3, 50, 255); set(4, 60, 255)
        val mask = byteArrayOf(1,0,1, 1,0,0)
        val change = SelectionMirror.prepare(media, px, 0, 3, 2, mask, true, Long.MAX_VALUE)!!
        assertArrayEquals(byteArrayOf(1,0,1, 0,0,1), change.selection)
        assertEquals(20, change.after.rgba[0].toInt() and 255)
        assertEquals(80, change.after.rgba[8].toInt() and 255)
        assertEquals(0, change.after.rgba[12].toInt() and 255)
        assertEquals(60, change.after.rgba[16].toInt() and 255)
        assertEquals(50, change.after.rgba[20].toInt() and 255)
        assertArrayEquals(px, change.before.rgba)
        val history = ExpandedLayerHistory(change)
        assertSame(change.before, history.transition(ExpandedLayerHistory.Direction.UNDO).target)
        assertSame(change.after, history.transition(ExpandedLayerHistory.Direction.REDO).target)
        assertEquals(media, change.after.media)
    }
    @Test fun quarterTurnAndScaleHaveUndoSnapshotsAndNeverClip() {
        val m = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 6, 6, 0f, 0f, 6f, 6f)
        val px = ByteArray(6 * 6 * 4); val mask = ByteArray(36)
        for (y in 2..3) for (x in 2..3) { val i = y * 6 + x; mask[i] = 1; px[i * 4] = (x + y * 6).toByte(); px[i * 4 + 3] = 255.toByte() }
        val cw = SelectionMirror.prepare(m, px, 0, 6, 6, mask, SelectionMirror.Operation.ROTATE_CW, Long.MAX_VALUE)!!
        assertEquals(14, cw.after.rgba[(2 * 6 + 3) * 4].toInt() and 255)
        assertEquals(20, cw.after.rgba[(2 * 6 + 2) * 4].toInt() and 255)
        assertArrayEquals(px, cw.before.rgba)
        val big = SelectionMirror.prepare(m, px, 0, 6, 6, mask, SelectionMirror.Operation.SCALE_DOUBLE, Long.MAX_VALUE)!!
        assertEquals(16, RasterMath.count(big.selection))
        assertEquals(14, big.after.rgba[(1 * 6 + 1) * 4].toInt() and 255)
        assertArrayEquals(px, big.before.rgba)
        val small = SelectionMirror.prepare(m, px, 0, 6, 6, mask, SelectionMirror.Operation.SCALE_HALF, Long.MAX_VALUE)!!
        assertEquals(1, RasterMath.count(small.selection))
        val edge = ByteArray(36).also { it[0] = 1; it[1] = 1; it[6] = 1; it[7] = 1 }
        assertNull(SelectionMirror.prepare(m, px, 0, 6, 6, edge, SelectionMirror.Operation.SCALE_DOUBLE, Long.MAX_VALUE))
        assertArrayEquals(px, big.before.rgba)
    }
    @Test fun rejectsUnsupportedAndInsufficientMemory() {
        val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 2, 2, 0f, 0f, 2f, 2f)
        val px = ByteArray(16); val mask = byteArrayOf(1,0,0,1)
        assertNull(SelectionMirror.prepare(media.copy(vector = true), px, 0, 2, 2, mask, true, Long.MAX_VALUE))
        assertNull(SelectionMirror.prepare(media, px, 0, 2, 2, mask, false, 0))
        assertArrayEquals(ByteArray(16), px)
    }
}
