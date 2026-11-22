package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTest {
    private fun area(m: Mesh, w: Float, h: Float): Float {
        var s = 0f
        for (t in 0 until m.tris.size / 3) {
            val a = m.tris[t * 3]; val b = m.tris[t * 3 + 1]; val c = m.tris[t * 3 + 2]
            val ax = m.u(a) * w; val ay = m.v(a) * h
            s += kotlin.math.abs((m.u(b) * w - ax) * (m.v(c) * h - ay) - (m.v(b) * h - ay) * (m.u(c) * w - ax)) / 2f
        }
        return s
    }

    @Test fun generate_coversWholeLayer() {
        val m = MeshMath.generate(400, 200, 8)
        assertEquals(400f * 200f, area(m, 400f, 200f), 1f)
        assertTrue(m.count <= MeshMath.MAX_VERTS)
        assertTrue(m.tris.all { it in 0 until m.count })
    }

    @Test fun generate_respectsVertexCap() {
        val m = MeshMath.generate(4000, 4000, 500)
        assertTrue(m.count <= MeshMath.MAX_VERTS)
    }

    @Test fun addVertex_keepsOldIndicesAndArea() {
        val m = MeshMath.generate(100, 100, 3)
        val m2 = MeshMath.addVertex(m, 0.37f, 0.61f, 100, 100)
        assertNotNull(m2)
        assertEquals(m.count + 1, m2!!.count)
        for (k in 0 until m.count) { assertEquals(m.u(k), m2.u(k), 0f); assertEquals(m.v(k), m2.v(k), 0f) }
        assertEquals(100f * 100f, area(m2, 100f, 100f), 1f)
    }

    @Test fun addVertex_rejectsDuplicatesAndOutside() {
        val m = MeshMath.generate(100, 100, 3)
        assertNull(MeshMath.addVertex(m, m.u(4), m.v(4), 100, 100))
        assertNull(MeshMath.addVertex(m, 1.2f, 0.5f, 100, 100))
    }

    @Test fun offsets_bindAndFx() {
        val m = MeshMath.generate(100, 100, 2)
        val id = MeshMath.vertexId(7, 3)
        val base = mapOf(id to LayerTransform(dx = 0.1f))
        val anim = Animation(tracks = mapOf(id to AnimationTrack(id, listOf(Keyframe.of(0, LayerTransform(dx = 0.3f))))))
        assertEquals(0.1f, MeshMath.offsets(7, m, 0, base, anim, fx = false)[6], 1e-6f)
        assertEquals(0.4f, MeshMath.offsets(7, m, 0, base, anim, fx = true)[6], 1e-6f)
    }

    @Test fun json_roundTrip() {
        val m = MeshMath.generate(100, 50, 4)
        val d = ProjectData(ProjectMeta(id = "x", name = "t", width = 100, height = 100, fps = 24, durationFrames = 10), meshes = mapOf(5 to m))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow().meshes[5]!!
        assertEquals(m.count, back.count)
        assertTrue(m.tris.contentEquals(back.tris))
        assertEquals(m.u(3), back.u(3), 1e-6f)
    }

    @Test fun cut_removesHitTrianglesAndCompactDropsVertices() {
        val m = MeshMath.generate(100, 100, 2)           // 3x3 вершины, 8 треугольников
        val zero = FloatArray(m.count * 2)
        // отрезок по левому верхнему углу
        val hit = MeshMath.trianglesHit(m, zero, 0.05f, 0.05f, 0.1f, 0.1f)
        assertTrue(hit.isNotEmpty() && hit.size < m.tris.size / 3)
        val cut = MeshMath.removeTriangles(m, hit.toSet())
        assertEquals(m.tris.size / 3 - hit.size, cut.tris.size / 3)
        val (c, map) = MeshMath.compact(cut)
        assertTrue(c.tris.all { it in 0 until c.count })
        assertEquals(c.count, map.count { it >= 0 })
    }

    @Test fun cutAcrossWholeMeshHitsEverythingOnPath() {
        val m = MeshMath.generate(100, 100, 4)
        val zero = FloatArray(m.count * 2)
        val hit = MeshMath.trianglesHit(m, zero, -0.1f, 0.5f, 1.1f, 0.5f)
        assertTrue(hit.size >= 8)
    }

    @Test fun addVertex_afterCutKeepsHole() {
        val m = MeshMath.generate(100, 100, 2)
        val hit = MeshMath.trianglesHit(m, FloatArray(m.count * 2), 0.05f, 0.05f, 0.1f, 0.1f)
        val cut = MeshMath.removeTriangles(m, hit.toSet())
        assertNull(MeshMath.addVertex(cut, 0.1f, 0.1f, 100, 100))   // точка в дырке - вершина не добавляется
        val ok = MeshMath.addVertex(cut, 0.9f, 0.9f, 100, 100)
        assertNotNull(ok)
        assertEquals(cut.tris.size / 3 + 2, ok!!.tris.size / 3)
    }

    @Test fun fromAlpha_keepsOnlyOpaqueCells() {
        val aw = 40; val ah = 40
        val a = ByteArray(aw * ah)
        for (y in 0 until 20) for (x in 0 until 20) a[y * aw + x] = 255.toByte()   // непрозрачен левый верхний квадрант
        val m = MeshMath.fromAlpha(400, 400, a, aw, ah, 4)!!
        val full = MeshMath.generate(400, 400, 4)
        assertTrue(m.count < full.count)
        assertTrue(m.tris.size / 3 in 1 until full.tris.size / 3)
        assertTrue((0 until m.count).all { m.u(it) <= 0.51f && m.v(it) <= 0.51f })
        assertNull(MeshMath.fromAlpha(400, 400, ByteArray(aw * ah), aw, ah, 4))   // полностью прозрачный слой
    }
}

