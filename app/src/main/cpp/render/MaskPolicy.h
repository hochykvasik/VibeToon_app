#pragma once
#include <cstddef>
#include <vector>

// Пакет 26: «Маска по слою» у «Оси растяжения». Группа kMaskSrc рисует маску (слой или группу без растяжения) в свой
// буфер и не накладывается; следующая группа kMasked умножается на альфу этой маски и накладывается как обычно.
// Слои внутри маски (флаг kMaskSrc у слоя) рисуются без шейдерных эффектов и не ловятся касанием.
namespace vt::maskpolicy {
constexpr int kMaskSrc = 4;
constexpr int kMasked = 8;
// Спринт 38, маска слоя: у группы под маской - инверсия (буфер умножается на 1 - альфа маски: нарисованное скрывает).
constexpr int kMaskInvert = 16;

// Нужен ли группе свой буфер: маске и группе под маской - всегда, остальным - при прозрачности или режиме наложения.
constexpr bool isolated(int flags, bool normalOrPass, float opacity) {
    return (flags & (kMaskSrc | kMasked)) != 0 || !normalOrPass || opacity < 0.999f;
}
// Накладывается ли буфер группы на родителя при её закрытии (маска - нет, она ждёт группу под маской).
constexpr bool composites(int flags) { return (flags & kMaskSrc) == 0; }
// Слой маски: без шейдерных эффектов (кэш эффектов - по id слоя, тот же слой в кадре рисуется ещё раз растянутым).
constexpr bool plainDraw(int layerFlags) { return (layerFlags & kMaskSrc) != 0; }
// Касание не выбирает слой маски.
constexpr bool hittable(int layerFlags) { return (layerFlags & kMaskSrc) == 0; }
// Инверсия действует только у группы под маской (флаг на других операциях игнорируется).
constexpr bool inverted(int groupFlags) { return (groupFlags & kMasked) != 0 && (groupFlags & kMaskInvert) != 0; }
// Множитель приёмника при умножении на маску: 0 - альфа маски (GL_SRC_ALPHA), 1 - 1 - альфа (GL_ONE_MINUS_SRC_ALPHA).
constexpr int dstFactor(int groupFlags) { return inverted(groupFlags) ? 1 : 0; }
// Итоговая видимость пикселя цели при альфе маски a (0..1) - модель для проверки инверсии.
constexpr float coverage(int groupFlags, float maskAlpha) { return inverted(groupFlags) ? 1.f - maskAlpha : maskAlpha; }

// Модель обхода drawFrame для проверки пар «маска - группа под маской»: begin/end групп с флагами.
struct Sim {
    std::vector<int> open;     // флаги открытых групп
    std::size_t masks = 0;     // готовые маски (ждут группу под маской)
    int applied = 0;           // группы, умноженные на маску
    int unmasked = 0;          // группы kMasked без маски (рисуются как есть)
    int invertedApplied = 0;   // спринт 38: из них с инверсией
    void begin(int flags) { open.push_back(flags); }
    void end() {
        if (open.empty()) return;
        const int f = open.back();
        open.pop_back();
        if (!composites(f)) { ++masks; return; }
        if (f & kMasked) {
            if (masks > 0) { --masks; ++applied; if (inverted(f)) ++invertedApplied; } else ++unmasked;
        }
    }
    // Конец кадра: незакрытые группы сворачиваются, оставшиеся маски освобождаются; true - всё было парным.
    bool finish() {
        const bool balanced = open.empty() && masks == 0 && unmasked == 0;
        open.clear();
        masks = 0;
        return balanced;
    }
};
}  // namespace vt::maskpolicy
