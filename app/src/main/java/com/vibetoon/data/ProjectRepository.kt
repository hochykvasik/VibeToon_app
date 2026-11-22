package com.vibetoon.data

import android.content.Context
import android.util.Log
import com.vibetoon.domain.project.BackupMath
import com.vibetoon.domain.project.NewProjectSpec
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import java.io.File
import java.util.UUID

private const val TAG = "VT/Project"

/**
 * Проекты на диске: filesDir/projects/<id>/{project.json, assets/source.psd, thumbnail.png}.
 * Все методы блокирующие - вызывать с Dispatchers.IO. project.json пишется атомарно
 * (во временный файл, затем переименование), поэтому обрыв записи не портит проект.
 */
class ProjectRepository(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    private fun dir(id: String) = File(root, id)
    private fun jsonFile(id: String) = File(dir(id), "project.json")

    fun assetFile(id: String): File = File(dir(id), "assets/source.psd").also { it.parentFile?.mkdirs() }
    fun thumbnailFile(id: String): File = File(dir(id), "thumbnail.png")

    /** Файл медиа в папке проекта: assets/<имя>. */
    fun mediaFile(id: String, name: String): File = File(dir(id), "assets/$name").also { it.parentFile?.mkdirs() }

    /** Файл по относительному пути из project.json (например assets/m100000.png). */
    fun fileOf(id: String, relative: String): File = File(dir(id), relative)

    /** Все проекты, новые правки сверху. Повреждённые проекты пропускаются. */
    fun list(): List<ProjectMeta> =
        (root.listFiles { f -> f.isDirectory }.orEmpty()).mapNotNull { d ->
            val f = File(d, "project.json")
            if (!f.exists()) return@mapNotNull null
            ProjectJson.decode(f.readText()).onFailure { Log.w(TAG, "skip ${d.name}: ${it.message}") }
                .getOrNull()?.meta
        }.sortedByDescending { it.updatedAt }

    fun load(id: String): Result<ProjectData> = runCatching {
        val f = jsonFile(id)
        if (!f.exists()) error("Проект не найден")
        ProjectJson.decode(f.readText()).getOrThrow()
    }

    fun create(spec: NewProjectSpec): ProjectMeta {
        val now = System.currentTimeMillis()
        val meta = ProjectMeta(
            id = UUID.randomUUID().toString().take(8),
            name = spec.name.ifBlank { "Новый проект" },
            width = spec.width,
            height = spec.height,
            fps = spec.fps,
            background = spec.background,
            createdAt = now,
            updatedAt = now
        )
        save(ProjectData(meta))
        return meta
    }

    /** Готовый урок создаётся на IO без GL; сохранённый пользовательский проект не перезаписывается. */
    fun createStarter(id: String): ProjectMeta {
        load(id).getOrNull()?.let { return it.meta }
        val data = com.vibetoon.domain.project.StarterProject.data(id, System.currentTimeMillis())
        com.vibetoon.domain.project.StarterProject.shapes.forEachIndexed { i, shape ->
            val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bmp)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = shape.color
                    style = android.graphics.Paint.Style.FILL
                }
                for (polygon in com.vibetoon.domain.paint.ShapeMath.polygons(shape.kind)) {
                    val path = android.graphics.Path()
                    for (n in polygon.indices step 2) {
                        if (n == 0) path.moveTo(polygon[n] * 512, polygon[n + 1] * 512)
                        else path.lineTo(polygon[n] * 512, polygon[n + 1] * 512)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                mediaFile(id, "m${data.media[i].id}.png").outputStream().use {
                    check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bmp.recycle() }
        }
        save(data)
        return data.meta
    }

    fun save(data: ProjectData) {
        dir(data.meta.id).mkdirs()
        val tmp = File(dir(data.meta.id), "project.json.tmp")
        tmp.outputStream().use { stream ->
            stream.write(ProjectJson.encode(data).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        // На Android rename в одном каталоге атомарно заменяет цель. При ошибке не удаляем старый JSON.
        check(tmp.renameTo(jsonFile(data.meta.id))) { "Не удалось сохранить проект" }
    }

    /** Подготовленный API для copy-on-write: вызывающий сериализует доступ к проекту. */
    fun saveWithRasterVersion(data: ProjectData, layerId: Int, writePng: (File) -> Unit, verifyPng: (File) -> Boolean): ProjectData {
        require(data.media.count { it.id == layerId } == 1)
        val layer = data.media.single { it.id == layerId }
        require(layer.kind == com.vibetoon.domain.project.MediaKind.IMAGE && layer.cells.isEmpty())
        var committed: ProjectData? = null
        com.vibetoon.domain.project.VersionedRasterFiles.publish(dir(data.meta.id), writePng, verifyPng) { path ->
            data.copy(media = data.media.map { if (it.id == layerId) it.copy(file = path) else it }).also { committed = it }
                .let(ProjectJson::encode)
        }
        return checkNotNull(committed)
    }

    fun rename(id: String, name: String) {
        val data = load(id).getOrNull() ?: return
        save(data.copy(meta = data.meta.copy(name = name.ifBlank { data.meta.name }, updatedAt = System.currentTimeMillis())))
    }

    /** Копия проекта целиком (файлы и project.json с новым id); возвращает id копии. */
    fun duplicate(id: String): String? {
        val src = dir(id)
        if (!src.isDirectory) return null
        val data = load(id).getOrNull() ?: return null
        val newId = UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        src.copyRecursively(dir(newId), overwrite = true)
        File(dir(newId), "project.json.tmp").delete()
        save(data.copy(meta = data.meta.copy(id = newId, name = data.meta.name + " (копия)", createdAt = now, updatedAt = now)))
        return newId
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    /** Размер проекта на диске, байты. */
    /** Размер проекта без экспортов (готовые видео/GIF/PSD лежат отдельно и не должны раздувать карточку и сортировку по размеру). */
    fun sizeOf(id: String): Long {
        val root = dir(id)
        val exports = java.io.File(root, "exports")
        return root.walkTopDown().filter { it.isFile && !it.startsWith(exports) }.sumOf { it.length() }
    }

    fun moveToFolder(id: String, folder: String) {
        val data = load(id).getOrNull() ?: return
        save(data.copy(meta = data.meta.copy(folder = folder)))
    }

    /** Кэш, который пересоздаётся сам: волны звука (.wave) и миниатюра проекта. Возвращает освобождённые байты. */
    fun clearCache(): Long {
        var freed = 0L
        for (d in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            d.walkTopDown().filter { it.isFile && (it.name.endsWith(".wave") || (it.name.endsWith(".tmp") && it.name != "project.json.tmp" && !it.name.startsWith("project.") && !it.name.startsWith("raster_"))) }.forEach { freed += it.length(); it.delete() }
        }
        return freed
    }

    /** PNG не удаляем: на них может ссылаться активная история или незавершённая запись. */
    fun orphans(id: String): List<File> {
        val json = jsonFile(id).takeIf { it.exists() }?.readText() ?: return emptyList()
        val assets = File(dir(id), "assets")
        return assets.walkTopDown().filter { it.isFile && it.name != "source.psd" && !it.name.endsWith(".png", ignoreCase = true) && !it.name.endsWith(".wave") && !json.contains(it.name) }.toList()
    }

    /** Удаляет осиротевшие ассеты всех проектов; возвращает (число файлов, байты). */
    fun deleteOrphans(): Pair<Int, Long> {
        var n = 0; var bytes = 0L
        for (d in root.listFiles { f -> f.isDirectory }.orEmpty()) {
            for (f in orphans(d.name)) { bytes += f.length(); if (f.delete()) n++ }
        }
        return n to bytes
    }

    /** Резервная копия: ZIP проекта (.tsproj) с манифестом; экспорты и временные файлы не входят. */
    fun exportBackup(id: String, out: java.io.OutputStream) {
        val src = dir(id)
        java.util.zip.ZipOutputStream(out.buffered()).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry(BackupMath.MANIFEST))
            z.write(BackupMath.manifest().toByteArray()); z.closeEntry()
            for (f in src.walkTopDown().filter { it.isFile }) {
                val rel = f.relativeTo(src).invariantSeparatorsPath
                if (!BackupMath.included(rel)) continue
                z.putNextEntry(java.util.zip.ZipEntry(rel))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    /** Импорт .tsproj: проект распаковывается в новую папку с новым id. Возвращает id или null, если файл не наш. */
    fun importBackup(input: java.io.InputStream): String? {
        val newId = UUID.randomUUID().toString().take(8)
        val target = dir(newId)
        var hasJson = false
        try {
            java.util.zip.ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory || e.name == BackupMath.MANIFEST) continue
                    val rel = BackupMath.safePath(e.name) ?: continue   // защита от ../ в именах
                    val f = File(target, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                    if (rel == "project.json") hasJson = true
                }
            }
            if (!hasJson) { target.deleteRecursively(); return null }
            val data = load(newId).getOrNull() ?: run { target.deleteRecursively(); return null }
            val now = System.currentTimeMillis()
            save(data.copy(meta = data.meta.copy(id = newId, updatedAt = now)))
            return newId
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.message}")
            target.deleteRecursively()
            return null
        }
    }
}
