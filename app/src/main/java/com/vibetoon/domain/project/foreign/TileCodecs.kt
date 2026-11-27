package com.vibetoon.domain.project.foreign

/**
 * Пакет 28: распаковщики плиток Procreate - LZO1X (.chunk, старые версии) и цепочка Apple LZ4 «bv41» (.lz4, новые).
 * Оба пишут в готовый буфер и возвращают число записанных байт; повреждённый поток - [IllegalArgumentException].
 */
object TileCodecs {
    private fun fail(msg: String): Nothing = throw IllegalArgumentException(msg)

    /** LZO1X-1 (по устройству lzo1x_decompress_safe ядра Linux). */
    fun lzo(src: ByteArray, dst: ByteArray): Int {
        var ip = 0; var op = 0; var state = 0
        val ilen = src.size; val olen = dst.size
        fun u(i: Int): Int { if (i >= ilen) fail("LZO overrun"); return src[i].toInt() and 0xFF }
        fun literals(n: Int) {
            if (ip + n > ilen || op + n > olen) fail("LZO overrun")
            System.arraycopy(src, ip, dst, op, n); ip += n; op += n
        }
        fun extLen(base: Int): Int {
            var n = 0
            while (u(ip) == 0) { n += 255; ip++; if (n > 1 shl 28) fail("LZO length") }
            return n + base + u(ip++)
        }
        if (ilen > 0 && u(0) > 17) {
            val t = u(ip++) - 17
            literals(t)
            state = if (t < 4) t else 4
        }
        while (true) {
            var t = u(ip++)
            var mpos: Int
            var len: Int
            var next: Int
            if (t < 16) {
                if (state == 0) {
                    if (t == 0) t = extLen(15)
                    literals(t + 3)
                    state = 4
                    continue
                } else if (state != 4) {
                    next = t and 3
                    mpos = op - 1 - (t shr 2) - (u(ip++) shl 2)
                    if (mpos < 0 || op + 2 > olen) fail("LZO bad offset")
                    dst[op++] = dst[mpos++]; dst[op++] = dst[mpos]
                    literals(next)
                    state = next
                    continue
                } else {
                    next = t and 3
                    mpos = op - 2049 - (t shr 2) - (u(ip++) shl 2)
                    len = 3
                }
            } else if (t >= 64) {
                next = t and 3
                mpos = op - 1 - ((t shr 2) and 7) - (u(ip++) shl 3)
                len = (t shr 5) + 1
            } else if (t >= 32) {
                len = (t and 31) + 2
                if (len == 2) len = extLen(31) + 2
                val v = u(ip) or (u(ip + 1) shl 8); ip += 2
                mpos = op - 1 - (v shr 2); next = v and 3
            } else {
                mpos = op - ((t and 8) shl 11)
                len = (t and 7) + 2
                if (len == 2) len = extLen(7) + 2
                val v = u(ip) or (u(ip + 1) shl 8); ip += 2
                mpos -= v shr 2; next = v and 3
                if (mpos == op) return op   // конец потока
                mpos -= 0x4000
            }
            if (mpos < 0 || op + len > olen) fail("LZO bad match")
            for (k in 0 until len) dst[op++] = dst[mpos++]
            literals(next)
            state = next
        }
    }

    /** Блок LZ4 из [src] (s..sEnd) в [dst] с позиции [op0]; совпадения могут ссылаться на прежний вывод (цепочка bv41). */
    fun lz4Block(src: ByteArray, s0: Int, sEnd: Int, dst: ByteArray, op0: Int, oEnd: Int): Int {
        var s = s0; var op = op0
        fun u(i: Int): Int { if (i >= sEnd) fail("LZ4 overrun"); return src[i].toInt() and 0xFF }
        while (s < sEnd) {
            val token = u(s++)
            var n = token shr 4
            if (n == 15) { var x: Int; do { x = u(s++); n += x } while (x == 255) }
            if (op + n > oEnd || s + n > sEnd) fail("LZ4 literal overrun")
            System.arraycopy(src, s, dst, op, n); s += n; op += n
            if (s >= sEnd) break
            val off = u(s) or (u(s + 1) shl 8); s += 2
            var m = token and 15
            if (m == 15) { var x: Int; do { x = u(s++); m += x } while (x == 255) }
            m += 4
            var p = op - off
            if (off == 0 || p < 0 || op + m > oEnd) fail("LZ4 bad match")
            for (k in 0 until m) dst[op++] = dst[p++]
        }
        return op
    }

    private const val BV4_END = 0x62763424      // 'bv4$'
    private const val BV4_LZ4 = 0x62763431      // 'bv41'
    private const val BV4_RAW = 0x6276342d      // 'bv4-'

    /** Контейнер Apple compression LZ4: блоки 'bv41' (LZ4), 'bv4-' (без сжатия) до 'bv4$'. */
    fun bv4(src: ByteArray, dst: ByteArray): Int {
        var s = 0; var op = 0
        fun tag(i: Int) = if (i + 4 > src.size) -1 else ((src[i].toInt() and 0xFF) shl 24) or ((src[i + 1].toInt() and 0xFF) shl 16) or ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
        fun le(i: Int): Int {
            if (i + 4 > src.size) fail("bv4 overrun")
            val v = (src[i].toLong() and 0xFF) or ((src[i + 1].toLong() and 0xFF) shl 8) or ((src[i + 2].toLong() and 0xFF) shl 16) or ((src[i + 3].toLong() and 0xFF) shl 24)
            if (v > Int.MAX_VALUE) fail("bv4 size")
            return v.toInt()
        }
        while (s + 4 <= src.size) {
            when (tag(s)) {
                BV4_END -> return op
                BV4_LZ4 -> {
                    val dec = le(s + 4); val enc = le(s + 8); s += 12
                    if (s + enc > src.size || op + dec > dst.size) fail("bv41 overrun")
                    val end = lz4Block(src, s, s + enc, dst, op, op + dec)
                    if (end - op != dec) fail("LZ4 size mismatch")
                    op = end; s += enc
                }
                BV4_RAW -> {
                    val n = le(s + 4)
                    // у Apple заголовок несжатого блока 8 байт; встречается и вариант с повтором длины (12 байт)
                    val known = setOf(BV4_END, BV4_LZ4, BV4_RAW)
                    val head = if (s + 8 + n == src.size || tag(s + 8 + n) in known) 8 else if (s + 12 <= src.size && le(s + 8) == n) 12 else 8
                    s += head
                    if (n < 0 || s + n > src.size || op + n > dst.size) fail("bv4- overrun")
                    System.arraycopy(src, s, dst, op, n); op += n; s += n
                }
                else -> fail("unknown LZ4 block")
            }
        }
        return op
    }
}
