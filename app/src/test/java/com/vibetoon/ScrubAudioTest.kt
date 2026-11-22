package com.vibetoon

import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.ScrubAudio
import org.junit.Assert.*
import org.junit.Test

class ScrubAudioTest {
    @Test fun monoPreviewUsesClipPositionAndFades() {
        val samples = ShortArray(2000) { if (it < 1000) 1000 else 10000 }
        val source = ScrubAudio.Source(samples, 1000, 1, Clip(0, 48))
        val a = ScrubAudio.render(listOf(source), 0, 24, 1000)
        val b = ScrubAudio.render(listOf(source), 24, 24, 1000)
        assertTrue(a[20] > a[0])
        assertEquals(1000, a[40].toInt())
        assertEquals(10000, b[40].toInt())
        assertEquals(b[40], b[41])
        assertTrue(b.last() < b[40])
    }

    @Test fun respectsClipEndAndPadsSilenceForAudioTrackStart() {
        val source = ScrubAudio.Source(ShortArray(2000) { 12000 }, 1000, 1, Clip(0, 1))
        val sound = ScrubAudio.render(listOf(source), 0, 24, 1000)
        assertEquals(0, sound[160].toInt())
        val padded = ScrubAudio.padForStart(sound, 256)
        assertEquals(512, padded.size)
        assertTrue(padded.drop(sound.size).all { it.toInt() == 0 })
    }
}
