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
        assertEquals(com.vibetoon.domain.export.VideoExportPolicy.MAX_AVC_BITRATE, spec(4096, 4096, 60).bitrate)
        assertEquals(4_000_000, spec(320, 240, 24).bitrate)
    }

    /** Спринт 32: HEVC и предел кодировщика доходят до ExportSpec; подсказка частоты по умолчанию = fps. */
    @Test fun specCarriesCodecRateHintAndCap() {
        val avc = ExportSpec(3840, 2160, 30, 30, File("x.mp4"))
        val hevc = ExportSpec(3840, 2160, 30, 30, File("x.mp4"), codec = com.vibetoon.domain.export.VideoCodec.HEVC)
        assertTrue(hevc.bitrate < avc.bitrate)
        assertEquals(30, avc.frameRateHint)
        assertEquals(60, ExportSpec(1920, 1072, 120, 30, File("x.mp4"), rateHint = 60).frameRateHint)
        assertEquals(10_000_000, ExportSpec(3840, 2160, 30, 30, File("x.mp4"), bitrateCap = 10_000_000).bitrate)
    }
}
