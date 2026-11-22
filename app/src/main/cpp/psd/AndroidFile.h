// Реализация psd::File поверх POSIX (open/pread/fstat) для Android.
// psd_sdk читает файл только через этот интерфейс.
#pragma once
#include <string>
#include "Psd/Psd.h"
#include "Psd/PsdFile.h"

namespace vt {

class AndroidFile : public psd::File {
public:
    AndroidFile(psd::Allocator* allocator, std::string utf8Path);
    ~AndroidFile() override;

    // false, если при чтении были ошибки (короткое чтение / I/O)
    bool ok() const { return ok_; }

private:
    // Аргумент wchar_t* игнорируем: реальный путь (UTF-8) хранится в path_.
    bool DoOpenRead(const wchar_t* filename) override;
    bool DoOpenWrite(const wchar_t* filename) override;
    bool DoClose() override;
    ReadOperation DoRead(void* buffer, uint32_t count, uint64_t position) override;
    bool DoWaitForRead(ReadOperation& operation) override;
    WriteOperation DoWrite(const void* buffer, uint32_t count, uint64_t position) override;
    bool DoWaitForWrite(WriteOperation& operation) override;
    uint64_t DoGetSize() const override;

    std::string path_;
    int fd_ = -1;
    uint64_t size_ = 0;
    bool ok_ = true;
};

}  // namespace vt
