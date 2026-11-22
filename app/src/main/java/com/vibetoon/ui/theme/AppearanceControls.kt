package com.vibetoon.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.ui.i18n.Text

/** Выбор языка, темы и акцентного цвета: один и тот же блок в онбординге и во вкладке «Внешний вид» настроек. */
@Composable
fun AppearanceControls(s: AppSettings) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Язык", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
            for ((code, title) in listOf("ru" to "Русский", "en" to "English"))
                FilterChip(selected = s.effectiveLang == code, onClick = { s.lang = code; s.save() }, label = { Text(title) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тема", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
            for ((code, title) in listOf("light" to "Светлая", "dark" to "Тёмная", "system" to "Системная"))
                FilterChip(selected = s.theme == code, onClick = { s.theme = code; s.save() }, label = { Text(title) })
        }
        Text("Акцентный цвет", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ACCENTS.forEachIndexed { i, a ->
                val sel = s.accent == i
                val ring = MaterialTheme.colorScheme.onSurface
                Box(
                    Modifier.size(34.dp)
                        .drawBehind { if (sel) drawCircle(ring, radius = size.minDimension / 2f + 5.dp.toPx(), style = Stroke(2.5f.dp.toPx())) }
                        .clip(CircleShape).background(a.color)
                        .clickable { s.accent = i; s.save() }
                )
            }
        }
    }
}
