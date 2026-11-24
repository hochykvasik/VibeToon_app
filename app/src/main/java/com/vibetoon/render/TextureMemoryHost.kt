package com.vibetoon.render

import com.vibetoon.domain.render.TextureBudget

/**
 * Пакет 29: бюджет и занятость памяти текстур рендерера для ViewModel (обновляет GL-поток): бюджет форм символов,
 * текст предупреждения о нехватке памяти.
 */
object TextureMemoryHost {
    @Volatile var budgetBytes: Long = TextureBudget.DEFAULT
    @Volatile var usedBytes: Long = 0L
    @Volatile var maxTextureSize: Int = 0
}