class MeshContourTest {
    private fun circle(n: Int): ByteArray = ByteArray(n * n) { i ->
        val x = i % n - n / 2f; val y = i / n - n / 2f
        if (x * x + y * y < (n * 0.4f) * (n * 0.4f)) 255.toByte() else 0
    }

    @org.junit.Test fun contourMeshStaysInsideShape() {
        val n = 200
        val m = com.vibetoon.domain.anim.MeshMath.fromContour(400, 400, circle(n), n, n, 10)!!
        org.junit.Assert.assertTrue(m.count in 20..com.vibetoon.domain.anim.MeshMath.MAX_VERTS)
        // центроиды всех треугольников внутри круга (с запасом на расширение маски)
        for (t in 0 until m.tris.size / 3) {
            val cu = (m.u(m.tris[t * 3]) + m.u(m.tris[t * 3 + 1]) + m.u(m.tris[t * 3 + 2])) / 3f - 0.5f
            val cv = (m.v(m.tris[t * 3]) + m.v(m.tris[t * 3 + 1]) + m.v(m.tris[t * 3 + 2])) / 3f - 0.5f
            org.junit.Assert.assertTrue(cu * cu + cv * cv < 0.43f * 0.43f)
        }
        // углы квадрата вне формы в сетку не попали
        org.junit.Assert.assertTrue((0 until m.count).none { m.u(it) < 0.05f && m.v(it) < 0.05f })
    }

    @org.junit.Test fun transparentLayerGivesNull() {
        org.junit.Assert.assertNull(com.vibetoon.domain.anim.MeshMath.fromContour(100, 100, ByteArray(100), 10, 10, 8))
    }
}

