package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
import com.vibetoon.domain.render.SymbolContext
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 26: «Маска по слою» у «Оси растяжения» - растянутый слой (группа) виден только в своей форме без растяжения. */
class Package26StretchMaskTest {
    private val eps = 1e-3f
    private fun leaf(id: Int, parent: Int = -1, x: Int = 10, y: Int = 20, w: Int = 40, h: Int = 30, clipped: Boolean = false, opacity: Float = 1f, blend: String = "norm") =
        LayerInfo(id, "n$id", x, y, w, h, opacity, true, 0, parent, blend, clipped)
    private fun group(id: Int, parent: Int = -1) = LayerInfo(id, "g$id", 0, 0, 0, 0, 1f, true, 1, parent, "norm", false)
    private fun end(parent: Int) = LayerInfo(900 + parent, "</g>", 0, 0, 0, 0, 1f, true, 3, parent, "norm", false)
    private fun stretch(k: Float, deg: Float, mask: Float, slot: Int = -1) = Effect(EffectMath.STRETCH_AXIS, listOf(k, deg, mask, 0f, 0f, 0f, 0f, 0f), slot)

    private data class Op(val op: Int, val id: Int, val blend: Int, val flags: Int, val m: Affine2D, val opacity: Float)
    private fun ops(sc: SceneData) = (0 until sc.ops.size / 4).map { i ->
        val p = i * SceneData.PARAM_STRIDE
        Op(sc.ops[i * 4], sc.ops[i * 4 + 1], sc.ops[i * 4 + 2], sc.ops[i * 4 + 3],
            Affine2D(sc.params[p], sc.params[p + 1], sc.params[p + 2], sc.params[p + 3], sc.params[p + 4], sc.params[p + 5]), sc.params[p + 6])
    }
    private fun build(layers: List<LayerInfo>, stretches: Map<Int, Affine2D>, masks: Set<Int>, transforms: Map<Int, LayerTransform> = emptyMap()) =
        SceneBuilder.buildTree(SceneTree.build(layers), 200, 200, transforms, Affine2D.IDENTITY, stretches = stretches, stretchMasks = masks)
    private fun same(a: Affine2D, b: Affine2D) = listOf(a.a - b.a, a.b - b.b, a.c - b.c, a.d - b.d, a.tx - b.tx, a.ty - b.ty).all { kotlin.math.abs(it) < eps }

    @Test fun parameterIsAnOnOffSwitchAndOldEffectsStayOff() {
        val def = EffectMath.def(EffectMath.STRETCH_AXIS)!!
        assertEquals(3, def.params.size)
        assertEquals("Маска по слою", def.params[2].name)
        assertEquals(listOf("Выкл", "Вкл"), def.params[2].options)
        assertEquals(0f, EffectMath.create(EffectMath.STRETCH_AXIS)!!.p[2], 0f)       // по умолчанию выкл
        assertFalse(EffectMath.stretchMasked(listOf(1.25f, 0f)))                          // эффект из старого проекта
        assertFalse(EffectMath.stretchMasked(listOf(1.25f, 0f, 0.49f)))
        assertTrue(EffectMath.stretchMasked(listOf(1.25f, 0f, 0.5f)))
        assertFalse(EffectMath.stretchMasked(listOf(1.25f, 0f, Float.NaN)))
        assertEquals(1f, EffectMath.clamp(EffectMath.STRETCH_AXIS, 2, 7f), 0f)
    }

    @Test fun masksPerFrameFollowKeys() {
        val fx = mapOf(1 to listOf(stretch(2f, 0f, 1f)), 2 to listOf(stretch(2f, 0f, 0f)), 3 to listOf(EffectMath.create(EffectMath.BLUR)!!))
        assertEquals(setOf(1), EffectTracks.stretchMasks(fx, Animation(), 0))
        // ключи на «Маске по слою» (параметр 2): выкл на 0, вкл с 10-го кадра (порог 0.5 - с середины)
        val layer = 4
        val keyed = mapOf(layer to listOf(stretch(2f, 0f, 0f, slot = 0)))
        val anim = Animation().withKey(EffectTracks.id(layer, 0, 2), 0, LayerTransform(dx = 0f))
            .withKey(EffectTracks.id(layer, 0, 2), 10, LayerTransform(dx = 1f))
        assertTrue(EffectTracks.stretchMasks(keyed, anim, 0).isEmpty())
        assertEquals(setOf(layer), EffectTracks.stretchMasks(keyed, anim, 10))
        assertEquals(setOf(layer), EffectTracks.stretchMasks(keyed, anim, 30))
        assertTrue(EffectTracks.stretchMasks(emptyMap(), anim, 0).isEmpty())
    }

