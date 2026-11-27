package com.vibetoon

import com.vibetoon.domain.anim.ExportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMathTest {
    @Test fun exportPresetsAreExplicitPerFormat() {
        // спринт 32: 4K и 1440p, частоты 12-120, GIF до 50 fps
        assertEquals(listOf(2160, 1440, 1080, 720, 480), ExportMath.QUALITIES)
        assertEquals(listOf(480, 360), ExportMath.GIF_QUALITIES)
        assertEquals(listOf(12, 15, 24, 25, 30, 48, 50, 60, 90, 120), ExportMath.FPS_OPTIONS)
        assertEquals(ExportMath.FPS_OPTIONS, ExportMath.SEQUENCE_FPS_OPTIONS)
        assertEquals(listOf(8, 10, 12, 15, 20, 24, 25, 30, 50), ExportMath.GIF_FPS_OPTIONS)
        assertEquals("4K (2160p)", ExportMath.qualityLabel(2160))
        assertEquals("1440p (2K)", ExportMath.qualityLabel(1440))
        assertEquals("720p", ExportMath.qualityLabel(720))
        assertEquals(3840 to 2160, ExportMath.videoSize(1920, 1080, 2160))
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

    @Test fun videoSizeIsMultipleOf16() {
        val (w, h) = ExportMath.videoSize(1920, 1080, 1080)
        assertEquals(1920, w); assertEquals(1072, h)
        assertTrue(w % 16 == 0 && h % 16 == 0)
        val (pw, ph) = ExportMath.videoSize(880, 1280, 720)
        assertEquals(720, pw); assertTrue(ph % 16 == 0)
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
