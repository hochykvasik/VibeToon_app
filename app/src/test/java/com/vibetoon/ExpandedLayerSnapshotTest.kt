package com.vibetoon

import com.vibetoon.domain.project.*
import com.vibetoon.domain.raster.ExpandedLayerSnapshot
import com.vibetoon.domain.raster.ExpandedLayerHistory
import org.junit.Assert.*
import org.junit.Test

class ExpandedLayerSnapshotTest {
    @Test fun geometryPreservesUnmovedPixelsAndProjectMetadataRoundTrips() {
        val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 2, 2, 20f, 30f, 10f, 20f)
        val rgba = ByteArray(16).also { it[3] = 255.toByte(); it[15] = 255.toByte() }
        val change = ExpandedLayerSnapshot.prepare(media, rgba, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -2, 1)!!
        assertEquals(10f, change.after.media.x, 0.001f)
        assertEquals(30f, change.after.media.y, 0.001f)
        assertEquals(20f, change.after.media.w, 0.001f)
        assertEquals(20f, change.after.media.h, 0.001f)
        // Исходный пиксель (1,1) после добавления двух пикселей слева остаётся на тех же координатах холста.
        assertEquals(media.x + media.w / 2, change.after.media.x + 3 * change.after.media.w / change.after.width, 0.001f)
        assertEquals(media.y + media.h / 2, change.after.media.y + change.after.media.h / change.after.height, 0.001f)
        assertEquals(media, change.before.media)
        assertArrayEquals(rgba, change.before.rgba)
        assertEquals(4, change.after.width)
        val data = ProjectData(ProjectMeta("p", "Test", 100, 100), media = listOf(change.after.media))
        val saved = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        assertEquals(change.after.media, saved.media.single())
        assertEquals(media.id, change.after.media.id)
        assertEquals(media.file, change.after.media.file)
    }

    @Test fun rejectsUnsupportedAndInvalidGeometryWithoutChangingInput() {
        val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 2, 2, 0f, 0f, 2f, 2f)
        val bytes = ByteArray(16)
        val mask = byteArrayOf(1, 0, 0, 0)
        assertNull(ExpandedLayerSnapshot.prepare(media.copy(vector = true), bytes, 0, 2, 2, mask, -1, 0))
        assertNull(ExpandedLayerSnapshot.prepare(media.copy(w = Float.NaN), bytes, 0, 2, 2, mask, -1, 0))
        assertArrayEquals(ByteArray(16), bytes)
    }
    @Test fun historyMapsBothDirectionsToCompleteTextureAndGeometry() {
        val media = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 2, 2, 20f, 30f, 10f, 20f)
        val pixels = ByteArray(16).also { it[3] = 255.toByte() }
        val change = ExpandedLayerSnapshot.prepare(media, pixels, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -2, 0)!!
        val history = ExpandedLayerHistory(change)
        val undo = history.transition(ExpandedLayerHistory.Direction.UNDO)
        val redo = history.transition(ExpandedLayerHistory.Direction.REDO)
        assertSame(change.after, undo.expected)
        assertSame(change.before, undo.target)
        assertSame(change.before, redo.expected)
        assertSame(change.after, redo.target)
        assertEquals(media, undo.target.media)
        assertArrayEquals(pixels, undo.target.rgba)
        assertEquals(4, redo.target.width)
        assertEquals(10f, redo.target.media.x, .001f)
        assertArrayEquals(change.after.rgba, redo.target.rgba)
    }

    @Test fun moveAndHistoryTransitionsKeepPixelsAndGeometryTogether() = kotlinx.coroutines.runBlocking {
        val original = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/ink.png", 2, 2, 0f, 0f, 2f, 2f)
        val pixels = ByteArray(16).also { it[0] = 30; it[3] = 255.toByte(); it[15] = 255.toByte() }
        val change = ExpandedLayerSnapshot.prepare(original, pixels, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -2, 0)!!
        val history = ExpandedLayerHistory(change)
        var texture = change.before
        var geometry = change.before.media
        suspend fun apply(target: ExpandedLayerSnapshot.State) = com.vibetoon.domain.raster.TextureTransaction.execute(
            replace = { texture = target; true },
            publish = { geometry = target.media },
            rollback = { false }
        )
        assertEquals(com.vibetoon.domain.raster.TextureTransaction.Outcome.COMMITTED, apply(change.after))
        assertEquals(-2f, geometry.x, .001f)
        assertArrayEquals(change.after.rgba, texture.rgba)
        val undo = history.transition(ExpandedLayerHistory.Direction.UNDO)
        assertSame(texture, undo.expected)
        apply(undo.target)
        assertEquals(original, geometry)
        assertArrayEquals(pixels, texture.rgba)
        val redo = history.transition(ExpandedLayerHistory.Direction.REDO)
        assertSame(texture, redo.expected)
        apply(redo.target)
        assertEquals(change.after.media, geometry)
        assertArrayEquals(change.after.rgba, texture.rgba)
    }

    @Test fun drawingAfterDeletedImportKeepsItsOwnIdentityAcrossMoveUndoRedo() {
        val allocator = MediaIdAllocator()
        val deletedImportId = allocator.next(emptyList())
        val drawingId = allocator.next(emptyList())
        assertNotEquals(deletedImportId, drawingId)
        val drawing = MediaItem(drawingId, MediaKind.IMAGE, "Drawing", "assets/drawing.png", 2, 2, 0f, 0f, 2f, 2f)
        val before = ByteArray(16).also { it[3] = 255.toByte(); it[15] = 255.toByte() }
        val change = ExpandedLayerSnapshot.prepare(drawing, before, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -1, 0)!!
        val history = ExpandedLayerHistory(change)
        val undo = history.transition(ExpandedLayerHistory.Direction.UNDO).target
        val redo = history.transition(ExpandedLayerHistory.Direction.REDO).target
        assertEquals(drawingId, undo.media.id)
        assertEquals(drawing, undo.media)
        assertArrayEquals(before, undo.rgba)
        assertEquals(drawingId, redo.media.id)
        assertEquals(3, redo.width)
        assertArrayEquals(change.after.rgba, redo.rgba)
    }

    @Test fun tutorialTrimCanExpandBeyondCanvasAndUndoKeepsWholeOriginal() {
        val w = 1920; val h = 1080
        val media = MediaItem(100001, MediaKind.IMAGE, "Drawing", "assets/drawing.png", w, h, 0f, 0f, w.toFloat(), h.toFloat())
        val pixels = ByteArray(w * h * 4)
        // Невыделенный пиксель слева и выбранный справа; прозрачные поля вокруг обрезаются.
        pixels[(500 * w + 200) * 4 + 3] = 255.toByte()
        pixels[(500 * w + 1919) * 4 + 3] = 255.toByte()
        val mask = ByteArray(w * h).also { it[500 * w + 1919] = 1 }
        assertNull(ExpandedLayerSnapshot.prepare(media, pixels, 0, w, h, mask, 300, 0, 128L * 1024 * 1024))
        val c = ExpandedLayerSnapshot.prepareTrimmed(media, pixels, 0, w, h, mask, 300, 0, 128L * 1024 * 1024)!!
        assertEquals(2020, c.after.width)
        assertEquals(200f, c.after.media.x, .001f)
        assertEquals(500f, c.after.media.y, .001f)
        assertEquals(255, c.after.rgba[3].toInt() and 255)
        assertEquals(255, c.after.rgba[(2019 * 4) + 3].toInt() and 255)
        val history = ExpandedLayerHistory(c)
        assertArrayEquals(pixels, history.transition(ExpandedLayerHistory.Direction.UNDO).target.rgba)
        assertEquals(media, history.transition(ExpandedLayerHistory.Direction.UNDO).target.media)
        assertArrayEquals(c.after.rgba, history.transition(ExpandedLayerHistory.Direction.REDO).target.rgba)
        assertNull(ExpandedLayerSnapshot.prepareTrimmed(media, pixels, 0, w, h, mask, 300, 0, 1024))
    }

    @Test fun trimmingDoesNotDiscardNonzeroRgbEvenWhenAlphaIsZero() {
        val m = MediaItem(100002, MediaKind.IMAGE, "ink", "assets/a.png", 8, 1, 0f, 0f, 8f, 1f)
        val pixels = ByteArray(32).also { it[0] = 1; it[7 * 4 + 3] = 127 }
        val mask = ByteArray(8).also { it[7] = 1 }
        val c = ExpandedLayerSnapshot.prepareTrimmed(m, pixels, 0, 8, 1, mask, 1, 0, 128L * 1024 * 1024)!!
        assertEquals(0f, c.after.media.x, .001f)
        assertEquals(1, c.after.rgba[0].toInt())
        assertArrayEquals(pixels, c.before.rgba)
    }

    @Test fun trimStillRejectsIfNontransparentPixelsMakeResultTooWide() {
        val w = 1920; val m = MediaItem(100001, MediaKind.IMAGE, "Drawing", "assets/d.png", w, 1, 0f, 0f, w.toFloat(), 1f)
        val pixels = ByteArray(w * 4).also { it[3] = 1; it[(w - 1) * 4 + 3] = 1 }
        val mask = ByteArray(w).also { it[w - 1] = 1 }
        assertNull(ExpandedLayerSnapshot.prepareTrimmed(m, pixels, 0, w, 1, mask, 129, 0, 128L * 1024 * 1024))
        assertEquals(1, pixels[3].toInt())
        assertEquals(1, pixels[(w - 1) * 4 + 3].toInt())
    }

    @Test fun wholeObjectCanTravelFarBeyondCanvasWithoutIncludingItsOldEmptyPosition() {
        val w = 1920; val h = 1080
        val m = MediaItem(100001, MediaKind.IMAGE, "Drawing", "assets/d.png", w, h, 0f, 0f, w.toFloat(), h.toFloat())
        val pixels = ByteArray(w * h * 4)
        val mask = ByteArray(w * h)
        for (x in 32..100) { val k = 50 * w + x; pixels[k * 4 + 3] = 255.toByte(); mask[k] = 1 }
        val c = ExpandedLayerSnapshot.prepareTrimmed(m, pixels, 0, w, h, mask, 3000, 0, 128L * 1024 * 1024)!!
        assertEquals(69, c.after.width)
        assertEquals(1, c.after.height)
        assertEquals(3032f, c.after.media.x, .001f)
        assertEquals(255, c.after.rgba[3].toInt() and 255)
        assertArrayEquals(pixels, ExpandedLayerHistory(c).transition(ExpandedLayerHistory.Direction.UNDO).target.rgba)
        val json = ProjectJson.encode(ProjectData(ProjectMeta("p", "p", w, h), media = listOf(c.after.media)))
        assertEquals(c.after.media, ProjectJson.decode(json).getOrThrow().media.single())
    }

    @Test fun compactOverlapMatchesOrdinaryPremultipliedMove() {
        val m = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/a.png", 4, 1, 0f, 0f, 4f, 1f)
        val pixels = ByteArray(16).also {
            it[0] = 1 // нулевая альфа, но ненулевой канал нельзя потерять
            it[4] = 64; it[7] = 128.toByte(); it[10] = 100; it[11] = 255.toByte()
        }
        val mask = byteArrayOf(0, 1, 0, 0)
        val full = ExpandedLayerSnapshot.prepare(m, pixels, 0, 4, 1, mask, 1, 0)!!
        val compact = ExpandedLayerSnapshot.prepareTrimmed(m, pixels, 0, 4, 1, mask, 1, 0, 128L * 1024 * 1024)!!
        assertEquals(3, compact.after.width)
        assertArrayEquals(full.after.rgba.copyOfRange(0, 12), compact.after.rgba)
        assertArrayEquals(pixels, compact.before.rgba)
    }

}
