package com.vibetoon.domain.project

import com.vibetoon.domain.paint.ShapeKind

/** Данные учебного проекта, независимые от UI и графического контекста. */
object StarterProject {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    data class Shape(val kind: ShapeKind, val color: Int, val dx: Float)
    val shapes = listOf(
        Shape(ShapeKind.CIRCLE, 0xFFEF5350.toInt(), -591.36f),
        Shape(ShapeKind.STAR, 0xFFFFCA28.toInt(), 0f),
        Shape(ShapeKind.HEART, 0xFF42A5F5.toInt(), 591.36f)
    )

    fun data(id: String, now: Long): ProjectData {
        val side = HEIGHT * 0.4f
        val media = shapes.mapIndexed { index, shape ->
            val layerId = MEDIA_ID_BASE + index
            MediaItem(layerId, MediaKind.IMAGE, "Фигура: ${shape.kind.title}", "assets/m$layerId.png", 512, 512,
                (WIDTH - side) / 2f + shape.dx, (HEIGHT - side) / 2f, side, side)
        }
        return ProjectData(ProjectMeta(id, "Учебный проект", WIDTH, HEIGHT, fps = 24,
            background = 0xFFFFFFFFL, createdAt = now, updatedAt = now), media = media)
    }
}
