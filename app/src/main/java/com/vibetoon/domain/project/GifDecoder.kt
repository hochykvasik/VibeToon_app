package com.vibetoon.domain.project

import java.io.InputStream

/**
 * Пакет 15: декодер анимированного GIF без Android (проверяется unit-тестом).
 *
 * Кадры выдаются по одному через колбэк уже собранными на холсте GIF (ARGB, с учётом прозрачности, смещения
 * кадра и способа очистки 0-3), поэтому в памяти держатся только холст и предыдущее состояние - не все кадры.
 * Поддерживаются GIF87a/GIF89a, глобальная и локальная палитры, чересстрочные кадры, задержки кадров.
 * Ограничения: [maxFrames] кадров и [maxPixels] пикселей холста (защита памяти). Повреждённый хвост файла
 * не роняет импорт: уже прочитанные кадры остаются.
 */
object GifDecoder {
    const val MAX_FRAMES = 300
    const val MAX_PIXELS = 4096 * 4096

    class GifException(message: String) : IllegalStateException(message)

    data class Info(val width: Int, val height: Int, val frames: Int, val totalMs: Long, val loops: Int)

    /** Начинается ли файл с подписи GIF. */
    fun isGif(head: ByteArray): Boolean = head.size >= 6 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
        head[2] == 'F'.code.toByte() && head[3] == '8'.code.toByte() && (head[4] == '7'.code.toByte() || head[4] == '9'.code.toByte()) && head[5] == 'a'.code.toByte()

    /** Задержка кадра так, как её показывают браузеры: 0-1 сотых секунды - это 100 мс. */
    fun effectiveDelayMs(delayCs: Int): Int = if (delayCs <= 1) 100 else delayCs * 10

