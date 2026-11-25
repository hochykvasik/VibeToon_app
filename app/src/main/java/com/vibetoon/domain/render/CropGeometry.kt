package com.vibetoon.domain.render

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LayerCrop
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Спринт 46: обрезка слоя меняет его геометрию. Единичный квадрат слоя в сцене - видимая рамка картинки
 * (раньше - вся картинка, а лишнее отбрасывал шейдер), поэтому рамка выделения, углы перспективы, сетки Mesh/Warp,
 * булавки, касания и групповые рамки строятся по видимой части. Рендерер берёт текстуру из той же рамки
 * (CropPolicy.h: uvRect/toTexture). Чистые функции - Sprint46Test.
 */
object CropGeometry {
    /** Матрица единичного квадрата -> видимая часть прямоугольника слоя [x], [y], [w]×[h] (координаты холста). */
    fun rect(x: Float, y: Float, w: Float, h: Float, c: LayerCrop): Affine2D =
        Affine2D.translate(x + c.l * w, y + c.t * h) * Affine2D.scale(w * (c.r - c.l), h * (c.b - c.t))

    /** (u, v) квадрата обрезанного слоя -> доля картинки (то же, что вершинный шейдер с uUvRect). */
    fun toTexture(c: LayerCrop, u: Float, v: Float): Pair<Float, Float> = (c.l + u * (c.r - c.l)) to (c.t + v * (c.b - c.t))

    /** Доля картинки -> (u, v) квадрата обрезанного слоя. */
    fun fromTexture(c: LayerCrop, tu: Float, tv: Float): Pair<Float, Float> = ((tu - c.l) / (c.r - c.l)) to ((tv - c.t) / (c.b - c.t))

    /**
     * Границы содержимого [box] (l, t, r, b в долях картинки, RasterMath.alphaBox) в пределах видимой рамки [c]:
     * пустой массив - всё содержимое обрезано; без обрезки - как есть.
     */
    fun visibleBox(box: FloatArray, c: LayerCrop?): FloatArray {
        if (c == null || box.size < 4) return box
        val l = maxOf(box[0], c.l); val t = maxOf(box[1], c.t); val r = minOf(box[2], c.r); val b = minOf(box[3], c.b)
        return if (r <= l || b <= t) FloatArray(0) else floatArrayOf(l, t, r, b)
    }

    /**
     * Карта прозрачности (8 байт: ширина и высота LE, затем альфа построчно, RenderNative.layerAlpha) только видимой
     * части [c] - для сетки «по контуру» и границ содержимого обрезанного слоя. null - карта неверна; без обрезки - как есть.
     */
    fun cropAlphaMap(bytes: ByteArray, c: LayerCrop?): ByteArray? {
        if (bytes.size < 9) return null
        if (c == null) return bytes
        val w = i32(bytes, 0); val h = i32(bytes, 4)
        if (w <= 0 || h <= 0 || w.toLong() * h + 8 > bytes.size) return null
        val (x0, y0, x1, y1) = pixelRect(c, w, h)
        val cw = x1 - x0; val ch = y1 - y0
        val out = ByteArray(8 + cw * ch)
        put32(out, 0, cw); put32(out, 4, ch)
        for (y in 0 until ch) System.arraycopy(bytes, 8 + (y0 + y) * w + x0, out, 8 + y * cw, cw)
        return out
    }

    /** Пиксели видимой части картинки [w]×[h]: x0, y0, x1, y1 (x1, y1 - не включительно), не меньше 1×1. */
    fun pixelRect(c: LayerCrop, w: Int, h: Int): IntArray {
        val x0 = floor(c.l * w + 1e-4f).toInt().coerceIn(0, w - 1)
        val y0 = floor(c.t * h + 1e-4f).toInt().coerceIn(0, h - 1)
        val x1 = ceil(c.r * w - 1e-4f).toInt().coerceIn(x0 + 1, w)
        val y1 = ceil(c.b * h - 1e-4f).toInt().coerceIn(y0 + 1, h)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Таблица обрезки сцены: id -> рамка (скрытые и без обрезки не попадают), черновик вкладки поверх сохранённой. */
    fun table(props: Map<Int, com.vibetoon.domain.model.LayerProps>, override: Pair<Int, LayerCrop?>? = null): Map<Int, LayerCrop> {
        val (ids, rects) = LayerCrop.scene(props, override)
        if (ids.isEmpty()) return emptyMap()
        val m = HashMap<Int, LayerCrop>(ids.size * 2)
        for (i in ids.indices) m[ids[i]] = LayerCrop(rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3])
        return m
    }

    private fun i32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun put32(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte() }
}
