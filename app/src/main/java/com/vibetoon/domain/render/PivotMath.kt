package com.vibetoon.domain.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Опорная точка (pivot) слоя: центр поворота и масштаба.
 *
 * Локальная матрица узла: local = T(P + d) * R * S * T(-P), где P - опорная точка в координатах
 * родителя (по умолчанию центр рамки слоя), d - сдвиг (dx, dy) из LayerTransform.
 * Пользователь двигает P (Layer Pivot Editing); чтобы картинка не прыгнула, сдвиг каждого ключа
 * компенсируется: d' = d + (I - R*S) * (P - P'). Тогда local' == local на всех ключах; меняются только
 * промежуточные кадры (поворот теперь идёт вокруг новой точки) - так же работает Anchor Point в After Effects.
 */
object PivotMath {

    /** Линейная часть R(rotation) * S(scaleX, scaleY) по столбцам: a, b, c, d. */
    fun linear(t: LayerTransform): FloatArray {
        val cs = cos(t.rotation); val sn = sin(t.rotation)
        return floatArrayOf(cs * t.scaleX, sn * t.scaleX, -sn * t.scaleY, cs * t.scaleY)
    }

    /** То же для всех ключей дорожки: на каждом ключевом кадре слой остаётся на месте. */
    fun compensateTrack(track: com.vibetoon.domain.anim.AnimationTrack, oldPivot: Pair<Float, Float>, newPivot: Pair<Float, Float>) =
        com.vibetoon.domain.anim.AnimationTrack(track.layerId, track.keys.map { k ->
            val c = compensate(k.toTransform(), oldPivot, newPivot)
            k.copy(dx = c.dx, dy = c.dy)
        })

    /** Компенсация сдвига при переносе опорной точки из [oldPivot] в [newPivot]: внешний вид слоя не меняется. */
    fun compensate(t: LayerTransform, oldPivot: Pair<Float, Float>, newPivot: Pair<Float, Float>): LayerTransform {
        val m = linear(t)
        val ex = oldPivot.first - newPivot.first
        val ey = oldPivot.second - newPivot.second
        // (I - M) * e
        val cx = ex - (m[0] * ex + m[2] * ey)
        val cy = ey - (m[1] * ex + m[3] * ey)
        return t.copy(dx = t.dx + cx, dy = t.dy + cy)
    }

    /**
     * Новая опорная точка, которая на экране окажется в точке [lx], [ly] (координаты родителя) при
     * трансформации [t] и текущей опорной точке [pivot]: P' = P + (R*S)^-1 * (L - P - d).
     * null - вырожденный масштаб.
     */
    fun pivotForPoint(t: LayerTransform, pivot: Pair<Float, Float>, lx: Float, ly: Float): Pair<Float, Float>? {
        val m = linear(t)
        val det = m[0] * m[3] - m[1] * m[2]
        if (abs(det) < 1e-9f) return null
        val rx = lx - pivot.first - t.dx
        val ry = ly - pivot.second - t.dy
        val ux = (m[3] * rx - m[2] * ry) / det
        val uy = (-m[1] * rx + m[0] * ry) / det
        return (pivot.first + ux) to (pivot.second + uy)
    }

    /** Где опорная точка видна (координаты родителя): local(P) = P + d. */
    fun displayed(t: LayerTransform, pivot: Pair<Float, Float>): Pair<Float, Float> = (pivot.first + t.dx) to (pivot.second + t.dy)

    /**
     * Временная опорная точка (Temporary Pivots): [old] -> [new] отличаются поворотом/масштабом (и сдвигом),
     * посчитанными вокруг [pivot]; результат - то же приращение поворота/масштаба, но вокруг точки (gx, gy)
     * в координатах родителя (точка между пальцами остаётся на месте), сдвиг жеста сохраняется.
     */
    fun aroundPoint(old: LayerTransform, new: LayerTransform, pivot: Pair<Float, Float>, gx: Float, gy: Float): LayerTransform {
        val mo = linear(old); val mn = linear(new)
        val det = mo[0] * mo[3] - mo[1] * mo[2]
        if (abs(det) < 1e-9f) return new
        // приращение M_inc = Mn * Mo^-1
        val ia = mo[3] / det; val ib = -mo[1] / det; val ic = -mo[2] / det; val id = mo[0] / det
        val a = mn[0] * ia + mn[2] * ib; val b = mn[1] * ia + mn[3] * ib
        val c = mn[0] * ic + mn[2] * id; val d = mn[1] * ic + mn[3] * id
        val shiftX = new.dx - old.dx; val shiftY = new.dy - old.dy
        // P + d_f = G + shift + M_inc * (P + d - G)
        val qx = pivot.first + old.dx - gx; val qy = pivot.second + old.dy - gy
        val fx = gx + shiftX + a * qx + c * qy - pivot.first
        val fy = gy + shiftY + b * qx + d * qy - pivot.second
        return new.copy(dx = fx, dy = fy)
    }

    /**
     * Притяжение опорной точки к 9 точкам рамки слоя (углы, середины сторон, центр), если ближе [radius].
     * [box] = minX, minY, maxX, maxY в тех же координатах, что и точка.
     */
    fun snapToBox(x: Float, y: Float, box: FloatArray, radius: Float): Pair<Float, Float> {
        if (box.size < 4) return x to y
        val xs = floatArrayOf(box[0], (box[0] + box[2]) / 2f, box[2])
        val ys = floatArrayOf(box[1], (box[1] + box[3]) / 2f, box[3])
        var best = x to y
        var bestD = radius
        for (sx in xs) for (sy in ys) {
            val dd = hypot(sx - x, sy - y)
            if (dd <= bestD) { bestD = dd; best = sx to sy }
        }
        return best
    }

    /** Ближайшая из точек [pts] (x0, y0, x1, y1, ...) к (x, y) не дальше [radius]; null - нет такой. */
    fun nearest(pts: FloatArray, x: Float, y: Float, radius: Float): Int? {
        var best: Int? = null
        var bestD = radius
        for (i in 0 until pts.size / 2) {
            val dd = hypot(pts[i * 2] - x, pts[i * 2 + 1] - y)
            if (dd <= bestD) { bestD = dd; best = i }
        }
        return best
    }
}
