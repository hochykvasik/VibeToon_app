package com.vibetoon

import com.vibetoon.domain.render.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** Вид редактора: поворот холста жестом, отражение, сброс в 0°. Формулы совпадают с C++ (render/ViewMath.h). */
class ViewCameraTest {
    private val vw = 1000f; private val vh = 800f; private val cw = 1920; private val ch = 1080

    private fun screen(c: Camera, x: Float, y: Float): Pair<Float, Float> = c.viewMatrix(vw, vh, cw, ch).let { it.applyX(x, y) to it.applyY(x, y) }
    private fun canvas(c: Camera, sx: Float, sy: Float): Pair<Float, Float> = c.viewMatrix(vw, vh, cw, ch).inverse()!!.let { it.applyX(sx, sy) to it.applyY(sx, sy) }

    @Test fun identityViewMatchesOldFormula() {
        // без поворота: экран = центр вида + (p - центр холста) * fit * zoom + pan (старая формула toScreenX/Y)
        val c = Camera(zoom = 1.7f, panX = 12f, panY = -30f)
        val fit = Camera.fitScale(vw, vh, cw, ch)
        val (sx, sy) = screen(c, 100f, 200f)
        assertEquals(vw / 2f + (100f - cw / 2f) * fit * 1.7f + 12f, sx, 1e-2f)
        assertEquals(vh / 2f + (200f - ch / 2f) * fit * 1.7f - 30f, sy, 1e-2f)
    }

    @Test fun rotationIsAroundCanvasCenterAndRoundTrips() {
        val c = Camera(zoom = 1.3f, panX = 40f, panY = 10f, rotation = 0.7f, flipX = true)
        // центр холста стоит в центре вида + pan при любом повороте
        val (cx, cy) = screen(c, cw / 2f, ch / 2f)
        assertEquals(vw / 2f + 40f, cx, 1e-2f); assertEquals(vh / 2f + 10f, cy, 1e-2f)
        val (x, y) = canvas(c, 123f, 456f)
        val (sx, sy) = screen(c, x, y)
        assertEquals(123f, sx, 1e-2f); assertEquals(456f, sy, 1e-2f)
    }

    @Test fun ninetyDegreesTurnsCanvasXAxisDown() {
        val c = Camera(rotation = (PI / 2).toFloat())
        val m = c.viewMatrix(vw, vh, cw, ch)
        assertEquals(0f, m.a, 1e-6f)   // точный 0 без «лесенки» по краям
        assertTrue(m.b > 0f)           // ось X холста смотрит вниз экрана: поворот по часовой
    }

    @Test fun gestureKeepsPointUnderFingers() {
        val c0 = Camera(zoom = 1.2f, panX = -20f, panY = 15f, rotation = 0.3f)
        val gx = 300f; val gy = 500f
        val p = canvas(c0, gx, gy)
        val c1 = c0.transformed(gx, gy, vw, vh, cw, ch, 1.5f, 0f, 0f, 25f)
        val (sx, sy) = screen(c1, p.first, p.second)
        assertEquals(gx, sx, 1e-2f); assertEquals(gy, sy, 1e-2f)
        assertEquals(0.3f + 25f * PI.toFloat() / 180f, c1.rotation, 1e-4f)
        assertEquals(1.8f, c1.zoom, 1e-4f)
        // сдвиг пальцев переносит ту же точку вместе с ними
        val c2 = c1.transformed(gx, gy, vw, vh, cw, ch, 1f, 30f, -10f, 0f)
        val (sx2, sy2) = screen(c2, p.first, p.second)
        assertEquals(gx + 30f, sx2, 1e-2f); assertEquals(gy - 10f, sy2, 1e-2f)
    }

    @Test fun resetToZeroKeepsCenterAndZoom() {
        val c0 = Camera(zoom = 2f, panX = 80f, panY = -40f, rotation = 1.1f)
        val center = canvas(c0, vw / 2f, vh / 2f)
        val c1 = c0.withRotation(0f, vw, vh, cw, ch)
        assertEquals(0f, c1.rotation, 1e-6f)
        assertEquals(2f, c1.zoom, 1e-6f)
        val (sx, sy) = screen(c1, center.first, center.second)
        assertEquals(vw / 2f, sx, 1e-2f); assertEquals(vh / 2f, sy, 1e-2f)
    }

    @Test fun flipMirrorsAroundViewCenter() {
        val c0 = Camera(zoom = 1.5f, panX = 33f)
        val center = canvas(c0, vw / 2f, vh / 2f)
        val c1 = c0.withFlip(true, false, vw, vh, cw, ch)
        assertTrue(c1.flipX); assertFalse(c1.flipY)
        val (sx, sy) = screen(c1, center.first, center.second)
        assertEquals(vw / 2f, sx, 1e-2f); assertEquals(vh / 2f, sy, 1e-2f)
        assertTrue(c1.viewMatrix(vw, vh, cw, ch).a < 0f)
        assertEquals(-1f, c1.handedness, 0f)
        assertEquals(1f, c1.copy(flipY = true).handedness, 0f)
    }

    @Test fun zoomIsClampedAndAnglesNormalized() {
        assertEquals(Camera.MAX_ZOOM, Camera(zoom = 19f).transformed(0f, 0f, vw, vh, cw, ch, 5f, 0f, 0f).zoom, 1e-6f)
        assertEquals(Camera.MIN_ZOOM, Camera(zoom = 0.3f).transformed(0f, 0f, vw, vh, cw, ch, 0.1f, 0f, 0f).zoom, 1e-6f)
        assertEquals(-170f, Camera.normalizeDeg(190f), 1e-4f)
        assertEquals(180f, Camera.normalizeDeg(-180f), 1e-4f)
        assertEquals(90f, Camera.snapDeg(87f), 1e-4f)
        assertEquals(80f, Camera.snapDeg(80f), 1e-4f)
        assertEquals(0f, Camera.snapDeg(-3.5f), 1e-4f)
    }

    @Test fun legacyTransformStillWorks() {
        val c = Camera().transformed(500f, 400f, 500f, 400f, 2f, 10f, 0f)
        assertEquals(2f, c.zoom, 1e-6f); assertEquals(10f, c.panX, 1e-4f)
    }
}
