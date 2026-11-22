package com.vibetoon.domain.export

import java.io.OutputStream

/** Запись PCM 16 бит в WAV (RIFF): заголовок 44 байта и данные little-endian. */
object WavMath {
    fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + dataBytes); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(sampleRate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes)
        return b.array()
    }

    fun write(out: OutputStream, pcm: ShortArray, sampleRate: Int, channels: Int) {
        out.write(header(sampleRate, channels, pcm.size * 2))
        val chunk = ByteArray(8192 * 2)
        var i = 0
        while (i < pcm.size) {
            val n = minOf(8192, pcm.size - i)
            for (k in 0 until n) { val v = pcm[i + k].toInt(); chunk[k * 2] = v.toByte(); chunk[k * 2 + 1] = (v shr 8).toByte() }
            out.write(chunk, 0, n * 2)
            i += n
        }
    }
}
