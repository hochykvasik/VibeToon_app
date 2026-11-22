package com.vibetoon.domain.render

import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.model.Affine2D

/**
 * Пивот узла хранится в координатах до трансформации (система родителя), как в SceneBuilder:
 * local(t, P) = T(P + d) * R * S * T(-P).
 * При переносе пивота P -> P' смещение компенсируется d' = d + (R*S - I)(P' - P),
 * поэтому картинка не прыгает (точно на каждом ключе).
 */
object PivotMath {
    fun local(t: LayerTransform, px: Float, py: Float): Affine2D =
        Affine2D.translate(px + t.dx, py + t.dy) * Affine2D.rotate(t.rotation) *
            Affine2D.scale(t.scaleX, t.scaleY) * Affine2D.translate(-px, -py)

    /** Поправка смещения (ddx, ddy) для переноса пивота при данных повороте и масштабе. */
    fun offset(rotation: Float, scaleX: Float, scaleY: Float, oldX: Float, oldY: Float, newX: Float, newY: Float): Pair<Float, Float> {
        val vx = newX - oldX
        val vy = newY - oldY
        val m = Affine2D.rotate(rotation) * Affine2D.scale(scaleX, scaleY)
        return (m.applyX(vx, vy) - vx) to (m.applyY(vx, vy) - vy)
    }

    fun compensate(t: LayerTransform, oldX: Float, oldY: Float, newX: Float, newY: Float): LayerTransform {
        val (ox, oy) = offset(t.rotation, t.scaleX, t.scaleY, oldX, oldY, newX, newY)
        return t.copy(dx = t.dx + ox, dy = t.dy + oy)
    }

    fun compensate(k: Keyframe, oldX: Float, oldY: Float, newX: Float, newY: Float): Keyframe {
        val (ox, oy) = offset(k.rotation, k.scaleX, k.scaleY, oldX, oldY, newX, newY)
        return k.copy(dx = k.dx + ox, dy = k.dy + oy)
    }

    /** Положение пивота на холсте: pw * (P + d). */
    fun world(parentWorld: Affine2D, t: LayerTransform, px: Float, py: Float): Pair<Float, Float> {
        val x = px + t.dx
        val y = py + t.dy
        return parentWorld.applyX(x, y) to parentWorld.applyY(x, y)
    }

    /** Точка холста -> координаты пивота (до трансформации узла); null при вырожденной матрице. */
    fun toLocal(parentWorld: Affine2D, t: LayerTransform, px: Float, py: Float, wx: Float, wy: Float): Pair<Float, Float>? {
        val inv = (parentWorld * local(t, px, py)).inverse() ?: return null
        val x = inv.applyX(wx, wy)
        val y = inv.applyY(wx, wy)
        return if (x.isFinite() && y.isFinite()) x to y else null
    }
}
