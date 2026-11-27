package com.vibetoon

import com.vibetoon.domain.paint.BrushExchange
import com.vibetoon.domain.paint.BrushParams
import com.vibetoon.domain.paint.BrushPreset
import com.vibetoon.domain.paint.BrushPresets
import com.vibetoon.domain.paint.BrushShape
import com.vibetoon.domain.paint.DabRotation
import com.vibetoon.domain.paint.ExchangeError
import com.vibetoon.domain.paint.GrainKind
import com.vibetoon.domain.paint.GrainMode
import com.vibetoon.domain.paint.PaletteExchange
import com.vibetoon.domain.paint.StrokeMode
import com.vibetoon.domain.paint.UserPalette
import com.vibetoon.domain.settings.SettingsBackup
import org.junit.Assert.*
import org.junit.Test

/** Спринт 39: экспорт/импорт своих кистей и палитр, проверка файлов, сообщения и резервная копия. */
class Package39BrushPaletteExchangeTest {
    private val custom = BrushPreset(
        "user_1", "Мой карандаш",
        BrushParams(size = 33f, spacing = 0.15f, minSizePercent = 0.2f, flow = 0.5f, opacity = 0.8f, hardness = 0.6f, mode = StrokeMode.ACCUMULATE,
            shape = BrushShape.SQUARE, grain = GrainKind.PAPER, grainMode = GrainMode.CANVAS, grainScale = 2f, grainContrast = 1.5f, roundness = 0.5f,
            curve = 1, rotation = DabRotation.RANDOM, taperPx = 40f, pixelSnap = true, presetId = "user_1")
    )

    @Test
    fun brushesRoundTripWithAllSettings() {
        val text = BrushExchange.encode(listOf(custom))
        assertTrue(text.contains("\"format\": \"vibetoon-brushes\""))
        val r = BrushExchange.decode(text)
        assertTrue(r.ok)
        assertEquals(0, r.skipped)
        val b = r.items.single()
        assertEquals("user_1", b.id)
        assertEquals("Мой карандаш", b.title)
        assertEquals(custom.params, b.params)
        assertEquals("Импортировано кистей: 1", BrushExchange.report(r))
    }

    @Test
    fun importNeverOverwritesExistingOrBuiltInBrushes() {
        val text = BrushExchange.encode(listOf(custom, custom.copy(id = "pen", title = "Чужое перо")))
        val r = BrushExchange.decode(text, existingIds = setOf("user_1"))
        assertEquals(2, r.items.size)
        assertTrue(r.items.none { it.id == "user_1" || it.id == "pen" })
        assertTrue(r.items.all { BrushExchange.isCustom(it.id) && it.params.presetId == it.id })
        assertEquals(r.items.size, r.items.map { it.id }.toSet().size)
        assertTrue(BrushPresets.all().none { BrushExchange.isCustom(it.id) })
    }

    @Test
    fun clearErrorsForBadFiles() {
        assertEquals(ExchangeError.EMPTY, BrushExchange.decode("   ").error)
        assertEquals(ExchangeError.NOT_JSON, BrushExchange.decode("{oops").error)
        assertEquals(ExchangeError.NOT_BRUSHES, BrushExchange.decode("{\"format\":\"vibetoon-settings\",\"version\":1}").error)
        assertEquals(ExchangeError.NOT_BRUSHES, BrushExchange.decode("[1,2]").error)
        assertEquals(ExchangeError.NEWER_VERSION, BrushExchange.decode("{\"format\":\"vibetoon-brushes\",\"version\":99,\"brushes\":[]}").error)
        assertEquals(ExchangeError.NO_BRUSHES, BrushExchange.decode("{\"format\":\"vibetoon-brushes\",\"version\":1,\"brushes\":[]}").error)
        assertEquals(ExchangeError.TOO_BIG, BrushExchange.decode("x".repeat(BrushExchange.MAX_BYTES + 1)).error)
        // перепутанный файл - подсказка, куда его импортировать
        assertEquals(ExchangeError.PALETTE_IN_BRUSHES, BrushExchange.decode(PaletteExchange.encode(listOf(UserPalette("A", listOf(0xFFFF0000L))))).error)
        assertEquals(ExchangeError.PALETTE_IN_BRUSHES, BrushExchange.decode("GIMP Palette\n255 0 0 red\n").error)
        assertEquals(ExchangeError.BRUSHES_IN_PALETTE, PaletteExchange.decode(BrushExchange.encode(listOf(custom))).error)
        for (e in ExchangeError.values()) assertTrue(e.ru.isNotBlank())
        assertEquals(ExchangeError.NOT_JSON.ru, BrushExchange.report(BrushExchange.decode("{oops")))
    }

