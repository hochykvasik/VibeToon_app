package com.vibetoon

import com.vibetoon.domain.export.PsdLayer
import com.vibetoon.domain.export.PsdWriter
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class PsdWriterTest {
    private fun layerFile(w: Int, h: Int, a: Int, r: Int, g: Int, b: Int): File {
        val f = File.createTempFile("psdlayer", ".bin"); f.deleteOnExit()
        val bytes = ByteArray(w * h * 4)
        val plane = w * h
        for (i in 0 until plane) { bytes[i] = a.toByte(); bytes[plane + i] = r.toByte(); bytes[2 * plane + i] = g.toByte(); bytes[3 * plane + i] = b.toByte() }
        f.writeBytes(bytes); return f
    }

    @Test fun headerLayersGroupsAndSizes() {
        val layers = listOf(
            PsdLayer("Фон", 0, 0, 4, 3, "norm", 255, true, PsdLayer.NORMAL, layerFile(4, 3, 255, 10, 20, 30)),
            PsdLayer("</Layer group>", 0, 0, 0, 0, kind = PsdLayer.END),
            PsdLayer("A", 1, 1, 2, 2, "mul ", 255, true, PsdLayer.NORMAL, layerFile(2, 2, 255, 200, 0, 0)),
            PsdLayer("Группа 1", 0, 0, 0, 0, kind = PsdLayer.GROUP_OPEN)
        )
        val out = ByteArrayOutputStream()
        PsdWriter.write(out, 4, 3, layers, null)
        val b = ByteBuffer.wrap(out.toByteArray())
        assertEquals("8BPS", String(b.array(), 0, 4)); assertEquals(1, b.getShort(4).toInt())
        assertEquals(4, b.getShort(12).toInt()); assertEquals(3, b.getInt(14)); assertEquals(4, b.getInt(18)); assertEquals(8, b.getShort(22).toInt()); assertEquals(3, b.getShort(24).toInt())
        // 26: color mode len, 30: resources len, 34: layer+mask section length, 38: layer info length, 42: layer count
        assertEquals(0, b.getInt(26)); assertEquals(0, b.getInt(30))
        val sectionLen = b.getInt(34); val infoLen = b.getInt(38)
        assertEquals(-4, b.getShort(42).toInt())
        assertEquals(infoLen + 8, sectionLen)
        // данные изображения: после секции слоёв 2 байта сжатия и 4 плоскости 4*3
        assertEquals(34 + 4 + sectionLen + 2 + 4 * 12, b.array().size)
        assertEquals(1, PsdWriter.pascalName("A")[0].toInt()); assertEquals(0, PsdWriter.pascalName("abc").size % 4)
    }
}
