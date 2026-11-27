package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Спринт 32: проводка UI/ViewModel/кодека, которую нельзя запустить в unit-тесте (MediaCodec, Compose) -
 * проверяется по исходникам: частоты 1–120 везде, кодек из плана, MOV в окне экспорта и в «Поделиться».
 */
class Sprint32ExportWiringTest {
    private fun src(path: String): String =
        listOf(File("src/main/java/com/vibetoon/$path"), File("app/src/main/java/com/vibetoon/$path")).first { it.exists() }.readText()

    @Test fun videoExporterUsesPlannedCodecAndLevels() {
        val s = src("render/VideoExporter.kt")
        assertFalse("кодек не должен быть зашит в H.264", s.contains("MIMETYPE_VIDEO_AVC"))
        assertTrue(s.contains("createVideoFormat(spec.codec.mime"))
        assertTrue(s.contains("VideoExportPolicy.avcLevel("))
        assertTrue(s.contains("HEVCProfileMain"))
        assertTrue(s.contains("spec.frameRateHint"))
        assertTrue(s.contains("VideoCodecs.candidates("))
    }

    @Test fun codecProbeChecksSizeRateAndBitrate() {
        val s = src("render/VideoCodecs.kt")
        assertTrue(s.contains("isSizeSupported"))
        assertTrue(s.contains("getSupportedFrameRatesFor"))
        assertTrue(s.contains("bitrateRange"))
        assertTrue(s.contains("REGULAR_CODECS"))
    }

    @Test fun viewModelPlansVideoAndWritesMov() {
        val s = src("ui/MainViewModel.kt")
        assertTrue(s.contains("VideoExportPolicy.plan(canvasW, canvasH"))
        assertTrue(s.contains("ExportSpec(w, h, fps, frames, file, compatible, plan.codec, plan.rateHint, plan.bitrate)"))
        assertTrue(s.contains("fun exportMov("))
        assertTrue(s.contains("MovWriter(file, w, h, outFps, rate)"))
        assertTrue(s.contains("AudioMix.frameSamples(i, outFps, rate)"))
        assertTrue(s.contains("saveToGallery(file, \"video/quicktime\")"))
        assertTrue("GIF: частота не выше 50", s.contains("FrameRates.GIF_MAX)"))
    }

    @Test fun exportDialogOffersRatesCodecsUhdAndMov() {
        val s = src("ui/ExportDialog.kt")
        assertTrue(s.contains("MOV(\"MOV (альфа)\", true)"))
        assertTrue(s.contains("FrameRates.exportOptions(projectFps)"))
        assertTrue(s.contains("FrameRates.gifOptions(projectFps)"))
        assertTrue(s.contains("vm.videoPlan(videoQuality, fps, codec)"))
        assertTrue(s.contains("vm.startExport(videoQuality, fps, hasSound && withAudio, codec)"))
        assertTrue(s.contains("vm.exportMov(quality, movFps, movAlpha, hasSound && withAudio)"))
        assertTrue(s.contains("\"mov\" -> \"video/quicktime\""))
        assertTrue(s.contains("for (n in plan.notes)"))
        assertFalse("устаревшая фраза про отсутствие MOV", s.contains("Это замена видео MOV с альфой"))
        assertFalse(s.contains("ExportMath.FPS_OPTIONS.forEach"))
    }

    @Test fun projectAndTimelineAcceptAnyRateUpTo120() {
        val t = src("ui/timeline/TimelinePanel.kt")
        assertTrue(t.contains("FrameRates.parse(rate)"))
        assertTrue(t.contains("FrameRates.PROJECT_PRESETS"))
        assertFalse(t.contains("listOf(12, 24, 30, 60)"))
        val n = src("ui/projects/NewProjectSheet.kt")
        assertTrue(n.contains("FrameRates.parse(form.customFps)"))
        assertTrue(n.contains("form.sizeValid && form.fpsValid"))
        assertTrue(src("data/ProjectRepository.kt").contains("FrameRates.clamp(spec.fps)"))
        assertTrue(src("domain/project/ProjectJson.kt").contains("FrameRates.clamp(root.int(\"fps\", 24))"))
    }
}
