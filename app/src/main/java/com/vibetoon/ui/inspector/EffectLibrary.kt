package com.vibetoon.ui.inspector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibetoon.domain.anim.EffectCategory
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.FxDef

private val Navy = Color(0xFF2B2E4A)
private val Card = Color(0xFFF7F4FA)
private val Orange = Color(0xFFF08A3C)
private val Pink = Color(0xFFE8658A)

/**
 * «Библиотека эффектов»: на весь экран, сверху категории, ниже сетка карточек с превью (рисуются кодом,
 * без картинок). Тап по карточке добавляет эффект в цепочку выделенного слоя.
 */
@Composable
fun EffectLibraryDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var cat by remember { mutableStateOf<EffectCategory?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, "Назад", tint = Color.White) }
                    Text("Библиотека эффектов", color = Color.White, fontSize = 19.sp, modifier = Modifier.padding(start = 4.dp))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("Все") })
                    for (c in EffectCategory.values()) FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.title) })
                }
                val items = EffectMath.DEFS.filter { cat == null || it.category == cat }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(118.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(items, key = { it.type }) { d -> FxCard(d) { onPick(d.type) } }
                }
            }
        }
    }
}

@Composable
private fun FxCard(d: FxDef, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Card)) {
            Canvas(Modifier.fillMaxSize()) { preview(d.type) }
        }
        Text(d.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(d.subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Учебное превью эффекта: схематичная картинка «до/после» одним рисунком. */
private fun DrawScope.preview(type: Int) {
    val w = size.width; val h = size.height; val c = Offset(w / 2, h / 2); val r = w * 0.26f
    fun disc(o: Offset, rad: Float, col: Color) = drawCircle(col, rad, o)
    when (type) {
        EffectMath.CHROMA -> {
            drawRect(Color(0xFF3DBE6B)); disc(c, r, Orange)
            drawRect(Color(0xFFB9B9B9), Offset(w * 0.62f, h * 0.6f), Size(w * 0.38f, h * 0.4f))
        }
        EffectMath.BLUR -> for (i in 6 downTo 1) disc(c, r * (1f + i * 0.14f), Orange.copy(alpha = 0.12f + 0.06f * (7 - i)))
        EffectMath.COLOR -> {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF6A5ACD), Color(0xFFF08A3C), Color(0xFFE8658A))))
            drawRect(Color.White.copy(alpha = 0.18f), Offset(0f, h * 0.5f), Size(w, h * 0.5f))
        }
        EffectMath.PIXELATE -> {
            val n = 6; val s = w / n
            for (i in 0 until n) for (j in 0 until n) {
                val d = Offset(i * s + s / 2 - c.x, j * s + s / 2 - c.y).getDistance()
                drawRect(if (d < r * 1.1f) Orange else Pink.copy(alpha = 0.25f), Offset(i * s, j * s), Size(s - 1f, s - 1f))
            }
        }
        EffectMath.GLOW -> {
            for (i in 5 downTo 1) disc(c, r * (1f + i * 0.2f), Orange.copy(alpha = 0.10f))
            disc(c, r, Color(0xFFFFE0B0))
        }
        EffectMath.SHADOW -> {
            disc(c + Offset(r * 0.35f, r * 0.35f), r, Color.Black.copy(alpha = 0.35f)); disc(c, r, Pink)
        }
        EffectMath.INVERT -> {
            drawRect(Color(0xFF1B1B2F), size = Size(w / 2, h)); drawRect(Color(0xFFE4E4D0), Offset(w / 2, 0f), Size(w / 2, h))
            disc(Offset(w * 0.25f, h * 0.5f), r * 0.7f, Color(0xFFE0A33C)); disc(Offset(w * 0.75f, h * 0.5f), r * 0.7f, Color(0xFF1F5CC3))
        }
        EffectMath.VIGNETTE -> {
            disc(c, r * 1.2f, Orange)
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), c, w * 0.75f))
        }
        EffectMath.STROKE -> {
            drawCircle(Color(0xFF2B2E4A), r + 6f, c, style = Stroke(6f)); disc(c, r, Orange)
        }
        EffectMath.WAVE -> {
            for (i in 0 until 7) {
                val y = h * (0.15f + i * 0.11f)
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(0f, y)
                    for (x in 0..20) lineTo(w * x / 20f, y + kotlin.math.sin(x * 0.7f + i) * h * 0.04f)
                }
                drawPath(path, Pink, style = Stroke(5f))
            }
        }
        EffectMath.MIRROR -> {
            drawRect(Color(0xFFE8E0F0), Offset(w / 2 - 1f, 0f), Size(2f, h))
            disc(Offset(w * 0.32f, h * 0.45f), r * 0.65f, Orange); disc(Offset(w * 0.68f, h * 0.45f), r * 0.65f, Orange.copy(alpha = 0.6f))
        }
        EffectMath.TILES -> {
            for (i in 0 until 3) for (j in 0 until 3) {
                val o = Offset(i * w / 3 + w / 6, j * h / 3 + h / 6)
                drawRect(Color(0xFFE8E0F0), Offset(i * w / 3 + 2, j * h / 3 + 2), Size(w / 3 - 4, h / 3 - 4), style = Stroke(2f)); disc(o, w * 0.08f, Pink)
            }
        }
        EffectMath.NOISE -> {
            var s = 7L
            for (i in 0 until 160) {
                s = (s * 1103515245L + 12345L) and 0x7fffffffL
                val x = (s % 1000) / 1000f * w; s = (s * 1103515245L + 12345L) and 0x7fffffffL
                val y = (s % 1000) / 1000f * h
                drawCircle(Color.Black.copy(alpha = 0.25f), 2.2f, Offset(x, y))
            }
            disc(c, r, Orange.copy(alpha = 0.7f))
        }
        EffectMath.SHAKE -> {
            for (i in 1..3) disc(c + Offset((i - 2) * r * 0.4f, (i % 2) * r * 0.2f), r, Pink.copy(alpha = 0.25f * i))
        }
        EffectMath.SPIN -> {
            drawLine(Pink, Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.2f), strokeWidth = 12f)
            drawCircle(Orange.copy(alpha = 0.5f), w * 0.32f, c, style = Stroke(3f))
        }
        EffectMath.STRETCH_AXIS -> {
            // круг, растянутый вдоль диагональной оси
            drawLine(Color(0xFFE8E0F0), Offset(w * 0.1f, h * 0.9f), Offset(w * 0.9f, h * 0.1f), strokeWidth = 2f)
            rotate(-45f, c) { drawOval(Pink, Offset(c.x - r * 1.5f, c.y - r * 0.75f), Size(r * 3f, r * 1.5f)) }
        }
        EffectMath.SWING -> {
            // маятник: два крайних положения и текущее
            val top = Offset(w * 0.5f, h * 0.12f)
            for ((a, alpha) in listOf(-30f to 0.3f, 30f to 0.3f, 12f to 1f)) {
                val rad = Math.toRadians(a.toDouble())
                val end = top + Offset(kotlin.math.sin(rad).toFloat(), kotlin.math.cos(rad).toFloat()) * h * 0.62f
                drawLine(Orange.copy(alpha = alpha), top, end, strokeWidth = 5f)
                disc(end, r * 0.42f, Pink.copy(alpha = alpha))
            }
        }
        EffectMath.MOTION -> {
            for (i in 0 until 6) disc(Offset(w * (0.25f + i * 0.1f), h * 0.5f), r * 0.7f, Pink.copy(alpha = 0.12f + i * 0.1f))
        }
        EffectMath.ZOOM -> {
            for (i in 0 until 12) {
                val a = i * 0.5236f
                drawLine(Orange.copy(alpha = 0.6f), c + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * r * 0.4f, c + Offset(kotlin.math.cos(a), kotlin.math.sin(a)) * w * 0.45f, strokeWidth = 4f)
            }
            disc(c, r * 0.5f, Pink)
        }
        else -> disc(c, r, Orange)
    }
}
