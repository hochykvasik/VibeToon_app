package com.vibetoon.ui.i18n

import androidx.compose.runtime.Composable
import com.vibetoon.domain.project.DefaultBoneName

/** Произвольные пользовательские имена не переводим. */
@Composable
fun boneDisplayName(name: String): String = if (DefaultBoneName.isDefault(name)) L(name) else name

/** Перевод только стандартного вложенного имени в известных системных сообщениях о костях. */
@Composable
fun boneStatusMessage(message: String): String {
    val templates = listOf(
        Regex("^Кость создана, родитель: (Кость [1-9][0-9]*)$"),
        Regex("^(Кость [1-9][0-9]*): теперь корневая кость$"),
        Regex("^Привязано слоёв: [0-9]+ к «(Кость [1-9][0-9]*)» \\(вручную\\)$"),
        Regex("^Привязано к: (Кость [1-9][0-9]*)$")
    )
    val name = templates.firstNotNullOfOrNull { it.matchEntire(message)?.groupValues?.get(1) } ?: return message
    return message.replace(name, boneDisplayName(name))
}

@Composable
fun layerDisplayName(name: String, renamed: Boolean = false): String =
    if (!renamed && com.vibetoon.domain.project.DefaultLayerName.isDefault(name)) L(name) else name
