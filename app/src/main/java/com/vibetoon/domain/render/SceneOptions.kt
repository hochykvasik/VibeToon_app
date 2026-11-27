package com.vibetoon.domain.render

import com.vibetoon.domain.anim.PathFollow
import com.vibetoon.domain.anim.PathLayerMath
import com.vibetoon.domain.model.LayerMasks
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind

/**
 * Общие для редактора и экспорта свойства сцены. Создаёт независимый снимок на время экспорта.
 * Спринт 38: служебные слои (Transform Layer, путь, кости) не рисуются; маски слоёв и прикрепления к путям - одни и те же
 * на холсте, при Play и во всех экспортах.
 */
data class SceneOptions(
    val nullIds: Set<Int>,
    val depths: Map<Int, Float>,
    val layerMasks: Map<Int, LayerMasks.Mask> = emptyMap(),
    val maskSources: Set<Int> = emptySet(),
    val paths: Map<Int, PathLayerMath.Path> = emptyMap(),
    val attachments: Map<Int, PathFollow.Attach> = emptyMap()
) {
    /** Точки прикреплённых слоёв на путях; [percent] - положение слоя (0..100) на нужном кадре. */
    fun pathPlaces(percent: (Int) -> Float): Map<Int, FloatArray> = PathFollow.places(attachments, paths, percent)

    companion object {
        fun capture(media: List<MediaItem>, properties: Map<Int, LayerProps>): SceneOptions {
            val paths = HashMap<Int, PathLayerMath.Path>()
            for (m in media) if (m.kind == MediaKind.PATH) PathLayerMath.pathOf(m.vectors)?.let { paths[m.id] = it }
            val pathIds = media.filter { it.kind == MediaKind.PATH }.mapTo(HashSet()) { it.id }
            return SceneOptions(
                media.filter { it.isRig }.mapTo(HashSet()) { it.id },
                properties.filterValues { it.depth != 0f }.mapValues { it.value.depth },
                LayerMasks.active(properties),
                LayerMasks.sources(properties),
                paths,
                PathFollow.attachments(properties, pathIds)
            )
        }
    }
}
