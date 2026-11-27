package com.vibetoon.domain.anim

/**
 * Ключи костей на таймлайне (пакет 7). Поза кости хранится как трансформация под id кости (сдвиг сустава,
 * угол, длина через масштаб), поэтому ключ кости - обычный ключ дорожки с id кости. Раньше у костей не было
 * строк на таймлайне и ключи были невидимы; теперь каждая кость с ключами (а в режиме «Кости» - каждая кость)
 * получает свою строку с ромбами ◆. Чистые функции - проверяются unit-тестом.
 */
object BoneKeys {
    /** Кадры ключей кости по возрастанию. */
    fun frames(boneId: Int, tracks: Map<Int, AnimationTrack>): List<Int> =
        tracks[boneId]?.keys?.map { it.frame }?.distinct()?.sorted().orEmpty()

    /** Строки костей на таймлайне: в режиме «Кости» - все кости, иначе только кости с ключами (порядок создания). */
    fun rows(boneIds: List<Int>, tracks: Map<Int, AnimationTrack>, boneMode: Boolean): List<Int> =
        if (boneMode) boneIds else boneIds.filter { tracks[it]?.isEmpty() == false }

    /** Предыдущий ключ строго левее [frame] или null. */
    fun prev(frames: List<Int>, frame: Int): Int? = frames.filter { it < frame }.maxOrNull()

    /** Следующий ключ строго правее [frame] или null. */
    fun next(frames: List<Int>, frame: Int): Int? = frames.filter { it > frame }.minOrNull()

    /** Ромб под пальцем: ближайший ключ в пределах [grabPx] от точки [x] (пиксели содержимого таймлайна). */
    fun hit(frames: Collection<Int>, x: Float, frameWPx: Float, grabPx: Float): Int? =
        frames.minByOrNull { kotlin.math.abs(it * frameWPx - x) }?.takeIf { kotlin.math.abs(it * frameWPx - x) <= grabPx }

    /**
     * Кадр, с которым работают кнопки свойств кости: выделенный на таймлайне ключ этой кости, иначе ключ
     * на текущем кадре, иначе null (кнопки удаления/кривой неактивны).
     */
    fun activeFrame(boneId: Int, frames: List<Int>, keySel: Set<Pair<Int, Int>>, current: Int): Int? {
        val picked = keySel.filter { it.first == boneId && it.second in frames }.map { it.second }
        if (picked.size == 1) return picked[0]
        return current.takeIf { it in frames }
    }

    /** Id строки ключей по индексу: строки слоёв и групп (null - строка без ключей), затем строки костей. */
    fun rowIdAt(index: Int, layerRowIds: List<Int?>, boneRowIds: List<Int>): Int? = when {
        index < 0 -> null
        index < layerRowIds.size -> layerRowIds[index]
        else -> boneRowIds.getOrNull(index - layerRowIds.size)
    }
}
