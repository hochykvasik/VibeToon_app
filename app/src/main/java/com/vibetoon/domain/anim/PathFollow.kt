package com.vibetoon.domain.anim

import com.vibetoon.domain.model.LayerProps

/**
 * Спринт 38: прикрепление слоёв к слою-пути. Прикреплённый слой привязан к пути (links: слой -> путь), поэтому
 * сдвиг/поворот/масштаб слоя-пути везут его вместе с кривой; опорная точка слоя стоит в точке пути на его «Положении»,
 * собственный сдвиг слоя (X/Y) - смещение от этой точки, «По направлению» добавляет угол касательной к повороту.
 * Одна и та же функция работает в редакторе, при Play и в экспорте.
 */
object PathFollow {
    data class Attach(val path: Int, val orient: Boolean)

    /** Прикрепления из свойств слоёв: только к существующим путям и не к себе. */
    fun attachments(props: Map<Int, LayerProps>, paths: Set<Int>): Map<Int, Attach> {
        val out = HashMap<Int, Attach>()
        for ((id, p) in props) if (p.path >= 0 && p.path != id && p.path in paths && id !in paths) out[id] = Attach(p.path, p.pathOrient)
        return out
    }

    /**
     * Точки слоёв на пути: id -> [x, y, угол] в координатах холста (угол 0 без «По направлению»).
     * [percent] - положение слоя на пути (0..100, у замкнутого пути - по кругу).
     */
    fun places(attach: Map<Int, Attach>, paths: Map<Int, PathLayerMath.Path>, percent: (Int) -> Float): Map<Int, FloatArray> {
        if (attach.isEmpty()) return emptyMap()
        val out = HashMap<Int, FloatArray>()
        for ((id, a) in attach) {
            val path = paths[a.path] ?: continue
            val pl = PathLayerMath.place(path, percent(id))
            out[id] = floatArrayOf(pl.x, pl.y, if (a.orient) pl.angle else 0f)
        }
        return out
    }
}
