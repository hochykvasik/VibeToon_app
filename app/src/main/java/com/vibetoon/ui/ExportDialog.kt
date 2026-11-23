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
    val shareTitle = com.vibetoon.ui.i18n.L("Поделиться")
    when (val s = state) {
        ExportUi.Idle -> if (visible) {
            var mode by remember { mutableStateOf(ExportMode.VIDEO) }
            var videoQuality by remember { mutableIntStateOf(ExportMath.QUALITIES[1]) }
            var gifQuality by remember { mutableIntStateOf(ExportMath.GIF_QUALITIES[0]) }
            var sequenceQuality by remember { mutableIntStateOf(ExportMath.QUALITIES[1]) }
            var fps by remember { mutableIntStateOf(30) }
            var qMenu by remember { mutableStateOf(false) }
            var fMenu by remember { mutableStateOf(false) }
            var gifFps by remember { mutableIntStateOf(24) }
            var sequenceFps by remember { mutableIntStateOf(24) }
            var gifAlpha by remember { mutableStateOf(false) }
            var sequenceAlpha by remember { mutableStateOf(false) }
            var jpeg by remember { mutableStateOf(false) }
            // пакет 12: звук прямо в MP4 (по умолчанию включён, если в проекте есть звук)
            // пакет 14: состояние звука пересчитывается при смене «Только сцена» и когда дорожка декодирована
            val audioRev by vm.audioRevision.collectAsStateWithLifecycle()
            var withAudio by remember { mutableStateOf(true) }
            // пакет 9: экспорт только сцены под плейхедом
            val scene = remember { vm.activeRange() ?: vm.currentScene() }
            // открыта сцена - по умолчанию экспортируется она (пакет 10)
            var sceneOnly by remember { mutableStateOf((vm.exportSceneOnly || vm.activeRange() != null) && scene != null) }
            val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                if (uri != null) { vm.exportProjectBackup(uri, ctx); onHide() }
            }
            val quality = when (mode) {
                ExportMode.GIF -> gifQuality
                ExportMode.SEQUENCE -> sequenceQuality
                else -> videoQuality
            }
            val exportFps = when (mode) {
                ExportMode.GIF -> gifFps
                ExportMode.SEQUENCE -> sequenceFps
                else -> fps
            }
            vm.setExportSceneOnly(sceneOnly)
            val (w, h, frames) = vm.exportInfo(quality, exportFps)
            val audio = remember(audioRev, sceneOnly) { vm.exportAudioStatus() }
            val hasSound = audio.ready
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
                        if (scene != null && (mode == ExportMode.VIDEO || mode == ExportMode.GIF || mode == ExportMode.SEQUENCE || mode == ExportMode.AUDIO))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                androidx.compose.material3.Switch(checked = sceneOnly, onCheckedChange = { sceneOnly = it })
                                Text(com.vibetoon.ui.i18n.L("Только сцена") + " «" + com.vibetoon.ui.timeline.sceneDisplayName(scene.name) + "»", fontSize = 13.sp)
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
                                for (f in ExportMath.SEQUENCE_FPS_OPTIONS) androidx.compose.material3.FilterChip(selected = sequenceFps == f, onClick = { sequenceFps = f }, label = { Text("$f fps", maxLines = 1, softWrap = false) })
                                for (q in ExportMath.QUALITIES) androidx.compose.material3.FilterChip(selected = sequenceQuality == q, onClick = { sequenceQuality = q }, label = { Text("${q}p", maxLines = 1, softWrap = false) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = sequenceAlpha, onCheckedChange = { sequenceAlpha = it })
                                Text("Прозрачный фон", fontSize = 13.sp)
                            }
                            Text("${w}×$h, $frames кадров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        } else if (mode == ExportMode.PSD) {
                            Text("Многослойный PSD текущего кадра: слои и группы сохраняются, режимы наложения тоже. Текст, видео, символы и вектор идут как растр; прозрачность слоя запекается в пиксели.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.AUDIO) {
                            Text("Все дорожки проекта (и звук видео-слоёв) сводятся в один WAV (стерео 44,1 кГц) на всю длительность проекта или сцены. Дорожки «Без звука» пропускаются.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.PROJECT) {
                            Text("Резервная копия проекта со всеми файлами. Её можно импортировать на экране проектов.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        } else if (mode == ExportMode.GIF) {
                            Text("Размер и частота кадров GIF", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (f in ExportMath.GIF_FPS_OPTIONS) androidx.compose.material3.FilterChip(selected = gifFps == f, onClick = { gifFps = f }, label = { Text("$f fps", maxLines = 1, softWrap = false) })
                                for (q in ExportMath.GIF_QUALITIES) androidx.compose.material3.FilterChip(selected = gifQuality == q, onClick = { gifQuality = q }, label = { Text("${q}p", maxLines = 1, softWrap = false) })
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
                                    ExportMath.QUALITIES.forEach { q -> DropdownMenuItem(text = { Text("${q}p") }, onClick = { videoQuality = q; qMenu = false }) }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { fMenu = true }, modifier = Modifier.fillMaxWidth()) { com.vibetoon.ui.DropText("$fps fps", 14.sp) }
                                DropdownMenu(expanded = fMenu, onDismissRequest = { fMenu = false }) {
                                    ExportMath.FPS_OPTIONS.forEach { f -> DropdownMenuItem(text = { Text("$f") }, onClick = { fps = f; fMenu = false }) }
                                }
                            }
                        }
                        // пакет 14: переключатель виден, если в проекте есть звуковые дорожки; недоступен, когда звучать нечему
                        if (mode == ExportMode.VIDEO && audio.hasTracks) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.Switch(checked = hasSound && withAudio, enabled = hasSound, onCheckedChange = { withAudio = it })
                            Text("Звук в видео", fontSize = 13.sp)
                        }
                        if (mode == ExportMode.VIDEO) Text(if (hasSound && withAudio) "Видео ${w}×$h, $frames кадров, со звуком (AAC, стерео 44,1 кГц)" else "Видео ${w}×$h, $frames кадров, без звука", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        if (mode == ExportMode.VIDEO && audio.hasTracks) Text(com.vibetoon.domain.export.ExportAudio.label(audio, withAudio), fontSize = 12.sp,
                            color = if (hasSound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        // пакет 14: какая сборка установлена (хеш коммита CI) - для сверки при проверке на телефоне
                        Text("Сборка ${com.vibetoon.BuildConfig.BUILD_ID}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    Button({
                        when (mode) {
                            ExportMode.GIF -> vm.exportGif(quality, gifFps, gifAlpha)
                            ExportMode.SEQUENCE -> vm.exportPngZip(quality, sequenceFps, sequenceAlpha)
                            ExportMode.AUDIO -> vm.exportAudioOnly()
                            ExportMode.PSD -> vm.exportPsd()
                            ExportMode.PROJECT -> backupLauncher.launch("${vm.projectName.value}.tsproj")
                            ExportMode.IMAGE -> vm.exportStill(jpeg)
                            else -> vm.startExport(quality, fps, hasSound && withAudio)
                        }
                    }, enabled = mode.ready) { Text("Экспорт") }
                },
                dismissButton = { TextButton(onHide) { Text("Отмена") } }
            )
        }

        is ExportUi.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (s.phase != null) "Звук…" else "Рендер…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.done / s.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                    if (s.phase != null) Text("Кодирование и сведение звука: ${s.done}%") else Text("Кадр ${s.done} из ${s.total}")
                }
            },
            confirmButton = { TextButton(vm::cancelExport) { Text("Отмена") } }
        )

        is ExportUi.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport(); onHide() },
            title = { Text(if (s.file.extension == "mp4") "Видео готово" else "Файл готов") },   // zip и wav - тоже «Файл готов"
            text = {
                val message = if (s.gallerySaved) "${s.file.length() / 1024} КБ. Сохранено в папку проекта и в галерею."
                    else if (s.file.extension.lowercase() in setOf("png", "jpg", "gif", "mp4")) "${s.file.length() / 1024} КБ. Сохранено в папку проекта; копию в галерею создать не удалось."
                    else "${s.file.length() / 1024} КБ. Сохранено в папку проекта."
                Text(s.file.name + "\n" + com.vibetoon.ui.i18n.L(message) + (s.note?.let { "\n" + com.vibetoon.ui.i18n.L(it) } ?: ""))
            },
            confirmButton = { TextButton({ share(ctx, s.file, shareTitle) }) { Text("Поделиться") } },
            dismissButton = { TextButton({ vm.dismissExport(); onHide() }) { Text("Закрыть") } }
        )

        is ExportUi.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport(); onHide() },
            title = { Text("Не получилось") },
            text = { Text(com.vibetoon.ui.i18n.localizeDiagnostic(s.message)) },
            confirmButton = { TextButton({ vm.dismissExport(); onHide() }) { Text("Закрыть") } }
        )
    }
}

private fun share(ctx: android.content.Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = when (file.extension) { "png" -> "image/png"; "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "zip" -> "application/zip"; "psd" -> "image/vnd.adobe.photoshop"; "wav" -> "audio/wav"; else -> "video/mp4" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
