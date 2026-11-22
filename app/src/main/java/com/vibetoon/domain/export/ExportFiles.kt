package com.vibetoon.domain.export

import java.io.File
import java.io.OutputStream

/** Close output before deleting an incomplete export, including on cancellation/failure. */
object ExportFiles {
    inline fun <T> write(file: File, block: (OutputStream) -> T): T {
        try {
            file.parentFile?.mkdirs()
            return file.outputStream().buffered().use(block)
        } catch (t: Throwable) {
            discard(file)
            throw t
        }
    }
    fun discard(file: File): Boolean = !file.exists() || file.delete()
}
