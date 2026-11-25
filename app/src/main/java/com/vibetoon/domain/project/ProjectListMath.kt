package com.vibetoon.domain.project

/**
 * Порядок сортировки списка проектов. DATE - новые сверху, SIZE - крупные сверху (как раньше);
 * спринт 46: DATE_OLD (старые сверху), SIZE_SMALL (маленькие сверху), MANUAL (свой порядок перетаскиванием).
 */
enum class ProjectSort { DATE, DATE_OLD, NAME, SIZE, SIZE_SMALL, MANUAL;
    companion object {
        /** Сохранённое имя -> сортировка (неизвестное - по дате). */
        fun of(name: String?): ProjectSort = values().firstOrNull { it.name == name } ?: DATE
    }
}

/** Чистая логика менеджера проектов: поиск, папки, сортировка, свой порядок, выделение. */
object ProjectListMath {
    /**
     * Строка для поиска: без учёта регистра, «ё» = «е», пробелы схлопнуты. Спринт 46: имена проектов показываются
     * переведёнными («Новый проект 2» -> «New project 2»), поэтому поиск сравнивает и исходное, и показанное имя.
     */
    fun normalize(s: String): String =
        s.lowercase(java.util.Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()

    /** Подходит ли проект к запросу: каждое слово запроса - часть хотя бы одного из имён ([names]: исходное и показанное). */
    fun matches(query: String, vararg names: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = names.map(::normalize)
        return words.all { w -> hay.any { it.contains(w) } }
    }

    /** Фильтр по папке ([folder] пусто - все проекты) и по запросу ([display] - имя на экране, по умолчанию исходное). */
    fun filter(all: List<ProjectMeta>, query: String, folder: String, display: (ProjectMeta) -> String = { it.name }): List<ProjectMeta> =
        all.filter { (folder.isEmpty() || it.folder == folder) && matches(query, it.name, display(it)) }

    /**
     * Спринт 46: что видно в библиотеке. Внутри папки [folder] - её проекты; в корне - проекты без папки (папки
     * показываются отдельными карточками). С запросом в корне поиск идёт по всем проектам, включая папки.
     */
    fun visible(all: List<ProjectMeta>, query: String, folder: String, display: (ProjectMeta) -> String = { it.name }): List<ProjectMeta> {
        val searching = normalize(query).isNotEmpty()
        return all.filter { p ->
            val place = if (folder.isNotEmpty()) p.folder == folder else searching || p.folder.isBlank()
            place && matches(query, p.name, display(p))
        }
    }

    /**
     * Сортировка ([sizes]: id -> байты; [manual] - свой порядок id). Без размера проект считается 0 байт. В своём порядке
     * проекты, которых в нём ещё нет (новые), идут первыми - новые сверху.
     */
    fun sort(list: List<ProjectMeta>, by: ProjectSort, sizes: Map<String, Long>, manual: List<String> = emptyList()): List<ProjectMeta> = when (by) {
        ProjectSort.DATE -> list.sortedByDescending { it.updatedAt }
        ProjectSort.DATE_OLD -> list.sortedBy { it.updatedAt }
        ProjectSort.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        ProjectSort.SIZE -> list.sortedByDescending { sizes[it.id] ?: 0L }
        ProjectSort.SIZE_SMALL -> list.sortedBy { sizes[it.id] ?: 0L }
        ProjectSort.MANUAL -> {
            val pos = HashMap<String, Int>(manual.size * 2).apply { manual.forEachIndexed { i, id -> putIfAbsent(id, i) } }
            val (known, fresh) = list.partition { it.id in pos }
            fresh.sortedByDescending { it.updatedAt } + known.sortedBy { pos.getValue(it.id) }
        }
    }

    /** Русское название сортировки для меню (переводится словарём). */
    fun sortLabel(s: ProjectSort): String = when (s) {
        ProjectSort.DATE -> "Дата: сначала новые"
        ProjectSort.DATE_OLD -> "Дата: сначала старые"
        ProjectSort.NAME -> "По имени"
        ProjectSort.SIZE -> "Размер: сначала большие"
        ProjectSort.SIZE_SMALL -> "Размер: сначала маленькие"
        ProjectSort.MANUAL -> "Свой порядок"
    }

    /**
     * Перетаскивание: [from] встаёт на место [to] в видимом порядке [visible]; остальные проекты сдвигаются.
     * Возвращает новый видимый порядок (без изменений, если id не найдены).
     */
    fun move(visible: List<String>, from: String, to: String): List<String> {
        val i = visible.indexOf(from); val j = visible.indexOf(to)
        if (i < 0 || j < 0 || i == j) return visible
        val out = visible.toMutableList()
        out.removeAt(i)
        out.add(j, from)
        return out
    }

    /**
     * Свой порядок всей библиотеки после перетаскивания в одном виде (папке или корне): места видимых проектов в общем
     * порядке [global] занимает новый видимый порядок [visible], остальные проекты не двигаются. Проекты, которых в
     * общем порядке не было, добавляются в начало (в порядке [visible]).
     */
    fun mergeOrder(global: List<String>, visible: List<String>): List<String> {
        val vis = visible.toHashSet()
        val missing = visible.filter { it !in global }
        val base = missing + global.distinct()
        val it = visible.iterator()
        val out = ArrayList<String>(base.size)
        for (id in base) {
            if (id !in vis) out += id
            else if (it.hasNext()) out += it.next()
        }
        return out
    }

    /** Свой порядок без удалённых проектов ([alive] - существующие id). */
    fun pruneOrder(order: List<String>, alive: Set<String>): List<String> = order.filter { it in alive }.distinct()

    /** Первые проекты папки для превью карточки папки (2×2), в текущей сортировке. */
    fun folderPreview(all: List<ProjectMeta>, folder: String, by: ProjectSort, sizes: Map<String, Long>, manual: List<String>, n: Int = 4): List<ProjectMeta> =
        sort(all.filter { it.folder == folder }, by, sizes, manual).take(n)

    /** Число проектов в каждой папке. */
    fun folderCounts(all: List<ProjectMeta>): Map<String, Int> = all.filter { it.folder.isNotBlank() }.groupingBy { it.folder }.eachCount()

    /** Выделение: тап по проекту добавляет или снимает его. */
    fun toggle(sel: Set<String>, id: String): Set<String> = if (id in sel) sel - id else sel + id

    /** Выделение без удалённых проектов. */
    fun pruneSelection(sel: Set<String>, alive: Collection<ProjectMeta>): Set<String> {
        val ids = alive.mapTo(HashSet()) { it.id }
        return sel.filterTo(LinkedHashSet()) { it in ids }
    }

    /** Свой порядок в настройках: id через перевод строки (id проектов - 8 символов UUID, без переводов строк). */
    fun encodeOrder(order: List<String>): String = order.joinToString("\n")
    fun decodeOrder(s: String?): List<String> = s.orEmpty().split('\n').filter { it.isNotBlank() }.distinct()

    /** Имена папок: хранимые плюс те, что встречаются у проектов; без пустых и повторов, по алфавиту. */
    fun folders(stored: Set<String>, all: List<ProjectMeta>): List<String> =
        (stored + all.map { it.folder }).filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun cleanFolderName(s: String): String = s.trim().replace(Regex("\\s+"), " ").take(24)

    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} КБ"
    }

    /**
     * Спринт 47: центр перетаскиваемой карточки [centerY] (px в сетке) над зоной «убрать из папки» - шапкой папки
     * (её верх [headTop] и высота [headHeight]) или выше неё. Шапка прокручена за край ([headTop] = null) - зона
     * у верхнего края сетки (центр выше 0).
     */
    fun overTopDropZone(centerY: Float, headTop: Int?, headHeight: Int?): Boolean =
        if (headTop == null || headHeight == null) centerY < 0f else centerY < headTop + headHeight
}
