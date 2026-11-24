package com.vibetoon.data

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.vibetoon.domain.perf.BudgetLru
import com.vibetoon.domain.perf.CellCacheKey
import com.vibetoon.domain.perf.CellCachePolicy
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Спринт 31: кэш и предзагрузка декодированных рисунков покадровых слоёв.
 *
 * - Пул декодирования из [CellCachePolicy.decodeThreads] потоков (разные слои декодируются параллельно,
 *   импорт и PSD остаются на своём однопоточном диспетчере).
 * - LRU по байтам ([CellCachePolicy.budgetBytes]); ключ - путь + время изменения + размер файла, поэтому
 *   после сохранения кисти старая картинка не показывается. Вытесненные Bitmap не освобождаются вручную
 *   (их может в этот момент копировать JNI) - память возвращает сборщик мусора.
 * - Предзагрузка следующих рисунков по направлению воспроизведения - на отдельном низкоприоритетном потоке,
 *   чтобы не задерживать декод рисунка, нужного прямо сейчас. На устройствах с малой памятью выключена.
 */
class CellFrameCache(context: Context, private val importer: MediaImporter) {
    private val lowRam: Boolean
    private val baseBudget: Long
    val threads: Int

    init {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        lowRam = am?.isLowRamDevice == true
        baseBudget = CellCachePolicy.budgetBytes(am?.memoryClass ?: 0, lowRam)
        threads = CellCachePolicy.decodeThreads(Runtime.getRuntime().availableProcessors(), lowRam)
    }

    private val counter = AtomicInteger()
    private val executor = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "vt-cells-${counter.incrementAndGet()}").also { it.isDaemon = true }
    }
    /** Диспетчер декодирования рисунков покадровых слоёв и кальки. */
    val dispatcher = executor.asCoroutineDispatcher()
    private val prefetchExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vt-cells-prefetch").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 2 }
    }
    private val lru = BudgetLru<CellCacheKey, Bitmap>(baseBudget, { it.allocationByteCount.toLong() })
    private val prefetching = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var generation = 0

    /** Декодированный рисунок; [owned] - не в кэше, вызывающий освобождает его сам (recycle) после загрузки. */
    class Frame(val bitmap: Bitmap, val owned: Boolean)

    val budgetBytes: Long get() = lru.budgetBytes
    val prefetchEnabled: Boolean get() = !lowRam && lru.budgetBytes > 0

    private fun keyOf(file: File): CellCacheKey? =
        if (!file.isFile) null else CellCacheKey(file.absolutePath, file.lastModified(), file.length())

    /** Блокирующий вызов (на [dispatcher]): рисунок из кэша или декод с записью в кэш. */
    fun load(file: File): Frame {
        val key = keyOf(file)
        if (key != null) lru.get(key)?.let { if (!it.isRecycled) return Frame(it, false) }
        val bmp = importer.decodeImage(file).bitmap
        return if (remember(file, key, bmp)) Frame(bmp, false) else Frame(bmp, true)
    }

    /** Кладёт в кэш, только если файл не изменился за время декодирования (иначе картинка могла быть неполной). */
    private fun remember(file: File, key: CellCacheKey?, bmp: Bitmap): Boolean {
        if (key == null || keyOf(file) != key) return false
        if (!lru.put(key, bmp)) return false
        lru.removeIf { it.path == key.path && it != key }   // прежние версии файла больше не нужны
        return true
    }

    /** Файл перезаписан (сохранение кисти): все его версии выбрасываются из кэша. */
    fun invalidate(file: File) {
        val path = file.absolutePath
        lru.removeIf { it.path == path }
    }

    /** Есть ли актуальная версия файла в кэше. */
    fun has(file: File): Boolean = keyOf(file)?.let { lru.contains(it) } == true

    /**
     * Декодировать заранее [files] (не больше, чем помещается в бюджет). Уже закэшированные и уже стоящие
     * в очереди пропускаются; при смене проекта ([clear]) недоделанные задачи ничего не кладут.
     */
    fun prefetch(files: List<File>) {
        if (!prefetchEnabled) return
        val gen = generation
        for (f in files) {
            val path = f.absolutePath
            if (prefetching.size >= MAX_QUEUED) break
            if (has(f) || !prefetching.add(path)) continue
            try {
                prefetchExecutor.execute {
                    try {
                        if (gen != generation) return@execute
                        val key = keyOf(f) ?: return@execute
                        if (lru.contains(key)) return@execute
                        val bmp = importer.decodeImage(f).bitmap
                        if (gen != generation || !remember(f, key, bmp)) bmp.recycle()   // никому не отдан - можно освободить
                    } catch (t: Throwable) {
                        Log.w("VT/Frames", "prefetch ${f.name} failed: ${t.message}")
                    } finally {
                        prefetching.remove(path)
                    }
                }
            } catch (t: java.util.concurrent.RejectedExecutionException) {
                prefetching.remove(path)
            }
        }
    }

    /** Глубина предзагрузки на слой для рисунков размера [width]x[height]. */
    fun prefetchDepth(width: Int, height: Int, layers: Int): Int =
        if (!prefetchEnabled) 0 else CellCachePolicy.prefetchDepth(CellCachePolicy.bitmapBytes(width, height), lru.budgetBytes, layers)

    /** onTrimMemory: бюджет уменьшается или кэш очищается; при возврате на передний план бюджет восстанавливается. */
    fun trim(level: Int) {
        lru.resize(CellCachePolicy.trimmedBudget(baseBudget, level))
    }

    fun restoreBudget() { if (lru.budgetBytes != baseBudget) lru.resize(baseBudget) }

    /** Смена/закрытие проекта. */
    fun clear() {
        generation++
        lru.clear()
        restoreBudget()
    }

    /** Доля показов рисунков из кэша, % (для панели метрик). */
    fun hitPercent(): Int {
        val h = lru.hits(); val total = h + lru.misses()
        return if (total == 0L) 0 else (h * 100 / total).toInt()
    }

    fun stats(): String = "cells ${lru.count()} (${lru.sizeBytes / (1024 * 1024)}/${lru.budgetBytes / (1024 * 1024)} MB), hit ${lru.hits()} miss ${lru.misses()}, threads $threads"

    fun shutdown() {
        generation++
        prefetchExecutor.shutdownNow()
        executor.shutdown()
        lru.clear()
    }

    private companion object { const val MAX_QUEUED = 8 }
}
