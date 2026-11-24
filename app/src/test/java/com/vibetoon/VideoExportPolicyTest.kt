package com.vibetoon

import com.vibetoon.domain.export.CodecSupport
import com.vibetoon.domain.export.VideoCodec
import com.vibetoon.domain.export.VideoExportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 32: выбор кодека и размера MP4 (4K, HEVC) по возможностям устройства, битрейт и уровень H.264. */
class VideoExportPolicyTest {
    /** Устройство-таблица: предел ширины/высоты (в любой ориентации) по кодеку и предел fps. */
    private class Device(
        val avc: Pair<Int, Int>?, val hevc: Pair<Int, Int>?, val fps: Double? = null, val cap: Int? = null
    ) : CodecSupport {
        private fun lim(c: VideoCodec) = if (c == VideoCodec.AVC) avc else hevc
        override fun supports(codec: VideoCodec, width: Int, height: Int): Boolean {
            val (mw, mh) = lim(codec) ?: return false
            return (width <= mw && height <= mh) || (width <= mh && height <= mw)
        }
        override fun maxFps(codec: VideoCodec, width: Int, height: Int): Double? = fps
        override fun maxBitrate(codec: VideoCodec): Int? = cap
    }

    private val flagship = Device(3840 to 2160, 3840 to 2160)
    private val midrange = Device(1920 to 1088, 1920 to 1088)
    private val noHevc = Device(3840 to 2160, null)
    private val hevc1080 = Device(3840 to 2160, 1920 to 1088)

    @Test fun uhdOnCapableDeviceStaysUhd() {
        val p = VideoExportPolicy.plan(1920, 1080, 2160, 30, VideoCodec.HEVC, flagship)
        assertEquals(VideoCodec.HEVC, p.codec)
        assertEquals(3840, p.width); assertEquals(2160, p.height)
        assertEquals(2160, p.shortSide)
        assertTrue(p.notes.isEmpty())
        assertTrue(p.possible)
    }

    @Test fun uhdFallsBackToLargestSupportedSizeWithNote() {
        val p = VideoExportPolicy.plan(1920, 1080, 2160, 30, VideoCodec.AVC, midrange)
        assertEquals(1080, p.shortSide)
        assertEquals(1920, p.width); assertEquals(1072, p.height)   // 1080 -> кратно 16
        assertEquals(1, p.notes.size)
        assertTrue(p.notes[0], p.notes[0].startsWith("Устройство не кодирует 3840×2160 (H.264): видео будет 1920×1072"))
    }

    @Test fun hevcMissingFallsBackToAvcSameSize() {
        val p = VideoExportPolicy.plan(1920, 1080, 1080, 60, VideoCodec.HEVC, noHevc)
        assertEquals(VideoCodec.AVC, p.codec)
        assertEquals(1080, p.shortSide)
        assertEquals(listOf("На устройстве нет кодировщика HEVC: видео будет в H.264"), p.notes)
    }

    @Test fun hevcTooSmallForUhdPrefersAvcUhdOverSmallerHevc() {
        val p = VideoExportPolicy.plan(1920, 1080, 2160, 24, VideoCodec.HEVC, hevc1080)
        assertEquals(VideoCodec.AVC, p.codec)
        assertEquals(3840, p.width)
        assertEquals(listOf("HEVC на этом устройстве не берёт 3840×2160: видео будет в H.264"), p.notes)
    }

    @Test fun avcNeverSwitchesToHevc() {
        val p = VideoExportPolicy.plan(1920, 1080, 2160, 30, VideoCodec.AVC, Device(1280 to 720, 3840 to 2160))
        assertEquals(VideoCodec.AVC, p.codec)
        assertEquals(720, p.shortSide)
    }

    @Test fun portraitUsesRotatedLimits() {
        val p = VideoExportPolicy.plan(1080, 1920, 2160, 30, VideoCodec.AVC, flagship)
        assertEquals(2160, p.width); assertEquals(3840, p.height)
        assertTrue(p.notes.isEmpty())
    }

