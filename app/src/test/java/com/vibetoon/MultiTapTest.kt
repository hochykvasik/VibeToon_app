package com.vibetoon

import com.vibetoon.domain.MultiTap
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiTapTest {
    @Test fun shortStillTwoAndThreeFingerTaps() {
        assertEquals(2, MultiTap.decide(2, 120, 4f, 18f))
        assertEquals(3, MultiTap.decide(3, 200, 0f, 18f))
    }

    @Test fun pinchPanLongOrSingleAreNotTaps() {
        assertEquals(0, MultiTap.decide(2, 120, 60f, 18f))    // щипок/сдвиг камеры
        assertEquals(0, MultiTap.decide(2, 900, 2f, 18f))     // долгое удержание
        assertEquals(0, MultiTap.decide(1, 100, 0f, 18f))     // один палец
        assertEquals(0, MultiTap.decide(4, 100, 0f, 18f))     // четыре и больше
    }
}
