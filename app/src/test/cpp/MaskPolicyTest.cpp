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

    // спринт 38: маска слоя с инверсией - та же пара, буфер умножается на 1 - альфа
    static_assert(kMaskInvert == 16, "flag must match SceneBuilder.FLAG_MASK_INVERT");
    static_assert(inverted(kMasked | kMaskInvert) && !inverted(kMasked) && !inverted(kMaskInvert), "invert only under mask");
    static_assert(dstFactor(kMasked) == 0 && dstFactor(kMasked | kMaskInvert) == 1, "blend factor");
    static_assert(isolated(kMasked | kMaskInvert, true, 1.f), "masked group has own buffer");
    assert(coverage(kMasked, 1.f) == 1.f && coverage(kMasked, 0.f) == 0.f);
    assert(coverage(kMasked | kMaskInvert, 1.f) == 0.f && coverage(kMasked | kMaskInvert, 0.f) == 1.f);
    assert(coverage(kMasked | kMaskInvert, 0.25f) == 0.75f);
    Sim inv;
    inv.begin(kMaskSrc); inv.end(); inv.begin(kMasked | kMaskInvert); inv.begin(0); inv.end(); inv.end();
    inv.begin(kMaskSrc); inv.end(); inv.begin(kMasked); inv.end();
    assert(inv.applied == 2 && inv.invertedApplied == 1);
    assert(inv.finish());
    // пустая маска (слой-маска без пикселей): пустой буфер, пара по-прежнему сбалансирована
    Sim empty;
    empty.begin(kMaskSrc); empty.end(); empty.begin(kMasked | kMaskInvert); empty.end();
    assert(empty.applied == 1 && empty.finish());

    std::printf("MaskPolicyTest OK\n");
    return 0;
}
