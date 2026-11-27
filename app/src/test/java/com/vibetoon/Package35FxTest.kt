package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.EffectCategory
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.EffectTracks
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 35: неразрушающие эффекты Inner Glow, Halftone, Line Boil - модель, ключи, project.json, совместимость. */
class Package35FxTest {
    private val layer = 100_001
    private val newTypes = listOf(EffectMath.INNER_GLOW, EffectMath.HALFTONE, EffectMath.LINE_BOIL)

    @Test fun typesAreRegisteredWithUniqueIdsAndShaderRecords() {
        assertEquals(listOf(22, 23, 24), newTypes)
        val types = EffectMath.DEFS.map { it.type }
        assertEquals(types.size, types.toSet().size)
        for (t in newTypes) {
            val d = EffectMath.def(t)
            assertNotNull(d)
            assertTrue("≤ 8 параметров: ${d!!.title}", d.params.size <= EffectTracks.PARAMS)
            assertTrue(d.hint.isNotEmpty())
            assertFalse(EffectMath.isTransform(t))
            val r = EffectMath.records(listOf(EffectMath.create(t)!!), 0f)
            assertEquals(1, r.size)
            assertEquals(t.toFloat(), r[0][0], 0f)
        }
        assertEquals(EffectCategory.STYLE, EffectMath.def(EffectMath.INNER_GLOW)!!.category)
        assertEquals(EffectCategory.STYLE, EffectMath.def(EffectMath.HALFTONE)!!.category)
        assertEquals(EffectCategory.NOISE_MOTION, EffectMath.def(EffectMath.LINE_BOIL)!!.category)
        // Цвет - три подряд идущих параметра (пипетка/палитра в инспекторе).
        assertEquals(0, EffectMath.def(EffectMath.INNER_GLOW)!!.colorAt)
        assertEquals(5, EffectMath.def(EffectMath.HALFTONE)!!.colorAt)
        for (t in listOf(EffectMath.INNER_GLOW, EffectMath.HALFTONE)) {
            val d = EffectMath.def(t)!!
            assertEquals(listOf("R", "G", "B"), d.params.subList(d.colorAt, d.colorAt + 3).map { it.name })
        }
    }

    @Test fun defaultsAreInsideRangesAndClampHandlesGarbage() {
        for (t in newTypes) {
            val d = EffectMath.def(t)!!
            val e = EffectMath.create(t)!!
            for ((i, p) in d.params.withIndex()) {
                assertTrue("${d.title}.${p.name}", e.p[i] in p.min..p.max)
                assertEquals(p.max, EffectMath.clamp(t, i, 1e9f), 0f)
                assertEquals(p.min, EffectMath.clamp(t, i, -1e9f), 0f)
                assertEquals(p.def, EffectMath.clamp(t, i, Float.NaN), 0f)
                if (p.options.isNotEmpty()) assertEquals(p.options.lastIndex.toFloat(), p.max, 0f)
            }
        }
        // Варианты и целые параметры не остаются дробными после загрузки/ввода.
        assertEquals(2f, EffectMath.clamp(EffectMath.HALFTONE, 4, 1.6f), 0f)
        assertEquals(3f, EffectMath.clamp(EffectMath.LINE_BOIL, 4, 3.4f), 0f)
        assertEquals(1.5f, EffectMath.clamp(EffectMath.INNER_GLOW, 5, 1.5f), 0f)   // интенсивность - непрерывная
        assertEquals(EffectMath.HT_RGB, EffectMath.option(EffectMath.HALFTONE, 4, 99f))
        assertEquals(EffectMath.HT_MONO, EffectMath.option(EffectMath.HALFTONE, 4, Float.NaN))
        assertEquals(EffectMath.HALFTONE_MODES.size, 5)
        assertEquals(EffectMath.GLOW_MODES.size, 4)
        assertEquals(EffectMath.BOIL_NOISE.size, 3)
    }

    @Test fun everyNumericAndColorParameterIsKeyframableWithAllEasings() {
        for (t in newTypes) {
            val d = EffectMath.def(t)!!
            val fx = EffectTracks.assignSlots(listOf(EffectMath.create(t)!!))
            for ((i, p) in d.params.withIndex()) {
                val id = EffectTracks.id(layer, fx[0].slot, i)
                val base = Animation().withKey(id, 0, LayerTransform(dx = p.min)).withKey(id, 10, LayerTransform(dx = p.max))
                val lin = EffectTracks.at(layer, fx, base, 5f).single().p[i]
                assertEquals("${d.title}.${p.name}", (p.min + p.max) / 2f, lin, 1e-3f)
                val hold = EffectTracks.at(layer, fx, base.withEasing(id, 0, EasingType.STEP), 9.9f).single().p[i]
                assertEquals(p.min, hold, 1e-4f)
                val easeIn = EffectTracks.at(layer, fx, base.withEasing(id, 0, EasingType.EASE_IN), 3f).single().p[i]
                val easeOut = EffectTracks.at(layer, fx, base.withEasing(id, 0, EasingType.EASE_OUT), 3f).single().p[i]
                if (p.max > p.min) assertTrue("${p.name}: ease in < ease out", easeIn < easeOut)
                val bez = EffectTracks.at(layer, fx, base.withCurve(id, 0, listOf(0.1f, 0.9f, 0.2f, 1f)), 3f).single().p[i]
                if (p.max > p.min) assertTrue("${p.name}: bezier", bez > lin && bez <= p.max)
                assertEquals(p.max, EffectTracks.at(layer, fx, base, 10f).single().p[i], 1e-4f)
            }
        }
    }