    @Test fun maskedLeafEmitsMaskThenStretchedLayer() {
        val st = EffectMath.stretchMatrix(2f, 0f)
        val layers = listOf(leaf(1, opacity = 0.5f, blend = "mul "))
        val plain = ops(build(layers, mapOf(1 to st), emptySet()))
        assertEquals(1, plain.size)                                    // без маски - как раньше: один слой
        val stretchedM = plain[0].m
        val sc = build(layers, mapOf(1 to st), setOf(1))
        val o = ops(sc)
        assertEquals(listOf(SceneBuilder.OP_BEGIN_GROUP, SceneBuilder.OP_LAYER, SceneBuilder.OP_END_GROUP,
            SceneBuilder.OP_BEGIN_GROUP, SceneBuilder.OP_LAYER, SceneBuilder.OP_END_GROUP), o.map { it.op })
        // маска: тот же слой без растяжения, непрозрачный, обычный режим
        assertEquals(SceneBuilder.FLAG_MASK_SRC, o[0].flags)
        assertEquals(SceneBuilder.FLAG_MASK_SRC, o[1].flags and SceneBuilder.FLAG_MASK_SRC)
        val unstretched = ops(build(layers, emptyMap(), emptySet()))[0].m
        assertTrue(same(unstretched, o[1].m))
        assertEquals(1f, o[1].opacity, 0f)
        // под маской: растянутый слой; прозрачность и режим слоя - у группы под маской
        assertEquals(SceneBuilder.FLAG_MASKED, o[3].flags)
        assertEquals(0.5f, o[3].opacity, eps)
        assertEquals(SceneBuilder.BLEND_MULTIPLY, o[3].blend)
        assertTrue(same(stretchedM, o[4].m))
        assertEquals(0, o[4].flags and SceneBuilder.FLAG_MASK_SRC)
        assertEquals(SceneBuilder.BLEND_NORMAL, o[4].blend)
        assertEquals(1f, o[4].opacity, 0f)
        assertEquals(SceneBuilder.FLAG_MASKED, o[5].flags)
        // рамка выделения и текстуры - растянутого слоя, без повторов
        assertEquals(listOf(1), sc.textureIds.toList())
        assertTrue(sc.selectionQuads[1]!!.contentEquals(build(layers, mapOf(1 to st), emptySet()).selectionQuads[1]!!))
        // матрица слоя для контекста символа - растянутая, не маска
        assertTrue(same(stretchedM, SymbolContext.layerMatrix(sc, 1)!!))
    }

    @Test fun clippedLayersStayInsideTheMaskedGroupAndChainCloses() {
        val st = EffectMath.stretchMatrix(1.5f, 90f)
        val layers = listOf(leaf(1), leaf(2, clipped = true), leaf(3, clipped = true), leaf(4))
        val o = ops(build(layers, mapOf(1 to st), setOf(1)))
        val ids = o.map { it.op to it.id }
        // маска(1), группа под маской: 1, 2(clip), 3(clip), конец; затем 4 - снаружи
        assertEquals(listOf(SceneBuilder.OP_BEGIN_GROUP to 1, SceneBuilder.OP_LAYER to 1, SceneBuilder.OP_END_GROUP to 1,
            SceneBuilder.OP_BEGIN_GROUP to 1, SceneBuilder.OP_LAYER to 1, SceneBuilder.OP_LAYER to 2, SceneBuilder.OP_LAYER to 3,
            SceneBuilder.OP_END_GROUP to -1, SceneBuilder.OP_LAYER to 4), ids)
        assertTrue(o[5].flags and SceneBuilder.FLAG_CLIPPED != 0 && o[6].flags and SceneBuilder.FLAG_CLIPPED != 0)
        assertEquals(0, o[8].flags and SceneBuilder.FLAG_CLIPPED)
        // маска в конце списка тоже закрывается
        val last = ops(build(listOf(leaf(4), leaf(1)), mapOf(1 to st), setOf(1)))
        assertEquals(SceneBuilder.OP_END_GROUP, last.last().op)
        assertEquals(SceneBuilder.FLAG_MASKED, last.last().flags)
        assertEquals(last.count { it.op == SceneBuilder.OP_BEGIN_GROUP }, last.count { it.op == SceneBuilder.OP_END_GROUP })
    }

