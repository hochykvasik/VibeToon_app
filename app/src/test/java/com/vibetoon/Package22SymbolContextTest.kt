package com.vibetoon

import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
import com.vibetoon.domain.render.SymbolContext
import com.vibetoon.domain.render.TextureAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 22: Show Symbol Context / Symbol Context Opacity - выравнивание главной сцены по экземпляру. */
class Package22SymbolContextTest {
    private val cw = 200f; private val ch = 100f

    private fun pt(m: Affine2D, x: Float, y: Float) = m.applyX(x, y) to m.applyY(x, y)

    @Test fun untouchedInstanceGivesIdentity() {
        val unit = Affine2D.scale(cw, ch)
        val m = SymbolContext.mainToEdit(Affine2D.IDENTITY, unit, 0f, 0f, cw, ch)!!
        val (x, y) = pt(m, 37f, 81f)
        assertEquals(37f, x, 1e-3f); assertEquals(81f, y, 1e-3f)
    }

    @Test fun movedAndScaledInstanceMovesContextInverse() {
        // экземпляр сдвинут на (50, 20) и уменьшен вдвое: содержимое символа на главной сцене меньше и правее
        val unit = Affine2D.translate(50f, 20f) * Affine2D.scale(0.5f, 0.5f) * Affine2D.scale(cw, ch)
        val m = SymbolContext.mainToEdit(Affine2D.IDENTITY, unit, 0f, 0f, cw, ch)!!
        val (x0, y0) = pt(m, 50f, 20f)
        assertEquals(0f, x0, 1e-3f); assertEquals(0f, y0, 1e-3f)
        val (x1, y1) = pt(m, 50f + cw / 2f, 20f + ch / 2f)
        assertEquals(cw, x1, 1e-3f); assertEquals(ch, y1, 1e-3f)
        // общий предок символа в редакторе сдвинут на 10 по x: контекст едет вместе с ним
        val shifted = SymbolContext.mainToEdit(Affine2D.translate(10f, 0f), unit, 0f, 0f, cw, ch)!!
        assertEquals(10f, pt(shifted, 50f, 20f).first, 1e-3f)
    }

    @Test fun rotatedInstanceAndPartialFrame() {
        val rot = Affine2D.rotate((Math.PI / 2).toFloat())
        val unit = Affine2D.translate(100f, 0f) * rot * Affine2D.translate(10f, 10f) * Affine2D.scale(50f, 40f)
        val m = SymbolContext.mainToEdit(Affine2D.IDENTITY, unit, 10f, 10f, 50f, 40f)!!
        // точка формы (10, 10) лежит на главной сцене в unit(0,0); контекст переводит её обратно в (10, 10)
        val main = pt(unit, 0f, 0f)
        val (x, y) = pt(m, main.first, main.second)
        assertEquals(10f, x, 1e-3f); assertEquals(10f, y, 1e-3f)
        val far = pt(unit, 1f, 1f)
        val (fx, fy) = pt(m, far.first, far.second)
        assertEquals(60f, fx, 1e-3f); assertEquals(50f, fy, 1e-3f)
    }

    @Test fun degenerateInstanceGivesNull() {
        assertNull(SymbolContext.mainToEdit(Affine2D.IDENTITY, Affine2D.scale(0f, ch), 0f, 0f, cw, ch))
        assertNull(SymbolContext.mainToEdit(Affine2D.IDENTITY, Affine2D.scale(cw, ch), 0f, 0f, 0f, ch))
    }

    @Test fun applyMovesOnlyContextLayersAndHidesTheirFrames() {
        val p = floatArrayOf(10f, 0f, 0f, 10f, 1f, 2f, 0.5f, 0f, /* группа */ 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, /* слой символа */ 20f, 0f, 0f, 20f, 3f, 4f, 1f, 0f)
        val ops = intArrayOf(SceneBuilder.OP_LAYER, 7, 0, 0, SceneBuilder.OP_BEGIN_GROUP, 7, 0, 0, SceneBuilder.OP_LAYER, 8, 0, 0)
        val quads = mapOf(7 to FloatArray(8), 8 to FloatArray(8))
        val sc = SceneData(200, 100, ops, p, intArrayOf(7, 8), quads, mapOf(7 to Affine2D.IDENTITY, 8 to Affine2D.IDENTITY))
        val aliases = TextureAliases(intArrayOf(8), intArrayOf(1), intArrayOf(-1), FloatArray(8))
        sc.aliases = aliases
        val out = SymbolContext.apply(sc, setOf(7), Affine2D.translate(100f, 0f))
        assertEquals(101f, out.params[4], 1e-4f); assertEquals(2f, out.params[5], 1e-4f)
        assertEquals(10f, out.params[0], 1e-4f); assertEquals(0.5f, out.params[6], 1e-4f)   // прозрачность не трогается
        assertEquals(0f, out.params[12], 1e-4f)   // параметры группы с тем же id не трогаются
        assertEquals(3f, out.params[20], 1e-4f)   // слой символа на месте
        assertEquals(3f, sc.params[20], 1e-4f); assertEquals(1f, sc.params[4], 1e-4f)   // исходная сцена не изменена
        assertFalse(7 in out.selectionQuads); assertTrue(8 in out.selectionQuads)
        assertFalse(7 in out.parentWorlds)
        assertTrue(aliases === out.aliases)
        assertTrue(sc === SymbolContext.apply(sc, emptySet(), Affine2D.translate(5f, 5f)))
        assertEquals(Affine2D(20f, 0f, 0f, 20f, 3f, 4f), SymbolContext.layerMatrix(sc, 8))
        assertNull(SymbolContext.layerMatrix(sc, 99))
    }

    @Test fun deepestSharedAncestorAndOpacity() {
        val parent = mapOf(3 to 2, 2 to 1, 1 to -1)
        assertEquals(3, SymbolContext.deepest(setOf(1, 2, 3)) { parent[it] })
        assertEquals(2, SymbolContext.deepest(setOf(1, 2)) { parent[it] })
        assertNull(SymbolContext.deepest(emptySet()) { parent[it] })
        assertEquals(0.3f, SymbolContext.opacity(SymbolContext.DEFAULT_OPACITY_PERCENT), 1e-6f)
        assertEquals(0.05f, SymbolContext.opacity(0), 1e-6f)
        assertEquals(1f, SymbolContext.opacity(250), 1e-6f)
    }
}
