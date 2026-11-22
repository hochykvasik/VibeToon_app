package com.vibetoon.domain.render

/**
 * RGBA -> YUV 4:2:0 (BT.601, ограниченный диапазон 16..235 / 16..240) для «совместимого» экспорта видео: кадр читается
 * из GL и сам кладётся в буферы кодека с учётом их шага строки и шага пикселя (I420, NV12, NV21 - любой вариант
 * YUV420Flexible). Путь через Surface кодека быстрее, но на части устройств даёт зелёные полосы.
 */
object YuvMath {
    /**
     * [rgba] - premultiplied RGBA, [w] x [h]; [bottomUp] - строка 0 внизу (как отдаёт glReadPixels). Альфа игнорируется:
     * кадр экспорта всегда непрозрачный. Результат пишется в [y] (w*h) и [u], [v] ((w/2)*(h/2), w и h чётные).
     */
    fun rgbaToI420(rgba: ByteArray, w: Int, h: Int, bottomUp: Boolean, y: ByteArray, u: ByteArray, v: ByteArray) {
        require(w % 2 == 0 && h % 2 == 0 && rgba.size >= w * h * 4) { "размер $w x $h" }
        val cw = w / 2
        for (row in 0 until h) {
            val src = (if (bottomUp) h - 1 - row else row) * w * 4
            val dst = row * w
            for (x in 0 until w) {
                val o = src + x * 4
                val r = rgba[o].toInt() and 0xFF; val g = rgba[o + 1].toInt() and 0xFF; val b = rgba[o + 2].toInt() and 0xFF
                y[dst + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
        }
        for (row in 0 until h / 2) {
            val r0 = (if (bottomUp) h - 1 - 2 * row else 2 * row) * w * 4
            val r1 = (if (bottomUp) h - 2 - 2 * row else 2 * row + 1) * w * 4
            for (x in 0 until cw) {
                var r = 0; var g = 0; var b = 0
                for (o in intArrayOf(r0 + x * 8, r0 + x * 8 + 4, r1 + x * 8, r1 + x * 8 + 4)) {
                    r += rgba[o].toInt() and 0xFF; g += rgba[o + 1].toInt() and 0xFF; b += rgba[o + 2].toInt() and 0xFF
                }
                r = (r + 2) shr 2; g = (g + 2) shr 2; b = (b + 2) shr 2
                u[row * cw + x] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                v[row * cw + x] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
            }
        }
    }

    /**
     * Копирует плоскость [src] (ширина [w], высота [h], плотно) в буфер с шагом строки [rowStride] и шагом пикселя
     * [pixelStride] через [put] (индекс, байт). Так пишутся плоскости Image кодека любой раскладки.
     */
    inline fun copyPlane(src: ByteArray, w: Int, h: Int, rowStride: Int, pixelStride: Int, put: (Int, Byte) -> Unit) {
        for (row in 0 until h) {
            val s = row * w; val d = row * rowStride
            for (x in 0 until w) put(d + x * pixelStride, src[s + x])
        }
    }
}
