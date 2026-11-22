package com.vibetoon.ui.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.project.BackgroundPresets
import com.vibetoon.domain.project.NewProjectSpec
import com.vibetoon.domain.project.ProjectMath

/**
 * Лист "Новый проект": название, пропорции (пресеты или свой размер), разрешение, частота кадров
 * и фон. Кнопка "Создать" открывает рабочий экран с выбранными параметрами.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewProjectSheet(defaultName: String, onDismiss: () -> Unit, onCreate: (NewProjectSpec) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(defaultName) }
    var aspectIndex by remember { mutableIntStateOf(0) }          // индекс в ASPECTS; -1 = свой размер
    var w by remember { mutableStateOf("1920") }
    var h by remember { mutableStateOf("1080") }
    var fps by remember { mutableIntStateOf(24) }
    var bg by remember { mutableLongStateOf(0L) }

    val wInt = w.toIntOrNull()
    val hInt = h.toIntOrNull()
    val sizeError = wInt == null || hInt == null ||
        wInt !in ProjectMath.MIN_SIZE..ProjectMath.MAX_SIZE || hInt !in ProjectMath.MIN_SIZE..ProjectMath.MAX_SIZE

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Пропорции", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectMath.ASPECTS.forEachIndexed { i, a ->
                    FilterChip(
                        selected = aspectIndex == i,
                        onClick = {
                            aspectIndex = i
                            val (pw, ph) = ProjectMath.sizeFor(a.w, a.h)
                            w = pw.toString(); h = ph.toString()
                        },
                        label = { Text(a.label) }
                    )
                }
                FilterChip(selected = aspectIndex == -1, onClick = { aspectIndex = -1 }, label = { Text("Своё") })
            }

            Text("Разрешение (px)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    w, { w = it.filter(Char::isDigit).take(4); aspectIndex = -1 },
                    label = { Text("Ширина") }, singleLine = true, isError = sizeError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
                Text("×")
                OutlinedTextField(
                    h, { h = it.filter(Char::isDigit).take(4); aspectIndex = -1 },
                    label = { Text("Высота") }, singleLine = true, isError = sizeError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
                )
            }
            if (sizeError) {
                Text(
                    "Допустимо от ${ProjectMath.MIN_SIZE} до ${ProjectMath.MAX_SIZE} px",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    "Пропорции ${ProjectMath.ratioLabel(wInt!!, hInt!!)}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Text("Частота кадров", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in ProjectMath.FPS_OPTIONS) {
                    FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f fps") })
                }
            }

            Text("Фон", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, argb) in BackgroundPresets.ALL) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (argb == 0L) Color(0xFFE0E0E0) else Color(argb.toInt()))
                                .border(
                                    BorderStroke(if (bg == argb) 3.dp else 1.dp, if (bg == argb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                                    CircleShape
                                )
                                .clickable { bg = argb },
                            contentAlignment = Alignment.Center
                        ) {
                            if (argb == 0L) Text("∅", fontSize = 16.sp, color = Color(0xFF616161))
                        }
                        Text(label, fontSize = 10.sp)
                    }
                }
            }

            Button(
                onClick = {
                    onCreate(
                        NewProjectSpec(
                            name = name.trim().ifBlank { defaultName },
                            width = ProjectMath.clampSize(wInt ?: 1920),
                            height = ProjectMath.clampSize(hInt ?: 1080),
                            fps = fps,
                            background = bg
                        )
                    )
                },
                enabled = !sizeError,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
            ) { Text("Создать") }
        }
    }
}
