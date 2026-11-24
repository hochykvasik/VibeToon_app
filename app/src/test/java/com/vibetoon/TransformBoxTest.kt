package com.vibetoon

import com.vibetoon.domain.raster.IRect
import com.vibetoon.domain.raster.TransformBox
import com.vibetoon.domain.raster.TransformBox.Handle
import com.vibetoon.domain.raster.TransformBox.Mode
import com.vibetoon.domain.raster.TransformBox.P
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformBoxTest {
    private val box = TransformBox.initial(IRect(10, 10, 20, 10))
    private fun near(e: P, a: P) { assertEquals(e.x, a.x, 1e-6); assertEquals(e.y, a.y, 1e-6) }

    @Test fun initialAnchorsAndHit() {
        near(P(20.0, 15.0), box.pivot)
        near(P(10.0, 10.0), TransformBox.withAnchor(box, 0, 0).pivot)
        near(P(30.0, 20.0), TransformBox.withAnchor(box, 2, 2).pivot)
        near(P(30.0, 15.0), TransformBox.withAnchor(box, 2, 1).pivot)
        assertEquals(Handle.C2, TransformBox.hit(box, P(29.0, 19.0), 3.0))
        assertEquals(Handle.E0, TransformBox.hit(box, P(20.0, 10.5), 3.0))
        assertEquals(Handle.PIVOT, TransformBox.hit(box, P(20.5, 15.0), 2.0))
        assertEquals(Handle.MOVE, TransformBox.hit(box, P(14.0, 14.0), 2.0))
        assertEquals(Handle.ROTATE, TransformBox.hit(box, P(50.0, 50.0), 2.0))
        assertTrue(TransformBox.isIdentity(box)); assertTrue(TransformBox.valid(box))
    }

    @Test fun moveRotateAndPivot() {
        val m = TransformBox.drag(box, Handle.MOVE, Mode.FREE, P(0.0, 0.0), P(5.0, -2.0), false)
        near(P(15.0, 8.0), m.corners[0]); near(P(25.0, 13.0), m.pivot)
        val r = TransformBox.drag(box, Handle.ROTATE, Mode.FREE, P(40.0, 15.0), P(20.0, 35.0), false)   // +90°
        near(P(25.0, 5.0), r.corners[0]); near(box.pivot, r.pivot)
        val pv = TransformBox.drag(box, Handle.PIVOT, Mode.FREE, P(20.0, 15.0), P(10.0, 10.0), false)
        near(P(10.0, 10.0), pv.pivot); assertEquals(box.corners, pv.corners)
    }

    @Test fun freeScaleAroundPivot() {
        val anchored = TransformBox.withAnchor(box, 0, 0)
        val s = TransformBox.drag(anchored, Handle.C2, Mode.FREE, P(30.0, 20.0), P(50.0, 25.0), false)
        near(P(10.0, 10.0), s.corners[0]); near(P(50.0, 25.0), s.corners[2])
        val prop = TransformBox.drag(anchored, Handle.C2, Mode.FREE, P(30.0, 20.0), P(50.0, 30.0), true)
        near(P(50.0, 30.0), prop.corners[2])
        val edge = TransformBox.drag(box, Handle.E1, Mode.FREE, P(30.0, 15.0), P(40.0, 99.0), false)
        near(P(0.0, 10.0), edge.corners[0]); near(P(40.0, 20.0), edge.corners[2])   // только по ширине от центра
    }

    @Test fun skewAndDistort() {
        val sk = TransformBox.drag(box, Handle.E0, Mode.SKEW, P(20.0, 10.0), P(25.0, 30.0), false)
        near(P(15.0, 10.0), sk.corners[0]); near(P(35.0, 10.0), sk.corners[1]); near(box.corners[2], sk.corners[2])
        val ds = TransformBox.drag(box, Handle.C1, Mode.DISTORT, P(30.0, 10.0), P(34.0, 6.0), false)
        near(P(34.0, 6.0), ds.corners[1]); near(box.corners[0], ds.corners[0])
        assertTrue(TransformBox.valid(ds))
        val bowtie = box.copy(corners = listOf(box.corners[0], box.corners[2], box.corners[1], box.corners[3]))
        assertFalse(TransformBox.valid(bowtie))
        val tiny = TransformBox.drag(TransformBox.withAnchor(box, 0, 0), Handle.C2, Mode.FREE, P(30.0, 20.0), P(10.5, 10.5), false)
        assertFalse(TransformBox.valid(tiny))
    }

    @Test fun numericAndFlip() {
        val n = TransformBox.numeric(box, TransformBox.Numeric(scaleX = 2.0, scaleY = .5))!!
        near(P(0.0, 12.5), n.corners[0]); near(P(40.0, 17.5), n.corners[2])
        val f = TransformBox.numeric(box, TransformBox.Numeric(flipX = true))!!
        near(P(30.0, 10.0), f.corners[0]); assertTrue(TransformBox.valid(f))
        val sk = TransformBox.numeric(TransformBox.withAnchor(box, 0, 0), TransformBox.Numeric(skewXDeg = 45.0))!!
        near(P(20.0, 20.0), sk.corners[3])
        val mv = TransformBox.numeric(box, TransformBox.Numeric(dx = 3.0, dy = 4.0, rotationDeg = 90.0))!!
        near(P(23.0, 19.0), mv.pivot)
        assertNull(TransformBox.numeric(box, TransformBox.Numeric(scaleX = 0.0)))
        assertNull(TransformBox.numeric(box, TransformBox.Numeric(skewYDeg = 89.0)))
        assertNull(TransformBox.numeric(box, TransformBox.Numeric(rotationDeg = Double.NaN)))
    }

    @Test fun homographyMapsCornersAndInverts() {
        val ds = box.copy(corners = listOf(P(10.0, 10.0), P(34.0, 6.0), P(31.0, 22.0), P(8.0, 20.0)))
        val h = TransformBox.homography(ds)!!
        near(ds.corners[0], TransformBox.mapUnit(h, 0.0, 0.0)); near(ds.corners[1], TransformBox.mapUnit(h, 1.0, 0.0))
        near(ds.corners[2], TransformBox.mapUnit(h, 1.0, 1.0)); near(ds.corners[3], TransformBox.mapUnit(h, 0.0, 1.0))
        val inv = TransformBox.invert(h)!!
        val mid = TransformBox.mapUnit(h, .3, .7)
        near(P(.3, .7), TransformBox.mapUnit(inv, mid.x, mid.y))
        val aff = TransformBox.homography(box)!!
        assertEquals(0.0, aff[6], 1e-12); assertEquals(0.0, aff[7], 1e-12)
        assertNotNull(TransformBox.invert(aff))
        assertNull(TransformBox.invert(DoubleArray(9)))
    }
}
