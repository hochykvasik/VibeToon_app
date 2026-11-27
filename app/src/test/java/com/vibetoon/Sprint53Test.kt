package com.vibetoon

import com.vibetoon.domain.tutorial.CoachPlacement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 53: карточка подсказки урока не закрывает подсвеченную кнопку внизу экрана (таймлайн, строка рисунков). */
class Sprint53Test {
    @Test fun targetInLowerHalfMovesCardToTop() {
        assertTrue(CoachPlacement.cardOnTop(900f, 1000f))     // строка рисунков / таймлайн
        assertTrue(CoachPlacement.cardOnTop(501f, 1000f))
    }

    @Test fun targetInUpperHalfKeepsCardAtBottom() {
        assertFalse(CoachPlacement.cardOnTop(40f, 1000f))     // верхняя панель: «Действия», «Export»
        assertFalse(CoachPlacement.cardOnTop(500f, 1000f))    // ровно середина - по-прежнему снизу
    }

    @Test fun degenerateViewportKeepsDefault() {
        assertFalse(CoachPlacement.cardOnTop(10f, 0f))
        assertFalse(CoachPlacement.cardOnTop(Float.NaN, 1000f))
        assertFalse(CoachPlacement.cardOnTop(-50f, 1000f))
    }
}
