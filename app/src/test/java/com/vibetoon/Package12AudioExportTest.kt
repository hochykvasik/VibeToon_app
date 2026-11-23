package com.vibetoon

import com.vibetoon.domain.export.AudioMix
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.ImportLimit
import com.vibetoon.domain.project.ScrubAudio
import com.vibetoon.domain.project.VolKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Пакет 12: сведение звука для MP4/WAV (синхронизация, сцена, клип, громкость), переплетение дорожек и Resolution Limit. */
class Package12AudioExportTest {
    private val rate = 44100
    private val fps = 30

    /** Моно-«пила»: значение сэмпла = его номер (до 30000), по нему видно, какой момент источника звучит. */
    private fun ramp(n: Int = rate * 3) = ShortArray(n) { (it % 30000).toShort() }
    private fun src(clip: Clip, vol: List<VolKey> = emptyList(), data: ShortArray = ramp()) = ScrubAudio.Source(data, rate, 1, clip, vol)

    private fun near(expected: Int, actual: Short) = assertTrue("ожидалось ~$expected, получено $actual", abs(expected - actual) <= 1)

    @Test fun lengthMatchesVideo() {
        assertEquals(44100, AudioMix.sampleCount(30, 30, rate))
        assertEquals(44100, AudioMix.sampleCount(24, 24, rate))
        assertEquals(88200, AudioMix.sampleCount(48, 24, rate))
        assertEquals(1_000_000L, AudioMix.ptsUs(44100, rate))
        assertEquals(0L, AudioMix.ptsUs(0, rate))
    }

    @Test fun clipStartsOnItsTimelineFrame() {
        val s = src(Clip(10, 40))
        val total = AudioMix.sampleCount(60, fps, rate)
        val out = AudioMix.renderAll(listOf(s), 0, fps, rate, total)
        // до начала клипа (кадр 10 = 14700-й сэмпл) - тишина
        assertEquals(0.toShort(), out[2 * 14000])
        // кадр 10.3 -> 0.3 кадра от начала клипа -> 441-й сэмпл источника, в обоих каналах (моно -> стерео)
        near(441, out[2 * 15141]); near(441, out[2 * 15141 + 1])
        // после конца клипа (кадр 40) - тишина
        assertEquals(0.toShort(), out[2 * 59000])
    }

    @Test fun sceneExportStartsAtSceneFrame() {
        val s = src(Clip(10, 40))
        val total = AudioMix.sampleCount(30, fps, rate)
        // экспорт сцены с кадра 10: первый сэмпл файла = кадр 10 проекта
        val out = AudioMix.renderAll(listOf(s), 10, fps, rate, total)
        near(441, out[2 * 441])
        // кусками - то же самое, что целиком (кодек получает сведение порциями)
        val part = AudioMix.render(listOf(s), 10, fps, rate, 400, 100, total)
        for (j in 0 until 100) assertEquals(out[2 * (400 + j)], part[2 * j])
    }

    @Test fun speedReverseFreezeAndVolume() {
        val total = AudioMix.sampleCount(30, fps, rate)
        val fast = AudioMix.renderAll(listOf(src(Clip(0, 30, speed = 2f))), 0, fps, rate, total)
        near(882, fast[2 * 441])
        val frozen = AudioMix.renderAll(listOf(src(Clip(0, 30, freeze = true))), 0, fps, rate, total)
        assertTrue(frozen.all { it.toInt() == 0 })
        val silent = AudioMix.renderAll(listOf(src(Clip(0, 30), listOf(VolKey(0, 0f)))), 0, fps, rate, total)
        assertTrue(silent.all { it.toInt() == 0 })
        val half = AudioMix.renderAll(listOf(src(Clip(0, 30), listOf(VolKey(0, 0.5f)))), 0, fps, rate, total)
        near(1000, half[2 * 2000])
        // реверс: в начале клипа звучит конец источника (offset + length * speed = 30 кадров = 44100-й сэмпл)
        val rev = AudioMix.renderAll(listOf(src(Clip(0, 30, reverse = true))), 0, fps, rate, total)
        near((44100 - 2000) % 30000, rev[2 * 2000])
    }

    @Test fun edgesFadeWithoutClick() {
        val data = ShortArray(rate * 2) { 20000 }
        val total = AudioMix.sampleCount(30, fps, rate)
        val out = AudioMix.renderAll(listOf(src(Clip(0, 60), data = data)), 0, fps, rate, total)
        assertTrue(abs(out[0].toInt()) < 200)
        assertTrue(abs(out[2 * (total - 1)].toInt()) < 200)
        assertEquals(20000, out[2 * (total / 2)].toInt())
    }

    @Test fun tracksAreSummedAndClamped() {
        val loud = ShortArray(rate * 2) { 30000 }
        val total = AudioMix.sampleCount(30, fps, rate)
        val out = AudioMix.renderAll(listOf(src(Clip(0, 60), data = loud), src(Clip(0, 60), data = loud)), 0, fps, rate, total)
        assertEquals(32767, out[2 * 1000].toInt())
    }

    @Test fun audibleOnlyInsideRange() {
        val s = listOf(src(Clip(100, 150)))
        assertFalse(AudioMix.audibleIn(s, 0, 100))
        assertTrue(AudioMix.audibleIn(s, 120, 10))
        assertFalse(AudioMix.audibleIn(listOf(src(Clip(0, 50), listOf(VolKey(0, 0f)))), 0, 50))
        assertFalse(AudioMix.audibleIn(emptyList(), 0, 50))
    }

    @Test fun interleaveKeepsTimeOrder() {
        val order = AudioMix.interleave(listOf(0L, 33_333L, 66_666L), listOf(0L, 23_219L, 46_439L, 69_659L))
        assertEquals(listOf(false to 0, true to 0, false to 1, true to 1, false to 2, true to 2, false to 3), order)
    }

    @Test fun resolutionLimit() {
        assertEquals(1920, ImportLimit.maxSide(1920, 1080))
        assertEquals(512, ImportLimit.maxSide(200, 300))
        assertEquals(2048, ImportLimit.maxSide(4000, 3000))
        assertFalse(ImportLimit.needsDownscale(1920, 1080, 1920))
        assertTrue(ImportLimit.needsDownscale(4000, 3000, 1920))
        assertEquals(1920 to 1440, ImportLimit.scaled(4000, 3000, 1920))
        assertEquals(1 to 1920, ImportLimit.scaled(2, 8000, 1920))
        assertEquals(800 to 600, ImportLimit.scaled(800, 600, 1920))
    }
}
