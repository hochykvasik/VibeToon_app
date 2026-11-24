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
import com.vibetoon.ui.motion.AlertDialog
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

/** Варианты экспорта: MP4 (H.264/HEVC), MOV с альфой (спринт 32), GIF, изображение, последовательность, PSD, проект, звук. */
private enum class ExportMode(val title: String, val ready: Boolean) {
    VIDEO("MP4", true),
    MOV("MOV (альфа)", true),
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
            // спринт 32: частота по умолчанию - как в проекте (1–120), GIF - не выше 50
            val projectFps = remember { vm.animation.value.settings.fps }
            var videoQuality by remember { mutableIntStateOf(720) }
            var gifQuality by remember { mutableIntStateOf(ExportMath.GIF_QUALITIES[0]) }
            var sequenceQuality by remember { mutableIntStateOf(720) }
            var movQuality by remember { mutableIntStateOf(1080) }
            var fps by remember { mutableIntStateOf(com.vibetoon.domain.anim.FrameRates.defaultExport(projectFps)) }
            var qMenu by remember { mutableStateOf(false) }
            var fMenu by remember { mutableStateOf(false) }
            var gifFps by remember { mutableIntStateOf(com.vibetoon.domain.anim.FrameRates.defaultExport(projectFps, gif = true)) }
            var sequenceFps by remember { mutableIntStateOf(com.vibetoon.domain.anim.FrameRates.defaultExport(projectFps)) }
            var movFps by remember { mutableIntStateOf(com.vibetoon.domain.anim.FrameRates.defaultExport(projectFps)) }
            var movAlpha by remember { mutableStateOf(true) }
            var codec by remember { mutableStateOf(com.vibetoon.domain.export.VideoCodec.AVC) }
            val hevcOk = remember { vm.hevcAvailable() }
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
                ExportMode.MOV -> movQuality
                else -> videoQuality
            }
            val exportFps = when (mode) {
                ExportMode.GIF -> gifFps
                ExportMode.SEQUENCE -> sequenceFps
                ExportMode.MOV -> movFps
                else -> fps
            }
            vm.setExportSceneOnly(sceneOnly)
            // MP4: размер и кодек - по возможностям устройства (4K/HEVC могут откатиться, это видно заранее)
            val plan = remember(videoQuality, fps, codec) { vm.videoPlan(videoQuality, fps, codec) }
            val (w, h, frames) = if (mode == ExportMode.VIDEO) vm.exportInfo(plan.shortSide, plan.fps) else vm.exportInfo(quality, exportFps)
            val evenSize = remember(quality) { vm.exportEvenSize(quality) }
            val fpsOptions = remember(projectFps) { com.vibetoon.domain.anim.FrameRates.exportOptions(projectFps) }
            val gifFpsOptions = remember(projectFps) { com.vibetoon.domain.anim.FrameRates.gifOptions(projectFps) }
            fun fpsLabel(f: Int) = if (f == projectFps) "$f fps (проект)" else "$f fps"
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
                        if (scene != null && (mode == ExportMode.VIDEO || mode == ExportMode.MOV || mode == ExportMode.GIF || mode == ExportMode.SEQUENCE || mode == ExportMode.AUDIO))
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
                        } else if (mode == ExportMode.MOV) {
                            Text("Видео QuickTime с альфа-каналом: кадры PNG без потерь, звук PCM. Открывается в After Effects, Premiere, Final Cut и DaVinci Resolve. ProRes 4444 Android не кодирует, файл крупнее MP4.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (q in ExportMath.QUALITIES) androidx.compose.material3.FilterChip(selected = movQuality == q, onClick = { movQuality = q }, label = { Text(ExportMath.qualityLabel(q), maxLines = 1, softWrap = false) })
                            }
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (f in fpsOptions) androidx.compose.material3.FilterChip(selected = movFps == f, onClick = { movFps = f }, label = { Text(fpsLabel(f), maxLines = 1, softWrap = false) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = movAlpha, onCheckedChange = { movAlpha = it })
                                Text("Прозрачный фон", fontSize = 13.sp)
                            }
                            if (audio.hasTracks) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = hasSound && withAudio, enabled = hasSound, onCheckedChange = { withAudio = it })
                                Text("Звук в видео", fontSize = 13.sp)
                            }
                            Text("MOV ${evenSize.first}×${evenSize.second}, $frames кадров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            if (quality >= ExportMath.UHD) Text("4K в MOV: каждый кадр - PNG в несколько мегабайт, экспорт долгий и файл большой", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        } else if (mode == ExportMode.SEQUENCE) {
                            Text("Каждый кадр - PNG с прозрачным фоном, всё в одном ZIP. Для видео с альфой выберите «MOV (альфа)».", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (q in ExportMath.QUALITIES) androidx.compose.material3.FilterChip(selected = sequenceQuality == q, onClick = { sequenceQuality = q }, label = { Text(ExportMath.qualityLabel(q), maxLines = 1, softWrap = false) })
                            }
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (f in fpsOptions) androidx.compose.material3.FilterChip(selected = sequenceFps == f, onClick = { sequenceFps = f }, label = { Text(fpsLabel(f), maxLines = 1, softWrap = false) })
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
                                for (f in gifFpsOptions) androidx.compose.material3.FilterChip(selected = gifFps == f, onClick = { gifFps = f }, label = { Text(fpsLabel(f), maxLines = 1, softWrap = false) })
                                for (q in ExportMath.GIF_QUALITIES) androidx.compose.material3.FilterChip(selected = gifQuality == q, onClick = { gifQuality = q }, label = { Text("${q}p", maxLines = 1, softWrap = false) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.Switch(checked = gifAlpha, onCheckedChange = { gifAlpha = it })
                                Text("Прозрачный фон", fontSize = 13.sp)
                            }
                            Text("GIF ${w}×$h, $frames кадров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            if (projectFps > com.vibetoon.domain.anim.FrameRates.GIF_MAX) Text("GIF показывает не больше {} fps: кадры проекта прорежены".replace("{}", com.vibetoon.domain.anim.FrameRates.GIF_MAX.toString()), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        if (mode == ExportMode.VIDEO) Text("Настройки видео: кодек, разрешение и FPS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                        if (mode == ExportMode.VIDEO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in com.vibetoon.domain.export.VideoCodec.values()) {
                                val enabled = c == com.vibetoon.domain.export.VideoCodec.AVC || hevcOk
                                androidx.compose.material3.FilterChip(selected = codec == c, enabled = enabled, onClick = { codec = c }, label = { Text(c.label, maxLines = 1, softWrap = false) })
                            }
                        }
                        if (mode == ExportMode.VIDEO && !hevcOk) Text("На устройстве нет кодировщика HEVC: доступен только H.264", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        if (mode == ExportMode.VIDEO && codec == com.vibetoon.domain.export.VideoCodec.HEVC) Text("HEVC: файл примерно вдвое меньше при том же качестве, но старые телефоны и Windows без расширения его могут не открыть", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        if (mode == ExportMode.VIDEO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { qMenu = true }, modifier = Modifier.fillMaxWidth()) { com.vibetoon.ui.DropText(ExportMath.qualityLabel(videoQuality), 14.sp) }
                                DropdownMenu(expanded = qMenu, onDismissRequest = { qMenu = false }) {
                                    ExportMath.QUALITIES.forEach { q -> DropdownMenuItem(text = { Text(ExportMath.qualityLabel(q)) }, onClick = { videoQuality = q; qMenu = false }) }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { fMenu = true }, modifier = Modifier.fillMaxWidth()) { com.vibetoon.ui.DropText(fpsLabel(fps), 14.sp) }
                                DropdownMenu(expanded = fMenu, onDismissRequest = { fMenu = false }) {
                                    fpsOptions.forEach { f -> DropdownMenuItem(text = { Text(fpsLabel(f)) }, onClick = { fps = f; fMenu = false }) }
                                }
                            }
                        }
                        // спринт 32: ограничения устройства - заранее и простыми словами (4K/HEVC/частота)
                        if (mode == ExportMode.VIDEO) for (n in plan.notes) Text(n, fontSize = 12.sp, color = if (plan.possible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        // пакет 14: переключатель виден, если в проекте есть звуковые дорожки; недоступен, когда звучать нечему
                        if (mode == ExportMode.VIDEO && audio.hasTracks) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            androidx.compose.material3.Switch(checked = hasSound && withAudio, enabled = hasSound, onCheckedChange = { withAudio = it })
                            Text("Звук в видео", fontSize = 13.sp)
                        }
                        if (mode == ExportMode.VIDEO) Text(if (hasSound && withAudio) "Видео ${w}×$h, $frames кадров, со звуком (AAC, стерео 44,1 кГц)" else "Видео ${w}×$h, $frames кадров, без звука", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        if (mode == ExportMode.VIDEO) Text("Кодек: {}, {}×{}, {} fps".replaceFirst("{}", plan.codec.label).replaceFirst("{}", "${plan.width}").replaceFirst("{}", "${plan.height}").replaceFirst("{}", "${plan.fps}"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
                            ExportMode.MOV -> vm.exportMov(quality, movFps, movAlpha, hasSound && withAudio)
                            ExportMode.AUDIO -> vm.exportAudioOnly()
                            ExportMode.PSD -> vm.exportPsd()
                            ExportMode.PROJECT -> backupLauncher.launch("${vm.projectName.value}.tsproj")
                            ExportMode.IMAGE -> vm.exportStill(jpeg)
                            else -> vm.startExport(videoQuality, fps, hasSound && withAudio, codec)
                        }
                    }, enabled = mode.ready && (mode != ExportMode.VIDEO || plan.possible)) { Text("Экспорт") }
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
            title = { Text(if (s.file.extension == "mp4" || s.file.extension == "mov") "Видео готово" else "Файл готов") },   // zip и wav - тоже «Файл готов"
            text = {
                val message = if (s.gallerySaved) "${s.file.length() / 1024} КБ. Сохранено в папку проекта и в галерею."
                    else if (s.file.extension.lowercase() in setOf("png", "jpg", "gif", "mp4", "mov")) "${s.file.length() / 1024} КБ. Сохранено в папку проекта; копию в галерею создать не удалось."
                    else "${s.file.length() / 1024} КБ. Сохранено в папку проекта."
                Text(s.file.name + "\n" + com.vibetoon.ui.i18n.L(message) + (s.note?.let { n -> "\n" + n.split("\n").map { com.vibetoon.ui.i18n.L(it) }.joinToString("\n") } ?: ""))
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
        type = when (file.extension) { "png" -> "image/png"; "jpg" -> "image/jpeg"; "gif" -> "image/gif"; "zip" -> "application/zip"; "psd" -> "image/vnd.adobe.photoshop"; "wav" -> "audio/wav"; "mov" -> "video/quicktime"; else -> "video/mp4" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
