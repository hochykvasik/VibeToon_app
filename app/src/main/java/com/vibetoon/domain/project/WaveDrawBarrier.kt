package com.vibetoon.domain.project

import kotlinx.coroutines.CompletableDeferred

/** Завершается только после отрисовки актуальных пиков нужной дорожки, а не по одному событию декодера. */
class WaveDrawBarrier(val id: Int) {
    val drawn = CompletableDeferred<Unit>()
    fun acknowledge(id: Int, drawnPeaks: FloatArray, currentPeaks: FloatArray?) {
        if (this.id == id && drawnPeaks === currentPeaks && drawnPeaks.isNotEmpty()) drawn.complete(Unit)
    }
}
