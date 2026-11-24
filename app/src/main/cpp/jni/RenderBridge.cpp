// JNI для рендерера. Все вызовы (кроме create) приходят из GL-потока.
// Указатель на Renderer хранится в Kotlin как Long.
#include <android/bitmap.h>
#include <jni.h>
#include <cstring>

#include "render/Renderer.h"
#include "render/UploadQueue.h"

namespace {
vt::Renderer* R(jlong h) { return reinterpret_cast<vt::Renderer*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_vibetoon_nativebridge_RenderNative_create(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new vt::Renderer());
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_destroy(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    R(h)->release();  // вызывать с текущим GL-контекстом
    delete R(h);
}

JNIEXPORT jint JNICALL
Java_com_vibetoon_nativebridge_RenderNative_initGL(JNIEnv*, jobject, jlong h) {
    return R(h)->initGL();
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setViewport(JNIEnv*, jobject, jlong h, jint w, jint hh) {
    R(h)->setViewport(w, hh);
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setCanvas(JNIEnv*, jobject, jlong h, jint w, jint hh) {
    R(h)->setCanvas(w, hh);
}

// ops: n*4 int (op, layerId, blend, flags); params: n*8 float (a, b, c, d, tx, ty, opacity, 0)
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setScene(JNIEnv* env, jobject, jlong h,
                                                      jintArray ops, jfloatArray params) {
    const jsize n = env->GetArrayLength(ops) / 4;
    if (env->GetArrayLength(params) < n * 8) return;
    jint* o = env->GetIntArrayElements(ops, nullptr);
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    R(h)->setScene(o, p, n);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    env->ReleaseIntArrayElements(ops, o, JNI_ABORT);
}

// Warp: ids - слои, offsets - по 32 float (16 точек x 2) на слой
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setWarps(JNIEnv* env, jobject, jlong h, jintArray ids, jfloatArray offsets) {
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(offsets) < n * 32) return;
    jint* i = env->GetIntArrayElements(ids, nullptr);
    jfloat* o = env->GetFloatArrayElements(offsets, nullptr);
    R(h)->setWarps(i, o, n);
    env->ReleaseFloatArrayElements(offsets, o, JNI_ABORT);
    env->ReleaseIntArrayElements(ids, i, JNI_ABORT);
}

// Perspective: ids - слои, mats - по 9 float (3x3 по столбцам) на слой
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setPerspectives(JNIEnv* env, jobject, jlong h, jintArray ids, jfloatArray mats) {
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(mats) < n * 9) return;
    jint* i = env->GetIntArrayElements(ids, nullptr);
    jfloat* m = env->GetFloatArrayElements(mats, nullptr);
    R(h)->setPerspectives(i, m, n);
    env->ReleaseFloatArrayElements(mats, m, JNI_ABORT);
    env->ReleaseIntArrayElements(ids, i, JNI_ABORT);
}

// Эффекты слоёв: ids - слои, counts - число эффектов на слой, data - по 10 float на эффект
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setEffects(JNIEnv* env, jobject, jlong h, jintArray ids, jintArray counts, jfloatArray data) {
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(counts) < n) return;
    jint* i = env->GetIntArrayElements(ids, nullptr);
    jint* c = env->GetIntArrayElements(counts, nullptr);
    long long need = 0;
    for (jsize k = 0; k < n; ++k) need += 10LL * c[k];
    if (env->GetArrayLength(data) >= need) {
        jfloat* d = env->GetFloatArrayElements(data, nullptr);
        R(h)->setEffects(i, c, n, d);
        env->ReleaseFloatArrayElements(data, d, JNI_ABORT);
    } else {
        R(h)->setEffects(i, c, 0, nullptr);
    }
    env->ReleaseIntArrayElements(counts, c, JNI_ABORT);
    env->ReleaseIntArrayElements(ids, i, JNI_ABORT);
}

// Turbulent Displace: ids - слои, data - по 4 float (intensity, size, complexity, seed) на слой
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setTurbulence(JNIEnv* env, jobject, jlong h, jintArray ids, jfloatArray data) {
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(data) < n * 4) return;
    jint* i = env->GetIntArrayElements(ids, nullptr);
    jfloat* d = env->GetFloatArrayElements(data, nullptr);
    R(h)->setTurbulence(i, d, n);
    env->ReleaseFloatArrayElements(data, d, JNI_ABORT);
    env->ReleaseIntArrayElements(ids, i, JNI_ABORT);
}

