package com.vibetoon.domain.project

import java.io.EOFException
import java.io.InputStream

/**
 * Пакет 16: дополнительные сведения о слоях PSD, которые нативный парсер не отдаёт - замки слоёв (блок `lspf`)
 * и текстовые слои (блок `TySh`: текст, размер шрифта, цвет, выравнивание, трансформация).
 *
 * Читаются только записи слоёв (заголовки), пиксели не загружаются: даже большой PSD сканируется быстро и без
 * лишней памяти. Номер записи совпадает с id слоя из нативного парсера (0 - самая нижняя запись). Чистый JVM-код,
 * проверяется unit-тестом. Любая ошибка разбора даёт пустой результат - импорт PSD идёт как раньше.
 */
object PsdLayerExtras {
    /** Флаги `lspf`. */
    const val LOCK_TRANSPARENCY = 1
    const val LOCK_PIXELS = 2
    const val LOCK_POSITION = 4
    const val LOCK_ALL = Int.MIN_VALUE   // 0x80000000

    private const val MAX_TEXT = 10_000
    private const val MAX_LAYERS = 20_000

    /** Текст слоя PSD. [size] - размер шрифта в пикселях PSD (с учётом масштаба трансформации), [color] - ARGB или null. */
    data class Text(val text: String, val size: Float?, val color: Long?, val align: Int, val scale: Float)

    data class Extra(
        val index: Int,
        val top: Int, val left: Int, val bottom: Int, val right: Int,
        val lockFlags: Int = 0,
        val text: Text? = null
    ) {
        /** Перенос в VibeToon: «замок прозрачности» - alphaLock, замок пикселей/положения/всего слоя - обычный замок. */
        val alphaLock: Boolean get() = lockFlags and LOCK_TRANSPARENCY != 0 && !locked
        val locked: Boolean get() = lockFlags and (LOCK_PIXELS or LOCK_POSITION or LOCK_ALL) != 0
    }

    private class Reader(private val input: InputStream) {
        var pos = 0L
            private set

