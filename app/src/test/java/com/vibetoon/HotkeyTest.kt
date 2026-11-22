package com.vibetoon

import com.vibetoon.domain.HotkeyAction
import com.vibetoon.domain.Hotkeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotkeyTest {
    @Test fun defaultsMatchOldLayout() {
        assertEquals(HotkeyAction.BRUSH, Hotkeys.mapCustom(Hotkeys.KEY_B, false, false, emptyMap()))
        assertEquals(HotkeyAction.UNDO, Hotkeys.mapCustom(Hotkeys.KEY_Z, true, false, emptyMap()))
        assertEquals(HotkeyAction.REDO, Hotkeys.mapCustom(Hotkeys.KEY_Z, true, true, emptyMap()))
        assertEquals(HotkeyAction.DELETE, Hotkeys.mapCustom(Hotkeys.KEY_FWD_DEL, false, false, emptyMap()))
    }

    @Test fun remapReplacesOldKey() {
        val custom = mapOf(HotkeyAction.BRUSH to 31)   // C
        assertEquals(HotkeyAction.BRUSH, Hotkeys.mapCustom(31, false, false, custom))
        assertNull(Hotkeys.mapCustom(Hotkeys.KEY_B, false, false, custom))   // старая клавиша больше не рисует кистью
        assertEquals(HotkeyAction.UNDO, Hotkeys.mapCustom(31 + 1, true, false, mapOf(HotkeyAction.UNDO to 32)))
    }
}
