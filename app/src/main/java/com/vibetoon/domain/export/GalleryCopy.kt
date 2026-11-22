package com.vibetoon.domain.export

/** A failed gallery copy never turns a successfully exported project file into a failed export. */
object GalleryCopy {
    fun <T : Any> publish(create: () -> T?, write: (T) -> Unit, finish: (T) -> Boolean, discard: (T) -> Unit): Boolean {
        var entry: T? = null
        return try {
            val item = create() ?: return false
            entry = item
            write(item)
            if (!finish(item)) throw IllegalStateException("Could not publish gallery item")
            true
        } catch (_: Exception) {
            entry?.let { runCatching { discard(it) } }
            false
        }
    }
}
