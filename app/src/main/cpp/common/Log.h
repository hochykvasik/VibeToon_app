// Макросы логирования в Logcat. Фильтр в Android Studio: tag:VT/
#pragma once
#include <android/log.h>

#define VT_TAG "VT/Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, VT_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, VT_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, VT_TAG, __VA_ARGS__)
