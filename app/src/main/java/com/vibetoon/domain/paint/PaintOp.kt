package com.vibetoon.domain.paint

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Запись Undo/Redo одного штриха: прямоугольник слоя ([x], [y], [w], [h]) в пикселях текстуры, пиксели "до" и "после"
 * (RGBA premultiplied). Сразу после штриха буферы лежат как есть; [compress] сжимает их Deflater.BEST_SPEED в фоне,
 * после чего хранится только сжатая копия. [evict] освобождает данные (вытеснение при нехватке памяти Undo).
 */
class PaintOp(val layerId: Int, val x: Int, val y: Int, val w: Int, val h: Int, before: ByteArray, after: ByteArray) {
    @Volatile private var rawBefore: ByteArray? = before
    @Volatile private var rawAfter: ByteArray? = after
    @Volatile private var zBefore: ByteArray? = null
    @Volatile private var zAfter: ByteArray? = null
    /** Файл рисунка покадрового слоя, в котором нанесён штрих (null - обычный слой). Undo сначала возвращает этот рисунок на экран. */
    @Volatile var cellFile: String? = null
    @Volatile var evicted = false
        private set

    private val rawSize = w * h * 4

    /** Сколько байт сейчас занимает запись в памяти. */
    val sizeBytes: Long
        get() = if (evicted) 0L else (rawBefore?.size ?: zBefore?.size ?: 0).toLong() + (rawAfter?.size ?: zAfter?.size ?: 0).toLong()

    /** Сжимает буферы (вызывать из фонового потока). */
    fun compress() {
        val b = rawBefore; val a = rawAfter
        if (b != null) { zBefore = deflate(b); rawBefore = null }
        if (a != null) { zAfter = deflate(a); rawAfter = null }
    }

    fun evict() { evicted = true; rawBefore = null; rawAfter = null; zBefore = null; zAfter = null }

    fun before(): ByteArray? = if (evicted) null else rawBefore ?: zBefore?.let { inflate(it, rawSize) }
    fun after(): ByteArray? = if (evicted) null else rawAfter ?: zAfter?.let { inflate(it, rawSize) }

    companion object {
        /** Разбирает ответ `paintEnd`: x, y, w, h (int32 LE), затем "до" и "после". null - некорректный буфер. */
        fun parse(layerId: Int, bytes: ByteArray): PaintOp? {
            if (bytes.size < 16) return null
            fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
            val x = i32(0); val y = i32(4); val w = i32(8); val h = i32(12)
            val n = w * h * 4
            if (w <= 0 || h <= 0 || bytes.size < 16 + 2 * n) return null
            return PaintOp(layerId, x, y, w, h, bytes.copyOfRange(16, 16 + n), bytes.copyOfRange(16 + n, 16 + 2 * n))
        }

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 4))
            val buf = ByteArray(16 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        fun inflate(z: ByteArray, size: Int): ByteArray {
            val inf = Inflater()
            inf.setInput(z)
            val out = ByteArray(size)
            var off = 0
            while (off < size && !inf.finished()) {
                val n = inf.inflate(out, off, size - off)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                off += n
            }
            inf.end()
            return out
        }
    }
}

/** Бюджет памяти стеков Undo/Redo: при превышении вытесняются самые старые записи (см. [overflow]). */
object PaintBudget {
    const val LIMIT_BYTES = 160L * 1024 * 1024

    /** Сколько самых старых записей (индексы с начала списка [sizes]) нужно вытеснить, чтобы уложиться в [limit]. */
    fun overflow(sizes: List<Long>, limit: Long = LIMIT_BYTES): Int {
        var total = sizes.sum()
        var n = 0
        while (total > limit && n < sizes.size) { total -= sizes[n]; n++ }
        return n
    }
}
