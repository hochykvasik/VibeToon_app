package com.vibetoon

import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.SymbolReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 48: первая форма символа (маркер кадра 0 - закрытый рот) после выхода из проекта и повторного открытия.
 * Сценарий: символ «рот» из слоёв PSD, перенесённых в группу символа, маркеры 0/1/9. При открытии проект публикуется на
 * холст раньше, чем разбирается PSD - снимать формы в этот момент нельзя.
 */
class Sprint48Test {
    private val def = 30
    // слои PSD 11, 12, 13 перенесены в символ 30; 14 - в подгруппу 31 символа; 15 - удалён; 40 - вне символа
    private val regroup = listOf(11 to def, 12 to def, 13 to def, 31 to def, 14 to 31, 15 to def, 41 to 40)
    private val deleted = setOf(15)
    private val markers = listOf(FrameMarker(0, "closed"), FrameMarker(1, "open"), FrameMarker(9, "o"))

    @Test fun reopenBeforePsdParsedIsNotReady() {
        // повторное открытие: в списке слоёв только группы и медиа, слоёв PSD ещё нет
        val beforePsd = setOf(def, 31, 40)
        val missing = SymbolReadiness.missingMembers(def, regroup, beforePsd, deleted)
        assertEquals(setOf(11, 12, 13, 14), missing)
        assertFalse(SymbolReadiness.canSnapshot(psdPending = true, missing = missing))
        assertFalse(SymbolReadiness.canSnapshot(psdPending = false, missing = missing))
        // PSD разобран, слои в списке - форму кадра 0 можно снимать
        val afterPsd = beforePsd + setOf(11, 12, 13, 14, 41)
        val none = SymbolReadiness.missingMembers(def, regroup, afterPsd, deleted)
        assertTrue(none.isEmpty())
        assertTrue(SymbolReadiness.canSnapshot(psdPending = false, missing = none))
        assertFalse("PSD ещё разбирается", SymbolReadiness.canSnapshot(psdPending = true, missing = none))
    }

    @Test fun deletedAndStaleMembersDoNotBlockForever() {
        val present = setOf(def, 31, 11, 12, 13, 14)
        assertTrue(SymbolReadiness.missingMembers(def, regroup, present, deleted).isEmpty())
        // устаревшая запись regroup об удалённом медиа-слое 777 - не блокирует снимок
        val stale = regroup + (777 to def)
        assertEquals(setOf(777), SymbolReadiness.missingMembers(def, stale, present, deleted))
        assertTrue(SymbolReadiness.missingMembers(def, stale, present, deleted, known = present + setOf(40, 41)).isEmpty())
        // символ без участников в regroup и циклы - без зависания
        assertTrue(SymbolReadiness.missingMembers(5, regroup, present, deleted).isEmpty())
        assertTrue(SymbolReadiness.missingMembers(1, listOf(2 to 1, 1 to 2, 3 to 3), setOf(1, 2), emptySet()).isEmpty())
        assertTrue(SymbolReadiness.missingMembers(def, emptyList(), emptySet(), emptySet()).isEmpty())
    }

    @Test fun blankMarkerFormIsRetaken() {
        // пустой снимок на кадре маркера - временный (переснимется); пустой кадр без маркера - нормален
        assertTrue(SymbolReadiness.suspiciousBlank(blank = true, local = 0, markers = markers))
        assertTrue(SymbolReadiness.suspiciousBlank(blank = true, local = 9, markers = markers))
        assertFalse(SymbolReadiness.suspiciousBlank(blank = true, local = 5, markers = markers))
        assertFalse(SymbolReadiness.suspiciousBlank(blank = false, local = 0, markers = markers))
        // маркер-ссылка показывает форму другого кадра: проверяется её цель
        val alias = listOf(FrameMarker(0, "closed"), FrameMarker(20, "closed again", form = 0))
        assertTrue(SymbolReadiness.suspiciousBlank(true, 0, alias))
        assertFalse(SymbolReadiness.suspiciousBlank(true, 20, alias))
    }

    @Test fun waitingDuringLoadDoesNotCount() {
        assertTrue(SymbolReadiness.waitCounts(0L, opening = false, psdPending = false))
        assertFalse(SymbolReadiness.waitCounts(1024L, opening = false, psdPending = false))
        assertFalse(SymbolReadiness.waitCounts(0L, opening = true, psdPending = false))
        // проект уже опубликован (_opening=false), но PSD ещё разбирается - ожидание не в счёт лимита
        assertFalse(SymbolReadiness.waitCounts(0L, opening = false, psdPending = true))
    }

    @Test fun firstMarkerStaysBoundToFrameZeroAfterReload() {
        // «слои как кадры»: первый маркер - кадр 0 (нумерация с нуля), его форма показывается на кадре 0 экземпляра
        val plan = SymbolMath.framesPlan(listOf(11, 12, 13), listOf("closed", "open", "o"), step = 3)
        assertEquals(listOf(0, 3, 6), plan.markers.map { it.frame })
        assertEquals(Triple(11, 0, 3), plan.clips.first())   // слой первой формы виден с кадра 0
        val shown = SymbolForms.shownMarker(markers, 0)
        assertEquals(0, shown?.frame)
        assertEquals("closed", shown?.name)
        assertEquals(0, shown?.target)
    }

    @Test fun emptySnapshotHasNoOpaqueBounds() {
        // прозрачный снимок (участники ещё не загружены) распознаётся как пустой; рисунок - нет
        val w = 8; val h = 8
        val px = IntArray(w * h)
        assertNull(SymbolForms.opaqueBounds(px, w, h))
        px[3 * w + 4] = 0xFF000000.toInt()
        assertTrue(SymbolForms.opaqueBounds(px, w, h) != null)
    }
}
