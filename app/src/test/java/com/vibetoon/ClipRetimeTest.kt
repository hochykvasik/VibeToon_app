package com.vibetoon

import com.vibetoon.domain.project.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipRetimeTest {
    @Test fun speedScalesLocalTime() {
        val c = Clip(10, 30, speed = 2f)
        assertEquals(10f, c.animFrame(10), 1e-4f)
        assertEquals(20f, c.animFrame(15), 1e-4f)
        assertEquals(50f, c.animFrame(60), 1e-4f)   // источник 20*2 = 40 кадров, дальше упирается в конец
        assertEquals(5f, Clip(10, 30, speed = 0.5f).animFrame(20) - 10f, 1e-4f)
    }
    @Test fun retimedLengthKeepsSourceTime() {
        assertEquals(10, com.vibetoon.domain.project.retimedLength(20, 1f, 2f, 100))
        assertEquals(40, com.vibetoon.domain.project.retimedLength(20, 1f, 0.5f, 100))
        assertEquals(30, com.vibetoon.domain.project.retimedLength(20, 1f, 0.25f, 30))   // не дальше конца проекта
    }
    @Test fun loopWrapsAndStaysVisible() {
        val c = Clip(10, 30, loop = true)
        assertEquals(10f, c.animFrame(30), 1e-4f)
        assertEquals(15f, c.animFrame(35), 1e-4f)
        assertTrue(c.visibleAt(100)); assertFalse(c.visibleAt(9))
        assertFalse(Clip(10, 30).visibleAt(30))
    }
    @Test fun beforeStartUnchanged() { assertEquals(3f, Clip(10, 30, speed = 4f).animFrame(3), 0f) }
}
