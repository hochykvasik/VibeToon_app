package com.vibetoon

import com.vibetoon.domain.paint.PaintOp
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.PHONEME_MARKERS
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.SymbolMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolsTest {
    @Test fun localFrameLoopsBySymbolDuration() {
        assertEquals(0, SymbolMath.localFrame(0, 12))
        assertEquals(5, SymbolMath.localFrame(17, 12))
        assertEquals(0, SymbolMath.localFrame(24, 12))
        assertEquals(0, SymbolMath.localFrame(7, 0))
    }

    @Test fun durationFromLastKeyOrFallback() {
        assertEquals(9, SymbolMath.duration(listOf(3, 8, 0), 100))
        assertEquals(100, SymbolMath.duration(listOf(0, 0), 100))
        assertEquals(24, SymbolMath.duration(emptyList(), 24))
    }

    @Test fun descendantsFollowNestedGroups() {
        val parent = mapOf(2 to 1, 3 to 1, 4 to 2, 9 to 8)
        assertEquals(setOf(2, 3, 4), SymbolMath.descendants(1, parent))
        assertEquals(setOf(9), SymbolMath.descendants(8, parent))
        assertEquals(emptySet<Int>(), SymbolMath.descendants(5, parent))
    }

    @Test fun timeRemappingHoldsUntilNextKey() {
        val keys = listOf(TimeKey(10, 3), TimeKey(4, 1))
        assertNull(SymbolMath.timeAt(emptyList(), 5))
        assertEquals(1, SymbolMath.timeAt(keys, 0))   // до первого ключа - его значение
        assertEquals(1, SymbolMath.timeAt(keys, 9))
        assertEquals(3, SymbolMath.timeAt(keys, 10))
        assertEquals(3, SymbolMath.timeAt(keys, 500))
        val k = SymbolMath.withKey(SymbolMath.withKey(emptyList(), TimeKey(5, 2)), TimeKey(5, 7))
        assertEquals(listOf(TimeKey(5, 7)), k)
        assertEquals(emptyList<TimeKey>(), SymbolMath.withoutKey(k, 5))
    }

    @Test fun markersReplaceOnSameFrame() {
        var m = SymbolMath.withMarker(emptyList(), FrameMarker(3, "AI"))
        m = SymbolMath.withMarker(m, FrameMarker(1, "Rest"))
        m = SymbolMath.withMarker(m, FrameMarker(3, "O"))
        assertEquals(listOf(FrameMarker(1, "Rest"), FrameMarker(3, "O")), m)
        assertEquals(listOf(FrameMarker(3, "O")), SymbolMath.withoutMarker(m, 1))
        assertEquals(8, PHONEME_MARKERS.size)
    }

    @Test fun symbolMarkersAndTimeKeysSurviveJson() {
        val meta = ProjectMeta("p", "n", 100, 100)
        val media = listOf(
            MediaItem(99999, MediaKind.GROUP, "Символ 1", "", symDur = 12, markers = listOf(FrameMarker(0, "Rest"), FrameMarker(3, "AI"))),
            MediaItem(100001, MediaKind.SYMBOL, "Символ 1", "", w = 100f, h = 100f, symRef = 99999, timeKeys = listOf(TimeKey(0, 0), TimeKey(6, 3)))
        )
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = media))).getOrThrow().media
        assertEquals(12, back.first { it.id == 99999 }.symDur)
        assertEquals(listOf(FrameMarker(0, "Rest"), FrameMarker(3, "AI")), back.first { it.id == 99999 }.markers)
        val inst = back.first { it.kind == MediaKind.SYMBOL }
        assertEquals(99999, inst.symRef)
        assertEquals(listOf(TimeKey(0, 0), TimeKey(6, 3)), inst.timeKeys)
    }

    @Test fun paintOpRemembersItsCell() {
        val op = PaintOp(100001, 0, 0, 1, 1, ByteArray(4), ByteArray(4))
        assertNull(op.cellFile)
        op.cellFile = "assets/m5.png"
        assertEquals("assets/m5.png", op.cellFile)
    }
}

class WaveformTest {
    @org.junit.Test fun peaksAreNormalizedPerBucket() {
        // 100 Гц, моно: корзина = 1 отсчёт; громкий отсчёт в корзине 2
        val d = shortArrayOf(1000, -2000, 16384, 0)
        val p = com.vibetoon.domain.project.WaveformMath.peaks(d, 1, 100, 100)
        org.junit.Assert.assertEquals(4, p.size)
        org.junit.Assert.assertEquals(1f, p[2], 1e-6f)
        org.junit.Assert.assertEquals(2000f / 16384f, p[1], 1e-5f)
        org.junit.Assert.assertEquals(0f, p[3], 1e-6f)
    }

