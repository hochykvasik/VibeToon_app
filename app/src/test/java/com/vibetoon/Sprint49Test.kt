package com.vibetoon

import com.vibetoon.domain.anim.BoneSeg
import com.vibetoon.domain.anim.Curve
import com.vibetoon.domain.anim.CurveClipboard
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.SkinEdit
import com.vibetoon.domain.anim.SkinMath
import com.vibetoon.domain.anim.UserCurves
import com.vibetoon.domain.input.BonePointPick
import com.vibetoon.domain.input.BoneSelectMode
import com.vibetoon.domain.input.KeyboardLift
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.settings.SettingsBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 49: клавиатура не сжимает холст, копирование/вставка и свои кривые, кисть сетки на слое с костями,
 * точечный выбор слоёв для привязки к кости.
 */
class Sprint49Test {
    // ---------------------------------------------------------------- 1. клавиатура

    @Test fun keyboardLiftOnlyWhileFocused() {
        assertEquals(0, KeyboardLift.lift(false, 800, 0))
        assertEquals(0, KeyboardLift.lift(true, 0, 0))
        assertEquals(800, KeyboardLift.lift(true, 800, 0))
        // низ панели выше низа окна (навигационная панель 48 px) - сдвиг меньше на этот зазор
        assertEquals(752, KeyboardLift.lift(true, 800, 48))
        // клавиатура ниже панели - панель не двигается и не уходит вниз
        assertEquals(0, KeyboardLift.lift(true, 40, 100))
        assertEquals(800, KeyboardLift.lift(true, 800, -5))
    }

    @Test fun inspectorInsetIsKeyboardAboveItsBottom() {
        assertEquals(0, KeyboardLift.panelInset(0, 48))
        assertEquals(652, KeyboardLift.panelInset(700, 48))
        assertEquals(0, KeyboardLift.panelInset(30, 48))
    }

    // ---------------------------------------------------------------- 2. кривые

    private val spring = Curve.of(EasingType.EASE_IN_OUT)

    @Test fun userCurvesRoundTripAndSkipBrokenLines() {
        val list = UserCurves.save(UserCurves.save(emptyList(), "Пружина", spring), "Отскок", Curve.of(EasingType.EASE_OUT))
        val text = UserCurves.encode(list)
        val back = UserCurves.decode(text)
        assertEquals(list, back)
        assertEquals(listOf("Пружина", "Отскок"), back.map { it.name })
        assertNotNull(back[0].curve)
        // битая строка, пустое имя и повтор имени (без учёта регистра) пропускаются
        val messy = text + "\nбез табуляции\n\t1,2,3\nсломанная\t9,9\nпружина\t" + spring.toList().joinToString(",")
        assertEquals(list, UserCurves.decode(messy))
        assertTrue(UserCurves.decode(null).isEmpty())
        assertTrue(UserCurves.decode("  ").isEmpty())
    }

    @Test fun saveSameNameOverwritesInPlace() {
        val a = UserCurves.save(UserCurves.save(emptyList(), "A", spring), "B", spring)
        val linear = Curve.LINEAR
        val b = UserCurves.save(a, "a", linear)
        assertEquals(2, b.size)
        assertEquals("a", b[0].name)
        assertEquals(linear.toList(), b[0].data)
        assertTrue(UserCurves.exists(b, "A "))
        assertFalse(UserCurves.exists(b, "C"))
        assertEquals(listOf("B"), UserCurves.delete(b, "A").map { it.name })
    }

    @Test fun saveKeepsAtMostMaxDroppingOldest() {
        var list = emptyList<UserCurves.Saved>()
        for (i in 0 until UserCurves.MAX + 3) list = UserCurves.save(list, "c$i", spring)
        assertEquals(UserCurves.MAX, list.size)
        assertEquals("c3", list.first().name)
        assertEquals("c${UserCurves.MAX + 2}", list.last().name)
        assertEquals(UserCurves.MAX, UserCurves.decode(UserCurves.encode(list + list.first())).size)
    }

