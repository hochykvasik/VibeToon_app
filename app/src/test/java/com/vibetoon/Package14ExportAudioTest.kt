package com.vibetoon

import com.vibetoon.domain.export.AudioMix
import com.vibetoon.domain.export.ExportAudio
import com.vibetoon.domain.export.ExportAudio.Summary
import com.vibetoon.domain.export.ExportAudio.TrackState
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ScrubAudio
import com.vibetoon.domain.project.VolKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 14: MP3 в проекте определяется окном экспорта и попадает в MP4 синхронно с картинкой. */
class Package14ExportAudioTest {
    private fun mp3(id: Int = 100002, clip: Clip = Clip(0, 96), muted: Boolean = false, exists: Boolean = true, vol: List<VolKey> = emptyList()) =
        ExportAudio.Track(id, "song.mp3", video = false, muted = muted, fileExists = exists, noAudioTrack = false, clip = clip, volume = vol)

    @Test fun mp3InProjectIsDetected() {
        // главный сценарий: один MP3, проект 96 кадров, экспорт всего таймлайна
        val s = ExportAudio.status(listOf(mp3()), 0, 96)
        assertEquals(Summary.READY, s.summary)
        assertTrue(s.ready && s.hasTracks)
        assertEquals(setOf(100002), s.audibleIds)
        assertEquals("Звуковых дорожек в видео: 1", ExportAudio.label(s, withAudio = true))
        assertEquals("Звук выключен - видео без звука", ExportAudio.label(s, withAudio = false))
    }

    @Test fun detectionDoesNotDependOnDecoder() {
        // MP3 ещё декодируется или декодер когда-то ошибся: для аудио это не «нет звука» - экспорт дождётся PCM
        val s = ExportAudio.status(listOf(mp3().copy(noAudioTrack = true)), 0, 96)
        assertEquals(Summary.READY, s.summary)
    }

    @Test fun soundItemsFromProjectMedia() {
        val media = listOf(
            MediaItem(100000, MediaKind.IMAGE, "Фон", "assets/m100000.png"),
            MediaItem(100001, MediaKind.AUDIO, "song.mp3", "assets/m100001_x.mp3", durationMs = 4000),
            MediaItem(100002, MediaKind.VIDEO, "clip.mp4", "assets/m100002_y.mp4"),
            MediaItem(100003, MediaKind.TEXT, "Текст", ""),
            MediaItem(100004, MediaKind.AUDIO, "битая", "")
        )
        assertEquals(listOf(100001, 100002), ExportAudio.soundItems(media).map { it.id })
    }

    @Test fun clearReasonsInsteadOfFalseNoAudio() {
        assertEquals(Summary.NONE, ExportAudio.status(emptyList(), 0, 96).summary)
        assertEquals("", ExportAudio.label(ExportAudio.status(emptyList(), 0, 96), true))
        assertEquals(Summary.ALL_MUTED, ExportAudio.status(listOf(mp3(muted = true)), 0, 96).summary)
        assertEquals(Summary.MISSING, ExportAudio.status(listOf(mp3(exists = false)), 0, 96).summary)
        assertEquals(Summary.SILENT, ExportAudio.status(listOf(mp3(vol = listOf(VolKey(0, 0f), VolKey(48, 0f)))), 0, 96).summary)
        assertEquals(Summary.OUTSIDE_RANGE, ExportAudio.status(listOf(mp3(clip = Clip(0, 40))), 48, 48).summary)   // сцена 48..96, звук 0..40
        assertEquals(Summary.OUTSIDE_RANGE, ExportAudio.status(listOf(mp3(clip = Clip(0, 96, freeze = true))), 0, 96).summary)
        // один звучащий среди выключенных - звук есть, в сведение идёт только он
        val mixed = ExportAudio.status(listOf(mp3(1, muted = true), mp3(2), mp3(3, exists = false)), 0, 96)
        assertEquals(Summary.READY, mixed.summary)
        assertEquals(setOf(2), mixed.audibleIds)
        assertEquals(TrackState.MUTED, mixed.tracks.first { it.first.id == 1 }.second)
        assertEquals(TrackState.MISSING_FILE, mixed.tracks.first { it.first.id == 3 }.second)
    }

    @Test fun videoWithoutSoundIsNotASoundTrack() {
        val silentVideo = ExportAudio.Track(5, "clip.mp4", video = true, muted = false, fileExists = true, noAudioTrack = true, clip = Clip(0, 96))
        val s = ExportAudio.status(listOf(silentVideo), 0, 96)
        assertEquals(Summary.NONE, s.summary)
        assertFalse(s.hasTracks)
        assertEquals(Summary.READY, ExportAudio.status(listOf(silentVideo, mp3()), 0, 96).summary)
        assertEquals(Summary.READY, ExportAudio.status(listOf(silentVideo.copy(noAudioTrack = false)), 0, 96).summary)
    }

    @Test fun sceneOverlap() {
        assertTrue(ExportAudio.overlaps(Clip(40, 60), 48, 48))
        assertTrue(ExportAudio.overlaps(Clip(90, 200), 48, 48))
        assertFalse(ExportAudio.overlaps(Clip(96, 120), 48, 48))
        assertFalse(ExportAudio.overlaps(Clip(10, 10), 0, 96))
        assertEquals(Summary.READY, ExportAudio.status(listOf(mp3(clip = Clip(40, 60))), 48, 48).summary)
    }

    @Test fun mp3MixIsInSyncWithPicture() {
        // MP3 моно 22,05 кГц (частый случай), клип с кадра 24 проекта 24 fps; экспорт 30 fps, 2 секунды
        val sr = 22050
        val pcm = ShortArray(sr * 3) { 8000 }   // 3 с постоянного сигнала
        val src = listOf(ScrubAudio.Source(pcm, sr, 1, Clip(24, 72)))
        val rate = 44100
        val total = AudioMix.sampleCount(60, 30, rate)
        assertEquals(rate * 2, total)   // звук ровно той же длины, что видео
        assertTrue(AudioMix.audibleIn(src, 0, 48))
        val mix = AudioMix.renderAll(src, 0, 24, rate, total)
        // до кадра 24 (1 с) - тишина, после - звук в обоих каналах (моно -> стерео)
        assertEquals(0, mix[(rate / 2) * 2].toInt())
        assertEquals(0, mix[(rate - 10) * 2].toInt())
        assertEquals(8000, mix[(rate + 100) * 2].toInt())
        assertEquals(8000, mix[(rate + 100) * 2 + 1].toInt())
        // кусками (как кодирует MP4) - то же самое
        val a = AudioMix.render(src, 0, 24, rate, 0, 1024, total)
        val b = AudioMix.render(src, 0, 24, rate, rate + 100, 1, total)
        assertEquals(mix.copyOfRange(0, 2048).toList(), a.toList())
        assertEquals(8000, b[0].toInt())
    }
}
