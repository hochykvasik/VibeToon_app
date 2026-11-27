package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.BoneChainKeys
import com.vibetoon.domain.anim.DeformAutoKey
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.anim.TimelineZoom
import com.vibetoon.domain.export.BackgroundRender
import com.vibetoon.domain.project.MediaAutoExtend
import com.vibetoon.domain.project.PaintSaveRetry
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.ResizeSettle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 41: чистая логика - автопродление под видео, длительность временем, автоключи деформаций и цепочек костей, фон. */
class Sprint41Test {
    private fun t(dx: Float = 0f, dy: Float = 0f, rot: Float = 0f) = LayerTransform(dx = dx, dy = dy, rotation = rot)

    // ---------------------------------------------------------------- 1. автопродление под аудио и видео

    @Test fun mediaExtendsProjectToItsEnd() {
        assertEquals(240, MediaAutoExtend.framesFor(10_000L, 24))
        assertEquals(241, MediaAutoExtend.framesFor(10_001L, 24))   // последний неполный кадр включается
        assertEquals(0, MediaAutoExtend.framesFor(0L, 24))
        assertEquals(MediaAutoExtend.Plan(240, false), MediaAutoExtend.plan(120, 10_000L, 24, enabled = true))
        assertNull("выключено в настройках", MediaAutoExtend.plan(120, 10_000L, 24, enabled = false))
        assertNull("проект уже длиннее", MediaAutoExtend.plan(300, 10_000L, 24, enabled = true))
        assertNull("длина неизвестна", MediaAutoExtend.plan(120, 0L, 24, enabled = true))
        // видео длиннее предела - проект продлевается до предела, конец помечается обрезанным
        val huge = MediaAutoExtend.plan(120, 2L * 60 * 60 * 1000, 30, enabled = true)!!
        assertEquals(TimeFormat.MAX_DURATION_FRAMES, huge.frames)
        assertTrue(huge.clipped)
        assertEquals(MediaAutoExtend.Plan(300, false), MediaAutoExtend.planFrames(100, 300, true))
        assertNull(MediaAutoExtend.planFrames(TimeFormat.MAX_DURATION_FRAMES, TimeFormat.MAX_DURATION_FRAMES + 5, true))
    }

    // ---------------------------------------------------------------- 3. длительность временем, без предела 5000

    @Test fun durationLimitIsTimeBasedNotFiveThousandFrames() {
        assertTrue(TimeFormat.MAX_DURATION_FRAMES > 5000)
        assertTrue("10 минут при 120 fps", TimeFormat.maxSeconds(120) >= 600)
        assertEquals(3000, TimeFormat.maxSeconds(24))
        assertEquals("10:00", TimeFormat.formatDuration(TimeFormat.MAX_DURATION_FRAMES, 120))
        assertEquals("75:00", TimeFormat.formatDuration(75 * 60 * 24, 24))
    }

    @Test fun durationIsParsedAsMinutesSecondsFrames() {
        assertEquals(90 * 24, TimeFormat.parseDuration("1:30", 24))
        assertEquals(90 * 24, TimeFormat.parseDuration(" 1.30 ", 24))
        assertEquals(5 * 24 + 12, TimeFormat.parseDuration("0:05:12", 24))
        assertEquals(10 * 60, TimeFormat.parseDuration("10", 60))   // одно число - секунды
        assertNull(TimeFormat.parseDuration("1:75", 24))             // секунд больше 59
        assertNull(TimeFormat.parseDuration("0:01:24", 24))          // кадров не меньше fps
        assertNull(TimeFormat.parseDuration("", 24))
        assertNull(TimeFormat.parseDuration("1::2", 24))
        assertNull(TimeFormat.parseDuration("0:00", 24))             // меньше одного кадра
        assertEquals("01:30", TimeFormat.durationText(90 * 24, 24))
        assertEquals(TimeFormat.format(5 * 24 + 12, 24), TimeFormat.durationText(5 * 24 + 12, 24))
        for (fps in listOf(1, 12, 24, 25, 30, 60, 120)) for (sec in listOf(1, 59, 61, 600))
            assertEquals(sec * fps, TimeFormat.parseDuration(TimeFormat.durationText(sec * fps, fps), fps))
    }

    @Test fun changingFpsKeepsDurationInTime() {
        assertEquals(1200, TimeFormat.reframe(240, 24, 120))   // 10 с при 24 -> 10 с при 120
        assertEquals(250, TimeFormat.reframe(240, 24, 25))
        assertEquals(1, TimeFormat.reframe(1, 120, 1))
        assertEquals(TimeFormat.MAX_DURATION_FRAMES, TimeFormat.reframe(TimeFormat.MAX_DURATION_FRAMES, 24, 120))
    }