    @Test
    fun damagedEntriesAreSkippedAndCounted() {
        val text = """{"format":"vibetoon-brushes","version":1,"brushes":[
            {"id":"a","title":"Хорошая","params":{"size":5000,"hardness":-3,"mode":"accumulate"}},
            {"id":"b","title":"Плохой режим","params":{"mode":"LASER"}},
            {"id":"c","title":"Не число","params":{"size":"big"}},
            {"id":"d","title":"Без настроек"},
            42
        ]}"""
        val r = BrushExchange.decode(text)
        assertTrue(r.ok)
        assertEquals(1, r.items.size)
        assertEquals(4, r.skipped)
        val p = r.items[0].params
        assertEquals(1500f, p.size, 0f)          // числа приводятся к допустимым пределам
        assertEquals(0f, p.hardness, 0f)
        assertEquals(StrokeMode.ACCUMULATE, p.mode)
        assertEquals("Импортировано кистей: 1, пропущено повреждённых: 4", BrushExchange.report(r))
    }

    @Test
    fun titlesAreCleanedAndErasersKeepTheirMode() {
        val text = """{"format":"vibetoon-brushes","version":1,"brushes":[{"title":"  \u0007${"Я".repeat(80)}","params":{"mode":"ERASE"}},{"params":{}}]}"""
        val r = BrushExchange.decode(text)
        assertEquals(2, r.items.size)
        assertEquals(BrushExchange.MAX_TITLE, r.items[0].title.length)
        assertEquals(StrokeMode.ERASE, r.items[0].params.mode)
        assertEquals("Своя кисть", r.items[1].title)
    }

    @Test
    fun saveCurrentAndMergeKeepLimits() {
        val cur = BrushExchange.fromCurrent(BrushParams(size = 12f, color = 0xFFFF0000.toInt()), "Тест", setOf("user_1"))
        assertEquals("user_2", cur.id)
        assertEquals(BrushParams().color, cur.params.color)   // цвет не входит в кисть
        val many = (1..150).map { custom.copy(id = "user_x$it") }
        assertEquals(BrushExchange.MAX_BRUSHES, BrushExchange.merge(emptyList(), many).size)
        assertEquals("Своя кисть 1", BrushExchange.suggestName(BrushParams(), emptyList()))
        assertEquals("Свой ластик 1", BrushExchange.suggestName(BrushParams(mode = StrokeMode.ERASE), listOf(custom)))
        assertTrue(BrushExchange.load(null).isEmpty())
        assertTrue(BrushExchange.load("garbage").isEmpty())
        assertEquals(listOf(custom), BrushExchange.load(BrushExchange.encode(listOf(custom))))
    }

    @Test
    fun palettesRoundTripAndAlphaIsKept() {
        val pals = listOf(UserPalette("Кожа", listOf(0xFFFFCC99L, 0x80112233L)), UserPalette("Ночь", listOf(0xFF000000L)))
        val text = PaletteExchange.encode(pals)
        assertTrue(text.contains("#FFCC99")); assertTrue(text.contains("#80112233"))
        val r = PaletteExchange.decode(text)
        assertTrue(r.ok)
        assertEquals(pals, r.items)
        assertEquals("Импортировано палитр: 2, цветов: 3", PaletteExchange.report(r))
    }

