package com.vibetoon.nativebridge

enum class LayerKind(val code: Int) {
    LAYER(0), GROUP_OPEN(1), GROUP_CLOSED(2), GROUP_END(3);

    companion object {
        /** Неизвестный код трактуем как обычный слой. */
        fun fromCode(code: Int): LayerKind = entries.firstOrNull { it.code == code } ?: LAYER
    }
}
