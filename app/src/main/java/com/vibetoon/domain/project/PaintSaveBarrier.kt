package com.vibetoon.domain.project

/** Не публикует метаданные, если хотя бы один обязательный PNG не сохранён. */
object PaintSaveBarrier {
    suspend fun save(paint: suspend () -> Boolean, metadata: suspend () -> Unit): Boolean {
        if (!paint()) return false
        metadata()
        return true
    }
}
