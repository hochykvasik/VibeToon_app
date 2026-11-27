package com.vibetoon

import com.vibetoon.domain.export.WorkNotice
import com.vibetoon.domain.render.Camera
import com.vibetoon.domain.render.ViewRectFit
import com.vibetoon.domain.settings.SidePanelEdge
import com.vibetoon.domain.settings.UiMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 42: холст без сплющивания при движении панелей, правая панель и стрелка (включая левшу), уведомление экспорта. */
class Sprint42Test {
    private val eps = 0.02f

    // ---------------------------------------------------------------- 1. холст в видимой области поверхности

    /** Точка холста на поверхности = точка в области (жесты, наложения) + сдвиг области - при любом виде камеры. */
    @Test fun effectiveCameraDrawsCanvasExactlyInsideVisibleRect() {
        val cams = listOf(
            Camera(), Camera(zoom = 2.5f, panX = -40f, panY = 75f), Camera(zoom = 0.6f, panX = 13f, panY = -8f, rotDeg = 33f),
            Camera(zoom = 1.3f, panX = 5f, panY = 9f, rotDeg = -120f, flipX = true), Camera(zoom = 4f, rotDeg = 90f, flipY = true)
        )
        val rects = listOf(floatArrayOf(0f, 0f, 2560f, 1600f), floatArrayOf(72f, 96f, 2168f, 980f), floatArrayOf(380f, 50f, 1800f, 600f))
        for (c in cams) for (r in rects) for ((cw, ch) in listOf(1920 to 1080, 1080 to 1920, 800 to 800)) {
            val rect = ViewRectFit.rect(r[0], r[1], r[2], r[3], 2560f, 1600f)
            val eff = ViewRectFit.effective(c, rect, 2560, 1600, cw, ch)
            for ((px, py) in listOf(0f to 0f, cw.toFloat() to ch.toFloat(), cw * 0.3f to ch * 0.8f)) {
                val (vx, vy) = c.toScreen(px, py, r[2], r[3], cw, ch)
                val (sx, sy) = eff.toScreen(px, py, 2560f, 1600f, cw, ch)
                assertEquals(vx + r[0], sx, eps * 10)
                assertEquals(vy + r[1], sy, eps * 10)
            }
            assertEquals(c.rotDeg, eff.rotDeg, 0f); assertEquals(c.flipX, eff.flipX); assertEquals(c.flipY, eff.flipY)
        }
    }

    /** Смена высоты области (таймлайн выше/ниже) - только равномерный масштаб: пропорции холста не меняются. */
    @Test fun changingVisibleHeightKeepsCanvasProportions() {
        val cam = Camera(zoom = 1.7f, panX = 20f, panY = -30f)
        for (h in listOf(1500f, 1200f, 900f, 640f, 400f)) {
            val rect = ViewRectFit.rect(80f, 100f, 2200f, h, 2560f, 1600f)
            val eff = ViewRectFit.effective(cam, rect, 2560, 1600, 1920, 1080)
            val (x0, y0) = eff.toScreen(0f, 0f, 2560f, 1600f, 1920, 1080)
            val (x1, y1) = eff.toScreen(1920f, 1080f, 2560f, 1600f, 1920, 1080)
            assertEquals(1920f / 1080f, (x1 - x0) / (y1 - y0), 1e-3f)
        }
    }

    /** Касание слоя и пипетка: координаты области переводятся в пиксели буфера, в том числе при удержанном буфере. */
    @Test fun touchPointsMapIntoBuffer() {
        val rect = ViewRectFit.rect(100f, 50f, 1000f, 600f, 2000f, 1200f)
        assertEquals(150f to 150f, ViewRectFit.toBuffer(50f, 100f, rect, 2000, 1200))
        val (bx, by) = ViewRectFit.toBuffer(50f, 100f, rect, 1000, 600)   // буфер вдвое меньше вида
        assertEquals(75f, bx, eps); assertEquals(75f, by, eps)
        assertEquals(7f to 9f, ViewRectFit.toBuffer(7f, 9f, null, 2000, 1200))
    }

    @Test fun invalidRectKeepsLegacyCamera() {
        val c = Camera(zoom = 2f, panX = 3f)
        assertEquals(c, ViewRectFit.effective(c, null, 100, 100, 10, 10))
        assertEquals(c, ViewRectFit.effective(c, floatArrayOf(0f, 0f, 0f, 10f, 100f, 100f), 100, 100, 10, 10))
        assertEquals(c, ViewRectFit.effective(c, floatArrayOf(0f, 0f, Float.NaN, 10f, 100f, 100f), 100, 100, 10, 10))
        assertEquals(c, ViewRectFit.effective(c, ViewRectFit.rect(0f, 0f, 50f, 50f, 100f, 100f), 0, 100, 10, 10))
        assertEquals(c, ViewRectFit.effective(c, ViewRectFit.rect(0f, 0f, 50f, 50f, 100f, 100f), 100, 100, 0, 10))
        assertFalse(ViewRectFit.valid(floatArrayOf(1f, 2f, 3f)))
        // область = вся поверхность - камера не меняется
        val full = ViewRectFit.effective(c, ViewRectFit.rect(0f, 0f, 100f, 100f, 100f, 100f), 100, 100, 10, 10)
        assertEquals(c.zoom, full.zoom, 1e-5f); assertEquals(c.panX, full.panX, 1e-4f); assertEquals(c.panY, full.panY, 1e-4f)
    }

