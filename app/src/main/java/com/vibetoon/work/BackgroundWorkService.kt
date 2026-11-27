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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.vibetoon.domain.export.BackgroundRender
import com.vibetoon.domain.export.ExportOutcome
import com.vibetoon.domain.export.ExportStall
import com.vibetoon.domain.export.WorkNotice

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
        // спринт 56: «Отменить экспорт» в уведомлении - отмена без возврата в приложение
        if (intent?.action == ACTION_CANCEL) {
            BackgroundWork.requestCancel()
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
        const val ACTION_CANCEL = "com.vibetoon.work.CANCEL"
        /** Спринт 56: уведомление-итог (готово / отменено / прервано / ошибка) - отдельно от хода работы. */
        const val RESULT_ID = 4108
        const val RESULT_CHANNEL_ID = "vt_work_results"
        /** Нажатие на уведомление: вернуть то же окно приложения (MainActivity singleTask) и, если нужно, проект. */
        const val ACTION_OPEN = "com.vibetoon.work.OPEN"
        const val EXTRA_PROJECT = "com.vibetoon.work.PROJECT"
    }
}

/**
 * Учёт фоновых работ (импорт, экспорт) и уведомления службы. Вызовы - с главного потока.
 *
 * Спринт 56: у экспорта один источник правды. Каждая работа кончается через [finish] с итогом
 * ([ExportOutcome.Kind]): прогресс снимается, служба останавливается, итог появляется в шторке (если приложение не на
 * экране). Стоящий в фоне экспорт подписан «ждёт», а не тикает таймером. Пока работа идёт, процессор не засыпает
 * (частичная блокировка сна с пределом), нажатие на уведомление возвращает то же окно приложения.
 */