        fun u8(): Int { val b = input.read(); if (b < 0) throw EOFException(); pos++; return b }
        fun u16(): Int = (u8() shl 8) or u8()
        fun i16(): Int = u16().toShort().toInt()
        fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n); var r = 0
            while (r < n) { val k = input.read(b, r, n - r); if (k < 0) throw EOFException(); r += k }
            pos += n; return b
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = input.skip(left)
                if (k <= 0) { u8(); left--; continue }   // skip() может вернуть 0 - читаем побайтно
                left -= k; pos += k
            }
        }
    }

    /** Сведения о слоях или пустой список (не PSD, PSB, нет слоёв, повреждённый файл). */
    fun read(input: InputStream): List<Extra> = try { readOrThrow(input.buffered(1 shl 16)) } catch (e: Exception) { emptyList() }

    private fun readOrThrow(input: InputStream): List<Extra> {
        val r = Reader(input)
        val sig = r.bytes(4)
        if (String(sig, Charsets.US_ASCII) != "8BPS" || r.u16() != 1) return emptyList()
        r.skip(6 + 2 + 4 + 4 + 2 + 2)   // reserved, channels, height, width, depth, mode
        r.skip(r.u32())   // color mode data
        r.skip(r.u32())   // image resources
        val lmLen = r.u32()
        if (lmLen < 4) return emptyList()
        val layerInfoLen = r.u32()
        if (layerInfoLen < 2) return emptyList()
        val count = kotlin.math.abs(r.i16())
        if (count == 0 || count > MAX_LAYERS) return emptyList()
        val out = ArrayList<Extra>(count)
        for (i in 0 until count) {
            val top = r.i32(); val left = r.i32(); val bottom = r.i32(); val right = r.i32()
            val channels = r.u16()
            if (channels > 64) return emptyList()
            r.skip(channels * 6L)
            if (String(r.bytes(4), Charsets.US_ASCII) != "8BIM") return emptyList()
            r.skip(4 + 4)   // blend key, opacity, clipping, flags, filler
            val extraLen = r.u32()
            val extraEnd = r.pos + extraLen
            r.skip(r.u32())   // layer mask
            r.skip(r.u32())   // blending ranges
            val nameLen = r.u8()
            r.skip(((nameLen + 1 + 3) / 4 * 4 - 1).toLong())   // pascal-строка, выровненная по 4
            var lock = 0
            var text: Text? = null
            while (r.pos + 12 <= extraEnd) {
                val bsig = String(r.bytes(4), Charsets.US_ASCII)
                if (bsig != "8BIM" && bsig != "8B64") break
                val key = String(r.bytes(4), Charsets.US_ASCII)
                val len = if (bsig == "8B64") (r.u32() shl 32) or r.u32() else r.u32()
                if (len < 0 || r.pos + len > extraEnd) break
                when (key) {
                    "lspf" -> if (len >= 4) { lock = r.i32(); r.skip(len - 4) } else r.skip(len)
                    "TySh" -> if (len <= 8L * 1024 * 1024) text = parseTySh(r.bytes(len.toInt())) else r.skip(len)
                    else -> r.skip(len)
                }
                if (len % 2 == 1L && r.pos < extraEnd) r.skip(1)   // данные блока выравниваются до чётной длины (как в psd_sdk)
            }
            if (r.pos > extraEnd) return emptyList()
            r.skip(extraEnd - r.pos)
            out += Extra(i, top, left, bottom, right, lock, text)
        }
        return out
    }

    /**
     * `TySh`: версия, трансформация (6 double), дескриптор текста. Текст берётся из ключа `Txt ` (TEXT, UTF-16),
     * размер, цвет и выравнивание - из EngineData (первый стиль и абзац).
     */
    fun parseTySh(d: ByteArray): Text? {
        if (d.size < 2 + 48) return null
        val buf = java.nio.ByteBuffer.wrap(d)
        buf.position(2)
        val xx = buf.double; val xy = buf.double; val yx = buf.double; val yy = buf.double
        val det = kotlin.math.abs(xx * yy - xy * yx)
        val scale = if (det.isFinite() && det > 1e-9) kotlin.math.sqrt(det).toFloat() else 1f
        val raw = textOf(d) ?: return null
        val text = raw.trimEnd('\u0000').replace("\r\n", "\n").replace('\r', '\n').replace('\u0003', '\n')
        if (text.isBlank()) return null
        val engine = engineData(d)
        val fontSize = engine?.let { Regex("/FontSize\\s+(-?[0-9.]+)").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
        val color = engine?.let { e ->
            Regex("/FillColor\\s*<<\\s*/Type\\s+1\\s*/Values\\s*\\[\\s*([0-9.\\s-]+)\\]").find(e)?.groupValues?.get(1)
                ?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 }
                ?.let { v -> argb(v[0], v[1], v[2], v[3]) }
        }
        val just = engine?.let { Regex("/Justification\\s+([0-9]+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        return Text(text.take(MAX_TEXT), fontSize?.takeIf { it > 0f && it.isFinite() }?.times(scale), color, alignOf(just), scale)
    }

    /** Выравнивание Photoshop (0 слева, 1 справа, 2 по центру, 3+ по ширине) -> VibeToon (0 слева, 1 центр, 2 справа). */
    fun alignOf(justification: Int): Int = when (justification) { 1 -> 2; 2 -> 1; else -> 0 }

    fun argb(a: Float, r: Float, g: Float, b: Float): Long {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toLong()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    private fun indexOf(d: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..d.size - pattern.size) {
            for (j in pattern.indices) if (d[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    /** Ключ `Txt ` в дескрипторе: [0000]"Txt " "TEXT" [длина] UTF-16BE. */
    private fun textOf(d: ByteArray): String? {
        val at = indexOf(d, "Txt TEXT".toByteArray(Charsets.US_ASCII))
        if (at < 0 || at + 12 > d.size) return null
        val p = at + 8
        val n = ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
        if (n < 0 || n > MAX_TEXT * 4) return null
        val bytes = minOf(n * 2, d.size - p - 4)
        return String(d, p + 4, bytes - bytes % 2, Charsets.UTF_16BE)
    }

    /** EngineData (`tdta`) как Latin-1 строка: числа и ключи в ней - ASCII. */
    private fun engineData(d: ByteArray): String? {
        val at = indexOf(d, "EngineDatatdta".toByteArray(Charsets.US_ASCII))
        if (at < 0 || at + 18 > d.size) return null
        val p = at + 14
        val n = ((d[p].toInt() and 0xFF) shl 24) or ((d[p + 1].toInt() and 0xFF) shl 16) or ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
        if (n <= 0) return null
        return String(d, p + 4, minOf(n, d.size - p - 4), Charsets.ISO_8859_1)
    }

    /** Где и каким размером поставить редактируемый текст вместо текстового слоя PSD (координаты холста). */
    data class Placement(val x: Float, val y: Float, val size: Float)

    /**
     * [scale], [tx], [ty] - вписывание PSD в холст. Размер шрифта - из EngineData, а если его нет - по высоте рамки
     * слоя и числу строк. Верх-лево текста совпадает с рамкой слоя PSD.
     */
    fun placement(e: Extra, t: Text, scale: Float, tx: Float, ty: Float): Placement {
        val lines = t.text.count { it == '\n' } + 1
        val boxH = (e.bottom - e.top).coerceAtLeast(1).toFloat()
        val size = t.size ?: (boxH / lines / 1.2f)
        return Placement(e.left * scale + tx, e.top * scale + ty, size * scale)
    }
}
