package com.vibetoon.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.vibetoon.domain.export.ExportVideo
import java.io.Closeable
import java.io.File

/**
 * Пакет 29: кадры видео-слоёв для экспорта (MP4, GIF, PNG-кадры). Раньше экспорт не запрашивал кадры видео вовсе:
 * видео-слой весь ролик показывал один кадр - тот, что был на плейхеде в момент запуска. Теперь на каждый кадр
 * экспорта декодируется кадр источника (тот же расчёт момента, что при воспроизведении: начало клипа, скорость,
 * реверс, стоп-кадр, цикл) в размере кадра экспорта.
 *
 * Свой MediaMetadataRetriever на файл (не общий с воспроизведением в редакторе). Вызовы сериализованы: сборка сцены
 * в фоне и редкая догрузка в потоке отрисовки не пересекаются; [close] дожидается текущего декодирования.
 */
class ExportVideoFrames(private val exportW: Int, private val exportH: Int) : Closeable {
    private val lock = Any()
    private val retrievers = HashMap<String, MediaMetadataRetriever>()
    private var closed = false
    @Volatile var decoded = 0
        private set
    @Volatile var failed = 0
        private set

    /** Кадр источника [file] на моменте [timeMs] (ARGB_8888) или null - не вышло (слой остаётся с прежним кадром). */
    fun frame(file: File, timeMs: Long, srcW: Int, srcH: Int): Bitmap? {
        synchronized(lock) {
            if (closed || !file.isFile) return null
            return try {
                val r = retrievers.getOrPut(file.absolutePath) { MediaMetadataRetriever().also { it.setDataSource(file.absolutePath) } }
                val us = timeMs.coerceAtLeast(0L) * 1000L
                var bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27 && srcW > 0 && srcH > 0) {
                    val (w, h) = ExportVideo.decodeSize(srcW, srcH, exportW, exportH)
                    r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                } else r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) {
                    val c = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                    bmp = c
                }
                if (bmp == null) failed++ else decoded++
                bmp
            } catch (e: Exception) {
                failed++
                Log.w("VT/Export", "video frame $timeMs ms of ${file.name} failed: ${e.message}")
                null
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            retrievers.values.forEach { runCatching { it.release() } }
            retrievers.clear()
        }
    }
}
