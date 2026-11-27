package com.vibetoon

import com.vibetoon.domain.model.LayerTree
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.ui.EditorLayout
import com.vibetoon.domain.ui.ReferenceWindow
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 60: раскладка редактора по реальному размеру окна и ориентации (телефон / планшет, портрет / ландшафт,
 * режим левши, поворот), сокращение подписей, окно референса, защита деревьев слоёв от повторных id.
 */
class Sprint60LayoutTest {
    // типичные окна в dp
    private val phonePortrait = 411f to 891f      // Pixel 7
    private val phoneLandscape = 891f to 411f
    private val smallPhonePortrait = 360f to 640f
    private val tabletLandscape = 1280f to 800f
    private val tabletPortrait = 800f to 1280f

    private fun spec(wh: Pair<Float, Float>, left: Boolean = false) = EditorLayout.of(wh.first, wh.second, left)

    @Test fun phoneIsDecidedByShortSideNotWidth() {
        // раньше ландшафтный телефон 891 dp (> 800) считался планшетом
        assertTrue(spec(phoneLandscape).phone)
        assertTrue(spec(phonePortrait).phone)
        assertFalse(spec(tabletLandscape).phone)
        assertFalse(spec(tabletPortrait).phone)
        // поворот не меняет класс окна: панели не закрываются и не перестраиваются из-за смены «телефон/планшет»
        assertEquals(spec(phonePortrait).phone, spec(phoneLandscape).phone)
        assertEquals(spec(tabletPortrait).phone, spec(tabletLandscape).phone)
    }

    @Test fun portraitPhoneGetsToolStripUnderCanvasAndCompactHeader() {
        val p = spec(phonePortrait)
        assertTrue(p.portrait && p.toolbarBottom && p.compactHeader && p.sidePanelOverlay)
        assertFalse("в портрете хватает высоты для отдельной строки режимов", p.modesInTopBar)
        val l = spec(phoneLandscape)
        assertFalse(l.portrait); assertFalse("в ландшафте инструменты колонкой у края", l.toolbarBottom)
        assertTrue("низкий ландшафт: режимы в верхней строке", l.modesInTopBar)
        // планшет в портрете - прежняя раскладка с колонкой инструментов
        assertFalse(spec(tabletPortrait).toolbarBottom)
        assertFalse(spec(tabletLandscape).compactHeader)
    }

    @Test fun landscapePhoneSidePanelNeverCoversWholeCanvas() {
        for (wh in listOf(phoneLandscape, 640f to 360f, 740f to 360f, 960f to 540f)) {
            val s = spec(wh)
            assertTrue("$wh: панель ${s.sidePanelDp}", s.sidePanelDp <= wh.first * 0.42f + 0.01f)
            assertTrue(s.sidePanelOverlay)
        }
        // портрет: панель выезжает шире, но остаётся полоска холста для закрытия тапом
        val p = spec(smallPhonePortrait)
        assertTrue(p.sidePanelDp < smallPhonePortrait.first)
        assertEquals(320f, spec(tabletLandscape).sidePanelDp, 0.01f)
    }

    @Test fun canvasKeepsAtLeastThirtyPercentHeight() {
        for (wh in listOf(phonePortrait, phoneLandscape, smallPhonePortrait, tabletLandscape, tabletPortrait, 640f to 330f, 900f to 300f)) {
            val s = spec(wh)
            val h = wh.second
            // даже при перетаскивании хвата до упора вверх
            val tl = EditorLayout.timelineHeight(s, h, 99999f)
            val chrome = EditorLayout.TOP_BAR_DP + (if (s.modesInTopBar) 0f else EditorLayout.MODE_BAR_DP) +
                (if (s.toolbarBottom) EditorLayout.TOOL_STRIP_DP else 0f) + EditorLayout.TIMELINE_GRIP_DP
            val canvas = h - chrome - tl
            val minTl = minOf(s.timelineMinDp, h) // на совсем низком окне таймлайн хотя бы минимальный
            assertTrue("$wh: холст $canvas при таймлайне $tl", canvas >= h * EditorLayout.MIN_CANVAS_FRACTION - 0.5f || tl <= minTl + 0.5f)
            // по умолчанию - доля окна в пределах
            val def = EditorLayout.timelineHeight(s, h, 0f)
            assertTrue(def in s.timelineMinDp..maxOf(s.timelineMinDp, s.timelineMaxDp))
        }
    }

    @Test fun timelineUserHeightIsClampedAfterRotation() {
        // пользователь растянул таймлайн в портрете до 300 dp, повернул в ландшафт (высота 411): таймлайн ужимается
        val portrait = spec(phonePortrait)
        val tall = EditorLayout.timelineHeight(portrait, phonePortrait.second, 300f)
        assertEquals(300f, tall, 0.01f)
        val land = spec(phoneLandscape)
        val after = EditorLayout.timelineHeight(land, phoneLandscape.second, 300f)
        assertTrue("после поворота $after", after <= land.timelineMaxDp + 0.01f && after < 300f)
        val r = EditorLayout.timelineDragRange(land)
        assertTrue(after in r)
        // мусор не роняет раскладку
        assertTrue(EditorLayout.timelineHeight(land, Float.NaN, Float.POSITIVE_INFINITY).isFinite())
        val zero = EditorLayout.of(0f, 0f, false)
        assertTrue(EditorLayout.timelineHeight(zero, 0f, 0f) >= 0f)
        val nan = EditorLayout.of(Float.NaN, -5f, true)
        assertTrue(nan.sidePanelDp >= 0f && nan.timelineMaxDp >= 0f)
    }

