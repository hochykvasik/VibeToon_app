package com.vibetoon

import com.vibetoon.domain.export.GifEncoder
import com.vibetoon.domain.export.GifQuantizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class GifEncoderTest {
    /** Минимальный GIF-LZW декодер для проверки кодировщика. */
    private fun unlzw(data: ByteArray, minCode: Int, count: Int): ByteArray {
        val clear = 1 shl minCode; val eoi = clear + 1
        var width = minCode + 1
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val length = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
        var next = eoi + 1
        val out = ByteArrayOutputStream()
        var bitPos = 0
        fun read(): Int {
            var v = 0
            for (b in 0 until width) {
                val byte = data[(bitPos + b) / 8].toInt() and 0xFF
                v = v or (((byte shr ((bitPos + b) % 8)) and 1) shl b)
            }
            bitPos += width
            return v
        }
        fun expand(code: Int): ByteArray {
            val r = ByteArray(length[code]); var c = code; var i = r.size - 1
            while (c >= 0 && i >= 0) { r[i--] = suffix[c]; c = prefix[c] }
            return r
        }
        var prev = -1
        while (bitPos + width <= data.size * 8) {
            val code = read()
            if (code == clear) { width = minCode + 1; next = eoi + 1; prev = -1; continue }
            if (code == eoi) break
            val entry: ByteArray
            if (prev == -1) entry = expand(code)
            else if (code < next) {
                entry = expand(code)
                prefix[next] = prev; suffix[next] = entry[0]; length[next] = length[prev] + 1; next++
            } else {
                val p = expand(prev)
                entry = p + p[0]
                prefix[next] = prev; suffix[next] = p[0]; length[next] = length[prev] + 1; next++
            }
            out.write(entry)
            prev = code
            if (next == (1 shl width) && width < 12) width++
        }
        val r = out.toByteArray()
        assertEquals(count, r.size)
        return r
    }

    @Test fun lzwRoundTripOnSmallAndLargeData() {
        val rnd = Random(7)
        for ((alphabet, n) in listOf(2 to 50, 4 to 5000, 16 to 40000, 256 to 120000)) {
            val bits = maxOf(2, 32 - Integer.numberOfLeadingZeros(alphabet - 1))
            val src = ByteArray(n) { i -> (if (i % 3 == 0) rnd.nextInt(alphabet) else (i / 7) % alphabet).toByte() }
            val enc = GifEncoder.lzw(src, bits)
            assertArrayEquals("alphabet $alphabet n $n", src, unlzw(enc, bits, n))
        }
    }

    @Test fun quantizerKeepsFewColorsAndMarksTransparency() {
        val red = 0xFFFF0000.toInt(); val blue = 0xFF0000FF.toInt()
        val px = IntArray(100) { if (it < 40) red else if (it < 80) blue else 0 }
        val q = GifQuantizer.quantize(px, true)
        assertTrue(q.transparentIndex >= 0)
        assertEquals(q.transparentIndex.toByte(), q.indices[99])
        val pr = q.palette[q.indices[0].toInt() and 0xFF]; val pb = q.palette[q.indices[50].toInt() and 0xFF]
        assertEquals(0xFF0000, pr and 0xFFFFFF); assertEquals(0x0000FF, pb and 0xFFFFFF)
        val opaque = GifQuantizer.quantize(px, false)
        assertEquals(-1, opaque.transparentIndex)
    }

    @Test fun gifHasValidStructure() {
        val bos = ByteArrayOutputStream()
        val enc = GifEncoder(bos, 4, 3, 24, true)
        repeat(3) { f -> enc.addFrame(IntArray(12) { if (it % 2 == 0) 0xFF000000.toInt() or (f * 80) else 0 }) }
        enc.finish()
        val b = bos.toByteArray()
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        assertEquals(4, b[6].toInt()); assertEquals(3, b[8].toInt())
        assertEquals(0x3B, b.last().toInt())
        assertEquals(3, b.indices.count { it + 1 < b.size && b[it] == 0x21.toByte() && b[it + 1] == 0xF9.toByte() })
    }
}
