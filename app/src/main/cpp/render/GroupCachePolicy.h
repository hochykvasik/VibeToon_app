// Пакет 27: кэш поддеревьев групп в FBO между кадрами.
// Чистые функции без GL: проверяются нативным тестом в CI (app/src/test/cpp/GroupCachePolicyTest.cpp).
//
// Изолированная группа (своя прозрачность < 1 или режим наложения не Normal/Pass) рисуется в свой экранный FBO и затем
// накладывается на родителя. Если содержимое поддерева от кадра к кадру не меняется (статичный фон, персонаж ждёт,
// пока двигается другой), этот FBO можно не перерисовывать: он сохраняется и в следующих кадрах просто накладывается.
//
// Ключ содержимого - 64-битный хэш (FNV-1a) всего, от чего зависит картинка ВНУТРИ группы: операции поддерева
// (id, режимы, флаги, матрицы, прозрачности), версии текстур слоёв и их псевдонимов (формы символов), деформеры и эффекты
// слоёв, вид (камера -> экран), размер экрана и холста, обрезка кадром. Собственные прозрачность и режим наложения
// группы в ключ НЕ входят: они применяются при наложении, поэтому затухание группы тоже берётся из кэша.
//
// Правила:
//  - кэшируется только изолированная группа без «Маски по слою» (ни у неё, ни внутри) и минимум с kMinLayers слоями;
//  - FBO сохраняется, только когда хэш повторился kStableFrames кадров подряд (анимированное поддерево не копится
//    каждый кадр и не держит память);
//  - запись берётся, только если хэш и размер экрана совпали; при недогруженных текстурах внутри запись не сохраняется;
//  - запись, не понадобившаяся в кадре, освобождается (FBO возвращается в пул); число записей ограничено бюджетом памяти.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace vt {
namespace groupcache {

constexpr uint64_t kFnvBasis = 1469598103934665603ull;
constexpr uint64_t kFnvPrime = 1099511628211ull;
constexpr int kMinLayers = 2;            // одиночный слой в группе дешевле нарисовать, чем держать экранный FBO
constexpr int kStableFrames = 2;         // хэш должен повториться: кадр N посчитан, кадр N+1 тот же -> сохраняем
constexpr size_t kBudgetBytes = 64u * 1024u * 1024u;
constexpr int kMaxEntries = 8;

inline uint64_t mix(uint64_t h, uint64_t v) {
    for (int i = 0; i < 8; ++i) {
        h ^= (v >> (i * 8)) & 0xffu;
        h *= kFnvPrime;
    }
    return h;
}

inline uint64_t mixInt(uint64_t h, int v) { return mix(h, static_cast<uint64_t>(static_cast<uint32_t>(v))); }

// -0.f и 0.f дают одну картинку - один ключ; NaN нормализуется.
inline uint64_t mixFloat(uint64_t h, float f) {
    if (f == 0.f) f = 0.f;
    if (f != f) f = 0.f;
    uint32_t bits = 0;
    std::memcpy(&bits, &f, sizeof(bits));
    return mix(h, bits);
}

inline uint64_t mixFloats(uint64_t h, const float* p, size_t n) {
    h = mix(h, static_cast<uint64_t>(n));
    for (size_t i = 0; i < n; ++i) h = mixFloat(h, p[i]);
    return h;
}

// Можно ли вообще кэшировать эту группу.
inline bool eligible(bool enabled, bool isolated, int groupMaskFlags, bool subtreeHasMask, int layerCount) {
    return enabled && isolated && groupMaskFlags == 0 && !subtreeHasMask && layerCount >= kMinLayers;
}

// Сколько экранных FBO можно держать при размере экрана w x h.
inline int maxEntries(int w, int h, size_t budget = kBudgetBytes) {
    if (w <= 0 || h <= 0) return 0;
    const size_t bytes = static_cast<size_t>(w) * static_cast<size_t>(h) * 4u;
    const size_t n = budget / bytes;
    return n > static_cast<size_t>(kMaxEntries) ? kMaxEntries : static_cast<int>(n);
}

enum class Action { Draw, DrawAndKeep, Reuse };

// Состояние группы между кадрами.
struct State {
    uint64_t lastHash = 0;   // хэш, с которым группа рисовалась в прошлый раз
    int stable = 0;          // сколько кадров подряд хэш не менялся
    bool hasCache = false;   // сохранён FBO
    uint64_t cachedHash = 0;
    int w = 0, h = 0;        // размер экрана сохранённого FBO
    bool used = false;       // нужна в этом кадре (иначе освобождается после кадра)
};

inline int nextStable(const State& s, uint64_t hash) { return (s.stable > 0 && s.lastHash == hash) ? s.stable + 1 : 1; }

// Что делать с группой в этом кадре. [canKeep] - бюджет позволяет сохранить ещё один FBO.
inline Action decide(const State& s, uint64_t hash, int w, int h, bool canKeep) {
    if (s.hasCache && s.cachedHash == hash && s.w == w && s.h == h) return Action::Reuse;
    return (canKeep && nextStable(s, hash) >= kStableFrames) ? Action::DrawAndKeep : Action::Draw;
}

// Группа нарисована заново с хэшем [hash]; [kept] - её FBO сохранён в кэш.
inline void drawn(State& s, uint64_t hash, int w, int h, bool kept) {
    s.stable = nextStable(s, hash);
    s.lastHash = hash;
    s.used = true;
    // Группа перерисована - прежний FBO (другой хэш или размер) устарел; освобождает его вызывающий.
    s.hasCache = kept;
    if (kept) { s.cachedHash = hash; s.w = w; s.h = h; }
}

inline void reused(State& s) {
    s.used = true;
    s.stable += 1;
}

// Индекс парной kEndGroup для kBeginGroup в позиции [begin] (или n, если поток не сбалансирован).
// ops[i]: 0 - слой, 1 - начало группы, 2 - конец группы.
inline size_t subtreeEnd(const int* ops, size_t n, size_t begin) {
    int depth = 0;
    for (size_t i = begin; i < n; ++i) {
        if (ops[i] == 1) ++depth;
        else if (ops[i] == 2 && --depth == 0) return i;
    }
    return n;
}

// Модель кэша для теста: группа с хэшем по кадрам, считает перерисовки и сохранения.
struct Sim {
    State st;
    int draws = 0, keeps = 0, reuses = 0;
    Action frame(uint64_t hash, int w = 100, int h = 100, bool canKeep = true) {
        st.used = false;
        const Action a = decide(st, hash, w, h, canKeep);
        if (a == Action::Reuse) { reused(st); ++reuses; }
        else { drawn(st, hash, w, h, a == Action::DrawAndKeep); ++draws; if (a == Action::DrawAndKeep) ++keeps; }
        return a;
    }
};

}  // namespace groupcache
}  // namespace vt
