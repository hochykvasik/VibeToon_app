package com.vibetoon.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Фон прозрачности только у миниатюр, исходное изображение не меняется. */
fun Modifier.thumbnailChecker(enabled: Boolean): Modifier = if (!enabled) this else drawBehind {
    val tile = 4.dp.toPx()
    drawRect(Color(0xFFECECEC))
    val columns = kotlin.math.ceil(size.width / tile).toInt()
    val rows = kotlin.math.ceil(size.height / tile).toInt()
    for (y in 0 until rows) for (x in 0 until columns) if ((x + y) % 2 == 1) {
        drawRect(Color(0xFFBDBDBD), Offset(x * tile, y * tile), Size(tile, tile))
    }
}
