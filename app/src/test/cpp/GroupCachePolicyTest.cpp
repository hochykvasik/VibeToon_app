// Нативный тест правил FBO-кэша поддеревьев групп (пакет 27).
#include "render/GroupCachePolicy.h"
#include <cassert>
#include <cstdio>

using namespace vt::groupcache;

int main() {
    // хэш: детерминирован, чувствителен к порядку и значениям, -0 == 0, NaN нормализуется
    const uint64_t a = mixInt(mixInt(kFnvBasis, 1), 2);
    assert(a == mixInt(mixInt(kFnvBasis, 1), 2));
    assert(a != mixInt(mixInt(kFnvBasis, 2), 1));
    assert(mixFloat(kFnvBasis, 0.f) == mixFloat(kFnvBasis, -0.f));
    assert(mixFloat(kFnvBasis, 0.5f) != mixFloat(kFnvBasis, 0.25f));
    const float nan = 0.f / 0.f;
    assert(mixFloat(kFnvBasis, nan) == mixFloat(kFnvBasis, 0.f));
    const float m1[6] = {1.f, 0.f, 0.f, 1.f, 10.f, 20.f};
    const float m2[6] = {1.f, 0.f, 0.f, 1.f, 10.f, 21.f};
    assert(mixFloats(kFnvBasis, m1, 6) != mixFloats(kFnvBasis, m2, 6));
    assert(mixFloats(kFnvBasis, m1, 5) != mixFloats(kFnvBasis, m1, 6));   // длина входит в ключ

    // кому можно
    assert(eligible(true, true, 0, false, 2));
    assert(!eligible(false, true, 0, false, 5));    // снимок / рисование кистью
    assert(!eligible(true, false, 0, false, 5));    // pass-through группа рисуется прямо в родителя
    assert(!eligible(true, true, 4, false, 5));     // маска «Маски по слою»
    assert(!eligible(true, true, 8, false, 5));     // группа под маской
    assert(!eligible(true, true, 0, true, 5));      // маска внутри поддерева
    assert(!eligible(true, true, 0, false, 1));     // один слой - дешевле нарисовать

    // бюджет: 1080x2400 ~ 10 МБ -> 6 записей; маленький экран упирается в kMaxEntries; огромный - 0
    assert(maxEntries(1080, 2400) == 6);
    assert(maxEntries(100, 100) == kMaxEntries);
    assert(maxEntries(8192, 8192) == 0);
    assert(maxEntries(0, 10) == 0);

    // статичная группа: кадр 1 рисуется, кадр 2 рисуется и сохраняется, дальше - из кэша
    Sim s;
    assert(s.frame(42) == Action::Draw);
    assert(s.frame(42) == Action::DrawAndKeep);
    assert(s.frame(42) == Action::Reuse);
    assert(s.frame(42) == Action::Reuse);
    assert(s.draws == 2 && s.keeps == 1 && s.reuses == 2);

    // содержимое изменилось: перерисовка без сохранения, FBO сброшен; снова статично - снова кэш
    assert(s.frame(43) == Action::Draw && !s.st.hasCache);
    assert(s.frame(43) == Action::DrawAndKeep && s.st.hasCache);
    assert(s.frame(43) == Action::Reuse);

    // анимированное поддерево (хэш каждый кадр новый) никогда не копится
    Sim anim;
    for (uint64_t f = 1; f <= 30; ++f) assert(anim.frame(f * 7919) == Action::Draw);
    assert(anim.keeps == 0 && !anim.st.hasCache);

    // смена размера экрана: запись не подходит
    Sim size;
    size.frame(5); size.frame(5);
    assert(size.frame(5) == Action::Reuse);
    assert(size.frame(5, 200, 100) != Action::Reuse);   // (в рендере размер ещё и входит в хэш)

    // бюджет исчерпан: стабильная группа рисуется, но не сохраняется
    Sim full;
    full.frame(9, 100, 100, false);
    assert(full.frame(9, 100, 100, false) == Action::Draw && !full.st.hasCache);
    assert(full.frame(9, 100, 100, true) == Action::DrawAndKeep);

    // парный конец группы: вложенность учитывается, несбалансированный поток -> n
    const int ops[] = {1, 0, 1, 0, 0, 2, 0, 2, 0};
    assert(subtreeEnd(ops, 9, 0) == 7);
    assert(subtreeEnd(ops, 9, 2) == 5);
    const int broken[] = {1, 0, 1, 0, 2};
    assert(subtreeEnd(broken, 5, 0) == 5);

    std::printf("GroupCachePolicyTest OK\n");
    return 0;
}
