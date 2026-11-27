// Спринт 55: нативный тест правил производительности рендера - ключ кэша эффектов по содержимому слоя,
// редкая проверка glGetError, ограниченный список удалённых текстур.
#include "render/FxKeyPolicy.h"
#include "render/GlErrorPolicy.h"
#include "render/TexTombstones.h"
#include <cassert>
#include <cstdio>

using namespace vt;

static uint64_t key(const float m[6], const float fx[10], uint64_t tex, const float* crop, uint64_t salt, int id = 7) {
    const uint64_t fh = fxkey::effectsHash(groupcache::kFnvBasis, fx, 10);
    return fxkey::layerKey(id, m, fh, tex, crop, crop ? 4 : 0, salt);
}

int main() {
    const float m[6] = {100.f, 0.f, 0.f, 80.f, 10.f, 20.f};
    const float blur[10] = {1.f, 6.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};   // время 0 - статичный эффект
    const uint64_t k = key(m, blur, 42, nullptr, 1);

    // Тот же слой на следующем кадре Play (ничего своего не менялось) - тот же ключ: кэш годен.
    assert(k == key(m, blur, 42, nullptr, 1));
    assert(fxkey::entryValid(k, k, 1080, 1920, 1080, 1920));
    // Другой размер экрана - запись не годна; пустая запись (0) - никогда.
    assert(!fxkey::entryValid(k, k, 1080, 1920, 1920, 1080));
    assert(!fxkey::entryValid(0, 0, 1, 1, 1, 1));
    assert(k != 0);

    // Своё изменилось - другой ключ: сдвиг слоя, параметр эффекта, время анимированного эффекта, текстура, обрезка, соль, id.
    float moved[6] = {100.f, 0.f, 0.f, 80.f, 11.f, 20.f};
    assert(key(moved, blur, 42, nullptr, 1) != k);
    float stronger[10] = {1.f, 7.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f};
    assert(key(m, stronger, 42, nullptr, 1) != k);
    float wave1[10] = {9.f, 4.f, 30.f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.5f};
    float wave2[10] = {9.f, 4.f, 30.f, 1.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.54f};
    assert(key(m, wave1, 42, nullptr, 1) != key(m, wave2, 42, nullptr, 1));
    assert(key(m, blur, 43, nullptr, 1) != k);
    const float crop[4] = {0.1f, 0.f, 0.9f, 1.f};
    assert(key(m, blur, 42, crop, 1) != k);
    assert(key(m, blur, 42, nullptr, 2) != k);
    assert(key(m, blur, 42, nullptr, 1, 8) != k);
    // Цепочка из двух эффектов отличается от одного.
    uint64_t two = fxkey::effectsHash(fxkey::effectsHash(groupcache::kFnvBasis, blur, 10), blur, 10);
    assert(fxkey::layerKey(7, m, two, 42, nullptr, 0, 1) != k);

    // glGetError: в кадре редактора - раз в kFrameInterval, в экспорте - всегда; после пустой очереди загрузок - нет.
    int checks = 0;
    for (uint64_t f = 1; f <= 1200; ++f) if (glerr::frameCheckDue(f, false)) ++checks;
    assert(checks == 10);
    assert(glerr::frameCheckDue(1, true) && glerr::frameCheckDue(77, true));
    assert(!glerr::afterQueue(0) && glerr::afterQueue(1));

    // Надгробия: не больше cap, старые уходят первыми, повтор не дублирует, erase снимает.
    TexTombstones t(3);
    assert(t.empty() && !t.contains(5));
    t.insert(1); t.insert(2); t.insert(3); t.insert(2);
    assert(t.size() == 3 && t.contains(1));
    t.insert(4);
    assert(t.size() == 3 && !t.contains(1) && t.contains(4));
    t.erase(3);
    assert(!t.contains(3) && t.size() == 2);
    t.insert(5); t.insert(6);
    assert(t.size() == 3 && !t.contains(2) && t.contains(4) && t.contains(5) && t.contains(6));
    TexTombstones big;
    for (int i = 0; i < 100000; ++i) big.insert(i);
    assert(big.size() == TexTombstones::kCap && big.contains(99999) && !big.contains(0));
    t.clear();
    assert(t.empty());

    std::puts("RenderPerfPolicyTest OK");
    return 0;
}
