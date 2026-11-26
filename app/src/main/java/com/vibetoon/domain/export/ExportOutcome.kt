package com.vibetoon.domain.export

/**
 * Спринт 56: один итог задачи экспорта для шторки и для приложения.
 *
 * Раньше уведомление жило отдельно от экспорта: оно закрывалось, только если экран редактора сам дождался конца работы.
 * Когда экран закрывался системой (смахнули из «Недавних», вызов уведомления запускал второй экземпляр приложения,
 * пересоздание экрана), задача экспорта пропадала вместе с GL-потоком, а уведомление оставалось с бегущим таймером и
 * замершим процентом. Теперь каждый запуск заканчивается ровно одним итогом - готово, отменено, прервано или ошибка, -
 * служба снимает прогресс и показывает итог в шторке, а приложение - в окне экспорта или в окне-сообщении.
 */
object ExportOutcome {
    enum class Kind { DONE, CANCELLED, INTERRUPTED, FAILED }

    /** Состояние экспорта, как его показывает интерфейс (ExportUi без файлов и чисел). */
    enum class Ui { IDLE, RUNNING, DONE, FAILED }

    /**
     * Итог перехода из «идёт» в [next]; null - работа продолжается или и не начиналась.
     * [interrupted] - ошибка означает прерывание (экран/приложение закрыто), [cancelRequested] - пользователь нажал «Отмена».
     */
    fun classify(wasRunning: Boolean, next: Ui, interrupted: Boolean, cancelRequested: Boolean): Kind? = when {
        !wasRunning || next == Ui.RUNNING -> null
        next == Ui.DONE -> Kind.DONE
        next == Ui.FAILED -> if (interrupted) Kind.INTERRUPTED else Kind.FAILED
        cancelRequested -> Kind.CANCELLED
        // вернулись в «нет экспорта» без отмены пользователем - работу оборвали (закрыт экран, отменена корутина)
        else -> Kind.INTERRUPTED
    }

    /** Заголовок уведомления-итога (имя приложения - в литерале, строка целиком совпадает с парой словаря). */
    fun title(kind: Kind): String = when (kind) {
        Kind.DONE -> "VibeToon: экспорт завершён"
        Kind.CANCELLED -> "VibeToon: экспорт отменён"
        Kind.INTERRUPTED -> "VibeToon: экспорт прерван"
        Kind.FAILED -> "VibeToon: экспорт не удался"
    }

    /** Текст итога: имя файла для готового экспорта, причина - для ошибки и прерывания. */
    fun text(kind: Kind, detail: String?): String {
        val d = detail?.trim()?.takeIf { it.isNotEmpty() }
        return when (kind) {
            Kind.DONE -> if (d != null) "Сохранено: $d" else "Файл сохранён в папку проекта"
            Kind.CANCELLED -> "Файл не записан"
            Kind.INTERRUPTED -> d ?: REASON_APP_CLOSED
            Kind.FAILED -> d ?: "Не удалось записать файл"
        }
    }

    /**
     * Нужен ли итог в шторке. Пока приложение на экране, итог виден в окне экспорта; в шторку он идёт, если приложение
     * свёрнуто или его экран закрыт (иначе пользователь не узнает, чем всё закончилось).
     */
    fun postToShade(appVisible: Boolean): Boolean = !appVisible

    /** Сколько живёт итог в шторке, мс (0 - пока его не откроют или не смахнут). Отмена - короткое подтверждение. */
    fun timeoutMs(kind: Kind): Long = if (kind == Kind.CANCELLED) 10_000L else 0L

    /** Итог прерывания нужно ещё и показать окном при следующем открытии приложения (окно экспорта уже закрыто). */
    fun needsInAppNotice(kind: Kind, editorAlive: Boolean): Boolean = kind == Kind.INTERRUPTED && !editorAlive

    const val TAP_HINT = "Нажмите, чтобы открыть VibeToon"
    const val REASON_APP_CLOSED = "Приложение было закрыто, экспорт остановлен. Откройте проект и запустите экспорт снова"
    const val REASON_VIEW_CLOSED = "Экран редактора закрылся во время экспорта. Запустите экспорт снова"
    const val REASON_PROCESS_DIED = "Прошлый экспорт не завершился: приложение было закрыто системой. Запустите экспорт снова"
    const val LEAVE_BLOCKED = "Дождитесь окончания экспорта или отмените его"
}

/** Экспорт оборвался не по вине проекта (закрыт экран редактора): итог - «прерван», а не «ошибка». */
class ExportInterrupted(message: String) : IllegalStateException(message)

/**
 * Спринт 56: экспорт стоит. В фоне цепочка кадров может остановиться (переход состояния, устройство не даёт
 * внеэкранный контекст, системные ограничения) - раньше уведомление при этом показывало бегущий таймер и прежний
 * процент, будто работа идёт. Теперь стоящий экспорт честно подписан «ждёт» и просит вернуться в приложение.
 */
object ExportStall {
    /** Без нового кадра столько миллисекунд в фоне - экспорт считается стоящим. */
    const val STALL_MS = 30_000L
    /** Как часто служба проверяет, не встал ли экспорт. */
    const val CHECK_MS = 5_000L

    /**
     * Стоит ли экспорт: только когда приложение не на экране (на экране окно экспорта само показывает ход) и цепочка
     * кадров запаркована или давно не было нового кадра.
     */
    fun stalled(appVisible: Boolean, parked: Boolean, lastProgressAt: Long, now: Long): Boolean =
        !appVisible && (parked || now - lastProgressAt >= STALL_MS)

    fun title(percent: Int?): String =
        if (percent != null) "VibeToon: экспорт ждёт ${percent.coerceIn(0, 100)} %" else "VibeToon: экспорт ждёт"

    const val TEXT = "Работа приостановлена · вернитесь в VibeToon, чтобы продолжить"
}

/**
 * Спринт 56: отметка «экспорт идёт» в настройках приложения. Если процесс убит системой, служба и её уведомление
 * пропадают молча; при следующем запуске отметка остаётся - приложение говорит, что прошлый экспорт не завершился.
 */
object ExportInflight {
    const val PREF = "export_inflight"

    data class Info(val projectId: String, val projectName: String, val startedAt: Long)

    fun encode(info: Info): String =
        listOf(info.projectId, info.projectName.replace('\t', ' ').replace('\n', ' '), info.startedAt.toString()).joinToString("\t")

    fun decode(s: String?): Info? {
        if (s.isNullOrEmpty()) return null
        val p = s.split('\t')
        if (p.size != 3 || p[0].isEmpty()) return null
        val t = p[2].toLongOrNull() ?: return null
        return Info(p[0], p[1], t)
    }

    /** Отметка от прошлого запуска, о которой надо сказать: она есть, а экспорт сейчас не идёт. */
    fun staleOnStart(marker: String?, exportRunningNow: Boolean): Info? = if (exportRunningNow) null else decode(marker)
}

/** Спринт 56: что можно делать с редактором, пока идёт экспорт. */
object EditorLeave {
    /** Выход в список проектов закрывает GL-вид - посреди экспорта он оборвал бы работу, поэтому сначала конец или «Отмена». */
    fun allowed(exportRunning: Boolean): Boolean = !exportRunning

    /** Нехватка памяти посреди экспорта не удаляет текстуры форм символов: кадры экспорта на них ссылаются. */
    fun trimFormsAllowed(exportRunning: Boolean): Boolean = !exportRunning
}
