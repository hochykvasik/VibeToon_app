package com.vibetoon.domain.paint

/**
 * Спринт 50: на каком слое рисуют кисть, ластик, заливка и выделение. Раньше слой брался только при ровно одном
 * отмеченном слое: при мультивыборе (или выделенных вместе с ним дорожках) кисть отказывала или авто-режим создавал
 * новый пустой слой. Теперь цель - основной выбранный слой (тот, что подсвечен в панели), если это обычный слой.
 */
object PaintTargetPolicy {
    /**
     * [selected] - основной выбранный слой, [selection] - все отмеченные, [isLeaf] - обычный слой (не группа, не кость).
     * Основной слой годится, если отметок нет или он среди них.
     */
    fun primary(selected: Int?, selection: Set<Int>, isLeaf: (Int) -> Boolean): Int? {
        val id = selected ?: return selection.singleOrNull()?.takeIf(isLeaf)
        if (selection.isNotEmpty() && id !in selection) return null
        return id.takeIf(isLeaf)
    }

    enum class Kind { RASTER_MEDIA, PSD_LAYER, OTHER }

    /** Можно ли рисовать пикселями: картинка (не векторная) или слой PSD; замок запрещает. */
    fun paintable(kind: Kind, locked: Boolean): Boolean = !locked && kind != Kind.OTHER
}
