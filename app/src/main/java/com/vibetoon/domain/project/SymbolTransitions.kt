package com.vibetoon.domain.project

/**
 * Спринт 47: что показывает экземпляр символа на кадре сцены - одна форма (внутренний кадр [local]) или растворение
 * формы [local] в форму [to] с долей [mix] из [SymbolForms.MIX_STEPS].
 *
 * Почему так. Ключи времени символа с формами (маркерами) указывают на РАЗНЫЕ рисунки (закрытый рот, «О», открытый).
 * Спринт 46 вёл внутренний кадр от значения ключа к значению следующего по кривой - между открытым (39) и закрытым (30)
 * ртом символ проходил кадры 38…31, то есть чужую форму «О». Теперь кривая между разными формами задаёт растворение
 * одной формы в другую (как интерполяция значения: на ключе - ровно его форма, посередине - смесь двух соседних, чужие
 * формы не появляются никогда). Hold - резкая смена на кадре следующего ключа. Внутри одной формы (анимированная форма)
 * и у символов без маркеров кривая по-прежнему ведёт внутренние кадры (Time Remapping), но не выходит за форму.
 */
data class FormRef(val local: Int, val to: Int = -1, val mix: Int = 0) {
    /** Это смесь двух форм (а не одна форма). */
    val blended: Boolean get() = to >= 0 && to != local && mix in 1 until SymbolForms.MIX_STEPS

    /** Форма, которая видна сильнее: подсветка маркера, вложенный символ, запасная картинка, пока смесь не готова. */
    val dominant: Int get() = if (blended && mix * 2 >= SymbolForms.MIX_STEPS) to else local

    /** Доля второй формы 0..1. */
    val weight: Float get() = if (blended) mix.toFloat() / SymbolForms.MIX_STEPS else 0f
}

/**
 * Спринт 47: растворение двух снимков формы. Снимки хранятся обрезанными по непрозрачному содержимому ([Pixels.box] в
 * пикселях полного снимка): рот - доли процента холста, полный снимок в 2x был бы десятки мегабайт.
 * Смешение - в premultiplied-цветах: посередине перехода прозрачность не «проваливается» (две полупрозрачные формы
 * поверх друг друга дали бы просвет), край формы не темнеет. Цвета на входе и выходе - ARGB без premultiply
 * (как Bitmap.getPixels / setPixels). Чистые функции - JVM-тесты.
 */
object FormBlend {
    /** Обрезанный снимок: [box] = l, t, r, b в пикселях полного снимка [fullW] x [fullH]; [px] - box-размера. */
    class Pixels(val fullW: Int, val fullH: Int, val box: IntArray, val px: IntArray) {
        val w: Int get() = box[2] - box[0]
        val h: Int get() = box[3] - box[1]
        val empty: Boolean get() = w <= 0 || h <= 0
        val bytes: Long get() = px.size.toLong() * 4L
        fun at(x: Int, y: Int): Int = if (x < box[0] || y < box[1] || x >= box[2] || y >= box[3]) 0 else px[(y - box[1]) * w + (x - box[0])]
    }

    /** Обрезает полный снимок по непрозрачному содержимому (с запасом в 2 пикселя, как формы редактора). */
    fun crop(full: IntArray, w: Int, h: Int): Pixels {
        val b = SymbolForms.opaqueBounds(full, w, h) ?: return Pixels(w, h, intArrayOf(0, 0, 0, 0), IntArray(0))
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        val out = IntArray(bw * bh)
        for (y in 0 until bh) System.arraycopy(full, (b[1] + y) * w + b[0], out, y * bw, bw)
        return Pixels(w, h, b, out)
    }

    /** Один пиксель: доля второго [wb] из 256. */
    fun mixPixel(pa: Int, pb: Int, wb: Int): Int {
        if (pa == pb) return pa
        val wa = 256 - wb
        val aa = pa ushr 24; val ab = pb ushr 24
        val den = aa * wa + ab * wb
        if (den <= 0) return 0
        val alpha = ((den + 128) shr 8).coerceIn(0, 255)
        if (alpha == 0) return 0
        fun ch(shift: Int): Int {
            val num = ((pa shr shift) and 255) * aa * wa + ((pb shr shift) and 255) * ab * wb
            return ((num + den / 2) / den).coerceIn(0, 255)
        }
        return (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Смесь [a] и [b] (снимки одного размера) с долей второй [weight] 0..1. Результат - в общей рамке обоих снимков.
     * weight 0 - ровно [a], 1 - ровно [b].
     */
    fun blend(a: Pixels, b: Pixels, weight: Float): Pixels {
        require(a.fullW == b.fullW && a.fullH == b.fullH) { "form snapshots differ in size" }
        val wb = Math.round(weight.coerceIn(0f, 1f) * 256f)
        if (a.empty && b.empty) return a
        val l = if (a.empty) b.box[0] else if (b.empty) a.box[0] else minOf(a.box[0], b.box[0])
        val t = if (a.empty) b.box[1] else if (b.empty) a.box[1] else minOf(a.box[1], b.box[1])
        val r = if (a.empty) b.box[2] else if (b.empty) a.box[2] else maxOf(a.box[2], b.box[2])
        val btm = if (a.empty) b.box[3] else if (b.empty) a.box[3] else maxOf(a.box[3], b.box[3])
        val w = r - l; val h = btm - t
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = mixPixel(a.at(l + x, t + y), b.at(l + x, t + y), wb)
        return Pixels(a.fullW, a.fullH, intArrayOf(l, t, r, btm), out)
    }

    /** Смесь полных снимков одного размера ([out] может совпадать с [a]). */
    fun blendFull(a: IntArray, b: IntArray, weight: Float, out: IntArray) {
        require(a.size == b.size && out.size == a.size) { "snapshots differ in size" }
        val wb = Math.round(weight.coerceIn(0f, 1f) * 256f)
        for (i in a.indices) out[i] = mixPixel(a[i], b[i], wb)
    }

    /** Прямоугольник обрезанного снимка в долях квадрата экземпляра (x, y, w, h), как у форм редактора. */
    fun rect(p: Pixels): FloatArray =
        if (p.empty) floatArrayOf(0f, 0f, 1f, 1f)
        else floatArrayOf(p.box[0].toFloat() / p.fullW, p.box[1].toFloat() / p.fullH, p.w.toFloat() / p.fullW, p.h.toFloat() / p.fullH)

    /** Полный снимок (для экземпляров с сеткой/Warp/перспективой - их форма занимает весь квадрат). */
    fun toFull(p: Pixels): IntArray {
        val out = IntArray(p.fullW * p.fullH)
        if (p.empty) return out
        for (y in 0 until p.h) System.arraycopy(p.px, y * p.w, out, (p.box[1] + y) * p.fullW + p.box[0], p.w)
        return out
    }
}