    @Test
    fun gimpAndHexPalettesAreAccepted() {
        val gpl = "GIMP Palette\nName: Sunset\nColumns: 4\n# comment\n255 128   0\tOrange\n 10 20 30 Dark\nbad line\n"
        val g = PaletteExchange.decode(gpl)
        assertTrue(g.ok)
        assertEquals("Sunset", g.items[0].name)
        assertEquals(listOf(0xFFFF8000L, 0xFF0A141EL), g.items[0].colors)
        assertEquals(1, g.skipped)
        val h = PaletteExchange.decode("#ff0000, 00ff00\n#00F zzz", "Импорт")
        assertEquals(listOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL), h.items[0].colors)
        assertEquals("Импорт", h.items[0].name)
        assertEquals(1, h.skipped)
        assertEquals(ExchangeError.NOT_PALETTE, PaletteExchange.decode("просто текст").error)
        assertEquals(ExchangeError.NO_COLORS, PaletteExchange.decode("GIMP Palette\nName: X\n").error)
        assertEquals(ExchangeError.NO_COLORS, PaletteExchange.decode("{\"format\":\"vibetoon-palettes\",\"version\":1,\"palettes\":[{\"name\":\"a\",\"colors\":[\"nope\"]}]}").error)
        assertEquals(ExchangeError.NEWER_VERSION, PaletteExchange.decode("{\"format\":\"vibetoon-palettes\",\"version\":2,\"palettes\":[]}").error)
    }

    @Test
    fun paletteLimitsAndMerge() {
        val big = UserPalette("Большая", (0 until 200).map { 0xFF000000L or it.toLong() })
        val r = PaletteExchange.decode(PaletteExchange.encode(listOf(big, big.copy(colors = listOf(0xFFFF0000L, 0xFFFF0000L)))))
        assertEquals(PaletteExchange.MAX_COLORS, r.items[0].colors.size)
        assertEquals(1, r.items[1].colors.size)     // повторы убраны
        val merged = PaletteExchange.merge(listOf(UserPalette("A", listOf(1L))), listOf(UserPalette("A", listOf(2L)), UserPalette("A", listOf(3L))))
        assertEquals(listOf("A", "A (2)", "A (3)"), merged.map { it.name })
        assertEquals(0xFFAABBCCL, PaletteExchange.parseHex("#abc".replace("abc", "AABBCC")))
        assertEquals(0xFFAABBCCL, PaletteExchange.parseHex("ABC"))
        assertNull(PaletteExchange.parseHex("#12345"))
    }

    @Test
    fun customBrushesAndPalettesTravelInSettingsBackup() {
        val prefs = mapOf<String, Any?>(
            BrushExchange.PREF_KEY to BrushExchange.encode(listOf(custom)),
            PaletteExchange.PREF_KEY to PaletteExchange.encode(listOf(UserPalette("Кожа", listOf(0xFFFFCC99L)))),
            "layers_view" to "list"
        )
        val back = SettingsBackup.decode(SettingsBackup.encode(prefs, colors = mapOf("recent" to "ffff0000,ff00ff00", "mode" to 1)))!!
        assertEquals(listOf(custom), BrushExchange.load(back[BrushExchange.PREF_KEY] as String))
        assertEquals("Кожа", PaletteExchange.load(back[PaletteExchange.PREF_KEY] as String).single().name)
        assertEquals("list", back["layers_view"])
        val colors = SettingsBackup.decodeColors(SettingsBackup.encode(prefs, colors = mapOf("recent" to "ffff0000", "mode" to 1)))
        assertEquals("ffff0000", colors["recent"])
        assertEquals(1, colors["mode"])
        // старые копии без раздела цветов читаются как раньше
        assertTrue(SettingsBackup.decodeColors(SettingsBackup.encode(prefs)).isEmpty())
    }
}
