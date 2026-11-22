package com.vibetoon.domain.render

import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind

/** Общие для редактора и экспорта свойства сцены. Создаёт независимый снимок на время экспорта. */
data class SceneOptions(val nullIds: Set<Int>, val depths: Map<Int, Float>) {
    companion object {
        fun capture(media: List<MediaItem>, properties: Map<Int, LayerProps>): SceneOptions = SceneOptions(
            media.filter { it.kind == MediaKind.NULLOBJ }.mapTo(HashSet()) { it.id },
            properties.filterValues { it.depth != 0f }.mapValues { it.value.depth }
        )
    }
}
