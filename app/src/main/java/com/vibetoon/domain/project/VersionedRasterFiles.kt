package com.vibetoon.domain.project

import java.io.File
import java.util.UUID

/** Новый asset не перезаписывает файл, на который указывает текущий JSON. */
object VersionedRasterFiles {
    /** Вызывать с эксклюзивным владением проектом. После ошибки JSON новая версия может остаться как сирота. */
    fun publish(projectDir: File, writeAsset: (File) -> Unit, verifyAsset: (File) -> Boolean,
                makeJson: (String) -> String): String {
        val assets = File(projectDir, "assets")
        check(assets.isDirectory || assets.mkdirs())
        val name = "raster_${UUID.randomUUID()}.png"
        val file = File(assets, name)
        check(!file.exists())
        // Ранняя смерть процесса оставляет только неиспользуемый файл, а не повреждает старую версию.
        writeAsset(file)
        check(file.isFile && verifyAsset(file)) { "Asset verification failed" }
        java.io.RandomAccessFile(file, "rw").use { it.fd.sync() }
        val target = File(projectDir, "project.json")
        val tmp = File(projectDir, "project.${UUID.randomUUID()}.tmp")
        try {
            tmp.outputStream().use { stream ->
                stream.write(makeJson("assets/$name").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(tmp.renameTo(target)) { "JSON replacement failed" }
        } finally { tmp.delete() }
        return "assets/$name"
    }
}
