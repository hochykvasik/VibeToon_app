package com.vibetoon.domain.project

/** Непосредственные привязки реально существующих слоёв; порядок задаётся панелью, а не картой links. */
data class BoundLayer(val id: Int, val name: String, val isGroup: Boolean = false)

object BoneBindings {
    fun layers(boneId: Int, links: Map<Int, Int>, ordered: List<BoundLayer>): List<BoundLayer> =
        ordered.filter { links[it.id] == boneId }.distinctBy { it.id }
}

/** Старый формат проекта не хранит происхождение имени; переводим только стандартный шаблон. */
object DefaultBoneName {
    private val pattern = Regex("^Кость [1-9][0-9]*$")
    fun isDefault(name: String): Boolean = pattern.matches(name)
}

/** Только известные автоматически создаваемые подписи; остальные имена — пользовательские данные. */
object DefaultLayerName {
    private val drawing = Regex("^Рисунок [1-9][0-9]*$")
    private val shapes = setOf("Фигура: Круг", "Фигура: Сердце", "Фигура: Звезда", "Фигура: Квадрат", "Фигура: Треугольник", "Фигура: Стрелка", "Фигура: Диалог")
    fun isDefault(name: String): Boolean = name in shapes || drawing.matches(name)
}
