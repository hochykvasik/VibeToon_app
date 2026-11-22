package com.vibetoon

import com.vibetoon.domain.anim.ExportMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMathTest {
    // Стороны кратны 16 (макроблок H.264): 1080 -> 1088, иначе часть аппаратных кодеков даёт зелёные полосы.
    @Test fun landscapeKeepsAspectAndAligns16() {
        assertEquals(1920 to 1088, ExportMath.size(1920, 1080, 1080))
        assertEquals(1280 to 720, ExportMath.size(1920, 1080, 720))
        assertEquals(848 to 480, ExportMath.size(1920, 1080, 480))
    }

    @Test fun portraitScalesByShortSide() {
        val (w, h) = ExportMath.size(880, 1280, 720)
        assertEquals(720, w)
        assertEquals(1040, h)   // 1047.3 -> ближайшее кратное 16
        assertTrue(w % 16 == 0 && h % 16 == 0)
    }

    @Test fun oddResultIsAligned() {
        for ((cw, ch) in listOf(1001 to 777, 1 to 1, 4000 to 3, 1080 to 1350, 2048 to 2048)) for (q in ExportMath.QUALITIES) {
            val (w, h) = ExportMath.size(cw, ch, q)
            assertTrue("$cw x $ch @ $q -> $w x $h", w % 16 == 0 && h % 16 == 0 && w >= 16 && h >= 16)
        }
    }

    @Test fun aspectErrorFromAlignmentIsTiny() {
        for ((cw, ch) in listOf(1920 to 1080, 1080 to 1920, 1080 to 1350, 2000 to 1500)) for (q in ExportMath.QUALITIES) {
            val (w, h) = ExportMath.size(cw, ch, q)
            val err = Math.abs(w.toDouble() / h - cw.toDouble() / ch) / (cw.toDouble() / ch)
            assertTrue("$cw x $ch @ $q: $err", err < 0.035)
        }
    }

    @Test fun encoderSizeShrinksUntilSupported() {
        // кодек принимает максимум 1920 x 1088 и только стороны, кратные 16
        val caps = { w: Int, h: Int -> w <= 1920 && h <= 1088 && w % 16 == 0 && h % 16 == 0 }
        assertEquals(1920 to 1088, ExportMath.encoderSize(1920, 1088, supported = caps))
        val (w, h) = ExportMath.encoderSize(1088, 1936, supported = caps)!!   // портрет 1080p не влезает по высоте
        assertTrue("$w x $h", h <= 1088 && w % 16 == 0 && h % 16 == 0)
        assertEquals(1088.0 / 1936, w.toDouble() / h, 0.03)
        assertEquals(null, ExportMath.encoderSize(640, 480, supported = { _, _ -> false }))
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
