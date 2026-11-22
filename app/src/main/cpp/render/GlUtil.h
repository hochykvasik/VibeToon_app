// Вспомогательные функции OpenGL ES: компиляция шейдеров, проверка ошибок.
#pragma once
#include <GLES3/gl3.h>

namespace vt {

// Компилирует и линкует программу. Возвращает 0 при ошибке (причина в Logcat).
GLuint buildProgram(const char* vertexSrc, const char* fragmentSrc);

// Пишет в Logcat все накопленные GL-ошибки. Возвращает true, если ошибок не было.
bool checkGl(const char* where);

}  // namespace vt
