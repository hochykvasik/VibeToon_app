package com.vibetoon.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.vibetoon.nativebridge.RenderNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Покадровое воспроизведение видео-слоёв: на каждый новый кадр проекта берётся кадр источника
 * (MediaMetadataRetriever на отдельном потоке) и подставляется в текстуру слоя. Запросы сливаются:
 * для слоя всегда выполняется только самый свежий. Источник декодируется в уменьшенном размере.
 */
class VideoFrames(private val onUploaded: () -> Unit) {
    private class Req(val file: File, val timeMs: Long, val w: Int, val h: Int, val ticket: Long)

    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "vt-video").also { it.isDaemon = true } }
    private val pending = ConcurrentHashMap<Int, Req>()
    private val retrievers = HashMap<Int, Pair<File, MediaMetadataRetriever>>()   // только из потока pool
    private val lastMs = HashMap<Int, Long>()                                    // только из главного потока

    /** Запросить кадр источника [timeMs] для слоя [id]; повтор того же момента игнорируется. */
    fun request(id: Int, file: File, timeMs: Long, srcW: Int, srcH: Int, maxSide: Int) {
        if (lastMs[id] == timeMs) return
        lastMs[id] = timeMs
        val k = maxSide.toFloat() / max(srcW, srcH).coerceAtLeast(1)
        val s = if (k < 1f) k else 1f
        pending[id] = Req(file, timeMs, max(2, (srcW * s).toInt()), max(2, (srcH * s).toInt()), RenderNative.textureUploadTicket(id))
        pool.execute { drain() }
    }

    /** Сбросить запоминание момента (после замены текстуры другим способом). */
    fun forget(id: Int) { lastMs.remove(id) }

    private fun drain() {
        for (id in pending.keys.toList()) {
            val r = pending.remove(id) ?: continue
            try {
                val (f, ret) = retrievers[id]?.takeIf { it.first == r.file } ?: run {
                    retrievers.remove(id)?.second?.release()
                    val nr = MediaMetadataRetriever().also { it.setDataSource(r.file.absolutePath) }
                    (r.file to nr).also { retrievers[id] = it }
                }
                val us = r.timeMs * 1000L
                var bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) ret.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, r.w, r.h)
                else ret.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp == null) continue
                if (bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
                if (bmp != null) {
                    val uploaded = RenderNative.uploadBitmapIfCurrent(id, bmp, r.ticket)
                    bmp.recycle()
                    if (uploaded) onUploaded()
                }
                if (f != r.file) Unit
            } catch (e: Exception) {
                Log.w("VT/Video", "frame ${r.timeMs} ms failed: ${e.message}")
            }
        }
    }

    fun release() {
        pending.clear()
        lastMs.clear()
        pool.execute { retrievers.values.forEach { runCatching { it.second.release() } }; retrievers.clear() }
    }
}
