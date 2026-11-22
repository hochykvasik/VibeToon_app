package com.vibetoon

import com.vibetoon.domain.model.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

class SceneOptionsTest {
    private fun leaf(id: Int, x: Int = 0) = LeafNode(id, "leaf", true, 1f, "norm", false, x, 0, 100, 100)

    @Test fun editorAndExportShareGroupDepthAndDoNotRenderNullObject() {
        val group = GroupNode(100001, "group", true, 1f, "pass", listOf(leaf(100002, -120), leaf(100003, 30)), 5f, 50f)
        val nullId = 100004; val camera = 100005
        val media = mutableListOf(MediaItem(nullId, MediaKind.NULLOBJ, "null", ""), MediaItem(camera, MediaKind.CAMERA, "camera", ""))
        val props = mutableMapOf(group.id to LayerProps(depth = 1f))
        val options = SceneOptions.capture(media, props)
        // Export must keep its own snapshot even if UI edits settings later.
        props[group.id] = LayerProps(depth = 0f); media.clear()
        assertEquals(1f, options.depths.getValue(group.id), 0f)
        assertTrue(nullId in options.nullIds)
        val roots = listOf(group, leaf(nullId), leaf(camera))
        val transforms = mapOf(camera to LayerTransform(dx = 40f), group.id to LayerTransform(dx = 10f))
        val editor = SceneBuilder.buildTree(roots, 200, 200, transforms, cameraId = camera, nullIds = options.nullIds, depths = options.depths)
        val export = SceneBuilder.buildTree(roots, 200, 200, transforms, cameraId = camera, nullIds = options.nullIds, depths = options.depths)
        assertArrayEquals(editor.ops, export.ops); assertArrayEquals(editor.params, export.params, 0f)
        assertArrayEquals(editor.textureIds, export.textureIds)
        assertFalse(nullId in export.textureIds.toList())
        assertEquals(-130f, export.selectionQuads.getValue(100002)[0], .001f)
        assertEquals(20f, export.selectionQuads.getValue(100003)[0], .001f)
        val oldExport = SceneBuilder.buildTree(roots, 200, 200, transforms, cameraId = camera)
        assertEquals(-150f, oldExport.selectionQuads.getValue(100002)[0], .001f)
        assertTrue(nullId in oldExport.textureIds.toList())
    }

    @Test fun absentOptionsKeepLegacySceneDefaults() {
        val options = SceneOptions.capture(emptyList(), emptyMap())
        val roots = listOf(leaf(100001))
        val old = SceneBuilder.buildTree(roots, 100, 100)
        val scene = SceneBuilder.buildTree(roots, 100, 100, nullIds = options.nullIds, depths = options.depths)
        assertArrayEquals(old.ops, scene.ops); assertArrayEquals(old.params, scene.params, 0f)
        assertArrayEquals(old.textureIds, scene.textureIds)
    }
}
