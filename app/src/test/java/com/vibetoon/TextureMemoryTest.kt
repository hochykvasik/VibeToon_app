package com.vibetoon

import com.vibetoon.domain.project.ExportSymbolForms
import com.vibetoon.domain.project.SymbolFormCache
import com.vibetoon.domain.render.TextureBudget
import com.vibetoon.domain.render.TextureEvents
import com.vibetoon.domain.render.TextureEviction
import com.vibetoon.domain.render.TextureEviction.Source as S
import com.vibetoon.domain.render.TextureResidency
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет 29: бюджет памяти текстур по устройству (вместо жёстких 192 МБ), что можно вытеснять, повторы и
 * предупреждения, формы символов на GPU для экспорта.
 */
class TextureMemoryTest {
    private val MIB = TextureBudget.MIB
    private val GIB = 1024L * MIB

    @Test fun budgetFollowsDeviceMemory() {
        // 8 ГБ, много свободной: ~12 % (около 980 МБ)
        val b8 = TextureBudget.compute(8 * GIB, 6 * GIB, false, 16384)
        assertTrue(b8 in 900 * MIB..1024 * MIB)
        // 4 ГБ: больше прежних 192 МБ
        val b4 = TextureBudget.compute(4 * GIB, 3 * GIB, false, 8192)
        assertTrue(b4 > 192 * MIB && b4 < b8)
        // мало свободной памяти: не больше половины свободной (но не ниже минимума)
        assertEquals(300 * MIB, TextureBudget.compute(8 * GIB, 600 * MIB, false, 8192))
        assertEquals(TextureBudget.MIN, TextureBudget.compute(8 * GIB, 100 * MIB, false, 8192))
    }

    @Test fun budgetCapsAndUnknownMemory() {
        assertEquals(TextureBudget.DEFAULT, TextureBudget.compute(0, 0, false, 0))
        assertEquals(160 * MIB, TextureBudget.compute(4 * GIB, 3 * GIB, true, 8192))     // isLowRamDevice
        assertEquals(256 * MIB, TextureBudget.compute(8 * GIB, 6 * GIB, false, 2048))    // слабый GPU
        assertEquals(TextureBudget.MAX, TextureBudget.compute(32 * GIB, 30 * GIB, false, 16384))
        assertEquals(TextureBudget.MIN, TextureBudget.compute(512 * MIB, 400 * MIB, false, 4096))
    }

    @Test fun formBudgetIsShareOfTextureBudget() {
        assertEquals(72 * MIB, TextureBudget.formBudget(192 * MIB))   // прежнее соотношение 72 из 192
        assertEquals(48 * MIB, TextureBudget.formBudget(64 * MIB))
        assertEquals(384 * MIB, TextureBudget.formBudget(1024 * MIB))
        assertEquals(1L, TextureBudget.mb(MIB - 1))
    }

    @Test fun eventsDecodeSkipsGarbage() {
        val ev = TextureEvents.decode(intArrayOf(1, 10, 9, 11, 2, 12, 3, 13, 1))
        assertEquals(listOf(TextureEvents.Event(1, 10), TextureEvents.Event(2, 12), TextureEvents.Event(3, 13)), ev)
        assertTrue(TextureEvents.decode(null).isEmpty())
        assertTrue(TextureEvents.decode(intArrayOf(1)).isEmpty())
    }

    @Test fun onlyLosslessTexturesAreEvictable() {
        val items = listOf(1 to S.VIDEO, 2 to S.TEXT, 3 to S.IMAGE_FILE, 4 to S.IMAGE_FILE, 5 to S.CELLS, 6 to S.CELLS, 7 to S.OTHER)
        val ids = TextureEviction.evictable(items, everEdited = setOf(4, 6), formTextures = listOf(1_500_000_001, 1_500_000_000), pinnedForms = setOf(1_500_000_001))
        // правленые (4, 6) и прочие (7) не вытесняются; закреплённая форма - тоже; результат отсортирован
        assertArrayEquals(intArrayOf(1, 2, 3, 5, 1_500_000_000), ids)
    }

    @Test fun evictedReloadsWhenBackInScene() {
        val r = TextureResidency()
        val out = r.onEvents(listOf(TextureEvents.Event(TextureEvents.EVICTED, 5)), 1000)
        assertEquals(listOf(5), out.evicted)
        assertNull(out.warning)   // вытеснение - штатно, без предупреждения
        assertTrue(r.toReload(intArrayOf(1, 2), 1000).isEmpty())   // не в сцене - не грузим
        assertEquals(listOf(5), r.toReload(intArrayOf(1, 5), 1001))
        assertTrue(r.nonResident.isEmpty())
        assertTrue(r.toReload(intArrayOf(5), 1002).isEmpty())   // один раз
    }

    @Test fun budgetRejectionRetriesLaterAndWarnsRarely() {
        val r = TextureResidency(retryMs = 5000, warnEveryMs = 20000)
        val o1 = r.onEvents(listOf(TextureEvents.Event(TextureEvents.REJECTED_BUDGET, 7)), 0)
        assertNotNull(o1.warning); assertFalse(o1.warning!!.tooLarge)
        assertTrue(r.toReload(intArrayOf(7), 4999).isEmpty())
        assertEquals(listOf(7), r.toReload(intArrayOf(7), 5000))
        val o2 = r.onEvents(listOf(TextureEvents.Event(TextureEvents.REJECTED_BUDGET, 7)), 6000)
        assertNull(o2.warning)   // не чаще раза в 20 с
        val o3 = r.onEvents(listOf(TextureEvents.Event(TextureEvents.REJECTED_BUDGET, 8)), 20000)
        assertNotNull(o3.warning)
        assertEquals(3, r.rejectedSinceMark)
        r.mark(); assertEquals(0, r.rejectedSinceMark)
    }

