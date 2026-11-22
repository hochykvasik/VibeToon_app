package com.vibetoon

import com.vibetoon.domain.HotkeyAction
import com.vibetoon.domain.Hotkeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotkeysTest {
    @Test fun mapping() {
        assertEquals(HotkeyAction.UNDO, Hotkeys.map(Hotkeys.KEY_Z, true, false))
        assertEquals(HotkeyAction.REDO, Hotkeys.map(Hotkeys.KEY_Z, true, true))
        assertEquals(HotkeyAction.REDO, Hotkeys.map(Hotkeys.KEY_Y, true, false))
        assertEquals(HotkeyAction.PLAY_PAUSE, Hotkeys.map(Hotkeys.KEY_SPACE, false, false))
        assertEquals(HotkeyAction.BRUSH, Hotkeys.map(Hotkeys.KEY_B, false, false))
        assertEquals(HotkeyAction.DELETE, Hotkeys.map(Hotkeys.KEY_DEL, false, false))
        assertNull(Hotkeys.map(Hotkeys.KEY_B, true, false))   // Ctrl+B - не кисть
        assertNull(Hotkeys.map(999, false, false))
    }
    @Test fun resizeIsMonotonicAndClamped() {
        assertTrue(Hotkeys.resize(10f, true) > 10f && Hotkeys.resize(10f, false) < 10f)
        assertEquals(1f, Hotkeys.resize(1f, false), 0f)
        assertEquals(1500f, Hotkeys.resize(1500f, true), 0f)
    }
}
