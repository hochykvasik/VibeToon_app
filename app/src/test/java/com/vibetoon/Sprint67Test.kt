package com.vibetoon

import com.vibetoon.domain.anim.TimelineNavigation
import com.vibetoon.domain.project.WaveDrawBarrier
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Sprint67Test {
    @Test fun navigationFramesExposuresAndBoundaries() {
        val p = listOf(2, 10, 25, 25, 90)
        assertEquals(11, TimelineNavigation.step(10, 1, 40, true, p))
        assertEquals(9, TimelineNavigation.step(10, -1, 40, true, p))
        assertEquals(25, TimelineNavigation.step(10, 1, 40, false, p))
        assertEquals(2, TimelineNavigation.step(10, -1, 40, false, p))
        assertEquals(25, TimelineNavigation.step(25, 1, 40, false, p))
        assertEquals(0, TimelineNavigation.step(0, -1, 40, true, p))
        assertEquals(8, TimelineNavigation.step(8, 1, 40, false, emptyList()))
    }

    @Test fun importedWaveMustActuallyBeDrawnForCorrectTrackAndData() = runBlocking {
        val barrier = WaveDrawBarrier(7)
        val actual = floatArrayOf(0f, 0.5f)
        barrier.acknowledge(8, actual, actual)
        barrier.acknowledge(7, floatArrayOf(0f, 0.5f), actual)
        val empty = floatArrayOf()
        barrier.acknowledge(7, empty, empty)
        assertFalse(barrier.drawn.isCompleted)
        barrier.acknowledge(7, actual, actual)
        barrier.drawn.await()
        assertTrue(barrier.drawn.isCompleted)
    }
    @Test fun quickDrawingRemovalKeepsLayerAndOtherExposureData() {
        val first = FrameCell(0, "first.png")
        val second = FrameCell(10, "second.png")
        val before = listOf(first, second)
        val after = FrameMath.without(before, 15)
        assertEquals(listOf(first), after)
        assertEquals(before, listOf(first, second)) // исходный снимок пригоден для Undo
        assertEquals(after, FrameMath.without(after, 0)) // последний рисунок нельзя удалить
    }

    @Test fun visualSwitchesOnlyTouchEditorOverlays() {
        val main = java.io.File("src/main/java/com/vibetoon/ui/MainScreen.kt").readText()
        val settings = java.io.File("src/main/java/com/vibetoon/ui/theme/AppTheme.kt").readText()
        assertTrue(main.contains("selectionMaskVisible != false) so.bmp else null"))
        assertTrue(main.contains("if (b.selected) Color(0xFFFFA726) else if (colored)"))
        assertTrue(settings.contains("putBoolean(\"selection_mask_visible\", selectionMaskVisible)"))
        assertTrue(settings.contains("putBoolean(\"bone_colors\", boneColors)"))
    }
}
