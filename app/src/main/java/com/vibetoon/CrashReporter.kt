package com.vibetoon

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Записывает стек последнего падения приложения в файл; при следующем запуске экран показывает
 * отчёт, чтобы причину можно было прочитать без компьютера (adb не нужен).
 * Нативные падения (SIGSEGV) так не перехватываются.
 */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val f = file(ctx)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { f.writeText("Поток: ${thread.name}\n" + Log.getStackTraceString(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Отчёт о прошлом падении (и удаляет его), либо null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
