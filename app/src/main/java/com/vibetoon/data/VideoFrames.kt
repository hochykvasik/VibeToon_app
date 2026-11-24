package com.vibetoon.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.vibetoon.domain.perf.VideoDecodePolicy
import com.vibetoon.nativebridge.RenderNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Покадровое воспроизведение видео-слоёв: на каждый новый кадр проекта берётся кадр источника
 * (MediaMetadataRetriever на отдельном потоке) и подставляется в текстуру слоя. Запросы сливаются:
 * для слоя всегда выполняется только самый свежий. Источник декодируется в уменьшенном размере.
 *
 * Спринт 31: несколько видео-слоёв декодируются параллельно (каждый слой - строго по одному потоку за раз,
 * у него свой retriever), на Android 11+ кадр сразу приходит в ARGB_8888 (без лишней копии Bitmap),
 * а CPU-буфер копии в C++ берётся из пула (см. UploadQueue::acquireBuffer).
 */
class VideoFrames(private val onUploaded: () -> Unit) {
    private class Req(val file: File, val timeMs: Long, val w: Int, val h: Int, val ticket: Long)

    private val threads = VideoDecodePolicy.threads(Runtime.getRuntime().availableProcessors())
    private val counter = AtomicInteger()
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "vt-video-${counter.incrementAndGet()}").also { it.isDaemon = true } }
    private val pending = ConcurrentHashMap<Int, Req>()
    /** Слои, для которых сейчас работает поток: второй поток для того же слоя не запускается. */
    private val running = ConcurrentHashMap.newKeySet<Int>()
    /** Retriever слоя; доступ только под замком слоя [lockOf]. */
    private val retrievers = ConcurrentHashMap<Int, Pair<File, MediaMetadataRetriever>>()
    private val locks = ConcurrentHashMap<Int, Any>()
    private val lastMs = HashMap<Int, Long>()                                    // только из главного потока
    private val argbParams: Any? = if (Build.VERSION.SDK_INT >= 30) runCatching {
        MediaMetadataRetriever.BitmapParams().apply { setPreferredConfig(Bitmap.Config.ARGB_8888) }
    }.getOrNull() else null

    private fun lockOf(id: Int): Any = locks.getOrPut(id) { Any() }

    /** Запросить кадр источника [timeMs] для слоя [id]; повтор того же момента игнорируется. */
    fun request(id: Int, file: File, timeMs: Long, srcW: Int, srcH: Int, maxSide: Int) {
        if (lastMs[id] == timeMs) return
        lastMs[id] = timeMs
        val k = maxSide.toFloat() / max(srcW, srcH).coerceAtLeast(1)
        val s = if (k < 1f) k else 1f
        pending[id] = Req(file, timeMs, max(2, (srcW * s).toInt()), max(2, (srcH * s).toInt()), RenderNative.textureUploadTicket(id))
        schedule(id)
    }

    private fun schedule(id: Int) {
        if (!running.add(id)) return   // поток слоя уже работает и заберёт свежий запрос сам
        try { pool.execute { drainLayer(id) } }
        catch (e: java.util.concurrent.RejectedExecutionException) { running.remove(id) }
    }

    /** Сбросить запоминание момента (после замены текстуры другим способом). */
    fun forget(id: Int) { lastMs.remove(id) }

    private fun drainLayer(id: Int) {
        try {
            while (true) {
                val r = pending.remove(id) ?: break
                synchronized(lockOf(id)) { decodeAndUpload(id, r) }
            }
        } finally {
            running.remove(id)
            // запрос мог прийти между последней проверкой и снятием отметки
            if (pending.containsKey(id)) schedule(id)
        }
    }

    private fun decodeAndUpload(id: Int, r: Req) {
        try {
            val ret = retrievers[id]?.takeIf { it.first == r.file }?.second ?: run {
                retrievers.remove(id)?.second?.let { runCatching { it.release() } }
                val nr = MediaMetadataRetriever().also { it.setDataSource(r.file.absolutePath) }
                retrievers[id] = r.file to nr
                nr
            }
            val us = r.timeMs * 1000L
            val decoded: Bitmap = frameAt(ret, us, r.w, r.h) ?: return
            var bmp: Bitmap = decoded
            if (bmp.config != Bitmap.Config.ARGB_8888) {
                // старые Android: кадр приходит в RGB_565 - одна копия, исходник освобождается сразу
                val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                bmp.recycle()
                bmp = copy ?: return
            }
            val uploaded = RenderNative.uploadBitmapIfCurrent(id, bmp, r.ticket)
            bmp.recycle()
            if (uploaded) onUploaded()
        } catch (e: Exception) {
            Log.w("VT/Video", "frame ${r.timeMs} ms failed: ${e.message}")
        }
    }

    private fun frameAt(ret: MediaMetadataRetriever, us: Long, w: Int, h: Int): Bitmap? {
        val params = argbParams
        if (Build.VERSION.SDK_INT >= 30 && params is MediaMetadataRetriever.BitmapParams)
            return ret.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h, params)
        return if (Build.VERSION.SDK_INT >= 27) ret.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
        else ret.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    /** Смена проекта: запросы выбрасываются, retriever'ы закрываются (каждый под замком своего слоя). */
    fun release() {
        pending.clear()
        lastMs.clear()
        runCatching { pool.execute { releaseRetrievers() } }
    }

    private fun releaseRetrievers() {
        for (id in retrievers.keys.toList()) synchronized(lockOf(id)) {
            retrievers.remove(id)?.second?.let { runCatching { it.release() } }
        }
    }

    /** Закрытие ViewModel: потоки останавливаются после освобождения декодеров. */
    fun shutdown() {
        release()
        pool.shutdown()
    }
}
