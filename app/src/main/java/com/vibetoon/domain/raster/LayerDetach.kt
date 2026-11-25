package com.vibetoon.domain.raster

import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.LayerTransform
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Спринт 50: копия слоя PSD в виде слоя-картинки (выделение «Вырезать/Копировать в новый слой», «Дублировать»).
 * Слой PSD задан в координатах PSD (в холст его вписывает fit = масштаб k + сдвиг), картинка - сразу в координатах
 * холста. Чтобы копия совпала со слоем пиксель в пиксель и двигалась так же, рамка переводится через fit, а сдвиги
 * трансформации и ключей умножаются на k (поворот и масштаб вокруг опорной точки от вписывания не зависят).
 */
object LayerDetach {
    /** Равномерный масштаб вписывания (fit без поворота и перекоса). */
    fun scaleOf(fit: Affine2D): Float {
        val k = sqrt(abs(fit.a * fit.d - fit.b * fit.c))
        return if (k.isFinite() && k > 0f) k else 1f
    }

    /** Рамка слоя PSD (x, y, w, h в координатах PSD) -> рамка картинки в холсте: x, y, w, h. */
    fun mediaRect(fit: Affine2D, x: Float, y: Float, w: Float, h: Float): FloatArray {
        val k = scaleOf(fit)
        return floatArrayOf(fit.applyX(x, y), fit.applyY(x, y), w * k, h * k)
    }

    /** Опорная точка PSD -> холст. */
    fun pivot(fit: Affine2D, p: Pair<Float, Float>): Pair<Float, Float> = fit.applyX(p.first, p.second) to fit.applyY(p.first, p.second)

    fun transform(t: LayerTransform, k: Float): LayerTransform = if (k == 1f) t else t.copy(dx = t.dx * k, dy = t.dy * k)

    fun keys(keys: List<Keyframe>, k: Float): List<Keyframe> = if (k == 1f) keys else keys.map { it.copy(dx = it.dx * k, dy = it.dy * k) }

    /**
     * Слой с обтравкой над источником: новый слой встаёт прямо над источником, и обтравленный сосед сверху начал бы
     * обтравливаться по нему. [aboveClipped] - ближайший сосед сверху обтравлен, [sourceClipped] - источник сам обтравлен
     * (тогда копия тоже обтравлена и цепочка сохраняется).
     */
    fun breaksClipping(aboveClipped: Boolean, sourceClipped: Boolean): Boolean = aboveClipped && !sourceClipped

    /** Снимок слоя (RGBA premultiplied, строка 0 - верх, смещение [off]) -> прямой ARGB для Bitmap. null - неверный размер. */
    fun toArgb(rgba: ByteArray, off: Int, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0 || off < 0 || off.toLong() + w.toLong() * h * 4 > rgba.size) return null
        val out = IntArray(w * h)
        for (i in out.indices) {
            val o = off + i * 4
            val a = rgba[o + 3].toInt() and 255
            if (a == 0) continue
            fun straight(c: Int): Int = (((rgba[o + c].toInt() and 255) * 255 + a / 2) / a).coerceAtMost(255)
            out[i] = (a shl 24) or (straight(0) shl 16) or (straight(1) shl 8) or straight(2)
        }
        return out
    }
}
