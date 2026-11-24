// Пакет 29: бюджет GPU-памяти под текстуры слоёв и LRU-вытеснение. Чистые функции без GL: проверяются нативным тестом в CI.
//
// Раньше бюджет был жёстким (192 МБ) на любом устройстве, а при переполнении новая текстура просто не создавалась:
// слой молча не обновлялся (только запись в лог). Теперь:
//  - бюджет задаёт Kotlin по памяти устройства (ActivityManager) и GL_MAX_TEXTURE_SIZE, нативная сторона его ограничивает;
//  - если новая текстура не помещается, освобождается место за счёт давно не показанных текстур, которые разрешено
//    вытеснять (их можно восстановить: формы символов, кадры видео, текст, сохранённые картинки) и которых нет в
//    текущей сцене;
//  - если места всё равно нет, загрузка отклоняется и об этом узнаёт Kotlin (видимое предупреждение пользователю).
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace vt {
namespace texbudget {

constexpr size_t kMiB = 1024u * 1024u;
// Пределы бюджета: ниже - не хватит даже на пару слоёв 4K, выше - не нужно и опасно для системы.
constexpr size_t kMinBudget = 128u * kMiB;
constexpr size_t kMaxBudget = 1536u * kMiB;
// До вызова setTextureBudget (и на старых сборках Kotlin) - прежнее значение.
constexpr size_t kDefaultBudget = 192u * kMiB;

inline size_t clampBudget(int64_t bytes) {
    if (bytes <= 0) return kDefaultBudget;
    const uint64_t b = static_cast<uint64_t>(bytes);
    if (b < kMinBudget) return kMinBudget;
    if (b > kMaxBudget) return kMaxBudget;
    return static_cast<size_t>(b);
}

// Байт на текстуру w x h RGBA8 с mip-уровнями (+1/3).
inline size_t textureBytes(int w, int h) {
    if (w <= 0 || h <= 0) return 0;
    return static_cast<size_t>(w) * static_cast<size_t>(h) * 4u * 4u / 3u;
}

struct Candidate {
    int id = 0;
    uint64_t lastUse = 0;   // когда текстура последний раз была в сцене (больше - позже)
    size_t bytes = 0;
    bool pinned = false;    // в текущей сцене, рисуется кистью, изменена на месте или не разрешена к вытеснению
};

// Хватит ли места под [need] байт при занятых [used] и бюджете [budget].
inline bool fits(size_t used, size_t need, size_t budget) {
    return need <= budget && used <= budget - need;
}

// Какие текстуры вытеснить, чтобы поместились [need] байт: давно не использованные первыми, закреплённые - никогда.
// true и список в [out] - места хватит после вытеснения (список может быть пустым, если место уже есть);
// false - не хватит даже после вытеснения всех доступных: ничего не вытесняется (бесполезно), [out] пуст.
inline bool plan(std::vector<Candidate> cands, size_t used, size_t need, size_t budget, std::vector<int>& out) {
    out.clear();
    if (need > budget) return false;
    if (fits(used, need, budget)) return true;
    cands.erase(std::remove_if(cands.begin(), cands.end(), [](const Candidate& c) { return c.pinned || c.bytes == 0; }), cands.end());
    std::stable_sort(cands.begin(), cands.end(), [](const Candidate& a, const Candidate& b) {
        if (a.lastUse != b.lastUse) return a.lastUse < b.lastUse;
        return a.bytes > b.bytes;   // при равной давности - сначала крупные (меньше вытеснений)
    });
    size_t freed = 0;
    for (const Candidate& c : cands) {
        if (fits(used - std::min(used, freed), need, budget)) break;
        out.push_back(c.id);
        freed += c.bytes;
    }
    if (!fits(used - std::min(used, freed), need, budget)) { out.clear(); return false; }
    return true;
}

// Сколько байт нужно новой загрузке: замена текстуры того же размера пишется в существующую (glTexSubImage2D),
// новой памяти не нужно; иначе прежняя текстура живёт до создания новой - пик обеих.
inline size_t uploadNeed(int oldW, int oldH, bool hasOld, int w, int h) {
    if (hasOld && oldW == w && oldH == h) return 0;
    return textureBytes(w, h);
}

}  // namespace texbudget
}  // namespace vt
