package com.vibetoon.domain.anim

/**
 * Спринт 50: Turbulent Displace у группы целиком. У слоя турбулентность смещает координаты его текстуры (доля слоя),
 * у группы текстуры нет - её содержимое собирается в буфер (как для режимов наложения), и турбулентность идёт
 * шейдерным эффектом над этим буфером в рамке группы: доля и число ячеек считаются от рамки группы в холсте, поэтому
 * рисунок турбулентности привязан к группе (зум и панорама его не сдвигают).
 */
object GroupTurb {
    /** uMode шейдера эффектов (kFragFx). Не входит в библиотеку эффектов - запись строит только сцена. */
    const val MODE = 25

    /**
     * Запись эффекта (EffectMath.RECORD = 10): [params] - 4 числа TurbMath.params (доля, ячеек, октавы, seed),
     * [quad] - рамка группы в холсте (8 чисел). null - пустая/вырожденная рамка или турбулентность выключена.
     */
    fun record(params: FloatArray, quad: FloatArray): FloatArray? {
        if (params.size < 4 || quad.size < 8 || params[0] <= 0f) return null
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            val x = quad[i * 2]; val y = quad[i * 2 + 1]
            if (!x.isFinite() || !y.isFinite()) return null
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val w = maxX - minX; val h = maxY - minY
        if (w < 1f || h < 1f) return null
        return floatArrayOf(MODE.toFloat(), params[0], params[1], params[2], params[3], minX, minY, w, h, 0f)
    }

    /**
     * Делит турбулентность сцены: слои остаются в [turbs] (их рисует текстурный шейдер), группы уходят первым эффектом
     * в цепочку группы (до остальных эффектов, как у слоя турбулентность действует до эффектов).
     */
    fun split(turbs: Map<Int, FloatArray>, fx: Map<Int, List<FloatArray>>, groupQuads: Map<Int, FloatArray>, groups: Set<Int>):
        Pair<Map<Int, FloatArray>, Map<Int, List<FloatArray>>> {
        if (turbs.keys.none { it in groups }) return turbs to fx
        val layerTurbs = turbs.filterKeys { it !in groups }
        val out = HashMap(fx)
        for ((id, p) in turbs) {
            if (id !in groups) continue
            val r = groupQuads[id]?.let { record(p, it) } ?: continue
            out[id] = listOf(r) + fx[id].orEmpty()
        }
        return layerTurbs to out
    }
}