    @Test fun projectJsonRoundTripKeepsEffectsKeysAndOptions() {
        val glow = EffectMath.create(EffectMath.INNER_GLOW)!!.let { it.copy(p = it.p.toMutableList().also { p -> p[3] = 42f; p[6] = 3f; p[7] = 1f }) }
        val ht = EffectMath.create(EffectMath.HALFTONE)!!.let { it.copy(p = it.p.toMutableList().also { p -> p[0] = 18f; p[4] = EffectMath.HT_CMYK.toFloat() }) }
        val boil = EffectMath.create(EffectMath.LINE_BOIL)!!
        val fx = EffectTracks.assignSlots(listOf(glow, ht, boil))
        val ampId = EffectTracks.id(layer, fx[2].slot, 0)
        val colorId = EffectTracks.id(layer, fx[0].slot, 1)
        val anim = Animation().withKey(ampId, 0, LayerTransform(dx = 1f)).withKey(ampId, 24, LayerTransform(dx = 9f)).withEasing(ampId, 0, EasingType.EASE_IN_OUT)
            .withKey(colorId, 0, LayerTransform(dx = 0f)).withKey(colorId, 12, LayerTransform(dx = 1f))
        val d = ProjectData(ProjectMeta("p", "fx35", 200, 100), tracks = anim.tracks, fx = mapOf(layer to fx))
        val back = ProjectJson.decode(ProjectJson.encode(d)).getOrThrow()
        val list = back.fx.getValue(layer)
        assertEquals(fx.map { it.type }, list.map { it.type })
        assertEquals(fx.map { it.slot }, list.map { it.slot })
        for ((a, b) in fx.zip(list)) assertEquals(a.p, b.p)
        val ba = back.animation()
        assertEquals(5f, EffectTracks.at(layer, list, ba, 12f)[2].p[0], 1e-3f)
        assertEquals(0.5f, EffectTracks.at(layer, list, ba, 6f)[0].p[1], 1e-3f)
        assertEquals(EasingType.EASE_IN_OUT, ba.easingAt(ampId, 0))
        // Сброс: эффект по умолчанию снова равен create().
        assertEquals(EffectMath.create(EffectMath.INNER_GLOW)!!.p, EffectMath.def(EffectMath.INNER_GLOW)!!.params.map { it.def })
    }

    @Test fun oldProjectsLoadWithoutNewEffectsAndUnknownTypesAreDropped() {
        // Старый проект без эффектов спринта 35 - загружается как был.
        val old = ProjectData(ProjectMeta("p", "old", 100, 100), fx = mapOf(layer to listOf(EffectMath.create(EffectMath.BLUR)!!)))
        val oldBack = ProjectJson.decode(ProjectJson.encode(old)).getOrThrow()
        assertEquals(listOf(EffectMath.BLUR), oldBack.fx.getValue(layer).map { it.type })
        // Файл из будущей версии с неизвестным типом: неизвестный отбрасывается, известные и новые остаются.
        val withFuture = ProjectData(ProjectMeta("p", "new", 100, 100),
            fx = mapOf(layer to EffectTracks.assignSlots(listOf(EffectMath.create(EffectMath.HALFTONE)!!, Effect(99, List(8) { 0f }), EffectMath.create(EffectMath.LINE_BOIL)!!))))
        val back = ProjectJson.decode(ProjectJson.encode(withFuture)).getOrThrow()
        assertEquals(listOf(EffectMath.HALFTONE, EffectMath.LINE_BOIL), back.fx.getValue(layer).map { it.type })
        // Повреждённые параметры: NaN -> значение по умолчанию, выход за пределы -> граница.
        val json = ProjectJson.encode(ProjectData(ProjectMeta("p", "bad", 100, 100), fx = mapOf(layer to listOf(EffectMath.create(EffectMath.INNER_GLOW)!!))))
        val broken = json.replace(Regex("\"p\"\\s*:\\s*\\[[^\\]]*\\]"), "\"p\":[5,-3,0.5,9999,0.2,1,7,0.4]")
        val glow = ProjectJson.decode(broken).getOrThrow().fx.getValue(layer).single()
        assertEquals(listOf(1f, 0f, 0.5f, 150f, 0.2f, 1f, 3f, 0f), glow.p)
    }

