package com.vibetoon

import com.vibetoon.domain.export.AudioMix
import com.vibetoon.domain.export.ExportAudio
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.GifDecoder
import com.vibetoon.domain.project.ScrubAudio
import com.vibetoon.domain.project.VideoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Пакет 15: цикл клипа (звук, сведение, видео) и импорт анимированного GIF. */
class Package15LoopGifTest {

    // ------------------------------------------------------------------ цикл клипа

    @Test fun sourceAtLoopsWithOffsetSpeedReverse() {
        val loop = Clip(10, 20, loop = true)
        assertNull(loop.sourceAt(9.0))
        assertEquals(0.0, loop.sourceAt(10.0)!!, 1e-9)
        assertEquals(5.0, loop.sourceAt(25.0)!!, 1e-9)
        assertEquals(5.5, loop.sourceAt(35.5)!!, 1e-9)
        val plain = Clip(10, 20)
        assertEquals(9.0, plain.sourceAt(19.0)!!, 1e-9)
        assertNull(plain.sourceAt(25.0))   // без цикла после конца - тишина
        assertEquals(10.0, Clip(0, 10, speed = 2f, offset = 4, loop = true).sourceAt(13.0)!!, 1e-9)   // 4 + 3 * 2
        val rev = Clip(0, 10, reverse = true)
        assertEquals(10.0, rev.sourceAt(0.0)!!, 1e-9)
        assertEquals(1.0, rev.sourceAt(9.0)!!, 1e-9)
        assertEquals(8.0, rev.copy(loop = true).sourceAt(12.0)!!, 1e-9)
        assertNull(Clip(0, 10, freeze = true, loop = true).sourceAt(3.0))
    }

    @Test fun soundsInAndExportOverlapCountLoopToRangeEnd() {
        assertTrue(Clip(0, 10, loop = true).soundsIn(50, 10))
        assertFalse(Clip(0, 10).soundsIn(50, 10))
        assertFalse(Clip(60, 70, loop = true).soundsIn(50, 10))   // начинается после отрезка
        assertTrue(ExportAudio.overlaps(Clip(0, 40, loop = true), 48, 48))   // сцена 48..96, звук 0..40 по кругу
        assertFalse(ExportAudio.overlaps(Clip(0, 40), 48, 48))
    }

    private val pcm = shortArrayOf(1000, 2000, 3000, 4000, 5000, 6000)

    @Test fun audioMixRepeatsLoopedClip() {
        // частота звука = fps: один сэмпл на кадр
        val looped = ScrubAudio.Source(pcm, 24, 1, Clip(0, 4, loop = true))
        val out = AudioMix.renderAll(listOf(looped), 0, 24, 24, 12)
        for (k in 0 until 12) assertEquals("сэмпл $k", pcm[k % 4], out[k * 2])
        val plain = AudioMix.renderAll(listOf(looped.copy(clip = Clip(0, 4))), 0, 24, 24, 12)
        for (k in 4 until 12) assertEquals(0.toShort(), plain[k * 2])
        assertTrue(AudioMix.audibleIn(listOf(looped), 30, 10))
        assertFalse(AudioMix.audibleIn(listOf(looped.copy(clip = Clip(0, 4))), 30, 10))
    }

    @Test fun scrubPlaysLoopedClipAfterItsEnd() {
        val looped = ScrubAudio.Source(pcm, 24, 1, Clip(0, 4, loop = true))
        val out = ScrubAudio.render(listOf(looped), 9, 24, 24, durationMs = 1000)
        assertEquals(pcm[1], out[0])   // кадр 9 -> 9 % 4 = 1
        assertEquals(pcm[2], out[2])
        val silent = ScrubAudio.render(listOf(looped.copy(clip = Clip(0, 4))), 9, 24, 24, durationMs = 1000)
        assertTrue(silent.all { it == 0.toShort() })
    }

    @Test fun videoTimeWrapsLoopedClip() {
        assertEquals(300L, VideoTime.sourceMs(Clip(0, 10, loop = true), 13f, 10))
        assertEquals(900L, VideoTime.sourceMs(Clip(0, 10), 13f, 10))   // без цикла - последний кадр
    }

    // ------------------------------------------------------------------ GIF

