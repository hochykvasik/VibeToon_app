package com.vibetoon.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vibetoon.domain.export.BackgroundRender

/**
 * Спринт 41: служба переднего плана на время импорта/экспорта видео. Пока она работает, система не замораживает и не
 * выгружает процесс, если приложение свернули или ненадолго ушли в другое приложение, - работа продолжается, а в
 * шторке виден ход работы. Сама работа идёт в MainViewModel (корутины и GL-поток); служба только держит процесс и
 * показывает уведомление. Запускается, пока приложение на экране (жест пользователя), и останавливается сама, когда
 * последняя работа закончилась.
 */
class BackgroundWorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = BackgroundWork.notification(this)
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            // Android 12+: запуск из фона запрещён - работа продолжится без службы (как раньше)
            Log.w("VT/Work", "foreground service not started: ${t.message}")
            BackgroundWork.serviceStopped()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    override fun onTimeout(startId: Int) {
        // Android 15: лимит времени dataSync - служба останавливается, работа продолжается, пока процесс жив
        BackgroundWork.serviceStopped()
        stopForegroundCompat()
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 4107
        const val CHANNEL_ID = "vt_background_work"
        const val ACTION_STOP = "com.vibetoon.work.STOP"
    }
}

/** Учёт фоновых работ (импорт, экспорт) и уведомление службы. Вызовы - с главного потока. */
object BackgroundWork {
    /** Спринт 42: состояние работы для уведомления - заголовок, процент, кадр из скольких, время начала. */
    private data class Job(val title: String, val pct: Int?, val done: Int, val total: Int, val startedAt: Long)
    private val active = LinkedHashMap<Long, Job>()
    private var nextId = 1L
    @Volatile var running: Boolean = false
        private set
    /** MainActivity: запрос разрешения на уведомления (Android 13+), один раз за установку. */
    var requestNotificationPermission: (() -> Unit)? = null

    private fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("vt_app", Context.MODE_PRIVATE).getBoolean(BackgroundRender.PREF, true)

    /** Начало работы [title]; возвращает id для [progress]/[end] (0 - фоновая работа выключена в настройках). */
    fun begin(ctx: Context, title: String): Long {
        if (!enabled(ctx)) return 0L
        val id = nextId++
        active[id] = Job(title, null, 0, 0, System.currentTimeMillis())
        askPermissionOnce(ctx)
        start(ctx.applicationContext)
        return id
    }

    fun progress(ctx: Context, id: Long, done: Int, total: Int) {
        val cur = active[id] ?: return
        val pct = BackgroundRender.percent(done, total)
        // шторку обновляем при смене процента (не на каждом кадре): система ограничивает частоту обновлений
        if (pct == cur.pct && total == cur.total) return
        active[id] = cur.copy(pct = pct, done = done, total = total)
        if (running) runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(BackgroundWorkService.NOTIFICATION_ID, notification(ctx.applicationContext))
        }
    }

    fun end(ctx: Context, id: Long) {
        if (id == 0L || active.remove(id) == null) return
        val app = ctx.applicationContext
        if (active.isEmpty()) {
            running = false
            runCatching { app.startService(Intent(app, BackgroundWorkService::class.java).setAction(BackgroundWorkService.ACTION_STOP)) }
                .onFailure { runCatching { app.stopService(Intent(app, BackgroundWorkService::class.java)) } }
        } else if (running) runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(BackgroundWorkService.NOTIFICATION_ID, notification(app))
        }
    }

    /** Служба не запустилась или остановлена системой: GL-работа в фоне больше не разрешена. */
    fun serviceStopped() { running = false }

    private fun start(app: Context) {
        ensureChannel(app)
        try {
            val i = Intent(app, BackgroundWorkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            running = true
        } catch (t: Throwable) {
            Log.w("VT/Work", "background service unavailable: ${t.message}")
            running = false
        }
    }

    private fun askPermissionOnce(ctx: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val prefs = ctx.getSharedPreferences("vt_app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notif_permission_asked", false)) return
        prefs.edit().putBoolean("notif_permission_asked", true).apply()
        requestNotificationPermission?.invoke()
    }

    /** Перевод без Compose: язык приложения из настроек (как у L()). */
    private fun tr(ctx: Context, ru: String): String {
        val saved = ctx.getSharedPreferences("vt_app", Context.MODE_PRIVATE).getString("lang", "")
        val lang = com.vibetoon.domain.settings.AppLanguage.effective(saved, java.util.Locale.getDefault().language)
        return runCatching { com.vibetoon.ui.i18n.translate(ctx, lang, ru) }.getOrDefault(ru)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(BackgroundWorkService.CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(BackgroundWorkService.CHANNEL_ID,
            tr(ctx, "Импорт и экспорт"), NotificationManager.IMPORTANCE_LOW))
    }

    /**
     * Спринт 42: уведомление с именем приложения, понятным заголовком и ходом работы:
     * «VibeToon: экспорт 42 %» / «Кадр 120 из 288 · можно свернуть приложение», в развёрнутом виде - подсказка
     * «Нажмите, чтобы вернуться в VibeToon», в шапке - время работы. RU/EN - как язык интерфейса.
     */
    internal fun notification(ctx: Context): Notification {
        ensureChannel(ctx)
        val job = active.values.lastOrNull()
        val kind = com.vibetoon.domain.export.WorkNotice.kind(job?.title ?: "")
        val pct = job?.pct
        val title = tr(ctx, com.vibetoon.domain.export.WorkNotice.title(kind, pct))
        val status = tr(ctx, com.vibetoon.domain.export.WorkNotice.status(kind, job?.done ?: 0, job?.total ?: 0))
        val short = status + " · " + tr(ctx, com.vibetoon.domain.export.WorkNotice.HINT)
        val long = short + "\n" + tr(ctx, com.vibetoon.domain.export.WorkNotice.TAP_HINT)
        val more = com.vibetoon.domain.export.WorkNotice.more(active.size)?.let { tr(ctx, it) }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, BackgroundWorkService.CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)
        b.setSmallIcon(if (kind == com.vibetoon.domain.export.WorkNotice.Kind.IMPORT) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(long))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (more != null) b.setSubText(more)   // имя приложения система и так пишет в шапке, а заголовок начинается с него
        if (job != null) b.setWhen(job.startedAt).setShowWhen(true).setUsesChronometer(true)   // сколько уже идёт работа
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        if (open != null) b.setContentIntent(open)
        return b.build()
    }
}
