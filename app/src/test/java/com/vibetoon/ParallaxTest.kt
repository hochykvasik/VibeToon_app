package com.vibetoon

import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ParallaxTest {
    private fun leaf(id: Int) = LeafNode(id = id, name = "i", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
    private val cam = leaf(100_009)

    @Test fun deeperLayerMovesLessWithCamera() {
        val t = mapOf(100_009 to LayerTransform(dx = 40f))
        val sc = SceneBuilder.buildTree(listOf(leaf(100_001), leaf(100_002), cam), 200, 200, t, cameraId = 100_009, depths = mapOf(100_002 to 1f))
        assertEquals(-40f, sc.selectionQuads.getValue(100_001)[0], 1e-3f)   // глубина 0: 1:1
        assertEquals(-20f, sc.selectionQuads.getValue(100_002)[0], 1e-3f)   // глубина 1: вдвое медленнее
    }

    @Test fun nullLayerHasFrameButIsNotDrawn() {
        val sc = SceneBuilder.buildTree(listOf(leaf(100_001), leaf(100_003)), 200, 200, nullIds = setOf(100_003))
        assertFalse(100_003 in sc.textureIds.toList())
        assertEquals(0f, sc.selectionQuads.getValue(100_003)[0], 1e-3f)
    }
}
