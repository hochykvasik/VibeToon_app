package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.*
import org.junit.Test

class PerspectiveHandlesTest {
    @Test fun allSidesHaveMidpointsAndMoveOnlyTheTwoAdjacentCorners() {
        val corners = floatArrayOf(0f, 0f, 10f, 0f, 12f, 8f, -2f, 8f)
        val handles = PerspectiveHandles.screenHandles(corners)!!
        assertArrayEquals(floatArrayOf(5f, 0f, 11f, 4f, 5f, 8f, -1f, 4f), handles.copyOfRange(8, 16), 0f)
        val original = FloatArray(8)
        for (h in 4 until 8) {
            val moved = PerspectiveHandles.moveEdge(original, h, .2f, -.3f)!!
            val (a, b) = PerspectiveHandles.edgeCorners(h)!!
            for (k in 0..3) {
                assertEquals(if (k == a || k == b) .2f else 0f, moved[k * 2], .000001f)
                assertEquals(if (k == a || k == b) -.3f else 0f, moved[k * 2 + 1], .000001f)
            }
            assertArrayEquals(FloatArray(8), original, 0f)
        }
    }
    @Test fun sharedClampDoesNotDistortSideAndInvalidInputIsRejected() {
        val original = floatArrayOf(1.4f, 0f, 1.1f, 0f, 0f, 0f, 0f, 0f)
        val m = PerspectiveHandles.moveEdge(original, 4, 1f, 0f)!!
        assertEquals(1.5f, m[0], .00001f); assertEquals(1.2f, m[2], .00001f)
        assertEquals(original[0] - original[2], m[0] - m[2], .00001f)
        assertNull(PerspectiveHandles.moveEdge(original, 8, 1f, 0f))
        assertNull(PerspectiveHandles.moveEdge(original, 4, Float.NaN, 0f))
    }
    @Test fun oldFourCornerProjectAndKeysKeepTheirIdsAndPerspective() {
        val id = 100001
        val moved = PerspectiveHandles.moveEdge(FloatArray(8), 4, .25f, -.4f)!!
        val ids = (0..3).map { PerspectiveMath.pointId(id, it) }
        val base = ids.mapIndexed { k, p -> p to LayerTransform(dx = moved[k * 2], dy = moved[k * 2 + 1]) }.toMap()
        val old = ProjectData(ProjectMeta("p", "test", 100, 100), base = base, persps = setOf(id))
        val back = ProjectJson.decode(ProjectJson.encode(old)).getOrThrow()
        assertEquals(base, back.base); assertEquals(setOf(id), back.persps)
        val matrix = PerspectiveMath.matrix(moved)
        val p0 = PerspectiveMath.apply(matrix, 0f, 0f)
        val p1 = PerspectiveMath.apply(matrix, 1f, 0f)
        assertEquals(.25f, p0.first, .0001f); assertEquals(-.4f, p0.second, .0001f)
        assertEquals(1.25f, p1.first, .0001f); assertEquals(-.4f, p1.second, .0001f)
        var animation = Animation(settings = ProjectSettings(durationFrames = 30))
        for (k in 0..1) {
            animation = animation.withKey(ids[k], 0, LayerTransform())
            animation = animation.withKey(ids[k], 10, LayerTransform(dx = moved[k * 2], dy = moved[k * 2 + 1]))
        }
        assertEquals(.125f, animation.evaluate(ids[0], 5, null).dx, .0001f)
        assertEquals(.125f, animation.evaluate(ids[1], 5, null).dx, .0001f)
        assertEquals(4, ids.size)
    }
}