    /** LZW-кодер GIF: рост длины кода повторяет декодер (таблица декодера отстаёт на одну запись). */
    private fun lzwEncode(idx: IntArray, minCode: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        val out = ByteArrayOutputStream()
        var acc = 0; var bits = 0
        var size = minCode + 1
        fun emit(code: Int) {
            acc = acc or (code shl bits); bits += size
            while (bits >= 8) { out.write(acc and 0xFF); acc = acc ushr 8; bits -= 8 }
        }
        val dict = HashMap<String, Int>()
        var encNext = eoi + 1; var decNext = eoi + 1; var emitted = 0
        fun put(code: Int) {
            emit(code)
            if (emitted > 0) { if (decNext < 4096) decNext++; if (decNext == (1 shl size) && size < 12) size++ }
            emitted++
        }
        emit(clear)
        var cur = ""
        var curCode = -1
        for (p in idx) {
            val s = if (cur.isEmpty()) "$p" else "$cur,$p"
            val known = if (cur.isEmpty()) p else dict[s]
            if (known != null) { cur = s; curCode = known; continue }
            put(curCode)
            if (encNext < 4096) dict[s] = encNext++
            cur = "$p"; curCode = p
        }
        if (curCode >= 0) put(curCode)
        emit(eoi)
        if (bits > 0) out.write(acc and 0xFF)
        return out.toByteArray()
    }

    private class Frame(val x: Int, val y: Int, val w: Int, val h: Int, val idx: IntArray, val delayCs: Int, val disposal: Int = 1, val transparent: Int = -1, val interlaced: Boolean = false)

    private val red = 0xFFFF0000.toInt(); private val green = 0xFF00FF00.toInt(); private val blue = 0xFF0000FF.toInt(); private val white = -1

