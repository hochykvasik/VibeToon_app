package com.vibetoon.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibetoon.domain.input.PressureCurve
import com.vibetoon.domain.input.StrokeStabilizer
import com.vibetoon.domain.input.StylusSample
import com.vibetoon.ui.i18n.L
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.theme.AppSettings

/**
 * Stylus sprint: график кривой нажима. [live] - точка текущего нажима (сырой нажим пера по X, нажим кисти по Y).
 * В режиме [onChange] узлы перетаскиваются, касание пустого места добавляет узел, двойное касание узла удаляет его;
 * [onCommit] вызывается при отпускании (сохранение в настройки).
 */
@Composable
fun PressureCurveGraph(
    curve: PressureCurve,
    live: Float?,
    modifier: Modifier = Modifier,
    onChange: ((PressureCurve) -> Unit)? = null,
    onCommit: (() -> Unit)? = null
) {
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.tertiary
    val desc = L("График кривой нажима")
    val state = curve.preset()?.title?.let { L(it) } ?: L("Своя кривая")
    val cur by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val gestures = if (onChange != null) Modifier.pointerInput(Unit) {
        var lastTap = 0L
        var lastTapIndex = -1
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            val w = size.width.toFloat().coerceAtLeast(1f); val h = size.height.toFloat().coerceAtLeast(1f)
            fun ux(x: Float) = (x / w).coerceIn(0f, 1f)
            fun uy(y: Float) = (1f - y / h).coerceIn(0f, 1f)
            var index = cur.nearest(ux(down.position.x), uy(down.position.y), 0.09f)
            val tappedNode = index >= 0
            if (index < 0) {
                val (next, at) = cur.insert(ux(down.position.x), uy(down.position.y))
                if (at >= 0) { change?.invoke(next); index = at }
            }
            var moved = false
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (moved && index >= 0) change?.invoke(cur.move(index, ux(c.position.x), uy(c.position.y)))
                c.consume()
            }
            if (tappedNode && !moved) {
                if (index == lastTapIndex && down.uptimeMillis - lastTap < 350L) { change?.invoke(cur.remove(index)); lastTapIndex = -1 }
                else { lastTapIndex = index; lastTap = down.uptimeMillis }
            } else lastTapIndex = -1
            commit?.invoke()
        }
    } else Modifier
    Canvas(
        modifier.clip(RoundedCornerShape(8.dp)).background(surface.copy(alpha = 0.45f))
            .border(1.dp, outline.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .semantics { contentDescription = desc; stateDescription = state }
            .then(gestures)
    ) {
        val w = size.width; val h = size.height
        for (i in 1..3) {
            val f = i / 4f
            drawLine(outline.copy(alpha = 0.25f), Offset(w * f, 0f), Offset(w * f, h))
            drawLine(outline.copy(alpha = 0.25f), Offset(0f, h * f), Offset(w, h * f))
        }
        drawLine(outline.copy(alpha = 0.5f), Offset(0f, h), Offset(w, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        val ys = curve.sample(64)
        val path = Path()
        ys.forEachIndexed { i, y ->
            val x = w * i / (ys.size - 1); val yy = h * (1f - y)
            if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        drawPath(path, primary, style = Stroke(width = 2.5f * density))
        if (onChange != null) for (pt in curve.points) {
            drawCircle(primary, 5.5f * density, Offset(w * pt.x, h * (1f - pt.y)))
            drawCircle(Color.White, 2.5f * density, Offset(w * pt.x, h * (1f - pt.y)))
        }
        if (live != null && live.isFinite()) {
            val lx = live.coerceIn(0f, 1f); val ly = curve.map(lx)
            drawLine(accent.copy(alpha = 0.6f), Offset(w * lx, h), Offset(w * lx, h * (1f - ly)), strokeWidth = 1.5f * density)
            drawCircle(accent, 6f * density, Offset(w * lx, h * (1f - ly)))
        }
    }
}

/** Вкладка «Стилус»: кривая нажима с пресетами и тестовой площадкой, стабилизация 1–10, ластик на обратном конце пера. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun StylusSettingsSection(st: AppSettings) {
    var curve by remember(st.pressureCurve) { mutableStateOf(PressureCurve.decode(st.pressureCurve)) }
    var testRaw by remember { mutableStateOf<Float?>(null) }
    var testStylus by remember { mutableStateOf(true) }
    var tilt by remember { mutableStateOf<Int?>(null) }
    var tiltRad by remember { mutableStateOf(Float.NaN) }
    val tracker = remember { com.vibetoon.ui.StylusTracker() }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Чувствительность стилуса", fontSize = 14.sp)
        Text("Кривая переводит нажим пера (по горизонтали) в нажим кисти (по вертикали): выше диагонали - мягче, ниже - жёстче. Действует на кисть и ластик", fontSize = 11.sp, color = muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val active = curve.preset()
            PressureCurve.Preset.entries.forEach { pr ->
                FilterChip(selected = active == pr, onClick = { curve = pr.curve; st.pressureCurve = curve.encode(); st.saveStylus() },
                    label = { Text(pr.title, fontSize = 12.sp) })
            }
            if (active == null) FilterChip(selected = true, onClick = {}, label = { Text("Своя кривая", fontSize = 12.sp) })
        }
        PressureCurveGraph(
            curve, testRaw, Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1.4f),
            onChange = { curve = it }, onCommit = { st.pressureCurve = curve.encode(); st.saveStylus() }
        )
        Text("Перетащите узел; коснитесь пустого места - новый узел; двойное касание узла - удалить", fontSize = 11.sp, color = muted)
        // тестовая площадка: нажим и наклон пера видны на графике сразу
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .motionEventSpy { tracker.onEvent(it) }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        testStylus = stylus
                        var last = StylusSample.pressure(down.pressure, stylus)
                        testRaw = last
                        tilt = if (stylus) StylusSample.tiltDegrees(tracker.tiltRadians) else null
                        tiltRad = if (stylus) tracker.tiltRadians else Float.NaN
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            stylus = c.type == PointerType.Stylus || c.type == PointerType.Eraser
                            last = StylusSample.pressure(c.pressure, stylus, last)
                            testRaw = last
                            if (stylus) { tilt = StylusSample.tiltDegrees(tracker.tiltRadians); tiltRad = tracker.tiltRadians }
                            c.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("Проведите пером здесь, чтобы проверить нажим", fontSize = 12.sp, color = muted)
        }
        val raw = testRaw
        if (raw != null) {
            val mapped = if (testStylus) curve.map(raw).coerceIn(StylusSample.MIN_PRESSURE, 1f) else 1f
            Text("Нажим пера: ${Math.round(raw * 100)} % → кисть: ${Math.round(mapped * 100)} %", fontSize = 12.sp)
            if (!testStylus) Text("Палец или перо без датчика нажима рисуют с нажимом 100 %", fontSize = 11.sp, color = muted)
            else {
                val t = tilt; Text(if (t != null) "Наклон пера: $t°" else "Наклон пера: нет данных", fontSize = 11.sp, color = muted)
                // спринт 40: что наклон сделает с кистью при текущем режиме
                if (t != null) Text(com.vibetoon.domain.input.TiltMath.describe(com.vibetoon.domain.input.TiltMath.settings(st.stylusTilt, st.stylusTiltStrength), tiltRad), fontSize = 11.sp)
            }
        }
        // спринт 40: наклон пера меняет кисть (толщина, штриховка, плоское перо); палец и перо без датчика - без изменений
        Text("Наклон пера", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            com.vibetoon.domain.input.TiltMath.Mode.values().forEach { m ->
                FilterChip(selected = st.stylusTilt == m.id, onClick = { st.stylusTilt = m.id; st.saveStylus() }, label = { Text(m.ru, fontSize = 12.sp) })
            }
        }
        if (st.stylusTilt != com.vibetoon.domain.input.TiltMath.Mode.OFF.id) {
            Text("Сила наклона: ${st.stylusTiltStrength} %", fontSize = 12.sp)
            val tiltDesc = L("Сила наклона")
            Slider(
                value = st.stylusTiltStrength.toFloat(), valueRange = 0f..100f,
                onValueChange = { st.stylusTiltStrength = Math.round(it).coerceIn(0, 100) },
                onValueChangeFinished = { st.saveStylus() },
                modifier = Modifier.semantics { contentDescription = tiltDesc }
            )
        }
        Text("Толщина - наклонённое перо рисует шире; Штриховка - шире и прозрачнее, как карандаш боком; Плоское перо - штамп сплющивается и поворачивается по направлению пера. Действует на кисть, ластик и векторные кисти, кроме «Ровная»", fontSize = 11.sp, color = muted)

        Text("Стабилизация: ${st.stabilization}", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        val stabDesc = L("Стабилизация")
        Slider(
            value = st.stabilization.toFloat(), valueRange = StrokeStabilizer.MIN_LEVEL.toFloat()..StrokeStabilizer.MAX_LEVEL.toFloat(), steps = 8,
            onValueChange = { st.stabilization = Math.round(it).coerceIn(StrokeStabilizer.MIN_LEVEL, StrokeStabilizer.MAX_LEVEL) },
            onValueChangeFinished = { st.saveStylus() },
            modifier = Modifier.semantics { contentDescription = stabDesc }
        )
        Text("1 - почти сырой штрих, 10 - сильное сглаживание для чистого лайнарта. При отпускании хвост линии догоняет перо, конец не теряется. Работает с кистью, ластиком, направляющими и симметрией", fontSize = 11.sp, color = muted)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ластик на обратном конце пера", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = st.stylusEraserTip, onCheckedChange = { st.stylusEraserTip = it; st.saveStylus() })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Показывать нажим при рисовании", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = st.pressureHud, onCheckedChange = { st.pressureHud = it; st.saveStylus() })
        }
        Text("Защита от ладони: вкладка «Жесты» → «Определение касаний»", fontSize = 11.sp, color = muted)
        OutlinedButton(onClick = { st.resetStylus(); curve = PressureCurve.LINEAR }, modifier = Modifier.fillMaxWidth()) { Text("Сбросить настройки пера", fontSize = 13.sp) }
    }
}
