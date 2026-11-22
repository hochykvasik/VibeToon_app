package com.vibetoon

import com.vibetoon.domain.anim.ExportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMathTest {
    @Test fun exportPresetsAreExplicitPerFormat() {
        assertEquals(listOf(1080, 720, 480), ExportMath.QUALITIES)
        assertEquals(listOf(480, 360), ExportMath.GIF_QUALITIES)
        assertEquals(listOf(24, 30, 60), ExportMath.FPS_OPTIONS)
        assertEquals(ExportMath.FPS_OPTIONS, ExportMath.SEQUENCE_FPS_OPTIONS)
        assertEquals(listOf(12, 24, 30), ExportMath.GIF_FPS_OPTIONS)
    }

    @Test fun landscapeKeepsAspectAndEven() {
        assertEquals(1920 to 1080, ExportMath.size(1920, 1080, 1080))
        assertEquals(1280 to 720, ExportMath.size(1920, 1080, 720))
    }

    @Test fun portraitScalesByShortSide() {
        val (w, h) = ExportMath.size(880, 1280, 720)
        assertEquals(720, w)
        assertEquals(1047 / 2 * 2, h)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun oddResultIsMadeEven() {
        val (w, h) = ExportMath.size(1001, 777, 480)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun frameCountFollowsDuration() {
        assertEquals(120, ExportMath.frameCount(120, 24, 24))
        assertEquals(150, ExportMath.frameCount(120, 24, 30))
        assertEquals(300, ExportMath.frameCount(120, 24, 60))
    }

    @Test fun projectFrameMapsAndStaysInRange() {
        assertEquals(0, ExportMath.projectFrame(0, 24, 60, 120))
        assertEquals(12, ExportMath.projectFrame(30, 24, 60, 120))
        assertEquals(119, ExportMath.projectFrame(299, 24, 60, 120))
        assertEquals(119, ExportMath.projectFrame(5000, 24, 60, 120))
    }
}
