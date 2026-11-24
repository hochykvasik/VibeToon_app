package com.vibetoon.domain.perf

/**
 * Спринт 31: потокобезопасный LRU-кэш с бюджетом в байтах (декодированные рисунки покадровых слоёв).
 * Самый давно не использованный элемент вытесняется первым; элемент больше половины бюджета не кэшируется
 * (иначе один кадр выбросил бы весь кэш). Вытесненные значения отдаются в [onEvict] вне блокировки.
 * Без Android - проверяется unit-тестом.
 */
class BudgetLru<K : Any, V : Any>(
    budgetBytes: Long,
    private val sizeOf: (V) -> Long,
    private val onEvict: (K, V) -> Unit = { _, _ -> }
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)   // accessOrder: get переносит в конец
    @Volatile var budgetBytes: Long = budgetBytes.coerceAtLeast(0L)
        private set
    var sizeBytes: Long = 0L
        private set
    private var hitCount = 0L
    private var missCount = 0L

    @Synchronized fun get(key: K): V? {
        val v = map[key]
        if (v == null) missCount++ else hitCount++
        return v
    }

    /** Есть ли ключ, не меняя порядок вытеснения и счётчики (для решения «префетчить или нет»). */
    @Synchronized fun contains(key: K): Boolean = map.containsKey(key)

    /** Можно ли вообще закэшировать значение такого размера. */
    fun fits(bytes: Long): Boolean = bytes in 1..(budgetBytes / 2)

    /** Кладёт значение; false - не поместилось (слишком большое), вызывающий сам владеет значением. */
    fun put(key: K, value: V): Boolean {
        val bytes = sizeOf(value)
        if (!fits(bytes)) return false
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(this) {
            map.put(key, value)?.let { old -> sizeBytes -= sizeOf(old); if (old !== value) evicted += key to old }
            sizeBytes += bytes
            trimLocked(budgetBytes, evicted)
        }
        for ((k, v) in evicted) onEvict(k, v)
        return true
    }

    fun remove(key: K) {
        val v = synchronized(this) { map.remove(key)?.also { sizeBytes -= sizeOf(it) } } ?: return
        onEvict(key, v)
    }

    /** Удаляет все ключи, для которых [predicate] истинно (например, все версии одного файла). */
    fun removeIf(predicate: (K) -> Boolean) {
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(this) {
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (predicate(e.key)) { sizeBytes -= sizeOf(e.value); evicted += e.key to e.value; it.remove() }
            }
        }
        for ((k, v) in evicted) onEvict(k, v)
    }

    /** Новый бюджет (onTrimMemory / смена настроек): лишнее вытесняется сразу. */
    fun resize(newBudget: Long) {
        val evicted = ArrayList<Pair<K, V>>()
        synchronized(this) {
            budgetBytes = newBudget.coerceAtLeast(0L)
            trimLocked(budgetBytes, evicted)
        }
        for ((k, v) in evicted) onEvict(k, v)
    }

    fun clear() {
        val evicted = synchronized(this) { map.entries.map { it.key to it.value }.also { map.clear(); sizeBytes = 0L } }
        for ((k, v) in evicted) onEvict(k, v)
    }

    @Synchronized fun count(): Int = map.size
    @Synchronized fun hits(): Long = hitCount
    @Synchronized fun misses(): Long = missCount
    @Synchronized fun keys(): List<K> = map.keys.toList()   // от старых к свежим

    private fun trimLocked(limit: Long, out: MutableList<Pair<K, V>>) {
        val it = map.entries.iterator()
        while (sizeBytes > limit && it.hasNext()) {
            val e = it.next()
            sizeBytes -= sizeOf(e.value)
            out += e.key to e.value
            it.remove()
        }
    }
}
