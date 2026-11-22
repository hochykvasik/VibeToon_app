package com.vibetoon

import com.vibetoon.domain.anim.PausePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PausePolicyTest {
    @Test fun onlyExplicitPauseWithSettingRestoresStart() {
        assertEquals(12, PausePolicy.frameAfterStop(48, 12, true, true))
        assertEquals(48, PausePolicy.frameAfterStop(48, 12, false, true))
        assertEquals(48, PausePolicy.frameAfterStop(48, 12, true, false))
    }
}
