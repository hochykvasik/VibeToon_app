package com.vibetoon

import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.GroupEffects
import com.vibetoon.domain.anim.GroupTurb
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.paint.PaintTargetPolicy
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.raster.LayerDetach
import com.vibetoon.domain.raster.LayerMergeMath
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спринт 50: «Слить вниз» с режимом наложения в пиксели нижнего слоя, отделение и дубликат слоя PSD (рамка, сдвиги
 * ключей), цель кисти при мультивыборе, эффекты и Turbulent Displace у группы целиком.
 */
class Sprint50Test {
    private fun px(w: Int, h: Int, r: Int, g: Int, b: Int, a: Int): ByteArray {
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            out[i * 4] = (r * a / 255).toByte(); out[i * 4 + 1] = (g * a / 255).toByte()
            out[i * 4 + 2] = (b * a / 255).toByte(); out[i * 4 + 3] = a.toByte()
        }
        return out
    }
    private fun quad(x: Float, y: Float, w: Float, h: Float) = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
    private fun u(b: Byte) = b.toInt() and 255

    // ---- 4. Слить вниз ----

    @Test fun screenHighlightSurvivesMergeDown() {
        // нижний - тёмно-серая чёлка, верхний - блик 50% серого в режиме «Экран»: результат светлее нижнего, а не пропадает
        val bottom = px(4, 4, 64, 64, 64, 255)
        val top = px(4, 4, 128, 128, 128, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 4f, 4f), 4, 4, quad(0f, 0f, 4f, 4f), 4, 4)!!
        val r = LayerMergeMath.merge(bottom, 0, 4, 4, top, 0, 4, 4, map, SceneBuilder.BLEND_SCREEN, 1f, clipped = false)
        assertNotNull(r)
        // screen: 1 - (1 - .251)(1 - .502) = .627 -> 160
        val v = u(r!!.after[0])
        assertTrue("got $v", v in 158..162)
        assertEquals(255, u(r.after[3]))
        // normal на тех же данных дал бы 128: экран сохранён именно как экран
        val n = LayerMergeMath.merge(bottom, 0, 4, 4, top, 0, 4, 4, map, SceneBuilder.BLEND_NORMAL, 1f, clipped = false)!!
        assertEquals(128, u(n.after[0]))
    }

    @Test fun mergeRespectsOpacityAndMultiply() {
        val bottom = px(2, 2, 200, 200, 200, 255)
        val top = px(2, 2, 100, 100, 100, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 2f, 2f), 2, 2, quad(0f, 0f, 2f, 2f), 2, 2)!!
        val r = LayerMergeMath.merge(bottom, 0, 2, 2, top, 0, 2, 2, map, SceneBuilder.BLEND_MULTIPLY, 0.5f, clipped = false)!!
        // multiply .784 * .392 = .307; 50%: (.784 + .307) / 2 = .546 -> 139
        assertTrue(u(r.after[0]) in 137..141)
    }

    @Test fun clippedTopStaysInsideBottomAlpha() {
        // нижний: левая половина непрозрачна, правая пуста; верхний (обтравлен) - белый на всю рамку
        val bottom = ByteArray(2 * 1 * 4); bottom[0] = 50; bottom[1] = 50; bottom[2] = 50; bottom[3] = 255.toByte()
        val top = px(2, 1, 255, 255, 255, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 2f, 1f), 2, 1, quad(0f, 0f, 2f, 1f), 2, 1)!!
        val r = LayerMergeMath.merge(bottom, 0, 2, 1, top, 0, 2, 1, map, SceneBuilder.BLEND_NORMAL, 1f, clipped = true)!!
        assertEquals(255, u(r.after[0]))
        assertEquals(1, r.rect.w)   // за альфой нижнего ничего не появилось: изменён только левый пиксель
        // без обтравки верх выходит на пустую часть
        val free = LayerMergeMath.merge(bottom, 0, 2, 1, top, 0, 2, 1, map, SceneBuilder.BLEND_NORMAL, 1f, clipped = false)!!
        assertEquals(2, free.rect.w)
        assertEquals(255, u(free.after[4 + 3]))
    }

    @Test fun bottomOpacityIsBaked() {
        val bottom = px(1, 1, 255, 0, 0, 255)
        val top = ByteArray(4)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 1f, 1f), 1, 1, quad(0f, 0f, 1f, 1f), 1, 1)!!
        val r = LayerMergeMath.merge(bottom, 0, 1, 1, top, 0, 1, 1, map, SceneBuilder.BLEND_NORMAL, 1f, clipped = false, bottomOpacity = 0.5f)!!
        assertTrue(u(r.after[3]) in 127..128)
    }

    @Test fun offsetTopMapsIntoBottomPixels() {
        // верхний слой 2x2 в холсте (2,2)-(4,4) над нижним 4x4 (0,0)-(4,4): меняются только пиксели правого нижнего угла
        val bottom = ByteArray(4 * 4 * 4)
        val top = px(2, 2, 255, 255, 255, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 4f, 4f), 4, 4, quad(2f, 2f, 2f, 2f), 2, 2)!!
        assertFalse(LayerMergeMath.topOutside(top, 0, 2, 2, map, 4, 4))
        val r = LayerMergeMath.merge(bottom, 0, 4, 4, top, 0, 2, 2, map, SceneBuilder.BLEND_NORMAL, 1f, clipped = false)!!
        assertEquals(2, r.rect.x); assertEquals(2, r.rect.y); assertEquals(2, r.rect.w); assertEquals(2, r.rect.h)
        // верхний за рамкой нижнего - слияние в пиксели откажет (сработает прежнее слияние снимком)
        val out = LayerMergeMath.pixelMap(quad(0f, 0f, 4f, 4f), 4, 4, quad(3.6f, 3.6f, 2f, 2f), 2, 2)!!
        assertTrue(LayerMergeMath.topOutside(top, 0, 2, 2, out, 4, 4))
    }

    @Test fun mergeNoChangeAndBadInput() {
        val b = px(1, 1, 10, 10, 10, 255)
        val map = LayerMergeMath.pixelMap(quad(0f, 0f, 1f, 1f), 1, 1, quad(0f, 0f, 1f, 1f), 1, 1)!!
        assertNull(LayerMergeMath.merge(b, 0, 1, 1, ByteArray(4), 0, 1, 1, map, SceneBuilder.BLEND_SCREEN, 1f, false))
        assertNull(LayerMergeMath.merge(b, 0, 2, 2, ByteArray(4), 0, 1, 1, map, SceneBuilder.BLEND_SCREEN, 1f, false))
        assertNull(LayerMergeMath.pixelMap(quad(0f, 0f, 1f, 1f), 1, 1, FloatArray(8), 1, 1))
    }

    @Test fun blendFormulas() {
        assertEquals(0.75f, LayerMergeMath.blend(SceneBuilder.BLEND_SCREEN, 0.5f, 0.5f), 1e-5f)
        assertEquals(0.25f, LayerMergeMath.blend(SceneBuilder.BLEND_MULTIPLY, 0.5f, 0.5f), 1e-5f)
        assertEquals(1f, LayerMergeMath.blend(SceneBuilder.BLEND_ADD, 0.7f, 0.6f), 1e-5f)
        assertEquals(0.3f, LayerMergeMath.blend(SceneBuilder.BLEND_DARKEN, 0.3f, 0.6f), 1e-5f)
        assertEquals(0.6f, LayerMergeMath.blend(SceneBuilder.BLEND_LIGHTEN, 0.3f, 0.6f), 1e-5f)
        assertEquals(0.6f, LayerMergeMath.blend(SceneBuilder.BLEND_NORMAL, 0.3f, 0.6f), 1e-5f)
    }

    // ---- 1. Отделение и дубликат слоя PSD ----

    @Test fun psdRectGoesThroughFit() {
        // PSD 2000x1000 в холсте 1000x1000: масштаб .5, сдвиг по y 250
        val fit = ProjectMath.fit(2000, 1000, 1000, 1000)
        assertEquals(0.5f, LayerDetach.scaleOf(fit), 1e-5f)
        val r = LayerDetach.mediaRect(fit, 400f, 200f, 100f, 60f)
        assertEquals(200f, r[0], 1e-3f); assertEquals(350f, r[1], 1e-3f)
        assertEquals(50f, r[2], 1e-3f); assertEquals(30f, r[3], 1e-3f)
        val p = LayerDetach.pivot(fit, 450f to 230f)
        assertEquals(225f, p.first, 1e-3f); assertEquals(365f, p.second, 1e-3f)
    }

    @Test fun psdShiftsScaleIntoCanvas() {
        val t = LayerDetach.transform(LayerTransform(dx = 40f, dy = -20f, scaleX = 2f, rotation = 0.3f, opacity = 0.5f), 0.5f)
        assertEquals(20f, t.dx, 1e-5f); assertEquals(-10f, t.dy, 1e-5f)
        assertEquals(2f, t.scaleX, 1e-5f); assertEquals(0.3f, t.rotation, 1e-5f); assertEquals(0.5f, t.opacity, 1e-5f)
        val k = LayerDetach.keys(listOf(Keyframe(0, 10f, 6f, 1f, 1f, 0f), Keyframe(12, -4f, 0f, 1.5f, 1f, 1f)), 0.5f)
        assertEquals(5f, k[0].dx, 1e-5f); assertEquals(3f, k[0].dy, 1e-5f); assertEquals(-2f, k[1].dx, 1e-5f)
        assertEquals(1.5f, k[1].scaleX, 1e-5f); assertEquals(12, k[1].frame)
        // картинки (k = 1) не меняются
        val same = listOf(Keyframe(0, 1f, 2f, 1f, 1f, 0f))
        assertTrue(LayerDetach.keys(same, 1f) === same)
    }

    @Test fun clippingNeighbourBlocksDetach() {
        assertTrue(LayerDetach.breaksClipping(aboveClipped = true, sourceClipped = false))
        assertFalse(LayerDetach.breaksClipping(aboveClipped = true, sourceClipped = true))
        assertFalse(LayerDetach.breaksClipping(aboveClipped = false, sourceClipped = false))
    }

    @Test fun premultipliedToArgb() {
        val rgba = ByteArray(8 + 8)
        // пиксель 0: красный 50% (premultiplied 128, 0, 0, 128); пиксель 1: прозрачный
        rgba[8] = 128.toByte(); rgba[11] = 128.toByte()
        val argb = LayerDetach.toArgb(rgba, 8, 2, 1)!!
        assertEquals(128, argb[0] ushr 24)
        assertEquals(255, (argb[0] shr 16) and 255)
        assertEquals(0, argb[1])
        assertNull(LayerDetach.toArgb(rgba, 8, 3, 1))
    }

    // ---- 3. Кисть прямо на слое ----

    @Test fun paintTargetIsPrimaryLayerEvenWithMultiSelection() {
        val leaf: (Int) -> Boolean = { it < 1000 }
        assertEquals(5, PaintTargetPolicy.primary(5, setOf(5, 6, 7, 8), leaf))
        assertEquals(5, PaintTargetPolicy.primary(5, emptySet(), leaf))
        assertNull(PaintTargetPolicy.primary(5, setOf(6, 7), leaf))      // основной не среди отмеченных
        assertNull(PaintTargetPolicy.primary(2000, setOf(2000), leaf))   // группа/кость
        assertEquals(3, PaintTargetPolicy.primary(null, setOf(3), leaf))
        assertNull(PaintTargetPolicy.primary(null, setOf(3, 4), leaf))
        assertTrue(PaintTargetPolicy.paintable(PaintTargetPolicy.Kind.PSD_LAYER, locked = false))
        assertTrue(PaintTargetPolicy.paintable(PaintTargetPolicy.Kind.RASTER_MEDIA, locked = false))
        assertFalse(PaintTargetPolicy.paintable(PaintTargetPolicy.Kind.PSD_LAYER, locked = true))
        assertFalse(PaintTargetPolicy.paintable(PaintTargetPolicy.Kind.OTHER, locked = false))
    }

    // ---- 2. Эффекты группы ----

    @Test fun everyEffectGoesToGroupItself() {
        for (t in listOf(EffectMath.BLUR, EffectMath.SWING, EffectMath.SPIN, EffectMath.STRETCH_AXIS)) assertTrue(GroupEffects.addsToGroupItself(t))
        assertFalse(GroupEffects.addsToGroupItself(-12345))
    }

    @Test fun groupTurbRecord() {
        val r = GroupTurb.record(floatArrayOf(0.05f, 10f, 3f, 7f), quad(100f, 50f, 200f, 80f))!!
        assertEquals(10, r.size)
        assertEquals(GroupTurb.MODE.toFloat(), r[0], 0f)
        assertEquals(0.05f, r[1], 1e-6f); assertEquals(10f, r[2], 0f); assertEquals(3f, r[3], 0f); assertEquals(7f, r[4], 0f)
        assertEquals(100f, r[5], 1e-4f); assertEquals(50f, r[6], 1e-4f); assertEquals(200f, r[7], 1e-4f); assertEquals(80f, r[8], 1e-4f)
        assertNull(GroupTurb.record(floatArrayOf(0f, 10f, 3f, 7f), quad(0f, 0f, 10f, 10f)))   // выключена
        assertNull(GroupTurb.record(floatArrayOf(0.1f, 10f, 3f, 7f), quad(0f, 0f, 0f, 0f)))   // пустая рамка
    }

    @Test fun groupTurbSplitsGroupsIntoFx() {
        val turbs = mapOf(1 to floatArrayOf(0.1f, 5f, 2f, 1f), 50 to floatArrayOf(0.2f, 8f, 3f, 2f))
        val blur = floatArrayOf(EffectMath.BLUR.toFloat(), 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val (layers, fx) = GroupTurb.split(turbs, mapOf(50 to listOf(blur)), mapOf(50 to quad(0f, 0f, 10f, 10f)), setOf(50))
        assertEquals(setOf(1), layers.keys)                       // слой остаётся текстурной турбулентностью
        assertEquals(2, fx.getValue(50).size)
        assertEquals(GroupTurb.MODE.toFloat(), fx.getValue(50)[0][0], 0f)   // турбулентность группы - первым проходом
        assertTrue(fx.getValue(50)[1] === blur)
        // без групп - те же карты
        val (same, sameFx) = GroupTurb.split(mapOf(1 to floatArrayOf(0.1f, 5f, 2f, 1f)), emptyMap(), emptyMap(), setOf(50))
        assertEquals(setOf(1), same.keys); assertTrue(sameFx.isEmpty())
    }

    private fun groupScene(fx: Map<Int, List<FloatArray>> = emptyMap(), turbs: Map<Int, FloatArray> = emptyMap()): com.vibetoon.domain.render.SceneData {
        val a = LeafNode(id = 100_001, name = "a", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 0, y = 0, w = 100, h = 100)
        val b = LeafNode(id = 100_002, name = "b", visible = true, opacity = 1f, blend = "norm", clipped = false, x = 50, y = 50, w = 100, h = 100)
        val g = GroupNode(100_010, "hair", true, 1f, "pass", listOf(a, b), pivotX = 75f, pivotY = 75f)
        return SceneBuilder.buildTree(listOf(g), 300, 300, turbs = turbs, fx = fx)
    }
    private fun groupFlags(sc: com.vibetoon.domain.render.SceneData): Int {
        var i = 0
        while (i < sc.ops.size) { if (sc.ops[i] == SceneBuilder.OP_BEGIN_GROUP && sc.ops[i + 1] == 100_010) return sc.ops[i + 3]; i += 4 }
        error("no group")
    }

    @Test fun groupWithEffectsIsFlaggedForBuffer() {
        assertEquals(0, groupFlags(groupScene()) and SceneBuilder.FLAG_GROUP_FX)
        val blur = floatArrayOf(EffectMath.BLUR.toFloat(), 4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val sc = groupScene(fx = mapOf(100_010 to listOf(blur)))
        assertTrue(groupFlags(sc) and SceneBuilder.FLAG_GROUP_FX != 0)
        assertTrue(sc.fxIds.contains(100_010))
    }

    @Test fun groupTurbBecomesGroupEffectInScene() {
        val sc = groupScene(turbs = mapOf(100_010 to floatArrayOf(0.1f, 5f, 2f, 1f)))
        assertTrue(groupFlags(sc) and SceneBuilder.FLAG_GROUP_FX != 0)
        assertFalse(sc.turbIds.contains(100_010))
        val at = sc.fxIds.indexOf(100_010)
        assertTrue(at >= 0)
        var off = 0
        for (k in 0 until at) off += sc.fxCounts[k] * 10
        assertEquals(GroupTurb.MODE.toFloat(), sc.fxData[off], 0f)
        // рамка группы - объединение слоёв: (0,0)-(150,150)
        assertEquals(150f, sc.fxData[off + 7], 0.5f); assertEquals(150f, sc.fxData[off + 8], 0.5f)
    }
}
