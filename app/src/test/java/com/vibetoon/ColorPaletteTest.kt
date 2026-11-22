package com.vibetoon

import com.vibetoon.domain.paint.ColorPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorPaletteTest {
    @Test fun newestFirstNoDuplicatesCapped() {
        var l = emptyList<Long>()
        for (i in 1..12) l = ColorPalette.add(l, 0xFF000000L + i)
        assertEquals(ColorPalette.MAX, l.size)
        assertEquals(0xFF00000CL, l.first())
        l = ColorPalette.add(l, 0xFF000009L)
        assertEquals(0xFF000009L, l.first())
        assertEquals(ColorPalette.MAX, l.size)
        assertEquals(l.size, l.toSet().size)
    }
    @Test fun roundTrip() {
        val l = listOf(0xFFFF0000L, 0xFF00FF00L)
        assertEquals(l, ColorPalette.decode(ColorPalette.encode(l)))
        assertEquals(emptyList<Long>(), ColorPalette.decode(null))
        assertEquals(emptyList<Long>(), ColorPalette.decode("zz,,"))
    }
}
