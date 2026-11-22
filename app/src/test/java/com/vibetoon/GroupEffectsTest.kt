package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.*
import org.junit.Test

class GroupEffectsTest {
    private fun layer(id: Int, parent: Int, group: Boolean = false, clipped: Boolean = false) =
        LayerInfo(id, "n$id", 0, 0, 10, 10, 1f, true, if (group) 1 else 0, parent, "norm", clipped)
    private val tree = listOf(layer(100000, -1, true), layer(100001, 100000), layer(100002, 100000, true),
        layer(100003, 100002), layer(100004, 100002), layer(100005, -1))
    private fun plan(fx: Map<Int, List<Effect>> = emptyMap(), tracks: Set<Int> = emptySet(),
        unsupported: Set<Int> = emptySet(), locked: Set<Int> = emptySet(), clipped: Set<Int> = emptySet()) =
        GroupEffects.plan(100000, tree, fx, tracks, unsupported, locked, clipped)

    @Test fun editAllNestedLeavesUndoRedoAndJson() {
        val initial = emptyMap<Int, List<Effect>>()
        val firstPlan = plan(initial)
        assertNull(firstPlan.rejection)
        assertEquals(setOf(100001, 100003, 100004), firstPlan.ids.toSet())
        val effect = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.COLOR)!!))
        val after = GroupEffects.apply(initial, firstPlan.ids, effect)
        assertEquals(initial, emptyMap<Int, List<Effect>>())
        assertNull(after[100005])
        assertTrue(plan(after).rejection == null)
        val data = ProjectData(ProjectMeta("p", "test", 100, 100), fx = after)
        assertEquals(after, ProjectJson.decode(ProjectJson.encode(data)).getOrThrow().fx)
        val undo = initial
        val redo = after
        assertTrue(undo.isEmpty())
        assertEquals(effect, redo[100003])
        val removed = GroupEffects.apply(after, firstPlan.ids, emptyList())
        assertTrue(removed.isEmpty())
    }
    @Test fun refusesMixedOrAnimatedClippedLockedAndUnsupportedWithoutChangingEffects() {
        val effect = EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.COLOR)!!))
        assertEquals(GroupEffects.Rejection.DIFFERENT, plan(fx = mapOf(100001 to effect)).rejection)
        assertEquals(GroupEffects.Rejection.ANIMATED, plan(tracks = setOf(EffectTracks.id(100003, 0, 0))).rejection)
        assertEquals(GroupEffects.Rejection.CLIPPED, plan(clipped = setOf(100004)).rejection)
        assertEquals(GroupEffects.Rejection.LOCKED, plan(locked = setOf(100002)).rejection)
        assertEquals(GroupEffects.Rejection.UNSUPPORTED, plan(unsupported = setOf(100003)).rejection)
        assertEquals(GroupEffects.Rejection.EMPTY, GroupEffects.plan(100002, tree.filter { it.parentId != 100002 }, emptyMap(), emptySet(), emptySet(), emptySet(), emptySet()).rejection)
    }
}
