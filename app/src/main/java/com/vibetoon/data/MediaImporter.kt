package com.vibetoon.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.max

private const val TAG = "VT/Media"

/** Картинка или кадр видео, готовые к загрузке в текстуру. */
class DecodedMedia(val bitmap: Bitmap, val srcWidth: Int, val srcHeight: Int, val durationMs: Long = 0L)

/**
 * Импорт медиа: копирование файла в папку проекта и декодирование для текстуры. Все методы
 * блокирующие, вызывать с фонового потока. Большие картинки уменьшаются до [MAX_SIDE] px по
 * длинной стороне ещё при декодировании (inSampleSize), чтобы не переполнить память.
 */
class MediaImporter(private val context: Context) {

    fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "media"

    /** Копирует выбранный файл потоком (видео бывают большими) и возвращает размер в байтах. */
    fun copy(uri: Uri, dest: File): Long {
        dest.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        return dest.length()
    }

    /** Расширение исходного файла в нижнем регистре (без точки) или [fallback]. */
    fun extension(name: String, fallback: String): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 } ?: fallback

    /** PNG/JPG/WebP: размеры считаются с учётом EXIF-поворота, длинная сторона не больше [MAX_SIDE]. */
    fun decodeImage(file: File): DecodedMedia {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Не удалось прочитать изображение")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888   // premultiplied; не HARDWARE: C++ читает пиксели
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: throw IOException("Не удалось декодировать изображение")
        bmp = applyExifRotation(file, bmp)
        bmp = shrink(bmp)
        Log.i(TAG, "image ${file.name}: ${bounds.outWidth}x${bounds.outHeight} -> ${bmp.width}x${bmp.height} (sample $sample)")
        return DecodedMedia(bmp, bmp.width, bmp.height)
    }

    /** MP4: первый кадр как статичная текстура; сам проигрыватель появится позже. */
    fun decodeVideoFrame(file: File): DecodedMedia {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: throw IOException("Не удалось получить кадр видео")
            val soft = if (frame.config == Bitmap.Config.ARGB_8888) frame else frame.copy(Bitmap.Config.ARGB_8888, false)
            val bmp = shrink(soft)
            Log.i(TAG, "video ${file.name}: frame ${bmp.width}x${bmp.height}, ${duration} ms")
            return DecodedMedia(bmp, bmp.width, bmp.height, duration)
        } finally {
            r.release()
        }
    }

    /** MP3/WAV: нужна только длительность, звук не декодируется. */
    fun audioDurationMs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "audio duration unknown: ${e.message}")
            0L
        } finally {
            r.release()
        }
    }

    private fun shrink(bmp: Bitmap): Bitmap {
        val longest = max(bmp.width, bmp.height)
        if (longest <= MAX_SIDE) return bmp
        val k = MAX_SIDE.toFloat() / longest
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun applyExifRotation(file: File, bmp: Bitmap): Bitmap {
        val degrees = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    companion object {
        /** Предел длинной стороны текстуры: гарантированный минимум GL_MAX_TEXTURE_SIZE в ES 3.0. */
        const val MAX_SIDE = 2048
    }
}
