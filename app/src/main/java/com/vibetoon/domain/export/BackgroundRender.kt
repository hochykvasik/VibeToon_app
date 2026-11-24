package com.vibetoon.domain.export

/**
 * Спринт 41: работа GL-потока, когда приложение свёрнуто. GLSurfaceView сначала выполняет ВСЕ события очереди и
 * только потом подтверждает смену состояния (пауза, возврат, создание/смена/потеря поверхности), а главный поток всё это
 * время ждёт. Поэтому цепочка задач экспорта «паркуется» на время каждого перехода, а между переходами продолжает
 * работу и в фоне - во внеэкранном контексте (pbuffer или surfaceless), если устройство его поддерживает.
 */
object BackgroundRender {
    const val PREF = "background_work"

    /** true - очередная задача экспорта должна остановиться и ждать. */
    fun mustPark(transitions: Int, paused: Boolean, offscreen: Boolean, enabled: Boolean): Boolean =
        transitions > 0 || (paused && !(offscreen && enabled))

    /** Можно ли выполнять GL-работу сейчас (для снимков/чтения пикселей вне экспорта). */
    fun canRender(transitions: Int, paused: Boolean, offscreen: Boolean, enabled: Boolean): Boolean =
        !mustPark(transitions, paused, offscreen, enabled)

    /**
     * Неудачная запись рисунка при свёрнутом приложении (поверхность снята, текстуры не читаются) - не ошибка проекта:
     * изменения остаются в очереди и сохраняются после возврата, баннер «проект не сохранён» не показывается.
     */
    fun saveFailureIsTransient(surfacePaused: Boolean, appBackground: Boolean): Boolean = surfacePaused || appBackground

    /** Процент для уведомления (0..100) или null - прогресс неизвестен. */
    fun percent(done: Int, total: Int): Int? = if (total <= 0) null else (done.toLong() * 100 / total).toInt().coerceIn(0, 100)
}
