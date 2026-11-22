package com.vibetoon

import com.vibetoon.domain.input.LongPressPicker
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LongPressPickerTest {
    @Test fun stationarySinglePointerIsTheOnlyEligibleHold() {
        assertFalse(LongPressPicker.cancelled(true, 1, false, 1f, 8f))
        assertFalse(LongPressPicker.cancelled(true, 1, false, 8f, 8f))
        assertTrue(LongPressPicker.cancelled(true, 1, false, 8.1f, 8f))
        assertTrue(LongPressPicker.cancelled(false, 0, false, 0f, 8f))
        assertTrue(LongPressPicker.cancelled(true, 2, false, 0f, 8f))
        assertTrue(LongPressPicker.cancelled(true, 1, true, 0f, 8f))
        assertTrue(LongPressPicker.cancelled(true, 1, false, Float.NaN, 8f))
    }
    @Test fun preferenceAndGestureGuardsAreWired() {
        val settings = File("src/main/java/com/vibetoon/ui/theme/AppTheme.kt").readText()
        val vm = File("src/main/java/com/vibetoon/ui/MainViewModel.kt").readText()
        val ui = File("src/main/java/com/vibetoon/ui/MainScreen.kt").readText()
        assertTrue(settings.contains("getBoolean(\"long_press_picker\", false)"))
        assertTrue(settings.contains("putBoolean(\"long_press_picker\", longPressPicker)"))
        assertTrue(vm.contains("_paintTool.value == null && _rasterTool.value == null"))
        assertTrue(vm.contains("_state.value != UiState.Loading && _state.value !is UiState.Rendering"))
        assertTrue(vm.contains("!_meshMode.value && !_warpTool.value && !_perspTool.value && !_liquifyTool.value"))
        assertTrue(ui.contains("LongPressPicker.cancelled"))
        assertTrue(ui.contains("if (!longPressClaimed && !moved && !multi"))
    }
}
