package com.vibetoon.domain.perf

/**
 * Спринт 46: перевод кадра видео YUV 4:2:0 (MediaCodec, YUV_420_888) в ARGB нужного размера с поворотом из метаданных.
 * Раньше каждый кадр видео-слоя доставался MediaMetadataRetriever.getScaledFrameAtTime(OPTION_CLOSEST): это поиск
 * от ближайшего опорного кадра и декодирование всей группы кадров на КАЖДЫЙ кадр проекта - видео 60 fps в проекте
 * 30 fps шло слайд-шоу. Теперь декодер идёт по файлу последовательно (VideoStream), а кадр переводится здесь.
 * Чистые функции без Android - Sprint46Test.
 */
object VideoYuv {
    /** Матрица YUV -> RGB в 1/1024: коэффициенты R(V), G(U), G(V), B(U), масштаб Y, смещение Y. */
    class Matrix(val rv: Int, val gu: Int, val gv: Int, val bu: Int, val ys: Int, val y0: Int)

    // MediaFormat.COLOR_STANDARD_* и COLOR_RANGE_* (копия констант - домен без Android)
    const val STANDARD_BT709 = 1
    const val STANDARD_BT601_PAL = 2
    const val STANDARD_BT601_NTSC = 4
    const val STANDARD_BT2020 = 6
    const val RANGE_FULL = 1
    const val RANGE_LIMITED = 2

    /**
     * Матрица по метаданным потока: [standard]/[range] - значения MediaFormat или 0 (нет данных). Без данных - BT.709 для
     * HD (высота кадра от 720) и BT.601 для SD, ограниченный диапазон (16..235) - как у камер телефонов и большинства MP4.
     */
    fun matrix(standard: Int, range: Int, height: Int): Matrix {
        val std = when (standard) {
            STANDARD_BT709, STANDARD_BT601_PAL, STANDARD_BT601_NTSC, STANDARD_BT2020 -> standard
            else -> if (height >= 720) STANDARD_BT709 else STANDARD_BT601_NTSC
        }
        val full = range == RANGE_FULL
        // Kr, Kb стандарта -> коэффициенты
        val (kr, kb) = when (std) {
            STANDARD_BT709 -> 0.2126 to 0.0722
            STANDARD_BT2020 -> 0.2627 to 0.0593
            else -> 0.299 to 0.114
        }
        val kg = 1.0 - kr - kb
        val cs = if (full) 1.0 else 255.0 / 224.0      // размах цветности 16..240
        val rv = 2.0 * (1.0 - kr) * cs
        val bu = 2.0 * (1.0 - kb) * cs
        val gu = 2.0 * (1.0 - kb) * kb / kg * cs
        val gv = 2.0 * (1.0 - kr) * kr / kg * cs
        val ys = if (full) 1.0 else 255.0 / 219.0       // размах яркости 16..235
        fun q(v: Double) = Math.round(v * 1024.0).toInt()
        return Matrix(q(rv), q(gu), q(gv), q(bu), q(ys), if (full) 0 else 16)
    }

