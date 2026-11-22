package com.vibetoon

import com.vibetoon.domain.project.SymbolMath
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolHoldTest {
    @Test fun staticSymbolHoldsFirstFrame() {
        // Неанимированный символ: никакой самопроизвольной анимации, кадр 0 на любом глобальном
        assertEquals(0, SymbolMath.defaultFrame(0, 1, false))
        assertEquals(0, SymbolMath.defaultFrame(37, 1, false))
        assertEquals(0, SymbolMath.defaultFrame(999, 24, false))
    }

    @Test fun animatedSymbolLoops() {
        assertEquals(0, SymbolMath.defaultFrame(0, 12, true))
        assertEquals(5, SymbolMath.defaultFrame(17, 12, true))
        assertEquals(0, SymbolMath.defaultFrame(24, 12, true))
        assertEquals(0, SymbolMath.defaultFrame(7, 0, true))
    }

    @Test fun singleKeyOrEqualKeysAreStatic() {
        val k = com.vibetoon.domain.anim.Keyframe(0, 1f, 2f, 1f, 1f, 0f)
        assertEquals(false, SymbolMath.trackMoves(listOf(k)))
        assertEquals(false, SymbolMath.trackMoves(listOf(k, k.copy(frame = 10))))
        assertEquals(true, SymbolMath.trackMoves(listOf(k, k.copy(frame = 10, dx = 5f))))
    }

    @Test fun singleChosenFormHoldsEverywhere() {
        val keys = listOf(com.vibetoon.domain.project.TimeKey(12, 3))
        assertEquals(false, SymbolMath.timeKeysMove(keys))
        assertEquals(3, SymbolMath.timeAt(keys, 0))
        assertEquals(3, SymbolMath.timeAt(keys, 500))
        assertEquals(true, SymbolMath.timeKeysMove(keys + com.vibetoon.domain.project.TimeKey(20, 5)))
    }
}
