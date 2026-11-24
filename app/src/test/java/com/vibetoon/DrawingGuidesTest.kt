package com.vibetoon

import com.vibetoon.domain.paint.DrawingGuides
import com.vibetoon.domain.paint.DrawingGuides.Kind
import com.vibetoon.domain.paint.DrawingGuides.Point
import com.vibetoon.domain.paint.DrawingGuides.Symmetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

class DrawingGuidesTest {
    private val off = DrawingGuides.State()
    private fun near(a: Float, b: Float, eps: Float = .01f) = assertEquals(a, b, eps)

    @Test fun defaultsPerKind() {
        val p1 = DrawingGuides.withKind(off, Kind.PERSPECTIVE_1, 1000, 800)
        assertEquals(1, p1.vps.size); near(500f, p1.vps[0].x)
        assertEquals(2, DrawingGuides.withKind(off, Kind.PERSPECTIVE_2, 1000, 800).vps.size)
        assertEquals(3, DrawingGuides.withKind(off, Kind.PERSPECTIVE_3, 1000, 800).vps.size)
        val e = DrawingGuides.withKind(off, Kind.ELLIPSE, 1000, 800)
        near(500f, e.center.x); near(400f, e.center.y); assertTrue(e.rx > e.ry)
        val iso = DrawingGuides.withKind(off, Kind.ISOMETRIC, 1200, 1200)
        near(100f, iso.cell)
        assertTrue(iso.snapping); assertTrue(iso.active)
        assertFalse(off.active); assertFalse(off.snapping)
        // симметрия переживает смену вида
        val sym = DrawingGuides.withSymmetry(off, Symmetry.VERTICAL, 1000, 800)
        assertEquals(Symmetry.VERTICAL, DrawingGuides.withKind(sym, Kind.ELLIPSE, 1000, 800).symmetry)
        assertEquals(Kind.OFF, DrawingGuides.withKind(e, Kind.OFF, 10, 10).kind)
    }

    @Test fun isometricSnapsToThirtyDegreeAndVertical() {
        val iso = DrawingGuides.withKind(off, Kind.ISOMETRIC, 1000, 1000)
        val st = DrawingGuides.Stroke(iso, 10f)
        st.begin(Point(100f, 100f))
        // слишком короткое движение - направление ещё не выбрано, точек нет
        assertTrue(st.move(Point(103f, 101f))[0].isEmpty())
        val out = st.move(Point(200f, 150f))[0]   // ~26.6° вниз-вправо -> линия 30°
        assertEquals(1, out.size)
        val ang = Math.toDegrees(atan2((out[0].y - 100f).toDouble(), (out[0].x - 100f).toDouble()))
        assertEquals(30.0, ang, .01)
        // дальнейшие точки остаются на той же прямой
        val next = st.move(Point(300f, 100f))[0][0]
        assertEquals(30.0, Math.toDegrees(atan2((next.y - 100f).toDouble(), (next.x - 100f).toDouble())), .01)
        val v = DrawingGuides.Stroke(iso, 10f); v.begin(Point(0f, 0f))
        val vp = v.move(Point(3f, 50f))[0][0]
        near(0f, vp.x)
    }

    @Test fun perspectiveSnapsTowardVanishingPoint() {
        val p2 = DrawingGuides.withKind(off, Kind.PERSPECTIVE_2, 1000, 1000).copy(vps = listOf(Point(0f, 500f), Point(1000f, 500f)))
        val a = Point(500f, 800f)
        val dirs = DrawingGuides.directions(p2, a)
        assertEquals(3, dirs.size)
        val st = DrawingGuides.Stroke(p2, 5f); st.begin(a)
        val p = st.move(Point(550f, 740f))[0][0]   // к правой точке схода
        // точка лежит на прямой a -> VP1
        val cross = (p.x - a.x) * (500f - a.y) - (p.y - a.y) * (1000f - a.x)
        assertEquals(0f, cross, .5f)
        val vertical = DrawingGuides.Stroke(p2, 5f); vertical.begin(a)
        near(500f, vertical.move(Point(505f, 600f))[0][0].x)
        // 1 точка: горизонталь, вертикаль, к точке; 3 точки: только три точки схода
        assertEquals(3, DrawingGuides.directions(DrawingGuides.withKind(off, Kind.PERSPECTIVE_1, 100, 100), Point(0f, 0f)).size)
        val p3 = DrawingGuides.withKind(off, Kind.PERSPECTIVE_3, 100, 100)
        assertEquals(2, DrawingGuides.directions(p3, p3.vps[0]).size)   // точка в самой VP не даёт направления к себе
    }

