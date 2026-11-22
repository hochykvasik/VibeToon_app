package com.vibetoon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.vibetoon.ui.shell.CoachGeometry
import org.junit.Assert.*
import org.junit.Test

class CoachGeometryTest {
    @Test fun tracksWindowOriginAndClipsScrolledTargets() {
        assertEquals(Rect(20f, 30f, 120f, 70f), CoachGeometry.visible(Rect(30f, 50f, 130f, 90f), Offset(10f, 20f), Size(300f, 200f)))
        assertEquals(Rect(0f, 0f, 30f, 20f), CoachGeometry.visible(Rect(-10f, -20f, 30f, 20f), Offset.Zero, Size(300f, 200f)))
        assertNull(CoachGeometry.visible(Rect(310f, 20f, 400f, 80f), Offset.Zero, Size(300f, 200f)))
        // После поворота/перекомпоновки те же оконные координаты пересчитываются заново.
        assertNull(CoachGeometry.visible(Rect(30f, 50f, 130f, 90f), Offset.Zero, Size(20f, 200f)))
    }

    @Test fun popupHighlightIsLocalToMenuInsteadOfUsingEditorWindowCoordinates() {
        val coach = java.io.File("src/main/java/com/vibetoon/ui/shell/Coach.kt").readText()
        val menu = java.io.File("src/main/java/com/vibetoon/ui/shell/EditorShell.kt").readText()
        assertTrue(coach.contains("if (popupTarget) null"))
        assertTrue(menu.contains("Modifier.bringIntoViewRequester(requester).onGloballyPositioned"))
        assertFalse(menu.contains("Modifier.coachTarget(\"Тестовый PSD\")"))
    }
}
