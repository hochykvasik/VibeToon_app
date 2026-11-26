package com.vibetoon

import com.vibetoon.domain.export.EditorLeave
import com.vibetoon.domain.export.ExportInflight
import com.vibetoon.domain.export.ExportInterrupted
import com.vibetoon.domain.export.ExportOutcome
import com.vibetoon.domain.export.ExportOutcome.Kind as K
import com.vibetoon.domain.export.ExportOutcome.Ui as U
import com.vibetoon.domain.export.ExportStall
import com.vibetoon.domain.ui.ReferenceWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 56: итог фонового экспорта (одно состояние для шторки и приложения), «ждёт» вместо таймера, окно референса. */
class Sprint56Test {
    @Test fun everyRunEndsWithExactlyOneOutcome() {
        assertNull(ExportOutcome.classify(false, U.DONE, false, false))          // экспорта не было
        assertNull(ExportOutcome.classify(true, U.RUNNING, false, false))        // ещё идёт
        assertEquals(K.DONE, ExportOutcome.classify(true, U.DONE, false, true))  // поздняя отмена не портит готовый файл
        assertEquals(K.FAILED, ExportOutcome.classify(true, U.FAILED, false, false))
        assertEquals(K.INTERRUPTED, ExportOutcome.classify(true, U.FAILED, true, false))
        assertEquals(K.CANCELLED, ExportOutcome.classify(true, U.IDLE, false, true))
        // вернулись в «нет экспорта» без отмены - работу оборвали (экран закрыт), это не молчаливый конец
        assertEquals(K.INTERRUPTED, ExportOutcome.classify(true, U.IDLE, false, false))
    }

    @Test fun outcomeTextsAreClearAndShadeOnlyWhenHidden() {
        assertEquals("VibeToon: экспорт завершён", ExportOutcome.title(K.DONE))
        assertEquals("VibeToon: экспорт прерван", ExportOutcome.title(K.INTERRUPTED))
        assertEquals("VibeToon: экспорт не удался", ExportOutcome.title(K.FAILED))
        assertEquals("VibeToon: экспорт отменён", ExportOutcome.title(K.CANCELLED))
        assertEquals("Сохранено: clip.mp4", ExportOutcome.text(K.DONE, "clip.mp4"))
        assertEquals("Файл сохранён в папку проекта", ExportOutcome.text(K.DONE, "  "))
        assertEquals(ExportOutcome.REASON_APP_CLOSED, ExportOutcome.text(K.INTERRUPTED, null))
        assertEquals("Нет кодека", ExportOutcome.text(K.FAILED, "Нет кодека"))
        assertTrue(ExportOutcome.postToShade(appVisible = false))
        assertFalse(ExportOutcome.postToShade(appVisible = true))   // на экране итог виден в окне экспорта
        assertTrue(ExportOutcome.timeoutMs(K.CANCELLED) > 0)          // подтверждение отмены исчезает само
        assertEquals(0L, ExportOutcome.timeoutMs(K.DONE))
        assertTrue(ExportOutcome.needsInAppNotice(K.INTERRUPTED, editorAlive = false))
        assertFalse(ExportOutcome.needsInAppNotice(K.INTERRUPTED, editorAlive = true))
        assertFalse(ExportOutcome.needsInAppNotice(K.DONE, editorAlive = false))
        assertEquals("x", ExportInterrupted("x").message)
    }

    @Test fun stalledExportSaysWaitingInsteadOfTicking() {
        val t0 = 1_000_000L
        assertFalse(ExportStall.stalled(appVisible = true, parked = true, lastProgressAt = t0, now = t0 + 999_999))
        assertTrue(ExportStall.stalled(appVisible = false, parked = true, lastProgressAt = t0, now = t0))
        assertFalse(ExportStall.stalled(false, false, t0, t0 + ExportStall.STALL_MS - 1))
        assertTrue(ExportStall.stalled(false, false, t0, t0 + ExportStall.STALL_MS))
        assertEquals("VibeToon: экспорт ждёт 21 %", ExportStall.title(21))
        assertEquals("VibeToon: экспорт ждёт 100 %", ExportStall.title(140))
        assertEquals("VibeToon: экспорт ждёт", ExportStall.title(null))
        assertTrue(ExportStall.CHECK_MS < ExportStall.STALL_MS)
    }

    @Test fun inflightMarkerSurvivesRestartOnlyWhenExportIsGone() {
        val info = ExportInflight.Info("p1", "Сцена\t1\nа", 42L)
        val enc = ExportInflight.encode(info)
        val dec = ExportInflight.decode(enc)!!
        assertEquals("p1", dec.projectId); assertEquals("Сцена 1 а", dec.projectName); assertEquals(42L, dec.startedAt)
        assertNull(ExportInflight.decode(null)); assertNull(ExportInflight.decode("")); assertNull(ExportInflight.decode("p\tn"))
        assertNull(ExportInflight.decode("\tn\t1")); assertNull(ExportInflight.decode("p\tn\tx"))
        assertNull(ExportInflight.staleOnStart(enc, exportRunningNow = true))
        assertEquals("p1", ExportInflight.staleOnStart(enc, exportRunningNow = false)?.projectId)
    }

