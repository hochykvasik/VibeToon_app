package com.vibetoon.domain.raster

/** Результат синхронной GL-операции; неизвестный код никогда не считается успехом. */
enum class TextureReplaceResult {
    SUCCESS, INVALID_DATA, STALE_LAYER, UPLOADS_PENDING, LIMIT_EXCEEDED, CPU_ALLOCATION_FAILED, GL_FAILED, UNKNOWN_FAILURE;

    companion object {
        fun fromCode(code: Int): TextureReplaceResult = when (code) {
            0 -> SUCCESS
            -1 -> INVALID_DATA
            -2 -> STALE_LAYER
            -3 -> UPLOADS_PENDING
            -4 -> LIMIT_EXCEEDED
            -5 -> CPU_ALLOCATION_FAILED
            -6 -> GL_FAILED
            else -> UNKNOWN_FAILURE
        }
    }
}
