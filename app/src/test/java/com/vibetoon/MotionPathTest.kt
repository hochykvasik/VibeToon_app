package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.Camera
import org.junit.Assert.*
import org.junit.Test

class MotionPathTest {
    private fun key(frame: Int, x: Float, y: Float, easing: EasingType = EasingType.LINEAR) =
        Keyframe(frame, x, y, 1f, 1f, 0f, easing = easing)

    @Test fun interpolatesPivotInParentSpace() {
        val track = AnimationTrack(1, listOf(key(0, 0f, 0f), key(10, 20f, 10f)))
        val path = MotionPathMath.build(track, 5f, 3f, Affine2D.IDENTITY)!!
        assertEquals(PathPoint(5f, 3f), path.keys.first())
        assertEquals(PathPoint(25f, 13f), path.keys.last())
        assertEquals(PathPoint(15f, 8f), path.segments.single()[8])
    }

    @Test fun stepIsNotDrawnAsContinuousMovement() {
        val track = AnimationTrack(1, listOf(key(0, 0f, 0f, EasingType.STEP), key(10, 10f, 0f)))
        val path = MotionPathMath.build(track, 0f, 0f, Affine2D.IDENTITY)!!
        assertTrue(path.segments.isEmpty())
        assertEquals(2, path.keys.size)
    }

    @Test fun boundsWorkAndRejectsInvalidGeometry() {
        assertNull(MotionPathMath.build(AnimationTrack(1, listOf(key(0, 0f, 0f))), 0f, 0f, Affine2D.IDENTITY))
        assertNull(MotionPathMath.build(AnimationTrack(1, (0..128).map { key(it, 0f, 0f) }), 0f, 0f, Affine2D.IDENTITY))
        assertNull(MotionPathMath.build(AnimationTrack(1, listOf(key(0, Float.NaN, 0f), key(10, 0f, 0f))), 0f, 0f, Affine2D.IDENTITY))
    }

    @Test fun followCentersAtExistingZoom() {
        val camera = Camera(zoom = 2f, panX = 5f, panY = 7f)
        val result = camera.follow(600f, 200f, 1000f, 800f, 1000, 500)
        assertEquals(2f, result.zoom, 0f)
        assertEquals(-190f, result.panX, .001f)
        assertEquals(95f, result.panY, .001f)
        assertEquals(camera, camera.follow(Float.NaN, 0f, 1000f, 800f, 1000, 500))
        assertEquals(camera, camera.follow(0f, 0f, 0f, 800f, 1000, 500))
    }
}
