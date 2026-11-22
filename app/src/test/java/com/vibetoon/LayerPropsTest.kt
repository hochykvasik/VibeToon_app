package com.vibetoon

import com.vibetoon.domain.model.LayerBlend
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.SceneBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerPropsTest {

    @Test fun propsSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val props = mapOf(
            3 to LayerProps(0.4f, "mul ", alphaLock = true, clip = false),
            100001 to LayerProps(0.85f, "sLit", alphaLock = false, clip = true, exposure = 25f, contrast = -40f, saturation = 100f, hue = -90f)
        )
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, layerProps = props))).getOrThrow()
        assertEquals(props.keys, back.layerProps.keys)
        for ((id, p) in props) {
            val q = back.layerProps.getValue(id)
            assertEquals(p.opacity, q.opacity, 1e-6f); assertEquals(p.blend, q.blend)
            assertEquals(p.alphaLock, q.alphaLock); assertEquals(p.clip, q.clip)
            assertEquals(p.exposure, q.exposure, 1e-6f); assertEquals(p.contrast, q.contrast, 1e-6f)
            assertEquals(p.saturation, q.saturation, 1e-6f); assertEquals(p.hue, q.hue, 1e-6f)
        }
        assertTrue(ProjectJson.decode(ProjectJson.encode(ProjectData(meta))).getOrThrow().layerProps.isEmpty())
    }

    @Test fun visibilityLockAndNameSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val props = mapOf(
            1 to LayerProps(visible = false, locked = true, name = "Глаз"),
            2 to LayerProps(name = "   ")   // пустое имя - как будто имени нет
        ).mapValues { it.value.normalized() }
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, layerProps = props))).getOrThrow().layerProps
        assertEquals(false, back.getValue(1).visible); assertEquals(true, back.getValue(1).locked); assertEquals("Глаз", back.getValue(1).name)
        assertEquals(true, back.getValue(2).visible); assertEquals(null, back.getValue(2).name)
        // по умолчанию слой виден и не заблокирован
        assertTrue(LayerProps().visible && !LayerProps().locked)
    }

    @Test fun badValuesAreNormalized() {
        val p = LayerProps(7f, "zzzz").normalized()
        assertEquals(1f, p.opacity, 0f); assertEquals("norm", p.blend)
        assertEquals(0f, LayerProps(-2f).normalized().opacity, 0f)
    }

    @Test fun everyPanelBlendHasShaderCode() {
        val codes = LayerBlend.keys.map { SceneBuilder.blendCode(it) }
        assertEquals(setOf(0, 1, 2, 3, 5, 6, 7, 8, 9, 10), codes.toSet())
    }

    // Те же формулы, что в шейдере (W3C compositing), для контрольных значений.
    private fun overlay(cb: Float, cs: Float) = if (cb <= 0.5f) 2f * cb * cs else cs + (2f * cb - 1f) - cs * (2f * cb - 1f)
    private fun softLight(cb: Float, cs: Float): Float {
        if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb)
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else Math.sqrt(cb.toDouble()).toFloat()
        return cb + (2f * cs - 1f) * (d - cb)
    }

    @Test fun blendFormulasKnownValues() {
        assertEquals(0.5f, overlay(0.5f, 0.5f), 1e-6f)
        assertEquals(0.5f, softLight(0.5f, 0.5f), 1e-6f)                       // серый 50% не меняет подложку
        assertEquals(0f, overlay(0f, 0.7f), 1e-6f); assertEquals(1f, overlay(1f, 0.3f), 1e-6f)
        assertTrue(softLight(0.4f, 0.8f) > 0.4f); assertTrue(softLight(0.4f, 0.2f) < 0.4f)
    }
}
