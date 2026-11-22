package com.vibetoon

import com.vibetoon.domain.project.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class VersionedRasterFilesTest {
    @Test fun failedAssetOrInterruptedBeforeJsonLeavesOriginalPair() {
        val root = Files.createTempDirectory("raster-version").toFile()
        try {
            val assets = root.resolve("assets").apply { mkdirs() }
            assets.resolve("old.png").writeText("old pixels")
            val old = ProjectData(ProjectMeta("p", "p", 100, 100), media = listOf(
                MediaItem(100001, MediaKind.IMAGE, "ink", "assets/old.png", 2, 2, 0f, 0f, 2f, 2f)))
            root.resolve("project.json").writeText(ProjectJson.encode(old))
            fun assertOld() {
                val reopened = ProjectJson.decode(root.resolve("project.json").readText()).getOrThrow()
                assertEquals(old.media.single(), reopened.media.single())
                assertEquals("old pixels", root.resolve(reopened.media.single().file).readText())
            }
            try {
                VersionedRasterFiles.publish(root, { it.writeText("partial"); error("write failed") }, { true }, { "unused" })
                fail()
            } catch (_: IllegalStateException) { }
            assertOld()
            try {
                VersionedRasterFiles.publish(root, { it.writeText("new pixels") }, { true }, { error("process interrupted before JSON") })
                fail()
            } catch (_: IllegalStateException) { }
            assertOld()
            try {
                VersionedRasterFiles.publish(root, { it.writeText("bad") }, { false }, { "unused" })
                fail()
            } catch (_: IllegalStateException) { }
            assertOld()
            val path = VersionedRasterFiles.publish(root, { it.writeText("new pixels") }, { it.length() > 0 }) { path ->
                ProjectJson.encode(old.copy(media = listOf(old.media.single().copy(file = path, srcWidth = 4, w = 4f))))
            }
            val reopened = ProjectJson.decode(root.resolve("project.json").readText()).getOrThrow()
            assertEquals(path, reopened.media.single().file)
            assertEquals(4, reopened.media.single().srcWidth)
            assertEquals("new pixels", root.resolve(path).readText())
            assertEquals("old pixels", assets.resolve("old.png").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun versionedUndoRedoPublishesEachGeometryWithItsOwnPixels() {
        val root = Files.createTempDirectory("raster-history").toFile()
        try {
            val src = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/original.png", 2, 2, 0f, 0f, 2f, 2f)
            val pixels = ByteArray(16).also { it[3] = 255.toByte() }
            val change = com.vibetoon.domain.raster.ExpandedLayerSnapshot.prepare(src, pixels, 0, 2, 2, byteArrayOf(1, 0, 0, 0), -2, 0)!!
            val history = com.vibetoon.domain.raster.ExpandedLayerHistory(change)
            fun publish(state: com.vibetoon.domain.raster.ExpandedLayerSnapshot.State) {
                VersionedRasterFiles.publish(root, { it.writeBytes(state.rgba) }, { it.length() == state.rgba.size.toLong() }) { path ->
                    ProjectJson.encode(ProjectData(ProjectMeta("p", "p", 100, 100), media = listOf(state.media.copy(file = path))))
                }
                val reopened = ProjectJson.decode(root.resolve("project.json").readText()).getOrThrow().media.single()
                assertEquals(state.media.copy(file = reopened.file), reopened)
                assertArrayEquals(state.rgba, root.resolve(reopened.file).readBytes())
            }
            publish(change.after)
            publish(history.transition(com.vibetoon.domain.raster.ExpandedLayerHistory.Direction.UNDO).target)
            publish(history.transition(com.vibetoon.domain.raster.ExpandedLayerHistory.Direction.REDO).target)
        } finally { root.deleteRecursively() }
    }

}
