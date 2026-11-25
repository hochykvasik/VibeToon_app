package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.model.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

/** Спринт 43: каскад сеток, кэш деформаций, нагрузка, поток ключей волос, деформер-путь, ключи слоя. */
class Sprint43Test {
    private fun leaf(id: Int, x: Int, y: Int = 0, w: Int = 100, h: Int = 100) = LeafNode(id, "l$id", true, 1f, "norm", false, x, y, w, h)
    private fun t(dx: Float = 0f, dy: Float = 0f) = LayerTransform(dx = dx, dy = dy)
    private fun rnd(seed: Int, n: Int, amp: Float): FloatArray { val r = java.util.Random(seed.toLong()); return FloatArray(n) { (r.nextFloat() - .5f) * amp } }

    /** Блок вершин сетки слоя [id] в упакованной сцене (4 float на вершину). */
    private fun meshBlock(s: SceneData, id: Int): FloatArray {
        val i = s.meshIds.indexOf(id); assertTrue("нет сетки $id", i >= 0)
        val from = (0 until i).sumOf { s.meshCounts[it * 2] } * 4
        return s.meshVerts.copyOfRange(from, from + s.meshCounts[i * 2] * 4)
    }

    // ---------------------------------------------------------------- 1. каскад и изоляция

    @Test fun smoothPlanIsBitIdenticalToLoopSmoothing() {
        val mesh = MeshMath.generate(120, 80, 3)
        assertTrue(mesh.count > 4)
        for (levels in 0..3) {
            val off = rnd(levels, mesh.count * 2, .2f)
            val (m2, o2) = MeshMath.smooth(mesh, off, levels)
            val plan = MeshSmoothPlan.of(mesh, levels)
            assertArrayEquals("verts L$levels", m2.verts, plan.mesh.verts, 0f)
            assertArrayEquals("tris L$levels", m2.tris, plan.mesh.tris)
            assertTrue("offsets L$levels", o2.contentEquals(plan.apply(off)))   // побитово (floatToIntBits)
        }
        assertSame(MeshSmoothPlan.of(mesh, 2), MeshSmoothPlan.of(mesh, 2))   // топология считается один раз
    }

    @Test fun deformStackIsTotal() {
        val bounds = GroupDeformationMesh.Bounds(0f, 0f, 100f, 100f)
        val pole = floatArrayOf(1f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, -.25f)
        val nan = FloatArray(32) { if (it == 3) Float.NaN else .1f }
        val world = Affine2D.scale(100f, 100f)
        val far = SceneBuilder.GroupDeformation(GroupDeformationMesh.Bounds(1e6f, 1e6f, 1f, 1f), nan, pole, 2)
        val level = DeformStack.GroupLevel(far, Affine2D.IDENTITY, Affine2D.IDENTITY)
        for (input in listOf(
            DeformStack.Input(null, null, 0, nan, null, pole, emptyList(), world),
            DeformStack.Input(null, null, 0, null, null, null, listOf(level), world),
            DeformStack.Input(MeshMath.generate(50, 50, 2), FloatArray(10) { Float.POSITIVE_INFINITY }, 3, nan, null, pole, listOf(level), null),
            DeformStack.Input(null, null, 0, null, null, null, listOf(DeformStack.GroupLevel(SceneBuilder.GroupDeformation(bounds, FloatArray(32), null, 2), world, world)), null, cells = 0)
        )) {
            val r = DeformStack.compose(input)
            assertTrue(r.mesh.count > 0)
            assertEquals(r.mesh.count * 2, r.offsets.size)
            assertTrue(r.offsets.all { it.isFinite() })
        }
    }

