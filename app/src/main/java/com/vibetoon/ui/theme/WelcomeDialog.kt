package com.vibetoon.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vibetoon.ui.i18n.Text

/** Первый запуск: язык, тема и акцентный цвет. Выбор применяется сразу (предпросмотр прямо в окне) и сохраняется кнопкой «Начать работу». */
@Composable
fun WelcomeDialog(s: AppSettings) {
    AlertDialog(
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f),
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        title = { Text("Добро пожаловать в VibeToon") },
        text = {
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Text("Настройте внешний вид. Всё можно изменить позже в «Настройках» → «Внешний вид».", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                AppearanceControls(s)
            }
        },
        confirmButton = { Button({ s.onboarded = true; s.save() }) { Text("Начать работу") } }
    )
}
