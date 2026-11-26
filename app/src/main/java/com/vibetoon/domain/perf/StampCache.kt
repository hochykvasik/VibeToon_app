package com.vibetoon.domain.perf

/**
 * Спринт 52: кэш значений, привязанных к «штампу» файла (время изменения, длина...). Значение пересчитывается, только
 * если штамп поменялся - экран проектов не обходит заново папки всех проектов при каждом обновлении списка.
 * Потокобезопасен; пересчёт идёт вне блокировки (обход диска не держит другие проекты).
 */
class StampCache<K : Any, S : Any, V : Any> {
    private val map = HashMap<K, Pair<S, V>>()

    /** Значение для [key] при штампе [stamp]; при промахе - [compute] и запоминание. */
    fun get(key: K, stamp: S, compute: () -> V): V {
        synchronized(this) { map[key]?.takeIf { it.first == stamp }?.let { return it.second } }
        val v = compute()
        synchronized(this) { map[key] = stamp to v }
        return v
    }

    /** Есть ли свежее значение (штамп совпадает). */
    @Synchronized fun peek(key: K, stamp: S): V? = map[key]?.takeIf { it.first == stamp }?.second

    /**
     * Известная правка: файл сменил штамп [old] -> [new] (например, запись поля папки), значение меняется [change].
     * Если в кэше другой штамп - запись удаляется (пересчитается при следующем [get]). true - значение обновлено.
     */
    @Synchronized fun update(key: K, old: S, new: S, change: (V) -> V): Boolean {
        val e = map[key]
        if (e == null || e.first != old) { map.remove(key); return false }
        map[key] = new to change(e.second)
        return true
    }

    @Synchronized fun put(key: K, stamp: S, value: V) { map[key] = stamp to value }
    @Synchronized fun remove(key: K) { map.remove(key) }
    /** Оставить только живые ключи (удалённые проекты не держат память). */
    @Synchronized fun retain(alive: Set<K>) { map.keys.retainAll(alive) }
    @Synchronized fun size(): Int = map.size
}