    /** Один пиксель (Y, U, V 0..255) -> ARGB (непрозрачный). */
    fun argb(y: Int, u: Int, v: Int, m: Matrix): Int {
        val yy = (y - m.y0) * m.ys
        val du = u - 128; val dv = v - 128
        val r = clamp((yy + m.rv * dv + 512) shr 10)
        val g = clamp((yy - m.gu * du - m.gv * dv + 512) shr 10)
        val b = clamp((yy + m.bu * du + 512) shr 10)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /** Размер кадра на экране с учётом поворота (90/270 - стороны меняются местами). */
    fun displaySize(w: Int, h: Int, rotation: Int): Pair<Int, Int> = if (norm(rotation) % 180 == 90) h to w else w to h

    fun norm(rotation: Int): Int = ((rotation % 360) + 360) % 360 / 90 * 90

    /**
     * Точка экрана (dx, dy) в пикселях повёрнутого кадра [dw]×[dh] -> пиксель кадра декодера [sw]×[sh]. Поворот - по
     * часовой стрелке, как KEY_ROTATION / METADATA_KEY_VIDEO_ROTATION: при 90 левый верхний угол экрана - левый нижний кадра.
     */
    fun sourceOf(dx: Int, dy: Int, sw: Int, sh: Int, rotation: Int): Pair<Int, Int> = when (norm(rotation)) {
        90 -> dy to (sh - 1 - dx)
        180 -> (sw - 1 - dx) to (sh - 1 - dy)
        270 -> (sw - 1 - dy) to dx
        else -> dx to dy
    }

    /**
     * Кадр декодера (плоскости [yP], [uP], [vP] с шагами строк и пикселей; [cropX], [cropY], [sw]×[sh] - видимая часть) ->
     * ARGB [out] размера [w]×[h] (экранная ориентация после [rotation]). [smooth] - экспорт и пауза: при уменьшении
     * больше чем в 1.5 раза яркость усредняется по 4 точкам (без «лесенки» и ряби), иначе - билинейно; при
     * воспроизведении - ближайший пиксель (быстрее, кадр всё равно сменится через 1/fps).
     */
    fun convert(
        yP: ByteArray, yRow: Int, yPix: Int,
        uP: ByteArray, vP: ByteArray, cRow: Int, cPix: Int,
        cropX: Int, cropY: Int, sw: Int, sh: Int, rotation: Int,
        out: IntArray, w: Int, h: Int, m: Matrix, smooth: Boolean
    ) {
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0 || out.size < w * h) return
        val rot = norm(rotation)
        val swap = rot % 180 == 90
        val dw = if (swap) sh else sw
        val dh = if (swap) sw else sh
        // экранные координаты центров выходных пикселей (в пикселях повёрнутого кадра, *256 - субпиксель)
        val fx = IntArray(w) { x -> (((x + 0.5) * dw / w - 0.5) * 256).toInt() }
        val fy = IntArray(h) { y -> (((y + 0.5) * dh / h - 0.5) * 256).toInt() }
        val scale = maxOf(dw.toFloat() / w, dh.toFloat() / h)
        val quad = smooth && scale >= 1.5f
        val q = (scale * 64f).toInt().coerceAtMost(255)   // четверть шага выхода в 1/256 пикселя

        fun yAt(sx: Int, sy: Int): Int {
            val x = sx.coerceIn(0, sw - 1) + cropX; val y = sy.coerceIn(0, sh - 1) + cropY
            return yP[y * yRow + x * yPix].toInt() and 0xFF
        }
        // пиксель экрана (в 1/256) -> пиксель источника (в 1/256)
        fun srcX(ex: Int, ey: Int): Int = when (rot) { 90 -> ey; 180 -> (sw - 1) * 256 - ex; 270 -> (sw - 1) * 256 - ey; else -> ex }
        fun srcY(ex: Int, ey: Int): Int = when (rot) { 90 -> (sh - 1) * 256 - ex; 180 -> (sh - 1) * 256 - ey; 270 -> ex; else -> ey }
        fun bilinear(px: Int, py: Int): Int {
            val x0 = px shr 8; val y0 = py shr 8; val ax = px and 0xFF; val ay = py and 0xFF
            val a = yAt(x0, y0); val b = yAt(x0 + 1, y0); val c = yAt(x0, y0 + 1); val d = yAt(x0 + 1, y0 + 1)
            val top = a * (256 - ax) + b * ax; val bot = c * (256 - ax) + d * ax
            return (top * (256 - ay) + bot * ay + 32768) shr 16
        }

        var o = 0
        for (y in 0 until h) {
            val ey = fy[y]
            for (x in 0 until w) {
                val ex = fx[x]
                val px = srcX(ex, ey); val py = srcY(ex, ey)
                val sx = ((px + 128) shr 8).coerceIn(0, sw - 1); val sy = ((py + 128) shr 8).coerceIn(0, sh - 1)
                val yy = when {
                    !smooth -> yAt(sx, sy)
                    quad -> (bilinear(px - q, py - q) + bilinear(px + q, py - q) + bilinear(px - q, py + q) + bilinear(px + q, py + q) + 2) shr 2
                    else -> bilinear(px.coerceAtLeast(0), py.coerceAtLeast(0))
                }
                val ci = ((sy + cropY) shr 1) * cRow + ((sx + cropX) shr 1) * cPix
                out[o++] = argb(yy, uP[ci].toInt() and 0xFF, vP[ci].toInt() and 0xFF, m)
            }
        }
    }
}
