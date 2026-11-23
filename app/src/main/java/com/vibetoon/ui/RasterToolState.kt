package com.vibetoon.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Растровые инструменты левой панели: заливка, пипетка, выделение, смазка, перо (контур). */
enum class RasterTool { FILL, PIPETTE, SELECT, SMUDGE, PATH }

/** Способ выделения: прямоугольник, овал, лассо, волшебная палочка. */
enum class SelMode { RECT, OVAL, LASSO, WAND }

/** Параметры заливки: порог цвета (0..255), расширение под контур (px), закрытие зазоров (px). */
data class FillParams(val threshold: Int = 32, val expand: Int = 1, val gap: Int = 2)

/** Параметры смазки: диаметр в пикселях слоя и сила (0..1). */
data class SmudgeParams(val size: Float = 48f, val strength: Float = 0.6f)

/** Выделение на экране: маска, нарисованная в слое, и три угла слоя на экране (левый верхний, правый верхний, левый нижний). */
class SelOverlay(val bmp: ImageBitmap? = null, val corners: FloatArray = FloatArray(0))

/** Жест в процессе: 0 - прямоугольник, 1 - овал (2 точки), 2 - лассо (ломаная), 3 - контур пера (точки). Координаты - пиксели экрана. */
/** kind 4 - узлы векторных примитивов (редактирование): [sel] - индекс выбранного узла в [pts]. */
/** [freeHandles] (пакет 21): ручки узла независимы - по флагу на группу [handles]. [curve] (пакет 20): кривая контура пера в работе на экране, вместо прямых между точками. [handles] (пакет 19): ручки Безье выбранного контура на экране - по 6 чисел: узел x,y, вход x,y, выход x,y; [selHandle] - индекс ручки (узел*2+0/1). */
class ToolDrag(val kind: Int, val pts: FloatArray, val closed: Boolean = false, val sel: Int = -1, val handles: FloatArray? = null, val selHandle: Int = -1, val curve: FloatArray? = null, val freeHandles: BooleanArray? = null)
