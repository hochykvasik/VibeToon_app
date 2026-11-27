package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.LayerMasks
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
import com.vibetoon.domain.render.SceneOptions
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 38: маски слоёв - назначение, инверсия, выключение, порядок операций рендера и сохранение. */
class Package38LayerMaskTest {
    private val eps = 1e-3f
    private fun leaf(id: Int, parent: Int = -1, x: Int = 10, y: Int = 20, w: Int = 40, h: Int = 30, clipped: Boolean = false, opacity: Float = 1f, blend: String = "norm") =
        LayerInfo(id, "n$id", x, y, w, h, opacity, true, 0, parent, blend, clipped)
    private fun group(id: Int, parent: Int = -1) = LayerInfo(id, "g$id", 0, 0, 0, 0, 1f, true, 1, parent, "norm", false)

    private data class Op(val op: Int, val id: Int, val blend: Int, val flags: Int, val m: Affine2D, val opacity: Float)
    private fun ops(sc: SceneData) = (0 until sc.ops.size / 4).map { i ->
        val p = i * SceneData.PARAM_STRIDE
        Op(sc.ops[i * 4], sc.ops[i * 4 + 1], sc.ops[i * 4 + 2], sc.ops[i * 4 + 3],
            Affine2D(sc.params[p], sc.params[p + 1], sc.params[p + 2], sc.params[p + 3], sc.params[p + 4], sc.params[p + 5]), sc.params[p + 6])
    }
    private fun build(layers: List<LayerInfo>, props: Map<Int, LayerProps>, transforms: Map<Int, LayerTransform> = emptyMap(), links: Map<Int, Int> = emptyMap()): SceneData {
        val o = SceneOptions.capture(emptyList(), props)
        return SceneBuilder.buildTree(SceneTree.build(layers), 200, 200, transforms, Affine2D.IDENTITY, links = links,
            layerMasks = o.layerMasks, maskSources = o.maskSources)
    }
    private val B = SceneBuilder.OP_BEGIN_GROUP
    private val L = SceneBuilder.OP_LAYER
    private val E = SceneBuilder.OP_END_GROUP

    @Test fun assignmentRulesHaveNoChainsAndOneTargetPerMask() {
        val none = emptyMap<Int, LayerProps>()
        assertEquals(LayerMasks.Error.SELF, LayerMasks.check(1, 1, none))
        assertNull(LayerMasks.check(1, 2, none))
        assertEquals(LayerMasks.Error.MISSING, LayerMasks.check(1, 2, none, setOf(1)))
        val p = mapOf(1 to LayerProps(mask = 2))
        assertEquals(LayerMasks.Error.SOURCE_TAKEN, LayerMasks.check(3, 2, p))         // у маски одна цель
        assertEquals(LayerMasks.Error.SOURCE_IS_TARGET, LayerMasks.check(3, 1, p))     // маска сама с маской - нет
        assertEquals(LayerMasks.Error.TARGET_IS_SOURCE, LayerMasks.check(2, 4, p))     // слой-маска не получает маску
        assertNull(LayerMasks.check(1, 5, p))                                           // замена маски у той же цели
        // повреждённые данные: цепочка и повтор отбрасываются, меньший id выигрывает
        val bad = mapOf(1 to LayerProps(mask = 2), 2 to LayerProps(mask = 3), 4 to LayerProps(mask = 9), 5 to LayerProps(mask = 9), 6 to LayerProps(mask = 6))
        assertEquals(setOf(4), LayerMasks.active(bad).keys)
        assertEquals(9, LayerMasks.active(bad).getValue(4).source)
        assertEquals(4, LayerMasks.targetOf(9, bad))
    }

    @Test fun disabledMaskStillHidesItsSourceButNotTheTarget() {
        val p = mapOf(1 to LayerProps(mask = 2, maskInvert = true), 3 to LayerProps(mask = 4, maskOff = true))
        assertEquals(mapOf(1 to LayerMasks.Mask(2, true)), LayerMasks.active(p))
        assertEquals(setOf(2, 4), LayerMasks.sources(p))
        assertEquals(setOf(2), LayerMasks.sources(p, existing = setOf(1, 2, 3)))       // удалённая маска 4 не учитывается
    }

    @Test fun clearedDropsReferencesToDeletedLayers() {
        val p = mapOf(1 to LayerProps(mask = 2, maskInvert = true, opacity = 0.5f), 3 to LayerProps(path = 7, pathOrient = true), 2 to LayerProps())
        val c = LayerMasks.cleared(p, setOf(2, 7))
        assertEquals(LayerProps(opacity = 0.5f), c[1])
        assertEquals(LayerProps(), c[3])
        assertFalse(c.containsKey(2))
    }

