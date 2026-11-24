package com.vibetoon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 37: ползунки RGB/HSB, настройки кальки и Play Once подключены к интерфейсу, VM, экспорту и переводам. */
class Sprint37WiringTest {
    private fun src(path: String): String = (File(path).takeIf { it.isFile } ?: File("app/$path")).readText()
    private val vm by lazy { src("src/main/java/com/vibetoon/ui/MainViewModel.kt") }
    private val picker by lazy { src("src/main/java/com/vibetoon/ui/inspector/ColorPicker.kt") }
    private val onion by lazy { src("src/main/java/com/vibetoon/ui/timeline/OnionSkinDialog.kt") }
    private val timeline by lazy { src("src/main/java/com/vibetoon/ui/timeline/TimelinePanel.kt") }
    private val symbols by lazy { src("src/main/java/com/vibetoon/ui/SymbolDialogs.kt") }
    private val en by lazy { src("src/main/res/values-en/strings.xml") }

    @Test fun colorPickerHasRgbAndHsbSliders() {
        assertTrue(picker.contains("ColorModels.MODE_RGB ->"))
        assertTrue(picker.contains("ColorModels.MODE_HSB ->"))
        assertTrue(picker.contains("ChannelSlider(\"R\""))
        assertTrue(picker.contains("ChannelSlider(\"H\""))
        assertTrue(picker.contains("prefs.edit().putInt(\"mode\", m)"))
        assertTrue(picker.contains("ColorModels.parseHex(clean)"))
        // вся математика цвета - в ColorModels (без android.graphics.Color.colorToHSV)
        assertFalse(picker.contains("colorToHSV"))
    }

    @Test fun onionSettingsAreWiredAndPersisted() {
        assertTrue(vm.contains("OnionSkin.plan(m.cells, _frame.value, _onionSettings.value)"))
        assertTrue(vm.contains("putString(\"onion_settings\", com.vibetoon.domain.project.OnionSkin.encode(n))"))
        assertTrue(vm.contains("ghostTransform(g)"))
        assertTrue(vm.contains("if (!_onionSettings.value.outOfPegs) return effective(g.owner)"))
        assertTrue(vm.contains("if (g.tint) out[g.id]"))
        assertTrue(timeline.contains("OnionSkinDialog(vm)"))
        assertTrue(onion.contains("s.copy(outOfPegs = it)"))
        assertTrue(onion.contains("s.copy(beforeColor = it)"))
        assertTrue(onion.contains("s.copy(afterAlpha = it)"))
    }

    @Test fun playOnceUsesOneRuleForCanvasPlayAndExport() {
        assertTrue(vm.contains("return SymbolForms.frameAt(inst.timeKeys, f, def.symDur, mode != SymbolPlayback.HOLD && symbolCycles(def.id), mode, symbolStart(inst.id), def.markers)"))
        assertTrue(vm.contains("SymbolForms.frameAt(si.timeKeys, pf, def.first, def.second && mode != SymbolPlayback.HOLD, mode, clipsCopy[si.id]?.start ?: 0"))
        assertTrue(vm.contains("SymbolPlayback.needsAllFrames(symbolPlaybackOf(inst), false, symbolCycles(def.id))"))
        assertTrue(vm.contains("it.copy(symLoop = loop, symOnce = once)"))
        assertTrue(symbols.contains("vm.setSymbolPlayback(instId, mode)"))
        assertFalse(vm.contains("symLoops[si.id]"))
    }

    @Test fun newStringsAreTranslated() {
        for (s in listOf("Play Once", "Drawings before (previous)", "Out of Pegs: ghosts stay in place", "Tint with color", "Falloff: farther drawings are fainter", "HSV wheel", "Without keys the symbol plays once and holds its last frame")) assertTrue(s, en.contains(s))
    }
}
