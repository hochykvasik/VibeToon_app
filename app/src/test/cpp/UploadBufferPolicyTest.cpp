// Спринт 31: правила пула буферов загрузки и пути через PBO.
#include "render/UploadBufferPolicy.h"

#include <cassert>
#include <cstdio>
#include <vector>

using namespace vt::uploadbuf;

int main() {
    const size_t fullHd = 1920u * 1080u * 4u;
    // Подходящий буфер: не меньше нужного и не больше чем вдвое (+запас).
    assert(reusable(fullHd, fullHd));
    assert(reusable(fullHd, fullHd - 4));
    assert(!reusable(fullHd - 1, fullHd));
    assert(!reusable(fullHd, 64 * 64 * 4));   // кадр Full HD не тратится на миниатюру
    assert(!reusable(1024, 0));
    assert(reusable(2 * 1000 + kSlackBytes, 1000));
    assert(!reusable(2 * 1000 + kSlackBytes + 1, 1000));

    // Лучший - самый маленький подходящий.
    std::vector<size_t> caps = {fullHd * 2, fullHd + 4096, fullHd, 4096};
    assert(bestFit(caps, fullHd) == 2);
    assert(bestFit(caps, fullHd + 100) == 1);
    assert(bestFit(caps, 4000) == 3);
    assert(bestFit(caps, fullHd * 3) == -1);
    assert(bestFit({}, 10) == -1);

    // Пределы пула: число, общий объём, размер одного буфера.
    assert(keep(0, 0, fullHd));
    assert(!keep(kMaxBuffers, 0, 16));
    assert(!keep(0, kMaxPoolBytes - 10, 11));
    assert(keep(0, kMaxPoolBytes - 11, 11));
    assert(!keep(0, 0, 0));
    assert(!keep(0, 0, kMaxSingleBytes + 1));
    assert(keep(0, 0, kMaxSingleBytes));
    // 4 кадра Full HD помещаются целиком.
    assert(4 * fullHd <= kMaxPoolBytes && kMaxBuffers >= 4);

    // Один memcpy только без выравнивания строк.
    assert(singleCopy(1920 * 4, 1920));
    assert(!singleCopy(1920 * 4 + 64, 1920));
    assert(singleCopy(4, 1));

    // PBO: только крупные кадры и пока драйвер не отказал.
    assert(usePbo(false, fullHd));
    assert(!usePbo(true, fullHd));
    assert(!usePbo(false, kPboMinBytes - 1));
    assert(usePbo(false, kPboMinBytes));

    std::puts("UploadBufferPolicyTest OK");
    return 0;
}