    @Test fun maskedLeafRendersMaskBufferThenTargetUnderIt() {
        val layers = listOf(leaf(1, opacity = 0.5f, blend = "mul "), leaf(2, x = 0, y = 0, w = 100, h = 100))
        val plain = ops(build(layers, emptyMap()))
        assertEquals(2, plain.size)
        val o = ops(build(layers, mapOf(1 to LayerProps(mask = 2))))
        assertEquals(listOf(B, L, E, B, L, E), o.map { it.op })
        assertEquals(listOf(2, 2, 2, 1, 1), o.take(5).map { it.id })
        assertEquals(SceneBuilder.FLAG_MASK_SRC, o[0].flags)
        assertTrue(o[1].flags and SceneBuilder.FLAG_MASK_SRC != 0)
        assertEquals(plain[1].m, o[1].m)                                // маска - на своём месте
        assertEquals(SceneBuilder.FLAG_MASKED, o[3].flags)              // без инверсии
        assertEquals(SceneBuilder.BLEND_MULTIPLY, o[3].blend)           // режим и прозрачность цели - у группы под маской
        assertEquals(0.5f, o[3].opacity, eps)
        assertEquals(1f, o[4].opacity, eps)
        assertEquals(plain[0].m, o[4].m)
        // слой-маска сам по себе не рисуется, но рамка для выбора есть
        assertEquals(1, o.count { it.op == L && it.id == 2 })
        assertTrue(build(layers, mapOf(1 to LayerProps(mask = 2))).selectionQuads.containsKey(2))
    }

    @Test fun invertAndDisableSwitches() {
        val layers = listOf(leaf(1), leaf(2))
        val inv = ops(build(layers, mapOf(1 to LayerProps(mask = 2, maskInvert = true))))
        assertEquals(SceneBuilder.FLAG_MASKED or SceneBuilder.FLAG_MASK_INVERT, inv[3].flags)
        assertEquals(16, SceneBuilder.FLAG_MASK_INVERT)
        val off = ops(build(layers, mapOf(1 to LayerProps(mask = 2, maskOff = true))))
        assertEquals(listOf(L), off.map { it.op })                     // выключена: цель целиком, маска не видна
        assertEquals(1, off[0].id)
        assertEquals(0, off[0].flags)
    }

    @Test fun maskOrderIsIndependentOfStackPositionAndFollowsLinks() {
        // маска ниже цели в стопке и привязана к ней: сдвиг цели везёт маску
        val layers = listOf(leaf(2, x = 0, y = 0, w = 50, h = 50), leaf(1))
        val t = mapOf(1 to LayerTransform(dx = 30f))
        val o = ops(build(layers, mapOf(1 to LayerProps(mask = 2)), t, links = mapOf(2 to 1)))
        assertEquals(listOf(B, L, E, B, L, E), o.map { it.op })
        assertEquals(30f, o[1].m.tx, eps)                              // маска сдвинута вместе с целью
        assertEquals(40f, o[4].m.tx, eps)
    }

    @Test fun clippedLayersStayInsideMaskedGroup() {
        val layers = listOf(leaf(1), leaf(3, clipped = true), leaf(4), leaf(2))
        val o = ops(build(layers, mapOf(1 to LayerProps(mask = 2))))
        // маска, группа под маской [слой 1, обтравка 3], затем обычный 4
        assertEquals(listOf(B, L, E, B, L, L, E, L), o.map { it.op })
        assertEquals(listOf(1, 3), listOf(o[4].id, o[5].id))
        assertEquals(SceneBuilder.FLAG_CLIPPED, o[5].flags and SceneBuilder.FLAG_CLIPPED)
        assertEquals(4, o[7].id)
    }

    @Test fun groupTargetAndMissingMask() {
        val layers = listOf(group(10), leaf(11, parent = 10), leaf(12, parent = 10), leaf(2))
        val o = ops(build(layers, mapOf(10 to LayerProps(mask = 2, maskInvert = true))))
        assertEquals(listOf(B, L, E, B, L, L, E), o.map { it.op })
        assertEquals(10, o[3].id)
        assertEquals(SceneBuilder.FLAG_MASKED or SceneBuilder.FLAG_MASK_INVERT, o[3].flags)
        // маски нет в сцене (удалена) - цель рисуется как обычно
        val miss = ops(build(listOf(leaf(1)), mapOf(1 to LayerProps(mask = 99))))
        assertEquals(listOf(L), miss.map { it.op })
    }

    @Test fun emptyMaskGivesEmptyBuffer() {
        val layers = listOf(leaf(1), leaf(2, w = 0, h = 0))
        val o = ops(build(layers, mapOf(1 to LayerProps(mask = 2))))
        assertEquals(listOf(B, E, B, L, E), o.map { it.op })           // пустой буфер: без инверсии слой скрыт, с инверсией - виден
    }

    @Test fun maskPropsSurviveSaveAndOldProjectsLoad() {
        val props = mapOf(100_001 to LayerProps(mask = 100_002, maskInvert = true, maskOff = true), 100_003 to LayerProps(path = 100_004, pathOrient = true))
        val d = ProjectData(ProjectMeta("p", "P", 100, 100), media = listOf(MediaItem(100_001, MediaKind.IMAGE, "a", "assets/a.png")), layerProps = props)
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        assertEquals(props, back.layerProps)
        val json = ProjectJson.encode(d.copy(layerProps = mapOf(100_001 to LayerProps(opacity = 0.5f))))
        assertFalse(json.contains("\"mk\""))                            // поля пишутся только при использовании
        assertFalse(json.contains("\"pa\""))
        assertEquals(-1, LayerProps(mask = -7).normalized().mask)
    }
}
