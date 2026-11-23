package com.vibetoon.domain.project

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Пакет 13: импорт проекта без Android - распознавание файла, безопасная распаковка, проверка и подготовка данных.
 *
 * Поддерживается:
 *  - .tsproj (ZIP, создаётся «Резервной копией»): формат 1 (только папка проекта) и 2 (плюс использованные
 *    шрифты в fonts/ и сведения о проекте в манифесте);
 *  - тот же ZIP, переименованный в .zip или сжатый вручную вместе с папкой (project.json во вложенной папке);
 *  - отдельный project.json (картинки, видео и звук такого проекта будут отмечены как отсутствующие);
 *  - PSD распознаётся отдельно: для него создаётся новый проект с размером из заголовка PSD (это делает вызывающий).
 *
 * Распаковка идёт во временную папку, проект появляется в списке только целиком (переименованием папки).
 * Пути вида ../ и абсолютные пропускаются, служебные файлы архиваторов (__MACOSX, .DS_Store) не копируются,
 * число записей и объём ограничены (защита от «ZIP-бомбы»).
 */
object ProjectArchive {
    const val FORMAT = 2
    const val FONTS_DIR = "fonts/"
    const val MAX_ENTRIES = 50_000
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    enum class Kind { ZIP, JSON, PSD, UNKNOWN }

    /** Причины отказа: у каждой своё понятное сообщение (RU/EN через таблицу перевода). */
    enum class Reason(val message: String) {
        NOT_PROJECT("Файл не похож на проект VibeToon: нужен .tsproj, .zip с project.json, project.json или PSD"),
        NEWER_VERSION("Проект создан в более новой версии VibeToon: обновите приложение и повторите импорт"),
        DAMAGED("Файл проекта повреждён или обрезан: project.json не читается"),
        TOO_LARGE("Архив слишком большой или содержит слишком много файлов"),
        NO_SPACE("Недостаточно места на устройстве для импорта"),
        READ_FAILED("Не удалось прочитать выбранный файл")
    }

    class ImportException(val reason: Reason, cause: Throwable? = null) : IOException(reason.message, cause)

    /** Итог подготовки: данные с новым id, исходная версия формата, отсутствующие файлы, шрифты. */
    data class Report(
        val data: ProjectData,
        val sourceVersion: Int,
        val missing: List<String>,
        val fontsRestored: Int = 0,
        val fontsMissing: List<String> = emptyList(),
        val renamed: Boolean = false
    )

    /** Тип файла по первым байтам (расширение у файлов из «Загрузок» часто теряется). */
    fun detect(head: ByteArray, size: Int = head.size): Kind {
        val n = minOf(size, head.size)
        if (n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4) return Kind.ZIP
        if (n >= 4 && head[0] == '8'.code.toByte() && head[1] == 'B'.code.toByte() && head[2] == 'P'.code.toByte() && head[3] == 'S'.code.toByte()) return Kind.PSD
        var i = 0
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3   // BOM
        while (i < n && head[i].toInt().toChar().isWhitespace()) i++
        if (i < n && head[i] == '{'.code.toByte()) return Kind.JSON
        return Kind.UNKNOWN
    }

    /** Размер холста из заголовка PSD (ширина, высота) или null. */
    fun psdSize(head: ByteArray): Pair<Int, Int>? {
        if (head.size < 26 || detect(head) != Kind.PSD) return null
        fun be(o: Int) = ((head[o].toInt() and 0xFF) shl 24) or ((head[o + 1].toInt() and 0xFF) shl 16) or ((head[o + 2].toInt() and 0xFF) shl 8) or (head[o + 3].toInt() and 0xFF)
        val h = be(14); val w = be(18)
        return if (w > 0 && h > 0) w to h else null
    }

    /** Холст нового проекта для PSD: тот же размер (чётный), длинная сторона не больше [ProjectMath.MAX_SIZE]. */
    fun psdCanvas(w: Int, h: Int): Pair<Int, Int> {
        val k = minOf(1.0, ProjectMath.MAX_SIZE.toDouble() / maxOf(w, h, 1))
        fun even(v: Double) = ProjectMath.clampSize((Math.round(v).toInt() / 2) * 2)
        return even(w * k) to even(h * k)
    }

    /** Служебные записи архиваторов, которые в проект не копируются. */
    fun isJunk(path: String): Boolean {
        val parts = path.split('/')
        val name = parts.last()
        return parts.any { it == "__MACOSX" } || name == ".DS_Store" || name == "Thumbs.db" || name == "desktop.ini" || name.startsWith("._")
    }

