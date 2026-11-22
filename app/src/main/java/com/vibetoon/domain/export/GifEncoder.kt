package com.vibetoon.domain.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Анимированный GIF89a без внешних библиотек: локальная палитра на каждый кадр (медианное сечение по
 * 15-битной гистограмме), LZW, расширение NETSCAPE2.0 (бесконечный цикл) и прозрачность одним индексом.
 */
class GifEncoder(private val out: OutputStream, private val width: Int, private val height: Int, fps: Int, private val transparency: Boolean) {
    private val delayCs = Math.round(100f / fps.coerceIn(1, 60)).coerceAtLeast(2)
    private var started = false

    private fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun header() {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(width); u16(height)
        out.write(0x00)   // без глобальной палитры
        out.write(0); out.write(0)
        // бесконечный цикл
        out.write(0x21); out.write(0xFF); out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(1); u16(0); out.write(0)
        started = true
    }

    /** Добавляет кадр: [argb] - width*height пикселей (прямой, не premultiplied ARGB). */
    fun addFrame(argb: IntArray) {
        require(argb.size == width * height)
        if (!started) header()
        val q = GifQuantizer.quantize(argb, transparency)
        // Graphic Control Extension
        out.write(0x21); out.write(0xF9); out.write(4)
        val hasT = q.transparentIndex >= 0
        out.write(((if (hasT) 2 else 1) shl 2) or (if (hasT) 1 else 0))   // disposal: 2 для прозрачных кадров, 1 иначе
        u16(delayCs)
        out.write(if (hasT) q.transparentIndex else 0)
        out.write(0)
        // Image Descriptor с локальной палитрой
        out.write(0x2C); u16(0); u16(0); u16(width); u16(height)
        val bits = q.bits
        out.write(0x80 or (bits - 1))
        val size = 1 shl bits
        for (i in 0 until size) {
            val c = if (i < q.palette.size) q.palette[i] else 0
            out.write((c shr 16) and 0xFF); out.write((c shr 8) and 0xFF); out.write(c and 0xFF)
        }
        val minCode = maxOf(2, bits)
        out.write(minCode)
        val data = lzw(q.indices, minCode)
        var p = 0
        while (p < data.size) {
            val n = minOf(255, data.size - p)
            out.write(n); out.write(data, p, n); p += n
        }
        out.write(0)
    }

    fun finish() {
        if (!started) header()
        out.write(0x3B)
        out.flush()
    }

    companion object {
        /** GIF-LZW: коды растут до 12 бит, при заполнении словаря посылается Clear. */
        fun lzw(indices: ByteArray, minCode: Int): ByteArray {
            val clear = 1 shl minCode
            val eoi = clear + 1
            val bos = ByteArrayOutputStream(indices.size / 2 + 16)
            var cur = 0; var nbits = 0
            fun emit(code: Int, width: Int) {
                cur = cur or (code shl nbits); nbits += width
                while (nbits >= 8) { bos.write(cur and 0xFF); cur = cur ushr 8; nbits -= 8 }
            }
            var width = minCode + 1
            var next = eoi + 1
            val dict = HashMap<Int, Int>()
            emit(clear, width)
            if (indices.isEmpty()) { emit(eoi, width); if (nbits > 0) bos.write(cur and 0xFF); return bos.toByteArray() }
            var prefix = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (prefix shl 8) or k
                val hit = dict[key]
                if (hit != null) { prefix = hit; continue }
                emit(prefix, width)
                if (next < 4096) {
                    dict[key] = next++
                    if (next - 1 == (1 shl width) && width < 12) width++   // код next-1 уже требует больше бит
                } else {
                    emit(clear, width); dict.clear(); width = minCode + 1; next = eoi + 1
                }
                prefix = k
            }
            emit(prefix, width)
            emit(eoi, width)
            if (nbits > 0) bos.write(cur and 0xFF)
            return bos.toByteArray()
        }
    }
}

class GifFrameData(val indices: ByteArray, val palette: IntArray, val transparentIndex: Int, val bits: Int)

