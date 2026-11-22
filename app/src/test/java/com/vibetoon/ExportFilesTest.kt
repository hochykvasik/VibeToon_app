package com.vibetoon

import com.vibetoon.domain.export.ExportFiles
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CancellationException

class ExportFilesTest {
    @Test fun failedOrCancelledWriteDeletesIncompleteFileAndSuccessKeepsIt() {
        val dir = Files.createTempDirectory("vt-export-").toFile()
        try {
            for (cancel in listOf(false, true)) {
                val file = dir.resolve("bad-$cancel.gif")
                try {
                    ExportFiles.write(file) { stream ->
                        stream.write(byteArrayOf(1, 2, 3)); stream.flush()
                        if (cancel) throw CancellationException("cancel") else throw IllegalArgumentException("group")
                    }
                    fail("must fail")
                } catch (_: Exception) { }
                assertFalse(file.exists())
            }
            val ok = dir.resolve("ok.zip")
            ExportFiles.write(ok) { it.write(byteArrayOf(1, 2, 3)) }
            assertArrayEquals(byteArrayOf(1, 2, 3), ok.readBytes())
            assertTrue(ExportFiles.discard(ok)); assertTrue(ExportFiles.discard(ok))
        } finally { dir.deleteRecursively() }
    }
}
