package com.vibetoon.domain.perf

import com.vibetoon.domain.project.FrameCell

/**
 * Спринт 31: ключ декодированного рисунка - путь файла и его версия на диске. После правки рисунка
 * (сохранение кисти, Undo) меняются время изменения или размер, и старая картинка из кэша не показывается.
 */
data class CellCacheKey(val path: String, val modified: Long, val length: Long)

/**
 * Спринт 31: чистые правила кэша и предзагрузки рисунков покадровых слоёв.
 * Пул декодирования, бюджет памяти и какие рисунки декодировать заранее по направлению воспроизведения.
 */
object CellCachePolicy {
    private const val MIB = 1024L * 1024L

    /**
     * Потоков декодирования PNG рисунков: половина ядер, от 1 до 3. На устройствах с малой памятью (isLowRamDevice)
     * один поток, как раньше: параллельный декод умножает пиковую память.
     */
    fun decodeThreads(cpus: Int, lowRam: Boolean): Int = if (lowRam) 1 else (cpus / 2).coerceIn(1, 3)

    /**
     * Бюджет кэша декодированных рисунков: четверть memoryClass приложения (МБ), 24-160 МБ;
     * на устройствах с малой памятью - 16 МБ (2 кадра Full HD). memoryClass <= 0 - как у 256 МБ.
     */
    fun budgetBytes(memoryClassMb: Int, lowRam: Boolean): Long {
        if (lowRam) return 16 * MIB
        val mc = if (memoryClassMb > 0) memoryClassMb else 256
        return (mc / 4).toLong().coerceIn(24L, 160L) * MIB
    }

    /** Бюджет после onTrimMemory: уровень >= 15 (RUNNING_CRITICAL, фон) - кэш очищается, >= 10 - половина. */
    fun trimmedBudget(budget: Long, trimLevel: Int): Long = when {
        trimLevel >= 15 -> 0L
        trimLevel >= 10 -> budget / 2
        else -> budget
    }

    /** Байт на рисунок RGBA. */
    fun bitmapBytes(width: Int, height: Int): Long = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0) * 4L

    /**
     * Сколько рисунков вперёд держать для каждого слоя: половина бюджета делится между [layers] слоями,
     * не больше [maxAhead]. 0 - предзагрузка выключена (кадры слишком большие для бюджета).
     */
    fun prefetchDepth(cellBytes: Long, budget: Long, layers: Int, maxAhead: Int = 4): Int {
        if (cellBytes <= 0 || layers <= 0 || maxAhead <= 0) return 0
        val per = budget / 2 / layers
        return (per / cellBytes).toInt().coerceIn(0, maxAhead)
    }

    /**
     * Файлы следующих [ahead] рисунков после активного на [frame] в направлении [direction] (+1 вперёд, -1 назад).
     * При [loop] за последним рисунком идут первые (цикл воспроизведения). Активный и повторяющиеся файлы не входят.
     */
    fun prefetchFiles(cells: List<FrameCell>, frame: Int, direction: Int, ahead: Int, loop: Boolean): List<String> {
        if (cells.size < 2 || ahead <= 0) return emptyList()
        val sorted = cells.sortedBy { it.start }
        val active = sorted.lastOrNull { it.start <= frame } ?: sorted.first()
        val i = sorted.indexOf(active)
        val step = if (direction < 0) -1 else 1
        val out = LinkedHashSet<String>()
        var k = i
        for (n in 1 until sorted.size) {
            k += step
            if (k !in sorted.indices) {
                if (!loop) break
                k = if (k < 0) sorted.size - 1 else 0
            }
            val f = sorted[k].file
            if (f != active.file) out += f
            if (out.size >= ahead) break
        }
        return out.toList()
    }

    /** Направление предзагрузки: при воспроизведении всегда вперёд (переход цикла - не шаг назад), иначе по смене кадра. */
    fun direction(previous: Int, current: Int, playing: Boolean): Int =
        if (playing || current >= previous) 1 else -1
}
