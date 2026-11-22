package com.vibetoon

import com.vibetoon.domain.render.YuvMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Совместимый экспорт (Sprint 6.9.1): RGBA -> YUV420 и раскладка плоскостей с шагом строки/пикселя кодека. */
class ExportYuvTest {
    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) = ByteArray(w * h * 4) { i -> when (i % 4) { 0 -> r; 1 -> g; 2 -> b; else -> 255 }.toByte() }
    private fun u8(b: Byte) = b.toInt() and 0xFF

    private fun conv(rgba: ByteArray, w: Int, h: Int, bottomUp: Boolean = false): Triple<ByteArray, ByteArray, ByteArray> {
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        YuvMath.rgbaToI420(rgba, w, h, bottomUp, y, u, v)
        return Triple(y, u, v)
    }

    @Test fun referenceColorsBt601Limited() {
        // эталоны BT.601 limited range: белый (235,128,128), чёрный (16,128,128), красный (81,90,240), зелёный (145,54,34), синий (41,240,110)
        val cases = listOf(intArrayOf(255, 255, 255, 235, 128, 128), intArrayOf(0, 0, 0, 16, 128, 128), intArrayOf(255, 0, 0, 81, 90, 240),
            intArrayOf(0, 255, 0, 145, 54, 34), intArrayOf(0, 0, 255, 41, 240, 110))
        for (c in cases) {
            val (y, u, v) = conv(solid(4, 2, c[0], c[1], c[2]), 4, 2)
            assertEquals("Y ${c.toList()}", c[3].toDouble(), u8(y[0]).toDouble(), 1.0)
            assertEquals("U ${c.toList()}", c[4].toDouble(), u8(u[0]).toDouble(), 1.0)
            assertEquals("V ${c.toList()}", c[5].toDouble(), u8(v[0]).toDouble(), 1.0)
        }
    }

    @Test fun greyFrameIsNotGreen() {
        // «зелёный мусор» - это Y/U/V около нуля; серый кадр должен дать U = V = 128
        val (_, u, v) = conv(solid(16, 16, 128, 128, 128), 16, 16)
        assertTrue(u.all { u8(it) == 128 } && v.all { u8(it) == 128 })
    }

    @Test fun bottomUpReadbackIsFlipped() {
        val w = 2; val h = 4
        val rgba = ByteArray(w * h * 4)
        for (x in 0 until w) { val o = x * 4; rgba[o] = 255.toByte(); rgba[o + 1] = 255.toByte(); rgba[o + 2] = 255.toByte(); rgba[o + 3] = 255.toByte() }   // строка 0 буфера (низ кадра) белая
        val (y, _, _) = conv(rgba, w, h, bottomUp = true)
        assertEquals(235, u8(y[(h - 1) * w]))   // в кадре белая строка - последняя
        assertEquals(16, u8(y[0]))
    }

    @Test fun planeCopyHonoursStrides() {
        // NV12: U и V чередуются (pixelStride 2), строка с запасом (rowStride 8 при ширине 3)
        val src = byteArrayOf(1, 2, 3, 4, 5, 6)
        val buf = ByteArray(16)
        YuvMath.copyPlane(src, 3, 2, 8, 2) { i, b -> buf[i] = b }
        assertEquals(listOf<Byte>(1, 0, 2, 0, 3, 0, 0, 0, 4, 0, 5, 0, 6, 0, 0, 0), buf.toList())
        val planar = ByteArray(6)
        YuvMath.copyPlane(src, 3, 2, 3, 1) { i, b -> planar[i] = b }
        assertEquals(src.toList(), planar.toList())
    }
}
