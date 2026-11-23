// Нативный тест правил «Маски по слою» (пакет 26).
#include "render/MaskPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::maskpolicy;

int main() {
    static_assert(isolated(kMaskSrc, true, 1.f), "mask needs its own buffer");
    static_assert(isolated(kMasked, true, 1.f), "masked group needs its own buffer");
    static_assert(!isolated(0, true, 1.f), "plain group stays pass-through");
    assert(isolated(0, false, 1.f));
    assert(isolated(0, true, 0.5f));
    assert(!composites(kMaskSrc) && composites(kMasked) && composites(0));
    assert(plainDraw(kMaskSrc | 1) && !plainDraw(1));
    assert(!hittable(kMaskSrc) && hittable(2));

    // слой с маской: [маска][под маской]
    Sim s;
    s.begin(kMaskSrc); s.end(); s.begin(kMasked); s.end();
    assert(s.applied == 1);
    assert(s.finish());

    // вложенная пара внутри маски группы и внутри группы под маской
    Sim n;
    n.begin(kMaskSrc);
    n.begin(kMaskSrc); n.end(); n.begin(kMasked); n.end();   // маска вложенного слоя в маске группы
    n.end();
    n.begin(kMasked);
    n.begin(0); n.begin(kMaskSrc); n.end(); n.begin(kMasked); n.end(); n.end();
    n.end();
    assert(n.applied == 3);
    assert(n.finish());

    // группа под маской без маски и маска без группы - не парные (рендер не падает, просто рисует как есть)
    Sim bad;
    bad.begin(kMasked); bad.end();
    assert(bad.unmasked == 1 && !bad.finish());
    Sim leak;
    leak.begin(kMaskSrc); leak.end();
    assert(!leak.finish() && leak.masks == 0);

    std::printf("MaskPolicyTest OK\n");
    return 0;
}
