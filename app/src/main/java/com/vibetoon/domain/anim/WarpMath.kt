package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Warp: сетка из 16 контрольных точек (4x4, то есть 3x3 ячейки) на слое. Смещение каждой точки хранится
 * как LayerTransform (dx, dy в долях размера слоя) под своим "виртуальным" id, поэтому ключевые кадры,
 * плавность, Undo и сохранение работают без отдельного кода: точка - обычный узел анимации.
 *
 * Итоговое смещение точки = форма привязки (base, Bind Pose) + анимация (треки; только при включённом FX).
 */
object WarpMath {
    const val POINTS = 16
    const val ID_BASE = 1_000_000

    fun pointId(layerId: Int, k: Int) = ID_BASE + layerId * POINTS + k
    fun isPointId(id: Int) = id in ID_BASE until MeshMath.ID_BASE

    /** Положение точки k в исходной решётке (доли слоя): k = строка * 4 + столбец. */
    fun latticeX(k: Int) = (k % 4) / 3f
    fun latticeY(k: Int) = (k / 4) / 3f

    /** 32 числа (dx, dy по 16 точкам) для слоя на кадре; [fx] = false - только форма привязки. */
    fun offsets(layerId: Int, frame: Int, base: Map<Int, LayerTransform>, anim: Animation, fx: Boolean): FloatArray {
        val out = FloatArray(POINTS * 2)
        for (k in 0 until POINTS) {
            val id = pointId(layerId, k)
            var dx = base[id]?.dx ?: 0f
            var dy = base[id]?.dy ?: 0f
            if (fx) anim.tracks[id]?.evaluate(frame)?.let { dx += it.dx; dy += it.dy }
            out[k * 2] = dx
            out[k * 2 + 1] = dy
        }
        return out
    }

    /** Все ли смещения нулевые (слой рисуется без деформации). */
    fun isIdentity(offsets: FloatArray) = offsets.all { it == 0f }
}