class MeshEditTest {
    @org.junit.Test fun removeInteriorVertexKeepsAreaAndDropsVertex() {
        val m = com.vibetoon.domain.anim.MeshMath.generate(300, 300, 4)
        // центральная вершина сетки
        var k = 0; var bd = 9f
        for (i in 0 until m.count) { val d = (m.u(i) - 0.5f) * (m.u(i) - 0.5f) + (m.v(i) - 0.5f) * (m.v(i) - 0.5f); if (d < bd) { bd = d; k = i } }
        fun area(mm: com.vibetoon.domain.anim.Mesh): Float {
            var s = 0f
            for (t in 0 until mm.tris.size / 3) {
                val a = mm.tris[t * 3]; val b = mm.tris[t * 3 + 1]; val c = mm.tris[t * 3 + 2]
                s += kotlin.math.abs((mm.u(b) - mm.u(a)) * (mm.v(c) - mm.v(a)) - (mm.v(b) - mm.v(a)) * (mm.u(c) - mm.u(a))) / 2f
            }
            return s
        }
        val before = area(m)
        val r = com.vibetoon.domain.anim.MeshMath.removeVertex(m, k, 300f, 300f)!!
        org.junit.Assert.assertTrue(r.tris.none { it == k })
        org.junit.Assert.assertEquals(before, area(r), 1e-4f)   // дыра закрыта без потери площади
        val (c, map) = com.vibetoon.domain.anim.MeshMath.compact(r)
        org.junit.Assert.assertEquals(m.count - 1, c.count)
        org.junit.Assert.assertEquals(-1, map[k])
    }

    @org.junit.Test fun smoothKeepsOriginalVerticesAndBendsBetween() {
        val m = com.vibetoon.domain.anim.MeshMath.generate(100, 100, 2)
        val off = FloatArray(m.count * 2)
        off[0] = 0.1f   // сдвигаем одну вершину
        val (s, so) = com.vibetoon.domain.anim.MeshMath.smooth(m, off, 1)
        org.junit.Assert.assertTrue(s.count > m.count)
        org.junit.Assert.assertEquals(0.1f, so[0], 1e-6f)       // исходная вершина на месте
        // середина ребра рядом с ней сместилась, но меньше самой вершины
        val moved = (m.count until s.count).any { so[it * 2] > 0.01f && so[it * 2] < 0.1f }
        org.junit.Assert.assertTrue(moved)
        org.junit.Assert.assertEquals(m.tris.size * 4, s.tris.size)
    }
}

class PuppetTest {
    private val m = com.vibetoon.domain.anim.MeshMath.generate(400, 400, 8)

    @org.junit.Test fun softPinPullsNeighboursSmoothlyAndFarStaysPut() {
        val pins = listOf(com.vibetoon.domain.anim.Pin(0.5f, 0.5f, false, 150f))
        val off = floatArrayOf(0.2f, 0f)
        val r = com.vibetoon.domain.anim.PuppetMath.addTo(m, FloatArray(m.count * 2), pins, off, 400f, 400f)
        var near = -1; var far = -1; var mid = -1
        for (k in 0 until m.count) {
            val d = Math.hypot(((m.u(k) - 0.5f) * 400f).toDouble(), ((m.v(k) - 0.5f) * 400f).toDouble())
            if (d < 1) near = k
            if (d > 200) far = k
            if (d in 60.0..100.0) mid = k
        }
        org.junit.Assert.assertEquals(0.2f, r[near * 2], 1e-3f)       // под пином смещение полное
        org.junit.Assert.assertEquals(0f, r[far * 2], 1e-6f)          // далеко за радиусом - ноль
        org.junit.Assert.assertTrue(r[mid * 2] > 0.01f && r[mid * 2] < 0.2f)   // между - плавный спад
    }

    @org.junit.Test fun stiffPinMovesInnerAreaRigidly() {
        val p = com.vibetoon.domain.anim.Pin(0.5f, 0.5f, true, 200f)
        org.junit.Assert.assertEquals(1f, com.vibetoon.domain.anim.PuppetMath.weight(p, 100f), 1e-6f)   // внутри 0.6 R - жёстко
        org.junit.Assert.assertEquals(0f, com.vibetoon.domain.anim.PuppetMath.weight(p, 200f), 1e-6f)
        org.junit.Assert.assertTrue(com.vibetoon.domain.anim.PuppetMath.weight(p, 160f) in 0.01f..0.99f)
    }

    @org.junit.Test fun pinsRoundTripJson() {
        val d = com.vibetoon.domain.project.ProjectData(
            com.vibetoon.domain.project.ProjectMeta(id = "p", name = "t", width = 10, height = 10),
            pins = mapOf(3 to listOf(com.vibetoon.domain.anim.Pin(0.25f, 0.75f, true, 220f)))
        )
        val back = com.vibetoon.domain.project.ProjectJson.decode(com.vibetoon.domain.project.ProjectJson.encode(d)).getOrThrow()
        org.junit.Assert.assertEquals(d.pins, back.pins)
    }
}

