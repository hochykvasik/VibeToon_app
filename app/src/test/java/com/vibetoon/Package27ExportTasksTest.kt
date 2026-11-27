package com.vibetoon

import com.vibetoon.domain.export.ExportTaskMachine
import com.vibetoon.domain.export.ExportTaskMachine.Mode
import com.vibetoon.domain.export.ExportTaskMachine.Phase
import com.vibetoon.domain.export.ExportTaskMachine.Step
import com.vibetoon.domain.export.FramePrefetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Пакет 27: экспорт MP4 по задаче GL-потока на кадр - порядок задач, пауза, отмена, запасной путь, предсборка сцен. */
class Package27ExportTasksTest {

    /** Исполнитель-модель: как VtGLSurfaceView гоняет задачи; [script] решает исход кадра и паузу/отмену. */
    private class Driver(val m: ExportTaskMachine) {
        val log = ArrayList<String>()
        var paused = false
        var cancelled = false
        var failStartIn: Mode? = null
        var failFrame = -1
        var interruptFrameOnce = -1
        var progress = 0
        var released = 0

        /** Одна задача; false - цепочка остановлена (пауза/конец). */
        fun task(): Boolean = when (val s = m.next(paused, cancelled)) {
            Step.None -> false
            Step.Park -> { log += "park"; false }
            Step.Abort -> { released++; m.aborted(); log += "abort"; false }
            is Step.Start -> {
                log += "start:${s.mode}"
                if (failStartIn == s.mode) { released++; m.failed("codec") } else { m.started(); true }
            }
            is Step.Frame -> when (s.index) {
                interruptFrameOnce -> { interruptFrameOnce = -1; m.frameInterrupted(); log += "int:${s.index}"; true }
                failFrame -> { failFrame = -1; released++; log += "fail:${s.index}"; m.failed("surface") }
                else -> { progress = m.frameDone(s.index); log += "f:${s.index}"; true }
            }
            Step.Finish -> { m.finished(); released++; log += "finish"; false }
        }

        fun runAll(limit: Int = 1000): Driver { var n = 0; while (n++ < limit && task()) { }; return this }
    }

    @Test
    fun framesAreSeparateTasksInOrder() {
        val d = Driver(ExportTaskMachine(4)).runAll()
        assertEquals(listOf("start:SURFACE", "f:0", "f:1", "f:2", "f:3", "finish"), d.log)
        assertEquals(Phase.DONE, d.m.phase)
        assertEquals(4, d.progress)
        assertEquals(1, d.released)
        assertEquals(Step.None, d.m.next(paused = false, cancelled = false))
    }

    @Test
    fun pauseParksAndResumeContinuesFromSameFrame() {
        val d = Driver(ExportTaskMachine(5))
        d.task(); d.task(); d.task()   // start, f0, f1
        d.paused = true
        assertFalse(d.task())          // park: цепочка стоит, очередь GL пуста - onPause подтверждается сразу
        assertFalse(d.task())
        assertEquals(2, d.m.next)
        d.paused = false
        d.runAll()
        assertEquals(listOf("start:SURFACE", "f:0", "f:1", "park", "park", "f:2", "f:3", "f:4", "finish"), d.log)
        assertEquals(Phase.DONE, d.m.phase)
    }

    @Test
    fun interruptedFrameIsRedrawnNotSkipped() {
        val d = Driver(ExportTaskMachine(3)).apply { interruptFrameOnce = 1 }.runAll()
        assertEquals(listOf("start:SURFACE", "f:0", "int:1", "f:1", "f:2", "finish"), d.log)
        assertEquals(1, d.m.interrupted)
        assertEquals(3, d.progress)
    }

    @Test
    fun cancelWinsOverPauseAndReleasesOnce() {
        val d = Driver(ExportTaskMachine(10))
        d.task(); d.task()
        d.paused = true
        d.task()
        d.cancelled = true             // отмена, пока экспорт запаркован (приложение свёрнуто)
        assertFalse(d.task())
        assertEquals(Phase.CANCELLED, d.m.phase)
        assertEquals(1, d.released)
        assertEquals(Step.None, d.m.next(paused = false, cancelled = true))
    }

