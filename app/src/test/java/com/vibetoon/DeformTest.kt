package com.vibetoon

import com.vibetoon.domain.anim.BrushMath
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.MeshGraph
import com.vibetoon.domain.anim.PathMath
import com.vibetoon.domain.anim.Pin
import com.vibetoon.domain.anim.PuppetMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DeformTest {
    /** Сетка-полоса: [cols] x [rows] вершин в прямоугольнике долей слоя, индексы вершин с [base]. */
    private fun grid(cols: Int, rows: Int, u0: Float, u1: Float, v0: Float, v1: Float, base: Int = 0): Pair<FloatArray, IntArray> {
        val vs = FloatArray(cols * rows * 2)
        for (r in 0 until rows) for (c in 0 until cols) {
            vs[(r * cols + c) * 2] = u0 + (u1 - u0) * c / (cols - 1)
            vs[(r * cols + c) * 2 + 1] = v0 + (v1 - v0) * r / (rows - 1)
        }
        val ts = ArrayList<Int>()
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val a = base + r * cols + c; val b = a + 1; val d = base + (r + 1) * cols + c; val e = d + 1
            ts.addAll(listOf(a, b, d, b, e, d))
        }
        return vs to ts.toIntArray()
    }

    private fun strip() = grid(5, 21, 0.4f, 0.6f, 0f, 1f).let { Mesh(it.first, it.second) }   // слой 100 x 400 px

    private fun pos(m: Mesh, off: FloatArray, k: Int, w: Float, h: Float) = (m.u(k) + off[k * 2]) * w to (m.v(k) + off[k * 2 + 1]) * h

    @Test fun pathBendsStrandAsArcAndKeepsLength() {
        val m = strip(); val w = 100f; val h = 400f
        val pins = listOf(Pin(0.5f, 0f, radius = 600f), Pin(0.5f, 0.5f, radius = 600f), Pin(0.5f, 1f, radius = 600f))
        // тянем кончик на 30% длины (120 px), средний пин идёт по прямой (60 px)
        val off = floatArrayOf(0f, 0f, 0.6f, 0f, 1.2f, 0f)
        val r = PuppetMath.addTo(m, FloatArray(m.count * 2), pins, off, w, h)
        val centre = (0 until 21).map { it * 5 + 2 }          // центральная колонка
        val mid = pos(m, r, centre[10], w, h)
        val dx = mid.first - 50f
        assertTrue("середина отклонилась на ${dx / h}", dx / h in 0.12f..0.17f)
        // длина пряди сохраняется: каждое звено центральной линии осталось 20 px
        for (i in 0 until 20) {
            val a = pos(m, r, centre[i], w, h); val b = pos(m, r, centre[i + 1], w, h)
            val l = hypot(b.first - a.first, b.second - a.second)
            assertEquals("звено $i", 20f, l, 1.0f)
        }
        // толщина пряди сохраняется
        val l = pos(m, r, centre[10] - 2, w, h); val rr = pos(m, r, centre[10] + 2, w, h)
        assertEquals(20f, hypot(rr.first - l.first, rr.second - l.second), 2f)
    }

    @Test fun pathIsIdentityWhenPinsDoNotMove() {
        val m = strip()
        val pins = listOf(Pin(0.5f, 0f), Pin(0.5f, 0.5f), Pin(0.5f, 1f))
        val r = PuppetMath.addTo(m, FloatArray(m.count * 2), pins, FloatArray(6), 100f, 400f)
        assertTrue(r.all { abs(it) < 1e-5f })
    }

    @Test fun pathCurveTurnsRestFrameExactly() {
        val m = strip(); val q = FloatArray(m.count * 2) { if (it % 2 == 0) m.u(it / 2) * 100f else m.v(it / 2) * 400f }
        val rest = PathMath.build(floatArrayOf(50f, 0f, 55f, 200f, 50f, 400f))!!
        val b = PathMath.bind(q, m.tris, rest, 600f)
        val back = PathMath.deform(q, b, rest)
        for (i in q.indices) assertEquals(q[i], back[i], 0.05f)
    }

    @Test fun geodesicDoesNotJumpToNeighbourStrand() {
        // две отдельные пряди в одном меше
        val a = grid(3, 11, 0.1f, 0.2f, 0f, 1f); val b = grid(3, 11, 0.6f, 0.7f, 0f, 1f, base = 33)
        val m = Mesh(a.first + b.first, a.second + b.second)
        val w = 100f; val h = 400f
        val pins = listOf(Pin(0.15f, 0f, radius = 2000f), Pin(0.15f, 0.5f, radius = 2000f), Pin(0.15f, 1f, radius = 2000f))
        val r = PuppetMath.addTo(m, FloatArray(m.count * 2), pins, floatArrayOf(0f, 0f, 0.5f, 0f, 1f, 0f), w, h)
        for (k in 33 until 66) assertEquals("прядь 2, вершина $k", 0f, hypot(r[k * 2], r[k * 2 + 1]), 1e-4f)
        assertTrue((0 until 33).any { hypot(r[it * 2], r[it * 2 + 1]) > 0.1f })
        // то же для двух пинов (ARAP с мягкими пинами)
        val r2 = PuppetMath.addTo(m, FloatArray(m.count * 2), pins.take(2), floatArrayOf(0f, 0f, 0.5f, 0f), w, h)
        for (k in 33 until 66) assertEquals("ARAP, прядь 2, вершина $k", 0f, hypot(r2[k * 2], r2[k * 2 + 1]), 1e-4f)
        assertTrue((0 until 33).any { hypot(r2[it * 2], r2[it * 2 + 1]) > 0.05f })
        val adj = MeshGraph.adjacency(m.count, m.tris)
        val g = MeshGraph.geodesic(FloatArray(m.count * 2) { if (it % 2 == 0) m.u(it / 2) * w else m.v(it / 2) * h }, adj, intArrayOf(0), floatArrayOf(0f))
        assertTrue(g[40].isInfinite())
    }

    @Test fun softPinsBendSmoothly() {
        val m = strip(); val w = 100f; val h = 400f
        val pins = listOf(Pin(0.5f, 0f, radius = 800f), Pin(0.5f, 1f, radius = 800f))
        val r = PuppetMath.addTo(m, FloatArray(m.count * 2), pins, floatArrayOf(0f, 0f, 0.8f, 0f), w, h)   // кончик на 80 px вбок
        val centre = (0 until 21).map { it * 5 + 2 }
        val dxs = centre.map { r[it * 2] * w }
        assertTrue("кончик следует за пином: ${dxs.last()}", dxs.last() > 60f)
        assertTrue("корень на месте: ${dxs.first()}", abs(dxs.first()) < 25f)
        for (i in 0 until 20) assertTrue("скачок между вершинами $i: ${dxs[i + 1] - dxs[i]}", abs(dxs[i + 1] - dxs[i]) < 25f)
    }

    @Test fun brushGrabFollowsFingerWithoutGapsOnFastStroke() {
        val g = grid(21, 21, 0f, 1f, 0f, 1f); val m = Mesh(g.first, g.second)
        val adj = MeshGraph.adjacency(m.count, m.tris)
        val off = FloatArray(m.count * 2)
        // быстрый мазок на 300 px при радиусе 100: шаги по 25 px, середина мазка тоже затронута
        val touched = BrushMath.stroke(m, adj, off, 400f, 400f, 0.1f, 0.5f, 0.85f, 0.5f, 100f, 1f, BrushMath.Mode.GRAB)
        val midIdx = 10 * 21 + 10   // (0.5, 0.5) - посередине мазка
        assertTrue(midIdx in touched)
        assertTrue("середина мазка сдвинулась вправо: ${off[midIdx * 2]}", off[midIdx * 2] > 0.02f)
        // далеко от мазка ничего не двигается
        val far = 0 * 21 + 0
        assertEquals(0f, off[far * 2], 1e-6f)
        assertEquals(0f, off[far * 2 + 1], 1e-6f)
    }

    @Test fun brushSmoothDoesNotShrinkUntouchedShape() {
        val g = grid(21, 21, 0f, 1f, 0f, 1f); val m = Mesh(g.first, g.second)
        val adj = MeshGraph.adjacency(m.count, m.tris)
        val off = FloatArray(m.count * 2)
        repeat(5) { BrushMath.stroke(m, adj, off, 400f, 400f, 0.5f, 0.5f, 0.5f, 0.5f, 150f, 1f, BrushMath.Mode.SMOOTH) }
        assertTrue("разглаживание нулевых смещений ничего не двигает", off.all { abs(it) < 1e-6f })
        // после сдвига и разглаживания граница не стягивается к центру
        BrushMath.stroke(m, adj, off, 400f, 400f, 0.5f, 0.5f, 0.6f, 0.5f, 120f, 1f, BrushMath.Mode.GRAB)
        repeat(5) { BrushMath.stroke(m, adj, off, 400f, 400f, 0.5f, 0.5f, 0.5f, 0.5f, 400f, 1f, BrushMath.Mode.SMOOTH) }
        for (k in 0 until m.count) if (m.u(k) == 0f || m.u(k) == 1f) assertTrue("граница поехала", abs(off[k * 2 + 1]) < 0.02f)
    }

    private fun minAngles(m: Mesh, w: Float, h: Float): List<Double> = (0 until m.tris.size / 3).map { t ->
        val ids = (0 until 3).map { m.tris[t * 3 + it] }
        val px = ids.map { m.u(it) * w }; val py = ids.map { m.v(it) * h }
        fun len(a: Int, b: Int) = Math.hypot((px[a] - px[b]).toDouble(), (py[a] - py[b]).toDouble())
        val l = listOf(len(1, 2), len(0, 2), len(0, 1))
        (0 until 3).minOf { k -> Math.toDegrees(Math.acos(((l[(k + 1) % 3] * l[(k + 1) % 3] + l[(k + 2) % 3] * l[(k + 2) % 3] - l[k] * l[k]) / (2 * l[(k + 1) % 3] * l[(k + 2) % 3])).coerceIn(-1.0, 1.0))) }
    }

    private fun shape(aw: Int, ah: Int, inside: (Int, Int) -> Boolean) = ByteArray(aw * ah) { i -> if (inside(i % aw, i / aw)) 0xFF.toByte() else 0 }

    @Test fun autoStepShrinksForComplexShapesAndStaysInRange() {
        val aw = 200; val ah = 200
        val disk = shape(aw, ah) { x, y -> Math.hypot(x - 100.0, y - 100.0) < 80 }
        val comb = shape(aw, ah) { x, y -> x in 20..180 && y in 20..180 && (x / 8) % 2 == 0 }   // гребёнка: длинный извилистый контур
        val md = com.vibetoon.domain.anim.MeshMath.contourMask(disk, aw, ah)!!
        val mc = com.vibetoon.domain.anim.MeshMath.contourMask(comb, aw, ah)!!
        val sd = com.vibetoon.domain.anim.MeshMath.autoGridStep(1000, 1000, md, aw, ah)
        val sc = com.vibetoon.domain.anim.MeshMath.autoGridStep(1000, 1000, mc, aw, ah)
        assertTrue("шаг круга $sd, гребёнки $sc", sc <= sd)
        assertTrue(sd in 16f..128f && sc in 16f..128f)
    }

    @Test fun autoMeshOnStripHasEvenTrianglesAndNoHoles() {
        val aw = 100; val ah = 400
        val strip = shape(aw, ah) { x, y -> x in 30..70 && y in 10..390 }
        val m = com.vibetoon.domain.anim.MeshMath.fromContourAuto(100, 400, strip, aw, ah)!!
        assertTrue("вершин ${m.count}", m.count in 20..400)
        val ang = minAngles(m, 100f, 400f)
        val bad = ang.count { it < 10 } / ang.size.toDouble()
        assertTrue("треугольников с углом < 10°: $bad", bad < 0.15)
        // монолитность: площадь сетки не меньше 90% площади фигуры (дыр нет)
        var area = 0.0
        for (t in 0 until m.tris.size / 3) {
            val a = m.tris[t * 3]; val b = m.tris[t * 3 + 1]; val c = m.tris[t * 3 + 2]
            area += Math.abs((m.u(b) - m.u(a)) * 100.0 * (m.v(c) - m.v(a)) * 400.0 - (m.v(b) - m.v(a)) * 400.0 * (m.u(c) - m.u(a)) * 100.0) / 2
        }
        assertTrue("площадь сетки $area", area > 0.9 * 41 * 381)
    }

    @Test fun autoMeshOnEllipseStaysBounded() {
        val aw = 300; val ah = 300
        val el = shape(aw, ah) { x, y -> ((x - 150.0) / 120) * ((x - 150.0) / 120) + ((y - 150.0) / 80) * ((y - 150.0) / 80) < 1 }
        val m = com.vibetoon.domain.anim.MeshMath.fromContourAuto(1000, 1000, el, aw, ah)!!
        assertTrue("вершин ${m.count}", m.count in 30..1024)
        assertTrue(minAngles(m, 1000f, 1000f).count { it < 10 } < m.tris.size / 3 * 0.15)
    }

    @Test fun highPresetGivesMoreVerticesThanStandard() {
        val aw = 300; val ah = 300
        val el = shape(aw, ah) { x, y -> ((x - 150.0) / 120) * ((x - 150.0) / 120) + ((y - 150.0) / 80) * ((y - 150.0) / 80) < 1 }
        val std = com.vibetoon.domain.anim.MeshMath.fromContourAuto(1000, 1000, el, aw, ah, false)!!
        val hi = com.vibetoon.domain.anim.MeshMath.fromContourAuto(1000, 1000, el, aw, ah, true)!!
        assertTrue("standard ${std.count}, high ${hi.count}", hi.count > std.count * 1.5 && hi.count <= 1024)
    }
}
