package com.vibetoon.domain.project

import kotlin.math.roundToInt

/**
 * Операции «как с ключами» для меток на таймлайне, у которых есть только кадр: ключи времени символа ([TimeKey]) и
 * маркеры кадров ([FrameMarker]). Выделение - множество кадров. Всё чистое и проверяется юнит-тестами.
 */
object TimelineItemOps {
    /** Как читать/менять кадр элемента. */
    class Access<T>(val frameOf: (T) -> Int, val withFrame: (T, Int) -> T)

    val TIME_KEYS = Access<TimeKey>({ it.frame }, { k, f -> k.copy(frame = f) })
    val MARKERS = Access<FrameMarker>({ it.frame }, { m, f -> m.copy(frame = f) })

    /** Допустимый сдвиг выделения: граница [0, last] останавливает всю группу. */
    fun clampDelta(frames: Set<Int>, d: Int, last: Int): Int {
        if (frames.isEmpty()) return 0
        return d.coerceIn(-frames.min(), last - frames.max())
    }

    /** Сдвиг выделенных на [d] кадров. null - цель занята невыделенным элементом. */
    fun <T> move(items: List<T>, frames: Set<Int>, d: Int, last: Int, a: Access<T>): List<T>? {
        val dd = clampDelta(frames.filterTo(HashSet()) { f -> items.any { a.frameOf(it) == f } }, d, last)
        if (dd == 0) return items
        val moving = items.filter { a.frameOf(it) in frames }
        val rest = items.filter { a.frameOf(it) !in frames }
        val targets = moving.map { a.frameOf(it) + dd }.toSet()
        if (rest.any { a.frameOf(it) in targets }) return null
        return (rest + moving.map { a.withFrame(it, a.frameOf(it) + dd) }).sortedBy(a.frameOf)
    }

    fun <T> delete(items: List<T>, frames: Set<Int>, a: Access<T>): List<T> = items.filter { a.frameOf(it) !in frames }

    /** Копия выделенного: кадры относительно самого раннего (0, 3, 7...). */
    fun <T> copy(items: List<T>, frames: Set<Int>, a: Access<T>): List<T> {
        val picked = items.filter { a.frameOf(it) in frames }.sortedBy(a.frameOf)
        val base = picked.firstOrNull()?.let(a.frameOf) ?: return emptyList()
        return picked.map { a.withFrame(it, a.frameOf(it) - base) }
    }

    /** Вставка буфера с кадра [at]: элементы на тех же кадрах заменяются. null - вставка выходит за [0, last]. */
    fun <T> paste(items: List<T>, clip: List<T>, at: Int, last: Int, a: Access<T>): List<T>? {
        if (clip.isEmpty()) return items
        val placed = clip.map { a.withFrame(it, at + a.frameOf(it)) }
        if (placed.any { a.frameOf(it) < 0 || a.frameOf(it) > last }) return null
        val dest = placed.map(a.frameOf).toSet()
        return (items.filter { a.frameOf(it) !in dest } + placed).sortedBy(a.frameOf)
    }

    /** Кадры, куда встанет вставка с [at]. */
    fun <T> pastedFrames(clip: List<T>, at: Int, a: Access<T>): Set<Int> = clip.mapTo(HashSet()) { at + a.frameOf(it) }

    /** Дубликат выделения сразу после него (с кадра «последний выделенный + 1»). Возвращает список и кадры копий. */
    fun <T> duplicate(items: List<T>, frames: Set<Int>, last: Int, a: Access<T>): Pair<List<T>, Set<Int>>? {
        val clip = copy(items, frames, a)
        if (clip.isEmpty()) return null
        val at = items.filter { a.frameOf(it) in frames }.maxOf(a.frameOf) + 1
        val next = paste(items, clip, at, last, a) ?: return null
        return next to pastedFrames(clip, at, a)
    }

    /** Зеркало во времени внутри диапазона выделения: кадр f -> lo + hi - f. */
    fun <T> mirror(items: List<T>, frames: Set<Int>, a: Access<T>): List<T> {
        val picked = items.filter { a.frameOf(it) in frames }
        if (picked.size < 2) return items
        val lo = picked.minOf(a.frameOf); val hi = picked.maxOf(a.frameOf)
        return (items.filter { a.frameOf(it) !in frames } + picked.map { a.withFrame(it, lo + hi - a.frameOf(it)) }).sortedBy(a.frameOf)
    }

    /** Зеркало ключей времени: переход сегмента хранится у левого ключа и переезжает вместе с сегментом. */
    fun mirrorTimeKeys(items: List<TimeKey>, frames: Set<Int>): List<TimeKey> {
        val picked = items.filter { it.frame in frames }.sortedBy { it.frame }
        val n = picked.size
        if (n < 2) return items
        val lo = picked.first().frame; val hi = picked.last().frame
        val mirrored = List(n) { i ->
            val src = picked[n - 1 - i]
            val seg = if (i < n - 1) picked[n - 2 - i].ease else TimeEase.HOLD
            src.copy(frame = lo + hi - src.frame, ease = seg)
        }
        return (items.filter { it.frame !in frames } + mirrored).sortedBy { it.frame }
    }

    /** Растяжение/сжатие выделения от самого раннего кадра. null - кадры слились или вышли за границу. */
    fun <T> stretch(items: List<T>, frames: Set<Int>, factor: Float, last: Int, a: Access<T>): List<T>? {
        val picked = items.filter { a.frameOf(it) in frames }
        if (picked.size < 2) return items
        val lo = picked.minOf(a.frameOf)
        val moved = picked.map { a.withFrame(it, (lo + ((a.frameOf(it) - lo) * factor).roundToInt()).coerceIn(0, last)) }
        val fs = moved.map(a.frameOf)
        if (fs.toSet().size != fs.size) return null
        val rest = items.filter { a.frameOf(it) !in frames }
        if (rest.any { a.frameOf(it) in fs }) return null
        return (rest + moved).sortedBy(a.frameOf)
    }

    /** Выделение после растяжения (те же элементы на новых кадрах). */
    fun stretchedFrames(frames: Set<Int>, factor: Float, last: Int): Set<Int> {
        if (frames.size < 2) return frames
        val lo = frames.min()
        return frames.mapTo(HashSet()) { (lo + ((it - lo) * factor).roundToInt()).coerceIn(0, last) }
    }

    /** Переход (кривая) выделенных ключей времени. */
    fun withEase(items: List<TimeKey>, frames: Set<Int>, ease: Int): List<TimeKey> =
        items.map { if (it.frame in frames) it.copy(ease = if (TimeEase.valid(ease)) ease else TimeEase.HOLD) else it }
}
