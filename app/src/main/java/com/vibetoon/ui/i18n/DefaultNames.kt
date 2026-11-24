package com.vibetoon.ui.i18n

import android.content.Context
import com.vibetoon.domain.settings.AppLanguage
import java.util.Locale

/** Язык приложения вне Compose (ViewModel, репозиторий): настройка «Язык» или язык системы. */
fun appLanguage(ctx: Context): String =
    AppLanguage.effective(ctx.applicationContext.getSharedPreferences(AppLanguage.PREFS, Context.MODE_PRIVATE).getString(AppLanguage.KEY, ""), Locale.getDefault().language)

/**
 * Спринт 30: стандартное имя нового объекта на текущем языке приложения («Слой» -> «Layer», «Фото копия» -> «Фото copy»).
 * Имя переводится один раз при создании; пользовательские имена не трогаются. Ключи - [com.vibetoon.domain.settings.DefaultNameKeys].
 */
fun defaultName(ctx: Context, ru: String): String = translate(ctx, appLanguage(ctx), ru)
