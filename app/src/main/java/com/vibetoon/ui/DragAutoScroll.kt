package com.vibetoon.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import com.vibetoon.domain.input.EdgeAutoScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Пока идёт перетаскивание: палец у края окна списка ([posInViewport]) - список прокручивается,
 * [onMoved] получает реально прокрученный путь (его прибавляют к сдвигу перетаскиваемой строки,
 * чтобы она оставалась под пальцем). Отменяется вместе с жестом.
 */
fun CoroutineScope.dragEdgeAutoScroll(
    scroll: ScrollState, viewportPx: () -> Int, edgePx: Float, maxStepPx: Float,
    posInViewport: () -> Float, onMoved: (Float) -> Unit
): Job = launch {
    while (true) {
        delay(16)
        val v = EdgeAutoScroll.velocity(posInViewport(), viewportPx(), edgePx, maxStepPx, scroll.value > 0, scroll.value < scroll.maxValue)
        if (v != 0f) {
            val used = scroll.scrollBy(v)
            if (used != 0f) onMoved(used)
        }
    }
}