    /** Сценарий с планшета: провод, белок, зрачок, ресницы (глаз и провод анимированы), затем сетка челюсти на голове. */
    private val wire = leaf(1, -300); private val white = leaf(2, 0); private val pupil = leaf(3, 10, 10, 40, 40)
    private val lashes = leaf(4, 0, -20, 100, 30); private val headLeaf = leaf(5, -50, 50, 200, 200)
    private val eye = GroupNode(20, "eye", true, 1f, "pass", listOf(white, pupil, lashes), 0f, 0f)
    private val head = GroupNode(10, "head", true, 1f, "pass", listOf(headLeaf, eye), 0f, 0f)
    private val roots = listOf(wire, head)
    private val m4 = MeshMath.generate(100, 100, 2)
    private fun meshes(frame: Int, jaw: Boolean): Map<Int, Pair<Mesh, FloatArray>> {
        val out = linkedMapOf(
            1 to (m4 to rnd(100 + frame, m4.count * 2, .1f)),
            2 to (m4 to rnd(200 + frame, m4.count * 2, .05f)),
            3 to (m4 to rnd(300 + frame, m4.count * 2, .05f)),
            4 to (m4 to rnd(400 + frame, m4.count * 2, .05f)))
        if (jaw) out[5] = m4 to rnd(500, m4.count * 2, .1f)
        return out
    }
    private val headWarp = mapOf(10 to SceneBuilder.GroupDeformation(GroupDeformationMesh.Bounds(-50f, -20f, 200f, 270f), FloatArray(32) { if (it % 2 == 0) .02f else 0f }, cells = 4))

