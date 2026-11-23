package com.vibetoon

import com.vibetoon.domain.project.LibraryFolders
import com.vibetoon.domain.project.PsdInsertPlan
import com.vibetoon.domain.project.PsdInsertPlan.Role
import com.vibetoon.nativebridge.LayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Пакет 23: вставка другого PSD (режимы PSD Import Mode) и пользовательские папки библиотеки. */
class Package23PsdInsertTest {
    private fun leaf(id: Int, parent: Int = -1, vis: Boolean = true, op: Float = 1f, clip: Boolean = false, blend: String = "norm", w: Int = 10, name: String = "L$id") =
        LayerInfo(id, name, id, id, w, 10, op, vis, 0, parent, blend, clip)
    private fun group(id: Int, parent: Int = -1, vis: Boolean = true, op: Float = 1f, name: String = "G$id") =
        LayerInfo(id, name, 0, 0, 0, 0, op, vis, 1, parent, "pass", false)
    private fun end(id: Int, parent: Int = -1) = LayerInfo(id, "</Layer group>", 0, 0, 0, 0, 1f, true, 3, parent, "norm", false)

    // файл: [0] конец группы, [1] рука, [2] тень (обтравка), [3] группа «Тело», [4] фон пустой, [5] голова (скрыта)
    private val doc = listOf(end(0, 3), leaf(1, 3), leaf(2, 3, clip = true, blend = "mul "), group(3, op = 0.5f), leaf(4, w = 0), leaf(5, vis = false))

    @Test fun layersModeKeepsHierarchyOrderAndDropsEmpty() {
        val t = PsdInsertPlan.tree(doc)
        assertEquals(listOf(1, 2, 3, 5), t.map { it.psdId })   // пустой фон и маркер конца группы отброшены
        assertEquals(3, t[0].parent); assertEquals(-1, t[2].parent)
        assertTrue(t[2].group); assertEquals(0.5f, t[2].opacity, 1e-6f)
        assertTrue(t[1].clipped); assertEquals("mul ", t[1].blend)
        assertFalse(t[3].visible)   // скрытый слой сохраняется с выключенным глазом
        // группа без непустых слоёв не создаётся
        assertEquals(listOf(9), PsdInsertPlan.tree(listOf(group(7), leaf(8, 7, w = 0), leaf(9))).map { it.psdId })
        assertEquals("norm", PsdInsertPlan.layerBlend("pass")); assertEquals("mul ", PsdInsertPlan.layerBlend("mul "))
    }

    @Test fun composeOpsHandleGroupsClippingAndHidden() {
        val ops = PsdInsertPlan.composeOps(doc)
        assertEquals(listOf(1, 2), ops.map { it.psdId })   // скрытая голова и пустой фон не рисуются
        assertEquals(Role.BASE, ops[0].role); assertEquals(0.5f, ops[0].opacity, 1e-6f)   // прозрачность группы - у основы
        assertEquals(Role.CLIPPED, ops[1].role); assertEquals(1f, ops[1].opacity, 1e-6f)
        // скрытая основа прячет обтравленные слои; одиночный слой - PLAIN
        val hiddenBase = PsdInsertPlan.composeOps(listOf(leaf(1, vis = false), leaf(2, clip = true), leaf(3)))
        assertEquals(listOf(3), hiddenBase.map { it.psdId }); assertEquals(Role.PLAIN, hiddenBase[0].role)
        // обтравка к группе не поддерживается: слой после группы рисуется обычным
        val afterGroup = PsdInsertPlan.composeOps(listOf(leaf(1, 2), group(2), leaf(3, clip = true)))
        assertEquals(listOf(Role.PLAIN, Role.PLAIN), afterGroup.map { it.role })
        // скрытая группа скрывает детей
        assertTrue(PsdInsertPlan.composeOps(listOf(leaf(1, 2), group(2, vis = false))).isEmpty())
        // поддерево группы
        assertEquals(listOf(1, 2), PsdInsertPlan.composeOps(doc, 3).map { it.psdId })
    }

