# Вид холста (поворот, отражение) и опорные точки слоёв

## Вид редактора

```
экран = O + k · L · p        p - точка холста (пиксели, y вниз)
k = 0.95 · min(viewW/cw, viewH/ch) · zoom
L = R(rotation) · diag(flipX ? -1 : 1, flipY ? -1 : 1)
центр холста стоит в (центр вида + pan)
```

* C++: `app/src/main/cpp/render/ViewMath.h` (`vt::makeView`, `composeMvp`, `canvasScreenBounds`). Renderer берёт вид через
  `Renderer::view()` во всех местах: MVP слоёв, hitTest, рамка выделения, проход эффектов (`uView` + новый `uLin`).
* Kotlin: `domain/render/Camera.kt` (`viewMatrix`, `transformed`, `withRotation`, `withFlip`) - та же формула; в
  `MainViewModel` все переводы экран↔холст идут через `toScreen/toCanvas(x, y)` (кэш матрицы и обратной).
* Повёрнутый вид: scissor - по описанному прямоугольнику холста, а вылезающие за холст слои закрываются 4 полосами цвета
  фона редактора в координатах холста (`drawOutsideCanvas`).
* Эффекты (FX-шейдер), которые раньше брали экранные смещения (Drop Shadow, Wave, Mirror, Motion Blur, Pixelate, Tiles,
  RGB Split, Pulse, Displacement, Zoom Blur), считают их в осях холста (`dirUv`, `uvOf`) - эффект поворачивается вместе с холстом.
* Экспорт и снимки (`beginExport`) сохраняют и сбрасывают поворот/отражение, `setCamera` во время экспорта игнорируется:
  поворот вида никогда не искажает результат.
* Тесты: `ViewCameraTest` (JVM), `app/src/test/cpp/ViewMathTest.cpp` (g++ в CI: `tools/ci/native_tests.sh`).

## Опорная точка (pivot)

`local = T(P + d) · R · S · T(−P)`, `P` = центр рамки (у группы - центр рамки детей) + `LayerProps.pivotX/Y`.

* `SceneBuilder.buildTree(..., pivotOffsets)` - редактор и экспорт передают одни и те же сдвиги.
* Перенос опорной точки P → P′: `d′ = d + (I − R·S)(P − P′)` для base, override и каждого ключа дорожки
  (`PivotMath.compensate/compensateTrack`) - на ключевых кадрах картинка не двигается, меняется только центр вращения
  между ключами (как Anchor Point в After Effects).
* Палец на перекрестии: экран → холст → координаты родителя → `PivotMath.pivotForPoint` → притяжение `snapToBox`.
* Temporary Pivots: `PivotMath.aroundPoint` пересчитывает сдвиг так, чтобы приращение поворота/масштаба шло вокруг точки жеста.
* Тест: `PivotTest`.

## Ластик и палец по контурам

`VectorMath.erase/smudge/touches/densify/outline` (чисто, без Android). Цели - `pathTargets()` в `MainViewModel`
(все видимые незапертые векторные слои или только выделенные при Only Erase/Smudge Selected Path Layers). Тест: `VectorPathToolsTest`.