    @Test fun ellipseProjectionAndArc() {
        val e = DrawingGuides.State(kind = Kind.ELLIPSE, center = Point(100f, 100f), rx = 50f, ry = 20f, angle = 0f)
        val p = DrawingGuides.projectOnEllipse(e, Point(300f, 100f)); near(150f, p.x); near(100f, p.y)
        val q = DrawingGuides.projectOnEllipse(e, Point(100f, 10f)); near(100f, q.x); near(80f, q.y)
        val rotated = e.copy(angle = (Math.PI / 2).toFloat())
        val r = DrawingGuides.projectOnEllipse(rotated, Point(100f, 300f)); near(100f, r.x, .05f); near(150f, r.y, .05f)
        val st = DrawingGuides.Stroke(e, 5f, 2f)
        val start = st.begin(Point(200f, 100f))[0][0]; near(150f, start.x)
        val arc = st.move(Point(100f, 0f))[0]
        assertTrue(arc.size > 10)
        for (pt in arc) { val u = (pt.x - 100f) / 50f; val v = (pt.y - 100f) / 20f; assertEquals(1f, u * u + v * v, .01f) }
        for ((a, b) in arc.zipWithNext()) assertTrue(hypot(b.x - a.x, b.y - a.y) <= 2.01f)
        near(100f, arc.last().x, .05f); near(80f, arc.last().y, .05f)
    }

    @Test fun mirrorImages() {
        val c = Point(50f, 50f)
        val v = off.copy(symmetry = Symmetry.VERTICAL, symCenter = c)
        assertEquals(listOf(Point(10f, 20f), Point(90f, 20f)), DrawingGuides.mirrorImages(v, Point(10f, 20f)))
        val h = off.copy(symmetry = Symmetry.HORIZONTAL, symCenter = c)
        assertEquals(Point(10f, 80f), DrawingGuides.mirrorImages(h, Point(10f, 20f))[1])
        val both = off.copy(symmetry = Symmetry.BOTH, symCenter = c)
        assertEquals(4, DrawingGuides.mirrorImages(both, Point(10f, 20f)).toSet().size)
        val radial = DrawingGuides.withRadial(off.copy(symmetry = Symmetry.RADIAL, symCenter = c), 4)
        val imgs = DrawingGuides.mirrorImages(radial, Point(60f, 50f))
        assertEquals(4, imgs.size)
        near(50f, imgs[1].x); near(60f, imgs[1].y); near(40f, imgs[2].x); near(40f, imgs[3].y)
        assertEquals(16, DrawingGuides.mirrorCount(DrawingGuides.withRadial(radial, 99)))
        assertEquals(2, DrawingGuides.mirrorCount(DrawingGuides.withRadial(radial, 0)))
        assertEquals(1, DrawingGuides.mirrorCount(off))
    }

    @Test fun strokeCombinesSnapAndSymmetry() {
        val s = DrawingGuides.withKind(off, Kind.ISOMETRIC, 100, 100).copy(symmetry = Symmetry.VERTICAL, symCenter = Point(50f, 50f))
        val st = DrawingGuides.Stroke(s, 5f)
        assertEquals(2, st.count)
        val b = st.begin(Point(20f, 20f)); assertEquals(1, b[0].size); assertEquals(1, b[1].size); near(80f, b[1][0].x)
        val m = st.move(Point(20f, 60f))
        near(20f, m[0][0].x); near(80f, m[1][0].x); near(60f, m[1][0].y)
        // «Притяжение» выключено: штрих свободный, но симметрия работает
        val free = DrawingGuides.Stroke(s.copy(snap = false), 5f)
        free.begin(Point(20f, 20f))
        val f = free.move(Point(23f, 21f)); assertEquals(Point(23f, 21f), f[0][0]); near(77f, f[1][0].x)
        assertNull(free.lockedDirection())
    }

