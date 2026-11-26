// Спринт 55: ключ содержимого записи кэша эффектов слоя. Чистые правила без GL - нативный тест в CI.
//
// Раньше запись кэша была годна только при совпадении глобальной «эпохи» рендерера, а эпоха увеличивалась при КАЖДОЙ
// установке сцены (setScene/setWarps/setEffects... - каждый кадр Play, каждое выделение). Итог: на каждом кадре Play
// пересчитывались все цепочки эффектов (Blur, Glow, Shadow) даже у неподвижных слоёв. Теперь запись хранит ключ
// содержимого самого слоя: трансформация, записи эффектов, состояние текстуры (с формой символа), обрезка и «соль»
// смены всех текстур. Изменилось что-то своё - пересчёт, чужие слои и смена кадра без изменений слоя - кэш годен.
#pragma once
#include <cstddef>
#include <cstdint>
#include "render/GroupCachePolicy.h"

namespace vt {
namespace fxkey {

// Хэш записей эффектов слоя: по [stride] float на запись (тип, 8 параметров, время). Время у статичных эффектов - 0
// (EffectMath.usesTime), поэтому смена кадра Play не меняет хэш неподвижного слоя с Blur/Glow/Shadow.
inline uint64_t effectsHash(uint64_t h, const float* record, size_t stride) {
    return groupcache::mixFloats(groupcache::mix(h, static_cast<uint64_t>(stride)), record, stride);
}

// [fxHash] - effectsHash по всем записям, [texState] - хэш текстуры слоя и его формы (id, размер, версия содержимого).
inline uint64_t layerKey(int layerId, const float m[6], uint64_t fxHash, uint64_t texState,
                         const float* crop, size_t cropFloats, uint64_t salt) {
    using namespace groupcache;
    uint64_t h = kFnvBasis;
    h = mixInt(h, layerId);
    h = mix(h, salt);
    h = mixFloats(h, m, 6);
    h = mix(h, fxHash);
    h = mix(h, texState);
    h = mix(h, static_cast<uint64_t>(cropFloats));
    if (crop && cropFloats) h = mixFloats(h, crop, cropFloats);
    return h == 0 ? 1 : h;   // 0 - «нет записи»
}

// Запись годна: тот же ключ содержимого и тот же размер экрана (рамка хранится в координатах GL с началом снизу).
constexpr bool entryValid(uint64_t entryKey, uint64_t key, int entryW, int entryH, int w, int h) {
    return entryKey != 0 && entryKey == key && entryW == w && entryH == h;
}

}  // namespace fxkey
}  // namespace vt
