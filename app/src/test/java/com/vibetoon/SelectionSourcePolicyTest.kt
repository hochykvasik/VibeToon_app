package com.vibetoon

import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.raster.SelectionSourcePolicy
import org.junit.Assert.*
import org.junit.Test

class SelectionSourcePolicyTest {
    private val ordinary = SelectionSourcePolicy.Facts(
        raster = true, rootAndVisible = true, defaultProperties = true,
        linked = false, clip = false, animated = false, transformed = false,
        deformed = false, effects = false, sceneClipping = false
    )
    @Test fun importedPngAndNewDrawingWithDefaultStateAreSupported() {
        assertNull(SelectionSourcePolicy.rejection(ordinary))
        assertNull(SelectionSourcePolicy.rejection(ordinary.copy()))
    }
    @Test fun rejectsActualUnsafeFeaturesWithSpecificReasons() {
        assertEquals(SelectionSourcePolicy.Reason.TRANSFORM, SelectionSourcePolicy.rejection(ordinary.copy(transformed = true)))
        assertEquals(SelectionSourcePolicy.Reason.ANIMATION, SelectionSourcePolicy.rejection(ordinary.copy(animated = true)))
        assertEquals(SelectionSourcePolicy.Reason.CLIP, SelectionSourcePolicy.rejection(ordinary.copy(clip = true)))
        assertEquals(SelectionSourcePolicy.Reason.CLIPPING_IN_SCENE, SelectionSourcePolicy.rejection(ordinary.copy(sceneClipping = true)))
        assertEquals(SelectionSourcePolicy.Reason.UNSUPPORTED_TYPE, SelectionSourcePolicy.rejection(ordinary.copy(raster = false)))
    }
    @Test fun sameLayerMoveAllowsOpacityAndBlendButNotAlphaLockOrEffects() {
        assertTrue(SelectionSourcePolicy.moveSafeProperties(LayerProps(opacity = .5f, blend = "mul ")))
        assertFalse(SelectionSourcePolicy.moveSafeProperties(LayerProps(alphaLock = true)))
        assertFalse(SelectionSourcePolicy.moveSafeProperties(LayerProps(exposure = 5f)))
        assertFalse(SelectionSourcePolicy.moveSafeProperties(LayerProps(clip = true)))
        assertFalse(SelectionSourcePolicy.moveSafeProperties(LayerProps(depth = .2f)))
    }
    @Test fun staticTranslationDoesNotMoveThePivotButRotationAndScaleStillRejected() {
        val t = com.vibetoon.domain.render.LayerTransform(dx = 120f, dy = -50f)
        assertTrue(SelectionSourcePolicy.translationOnly(t))
        assertFalse(SelectionSourcePolicy.translationOnly(t.copy(rotation = .1f)))
        assertFalse(SelectionSourcePolicy.translationOnly(t.copy(scaleX = 2f)))
        assertFalse(SelectionSourcePolicy.translationOnly(t.copy(dx = Float.NaN)))
    }

    @Test fun separationCanInheritStaticRotationScaleAndTranslation() {
        val t = com.vibetoon.domain.render.LayerTransform(dx = 40f, dy = -5f, scaleX = 1.5f, scaleY = .8f, rotation = .6f)
        assertTrue(SelectionSourcePolicy.copySafeStaticTransform(t))
        assertFalse(SelectionSourcePolicy.copySafeStaticTransform(t.copy(scaleX = 0f)))
        assertFalse(SelectionSourcePolicy.copySafeStaticTransform(t.copy(rotation = Float.NaN)))
        assertFalse(SelectionSourcePolicy.translationOnly(t))
    }

}