    @Test fun lineBoilIsQuantizedToItsOwnRateAndLoopsStably() {
        // 8 fps дрожания при проекте 24 fps: рисунок меняется раз в 3 кадра проекта.
        val steps = (0 until 24).map { EffectMath.boilStep(it / 24f, 8f, 0f) }
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 2, 2, 2), steps.take(9))
        assertEquals(7, steps.last())
        // Цикл из 3 вариантов: 0,1,2,0,1,2...
        assertEquals(listOf(0, 1, 2, 0, 1, 2), (0 until 6).map { EffectMath.boilStep(it / 8f, 8f, 3f) })
        // Частота дрожания выше fps проекта: каждый кадр - свой шаг, без ошибок округления на границах.
        for (f in 0 until 240) assertEquals(f, EffectMath.boilStep(f / 24f, 24f, 0f))
        // Записи: внутри шага одинаковые побайтно (кэш эффектов и групп не сбрасывается), между шагами - разные.
        val boil = EffectMath.create(EffectMath.LINE_BOIL)!!   // 8 fps, цикл 3
        val r = (0 until 48).map { EffectMath.records(listOf(boil), it / 24f).single() }
        assertArrayEquals(r[0], r[1], 0f)
        assertArrayEquals(r[0], r[2], 0f)
        assertNotEquals(r[2][9], r[3][9])
        // Повтор цикла (3 варианта x 3 кадра = 9 кадров) и пауза дают тот же рисунок.
        for (f in 0 until 39) assertArrayEquals("кадр $f", r[f], r[f + 9], 0f)
        assertArrayEquals(EffectMath.records(listOf(boil), 1.25f).single(), EffectMath.records(listOf(boil), 1.25f).single(), 0f)
        // Время записи совпадает с расчётом шейдера: floor(t * fps + 1e-4) даёт тот же шаг.
        for (f in 0 until 48) {
            val t = r[f][9]
            assertEquals(EffectMath.boilStep(f / 24f, 8f, 3f), kotlin.math.floor(t * 8f + 1e-4f).toInt() % 3)
        }
        // Без цикла время не растёт бесконечно (точность hash в шейдере), прочие эффекты получают время как есть.
        val noLoop = boil.copy(p = boil.p.toMutableList().also { it[4] = 0f })
        assertTrue(EffectMath.records(listOf(noLoop), 100_000f).single()[9] < 4096f / 8f + 1f)
        assertEquals(1.37f, EffectMath.records(listOf(EffectMath.create(EffectMath.NOISE)!!), 1.37f).single()[9], 0f)
        // Мусорные значения не роняют расчёт.
        assertEquals(0, EffectMath.boilStep(Float.NaN, Float.NaN, Float.NaN))
        assertEquals(0, EffectMath.boilStep(-5f, 8f, 3f))
    }

    @Test fun innerGlowStaysInsideAndHalftoneMathIsMonotonic() {
        // Внутреннее свечение: у края (размытая альфа 0.5) - полное, в глубине - нет, снаружи альфа слоя не меняется (шейдер).
        assertEquals(1f, EffectMath.innerGlowMask(0.5f, 0f, false), 1e-5f)
        assertEquals(0f, EffectMath.innerGlowMask(1f, 0f, false), 1e-5f)
        assertEquals(1f, EffectMath.innerGlowMask(1f, 0f, true), 1e-5f)
        assertTrue(EffectMath.innerGlowMask(0.8f, 0.6f, false) > EffectMath.innerGlowMask(0.8f, 0f, false))
        var prev = 2f
        for (i in 0..20) {
            val m = EffectMath.innerGlowMask(0.5f + i / 40f, 0.3f, false)
            assertTrue(m <= prev + 1e-6f); prev = m
        }
        // Полутон: контраст/сдвиг, радиус точки растёт с краской, 0 - точки нет, 1 - ячейка закрыта.
        assertEquals(0.5f, EffectMath.halftoneInk(0.5f, 2f, 0f), 1e-6f)
        assertEquals(1f, EffectMath.halftoneInk(0.8f, 3f, 0f), 1e-6f)
        assertEquals(0.7f, EffectMath.halftoneInk(0.5f, 1f, 0.2f), 1e-6f)
        assertEquals(0f, EffectMath.halftoneRadius(0f), 0f)
        assertTrue(EffectMath.halftoneRadius(1f) >= 0.7071f)
        var r = -1f
        for (i in 0..10) { val v = EffectMath.halftoneRadius(i / 10f); assertTrue(v > r); r = v }
    }
}
