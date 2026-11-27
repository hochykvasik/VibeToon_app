package com.vibetoon

import com.vibetoon.domain.export.AudioMix
import com.vibetoon.domain.export.MovWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Спринт 32: MOV с альфа-каналом (PNG в QuickTime) и звуком PCM. Файл разбирается обратно по атомам:
 * структура, таблицы сэмплов и смещения указывают ровно на записанные PNG и звук.
 */
class MovWriterTest {
    /** Минимальный PNG RGBA 8 бит (фильтр 0) - настоящий файл, который откроет любой декодер. */
    private fun png(w: Int, h: Int, argb: (Int, Int) -> Int): ByteArray {
        fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
            out.writeInt(data.size); val t = type.toByteArray(); out.write(t); out.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); out.writeInt(crc.value.toInt())
        }
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) { val c = argb(x, y); raw.write(c shr 16 and 0xFF); raw.write(c shr 8 and 0xFF); raw.write(c and 0xFF); raw.write(c ushr 24) }
        }
        val z = ByteArrayOutputStream(); DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val bos = ByteArrayOutputStream(); val out = DataOutputStream(bos)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr); chunk(out, "IDAT", z.toByteArray()); chunk(out, "IEND", ByteArray(0))
        return bos.toByteArray()
    }

    private class Atom(val type: String, val start: Long, val size: Long, val header: Int)

    private fun atoms(b: ByteArray, from: Long, to: Long): List<Atom> {
        val out = ArrayList<Atom>(); var p = from
        while (p + 8 <= to) {
            var size = ByteBuffer.wrap(b, p.toInt(), 4).int.toLong() and 0xFFFFFFFFL
            val type = String(b, p.toInt() + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) { size = ByteBuffer.wrap(b, p.toInt() + 8, 8).long; header = 16 }
            assertTrue("atom $type size $size", size >= header && p + size <= to)
            out += Atom(type, p, size, header); p += size
        }
        assertEquals("атомы не выходят за границу", to, p)
        return out
    }

    private fun child(b: ByteArray, parent: Atom, vararg path: String): Atom {
        var cur = parent
        for (t in path) cur = atoms(b, cur.start + cur.header, cur.start + cur.size).first { it.type == t }
        return cur
    }

    private fun int(b: ByteArray, at: Long) = ByteBuffer.wrap(b, at.toInt(), 4).int
    private fun short(b: ByteArray, at: Long) = ByteBuffer.wrap(b, at.toInt(), 2).short.toInt() and 0xFFFF
    private fun type(b: ByteArray, at: Long) = String(b, at.toInt(), 4, Charsets.ISO_8859_1)

    private fun tmp(): File = File.createTempFile("vt-mov", ".mov").also { it.deleteOnExit() }

    @Test fun movWithAlphaFramesRoundTrips() {
        val f = tmp()
        val frames = (0 until 5).map { i -> png(32, 18) { x, y -> if ((x + y + i) % 3 == 0) 0x00000000 else (0x80 shl 24) or (i * 40 shl 16) or (x * 7 shl 8) or y * 13 } }
        val w = MovWriter(f, 32, 18, 24)
        frames.forEach { w.addFrame(it) }
        assertEquals(5, w.frames)
        w.finish()
        val b = f.readBytes()
        val top = atoms(b, 0, b.size.toLong())
        assertEquals(listOf("ftyp", "wide", "mdat", "moov"), top.map { it.type })
        assertEquals("qt  ", type(b, 8))
        val moov = top.last()
        val traks = atoms(b, moov.start + 8, moov.start + moov.size).filter { it.type == "trak" }
        assertEquals(1, traks.size)
        val trak = traks[0]
        // описание кадров: кодек «png », глубина 32 (альфа), размер
        val stsd = child(b, trak, "mdia", "minf", "stbl", "stsd")
        val entry = stsd.start + 16
        assertEquals("png ", type(b, entry + 4))
        assertEquals(32, short(b, entry + 8 + 24))
        assertEquals(18, short(b, entry + 8 + 26))
        assertEquals(32, short(b, entry + 8 + 74))   // глубина
        assertEquals("vide", type(b, child(b, trak, "mdia", "hdlr").start + 16))
        // время: масштаб кратен 24, кадр = масштаб/24, длительность 5 кадров
        val mdhd = child(b, trak, "mdia", "mdhd")
        val scale = int(b, mdhd.start + 20)
        assertEquals(600, scale)
        assertEquals(5 * 25, int(b, mdhd.start + 24))
        val stts = child(b, trak, "mdia", "minf", "stbl", "stts")
        assertEquals(1, int(b, stts.start + 12)); assertEquals(5, int(b, stts.start + 16)); assertEquals(25, int(b, stts.start + 20))
        // смещения и размеры указывают ровно на PNG
        val stsz = child(b, trak, "mdia", "minf", "stbl", "stsz")
        val stco = child(b, trak, "mdia", "minf", "stbl", "stco")
        assertEquals(5, int(b, stsz.start + 16)); assertEquals(5, int(b, stco.start + 12))
        for (i in 0 until 5) {
            val size = int(b, stsz.start + 20 + i * 4)
            val off = int(b, stco.start + 16 + i * 4)
            assertTrue(b.copyOfRange(off, off + size).contentEquals(frames[i]))
        }
        // mvhd: 5 кадров при 24 fps = 208 мс
        val mvhd = child(b, moov, "mvhd")
        assertEquals(1000, int(b, mvhd.start + 20)); assertEquals(208, int(b, mvhd.start + 24))
        // tkhd: размер кадра 16.16
        val tkhd = child(b, trak, "tkhd")
        assertEquals(32 shl 16, int(b, tkhd.start + tkhd.size - 8)); assertEquals(18 shl 16, int(b, tkhd.start + tkhd.size - 4))
    }

    @Test fun pcmAudioIsInterleavedPerFrame() {
        val f = tmp()
        val rate = 44100; val fps = 30; val n = 7
        val w = MovWriter(f, 16, 16, fps, rate)
        val total = AudioMix.sampleCount(n, fps, rate)
        var written = 0
        for (i in 0 until n) {
            w.addFrame(png(16, 16) { _, _ -> 0xFF00FF00.toInt() })
            val (from, count) = AudioMix.frameSamples(i, fps, rate)
            assertEquals(written, from)
            w.addAudio(ShortArray(count * 2) { k -> ((from + k / 2) * (if (k % 2 == 0) 1 else -1)).toShort() }, count)
            written += count
        }
        assertEquals(total, written)
        assertEquals(total.toLong(), w.audioSamples)
        w.finish()
        val b = f.readBytes()
        val moov = atoms(b, 0, b.size.toLong()).last()
        val traks = atoms(b, moov.start + 8, moov.start + moov.size).filter { it.type == "trak" }
        assertEquals(2, traks.size)
        val a = traks[1]
        assertEquals("soun", type(b, child(b, a, "mdia", "hdlr").start + 16))
        val stsd = child(b, a, "mdia", "minf", "stbl", "stsd")
        val e = stsd.start + 16
        assertEquals("sowt", type(b, e + 4))
        assertEquals(2, short(b, e + 8 + 16)); assertEquals(16, short(b, e + 8 + 18))
        assertEquals(rate, int(b, e + 8 + 24) ushr 16)
        val mdhd = child(b, a, "mdia", "mdhd")
        assertEquals(rate, int(b, mdhd.start + 20)); assertEquals(total, int(b, mdhd.start + 24))
        val stsz = child(b, a, "mdia", "minf", "stbl", "stsz")
        assertEquals(4, int(b, stsz.start + 12)); assertEquals(total, int(b, stsz.start + 16))
        val stco = child(b, a, "mdia", "minf", "stbl", "stco")
        assertEquals(n, int(b, stco.start + 12))
        // первый кусок звука идёт сразу за первым кадром, сэмпл 1 = (1, -1) little-endian
        val off = int(b, stco.start + 16)
        val vStco = child(b, traks[0], "mdia", "minf", "stbl", "stco")
        val vStsz = child(b, traks[0], "mdia", "minf", "stbl", "stsz")
        assertEquals(int(b, vStco.start + 16) + int(b, vStsz.start + 20), off)
        assertEquals(1, (b[off + 4].toInt() and 0xFF) or (b[off + 5].toInt() shl 8))
        assertEquals(-1, ((b[off + 6].toInt() and 0xFF) or (b[off + 7].toInt() shl 8)).toShort().toInt())
        // stsc: 1470 сэмплов на кадр при 44100/30 - одна запись
        val stsc = child(b, a, "mdia", "minf", "stbl", "stsc")
        assertEquals(1, int(b, stsc.start + 12)); assertEquals(1470, int(b, stsc.start + 20))
    }

    @Test fun sampleToChunkRunsCompress() {
        assertEquals(listOf(1 to 1470), MovWriter.sampleToChunkRuns(listOf(1470, 1470, 1470)))
        assertEquals(listOf(1 to 1837, 2 to 1838, 3 to 1837), MovWriter.sampleToChunkRuns(listOf(1837, 1838, 1837)))
        assertEquals(emptyList<Pair<Int, Int>>(), MovWriter.sampleToChunkRuns(emptyList()))
    }

    @Test fun timeScaleIsMultipleOfFpsForAnyRate() {
        for (fps in listOf(1, 7, 12, 24, 25, 30, 48, 60, 90, 120)) {
            val f = tmp()
            val w = MovWriter(f, 2, 2, fps)
            assertEquals(0, w.timeScale % fps)
            assertTrue(w.timeScale >= 600)
            w.abort()
            assertFalse(f.exists())
        }
    }

    @Test fun rejectsNonPngAndEmptyMovie() {
        val f = tmp()
        val w = MovWriter(f, 4, 4, 24)
        assertTrue(runCatching { w.addFrame(ByteArray(16)) }.isFailure)
        assertTrue(runCatching { w.finish() }.isFailure)   // нет кадров
        w.abort()
        assertFalse(f.exists())
        val s = MovWriter(tmp(), 4, 4, 24)
        assertTrue(runCatching { s.addAudio(ShortArray(4)) }.isFailure)   // создан без звука
        s.abort()
    }
}
