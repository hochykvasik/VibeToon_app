package com.vibetoon

import com.vibetoon.domain.project.PsdLayerExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Пакет 16: замки и текстовые слои PSD (Import Layer Locked States / Import Text as Pixel Layer). */
class Package16PsdImportOptionsTest {

    private class Block(val key: String, val data: ByteArray)
    private class Rec(val top: Int, val left: Int, val bottom: Int, val right: Int, val name: String, val blocks: List<Block>)

    private fun bytes(f: DataOutputStream.() -> Unit): ByteArray { val b = ByteArrayOutputStream(); DataOutputStream(b).f(); return b.toByteArray() }

    private fun lspf(flags: Int) = Block("lspf", bytes { writeInt(flags) })

    /** TySh: версия, трансформация, версия текста, дескриптор с `Txt ` и EngineData (как пишет Photoshop). */
    private fun tysh(text: String, engine: String?, scale: Double = 1.0) = Block("TySh", bytes {
        writeShort(1)
        for (v in doubleArrayOf(scale, 0.0, 0.0, scale, 10.0, 20.0)) writeDouble(v)
        writeShort(50); writeInt(16)
        writeInt(1); writeChar(0)          // имя класса (пустая unicode-строка с нулём)
        writeInt(0); writeBytes("TxLr")   // classID
        writeInt(if (engine != null) 2 else 1)
        writeInt(0); writeBytes("Txt "); writeBytes("TEXT")
        writeInt(text.length + 1); for (c in text) writeChar(c.code); writeChar(0)
        if (engine != null) {
            writeInt(10); writeBytes("EngineData"); writeBytes("tdta")
            val e = engine.toByteArray(Charsets.ISO_8859_1); writeInt(e.size); write(e)
        }
    })

    private fun psd(recs: List<Rec>, version: Int = 1): ByteArray = bytes {
        writeBytes("8BPS"); writeShort(version); write(ByteArray(6)); writeShort(4); writeInt(100); writeInt(200); writeShort(8); writeShort(3)
        writeInt(0)                           // color mode data
        writeInt(4); writeBytes("8BIM")       // image resources (мусор длиной 4)
        val info = bytes {
            writeShort(-recs.size)            // отрицательное число: первый альфа-канал - прозрачность
            for (r in recs) {
                writeInt(r.top); writeInt(r.left); writeInt(r.bottom); writeInt(r.right)
                writeShort(4); for (ch in listOf(-1, 0, 1, 2)) { writeShort(ch); writeInt(2) }
                writeBytes("8BIM"); writeBytes("norm"); write(255); write(0); write(8); write(0)
                val extra = bytes {
                    writeInt(0)                                 // маска
                    writeInt(8); write(ByteArray(8))            // диапазоны наложения
                    val n = r.name.toByteArray(Charsets.US_ASCII); write(n.size); write(n)
                    var pad = (n.size + 1) % 4; if (pad != 0) write(ByteArray(4 - pad))
                    for (b in r.blocks) {
                        writeBytes("8BIM"); writeBytes(b.key); writeInt(b.data.size); write(b.data)
                        if (b.data.size % 2 == 1) write(0)
                    }
                }
                writeInt(extra.size); write(extra)
            }
            for (r in recs) repeat(4) { writeShort(0) }   // данные каналов (не читаются)
        }
        writeInt(info.size + 4 + 4); writeInt(info.size); write(info); writeInt(0)
    }

    private val engine = "<< /EngineDict << /ParagraphRun << /RunArray [ << /ParagraphSheet << /Properties << /Justification 2 >> >> >> ] >> " +
        "/StyleRun << /RunArray [ << /StyleSheet << /StyleSheetData << /Font 0 /FontSize 24.0 /FillColor << /Type 1 /Values [ 1.0 1.0 0.0 0.0 ] >> >> >> >> ] >> >> >>"

    private val sample = listOf(
        Rec(0, 0, 10, 10, "bg", listOf(lspf(PsdLayerExtras.LOCK_TRANSPARENCY))),
        Rec(5, 6, 15, 26, "odd", listOf(Block("xyzw", byteArrayOf(1, 2, 3)), lspf(PsdLayerExtras.LOCK_ALL))),
        Rec(40, 30, 90, 150, "Title", listOf(lspf(PsdLayerExtras.LOCK_POSITION), tysh("Hi\rthere", engine, scale = 2.0))),
        Rec(0, 0, 60, 100, "plain text", listOf(tysh("A\rB\rC", null)))
    )

