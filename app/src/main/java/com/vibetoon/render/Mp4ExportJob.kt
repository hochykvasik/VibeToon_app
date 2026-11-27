package com.vibetoon.render

import android.util.Log
import com.vibetoon.domain.export.ExportTaskMachine
import com.vibetoon.domain.export.FramePrefetch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG_EXPORT = "VT/Export"

/**
 * Пакет 27: экспорт MP4 как цепочка коротких задач GL-потока (по задаче на кадр). Порядок задач и реакция на паузу,
 * отмену и ошибки - в чистой [ExportTaskMachine]; здесь только исполнение. Все методы - только из GL-потока.
 *
 * [newSink] создаёт проход записи (прямой Surface или запасной YUV) с хуками рендерера, [scene] - сцена кадра (из
 * фоновой предсборки [prefetch]).
 */
internal class Mp4ExportJob(
    val spec: ExportSpec,
    sceneAt: (Int) -> com.vibetoon.domain.render.SceneData,
    private val onProgress: (Int) -> Unit,
    val cancel: AtomicBoolean,
    private val onDone: (Result<Unit>) -> Unit,
    private val newSink: (Mp4ExportJob, Boolean) -> FrameSink
) {
    val machine = ExportTaskMachine(spec.frames, if (spec.compatible) ExportTaskMachine.Mode.YUV else ExportTaskMachine.Mode.SURFACE)
    val prefetch = FramePrefetch(spec.frames, Executors.newSingleThreadExecutor { r ->
        Thread(r, "vt-export-scene").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }, sceneAt)
    private var sink: FrameSink? = null
    var completed = false
        private set

    /** Одна задача. true - сразу ставить следующую; false - экспорт завершён или «запаркован» до возврата приложения. */
    fun step(paused: Boolean): Boolean {
        if (completed) return false
        return when (val s = machine.next(paused, cancel.get())) {
            ExportTaskMachine.Step.None -> false
            ExportTaskMachine.Step.Park -> false
            ExportTaskMachine.Step.Abort -> { abort(); false }
            is ExportTaskMachine.Step.Start -> guarded {
                VideoExporter.validate(spec)?.let { throw IllegalArgumentException(it) }
                val created = newSink(this, s.mode == ExportTaskMachine.Mode.YUV)
                sink = created
                created.start()
                machine.started()
                true
            }
            is ExportTaskMachine.Step.Frame -> guarded {
                if (sink!!.frame(s.index)) onProgress(machine.frameDone(s.index)) else machine.frameInterrupted()
                true
            }
            ExportTaskMachine.Step.Finish -> guarded {
                sink!!.finish()
                closeSink(true)
                machine.finished()
                complete(Result.success(Unit))
                false
            }
        }
    }

    /** GL-контекст пересоздан (onSurfaceCreated посреди экспорта): никаких GL-вызовов, экспорт провален. */
    fun contextLost() {
        if (completed) return
        closeSink(false)
        machine.failed(null, contextLost = true)
        complete(Result.failure(IllegalStateException(machine.error)))
    }

    private fun abort() {
        closeSink(true)
        machine.aborted()
        complete(Result.failure(IllegalStateException("Отменено")))
    }

    private inline fun guarded(block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        if (cancel.get()) { abort(); false }
        else {
            val lost = t is GlContextLost
            Log.e(TAG_EXPORT, "export step failed (${machine.mode}, frame ${machine.next})", t)
            closeSink(!lost)
            if (machine.failed(t.message, contextLost = lost)) {
                Log.w(TAG_EXPORT, "Surface encoder failed; retrying with RGBA/YUV readback")
                true
            } else {
                complete(Result.failure(IllegalStateException(machine.error ?: t.message, t)))
                false
            }
        }
    }

    private fun closeSink(contextAlive: Boolean) {
        val s = sink ?: return
        sink = null
        runCatching { s.release(contextAlive) }
    }

    private fun complete(r: Result<Unit>) {
        if (completed) return
        completed = true
        prefetch.close()
        if (machine.interrupted > 0 || prefetch.prefetchHits > 0)
            Log.i(TAG_EXPORT, "export ${machine.phase}: ${machine.next}/${spec.frames} frames, prefetched ${prefetch.prefetchHits}, interrupted ${machine.interrupted}")
        onDone(r)
    }
}