// Mesh: ids - слои; counts - [вершин, треугольников] на слой; verts - по 4 float на вершину; tris - индексы
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setMeshes(JNIEnv* env, jobject, jlong h, jintArray ids, jintArray counts,
                                                      jfloatArray verts, jintArray tris) {
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(counts) < n * 2) return;
    jint* i = env->GetIntArrayElements(ids, nullptr);
    jint* c = env->GetIntArrayElements(counts, nullptr);
    long long vNeed = 0, tNeed = 0;
    for (jsize k = 0; k < n; ++k) { vNeed += 4LL * c[k * 2]; tNeed += 3LL * c[k * 2 + 1]; }
    if (env->GetArrayLength(verts) >= vNeed && env->GetArrayLength(tris) >= tNeed) {
        jfloat* v = env->GetFloatArrayElements(verts, nullptr);
        jint* t = env->GetIntArrayElements(tris, nullptr);
        R(h)->setMeshes(i, c, n, v, t);
        env->ReleaseIntArrayElements(tris, t, JNI_ABORT);
        env->ReleaseFloatArrayElements(verts, v, JNI_ABORT);
    } else {
        R(h)->setMeshes(i, c, 0, nullptr, nullptr);   // рассогласованные массивы: сетки сбрасываются, слои рисуются обычно
    }
    env->ReleaseIntArrayElements(counts, c, JNI_ABORT);
    env->ReleaseIntArrayElements(ids, i, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setCamera(JNIEnv*, jobject, jlong h, jfloat zoom,
                                                       jfloat panX, jfloat panY, jfloat rotDeg,
                                                       jboolean flipX, jboolean flipY) {
    R(h)->setCamera(zoom, panX, panY, rotDeg, flipX == JNI_TRUE, flipY == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setBackground(JNIEnv*, jobject, jlong h, jint argb) {
    const unsigned c = static_cast<unsigned>(argb);
    R(h)->setBackground(((c >> 16) & 0xFF) / 255.f, ((c >> 8) & 0xFF) / 255.f, (c & 0xFF) / 255.f,
                        ((c >> 24) & 0xFF) / 255.f);
}

// Передаёт Bitmap (RGBA_8888, premultiplied alpha, как выдаёт BitmapFactory) в очередь загрузки
// текстур: пиксели копируются построчно с учётом stride, дальше путь тот же, что у слоёв PSD.
// Возвращает false, если формат не RGBA_8888 или Bitmap нельзя заблокировать.
static jboolean uploadBitmap(JNIEnv* env, jint layerId, jobject bitmap, bool checked, uint64_t ticket, bool shadow = false) {
    AndroidBitmapInfo info;
    if (!bitmap || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) return JNI_FALSE;
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) return JNI_FALSE;

    vt::PendingUpload up;
    up.layerId = layerId;
    up.width = static_cast<int>(info.width);
    up.height = static_cast<int>(info.height);
    up.rgba.resize(static_cast<size_t>(info.width) * info.height * 4);
    const auto* src = static_cast<const uint8_t*>(pixels);
    for (uint32_t y = 0; y < info.height; ++y) {
        std::memcpy(&up.rgba[static_cast<size_t>(y) * info.width * 4], src + static_cast<size_t>(y) * info.stride,
                    static_cast<size_t>(info.width) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (shadow) { vt::uploadQueue::pushShadow(std::move(up)); return JNI_TRUE; }
    if (checked) return vt::uploadQueue::pushIfCurrent(std::move(up), ticket) ? JNI_TRUE : JNI_FALSE;
    vt::uploadQueue::push(std::move(up));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_uploadBitmap(JNIEnv* env, jobject, jint layerId, jobject bitmap) {
    return uploadBitmap(env, layerId, bitmap, false, 0);
}
JNIEXPORT jlong JNICALL
Java_com_vibetoon_nativebridge_RenderNative_textureUploadTicket(JNIEnv*, jobject, jint layerId) {
    return static_cast<jlong>(vt::uploadQueue::ticket(layerId));
}
JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_uploadBitmapIfCurrent(JNIEnv* env, jobject, jint layerId, jobject bitmap, jlong ticket) {
    return uploadBitmap(env, layerId, bitmap, true, static_cast<uint64_t>(ticket));
}

// Теневая текстура участника символа: используется только ближайшим снимком символа (kCaptureShadow),
// текстура слоя в редакторе не меняется.
JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_uploadShadowBitmap(JNIEnv* env, jobject, jint layerId, jobject bitmap) {
    return uploadBitmap(env, layerId, bitmap, false, 0, true);
}

// Сколько байт ждёт загрузки в GPU (ограничение памяти при массовом восстановлении текстур).
JNIEXPORT jlong JNICALL
Java_com_vibetoon_nativebridge_RenderNative_pendingUploadBytes(JNIEnv*, jobject) {
    return static_cast<jlong>(vt::uploadQueue::bytes());
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setInteractive(JNIEnv*, jobject, jlong h, jboolean on) {
    R(h)->setInteractive(on == JNI_TRUE);
}

// Формы символов для экземпляров: применяется вместе со сценой (тот же кадр GL).
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setTextureAliases(JNIEnv* env, jobject, jlong h, jintArray ids, jintArray prim, jintArray fb, jfloatArray rects) {
    if (!ids || !prim || !fb || !rects) { R(h)->setTextureAliases(nullptr, nullptr, nullptr, nullptr, 0); return; }
    const jsize n = env->GetArrayLength(ids);
    if (env->GetArrayLength(prim) < n || env->GetArrayLength(fb) < n || env->GetArrayLength(rects) < n * 8) return;
    std::vector<jint> a(n), b(n), c(n);
    std::vector<jfloat> r(static_cast<size_t>(n) * 8);
    if (n > 0) {
        env->GetIntArrayRegion(ids, 0, n, a.data());
        env->GetIntArrayRegion(prim, 0, n, b.data());
        env->GetIntArrayRegion(fb, 0, n, c.data());
        env->GetFloatArrayRegion(rects, 0, n * 8, r.data());
    }
    R(h)->setTextureAliases(reinterpret_cast<const int*>(a.data()), reinterpret_cast<const int*>(b.data()),
                            reinterpret_cast<const int*>(c.data()), r.data(), static_cast<int>(n));
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_dropTextures(JNIEnv* env, jobject, jlong h, jintArray ids) {
    if (!ids) return;
    const jsize n = env->GetArrayLength(ids);
    std::vector<jint> a(n);
    if (n > 0) env->GetIntArrayRegion(ids, 0, n, a.data());
    R(h)->dropTextures(reinterpret_cast<const int*>(a.data()), static_cast<int>(n));
}

// Отбрасывает ещё не загруженные в GPU текстуры предыдущего проекта
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_clearPendingUploads(JNIEnv*, jobject) {
    vt::uploadQueue::clear();
}

// Смена проекта: старые GPU-текстуры слоёв удаляются (иначе миниатюра нового слоя берёт картинку из прошлого проекта)
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_clearTextures(JNIEnv*, jobject, jlong h) {
    R(h)->clearTextures();
}

// quad: 8 float (4 угла рамки выделения в координатах холста) или null
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_setSelection(JNIEnv* env, jobject, jlong h, jfloatArray quad) {
    const int n = quad ? env->GetArrayLength(quad) / 8 : 0;
    if (n <= 0) {
        R(h)->setSelection(nullptr, 0);
        return;
    }
    jfloat* q = env->GetFloatArrayElements(quad, nullptr);
    R(h)->setSelection(q, n);
    env->ReleaseFloatArrayElements(quad, q, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_vibetoon_nativebridge_RenderNative_hitTest(JNIEnv*, jobject, jlong h, jfloat x, jfloat y) {
    return R(h)->hitTest(x, y);
}

// Альфа слоя: первые 8 байт - ширина и высота (int32, little-endian), дальше по байту на пиксель; null - нет текстуры
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_layerAlpha(JNIEnv* env, jobject, jlong h, jint layerId) {
    int w = 0, hh = 0;
    const uint8_t* a = R(h)->layerAlpha(layerId, w, hh);
    if (!a || w <= 0 || hh <= 0) return nullptr;
    const jsize n = static_cast<jsize>(w) * hh;
    jbyteArray out = env->NewByteArray(8 + n);
    if (!out) return nullptr;
    jbyte head[8];
    for (int i = 0; i < 4; ++i) { head[i] = static_cast<jbyte>((w >> (8 * i)) & 0xFF); head[4 + i] = static_cast<jbyte>((hh >> (8 * i)) & 0xFF); }
    env->SetByteArrayRegion(out, 0, 8, head);
    env->SetByteArrayRegion(out, 8, n, reinterpret_cast<const jbyte*>(a));
    return out;
}

// Миниатюра слоя: size*size*4 байта RGBA premultiplied (строка 0 - верх); null - слой не загружен
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_layerThumb(JNIEnv* env, jobject, jlong h, jint layerId, jint size) {
    std::vector<uint8_t> px;
    if (!R(h)->layerThumb(layerId, size, px)) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(px.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(px.size()), reinterpret_cast<const jbyte*>(px.data()));
    return out;
}

// Пиксели слоя: первые 8 байт - ширина и высота (int32 LE), дальше RGBA premultiplied; null - нет текстуры
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_layerPixels(JNIEnv* env, jobject, jlong h, jint layerId) {
    std::vector<uint8_t> px;
    int w = 0, hh = 0;
    if (!R(h)->readLayerPixels(layerId, px, w, hh)) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(8 + px.size()));
    if (!out) return nullptr;
    jbyte head[8];
    for (int i = 0; i < 4; ++i) { head[i] = static_cast<jbyte>((w >> (8 * i)) & 0xFF); head[4 + i] = static_cast<jbyte>((hh >> (8 * i)) & 0xFF); }
    env->SetByteArrayRegion(out, 0, 8, head);
    env->SetByteArrayRegion(out, 8, static_cast<jsize>(px.size()), reinterpret_cast<const jbyte*>(px.data()));
    return out;
}

// Размер текстуры слоя в пикселях: [w, h] или null, если текстура ещё не загружена. Только из GL-потока.
JNIEXPORT jintArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_layerSize(JNIEnv* env, jobject, jlong h, jint layerId) {
    int w = 0, hh = 0;
    R(h)->layerAlpha(layerId, w, hh);
    if (w <= 0 || hh <= 0) return nullptr;
    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    const jint v[2] = {w, hh};
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

// Рисование (только из GL-потока): начало штриха; mode 0 - CAP_OPACITY, 1 - ACCUMULATE, 2 - ластик; shape 0 - круг, 1 - квадрат
JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintBegin(JNIEnv* env, jobject, jlong h, jint layerId, jint mode, jfloat opacity, jboolean alphaLock,
                                                      jfloat r, jfloat g, jfloat b, jint shape, jfloat hardness,
                                                      jint grainKind, jint grainMode, jfloat grainScale, jfloat grainContrast, jfloat roundness, jint curve, jbyteArray grain) {
    vt::Renderer::BrushPreset p;
    p.shape = shape; p.hardness = hardness; p.grainKind = grainKind; p.grainMode = grainMode;
    p.grainScale = grainScale; p.grainContrast = grainContrast; p.roundness = roundness; p.curve = curve;
    jbyte* gb = nullptr;
    if (grain && env->GetArrayLength(grain) >= vt::Renderer::kGrainSize * vt::Renderer::kGrainSize) gb = env->GetByteArrayElements(grain, nullptr);
    const bool ok = R(h)->paintBegin(layerId, mode, opacity, alphaLock == JNI_TRUE, r, g, b, p, reinterpret_cast<const uint8_t*>(gb));
    if (gb) env->ReleaseByteArrayElements(grain, gb, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Превью кисти w x h (RGBA premultiplied, строка 0 - верх); null - не удалось
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_brushPreview(JNIEnv* env, jobject, jlong h, jint mode, jfloat r, jfloat g, jfloat b,
                                                        jint shape, jfloat hardness, jint grainKind, jint grainMode, jfloat grainScale,
                                                        jfloat grainContrast, jfloat roundness, jint curve, jbyteArray grain, jfloatArray dabs, jint count, jint w, jint hh) {
    if (!dabs || count <= 0 || env->GetArrayLength(dabs) < count * vt::Renderer::kDabStride) return nullptr;
    vt::Renderer::BrushPreset p;
    p.shape = shape; p.hardness = hardness; p.grainKind = grainKind; p.grainMode = grainMode;
    p.grainScale = grainScale; p.grainContrast = grainContrast; p.roundness = roundness; p.curve = curve;
    jbyte* gb = nullptr;
    if (grain && env->GetArrayLength(grain) >= vt::Renderer::kGrainSize * vt::Renderer::kGrainSize) gb = env->GetByteArrayElements(grain, nullptr);
    jfloat* d = env->GetFloatArrayElements(dabs, nullptr);
    std::vector<uint8_t> px;
    const bool ok = R(h)->brushPreview(mode, r, g, b, p, reinterpret_cast<const uint8_t*>(gb), d, count, w, hh, px);
    env->ReleaseFloatArrayElements(dabs, d, JNI_ABORT);
    if (gb) env->ReleaseByteArrayElements(grain, gb, JNI_ABORT);
    if (!ok) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(px.size()));
    if (!out) return nullptr;
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(px.size()), reinterpret_cast<const jbyte*>(px.data()));
    return out;
}

// count штампов по 4 float: x, y, радиус, непрозрачность
JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintDabs(JNIEnv* env, jobject, jlong h, jfloatArray dabs, jint count) {
    if (!dabs || count <= 0 || env->GetArrayLength(dabs) < count * vt::Renderer::kDabStride) return;
    jfloat* d = env->GetFloatArrayElements(dabs, nullptr);
    R(h)->paintDabs(d, count);
    env->ReleaseFloatArrayElements(dabs, d, JNI_ABORT);
}

JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintGradient(JNIEnv* env, jobject, jlong h, jint layerId, jfloatArray geometry) {
    if (!geometry || env->GetArrayLength(geometry) != 10) return JNI_FALSE;
    float g[10]; env->GetFloatArrayRegion(geometry, 0, 10, g);
    return R(h)->paintGradient(layerId, g) ? JNI_TRUE : JNI_FALSE;
}

// Асинхронный конец штриха (чтение в PBO): true - чтение поставлено в очередь
JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintEndAsync(JNIEnv*, jobject, jlong h, jint layerId) {
    return R(h)->paintEndAsync(layerId) ? JNI_TRUE : JNI_FALSE;
}

// Результат асинхронного чтения: null - очередь пуста/ошибка, пустой массив - ещё читается, иначе как paintEnd
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintPoll(JNIEnv* env, jobject, jlong h) {
    std::vector<uint8_t> out;
    const int st = R(h)->paintPoll(out);
    if (st < 0) return nullptr;
    const jsize n = st == 0 ? 0 : static_cast<jsize>(out.size());
    jbyteArray arr = env->NewByteArray(n);
    if (!arr) return nullptr;
    if (n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

// Конец штриха: x, y, w, h (int32 LE) + пиксели "до" + пиксели "после"; null - отмена или пустой штрих
JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintEnd(JNIEnv* env, jobject, jlong h, jboolean commit) {
    std::vector<uint8_t> out;
    if (!R(h)->paintEnd(commit == JNI_TRUE, out) || out.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

// Undo/Redo: прямая перезапись прямоугольника слоя (RGBA premultiplied)
JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_paintRestore(JNIEnv* env, jobject, jlong h, jint layerId, jint x, jint y, jint w, jint hh, jbyteArray data) {
    if (!data || env->GetArrayLength(data) < w * hh * 4) return JNI_FALSE;
    jbyte* d = env->GetByteArrayElements(data, nullptr);
    const bool ok = R(h)->paintRestore(layerId, x, y, w, hh, reinterpret_cast<const uint8_t*>(d));
    env->ReleaseByteArrayElements(data, d, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_vibetoon_nativebridge_RenderNative_replaceTextureChecked(JNIEnv* env, jobject, jlong handle, jint id,
        jint expectedW, jint expectedH, jint width, jint height, jbyteArray data) {
    if (!handle || !data || width <= 0 || height <= 0 || width > 4096 || height > 4096 ||
        static_cast<int64_t>(width) * height > 4194304 ||
        env->GetArrayLength(data) != static_cast<int64_t>(width) * height * 4) return -1;
    jbyte* pixels = env->GetByteArrayElements(data, nullptr);
    if (!pixels) return -5;
    const int result = R(handle)->replaceTextureChecked(id, expectedW, expectedH, width, height,
                                                         reinterpret_cast<const uint8_t*>(pixels));
    env->ReleaseByteArrayElements(data, pixels, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_beginExport(JNIEnv*, jobject, jlong h, jint w, jint hh) {
    R(h)->beginExport(w, hh);
}

JNIEXPORT void JNICALL
Java_com_vibetoon_nativebridge_RenderNative_endExport(JNIEnv*, jobject, jlong h) {
    R(h)->endExport();
}

JNIEXPORT jbyteArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_captureFrame(JNIEnv* env, jobject, jlong h, jint w, jint hh, jboolean transparent, jint flags) {
    std::vector<uint8_t> px;
    if (!R(h)->captureFrame(w, hh, transparent == JNI_TRUE, px, flags)) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(px.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(px.size()), reinterpret_cast<const jbyte*>(px.data()));
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_vibetoon_nativebridge_RenderNative_readPixel(JNIEnv*, jobject, jlong h, jint x, jint y) {
    return R(h)->readPixel(x, y);
}

JNIEXPORT jboolean JNICALL
Java_com_vibetoon_nativebridge_RenderNative_drawFrame(JNIEnv*, jobject, jlong h) {
    return R(h)->drawFrame() ? JNI_TRUE : JNI_FALSE;
}

// Метрики последнего кадра: [нарисовано, отсечено, эффектов в рамке, проходов эффектов, эффектов из кэша,
// без текстуры, групп из кэша поддерева (пакет 27)]
JNIEXPORT jintArray JNICALL
Java_com_vibetoon_nativebridge_RenderNative_frameStats(JNIEnv* env, jobject, jlong h) {
    const vt::FrameStats& s = R(h)->lastStats();
    const jint v[7] = {s.drawn, s.culled, s.scissoredFx, s.fxPasses, s.fxCached, s.missing, s.groupCached};
    jintArray out = env->NewIntArray(7);
    if (out) env->SetIntArrayRegion(out, 0, 7, v);
    return out;
}

}  // extern "C"
