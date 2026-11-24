package com.vibetoon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vibetoon.ui.motion.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.vibetoon.ui.i18n.Text
import com.vibetoon.ui.theme.AppSettings
import com.vibetoon.ui.theme.LocalAppSettings
import com.vibetoon.ui.theme.VibeTheme
import com.vibetoon.ui.theme.WelcomeDialog
import com.vibetoon.ui.theme.isDarkNow
import com.vibetoon.ui.i18n.localizedContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibetoon.ui.AppViewModel
import com.vibetoon.ui.MainScreen
import com.vibetoon.ui.MainViewModel
import com.vibetoon.ui.Screen
import com.vibetoon.ui.projects.ProjectsScreen

class MainActivity : ComponentActivity() {
    private val app: AppViewModel by viewModels()
    private val editor: MainViewModel by viewModels()
    private lateinit var settings: AppSettings

    /** Физическая клавиатура: срабатывает, только если клавишу не взял сам экран (поле ввода, кнопка). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (app.screen.value is Screen.Editor && event.repeatCount == 0) {
            val a = com.vibetoon.domain.Hotkeys.mapCustom(keyCode, event.isCtrlPressed, event.isShiftPressed, if (::settings.isInitialized) settings.hotkeys.toMap() else emptyMap())
            if (a != null) { editor.onHotkey(a); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onPause() {
        super.onPause()
        // Пакет 27: экспорт идёт по задаче GL-потока на кадр - пауза поверхности ждёт не дольше одного кадра,
        // экспорт останавливается на текущем кадре и продолжается после возврата (раньше он отменялся).
        com.vibetoon.render.RenderHost.view?.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        editor.onTrimMemory(level)   // спринт 31: кэш рисунков покадровых слоёв отдаёт память
    }

    override fun onResume() {
        super.onResume()
        com.vibetoon.render.RenderHost.view?.onResume()
        editor.restoreCaches()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-Edge: окно занимает весь физический экран (в том числе область вывыреза камеры), системные панели прозрачные
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            // Immersive Sticky: системные панели скрыты, по свайпу от края появляются поверх интерфейса и сами прячутся
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideSystemBars()
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
            // панель показалась (свайп): через ~1.5 с прячем снова
            if (vis and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) window.decorView.postDelayed({ hideSystemBars() }, 1500)
        }
        CrashReporter.install(applicationContext)
        val lastCrash = CrashReporter.take(applicationContext)
        settings = AppSettings(this)
        setContent {
            val locCtx = remember(settings.effectiveLang) { localizedContext(this@MainActivity, settings.effectiveLang) }
            // Спринт 31: анимации панелей и окон - настройка приложения + системный масштаб анимаций
            val motion = com.vibetoon.ui.motion.rememberMotion(settings.uiAnimations)
            CompositionLocalProvider(LocalContext provides locCtx, LocalConfiguration provides locCtx.resources.configuration, LocalAppSettings provides settings, com.vibetoon.ui.motion.LocalMotion provides motion) {
            VibeTheme(settings) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (!settings.onboarded || app.needsStarterRecovery) app.ensureStarterProject()
                }
                val dark = isDarkNow(settings)
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
                }
                // фон тянется на весь экран, а кнопки отступают от выреза камеры и системных панелей
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val screen by app.screen.collectAsStateWithLifecycle()
                when (val s = screen) {
                    // Сначала список проектов; рабочий экран открывается для выбранного проекта
                    Screen.Projects -> ProjectsScreen(app)
                    is Screen.Editor -> MainScreen(editor, s.id, s.psd) {
                        // выход: сохранить проект, снять миниатюру, вернуться к списку
                        editor.closeProject { app.goProjects() }
                    }
                }
                }
                if (!settings.onboarded) WelcomeDialog(settings)
                // Прошлый запуск закончился падением: показываем причину
                var report by remember { mutableStateOf(lastCrash) }
                report?.let { text ->
                    AlertDialog(
                        onDismissRequest = { report = null },
                        title = { Text("Прошлый запуск завершился ошибкой") },
                        text = {
                            Text(
                                text.take(2500),
                                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        },
                        confirmButton = {
                            TextButton({
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("VibeToon crash", text))
                            }) { Text("Копировать") }
                        },
                        dismissButton = { TextButton({ report = null }) { Text("Закрыть") } }
                    )
                }
            }
            }
        }
    }
}
