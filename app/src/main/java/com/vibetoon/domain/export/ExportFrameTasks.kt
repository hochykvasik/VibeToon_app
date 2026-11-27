package com.vibetoon.domain.export

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Пакет 27: экспорт MP4 покадровыми задачами GL-потока.
 *
 * Раньше весь экспорт шёл одной задачей GL-потока: пока она работала, поток не замечал паузу (сворачивание), поэтому
 * экспорт приходилось отменять, а остальные события GL (снимки, касания) ждали конца. Теперь каждый кадр - отдельная
 * задача; между кадрами поток свободен. Здесь - чистая (без Android и GL) логика порядка задач, её проверяют тесты.
 *
 * Порядок задач: Start (кодек, поверхность) -> Frame(0) ... Frame(frames - 1) -> Finish -> Done.
 * - пауза: очередная задача ничего не делает и «паркуется», после возврата приложения цепочка продолжается с того же кадра;
 * - кадр, прерванный паузой посреди рисования (догрузка текстур), не засчитывается и повторяется;
 * - отмена в любой момент: Abort (освободить кодек, вернуть вид редактора);
 * - ошибка прямой записи в поверхность кодека: один перезапуск с нуля через запасной путь (RGBA -> YUV);
 * - потеря GL-контекста (пересоздан, пока приложение было свёрнуто): ошибка без перезапуска.
 */
class ExportTaskMachine(val frames: Int, startMode: Mode = Mode.SURFACE, private val allowFallback: Boolean = true) {
    enum class Mode { SURFACE, YUV }

    sealed interface Step {
        /** Открыть кодек выбранным путём. */
        data class Start(val mode: Mode) : Step
        /** Нарисовать и отдать кодеку кадр [index]. */
        data class Frame(val index: Int) : Step
        /** Конец потока, дописать MP4. */
        data object Finish : Step
        /** Приложение свёрнуто: задача ничего не делает, продолжение - после возврата. */
        data object Park : Step
        /** Отмена: освободить ресурсы, результат - отмена. */
        data object Abort : Step
        /** Экспорт завершён (успех или ошибка) - задач больше нет. */
        data object None : Step
    }

    enum class Phase { NOT_STARTED, RUNNING, DONE, FAILED, CANCELLED }

    var mode: Mode = startMode
        private set
    var phase: Phase = if (frames > 0) Phase.NOT_STARTED else Phase.FAILED
        private set
    /** Следующий кадр к записи (= число уже записанных кадров текущего прохода). */
    var next: Int = 0
        private set
    var error: String? = if (frames > 0) null else "Пустой отрезок"
        private set
    /** Сколько раз кадр был прерван паузой и повторён (для логов и тестов). */
    var interrupted: Int = 0
        private set
    var fellBack: Boolean = false
        private set

    val terminal: Boolean get() = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED

    /** Какая задача сейчас. [paused] - приложение свёрнуто, [cancelled] - пользователь отменил экспорт. */
    fun next(paused: Boolean, cancelled: Boolean): Step = when {
        terminal -> Step.None
        cancelled -> Step.Abort
        paused -> Step.Park
        phase == Phase.NOT_STARTED -> Step.Start(mode)
        next < frames -> Step.Frame(next)
        else -> Step.Finish
    }

    /** Кодек открыт. */
    fun started() {
        check(phase == Phase.NOT_STARTED) { "Запуск кодека в фазе $phase" }
        phase = Phase.RUNNING
        next = 0
    }

    /** Кадр [index] записан; возвращает прогресс (число записанных кадров). */
    fun frameDone(index: Int): Int {
        check(phase == Phase.RUNNING && index == next) { "Кадр $index вне очереди (ожидается $next, фаза $phase)" }
        next++
        return next
    }

    /** Кадр прерван паузой до записи: тот же индекс будет нарисован заново. */
    fun frameInterrupted() { if (phase == Phase.RUNNING) interrupted++ }

    fun finished() {
        check(phase == Phase.RUNNING && next == frames) { "Завершение до последнего кадра ($next/$frames)" }
        phase = Phase.DONE
    }

    fun aborted() { if (!terminal) phase = Phase.CANCELLED }

    /**
     * Ошибка кодека/GL. true - перезапуск с нуля запасным путём (ресурсы прежнего прохода вызывающий уже освободил),
     * false - экспорт провален.
     */
    fun failed(message: String?, contextLost: Boolean = false): Boolean {
        if (terminal) return false
        if (mode == Mode.SURFACE && allowFallback && !contextLost && !fellBack) {
            mode = Mode.YUV
            fellBack = true
            phase = Phase.NOT_STARTED
            next = 0
            return true
        }
        phase = Phase.FAILED
        error = if (contextLost) "Контекст GL потерян, пока приложение было свёрнуто - экспорт прерван" else (message ?: "Ошибка экспорта")
        return false
    }
}

/**
 * Сцены кадров экспорта строятся заранее в фоне: пока GL-поток рисует кадр i, фоновый поток собирает сцену i + 1.
 * [compute] должен быть безопасен вне главного/GL-потока (как в экспорте GIF/PNG). Не потокобезопасен сам по себе:
 * вызывать из одного потока (GL).
 */
class FramePrefetch<T>(private val frames: Int, private val executor: ExecutorService?, private val compute: (Int) -> T) {
    private var pendingIndex = -1
    private var pending: Future<T>? = null
    private var lastIndex = -1
    private var last: T? = null
    var prefetchHits = 0
        private set

    /** Сцена кадра [index]; сразу ставит в очередь сборку следующего. Повтор прерванного кадра берёт ту же сцену. */
    fun get(index: Int): T {
        @Suppress("UNCHECKED_CAST")
        if (index == lastIndex) return last as T
        val ready = if (pendingIndex == index) pending else null
        // промах (перезапуск с нуля): дождаться фоновой сборки, чтобы [compute] никогда не шёл в двух потоках сразу
        if (ready == null) pending?.let { f -> runCatching { f.get() } }
        pending = null; pendingIndex = -1
        val value = if (ready != null) { prefetchHits++; ready.get() } else compute(index)
        val n = index + 1
        val ex = executor
        if (ex != null && n < frames) {
            pendingIndex = n
            pending = runCatching { ex.submit(Callable { compute(n) }) }.getOrNull()
            if (pending == null) pendingIndex = -1
        }
        lastIndex = index; last = value
        return value
    }

    /** Освобождает фоновую сборку (конец, отмена, ошибка). */
    fun close() {
        pending?.cancel(false)
        pending = null; pendingIndex = -1
        lastIndex = -1; last = null
        executor?.shutdown()   // без прерывания: начатая сборка просто доработает в фоне, её результат не нужен
    }
}
