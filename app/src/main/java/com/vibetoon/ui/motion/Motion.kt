package com.vibetoon.ui.motion

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.vibetoon.domain.settings.UiMotion

/**
 * Спринт 31: плавные анимации интерфейса в стиле ToonSquid. Все переходы - только свойства слоя
 * (прозрачность, масштаб, сдвиг): без перекомпоновки и без изменения размера холста на каждом кадре,
 * поэтому на 90/120 Гц анимация идёт с частотой экрана. Длительности умножает на системный
 * «Масштаб длительности анимации» сам Compose; масштаб 0 или выключенная настройка - мгновенно.
 */
@Immutable
data class Motion(val enabled: Boolean)

val LocalMotion = staticCompositionLocalOf { Motion(enabled = true) }

/** Material 3 Emphasized: быстрый старт и мягкая остановка (появление), обратная кривая для ухода. */
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
/** Спринт 42: мягкая S-кривая (Material standard) для боковой панели: плавный старт и плавная остановка. */
private val SoftEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val SoftExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Системный масштаб анимаций; обновляется на лету при смене в настройках Android. */
@Composable
fun rememberSystemAnimatorScale(): Float {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember { mutableFloatStateOf(readScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { scale = readScale(resolver) }
        }
        runCatching { resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer) }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return scale
}

private fun readScale(resolver: android.content.ContentResolver): Float =
    runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

/** Состояние анимаций для корня приложения: системный масштаб + настройка «Плавные анимации интерфейса». */
@Composable
fun rememberMotion(appEnabled: Boolean): Motion {
    val scale = rememberSystemAnimatorScale()
    val enabled = UiMotion.enabled(scale, appEnabled)
    return remember(enabled) { Motion(enabled) }
}

object Motions {
    /** Боковая панель выезжает от своего края ([fromEnd] - справа) и проявляется. */
    @Composable
    fun panelEnter(fromEnd: Boolean = true): EnterTransition {
        if (!LocalMotion.current.enabled) return EnterTransition.None
        return slideInHorizontally(tween(UiMotion.PANEL_MS, easing = EnterEasing)) { if (fromEnd) it else -it } +
            fadeIn(tween(UiMotion.PANEL_MS / 2))
    }

    @Composable
    fun panelExit(toEnd: Boolean = true): ExitTransition {
        if (!LocalMotion.current.enabled) return ExitTransition.None
        return slideOutHorizontally(tween(UiMotion.EXIT_MS, easing = ExitEasing)) { if (toEnd) it else -it } +
            fadeOut(tween(UiMotion.EXIT_MS))
    }

    /** Верхняя/нижняя панель: выезд по вертикали ([fromTop] - сверху). */
    /**
     * Спринт 42: правая панель (слои/инспектор). Мягкое появление: сдвиг с плавным торможением и проявление;
     * уход - плавный разгон и торможение, без резкого ускорения у края. [fromEnd] - панель у правого края
     * (в режиме левши - у левого, тогда false).
     */
    @Composable
    fun sidePanelEnter(fromEnd: Boolean): EnterTransition {
        if (!LocalMotion.current.enabled) return EnterTransition.None
        return slideInHorizontally(tween(UiMotion.SIDE_PANEL_MS, easing = SoftEasing)) { if (fromEnd) it else -it } +
            fadeIn(tween(UiMotion.SIDE_PANEL_MS * 2 / 3, easing = SoftEasing))
    }

    @Composable
    fun sidePanelExit(toEnd: Boolean): ExitTransition {
        if (!LocalMotion.current.enabled) return ExitTransition.None
        return slideOutHorizontally(tween(UiMotion.SIDE_PANEL_EXIT_MS, easing = SoftExitEasing)) { if (toEnd) it else -it } +
            fadeOut(tween(UiMotion.SIDE_PANEL_EXIT_MS, easing = SoftExitEasing))
    }

