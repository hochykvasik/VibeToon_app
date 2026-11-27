package com.vibetoon.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Плёнка видео-клипов на таймлайне (пакет 11): маленькие кадры источника декодируются в фоне
 * (MediaMetadataRetriever, ближайший опорный кадр - быстро) и кэшируются (LRU). Отрисовка таймлайна не ждёт:
 * пока кадра нет, плитка пустая; когда кадр готов, [onReady] просит перерисовку (ViewModel склеивает пачку в одну).
 */
class VideoThumbs(private val onReady: () -> Unit) {
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "vt-filmstrip").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY } }
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_CACHED
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0
    /** Открытые источники - только из потока [pool]. */
    private val retrievers = LinkedHashMap<String, MediaMetadataRetriever>()

    /** Кадр [ms] файла [file] высотой [h] px или null (тогда он уже заказан). */
    fun get(file: File, ms: Long, srcW: Int, srcH: Int, h: Int = THUMB_H): ImageBitmap? {
        val key = "${file.path}#$ms"
        synchronized(cache) { cache[key] }?.let { return it }
        if (key in failed || inFlight.size >= MAX_IN_FLIGHT || !inFlight.add(key)) return null
        val gen = generation
        pool.execute {
            try {
                if (gen != generation) return@execute
                val bmp = decode(file, ms, srcW, srcH, h)
                if (bmp == null) failed += key
                else if (gen == generation) synchronized(cache) { cache[key] = bmp.asImageBitmap() }
            } catch (t: Throwable) {
                failed += key
                Log.w("VT/Filmstrip", "frame $ms of ${file.name} failed: ${t.message}")
            } finally {
                inFlight.remove(key)
                onReady()
            }
        }
        return null
    }

    private fun decode(file: File, ms: Long, srcW: Int, srcH: Int, h: Int): Bitmap? {
        val ret = retrievers[file.path] ?: run {
            if (retrievers.size >= 3) retrievers.remove(retrievers.keys.first())?.let { runCatching { it.release() } }
            MediaMetadataRetriever().also { it.setDataSource(file.absolutePath); retrievers[file.path] = it }
        }
        val th = h.coerceAtLeast(8)
        val tw = if (srcW > 0 && srcH > 0) max(8, th * srcW / srcH) else th * 16 / 9
        val us = ms * 1000L
        var bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) ret.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        else ret.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
            Bitmap.createScaledBitmap(b, tw, th, true).also { if (it !== b) b.recycle() }
        }
        if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        return bmp
    }

    /** Сброс при смене проекта. */
    fun clear() {
        generation++
        synchronized(cache) { cache.clear() }
        failed.clear()
        pool.execute { retrievers.values.forEach { runCatching { it.release() } }; retrievers.clear() }
    }

    fun release() {
        clear()
        pool.shutdown()
    }

    private companion object {
        const val THUMB_H = 48
        const val MAX_CACHED = 240
        const val MAX_IN_FLIGHT = 24
    }
}
