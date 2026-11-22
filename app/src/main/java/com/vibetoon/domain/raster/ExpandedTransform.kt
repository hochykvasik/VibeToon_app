package com.vibetoon.domain.raster

import com.vibetoon.domain.render.LayerTransform
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Сохраняет отображение пиксельной сетки при изменении прямоугольника и центра слоя.
 * SceneTree округляет геометрию до целых единиц холста; используем те же размеры. */
object ExpandedTransform {
    fun compensate(change: ExpandedLayerSnapshot.Change, t: LayerTransform): LayerTransform? {
        val before = change.before
        val after = change.after
        val a = before.media; val b = after.media
        if (!SelectionSourcePolicy.copySafeStaticTransform(t) || before.width <= 0 || before.height <= 0 ||
            after.width <= 0 || after.height <= 0 || a.w <= 0f || a.h <= 0f) return null
        if (a == b && before.width == after.width && before.height == after.height) return t
        val ax = a.x.roundToInt().toDouble(); val ay = a.y.roundToInt().toDouble()
        val aw = a.w.roundToInt().coerceAtLeast(1).toDouble(); val ah = a.h.roundToInt().coerceAtLeast(1).toDouble()
        val bx = b.x.roundToInt().toDouble(); val by = b.y.roundToInt().toDouble()
        val bw = b.w.roundToInt().coerceAtLeast(1).toDouble(); val bh = b.h.roundToInt().coerceAtLeast(1).toDouble()
        val left = ((b.x.toDouble() - a.x) * before.width / a.w).roundToInt()
        val top = ((b.y.toDouble() - a.y) * before.height / a.h).roundToInt()
        val sx = t.scaleX.toDouble() * aw * after.width / (before.width * bw)
        val sy = t.scaleY.toDouble() * ah * after.height / (before.height * bh)
        if (!sx.isFinite() || !sy.isFinite() || sx < LayerTransform.MIN_SCALE || sx > LayerTransform.MAX_SCALE ||
            sy < LayerTransform.MIN_SCALE || sy > LayerTransform.MAX_SCALE) return null
        val c = cos(t.rotation.toDouble()); val s = sin(t.rotation.toDouble())
        val acx = ax + aw / 2; val acy = ay + ah / 2
        val bcx = bx + bw / 2; val bcy = by + bh / 2
        val u = ax + left * aw / before.width - acx
        val v = ay + top * ah / before.height - acy
        val desiredX = acx + t.dx + c * t.scaleX * u - s * t.scaleY * v
        val desiredY = acy + t.dy + s * t.scaleX * u + c * t.scaleY * v
        val newU = bx - bcx; val newV = by - bcy
        val dx = desiredX - bcx - c * sx * newU + s * sy * newV
        val dy = desiredY - bcy - s * sx * newU - c * sy * newV
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx) > Float.MAX_VALUE || kotlin.math.abs(dy) > Float.MAX_VALUE) return null
        return t.copy(dx = dx.toFloat(), dy = dy.toFloat(), scaleX = sx.toFloat(), scaleY = sy.toFloat())
    }
}
