package com.vibetoon.domain.anim

/**
 * Turbulent Displace: шум смещает текстурные координаты слоя в шейдере.
 * [intensity] - сила в % от размера слоя, [size] - ячеек шума на слой, [complexity] - октавы (1..6),
 * [boil] - Line Boil: шум меняется каждые N кадров (0 - шум неподвижен).
 */
data class Turb(val intensity: Float = 3f, val size: Float = 6f, val complexity: Int = 2, val boil: Int = 0) {
    fun clamped() = Turb(intensity.coerceIn(0f, 30f), size.coerceIn(1f, 40f), complexity.coerceIn(1, 6), boil.coerceIn(0, 24))
}

object TurbMath {
    const val ID_BASE = 400_000_000
    const val PARAMS = 4

    /** Узел анимации параметра турбулентности: 0 Intensity, 1 Size, 2 Complexity, 3 Line Boil (ключ хранит дельту в dx). */
    fun paramId(layerId: Int, k: Int) = ID_BASE + layerId * PARAMS + k

    /** Значения на кадре: базовые + дельты ключей (если ключей нет, база). */
    fun at(layerId: Int, t: Turb, frame: Int, anim: Animation): Turb {
        fun d(k: Int) = anim.evaluate(paramId(layerId, k), frame, null).dx
        return Turb(t.intensity + d(0), t.size + d(1), Math.round(t.complexity + d(2)), Math.round(t.boil + d(3))).clamped()
    }

    /** 4 числа для шейдера: intensity (доля слоя), size, complexity, seed (по кадру). */
    fun params(t: Turb, frame: Int): FloatArray {
        val c = t.clamped()
        val seed = if (c.boil <= 0) 0 else frame / c.boil
        return floatArrayOf(c.intensity / 100f, c.size, c.complexity.toFloat(), seed.toFloat())
    }
}
