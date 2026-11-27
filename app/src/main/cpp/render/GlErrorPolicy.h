// Спринт 55: когда спрашивать glGetError. Чистые правила - нативный тест в CI.
//
// glGetError на драйверах с многопоточной отправкой команд (Adreno, Mali) синхронизирует поток приложения с потоком
// драйвера: раньше он вызывался 2+ раза в КАЖДОМ кадре (конец uploadPending и drawFrame), даже когда ничего не
// загружалось. Теперь: после реальных загрузок (там ошибка меняет путь - запасная загрузка), всегда в экспорте и
// снимках, а в кадре редактора - раз в kFrameInterval кадров (ошибки по-прежнему попадают в лог).
#pragma once
#include <cstddef>
#include <cstdint>

namespace vt {
namespace glerr {

constexpr uint64_t kFrameInterval = 120;

constexpr bool frameCheckDue(uint64_t frameNo, bool exportMode) {
    return exportMode || frameNo % kFrameInterval == 0;
}

// Итоговая проверка после очереди загрузок: только если что-то действительно загружалось.
constexpr bool afterQueue(size_t processed) { return processed > 0; }

}  // namespace glerr
}  // namespace vt
