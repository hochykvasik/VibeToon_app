package com.vibetoon

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.PsdRef
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTest {
    private val meta = ProjectMeta("abc12345", "Мой проект", 1920, 1080, fps = 30, durationFrames = 90,
        background = 0xFFF8BBD0L, createdAt = 111L, updatedAt = 222L)

    private fun sample(): ProjectData {
        val track = AnimationTrack(5)
            .upsert(Keyframe(0, 0f, 0f, 1f, 1f, 0f, 1f, EasingType.EASE_IN_OUT))
            .upsert(Keyframe(60, 300f, -40f, 2f, 0.5f, 1.5f, 0.25f))
        return ProjectData(
            meta,
            PsdRef("assets/source.psd", "hero.psd", 4800, 7000),
            base = mapOf(3 to LayerTransform(dx = 10f, dy = -5f, scaleX = 1.2f, scaleY = 0.8f, rotation = 0.3f, opacity = 0.5f)),
            tracks = mapOf(5 to track)
        )
    }

    @Test fun roundTripKeepsEverything() {
        val back = ProjectJson.decode(ProjectJson.encode(sample())).getOrThrow()
        assertEquals(sample().meta, back.meta)
        assertEquals(sample().psd, back.psd)
        assertEquals(sample().base, back.base)
        val keys = back.tracks[5]!!.keys
        assertEquals(listOf(0, 60), keys.map { it.frame })
        assertEquals(EasingType.EASE_IN_OUT, keys[0].easing)
        assertEquals(EasingType.LINEAR, keys[1].easing)
        assertEquals(0.25f, keys[1].opacity, 1e-6f)
        assertEquals(0.5f, keys[1].scaleY, 1e-6f)
    }

    @Test fun missingOptionalSectionsAreFine() {
        val text = """{"id":"x1","name":"A","width":720,"height":1280}"""
        val d = ProjectJson.decode(text).getOrThrow()
        assertEquals(24, d.meta.fps)
        assertEquals(120, d.meta.durationFrames)
        assertNull(d.psd)
        assertTrue(d.base.isEmpty() && d.tracks.isEmpty())
        assertEquals(0L, d.meta.background)
    }

    @Test fun unknownEasingBecomesLinear() {
        val text = """{"id":"x","tracks":{"1":[{"f":0,"ease":"BOUNCE"}]}}"""
        assertEquals(EasingType.LINEAR, ProjectJson.decode(text).getOrThrow().tracks[1]!!.keys[0].easing)
    }

    @Test fun newerVersionIsRejected() {
        val r = ProjectJson.decode("""{"version":99,"id":"x"}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("новее") || r.exceptionOrNull()!!.message!!.contains("новой"))
    }

    @Test fun brokenJsonIsFailureNotCrash() {
        assertTrue(ProjectJson.decode("{ not json").isFailure)
        assertTrue(ProjectJson.decode("""{"name":"no id"}""").isFailure)
    }

    @Test fun colorStringRoundTrip() {
        assertEquals("#FFF8BBD0", ProjectJson.colorToString(0xFFF8BBD0L))
        assertEquals(0xFFF8BBD0L, ProjectJson.parseColor("#FFF8BBD0"))
        assertEquals(0xFF112233L, ProjectJson.parseColor("#112233"))   // без альфы = непрозрачный
        assertEquals(0L, ProjectJson.parseColor("мусор"))
    }

    // ---- размеры и пропорции ----

    @Test fun aspectPresetsGive1080p() {
        assertEquals(1920 to 1080, ProjectMath.sizeFor(16, 9))
        assertEquals(1080 to 1920, ProjectMath.sizeFor(9, 16))
        assertEquals(1080 to 1350, ProjectMath.sizeFor(4, 5))
        assertEquals(1080 to 1080, ProjectMath.sizeFor(1, 1))
        assertEquals(1440 to 1080, ProjectMath.sizeFor(4, 3))
    }

    @Test fun sizesAreEvenForTheEncoder() {
        val (w, h) = ProjectMath.sizeFor(16, 10, shortSide = 721)
        assertTrue(w % 2 == 0 && h % 2 == 0)
    }

    @Test fun ratioLabels() {
        assertEquals("16:9", ProjectMath.ratioLabel(1920, 1080))
        assertEquals("4:5", ProjectMath.ratioLabel(1080, 1350))
        assertEquals("1:1", ProjectMath.ratioLabel(500, 500))
        assertNotNull(ProjectMath.ratioLabel(479, 270))   // нестандартный размер не даёт "479:270"
    }

    @Test fun psdIsFittedAndCentered() {
        // PSD 4800x7000 в холст 1920x1080: масштаб ограничен высотой
        val m = ProjectMath.fit(4800, 7000, 1920, 1080)
        val s = 1080f / 7000f
        assertEquals(s, m.a, 1e-6f)
        assertEquals(s, m.d, 1e-6f)
        // центр PSD (2400, 3500) попадает в центр холста (960, 540)
        assertEquals(960f, m.applyX(2400f, 3500f), 1e-3f)
        assertEquals(540f, m.applyY(2400f, 3500f), 1e-3f)
        // левый верхний угол PSD остаётся внутри холста по вертикали
        assertTrue(m.applyY(0f, 0f) >= -1e-3f)
    }

    @Test fun durationInSeconds() = assertEquals(5.0, ProjectMath.durationSeconds(120, 24), 1e-9)
}