    @Test fun timelineZoomKeepsStripInsideComposeLimits() {
        val density = 3f
        for (frames in listOf(10, 5000, 30_000, TimeFormat.MAX_DURATION_FRAMES)) {
            val lo = TimelineZoom.clamp(0.01f, frames, density, 4f, 48f)
            val hi = TimelineZoom.clamp(1000f, frames, density, 4f, 48f)
            assertTrue(lo <= hi)
            assertTrue("полоса не шире предела Compose", hi * frames * density <= TimelineZoom.MAX_CONTENT_PX + 1f)
        }
        assertEquals(48f, TimelineZoom.clamp(1000f, 100, density, 4f, 48f), 0.0001f)
        assertEquals(4f, TimelineZoom.clamp(Float.NaN, 100, density, 4f, 48f), 0.0001f)
    }

    // ---------------------------------------------------------------- 5. автоключ деформации (Mesh/Warp/перспектива/пины)

    @Test fun firstDeformationOnLaterFrameKeepsOriginalShapeAtStart() {
        val group = listOf(1, 2, 3)
        val a = DeformAutoKey.key(Animation(), group, 20, mapOf(2 to t(dx = 5f)))
        // ресницы: в начале исходная форма, на кадре правки - новая, перемотка назад возвращает исходную
        assertEquals(0f, a.evaluate(2, 0, null).dx, 1e-4f)
        assertEquals(5f, a.evaluate(2, 20, null).dx, 1e-4f)
        assertEquals(2.5f, a.evaluate(2, 10, null).dx, 1e-4f)
        assertTrue(a.hasKey(2, 0) && a.hasKey(2, 20))
        assertFalse("незадетые точки без дорожек не получают ключей", a.hasTrack(1) || a.hasTrack(3))
    }

    @Test fun deformerKeysWholeShapeOnTheEditedFrame() {
        val group = listOf(1, 2, 3)
        val first = DeformAutoKey.key(Animation(), group, 20, mapOf(1 to t(dx = 4f), 2 to t(dx = 8f)))
        // правка на кадре 40 только точки 1: точка 2 фиксируется своим значением на кадре 40
        val second = DeformAutoKey.key(first, group, 40, mapOf(1 to t(dx = -4f)))
        assertTrue(second.hasKey(2, 40))
        assertEquals(8f, second.evaluate(2, 40, null).dx, 1e-4f)
        assertEquals(-4f, second.evaluate(1, 40, null).dx, 1e-4f)
        // новая точка получает прежнее (нулевое) значение на всех кадрах деформера
        val third = DeformAutoKey.key(second, group, 30, mapOf(3 to t(dy = 6f)))
        for (f in listOf(0, 20, 40)) assertEquals(0f, third.evaluate(3, f, null).dy, 1e-4f)
        assertEquals(6f, third.evaluate(3, 30, null).dy, 1e-4f)
        // прежние значения других точек не изменились
        assertEquals(4f, third.evaluate(1, 20, null).dx, 1e-4f)
        assertEquals(8f, third.evaluate(2, 20, null).dx, 1e-4f)
        assertTrue(DeformAutoKey.animated(third, group))
        assertFalse(DeformAutoKey.animated(third, listOf(9)))
    }

    @Test fun deformationOnFrameZeroAndRepeatedEditsDoNotDuplicateKeys() {
        val group = listOf(1, 2)
        val a = DeformAutoKey.key(Animation(), group, 0, mapOf(1 to t(dx = 3f)))
        assertEquals(1, a.tracks.getValue(1).keys.size)
        var b = DeformAutoKey.key(a, group, 12, mapOf(1 to t(dx = 5f)))
        b = DeformAutoKey.key(b, group, 12, mapOf(1 to t(dx = 7f)))   // перетаскивание продолжается
        assertEquals(2, b.tracks.getValue(1).keys.size)
        assertEquals(7f, b.evaluate(1, 12, null).dx, 1e-4f)
        assertEquals(3f, b.evaluate(1, 0, null).dx, 1e-4f)
        assertEquals(a, DeformAutoKey.key(a, group, 12, emptyMap()))
    }

    // ---------------------------------------------------------------- 2/5. ключи цепочки костей

    @Test fun boneChainIncludesAncestorsAndDescendants() {
        // плечо 1 -> предплечье 2 -> кисть 3, палец 4 у кисти; нога 5 отдельно
        val links = mapOf(2 to 1, 3 to 2, 4 to 3)
        val bones = listOf(1, 2, 3, 4, 5)
        assertEquals(listOf(1, 2, 3, 4), BoneChainKeys.chain(2, links, bones))
        assertEquals(listOf(1, 2, 3, 4), BoneChainKeys.chain(4, links, bones))
        assertEquals(listOf(5), BoneChainKeys.chain(5, links, bones))
        assertEquals(emptyList<Int>(), BoneChainKeys.chain(9, links, bones))
        // цикл в связях не зацикливает обход
        assertEquals(listOf(1, 2), BoneChainKeys.chain(1, mapOf(1 to 2, 2 to 1), listOf(1, 2)).sorted())
        // родитель - не кость (слой) - в цепочку не попадает
        assertEquals(listOf(2, 3, 4), BoneChainKeys.chain(3, mapOf(2 to 100, 3 to 2, 4 to 3), listOf(2, 3, 4)))
    }