    private class Reader(private val input: InputStream) {
        fun u8(): Int { val b = input.read(); if (b < 0) throw GifException("Файл GIF обрезан"); return b }
        fun u16(): Int { val a = u8(); return a or (u8() shl 8) }
        fun bytes(n: Int): ByteArray { val b = ByteArray(n); var r = 0; while (r < n) { val k = input.read(b, r, n - r); if (k < 0) throw GifException("Файл GIF обрезан"); r += k }; return b }
        fun skipBlocks() { while (true) { val n = u8(); if (n == 0) return; bytes(n) } }
        fun blocks(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) { val n = u8(); if (n == 0) break; out.write(bytes(n)) }
            return out.toByteArray()
        }
    }

    private fun palette(r: Reader, size: Int): IntArray {
        val raw = r.bytes(size * 3)
        return IntArray(size) { i -> (0xFF shl 24) or ((raw[i * 3].toInt() and 0xFF) shl 16) or ((raw[i * 3 + 1].toInt() and 0xFF) shl 8) or (raw[i * 3 + 2].toInt() and 0xFF) }
    }

    /**
     * Декодирует GIF. [onFrame] получает номер кадра, холст w*h ARGB (массив переиспользуется - копируйте, если
     * храните) и задержку кадра в мс. Возвращает сведения о файле.
     */
    fun decode(input: InputStream, maxFrames: Int = MAX_FRAMES, maxPixels: Int = MAX_PIXELS, onSize: (Int, Int) -> Unit = { _, _ -> }, onFrame: (Int, IntArray, Int) -> Unit): Info {
        val r = Reader(input.buffered())
        if (!isGif(r.bytes(6))) throw GifException("Это не GIF")
        val w = r.u16(); val h = r.u16()
        if (w <= 0 || h <= 0 || w.toLong() * h > maxPixels) throw GifException("Слишком большой GIF: ${w}×$h")
        onSize(w, h)
        val flags = r.u8(); r.u8(); r.u8()   // фон и соотношение сторон: фон по стандарту - прозрачный (как в браузерах)
        val global = if (flags and 0x80 != 0) palette(r, 2 shl (flags and 7)) else null
        val canvas = IntArray(w * h)
        var saved: IntArray? = null
        var disposal = 0; var transparent = -1; var delayCs = 0
        var frames = 0; var totalMs = 0L; var loops = 1
        try {
            loop@ while (frames < maxFrames) {
                when (r.u8()) {
                    0x21 -> when (r.u8()) {
                        0xF9 -> {
                            val block = r.blocks()
                            if (block.size >= 4) {
                                val pf = block[0].toInt() and 0xFF
                                disposal = (pf shr 2) and 7
                                delayCs = (block[1].toInt() and 0xFF) or ((block[2].toInt() and 0xFF) shl 8)
                                transparent = if (pf and 1 != 0) block[3].toInt() and 0xFF else -1
                            }
                        }
                        0xFF -> {
                            val id = r.blocks()
                            // NETSCAPE2.0: число повторов (0 - бесконечно) - после 11 байт имени: 1, lo, hi
                            if (id.size >= 14 && String(id, 0, 8, Charsets.US_ASCII) == "NETSCAPE") loops = (id[12].toInt() and 0xFF) or ((id[13].toInt() and 0xFF) shl 8)
                        }
                        else -> r.skipBlocks()
                    }
                    0x2C -> {
                        val fx = r.u16(); val fy = r.u16(); val fw = r.u16(); val fh = r.u16()
                        val ff = r.u8()
                        val local = if (ff and 0x80 != 0) palette(r, 2 shl (ff and 7)) else null
                        val interlaced = ff and 0x40 != 0
                        val minCode = r.u8()
                        val data = r.blocks()
                        val pal = local ?: global ?: IntArray(256) { (0xFF shl 24) or (it * 0x010101) }
                        if (disposal == 3) saved = canvas.copyOf()
                        val idx = lzw(data, minCode, fw * fh)
                        draw(canvas, w, h, idx, fx, fy, fw, fh, interlaced, pal, transparent)
                        val delay = effectiveDelayMs(delayCs)
                        onFrame(frames, canvas, delay)
                        frames++; totalMs += delay
                        // очистка после показа кадра
                        when (disposal) {
                            2 -> for (y in fy until minOf(fy + fh, h)) for (x in fx until minOf(fx + fw, w)) canvas[y * w + x] = 0
                            3 -> saved?.copyInto(canvas)
                        }
                        disposal = 0; transparent = -1; delayCs = 0; saved = null
                    }
                    0x3B -> break@loop
                    else -> break@loop   // мусор после кадров: оставляем прочитанное
                }
            }
        } catch (e: GifException) {
            if (frames == 0) throw e   // обрезанный хвост: уже прочитанных кадров достаточно
        }
        if (frames == 0) throw GifException("В GIF нет кадров")
        return Info(w, h, frames, totalMs, loops)
    }

    private fun draw(canvas: IntArray, w: Int, h: Int, idx: ByteArray, fx: Int, fy: Int, fw: Int, fh: Int, interlaced: Boolean, pal: IntArray, transparent: Int) {
        val rows = if (interlaced) interlaceRows(fh) else null
        for (row in 0 until fh) {
            val y = fy + (rows?.get(row) ?: row)
            if (y !in 0 until h) continue
            for (col in 0 until fw) {
                val x = fx + col
                if (x !in 0 until w) continue
                val c = idx[row * fw + col].toInt() and 0xFF
                if (c == transparent || c >= pal.size) continue
                canvas[y * w + x] = pal[c]
            }
        }
    }

    /** Порядок строк чересстрочного кадра: номер прочитанной строки -> строка изображения. */
    fun interlaceRows(h: Int): IntArray {
        val out = IntArray(h); var k = 0
        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) { var y = start; while (y < h) { out[k++] = y; y += step } }
        return out
    }

    /** LZW-распаковка индексов кадра (переменная длина кода 3..12 бит). Недостающие пиксели - индекс 0. */
    fun lzw(data: ByteArray, minCodeSize: Int, pixels: Int): ByteArray {
        val out = ByteArray(pixels)
        if (minCodeSize !in 1..11) return out
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = ByteArray(4096); val first = ByteArray(4096); val len = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i.toByte(); first[i] = i.toByte(); len[i] = 1; prefix[i] = -1 }
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var old = -1
        var bits = 0; var acc = 0; var pos = 0; var o = 0
        val stack = ByteArray(4097)
        while (o < pixels) {
            while (bits < codeSize) {
                if (pos >= data.size) return out
                acc = acc or ((data[pos++].toInt() and 0xFF) shl bits); bits += 8
            }
            val code = acc and ((1 shl codeSize) - 1)
            acc = acc ushr codeSize; bits -= codeSize
            if (code == clear) { codeSize = minCodeSize + 1; next = eoi + 1; old = -1; continue }
            if (code == eoi) break
            val cur: Int
            if (old == -1) {
                if (code >= next) return out
                out[o++] = suffix[code]; old = code; continue
            }
            if (code < next) {
                cur = code
                if (next < 4096) { prefix[next] = old; suffix[next] = first[code]; first[next] = first[old]; len[next] = len[old] + 1; next++ }
            } else if (code == next && next < 4096) {
                prefix[next] = old; suffix[next] = first[old]; first[next] = first[old]; len[next] = len[old] + 1; next++
                cur = code
            } else return out
            // выписываем строку кода cur
            var c = cur; var sp = 0
            while (c >= 0 && sp < stack.size) { stack[sp++] = suffix[c]; c = prefix[c] }
            while (sp > 0 && o < pixels) out[o++] = stack[--sp]
            old = cur
            if (next == (1 shl codeSize) && codeSize < 12) codeSize++
        }
        return out
    }

    /**
     * Кадры GIF на таймлайне проекта: начало каждого кадра (в кадрах проекта) по сумме задержек. Кадры, попавшие на тот
     * же кадр проекта, что и предыдущий (GIF быстрее FPS проекта), пропускаются. Возвращает (номер кадра GIF, начало)
     * и полную длину анимации в кадрах проекта.
     */
    fun timeline(delaysMs: List<Int>, fps: Int): Pair<List<Pair<Int, Int>>, Int> {
        val f = fps.coerceAtLeast(1)
        val out = ArrayList<Pair<Int, Int>>()
        var t = 0L
        for ((i, d) in delaysMs.withIndex()) {
            val start = Math.round(t * f / 1000.0).toInt()
            if (out.isEmpty() || start > out.last().second) out += i to start
            t += d.coerceAtLeast(1)
        }
        val total = maxOf(Math.round(t * f / 1000.0).toInt(), (out.lastOrNull()?.second ?: 0) + 1, 1)
        return out to total
    }

    /**
     * Потоковый вариант [timeline] для импорта: кадры приходят по одному. [place] возвращает начало кадра в кадрах
     * проекта или null, если кадр попал на тот же кадр проекта, что и предыдущий (его не сохраняем).
     */
    class Placer(fps: Int) {
        private val f = fps.coerceAtLeast(1)
        private var t = 0L
        private var last = -1
        var placed = 0
            private set

        fun place(delayMs: Int): Int? {
            val start = Math.round(t * f / 1000.0).toInt()
            t += delayMs.coerceAtLeast(1)
            if (placed > 0 && start <= last) return null
            last = start; placed++
            return start
        }

        /** Полная длина в кадрах проекта (как во [timeline]). */
        val total: Int get() = maxOf(Math.round(t * f / 1000.0).toInt(), last + 1, 1)
    }
}
