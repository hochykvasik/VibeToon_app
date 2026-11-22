package com.vibetoon.domain.project

/** Готовность импортируемого аудио относится к PCM, а не только к метаданным или пикам волны. */
sealed interface AudioReadiness {
    data object Idle : AudioReadiness
    data class Preparing(val name: String) : AudioReadiness
    data class Ready(val name: String) : AudioReadiness
    data class Failed(val name: String) : AudioReadiness
}
