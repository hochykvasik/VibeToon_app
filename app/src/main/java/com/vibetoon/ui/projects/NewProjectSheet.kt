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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
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
import com.vibetoon.ui.i18n.Text

/** Локальное состояние листа. Чтения происходят только в соответствующих секциях. */
@Stable
private class NewProjectState(val defaultName: String) {
    var name by mutableStateOf(defaultName)
    var aspectIndex by mutableIntStateOf(0) // -1: пользовательский размер
    var width by mutableStateOf("1920")
    var height by mutableStateOf("1080")
    var fps by mutableIntStateOf(24)
    /** Спринт 32: своя частота 1–120 (пусто - выбран пресет). */
    var customFps by mutableStateOf("")
    var background by mutableLongStateOf(0L)

    val fpsValid by derivedStateOf { customFps.isEmpty() || com.vibetoon.domain.anim.FrameRates.parse(customFps) != null }

    val sizeValid by derivedStateOf {
        val w = width.toIntOrNull()
        val h = height.toIntOrNull()
        w != null && h != null && w in ProjectMath.MIN_SIZE..ProjectMath.MAX_SIZE &&
            h in ProjectMath.MIN_SIZE..ProjectMath.MAX_SIZE
    }

    fun selectAspect(index: Int) {
        aspectIndex = index
        if (index >= 0) {
            val aspect = ProjectMath.ASPECTS[index]
            val (w, h) = ProjectMath.sizeFor(aspect.w, aspect.h)
            width = w.toString()
            height = h.toString()
        }
    }

    fun spec() = NewProjectSpec(
        name = name.trim().ifBlank { defaultName },
        width = ProjectMath.clampSize(width.toIntOrNull() ?: 1920),
        height = ProjectMath.clampSize(height.toIntOrNull() ?: 1080),
        fps = fps,
        background = background
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(defaultName: String, onDismiss: () -> Unit, onCreate: (NewProjectSpec) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val form = remember(defaultName) { NewProjectState(defaultName) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProjectName(form)
            AspectPicker(form)
            ResolutionFields(form)
            FpsPicker(form)
            BackgroundPicker(form)
            CreateButton(form, onCreate)
        }
    }
}

@Composable
private fun ProjectName(form: NewProjectState) {
    OutlinedTextField(
        form.name, { form.name = it.take(60) }, label = { Text("Название") }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AspectPicker(form: NewProjectState) {
    SectionLabel("Пропорции")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectMath.ASPECTS.forEachIndexed { i, aspect ->
            FilterChip(selected = form.aspectIndex == i, onClick = { form.selectAspect(i) }, label = { Text(aspect.label) })
        }
        FilterChip(selected = form.aspectIndex == -1, onClick = { form.selectAspect(-1) }, label = { Text("Своё") })
    }
}

@Composable
private fun ResolutionFields(form: NewProjectState) {
    val width = form.width
    val height = form.height
    val valid = form.sizeValid
    val ratio = remember(width, height, valid) {
        if (valid) ProjectMath.ratioLabel(width.toInt(), height.toInt()) else null
    }
    SectionLabel("Разрешение (px)")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            width, { form.width = it.filter(Char::isDigit).take(4); form.aspectIndex = -1 },
            label = { Text("Ширина") }, singleLine = true, isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
        )
        Text("×")
        OutlinedTextField(
            height, { form.height = it.filter(Char::isDigit).take(4); form.aspectIndex = -1 },
            label = { Text("Высота") }, singleLine = true, isError = !valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
        )
    }
    if (ratio == null) {
        Text("Допустимо от ${ProjectMath.MIN_SIZE} до ${ProjectMath.MAX_SIZE} px", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    } else {
        Text("Пропорции $ratio", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FpsPicker(form: NewProjectState) {
    SectionLabel("Частота кадров")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (fps in ProjectMath.FPS_OPTIONS) {
            FilterChip(selected = form.fps == fps && form.customFps.isEmpty(), onClick = { form.fps = fps; form.customFps = "" }, label = { Text("$fps fps", maxLines = 1, softWrap = false) })
        }
    }
    val custom = com.vibetoon.domain.anim.FrameRates.parse(form.customFps)
    OutlinedTextField(
        value = form.customFps,
        onValueChange = { v ->
            form.customFps = v.filter { it.isDigit() }.take(3)
            com.vibetoon.domain.anim.FrameRates.parse(form.customFps)?.let { form.fps = it }
        },
        label = { Text("Своя частота (1-120 fps)") },
        isError = form.customFps.isNotEmpty() && custom == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundPicker(form: NewProjectState) {
    SectionLabel("Фон")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, argb) in BackgroundPresets.ALL) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (argb == 0L) Color(0xFFE0E0E0) else Color(argb.toInt()))
                        .border(
                            BorderStroke(if (form.background == argb) 3.dp else 1.dp, if (form.background == argb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            CircleShape
                        )
                        .clickable { form.background = argb },
                    contentAlignment = Alignment.Center
                ) {
                    if (argb == 0L) Text("∅", fontSize = 16.sp, color = Color(0xFF616161))
                }
                Text(label, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun CreateButton(form: NewProjectState, onCreate: (NewProjectSpec) -> Unit) {
    Button(
        onClick = { onCreate(form.spec()) },
        enabled = form.sizeValid && form.fpsValid,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)
    ) { Text("Создать") }
}
