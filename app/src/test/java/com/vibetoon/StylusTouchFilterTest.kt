package com.vibetoon

import com.vibetoon.domain.input.StylusTouchFilter
import org.junit.Assert.*
import org.junit.Test

class StylusTouchFilterTest {
    @Test fun touchAllowedUntilPenContactAndWhenDisabled() {
        val f = StylusTouchFilter()
        assertFalse(f.blockTouch(true, true, 100))
        f.observePen(true, 200)
        assertTrue(f.blockTouch(true, true, 201))
        assertFalse(f.blockTouch(false, true, 201))
        assertFalse(f.blockTouch(true, false, 201))
    }
    @Test fun guardStartsAtReleaseAndExpires() {
        val f = StylusTouchFilter()
        f.observePen(true, 100)
        f.observePen(true, 500)
        f.observePen(false, 600)
        assertTrue(f.blockTouch(true, true, 600))
        assertTrue(f.blockTouch(true, true, 1799))
        assertFalse(f.blockTouch(true, true, 1800))
        assertFalse(f.blockTouch(true, true, 599))
    }
    @Test fun hoverDoesNotStartOrExtendGuard() {
        val f = StylusTouchFilter()
        f.observePen(false, 100)
        assertFalse(f.blockTouch(true, true, 101))
        f.observePen(true, 200)
        f.observePen(false, 300)
        f.observePen(false, 1000)
        assertFalse(f.blockTouch(true, true, 1500))
    }
    @Test fun newContactRestartsGuard() {
        val f = StylusTouchFilter()
        f.observePen(true, 100)
        f.observePen(false, 200)
        f.observePen(true, 1000)
        f.observePen(false, 1100)
        assertTrue(f.blockTouch(true, true, 2200))
        assertFalse(f.blockTouch(true, true, 2300))
    }
}