    @Test fun locksAndTextAreReadPerRecord() {
        val ex = PsdLayerExtras.read(ByteArrayInputStream(psd(sample)))
        assertEquals(4, ex.size)
        assertEquals(listOf(0, 1, 2, 3), ex.map { it.index })
        assertTrue(ex[0].alphaLock); assertFalse(ex[0].locked)
        assertTrue(ex[1].locked); assertFalse(ex[1].alphaLock)     // блок нечётной длины перед lspf не сбивает чтение
        assertEquals(listOf(5, 6, 15, 26), listOf(ex[1].top, ex[1].left, ex[1].bottom, ex[1].right))
        assertTrue(ex[2].locked)
        assertNull(ex[0].text); assertNull(ex[1].text)
        val t = ex[2].text!!
        assertEquals("Hi\nthere", t.text)
        assertEquals(48f, t.size!!, 1e-3f)              // 24 pt * масштаб трансформации 2
        assertEquals(0xFFFF0000L, t.color)
        assertEquals(1, t.align)                         // Photoshop 2 (по центру) -> VibeToon 1
        assertFalse(ex[3].locked); assertFalse(ex[3].alphaLock)
        val plain = ex[3].text!!
        assertEquals("A\nB\nC", plain.text); assertNull(plain.size); assertNull(plain.color); assertEquals(0, plain.align)
    }

    @Test fun placementOnCanvas() {
        val ex = PsdLayerExtras.read(ByteArrayInputStream(psd(sample)))
        val p = PsdLayerExtras.placement(ex[2], ex[2].text!!, 0.5f, 100f, 10f)
        assertEquals(115f, p.x, 1e-3f); assertEquals(30f, p.y, 1e-3f); assertEquals(24f, p.size, 1e-3f)
        // без размера в EngineData: высота рамки / строки / 1.2
        val q = PsdLayerExtras.placement(ex[3], ex[3].text!!, 1f, 0f, 0f)
        assertEquals(60f / 3 / 1.2f, q.size, 1e-3f)
    }

    @Test fun alignmentAndColor() {
        assertEquals(0, PsdLayerExtras.alignOf(0)); assertEquals(2, PsdLayerExtras.alignOf(1)); assertEquals(1, PsdLayerExtras.alignOf(2)); assertEquals(0, PsdLayerExtras.alignOf(3))
        assertEquals(0xFF0080FFL, PsdLayerExtras.argb(1f, 0f, 0.5f, 1f))
        assertEquals(0x00000000L, PsdLayerExtras.argb(-1f, -2f, 0f, 0f))
    }

    @Test fun brokenFilesGiveNothing() {
        assertTrue(PsdLayerExtras.read(ByteArrayInputStream("hello".toByteArray())).isEmpty())
        assertTrue(PsdLayerExtras.read(ByteArrayInputStream(psd(sample, version = 2))).isEmpty())   // PSB
        val full = psd(sample)
        for (cut in listOf(10, 40, full.size / 2, full.size - 60)) assertTrue("обрезано до $cut", PsdLayerExtras.read(ByteArrayInputStream(full.copyOf(cut))).isEmpty())
        // обрезаны только пиксели каналов: записи слоёв целы, их достаточно
        assertEquals(4, PsdLayerExtras.read(ByteArrayInputStream(full.copyOf(full.size - 20))).size)
        assertTrue(PsdLayerExtras.read(ByteArrayInputStream(psd(emptyList()))).isEmpty())
        // пустой текст и обрезанный TySh - не текстовый слой
        assertNull(PsdLayerExtras.parseTySh(ByteArray(10)))
        assertNull(PsdLayerExtras.parseTySh(tysh("   ", null).data))
        assertNotNull(PsdLayerExtras.parseTySh(tysh("ok", null).data))
    }

    @Test fun cyrillicTextAndCrLf() {
        val t = PsdLayerExtras.parseTySh(tysh("Привет\r\nмир", null).data)!!
        assertEquals("Привет\nмир", t.text)
    }
}
