package com.vibetoon

import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.TimelineItemOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет 4: маркеры символа - единственный источник правды о формах. Сдвиг/зеркало маркера переносит форму
 * (клипы «Слоёв как кадров», покадровые рисунки), ключи времени экземпляров и ссылки на форму; формы не
 * накладываются (нет «двух ртов») и не теряются (рот не пропадает).
 */
class SymbolFormsTest {
    // Символ «рот»: слои 1 (Rest), 2 (O), 3 (E) - по одному кадру; маркеры на кадрах 0, 1, 2.
    private val markers = listOf(FrameMarker(0, "Rest"), FrameMarker(1, "O"), FrameMarker(2, "E"))
    private val clips = mapOf(1 to Clip(0, 1), 2 to Clip(1, 2), 3 to Clip(2, 3), 9 to Clip(0, 3))   // 9 - общая подложка
    private val members = setOf(1, 2, 3, 9)

    /** Какие участники видны на внутреннем кадре [f] (без записи клипа - всегда). */
    private fun visible(c: Map<Int, Clip>, f: Int) = members.filter { id -> c[id]?.visibleAt(f) ?: true }.toSet()

    @Test fun movingMarkerMovesItsFormAndKeys() {
        val next = TimelineItemOps.move(markers, setOf(1), 4, 10, TimelineItemOps.MARKERS)!!   // O: 1 -> 5
        val mv = SymbolForms.moves(markers, next, mapOf(1 to 5))
        assertEquals(mapOf(1 to 5), mv)
        val c = SymbolForms.moveClips(clips, members, markers, next, mv, 10)
        assertEquals(Clip(5, 6), c[2])                // форма «O» переехала вместе с маркером
        assertEquals(Clip(0, 3), c[9])                // подложка на несколько форм не двигается
        assertEquals(setOf(2), visible(c, 5) - 9)     // на кадре маркера видна ровно одна форма
        // Экземпляр, показывавший «O» с кадра 12, продолжает показывать «O» (ключ переехал на новый кадр формы)
        val keys = SymbolForms.remapKeys(listOf(TimeKey(0, 0), TimeKey(12, 1), TimeKey(20, 2)), mv)
        assertEquals(listOf(TimeKey(0, 0), TimeKey(12, 5), TimeKey(20, 2)), keys)
        assertEquals(5, SymbolMath.instanceFrame(keys, 15, 10, animated = false, loop = false))
    }

    @Test fun mirrorSwapsFormsWithoutOverlap() {
        val next = TimelineItemOps.mirror(markers, setOf(0, 1, 2), TimelineItemOps.MARKERS)
        val mv = SymbolForms.moves(markers, next, mapOf(0 to 2, 1 to 1, 2 to 0))
        val c = SymbolForms.moveClips(clips, members, markers, next, mv, 3)
        for (f in 0..2) assertEquals("ровно одна форма на кадре $f", 1, (visible(c, f) - 9).size)
        assertEquals(setOf(3), visible(c, 0) - 9)
        assertEquals(setOf(1), visible(c, 2) - 9)
    }

    @Test fun movedFormIsClampedByNextMarker() {
        // Длинная форма (2 кадра) переезжает в щель длиной 1: обрезается, чтобы не залезть на соседнюю форму.
        val m = listOf(FrameMarker(0, "A"), FrameMarker(4, "B"), FrameMarker(6, "C"))
        val cl = mapOf(1 to Clip(0, 3), 2 to Clip(4, 6), 3 to Clip(6, 7))
        val next = TimelineItemOps.move(m, setOf(0), 5, 10, TimelineItemOps.MARKERS)!!   // A: 0 -> 5
        val mv = SymbolForms.moves(m, next, mapOf(0 to 5))
        val c = SymbolForms.moveClips(cl, setOf(1, 2, 3), m, next, mv, 10)
        assertEquals(Clip(5, 6), c[1])
        assertFalse(c[1]!!.visibleAt(6))
    }

