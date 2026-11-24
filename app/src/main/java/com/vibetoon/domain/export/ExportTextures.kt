package com.vibetoon.domain.export

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Пакет 29: текстуры кадра экспорта (покадровые рисунки, кадры видео, текст с эффектами, запасные картинки символов).
 *
 * Сцены MP4 собираются заранее в фоне (пакет 27: пока GL рисует кадр N, фон строит N + 1). Раньше сборка сразу ставила
 * новые картинки в общую очередь загрузки, и кадр N мог нарисоваться уже с картинкой кадра N + 1 (рисунок или рот «на
 * кадр раньше»). Теперь сборка только ГОТОВИТ загрузки (декодирует в фоне), а применяет их тот поток, который рисует
 * этот кадр, прямо перед отрисовкой ([commit]). Загрузки кадров применяются строго по порядку.
 *
 * Повторы не декодируются: если у слоя тот же ключ (файл кадра, момент видео...), что и в прошлом собранном кадре,
 * картинка не готовится заранее. Но если тот кадр так и не применили (перезапуск экспорта с начала), [commit] это
 * заметит (ключ применённой картинки другой) и догрузит её сам ([Item.load]) - на экране всегда картинка своего кадра.
 *
 * Потоки: [plan] вызывается только из потока сборки (по одному за раз), [commit] - только из потока отрисовки.
 * Без Android: [B] - картинка (Bitmap), [upload] ставит её в очередь текстуры слоя (true - принята).
 */
class ExportTextures<B : Any>(
    private val upload: (layerId: Int, data: B, ticket: Long) -> Boolean,
    private val release: (B) -> Unit
) {
    class Item<B : Any> internal constructor(
        val layerId: Int,
        val key: Any,
        val ticket: Long,
        @Volatile internal var data: B?,
        internal val load: () -> B?
    ) {
        /** Картинка подготовлена заранее (для тестов и метрик). */
        val prepared: Boolean get() = data != null
    }

    /** Загрузки одного кадра; применяются один раз (повтор прерванного кадра ничего не делает). */
    class Batch<B : Any> internal constructor(val items: List<Item<B>>) {
        internal val applied = AtomicBoolean(false)
        val isApplied: Boolean get() = applied.get()
    }

    private val planned = HashMap<Int, Any>()     // поток сборки: ключ последней подготовленной картинки слоя
    private val committed = HashMap<Int, Any>()   // поток отрисовки: ключ картинки, которая сейчас в текстуре слоя

    /** Сколько картинок загружено и сколько догружено при применении (не подготовлены заранее). */
    var uploads = 0
        private set
    var lateLoads = 0
        private set

    /**
     * Загрузка картинки слоя [layerId] с ключом [key] для собираемого кадра. [ticket] - билет загрузки текстуры
     * (берётся до декодирования). [load] декодирует картинку (null - не вышло, слой остаётся с прежней).
     */
    fun plan(layerId: Int, key: Any, ticket: Long, load: () -> B?): Item<B> {
        val same = planned[layerId] == key
        planned[layerId] = key
        val data = if (same) null else runCatching { load() }.getOrNull()
        return Item(layerId, key, ticket, data, load)
    }

    fun batch(items: List<Item<B>>): Batch<B> = Batch(items)

    /** Применяет загрузки кадра (поток отрисовки, перед отрисовкой). Возвращает число поставленных картинок. */
    fun commit(batch: Batch<B>): Int {
        if (!batch.applied.compareAndSet(false, true)) return 0
        var n = 0
        for (it in batch.items) {
            val ready = it.data
            it.data = null
            if (committed[it.layerId] == it.key) { ready?.let(release); continue }
            val b = ready ?: runCatching { it.load() }.getOrNull()?.also { lateLoads++ } ?: continue
            try {
                if (upload(it.layerId, b, it.ticket)) { committed[it.layerId] = it.key; n++; uploads++ }
            } finally {
                release(b)
            }
        }
        return n
    }

    /** Кадр не понадобился (отмена): освободить подготовленные картинки без загрузки. */
    fun discard(batch: Batch<B>) {
        if (!batch.applied.compareAndSet(false, true)) return
        for (it in batch.items) { it.data?.let(release); it.data = null }
    }

    /** Ключ картинки, которая сейчас применена к слою (для тестов). */
    fun committedKey(layerId: Int): Any? = committed[layerId]
}

/** Пакет 29: кадр источника видео-слоя в экспорте - тот же расчёт, что при воспроизведении (см. VideoTime). */
object ExportVideo {
    /** Ключ загрузки: файл и момент источника (одинаковый момент на соседних кадрах не декодируется повторно). */
    data class Key(val file: String, val timeMs: Long)

    /** Размер декодирования: длинная сторона не больше длинной стороны кадра экспорта и не больше источника. */
    fun decodeSize(srcW: Int, srcH: Int, exportW: Int, exportH: Int): Pair<Int, Int> {
        val sw = srcW.coerceAtLeast(1); val sh = srcH.coerceAtLeast(1)
        val maxSide = maxOf(exportW, exportH).coerceAtLeast(16)
        val k = minOf(1f, maxSide.toFloat() / maxOf(sw, sh))
        return maxOf(2, (sw * k).toInt()) to maxOf(2, (sh * k).toInt())
    }
}
