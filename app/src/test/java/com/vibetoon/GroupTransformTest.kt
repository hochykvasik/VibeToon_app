package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupTransformTest {
    private fun rec(
        id: Int, name: String, kind: Int, parent: Int,
        x: Int = 0, y: Int = 0, w: Int = 100, h: Int = 100, visible: Boolean = true
    ) = LayerInfo(id, name, x, y, w, h, 1f, visible, kind, parent, if (kind == 0) "norm" else "pass", false)

    // Группа G (id 3): divider (id 0) + слои A и B + folder (id 3).
    // A занимает (0..100, 0..100), B занимает (200..300, 0..100)
    private val layers = listOf(
        rec(0, "</G>", 3, 3, w = 0, h = 0),
        rec(1, "A", 0, 3, x = 0, y = 0),
        rec(2, "B", 0, 3, x = 200, y = 0),
        rec(3, "G", 1, -1, w = 0, h = 0)
    )

    private fun leaf(s: SceneData, id: Int): Affine2D {
        val i = s.ops.toList().chunked(4).indexOfFirst { it[0] == SceneBuilder.OP_LAYER && it[1] == id }
        val p = s.params
        return Affine2D(p[i * 8], p[i * 8 + 1], p[i * 8 + 2], p[i * 8 + 3], p[i * 8 + 4], p[i * 8 + 5])
    }

    @Test fun groupMovesAllChildrenTogether() {
        val s = SceneBuilder.build(layers, 1000, 1000, mapOf(3 to LayerTransform(dx = 50f, dy = 20f)))
        assertEquals(50f, leaf(s, 1).tx, 0.001f)
        assertEquals(250f, leaf(s, 2).tx, 0.001f)
        assertEquals(20f, leaf(s, 1).ty, 0.001f)
        assertEquals(20f, leaf(s, 2).ty, 0.001f)
    }

    @Test fun groupRotationPivotsAroundItsCenter() {
        // рамка детей: x 0..300, y 0..100 -> центр (150, 50). Поворот на 180 градусов
        val t = LayerTransform(rotation = Math.PI.toFloat())
        val s = SceneBuilder.build(layers, 1000, 1000, mapOf(3 to t))
        val a = leaf(s, 1)
        // центр слоя A (50, 50) переходит в (250, 50): отражение относительно (150, 50)
        assertEquals(250f, a.applyX(0.5f, 0.5f), 0.01f)
        assertEquals(50f, a.applyY(0.5f, 0.5f), 0.01f)
    }

    @Test fun childKeepsOwnLocalTransformInsideGroup() {
        val s = SceneBuilder.build(
            layers, 1000, 1000,
            mapOf(3 to LayerTransform(dx = 100f), 1 to LayerTransform(dy = 10f))
        )
        assertEquals(100f, leaf(s, 1).tx, 0.001f)
        assertEquals(10f, leaf(s, 1).ty, 0.001f)
        assertEquals(300f, leaf(s, 2).tx, 0.001f)   // второй слой только сдвинут группой
    }

    @Test fun groupSelectionBoundsCoverAllChildren() {
        val q = SceneBuilder.build(layers, 1000, 1000).selectionQuads[3]!!
        assertEquals(listOf(0f, 0f, 300f, 0f, 300f, 100f, 0f, 100f), q.toList())
        val moved = SceneBuilder.build(layers, 1000, 1000, mapOf(3 to LayerTransform(dx = 40f))).selectionQuads[3]!!
        assertEquals(40f, moved[0], 0.001f)
        assertEquals(340f, moved[2], 0.001f)
    }

    @Test fun parentWorldOfChildIsGroupWorld() {
        val s = SceneBuilder.build(layers, 1000, 1000, mapOf(3 to LayerTransform(dx = 40f)))
        assertEquals(40f, s.parentWorlds[1]!!.tx, 0.001f)
        assertEquals(0f, s.parentWorlds[3]!!.tx, 0.001f)   // родитель группы - корень
    }

    @Test fun hiddenGroupDropsWholeSubtree() {
        val hidden = layers.map { if (it.id == 3) it.copy(isVisible = false) else it }
        val s = SceneBuilder.build(hidden, 1000, 1000)
        assertEquals(0, s.ops.size)
        assertNull(s.selectionQuads[1])
    }

    @Test fun groupOpsAreBalanced() {
        val ops = SceneBuilder.build(layers, 1000, 1000).ops.toList().chunked(4).map { it[0] }
        assertEquals(listOf(SceneBuilder.OP_BEGIN_GROUP, 0, 0, SceneBuilder.OP_END_GROUP), ops)
    }

    @Test fun clippedLayerWithoutLeafBaseIsDrawnNotDropped() {
        // clip-слой лежит над группой: базы-слоя нет -> рисуется как обычный
        val l = listOf(
            rec(0, "</G>", 3, 3, w = 0, h = 0),
            rec(1, "A", 0, 3),
            rec(3, "G", 1, -1, w = 0, h = 0),
            rec(4, "clip", 0, -1).copy(isClipped = true)
        )
        val s = SceneBuilder.build(l, 1000, 1000)
        val flags = s.ops.toList().chunked(4).filter { it[0] == SceneBuilder.OP_LAYER }.map { it[1] to it[3] }
        assertEquals(listOf(1 to 0, 4 to 0), flags)
    }

    @Test fun clippedLayerWithHiddenBaseIsHidden() {
        val l = listOf(
            rec(0, "base", 0, -1, visible = false),
            rec(1, "clip", 0, -1).copy(isClipped = true)
        )
        assertEquals(0, SceneBuilder.build(l, 1000, 1000).ops.size)
    }
}
