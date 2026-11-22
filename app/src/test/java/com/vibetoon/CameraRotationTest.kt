package com.vibetoon

import com.vibetoon.domain.render.Camera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CameraRotationTest {
    private val vw = 1000f; private val vh = 800f; private val cw = 500; private val ch = 400

    @Test fun identityCameraMapsCenterToCenter() {
        val c = Camera()
        val (sx, sy) = c.toScreen(cw / 2f, ch / 2f, vw, vh, cw, ch)
        assertEquals(vw / 2f, sx, 1e-3f); assertEquals(vh / 2f, sy, 1e-3f)
    }

    @Test fun screenAndCanvasAreInverse() {
        val c = Camera(zoom = 1.7f, panX = 33f, panY = -21f, rotDeg = 37f, flipX = true, flipY = false)
        for ((x, y) in listOf(0f to 0f, 250f to 200f, 499f to 399f, 123f to 321f)) {
            val (sx, sy) = c.toScreen(x, y, vw, vh, cw, ch)
            val (bx, by) = c.toCanvas(sx, sy, vw, vh, cw, ch)
            assertEquals(x, bx, 1e-2f); assertEquals(y, by, 1e-2f)
        }
    }

    @Test fun flipMirrorsAroundCenter() {
        val c = Camera(flipX = true)
        val (sx, _) = c.toScreen(0f, ch / 2f, vw, vh, cw, ch)
        val (sx2, _) = c.toScreen(cw.toFloat(), ch / 2f, vw, vh, cw, ch)
        assertTrue(sx > vw / 2f); assertTrue(sx2 < vw / 2f)
        assertEquals(vw, sx + sx2, 1e-2f)
    }

    @Test fun rotation90SwapsAxes() {
        val c = Camera(rotDeg = 90f)
        val (sx, sy) = c.toScreen(cw.toFloat(), ch / 2f, vw, vh, cw, ch)
        // точка справа от центра уходит вниз (экранная Y вниз)
        assertEquals(vw / 2f, sx, 1e-2f); assertTrue(sy > vh / 2f)
    }

    @Test fun snappedRotSnapsNearCardinals() {
        assertEquals(0f, Camera(rotDeg = 3f).snappedRot(), 1e-3f)
        assertEquals(90f, Camera(rotDeg = 87f).snappedRot(), 1e-3f)
        assertEquals(180f, Camera(rotDeg = -178f).snappedRot(), 1e-3f)
        assertEquals(45f, Camera(rotDeg = 45f).snappedRot(), 1e-3f)
        assertEquals(270f, Camera(rotDeg = 272f).snappedRot(), 1e-3f)
    }

    @Test fun transformedKeepsGesturePointFixed() {
        val c = Camera(zoom = 1.2f, panX = 10f, panY = 5f, rotDeg = 15f)
        val gx = 700f; val gy = 500f
        val before = c.toCanvas(gx, gy, vw, vh, cw, ch)
        val c2 = c.transformed(gx, gy, vw / 2f, vh / 2f, 1.5f, 0f, 0f, 30f)
        val after = c2.toCanvas(gx, gy, vw, vh, cw, ch)
        assertEquals(before.first, after.first, 1e-2f)
        assertEquals(before.second, after.second, 1e-2f)
        assertEquals(45f, c2.rotDeg, 1e-3f)
        assertEquals(1.8f, c2.zoom, 1e-3f)
    }

    @Test fun followCentersPoint() {
        val c = Camera(zoom = 2f, rotDeg = 30f)
        val f = c.follow(100f, 150f, vw, vh, cw, ch)
        val (sx, sy) = f.toScreen(100f, 150f, vw, vh, cw, ch)
        assertEquals(vw / 2f, sx, 1e-2f); assertEquals(vh / 2f, sy, 1e-2f)
    }

    @Test fun invalidGeometryIsSafe() {
        val c = Camera()
        assertEquals(c, c.follow(Float.NaN, 0f, vw, vh, cw, ch))
        assertEquals(c, c.follow(0f, 0f, 0f, vh, cw, ch))
        val (x, y) = c.toCanvas(Float.NaN, 0f, vw, vh, cw, ch)
        assertFalse(x.isNaN()); assertFalse(y.isNaN())
        assertTrue(abs(x) < 1e6f)
    }
}
