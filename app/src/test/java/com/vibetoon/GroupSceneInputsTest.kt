package com.vibetoon

import com.vibetoon.domain.anim.*
import com.vibetoon.domain.model.*
import com.vibetoon.domain.project.*
import com.vibetoon.domain.render.*
import org.junit.Assert.*
import org.junit.Test

class GroupSceneInputsTest {
    @Test fun savedGroupAndKeysBuildSameEditorAndExportGeometry() {
        val groupId = 100010
        val children = listOf(LeafNode(100011, "one", true, 1f, "norm", false, -100, 0, 100, 100), LeafNode(100012, "two", true, 1f, "norm", false, 0, 0, 100, 100))
        val group = GroupNode(groupId, "group", true, 1f, "norm", children, 0f, 50f)
        val anchor = GroupDeformationAnchor(-100f, 0f, 200f, 100f)
        val anim0 = Animation(ProjectSettings(durationFrames = 20))
        var anim = anim0
        for (i in 0 until 16) {
            anim = anim.withKey(WarpMath.pointId(groupId, i), 0, LayerTransform())
            anim = anim.withKey(WarpMath.pointId(groupId, i), 10, LayerTransform(dx = .2f))
        }
        val data = ProjectData(ProjectMeta("p", "test", 100, 100), tracks = anim.tracks,
            warps = setOf(groupId), persps = setOf(groupId), groupDeformationAnchors = mapOf(groupId to anchor))
        val reopened = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        fun scene(d: ProjectData, frame: Int): SceneData {
            val inputs = GroupSceneInputs.build(d.groupDeformationAnchors, d.warps, d.persps, frame, d.base, d.animation(), true)
            assertTrue(inputs.warps.isEmpty()); assertTrue(inputs.perspectives.isEmpty())
            return SceneBuilder.buildTree(listOf(group), 100, 100, warps = inputs.warps, persp = inputs.perspectives, groupDeformations = inputs.groups)
        }
        val editor = scene(data, 5); val export = scene(reopened, 5)
        assertEquals(setOf(100011, 100012), editor.meshIds.toSet())
        assertArrayEquals(editor.meshIds, export.meshIds); assertArrayEquals(editor.meshVerts, export.meshVerts, 0f)
        assertArrayEquals(editor.params, export.params, 0f)
        assertEquals(.2f, editor.meshVerts[2], .0001f) // .1 of a 200px group = 20px on 100px leaf
        assertEquals(-100f, editor.groupWorlds.getValue(groupId).applyX(anchor.x, anchor.y), .001f)
        val undo = data.copy(tracks = anim0.tracks)
        assertEquals(0f, scene(undo, 5).meshVerts[2], .0001f)
        assertEquals(.2f, scene(reopened, 5).meshVerts[2], .0001f)
    }
    @Test fun anchorsAloneDoNotActivateAndLeafWarpsRemainSeparate() {
        val anchors = mapOf(10 to GroupDeformationAnchor(0f, 0f, 10f, 10f))
        val split = GroupSceneInputs.build(anchors, setOf(20), emptySet(), 0, emptyMap(), Animation(), false)
        assertTrue(split.groups.isEmpty()); assertEquals(setOf(20), split.warps.keys)
    }
    @Test fun sceneViewportAffineDoesNotChangeGroupLocalWarpOrPerspective() {
        val anchor = GroupDeformationAnchor(-150f, -80f, 300f, 160f)
        val leaves = listOf(LeafNode(1, "a", true, 1f, "norm", false, -150, -80, 150, 160), LeafNode(2, "b", true, 1f, "norm", false, 0, -80, 150, 160))
        val group = GroupNode(10, "g", true, 1f, "norm", leaves, 0f, 0f)
        val base = mapOf(WarpMath.pointId(10, 5) to LayerTransform(dx = .12f, dy = -.09f),
            PerspectiveMath.pointId(10, 1) to LayerTransform(dx = .08f, dy = .02f))
        val inputs = GroupSceneInputs.build(mapOf(10 to anchor), setOf(10), setOf(10), 0, base, Animation(), true)
        val pose = mapOf(10 to LayerTransform(dx = 30f, dy = -20f, rotation = .4f, scaleX = 1.2f, scaleY = .8f))
        val viewport = Affine2D.translate(200f, -120f) * Affine2D.scale(1.7f, 1.7f)
        val export = SceneBuilder.buildTree(listOf(group), 100, 100, pose, groupDeformations = inputs.groups)
        val editor = SceneBuilder.buildTree(listOf(group), 100, 100, pose, rootWorld0 = viewport, groupDeformations = inputs.groups)
        assertArrayEquals(export.meshIds, editor.meshIds)
        assertEquals(export.meshVerts.size, editor.meshVerts.size)
        for (i in export.meshVerts.indices) assertEquals(export.meshVerts[i], editor.meshVerts[i], .0001f)
        for (k in export.ops.indices step 4) {
            if (export.ops[k] != SceneBuilder.OP_LAYER) continue
            val j = k / 4 * SceneData.PARAM_STRIDE
            val em = Affine2D(export.params[j], export.params[j+1], export.params[j+2], export.params[j+3], export.params[j+4], export.params[j+5])
            val vm = Affine2D(editor.params[j], editor.params[j+1], editor.params[j+2], editor.params[j+3], editor.params[j+4], editor.params[j+5])
            val (u, v) = .3f to .6f
            assertEquals(viewport.applyX(em.applyX(u, v), em.applyY(u, v)), vm.applyX(u, v), .001f)
            assertEquals(viewport.applyY(em.applyX(u, v), em.applyY(u, v)), vm.applyY(u, v), .001f)
        }
    }

}