object BackgroundWork {
    /** Спринт 42/56: состояние работы для уведомления. */
    private data class Job(
        val title: String, val pct: Int?, val done: Int, val total: Int, val startedAt: Long,
        val projectId: String? = null, val lastProgressAt: Long = startedAt, val parked: Boolean = false, val stalled: Boolean = false
    ) {
        val kind: WorkNotice.Kind get() = WorkNotice.kind(title)
    }
    private val active = LinkedHashMap<Long, Job>()
    private var nextId = 1L
    @Volatile var running: Boolean = false
        private set
    /** MainActivity: запрос разрешения на уведомления (Android 13+), один раз за установку. */
    var requestNotificationPermission: (() -> Unit)? = null
    /** Спринт 56: «Отменить» в уведомлении - отмену выполняет экран редактора, пока он жив. */
    var cancelHandler: (() -> Unit)? = null
    /** Спринт 56: приложение на экране (MainActivity между onStart и onStop). */
    @Volatile var appVisible: Boolean = false
        private set
    private var appCtx: Context? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAt = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = appCtx ?: return
            if (active.isEmpty()) return
            restall(ctx)
            main.postDelayed(this, ExportStall.CHECK_MS)
        }
    }

    private fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("vt_app", Context.MODE_PRIVATE).getBoolean(BackgroundRender.PREF, true)

    /** Идёт ли сейчас экспорт под службой (для проверки отметки прошлого запуска). */
    fun exportActive(): Boolean = active.values.any { it.kind == WorkNotice.Kind.EXPORT }

    /** Начало работы [title]; возвращает id для [progress]/[end] (0 - фоновая работа выключена в настройках). */
    fun begin(ctx: Context, title: String, projectId: String? = null): Long {
        if (!enabled(ctx)) return 0L
        val app = ctx.applicationContext
        appCtx = app
        val id = nextId++
        active[id] = Job(title, null, 0, 0, System.currentTimeMillis(), projectId)
        // новый экспорт - прежний итог в шторке уже не нужен
        if (WorkNotice.kind(title) == WorkNotice.Kind.EXPORT) clearResult(app)
        askPermissionOnce(ctx)
        start(app)
        holdWake(app)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, ExportStall.CHECK_MS)
        return id
    }

    fun progress(ctx: Context, id: Long, done: Int, total: Int) {
        val cur = active[id] ?: return
        val now = System.currentTimeMillis()
        val pct = BackgroundRender.percent(done, total)
        val moved = done != cur.done || total != cur.total
        var next = if (moved) cur.copy(done = done, total = total, lastProgressAt = now, parked = false) else cur
        next = next.copy(stalled = stallOf(next, now))
        val visibleChange = pct != cur.pct || total != cur.total || next.stalled != cur.stalled
        active[id] = next.copy(pct = pct)
        if (moved) holdWake(ctx.applicationContext)
        // шторку обновляем при смене процента или состояния (не на каждом кадре): система ограничивает частоту
        if (visibleChange) refresh(ctx.applicationContext)
    }

    /** Спринт 56: цепочка кадров экспорта запаркована (true) или снова идёт (false). */
    fun setParked(ctx: Context, id: Long, parked: Boolean) {
        val cur = active[id] ?: return
        if (cur.parked == parked) return
        val now = System.currentTimeMillis()
        val next = cur.copy(parked = parked, lastProgressAt = if (parked) cur.lastProgressAt else now)
        active[id] = next.copy(stalled = stallOf(next, now))
        if (active[id]!!.stalled != cur.stalled) refresh(ctx.applicationContext)
    }

    /** Спринт 56: MainActivity onStart/onStop. На экране - итог в шторке больше не нужен, «ждёт» снимается. */
    fun setAppVisible(ctx: Context, visible: Boolean) {
        appVisible = visible
        val app = ctx.applicationContext
        appCtx = app
        if (visible) clearResult(app)
        if (active.isNotEmpty()) restall(app)
    }

    fun end(ctx: Context, id: Long) = finish(ctx, id, null, null)

    /**
     * Спринт 56: конец работы с итогом. Прогресс снимается всегда (даже если [id] = 0 - фон выключен), итог экспорта
     * идёт в шторку, когда приложение не на экране: «завершён» (имя файла), «отменён», «прерван» или «не удался».
     */
    fun finish(ctx: Context, id: Long, outcome: ExportOutcome.Kind?, detail: String?, projectId: String? = null) {
        val app = ctx.applicationContext
        val job = if (id != 0L) active.remove(id) else null
        if (job != null) {
            if (active.isEmpty()) {
                running = false
                releaseWake()
                main.removeCallbacks(watchdog)
                runCatching { app.startService(Intent(app, BackgroundWorkService::class.java).setAction(BackgroundWorkService.ACTION_STOP)) }
                    .onFailure { runCatching { app.stopService(Intent(app, BackgroundWorkService::class.java)) } }
                // запасной путь: даже если служба уже остановлена системой, уведомление хода работы не остаётся
                runCatching { nm(app).cancel(BackgroundWorkService.NOTIFICATION_ID) }
            } else if (running) refresh(app)
        }
        if (outcome != null && ExportOutcome.postToShade(appVisible)) postResult(app, outcome, detail, projectId ?: job?.projectId)
    }

    /** Служба не запустилась или остановлена системой: GL-работа в фоне больше не разрешена. */
    fun serviceStopped() { running = false }

    /** Спринт 56: «Отменить» из уведомления (служба, главный поток). */
    fun requestCancel() { cancelHandler?.invoke() }

    /** Спринт 56: убрать итог из шторки (пользователь вернулся в приложение или начал новый экспорт). */
    fun clearResult(ctx: Context) { runCatching { nm(ctx).cancel(BackgroundWorkService.RESULT_ID) } }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun refresh(app: Context) {
        if (running) runCatching { nm(app).notify(BackgroundWorkService.NOTIFICATION_ID, notification(app)) }
    }

    private fun stallOf(j: Job, now: Long): Boolean =
        j.kind == WorkNotice.Kind.EXPORT && ExportStall.stalled(appVisible, j.parked, j.lastProgressAt, now)

    private fun restall(app: Context) {
        val now = System.currentTimeMillis()
        var changed = false
        for ((k, j) in active.entries.toList()) {
            val s = stallOf(j, now)
            if (s != j.stalled) { active[k] = j.copy(stalled = s); changed = true }
        }
        if (changed) refresh(app)
    }

    /** Процессор не засыпает при выключенном экране, пока идёт работа (предел - на случай забытой работы). */
    private fun holdWake(app: Context) {
        val now = System.currentTimeMillis()
        if (wakeLock?.isHeld == true && now - wakeAt < WAKE_RENEW_MS) return
        runCatching {
            val wl = wakeLock ?: (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibeToon:work").also { it.setReferenceCounted(false); wakeLock = it }
            wl.acquire(WAKE_MAX_MS)
            wakeAt = now
        }.onFailure { Log.w("VT/Work", "wake lock unavailable: ${it.message}") }
    }

    private fun releaseWake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    private const val WAKE_MAX_MS = 30 * 60 * 1000L
    private const val WAKE_RENEW_MS = 20 * 60 * 1000L

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
        val nm = nm(ctx)
        if (nm.getNotificationChannel(BackgroundWorkService.CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(BackgroundWorkService.CHANNEL_ID,
                tr(ctx, "Импорт и экспорт"), NotificationManager.IMPORTANCE_LOW))
        // спринт 56: итоги - отдельный канал (обычная важность: итог заметен, ход работы - тихий)
        if (nm.getNotificationChannel(BackgroundWorkService.RESULT_CHANNEL_ID) == null)
            nm.createNotificationChannel(NotificationChannel(BackgroundWorkService.RESULT_CHANNEL_ID,
                tr(ctx, "Итоги экспорта"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    /**
     * Спринт 56: нажатие возвращает ТО ЖЕ окно приложения. Раньше бралось намерение запуска из лаунчера: оно не совпадало
     * с исходным намерением задачи, и система открывала второй экземпляр приложения - с экраном проектов, а экспорт
     * оставался в первом. Теперь явное намерение MainActivity (singleTask) с id проекта.
     */
    private fun openIntent(ctx: Context, projectId: String?, request: Int): PendingIntent? = runCatching {
        val i = Intent(ctx, com.vibetoon.MainActivity::class.java)
            .setAction(BackgroundWorkService.ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (projectId != null) i.putExtra(BackgroundWorkService.EXTRA_PROJECT, projectId)
        PendingIntent.getActivity(ctx, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }.getOrNull()

    private fun builder(ctx: Context, channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx)

    /** Итог экспорта в шторке (смахивается, исчезает по нажатию; отмена - сама через 10 с). */
    private fun postResult(ctx: Context, kind: ExportOutcome.Kind, detail: String?, projectId: String?) {
        runCatching {
            ensureChannel(ctx)
            val title = tr(ctx, ExportOutcome.title(kind))
            val text = tr(ctx, ExportOutcome.text(kind, detail?.let { tr(ctx, it) }))
            val long = text + "\n" + tr(ctx, ExportOutcome.TAP_HINT)
            val icon = when (kind) {
                ExportOutcome.Kind.DONE -> android.R.drawable.stat_sys_upload_done
                ExportOutcome.Kind.CANCELLED -> android.R.drawable.ic_menu_close_clear_cancel
                else -> android.R.drawable.stat_notify_error
            }
            val b = builder(ctx, BackgroundWorkService.RESULT_CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(long))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(if (kind == ExportOutcome.Kind.DONE) Notification.CATEGORY_STATUS else Notification.CATEGORY_ERROR)
            val t = ExportOutcome.timeoutMs(kind)
            if (t > 0 && Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(t)
            openIntent(ctx, projectId, 2)?.let { b.setContentIntent(it) }
            nm(ctx).notify(BackgroundWorkService.RESULT_ID, b.build())
        }.onFailure { Log.w("VT/Work", "result notification failed: ${it.message}") }
    }

    /**
     * Спринт 42: уведомление с именем приложения, понятным заголовком и ходом работы:
     * «VibeToon: экспорт 42 %» / «Кадр 120 из 288 · можно свернуть приложение», в развёрнутом виде - подсказка
     * «Нажмите, чтобы вернуться в VibeToon», в шапке - время работы. RU/EN - как язык интерфейса.
     * Спринт 56: стоящий экспорт - «VibeToon: экспорт ждёт 21 %» без таймера; у экспорта кнопка «Отменить».
     */
    internal fun notification(ctx: Context): Notification {
        ensureChannel(ctx)
        val job = active.values.lastOrNull()
        val kind = WorkNotice.kind(job?.title ?: "")
        val pct = job?.pct
        val stalled = job?.stalled == true
        val title = tr(ctx, if (stalled) ExportStall.title(pct) else WorkNotice.title(kind, pct))
        val status = tr(ctx, WorkNotice.status(kind, job?.done ?: 0, job?.total ?: 0))
        val short = status + " · " + tr(ctx, if (stalled) ExportStall.TEXT else WorkNotice.HINT)
        val long = short + "\n" + tr(ctx, WorkNotice.TAP_HINT)
        val more = WorkNotice.more(active.size)?.let { tr(ctx, it) }
        val b = builder(ctx, BackgroundWorkService.CHANNEL_ID)
        b.setSmallIcon(if (kind == WorkNotice.Kind.IMPORT) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(short)
            .setStyle(Notification.BigTextStyle().bigText(long))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (more != null) b.setSubText(more)   // имя приложения система и так пишет в шапке, а заголовок начинается с него
        // сколько уже идёт работа; у стоящей работы таймер не тикает (иначе кажется, что она идёт)
        if (job != null && !stalled) b.setWhen(job.startedAt).setShowWhen(true).setUsesChronometer(true)
        else b.setShowWhen(false).setUsesChronometer(false)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (pct != null) b.setProgress(100, pct, false) else b.setProgress(0, 0, !stalled)
        openIntent(ctx, job?.projectId, 1)?.let { b.setContentIntent(it) }
        if (kind == WorkNotice.Kind.EXPORT) runCatching {
            val cancel = PendingIntent.getService(ctx, 3,
                Intent(ctx, BackgroundWorkService::class.java).setAction(BackgroundWorkService.ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            b.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, tr(ctx, "Отменить экспорт"), cancel).build())
        }
        return b.build()
    }
}
