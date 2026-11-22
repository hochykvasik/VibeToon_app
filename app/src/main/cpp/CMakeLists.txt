cmake_minimum_required(VERSION 3.22.1)
project(vibetoon_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- psd_sdk как статическая библиотека ----
set(PSD_SDK_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/psd_sdk/src)
file(GLOB PSD_SDK_SOURCES CONFIGURE_DEPENDS ${PSD_SDK_ROOT}/Psd/*.cpp)
# Платформенные файлы (Windows/Linux/Mac) не нужны: у нас свой AndroidFile.
# PsdPch.cpp - прекомпилированный заголовок, тоже не нужен.
list(FILTER PSD_SDK_SOURCES EXCLUDE REGEX ".*/PsdNativeFile.*\\.cpp$")
list(FILTER PSD_SDK_SOURCES EXCLUDE REGEX ".*/PsdPch\\.cpp$")

add_library(psd_sdk STATIC ${PSD_SDK_SOURCES})
target_include_directories(psd_sdk PUBLIC ${PSD_SDK_ROOT} PRIVATE ${PSD_SDK_ROOT}/Psd)
# -w: заглушить предупреждения чужого кода; NDEBUG: отключить assert в psd_sdk
target_compile_options(psd_sdk PRIVATE -w -O2 -DNDEBUG)

# ---- наша нативная библиотека (имя = System.loadLibrary("vibetoon_native")) ----
add_library(vibetoon_native SHARED
    jni/PsdBridge.cpp
    jni/RenderBridge.cpp
    render/GlUtil.cpp
    render/Renderer.cpp
    render/Paint.cpp
    render/UploadQueue.cpp
    psd/PsdSession.cpp
    psd/AndroidFile.cpp
    psd/PsdLayerReader.cpp)
target_include_directories(vibetoon_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(vibetoon_native PRIVATE -Wall -Wextra)
target_link_libraries(vibetoon_native PRIVATE psd_sdk log android GLESv3 EGL jnigraphics)
