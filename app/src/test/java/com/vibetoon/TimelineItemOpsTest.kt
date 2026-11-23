package com.vibetoon

import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.TimelineItemOps
import com.vibetoon.domain.project.TimelineItemOps.MARKERS
import com.vibetoon.domain.project.TimelineItemOps.TIME_KEYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Маркеры кадров и ключи времени работают как ключи: сдвиг, копия/вставка, дубликат, зеркало, растяжение. */
class TimelineItemOpsTest {
    private val marks = listOf(FrameMarker(0, "A"), FrameMarker(3, "B"), FrameMarker(6, "C"))
    private fun frames(l: List<FrameMarker>?) = l!!.map { it.frame }

    @Test fun moveClampsAtBoundsAndRejectsCollision() {
        assertEquals(listOf(0, 5, 8), frames(TimelineItemOps.move(marks, setOf(3, 6), 2, 8, MARKERS)))
        assertEquals(listOf(0, 5, 8), frames(TimelineItemOps.move(marks, setOf(3, 6), 50, 8, MARKERS)))   // упор в последний кадр
        assertEquals(marks, TimelineItemOps.move(marks, setOf(0), -3, 8, MARKERS))                       // упор в 0 - без изменений
        assertNull(TimelineItemOps.move(marks, setOf(3), 3, 8, MARKERS))                                  // кадр 6 занят
        assertEquals("B", TimelineItemOps.move(marks, setOf(3), 1, 8, MARKERS)!!.first { it.frame == 4 }.name)
    }

    @Test fun copyPasteReplacesAndRespectsBounds() {
        val clip = TimelineItemOps.copy(marks, setOf(3, 6), MARKERS)
        assertEquals(listOf(0, 3), frames(clip))
        val pasted = TimelineItemOps.paste(marks, clip, 6, 10, MARKERS)!!
        assertEquals(listOf(0, 3, 6, 9), frames(pasted))
        assertEquals("B", pasted.first { it.frame == 6 }.name)   // C на кадре 6 заменён
        assertNull(TimelineItemOps.paste(marks, clip, 9, 10, MARKERS))
        assertEquals(setOf(6, 9), TimelineItemOps.pastedFrames(clip, 6, MARKERS))
    }

    @Test fun duplicatePlacesCopyRightAfterSelection() {
        val (list, sel) = TimelineItemOps.duplicate(marks, setOf(0, 3), 20, MARKERS)!!
        assertEquals(listOf(0, 3, 4, 6, 7), frames(list))
        assertEquals(setOf(4, 7), sel)
        assertNull(TimelineItemOps.duplicate(marks, setOf(3, 6), 8, MARKERS))   // не влезает
        assertNull(TimelineItemOps.duplicate(marks, emptySet(), 8, MARKERS))
    }

    @Test fun deleteAndMirror() {
        assertEquals(listOf(0), frames(TimelineItemOps.delete(marks, setOf(3, 6), MARKERS)))
        val m = TimelineItemOps.mirror(listOf(FrameMarker(2, "A"), FrameMarker(3, "B"), FrameMarker(8, "C")), setOf(2, 3, 8), MARKERS)
        assertEquals(listOf("C" to 2, "B" to 7, "A" to 8), m.map { it.name to it.frame })
        assertEquals(marks, TimelineItemOps.mirror(marks, setOf(3), MARKERS))   // один элемент - без изменений
    }

    @Test fun mirrorTimeKeysMovesSegmentEaseWithSegment() {
        // 0 -(SMOOTH)-> 4 -(HOLD)-> 10: после зеркала HOLD-сегмент идёт первым, SMOOTH - вторым
        val keys = listOf(TimeKey(0, 1, TimeEase.SMOOTH), TimeKey(4, 2, TimeEase.HOLD), TimeKey(10, 3, TimeEase.LINEAR))
        val m = TimelineItemOps.mirrorTimeKeys(keys, setOf(0, 4, 10))
        assertEquals(listOf(TimeKey(0, 3, TimeEase.HOLD), TimeKey(6, 2, TimeEase.SMOOTH), TimeKey(10, 1, TimeEase.HOLD)), m)
    }

    @Test fun stretchAndEase() {
        val keys = listOf(TimeKey(2, 0), TimeKey(4, 1), TimeKey(6, 2))
        assertEquals(listOf(2, 6, 10), TimelineItemOps.stretch(keys, setOf(2, 4, 6), 2f, 20, TIME_KEYS)!!.map { it.frame })
        assertNull(TimelineItemOps.stretch(keys, setOf(2, 4, 6), 0.1f, 20, TIME_KEYS))   // кадры слились
        assertEquals(setOf(2, 6, 10), TimelineItemOps.stretchedFrames(setOf(2, 4, 6), 2f, 20))
        val eased = TimelineItemOps.withEase(keys, setOf(4), TimeEase.SMOOTH)
        assertEquals(listOf(TimeEase.HOLD, TimeEase.SMOOTH, TimeEase.HOLD), eased.map { it.ease })
        assertEquals(TimeEase.HOLD, TimelineItemOps.withEase(keys, setOf(4), 42)[1].ease)
        assertEquals(0, TimelineItemOps.clampDelta(emptySet(), 5, 10))
    }
}
