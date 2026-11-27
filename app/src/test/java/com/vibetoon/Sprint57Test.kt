package com.vibetoon

import com.vibetoon.domain.settings.SidePanelSlide
import com.vibetoon.domain.settings.UiMotion
import com.vibetoon.domain.tutorial.CoachMenuTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 57: плавная правая панель планшета (SidePanelSlide) и подсказка урока внутри меню «Действия». */
class Sprint57Test {
    // ---------------------------------------------------------------- 1. правая панель

    @Test fun slotIsReservedOnlyWhenPanelFullyOpen() {
        assertTrue(SidePanelSlide.slotReserved(open = true, progress = 1f))
        assertFalse("въезжает - место ещё свободно, таймлайн не прыгает", SidePanelSlide.slotReserved(true, 0.5f))
        assertFalse("закрывается - место освобождается сразу", SidePanelSlide.slotReserved(false, 1f))
        assertFalse(SidePanelSlide.slotReserved(false, 0f))
    }

    @Test fun panelIsComposedWhileSlidingOut() {
        assertTrue(SidePanelSlide.composed(true, 0f))
        assertTrue("уезжает - ещё рисуется", SidePanelSlide.composed(false, 0.4f))
        assertFalse("уехала - не держит память и не рисуется", SidePanelSlide.composed(false, 0f))
    }

    @Test fun offsetMovesPanelBeyondItsEdge() {
        assertEquals(0, SidePanelSlide.offsetPx(1f, 320, atRight = true))
        assertEquals(320, SidePanelSlide.offsetPx(0f, 320, atRight = true))
        assertEquals(160, SidePanelSlide.offsetPx(0.5f, 320, atRight = true))
        assertEquals("левша: уходит влево", -160, SidePanelSlide.offsetPx(0.5f, 320, atRight = false))
        assertEquals("ход за пределами 0..1 обрезан", 0, SidePanelSlide.offsetPx(1.3f, 320, true))
        assertEquals(0, SidePanelSlide.offsetPx(0f, -5, true))
    }

    @Test fun canvasEdgeFollowsPanelContinuously() {
        // строка 0..1000, панель 300; холст (колонка) 80..1000, когда место свободно, и 80..700, когда занято
        val half = SidePanelSlide.canvasSpan(80f, 1000f, 0f, 1000f, 0.5f, 300, atRight = true)
        assertEquals(80f to 850f, half)
        assertEquals(80f to 1000f, SidePanelSlide.canvasSpan(80f, 1000f, 0f, 1000f, 0f, 300, true))
        // в момент, когда место занялось (колонка сузилась до 700), край холста не прыгает
        val before = SidePanelSlide.canvasSpan(80f, 1000f, 0f, 1000f, 1f, 300, true)
        val after = SidePanelSlide.canvasSpan(80f, 700f, 0f, 1000f, 1f, 300, true)
        assertEquals(before, after)
        // и в момент начала закрытия (колонка сразу 1000) - тоже
        assertEquals(after, SidePanelSlide.canvasSpan(80f, 1000f, 0f, 1000f, 1f, 300, true))
    }

    @Test fun canvasSpanLeftHandedAndUnmeasured() {
        // левша: панель слева, строка 0..1000, инструменты справа (колонка 0..920)
        assertEquals(150f to 920f, SidePanelSlide.canvasSpan(0f, 920f, 0f, 1000f, 0.5f, 300, atRight = false))
        assertEquals(300f to 920f, SidePanelSlide.canvasSpan(300f, 920f, 0f, 1000f, 1f, 300, false))
        assertEquals("строка не измерена - холст как есть", 80f to 1000f, SidePanelSlide.canvasSpan(80f, 1000f, 0f, 0f, 0.5f, 300, true))
        val tiny = SidePanelSlide.canvasSpan(80f, 200f, 0f, 1000f, 1f, 950, true)
        assertTrue("область не выворачивается", tiny.second >= tiny.first)
    }

    @Test fun slideUsesSoftSidePanelTimings() {
        assertTrue(UiMotion.SIDE_PANEL_MS in 250..400 && UiMotion.SIDE_PANEL_EXIT_MS < UiMotion.SIDE_PANEL_MS)
    }

    // ---------------------------------------------------------------- 2. окно референса в уроках

    @Test fun actionsMenuCoachTargets() {
        assertTrue(CoachMenuTargets.inActionsMenu("Окно референса…"))
        assertTrue(CoachMenuTargets.inActionsMenu("Тестовый PSD"))
        assertFalse(CoachMenuTargets.inActionsMenu("Действия"))
        assertFalse(CoachMenuTargets.inActionsMenu(null))
    }

    @Test fun coachOpensAndClosesMenuOnlyWhenItOpenedIt() {
        assertEquals(true, CoachMenuTargets.menuAction("Окно референса…", openedByCoach = false))
        assertEquals("шаг ушёл с пункта меню - урок закрывает своё меню", false, CoachMenuTargets.menuAction("Действия", openedByCoach = true))
        assertNull("меню открыл пользователь - урок его не трогает", CoachMenuTargets.menuAction("Действия", openedByCoach = false))
        assertEquals(false, CoachMenuTargets.menuAction(null, openedByCoach = true))
    }
}
