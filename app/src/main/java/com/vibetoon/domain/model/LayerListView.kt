package com.vibetoon.domain.model

/**
 * Спринт 39: вид панели слоёв «Список» (компактные строки без миниатюр, поиск и фильтры) рядом с привычным
 * видом «Миниатюры». Выбор вида хранится в настройках «vt_app» ([PREF_KEY]) и попадает в резервную копию.
 * Чистые функции - unit-тест; панель только рисует результат [apply].
 */
object LayerListView {
    const val PREF_KEY = "layers_view"
    const val THUMBS = "thumbs"
    const val LIST = "list"

    fun isList(stored: String?): Boolean = stored == LIST
    fun store(list: Boolean): String = if (list) LIST else THUMBS

    /** Категория строки для фильтра. */
    enum class Category { DRAWING, TEXT, MEDIA, GROUP, BONES, PATH, BONE, OTHER }

    /** Фильтры списка: подписи переводятся i18n. */
    enum class Filter(val title: String) {
        ALL("Все"), DRAWING("Рисунки"), TEXT("Текст"), MEDIA("Медиа"), GROUPS("Группы"), MASKS("Маски"), RIG("Кости и пути")
    }

    /**
     * Строка дерева: [parent] - id строки-родителя (папки или слоя костей) или -1, [depth] - вложенность,
     * [mask] - у слоя есть маска слоя или он сам служит маской, [bone] - строка кости внутри слоя костей.
     */
    data class Entry(
        val id: Int, val name: String, val depth: Int, val parent: Int, val category: Category,
        val isGroup: Boolean = false, val mask: Boolean = false, val bone: Boolean = false
    )

    /** Показанная строка: [matched] - подходит под поиск/фильтр; false - папка-родитель, оставлена для контекста. */
    data class Shown(val entry: Entry, val matched: Boolean)

    /** Категория по типу слоя из MainViewModel.layerType (русские названия типов). */
    fun categoryOf(type: String): Category = when (type) {
        "Пиксели", "Вектор" -> Category.DRAWING
        "Текст" -> Category.TEXT
        "Картинка", "Видео", "Аудио" -> Category.MEDIA
        "Группа", "Символ" -> Category.GROUP
        "Слой костей" -> Category.BONES
        "Путь" -> Category.PATH
        else -> Category.OTHER
    }

    fun active(filter: Filter, query: String): Boolean = filter != Filter.ALL || query.isNotBlank()

    fun matches(e: Entry, filter: Filter, query: String): Boolean {
        val okFilter = when (filter) {
            Filter.ALL -> true
            Filter.DRAWING -> e.category == Category.DRAWING
            Filter.TEXT -> e.category == Category.TEXT
            Filter.MEDIA -> e.category == Category.MEDIA
            Filter.GROUPS -> e.isGroup || e.category == Category.GROUP
            Filter.MASKS -> e.mask
            Filter.RIG -> e.category == Category.BONES || e.category == Category.PATH || e.category == Category.BONE
        }
        if (!okFilter) return false
        val q = query.trim()
        return q.isEmpty() || e.name.contains(q, ignoreCase = true)
    }

    /**
     * Строки для показа. Без поиска и фильтра - [collapsedTree] как есть (свёрнутые папки работают).
     * С поиском/фильтром - по полному дереву [fullTree]: подходящие строки и их родители (папки, слой костей)
     * в исходном порядке, чтобы было видно, где лежит найденный слой.
     */
    fun apply(collapsedTree: List<Entry>, fullTree: List<Entry>, filter: Filter, query: String): List<Shown> {
        if (!active(filter, query)) return collapsedTree.map { Shown(it, true) }
        val byId = HashMap<Int, Entry>()
        for (e in fullTree) if (!e.bone) byId[e.id] = e
        val keep = HashSet<Int>()           // индексы в fullTree
        val keepParents = HashSet<Int>()    // id родителей
        for ((i, e) in fullTree.withIndex()) {
            if (!matches(e, filter, query)) continue
            keep += i
            var p = e.parent
            var guard = 0
            while (p >= 0 && guard++ < 64) {
                if (!keepParents.add(p)) break
                p = byId[p]?.parent ?: -1
            }
        }
        val out = ArrayList<Shown>()
        for ((i, e) in fullTree.withIndex()) {
            val m = i in keep
            if (m || (!e.bone && e.id in keepParents)) out += Shown(e, m)
        }
        return out
    }

    /** Подпись счётчика: «Найдено: N из M» при активном поиске/фильтре. */
    fun countLabel(shown: List<Shown>, total: Int, filter: Filter, query: String): String =
        if (active(filter, query)) "Найдено: ${shown.count { it.matched }} из $total" else "Слоёв: $total"

    /** Вставка строк костей сразу под их слоем костей (развёрнутым), глубина +1. */
    fun withBones(tree: List<Entry>, bonesByLayer: Map<Int, List<Pair<Int, String>>>, collapsed: Set<Int> = emptySet()): List<Entry> {
        if (bonesByLayer.isEmpty()) return tree
        val out = ArrayList<Entry>(tree.size)
        for (e in tree) {
            out += e
            if (e.category == Category.BONES && e.id !in collapsed)
                for ((bid, name) in bonesByLayer[e.id].orEmpty()) out += Entry(bid, name, e.depth + 1, e.id, Category.BONE, bone = true)
        }
        return out
    }
}