    @Test fun tooLargeNeverRetriesAutomatically() {
        val r = TextureResidency()
        val o = r.onEvents(listOf(TextureEvents.Event(TextureEvents.REJECTED_SIZE, 9)), 0)
        assertTrue(o.warning!!.tooLarge)
        assertTrue(r.toReload(intArrayOf(9), Long.MAX_VALUE - 1).isEmpty())
        r.forget(9)
        assertTrue(r.nonResident.isEmpty())
        r.onEvents(listOf(TextureEvents.Event(TextureEvents.EVICTED, 3)), 0)
        r.clear()
        assertTrue(r.nonResident.isEmpty())
    }

    // ---- формы символов на GPU (экспорт) ----

    @Test fun formCacheBudgetAndEvictionByTexture() {
        val c = SymbolFormCache(100)
        val k1 = SymbolFormCache.Key(1, 0, true); val k2 = SymbolFormCache.Key(1, 1, true)
        c.put(k1, SymbolFormCache.Entry(11, floatArrayOf(0f, 0f, 1f, 1f), 40, c.gen), emptySet())
        c.put(k2, SymbolFormCache.Entry(12, floatArrayOf(0f, 0f, 1f, 1f), 40, c.gen), emptySet())
        assertEquals(setOf(11, 12), c.texIds().toSet())
        assertEquals(setOf(12), c.texIdsOf(listOf(k2, SymbolFormCache.Key(9, 9, true))))
        assertEquals(k1, c.removeTex(11))   // рендерер вытеснил текстуру - форма больше не готова
        assertNull(c.fresh(k1)); assertEquals(40L, c.bytes)
        assertNull(c.removeTex(999))
        c.budgetBytes = 30   // бюджет пересчитан по устройству
        val drop = c.put(k1, SymbolFormCache.Entry(13, floatArrayOf(0f, 0f, 1f, 1f), 20, c.gen), emptySet())
        assertEquals(listOf(12), drop)
        c.budgetBytes = 0; assertEquals(1L, c.budgetBytes)
    }

    @Test fun gpuFormSizeHalvesThenFallsBack() {
        // 2x холста 1920x1080 = 3840x2160 (~44 МБ с mip)
        assertEquals(3840 to 2160, ExportSymbolForms.gpuSize(3840, 2160, 64 * MIB, 1920))
        assertEquals(1920 to 1080, ExportSymbolForms.gpuSize(3840, 2160, 20 * MIB, 1920))   // вдвое меньше - разрешение холста
        assertNull(ExportSymbolForms.gpuSize(3840, 2160, 5 * MIB, 1920))   // ниже холста не уменьшаем - запасной путь
        assertEquals(3840L * 2160 * 4 * 4 / 3, ExportSymbolForms.textureBytes(3840, 2160))
        assertNotNull(ExportSymbolForms.gpuSize(100, 50, 1 * MIB, 4000))   // холст меньше порога
    }

    @Test fun editorFormsOnlyWhenNotDownscaled() {
        val max = 12L * MIB
        assertTrue(ExportSymbolForms.editorFormFullRes(floatArrayOf(0.4f, 0.6f, 0.1f, 0.05f), 3840, 2160, max))   // рот
        assertFalse(ExportSymbolForms.editorFormFullRes(floatArrayOf(0f, 0f, 1f, 1f), 3840, 2160, max))           // весь холст 2x
        assertFalse(ExportSymbolForms.editorFormFullRes(floatArrayOf(0f, 0f), 100, 100, max))
    }

    @Test fun exportAliasesUseSameFormForPrimaryAndFallback() {
        val a = ExportSymbolForms.aliases(listOf(5 to ExportSymbolForms.Form(77, floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)), 6 to ExportSymbolForms.Form(78, ExportSymbolForms.FULL_RECT)))
        assertArrayEquals(intArrayOf(5, 6), a.ids)
        assertArrayEquals(intArrayOf(77, 78), a.primary)
        assertArrayEquals(intArrayOf(77, 78), a.fallback)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.1f, 0.2f, 0.3f, 0.4f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 1f), a.rects, 0f)
        assertEquals(0, ExportSymbolForms.aliases(emptyList()).ids.size)
    }

    @Test fun exportSymbolSetLookup() {
        val k = SymbolFormCache.Key(3, 2, false)
        val set = ExportSymbolForms.Set(mapOf(k to ExportSymbolForms.Form(1, ExportSymbolForms.FULL_RECT)), mapOf(3 to listOf(null, byteArrayOf(1))), listOf(1), setOf())
        assertEquals(1, set.form(3, 2, false)!!.texId)
        assertNull(set.form(3, 2, true))
        assertArrayEquals(byteArrayOf(1), set.png(3, 1))
        assertNull(set.png(3, 0)); assertNull(set.png(3, 5)); assertNull(set.png(4, 0))
        assertTrue(ExportSymbolForms.Set.EMPTY.isEmpty); assertFalse(set.isEmpty)
    }

    @Test fun neededKeysInOrderOfFirstUse() {
        fun key(l: Int) = SymbolFormCache.Key(1, l, true)
        val keys = ExportSymbolForms.neededKeys(listOf(0, 0, 1, 2, 3), { f -> listOf(key(f % 2)) })
        assertEquals(listOf(key(0), key(1)), keys)
        val limited = ExportSymbolForms.neededKeys(0 until 10, { f -> listOf(key(f)) }, limit = 3)
        assertEquals(3, limited.size)
    }
}