    @Test fun namesAreSanitizedAndUnique() {
        assertEquals("a b", UserCurves.sanitize(" a\t\n b "))
        assertNull(UserCurves.sanitize(" \t "))
        assertEquals(UserCurves.NAME_MAX, UserCurves.sanitize("x".repeat(100))!!.length)
        val list = UserCurves.save(emptyList(), "Моя кривая 2", spring)
        assertEquals("Моя кривая 3", UserCurves.uniqueName(list))
        assertEquals("My curve 2", UserCurves.uniqueName(emptyList<UserCurves.Saved>().let { UserCurves.save(it, "x", spring) }, "My curve"))
        // пустое имя при сохранении - свободное имя по умолчанию, а не потеря кривой
        assertEquals("Моя кривая 1", UserCurves.save(emptyList(), "  ", spring)[0].name)
    }

    @Test fun userCurvesSurviveSettingsBackup() {
        val list = UserCurves.save(UserCurves.save(emptyList(), "Пружина «1»", spring), "Bounce, \"2\"", Curve.LINEAR)
        val json = SettingsBackup.encode(mapOf(UserCurves.PREF to UserCurves.encode(list), "lang" to "ru"))
        val prefs = SettingsBackup.decode(json)!!
        assertEquals(list, UserCurves.decode(prefs[UserCurves.PREF] as String))
    }

    @Test fun clipboardValidatesAndCounts() {
        assertNull(CurveClipboard.valid(null))
        assertNull(CurveClipboard.valid(listOf(1f, 2f)))
        assertEquals(spring.toList(), CurveClipboard.valid(spring.toList())!!.toList())
        assertEquals(5, CurveClipboard.pastedCount(3, 2))
        assertEquals(2, CurveClipboard.pastedCount(-1, 2))
    }

    // ---------------------------------------------------------------- 3. сетка на кости

    private fun rotZ(deg: Float, cx: Float, cy: Float) =
        Affine2D.translate(cx, cy) * Affine2D.rotate(Math.toRadians(deg.toDouble()).toFloat()) * Affine2D.translate(-cx, -cy)

    @Test fun blendMatchesSkinOfPoint() {
        val segs = listOf(BoneSeg(1, 0f, 0f, 100f, 0f), BoneSeg(2, 100f, 0f, 200f, 0f))
        val deltas = listOf(rotZ(30f, 0f, 0f), Affine2D.translate(5f, -7f))
        for ((x, y) in listOf(10f to 5f, 120f to 30f, 99f to -40f)) {
            val w = SkinMath.weights(x, y, segs)
            val b = SkinMath.blend(w, deltas)
            val (sx, sy) = SkinMath.skin(x, y, segs, deltas)
            assertEquals(sx, b.applyX(x, y), 1e-3f)
            assertEquals(sy, b.applyY(x, y), 1e-3f)
        }
        assertEquals(Affine2D.IDENTITY, SkinMath.blend(emptyList(), deltas))
    }

    @Test fun grabOffsetComposesWithBonePose() {
        // слой 200x80 в (40, 30); рукав повёрнут костью на 35° и сдвинут
        val rest = Affine2D(200f, 0f, 0f, 80f, 40f, 30f)
        val blend = rotZ(35f, 40f, 70f) * Affine2D.translate(12f, -4f)
        val m = SkinEdit.local(rest, blend)!!
        val arr = FloatArray(3 * SkinEdit.STRIDE)
        for (k in 0 until 3) SkinEdit.write(arr, k, if (k == 1) m else Affine2D.IDENTITY)
        val u0 = 0.25f; val v0 = 0.5f
        val finger = 0.61f to 0.83f
        val (ox, oy) = SkinEdit.grabOffset(arr, 1, u0, v0, finger.first, finger.second)
        // итоговая точка = M_k · (u + своё смещение) - ровно под пальцем: поза кости и правка сетки складываются
        val (fx, fy) = SkinEdit.skin(arr, 1, u0 + ox, v0 + oy)
        assertEquals(finger.first, fx, 1e-4f)
        assertEquals(finger.second, fy, 1e-4f)
        // без матрицы (слой без костей) - прежнее поведение: смещение = палец - исходная точка
        val (px, py) = SkinEdit.grabOffset(null, 1, u0, v0, finger.first, finger.second)
        assertEquals(finger.first - u0, px, 1e-6f)
        assertEquals(finger.second - v0, py, 1e-6f)
        // вне массива и вырожденная матрица - null, а не исключение
        assertNull(SkinEdit.unskin(arr, 7, 0f, 0f))
        val flat = FloatArray(SkinEdit.STRIDE)
        assertNull(SkinEdit.unskin(flat, 0, 0.5f, 0.5f))
    }

