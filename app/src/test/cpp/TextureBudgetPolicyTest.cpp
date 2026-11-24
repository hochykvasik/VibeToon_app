// Нативный тест бюджета текстур и LRU-вытеснения (пакет 29).
#include "render/TextureBudgetPolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::texbudget;

int main() {
    // Пределы бюджета.
    assert(clampBudget(0) == kDefaultBudget);
    assert(clampBudget(-5) == kDefaultBudget);
    assert(clampBudget(10 * kMiB) == kMinBudget);
    assert(clampBudget(int64_t(8000) * kMiB) == kMaxBudget);
    assert(clampBudget(int64_t(512) * kMiB) == 512 * kMiB);

    // Размер с mip-уровнями.
    assert(textureBytes(0, 10) == 0);
    assert(textureBytes(1024, 1024) == 1024u * 1024u * 4u * 4u / 3u);

    // Замена того же размера памяти не требует, другого - требует полной новой.
    assert(uploadNeed(100, 100, true, 100, 100) == 0);
    assert(uploadNeed(100, 100, true, 200, 100) == textureBytes(200, 100));
    assert(uploadNeed(0, 0, false, 100, 100) == textureBytes(100, 100));

    std::vector<int> out;
    // Место есть - ничего не вытесняем.
    assert(plan({{1, 1, 50, false}}, 50, 40, 100, out) && out.empty());
    // Больше бюджета целиком - отказ без вытеснения.
    assert(!plan({{1, 1, 50, false}}, 50, 101, 100, out) && out.empty());

    // LRU: давно не использованные первыми, закреплённые не трогаем.
    std::vector<Candidate> c = {
        {1, 10, 30, false},   // свежая
        {2, 1, 30, false},    // самая старая
        {3, 5, 30, true},     // в сцене
        {4, 3, 10, false},
    };
    // занято 100 из 120, нужно 40: освободить >= 20 -> вытесняется только самая старая (30)
    assert(plan(c, 100, 40, 120, out));
    assert(out.size() == 1 && out[0] == 2);
    // нужно 60: освободить >= 40 -> 2 (30), затем 4 (10)
    assert(plan(c, 100, 60, 120, out));
    assert(out.size() == 2 && out[0] == 2 && out[1] == 4);
    // нужно 100: освободить >= 80, а доступно только 70 (закреплённая 3 не в счёт) -> отказ, ничего не вытесняем
    assert(!plan(c, 100, 100, 120, out) && out.empty());

    // При равной давности - сначала крупные.
    assert(plan({{1, 1, 10, false}, {2, 1, 40, false}}, 90, 30, 100, out));
    assert(out.size() == 1 && out[0] == 2);

    // Пустые записи не считаются.
    assert(!plan({{1, 1, 0, false}}, 100, 10, 100, out) && out.empty());

    std::puts("TextureBudgetPolicyTest OK");
    return 0;
}
