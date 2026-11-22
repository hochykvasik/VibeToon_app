package com.vibetoon

import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ParentLinkTest {
    private fun leaf(id: Int, x: Int, y: Int) = LeafNode(id, "n$id", true, 1f, "norm", false, x, y, 100, 100)
    private val head = MEDIA_ID_BASE + 1
    private val eye = MEDIA_ID_BASE + 2
    private val mouth = MEDIA_ID_BASE + 3
    private val roots = listOf(leaf(head, 0, 0), leaf(eye, 200, 0), leaf(mouth, 400, 0))

    private fun txOf(s: com.vibetoon.domain.render.SceneData, id: Int): Float {
        // ops идут по 4 int; params по 8 float; tx - пятый параметр
        for (i in 0 until s.ops.size / 4) if (s.ops[i * 4 + 1] == id) return s.params[i * 8 + 4]
        error("no $id")
    }

    @Test fun childFollowsParentTranslation() {
        val t = mapOf(head to LayerTransform(dx = 50f))
        val s = SceneBuilder.buildTree(roots, 1000, 1000, t, links = mapOf(eye to head, mouth to head))
        assertEquals(50f, txOf(s, head), 0.001f)
        assertEquals(250f, txOf(s, eye), 0.001f)
        assertEquals(450f, txOf(s, mouth), 0.001f)
    }

    @Test fun noLinkNoFollow() {
        val s = SceneBuilder.buildTree(roots, 1000, 1000, mapOf(head to LayerTransform(dx = 50f)))
        assertEquals(200f, txOf(s, eye), 0.001f)
    }

    @Test fun chainAndCycleAreSafe() {
        val t = mapOf(head to LayerTransform(dx = 10f), eye to LayerTransform(dx = 5f))
        val chain = SceneBuilder.buildTree(roots, 1000, 1000, t, links = mapOf(eye to head, mouth to eye))
        assertEquals(415f, txOf(chain, mouth), 0.001f)   // 400 + 10 + 5
        SceneBuilder.buildTree(roots, 1000, 1000, t, links = mapOf(eye to head, head to eye))   // не зависает
    }

    @Test fun childSelectionFrameAndParentWorldFollow() {
        val t = mapOf(head to LayerTransform(dx = 50f))
        val s = SceneBuilder.buildTree(roots, 1000, 1000, t, links = mapOf(eye to head))
        assertEquals(50f, s.parentWorlds.getValue(eye).tx, 0.001f)
    }

    @Test fun linksSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, links = mapOf(5 to 3, 100002 to 100001)))).getOrThrow()
        assertEquals(mapOf(5 to 3, 100002 to 100001), back.links)
    }
}