    /** GIF89a с глобальной палитрой из 4 цветов: 0 красный, 1 зелёный, 2 синий, 3 белый. */
    private fun gif(w: Int, h: Int, frames: List<Frame>, loops: Int = 0): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v and 0xFF); o.write(v shr 8) }
        o.write("GIF89a".toByteArray(Charsets.US_ASCII)); u16(w); u16(h); o.write(0x81); o.write(0); o.write(0)
        for (c in listOf(red, green, blue, white)) { o.write((c shr 16) and 0xFF); o.write((c shr 8) and 0xFF); o.write(c and 0xFF) }
        o.write(0x21); o.write(0xFF); o.write(11); o.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII)); o.write(3); o.write(1); u16(loops); o.write(0)
        for (f in frames) {
            o.write(0x21); o.write(0xF9); o.write(4)
            o.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0)); u16(f.delayCs); o.write(maxOf(f.transparent, 0)); o.write(0)
            o.write(0x2C); u16(f.x); u16(f.y); u16(f.w); u16(f.h); o.write(if (f.interlaced) 0x40 else 0)
            o.write(2)
            val data = lzwEncode(f.idx, 2)
            var p = 0
            while (p < data.size) { val n = minOf(255, data.size - p); o.write(n); o.write(data, p, n); p += n }
            o.write(0)
        }
        o.write(0x3B)
        return o.toByteArray()
    }

    private val sample = listOf(
        Frame(0, 0, 2, 2, intArrayOf(0, 1, 2, 3), delayCs = 10),
        Frame(1, 0, 1, 1, intArrayOf(2), delayCs = 0, disposal = 2),                  // 0 -> 100 мс; после показа - очистка
        Frame(0, 1, 2, 1, intArrayOf(3, 1), delayCs = 5, disposal = 3, transparent = 3), // после показа - возврат
        Frame(0, 0, 1, 1, intArrayOf(1), delayCs = 20)
    )

    private fun decodeAll(bytes: ByteArray, maxPixels: Int = GifDecoder.MAX_PIXELS): Pair<GifDecoder.Info, List<Pair<List<Int>, Int>>> {
        val frames = ArrayList<Pair<List<Int>, Int>>()
        var size = 0 to 0
        val info = GifDecoder.decode(ByteArrayInputStream(bytes), maxPixels = maxPixels, onSize = { w, h -> size = w to h }) { _, c, d -> frames += c.toList() to d }
        assertEquals(info.width to info.height, size)
        return info to frames
    }

    @Test fun decodesFramesWithDisposalTransparencyAndDelays() {
        val (info, frames) = decodeAll(gif(2, 2, sample, loops = 0))
        assertEquals(2, info.width); assertEquals(2, info.height)
        assertEquals(4, info.frames); assertEquals(450L, info.totalMs); assertEquals(0, info.loops)
        assertEquals(listOf(100, 100, 50, 200), frames.map { it.second })
        assertEquals(listOf(red, green, blue, white), frames[0].first)
        assertEquals(listOf(red, blue, blue, white), frames[1].first)
        assertEquals(listOf(red, 0, blue, green), frames[2].first)   // очистка кадра 1 + прозрачный пиксель кадра 2
        assertEquals(listOf(green, 0, blue, white), frames[3].first) // кадр 2 убран (disposal 3)
    }

    @Test fun lzwRoundTripsLongDataWithCodeGrowth() {
        val rnd = java.util.Random(15)
        for (minCode in listOf(2, 8)) {
            val n = 5000
            val idx = IntArray(n) { if (it % 7 < 3) 1 else rnd.nextInt(1 shl minCode) }
            val stream = lzwEncode(idx, minCode)
            val back = GifDecoder.lzw(stream, minCode, n)
            assertEquals(idx.toList(), back.map { it.toInt() and 0xFF })
        }
        // недостающие данные: остаток кадра - индекс 0, без исключения
        assertEquals(List(4) { 0 }, GifDecoder.lzw(ByteArray(0), 2, 4).map { it.toInt() })
    }

    @Test fun interlacedRowsAndFrame() {
        assertEquals(listOf(0, 4, 2, 6, 1, 3, 5, 7), GifDecoder.interlaceRows(8).toList())
        assertEquals(listOf(0, 4, 2, 1, 3), GifDecoder.interlaceRows(5).toList())
        val (_, frames) = decodeAll(gif(1, 4, listOf(Frame(0, 0, 1, 4, intArrayOf(0, 1, 2, 3), 10, interlaced = true))))
        assertEquals(listOf(red, blue, green, white), frames[0].first)   // прочитано 0,1,2,3 -> строки 0,2,1,3
    }

    @Test fun truncatedTailKeepsFramesAndBadFilesFail() {
        val full = gif(2, 2, sample)
        val (info, _) = decodeAll(full.copyOf(full.size - 12))
        assertTrue(info.frames in 1..3)
        assertFalse(GifDecoder.isGif("PNG...".toByteArray()))
        assertTrue(GifDecoder.isGif(full))
        var notGif = false
        try { decodeAll("hello world".toByteArray()) } catch (e: GifDecoder.GifException) { notGif = true }
        assertTrue(notGif)
        var tooBig = false
        try { decodeAll(full, maxPixels = 3) } catch (e: GifDecoder.GifException) { tooBig = true }
        assertTrue(tooBig)
        val limited = GifDecoder.decode(ByteArrayInputStream(full), maxFrames = 2) { _, _, _ -> }
        assertEquals(2, limited.frames)
    }

    @Test fun delaysAndTimeline() {
        assertEquals(100, GifDecoder.effectiveDelayMs(0))
        assertEquals(100, GifDecoder.effectiveDelayMs(1))
        assertEquals(20, GifDecoder.effectiveDelayMs(2))
        assertEquals(listOf(0 to 0, 1 to 2, 2 to 5, 3 to 6) to 11, GifDecoder.timeline(listOf(100, 100, 50, 200), 24))
        // GIF быстрее 24 fps: лишние кадры пропускаются, длина не меньше последнего кадра + 1
        assertEquals(listOf(0 to 0, 3 to 1, 7 to 2) to 3, GifDecoder.timeline(List(10) { 10 }, 24))
    }

    @Test fun placerMatchesTimeline() {
        for (delays in listOf(listOf(100, 100, 50, 200), List(10) { 10 }, listOf(40, 40, 40), listOf(1000))) {
            val p = GifDecoder.Placer(24)
            val placed = delays.mapIndexedNotNull { i, d -> p.place(d)?.let { i to it } }
            val (line, total) = GifDecoder.timeline(delays, 24)
            assertEquals(line, placed)
            assertEquals(total, p.total)
            assertEquals(line.size, p.placed)
        }
    }
}
