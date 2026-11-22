// JNI-мост: PsdNative.parseLayers(path) -> Array<LayerInfo>.
// Здесь единственное место C++ с jni.h. C++-исключения наружу не выпускаем.
#include <jni.h>
#include <chrono>
#include <new>
#include <thread>
#include <string>

#include "common/Log.h"
#include "psd/PsdLayerReader.h"
#include "psd/PsdSession.h"
#include "render/UploadQueue.h"

namespace {

// Кэш класса и конструктора (FindClass из нативных потоков не видит классы приложения).
jclass g_layerInfoClass = nullptr;
jmethodID g_layerInfoCtor = nullptr;

// Сигнатура должна совпадать с полями LayerInfo.kt (порядок и типы!):
// (id:Int, name:String, x,y,w,h:Int, opacity:Float, visible:Boolean,
//  kindCode:Int, parentId:Int, blendMode:String, isClipped:Boolean)
constexpr const char* kCtorSig = "(ILjava/lang/String;IIIIFZIILjava/lang/String;Z)V";

void throwNew(JNIEnv* env, const char* cls, const std::string& msg) {
    jclass c = env->FindClass(cls);
    if (c) {
        env->ThrowNew(c, msg.c_str());
        env->DeleteLocalRef(c);
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass local = env->FindClass("com/vibetoon/nativebridge/LayerInfo");
    if (!local) {
        LOGE("JNI_OnLoad: LayerInfo class not found");
        return JNI_ERR;
    }
    g_layerInfoClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_layerInfoCtor = env->GetMethodID(g_layerInfoClass, "<init>", kCtorSig);
    if (!g_layerInfoCtor) {
        LOGE("JNI_OnLoad: LayerInfo ctor not found (signature mismatch / R8?)");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

// ---- Сессия документа: openDocument / documentSize / loadTexture / closeDocument ----

extern "C" JNIEXPORT jlong JNICALL
Java_com_vibetoon_nativebridge_PsdNative_openDocument(JNIEnv* env, jobject, jstring jpath) {
    if (!jpath) {
        throwNew(env, "java/io/IOException", "path is null");
        return 0;
    }
    const char* c = env->GetStringUTFChars(jpath, nullptr);
    std::string path(c);
    env->ReleaseStringUTFChars(jpath, c);
    try {
        std::string err;
        auto s = vt::PsdSession::open(path, err);
        if (!s) {
            throwNew(env, "java/io/IOException", err);
            return 0;
        }
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::bad_alloc&) {
        throwNew(env, "java/lang/OutOfMemoryError", "native OOM in openDocument");
    } catch (const std::exception& e) {
        throwNew(env, "java/io/IOException", e.what());
    }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_PsdNative_closeDocument(JNIEnv*, jobject, jlong h) {
    delete reinterpret_cast<vt::PsdSession*>(h);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_vibetoon_nativebridge_PsdNative_documentSize(JNIEnv* env, jobject, jlong h) {
    auto* s = reinterpret_cast<vt::PsdSession*>(h);
    jint v[2] = {static_cast<jint>(s->width()), static_cast<jint>(s->height())};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

// Декодирует слой в C++, уменьшает и кладёт в очередь загрузки в GPU.
// Возвращает размер RGBA в байтах или -1 (слой пуст/ошибка). Пиксели в Kotlin НЕ попадают.
extern "C" JNIEXPORT jlong JNICALL
Java_com_vibetoon_nativebridge_PsdNative_loadTexture(JNIEnv* env, jobject, jlong h, jint layerId,
                                                      jfloat scale, jint maxTex) {
    auto* s = reinterpret_cast<vt::PsdSession*>(h);
    try {
        // Backpressure: не копим в очереди больше ~48 МБ RGBA (ждём, пока GL-поток заберёт).
        for (int i = 0; i < 1000 && vt::uploadQueue::bytes() > 48u * 1024u * 1024u; ++i)
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        const uint64_t ticket = vt::uploadQueue::ticket(layerId);
        vt::DecodedLayer d;
        if (!s->decode(layerId, scale, maxTex, d)) return -1;
        vt::PendingUpload up;
        up.layerId = d.layerId;
        up.width = d.width;
        up.height = d.height;
        up.rgba = std::move(d.rgba);
        const jlong bytes = static_cast<jlong>(up.rgba.size());
        if (!vt::uploadQueue::pushIfCurrent(std::move(up), ticket)) return -1;
        return bytes;
    } catch (const std::bad_alloc&) {
        throwNew(env, "java/lang/OutOfMemoryError", "native OOM in loadTexture");
    } catch (const std::exception& e) {
        throwNew(env, "java/io/IOException", e.what());
    }
    return -1;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_vibetoon_nativebridge_PsdNative_parseLayers(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if (!jpath) {
        throwNew(env, "java/io/IOException", "path is null");
        return nullptr;
    }
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) return nullptr;  // OOM уже выброшен JVM
    std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);

    vt::ReadResult r;
    try {
        r = vt::readPsdLayers(path);
    } catch (const std::bad_alloc&) {
        throwNew(env, "java/lang/OutOfMemoryError", "native OOM while parsing PSD");
        return nullptr;
    } catch (const std::exception& e) {
        throwNew(env, "java/io/IOException", std::string("native exception: ") + e.what());
        return nullptr;
    } catch (...) {
        throwNew(env, "java/io/IOException", "unknown native exception");
        return nullptr;
    }
    if (!r.ok) {
        throwNew(env, "java/io/IOException", r.error);
        return nullptr;
    }

    const jsize n = static_cast<jsize>(r.layers.size());
    jobjectArray arr = env->NewObjectArray(n, g_layerInfoClass, nullptr);
    if (!arr) return nullptr;

    for (jsize i = 0; i < n; ++i) {
        const vt::LayerRecord& l = r.layers[static_cast<size_t>(i)];
        jstring name = env->NewString(reinterpret_cast<const jchar*>(l.name.data()),
                                      static_cast<jsize>(l.name.size()));
        jstring blend = env->NewStringUTF(l.blend);
        if (!name || !blend) return nullptr;

        jobject o = env->NewObject(g_layerInfoClass, g_layerInfoCtor,
                                   static_cast<jint>(l.id), name,
                                   static_cast<jint>(l.x), static_cast<jint>(l.y),
                                   static_cast<jint>(l.width), static_cast<jint>(l.height),
                                   static_cast<jfloat>(l.opacity),
                                   static_cast<jboolean>(l.visible ? JNI_TRUE : JNI_FALSE),
                                   static_cast<jint>(l.kind), static_cast<jint>(l.parentId),
                                   blend,
                                   static_cast<jboolean>(l.clipped ? JNI_TRUE : JNI_FALSE));
        if (!o || env->ExceptionCheck()) return nullptr;

        env->SetObjectArrayElement(arr, i, o);
        // Обязательно чистим local refs, иначе переполнится таблица на 60+ слоях
        env->DeleteLocalRef(o);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(blend);
    }
    return arr;
}
