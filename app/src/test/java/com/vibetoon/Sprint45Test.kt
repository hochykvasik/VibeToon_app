package com.vibetoon

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.SelectedKeyCurves
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.model.LayerCrop
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectJson
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.render.LayerTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Спринт 45: общая кривая ключей на разных слоях и неразрушающая обрезка слоя (вкладка «Обрезка»). */
class Sprint45Test {
    private val custom = listOf(0.7f, 0f, 0.3f, 1f)

    /** Слои 2, 3, 4: по ключу на кадре 12 (как на скриншоте: три ключа на трёх слоях); у слоя 2 ещё ключ на 0 и Warp на 12. */
    private fun anim(): Animation {
        var a = Animation()
        a = a.withKey(2, 0, LayerTransform(dx = 0f))
        for (l in listOf(2, 3, 4)) a = a.withKey(l, 12, LayerTransform(dx = l.toFloat()))
        a = a.withKey(WarpMath.pointId(2, 3), 12, LayerTransform(dx = 1f))
        return a
    }

    // ---------------------------------------------------------------- ключи на разных слоях

    @Test fun keysOnDifferentLayersFormOneSet() {
        val a = anim()
        val sel = setOf(2 to 12, 3 to 12, 4 to 12)
        val info = SelectedKeyCurves.info(a, sel)!!
        assertEquals(3, info.keys)
        assertEquals(3, info.layers)
        assertFalse(info.mixed)
        assertFalse(info.custom)
        assertEquals("Кривая выбранных ключей (3)", SelectedKeyCurves.title(info.keys))
        assertEquals("Ключей: 3 на слоях: 3 - одна кривая всем, остальные ключи не меняются", SelectedKeyCurves.note(3, 3))
        assertEquals("Ключей: 2 - одна кривая всем, остальные ключи не меняются", SelectedKeyCurves.note(2, 1))
    }

    @Test fun singleOrEmptySelectionIsNotASet() {
        val a = anim()
        assertNull(SelectedKeyCurves.info(a, setOf(3 to 12)))                 // один ромб - «Кривая ключа»
        assertNull(SelectedKeyCurves.info(a, setOf(3 to 12, 3 to 5)))         // второй ромб без ключей не считается
        assertEquals(setOf(3 to 12), SelectedKeyCurves.keysWithTracks(a, setOf(3 to 12, 3 to 5, 9 to 1)))
        assertNull(SelectedKeyCurves.info(a, emptySet()))
    }

    @Test fun commonEasingChangesOnlySelectedKeysOnAllLayers() {
        val a = anim()
        val sel = setOf(2 to 12, 3 to 12, 4 to 12)
        val t = SelectedKeyCurves.targets(a, sel)
        val b = SelectedKeyCurves.withEasing(a, t, EasingType.EASE_IN_OUT)
        for (l in listOf(2, 3, 4)) assertEquals(EasingType.EASE_IN_OUT, b.easingAt(l, 12))
        assertEquals("дорожка Warp того же ромба тоже", EasingType.EASE_IN_OUT, b.easingAt(WarpMath.pointId(2, 3), 12))
        assertEquals("ключ вне выделения не трогается", a.easingAt(2, 0), b.easingAt(2, 0))
        val info = SelectedKeyCurves.info(b, sel)!!
        assertEquals(EasingType.EASE_IN_OUT, info.easing)
        assertFalse(info.mixed)
    }

    @Test fun customCurveForAllAndMixedDetection() {
        val a = anim()
        val sel = setOf(2 to 12, 3 to 12, 4 to 12)
        val b = SelectedKeyCurves.apply(a, SelectedKeyCurves.targets(a, sel), custom)
        for (l in listOf(2, 3, 4)) assertEquals(custom, b.curveAt(l, 12))
        assertTrue(SelectedKeyCurves.info(b, sel)!!.custom)
        // у одного ключа другая кривая - набор «разный», выбор задаст одну всем
        val c = b.withCurve(4, 12, null)
        assertTrue(SelectedKeyCurves.info(c, sel)!!.mixed)
        val d = SelectedKeyCurves.withEasing(c, SelectedKeyCurves.targets(c, sel), EasingType.LINEAR)
        assertFalse(SelectedKeyCurves.info(d, sel)!!.mixed)
        assertNull("пресет сбрасывает свою кривую", d.curveAt(2, 12))
    }

    // ---------------------------------------------------------------- обрезка слоя

