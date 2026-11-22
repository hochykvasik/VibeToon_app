package com.vibetoon.domain.raster

/** Извлечение маски из RGBA premultiplied. Результат для Bitmap.setPixels — обычный ARGB. */
object SelectionExtract {
    data class Result(val argb: IntArray, val cut: RasterMath.FillResult)
    const val MAX_PIXELS = 4_194_304

    fun extract(rgba: ByteArray, offset: Int, width: Int, height: Int, mask: ByteArray): Result? {
        val pixels = width.toLong() * height
        if (width <= 0 || height <= 0 || pixels > MAX_PIXELS || offset < 0 ||
            pixels > mask.size.toLong() || offset.toLong() + pixels * 4 > rgba.size.toLong()) return null
        val cut = RasterMath.clearResult(rgba, offset, width, height, mask) ?: return null
        val argb = IntArray(pixels.toInt())
        var visible = false
        for (i in argb.indices) {
            if (mask[i].toInt() == 0) continue
            val o = offset + i * 4
            val a = rgba[o + 3].toInt() and 255
            if (a == 0) continue
            visible = true
            fun straight(channel: Int): Int = (((rgba[o + channel].toInt() and 255) * 255 + a / 2) / a).coerceAtMost(255)
            argb[i] = (a shl 24) or (straight(0) shl 16) or (straight(1) shl 8) or straight(2)
        }
        return if (visible) Result(argb, cut) else null
    }
}
