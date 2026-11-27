package com.vibetoon.domain.model

/**
 * Спринт 38: маски слоёв. Маска - отдельный слой (обычно белый пиксельный «Маска: …»), его альфа задаёт видимость
 * слоя-цели: непрозрачное - видно, стёртое - скрыто; «Инверсия» - наоборот. Сама маска никогда не рисуется (ни на холсте,
 * ни в Play, ни в экспорте); выключенная маска («Маска» снята) оставляет цель видимой целиком. Маска не бывает целью
 * другой маски (без цепочек), у одного слоя-маски одна цель. Хранится в LayerProps цели (mask / maskInvert / maskOff).
 */
object LayerMasks {
    data class Mask(val source: Int, val invert: Boolean)

    enum class Error { SELF, SOURCE_IS_TARGET, TARGET_IS_SOURCE, SOURCE_TAKEN, MISSING }

    /** Можно ли сделать [source] маской [target]. [existing] - id существующих слоёв (null - не проверять). */
    fun check(target: Int, source: Int, props: Map<Int, LayerProps>, existing: Set<Int>? = null): Error? {
        if (target == source) return Error.SELF
        if (existing != null && (target !in existing || source !in existing)) return Error.MISSING
        if ((props[source]?.mask ?: -1) >= 0) return Error.SOURCE_IS_TARGET
        if (props.any { (id, p) -> id != target && p.mask == target }) return Error.TARGET_IS_SOURCE
        if (props.any { (id, p) -> id != target && p.mask == source }) return Error.SOURCE_TAKEN
        return null
    }

    /** Корректные назначения цель -> слой-маска (включая выключенные): цепочки, повторы и удалённые слои отброшены. */
    private fun assigned(props: Map<Int, LayerProps>, existing: Set<Int>?): Map<Int, LayerProps> {
        val out = LinkedHashMap<Int, LayerProps>()
        val used = HashSet<Int>()
        for (target in props.keys.sorted()) {
            val p = props.getValue(target)
            val src = p.mask
            if (src < 0 || src == target) continue
            if (existing != null && (src !in existing || target !in existing)) continue
            if ((props[src]?.mask ?: -1) >= 0) continue          // маска сама с маской - цепочки нет
            if (props.any { (id, q) -> id != target && q.mask == target && id != src }) continue   // цель - чья-то маска
            if (!used.add(src)) continue                          // одна маска - одна цель (меньший id)
            out[target] = p
        }
        return out
    }

    /** Действующие маски: цель -> (слой-маска, инверсия). Выключенные маски не действуют. */
    fun active(props: Map<Int, LayerProps>, existing: Set<Int>? = null): Map<Int, Mask> =
        assigned(props, existing).filterValues { !it.maskOff }.mapValues { (_, p) -> Mask(p.mask, p.maskInvert) }

    /** Слои-маски (и у выключенных масок): сами по себе не рисуются. */
    fun sources(props: Map<Int, LayerProps>, existing: Set<Int>? = null): Set<Int> =
        assigned(props, existing).values.mapTo(HashSet()) { it.mask }

    /** Цель слоя-маски [source] или null. */
    fun targetOf(source: Int, props: Map<Int, LayerProps>): Int? =
        assigned(props, null).entries.firstOrNull { it.value.mask == source }?.key

    /** После удаления слоёв [removed]: маски и пути, ссылающиеся на них, снимаются; записи удалённых слоёв уходят. */
    fun cleared(props: Map<Int, LayerProps>, removed: Set<Int>): Map<Int, LayerProps> {
        if (removed.isEmpty()) return props
        val out = HashMap<Int, LayerProps>()
        for ((id, p) in props) {
            if (id in removed) continue
            var q = p
            if (q.mask in removed) q = q.copy(mask = -1, maskInvert = false, maskOff = false)
            if (q.path in removed) q = q.copy(path = -1, pathOrient = false)
            out[id] = q
        }
        return out
    }
}