object GifQuantizer {
    private class Box(var lo: Int, var hi: Int, val keys: IntArray, val counts: IntArray) {
        var rmin = 31; var rmax = 0; var gmin = 31; var gmax = 0; var bmin = 31; var bmax = 0; var total = 0L
        fun fit() {
            rmin = 31; rmax = 0; gmin = 31; gmax = 0; bmin = 31; bmax = 0; total = 0
            for (i in lo until hi) {
                val k = keys[i]; val r = k shr 10; val g = (k shr 5) and 31; val b = k and 31
                if (r < rmin) rmin = r; if (r > rmax) rmax = r
                if (g < gmin) gmin = g; if (g > gmax) gmax = g
                if (b < bmin) bmin = b; if (b > bmax) bmax = b
                total += counts[i]
            }
        }
        val span get() = maxOf(rmax - rmin, gmax - gmin, bmax - bmin)
    }

    fun quantize(argb: IntArray, transparency: Boolean): GifFrameData {
        val hist = IntArray(32768)
        var hasT = false
        for (p in argb) {
            if (transparency && (p ushr 24) < 128) { hasT = true; continue }
            hist[((p shr 19) and 31 shl 10) or ((p shr 11) and 31 shl 5) or ((p shr 3) and 31)]++
        }
        var n = 0
        for (c in hist) if (c > 0) n++
        val keys = IntArray(n); val counts = IntArray(n)
        var j = 0
        for (k in hist.indices) if (hist[k] > 0) { keys[j] = k; counts[j] = hist[k]; j++ }
        val maxColors = if (hasT) 255 else 256
        val boxes = ArrayList<Box>()
        if (n > 0) boxes += Box(0, n, keys, counts).also { it.fit() }
        // медианное сечение: делим самый «широкий» ящик по самому длинному каналу, пока не наберётся палитра
        while (boxes.size < maxColors) {
            var best: Box? = null
            for (b in boxes) if (b.hi - b.lo > 1 && (best == null || b.span * b.total.coerceAtMost(1L shl 20) > best.span * best.total.coerceAtMost(1L shl 20))) best = b
            val b = best ?: break
            val dr = b.rmax - b.rmin; val dg = b.gmax - b.gmin; val db = b.bmax - b.bmin
            val ch = if (dr >= dg && dr >= db) 0 else if (dg >= db) 1 else 2
            fun comp(k: Int) = when (ch) { 0 -> k shr 10; 1 -> (k shr 5) and 31; else -> k and 31 }
            // сортировка диапазона по каналу (вставками по индексам: n небольшое, ключи уникальны)
            val idx = (b.lo until b.hi).sortedBy { comp(keys[it]) }
            val ks = idx.map { keys[it] }; val cs = idx.map { counts[it] }
            for ((o, i) in (b.lo until b.hi).withIndex()) { keys[i] = ks[o]; counts[i] = cs[o] }
            var acc = 0L; var mid = b.lo
            val half = b.total / 2
            for (i in b.lo until b.hi) { acc += counts[i]; mid = i + 1; if (acc >= half) break }
            if (mid >= b.hi) mid = b.hi - 1
            if (mid <= b.lo) mid = b.lo + 1
            val nb = Box(mid, b.hi, keys, counts)
            b.hi = mid
            b.fit(); nb.fit()
            boxes += nb
        }
        val palette = ArrayList<Int>()
        val lut = IntArray(32768) { -1 }
        for ((bi, b) in boxes.withIndex()) {
            var r = 0L; var g = 0L; var bl = 0L; var t = 0L
            for (i in b.lo until b.hi) {
                val k = keys[i]; val c = counts[i].toLong()
                r += (k shr 10) * c; g += ((k shr 5) and 31) * c; bl += (k and 31) * c; t += c
                lut[k] = bi
            }
            val tt = t.coerceAtLeast(1)
            fun e(v: Long) = ((v / tt) * 255 / 31).toInt().coerceIn(0, 255)
            palette += (e(r) shl 16) or (e(g) shl 8) or e(bl)
        }
        var tIndex = -1
        if (hasT) { tIndex = palette.size; palette += 0 }
        if (palette.isEmpty()) palette += 0
        var bits = 1
        while ((1 shl bits) < palette.size) bits++
        bits = bits.coerceIn(1, 8)
        val out = ByteArray(argb.size)
        for (i in argb.indices) {
            val p = argb[i]
            out[i] = if (transparency && (p ushr 24) < 128) tIndex.toByte() else {
                val k = ((p shr 19) and 31 shl 10) or ((p shr 11) and 31 shl 5) or ((p shr 3) and 31)
                (if (lut[k] >= 0) lut[k] else 0).toByte()
            }
        }
        return GifFrameData(out, palette.toIntArray(), tIndex, bits)
    }
}