    @Test fun sameRectIgnoresSubpixelJitter() {
        val a = ViewRectFit.rect(0f, 0f, 100f, 100f, 200f, 200f)
        assertTrue(ViewRectFit.same(a, ViewRectFit.rect(0.1f, 0f, 100.2f, 100f, 200f, 200f)))
        assertFalse(ViewRectFit.same(a, ViewRectFit.rect(0f, 0f, 100f, 101f, 200f, 200f)))
        assertFalse(ViewRectFit.same(a, null)); assertTrue(ViewRectFit.same(null, null))
    }

    @Test fun canvasFitSpringIsCalm() {
        assertTrue(UiMotion.CANVAS_FIT_STIFFNESS in 200f..800f)   // ~0,2-0,4 с без отскока
    }

    // ---------------------------------------------------------------- 2. правая панель, стрелка, левша

    @Test fun edgeSwipeOpensTowardScreenCenterAndMirrorsForLeftHanded() {
        assertTrue(SidePanelEdge.atRightEdge(false)); assertFalse(SidePanelEdge.atRightEdge(true))
        assertTrue(SidePanelEdge.opens(-30f, 24f, leftHanded = false))
        assertFalse(SidePanelEdge.opens(-10f, 24f, leftHanded = false))
        assertFalse(SidePanelEdge.opens(40f, 24f, leftHanded = false))
        assertTrue(SidePanelEdge.opens(30f, 24f, leftHanded = true))
        assertFalse(SidePanelEdge.opens(-40f, 24f, leftHanded = true))
        assertTrue(SidePanelEdge.closes(30f, 24f, leftHanded = false))
        assertTrue(SidePanelEdge.closes(-30f, 24f, leftHanded = true))
        assertFalse(SidePanelEdge.opens(Float.NaN, 24f, false)); assertFalse(SidePanelEdge.opens(-30f, 0f, false))
    }

    @Test fun arrowDirectionMatchesPanelSide() {
        assertTrue(SidePanelEdge.arrowPointsLeft(open = false, leftHanded = false))    // «<» у правого края
        assertFalse(SidePanelEdge.arrowPointsLeft(open = true, leftHanded = false))    // «>» - закрыть вправо
        assertFalse(SidePanelEdge.arrowPointsLeft(open = false, leftHanded = true))    // левша: «>» у левого края
        assertTrue(SidePanelEdge.arrowPointsLeft(open = true, leftHanded = true))
    }

    @Test fun hintStaysSmallAndHiddenWhereNotNeeded() {
        assertTrue(SidePanelEdge.hintWidthDp(true) < SidePanelEdge.hintWidthDp(false))
        assertTrue(SidePanelEdge.hintWidthDp(false) <= 16 && SidePanelEdge.hintHeightDp(false) <= 48)
        assertTrue(SidePanelEdge.showClosedHint(open = false, fullscreen = false))
        assertFalse(SidePanelEdge.showClosedHint(open = false, fullscreen = true))
        assertFalse(SidePanelEdge.showClosedHint(open = true, fullscreen = false))
        assertTrue(SidePanelEdge.showOpenTab(phone = false, open = true, fullscreen = false))
        assertFalse(SidePanelEdge.showOpenTab(phone = true, open = true, fullscreen = false))   // телефон - без язычка
        assertFalse(SidePanelEdge.showOpenTab(phone = false, open = true, fullscreen = true))
    }

    @Test fun sidePanelMotionIsSofterThanGenericPanel() {
        assertTrue(UiMotion.SIDE_PANEL_MS > UiMotion.PANEL_MS)
        assertTrue(UiMotion.SIDE_PANEL_EXIT_MS > UiMotion.EXIT_MS)
        assertTrue(UiMotion.SIDE_PANEL_MS <= 400)
        assertTrue(UiMotion.EDGE_SWIPE_DP in 12f..40f)
    }

    // ---------------------------------------------------------------- 3. уведомление экспорта

    @Test fun noticeShowsAppNameStatusAndProgress() {
        val k = WorkNotice.kind("Экспорт")
        assertEquals(WorkNotice.Kind.EXPORT, k)
        assertEquals("VibeToon: экспорт 42 %", WorkNotice.title(k, 42))
        assertEquals("VibeToon: экспорт", WorkNotice.title(k, null))
        assertEquals("VibeToon: экспорт 100 %", WorkNotice.title(k, 140))
        assertEquals("Кадр 120 из 288", WorkNotice.status(k, 120, 288))
        assertEquals("Подготовка кадров", WorkNotice.status(k, 0, 1))
        assertEquals("Кадр 288 из 288", WorkNotice.status(k, 300, 288))
        val imp = WorkNotice.kind("Импорт")
        assertEquals("VibeToon: импорт", WorkNotice.title(imp, null))
        assertEquals("Подготовка медиа", WorkNotice.status(imp, 0, 0))
        assertEquals(WorkNotice.Kind.OTHER, WorkNotice.kind("что-то"))
        assertTrue(WorkNotice.title(WorkNotice.Kind.OTHER, null).startsWith("VibeToon"))
        assertNull(WorkNotice.more(1)); assertEquals("Задач: 2", WorkNotice.more(2))
        assertTrue(WorkNotice.TAP_HINT.contains("VibeToon"))
    }
}
