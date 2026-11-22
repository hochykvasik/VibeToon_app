package com.vibetoon

import com.vibetoon.nativebridge.LayerKind
import org.junit.Assert.assertEquals
import org.junit.Test

class LayerKindTest {
    @Test fun knownCodes() {
        assertEquals(LayerKind.LAYER, LayerKind.fromCode(0))
        assertEquals(LayerKind.GROUP_OPEN, LayerKind.fromCode(1))
        assertEquals(LayerKind.GROUP_CLOSED, LayerKind.fromCode(2))
        assertEquals(LayerKind.GROUP_END, LayerKind.fromCode(3))
    }

    @Test fun unknownCodeIsLayer() {
        assertEquals(LayerKind.LAYER, LayerKind.fromCode(99))
    }
}
