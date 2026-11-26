package com.vibetoon.domain.render

/**
 * Спринт 55: какие части сцены уже лежат в native-рендерере. Раньше каждая установка сцены отправляла через JNI все
 * разделы (сцена, Warp, сетки, перспектива, турбулентность, эффекты, формы, обрезка), даже пустые и те же самые массивы
 * (повтор кадра из кэша Play, возврат сцены редактора после снимка формы). Раздел с теми же массивами (по ссылке; пустые
 * массивы равны друг другу) не отправляется. [reset] - новый native-рендерер (пересоздан контекст GL).
 * Только GL-поток. Чистый класс - unit-тест.
 */
class SceneSectionTracker(slots: Int = SLOTS) {
    private val last = arrayOfNulls<Array<Any?>>(slots)

    /** true - раздел [slot] нужно отправить (и он запомнен как отправленный). */
    fun changed(slot: Int, vararg parts: Any?): Boolean {
        val prev = last[slot]
        if (prev != null && prev.size == parts.size && parts.indices.all { same(prev[it], parts[it]) }) return false
        last[slot] = arrayOf(*parts)
        return true
    }

    fun reset() { last.fill(null) }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a === b -> true
        a is IntArray || a is FloatArray || b is IntArray || b is FloatArray -> empty(a) && empty(b) && a!!::class == b!!::class
        else -> a == b   // числа (размер холста) - по значению
    }

    private fun empty(x: Any?): Boolean = when (x) {
        is IntArray -> x.isEmpty()
        is FloatArray -> x.isEmpty()
        else -> false
    }

    companion object {
        const val SCENE = 0; const val WARPS = 1; const val MESHES = 2; const val PERSP = 3; const val TURB = 4
        const val FX = 5; const val ALIASES = 6; const val CROPS = 7; const val CANVAS = 8
        const val SLOTS = 9
    }
}
