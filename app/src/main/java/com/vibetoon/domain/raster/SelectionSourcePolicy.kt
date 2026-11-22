package com.vibetoon.domain.raster

/** Явные причины отказа: не ослабляем ограничения, от которых зависит положение отделённой части. */
object SelectionSourcePolicy {
    fun moveSafeProperties(props: com.vibetoon.domain.model.LayerProps): Boolean =
        props.copy(name = null, opacity = 1f, blend = "norm") == com.vibetoon.domain.model.LayerProps()

    /** Для чистого переноса опорная точка не влияет на результат; масштаб и поворот остаются запрещены. */
    fun translationOnly(t: com.vibetoon.domain.render.LayerTransform): Boolean =
        t.dx.isFinite() && t.dy.isFinite() && t.opacity.isFinite() && t.scaleX == 1f && t.scaleY == 1f && t.rotation == 0f

    /** Отделённая часть имеет тот же прямоугольник и центр, поэтому может наследовать статическую трансформацию. */
    fun copySafeStaticTransform(t: com.vibetoon.domain.render.LayerTransform): Boolean =
        t.dx.isFinite() && t.dy.isFinite() && t.scaleX.isFinite() && t.scaleY.isFinite() &&
            t.scaleX > 0f && t.scaleY > 0f && t.rotation.isFinite() && t.opacity.isFinite()

    enum class Reason { UNSUPPORTED_TYPE, GROUP_OR_VISIBILITY, PROPERTIES, LINKS, CLIP, ANIMATION, TRANSFORM, DEFORMER, EFFECTS, CLIPPING_IN_SCENE }
    data class Facts(
        val raster: Boolean, val rootAndVisible: Boolean, val defaultProperties: Boolean,
        val linked: Boolean, val clip: Boolean, val animated: Boolean, val transformed: Boolean,
        val deformed: Boolean, val effects: Boolean, val sceneClipping: Boolean
    )
    fun rejection(f: Facts): Reason? = when {
        !f.raster -> Reason.UNSUPPORTED_TYPE
        !f.rootAndVisible -> Reason.GROUP_OR_VISIBILITY
        !f.defaultProperties -> Reason.PROPERTIES
        f.linked -> Reason.LINKS
        f.clip -> Reason.CLIP
        f.animated -> Reason.ANIMATION
        f.transformed -> Reason.TRANSFORM
        f.deformed -> Reason.DEFORMER
        f.effects -> Reason.EFFECTS
        f.sceneClipping -> Reason.CLIPPING_IN_SCENE
        else -> null
    }
}