    @Test
    fun surfaceFailureRestartsOnceWithYuvFromZero() {
        val d = Driver(ExportTaskMachine(3)).apply { failFrame = 1 }.runAll()
        assertEquals(listOf("start:SURFACE", "f:0", "fail:1", "start:YUV", "f:0", "f:1", "f:2", "finish"), d.log)
        assertTrue(d.m.fellBack)
        assertEquals(Mode.YUV, d.m.mode)
        assertEquals(Phase.DONE, d.m.phase)
    }

    @Test
    fun secondFailureIsFinalAndCompatibleModeHasNoFallback() {
        val d = Driver(ExportTaskMachine(3)).apply { failStartIn = Mode.SURFACE }
        d.runAll()
        // кодек Surface не открылся -> перезапуск запасным путём YUV, он открывается и пишет всё
        assertEquals(listOf("start:SURFACE", "start:YUV", "f:0", "f:1", "f:2", "finish"), d.log)
        assertEquals(Phase.DONE, d.m.phase)

        val yuvOnly = ExportTaskMachine(3, Mode.YUV)
        assertEquals(Step.Start(Mode.YUV), yuvOnly.next(false, false))
        yuvOnly.started()
        assertFalse(yuvOnly.failed("codec"))
        assertEquals(Phase.FAILED, yuvOnly.phase)
        assertEquals("codec", yuvOnly.error)

        val twice = ExportTaskMachine(3)
        twice.started()
        assertTrue(twice.failed("surface"))
        twice.started()
        assertFalse(twice.failed("yuv"))
        assertEquals(Phase.FAILED, twice.phase)
    }

    @Test
    fun contextLossFailsWithoutFallback() {
        val m = ExportTaskMachine(3)
        m.started(); m.frameDone(0)
        assertFalse(m.failed(null, contextLost = true))
        assertEquals(Phase.FAILED, m.phase)
        assertTrue(m.error!!.contains("Контекст GL"))
    }

    @Test
    fun guardsAgainstOutOfOrderFramesAndEmptyRange() {
        val m = ExportTaskMachine(2)
        m.started()
        assertTrue(runCatching { m.frameDone(1) }.isFailure)
        assertTrue(runCatching { m.finished() }.isFailure)
        val empty = ExportTaskMachine(0)
        assertEquals(Phase.FAILED, empty.phase)
        assertEquals(Step.None, empty.next(false, false))
    }

    @Test
    fun prefetchBuildsNextSceneInBackgroundAndReusesRetriedFrame() {
        val calls = AtomicInteger()
        val built = java.util.Collections.synchronizedList(ArrayList<Int>())
        val p = FramePrefetch(4, Executors.newSingleThreadExecutor()) { i -> calls.incrementAndGet(); built += i; "scene$i" }
        assertEquals("scene0", p.get(0))
        assertEquals("scene0", p.get(0))   // прерванный кадр повторяется с той же сценой, без пересборки
        assertEquals("scene1", p.get(1))
        assertEquals("scene2", p.get(2))
        assertEquals("scene3", p.get(3))
        p.close()
        assertEquals(4, calls.get())        // каждая сцена собрана ровно один раз
        assertEquals(3, p.prefetchHits)     // 1, 2, 3 пришли из фоновой предсборки
        assertEquals(listOf(0, 1, 2, 3), built.sorted())
    }

    @Test
    fun prefetchWithoutExecutorComputesInline() {
        val p = FramePrefetch(3, null) { i -> i * 10 }
        assertEquals(0, p.get(0)); assertEquals(20, p.get(2)); assertEquals(10, p.get(1))
        assertEquals(0, p.prefetchHits)
        p.close()
    }

    @Test
    fun prefetchMissAfterRestartRebuilds() {
        val calls = AtomicInteger()
        val p = FramePrefetch(5, Executors.newSingleThreadExecutor()) { i -> calls.incrementAndGet(); i }
        p.get(0); p.get(1); p.get(2)
        assertEquals(0, p.get(0))           // перезапуск запасным путём - с нулевого кадра
        assertEquals(1, p.get(1))
        p.close()
        assertTrue(calls.get() >= 5)
    }
}
