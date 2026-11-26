package com.vibetoon.work

import android.content.Context
import com.vibetoon.domain.export.ExportInflight
import com.vibetoon.domain.export.ExportOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Спринт 56: итог экспорта, который надо показать окном в приложении, когда окна экспорта уже нет (экран редактора
 * закрыт системой, процесс перезапущен). Живёт на уровне процесса - его видит и новый экран, и экран проектов.
 * Плюс отметка «экспорт идёт» в настройках: если процесс убит, при следующем запуске приложение скажет об этом.
 */
object ExportNotices {
    data class Notice(val kind: ExportOutcome.Kind, val message: String, val projectId: String?, val projectName: String?)

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    fun raise(n: Notice) { _notice.value = n }

    fun dismiss() { _notice.value = null }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("vt_app", Context.MODE_PRIVATE)

    fun markInflight(ctx: Context, projectId: String, projectName: String) {
        runCatching {
            prefs(ctx).edit().putString(ExportInflight.PREF,
                ExportInflight.encode(ExportInflight.Info(projectId, projectName, System.currentTimeMillis()))).apply()
        }
    }

    fun clearInflight(ctx: Context) { runCatching { prefs(ctx).edit().remove(ExportInflight.PREF).apply() } }

    /** Запуск приложения: отметка прошлого экспорта осталась, а экспорт не идёт - процесс был убит посреди работы. */
    fun recoverAfterRestart(ctx: Context) {
        val info = ExportInflight.staleOnStart(prefs(ctx).getString(ExportInflight.PREF, null), BackgroundWork.exportActive()) ?: run {
            if (!BackgroundWork.exportActive()) clearInflight(ctx)
            return
        }
        clearInflight(ctx)
        if (_notice.value == null)
            raise(Notice(ExportOutcome.Kind.INTERRUPTED, ExportOutcome.REASON_PROCESS_DIED, info.projectId, info.projectName))
    }
}
