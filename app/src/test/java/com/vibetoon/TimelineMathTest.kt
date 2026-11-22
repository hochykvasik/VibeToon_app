package com.vibetoon

import com.vibetoon.ui.timeline.TimelineMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineMathTest {
    @Test fun visiblePlayheadDoesNotScroll() =
        assertNull(TimelineMath.autoScrollTarget(x = 300f, scroll = 0, viewport = 600, margin = 40))

    @Test fun playheadPastRightEdgeScrollsRight() =
        assertEquals(700 - 600 + 40, TimelineMath.autoScrollTarget(x = 700f, scroll = 0, viewport = 600, margin = 40))

    @Test fun playheadBeforeLeftEdgeScrollsLeft() =
        assertEquals(460, TimelineMath.autoScrollTarget(x = 500f, scroll = 600, viewport = 600, margin = 40))

    @Test fun neverScrollsBelowZero() =
        assertEquals(0, TimelineMath.autoScrollTarget(x = 10f, scroll = 100, viewport = 600, margin = 40))

    @Test fun unknownViewportIsIgnored() =
        assertNull(TimelineMath.autoScrollTarget(x = 700f, scroll = 0, viewport = 0, margin = 40))
}
