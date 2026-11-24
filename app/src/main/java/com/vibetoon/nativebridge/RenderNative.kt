package com.vibetoon.nativebridge

/** JNI рендерера. Все вызовы, кроме create, делать из GL-потока. */
object RenderNative {
    init {
        System.loadLibrary("vibetoon_native")
    }

    external fun create(): Long
    external fun destroy(renderer: Long)
    /** Возвращает GL_MAX_TEXTURE_SIZE или -1 при ошибке. */
    external fun initGL(renderer: Long): Int
    external fun setViewport(renderer: Long, width: Int, height: Int)
    /** Размер холста PSD в пикселях. */
    external fun setCanvas(renderer: Long, width: Int, height: Int)
    /**
     * Сцена: ops[n*4] = [op, layerId, blend, flags], params[n*8] = [a, b, c, d, tx, ty, opacity, 0]
     * (пиксели холста). Порядок: снизу вверх. op: 0 слой, 1 начало группы, 2 конец группы.
     */
    external fun setScene(renderer: Long, ops: IntArray, params: FloatArray)
    /** Warp: слои и по 32 числа (16 точек x dx, dy) на слой. */
    external fun setWarps(renderer: Long, ids: IntArray, offsets: FloatArray)
    /** Perspective: слои и по 9 float (гомография 3x3 по столбцам) на слой. */
    external fun setPerspectives(renderer: Long, ids: IntArray, mats: FloatArray)
    external fun setTurbulence(renderer: Long, ids: IntArray, data: FloatArray)
    /** Эффекты слоёв: слои, число эффектов на слой, по 10 float на эффект [тип, p0..p7, время]. */
    external fun setEffects(renderer: Long, ids: IntArray, counts: IntArray, data: FloatArray)
    /** Mesh: слои, [вершин, треугольников] на слой, по 4 float (u, v, dx, dy) на вершину, индексы треугольников. */
    external fun setMeshes(renderer: Long, ids: IntArray, counts: IntArray, verts: FloatArray, tris: IntArray)
    /** Камера: экран = C + (p - C)*zoom + pan (пиксели вида). */
    external fun setCamera(renderer: Long, zoom: Float, panX: Float, panY: Float, rotDeg: Float, flipX: Boolean, flipY: Boolean)
    /** Цвет фона холста в формате ARGB (альфа 0 = прозрачный, показывается серым). */
    external fun setBackground(renderer: Long, argb: Int)
    /**
     * Ставит Bitmap (ARGB_8888, premultiplied) в очередь загрузки текстуры слоя [layerId].
     * Bitmap после вызова можно освободить: пиксели скопированы.
     */
    external fun uploadBitmap(layerId: Int, bitmap: android.graphics.Bitmap): Boolean
    /** Получить до начала декодирования, применить только если версия слоя/проекта не изменилась. */
    external fun textureUploadTicket(layerId: Int): Long
    external fun uploadBitmapIfCurrent(layerId: Int, bitmap: android.graphics.Bitmap, ticket: Long): Boolean
    /** Теневая текстура участника символа: видна только ближайшему снимку с [CAPTURE_SHADOW]; текстура слоя в редакторе не меняется. */
    external fun uploadShadowBitmap(layerId: Int, bitmap: android.graphics.Bitmap): Boolean
    /** Байт в очереди загрузки текстур (ограничение памяти при массовом восстановлении). */
    external fun pendingUploadBytes(): Long
    /** Идёт жест камеры: результаты эффектов слоёв переиспользуются с преобразованием вида (после жеста - точный пересчёт). */
    external fun setInteractive(renderer: Long, on: Boolean)
    /** Формы символов (см. SceneData.aliasIds); вызывается вместе со сценой из GL-потока. */
    external fun setTextureAliases(renderer: Long, ids: IntArray, primary: IntArray, fallback: IntArray, rects: FloatArray)
    /** Удалить текстуры форм символов (только GL-поток). */
    external fun dropTextures(renderer: Long, ids: IntArray)
    /** Пакет 29: бюджет GPU-памяти под текстуры в байтах (см. TextureBudget). Только GL-поток. */
    external fun setTextureBudget(renderer: Long, bytes: Long)
    /** [занято, бюджет] в байтах. Только GL-поток. */
    external fun textureMemory(renderer: Long): LongArray?
    /** Текстуры, которые рендерер может вытеснить при нехватке памяти (их восстановит ViewModel). Только GL-поток. */
    external fun setEvictable(renderer: Long, ids: IntArray?)
    /** События памяти текстур с прошлого вызова: пары [вид, id] (см. TextureEvents) или null. Только GL-поток. */
    external fun takeTextureEvents(renderer: Long): IntArray?
    /** Флаги [captureFrame]: теневые текстуры символа / не снимать, пока у видимых слоёв нет текстур (пустой результат). */
    const val CAPTURE_SHADOW = 1
    const val CAPTURE_STRICT = 2
    /** Сбрасывает очередь ещё не загруженных текстур (при открытии другого проекта). */
    external fun clearPendingUploads()
    /** Удаляет все GPU-текстуры слоёв (смена проекта). Только из GL-потока. */
    external fun clearTextures(renderer: Long)
    /** Рамки выделения: по 8 float на рамку (4 угла) в координатах холста, либо null. */
    external fun setSelection(renderer: Long, quad: FloatArray?)
    /** Верхний слой с непрозрачным пикселем под точкой экрана или -1. Только из GL-потока. */
    external fun hitTest(renderer: Long, x: Float, y: Float): Int
    /** Карта прозрачности превью слоя: 8 байт (ширина, высота int32 LE) и по байту на пиксель; null - слой не загружен. */
    external fun layerAlpha(renderer: Long, layerId: Int): ByteArray?
    /** Пиксели текстуры слоя: 8 байт (ширина, высота int32 LE) и RGBA premultiplied; null - слой не загружен. Только из GL-потока. */
    external fun layerPixels(renderer: Long, layerId: Int): ByteArray?
    /** Миниатюра слоя size x size: RGBA premultiplied, строка 0 - верх; null - слой не загружен. Только из GL-потока. */
    external fun layerThumb(renderer: Long, layerId: Int, size: Int): ByteArray?
    /** Размер текстуры слоя [w, h] в пикселях; null - не загружена. Только из GL-потока. */
    external fun layerSize(renderer: Long, layerId: Int): IntArray?
    /** Рисование (только из GL-потока): начало штриха. mode 0 - CAP_OPACITY, 1 - ACCUMULATE, 2 - ластик; shape 0 - круг, 1 - квадрат. */
    external fun paintBegin(
        renderer: Long, layerId: Int, mode: Int, opacity: Float, alphaLock: Boolean, r: Float, g: Float, b: Float, shape: Int, hardness: Float,
        grainKind: Int, grainMode: Int, grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?
    ): Boolean
    /** Превью кисти w x h (RGBA premultiplied, строка 0 - верх): те же штампы в Offscreen FBO; null - не удалось. Только из GL-потока. */
    external fun brushPreview(
        renderer: Long, mode: Int, r: Float, g: Float, b: Float, shape: Int, hardness: Float,
        grainKind: Int, grainMode: Int, grainScale: Float, grainContrast: Float, roundness: Float, curve: Int, grain: ByteArray?, dabs: FloatArray, count: Int, w: Int, h: Int
    ): ByteArray?
    /** [count] штампов по 4 float: x, y, радиус (пиксели слоя), непрозрачность штампа. */
    external fun paintDabs(renderer: Long, dabs: FloatArray, count: Int)
    external fun paintGradient(renderer: Long, layerId: Int, geometry: FloatArray): Boolean
    /** Конец штриха: 16 байт (x, y, w, h int32 LE) + пиксели "до" + пиксели "после" (RGBA premultiplied); null - отмена или пустой штрих. */
    external fun paintEnd(renderer: Long, commit: Boolean): ByteArray?
    /** Асинхронный конец штриха: чтение "до"/"после" в PBO без остановки GPU. true - поставлено в очередь. */
    external fun paintEndAsync(renderer: Long, layerId: Int): Boolean
    /** Результат paintEndAsync: null - очереди нет/ошибка, пустой массив - ещё читается, иначе формат paintEnd. */
    external fun paintPoll(renderer: Long): ByteArray?
    /** Undo/Redo: прямая перезапись прямоугольника слоя. */
    external fun paintRestore(renderer: Long, layerId: Int, x: Int, y: Int, w: Int, h: Int, data: ByteArray): Boolean
    /** Только GL-поток: 0 — успех, отрицательное значение — безопасный отказ без замены. */
    external fun replaceTextureChecked(renderer: Long, layerId: Int, expectedW: Int, expectedH: Int, width: Int, height: Int, rgba: ByteArray): Int
    /** Экспорт: холст на весь кадр w x h; вызывать с текущей EGL-поверхностью кодека. */
    external fun beginExport(renderer: Long, width: Int, height: Int)
    external fun endExport(renderer: Long)
    /** Снимок кадра w x h в память: RGBA premultiplied, строка 0 - низ кадра (как у glReadPixels); null - ошибка. Только из GL-потока. */
    external fun captureFrame(renderer: Long, width: Int, height: Int, transparent: Boolean, flags: Int): ByteArray?
    /** Пакет 29: снимок прямо в текстуру [id] без копии в память: 1 - готово, 0 - не всё загружено (CAPTURE_STRICT), -1 - ошибка, -2 - бюджет. */
    external fun captureToTexture(renderer: Long, id: Int, width: Int, height: Int, transparent: Boolean, flags: Int): Int
    /** Цвет пикселя экрана ARGB (пипетка); вызывать из GL-потока сразу после drawFrame. */
    external fun readPixel(renderer: Long, x: Int, y: Int): Int
    /** true = нужен ещё кадр (позже: идёт загрузка текстур). */
    external fun drawFrame(renderer: Long): Boolean
    /** Метрики последнего кадра: [нарисовано слоёв, отсечено, эффектов в рамке слоя, проходов эффектов]. */
    external fun frameStats(renderer: Long): IntArray?
}
