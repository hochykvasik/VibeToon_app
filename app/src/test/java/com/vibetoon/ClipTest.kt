package com.vibetoon

import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipTest {
    @Test fun visibleOnlyInsideRange() {
        val c = Clip(10, 20)
        assertFalse(c.visibleAt(9))
        assertTrue(c.visibleAt(10))
        assertTrue(c.visibleAt(19))
        assertFalse(c.visibleAt(20))
    }

    @Test fun clampKeepsClipInsideProjectWithMinLength() {
        assertEquals(Clip(0, 1), Clip.clamped(-5, -3, 120))
        assertEquals(Clip(119, 120), Clip.clamped(500, 900, 120))
        assertEquals(Clip(10, 11), Clip.clamped(10, 5, 120))     // конец не раньше начала
        assertEquals(Clip(0, 120), Clip.clamped(0, 999, 120))
    }

    @Test fun clampKeepsRetimingSettings() {
        val old = Clip(0, 50, speed = 2f, reverse = true)
        val c = Clip.clamped(5, 40, 120, old)
        assertEquals(2f, c.speed, 0f)
        assertTrue(c.reverse)
        assertEquals(5, c.start)
        assertEquals(40, c.end)
    }

    @Test fun clipsSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val clips = mapOf(3 to Clip(5, 40), 100000 to Clip(0, 60, speed = 0.5f, reverse = true, freeze = true))
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, clips = clips))).getOrThrow()
        assertEquals(clips, back.clips)
        assertTrue(ProjectJson.decode(ProjectJson.encode(ProjectData(meta))).getOrThrow().clips.isEmpty())
    }

    @Test fun offsetMarkersAndGradientSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val text = com.vibetoon.domain.project.MediaItem(
            100000, com.vibetoon.domain.project.MediaKind.TEXT, "t", "", text = "Привет",
            gradient = listOf(0xFFFF0000L, 0xFF0000FFL, 0xFF00FF00L), gradientAngle = 45f
        )
        val data = ProjectData(meta, media = listOf(text), clips = mapOf(1 to Clip(10, 50, offset = 24)), markers = listOf(30, 5, 5, 90))
        val back = ProjectJson.decode(ProjectJson.encode(data)).getOrThrow()
        assertEquals(24, back.clips.getValue(1).offset)
        assertEquals(listOf(5, 30, 90), back.markers)
        assertEquals(text.gradient, back.media.single().gradient)
        assertEquals(45f, back.media.single().gradientAngle, 0f)
    }
}