    @Test fun editorCannotBeLeftMidExportAndFormsStayDuringExport() {
        assertFalse(EditorLeave.allowed(exportRunning = true))
        assertTrue(EditorLeave.allowed(exportRunning = false))
        assertFalse(EditorLeave.trimFormsAllowed(exportRunning = true))
        assertTrue(EditorLeave.trimFormsAllowed(exportRunning = false))
    }

    @Test fun referenceWindowStartsTopRightInsideArea() {
        val f = ReferenceWindow.initial(1.5f, 1280f, 800f)
        val (w, h) = ReferenceWindow.size(f, 1.5f, 1280f, 800f)
        assertEquals(ReferenceWindow.DEFAULT_W, w, 0.01f)
        assertEquals(ReferenceWindow.BAR + w / 1.5f, h, 0.01f)
        assertTrue(f.x + w <= 1280f && f.x > 1280f / 2)
        assertTrue(f.y >= 0f && f.y + h <= 800f)
        // узкая область: окно не шире MAX_FRACTION, но не уже MIN_W
        val small = ReferenceWindow.initial(1f, 300f, 400f)
        assertEquals(ReferenceWindow.MIN_W.coerceAtLeast(300f * ReferenceWindow.MAX_FRACTION), small.w, 0.01f)
    }

    @Test fun referenceWindowMovesAndStaysInside() {
        val f = ReferenceWindow.Frame(100f, 100f, 200f)
        val m = ReferenceWindow.moveBy(f, 30f, -20f, 2f, 1000f, 700f)
        assertEquals(130f, m.x, 0.01f); assertEquals(80f, m.y, 0.01f)
        val far = ReferenceWindow.moveBy(f, 5000f, 5000f, 2f, 1000f, 700f)
        val (w, h) = ReferenceWindow.size(far, 2f, 1000f, 700f)
        assertEquals(1000f - w, far.x, 0.01f); assertEquals(700f - h, far.y, 0.01f)
        val neg = ReferenceWindow.moveBy(f, -5000f, -5000f, 2f, 1000f, 700f)
        assertEquals(0f, neg.x, 0.01f); assertEquals(0f, neg.y, 0.01f)
    }

    @Test fun referenceWindowResizesKeepingAspectAndLimits() {
        val f = ReferenceWindow.Frame(100f, 50f, 200f)
        val r = ReferenceWindow.resizeBy(f, 100f, 1f, 1000f, 700f)
        assertEquals(300f, r.w, 0.01f)
        val (_, h) = ReferenceWindow.size(r, 1f, 1000f, 700f)
        assertEquals(ReferenceWindow.BAR + 300f, h, 0.01f)
        assertEquals(ReferenceWindow.MIN_W, ReferenceWindow.resizeBy(f, -1000f, 1f, 1000f, 700f).w, 0.01f)
        assertEquals(1000f * ReferenceWindow.MAX_FRACTION, ReferenceWindow.resizeBy(f, 5000f, 1f, 1000f, 700f).w, 0.01f)
        // у правого края окно растёт только до края области
        val edge = ReferenceWindow.Frame(800f, 0f, 150f)
        assertEquals(200f, ReferenceWindow.resizeBy(edge, 500f, 1f, 1000f, 700f).w, 0.01f)
        // высокая картинка не вылезает по высоте
        val tall = ReferenceWindow.size(ReferenceWindow.Frame(0f, 0f, 500f), 0.1f, 1000f, 400f)
        assertTrue(tall.second <= 400f)
    }

    @Test fun referenceWindowCollapsesToBarAndBack() {
        val f = ReferenceWindow.Frame(100f, 600f, 300f)
        val c = ReferenceWindow.toggleCollapsed(f, 1f, 1000f, 700f)
        assertTrue(c.collapsed)
        assertEquals(ReferenceWindow.BAR, ReferenceWindow.size(c, 1f, 1000f, 700f).second, 0.01f)
        assertEquals(c, ReferenceWindow.resizeBy(c, 50f, 1f, 1000f, 700f))   // свёрнутое окно размер не меняет
        val e = ReferenceWindow.toggleCollapsed(c, 1f, 1000f, 700f)
        assertFalse(e.collapsed)
        val (_, h) = ReferenceWindow.size(e, 1f, 1000f, 700f)
        assertTrue(e.y + h <= 700f)   // развернулось внутри области (подвинулось вверх)
        // плохие пропорции не ломают геометрию
        assertTrue(ReferenceWindow.size(f, Float.NaN, 1000f, 700f).second.isFinite())
        assertTrue(ReferenceWindow.size(f, 0f, 1000f, 700f).second.isFinite())
    }

    @Test fun referenceImageIsDownsampledForMemory() {
        assertEquals(1, ReferenceWindow.sampleSize(800, 600))
        assertEquals(1, ReferenceWindow.sampleSize(1024, 1024))
        assertEquals(2, ReferenceWindow.sampleSize(2048, 1000))
        assertEquals(4, ReferenceWindow.sampleSize(4096, 3000))
        assertEquals(2, ReferenceWindow.sampleSize(3000, 2000))
        assertEquals(1, ReferenceWindow.sampleSize(0, 100))
        assertEquals(1, ReferenceWindow.sampleSize(-1, -1))
    }
}
