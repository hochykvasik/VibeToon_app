package com.vibetoon.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.vibetoon.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Спринт 41: окно, которое всегда помещается на экран телефона и планшета. Стандартный AlertDialog берёт ширину
 * платформы и обрезал нижний ряд пресетов FPS и карточки библиотеки эффектов. Здесь ширина - до [maxWidth], но не
 * больше экрана за вычетом системных панелей и выреза камеры; высота - не больше экрана; содержимое прокручивается,
 * а заголовок и кнопки остаются на месте. Тап вне окна закрывает его.
 */
@Composable
fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    title: String,
    maxWidth: Dp = 560.dp,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest, properties = fullWindowDialogProperties()) {
        FullWindowDialogSetup()
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = minOf(maxWidth, this.maxWidth)).fillMaxWidth()
                    .heightIn(max = this.maxHeight)
                    // тап по самому окну не закрывает его
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
                    val body = Modifier.weight(1f, fill = false).fillMaxWidth()
                    Column(if (scrollable) body.verticalScroll(rememberScrollState()) else body, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                    if (buttons != null) Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically
                    ) { buttons() }
                }
            }
        }
    }
}

/** Свойства окна-диалога на весь экран (вставки системных панелей и выреза учитывает содержимое). */
fun fullWindowDialogProperties(dismissOnBackPress: Boolean = true, dismissOnClickOutside: Boolean = true) =
    DialogProperties(dismissOnBackPress = dismissOnBackPress, dismissOnClickOutside = dismissOnClickOutside,
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

/**
 * Спринт 41: окно диалога - ровно на весь экран, как окно приложения (edge-to-edge, режим выреза ALWAYS/SHORT_EDGES).
 * Иначе на планшете окно диалога сдвигалось на ширину выреза/панели, а содержимое мерилось по всему экрану - правый
 * край карточек «Библиотеки эффектов» уходил за экран. Вызывать первой строкой содержимого Dialog; отступы от
 * системных панелей и выреза даёт windowInsetsPadding(WindowInsets.safeDrawing).
 */
@Composable
fun FullWindowDialogSetup() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val w = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@SideEffect
        w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val mode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (w.attributes.layoutInDisplayCutoutMode != mode) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = mode }
        }
    }
}
