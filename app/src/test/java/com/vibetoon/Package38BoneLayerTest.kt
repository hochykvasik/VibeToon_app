package com.vibetoon

import com.vibetoon.domain.anim.BoneLayers
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.project.BONE_ID_BASE
import com.vibetoon.domain.project.Bone
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 38: слой костей - владение костями, привязка скелета к слою, скрытие, удаление и сохранение. */
class Package38BoneLayerTest {
    private val rig = MEDIA_ID_BASE + 10          // слой костей
    private val arm = MEDIA_ID_BASE + 11          // рисунок руки
    private val upper = BONE_ID_BASE + 1
    private val lower = BONE_ID_BASE + 2
    private val free = BONE_ID_BASE + 3
    private val bones = listOf(
        Bone(upper, "Плечо", 0f, 0f, 100f, 0f, layer = rig),
        Bone(lower, "Предплечье", 100f, 0f, 200f, 0f, layer = rig),
        Bone(free, "Хвост", 500f, 500f, 600f, 500f)
    )
    private val baseLinks = mapOf(lower to upper, arm to lower)

    @Test fun newBonesGoToActiveOrOnlyBoneLayer() {
        assertEquals(rig, BoneLayers.targetLayer(rig, listOf(rig, rig + 5)))
        assertEquals(rig, BoneLayers.targetLayer(null, listOf(rig)))
        assertEquals(0, BoneLayers.targetLayer(null, listOf(rig, rig + 5)))   // несколько слоёв и ни один не выбран - свободная кость
        assertEquals(0, BoneLayers.targetLayer(rig + 7, emptyList()))
        assertEquals(listOf(upper, lower), BoneLayers.owned(bones, rig).map { it.id })
        assertTrue(BoneLayers.owned(bones, 0).isEmpty())
    }

    @Test fun rootBonesAreBoundToTheirLayer() {
        val l = BoneLayers.bindRoots(bones, baseLinks, rig)
        assertEquals(rig, l[upper])                 // корень - к слою
        assertEquals(upper, l[lower])               // цепочка не меняется
        assertEquals(lower, l[arm])
        assertFalse(l.containsKey(free))            // свободная кость не трогается
        val (adopted, links2) = BoneLayers.adopt(bones, baseLinks, rig)
        assertTrue(adopted.all { it.layer == rig })
        assertEquals(rig, links2[free])
        assertEquals(baseLinks, BoneLayers.bindRoots(bones, baseLinks, 0))
    }

    @Test fun deletingBoneLayerRemovesItsSkeletonOnly() {
        val links = BoneLayers.bindRoots(bones, baseLinks, rig)
        val (left, l2, gone) = BoneLayers.removeLayers(bones, links, setOf(rig))
        assertEquals(setOf(upper, lower), gone)
        assertEquals(listOf(free), left.map { it.id })
        assertFalse(l2.containsKey(arm))            // привязка рисунка к удалённой кости снята - рисунок возвращается на место
        assertFalse(l2.values.contains(rig))
        assertEquals(listOf(0, 0, 0), BoneLayers.orphaned(bones, emptySet()).map { it.layer })
    }

    @Test fun hiddenBoneLayerHidesItsBonesOnly() {
        assertEquals(listOf(free), BoneLayers.visible(bones, setOf(rig)).map { it.id })
        assertEquals(3, BoneLayers.visible(bones, emptySet()).size)
    }

    @Test fun movingBoneLayerCarriesSkeletonAndBoundLayers() {
        val links = BoneLayers.bindRoots(bones, baseLinks, rig)
        val heads = bones.associate { it.id to (it.hx to it.hy) }
        val roots = listOf(
            LeafNode(rig, "Кости", true, 1f, "norm", false, -50, -50, 100, 100),
            LeafNode(arm, "arm", true, 1f, "norm", false, 100, -10, 100, 20)
        )
        val opt = SceneOptions.capture(listOf(MediaItem(rig, MediaKind.BONES, "Кости", "")), emptyMap())
        assertTrue(rig in opt.nullIds)              // слой костей не рисуется
        fun build(t: Map<Int, LayerTransform>) = SceneBuilder.buildTree(roots, 1000, 1000, t, links = links, bones = heads, nullIds = opt.nullIds)
        val rest = build(emptyMap())
        assertEquals(0f, rest.boneDeltas.getValue(lower).tx, 0.001f)
        assertTrue(rest.ops.toList().chunked(4).none { it[1] == rig })   // в потоке рендера слоя костей нет
        val moved = build(mapOf(rig to LayerTransform(dx = 30f, dy = 5f)))
        val d = moved.boneDeltas.getValue(lower)
        assertEquals(130f, d.applyX(100f, 0f), 0.01f)
        assertEquals(5f, d.applyY(100f, 0f), 0.01f)
        assertEquals(30f, moved.boneDeltas.getValue(rig).tx, 0.01f)       // дельта слоя костей - для позы в Animation
        assertEquals(30f, moved.parentWorlds.getValue(arm).tx, 0.01f)     // рисунок на кости едет вместе со слоем
        assertEquals(0f, moved.boneDeltas.getValue(free).tx, 0.001f)
        // поворот кости внутри слоя по-прежнему вокруг её сустава
        val bent = build(mapOf(rig to LayerTransform(dx = 30f), lower to LayerTransform(rotation = (Math.PI / 2).toFloat())))
        val d2 = bent.boneDeltas.getValue(lower)
        assertEquals(130f, d2.applyX(200f, 0f), 0.01f)
        assertEquals(100f, d2.applyY(200f, 0f), 0.01f)
    }

    @Test fun boneLayerSurvivesSaveAndOldBonesStayFree() {
        val d = ProjectData(ProjectMeta("p", "P", 100, 100), media = listOf(MediaItem(rig, MediaKind.BONES, "Кости", "", x = 10f, y = 10f, w = 40f, h = 40f)),
            bones = bones, links = BoneLayers.bindRoots(bones, baseLinks, rig))
        val json = ProjectJson.encode(d)
        val back = ProjectJson.decode(json).getOrThrow()
        assertEquals(bones, back.bones)
        assertEquals(MediaKind.BONES, back.media.single().kind)
        assertEquals(rig, back.links[upper])
        // старый проект без поля "ly" - свободные кости
        val old = json.replace(Regex(",\\s*\"ly\"\\s*:\\s*$rig"), "")
        assertFalse(old.contains("\"ly\""))
        assertTrue(ProjectJson.decode(old).getOrThrow().bones.all { it.layer == 0 })
        // старая сборка не знает тип BONES - такой слой пропускается, остальное читается (проверка формата)
        val unknown = json.replace(Regex("\"kind\"\\s*:\\s*\"BONES\""), "\"kind\":\"FUTURE\"")
        assertFalse(unknown.contains("BONES"))
        assertTrue(ProjectJson.decode(unknown).getOrThrow().media.isEmpty())
    }
}