    @Test fun overlayAndClip() {
        assertNull(DrawingGuides.clip(Point(-10f, -10f), Point(-5f, -1f), 100f, 100f))
        val c = DrawingGuides.clip(Point(-50f, 50f), Point(150f, 50f), 100f, 100f)!!
        near(0f, c[0]); near(100f, c[2])
        val iso = DrawingGuides.withKind(off, Kind.ISOMETRIC, 400, 300)
        val lines = DrawingGuides.overlay(iso, 400, 300)
        assertTrue(lines.size in 10..400)
        for (l in lines) for (i in l.indices step 2) { assertTrue(l[i] in -0.01f..400.01f); assertTrue(l[i + 1] in -0.01f..300.01f) }
        val p3 = DrawingGuides.withKind(off, Kind.PERSPECTIVE_3, 400, 300)
        assertTrue(DrawingGuides.overlay(p3, 400, 300).size > 24)
        val e = DrawingGuides.overlay(DrawingGuides.withKind(off, Kind.ELLIPSE, 400, 300), 400, 300)
        assertEquals(1, e.size); assertEquals(97 * 2, e[0].size)
        val radial = DrawingGuides.withRadial(DrawingGuides.withSymmetry(off, Symmetry.RADIAL, 400, 300), 8)
        assertEquals(8, DrawingGuides.overlay(radial, 400, 300).size)
        assertTrue(DrawingGuides.overlay(iso, 0, 300).isEmpty())
    }

    @Test fun handlesHitAndMove() {
        val e = DrawingGuides.withKind(off, Kind.ELLIPSE, 1000, 1000)
        val rx = DrawingGuides.handles(e).first { it.first == DrawingGuides.Handle.ELLIPSE_RX }.second
        assertEquals(DrawingGuides.Handle.ELLIPSE_RX, DrawingGuides.hit(e, Point(rx.x + 3f, rx.y), 20f))
        assertNull(DrawingGuides.hit(e, Point(0f, 0f), 20f))
        val turned = DrawingGuides.moveHandle(e, DrawingGuides.Handle.ELLIPSE_RX, Point(500f, 800f))
        near(300f, turned.rx); near((Math.PI / 2).toFloat(), turned.angle)
        val ry = DrawingGuides.moveHandle(e, DrawingGuides.Handle.ELLIPSE_RY, Point(700f, 540f)); near(40f, ry.ry)
        assertEquals(e, DrawingGuides.moveHandle(e, DrawingGuides.Handle.ELLIPSE_RX, Point(501f, 500f)))   // слишком мало
        val p2 = DrawingGuides.withKind(off, Kind.PERSPECTIVE_2, 1000, 1000)
        assertEquals(Point(5f, 6f), DrawingGuides.moveHandle(p2, DrawingGuides.Handle.VP1, Point(5f, 6f)).vps[1])
        val sym = DrawingGuides.withSymmetry(p2, Symmetry.BOTH, 1000, 1000)
        assertEquals(3, DrawingGuides.handles(sym).size)
        val iso = DrawingGuides.withKind(off, Kind.ISOMETRIC, 1000, 1000)
        assertTrue(DrawingGuides.moveHandle(iso, DrawingGuides.Handle.ISO_CELL, Point(500f, 501f)).cell >= 8f)
        assertEquals(e, DrawingGuides.moveHandle(e, DrawingGuides.Handle.ELLIPSE_CENTER, Point(Float.NaN, 1f)))
    }

    @Test fun encodeDecodeAndRescale() {
        val s = DrawingGuides.withRadial(DrawingGuides.withSymmetry(DrawingGuides.withKind(off, Kind.PERSPECTIVE_3, 1000, 800), Symmetry.RADIAL, 1000, 800), 7).copy(snap = false)
        val d = DrawingGuides.decode(DrawingGuides.encode(s, 1000, 800))!!
        assertEquals(s, d.state); assertEquals(1000, d.width); assertEquals(800, d.height)
        assertNull(DrawingGuides.decode(null)); assertNull(DrawingGuides.decode("g1;1;2")); assertNull(DrawingGuides.decode("x"))
        assertNull(DrawingGuides.decode(DrawingGuides.encode(s, 1000, 800).replace("PERSPECTIVE_3", "BAD")))
        val r = DrawingGuides.rescale(s, 1000, 800, 2000, 800)
        near(s.vps[1].x * 2, r.vps[1].x); near(s.vps[1].y, r.vps[1].y)
        assertEquals(s, DrawingGuides.rescale(s, 1000, 800, 1000, 800))
        assertNotNull(DrawingGuides.decode(DrawingGuides.encode(off, 1, 1)))
        assertTrue(abs(DrawingGuides.rescale(DrawingGuides.withKind(off, Kind.ELLIPSE, 100, 100), 100, 100, 50, 200).rx - 15f) < .01f)
    }
}
