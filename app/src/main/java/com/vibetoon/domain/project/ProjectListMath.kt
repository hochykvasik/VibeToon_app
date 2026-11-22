package com.vibetoon.domain.project

/** Порядок сортировки списка проектов. */
enum class ProjectSort { DATE, NAME, SIZE }

/** Чистая логика менеджера проектов: поиск, папки, сортировка. */
object ProjectListMath {
    /** Фильтр по папке ([folder] пусто - все проекты) и по подстроке имени без учёта регистра. */
    fun filter(all: List<ProjectMeta>, query: String, folder: String): List<ProjectMeta> {
        val q = query.trim().lowercase()
        return all.filter { (folder.isEmpty() || it.folder == folder) && (q.isEmpty() || it.name.lowercase().contains(q)) }
    }

    /** Сортировка: дата - новые сверху, имя - по алфавиту, размер - крупные сверху ([sizes]: id -> байты). */
    fun sort(list: List<ProjectMeta>, by: ProjectSort, sizes: Map<String, Long>): List<ProjectMeta> = when (by) {
        ProjectSort.DATE -> list.sortedByDescending { it.updatedAt }
        ProjectSort.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ProjectSort.SIZE -> list.sortedByDescending { sizes[it.id] ?: 0L }
    }

    /** Имена папок: хранимые плюс те, что встречаются у проектов; без пустых и повторов, по алфавиту. */
    fun folders(stored: Set<String>, all: List<ProjectMeta>): List<String> =
        (stored + all.map { it.folder }).filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun cleanFolderName(s: String): String = s.trim().replace(Regex("\\s+"), " ").take(24)

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} КБ"
    }
}
