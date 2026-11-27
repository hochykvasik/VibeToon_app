package com.vibetoon

import com.vibetoon.domain.project.AssetCatalog
import com.vibetoon.domain.project.AssetCatalog.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Библиотека ассетов не показывает кэш (миниатюры маркеров, Liquify, кадры, старые версии рисунков). */
class AssetCatalogTest {
    private val used = mapOf("m100001_ab.png" to "Голова", "raster_x.png" to "Рисунок 1")
    private val cells = setOf("m100005_c0.png")

    @Test fun classifies() {
        fun k(f: String) = AssetCatalog.classify(f, used, cells).kind
        assertEquals(Kind.IN_PROJECT, k("m100001_ab.png"))
        assertEquals(Kind.IN_PROJECT, k("raster_x.png"))           // рисунок, который использует слой
        assertEquals(Kind.SERVICE, k("raster_old.png"))            // старая версия после правки кистью
        assertEquals(Kind.SERVICE, k("selection_1.png"))
        assertEquals(Kind.SERVICE, k("st2_99999_0.png"))           // миниатюра маркера символа
        assertEquals(Kind.SERVICE, k("liq_100002.png"))
        assertEquals(Kind.SERVICE, k("m100005_c0.png"))
        assertEquals(Kind.SERVICE, k("m100007_c3.png"))            // кадр покадрового слоя
        assertEquals(Kind.IN_PROJECT, k("source.psd"))             // пакет 11: папка PSD
        assertEquals(Kind.UNUSED, k("m100003_x.mp4"))              // пакет 11: видео удалённого слоя в папке «Видео»
        assertEquals(Kind.SERVICE, k("m100003_x.mp4.wave"))        // кэш волны
        assertEquals(Kind.UNUSED, k("m100009_old.png"))            // файл удалённого слоя
    }

    @Test fun libraryOrdersUsedFirstAndUsesLayerNames() {
        val files = listOf("st2_1_0.png", "m100009_old.png", "raster_x.png", "m100001_ab.png", "liq_1.png")
        val lib = AssetCatalog.library(files, used, cells)
        assertEquals(listOf("Голова", "Рисунок 1", "m100009_old.png"), lib.map { it.title })
        assertEquals("assets/m100001_ab.png", lib[0].rel)
        assertEquals(2, AssetCatalog.serviceCount(files, used, cells))
    }
}