    /**
     * Спринт 57: ход выезда правой панели планшета (0..1) - та же мягкая кривая и длительность, что у
     * [sidePanelEnter]/[sidePanelExit]; по нему едут и панель, и край холста.
     */
    fun sidePanelSpec(opening: Boolean): androidx.compose.animation.core.AnimationSpec<Float> =
        if (opening) tween(UiMotion.SIDE_PANEL_MS, easing = SoftEasing) else tween(UiMotion.SIDE_PANEL_EXIT_MS, easing = SoftExitEasing)

    /** Спринт 42: индикатор края (стрелка у закрытой панели) - только прозрачность. */
    @Composable
    fun hintEnter(): EnterTransition =
        if (!LocalMotion.current.enabled) EnterTransition.None else fadeIn(tween(UiMotion.SIDE_PANEL_MS, delayMillis = UiMotion.SIDE_PANEL_EXIT_MS / 2))

    @Composable
    fun hintExit(): ExitTransition =
        if (!LocalMotion.current.enabled) ExitTransition.None else fadeOut(tween(UiMotion.EXIT_MS / 2))

    @Composable
    fun barEnter(fromTop: Boolean): EnterTransition {
        if (!LocalMotion.current.enabled) return EnterTransition.None
        return slideInVertically(tween(UiMotion.PANEL_MS, easing = EnterEasing)) { if (fromTop) -it else it } +
            fadeIn(tween(UiMotion.PANEL_MS / 2))
    }

    @Composable
    fun barExit(toTop: Boolean): ExitTransition {
        if (!LocalMotion.current.enabled) return ExitTransition.None
        return slideOutVertically(tween(UiMotion.EXIT_MS, easing = ExitEasing)) { if (toTop) -it else it } +
            fadeOut(tween(UiMotion.EXIT_MS))
    }

    /** Плавающая строка инструментов над холстом: короткий сдвиг сверху и проявление. */
    @Composable
    fun toolbarEnter(): EnterTransition {
        if (!LocalMotion.current.enabled) return EnterTransition.None
        return fadeIn(tween(UiMotion.TOOLBAR_MS)) +
            slideInVertically(tween(UiMotion.TOOLBAR_MS, easing = EnterEasing)) { -(it * UiMotion.TOOLBAR_OFFSET).toInt() }
    }

    @Composable
    fun toolbarExit(): ExitTransition {
        if (!LocalMotion.current.enabled) return ExitTransition.None
        return fadeOut(tween(UiMotion.EXIT_MS)) +
            slideOutVertically(tween(UiMotion.EXIT_MS, easing = ExitEasing)) { -(it * UiMotion.TOOLBAR_OFFSET).toInt() }
    }

    /** Всплывающая карточка (панель маркеров и т.п.): проявление с лёгким увеличением. */
    @Composable
    fun popEnter(): EnterTransition {
        if (!LocalMotion.current.enabled) return EnterTransition.None
        return fadeIn(tween(UiMotion.DIALOG_MS)) + scaleIn(tween(UiMotion.DIALOG_MS, easing = EnterEasing), initialScale = UiMotion.DIALOG_START_SCALE)
    }

    @Composable
    fun popExit(): ExitTransition {
        if (!LocalMotion.current.enabled) return ExitTransition.None
        return fadeOut(tween(UiMotion.EXIT_MS)) + scaleOut(tween(UiMotion.EXIT_MS, easing = ExitEasing), targetScale = UiMotion.DIALOG_START_SCALE)
    }
}

/**
 * Появление окна: прозрачность 0 -> 1 и масштаб 0,94 -> 1. Значение читается внутри graphicsLayer,
 * поэтому каждый кадр анимации - только перерисовка слоя, без рекомпозиции содержимого окна.
 */
@Composable
fun Modifier.motionAppear(): Modifier {
    if (!LocalMotion.current.enabled) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(UiMotion.DIALOG_MS, easing = EnterEasing)) }
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        val s = UiMotion.DIALOG_START_SCALE + (1f - UiMotion.DIALOG_START_SCALE) * p
        scaleX = s
        scaleY = s
    }
}
