package com.vibetoon.ui.timeline

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.i18n.Text

/**
 * Спринт 44: «Кривая ключа» в свойствах слоя. Показывается, когда у выбранного слоя однозначно выбран один ключ:
 * тапнут один ромб на таймлайне или плейхед стоит на ключе слоя. Пресеты плавности и «График…» меняют только этот
 * ключ (переход до следующего ключа); остальные ключи слоя и другие слои не трогаются. Для пачки ключей - кнопка
 * «Общая кривая» на таймлайне.
 */
@Composable
fun KeyCurveSection(vm: MainViewModel, id: Int) {
    val animation by vm.animation.collectAsStateWithLifecycle()
    val keySel by vm.keySelection.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    val info = remember(animation, keySel, frame, id) { vm.singleKeyInfo()?.takeIf { it.id == id } } ?: return
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Кривая ключа: кадр ${info.frame + 1}", fontSize = 12.sp, maxLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((type, title) in EASING_TITLES) FilterChip(
                selected = !info.custom && !info.mixed && info.easing == type, enabled = !playing, onClick = { vm.setSingleKeyEasing(type) },
                label = { Text(title, fontSize = 11.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.height(30.dp)
            )
            FilterChip(
                selected = info.custom && !info.mixed, enabled = !playing, onClick = vm::openSingleKeyCurve,
                label = { Text("График…", fontSize = 11.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.height(30.dp)
            )
        }
        Text(if (info.mixed) "У дорожек этого ключа разные кривые: выбор задаст одну всем" else "Меняется только этот ключ, до следующего; остальные ключи не трогаются",
            fontSize = 10.sp, color = muted)
    }
}