    @Test fun clippedOrUnstretchedLayersIgnoreTheMask() {
        val st = EffectMath.stretchMatrix(2f, 0f)
        // слой с обтравкой по нижнему: маска не действует (обтравка не рвётся)
        val o = ops(build(listOf(leaf(1), leaf(2, clipped = true)), mapOf(2 to st), setOf(2)))
        assertEquals(listOf(SceneBuilder.OP_LAYER, SceneBuilder.OP_LAYER), o.map { it.op })
        // маска без растяжения - ничего не меняет
        assertEquals(1, ops(build(listOf(leaf(1)), emptyMap(), setOf(1))).size)
    }

    @Test fun maskedGroupRendersChildrenTwice() {
        val st = EffectMath.stretchMatrix(2f, 30f)
        val layers = listOf(group(5), leaf(1, 5), leaf(2, 5, x = 60), end(5))
        val plain = ops(build(layers, mapOf(5 to st), emptySet()))
        val o = ops(build(layers, mapOf(5 to st), setOf(5)))
        // маска: группа без растяжения (слои помечены как маска), затем группа под маской - как раньше
        assertEquals(SceneBuilder.OP_BEGIN_GROUP, o[0].op); assertEquals(SceneBuilder.FLAG_MASK_SRC, o[0].flags)
        val maskLayers = o.subList(1, 3)
        assertTrue(maskLayers.all { it.op == SceneBuilder.OP_LAYER && it.flags and SceneBuilder.FLAG_MASK_SRC != 0 })
        val unst = ops(build(layers, emptyMap(), emptySet())).filter { it.op == SceneBuilder.OP_LAYER }
        assertTrue(same(unst[0].m, maskLayers[0].m) && same(unst[1].m, maskLayers[1].m))
        assertEquals(SceneBuilder.OP_END_GROUP, o[3].op); assertEquals(SceneBuilder.FLAG_MASK_SRC, o[3].flags)
        val rest = o.drop(4)
        assertEquals(plain.map { it.op to it.id }, rest.map { it.op to it.id })
        assertEquals(SceneBuilder.FLAG_MASKED, rest.first().flags)
        assertEquals(SceneBuilder.FLAG_MASKED, rest.last().flags)
        for ((a, b) in plain.zip(rest)) if (a.op == SceneBuilder.OP_LAYER) { assertTrue(same(a.m, b.m)); assertEquals(0, b.flags and SceneBuilder.FLAG_MASK_SRC) }
    }

    @Test fun nestedMaskedLeafInsideMaskedGroupIsBalanced() {
        val layers = listOf(group(5), leaf(1, 5), end(5))
        val st = mapOf(5 to EffectMath.stretchMatrix(2f, 0f), 1 to EffectMath.stretchMatrix(0.5f, 45f))
        val o = ops(build(layers, st, setOf(5, 1)))
        var depth = 0
        for (op in o) { if (op.op == SceneBuilder.OP_BEGIN_GROUP) depth++; if (op.op == SceneBuilder.OP_END_GROUP) depth--; assertTrue(depth >= 0) }
        assertEquals(0, depth)
        // каждая маска сразу перед своей группой под маской
        val heads = o.withIndex().filter { it.value.op == SceneBuilder.OP_BEGIN_GROUP }.map { it.value.flags and (SceneBuilder.FLAG_MASK_SRC or SceneBuilder.FLAG_MASKED) }
        assertEquals(heads.count { it == SceneBuilder.FLAG_MASK_SRC }, heads.count { it == SceneBuilder.FLAG_MASKED })
    }

    @Test fun maskSwitchIsSavedWithTheEffect() {
        val meta = ProjectMeta("p26", "Маска", 1080, 1080, fps = 24, durationFrames = 24)
        val data = ProjectData(meta, fx = mapOf(1 to listOf(stretch(2f, 15f, 1f))))
        val back = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        assertTrue(EffectMath.stretchMasked(back.fx.getValue(1).first().p))
        assertEquals(setOf(1), EffectTracks.stretchMasks(back.fx, Animation(), 0))
    }
}
