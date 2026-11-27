package com.vibetoon

import com.vibetoon.domain.model.LayerListView
import com.vibetoon.domain.model.LayerListView.Category
import com.vibetoon.domain.model.LayerListView.Entry
import com.vibetoon.domain.model.LayerListView.Filter
import org.junit.Assert.*
import org.junit.Test

/** Спринт 39: вид слоёв «Список» - сохранение выбора, поиск, фильтры, папки, маски, кости и пути. */
class Package39LayerListViewTest {
    // Персонаж (папка) > Голова (папка) > Глаза (рисунок, с маской), Маска глаз (рисунок-маска); Тело (рисунок); Титр (текст);
    // Скелет (слой костей) с костями; Траектория (путь); Фон (картинка)
    private val full = listOf(
        Entry(1, "Персонаж", 0, -1, Category.GROUP, isGroup = true),
        Entry(2, "Голова", 1, 1, Category.GROUP, isGroup = true),
        Entry(3, "Глаза", 2, 2, Category.DRAWING, mask = true),
        Entry(4, "Маска глаз", 2, 2, Category.DRAWING, mask = true),
        Entry(5, "Тело", 1, 1, Category.DRAWING),
        Entry(6, "Титр", 0, -1, Category.TEXT),
        Entry(7, "Скелет", 0, -1, Category.BONES),
        Entry(8, "Траектория", 0, -1, Category.PATH),
        Entry(9, "Фон", 0, -1, Category.MEDIA)
    )

    @Test
    fun modeIsStoredAsString() {
        assertTrue(LayerListView.isList(LayerListView.store(true)))
        assertFalse(LayerListView.isList(LayerListView.store(false)))
        assertFalse(LayerListView.isList(null))          // по умолчанию - привычные миниатюры
        assertFalse(LayerListView.isList("garbage"))
        assertEquals("layers_view", LayerListView.PREF_KEY)
    }

    @Test
    fun withoutSearchCollapsedTreeIsShownAsIs() {
        val collapsed = full.filter { it.parent != 2 }   // «Голова» свёрнута
        val shown = LayerListView.apply(collapsed, full, Filter.ALL, "  ")
        assertEquals(collapsed, shown.map { it.entry })
        assertTrue(shown.all { it.matched })
        assertEquals("Слоёв: 9", LayerListView.countLabel(shown, 9, Filter.ALL, ""))
    }

    @Test
    fun searchFindsInsideCollapsedFoldersAndKeepsParents() {
        val collapsed = full.filter { it.depth == 0 }    // всё свёрнуто
        val shown = LayerListView.apply(collapsed, full, Filter.ALL, "глаз")
        assertEquals(listOf(1, 2, 3, 4), shown.map { it.entry.id })
        assertEquals(listOf(false, false, true, true), shown.map { it.matched })
        assertEquals("Найдено: 2 из 9", LayerListView.countLabel(shown, 9, Filter.ALL, "глаз"))
        // регистр не важен, пустой результат - пустой список
        assertEquals(listOf(6), LayerListView.apply(collapsed, full, Filter.ALL, "ТИТР").map { it.entry.id })
        assertTrue(LayerListView.apply(collapsed, full, Filter.ALL, "нет такого").isEmpty())
    }

    @Test
    fun filtersByKind() {
        fun ids(f: Filter, q: String = "") = LayerListView.apply(full, full, f, q).filter { it.matched }.map { it.entry.id }
        assertEquals(listOf(3, 4, 5), ids(Filter.DRAWING))
        assertEquals(listOf(6), ids(Filter.TEXT))
        assertEquals(listOf(9), ids(Filter.MEDIA))
        assertEquals(listOf(1, 2), ids(Filter.GROUPS))
        assertEquals(listOf(3, 4), ids(Filter.MASKS))
        assertEquals(listOf(7, 8), ids(Filter.RIG))
        assertEquals(listOf(5), ids(Filter.DRAWING, "тел"))   // фильтр и поиск вместе
    }

    @Test
    fun bonesAppearUnderTheirBoneLayerAndAreSearchable() {
        val bones = mapOf(7 to listOf(101 to "Плечо", 102 to "Локоть"))
        val tree = LayerListView.withBones(full, bones)
        val i = tree.indexOfFirst { it.id == 7 }
        assertEquals(101, tree[i + 1].id); assertTrue(tree[i + 1].bone); assertEquals(1, tree[i + 1].depth)
        assertEquals(Category.BONE, tree[i + 2].category)
        // свёрнутый слой костей прячет кости
        assertEquals(full, LayerListView.withBones(full, bones, collapsed = setOf(7)))
        val found = LayerListView.apply(full, tree, Filter.ALL, "локоть")
        assertEquals(listOf(7, 102), found.map { it.entry.id })
        assertEquals(listOf(false, true), found.map { it.matched })
        assertEquals(listOf(7, 101, 102, 8), LayerListView.apply(full, tree, Filter.RIG, "").map { it.entry.id })
    }

    @Test
    fun boneIdsDoNotCollideWithLayerParents() {
        // кость с тем же числом id, что и папка, не делает папку «родителем»
        val tree = LayerListView.withBones(full, mapOf(7 to listOf(1 to "Кость 1")))
        val shown = LayerListView.apply(full, tree, Filter.ALL, "Кость")
        assertEquals(listOf(7, 1), shown.map { it.entry.id })
        assertTrue(shown.last().entry.bone)
    }

    @Test
    fun categoriesFromLayerTypes() {
        assertEquals(Category.DRAWING, LayerListView.categoryOf("Пиксели"))
        assertEquals(Category.DRAWING, LayerListView.categoryOf("Вектор"))
        assertEquals(Category.MEDIA, LayerListView.categoryOf("Видео"))
        assertEquals(Category.GROUP, LayerListView.categoryOf("Символ"))
        assertEquals(Category.BONES, LayerListView.categoryOf("Слой костей"))
        assertEquals(Category.PATH, LayerListView.categoryOf("Путь"))
        assertEquals(Category.OTHER, LayerListView.categoryOf("Камера"))
        for (f in Filter.values()) assertTrue(f.title.isNotBlank())
    }
}