    /**
     * Распаковывает ZIP в [dest]. Возвращает число файлов. Бросает [ImportException] при превышении лимитов,
     * нехватке места или повреждённом архиве.
     */
    fun unzip(input: InputStream, dest: File, maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_BYTES): Int {
        var files = 0
        var total = 0L
        val buf = ByteArray(64 * 1024)
        try {
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val rel = BackupMath.safePath(e.name) ?: continue
                    if (isJunk(rel)) continue
                    if (++files > maxEntries) throw ImportException(Reason.TOO_LARGE)
                    val f = File(dest, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out ->
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw ImportException(Reason.TOO_LARGE)
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: IOException) {
            throw ImportException(if (isNoSpace(e)) Reason.NO_SPACE else Reason.DAMAGED, e)
        } catch (e: IllegalArgumentException) {   // кривые имена/кодировка записей
            throw ImportException(Reason.DAMAGED, e)
        }
        return files
    }

    fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val m = t.message.orEmpty()
            if ("ENOSPC" in m || "No space" in m) return true
            t = t.cause
        }
        return false
    }

    /**
     * Папка с project.json: корень архива или единственная вложенная папка (архив, сжатый вместе с папкой).
     * null - проекта в архиве нет.
     */
    fun locateRoot(staging: File): File? {
        if (File(staging, "project.json").isFile) return staging
        val subs = staging.listFiles { f -> f.isDirectory && File(f, "project.json").isFile }.orEmpty()
        if (subs.size == 1) return subs[0]
        val deeper = staging.listFiles { f -> f.isDirectory }.orEmpty()
        if (deeper.size == 1) return locateRoot(deeper[0])   // ещё один уровень вложенности (Проект/Проект/project.json)
        return null
    }

    /** Версия формата из project.json (без полного разбора). */
    fun versionOf(json: String): Int? = runCatching {
        @Suppress("UNCHECKED_CAST")
        ((MiniJson.parse(json) as? Map<String, Any?>)?.get("version") as? Number)?.toInt() ?: 1
    }.getOrNull()

    /** Файлы, на которые ссылается проект (относительно папки проекта). */
    fun referencedFiles(d: ProjectData): List<String> {
        val out = LinkedHashSet<String>()
        if (d.psd != null) out += "assets/source.psd"
        for (m in d.media) {
            when (m.kind) {
                MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO -> {
                    if (m.cells.isNotEmpty()) m.cells.forEach { if (it.file.isNotEmpty()) out += it.file }
                    else if (m.file.isNotEmpty()) out += m.file
                }
                else -> {}
            }
        }
        return out.toList()
    }

    /** Импортированные шрифты (файлы TTF/OTF), которые использует текст проекта. */
    fun usedFontFiles(d: ProjectData): Set<String> =
        d.media.filter { it.isText && FontKeys.isFile(it.font) }.mapTo(LinkedHashSet()) { FontKeys.fileName(it.font) }

    /** Имя без повтора: «Имя», «Имя (2)», «Имя (3)»… */
    fun uniqueName(name: String, existing: Collection<String>): String {
        val base = name.trim().ifEmpty { "Проект" }
        if (base !in existing) return base
        var k = 2
        while ("$base ($k)" in existing) k++
        return "$base ($k)"
    }

    /**
     * Читает и проверяет project.json из [root], назначает новый id и уникальное имя, считает отсутствующие файлы.
     * Сам project.json не переписывает (это делает репозиторий текущим форматом).
     */
    fun prepare(root: File, newId: String, now: Long, existingNames: Collection<String>): Report {
        val jsonFile = File(root, "project.json")
        if (!jsonFile.isFile) throw ImportException(Reason.NOT_PROJECT)
        val text = try { jsonFile.readText() } catch (e: IOException) { throw ImportException(Reason.READ_FAILED, e) }
        return prepareJson(text, newId, now, existingNames) { rel -> File(root, rel).let { it.isFile && it.length() > 0 } }
    }

    /** То же для текста project.json; [exists] - есть ли файл проекта по относительному пути. */
    fun prepareJson(text: String, newId: String, now: Long, existingNames: Collection<String>, exists: (String) -> Boolean): Report {
        val version = versionOf(text) ?: throw ImportException(Reason.DAMAGED)
        if (version > ProjectJson.VERSION) throw ImportException(Reason.NEWER_VERSION)
        val data = ProjectJson.decode(text).getOrElse { throw ImportException(Reason.DAMAGED, it) }
        val name = uniqueName(data.meta.name, existingNames)
        val meta = data.meta.copy(
            id = newId, name = name,
            width = ProjectMath.clampSize(data.meta.width), height = ProjectMath.clampSize(data.meta.height),
            fps = data.meta.fps.coerceIn(1, 120),
            createdAt = if (data.meta.createdAt > 0) data.meta.createdAt else now,
            updatedAt = now
        )
        val fixed = data.copy(meta = meta)
        val missing = referencedFiles(fixed).filterNot(exists)
        return Report(fixed, version, missing, renamed = name != data.meta.name.trim().ifEmpty { "Проект" })
    }

    /**
     * Шрифты из fonts/ архива копируются в папку шрифтов приложения (существующие с тем же именем не трогаются).
     * Возвращает (сколько добавлено, каких использованных шрифтов нет ни в архиве, ни в приложении).
     */
    fun restoreFonts(archiveFonts: File?, appFonts: File, used: Set<String>): Pair<Int, List<String>> {
        var restored = 0
        appFonts.mkdirs()
        archiveFonts?.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("ttf", "otf") }.orEmpty().forEach { f ->
            val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(appFonts, safe)
            if (!dest.exists() && runCatching { f.copyTo(dest) }.isSuccess) restored++
        }
        val missing = used.filterNot { File(appFonts, it).isFile }
        return restored to missing
    }

    /** Манифест .tsproj формата 2: приложение, формат, версия и имя проекта (для будущих проверок). */
    fun manifest(version: Int, name: String): String =
        MiniJson.stringify(linkedMapOf<String, Any?>("app" to "VibeToon", "format" to FORMAT, "version" to version, "name" to name), pretty = false)
}
