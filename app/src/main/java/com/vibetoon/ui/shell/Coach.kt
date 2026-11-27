package com.vibetoon.ui.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.L
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.tutorial.CoachWindow
import kotlin.math.roundToInt

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

    /** Спринт 59: окно подсказки, сдвинутое пользователем (px окна редактора); null - авторазмещение сверху или снизу. */
    var customPos by mutableStateOf<CoachWindow.Pos?>(null)
        private set
    /** Спринт 59: окно свёрнуто в полоску «Шаг N из M» - видно интерфейс, урок не сбрасывается. */
    var compact by mutableStateOf(false)
        private set

    fun start(s: Session) { session = s; index = 0; customPos = null; compact = false }
    fun stop() { session = null; index = 0; popupTargetVisible = false; customPos = null; compact = false }
    fun toggleCompact() { compact = !compact }
    fun moveTo(p: CoachWindow.Pos) { customPos = p }
    fun next() { val s = session ?: return; if (index < s.steps.lastIndex) index++ else stop() }
    fun prev() { if (index > 0) index-- }
}

/** Помечает элемент как цель подсказки: его границы сохраняются, пока он на экране. */
fun Modifier.coachTarget(id: String): Modifier = composed {
    DisposableEffect(id) { onDispose { Coach.bounds.remove(id) } }
    onGloballyPositioned { Coach.bounds[id] = it.boundsInWindow() }
}

/** Спринт 59: последние размеры окна подсказки и области (px) - для перетаскивания между перерисовками; не состояние. */
private class CoachGeo {
    var pos = CoachWindow.Pos(0f, 0f)
    var w = 0f
    var h = 0f
    var areaW = 0f
    var areaH = 0f
}

/** Шапка окна подсказки: перетаскивание в пределах области редактора (как окно референса). */
private fun Modifier.coachDrag(geo: CoachGeo): Modifier = pointerInput(Unit) {
    detectDragGestures { change, drag ->
        change.consume()
        val cur = Coach.customPos ?: geo.pos
        Coach.moveTo(CoachWindow.moveBy(cur, drag.x, drag.y, geo.w, geo.h, geo.areaW, geo.areaH))
    }
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
    val popupTarget = com.vibetoon.domain.tutorial.CoachMenuTargets.inActionsMenu(step.target)   // спринт 57: пункты меню «Действия»
    val rect = if (popupTarget) null else step.target?.let { Coach.bounds[it] }?.let { CoachGeometry.visible(it, origin, viewport) }
    // автопроверка шага: цель не на экране - в окне подсказка, где её искать
    val targetHidden = step.target != null && (if (popupTarget) !Coach.popupTargetVisible else rect == null)
    val geo = remember { CoachGeo() }
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
        // спринт 53: подсвеченная кнопка внизу (таймлайн, строка рисунков) - карточка уходит наверх, чтобы не закрывать её
        val cardOnTop = rect != null && com.vibetoon.domain.tutorial.CoachPlacement.cardOnTop(rect.center.y, viewport.height)
        val compact = Coach.compact
        // спринт 59: окно без ручки размера - ширина фиксирована, высота по тексту (не выше 60% области, текст
        // прокручивается); положение - авторазмещение или куда его перетащили, всегда целиком в области
        Layout(
            content = {
                Surface(
                    color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp, shadowElevation = 8.dp,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (compact) CoachCompactBar(s, step, geo) else CoachCard(s, step, targetHidden, geo)
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { measurables, c ->
            val areaW = c.maxWidth.toFloat()
            val areaH = c.maxHeight.toFloat()
            val margin = CoachWindow.MARGIN_DP.dp.toPx()
            val w = CoachWindow.width(areaW, (if (compact) CoachWindow.COMPACT_W_DP else CoachWindow.MAX_W_DP).dp.toPx(), margin).roundToInt()
            val maxH = CoachWindow.maxHeight(areaH, CoachWindow.MIN_HEIGHT_DP.dp.toPx()).roundToInt()
            val card = measurables.first().measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = maxH))
            layout(c.maxWidth, c.maxHeight) {
                val pos = CoachWindow.place(Coach.customPos, w.toFloat(), card.height.toFloat(), areaW, areaH, margin, cardOnTop)
                geo.pos = pos; geo.w = w.toFloat(); geo.h = card.height.toFloat(); geo.areaW = areaW; geo.areaH = areaH
                card.place(pos.x.roundToInt(), pos.y.roundToInt())
            }
        }
    }
}

/** Развёрнутое окно: шапка (перетаскивание, свернуть, закрыть), текст шага с прокруткой, «Пропустить / Назад / Далее». */
@Composable
private fun CoachCard(s: Coach.Session, step: CoachStep, targetHidden: Boolean, geo: CoachGeo) {
    Column(Modifier.padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp).coachDrag(geo), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DragIndicator, L("Перетащите, чтобы передвинуть подсказку"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Text(
                "${L(s.title)} · шаг ${Coach.index + 1} из ${s.steps.size}", fontSize = 11.sp, color = s.color,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            IconButton(Coach::toggleCompact, Modifier.size(34.dp)) { Icon(Icons.Default.Remove, L("Свернуть подсказку"), Modifier.size(18.dp)) }
            IconButton(Coach::stop, Modifier.size(34.dp)) { Icon(Icons.Default.Close, L("Закрыть урок"), Modifier.size(18.dp)) }
        }
        // новый шаг - текст снова с начала
        val scroll: ScrollState = remember(s, Coach.index) { ScrollState(0) }
        Column(Modifier.weight(1f, fill = false).padding(end = 6.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(step.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(step.text, fontSize = 13.sp, lineHeight = 18.sp)
            if (targetHidden)
                Text("Кнопка сейчас не видна: выберите слой или откройте нужную панель или режим (на телефоне часть кнопок таймлайна - под «Ещё» ⋮).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = Coach::stop) { Text("Пропустить") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = Coach::prev, enabled = Coach.index > 0) { Text("Назад") }
            Button(onClick = Coach::next) { Text(if (Coach.index == s.steps.lastIndex) "Готово" else "Далее") }
        }
    }
}

/** Свёрнутое окно: полоска «Шаг N из M: заголовок» - её можно двигать, развернуть обратно или закрыть урок. */
@Composable
private fun CoachCompactBar(s: Coach.Session, step: CoachStep, geo: CoachGeo) {
    Row(Modifier.fillMaxWidth().height(44.dp).coachDrag(geo).padding(start = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 22.dp).background(s.color, RoundedCornerShape(2.dp)))
        Icon(Icons.Default.DragIndicator, L("Перетащите, чтобы передвинуть подсказку"), Modifier.padding(start = 4.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(
            "Шаг ${Coach.index + 1} из ${s.steps.size}: ${step.title}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
        )
        IconButton(Coach::toggleCompact, Modifier.size(38.dp)) { Icon(Icons.Default.CropSquare, L("Развернуть подсказку"), Modifier.size(18.dp)) }
        IconButton(Coach::stop, Modifier.size(38.dp)) { Icon(Icons.Default.Close, L("Закрыть урок"), Modifier.size(18.dp)) }
    }
}
