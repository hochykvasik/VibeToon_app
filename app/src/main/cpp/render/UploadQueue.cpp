#include "render/UploadQueue.h"

#include <deque>
#include <mutex>

namespace vt {
namespace uploadQueue {
namespace {
std::mutex g_mu;
std::deque<PendingUpload> g_queue;
size_t g_bytes = 0;
}  // namespace

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
}

size_t bytes() {
    std::lock_guard<std::mutex> l(g_mu);
    return g_bytes;
}

}  // namespace uploadQueue
}  // namespace vt
