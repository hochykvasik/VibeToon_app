package com.vibetoon.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.HotkeyAction
import com.vibetoon.domain.Hotkeys
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.theme.AppSettings

private val TITLES = mapOf(
    HotkeyAction.UNDO to "Отменить (с Ctrl)", HotkeyAction.REDO to "Повторить (с Ctrl)", HotkeyAction.PLAY_PAUSE to "Играть / пауза",
    HotkeyAction.BRUSH to "Кисть", HotkeyAction.ERASER to "Ластик", HotkeyAction.SELECT to "Снять инструмент",
    HotkeyAction.BRUSH_SMALLER to "Кисть меньше", HotkeyAction.BRUSH_BIGGER to "Кисть больше", HotkeyAction.DELETE to "Удалить"
)

private fun keyName(code: Int): String = android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

/** Вкладка «Keyboard»: переназначение 9 горячих клавиш физической клавиатуры. */
@Composable
fun HotkeyEditor(st: AppSettings) {
    var editing by remember { mutableStateOf<HotkeyAction?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (a in HotkeyAction.values()) {
            val code = st.hotkeys[a] ?: Hotkeys.DEFAULTS.getValue(a)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(TITLES.getValue(a), fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { editing = a }) { Text(keyName(code), fontSize = 13.sp) }
            }
        }
        OutlinedButton(onClick = { st.resetHotkeys() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Сбросить сочетания", fontSize = 13.sp) }
        Text("Нужна физическая клавиатура. Отмена и повтор работают вместе с Ctrl.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
    }
    editing?.let { a ->
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { fr.requestFocus() }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(TITLES.getValue(a)) },
            text = {
                Text("Нажмите новую клавишу на клавиатуре", fontSize = 14.sp, modifier = Modifier.focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown) {
                        val c = e.key.nativeKeyCode
                        if (c != android.view.KeyEvent.KEYCODE_BACK) { st.setHotkey(a, c); editing = null; true } else false
                    } else false
                })
            },
            confirmButton = {},
            dismissButton = { TextButton({ editing = null }) { Text("Отмена") } }
        )
    }
}