    @Test fun noEncoderAtAllIsReportedNotCrashed() {
        val p = VideoExportPolicy.plan(1920, 1080, 1080, 30, VideoCodec.AVC, Device(null, null))
        assertFalse(p.possible)
        assertEquals(listOf("На устройстве нет кодировщика видео для этого размера"), p.notes)
    }

    @Test fun fpsIsNeverLoweredOnlyHinted() {
        val p = VideoExportPolicy.plan(1920, 1080, 2160, 120, VideoCodec.AVC, Device(3840 to 2160, null, fps = 60.0))
        assertEquals(120, p.fps)
        assertEquals(60, p.rateHint)
        assertTrue(p.notes.single().startsWith("Кодировщик заявляет до 60 fps при 3840×2160: файл записан с 120 fps"))
        val q = VideoExportPolicy.plan(1920, 1080, 1080, 24, VideoCodec.AVC, Device(3840 to 2160, null, fps = 60.0))
        assertEquals(24, q.rateHint)
        assertTrue(q.notes.isEmpty())
    }

    @Test fun fpsClampedToRange() {
        assertEquals(120, VideoExportPolicy.plan(1920, 1080, 720, 500, VideoCodec.AVC, flagship).fps)
        assertEquals(1, VideoExportPolicy.plan(1920, 1080, 720, 0, VideoCodec.AVC, flagship).fps)
    }

    @Test fun bitrateScalesWithCodecAndCaps() {
        val avc4k = VideoExportPolicy.bitrate(3840, 2160, 30, VideoCodec.AVC)
        val hevc4k = VideoExportPolicy.bitrate(3840, 2160, 30, VideoCodec.HEVC)
        assertEquals(62_208_000, avc4k)                       // 0.25 бит/пиксель/кадр
        assertEquals(37_324_800, hevc4k)                      // 60% от H.264
        assertEquals(VideoExportPolicy.MAX_AVC_BITRATE, VideoExportPolicy.bitrate(3840, 2160, 120, VideoCodec.AVC))
        assertEquals(VideoExportPolicy.MAX_HEVC_BITRATE, VideoExportPolicy.bitrate(3840, 2160, 120, VideoCodec.HEVC))
        assertEquals(VideoExportPolicy.MIN_BITRATE, VideoExportPolicy.bitrate(320, 240, 1))
        assertEquals(20_000_000, VideoExportPolicy.bitrate(3840, 2160, 30, VideoCodec.AVC, cap = 20_000_000))   // предел кодировщика
        assertEquals(2_000_000, VideoExportPolicy.bitrate(320, 240, 24, VideoCodec.AVC, cap = 2_000_000))       // предел ниже минимума
        val plan = VideoExportPolicy.plan(1920, 1080, 2160, 30, VideoCodec.AVC, Device(3840 to 2160, null, cap = 30_000_000))
        assertEquals(30_000_000, plan.bitrate)
    }

    @Test fun avcLevelFollowsStandardLimits() {
        assertEquals(31, VideoExportPolicy.avcLevel(1280, 720, 30))
        assertEquals(40, VideoExportPolicy.avcLevel(1920, 1088, 30))
        assertEquals(42, VideoExportPolicy.avcLevel(1920, 1088, 60))
        assertEquals(51, VideoExportPolicy.avcLevel(3840, 2160, 30))
        assertEquals(52, VideoExportPolicy.avcLevel(3840, 2160, 60))
        assertEquals(51, VideoExportPolicy.avcLevel(1920, 1088, 120))
        assertEquals(60, VideoExportPolicy.avcLevel(3840, 2160, 120))
        // битрейт тоже ограничивает уровень: 1080p30 c 40 Мбит/с не влезает в 4.0 (25 Мбит/с High)
        assertEquals(41, VideoExportPolicy.avcLevel(1920, 1088, 30, 40_000_000))
    }
}
