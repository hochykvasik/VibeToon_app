package com.vibetoon

import com.vibetoon.domain.input.EditorInputPolicy
import org.junit.Assert.*
import org.junit.Test

class EditorInputPolicyTest {
    @Test fun fingerSettingNeverDisablesStylus() {
        assertTrue(EditorInputPolicy.canStartStroke(true, true))
        assertFalse(EditorInputPolicy.canStartStroke(true, false))
        assertTrue(EditorInputPolicy.canStartStroke(false, false))
    }
    @Test fun multiTapDoesNotStartTransformOrGenerateAutoKey() {
        assertFalse(EditorInputPolicy.transformGestureStarted(3f, 8f))
        assertFalse(EditorInputPolicy.transformGestureStarted(8f, 8f))
        assertTrue(EditorInputPolicy.transformGestureStarted(8.1f, 8f))
    }
    @Test fun hoverRadiusMatchesBrushZoomAndHandlesMissingSize() {
        assertEquals(10f, EditorInputPolicy.cursorRadius(20f, 100f, 100), .001f)
        assertEquals(20f, EditorInputPolicy.cursorRadius(20f, 200f, 100), .001f)
        assertEquals(2f, EditorInputPolicy.cursorRadius(1f, 1f, 100), .001f)
        assertTrue(EditorInputPolicy.cursorRadius(20f, 10f, 0).isFinite())
    }
    @Test fun displayAndSelectionAreIndependent() {
        assertFalse(EditorInputPolicy.showCursor(false))
        assertTrue(EditorInputPolicy.canSelectCanvasLayer(true))
        assertFalse(EditorInputPolicy.canSelectCanvasLayer(false))
        assertTrue(EditorInputPolicy.showCursor(true))
    }
    @Test fun quickSelectionSelectsLeafAndDefaultStepsThroughHierarchy() {
        val chain = listOf(1, 2, 3)
        assertEquals(1, EditorInputPolicy.tapTarget(chain, null, false))
        assertEquals(2, EditorInputPolicy.tapTarget(chain, 1, false))
        assertEquals(3, EditorInputPolicy.tapTarget(chain, null, true))
        assertNull(EditorInputPolicy.tapTarget(emptyList(), null, true))
    }
    @Test fun settingsArePersistedWithDefaultsPreservingPreviousBehavior() {
        val src = java.io.File("src/main/java/com/vibetoon/ui/theme/AppTheme.kt").readText()
        for (key in listOf("finger_drawing", "cursor_enabled", "select_layers_canvas")) {
            assertTrue(src.contains("getBoolean(\"$key\", true)"))
            assertTrue(src.contains("putBoolean(\"$key\","))
        }
        assertTrue(src.contains("getBoolean(\"quick_selection\", false)"))
        assertTrue(src.contains("putBoolean(\"quick_selection\", quickSelection)"))
        assertTrue(src.contains("getBoolean(\"auto_keyframes\", false)"))
        assertTrue(src.contains("getBoolean(\"cursor_hover_preview\", false)"))
        assertTrue(src.contains("putBoolean(\"cursor_hover_preview\", cursorHoverPreview)"))
    }
}
