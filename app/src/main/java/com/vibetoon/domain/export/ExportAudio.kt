package com.vibetoon.domain.export

import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.VolKey

/**
 * Пакет 14: есть ли звук для экспорта - одно правило для окна экспорта, MP4 и WAV.
 *
 * Раньше окно экспорта решало «есть звук / no audio» один раз при открытии по своей проверке, а экспорт брал
 * дорожки из аудио-движка; если эти два источника расходились (движок не знал о дорожке или ещё не декодировал её,
 * проверка окна смотрела не туда), окно писало «без звука», переключателя не было, и MP4 выходил немым, хотя Play и
 * скраббинг звучали. Теперь список звуковых дорожек строится из проекта (аудио и видео-слои), каждая получает
 * понятное состояние, а экспорт берёт ровно те дорожки, которые окно показало как звучащие.
 */
object ExportAudio {
    /** Почему дорожка (не) звучит в экспорте. */
    enum class TrackState { AUDIBLE, MUTED, NO_AUDIO_TRACK, MISSING_FILE, OUTSIDE_RANGE, SILENT }

    /** Звуковая дорожка проекта: аудио или видео-слой. [noAudioTrack] - у видео точно нет звука (после декодирования). */
    data class Track(
        val id: Int,
        val name: String,
        val video: Boolean,
        val muted: Boolean,
        val fileExists: Boolean,
        val noAudioTrack: Boolean,
        val clip: Clip,
        val volume: List<VolKey> = emptyList()
    )

    /** Итог для окна экспорта. */
    enum class Summary { READY, NONE, ALL_MUTED, OUTSIDE_RANGE, SILENT, MISSING }

    data class Status(val summary: Summary, val tracks: List<Pair<Track, TrackState>>) {
        /** id дорожек, которые войдут в сведение. */
        val audibleIds: Set<Int> get() = tracks.filter { it.second == TrackState.AUDIBLE }.mapTo(LinkedHashSet()) { it.first.id }
        val audibleCount: Int get() = audibleIds.size
        /** В проекте есть звуковые дорожки (переключатель «Звук в видео» показывается). */
        val hasTracks: Boolean get() = tracks.isNotEmpty()
        val ready: Boolean get() = summary == Summary.READY
    }

    /** Звуковые дорожки проекта из списка медиа: аудио-слои (MP3, WAV, M4A…) и видео-слои. */
    fun soundItems(media: List<MediaItem>): List<MediaItem> =
        media.filter { (it.kind == MediaKind.AUDIO || it.kind == MediaKind.VIDEO) && it.file.isNotBlank() }

    /** Клип звучит в отрезке [start, start + frames): пересечение с клипом, без стоп-кадра. */
    fun overlaps(c: Clip, start: Int, frames: Int): Boolean = c.soundsIn(start, frames.coerceAtLeast(0))   // с циклом - до конца отрезка

    fun stateOf(t: Track, start: Int, frames: Int): TrackState = when {
        t.muted -> TrackState.MUTED
        !t.fileExists -> TrackState.MISSING_FILE
        t.video && t.noAudioTrack -> TrackState.NO_AUDIO_TRACK
        !overlaps(t.clip, start, frames) -> TrackState.OUTSIDE_RANGE
        t.volume.isNotEmpty() && t.volume.none { it.value > 0f } -> TrackState.SILENT
        else -> TrackState.AUDIBLE
    }

    /**
     * Состояние звука для отрезка экспорта. Видео без звуковой дорожки не считается звуковой дорожкой
     * (у обычного немого видео окно честно пишет «без звука», а не «Звук не найден»).
     */
    fun status(tracks: List<Track>, start: Int, frames: Int): Status {
        val list = tracks.filterNot { it.video && it.noAudioTrack }.map { it to stateOf(it, start, frames) }
        val states = list.map { it.second }
        val summary = when {
            list.isEmpty() -> Summary.NONE
            TrackState.AUDIBLE in states -> Summary.READY
            states.all { it == TrackState.MUTED } -> Summary.ALL_MUTED
            TrackState.OUTSIDE_RANGE in states -> Summary.OUTSIDE_RANGE
            TrackState.SILENT in states -> Summary.SILENT
            TrackState.MISSING_FILE in states -> Summary.MISSING
            else -> Summary.ALL_MUTED
        }
        return Status(summary, list)
    }

    /** Подпись под настройками MP4 (RU; перевод - по таблице, числа подставляются шаблоном). */
    fun label(s: Status, withAudio: Boolean): String = when {
        s.summary == Summary.READY && !withAudio -> "Звук выключен - видео без звука"
        s.summary == Summary.READY -> "Звуковых дорожек в видео: ${s.audibleCount}"
        s.summary == Summary.ALL_MUTED -> "Все звуковые дорожки «Без звука» - видео без звука"
        s.summary == Summary.OUTSIDE_RANGE -> "Звук не попадает в экспортируемый отрезок - видео без звука"
        s.summary == Summary.SILENT -> "Громкость звука на нуле - видео без звука"
        s.summary == Summary.MISSING -> "Файл звука не найден в проекте - видео без звука"
        else -> ""
    }
}
