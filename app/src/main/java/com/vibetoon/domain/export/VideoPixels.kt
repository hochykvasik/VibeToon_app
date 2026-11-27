package com.vibetoon.domain.export

/** BT.601 limited-range YUV420, RGBA readback has its first row at the GL bottom. */
object VideoPixels {
    data class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)
    fun fromGlRgba(rgba: ByteArray, width: Int, height: Int): Yuv {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
        require(width.toLong() * height * 4 == rgba.size.toLong())
        val y = ByteArray(width * height)
        val u = ByteArray(width * height / 4)
        val v = ByteArray(u.size)
        fun component(x: Int, row: Int, c: Int) = rgba[((height - 1 - row) * width + x) * 4 + c].toInt() and 255
        for (row in 0 until height) for (x in 0 until width) {
            val r = component(x, row, 0); val g = component(x, row, 1); val b = component(x, row, 2)
            y[row * width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235).toByte()
        }
        for (row in 0 until height step 2) for (x in 0 until width step 2) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                r += component(x + dx, row + dy, 0); g += component(x + dx, row + dy, 1); b += component(x + dx, row + dy, 2)
            }
            r = (r + 2) / 4; g = (g + 2) / 4; b = (b + 2) / 4
            val i = row / 2 * (width / 2) + x / 2
            u[i] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
            v[i] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240).toByte()
        }
        return Yuv(y, u, v)
    }
}