    @Test fun leftHandedMirrorsToolsInBothOrientations() {
        for (wh in listOf(phonePortrait, phoneLandscape, tabletLandscape)) {
            val r = spec(wh, left = false); val l = spec(wh, left = true)
            assertFalse(r.toolsAtEnd); assertTrue(l.toolsAtEnd)
            assertTrue(l.indicatorAtStart)
            // остальное от режима левши не зависит
            assertEquals(r.copy(toolsAtEnd = true, indicatorAtStart = true), l)
        }
        val items = listOf("Слои", "Кисть", "Ластик", "Заливка")
        assertEquals(items, EditorLayout.stripOrder(items, spec(phonePortrait)))
        assertEquals(items.reversed(), EditorLayout.stripOrder(items, spec(phonePortrait, left = true)))
    }

    @Test fun longLabelsShortenOnWordBoundary() {
        assertEquals("Кости: точечный…", EditorLayout.shortLabel("Кости: точечный выбор", 18))
        assertEquals("Кисть", EditorLayout.shortLabel("Кисть", 18))
        assertEquals("Кисть", EditorLayout.shortLabel("  Кисть  ", 18))
        // одно длинное слово режется по символам, длина с многоточием не больше предела
        val long = EditorLayout.shortLabel("Суперкалифраджилистик", 10)
        assertEquals(10, long.length); assertTrue(long.endsWith("…"))
        assertEquals("…", EditorLayout.shortLabel("Длинно", 1))
        assertEquals("", EditorLayout.shortLabel("", 0))
        for (n in 2..30) assertTrue(EditorLayout.shortLabel("Кости: добавить кость к выбранной точке", n).length <= n)
        // хвостовые знаки препинания не остаются перед многоточием
        assertEquals("Кости…", EditorLayout.shortLabel("Кости: двигать кости", 8))
        assertTrue(spec(phonePortrait).labelMaxChars < spec(tabletLandscape).labelMaxChars)
    }

    @Test fun referenceWindowStartsAwayFromToolsAndBelowModes() {
        val right = ReferenceWindow.initial(1f, 891f, 411f)
        val left = ReferenceWindow.initial(1f, 891f, 411f, leftHanded = true)
        val (w, _) = ReferenceWindow.size(right, 1f, 891f, 411f)
        // правша: инструменты слева - окно справа; левша: инструменты справа - окно слева
        assertEquals(891f - w - ReferenceWindow.MARGIN, right.x, 0.01f)
        assertEquals(ReferenceWindow.MARGIN, left.x, 0.01f)
        // не закрывает верхнюю строку и строку режимов
        assertTrue(right.y >= EditorLayout.TOP_BAR_DP + EditorLayout.MODE_BAR_DP)
        // портрет телефона: целиком внутри и не шире 60 %
        val p = ReferenceWindow.initial(0.7f, 360f, 640f, leftHanded = true)
        val (pw, ph) = ReferenceWindow.size(p, 0.7f, 360f, 640f)
        assertTrue(p.x >= 0f && p.x + pw <= 360f && p.y + ph <= 640f)
        // поворот: сохранённая в ландшафте рамка вписывается в портрет
        val moved = ReferenceWindow.clamp(right.copy(x = 700f), 1f, 411f, 891f)
        val (mw, _) = ReferenceWindow.size(moved, 1f, 411f, 891f)
        assertTrue(moved.x + mw <= 411f)
    }

    private fun li(id: Int, parent: Int, group: Boolean = false) =
        LayerInfo(id, "n$id", 0, 0, 10, 10, 1f, true, if (group) 1 else 0, parent, if (group) "pass" else "norm", false)

    @Test fun duplicateGroupIdsDoNotRecurseForever() {
        // группа 5 в корне и ещё одна запись-группа с тем же id внутри неё - раньше бесконечная рекурсия
        val layers = listOf(li(1, 5), li(5, 5, group = true), li(5, -1, group = true), li(2, -1))
        val rows = LayerTree.flatten(layers, emptySet())
        assertTrue(rows.size in 3..6)
        val tree = SceneTree.build(layers)
        assertEquals(2, tree.size)
        // обычное дерево не изменилось
        val ok = listOf(li(1, 3), li(2, 3), li(3, -1, group = true), li(4, -1))
        assertEquals(listOf(4, 3, 2, 1), LayerTree.flatten(ok, emptySet()).map { it.layer.id })
        assertEquals(listOf(3, 4), SceneTree.build(ok).map { it.id })
    }
}
