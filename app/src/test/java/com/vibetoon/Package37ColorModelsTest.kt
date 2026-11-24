package com.vibetoon

import com.vibetoon.domain.paint.ColorModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 37: точные модели RGB/HSB окна выбора цвета. */
class Package37ColorModelsTest {
    @Test fun primaryColorsConvertBothWays() {
        assertEquals(ColorModels.Rgb(255, 0, 0), ColorModels.hsbToRgb(0f, 1f, 1f))
        assertEquals(ColorModels.Rgb(0, 255, 0), ColorModels.hsbToRgb(120f, 1f, 1f))
        assertEquals(ColorModels.Rgb(0, 0, 255), ColorModels.hsbToRgb(240f, 1f, 1f))
        assertEquals(ColorModels.Rgb(255, 255, 0), ColorModels.hsbToRgb(60f, 1f, 1f))
        assertEquals(ColorModels.Rgb(255, 255, 255), ColorModels.hsbToRgb(77f, 0f, 1f))
        assertEquals(ColorModels.Rgb(0, 0, 0), ColorModels.hsbToRgb(200f, 0.5f, 0f))
        val c = ColorModels.rgbToHsb(0, 128, 255)
        assertEquals(209.88f, c.h, 0.05f)
        assertEquals(1f, c.s, 1e-4f)
        assertEquals(1f, c.v, 1e-4f)
        assertEquals(Triple(210, 100, 100), ColorModels.hsbInts(c))
    }

    @Test fun hueWrapsAndOutOfRangeIsClamped() {
        assertEquals(ColorModels.hsbToRgb(0f, 1f, 1f), ColorModels.hsbToRgb(360f, 1f, 1f))
        assertEquals(ColorModels.hsbToRgb(30f, 1f, 1f), ColorModels.hsbToRgb(-330f, 1f, 1f))
        assertEquals(ColorModels.Rgb(255, 255, 255), ColorModels.hsbToRgb(10f, -1f, 3f))
        assertEquals(ColorModels.Rgb(0, 0, 0), ColorModels.hsbToRgb(Float.NaN, 1f, 0f))
        assertEquals(0, ColorModels.hsbInts(ColorModels.Hsb(359.7f, 0f, 0f)).first)
    }

    @Test fun everyRgbSampleRoundTripsExactly() {
        // ползунок R/G/B ставит канал -> HSB -> обратно в RGB: число в поле не «уплывает»
        for (r in 0..255 step 15) for (g in 0..255 step 17) for (b in 0..255 step 5) {
            val h = ColorModels.rgbToHsb(r, g, b)
            assertEquals(ColorModels.Rgb(r, g, b), ColorModels.hsbToRgb(h.h, h.s, h.v))
        }
    }

    @Test fun greyAndBlackKeepPreviousHueAndSaturation() {
        val grey = ColorModels.rgbToHsb(128, 128, 128, keepHue = 200f)
        assertEquals(200f, grey.h, 0f)
        assertEquals(0f, grey.s, 0f)
        val black = ColorModels.hsbOf(0xFF000000L, keepHue = 33f, keepSat = 0.7f)
        assertEquals(33f, black.h, 0f)
        assertEquals(0.7f, black.s, 0f)
        assertEquals(0f, black.v, 0f)
    }

    @Test fun argbHexAndParsing() {
        assertEquals(0xFF1E90FFL, ColorModels.argb(ColorModels.Rgb(30, 144, 255)))
        assertEquals(ColorModels.Rgb(30, 144, 255), ColorModels.rgbOf(0xFF1E90FFL))
        assertEquals("#1E90FF", ColorModels.hex(0xFF1E90FFL))
        assertEquals(0xFF1E90FFL, ColorModels.parseHex("#1e90ff"))
        assertEquals(0xFF1E90FFL, ColorModels.parseHex("1E90FF"))
        assertEquals(0xFFFFAA00L, ColorModels.parseHex("#FA0"))
        assertNull(ColorModels.parseHex("#12345"))
        assertNull(ColorModels.parseHex("#GG0000"))
        assertEquals(255, ColorModels.parseChannel("300", 255))
        assertEquals(0, ColorModels.parseChannel(" 0 ", 100))
        assertNull(ColorModels.parseChannel("", 255))
        assertNull(ColorModels.parseChannel("abc", 255))
    }

    @Test fun modesHaveLabels() {
        assertEquals(listOf(ColorModels.MODE_WHEEL, ColorModels.MODE_RGB, ColorModels.MODE_HSB), ColorModels.MODES)
        assertEquals("RGB", ColorModels.modeLabel(ColorModels.MODE_RGB))
        assertEquals("HSB", ColorModels.modeLabel(ColorModels.MODE_HSB))
        assertTrue(ColorModels.modeLabel(ColorModels.MODE_WHEEL).isNotBlank())
    }
}
