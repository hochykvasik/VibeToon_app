package com.vibetoon.domain.project

/**
 * Библиотека ассетов: разделяет файлы папки assets проекта на полезные картинки и служебный кэш.
 * Раньше в библиотеку попадали все PNG папки: миниатюры маркеров (st2_*), кэш Liquify (liq_*), снимки выделения,
 * рисунки покадровых слоёв (m12_c3.png) и старые версии рисунков после правки кистью (raster_*) - отсюда
 * «технические» картинки и случайные каракули.
 */
object AssetCatalog {
    enum class Kind {
        /** Картинка, которую сейчас использует слой проекта. */
        IN_PROJECT,
        /** Картинка удалённого слоя / прошлого импорта: файл остался в папке, слоя больше нет. */
        UNUSED,
        /** Кэш и служебные файлы - в библиотеке не показываются. */
        SERVICE
    }

    /** Папка библиотеки (пакет 11): картинки, видео, аудио и PSD. */
    enum class Type { IMAGE, VIDEO, AUDIO, PSD }

    data class Entry(val file: String, val rel: String, val title: String, val kind: Kind, val type: Type = Type.IMAGE)

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")
    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "3gp", "mkv")
    private val AUDIO_EXT = setOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus")

    /** Тип файла по расширению или null (служебный: .wave, .json, ...). */
    fun typeOf(file: String): Type? = when (file.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXT -> Type.IMAGE
        in VIDEO_EXT -> Type.VIDEO
        in AUDIO_EXT -> Type.AUDIO
        "psd" -> Type.PSD
        else -> null
    }
    /** Кэш: миниатюры маркеров символов, Liquify, снимки, PCM/волна. */
    private val CACHE_PREFIX = listOf("st_", "st2_", "liq_", "thumb", "snap", "tmp", "cache", "pcm_", "wave_")
    /** Версии рисунков: нужны, пока на них ссылается слой; после правки старая версия остаётся в папке до очистки. */
    private val REVISION_PREFIX = listOf("raster_", "selection_")
    private val CELL = Regex("""^m\d+_c\d+\.(png|webp)$""", RegexOption.IGNORE_CASE)

    /**
     * [files] - имена файлов в assets; [used] - имя файла -> название слоя для картинок, которые используют слои
     * (обычные картинки, не кадры покадровой анимации); [cellFiles] - имена файлов кадров покадровых слоёв.
     */
    fun classify(file: String, used: Map<String, String>, cellFiles: Set<String>): Entry {
        val type = typeOf(file)
        val lower = file.lowercase()
        val rel = "assets/$file"
        val kind = when {
            type == null -> Kind.SERVICE
            CACHE_PREFIX.any { lower.startsWith(it) } -> Kind.SERVICE
            // PSD проекта (assets/source.psd) - всегда «в проекте»: его слои уже разложены на холсте
            type == Type.PSD -> Kind.IN_PROJECT
            type != Type.IMAGE -> if (file in used) Kind.IN_PROJECT else Kind.UNUSED
            file in cellFiles || CELL.matches(file) -> Kind.SERVICE
            // Рисунок слоя (в т.ч. raster_/selection_) показывается, пока его использует слой.
            file in used -> Kind.IN_PROJECT
            // Старые версии рисунков после правки кистью и отделения выделения - служебные.
            REVISION_PREFIX.any { lower.startsWith(it) } -> Kind.SERVICE
            else -> Kind.UNUSED
        }
        val title = used[file]?.takeIf { it.isNotBlank() } ?: file
        return Entry(file, rel, title, kind, type ?: Type.IMAGE)
    }

    /** Записи одной папки библиотеки. */
    fun folder(entries: List<Entry>, type: Type): List<Entry> = entries.filter { it.type == type }

    /** Все файлы: сначала используемые (по названию слоя), затем неиспользуемые; служебные отброшены. */
    fun library(files: List<String>, used: Map<String, String>, cellFiles: Set<String>): List<Entry> =
        files.map { classify(it, used, cellFiles) }
            .filter { it.kind != Kind.SERVICE }
            .sortedWith(compareBy<Entry>({ it.kind.ordinal }, { it.title.lowercase() }))

    fun serviceCount(files: List<String>, used: Map<String, String>, cellFiles: Set<String>): Int =
        files.count { classify(it, used, cellFiles).kind == Kind.SERVICE }
}
