// Спринт 45: порядок привязки текстур при наложении шейдером (Multiply/Screen/Overlay/...). Чистые правила без GL,
// проверяются нативным тестом в CI.
//
// Артефакт «мини-копия холста» (перевёрнутая, сжатая по X, полупрозрачная, у головы - там, где слой с режимом
// наложения). Раньше drawTexture привязывал текстуру слоя к блоку 0 и ПОТОМ вызывал copyBackdrop(). Если подложки
// ещё не было (первый кадр после смены размера цели: экспорт, каждый снимок captureFrame - а значит, каждый кадр
// MP4 в совместимом режиме, GIF и PNG-кадры; в редакторе - при перетаскивании панелей), ensureBackdrop() создавал
// текстуру подложки и glBindTexture'ом привязывал её к АКТИВНОМУ блоку 0 - поверх текстуры слоя. Шейдер читал
// подложку (весь уже нарисованный кадр, строки снизу вверх) координатами слоя: в рамке слоя появлялась вся сцена,
// перевёрнутая по вертикали и сжатая под пропорции слоя. Теперь подложка готовится до привязок, а текстура слоя
// привязывается последней.
#pragma once

namespace vt {
namespace blendbind {

constexpr int kUnitLayer = 0;      // uTex
constexpr int kUnitBackdrop = 1;   // uBackdrop

// Привязывает входы шейдера наложения. prepareBackdrop() копирует цель в подложку и возвращает её текстуру; он может
// создать текстуру (и сменить привязку активного блока) - поэтому вызывается ПЕРВЫМ, а слой привязывается последним.
// Gl - любые activeTexture(unit) / bindTexture(tex) (в рендерере - вызовы GL, в тесте - модель состояния).
template <class Gl, class Prepare>
void bindInputs(Gl& gl, unsigned layerTex, Prepare&& prepareBackdrop) {
    const unsigned backdrop = prepareBackdrop();
    gl.activeTexture(kUnitBackdrop);
    gl.bindTexture(backdrop);
    gl.activeTexture(kUnitLayer);
    gl.bindTexture(layerTex);
}

}  // namespace blendbind
}  // namespace vt
