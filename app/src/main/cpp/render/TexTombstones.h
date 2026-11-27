// Спринт 55: id удалённых текстур (формы символов), чьи запоздавшие загрузки надо выбросить. Чистый класс - нативный тест.
//
// Раньше это было неограниченное множество: каждая переснятая форма символа (id растут) оставалась в нём навсегда,
// за долгую сессию - десятки тысяч записей и поиск в них на каждой загрузке. Теперь хранится не больше kCap последних
// id (FIFO): запоздавшая загрузка приходит в пределах нескольких кадров, старые надгробия не нужны.
#pragma once
#include <cstddef>
#include <deque>
#include <unordered_set>

namespace vt {

class TexTombstones {
public:
    static constexpr size_t kCap = 2048;
    explicit TexTombstones(size_t cap = kCap) : cap_(cap ? cap : 1) {}

    void insert(int id) {
        if (!set_.insert(id).second) return;
        order_.push_back(id);
        while (order_.size() > cap_) { set_.erase(order_.front()); order_.pop_front(); }
    }
    // Новая загрузка с тем же id (форма вернулась) - надгробие снимается.
    void erase(int id) {
        if (!set_.erase(id)) return;
        for (auto it = order_.begin(); it != order_.end(); ++it) if (*it == id) { order_.erase(it); break; }
    }
    bool contains(int id) const { return !set_.empty() && set_.count(id) != 0; }
    bool empty() const { return set_.empty(); }
    size_t size() const { return set_.size(); }
    void clear() { set_.clear(); order_.clear(); }

private:
    size_t cap_;
    std::deque<int> order_;
    std::unordered_set<int> set_;
};

}  // namespace vt
