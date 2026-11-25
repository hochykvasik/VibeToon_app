package com.vibetoon.domain.model

import kotlin.math.roundToInt

/**
 * Спринт 45: вкладка «Обрезка» в свойствах. Рамка видимой части слоя в долях его картинки: [l], [t], [r], [b]
 * (0..1, строка 0 - верх). Обрезка неразрушающая: файл PNG не меняется, рендерер (CropPolicy.h) отбрасывает пиксели вне
 * рамки в пространстве текстуры слоя - поэтому обрезанный край едет вместе с поворотом, масштабом, Mesh/Warp и
 * анимацией, одинаково на холсте и в экспорте; касания вне рамки не ловятся. «Сбросить» возвращает картинку целиком.
 * Чистые функции - Sprint45Test.
 */
data class LayerCrop(val l: Float, val t: Float, val r: Float, val b: Float) {
    fun toList(): List<Float> = listOf(l, t, r, b)

    /** Ширина и высота видимой части в пикселях картинки [srcW]×[srcH]. */
    fun pixelSize(srcW: Int, srcH: Int): Pair<Int, Int> =
        ((r - l) * srcW).roundToInt().coerceAtLeast(1) to ((b - t) * srcH).roundToInt().coerceAtLeast(1)

    companion object {
        /** Наименьшая сторона рамки (как kMinSide в CropPolicy.h). */
        const val MIN_SIDE = 1f / 4096f
        /** Рамка не уже 1 % картинки: ползунки не схлопывают слой в ничто. */
        const val MIN_UI_SIDE = 0.01f
        val FULL = LayerCrop(0f, 0f, 1f, 1f)

        /**
         * Допустимая рамка из сохранённых чисел или null - обрезки нет (вся картинка, мусор, NaN, вырожденная рамка).
         * Те же правила, что у рендерера (CropPolicy.h: sanitize).
         */
        fun of(v: List<Float>?): LayerCrop? {
            if (v == null || v.size != 4 || v.any { !it.isFinite() }) return null
            val c = v.map { it.coerceIn(0f, 1f) }
            if (c[2] - c[0] < MIN_SIDE || c[3] - c[1] < MIN_SIDE) return null
            if (c[0] <= 0f && c[1] <= 0f && c[2] >= 1f && c[3] >= 1f) return null
            return LayerCrop(c[0], c[1], c[2], c[3])
        }

        /**
         * Ползунок вкладки: край [edge] (0 слева, 1 сверху, 2 справа, 3 снизу) ставится в [value] (доля картинки),
         * противоположный край не перескакивается - между ними остаётся не меньше [MIN_UI_SIDE].
         */
        fun withEdge(c: LayerCrop?, edge: Int, value: Float): LayerCrop {
            val s = c ?: FULL
            val v = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
            return when (edge) {
                0 -> s.copy(l = v.coerceAtMost(s.r - MIN_UI_SIDE).coerceAtLeast(0f))
                1 -> s.copy(t = v.coerceAtMost(s.b - MIN_UI_SIDE).coerceAtLeast(0f))
                2 -> s.copy(r = v.coerceAtLeast(s.l + MIN_UI_SIDE).coerceAtMost(1f))
                else -> s.copy(b = v.coerceAtLeast(s.t + MIN_UI_SIDE).coerceAtMost(1f))
            }
        }

        /** Рамка из пикселей картинки (x, y, w, h) [srcW]×[srcH]; null - вся картинка или неверный размер. */
        fun fromPixels(x: Int, y: Int, w: Int, h: Int, srcW: Int, srcH: Int): LayerCrop? {
            if (srcW <= 0 || srcH <= 0 || w <= 0 || h <= 0) return null
            val x0 = x.coerceIn(0, srcW - 1); val y0 = y.coerceIn(0, srcH - 1)
            val x1 = (x + w).coerceIn(x0 + 1, srcW); val y1 = (y + h).coerceIn(y0 + 1, srcH)
            return of(listOf(x0.toFloat() / srcW, y0.toFloat() / srcH, x1.toFloat() / srcW, y1.toFloat() / srcH))
        }

        /**
         * Рамка по непрозрачному содержимому: [alpha] - альфа картинки [w]×[h] построчно (строка 0 - верх), пиксели с
         * альфой больше [threshold] считаются содержимым. null - картинка пуста или содержимое занимает её целиком.
         */
        fun trimToContent(alpha: ByteArray, w: Int, h: Int, threshold: Int = 8): LayerCrop? {
            if (w <= 0 || h <= 0 || alpha.size < w * h) return null
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) if ((alpha[row + x].toInt() and 0xFF) > threshold) {
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
            }
            if (x1 < 0) return null
            return fromPixels(x0, y0, x1 - x0 + 1, y1 - y0 + 1, w, h)
        }

        /** То же по карте прозрачности рендерера (RenderNative.layerAlpha): 8 байт - ширина и высота LE, затем альфа. */
        fun trimToContentMap(bytes: ByteArray): LayerCrop? {
            if (bytes.size < 9) return null
            fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
            val w = i32(0); val h = i32(4)
            if (w <= 0 || h <= 0 || w.toLong() * h + 8 > bytes.size) return null
            return trimToContent(bytes.copyOfRange(8, 8 + w * h), w, h)
        }

        /** Таблица для рендерера: id слоёв и по 4 числа на слой (скрытые и без обрезки не передаются). */
        fun scene(props: Map<Int, LayerProps>, override: Pair<Int, LayerCrop?>? = null): Pair<IntArray, FloatArray> {
            val m = LinkedHashMap<Int, LayerCrop>()
            for ((id, p) in props) of(p.crop)?.let { m[id] = it }
            if (override != null) { val c = override.second; if (c != null && of(c.toList()) != null) m[override.first] = c else m.remove(override.first) }
            val ids = IntArray(m.size); val rects = FloatArray(m.size * 4)
            var i = 0
            for ((id, c) in m) { ids[i] = id; rects[i * 4] = c.l; rects[i * 4 + 1] = c.t; rects[i * 4 + 2] = c.r; rects[i * 4 + 3] = c.b; i++ }
            return ids to rects
        }
    }
}