    @org.junit.Test fun amplitudeFollowsClipTime() {
        val pk = FloatArray(200) { if (it in 100..109) 1f else 0.1f }   // громко на 1.0..1.1 с источника
        val clip = com.vibetoon.domain.project.Clip(10, 60)               // клип с кадра 10, 10 fps
        // кадр 20 таймлайна = 1.0 c источника
        org.junit.Assert.assertEquals(1f, com.vibetoon.domain.project.WaveformMath.amplitude(pk, clip, 10, 20f, 20.5f), 1e-6f)
        org.junit.Assert.assertEquals(0.1f, com.vibetoon.domain.project.WaveformMath.amplitude(pk, clip, 10, 12f, 12.5f), 1e-6f)
        org.junit.Assert.assertEquals(0f, com.vibetoon.domain.project.WaveformMath.amplitude(pk, clip, 10, 500f, 501f), 1e-6f)   // вне записи
        // реверс: кадр 59 (конец клипа) играет начало источника, кадр 10 - конец
        val rev = clip.copy(reverse = true)
        org.junit.Assert.assertEquals(rev.length * 1.0 / 10, com.vibetoon.domain.project.WaveformMath.sourceSecond(rev, 10, 10f), 1e-9)
        org.junit.Assert.assertEquals(0.0, com.vibetoon.domain.project.WaveformMath.sourceSecond(rev, 10, 60f), 1e-9)
    }
}

class WaveCacheTest {
    @org.junit.Test fun roundTripAndStaleDetection() {
        val pk = floatArrayOf(0f, 0.5f, 1f, 0.25f)
        val bytes = com.vibetoon.domain.project.WaveCache.encode(pk, 1234L, 99L)
        val back = com.vibetoon.domain.project.WaveCache.decode(bytes, 1234L, 99L)!!
        org.junit.Assert.assertEquals(4, back.size)
        for (i in pk.indices) org.junit.Assert.assertEquals(pk[i], back[i], 1f / 255f)
        org.junit.Assert.assertNull(com.vibetoon.domain.project.WaveCache.decode(bytes, 1235L, 99L))   // файл изменился по размеру
        org.junit.Assert.assertNull(com.vibetoon.domain.project.WaveCache.decode(bytes, 1234L, 100L))  // и по дате
        org.junit.Assert.assertNull(com.vibetoon.domain.project.WaveCache.decode(bytes.copyOf(10), 1234L, 99L))
        org.junit.Assert.assertNull(com.vibetoon.domain.project.WaveCache.decode(ByteArray(0), 1L, 1L))
    }
}

class LayersAsFramesTest {
    @org.junit.Test fun eachLayerGetsItsFrameAndMarker() {
        val plan = com.vibetoon.domain.project.SymbolMath.framesPlan(listOf(10, 11, 12), listOf("Рот_A", "Рот_O", "Рот_A"))
        org.junit.Assert.assertEquals(3, plan.duration)
        org.junit.Assert.assertEquals(listOf("Рот_A", "Рот_O", "Рот_A 2"), plan.markers.map { it.name })   // повторы получают номер
        org.junit.Assert.assertEquals(listOf(0, 1, 2), plan.markers.map { it.frame })
        // слой виден только на своём кадре (Clip как в сцене)
        for ((id, s, e) in plan.clips) {
            val clip = com.vibetoon.domain.project.Clip(s, e)
            for (f in 0..4) org.junit.Assert.assertEquals("слой $id кадр $f", f == s, clip.visibleAt(f))
        }
    }

    @org.junit.Test fun blankNamesFallBackAndStepWorks() {
        val plan = com.vibetoon.domain.project.SymbolMath.framesPlan(listOf(1, 2), listOf("", "  "), step = 10)
        org.junit.Assert.assertEquals(listOf("Кадр 1", "Кадр 2"), plan.markers.map { it.name })
        org.junit.Assert.assertEquals(listOf(0, 10), plan.markers.map { it.frame })
        org.junit.Assert.assertEquals(20, plan.duration)
    }

    @org.junit.Test fun pickingMarkersSwitchesFramesWithHold() {
        // три маркера на кадрах 0,1,2; ключи времени на кадрах сцены 5, 12, 30: между ключами значение держится
        val keys = listOf(
            com.vibetoon.domain.project.TimeKey(5, 2), com.vibetoon.domain.project.TimeKey(12, 0), com.vibetoon.domain.project.TimeKey(30, 1)
        )
        val at = { f: Int -> com.vibetoon.domain.project.SymbolMath.timeAt(keys, f) }
        org.junit.Assert.assertEquals(2, at(0))    // до первого ключа
        org.junit.Assert.assertEquals(2, at(11))
        org.junit.Assert.assertEquals(0, at(12))   // мгновенный переход, без промежуточных значений
        org.junit.Assert.assertEquals(0, at(29))
        org.junit.Assert.assertEquals(1, at(30))
        org.junit.Assert.assertEquals(1, at(4999))
    }

    @org.junit.Test fun markerThumbReferenceSurvivesJson() {
        val meta = com.vibetoon.domain.project.ProjectMeta("p", "n", 100, 100)
        val g = com.vibetoon.domain.project.MediaItem(99999, com.vibetoon.domain.project.MediaKind.GROUP, "Символ 1", "", symDur = 3,
            markers = listOf(com.vibetoon.domain.project.FrameMarker(0, "A", "assets/st99999_0.png"), com.vibetoon.domain.project.FrameMarker(1, "O")))
        val back = com.vibetoon.domain.project.ProjectJson.decode(com.vibetoon.domain.project.ProjectJson.encode(com.vibetoon.domain.project.ProjectData(meta, media = listOf(g)))).getOrThrow().media.first()
        org.junit.Assert.assertEquals("assets/st99999_0.png", back.markers[0].thumb)
        org.junit.Assert.assertEquals("", back.markers[1].thumb)
    }
}
