package com.vibetoon.domain.raster

/** Sprint 33: операции над маской выделения (1 байт на пиксель, 0 - не выделено). */
object SelectionOps {
    /** Как новый контур сочетается с уже выделенным: заменить, добавить, вычесть, пересечь. */
    enum class Combine { REPLACE, ADD, SUBTRACT, INTERSECT }

    const val MAX_GROW = 128

    /** [old] - текущая маска того же слоя и размера или null (тогда ADD = REPLACE, SUBTRACT/INTERSECT дают пустую маску). */
    fun combine(old: ByteArray?, new: ByteArray, op: Combine): ByteArray {
        if (old == null || old.size != new.size) return when (op) {
            Combine.REPLACE, Combine.ADD -> new.copyOf()
            Combine.SUBTRACT, Combine.INTERSECT -> ByteArray(new.size)
        }
        return ByteArray(new.size) { i ->
            val a = old[i].toInt() != 0; val b = new[i].toInt() != 0
            val r = when (op) {
                Combine.REPLACE -> b
                Combine.ADD -> a || b
                Combine.SUBTRACT -> a && !b
                Combine.INTERSECT -> a && b
            }
            if (r) 1 else 0
        }
    }

    fun all(w: Int, h: Int): ByteArray = ByteArray(maxOf(0, w) * maxOf(0, h)) { 1 }

    /**
     * Расширение на [radius] пикселей (radius < 0 - сужение) по фаске 3-4 (близко к кругу).
     * При сужении край слоя не «съедает» выделение: снаружи слоя считаем выделенным.
     */
    fun grow(mask: ByteArray, w: Int, h: Int, radius: Int): ByteArray? {
        if (w <= 0 || h <= 0 || mask.size != w * h) return null
        val r = radius.coerceIn(-MAX_GROW, MAX_GROW)
        if (r == 0) return mask.copyOf()
        val shrink = r < 0
        val seed = if (shrink) RasterMath.invert(mask) else mask
        val inf = Int.MAX_VALUE / 4
        val d = IntArray(w * h) { if (seed[it].toInt() != 0) 0 else inf }
        fun relax(i: Int, j: Int, cost: Int) { val v = d[j] + cost; if (v < d[i]) d[i] = v }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (x > 0) relax(i, i - 1, 3)
            if (y > 0) {
                relax(i, i - w, 3)
                if (x > 0) relax(i, i - w - 1, 4)
                if (x < w - 1) relax(i, i - w + 1, 4)
            }
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (x < w - 1) relax(i, i + 1, 3)
            if (y < h - 1) {
                relax(i, i + w, 3)
                if (x < w - 1) relax(i, i + w + 1, 4)
                if (x > 0) relax(i, i + w - 1, 4)
            }
        }
        val limit = 3 * kotlin.math.abs(r)
        val grown = ByteArray(w * h) { if (d[it] <= limit) 1 else 0 }
        return if (shrink) RasterMath.invert(grown) else grown
    }
}
