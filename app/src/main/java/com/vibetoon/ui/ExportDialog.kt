package com.vibetoon.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LinearProgressIndicator
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.anim.ExportMath
import java.io.File

/** Варианты экспорта: готовы MP4, GIF и изображение (PNG/JPEG текущего кадра). */
private enum class ExportMode(val title: String, val ready: Boolean) {
    VIDEO("MP4", true),
    GIF("GIF", true),
    IMAGE("Изображение", true),
    SEQUENCE("PNG-последовательность (ZIP)", true),
    PSD("PSD", true),
    PROJECT("Проект (.tsproj)", true),
    AUDIO("Только аудио (WAV)", true)
}

/** Диалог экспорта в MP4: настройки -> прогресс -> "Поделиться". [visible] открывает настройки. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(vm: MainViewModel, visible: Boolean, onHide: () -> Unit) {
    val state by vm.export.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    when (val s = state) {
        ExportUi.Idle -> if (visible) {
            var mode by remember { mutableStateOf(ExportMode.VIDEO) }
            var quality by remember { mutableIntStateOf(ExportMath.QUALITIES[1]) }
            var fps by remember { mutableIntStateOf(30) }
            var qMenu by remember { mutableStateOf(false) }
            var fMenu by remember { mutableStateOf(false) }
            var gifFps by remember { mutableIntStateOf(24) }
            var gifAlpha by remember { mutableStateOf(false) }
            var jpeg by remember { mutableStateOf(false) }
            val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) { vm.exportProjectBackup(uri, ctx); onHide() }
            }
            val (w, h, frames) = vm.exportInfo(quality, if (mode == ExportMode.GIF) gifFps else fps)
            AlertDialog(
                onDismissRequest = onHide,
                shape = RoundedCornerShape(24.dp),
                title = { Text("Экспорт", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        // Что экспортировать (MP4 готов; остальное будет следующими шагами)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            for (m in ExportMode.values().filter { it.ready }) {
                                androidx.compose.material3.FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.title) })
                            }
                        }
                        if (mode == ExportMode.IMAGE) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                androidx.compose.material3.FilterChip(selected = !jpeg, onClick = { jpeg = false }, label = { Text("PNG (с прозрачностью)") })
                                androidx.compose.material3.FilterChip(selected = jpeg, onClick = { jpeg = true }, label = { Text("JPEG") })
                            }
                            Text("Кадр на плейхеде, полное разрешение холста. Копия сохраняется и в галерею (Pictures/VibeToon).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        } else if (mode == ExportMode.SEQUENCE) {
                            Text("Каждый кадр - PNG с прозрачным фоном, всё в одном ZIP. Это замена видео MOV с альфой: Android не умеет ProRes 4444.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (f in listOf(12, 24, 30)) androidx.compose.material3.FilterChip(selected = gifFps == f, onClick = { gifFps = f }, label = { Text("$f fps", maxLines = 1, softWrap = false) })
                                for (q in listOf(1080, 720, 480)) androidx.compose.material3.FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text("${q}p", maxLines = 1, softWrap = false) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = gifAlpha, onCheckedChange = { gifAlpha = it })
                                Text("Прозрачный фон", fontSize = 13.sp)
                            }
                            Text("${w}×$h, $frames кадров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        } else if (mode == ExportMode.PSD) {
                            Text("Многослойный PSD текущего кадра: слои и группы сохраняются, режимы наложения тоже. Текст, видео, символы и вектор идут как растр; прозрачность слоя запекается в пиксели.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.AUDIO) {
                            Text("Все дорожки проекта сводятся в один WAV (стерео 44,1 кГц) на всю длительность проекта.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.PROJECT) {
                            Text("Резервная копия проекта со всеми файлами. Её можно импортировать на экране проектов.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.GIF) {
                            Text("Размер и частота кадров GIF", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (f in listOf(12, 24, 30)) androidx.compose.material3.FilterChip(selected = gifFps == f, onClick = { gifFps = f }, label = { Text("$f fps", maxLines = 1, softWrap = false) })
                                for (q in listOf(480, 360)) androidx.compose.material3.FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text("${q}p", maxLines = 1, softWrap = false) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = gifAlpha, onCheckedChange = { gifAlpha = it })
                                Text("Прозрачный фон", fontSize = 13.sp)
                            }
                            Text("GIF ${w}×$h, $frames кадров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        if (mode == ExportMode.VIDEO) Text("Настройки видео: разрешение и FPS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        if (mode == ExportMode.VIDEO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { qMenu = true }, modifier = Modifier.fillMaxWidth()) { com.vibetoon.ui.DropText("${quality}p", 14.sp) }
                                DropdownMenu(expanded = qMenu, onDismissRequest = { qMenu = false }) {
                                    ExportMath.QUALITIES.forEach { q -> DropdownMenuItem(text = { Text("${q}p") }, onClick = { quality = q; qMenu = false }) }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { fMenu = true }, modifier = Modifier.fillMaxWidth()) { com.vibetoon.ui.DropText("$fps fps", 14.sp) }
                                DropdownMenu(expanded = fMenu, onDismissRequest = { fMenu = false }) {
                                    ExportMath.FPS_OPTIONS.filter { it >= 30 }.forEach { f -> DropdownMenuItem(text = { Text("$f") }, onClick = { fps = f; fMenu = false }) }
                                }
                            }
                        }
                        if (mode == ExportMode.VIDEO) Text("Видео ${w}×$h, $frames кадров, без звука", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                },
                confirmButton = {
                    Button({
                        when (mode) {
                            ExportMode.GIF -> vm.exportGif(quality, gifFps, gifAlpha)
                            ExportMode.SEQUENCE -> vm.exportPngZip(quality, gifFps, gifAlpha)
                            ExportMode.AUDIO -> vm.exportAudioOnly()
                            ExportMode.PSD -> vm.exportPsd()
                            ExportMode.PROJECT -> backupLauncher.launch("${vm.projectName.value}.tsproj")
                            ExportMode.IMAGE -> vm.exportStill(jpeg)
                            else -> vm.startExport(quality, fps)
                        }
                    }, enabled = mode.ready) { Text("Экспорт") }
                },
                dismissButton = { TextButton(onHide) { Text("Отмена") } }
            )
        }

        is ExportUi.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Рендер…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.done / s.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text("Кадр ${s.done} из ${s.total}")
                }
            },
            confirmButton = { TextButton(vm::cancelExport) { Text("Отмена") } }
        )

        is ExportUi.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport(); onHide() },
            title = { Text(if (s.file.extension == "mp4") "Видео готово" else "Файл готов") },   // zip и wav - тоже «Файл готов"
            text = { Text("${s.file.name}\n${s.file.length() / 1024} КБ. Сохранено в папку проекта" + if (s.file.extension in setOf("png", "jpg", "gif", "mp4")) " и в галерею." else ".") },
            confirmButton = { TextButton({ share(ctx, s.file) }) { Text("Поделиться") } },
            dismissButton = { TextButton({ vm.dismissExport(); onHide() }) { Text("Закрыть") } }
        )

        is ExportUi.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport(); onHide() },
            title = { Text("Не получилось") },
            text = { Text(s.message) },
            confirmButton = { TextButton({ vm.dismissExport(); onHide() }) { Text("Закрыть") } }
        )
    }
}

private fun share(ctx: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = when (file.extension) { "png" -> "image/png"; "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "zip" -> "application/zip"; "psd" -> "image/vnd.adobe.photoshop"; "wav" -> "audio/wav"; else -> "video/mp4" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
