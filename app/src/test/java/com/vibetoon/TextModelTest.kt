package com.vibetoon

import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.project.FontKeys
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextModelTest {
    private val meta = ProjectMeta("p1", "Проект", 1920, 1080)

    private fun textItem(text: String = "Привет\nмир", font: String = "serif") =
        MediaItem(
            MEDIA_ID_BASE, MediaKind.TEXT, "Текст", "", x = 100f, y = 200f, w = 640f, h = 230.5f,
            text = text, textColor = 0xFFE53935L, font = font, textSize = 108f
        )

    @Test fun textRoundTripsThroughJsonIncludingCyrillicAndNewlines() {
        val item = textItem()
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(item)))).getOrThrow().media.single()
        assertEquals(item, back)
        assertEquals("Привет\nмир", back.text)
        assertEquals(0xFFE53935L, back.textColor)
        assertEquals("serif", back.font)
        assertEquals(108f, back.textSize, 1e-6f)
    }

    @Test fun textKindSurvivesAndQuotesAreEscaped() {
        val item = textItem(text = "Он сказал: \"привет\" \\ /")
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(item)))).getOrThrow().media.single()
        assertEquals(MediaKind.TEXT, back.kind)
        assertEquals(item.text, back.text)
    }

    @Test fun importedFontKeyIsKeptAsIs() {
        val key = FontKeys.fileKey("Pacifico-Regular.ttf")
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(meta, media = listOf(textItem(font = key))))).getOrThrow().media.single()
        assertEquals(key, back.font)
        assertTrue(FontKeys.isFile(back.font))
        assertEquals("Pacifico-Regular.ttf", FontKeys.fileName(back.font))
    }

    @Test fun mediaWithoutTextFieldsGetsDefaults() {
        val text = """{"id":"x","media":[{"id":100000,"kind":"TEXT","name":"t","x":1,"y":2,"w":3,"h":4}]}"""
        val m = ProjectJson.decode(text).getOrThrow().media.single()
        assertEquals("", m.text)
        assertEquals(FontKeys.DEFAULT, m.font)
        assertEquals(96f, m.textSize, 1e-6f)
        assertEquals(0xFF000000L, m.textColor)
    }

    @Test fun nonTextItemsDoNotWriteTextFields() {
        val encoded = ProjectJson.encode(ProjectData(meta, media = listOf(MediaItem(MEDIA_ID_BASE, MediaKind.IMAGE, "a.png", "assets/a.png"))))
        assertFalse(encoded.contains("\"font\""))
    }

    @Test fun layerListLabelIsFirstNonBlankLine() {
        assertEquals("Привет", textItem("\n  \nПривет\nмир").label)
        assertEquals("Текст", textItem("   ").label)
        assertEquals(28, textItem("x".repeat(100)).label.length)
        assertEquals("a.png", MediaItem(1, MediaKind.IMAGE, "a.png", "f").label)
    }

    @Test fun textIsVisualAndAudioIsNot() {
        assertTrue(textItem().isVisual && textItem().isText)
        assertFalse(MediaItem(1, MediaKind.AUDIO, "a", "f").isVisual)
    }

    @Test fun systemFontListStartsWithTheDefault() {
        assertEquals(FontKeys.DEFAULT, FontKeys.SYSTEM.first().first)
        assertTrue(FontKeys.SYSTEM.map { it.first }.containsAll(listOf("serif", "monospace", "cursive")))
    }

    // ---- hit-test по рамке: флаг уходит в сцену только для текста ----

    private fun leaf(id: Int) = LayerInfo(id, "n$id", 0, 0, 100, 50, 1f, true, 0, -1, "norm", false)

    @Test fun bboxHitFlagIsSetOnlyForRequestedIds() {
        val layers = listOf(leaf(0), leaf(MEDIA_ID_BASE))
        val tree = SceneTree.build(layers)
        val s = SceneBuilder.buildTree(tree, 1000, 1000, emptyMap(), com.vibetoon.domain.model.Affine2D.IDENTITY, setOf(MEDIA_ID_BASE))
        val flags = s.ops.toList().chunked(4).associate { it[1] to it[3] }
        assertEquals(0, flags[0])
        assertEquals(SceneBuilder.FLAG_HIT_BBOX, flags[MEDIA_ID_BASE])
    }
}
