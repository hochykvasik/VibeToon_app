package com.vibetoon

import com.vibetoon.domain.project.FormRun
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 3: ключи времени символа с переходами Hold / Linear / Smooth, полосы одинаковых форм, стабильный кадр без ключей. */
class SymbolTimeEaseTest {
    @Test fun singleKeyHoldsFormForever() {
        val keys = listOf(TimeKey(10, 4))
        for (f in listOf(0, 9, 10, 11, 500)) assertEquals(4, SymbolMath.timeAt(keys, f))
        assertEquals(TimeEase.HOLD, keys[0].ease)   // по умолчанию маркер ставит Hold
    }

    @Test fun holdSwitchesInstantly() {
        val keys = listOf(TimeKey(0, 0), TimeKey(10, 8))
        assertEquals(0, SymbolMath.timeAt(keys, 9))
        assertEquals(8, SymbolMath.timeAt(keys, 10))
    }

    @Test fun linearAndSmoothPlayInnerFramesBetweenKeys() {
        val lin = listOf(TimeKey(0, 0, TimeEase.LINEAR), TimeKey(10, 10))
        assertEquals(0, SymbolMath.timeAt(lin, 0))
        assertEquals(5, SymbolMath.timeAt(lin, 5))
        assertEquals(10, SymbolMath.timeAt(lin, 10))
        val smooth = listOf(TimeKey(0, 0, TimeEase.SMOOTH), TimeKey(10, 10))
        assertEquals(5, SymbolMath.timeAt(smooth, 5))      // середина S-кривой
        assertTrue(SymbolMath.timeAt(smooth, 2)!! < 2)     // медленный старт
        assertTrue(SymbolMath.timeAt(smooth, 8)!! > 8)     // медленный финиш
        val back = listOf(TimeKey(0, 6, TimeEase.LINEAR), TimeKey(6, 0))
        assertEquals(3, SymbolMath.timeAt(back, 3))        // переход назад по кадрам символа
    }

    @Test fun invalidEaseFallsBackToHold() {
        assertEquals(0, SymbolMath.timeAt(listOf(TimeKey(0, 0, 99), TimeKey(10, 10)), 5))
    }

    @Test fun formRunsMergeRepeatsAndSplitTransitions() {
        val keys = listOf(TimeKey(0, 2), TimeKey(5, 2), TimeKey(10, 3, TimeEase.LINEAR), TimeKey(20, 7))
        val runs = SymbolMath.formRuns(keys, 30)
        assertEquals(listOf(FormRun(0, 10, 2, true), FormRun(10, 20, 3, false, 7), FormRun(20, 30, 7, true)), runs)
        // первый отрезок начинается с 0: до первого ключа символ уже показывает его форму
        assertEquals(0, SymbolMath.formRuns(listOf(TimeKey(4, 1)), 10).single().start)
        assertTrue(SymbolMath.formRuns(emptyList(), 10).isEmpty())
        // переход к той же форме - всё равно одна полоса
        assertEquals(1, SymbolMath.formRuns(listOf(TimeKey(0, 1, TimeEase.SMOOTH), TimeKey(8, 1)), 12).size)
    }

    @Test fun noKeysHoldsFirstFrameUnlessLoopEnabled() {
        for (f in listOf(0, 3, 17, 99)) assertEquals(0, SymbolMath.instanceFrame(emptyList(), f, 12, animated = true, loop = false))
        assertEquals(5, SymbolMath.instanceFrame(emptyList(), 17, 12, animated = true, loop = true))
        assertEquals(0, SymbolMath.instanceFrame(emptyList(), 17, 12, animated = false, loop = true))
        // ключ за пределами длительности символа обрезается к последнему кадру
        assertEquals(11, SymbolMath.instanceFrame(listOf(TimeKey(0, 40)), 3, 12, animated = true, loop = false))
    }

    @Test fun easeAndLoopSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val inst = MediaItem(100001, MediaKind.SYMBOL, "S", "", w = 10f, h = 10f, symRef = 99999, symLoop = true,
            timeKeys = listOf(TimeKey(0, 0, TimeEase.SMOOTH), TimeKey(6, 3)))
        val plain = MediaItem(100002, MediaKind.SYMBOL, "S2", "", w = 10f, h = 10f, symRef = 99999)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(inst, plain)))).getOrThrow().media
        val a = back.first { it.id == 100001 }
        assertEquals(listOf(TimeKey(0, 0, TimeEase.SMOOTH), TimeKey(6, 3, TimeEase.HOLD)), a.timeKeys)
        assertTrue(a.symLoop)
        assertFalse(back.first { it.id == 100002 }.symLoop)
    }
}
