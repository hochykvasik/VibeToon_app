package com.vibetoon.ui.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Шаг подсказки: заголовок, текст и id кнопки редактора, которую нужно подсветить (null - только текст). */
/** Учебный проект: после создания в нём автоматически появляются три фигуры на отдельных слоях. */
object Lesson { @Volatile var pending = false }

class CoachStep(val title: String, val text: String, val target: String?)

/** Подсказки поверх редактора (coachmarks): пульсирующая обводка кнопки и карточка с текстом внизу. */
object Coach {
    class Session(val title: String, val color: Color, val steps: List<CoachStep>)

    /** Активная сессия; null - подсказок нет. Читается из composable, поэтому это состояние Compose. */
    var session by mutableStateOf<Session?>(null)
        private set
    var index by mutableStateOf(0)
        private set

    /** Границы зарегистрированных кнопок в координатах окна. */
    val bounds = mutableStateMapOf<String, Rect>()
    /** Popup имеет собственное окно; подсветку для него рисуем внутри самого Popup. */
    var popupTargetVisible by mutableStateOf(false)

    fun start(s: Session) { session = s; index = 0 }
    fun stop() { session = null; index = 0; popupTargetVisible = false }
    fun next() { val s = session ?: return; if (index < s.steps.lastIndex) index++ else stop() }
    fun prev() { if (index > 0) index-- }
}

/** Помечает элемент как цель подсказки: его границы сохраняются, пока он на экране. */
fun Modifier.coachTarget(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { Coach.bounds.remove(id) } }
    onGloballyPositioned { Coach.bounds[id] = it.boundsInWindow() }
}

@Composable
fun CoachOverlay() {
    val s = Coach.session ?: return
    val step = s.steps.getOrNull(Coach.index) ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val pulse = rememberInfiniteTransition(label = "coach")
    val k by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "k")
    // Координаты отдельного Popup нельзя смешивать с координатами окна редактора.
    val popupTarget = step.target == "Тестовый PSD"
    val rect = if (popupTarget) null else step.target?.let { Coach.bounds[it] }?.let { CoachGeometry.visible(it, origin, viewport) }
    Box(Modifier.fillMaxSize().onGloballyPositioned {
        origin = it.positionInWindow()
        viewport = Size(it.size.width.toFloat(), it.size.height.toFloat())
    }) {
        if (rect != null) Canvas(Modifier.fillMaxSize()) {
            val grow = 4f + 10f * k
            val tl = rect.topLeft - Offset(grow, grow)
            val sz = Size(rect.width + grow * 2, rect.height + grow * 2)
            drawRoundRect(s.color.copy(alpha = 0.25f + 0.2f * k), tl, sz, CornerRadius(18f), style = Stroke(width = 14f))
            drawRoundRect(s.color, tl, sz, CornerRadius(18f), style = Stroke(width = 5f))
        }
        Surface(
            color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 8.dp,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 520.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${com.vibetoon.ui.i18n.L(s.title)} · шаг ${Coach.index + 1} из ${s.steps.size}", fontSize = 11.sp, color = s.color)
                Text(step.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(step.text, fontSize = 13.sp, lineHeight = 18.sp)
                if (step.target != null && (if (popupTarget) !Coach.popupTargetVisible else rect == null))
                    Text("Кнопка сейчас не видна на экране: откройте нужную панель или режим.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = Coach::stop) { Text("Пропустить") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = Coach::prev, enabled = Coach.index > 0) { Text("Назад") }
                    Button(onClick = Coach::next) { Text(if (Coach.index == s.steps.lastIndex) "Готово" else "Далее") }
                }
            }
        }
    }
}
