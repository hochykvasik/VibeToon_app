package com.vibetoon.domain.project

/** Резервирует идентификаторы на время сессии, в том числе для удалённых или ещё загружающихся слоёв. */
class MediaIdAllocator {
    private var last = MEDIA_ID_BASE - 1
    fun reset() { last = MEDIA_ID_BASE - 1 }
    fun next(occupied: Iterable<Int>): Int {
        val highest = occupied.asSequence().filter { it in MEDIA_ID_BASE until BONE_ID_BASE }.maxOrNull() ?: (MEDIA_ID_BASE - 1)
        val next = maxOf(last, highest) + 1
        check(next < BONE_ID_BASE) { "Media layer limit reached" }
        last = next
        return next
    }
}
