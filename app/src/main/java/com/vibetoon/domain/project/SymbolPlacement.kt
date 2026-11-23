package com.vibetoon.domain.project

/**
 * Пакет 9: символ в иерархии слоёв. Раньше экземпляр символа всегда ставился в корень, а перенести его в группу
 * PSD было нельзя («Картинки, видео и текст пока нельзя класть в группы PSD»): рот-символ выпадал из папки головы
 * и переставал двигаться с ней и её «Тряской». Теперь:
 *  - экземпляр создаётся в той же группе, где лежало определение (контекст выбранных слоёв);
 *  - экземпляр можно переносить в любые группы, кроме собственного определения (цикл);
 *  - общие предки определения и всех его экземпляров наследуются экземпляром «вживую», поэтому в снимке форм символа
 *    их трансформации нейтрализуются (иначе поворот/сдвиг головы применился бы дважды или «застыл» в форме);
 *  - общая цепочка эффектов экземпляров (например, «Тряска» головы, унаследованная при переносе) в снимке форм
 *    снимается с участников с той же цепочкой - рот трясётся один раз, синхронно с головой.
 * Чистые функции - unit-тест.
 */
object SymbolPlacement {
    /** Предки узла снизу вверх (без самого узла); защита от циклов в данных. */
    fun ancestors(id: Int, parentOf: (Int) -> Int?): List<Int> {
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        var cur = parentOf(id)
        while (cur != null && cur >= 0 && seen.add(cur)) {
            out += cur
            cur = parentOf(cur)
        }
        return out
    }

    /**
     * Общие предки определения [def] и всех его экземпляров [instances] - группы, трансформации и эффекты которых
     * экземпляры получают в сцене сами. Нет экземпляров - пусто (снимок как раньше).
     */
    fun sharedContext(def: Int, instances: List<Int>, parentOf: (Int) -> Int?): Set<Int> {
        if (instances.isEmpty()) return emptySet()
        val shared = ancestors(def, parentOf).toMutableSet()
        for (i in instances) {
            if (shared.isEmpty()) break
            shared.retainAll(ancestors(i, parentOf).toSet())
        }
        return shared
    }

    /**
     * Можно ли положить экземпляр символа с определением [defId] в группу [newParent] (-1 - корень):
     * нельзя внутрь собственного определения и его потомков - символ рисовал бы сам себя.
     */
    fun canPlace(defId: Int, newParent: Int, parentOf: (Int) -> Int?): Boolean {
        if (newParent < 0) return true
        if (newParent == defId) return false
        return defId !in ancestors(newParent, parentOf)
    }

    /** Общая для всех экземпляров непустая цепочка эффектов или пусто (тогда в снимке ничего не снимается). */
    fun <E> liveChain(instanceChains: List<List<E>>): List<E> {
        val first = instanceChains.firstOrNull() ?: return emptyList()
        if (first.isEmpty()) return emptyList()
        return if (instanceChains.all { it == first }) first else emptyList()
    }

    /** Эффекты участников для снимка формы: цепочка, совпадающая с живой цепочкой экземпляров, снимается. */
    fun <E> snapshotFx(fx: Map<Int, List<E>>, members: Set<Int>, live: List<E>, neutral: Set<Int>): Map<Int, List<E>> {
        if (live.isEmpty() && neutral.isEmpty()) return fx
        return fx.filter { (id, chain) -> id !in neutral && !(live.isNotEmpty() && id in members && chain == live) }
    }

    /** Группа для нового экземпляра: родитель определения (контекст выбранных слоев) или корень. */
    fun instanceParent(defParent: Int?): Int = if (defParent == null || defParent < 0) -1 else defParent
}
