#include "psd/AndroidFile.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "common/Log.h"

namespace vt {

AndroidFile::AndroidFile(psd::Allocator* allocator, std::string utf8Path)
    : psd::File(allocator), path_(std::move(utf8Path)) {}

AndroidFile::~AndroidFile() {
    if (fd_ >= 0) ::close(fd_);
}

bool AndroidFile::DoOpenRead(const wchar_t*) {
    fd_ = ::open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd_ < 0) {
        LOGE("open failed errno=%d path=%s", errno, path_.c_str());
        return false;
    }
    struct stat st;
    if (::fstat(fd_, &st) != 0) {
        LOGE("fstat failed errno=%d", errno);
        ::close(fd_);
        fd_ = -1;
        return false;
    }
    size_ = static_cast<uint64_t>(st.st_size);
    LOGI("open ok path=%s size=%llu", path_.c_str(), static_cast<unsigned long long>(size_));
    return true;
}

bool AndroidFile::DoOpenWrite(const wchar_t*) { return false; }  // запись не нужна

bool AndroidFile::DoClose() {
    if (fd_ >= 0) {
        ::close(fd_);
        fd_ = -1;
    }
    return true;
}

// Чтение синхронное: читаем сразу, а "операция" - просто ненулевой маркер.
psd::File::ReadOperation AndroidFile::DoRead(void* buffer, uint32_t count, uint64_t position) {
    auto* dst = static_cast<uint8_t*>(buffer);
    uint32_t done = 0;
    while (done < count) {
        ssize_t n = ::pread(fd_, dst + done, count - done, static_cast<off_t>(position + done));
        if (n < 0) {
            if (errno == EINTR) continue;
            LOGE("pread failed errno=%d", errno);
            ok_ = false;
            break;
        }
        if (n == 0) break;  // EOF
        done += static_cast<uint32_t>(n);
    }
    if (done < count) {
        // Файл обрезан: дозаполняем нулями и помечаем ошибку
        std::memset(dst + done, 0, count - done);
        ok_ = false;
        LOGE("short read: wanted=%u got=%u pos=%llu", count, done,
             static_cast<unsigned long long>(position));
    }
    return this;
}

bool AndroidFile::DoWaitForRead(ReadOperation&) { return ok_; }

psd::File::WriteOperation AndroidFile::DoWrite(const void*, uint32_t, uint64_t) { return nullptr; }
bool AndroidFile::DoWaitForWrite(WriteOperation&) { return false; }

uint64_t AndroidFile::DoGetSize() const { return size_; }

}  // namespace vt
