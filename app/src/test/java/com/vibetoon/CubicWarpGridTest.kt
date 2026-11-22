package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

class CubicWarpGridTest {
    private val id = 100010
    @Test fun axesConvertActualPointsAndKeepCubicRenderingAndKeyframes() {
        val size = CubicWarpGrid.Size(2, 3)
        val base = (0 until 6).associate { WarpMath.pointId(id, it) to LayerTransform(dx = it * .05f, dy = -it * .03f) }
        var anim = Animation()
        for (k in 0 until 6) {
            val point = WarpMath.pointId(id, k)
            anim = anim.withKey(point, 0, LayerTransform()).withKey(point, 10, LayerTransform(dx = k * .02f, dy = k * -.01f))
        }
        val next = CubicWarpGrid.migrate(id, size, CubicWarpGrid.Size(4, 3), base, anim)!!
        for (frame in listOf(0, 3, 10, 15)) {
            val before = CubicWarpGrid.cubic(id, size, frame, base, anim, true)
            val after = CubicWarpGrid.cubic(id, next.size, frame, next.base, next.animation, true)
            assertArrayEquals(before, after, .00001f)
        }
        val back = CubicWarpGrid.migrate(id, next.size, size, next.base, next.animation)!!
        for (frame in listOf(0, 4, 10)) assertArrayEquals(CubicWarpGrid.cubic(id, size, frame, base, anim, true), CubicWarpGrid.cubic(id, back.size, frame, back.base, back.animation, true), .00001f)
        val input = GroupSceneInputs.build(emptyMap(), setOf(id), emptySet(), 5, next.base, next.animation, true, mapOf(id to next.size))
        assertArrayEquals(CubicWarpGrid.cubic(id, next.size, 5, next.base, next.animation, true), input.warps.getValue(id), 0f)
    }
    @Test fun refusesLossAndDesynchronisedKeysWithoutChangingInputs() {
        val size = CubicWarpGrid.DEFAULT
        val id0 = WarpMath.pointId(id, 1)
        val base = mapOf(id0 to LayerTransform(dx = .5f))
        val anim = Animation().withKey(id0, 0, LayerTransform()).withKey(id0, 10, LayerTransform(dx = .3f))
        assertNull(CubicWarpGrid.migrate(id, size, CubicWarpGrid.Size(3, 4), base, anim))
        assertEquals(.5f, base.getValue(id0).dx, 0f)
        val synced = anim.withKey(WarpMath.pointId(id, 2), 5, LayerTransform(dx = .1f))
        assertNull(CubicWarpGrid.migrate(id, size, CubicWarpGrid.Size(3, 4), emptyMap(), synced))
        assertEquals(2, synced.tracks.size)
        val stale = mapOf(WarpMath.pointId(id, 15) to LayerTransform(dx = .1f))
        assertNull(CubicWarpGrid.migrate(id, CubicWarpGrid.Size(2, 2), CubicWarpGrid.Size(3, 2), stale, Animation()))
    }
    @Test fun v3AndLegacyRoundTripWithoutReinterpretingExistingIds() {
        val meta = ProjectMeta("p", "test", 100, 100)
        val old = ProjectData(meta, warps = setOf(id), base = mapOf(WarpMath.pointId(id, 10) to LayerTransform(dx = .2f)))
        val oldJson = ProjectJson.encode(old)
        assertTrue(oldJson.contains("\"version\": 1"))
        val reopened = ProjectJson.decode(oldJson).getOrThrow()
        assertTrue(reopened.warpGridSizes.isEmpty())
        assertEquals(.2f, reopened.base.getValue(WarpMath.pointId(id, 10)).dx, 0f)
        val newer = ProjectData(meta, warps = setOf(id), warpGridSizes = mapOf(id to CubicWarpGrid.Size(3, 4)))
        val json = ProjectJson.encode(newer)
        assertTrue(json.contains("\"version\": 3"))
        assertEquals(newer.warpGridSizes, ProjectJson.decode(json).getOrThrow().warpGridSizes)
        assertTrue(ProjectJson.decode("{\"version\":2,\"id\":\"p\",\"warps\":[$id],\"warpGridSizes\":{\"$id\":[3,4]}}").isFailure)
        assertTrue(ProjectJson.decode("{\"version\":3,\"id\":\"p\",\"warps\":[$id],\"warpGridSizes\":{\"$id\":[5,4]}}").isFailure)
    }
}
