package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.BoneKeys
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.anim.GroupEffects
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.BONE_ID_BASE
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 7: ключи костей на таймлайне (ромбы ◆, навигация) и Вращение/Качание/Ось растяжения у самой группы. */
class BoneKeysGroupSwingTest {
    private val eps = 1e-3f
    private val bone = BONE_ID_BASE + 3

    private fun animWithBoneKeys(vararg frames: Int): Animation {
        var a = Animation()
        for (f in frames) a = a.withKey(bone, f, LayerTransform(rotation = f / 10f))
        return a
    }

    @Test fun boneKeyFramesAreSortedAndRowsShowOnlyKeyedBonesOutsideBoneMode() {
        val anim = animWithBoneKeys(24, 0, 12)
        assertEquals(listOf(0, 12, 24), BoneKeys.frames(bone, anim.tracks))
        assertTrue(BoneKeys.frames(bone + 1, anim.tracks).isEmpty())
        val all = listOf(bone, bone + 1)
        assertEquals(listOf(bone), BoneKeys.rows(all, anim.tracks, boneMode = false))   // кость без ключей не занимает строку
        assertEquals(all, BoneKeys.rows(all, anim.tracks, boneMode = true))             // в режиме «Кости» видны все
    }

    @Test fun boneKeyNavigationAndHit() {
        val fs = listOf(0, 12, 24)
        assertEquals(12, BoneKeys.next(fs, 0))
        assertEquals(12, BoneKeys.prev(fs, 24))
        assertEquals(12, BoneKeys.prev(fs, 13))
        assertNull(BoneKeys.prev(fs, 0))
        assertNull(BoneKeys.next(fs, 24))
        // тап по ромбу: ближайший ключ в радиусе захвата (кадр = 10 px, захват 14 px)
        assertEquals(12, BoneKeys.hit(fs, 125f, 10f, 14f))
        assertEquals(24, BoneKeys.hit(fs, 230f, 10f, 14f))
        assertNull(BoneKeys.hit(fs, 60f, 10f, 14f))
    }

    @Test fun activeBoneKeyPrefersTimelineSelection() {
        val fs = listOf(0, 12, 24)
        assertEquals(12, BoneKeys.activeFrame(bone, fs, setOf(bone to 12), current = 5))
        assertEquals(24, BoneKeys.activeFrame(bone, fs, emptySet(), current = 24))     // ключ под плейхедом
        assertNull(BoneKeys.activeFrame(bone, fs, emptySet(), current = 5))
        assertNull(BoneKeys.activeFrame(bone, fs, setOf(7 to 12), current = 5))         // ключ другого узла не считается
    }

    @Test fun boneRowsFollowLayerRowsInHitTesting() {
        val layers = listOf<Int?>(1, 2, 3)
        val bones = listOf(bone, bone + 1)
        assertEquals(2, BoneKeys.rowIdAt(1, layers, bones))
        assertEquals(bone, BoneKeys.rowIdAt(3, layers, bones))
        assertEquals(bone + 1, BoneKeys.rowIdAt(4, layers, bones))
        assertNull(BoneKeys.rowIdAt(5, layers, bones))
        assertNull(BoneKeys.rowIdAt(-1, layers, bones))
    }

    @Test fun groupTransformEffectsLiveOnTheGroupItself() {
        assertTrue(GroupEffects.addsToGroupItself(EffectMath.SWING))
        assertTrue(GroupEffects.addsToGroupItself(EffectMath.SPIN))
        assertTrue(GroupEffects.addsToGroupItself(EffectMath.STRETCH_AXIS))
        assertFalse(GroupEffects.addsToGroupItself(EffectMath.BLUR))
        val fx = mapOf(7 to listOf(EffectMath.create(EffectMath.SWING)!!), 8 to listOf(EffectMath.create(EffectMath.BLUR)!!))
        assertEquals(1, GroupEffects.own(fx, 7).size)
        // список инспектора группы: свои трансформации, затем общая цепочка вложенных слоёв
        assertEquals(true to 0, GroupEffects.route(0, ownCount = 1))
        assertEquals(false to 0, GroupEffects.route(1, ownCount = 1))
        assertEquals(false to 2, GroupEffects.route(3, ownCount = 1))
        assertFalse(GroupEffects.canMove(0, 1, ownCount = 1, total = 3))   // через границу частей - нельзя
        assertTrue(GroupEffects.canMove(1, 1, ownCount = 1, total = 3))
        assertFalse(GroupEffects.canMove(2, 1, ownCount = 1, total = 3))
    }

    @Test fun groupSwingRotatesChildrenAroundGroupPivotAsAWhole() {
        val group = 7
        val leaf = LeafNode(id = 100_001, name = "i", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
        val g = GroupNode(group, "g", true, 1f, "pass", listOf(leaf), pivotX = 100f, pivotY = 50f)
        // частота 0 - Swing стоит на «Угол 1» = 90°
        val fx = listOf(Effect(EffectMath.SWING, listOf(0f, 90f, -90f, 0f, EffectMath.WAVE_SINE.toFloat(), 0f, 0f, 0f)))
        val rot = EffectTracks.extraRotation(group, fx, Animation(), 10, 24)
        assertEquals(90f, Math.toDegrees(rot.toDouble()).toFloat(), eps)
        val sc = SceneBuilder.buildTree(listOf(g), 300, 300, mapOf(group to LayerTransform(rotation = rot)))
        val q = sc.selectionQuads.getValue(100_001)
        // угол (0,0) вокруг пивота группы (100,50): (-100,-50) -> (50,-100) -> (150,-50); (100,0) -> (150,50)
        assertEquals(150f, q[0], eps); assertEquals(-50f, q[1], eps)
        assertEquals(150f, q[2], eps); assertEquals(50f, q[3], eps)
        // ключ параметра Swing на дорожках САМОЙ группы
        val keyed = listOf(fx[0].copy(slot = 0))
        val anim = Animation().withKey(EffectTracks.id(group, 0, 1), 0, LayerTransform(dx = 0f))
            .withKey(EffectTracks.id(group, 0, 1), 24, LayerTransform(dx = 40f))
        assertEquals(20f, Math.toDegrees(EffectTracks.extraRotation(group, keyed, anim, 12, 24).toDouble()).toFloat(), 0.01f)
    }

    @Test fun groupStretchAppliesToGroupMatrix() {
        val group = 7
        val leaf = LeafNode(id = 100_001, name = "i", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
        val g = GroupNode(group, "g", true, 1f, "pass", listOf(leaf), pivotX = 100f, pivotY = 50f)
        val st = EffectTracks.stretches(mapOf(group to listOf(Effect(EffectMath.STRETCH_AXIS, listOf(2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)))), Animation(), 0)
        val q = SceneBuilder.buildTree(listOf(g), 300, 300, emptyMap(), stretches = st).selectionQuads.getValue(100_001)
        // x вокруг пивота группы 100: 0 -> -100, 100 -> 100; высота не меняется
        assertEquals(-100f, q[0], eps); assertEquals(0f, q[1], eps)
        assertEquals(100f, q[2], eps)
    }
}
