package com.vibetoon

import com.vibetoon.domain.ui.ActionBarLayout
import com.vibetoon.domain.ui.ReferenceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 61: верхняя строка экрана проектов и шапки редактора на телефоне. Раньше 8 кнопок и заголовок с weight(1f)
 * делили одну строку, и последняя кнопка («Настройки») сжималась до нескольких dp. Теперь кнопки полного размера в
 * полосе с прокруткой, заголовок - не уже MIN_TITLE_DP (или 40% строки) со «…».
 */
class Sprint61LayoutTest {
    private val projectButtons = 8          // создать, поиск, сортировка, импорт, экспорт, папка, справка, настройки
    private fun bar(screenDp: Float) = screenDp - 24f   // отступы строки 12 dp по краям

    @Test fun portraitPhonesScrollButtonsInsteadOfShrinking() {
        for (w in listOf(320f, 360f, 393f, 411f)) {
            val p = ActionBarLayout.plan(bar(w), projectButtons, hasTitle = true, leftHanded = false)
            assertTrue("$w: кнопки не помещаются - полоса прокручивается", p.scrolls)
            // кнопка не сжимается: ширина полосы - это окно прокрутки, а не доля кнопки
            assertTrue("$w: видна хотя бы одна кнопка целиком", p.visibleButtons >= 1)
            assertTrue("$w: заголовок не схлопывается", p.titleDp >= minOf(ActionBarLayout.MIN_TITLE_DP, bar(w) * ActionBarLayout.MAX_TITLE_FRACTION) - 0.01f)
            assertEquals("$w: строка занята целиком без переполнения", bar(w), p.titleDp + p.stripDp, 0.01f)
            assertTrue("$w: полоса не перекрывает заголовок", p.stripDp <= bar(w))
        }
    }

    @Test fun oldLayoutReallyOverflowed() {
        // доказательство причины: 8 кнопок по 48 dp шире строки любого телефона в портрете
        for (w in listOf(360f, 411f)) assertTrue(projectButtons * ActionBarLayout.BUTTON_DP > bar(w) - ActionBarLayout.MIN_TITLE_DP)
    }

    @Test fun landscapeAndTabletFitWithoutScrolling() {
        for (w in listOf(640f, 731f, 800f, 1280f)) {
            val p = ActionBarLayout.plan(bar(w), projectButtons, hasTitle = true, leftHanded = false)
            assertFalse("$w: всё помещается", p.scrolls)
            assertEquals(projectButtons, p.visibleButtons)
            assertEquals("$w: заголовок забирает остаток", bar(w) - projectButtons * 48f, p.titleDp, 0.01f)
        }
    }

    @Test fun rotationReplansFromWidthOnly() {
        val portrait = ActionBarLayout.plan(bar(360f), projectButtons, true, false)
        val landscape = ActionBarLayout.plan(bar(780f), projectButtons, true, false)
        val back = ActionBarLayout.plan(bar(360f), projectButtons, true, false)
        assertTrue(portrait.scrolls); assertFalse(landscape.scrolls)
        assertEquals("поворот туда и обратно даёт ту же раскладку", portrait, back)
    }

    @Test fun leftHandedMirrorsOrderAndKeepsSizes() {
        val r = ActionBarLayout.plan(bar(360f), projectButtons, true, leftHanded = false)
        val l = ActionBarLayout.plan(bar(360f), projectButtons, true, leftHanded = true)
        assertFalse(r.mirrored); assertTrue(l.mirrored)
        assertEquals(r.copy(mirrored = true), l)
        val items = listOf("Создать", "Поиск", "Сортировка", "Настройки")
        assertEquals(items, ActionBarLayout.order(items, false))
        assertEquals(listOf("Настройки", "Сортировка", "Поиск", "Создать"), ActionBarLayout.order(items, true))
        assertEquals("Настройки у левого края у левши", "Настройки", ActionBarLayout.order(items, true).first())
    }

    @Test fun badInputsDoNotProduceNegativeOrNaN() {
        for (barDp in listOf(Float.NaN, -50f, 0f, Float.POSITIVE_INFINITY)) {
            val p = ActionBarLayout.plan(barDp, 8, true, false, buttonDp = Float.NaN, extraDp = Float.NaN)
            for (v in listOf(p.titleDp, p.stripDp)) assertTrue("$barDp -> $v", v.isFinite() && v >= 0f)
            assertTrue(p.visibleButtons >= 0)
        }
        val none = ActionBarLayout.plan(300f, -3, hasTitle = false, leftHanded = false)
        assertEquals(0f, none.titleDp, 0f); assertFalse(none.scrolls); assertEquals(0, none.visibleButtons)
    }

    @Test fun searchFieldMovesToItsOwnRowOnPhones() {
        assertFalse(ActionBarLayout.inlineSearch(360f))
        assertFalse(ActionBarLayout.inlineSearch(599f))
        assertTrue(ActionBarLayout.inlineSearch(600f))
        assertFalse(ActionBarLayout.inlineSearch(Float.NaN))
        // планшет: поле поиска 220 dp в строке учитывается как ширина
        val p = ActionBarLayout.plan(bar(800f), projectButtons, true, false, extraDp = 220f)
        assertFalse(p.scrolls)
        assertEquals(bar(800f) - 8 * 48f - 220f, p.titleDp, 0.01f)
    }

    @Test fun edgeHintsFollowScrollPosition() {
        assertEquals(false to false, ActionBarLayout.edgeHints(0, 0))
        assertEquals(false to true, ActionBarLayout.edgeHints(0, 120))
        assertEquals(true to true, ActionBarLayout.edgeHints(60, 120))
        assertEquals(true to false, ActionBarLayout.edgeHints(120, 120))
    }

    @Test fun editorTopBarOnPhoneKeepsButtonsAndTitle() {
        // шапка редактора: 2 кнопки слева, справа FX, Undo, Redo, Export (значок), «Ещё» - по 42 dp
        val left = 2; val right = 3 + 1 + 1
        for (w in listOf(320f, 360f)) {
            val p = ActionBarLayout.plan(w - 8f - left * 42f, right, true, false, buttonDp = 42f)
            assertTrue(p.titleDp > 0f)
            assertEquals(w - 8f - left * 42f, p.titleDp + p.stripDp, 0.01f)
        }
        val wide = ActionBarLayout.plan(1200f - 8f - 3 * 42f, 1 + 3 + 2 + 3, true, false, buttonDp = 42f)
        assertFalse(wide.scrolls)
    }

    @Test fun referenceWindowRecoversFromNaNFrame() {
        val f = ReferenceWindow.clamp(ReferenceWindow.Frame(Float.NaN, Float.NaN, Float.NaN), 1.5f, 360f, 640f)
        assertTrue(f.x.isFinite() && f.y.isFinite() && f.w.isFinite())
        assertTrue(f.x >= 0f && f.x + f.w <= 360f)
        val g = ReferenceWindow.clamp(ReferenceWindow.Frame(10f, 10f, 200f), 1f, Float.NaN, Float.NaN)
        assertTrue(g.x.isFinite() && g.y.isFinite() && g.w.isFinite())
        // поворот: окно справа в альбомной ориентации остаётся на экране в портретной
        val land = ReferenceWindow.initial(1f, 800f, 360f)
        val port = ReferenceWindow.clamp(land, 1f, 360f, 800f)
        assertTrue(port.x + port.w <= 360f + 0.01f)
        val lh = ReferenceWindow.initial(1f, 360f, 800f, leftHanded = true)
        assertEquals(ReferenceWindow.MARGIN, lh.x, 0.01f)
    }
}
