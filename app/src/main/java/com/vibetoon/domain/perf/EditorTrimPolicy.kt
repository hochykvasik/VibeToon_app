package com.vibetoon.domain.perf

/**
 * Спринт 55: что редактор отдаёт по onTrimMemory, кроме кэша рисунков и форм символов (они - [CellCachePolicy]).
 *  - кэш сцен Play (до 48 МБ): с уровня RUNNING_LOW (10) - он пересоберётся на следующем проходе Play;
 *  - готовые сетки деформаций и память ARAP-пинов: с RUNNING_CRITICAL (15) и в фоне - пересчитываются по требованию.
 * Чистые функции - unit-тест.
 */
object EditorTrimPolicy {
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15

    fun dropPlayScenes(level: Int): Boolean = level >= RUNNING_LOW
    fun dropDeformCaches(level: Int): Boolean = level >= RUNNING_CRITICAL

    /** Записи кэша по id слоя, которых больше нет в проекте ([live]) - удаляются при любой нехватке памяти. */
    fun <V> stale(cache: Map<Int, V>, live: Set<Int>): Set<Int> = cache.keys.filterTo(HashSet()) { it !in live }
}
