#include "render/UploadQueue.h"

#include <deque>
#include <mutex>
#include <new>
#include <unordered_map>

namespace vt {
namespace uploadQueue {
namespace {
std::mutex g_mu;
std::deque<PendingUpload> g_queue;
size_t g_bytes = 0;
uint32_t g_epoch = 1;
std::unordered_map<int, uint32_t> g_generation;
uint64_t currentTicket(int id) {
    auto it = g_generation.find(id);
    return (static_cast<uint64_t>(g_epoch) << 32) | (it == g_generation.end() ? 0 : it->second);
}
}  // namespace

EmptyGuard::EmptyGuard() : lock_(g_mu), acquired_(g_queue.empty()) {
    if (!acquired_) lock_.unlock();
}

bool EmptyGuard::prepareLayer(int layerId) {
    if (!acquired_) return false;
    try { g_generation.emplace(layerId, 0); return true; }
    catch (const std::bad_alloc&) { return false; }
}

void EmptyGuard::invalidateLayer(int layerId) {
    if (!acquired_) return;
    auto it = g_generation.find(layerId);
    if (it != g_generation.end()) ++it->second;
}

uint64_t ticket(int layerId) {
    std::lock_guard<std::mutex> l(g_mu);
    return currentTicket(layerId);
}

bool pushIfCurrent(PendingUpload&& item, uint64_t t) {
    std::lock_guard<std::mutex> l(g_mu);
    if (currentTicket(item.layerId) != t) return false;
    g_bytes += item.rgba.size();
    g_queue.push_back(std::move(item));
    return true;
}

void push(PendingUpload&& item) {
    std::lock_guard<std::mutex> l(g_mu);
    g_bytes += item.rgba.size();
    g_queue.push_back(std::move(item));
}

bool pop(PendingUpload& out) {
    std::lock_guard<std::mutex> l(g_mu);
    if (g_queue.empty()) return false;
    out = std::move(g_queue.front());
    g_queue.pop_front();
    g_bytes -= out.rgba.size();
    return true;
}

bool empty() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_queue.empty();
}

void clear() {
    std::lock_guard<std::mutex> l(g_mu);
    g_queue.clear();
    g_bytes = 0;
    ++g_epoch;
    g_generation.clear();
}

size_t bytes() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_bytes;
}

}  // namespace uploadQueue
}  // namespace vt
