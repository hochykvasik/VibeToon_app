package com.vibetoon.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.domain.ui.ReferenceWindow
import com.vibetoon.ui.i18n.L
import com.vibetoon.ui.i18n.Text

/**
 * Спринт 56: плавающее окно референса поверх редактора (как в ibisPaint): картинка-образец, которую можно двигать за
 * шапку, менять размер за уголок, сворачивать в полоску, заменять и закрывать. Это только окно интерфейса - сцена,
 * кадр, проект и экспорт его не видят. Касания по окну не доходят до холста.
 */
@Composable
fun ReferenceWindowOverlay(vm: MainViewModel, onReplace: () -> Unit) {
    val ref by vm.reference.collectAsStateWithLifecycle()
    val r = ref ?: return
    val stored by vm.referenceFrame.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val areaW = maxWidth.value
        val areaH = maxHeight.value
        val aspect = r.aspect
        val frame = ReferenceWindow.clamp(stored ?: ReferenceWindow.initial(aspect, areaW, areaH), aspect, areaW, areaH)
        val current by rememberUpdatedState(frame)
        val (w, h) = ReferenceWindow.size(frame, aspect, areaW, areaH)
        val density = LocalDensity.current.density
        Surface(
            modifier = Modifier.offset(frame.x.dp, frame.y.dp).size(w.dp, h.dp),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().height(ReferenceWindow.BAR.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(areaW, areaH, aspect) {
                            // рамка копится внутри жеста: между перерисовками приходит несколько сдвигов
                            var f = current
                            detectDragGestures(onDragStart = { f = current }) { change, drag ->
                                change.consume()
                                f = ReferenceWindow.moveBy(f, drag.x / density, drag.y / density, aspect, areaW, areaH)
                                vm.setReferenceFrame(f)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragIndicator, L("Перетащите, чтобы передвинуть окно"), modifier = Modifier.padding(start = 4.dp).size(18.dp))
                    Text("Референс", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    IconButton(onReplace, Modifier.size(30.dp)) { Icon(Icons.Default.Image, L("Заменить картинку"), Modifier.size(18.dp)) }
                    IconButton({ vm.setReferenceFrame(ReferenceWindow.toggleCollapsed(current, aspect, areaW, areaH)) }, Modifier.size(30.dp)) {
                        if (frame.collapsed) Icon(Icons.Default.CropSquare, L("Развернуть окно"), Modifier.size(18.dp))
                        else Icon(Icons.Default.Remove, L("Свернуть окно"), Modifier.size(18.dp))
                    }
                    IconButton(vm::closeReference, Modifier.size(30.dp)) { Icon(Icons.Default.Close, L("Закрыть референс"), Modifier.size(18.dp)) }
                }
                if (!frame.collapsed) Box(Modifier.fillMaxSize()) {
                    Image(r.bitmap, contentDescription = r.name.ifEmpty { null }, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    // уголок: ширина окна, пропорции картинки сохраняются
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(28.dp)
                            .pointerInput(areaW, areaH, aspect) {
                                var f = current
                                detectDragGestures(onDragStart = { f = current }) { change, drag ->
                                    change.consume()
                                    f = ReferenceWindow.resizeBy(f, drag.x / density, aspect, areaW, areaH)
                                    vm.setReferenceFrame(f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)) {
                            Icon(Icons.Default.OpenInFull, L("Потяните, чтобы изменить размер"), Modifier.padding(3.dp).size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
