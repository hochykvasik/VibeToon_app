package com.vibetoon

import com.vibetoon.domain.project.BoneBindings
import com.vibetoon.domain.project.BoundLayer
import com.vibetoon.domain.project.DefaultBoneName
import com.vibetoon.domain.project.DefaultLayerName
import org.junit.Assert.*
import org.junit.Test

class BoneBindingsTest {
    @Test fun showsEveryExistingDirectLayerInDisplayOrderRegardlessOfCollapsedGroups() {
        val layers = listOf(BoundLayer(4, "Nested"), BoundLayer(3, "Group", true), BoundLayer(2, "Sound"), BoundLayer(1, "Background"))
        val links = mapOf(4 to 200001, 3 to 200001, 2 to 200001, 1 to 200002, 200003 to 200001, 999 to 200001)
        val result = BoneBindings.layers(200001, links, layers)
        assertEquals(listOf(4, 3, 2), result.map { it.id })
        assertEquals(result.size, result.map { it.id }.toSet().size)
        assertEquals(emptyList<BoundLayer>(), BoneBindings.layers(200099, links, layers))
    }
    @Test fun bindUnbindUndoRedoSnapshotsUpdateCountAndNames() {
        val ordered = listOf(BoundLayer(1, "One"), BoundLayer(2, "Two"))
        val original = mapOf(1 to 200001)
        val autoBind = original + (2 to 200001)
        val unbound = autoBind - 1
        fun ids(links: Map<Int, Int>) = BoneBindings.layers(200001, links, ordered).map { it.id }
        assertEquals(listOf(1), ids(original))
        assertEquals(listOf(1, 2), ids(autoBind))
        assertEquals(listOf(2), ids(unbound))
        assertEquals(listOf(1, 2), ids(autoBind)) // Undo snapshot
        assertEquals(listOf(2), ids(unbound)) // Redo snapshot
        assertEquals("Renamed", BoneBindings.layers(200001, autoBind, listOf(BoundLayer(2, "Renamed"))).single().name)
    }
    @Test fun onlyCanonicalDefaultBoneNamesAreEligibleForTranslation() {
        assertTrue(DefaultBoneName.isDefault("Кость 1"))
        assertTrue(DefaultBoneName.isDefault("Кость 42"))
        for (name in listOf("Моя кость", "Кость 01", "Кость arm", "Кость 1 левая", "Bone 2")) assertFalse(DefaultBoneName.isDefault(name))
    }
    @Test fun onlyRecognizedGeneratedLayerNamesAreTranslated() {
        assertTrue(DefaultLayerName.isDefault("Фигура: Круг"))
        assertTrue(DefaultLayerName.isDefault("Рисунок 3"))
        assertFalse(DefaultLayerName.isDefault("Фигура: Моя"))
        assertFalse(DefaultLayerName.isDefault("Отпуск.jpg"))
    }
    @Test fun inspectorUsesReactiveLinksAndExistingThumbnailChecker() {
        val ui = java.io.File("src/main/java/com/vibetoon/ui/shell/EditorShell.kt").readText()
        assertTrue(ui.contains("val links by vm.links.collectAsStateWithLifecycle()"))
        assertTrue(ui.contains("vm.boundLayers(bone.id, links)"))
        assertTrue(ui.contains("Привязано слоёв: ${'$'}{bound.size}"))
        assertTrue(ui.contains(".thumbnailChecker(checker)"))
        assertTrue(ui.contains("vm.requestThumb(layer.id)"))
    }
}
