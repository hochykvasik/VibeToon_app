package com.vibetoon

import com.vibetoon.domain.raster.TextureReplaceResult
import org.junit.Assert.assertEquals
import org.junit.Test

class TextureReplaceResultTest {
    @Test fun onlyZeroIsSuccessAndKnownFailuresAreDistinct() {
        assertEquals(TextureReplaceResult.SUCCESS, TextureReplaceResult.fromCode(0))
        assertEquals(TextureReplaceResult.INVALID_DATA, TextureReplaceResult.fromCode(-1))
        assertEquals(TextureReplaceResult.STALE_LAYER, TextureReplaceResult.fromCode(-2))
        assertEquals(TextureReplaceResult.UPLOADS_PENDING, TextureReplaceResult.fromCode(-3))
        assertEquals(TextureReplaceResult.LIMIT_EXCEEDED, TextureReplaceResult.fromCode(-4))
        assertEquals(TextureReplaceResult.CPU_ALLOCATION_FAILED, TextureReplaceResult.fromCode(-5))
        assertEquals(TextureReplaceResult.GL_FAILED, TextureReplaceResult.fromCode(-6))
        assertEquals(TextureReplaceResult.UNKNOWN_FAILURE, TextureReplaceResult.fromCode(1))
        assertEquals(TextureReplaceResult.UNKNOWN_FAILURE, TextureReplaceResult.fromCode(-100))
    }
}
