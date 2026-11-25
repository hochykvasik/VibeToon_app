// Нативный тест правил эффектов группы (спринт 50).
#include "render/GroupFxPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::groupfx;

int main() {
    static_assert(applies(kGroupFx, true, true), "group effects apply to a flagged group");
    static_assert(!applies(0, true, true), "layer mask wrapper (same id as the layer) gets no second pass");
    static_assert(!applies(kGroupFx | kMaskSrc, true, true), "mask pass stays without effects");
    static_assert(!applies(kGroupFx, false, true), "no effects - nothing to apply");
    static_assert(!applies(kGroupFx, true, false), "no fx program - plain composite");
    assert(applies(kGroupFx | 8, true, true));   // группа под маской: эффекты, затем маска
    assert(isolated(false, true));             // Normal/Pass группа с эффектами получает буфер
    assert(isolated(true, false) && !isolated(false, false));
    assert(!cacheable(true, true) && cacheable(true, false) && !cacheable(false, false));
    assert(hashEffects(0) && hashEffects(1) && !hashEffects(2));
    std::puts("GroupFxPolicyTest OK");
    return 0;
}
