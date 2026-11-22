package com.vibetoon.domain.paint

/** Последние/сохранённые цвета: свежие впереди, без повторов, не больше [MAX]. */
object ColorPalette {
    const val MAX = 8

    fun add(list: List<Long>, color: Long): List<Long> {
        val c = color and 0xFFFFFFFFL
        return (listOf(c) + list.filter { it != c }).take(MAX)
    }

    fun encode(list: List<Long>): String = list.joinToString(",") { java.lang.Long.toHexString(it) }

    fun decode(s: String?): List<Long> =
        s.orEmpty().split(',').mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() }?.toLongOrNull(16) }.distinct().take(MAX)
}
