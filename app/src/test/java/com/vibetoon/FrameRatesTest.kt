package com.vibetoon

import com.vibetoon.domain.anim.ExportMath
import com.vibetoon.domain.anim.FrameRates
import com.vibetoon.domain.anim.PlaybackMath
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.export.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Спринт 32: частота 1–120 - пределы, пресеты, воспроизведение, экспорт и GIF. */
class FrameRatesTest {
    @Test fun limitsMatchTimeFormatAndClamp() {
        assertEquals(1, FrameRates.MIN)
        assertEquals(120, FrameRates.MAX)
        assertEquals(TimeFormat.MAX_FPS, FrameRates.MAX)
        assertEquals(1, FrameRates.clamp(0))
        assertEquals(1, FrameRates.clamp(-5))
        assertEquals(120, FrameRates.clamp(240))
        assertEquals(37, FrameRates.clamp(37))
    }

    @Test fun parseAcceptsOnlyOneToHundredTwenty() {
        assertEquals(1, FrameRates.parse("1"))
        assertEquals(120, FrameRates.parse(" 120 "))
        assertEquals(7, FrameRates.parse("7"))
        assertNull(FrameRates.parse("0"))
        assertNull(FrameRates.parse("121"))
        assertNull(FrameRates.parse(""))
        assertNull(FrameRates.parse("2x"))
    }

    @Test fun presetsCoverCommonRatesAndStayInRange() {
        for (f in listOf(12, 24, 25, 30, 50, 60, 120)) assertTrue("project $f", f in FrameRates.PROJECT_PRESETS)
        for (f in listOf(12, 15, 24, 25, 30, 48, 50, 60, 90, 120)) assertTrue("export $f", f in FrameRates.EXPORT_PRESETS)
        assertTrue((FrameRates.PROJECT_PRESETS + FrameRates.EXPORT_PRESETS).all { it in 1..120 })
        assertTrue(FrameRates.GIF_PRESETS.all { it in 1..FrameRates.GIF_MAX })
        assertEquals(FrameRates.PROJECT_PRESETS.sorted(), FrameRates.PROJECT_PRESETS)
        assertEquals(FrameRates.EXPORT_PRESETS.sorted(), FrameRates.EXPORT_PRESETS)
    }

    @Test fun exportOptionsIncludeUnusualProjectRate() {
        val o = FrameRates.exportOptions(7)
        assertTrue(7 in o)
        assertEquals(o.sorted(), o)
        assertEquals(o.distinct(), o)
        assertEquals(FrameRates.EXPORT_PRESETS, FrameRates.exportOptions(24))   // 24 уже есть - без повтора
        assertTrue(120 in FrameRates.exportOptions(500))                        // мусор в проекте -> предел
        assertTrue(1 in FrameRates.exportOptions(1))
    }

    @Test fun gifOptionsNeverExceedFifty() {
        assertTrue(FrameRates.gifOptions(60).all { it <= 50 })
        assertTrue(7 in FrameRates.gifOptions(7))
        assertFalse(120 in FrameRates.gifOptions(120))
        assertEquals(50, FrameRates.defaultExport(120, gif = true))
        assertEquals(24, FrameRates.defaultExport(24, gif = true))
        assertEquals(120, FrameRates.defaultExport(120))
    }

    @Test fun gifDelaysSumToExactSeconds() {
        for (fps in listOf(1, 7, 12, 15, 24, 25, 30, 33, 48, 50)) {
            val sum = (0 until fps).sumOf { FrameRates.gifDelayCs(it, fps) }
            assertEquals("fps $fps", 100, sum)
            assertTrue((0 until fps).all { FrameRates.gifDelayCs(it, fps) >= 2 })
        }
        // 24 fps: начала кадров 0, 4, 8, 13 (12,5), 17, 21, 25 сотых -> задержки 4, 4, 5, 4, 4, 4
        assertEquals(listOf(4, 4, 5, 4, 4, 4), (0 until 6).map { FrameRates.gifDelayCs(it, 24) })
        assertEquals(2, FrameRates.gifDelayCs(0, 50))
        assertEquals(2, FrameRates.gifDelayCs(0, 120))   // выше 50 -> как 50
        assertEquals(100, FrameRates.gifDelayCs(0, 1))
    }