class ContourMarginAndGradientTest {
    @org.junit.Test fun contourMeshCoversFaintEdgesWithMargin() {
        val n = 100
        val a = ByteArray(n * n)
        for (y in 40..60) for (x in 40..60) a[y * n + x] = 255.toByte()
        // тонкий полупрозрачный «локон» (альфа 30) справа от фигуры
        for (x in 61..75) a[50 * n + x] = 30
        val m = com.vibetoon.domain.anim.MeshMath.fromContour(400, 400, a, n, n, 10)!!
        val maxU = (0 until m.count).maxOf { m.u(it) }
        org.junit.Assert.assertTrue("локон должен быть накрыт сеткой, maxU=$maxU", maxU >= 0.75f)
        // запас: сетка выходит за непрозрачный квадрат (его правая граница u = 0.61)
        org.junit.Assert.assertTrue((0 until m.count).any { m.u(it) > 0.63f })
        // и слева от квадрата есть вершины левее 0.40
        org.junit.Assert.assertTrue((0 until m.count).any { m.u(it) < 0.39f })
    }

    @org.junit.Test fun gradientAngleRotatesTheAxis() {
        val e0 = com.vibetoon.data.TextRenderer.gradientEnds(0f, 300f, 100f)
        org.junit.Assert.assertTrue(e0[0] < e0[2]); org.junit.Assert.assertEquals(e0[1], e0[3], 1e-3f)       // слева направо
        val e90 = com.vibetoon.data.TextRenderer.gradientEnds(90f, 300f, 100f)
        org.junit.Assert.assertEquals(e90[0], e90[2], 1e-2f); org.junit.Assert.assertTrue(e90[1] < e90[3])   // сверху вниз
        val e45 = com.vibetoon.data.TextRenderer.gradientEnds(45f, 300f, 100f)
        org.junit.Assert.assertTrue(e45[0] < e45[2] && e45[1] < e45[3])                                     // по диагонали
        val e180 = com.vibetoon.data.TextRenderer.gradientEnds(180f, 300f, 100f)
        org.junit.Assert.assertTrue(e180[0] > e180[2])                                                      // справа налево
    }
}

class ArapTest {
    private val mesh = com.vibetoon.domain.anim.MeshMath.generate(100, 400, 12)   // вертикальная «прядь» 100x400

    private fun dist(a: FloatArray, i: Int, j: Int) = Math.hypot((a[i * 2] - a[j * 2]).toDouble(), (a[i * 2 + 1] - a[j * 2 + 1]).toDouble())

    @org.junit.Test fun arapRotatesStrandAndPreservesLength() {
        val n = mesh.count
        val q = FloatArray(n * 2) { if (it % 2 == 0) mesh.u(it / 2) * 100f else mesh.v(it / 2) * 400f }
        val sys = com.vibetoon.domain.anim.ArapSystem(q, mesh.tris)
        // корень (вершина вверху) закреплён, кончик (вершина внизу) уводится по дуге вправо
        var root = 0; var tip = 0
        for (k in 0 until n) { if (mesh.v(k) < mesh.v(root)) root = k; if (mesh.v(k) > mesh.v(tip)) tip = k }
        val len0 = dist(q, root, tip)
        val ang = Math.toRadians(40.0)
        val tx = q[root * 2] + (len0 * Math.sin(ang)).toFloat(); val ty = q[root * 2 + 1] + (len0 * Math.cos(ang)).toFloat()
        val p = sys.solve(intArrayOf(root, tip), floatArrayOf(q[root * 2], q[root * 2 + 1], tx, ty), null)
        // длина прядки сохраняется (дуга, а не растяжение): расстояние до пинов не больше исходного + небольшой допуск
        org.junit.Assert.assertEquals(len0, dist(p, root, tip), 1.0)
        // середина прядки сместилась вправо, но меньше кончика (изгиб)
        var mid = 0; var bd = 1e9f
        for (k in 0 until n) { val d = Math.abs(mesh.v(k) - 0.5f) + Math.abs(mesh.u(k) - 0.5f); if (d < bd) { bd = d; mid = k } }
        val shiftMid = p[mid * 2] - q[mid * 2]; val shiftTip = p[tip * 2] - q[tip * 2]
        org.junit.Assert.assertTrue("mid=$shiftMid tip=$shiftTip", shiftMid > 5f && shiftMid < shiftTip)
        // ни одна вершина не NaN
        org.junit.Assert.assertTrue(p.all { it.isFinite() })
    }

