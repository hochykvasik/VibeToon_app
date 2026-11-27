package com.vibetoon.ui

import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.export.ExportOutcome
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.motion.AlertDialog
import com.vibetoon.work.ExportNotices

/**
 * Спринт 56: итог экспорта окном, когда окна экспорта уже нет: экран редактора закрыт системой посреди экспорта или
 * процесс был убит. Показывается поверх любого экрана; «Открыть проект» - сразу к тому проекту, чтобы запустить
 * экспорт снова.
 */
@Composable
fun ExportNoticeDialog(onOpenProject: (String) -> Unit, canOpen: Boolean) {
    val notice by ExportNotices.notice.collectAsStateWithLifecycle()
    val n = notice ?: return
    AlertDialog(
        onDismissRequest = ExportNotices::dismiss,
        title = {
            Text(when (n.kind) {
                ExportOutcome.Kind.DONE -> "Экспорт завершён"
                ExportOutcome.Kind.CANCELLED -> "Экспорт отменён"
                ExportOutcome.Kind.INTERRUPTED -> "Экспорт прерван"
                ExportOutcome.Kind.FAILED -> "Экспорт не удался"
            })
        },
        text = {
            val name = n.projectName?.takeIf { it.isNotBlank() }
            Text(com.vibetoon.ui.i18n.L(n.message) + (if (name != null) "\n" + com.vibetoon.ui.i18n.L("Проект: $name") else ""))
        },
        confirmButton = {
            val pid = n.projectId
            if (pid != null && canOpen) TextButton({ ExportNotices.dismiss(); onOpenProject(pid) }) { Text("Открыть проект") }
            else TextButton(ExportNotices::dismiss) { Text("Понятно") }
        },
        dismissButton = { if (n.projectId != null && canOpen) TextButton(ExportNotices::dismiss) { Text("Закрыть") } }
    )
}
