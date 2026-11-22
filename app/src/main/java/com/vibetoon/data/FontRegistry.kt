package com.vibetoon.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.vibetoon.domain.project.FontKeys
import java.io.File
import java.io.IOException

/** Шрифт в списке выбора: ключ для project.json и название для человека. */
data class FontEntry(val key: String, val title: String, val imported: Boolean)

/**
 * Шрифты приложения: системные семейства Android плюс импортированные пользователем TTF/OTF.
 * Импортированные файлы лежат в filesDir/fonts и общие для всех проектов.
 */
class FontRegistry(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()

    fun list(): List<FontEntry> {
        val system = FontKeys.SYSTEM.map { FontEntry(it.first, it.second, false) }
        val imported = dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { FontEntry(FontKeys.fileKey(it.name), it.nameWithoutExtension, true) }
        return system + imported
    }

    /** Шрифт по ключу; если файла нет или он повреждён, возвращается шрифт по умолчанию. */
    @Synchronized
    fun typeface(key: String): Typeface = cache.getOrPut(key) {
        if (FontKeys.isFile(key)) {
            val f = File(dir, FontKeys.fileName(key))
            runCatching { Typeface.createFromFile(f) }.getOrElse {
                Log.w(TAG, "font $key not available: ${it.message}")
                Typeface.DEFAULT
            }
        } else {
            Typeface.create(key, Typeface.NORMAL)
        }
    }

    fun title(key: String): String = list().firstOrNull { it.key == key }?.title
        ?: if (FontKeys.isFile(key)) FontKeys.fileName(key).substringBeforeLast('.') else key

    /**
     * Импорт TTF/OTF: файл копируется в папку шрифтов и проверяется загрузкой как Typeface;
     * если шрифт не читается, копия удаляется. Блокирующий вызов (фоновый поток).
     */
    fun import(uri: Uri): Result<FontEntry> = runCatching {
        val name = displayName(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) throw IOException("Нужен файл шрифта TTF или OTF")
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, safe)
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
        try {
            Typeface.createFromFile(dest)   // бросает исключение для повреждённого файла
        } catch (e: Exception) {
            dest.delete()
            throw IOException("Не удалось прочитать шрифт")
        }
        synchronized(this) { cache.remove(FontKeys.fileKey(safe)) }
        FontEntry(FontKeys.fileKey(safe), dest.nameWithoutExtension, true)
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font.ttf"

    companion object {
        private const val TAG = "VT/Font"
        val EXTENSIONS = setOf("ttf", "otf")
    }
}
