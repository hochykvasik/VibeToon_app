package com.vibetoon

import com.vibetoon.render.ExportSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQualityTest {
    private fun spec(w: Int, h: Int, fps: Int) = ExportSpec(w, h, fps, 30, File("x.mp4"))

    @Test fun bitrate720p30IsHigh() {
        val b = spec(1280, 720, 30).bitrate
        assertTrue("720p30 bitrate $b too low", b >= 6_000_000)
        assertTrue(b <= 8_000_000)
    }

    @Test fun bitrateScalesAndClamps() {
        assertTrue(spec(1920, 1080, 60).bitrate > spec(1280, 720, 30).bitrate)
        assertEquals(40_000_000, spec(4096, 4096, 60).bitrate)
        assertEquals(4_000_000, spec(320, 240, 24).bitrate)
    }
}
