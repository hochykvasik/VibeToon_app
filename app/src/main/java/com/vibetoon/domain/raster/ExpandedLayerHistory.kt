package com.vibetoon.domain.raster

/** Пара полных состояний одного слоя; используется одной записью истории, не выполняет GL или файловый ввод. */
class ExpandedLayerHistory(val change: ExpandedLayerSnapshot.Change) {
    /** Снимки хранятся без сжатия; маска также остаётся в памяти вместе с ними. */
    val sizeBytes: Long get() = change.before.rgba.size.toLong() + change.after.rgba.size + change.selection.size

    enum class Direction { UNDO, REDO }
    data class Transition(val expected: ExpandedLayerSnapshot.State, val target: ExpandedLayerSnapshot.State)

    fun transition(direction: Direction): Transition = when (direction) {
        Direction.UNDO -> Transition(change.after, change.before)
        Direction.REDO -> Transition(change.before, change.after)
    }
}
