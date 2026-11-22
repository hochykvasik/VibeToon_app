package com.vibetoon.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.vibetoon.render.VtGLSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Миниатюра проекта для списка: снимок GL-вида (PixelCopy), обрезанный по границам холста. */
object ThumbnailCapture {
    private const val MAX_WIDTH = 480

    suspend fun capture(view: VtGLSurfaceView?, canvasW: Int, canvasH: Int, out: File): Boolean {
        if (view == null || view.width <= 0 || view.height <= 0) return false
        val w = view.width
        val h = view.height
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(view, full, { result -> cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w("VT/Project", "PixelCopy failed: ${e.message}")
                cont.resume(false)
            }
        }
        if (!ok) return false
        // Холст вписан в вид с полями 5% (камера сброшена): вырезаем его прямоугольник
        val fit = 0.95f * min(w.toFloat() / canvasW, h.toFloat() / canvasH)
        val cw = (canvasW * fit).roundToInt().coerceIn(1, w)
        val ch = (canvasH * fit).roundToInt().coerceIn(1, h)
        val left = max(0, ((w - cw) / 2f).roundToInt()).coerceAtMost(w - cw)
        val top = max(0, ((h - ch) / 2f).roundToInt()).coerceAtMost(h - ch)
        val cropped = Bitmap.createBitmap(full, left, top, cw, ch)
        val scale = min(1f, MAX_WIDTH.toFloat() / cw)
        val thumb = if (scale < 1f) Bitmap.createScaledBitmap(cropped, (cw * scale).roundToInt().coerceAtLeast(1), (ch * scale).roundToInt().coerceAtLeast(1), true) else cropped
        return withContext(Dispatchers.IO) {
            out.parentFile?.mkdirs()
            out.outputStream().use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Log.i("VT/Project", "thumbnail ${thumb.width}x${thumb.height} -> ${out.name}")
            true
        }
    }
}
