package com.vibetoon

import com.vibetoon.domain.input.MarqueePolicy
import org.junit.Assert.*
import org.junit.Test

class MarqueePolicyTest {
    @Test fun doubleTapRequiresTimeAndProximity() {
        assertTrue(MarqueePolicy.secondTap(100, 400, 10f, 10f, 20f, 10f, 10f))
        assertFalse(MarqueePolicy.secondTap(100, 401, 10f, 10f, 20f, 10f, 10f))
        assertFalse(MarqueePolicy.secondTap(100, 200, 10f, 10f, 31f, 10f, 10f))
        assertFalse(MarqueePolicy.secondTap(-1, 100, 0f, 0f, 0f, 0f, 10f))
        assertFalse(MarqueePolicy.secondTap(100, 200, 0f, 0f, Float.NaN, 0f, 10f))
    }
    @Test fun selectsByCenterInEitherDragDirection() {
        val q = floatArrayOf(0f, 0f, 40f, 10f, 30f, 50f, -10f, 40f)
        assertTrue(MarqueePolicy.containsCenter(q, 10f, 20f, 20f, 30f))
        assertTrue(MarqueePolicy.containsCenter(q, 20f, 30f, 10f, 20f))
        assertFalse(MarqueePolicy.containsCenter(q, 0f, 0f, 5f, 5f))
        assertFalse(MarqueePolicy.containsCenter(floatArrayOf(0f), 0f, 0f, 10f, 10f))
    }
}
