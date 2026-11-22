package com.vibetoon.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Подпись с векторной иконкой слева (вместо эмодзи в тексте). */
@Composable
fun IconText(icon: ImageVector, text: String, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size((fontSize.value + 4f).dp), tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = fontSize, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/** Подпись выпадающего списка: текст и векторная стрелка справа (вместо «▾»). */
@Composable
fun DropText(text: String, fontSize: TextUnit = 12.sp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = fontSize, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Default.ArrowDropDown, null, Modifier.size((fontSize.value + 6f).dp))
    }
}