    @Test fun cellsFollowMarkersButNeverDrop() {
        val cells = listOf(FrameCell(0, "a.png"), FrameCell(1, "b.png"), FrameCell(2, "c.png"))
        assertEquals(listOf(FrameCell(0, "a.png"), FrameCell(2, "c.png"), FrameCell(5, "b.png")), SymbolForms.moveCells(cells, mapOf(1 to 5)))
        assertNull("цель занята неподвижным рисунком - отказ, рисунок не теряем", SymbolForms.moveCells(cells, mapOf(1 to 2)))
        assertEquals(cells, SymbolForms.moveCells(cells, emptyMap()))
    }

    @Test fun copiesAreAliasesOfTheSameForm() {
        val copied = TimelineItemOps.copy(markers, setOf(1), TimelineItemOps.MARKERS)
        val clip = SymbolForms.asAliases(copied, markers.filter { it.frame == 1 })
        val pasted = SymbolForms.normalize(TimelineItemOps.paste(markers, clip, 7, 10, TimelineItemOps.MARKERS)!!)
        val copy = pasted.first { it.frame == 7 }
        assertTrue(copy.isAlias)
        assertEquals(1, copy.target)                  // «Выбрать маркер» по копии показывает форму «O», а не пустой кадр 7
        assertEquals("O", SymbolForms.labelFor(pasted, 1)!!.name)
        // Форма-источник переехала - ссылка едет за ней
        val moved = SymbolForms.remapAliases(pasted, mapOf(1 to 4))
        assertEquals(4, moved.first { it.frame == 7 }.target)
        // Вставка на кадр самого источника - обычный маркер
        assertFalse(FrameMarker(1, "O", form = 1).normalized().isAlias)
    }

    @Test fun deletingMarkerKeepsFormsAndKeys() {
        val next = TimelineItemOps.delete(markers, setOf(1), TimelineItemOps.MARKERS)
        assertTrue(SymbolForms.moves(markers, next, emptyMap()).isEmpty())
        assertEquals(clips, SymbolForms.moveClips(clips, members, markers, next, emptyMap(), 3))
    }

    @Test fun pickedFormHoldsWithoutDrift() {
        // Ключи «Выбрать маркер» - Hold: между ключами форма не перелистывается сама.
        val keys = listOf(TimeKey(0, 0, TimeEase.HOLD), TimeKey(10, 2, TimeEase.HOLD))
        for (f in 0..9) assertEquals(0, SymbolMath.instanceFrame(keys, f, 3, animated = true, loop = false))
        for (f in 10..30) assertEquals(2, SymbolMath.instanceFrame(keys, f, 3, animated = true, loop = false))
        // Без ключей и без «Цикла» - всегда кадр 0
        for (f in 0..30) assertEquals(0, SymbolMath.instanceFrame(emptyList(), f, 3, animated = true, loop = false))
    }

    @Test fun durationCoversFormsAndAliases() {
        val m = listOf(FrameMarker(0, "A"), FrameMarker(3, "B", form = 0))
        assertEquals(7, SymbolForms.lastFrame(m, listOf(Clip(6, 8)), emptyList()))
        assertEquals(3, SymbolForms.lastFrame(m, emptyList(), emptyList()))
    }

    @Test fun markerNamesStayUnique() {
        val m = listOf(FrameMarker(0, "Обычный"), FrameMarker(1, "Обычный 2"), FrameMarker(4, "Обычный", form = 0))
        assertEquals("Обычный 3", SymbolForms.uniqueName(m, "Обычный", 2))
        assertEquals("Обычный", SymbolForms.uniqueName(m, "Обычный", 0))   // переименование самого себя
        assertEquals("О", SymbolForms.uniqueName(m, "О", 2))
    }

    @Test fun aliasSurvivesJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val def = MediaItem(100001, MediaKind.GROUP, "Символ 1", "", w = 10f, h = 10f, symDur = 3,
            markers = listOf(FrameMarker(0, "Rest"), FrameMarker(2, "Rest", form = 0), FrameMarker(1, "O", form = 1)))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(def)))).getOrThrow().media.first { it.id == 100001 }
        assertEquals(listOf(FrameMarker(0, "Rest"), FrameMarker(2, "Rest", form = 0), FrameMarker(1, "O")), back.markers)
    }
}