    @Test fun keyTargetsAreSelectedBonesPlusOptionalChain() {
        val links = mapOf(2 to 1, 3 to 2)
        val bones = listOf(1, 2, 3, 5)
        assertEquals(listOf(2, 1, 3), BoneChainKeys.targets(2, emptySet(), links, bones, chain = true))
        assertEquals(listOf(2, 5), BoneChainKeys.targets(2, setOf(2, 5, 77), links, bones, chain = false))
        assertEquals(listOf(3, 5, 1, 2), BoneChainKeys.targets(3, setOf(5), links, bones, chain = true))
        assertEquals(emptyList<Int>(), BoneChainKeys.targets(42, setOf(1), links, bones, chain = true))
    }

    @Test fun chainKeysLockWholePoseAndKeepEarlierFrames() {
        val rest = mapOf(1 to t(rot = 0.1f), 2 to t(rot = 0.2f), 3 to t(rot = 0.3f))
        val pose = mapOf(1 to t(rot = 1f), 2 to t(rot = 2f), 3 to t(rot = 3f))
        val a = BoneChainKeys.keyAll(Animation(), listOf(1, 2, 3, 2), 15, { pose.getValue(it) }, { rest.getValue(it) })
        for (id in 1..3) {
            assertEquals(rest.getValue(id).rotation, a.evaluate(id, 0, null).rotation, 1e-4f)
            assertEquals(pose.getValue(id).rotation, a.evaluate(id, 15, null).rotation, 1e-4f)
            assertEquals(2, a.tracks.getValue(id).keys.size)
        }
        // вторая поза на кадре 30: кадр 15 не меняется
        val b = BoneChainKeys.keyAll(a, listOf(1, 2, 3), 30, { t(rot = 9f) })
        for (id in 1..3) {
            assertEquals(pose.getValue(id).rotation, b.evaluate(id, 15, null).rotation, 1e-4f)
            assertEquals(3, b.tracks.getValue(id).keys.size)
        }
        // ключ на кадре 0 - без лишнего исходного ключа
        val c = BoneChainKeys.keyAll(Animation(), listOf(1), 0, { t(rot = 4f) })
        assertEquals(1, c.tracks.getValue(1).keys.size)
    }

    // ---------------------------------------------------------------- 6/7/8. размер поверхности, фон, сохранение

    @Test fun surfaceResizeWaitsForPanelAnimation() {
        assertTrue("первый размер", ResizeSettle.applyNow(0, 0, 1080, 2000))
        assertTrue("без изменений", ResizeSettle.applyNow(1080, 2000, 1080, 2000))
        assertFalse("выезд нижней панели - ждём окончания", ResizeSettle.applyNow(1080, 2000, 1080, 1940))
        assertFalse(ResizeSettle.applyNow(1080, 2000, 1080, 1300))
        assertTrue("поворот экрана", ResizeSettle.applyNow(1080, 2000, 2000, 1080))
        assertTrue(ResizeSettle.SETTLE_MS in 50L..500L)
    }

    @Test fun exportParksOnlyDuringTransitionsOrWithoutOffscreenContext() {
        assertFalse(BackgroundRender.mustPark(0, paused = false, offscreen = false, enabled = false))
        assertTrue("смена состояния поверхности", BackgroundRender.mustPark(1, paused = false, offscreen = true, enabled = true))
        assertFalse("свёрнуто, служба идёт, внеэкранный контекст есть", BackgroundRender.mustPark(0, paused = true, offscreen = true, enabled = true))
        assertTrue("свёрнуто без службы", BackgroundRender.mustPark(0, paused = true, offscreen = true, enabled = false))
        assertTrue("свёрнуто без внеэкранного контекста", BackgroundRender.mustPark(0, paused = true, offscreen = false, enabled = true))
        assertTrue(BackgroundRender.canRender(0, paused = true, offscreen = true, enabled = true))
        assertEquals(50, BackgroundRender.percent(5, 10))
        assertEquals(100, BackgroundRender.percent(12, 10))
        assertNull(BackgroundRender.percent(1, 0))
        assertTrue(BackgroundRender.saveFailureIsTransient(surfacePaused = true, appBackground = false))
        assertFalse(BackgroundRender.saveFailureIsTransient(surfacePaused = false, appBackground = false))
    }

    @Test fun paintSaveErrorIsShownOnlyForRealFailures() {
        assertFalse("свёрнуто - молча повторяем", PaintSaveRetry.report(10, transient = false, background = true))
        assertTrue("постоянный отказ на экране", PaintSaveRetry.report(1, transient = false, background = false))
        assertFalse("первый временный отказ (новое устройство, поверхность ещё не готова)", PaintSaveRetry.report(1, transient = true, background = false))
        assertTrue(PaintSaveRetry.report(PaintSaveRetry.MAX_TRANSIENT, transient = true, background = false))
        assertEquals(800L, PaintSaveRetry.delayMs(1))
        assertEquals(5000L, PaintSaveRetry.delayMs(100))
    }
}
