package com.vibetoon.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import com.vibetoon.ui.motion.AlertDialog
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
    // спринт 41: адаптивное окно (телефон в альбомной ориентации, планшет), закрывается только кнопкой
    com.vibetoon.ui.shell.AdaptiveDialog(
        onDismissRequest = {}, title = "Добро пожаловать в VibeToon",
        buttons = { Button({ s.onboarded = true; s.save() }) { Text("Начать работу") } }
    ) {
        Text("Настройте внешний вид. Всё можно изменить позже в «Настройках» → «Внешний вид».", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        AppearanceControls(s)
    }
}