    @Test fun nearestShownVertex() {
        val pts = listOf(0f to 0f, 10f to 0f, 10f to 10f)
        assertEquals(2, SkinEdit.nearest(3, { pts[it] }, 9f, 8f))
        assertEquals(-1, SkinEdit.nearest(0, { pts[it] }, 9f, 8f))
    }

    @Test fun sceneExportsSkinAffinesThatReproduceSkinning() {
        val leaf = LeafNode(id = 0, name = "sleeve", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
        val mesh = Mesh(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), intArrayOf(0, 1, 2, 0, 2, 3))
        val bone = 200_000
        val pose = mapOf(bone to LayerTransform(rotation = 0.5f, dx = 6f))
        val scene = SceneBuilder.buildTree(
            listOf(leaf), 100, 100, pose,
            bones = mapOf(bone to (0f to 0f)), boneTails = mapOf(bone to (100f to 0f)),
            meshes = mapOf(0 to (mesh to FloatArray(8))), skins = mapOf(0 to mapOf(bone to 1f))
        )
        val aff = scene.skinAffines[0]
        assertNotNull(aff)
        assertEquals(4 * SkinEdit.STRIDE, aff!!.size)
        // одна кость: M_k = rest⁻¹ · дельта кости · rest для всех вершин
        val rest = Affine2D(100f, 0f, 0f, 100f, 0f, 0f)
        val delta = scene.boneDeltas[bone]!!
        val expected = SkinEdit.local(rest, delta)!!
        for (k in 0 until 4) {
            val m = SkinEdit.read(aff, k)!!
            assertEquals(expected.a, m.a, 1e-4f); assertEquals(expected.b, m.b, 1e-4f)
            assertEquals(expected.tx, m.tx, 1e-4f); assertEquals(expected.ty, m.ty, 1e-4f)
        }
        // матрицы едут вместе со сценой при пересборке (символы, экспорт)
        val copy = scene.copyExtrasTo(com.vibetoon.domain.render.SceneData(100, 100, IntArray(0), FloatArray(0), IntArray(0)))
        assertTrue(copy.skinAffines === scene.skinAffines)
        // слой без костей матриц не получает
        val plain = SceneBuilder.buildTree(listOf(leaf), 100, 100, meshes = mapOf(0 to (mesh to FloatArray(8))), skins = emptyMap())
        assertTrue(plain.skinAffines.isEmpty())
    }

    // ---------------------------------------------------------------- 4. точечный выбор

    @Test fun pointPickTogglesAndHoldOnlyAdds() {
        val (a, r1) = BonePointPick.apply(emptySet(), 5, held = false)
        assertEquals(setOf(5), a); assertEquals(BonePointPick.Result.ADDED, r1)
        val (b, r2) = BonePointPick.apply(a, 7, held = true)
        assertEquals(setOf(5, 7), b); assertEquals(BonePointPick.Result.ADDED, r2)
        // удержание по уже выбранному слою не снимает его
        val (c, r3) = BonePointPick.apply(b, 5, held = true)
        assertEquals(setOf(5, 7), c); assertEquals(BonePointPick.Result.SKIPPED, r3)
        val (d, r4) = BonePointPick.apply(c, 5, held = false)
        assertEquals(setOf(7), d); assertEquals(BonePointPick.Result.REMOVED, r4)
    }

    @Test fun pointPickFiltersGhostsLockedAndUnbindable() {
        val bindable = setOf(1, 2, 3)
        assertEquals(2, BonePointPick.eligible(2, 900_000, { false }, bindable))
        assertNull(BonePointPick.eligible(-1, 900_000, { false }, bindable))
        assertNull(BonePointPick.eligible(900_002, 900_000, { false }, bindable + 900_002))
        assertNull(BonePointPick.eligible(3, 900_000, { it == 3 }, bindable))
        assertNull(BonePointPick.eligible(4, 900_000, { false }, bindable))
    }

    @Test fun holdThreshold() {
        assertFalse(BonePointPick.isHold(1000L, 1000L + BonePointPick.HOLD_MS - 1))
        assertTrue(BonePointPick.isHold(1000L, 1000L + BonePointPick.HOLD_MS))
        assertFalse(BonePointPick.isHold(0L, 5000L))
        assertEquals(listOf(BoneSelectMode.LASSO, BoneSelectMode.POINT), BoneSelectMode.values().toList())
    }
}
