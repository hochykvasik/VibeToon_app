// Нативный тест правил цели кадра (пакет 7): артефакт при перетаскивании шторки таймлайна.
#include "render/FrameTargetPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::frametarget;

int main() {
    // Редактор рисует во внутренний FBO, экспорт/снимок - в свой.
    static_assert(renderViaOffscreen(0u, false), "editor frame goes offscreen");
    assert(!renderViaOffscreen(0u, true));
    assert(!renderViaOffscreen(7u, false));

    // Смена высоты поверхности при перетаскивании шторки - пересоздание цели.
    assert(needsRealloc(0u, 0, 0, 1080, 1500));
    assert(needsRealloc(5u, 1080, 1500, 1080, 1320));
    assert(needsRealloc(5u, 1080, 1500, 1000, 1500));
    assert(!needsRealloc(5u, 1080, 1320, 1080, 1320));

    assert(fullClearOnCreate(true));
    assert(!fullClearOnCreate(false));

    // Кэш эффектов: другая эпоха или другой размер экрана - запись не используется.
    assert(cacheEntryValid(3ull, 3ull, 1080, 1320, 1080, 1320));
    assert(!cacheEntryValid(3ull, 4ull, 1080, 1320, 1080, 1320));
    assert(!cacheEntryValid(3ull, 3ull, 1080, 1500, 1080, 1320));
    assert(!cacheEntryValid(3ull, 3ull, 1000, 1320, 1080, 1320));

    std::printf("FrameTargetPolicyTest OK\n");
    return 0;
}
