package com.vibetoon

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.raster.RasterVersionPolicy
import org.junit.Assert.*
import org.junit.Test

class RasterVersionPolicyTest {
    private val old = MediaItem(100001, MediaKind.IMAGE, "ink", "assets/old.png", 2, 2, 0f, 0f, 2f, 2f)
    @Test fun historyIgnoresOnlyVersionNotGeometry() {
        val active = old.copy(file = "assets/raster_abc.png")
        assertTrue(RasterVersionPolicy.sameLayerIgnoringVersion(active, old))
        assertFalse(RasterVersionPolicy.sameLayerIgnoringVersion(active.copy(w = 4f), old))
        assertEquals(active.file, RasterVersionPolicy.keepCurrentFile(old, active, true).file)
        assertEquals(old.file, RasterVersionPolicy.keepCurrentFile(old, active, false).file)
        assertTrue(RasterVersionPolicy.isVersionPath(active.file))
        assertFalse(RasterVersionPolicy.isVersionPath(old.file))
    }
    @Test fun textureDimensionsMustMatchMetadataBeforeVersionCommit() {
        val bytes = ByteArray(24).also { it[0] = 2; it[4] = 2 }
        assertTrue(RasterVersionPolicy.matchesDimensions(bytes, 2, 2))
        assertFalse(RasterVersionPolicy.matchesDimensions(bytes, 4, 2))
        assertFalse(RasterVersionPolicy.matchesDimensions(bytes.copyOf(23), 2, 2))
    }
}
