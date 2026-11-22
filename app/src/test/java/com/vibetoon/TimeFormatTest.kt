package com.vibetoon

import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun frame30At24IsOneSecondSixFrames() {
        assertEquals("00:01:06", TimeFormat.format(30, 24))
        assertEquals("00:00:00", TimeFormat.format(0, 24))
        assertEquals("01:02:03", TimeFormat.format((62 * 24) + 3, 24))
    }

    @Test fun digitsAreRightAligned() {
        assertEquals(30, TimeFormat.parseDigits("106", 24))
        assertEquals(0, TimeFormat.parseDigits("", 24))
        assertEquals(5, TimeFormat.parseDigits("5", 24))
        assertEquals(60 * 24, TimeFormat.parseDigits("010000", 24))
    }

    @Test fun formatThenParseRoundTrips() {
        for (fps in listOf(24, 30, 60)) for (f in listOf(0, 1, 23, 24, 119, 1500)) {
            val digits = TimeFormat.format(f, fps).filter { it.isDigit() }
            assertEquals(f, TimeFormat.parseDigits(digits, fps))
        }
    }

    @Test fun secondsLabel() {
        assertEquals("00:05", TimeFormat.formatSeconds(120, 24))
    }

    @Test fun deletedLayersSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, deleted = setOf(3, 7)))).getOrThrow()
        assertEquals(setOf(3, 7), back.deleted)
        assertEquals(emptySet<Int>(), ProjectJson.decode(ProjectJson.encode(ProjectData(meta))).getOrThrow().deleted)
    }
}