    @org.junit.Test fun twoPinsNormalizedWeightsDoNotStickToBackground() {
        // два пина (корень без смещения, кончик со смещением): вершина посередине получает примерно половину, а не ноль
        val pins = listOf(com.vibetoon.domain.anim.Pin(0.5f, 0.05f, false, 600f), com.vibetoon.domain.anim.Pin(0.5f, 0.95f, false, 600f))
        val off = floatArrayOf(0f, 0f, 0.3f, 0f)
        val tmp = com.vibetoon.domain.anim.PuppetMath.fieldAt(0.5f, 0.5f, pins, off, 100f, 400f)
        org.junit.Assert.assertEquals(0.15f, tmp[0], 0.02f)       // 0.5 * 0 + 0.5 * 0.3
    }

    @org.junit.Test fun puppetOffsetsStayAtRestFarFromPins() {
        val pins = listOf(com.vibetoon.domain.anim.Pin(0.5f, 0.9f, false, 120f))
        val r = com.vibetoon.domain.anim.PuppetMath.addTo(mesh, FloatArray(mesh.count * 2), pins, floatArrayOf(0.4f, 0f), 100f, 400f)
        var far = -1
        for (k in 0 until mesh.count) if (mesh.v(k) < 0.3f) far = k
        org.junit.Assert.assertEquals(0f, r[far * 2], 1e-4f)
        org.junit.Assert.assertTrue(r.all { it.isFinite() })
    }
}

class ContourQualityTest {
    private fun strip(aw: Int, ah: Int): ByteArray = ByteArray(aw * ah) { i ->
        val x = i % aw; val y = i / aw
        if (x in aw / 3..aw * 2 / 3 && y in 10 until ah - 10) 255.toByte() else 0
    }

    @org.junit.Test fun narrowTallStripGetsReasonableMesh() {
        val m = com.vibetoon.domain.anim.MeshMath.fromContour(100, 400, strip(100, 400), 100, 400, 8)!!
        org.junit.Assert.assertTrue("слишком много вершин: ${m.count}", m.count in 20..250)
        // нет «ниток»: отношение сторон треугольников в пикселях слоя не экстремальное
        var bad = 0
        for (t in 0 until m.tris.size / 3) {
            val ids = intArrayOf(m.tris[t * 3], m.tris[t * 3 + 1], m.tris[t * 3 + 2])
            val len = FloatArray(3) { k ->
                val a = ids[k]; val b = ids[(k + 1) % 3]
                Math.hypot(((m.u(a) - m.u(b)) * 100f).toDouble(), ((m.v(a) - m.v(b)) * 400f).toDouble()).toFloat()
            }
            if (len.max() / len.min().coerceAtLeast(0.5f) > 8f) bad++
        }
        org.junit.Assert.assertTrue("слишком много вытянутых треугольников: $bad из ${m.tris.size / 3}", bad < m.tris.size / 3 / 5)
    }

    @org.junit.Test fun wideShapeAlsoStaysBounded() {
        val a = ByteArray(300 * 200) { i -> val x = i % 300 - 150; val y = i / 300 - 100; if (x * x / 140f / 140f + y * y / 90f / 90f < 1f) 255.toByte() else 0 }
        val m = com.vibetoon.domain.anim.MeshMath.fromContour(600, 400, a, 300, 200, 10)!!
        org.junit.Assert.assertTrue(m.count in 30..400)
    }
}
