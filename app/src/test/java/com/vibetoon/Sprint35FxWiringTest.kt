package com.vibetoon

import com.vibetoon.domain.anim.EffectMath
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Спринт 35: новые эффекты подключены к шейдеру, охвату (scissor/кэш), библиотеке, инспектору и словарю RU/EN. */
class Sprint35FxWiringTest {
    /** Пути от модуля app (рабочая папка юнит-тестов Gradle). */
    private fun src(path: String): String = File(path).readText()

    @Test fun shaderHasBranchesForEveryNewEffect() {
        val r = src("src/main/cpp/render/Renderer.cpp")
        for (t in listOf(EffectMath.INNER_GLOW, EffectMath.HALFTONE, EffectMath.LINE_BOIL)) assertTrue("uMode == $t", r.contains("uMode == $t)"))
        // Inner Glow не меняет альфу слоя (не выходит за контур); Line Boil берёт шаг из своего fps, а не из fps проекта.
        assertTrue(r.contains("o = vec4(mix(col, r, a) * c.a, c.a);"))
        assertTrue(r.contains("floor(max(uView.w, 0.0) * clamp(uP0.z, 0.1, 24.0) + 0.0001)"))
    }

    @Test fun reachIsBoundedSoScissorAndFxCacheStayOn() {
        val b = src("src/main/cpp/render/LayerBounds.h")
        assertTrue(b.contains("case 22: return f(4);"))
        assertTrue(b.contains("case 23: return f(1);"))
        assertTrue(b.contains("case 24: return f(1);"))
        val t = src("src/test/cpp/LayerBoundsTest.cpp")
        assertTrue(t.contains("effectReach(boil)"))
    }

    @Test fun libraryPreviewInspectorHintAndDictionary() {
        val lib = src("src/main/java/com/vibetoon/ui/inspector/EffectLibrary.kt")
        for (n in listOf("EffectMath.INNER_GLOW ->", "EffectMath.HALFTONE ->", "EffectMath.LINE_BOIL ->")) assertTrue(n, lib.contains(n))
        val insp = src("src/main/java/com/vibetoon/ui/inspector/InspectorPanel.kt")
        assertTrue(insp.contains("L(def.hint)"))
        assertTrue(insp.contains("if (p.whole) kotlin.math.round(it) else it"))
        val pairs = src("../tools/i18n/pairs2.py")
        val old = src("../tools/i18n/pairs.py")
        for (d in listOf(EffectMath.INNER_GLOW, EffectMath.HALFTONE, EffectMath.LINE_BOIL).map { EffectMath.def(it)!! }) {
            for (ru in listOf(d.title, d.hint) + d.params.flatMap { listOf(it.name) + it.options }) {
                if (ru.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) assertTrue("RU/EN: $ru", pairs.contains("\n$ru|") || old.contains("\n$ru|"))
            }
        }
    }
}
