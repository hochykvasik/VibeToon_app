// Цель кадра редактора (пакет 7): чистые правила без GL, проверяются нативным тестом в CI.
//
// Артефакт «отражения» при перетаскивании нижней шторки таймлайна: GLSurfaceView меняет размер
// поверхности почти каждый кадр, а режимы наложения (Multiply/Screen/...) копировали подложку blit'ом
// ПРЯМО ИЗ окна (FBO 0). На части драйверов чтение из оконной поверхности во время её пересоздания
// возвращает старый/сдвинутый буфер - на холсте оставался серый «осколок» прошлого кадра (у волос/лба,
// там, где лежит слой с Multiply). Экспорт был чистым, потому что уже рисовал во внутренний FBO.
// Теперь редактор тоже рисует сцену во внутренний FBO размера экрана и в конце копирует его на окно;
// из окна больше ничего не читается.
#pragma once

namespace vt {
namespace frametarget {

// Рисовать ли кадр во внутренний FBO: да для обычного кадра редактора (screenFbo == 0, не экспорт).
// Экспорт и снимки уже рисуют в свой FBO.
constexpr bool renderViaOffscreen(unsigned screenFbo, bool exportMode) { return screenFbo == 0u && !exportMode; }

// Внутренняя цель пересоздаётся при любом несовпадении размера с экраном.
constexpr bool needsRealloc(unsigned tex, int texW, int texH, int w, int h) {
    return tex == 0u || texW != w || texH != h;
}

// Только что созданная текстура (glTexStorage2D) содержит мусор: первая очистка - целиком, без scissor,
// иначе за рамкой scissor остаются случайные пиксели, которые могут прочитать эффекты/наложение.
constexpr bool fullClearOnCreate(bool created) { return created; }

// Запись кэша эффектов годна, только если совпадают эпоха и размер экрана, для которого она посчитана
// (рамка хранится в координатах GL с началом снизу - при другой высоте она бы сместилась).
constexpr bool cacheEntryValid(unsigned long long entryEpoch, unsigned long long epoch, int entryW, int entryH, int w, int h) {
    return entryEpoch == epoch && entryW == w && entryH == h;
}

}  // namespace frametarget
}  // namespace vt
