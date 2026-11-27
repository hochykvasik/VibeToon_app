package com.vibetoon.domain.project.foreign

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Пакет 28: PNG без Android - запись RGBA (8 бит, без премультипликации) и чтение размеров картинок по заголовку
 * (PNG, JPEG, WebP, GIF), чтобы импорт других форматов работал в чистом домене и проверялся тестами.
 */
object PngIO {
    private val SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** RGBA-пиксели [rgba] (w*h*4, построчно сверху вниз) -> PNG в [out]. Фильтр Sub на каждой строке. */
    fun write(out: OutputStream, w: Int, h: Int, rgba: ByteArray) {
        require(w > 0 && h > 0 && rgba.size >= w * h * 4) { "bad image" }
        out.write(SIG)
        val ihdr = ByteArrayOutputStream()
        ihdr.int(w); ihdr.int(h); ihdr.write(8); ihdr.write(6); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk(out, "IHDR", ihdr.toByteArray())
        val def = Deflater(6)
        val idat = ByteArrayOutputStream()
        val row = ByteArray(w * 4 + 1)
        val buf = ByteArray(64 * 1024)
        try {
            for (y in 0 until h) {
                row[0] = 1   // Sub
                val o = y * w * 4
                for (i in 0 until w * 4) {
                    val left = if (i >= 4) rgba[o + i - 4].toInt() else 0
                    row[i + 1] = (rgba[o + i].toInt() - left).toByte()
                }
                def.setInput(row)
                while (!def.needsInput()) { val n = def.deflate(buf); if (n > 0) idat.write(buf, 0, n) else break }
                if (idat.size() > 1 shl 20) { chunk(out, "IDAT", idat.toByteArray()); idat.reset() }
            }
            def.finish()
            while (!def.finished()) { val n = def.deflate(buf); idat.write(buf, 0, n) }
            if (idat.size() > 0) chunk(out, "IDAT", idat.toByteArray())
        } finally { def.end() }
        chunk(out, "IEND", ByteArray(0))
    }

    fun encode(w: Int, h: Int, rgba: ByteArray): ByteArray = ByteArrayOutputStream().also { write(it, w, h, rgba) }.toByteArray()

    private fun ByteArrayOutputStream.int(v: Int) { write(v ushr 24); write(v ushr 16 and 0xFF); write(v ushr 8 and 0xFF); write(v and 0xFF) }

    private fun chunk(out: OutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        val len = data.size
        out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        val c = crc.value
        out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
    }

    /** Размер картинки (ширина, высота) по первым байтам: PNG, JPEG, GIF, WebP; null - не картинка или не распознано. */
    fun imageSize(b: ByteArray): Pair<Int, Int>? {
        fun u(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun be16(i: Int) = (u(i) shl 8) or u(i + 1)
        fun le16(i: Int) = u(i) or (u(i + 1) shl 8)
        fun be32(i: Int) = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
        if (b.size >= 24 && (0 until 8).all { b[it] == SIG[it] }) return (be32(16) to be32(20)).takeIf { it.first > 0 && it.second > 0 }
        if (b.size >= 10 && u(0) == 'G'.code && u(1) == 'I'.code && u(2) == 'F'.code) return (le16(6) to le16(8)).takeIf { it.first > 0 && it.second > 0 }
        if (b.size >= 30 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP") {
            return when (String(b, 12, 4, Charsets.US_ASCII)) {
                "VP8 " -> (le16(26) and 0x3FFF) to (le16(28) and 0x3FFF)
                "VP8L" -> { val v = u(21) or (u(22) shl 8) or (u(23) shl 16) or (u(24) shl 24); ((v and 0x3FFF) + 1) to (((v shr 14) and 0x3FFF) + 1) }
                "VP8X" -> (1 + (u(24) or (u(25) shl 8) or (u(26) shl 16))) to (1 + (u(27) or (u(28) shl 8) or (u(29) shl 16)))
                else -> null
            }
        }
        if (b.size >= 4 && u(0) == 0xFF && u(1) == 0xD8) {
            var i = 2
            while (i + 9 < b.size) {
                if (u(i) != 0xFF) { i++; continue }
                val m = u(i + 1)
                if (m == 0xFF) { i++; continue }
                if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
                val len = be16(i + 2)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return (be16(i + 7) to be16(i + 5)).takeIf { it.first > 0 && it.second > 0 }
                if (len < 2) return null
                i += 2 + len
            }
        }
        return null
    }
}
