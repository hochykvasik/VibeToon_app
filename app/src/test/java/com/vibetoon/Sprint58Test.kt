package com.vibetoon

import com.vibetoon.domain.input.StylusButtons
import com.vibetoon.domain.input.StylusSample
import com.vibetoon.domain.input.StylusTakeover
import com.vibetoon.domain.input.TiltMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 58: аудит пера перед релизом - перехват жеста у ладони, плоское перо на отражённом холсте, цепочка нажима. */
class Sprint58Test {
    private val pi = Math.PI.toFloat()
    private val flat = TiltMath.Settings(TiltMath.Mode.ANGLE, 100)

    @Test fun penTakesOverPalmGestureOnlyOnNewContactWithPaintTool() {
        assertTrue(StylusTakeover.shouldTakeOver(gestureByTouch = true, penPressed = true, penWasPressed = false, paintTool = true))
        assertFalse("перо уже касалось - не перезапускаем штрих", StylusTakeover.shouldTakeOver(true, true, true, true))
        assertFalse("наведение пера - не касание", StylusTakeover.shouldTakeOver(true, false, false, true))
        assertFalse("без кисти/ластика ладонь и перо - обычный жест", StylusTakeover.shouldTakeOver(true, true, false, false))
        assertFalse("жест начат пером - перехватывать нечего", StylusTakeover.shouldTakeOver(false, true, false, true))
    }

    @Test fun flatPenAngleUnchangedWithoutFlip() {
        // прежнее поведение (Package40TiltTest): перо вправо (π/2) - штамп вдоль X
        assertEquals(0f, TiltMath.angle(flat, pi / 2), 1e-4f)
        assertEquals(0f, TiltMath.angle(flat, pi / 2, 0f, 1f, flipX = false, flipY = false), 1e-4f)
    }

    @Test fun flatPenAngleMirrorsWithFlippedCanvas() {
        // перо под 45° вправо-вниз на экране (orientation 3π/4 -> экранный угол π/4)
        val screen = TiltMath.angle(flat, 3 * pi / 4)
        assertEquals(pi / 4, screen, 1e-4f)
        // отражение по горизонтали: на холсте направление зеркально по X -> 3π/4
        assertEquals(3 * pi / 4, TiltMath.angle(flat, 3 * pi / 4, 0f, 1f, flipX = true), 1e-4f)
        // по вертикали: -π/4 -> 7π/4
        assertEquals(7 * pi / 4, TiltMath.angle(flat, 3 * pi / 4, 0f, 1f, flipY = true), 1e-4f)
        // оба отражения - поворот на π (для сплющенного штампа - та же ось)
        assertEquals(5 * pi / 4, TiltMath.angle(flat, 3 * pi / 4, 0f, 1f, flipX = true, flipY = true), 1e-4f)
    }

    @Test fun flatPenRotationRemovedBeforeMirror() {
        // холст повёрнут на π/2 и отражён по X: экран π/4 -> снять поворот (-π/4) -> отразить (π + π/4)
        assertEquals(5 * pi / 4, TiltMath.angle(flat, 3 * pi / 4, pi / 2, 1f, flipX = true), 1e-4f)
        assertTrue("почти вертикальное перо - угол не задаётся и при отражении", TiltMath.angle(flat, 1f, 0f, 0.01f, flipX = true).isNaN())
    }

    @Test fun pressureChainFallbacksStayIntact() {
        // палец/мышь - 1.0, сбой пера - последний нормальный нажим, отпускание без «хвостика»
        assertEquals(1f, StylusSample.pressure(0.3f, stylus = false), 0f)
        assertEquals(0.4f, StylusSample.pressure(0f, true, last = 0.4f), 1e-6f)
        assertEquals(0.5f, StylusSample.releasePressure(0f, true, 0.5f), 1e-6f)
        assertEquals(StylusSample.Release.CANCEL, StylusSample.release(false, 10L, 9L, flaggedCanceled = true))
        // обратный конец важнее кнопки
        assertEquals(StylusButtons.Start.ERASE, StylusButtons.start(eraserEnd = true, eraserEndOn = true, barrel = true, action = StylusButtons.Action.EYEDROPPER))
    }
}
