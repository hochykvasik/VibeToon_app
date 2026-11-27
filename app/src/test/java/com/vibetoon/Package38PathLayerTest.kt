package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.anim.PathFollow
import com.vibetoon.domain.anim.PathLayerMath
import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.anim.PuppetMath
import com.vibetoon.domain.anim.TrackGroups
import com.vibetoon.domain.anim.TurbMath
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneOptions
import com.vibetoon.domain.vector.VecKind
import com.vibetoon.domain.vector.VectorShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 38: слой-путь - кривая, положение на пути, ориентация, анимация положения и сохранение. */
class Package38PathLayerTest {
    private val eps = 1e-3f
    private val line = PathLayerMath.Path(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f), false)

    @Test fun progressTrackIdsDoNotCollide() {
        val id = PathLayerMath.progressId(MEDIA_ID_BASE + 5)
        assertTrue(PathLayerMath.isProgressId(id))
        assertEquals(MEDIA_ID_BASE + 5, PathLayerMath.layerOf(id))
        assertFalse(PathLayerMath.isProgressId(PuppetMath.pinId(MEDIA_ID_BASE + 5, 3)))
        assertFalse(PuppetMath.isPinId(PathLayerMath.progressId(MEDIA_ID_BASE + 5)))
        assertTrue(PuppetMath.isPinId(PuppetMath.pinId(MEDIA_ID_BASE + 5, 3)))
        assertFalse(PathLayerMath.isProgressId(MeshMath.vertexId(MEDIA_ID_BASE + 5, 3)))
        assertFalse(PathLayerMath.isProgressId(PerspectiveMath.pointId(MEDIA_ID_BASE + 5, 3)))
        assertFalse(PathLayerMath.isProgressId(TurbMath.paramId(MEDIA_ID_BASE + 5, 1)))
        assertTrue(id < 1_500_000_000)                                  // ниже id текстур форм символов
    }

    @Test fun placeAlongOpenPath() {
        assertEquals(200f, line.length, eps)
        val a = PathLayerMath.place(line, 0f); assertEquals(0f, a.x, eps); assertEquals(0f, a.angle, eps)
        val m = PathLayerMath.place(line, 25f); assertEquals(50f, m.x, eps); assertEquals(0f, m.y, eps)
        val c = PathLayerMath.place(line, 75f); assertEquals(100f, c.x, eps); assertEquals(50f, c.y, eps)
        assertEquals((Math.PI / 2).toFloat(), c.angle, eps)             // вниз по экрану - +90°
        val end = PathLayerMath.place(line, 140f); assertEquals(100f, end.y, eps)   // открытый путь: за концом - конец
        val start = PathLayerMath.place(line, -20f); assertEquals(0f, start.x, eps)
        val nan = PathLayerMath.place(line, Float.NaN); assertEquals(0f, nan.x, eps)
    }

    @Test fun closedPathWrapsForLoops() {
        val sq = PathLayerMath.path(VectorShape(VecKind.RECT, listOf(0f, 0f, 100f, 100f)))!!
        assertTrue(sq.closed)
        assertEquals(400f, sq.length, eps)
        val p10 = PathLayerMath.place(sq, 10f); val p110 = PathLayerMath.place(sq, 110f)
        assertEquals(p10.x, p110.x, eps); assertEquals(p10.y, p110.y, eps)
        val back = PathLayerMath.place(sq, 87.5f)                           // последний отрезок: от (0,100) к (0,0)
        assertEquals(0f, back.x, eps); assertEquals(50f, back.y, eps)
        val circle = PathLayerMath.path(PathLayerMath.preset("circle", 200f, 200f))!!
        assertTrue(circle.closed)
        assertEquals(2 * Math.PI.toFloat() * 60f, circle.length, 1f)
    }

    @Test fun shapesPresetsAndStrokes() {
        val pen = VectorShape(VecKind.POLY, listOf(0f, 0f, 100f, 0f), handles = listOf(0f, 0f, 0f, 50f, 0f, 50f, 0f, 0f))
        assertTrue(PathLayerMath.path(pen)!!.length > 100f)                  // ручки Безье выгибают путь
        assertNull(PathLayerMath.path(VectorShape(VecKind.PRESET, listOf(0f, 0f, 10f, 10f), preset = "STAR")))
        assertNull(PathLayerMath.path(VectorShape(VecKind.LINE, listOf(5f, 5f, 5f, 5f))))
        for (k in PathLayerMath.PRESETS) assertNotNull(k, PathLayerMath.path(PathLayerMath.preset(k, 1000f, 600f)))
        assertNull(PathLayerMath.fromStroke(listOf(0f, 0f, 3f, 0f)))         // тап - не путь
        val s = PathLayerMath.fromStroke((0..40).flatMap { listOf(it * 10f, if (it % 2 == 0) 0f else 1f) })
        assertNotNull(s)
        val p = PathLayerMath.path(s!!)!!
        assertEquals(0f, p.pts[0], eps); assertEquals(400f, p.pts[p.pts.size - 2], eps)   // концы на месте
        val b = PathLayerMath.bounds(line, 10f)
        assertEquals(-10f, b[0], eps); assertEquals(120f, b[2], eps)
    }

    @Test fun attachmentsAndPlaces() {
        val path = MEDIA_ID_BASE + 1
        val props = mapOf(MEDIA_ID_BASE + 2 to LayerProps(path = path, pathOrient = true), MEDIA_ID_BASE + 3 to LayerProps(path = path),
            MEDIA_ID_BASE + 4 to LayerProps(path = 77), path to LayerProps(path = path))
        val at = PathFollow.attachments(props, setOf(path))
        assertEquals(setOf(MEDIA_ID_BASE + 2, MEDIA_ID_BASE + 3), at.keys)
        val pl = PathFollow.places(at, mapOf(path to line)) { if (it == MEDIA_ID_BASE + 2) 75f else 25f }
        assertEquals((Math.PI / 2).toFloat(), pl.getValue(MEDIA_ID_BASE + 2)[2], eps)
        assertEquals(0f, pl.getValue(MEDIA_ID_BASE + 3)[2], eps)       // без «По направлению» угол не добавляется
        assertEquals(50f, pl.getValue(MEDIA_ID_BASE + 3)[0], eps)
    }

    @Test fun attachedLayerPivotSitsOnPathAndFollowsPathLayer() {
        val pathId = MEDIA_ID_BASE + 1
        val car = MEDIA_ID_BASE + 2
        val media = listOf(MediaItem(pathId, MediaKind.PATH, "Путь", "", x = 0f, y = 0f, w = 100f, h = 100f, vectors = listOf(VectorShape(VecKind.PATH, listOf(0f, 0f, 100f, 0f, 100f, 100f)))))
        val opt = SceneOptions.capture(media, mapOf(car to LayerProps(path = pathId, pathOrient = true)))
        assertTrue(pathId in opt.nullIds)
        val roots = listOf(LeafNode(pathId, "Путь", true, 1f, "norm", false, 0, 0, 100, 100), LeafNode(car, "car", true, 1f, "norm", false, 0, 0, 20, 10))
        val links = mapOf(car to pathId)
        fun build(percent: Float, t: Map<Int, LayerTransform> = emptyMap()) = SceneBuilder.buildTree(roots, 400, 400, t, Affine2D.IDENTITY,
            links = links, nullIds = opt.nullIds, pathPlaces = opt.pathPlaces { percent })
        fun center(q: FloatArray) = ((q[0] + q[4]) / 2f) to ((q[1] + q[5]) / 2f)
        val c25 = center(build(25f).selectionQuads.getValue(car))
        assertEquals(50f, c25.first, eps); assertEquals(0f, c25.second, eps)
        val q75 = build(75f).selectionQuads.getValue(car)
        assertEquals(100f, center(q75).first, eps); assertEquals(50f, center(q75).second, eps)
        assertEquals(100f + 5f, q75[0], eps)                            // повернут на 90°: левый верхний угол (-10,-5) -> (5,-10)
        assertEquals(50f - 10f, q75[1], eps)
        // сдвиг слоя-пути везёт прикреплённый слой; собственный X/Y слоя - смещение от точки пути
        val moved = center(build(25f, mapOf(pathId to LayerTransform(dx = 40f), car to LayerTransform(dy = 7f))).selectionQuads.getValue(car))
        assertEquals(90f, moved.first, eps); assertEquals(7f, moved.second, eps)
        assertTrue(build(25f).ops.toList().chunked(4).none { it[1] == pathId })   // слой-путь не рисуется
    }

    @Test fun progressIsAnimatedTrackOfTheLayer() {
        val car = MEDIA_ID_BASE + 2
        val pid = PathLayerMath.progressId(car)
        val anim = Animation().withKey(pid, 0, LayerTransform(dx = 0f)).withKey(pid, 10, LayerTransform(dx = 100f))
        assertEquals(50f, anim.evaluate(pid, 5, null).dx, eps)
        assertTrue(pid in TrackGroups.ids(car, anim.tracks))            // ключи положения - ключи слоя на таймлайне
        assertEquals(setOf(0, 10), TrackGroups.frames(car, anim.tracks))
        assertEquals(PathLayerMath.progressId(car + 1), TrackGroups.remap(pid, car, car + 1))
    }

    @Test fun pathLayerSurvivesSave() {
        val shape = VectorShape(VecKind.PATH, listOf(0f, 0f, 50f, 20f, 100f, 0f), 0xFF42A5F5L, 0L, 4f)
        val d = ProjectData(ProjectMeta("p", "P", 100, 100), media = listOf(MediaItem(MEDIA_ID_BASE + 1, MediaKind.PATH, "Путь 1", "", x = 1f, y = 2f, w = 3f, h = 4f, vectors = listOf(shape))),
            base = mapOf(PathLayerMath.progressId(MEDIA_ID_BASE + 2) to LayerTransform(dx = 33f)))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        assertEquals(d.media, back.media)
        assertEquals(33f, back.base.getValue(PathLayerMath.progressId(MEDIA_ID_BASE + 2)).dx, eps)
        assertTrue(back.media.single().isRig)
        assertFalse(MediaItem(1, MediaKind.IMAGE, "a", "").isRig)
    }

    @Test fun quadMapRecoversLayerWorld() {
        // рамка 100×50 в (10, 20), сдвинутая на (5, 7) и повёрнутая на 90°: точка пути попадает туда же, куда и рамка
        val w = com.vibetoon.domain.model.Affine2D.translate(5f, 7f) * com.vibetoon.domain.model.Affine2D(0f, 1f, -1f, 0f, 0f, 0f)
        val q = floatArrayOf(w.applyX(10f, 20f), w.applyY(10f, 20f), w.applyX(110f, 20f), w.applyY(110f, 20f),
            w.applyX(110f, 70f), w.applyY(110f, 70f), w.applyX(10f, 70f), w.applyY(10f, 70f))
        val m = PathLayerMath.quadMap(q, 10f, 20f, 100f, 50f)!!
        assertEquals(w.applyX(42f, 33f), m.applyX(42f, 33f), 1e-3f)
        assertEquals(w.applyY(42f, 33f), m.applyY(42f, 33f), 1e-3f)
        assertNull(PathLayerMath.quadMap(FloatArray(8), 0f, 0f, 10f, 10f))
        assertNull(PathLayerMath.quadMap(q, 0f, 0f, 0f, 10f))
    }
}
