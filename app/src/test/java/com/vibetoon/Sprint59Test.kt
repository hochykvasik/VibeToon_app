package com.vibetoon

import com.vibetoon.domain.input.TopBarDeselect
import com.vibetoon.domain.tutorial.CoachWindow
import com.vibetoon.domain.tutorial.CoachWindow.Pos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 59: окно подсказки урока (перетаскивание, свёрнутый вид, без изменения размера) и тап по верхней панели. */
class Sprint59Test {
    private val e = 0.001f

    @Test fun coachWidthIsFixedAndFitsNarrowScreens() {
        assertEquals(520f, CoachWindow.width(1280f, CoachWindow.MAX_W_DP, 12f), e)
        // телефон в портрете: окно уже экрана на два отступа
        assertEquals(336f, CoachWindow.width(360f, CoachWindow.MAX_W_DP, 12f), e)
        assertEquals(340f, CoachWindow.width(800f, CoachWindow.COMPACT_W_DP, 12f), e)
        assertEquals(0f, CoachWindow.width(10f, CoachWindow.MAX_W_DP, 12f), e)
    }

    @Test fun coachHeightIsCappedSoTheEditorStaysVisible() {
        // телефон в ландшафте: 360 dp высоты - окно не выше 216 dp, текст прокручивается внутри
        assertEquals(216f, CoachWindow.maxHeight(360f, CoachWindow.MIN_HEIGHT_DP), e)
        assertEquals(480f, CoachWindow.maxHeight(800f, CoachWindow.MIN_HEIGHT_DP), e)
        // очень низкая область - не меньше нижней границы, но и не выше самой области
        assertEquals(140f, CoachWindow.maxHeight(180f, CoachWindow.MIN_HEIGHT_DP), e)
        assertEquals(100f, CoachWindow.maxHeight(100f, CoachWindow.MIN_HEIGHT_DP), e)
    }

    @Test fun autoPlacementKeepsSprint53Rule() {
        val bottom = CoachWindow.place(null, 300f, 200f, 1000f, 600f, 12f, onTop = false)
        assertEquals(Pos(350f, 388f), bottom)
        val top = CoachWindow.place(null, 300f, 200f, 1000f, 600f, 12f, onTop = true)
        assertEquals(Pos(350f, 12f), top)
    }

    @Test fun draggedWindowStaysInsideTheArea() {
        val p = Pos(100f, 100f)
        assertEquals(Pos(150f, 80f), CoachWindow.moveBy(p, 50f, -20f, 300f, 200f, 1000f, 600f))
        assertEquals(Pos(0f, 0f), CoachWindow.moveBy(p, -500f, -500f, 300f, 200f, 1000f, 600f))
        assertEquals(Pos(700f, 400f), CoachWindow.moveBy(p, 5000f, 5000f, 300f, 200f, 1000f, 600f))
        // поворот экрана или разворот из полоски: сохранённое место подтягивается в новую область
        assertEquals(Pos(60f, 160f), CoachWindow.place(Pos(900f, 500f), 300f, 200f, 360f, 360f, 12f, onTop = false))
        // окно больше области - прижато к левому верхнему углу
        assertEquals(Pos(0f, 0f), CoachWindow.clamp(Pos(50f, 50f), 400f, 500f, 300f, 300f))
        // пользовательское место важнее авторазмещения
        assertEquals(Pos(20f, 30f), CoachWindow.place(Pos(20f, 30f), 300f, 44f, 1000f, 600f, 12f, onTop = true))
    }

    @Test fun onlyADeliberateSingleFingerTapCounts() {
        val slop = 18f
        assertTrue(TopBarDeselect.isTap(3f, 4f, 120L, slop, 500L, 1, false))
        assertTrue(TopBarDeselect.isTap(0f, 18f, 0L, slop, 500L, 1, false))
        assertFalse("протяжка", TopBarDeselect.isTap(15f, 15f, 120L, slop, 500L, 1, false))
        assertFalse("долгое нажатие", TopBarDeselect.isTap(0f, 0f, 500L, slop, 500L, 1, false))
        assertFalse("второй палец", TopBarDeselect.isTap(0f, 0f, 100L, slop, 500L, 2, false))
        assertFalse("касание забрал другой жест", TopBarDeselect.isTap(0f, 0f, 100L, slop, 500L, 1, true))
        assertFalse(TopBarDeselect.isTap(0f, 0f, -1L, slop, 500L, 1, false))
    }

    @Test fun deselectNeedsSelectionAndIdleCanvas() {
        assertTrue(TopBarDeselect.shouldDeselect(hasSelection = true, canvasBusy = false))
        assertFalse(TopBarDeselect.shouldDeselect(hasSelection = false, canvasBusy = false))
        assertFalse(TopBarDeselect.shouldDeselect(hasSelection = true, canvasBusy = true))
    }

    @Test fun projectNameLeavesRoomForTheTapZone() {
        // 600 px строки, режимы 300 px: имени до 252 px, зоне не меньше 48
        assertEquals(252, TopBarDeselect.titleMax(600, 300, 48))
        // тесно: зоне не больше трети свободного места, имя не исчезает
        assertEquals(60, TopBarDeselect.titleMax(390, 300, 48))
        assertEquals(0, TopBarDeselect.titleMax(200, 300, 48))
    }
}