    @Test fun framesModeTakesTopLevelNodesBottomUp() {
        val layers = listOf(leaf(0, name = "f1"), leaf(1, clip = true), leaf(2, 4), end(3, 4), group(4, name = "f2"), leaf(5, vis = false), leaf(6, w = 0))
        val f = PsdInsertPlan.frames(layers)
        assertEquals(listOf("f1", "f2"), f.map { it.name })
        assertEquals(listOf(0, 1), f[0].ops.map { it.psdId })   // обтравленный верхний слой - в кадре основы
        assertEquals(Role.BASE, f[0].ops[0].role)
        assertEquals(listOf(2), f[1].ops.map { it.psdId })
        val many = (0 until 320).map { leaf(it) }
        assertEquals(PsdInsertPlan.MAX_FRAMES, PsdInsertPlan.frames(many).size)
        assertEquals(320, PsdInsertPlan.frameCandidates(many))
    }

    @Test fun fitBlendAndNames() {
        val f = PsdInsertPlan.fit(1000, 500, 1920, 1080)
        assertEquals(1.92f, f[2], 1e-4f); assertEquals(0f, f[0], 1e-3f); assertEquals((1080 - 960) / 2f, f[1], 1e-3f)
        assertEquals(1f, PsdInsertPlan.decodeScale(1.92f), 0f); assertEquals(0.5f, PsdInsertPlan.decodeScale(0.5f), 0f)
        assertEquals("MULTIPLY", PsdInsertPlan.blendName("mul ")); assertNull(PsdInsertPlan.blendName("norm")); assertNull(PsdInsertPlan.blendName("pass"))
        assertEquals("ADD", PsdInsertPlan.porterDuffName("PLUS")); assertNull(PsdInsertPlan.porterDuffName("HUE"))
        assertEquals("Персонаж_v2_abcdef.psd", PsdInsertPlan.assetName("Персонаж v2.psd", "abcdef123"))
        assertEquals("psd_123456.psd", PsdInsertPlan.assetName("...psd", "123456"))
        assertEquals("Персонаж_v2", PsdInsertPlan.titleOf("assets/Персонаж_v2_abcdef.psd")); assertEquals("hero", PsdInsertPlan.titleOf("hero.psd"))
        assertTrue(PsdInsertPlan.isProjectPsd("source.psd")); assertFalse(PsdInsertPlan.isProjectPsd("hero_1a2b3c.psd"))
        assertEquals(PsdInsertPlan.Mode.FRAMES, PsdInsertPlan.Mode.of(2)); assertEquals(PsdInsertPlan.Mode.LAYERS, PsdInsertPlan.Mode.of(9))
    }

    @Test fun libraryFoldersEditAndPersist() {
        var lf = LibraryFolders().add("  Фоны  ")!!.add("Герои")!!
        assertNull(lf.add("фоны")); assertNull(lf.add("   "))
        lf = lf.assign("sky.png", "Фоны").assign("hero.png", "Герои").assign("sky.png", "Герои")
        assertEquals("Герои", lf.folderOf("sky.png")); assertTrue(lf.filesIn("Фоны").isEmpty())
        lf = lf.assign("ghost.png", "Нет такой")
        assertNull(lf.folderOf("ghost.png"))
        lf = lf.rename("Герои", "Персонажи")!!
        assertNull(lf.rename("Фоны", "персонажи")); assertNull(lf.rename("Нет", "X"))
        assertEquals(listOf("hero.png", "sky.png"), lf.filesIn("Персонажи"))
        val back = LibraryFolders.decode(lf.encode())
        assertEquals(lf, back)
        assertEquals(listOf("sky.png"), back.prune(setOf("sky.png")).filesIn("Персонажи"))
        assertEquals(listOf("Персонажи"), back.delete("Фоны").names)
        assertEquals("Персонажи", back.assign("sky.png", null).delete("Фоны").names.single())
        assertNull(back.assign("sky.png", null).folderOf("sky.png"))
        // повреждённый файл и чужие пути
        assertTrue(LibraryFolders.decode("{oops").folders.isEmpty())
        val bad = LibraryFolders.decode("""{"folders":[{"name":"A","files":["../x.png","ok.png","ok.png"]},{"name":"a"},{"name":"B","files":["ok.png"]}]}""")
        assertEquals(listOf("A", "B"), bad.names); assertEquals(listOf("ok.png"), bad.filesIn("A")); assertTrue(bad.filesIn("B").isEmpty())
    }
}
