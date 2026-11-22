package com.vibetoon.domain.model

import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind

/**
 * Дерево узлов сцены: группы и слои. Строится из плоского списка записей PSD (LayerInfo - только
 * DTO из файла). Порядок детей: снизу вверх, как в PSD. Идентификатор узла = индекс записи в файле.
 */
sealed interface Node {
    val id: Int
    val name: String
    val visible: Boolean
    val opacity: Float
    val blend: String
}

data class LeafNode(
    override val id: Int,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Float,
    override val blend: String,
    val clipped: Boolean,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) : Node {
    val isEmpty get() = w <= 0 || h <= 0
}

data class GroupNode(
    override val id: Int,
    override val name: String,
    override val visible: Boolean,
    override val opacity: Float,
    override val blend: String,
    val children: List<Node>,
    /** Точка, вокруг которой группа вращается и масштабируется (центр рамки детей, один раз). */
    val pivotX: Float,
    val pivotY: Float
) : Node

object SceneTree {
    fun build(layers: List<LayerInfo>): List<Node> {
        val byParent = layers.groupBy { it.parentId }
        fun make(parent: Int): List<Node> = byParent[parent].orEmpty().mapNotNull { l ->
            when (l.kind) {
                LayerKind.GROUP_END -> null          // divider в дереве не нужен
                LayerKind.LAYER -> LeafNode(
                    l.id, l.name, l.isVisible, l.opacity, l.blendMode, l.isClipped,
                    l.x, l.y, l.width, l.height
                )
                LayerKind.GROUP_OPEN, LayerKind.GROUP_CLOSED -> {
                    val kids = make(l.id)
                    val (px, py) = pivotOf(kids)
                    GroupNode(l.id, l.name, l.isVisible, l.opacity, l.blendMode, kids, px, py)
                }
            }
        }
        return make(-1)
    }

    /** Центр объединённой рамки всех непустых слоёв внутри (в исходных координатах PSD). */
    private fun pivotOf(nodes: List<Node>): Pair<Float, Float> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun visit(n: Node) {
            when (n) {
                is LeafNode -> if (!n.isEmpty) {
                    minX = minOf(minX, n.x.toFloat()); minY = minOf(minY, n.y.toFloat())
                    maxX = maxOf(maxX, (n.x + n.w).toFloat()); maxY = maxOf(maxY, (n.y + n.h).toFloat())
                }
                is GroupNode -> n.children.forEach(::visit)
            }
        }
        nodes.forEach(::visit)
        return if (minX > maxX) 0f to 0f else (minX + maxX) / 2f to (minY + maxY) / 2f
    }
}
