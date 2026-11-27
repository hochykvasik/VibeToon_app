package com.vibetoon.domain.render

/**
 * Спринт 42: холст без «сплющивания» при движении панелей.
 *
 * Раньше GL-поверхность занимала только область холста между панелями. Выезд/перетаскивание таймлайна и правой
 * панели меняли её размер; спринт 41 держал старый буфер до конца анимации, и система растягивала его по вертикали
 * (холст сплющивался), а потом скачком перестраивался. Теперь поверхность занимает всё окно редактора и размер не
 * меняет (кроме поворота экрана/клавиатуры), панели непрозрачно лежат поверх неё, а видимая область холста - это
 * прямоугольник (x, y, w, h) в координатах поверхности. Камера пользователя задана для этой области (как и раньше:
 * жесты, наложения и [Camera.toScreen] считают в ней), а рендерер получает эквивалентную камеру для всей поверхности.
 *
 * Экран = C + zoom * fit * A * (p - центр холста) + pan, поэтому та же картинка в области получается при
 * zoom' = zoom * fit(w, h) / fit(W, H) и pan' = pan + (центр области - центр поверхности). Поворот и отражение не меняются.
 * Смена области - только равномерный масштаб и сдвиг: пропорции холста сохраняются всегда.
 */
object ViewRectFit {
    /** Индексы массива области: x, y, w, h (пиксели вида), размер вида поверхности (пиксели вида). */
    const val SIZE = 6

    fun rect(x: Float, y: Float, w: Float, h: Float, viewW: Float, viewH: Float): FloatArray = floatArrayOf(x, y, w, h, viewW, viewH)

    /** Область годна: конечные числа и ненулевые размеры. */
    fun valid(r: FloatArray?): Boolean =
        r != null && r.size >= SIZE && r.all { it.isFinite() } && r[2] > 0f && r[3] > 0f && r[4] > 0f && r[5] > 0f

    /**
     * Камера для буфера [bufW]x[bufH] (пиксели GL), при которой холст выглядит так же, как [camera] в области [r].
     * Буфер может временно отличаться от размера вида (удержание размера спринта 41) - область масштабируется.
     * Неверные данные - камера без изменений (прежнее поведение: область = вся поверхность).
     */
    fun effective(camera: Camera, r: FloatArray?, bufW: Int, bufH: Int, canvasW: Int, canvasH: Int): Camera {
        if (!valid(r) || bufW <= 0 || bufH <= 0 || canvasW <= 0 || canvasH <= 0) return camera
        r!!
        val kx = bufW / r[4]; val ky = bufH / r[5]
        val x = r[0] * kx; val y = r[1] * ky; val w = r[2] * kx; val h = r[3] * ky
        val fs = Camera.fitScale(bufW.toFloat(), bufH.toFloat(), canvasW, canvasH)
        val fv = Camera.fitScale(w, h, canvasW, canvasH)
        if (!fs.isFinite() || !fv.isFinite() || fs <= 0f || fv <= 0f) return camera
        // pan пользователя задан в пикселях вида; в буфере - те же пиксели (буфер = вид), при удержании - масштаб
        val zoom = camera.zoom * fv / fs
        val panX = camera.panX * kx + x + w / 2f - bufW / 2f
        val panY = camera.panY * ky + y + h / 2f - bufH / 2f
        if (!zoom.isFinite() || !panX.isFinite() || !panY.isFinite() || zoom <= 0f) return camera
        return camera.copy(zoom = zoom, panX = panX, panY = panY)
    }

    /** Точка области (координаты жестов) -> пиксель буфера (касание слоя, пипетка). */
    fun toBuffer(px: Float, py: Float, r: FloatArray?, bufW: Int, bufH: Int): Pair<Float, Float> {
        if (!valid(r) || bufW <= 0 || bufH <= 0) return px to py
        r!!
        return (px + r[0]) * (bufW / r[4]) to (py + r[1]) * (bufH / r[5])
    }

    /** Области совпадают с точностью до долей пикселя (лишний кадр не нужен). */
    fun same(a: FloatArray?, b: FloatArray?): Boolean {
        if (a == null || b == null) return a === b
        if (a.size != b.size) return false
        for (i in a.indices) if (kotlin.math.abs(a[i] - b[i]) > 0.25f) return false
        return true
    }
}
