package com.vibetoon

import com.vibetoon.domain.export.WavMath
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class WavTest {
    @Test fun wavHeaderAndSamples() {
        val out = ByteArrayOutputStream()
        WavMath.write(out, shortArrayOf(1, -2, 300, -300), 44100, 2)
        val b = out.toByteArray()
        assertEquals(44 + 8, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 8, bb.getInt(4)); assertEquals(2, bb.getShort(22).toInt()); assertEquals(44100, bb.getInt(24)); assertEquals(8, bb.getInt(40))
        assertEquals(1, bb.getShort(44).toInt()); assertEquals(-2, bb.getShort(46).toInt()); assertEquals(-300, bb.getShort(50).toInt())
    }
}