    @Test fun gifEncoderWritesPerFrameDelays() {
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, 2, 2, 24, false)
        repeat(24) { enc.addFrame(IntArray(4) { 0xFF336699.toInt() }) }
        enc.finish()
        val b = bos.toByteArray()
        val delays = ArrayList<Int>()
        for (i in 0 until b.size - 5) if (b[i] == 0x21.toByte() && b[i + 1] == 0xF9.toByte() && b[i + 2] == 4.toByte())
            delays += (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8)
        assertEquals(24, delays.size)
        assertEquals(100, delays.sum())
    }

    @Test fun playbackIsTimeBasedForAnyRate() {
        // 120 fps: за 1 с - 120 кадров; 1 fps - один кадр в секунду; 7 fps - 7
        assertEquals(120, PlaybackMath.frameAt(1_000_000_000L, 120, 0, 1000, loop = false))
        assertEquals(1, PlaybackMath.frameAt(1_000_000_000L, 1, 0, 1000, loop = false))
        assertEquals(0, PlaybackMath.frameAt(999_000_000L, 1, 0, 1000, loop = false))
        assertEquals(7, PlaybackMath.frameAt(1_000_000_000L, 7, 0, 1000, loop = false))
        assertEquals(5, PlaybackMath.frameAt(1_000_000_000L, 120, 5, 120, loop = true))   // цикл 120 кадров
    }

    @Test fun displayLimitNoteOnlyAboveRefresh() {
        assertTrue(FrameRates.exceedsDisplay(120, 60f))
        assertFalse(FrameRates.exceedsDisplay(120, 120f))
        assertFalse(FrameRates.exceedsDisplay(60, 60f))
        assertEquals(90, FrameRates.shownPerSecond(120, 90f))
        assertEquals(24, FrameRates.shownPerSecond(24, 144f))
        assertEquals(60, FrameRates.shownPerSecond(120, Float.NaN))   // нет данных об экране -> 60 Гц
    }

    @Test fun exportFrameMappingWorksAcrossRates() {
        // проект 12 fps, 24 кадра (2 с) -> 120 fps = 240 кадров; кадр видео 239 -> последний кадр проекта
        assertEquals(240, ExportMath.frameCount(24, 12, 120))
        assertEquals(23, ExportMath.projectFrame(239, 12, 120, 24))
        assertEquals(10, ExportMath.projectFrame(100, 12, 120, 24))
        // проект 120 fps -> 1 fps: 2 кадра за 2 с
        assertEquals(2, ExportMath.frameCount(240, 120, 1))
        assertEquals(120, ExportMath.projectFrame(1, 120, 1, 240))
        // 7 fps -> 25 fps
        assertEquals(25, ExportMath.frameCount(7, 7, 25))
    }

    @Test fun projectFileFpsIsClampedOnLoadAndRoundTrips() {
        val hi = com.vibetoon.domain.project.ProjectJson.decode("""{"version":1,"id":"p","fps":500}""").getOrThrow()
        assertEquals(120, hi.meta.fps)
        val lo = com.vibetoon.domain.project.ProjectJson.decode("""{"version":1,"id":"p","fps":0}""").getOrThrow()
        assertEquals(1, lo.meta.fps)
        for (f in listOf(1, 7, 25, 48, 90, 120)) {
            val d = com.vibetoon.domain.project.ProjectJson.decode("""{"version":1,"id":"p","fps":$f}""").getOrThrow()
            val again = com.vibetoon.domain.project.ProjectJson.decode(com.vibetoon.domain.project.ProjectJson.encode(d)).getOrThrow()
            assertEquals(f, again.meta.fps)
            assertEquals(f, again.animation().settings.fps)
        }
    }
}
