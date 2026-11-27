package com.vibetoon.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.i18n.L
import com.vibetoon.ui.i18n.Text

/** Названия интерполяции - те же, что в меню «Кривые (Easing)». */
internal val EASING_TITLES = listOf(
    EasingType.LINEAR to "Линейно",
    EasingType.EASE_IN to "Плавное начало",
    EasingType.EASE_OUT to "Плавный конец",
    EasingType.EASE_IN_OUT to "Плавно и там и там",
    EasingType.STEP to "Ступенька (Hold)"
)

/**
 * Пакет 11: окно «Список ключей» выбранного слоя или кости. Каждый ключ - строка: кадр и время, значения
 * трансформации, интерполяция (меню на каждом ключе), переход к ключу и удаление. «Всем ключам» задаёт одну
 * интерполяцию сразу всем. Undo/Redo и сохранение - общие с таймлайном.
 */
@Composable
fun KeyListDialog(vm: MainViewModel, id: Int, onDismiss: () -> Unit) {
    val animation by vm.animation.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val rows = remember(animation, id) { vm.keyListOf(id) }
    val fps = animation.settings.fps
    val sub = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    var allMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Список ключей") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(vm.layerName(id), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (rows.isEmpty()) {
                    Text("У этого слоя пока нет ключей", fontSize = 13.sp, color = sub)
                    return@Column
                }
                Text("Ключей: ${rows.size}", fontSize = 12.sp, color = sub)
                for (r in rows) {
                    var menu by remember(r.frame) { mutableStateOf(false) }
                    val here = r.frame == frame
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (here) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { vm.goToKey(id, r.frame) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.width(64.dp)) {
                            Text("${r.frame + 1}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(TimeFormat.format(r.frame, fps), fontSize = 10.sp, color = sub, maxLines = 1)
                        }
                        Column(Modifier.weight(1f)) {
                            val t = r.transform
                            Text(if (t != null) com.vibetoon.domain.anim.KeyList.summary(t) else L("Только деформации"), fontSize = 11.sp, maxLines = 2)
                            if (r.tracks > 1) Text(L("Дорожек на кадре") + ": ${r.tracks}", fontSize = 10.sp, color = sub)
                        }
                        Box {
                            OutlinedButton(onClick = { menu = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text(if (r.custom) L("Своя кривая") else L(EASING_TITLES.firstOrNull { it.first == r.easing }?.second ?: "Линейно"),
                                    fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                for ((type, title) in EASING_TITLES) DropdownMenuItem(
                                    text = { Text(title) },
                                    leadingIcon = { if (!r.custom && type == r.easing) Icon(Icons.Default.Check, null) },
                                    onClick = { vm.setKeyEasingAt(id, r.frame, type); menu = false }
                                )
                            }
                        }
                        IconButton(onClick = { vm.deleteKeyAt(id, r.frame) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Delete, L("Удалить ключ")) }
                    }
                }
                Text("Интерполяция ключа действует до следующего ключа; ключ слоя задаёт её всем его дорожкам на этом кадре (трансформация и деформации). Тап по строке - перейти к ключу.",
                    fontSize = 11.sp, color = sub, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = {
            if (rows.isNotEmpty()) Box {
                TextButton(onClick = { allMenu = true }) { Text("Всем ключам…", maxLines = 1, softWrap = false) }
                DropdownMenu(expanded = allMenu, onDismissRequest = { allMenu = false }) {
                    for ((type, title) in EASING_TITLES) DropdownMenuItem(text = { Text(title) }, onClick = { vm.setAllKeysEasing(id, type); allMenu = false })
                }
            }
        }
    )
}