    @Test fun cropSanitizesLikeRenderer() {
        assertNull(LayerCrop.of(null))
        assertNull(LayerCrop.of(listOf(0f, 0f, 1f, 1f)))                  // вся картинка - обрезки нет
        assertNull(LayerCrop.of(listOf(-1f, -1f, 2f, 2f)))
        assertNull(LayerCrop.of(listOf(Float.NaN, 0f, 1f, 1f)))
        assertNull(LayerCrop.of(listOf(0.6f, 0f, 0.4f, 1f)))               // l > r
        assertNull(LayerCrop.of(listOf(0.1f, 0.2f, 0.3f)))
        assertEquals(LayerCrop(0.1f, 0f, 0.9f, 0.5f), LayerCrop.of(listOf(0.1f, -0.2f, 0.9f, 0.5f)))
    }

    @Test fun edgeSlidersNeverCrossOrCollapse() {
        var c = LayerCrop.withEdge(null, 0, 0.3f)
        assertEquals(LayerCrop(0.3f, 0f, 1f, 1f), c)
        c = LayerCrop.withEdge(c, 2, 0.1f)                                   // правый край левее левого - прижат
        assertEquals(0.3f + LayerCrop.MIN_UI_SIDE, c.r, 1e-6f)
        c = LayerCrop.withEdge(c, 1, 2f)                                     // верх ниже низа - прижат
        assertEquals(1f - LayerCrop.MIN_UI_SIDE, c.t, 1e-6f)
        assertNotNull(LayerCrop.of(c.toList()))
        assertEquals(LayerCrop.FULL.copy(b = 0.5f), LayerCrop.withEdge(null, 3, 0.5f))
    }

    @Test fun trimToContentFindsOpaqueBox() {
        val w = 10; val h = 8
        val a = ByteArray(w * h)
        for (y in 2..5) for (x in 3..6) a[y * w + x] = 0xFF.toByte()
        val c = LayerCrop.trimToContent(a, w, h)!!
        assertEquals(0.3f, c.l, 1e-6f); assertEquals(0.25f, c.t, 1e-6f)
        assertEquals(0.7f, c.r, 1e-6f); assertEquals(0.75f, c.b, 1e-6f)
        assertEquals(4 to 4, c.pixelSize(w, h))
        assertNull("пустая картинка", LayerCrop.trimToContent(ByteArray(w * h), w, h))
        assertNull("содержимое на всю картинку", LayerCrop.trimToContent(ByteArray(w * h) { -1 }, w, h))
        // карта рендерера: 8 байт размера (LE) + альфа
        val map = byteArrayOf(10, 0, 0, 0, 8, 0, 0, 0) + a
        assertEquals(c, LayerCrop.trimToContentMap(map))
        assertNull(LayerCrop.trimToContentMap(byteArrayOf(10, 0, 0, 0, 8, 0, 0, 0, 1)))   // обрезанный буфер
    }

    @Test fun sceneTableUsesDraftOverAppliedCrop() {
        val props = mapOf(5 to LayerProps(crop = listOf(0.1f, 0.1f, 0.9f, 0.9f)), 6 to LayerProps(), 7 to LayerProps(crop = listOf(0f, 0f, 1f, 1f)))
        val (ids, rects) = LayerCrop.scene(props)
        assertArrayEquals(intArrayOf(5), ids)
        assertArrayEquals(floatArrayOf(0.1f, 0.1f, 0.9f, 0.9f), rects, 0f)
        // черновик вкладки: новый слой появляется, «Сбросить» (null) убирает рамку слоя
        val (ids2, rects2) = LayerCrop.scene(props, 6 to LayerCrop(0f, 0.5f, 1f, 1f))
        assertArrayEquals(intArrayOf(5, 6), ids2)
        assertEquals(8, rects2.size)
        assertEquals(0, LayerCrop.scene(props, 5 to null).first.size)
    }

    @Test fun cropIsSavedInProjectAndNormalized() {
        assertNull(LayerProps(crop = listOf(0f, 0f, 1f, 1f)).normalized().crop)
        val p = LayerProps(crop = listOf(0.25f, 0f, 0.75f, 0.5f), opacity = 0.5f)
        val back = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "crop", 100, 100), layerProps = mapOf(4 to p)))).getOrThrow()
        assertEquals(listOf(0.25f, 0f, 0.75f, 0.5f), back.layerProps[4]?.crop)
        // старые проекты без «cr» - картинка целиком
        val old = ProjectJson.decode(ProjectJson.encode(ProjectData(ProjectMeta("p", "old", 100, 100), layerProps = mapOf(4 to LayerProps(opacity = 0.5f))))).getOrThrow()
        assertNull(old.layerProps[4]?.crop)
    }
}
