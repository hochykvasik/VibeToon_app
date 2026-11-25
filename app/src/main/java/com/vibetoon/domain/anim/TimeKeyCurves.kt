package com.vibetoon.domain.anim

import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimeKey

/**
 * Спринт 46: кривые ключей времени символа - те же пресеты и тот же редактор «График…», что у обычных ключей.
 * Переход хранится у левого ключа ([TimeKey.ease] / [TimeKey.curve]) и действует до следующего ключа: внутренний кадр
 * символа идёт от значения к значению по кривой (Hold - резкая смена формы на кадре следующего ключа).
 * Чистые функции - Sprint46Test.
 */
object TimeKeyCurves {
    /** Состояние для панели свойств: [easing] - пресет (null - своя кривая), [mixed] - у ключей разные переходы. */
    data class Info(val keys: Int, val easing: EasingType?, val custom: Boolean, val mixed: Boolean)

    fun info(keys: List<TimeKey>): Info? {
        if (keys.isEmpty()) return null
        val ref = keys.first()
        val mixed = keys.any { it.ease != ref.ease || it.curve != ref.curve }
        val custom = ref.ease == TimeEase.CUSTOM || ref.curve != null
        return Info(keys.size, if (custom) null else TimeEase.toEasing(ref.ease), custom, mixed)
    }

    /** Заголовок редактора кривой для ключей времени. */
    fun title(keys: Int): String = if (keys == 1) "Кривая ключа времени" else "Кривая ключей времени ($keys)"

    /** Пояснение под заголовком редактора. */
    const val NOTE = "Кадры символа между ключом и следующим ключом идут по этой кривой"

    /**
     * Внутренний кадр символа на каждом кадре сегмента [a] -> [b] (для проверки и подписи): по нему видно, что разные
     * переходы дают разные кадры. Кадры сцены от a.frame до b.frame включительно.
     */
    fun samples(a: TimeKey, b: TimeKey): IntArray {
        if (b.frame <= a.frame) return intArrayOf(a.value)
        return IntArray(b.frame - a.frame + 1) { i -> com.vibetoon.domain.project.SymbolMath.timeAt(listOf(a, b), a.frame + i) ?: a.value }
    }
}
