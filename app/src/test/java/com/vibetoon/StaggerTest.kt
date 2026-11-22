package com.vibetoon

import com.vibetoon.domain.anim.SnapMath
import org.junit.Assert.assertEquals
import org.junit.Test

class StaggerTest {
    @Test fun clipsAreChainedBackToBack() {
        // три клипа длиной 27, 27, 27 кадров: каждый начинается там, где кончился предыдущий
        val starts = SnapMath.chainStarts(0, listOf(27, 27, 27))
        assertEquals(listOf(0, 27, 54), starts)
        // у каждого клипа свой размер, первый остаётся на своём месте
        assertEquals(listOf(10, 25, 30), SnapMath.chainStarts(10, listOf(15, 5, 40)))
    }
}