    @Test fun newJawMeshDoesNotChangeOrDisableEyeAndWire() {
        val cache = DeformCache()
        val before = SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(7, false), groupDeformations = headWarp, warps = mapOf(1 to FloatArray(32) { .01f }), deformCache = cache)
        assertEquals(setOf(1, 2, 3, 4, 5), before.meshIds.toSet())   // голова под групповым Warp - тоже сетка
        val after = SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(7, true), groupDeformations = headWarp, warps = mapOf(1 to FloatArray(32) { .01f }), deformCache = cache)
        assertEquals(setOf(1, 2, 3, 4, 5), after.meshIds.toSet())
        for (id in 1..4) assertArrayEquals("слой $id не изменился", meshBlock(before, id), meshBlock(after, id), 0f)
        assertFalse(meshBlock(before, 5).contentEquals(meshBlock(after, 5)))   // челюсть - своя сетка
        assertTrue(after.meshVerts.all { it.isFinite() })
        // без кэша - тот же результат (кэш не меняет картинку)
        val plain = SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(7, true), groupDeformations = headWarp, warps = mapOf(1 to FloatArray(32) { .01f }))
        assertArrayEquals(plain.meshVerts, after.meshVerts, 0f)
    }

    @Test fun deformCacheHitsUnchangedLayersAndRetainsOnlySceneLayers() {
        val cache = DeformCache()
        SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(1, true), groupDeformations = headWarp, deformCache = cache)
        val m0 = cache.misses
        assertEquals(5, cache.size())
        // тот же кадр (скраббинг туда-обратно) - всё из кэша
        SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(1, true), groupDeformations = headWarp, deformCache = cache)
        assertEquals(m0, cache.misses); assertEquals(5, cache.hits)
        // другой кадр: анимированы провод и глаз, челюсть стоит - пересчитываются только изменённые слои
        SceneBuilder.buildTree(roots, 400, 400, meshes = meshes(2, true), groupDeformations = headWarp, deformCache = cache)
        assertEquals(m0 + 4, cache.misses); assertEquals(6, cache.hits)
        // слой удалён из сцены - его запись освобождается
        SceneBuilder.buildTree(listOf(wire), 400, 400, meshes = mapOf(1 to (m4 to FloatArray(m4.count * 2))), deformCache = cache)
        assertEquals(1, cache.size())
        cache.clear(); assertEquals(0, cache.size()); assertEquals(0, cache.hits)
    }

    @Test fun meshEverywhereInsideDeformedGroupsComposes() {
        // прежние отказы: сетка/Warp/перспектива/плотный Warp у слоя внутри групповых деформеров, вложенные группы
        val inner = GroupNode(11, "inner", true, 1f, "pass", listOf(leaf(2, 0), leaf(3, 50)), 10f, 0f)
        val outer = GroupNode(10, "outer", true, 1f, "pass", listOf(leaf(1, -100), inner), 0f, 0f)
        val gd = SceneBuilder.GroupDeformation(GroupDeformationMesh.Bounds(-100f, 0f, 260f, 100f), FloatArray(32) { if (it % 2 == 0) .1f else 0f }, PerspectiveMath.matrix(floatArrayOf(0f, 0f, .1f, 0f, .1f, .1f, 0f, 0f)), 3)
        val dense = DenseWarpGrid.zero(8, 8)!!
        val s = SceneBuilder.buildTree(listOf(outer), 300, 300,
            meshes = mapOf(1 to (m4 to rnd(1, m4.count * 2, .1f))), warps = mapOf(2 to FloatArray(32) { .05f }, 3 to FloatArray(32)),
            persp = mapOf(2 to PerspectiveMath.matrix(floatArrayOf(0f, 0f, .1f, 0f, 0f, 0f, 0f, 0f))),
            denseWarps = mapOf(3 to dense), groupDeformations = mapOf(10 to gd, 11 to gd))
        assertEquals(setOf(1, 2, 3), s.meshIds.toSet())
        assertTrue(s.meshVerts.all { it.isFinite() })
        assertTrue(s.warpIds.isEmpty() && s.perspIds.isEmpty())   // запечено в сетку - не применяется дважды
        assertTrue((0 until s.meshIds.size).all { s.meshCounts[it * 2] in 3..65535 })
    }

    // ---------------------------------------------------------------- 2. нагрузка Play/скраббинга

    @Test fun heavySceneScalesLinearlyAndUnchangedFramesComeFromCache() {
        val n = 160
        val leaves = (1..n).map { leaf(it, (it % 16) * 60, (it / 16) * 60, 60, 60) }
        val groups = (0 until 8).map { g -> GroupNode(1000 + g, "g$g", true, 1f, "pass", leaves.subList(g * 20, g * 20 + 20), 0f, 0f) }
        val defs = groups.associate { it.id to SceneBuilder.GroupDeformation(GroupDeformationMesh.Bounds(0f, 0f, 1000f, 700f), FloatArray(32) { k -> if (k % 2 == 0) .01f else 0f }, cells = 6) }
        val mesh = MeshMath.generate(60, 60, 3)
        val fx = (1..n step 3).associateWith { listOf(FloatArray(10) { k -> k.toFloat() }) }
        fun frame(f: Int) = (1..n).associateWith { id -> mesh to (if (id % 10 == 0) rnd(id * 31 + f, mesh.count * 2, .05f) else FloatArray(mesh.count * 2)) }
        val cache = DeformCache()
        SceneBuilder.buildTree(groups, 1000, 700, meshes = frame(0), groupDeformations = defs, fx = fx, deformCache = cache)
        val warm = cache.misses
        val t0 = System.nanoTime()
        for (f in 1..12) {
            val s = SceneBuilder.buildTree(groups, 1000, 700, meshes = frame(f), groupDeformations = defs, fx = fx, deformCache = cache)
            assertEquals(n, s.meshIds.size)
        }
        val ms = (System.nanoTime() - t0) / 1e6
        // на кадре пересчитываются только 16 анимированных слоев из 160
        assertEquals(warm + 12 * (n / 10), cache.misses)
        assertTrue("12 кадров тяжёлой сцены: $ms мс", ms < 20_000)
    }

    @Test fun hotPathNodeIdsExcludeDeformerPoints() {
        assertTrue(SceneHotPath.isNodeId(0)); assertTrue(SceneHotPath.isNodeId(150_000)); assertTrue(SceneHotPath.isNodeId(900_001))
        assertFalse(SceneHotPath.isNodeId(-5))
        assertFalse(SceneHotPath.isNodeId(WarpMath.pointId(3, 2)))
        assertFalse(SceneHotPath.isNodeId(MeshMath.vertexId(3, 7)))
        assertFalse(SceneHotPath.isNodeId(PuppetMath.pinId(3, 1)))
        assertFalse(SceneHotPath.isNodeId(PerspectiveMath.pointId(3, 1)))
        assertFalse(SceneHotPath.isNodeId(PathLayerMath.progressId(3)))
    }

    @Test fun trackIndexMatchesFullScanAndRefreshesOnNewAnimation() {
        var a = Animation()
        val layers = listOf(0, 1, 5, 42, 977)
        for (l in layers) {
            a = a.withKey(l, 3, t(1f))
            a = a.withKey(MeshMath.vertexId(l, l % 100), 4, t(.1f))
            a = a.withKey(WarpMath.pointId(l, 5), 5, t(.1f))
            a = a.withKey(PuppetMath.pinId(l, 2), 6, t(.1f))
            a = a.withKey(PerspectiveMath.pointId(l, 1), 7, t(.1f))
            a = a.withKey(TurbMath.paramId(l, 2), 8, t(.1f))
            a = a.withKey(PathLayerMath.progressId(l), 9, t(.1f))
            a = a.withKey(EffectTracks.id(l, 1, 2), 10, t(.1f))
        }
        for (l in layers + listOf(2, 3, 600)) assertEquals("слой $l", TrackGroups.idsFull(l, a.tracks).toSet(), TrackGroups.ids(l, a.tracks).toSet())
        assertEquals(setOf(3, 4, 5, 6, 7, 8, 9, 10), TrackGroups.frames(42, a.tracks))
        val b = a.withKey(MeshMath.vertexId(2, 0), 11, t(.2f))
        assertEquals(setOf(11), TrackGroups.frames(2, b.tracks))   // новая карта дорожек - индекс пересобран
        assertEquals(emptySet<Int>(), TrackGroups.frames(2, a.tracks))
        // нагрузка: 300 слоев x 40 дорожек - выбор всех ключей слоя без полного перебора id
        var big = Animation()
        val tracks = HashMap<Int, AnimationTrack>()
        for (l in 0 until 300) for (k in 0 until 40) { val id = MeshMath.vertexId(l, k); tracks[id] = AnimationTrack(id, listOf(Keyframe.of(k, t(.1f), EasingType.LINEAR))) }
        big = big.copy(tracks = tracks)
        val t0 = System.nanoTime()
        for (l in 0 until 300) assertEquals(40, TrackGroups.ids(l, big.tracks).size)
        assertTrue((System.nanoTime() - t0) / 1e6 < 5_000)
    }

    // ---------------------------------------------------------------- 3. волосы: ключи до/после правки, Undo/Redo

    private val hair = 7
    private fun verts(n: Int = 4) = (0 until n).map { MeshMath.vertexId(hair, it) }

    @Test fun keyBeforeShapeChangeKeepsBaselineAndInterpolates() {
        val history = ArrayList<Animation>()
        var a = Animation(); history += a
        // кадр 10: сначала «Add Key» (форма ещё не менялась)
        a = LayerKeyFlow.addKey(a, hair, 10, LayerTransform(), LayerKeyFlow.deformerGroups(hair, 4, 0, 0, false)); history += a
        assertTrue(LayerKeyFlow.hasKey(a, hair, 10))
        assertTrue("базовый ключ на кадре 0", a.hasKey(verts()[0], 0))
        // потом изгиб пряди на кадре 10
        a = DeformAutoKey.key(a, verts(), 10, mapOf(verts()[3] to t(.3f, .1f))); history += a
        assertEquals(0f, a.evaluate(verts()[3], 0, null).dx, 0f)
        assertEquals(.3f, a.evaluate(verts()[3], 10, null).dx, 1e-6f)
        assertEquals(.15f, a.evaluate(verts()[3], 5, null).dx, 1e-4f)
        // скраббинг вперёд-назад и Play: форма возвращается точно
        for (f in listOf(10, 0, 7, 10, 3, 10)) assertEquals(if (f == 10) .3f else .03f * f, a.evaluate(verts()[3], f, null).dx, 1e-4f)
        // Undo / Redo
        val undone = history[history.size - 2]
        assertEquals(0f, undone.evaluate(verts()[3], 10, null).dx, 0f)
        assertEquals(.3f, history.last().evaluate(verts()[3], 10, null).dx, 1e-6f)
    }

    @Test fun shapeChangeThenEditOnAnotherFrameCommitsNewKey() {
        var a = Animation()
        a = DeformAutoKey.key(a, verts(), 12, mapOf(verts()[2] to t(.2f)))          // правка без ключа на кадре 12
        assertTrue(a.hasKey(verts()[2], 0) && a.hasKey(verts()[2], 12))
        val beforeSecond = a
        a = DeformAutoKey.key(a, verts(), 24, mapOf(verts()[2] to t(-.1f)))         // правка на другом кадре
        assertTrue(a.hasKey(verts()[2], 24))
        assertEquals(.2f, a.evaluate(verts()[2], 12, null).dx, 1e-6f)                // прежний ключ не потерян
        assertEquals(-.1f, a.evaluate(verts()[2], 30, null).dx, 1e-6f)
        // ключ ПОСЛЕ правки (Add Key на кадре без ключа) фиксирует текущую форму, а не сбрасывает
        val keyed = LayerKeyFlow.addKey(a, hair, 18, LayerTransform(), LayerKeyFlow.deformerGroups(hair, 4, 0, 0, false))
        assertEquals(a.evaluate(verts()[2], 18, null).dx, keyed.evaluate(verts()[2], 18, null).dx, 1e-6f)
        assertTrue(keyed.hasKey(verts()[2], 18) && keyed.hasKey(verts()[0], 18))
        // Undo второй правки
        assertFalse(beforeSecond.hasKey(verts()[2], 24))
    }

    @Test fun transformEditOnAnimatedPropertyCommitsKeyAndScrubReturns() {
        var a = Animation().withKey(hair, 0, LayerTransform(dx = 0f)).withKey(hair, 20, LayerTransform(dx = 100f))
        val edited = LayerKeyFlow.transformEdit(a, hair, 10, a.evaluate(hair, 10, null), LayerTransform(dx = 80f), emptyList(), auto = false, canKey = true)
        assertNotNull("правка на кадре без ключа больше не временная", edited)
        a = edited!!
        assertEquals(80f, a.evaluate(hair, 10, null).dx, 1e-4f)
        assertEquals(0f, a.evaluate(hair, 0, null).dx, 1e-4f)
        assertEquals(100f, a.evaluate(hair, 20, null).dx, 1e-4f)
        // ретайминг клипа: только существующий ключ
        assertNull(LayerKeyFlow.transformEdit(Animation().withKey(hair, 0, LayerTransform()), hair, 5, LayerTransform(), LayerTransform(dx = 1f), emptyList(), auto = false, canKey = false))
        // автоключ первой правки на кадре > 0 ставит базовый ключ на кадре 0
        val auto = LayerKeyFlow.transformEdit(Animation(), hair, 15, LayerTransform(), LayerTransform(dx = 5f), emptyList(), auto = true, canKey = true)!!
        assertTrue(auto.hasKey(hair, 0) && auto.hasKey(hair, 15))
        // неанимированное свойство без автоключа - в base
        assertNull(LayerKeyFlow.transformEdit(Animation(), hair, 15, LayerTransform(), LayerTransform(dx = 5f), emptyList(), auto = false, canKey = true))
    }

    @Test fun deleteKeyKeepsShapeAndCoversAllTracks() {
        val v = MeshMath.vertexId(hair, 1); val p = PuppetMath.pinId(hair, 0)
        val a = Animation().withKey(v, 10, t(.2f)).withKey(p, 10, t(.05f)).withKey(hair, 10, LayerTransform(dx = 3f))
        val base = mapOf(v to t(.1f))
        val (next, updates) = LayerKeyFlow.removeKey(a, hair, 10, base)
        assertFalse(LayerKeyFlow.hasKey(next, hair, 10))
        assertEquals(.3f, updates.getValue(v).dx, 1e-6f)     // форма привязки + последнее смещение
        assertEquals(.05f, updates.getValue(p).dx, 1e-6f)
        assertEquals(3f, updates.getValue(hair).dx, 0f)      // трансформация - значением
        assertTrue(LayerKeyFlow.isShapeDelta(WarpMath.pointId(hair, 0)) && !LayerKeyFlow.isShapeDelta(hair))
    }

    @Test fun manualKeyCoversEveryDeformerOfLayer() {
        val groups = LayerKeyFlow.deformerGroups(hair, 6, 3, 16, true)
        assertEquals(4, groups.size)
        val a = LayerKeyFlow.addKey(Animation(), hair, 8, LayerTransform(), groups)
        for (g in groups) for (id in g) { assertTrue(a.hasKey(id, 8)); assertTrue(a.hasKey(id, 0)) }
        assertTrue(a.hasKey(hair, 8))
        // повторное нажатие на кадре с ключом ничего не портит
        assertEquals(a.tracks.keys, LayerKeyFlow.addKey(a, hair, 8, null, groups).tracks.keys)
    }

    @Test fun vertexDragUnderPinsLandsUnderFinger() {
        // показанная = своя .1 + добавка пинов .25; палец в .9 при исходной .5 -> своё смещение .15
        assertEquals(.15f, LayerKeyFlow.vertexTarget(.9f, .5f, .35f, .1f), 1e-6f)
    }

    // ---------------------------------------------------------------- 4. деформер-путь (Live2D Path Deformer)

    private val strand = MeshMath.generate(40, 400, 24)
    private fun pins(n: Int, rootOutside: Boolean) = (0 until n).map { k ->
        val v = if (rootOutside) -0.1f + 1.05f * k / (n - 1) else 0.05f + .9f * k / (n - 1)
        Pin(.5f, v, radius = 120f)
    }

    @Test fun pathBindingStoresFractionAndNormalOffsetAndRebuildsExactlyAtRest() {
        val q = FloatArray(strand.count * 2) { if (it % 2 == 0) strand.u(it / 2) * 40f else strand.v(it / 2) * 400f }
        val rest = PathMath.build(floatArrayOf(20f, 0f, 20f, 200f, 20f, 400f))!!
        val b = PathDeformer.bind(q, strand.tris, rest, 60f)
        assertTrue(b.t.all { it.isFinite() } && b.d.all { it.isFinite() })
        val top = (0 until strand.count).minByOrNull { strand.v(it) }!!; val bottom = (0 until strand.count).maxByOrNull { strand.v(it) }!!
        assertEquals(0f, b.t[top], .02f); assertEquals(1f, b.t[bottom], .02f)
        val p = PathDeformer.deform(q, b, rest)
        for (i in q.indices) assertEquals(q[i], p[i], .05f)                         // в покое вершины на месте
        // мягкий корень: спад влияния вдоль длины
        assertEquals(0f, PathDeformer.alongWeight(0f), 0f)
        assertTrue(PathDeformer.alongWeight(.05f) in .01f..0.6f)
        assertEquals(1f, PathDeformer.alongWeight(.5f), 0f)
        assertEquals(0f, PathDeformer.alongWeight(Float.NaN), 0f)
    }

    @Test fun movingTipBendsSmoothArcWithSoftRoot() {
        val ps = pins(4, rootOutside = true)                                     // первая точка вне сетки (в голове)
        val off = FloatArray(ps.size * 2).also { it[(ps.size - 1) * 2] = .8f; it[(ps.size - 2) * 2] = .3f }   // кончик хлещет вправо
        val out = PuppetMath.addTo(strand, FloatArray(strand.count * 2), ps, off, 40f, 400f)
        val byV = (0 until strand.count).groupBy { Math.round(strand.v(it) * 1000) }.toSortedMap()
        val rows = byV.values.map { ids -> ids.map { out[it * 2] }.average().toFloat() }
        assertTrue(rows.all { it.isFinite() })
        assertEquals(0f, rows.first(), .02f)                                     // корень стоит
        assertTrue(rows.last() > .3f)                                             // кончик ушёл
        // изгиб плавный (нет залома у корня): приращения не скачут; небольшой S-изгиб сплайна у корня допустим
        val steps = rows.zipWithNext { a, b -> b - a }
        assertTrue(rows.all { it >= -.05f })
        val jumps = steps.zipWithNext { a, b -> kotlin.math.abs(b - a) }
        assertTrue("излом: ${jumps.maxOrNull()}", jumps.max() < .12f)
        // поперёк пряди вершины одного ряда идут вместе (дуга, а не поворот вокруг точки)
        for (ids in byV.values) { val xs = ids.map { out[it * 2] }; assertTrue(xs.max() - xs.min() < .2f) }
    }

    @Test fun layerScaleKeepsNormalsAndRootTranslatesStrand() {
        val ps = pins(3, rootOutside = false)
        val off = FloatArray(6).also { it[4] = .5f }
        val a = PuppetMath.addTo(strand, FloatArray(strand.count * 2), ps, off, 40f, 400f)
        val stretched = PuppetMath.addTo(strand, FloatArray(strand.count * 2), ps, off, 40f, 400f, 3f, 1f)
        assertTrue(a.all { it.isFinite() } && stretched.all { it.isFinite() })
        // корень сдвинут - прядь переносится целиком (не «приклеена»)
        val moveRoot = FloatArray(6).also { it[0] = .2f; it[2] = .2f; it[4] = .2f }
        val shifted = PuppetMath.addTo(strand, FloatArray(strand.count * 2), ps, moveRoot, 40f, 400f)
        val mid = (0 until strand.count).first { kotlin.math.abs(strand.v(it) - .5f) < .1f && kotlin.math.abs(strand.u(it) - .5f) < .2f }
        assertEquals(.2f, shifted[mid * 2], .05f)
        assertFalse(PuppetMath.pathNeedsDenserMesh(strand, FloatArray(strand.count * 2), ps, 40f, 400f))
        val coarse = Mesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), intArrayOf(0, 1, 2, 1, 3, 2))
        assertTrue(PuppetMath.pathNeedsDenserMesh(coarse, FloatArray(8), ps, 40f, 400f))
    }

    @Test fun pathRootOutsideMeshSurvivesSaveAndLoad() {
        val meta = com.vibetoon.domain.project.ProjectMeta("p", "test", 100, 100)
        val pins = mapOf(3 to listOf(Pin(.5f, -.2f), Pin(.5f, .5f), Pin(1.3f, 1f)))
        val text = com.vibetoon.domain.project.ProjectJson.encode(com.vibetoon.domain.project.ProjectData(meta, pins = pins))
        assertEquals(pins, com.vibetoon.domain.project.ProjectJson.decode(text).getOrThrow().pins)
    }

    // ---------------------------------------------------------------- 5. ключи активного слоя

    @Test fun selectLayerKeysTakesOnlyActiveLayer() {
        val a = Animation().withKey(3, 0, LayerTransform()).withKey(MeshMath.vertexId(3, 2), 12, t(.1f))
            .withKey(EffectTracks.id(3, 0, 1), 30, t(1f)).withKey(4, 5, LayerTransform()).withKey(MeshMath.vertexId(4, 0), 7, t(.1f))
        assertEquals(setOf(3 to 0, 3 to 12, 3 to 30), LayerKeySelection.of(3, a.tracks, false, emptyList()))
        assertEquals(setOf(3 to 12), LayerKeySelection.of(3, a.tracks, false, emptyList(), 10 until 20))
        assertEquals(setOf(4 to 5, 4 to 7, 4 to 40), LayerKeySelection.of(4, a.tracks, false, listOf(40)))
        assertTrue(LayerKeySelection.of(9, a.tracks, false, emptyList()).isEmpty())
        val bone = 200_001
        val withBone = a.withKey(bone, 9, LayerTransform())
        assertEquals(setOf(bone to 9), LayerKeySelection.of(bone, withBone.tracks, true, emptyList()))
    }
}
