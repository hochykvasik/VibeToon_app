package com.vibetoon.nativebridge

import androidx.annotation.Keep

/**
 * DTO слоя из C++. ВАЖНО: порядок и типы полей = JNI-сигнатура конструктора в PsdBridge.cpp
 * "(ILjava/lang/String;IIIIFZIILjava/lang/String;Z)V". Значений по умолчанию нет.
 */
@Keep
data class LayerInfo(
    val id: Int,            // индекс записи в файле (0 = самый нижний)
    val name: String,
    val x: Int,             // left
    val y: Int,             // top
    val width: Int,
    val height: Int,
    val opacity: Float,     // layer opacity 0..1
    val isVisible: Boolean,
    val kindCode: Int,      // 0 LAYER, 1 GROUP_OPEN, 2 GROUP_CLOSED, 3 GROUP_END
    val parentId: Int,      // id группы-родителя или -1
    val blendMode: String,  // 4 символа: "norm", "mul ", "pass"...
    val isClipped: Boolean
) {
    val kind: LayerKind get() = LayerKind.fromCode(kindCode)
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}
