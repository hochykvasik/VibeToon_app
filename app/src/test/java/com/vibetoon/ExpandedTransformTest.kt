package com.vibetoon

import com.vibetoon.domain.project.*
import com.vibetoon.domain.raster.*
import com.vibetoon.domain.render.LayerTransform
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class ExpandedTransformTest {
    private fun map(state: ExpandedLayerSnapshot.State, t: LayerTransform, x: Double, y: Double): Pair<Double, Double> {
        val m = state.media
        val mx = m.x.roundToInt().toDouble(); val my = m.y.roundToInt().toDouble()
        val w = m.w.roundToInt().coerceAtLeast(1).toDouble(); val h = m.h.roundToInt().coerceAtLeast(1).toDouble()
        val px = mx + w / 2; val py = my + h / 2
        val u = (mx + x * w / state.width - px) * t.scaleX
        val v = (my + y * h / state.height - py) * t.scaleY
        val c = cos(t.rotation.toDouble()); val s = sin(t.rotation.toDouble())
        return (px + t.dx + c * u - s * v) to (py + t.dy + s * u + c * v)
    }
    @Test fun nonUniformRotationExpansionAndTrimPreservePixelGrid() {
        val media = MediaItem(100001, MediaKind.IMAGE, "part", "assets/part.png", 7, 5, 21.3f, -9.4f, 31.6f, 22.8f)
        val rgba = ByteArray(7 * 5 * 4).also { it[3] = -1; it[(4 * 7 + 6) * 4 + 3] = -1 }
        val mask = ByteArray(35).also { it[0] = 1 }
        val t = LayerTransform(dx = 7f, dy = -3f, scaleX = 1.7f, scaleY = .6f, rotation = .7f)
        val changes = listOf(
            ExpandedLayerSnapshot.prepare(media, rgba, 0, 7, 5, mask, -3, 1)!!,
            ExpandedLayerSnapshot.prepareTrimmed(media, rgba, 0, 7, 5, mask, 30, -10, Long.MAX_VALUE)!!
        )
        for (change in changes) {
            val next = ExpandedTransform.compensate(change, t)!!
            val left = ((change.after.media.x - media.x) * 7 / media.w).roundToInt()
            val top = ((change.after.media.y - media.y) * 5 / media.h).roundToInt()
            for ((x, y) in listOf(0.0 to 0.0, 6.5 to 4.5, 2.2 to 3.7)) {
                val old = map(change.before, t, x, y)
                val actual = map(change.after, next, x - left, y - top)
                assertEquals(old.first, actual.first, .0001)
                assertEquals(old.second, actual.second, .0001)
            }
            val data = ProjectData(ProjectMeta("p", "Test", 100, 100), base = mapOf(media.id to next), media = listOf(change.after.media))
            val reopened = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
            assertEquals(change.after.media, reopened.media.single())
            assertEquals(next, reopened.base[media.id])
            val history = ExpandedLayerHistory(change)
            assertArrayEquals(rgba, history.transition(ExpandedLayerHistory.Direction.UNDO).target.rgba)
            assertArrayEquals(change.after.rgba, history.transition(ExpandedLayerHistory.Direction.REDO).target.rgba)
        }
    }
    @Test fun rejectsDegenerateOrOutOfRangeTransforms() {
        val m = MediaItem(100001, MediaKind.IMAGE, "part", "assets/part.png", 2, 2, 0f, 0f, 2f, 2f)
        val change = ExpandedLayerSnapshot.prepare(m, ByteArray(16), 0, 2, 2, byteArrayOf(1,0,0,0), -1, 0)!!
        assertNull(ExpandedTransform.compensate(change, LayerTransform(scaleX = 0f)))
        assertNull(ExpandedTransform.compensate(change, LayerTransform(rotation = Float.NaN)))
        assertNull(ExpandedTransform.compensate(change, LayerTransform(scaleX = 30f)))
    }
}
