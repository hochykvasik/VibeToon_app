package com.vibetoon.nativebridge

/** Единственное место с JNI. Вызывать только с Dispatchers.IO. */
object PsdNative {
    init {
        System.loadLibrary("vibetoon_native") // имя = add_library в CMakeLists.txt
    }

    /** Читает список слоёв PSD по абсолютному пути. Бросает IOException при ошибке. */
    @Throws(java.io.IOException::class)
    external fun parseLayers(absolutePath: String): Array<LayerInfo>

    /** Открывает документ для извлечения пикселей. Обязательно закрыть closeDocument. */
    @Throws(java.io.IOException::class)
    external fun openDocument(absolutePath: String): Long
    external fun closeDocument(doc: Long)
    /** [ширина, высота] холста. */
    external fun documentSize(doc: Long): IntArray

    /**
     * Декодирует слой в C++ (с уменьшением до scale и maxTex) и ставит в очередь загрузки в GPU.
     * Возвращает размер RGBA в байтах, -1 если слой пуст или не загружен. Пиксели в Kotlin не попадают.
     */
    external fun loadTexture(doc: Long, layerId: Int, scale: Float, maxTex: Int): Long

    /**
     * Пакет 23: пиксели слоя для записи в PNG (вставка другого PSD в проект): [ширина, высота, ARGB без premultiply...]
     * или null, если слой пуст или не декодировался.
     */
    external fun decodePixels(doc: Long, layerId: Int, scale: Float, maxTex: Int): IntArray?
}
