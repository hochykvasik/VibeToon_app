package com.vibetoon.domain.project

import java.nio.ByteBuffer

/**
 * Бинарный кэш волновой формы (.wave рядом с аудиофайлом): заголовок "VTW1", длина и дата изменения исходника
 * (по ним кэш признаётся актуальным), число значений, затем пики по одному байту (0..255 = 0..1).
 */
object WaveCache {
    private const val MAGIC = 0x56545731   // "VTW1"
    private const val HEADER = 4 + 8 + 8 + 4

    fun encode(peaks: FloatArray, srcLength: Long, srcModified: Long): ByteArray {
        val b = ByteBuffer.allocate(HEADER + peaks.size)
        b.putInt(MAGIC).putLong(srcLength).putLong(srcModified).putInt(peaks.size)
        for (p in peaks) b.put((p.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        return b.array()
    }

    /** Пики из кэша или null: неверный формат либо исходный файл изменился. */
    fun decode(bytes: ByteArray, srcLength: Long, srcModified: Long): FloatArray? {
        if (bytes.size < HEADER) return null
        val b = ByteBuffer.wrap(bytes)
        if (b.int != MAGIC || b.long != srcLength || b.long != srcModified) return null
        val n = b.int
        if (n < 0 || bytes.size != HEADER + n) return null
        return FloatArray(n) { (b.get().toInt() and 0xFF) / 255f }
    }
}
