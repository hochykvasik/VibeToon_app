// Рендерер. Все методы вызываются ТОЛЬКО из GL-потока (потока GLSurfaceView).
// Слои-текстуры, изоляция групп через FBO, Multiply/Screen в шейдере, clipping-маски.
#pragma once
#include <GLES3/gl3.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "render/LayerBounds.h"
#include "render/MaskPolicy.h"
#include "render/GroupCachePolicy.h"
#include "render/TextureBudgetPolicy.h"

namespace vt {

struct PendingUpload;

// Счётчики последнего кадра (метрики производительности, читаются через JNI).
struct FrameStats {
    int drawn = 0;        // нарисовано слоёв
    int culled = 0;       // отсечено (вне экрана)
    int scissoredFx = 0;  // слоёв с эффектами, посчитанных только в своей рамке
    int fxPasses = 0;     // проходов эффектов
    int fxCached = 0;     // слоёв с эффектами, взятых из кэша (движение камеры)
    int missing = 0;      // видимых слоёв без загруженной текстуры (снимок символа ещё рано делать)
    int groupCached = 0;  // пакет 27: групп, наложенных из FBO-кэша поддерева (без перерисовки содержимого)
};

// Флаги снимка captureFrame.
constexpr int kCaptureShadow = 1;   // текстуры участников символа берутся из теневых (кадр символа), не из редактора
constexpr int kCaptureStrict = 2;   // если у видимого слоя ещё нет текстуры - снимок не делается (пустой результат)

class Renderer {
public:
    const FrameStats& lastStats() const { return stats_; }
    // Вызывать из onSurfaceCreated. Возвращает GL_MAX_TEXTURE_SIZE или -1 при ошибке.
    int initGL();
    void setViewport(int width, int height);
    // Размер холста PSD в пикселях (координаты слоёв задаются в этой системе).
    void setCanvas(int width, int height);
    // Поток операций снизу вверх. ops: n*4 int [op, layerId, blend, flags];
    // params: n*8 float [a, b, c, d, tx, ty, opacity, 0]: аффинное преобразование
    // "единичный квадрат -> холст" (canvas = (a*u + c*v + tx, b*u + d*v + ty)).
    void setScene(const int* ops, const float* params, int n);
    // Камера: экранные координаты = C + (p - C)*zoom + pan, C - центр экрана, p - положение
    // при вписывании холста. Одна матрица двигает и масштабирует весь холст.
    void setCamera(float zoom, float panX, float panY, float rotDeg = 0.f, bool flipX = false, bool flipY = false);
    // Цвет фона холста (RGBA 0..1). Альфа 0 (прозрачный фон проекта) показывается светло-серым.
    void setBackground(float r, float g, float b, float a);
    // Warp: для слоёв из списка ids (n штук) 16 смещений контрольных точек сетки 4x4 (по 2 float, в долях
    // размера слоя). Слой рисуется сеткой 24x24, вершины сдвигает бикубическая поверхность Безье.
    void setWarps(const int* ids, const float* offsets, int n);
    // Mesh: для n слоёв ids; counts[i*2] - вершин, counts[i*2+1] - треугольников; verts - по 4 float на вершину
    // (u, v, dx, dy), tris - индексы по 3 на треугольник (всё подряд для всех слоёв). Рисуются только треугольники.
    // Perspective: для n слоёв по матрице 3x3 (9 float по столбцам): слой рисуется через гомографию.
    void setPerspectives(const int* ids, const float* mats, int n);
    // Turbulent Displace: для n слоёв по 4 float (intensity, size, complexity, seed).
    void setTurbulence(const int* ids, const float* data, int n);
    // Эффекты слоёв (цепочка проходов на FBO): ids - слои, counts - число эффектов, data - по 10 float на эффект
    // [тип, p0..p7, время в секундах].
    void setEffects(const int* ids, const int* counts, int n, const float* data);
    void setMeshes(const int* ids, const int* counts, int n, const float* verts, const int* tris);
    // Рамки выделения: n четвёрок углов (по 8 float: x0,y0,...,x3,y3) в координатах холста; n = 0 - нет рамок.
    void setSelection(const float* quads, int n);
    // Hit-test: id верхнего слоя с непрозрачным пикселем под точкой экрана или -1.
    int hitTest(float screenX, float screenY) const;
    // Карта прозрачности превью слоя (1 байт/пиксель): возвращает указатель или nullptr, размеры в w, h.
    const uint8_t* layerAlpha(int layerId, int& w, int& h) const;
    /** Удаляет все текстуры слоёв (смена проекта: id слоёв нового проекта пересекаются со старыми). Только из GL-потока. */
    void clearTextures();
    // Пиксели текстуры слоя (RGBA, premultiplied, строка 0 - верх) для правки на CPU (Liquify слоя PSD). Только из GL-потока.
    bool readLayerPixels(int layerId, std::vector<uint8_t>& out, int& w, int& h);
    // Миниатюра слоя size x size (RGBA premultiplied, строка 0 - верх), вписанная с сохранением пропорций. Только из GL-потока.
    bool layerThumb(int layerId, int size, std::vector<uint8_t>& out);
    // Настройки формы и зерна кисти (остальное - режим, цвет, непрозрачность - передаётся отдельно).
    // shape: 0 - круг, 1 - квадрат. grainKind: 0 - нет, 1 - карандаш, 2 - скринтон, 3 - бумага.
    // grainMode: 0 - нет, 1 - зерно едет со штампом, 2 - зерно неподвижно в координатах холста (слоя):
    //   grainUV = worldPos / (grainTextureSize * grainScale).
    struct BrushPreset {
        int shape = 0;
        float hardness = 0.85f;
        int grainKind = 0;
        int grainMode = 0;
        float grainScale = 1.f;
        float grainContrast = 1.f;
        float roundness = 1.f;   // сплющивание штампа по Y (квадратный маркер 0.4)
        int curve = 0;           // 1 - двойной smoothstep по краю
    };
    static constexpr int kDabStride = 5;     // float на штамп: x, y, радиус, непрозрачность, поворот
    static constexpr int kGrainKinds = 8;    // 0 - нет, 1..6 - виды зерна
    static constexpr int kGrainSize = 256;   // текстуры зерна: 256 x 256, один байт на пиксель

    // Превью кисти: те же штампы рисуются в Offscreen FBO w x h; out - RGBA premultiplied, строка 0 - верх. Только из GL-потока.
    bool brushPreview(int mode, float r, float g, float b, const BrushPreset& preset, const uint8_t* grain,
                      const float* dabs, int count, int w, int h, std::vector<uint8_t>& out);

    // --- Рисование слоя (только из GL-потока) ---
    // Штрих копится в Stroke FBO (тот же размер, что и текстура слоя); на каждый пакет штампов превью запекается в текстуру слоя
    // из копии "до штриха" (backup), поэтому на экране видно сразу, а отмена штриха и Undo берут "до" из backup.
    // mode: 0 - CAP_OPACITY (GL_MAX по 4 каналам, маркер), 1 - ACCUMULATE (premultiplied ADD, аэрограф), 2 - ластик.
    // shape: 0 - круг (мягкий край по hardness), 1 - квадрат (бинарная отсечка).
    bool paintBegin(int layerId, int mode, float opacity, bool alphaLock, float r, float g, float b, const BrushPreset& preset, const uint8_t* grain);
    // count штампов по 5 float: x, y (пиксели слоя), радиус (пиксели), непрозрачность штампа 0..1, поворот (радианы).
    void paintDabs(const float* dabs, int count);
    // Gradient in screen-space affine layer coordinates; writes into the active paint transaction.
    bool paintGradient(int layerId, const float* geometry);
    // Конец штриха. commit = true: в out кладутся x, y, w, h (int32 LE) и два буфера RGBA premultiplied (до, после) для Undo;
    // false - возвращает слой к состоянию до штриха. Результат true, если штрих что-то изменил.
    bool paintEnd(bool commit, std::vector<uint8_t>& out);
    // Асинхронный конец штриха: чтение "до"/"после" идёт в PBO без остановки GPU (на больших штрихах синхронный glReadPixels
    // замораживал рендер на сотни мс). paintEndAsync ставит чтение в очередь, paintPoll отдаёт готовый результат в том же формате,
    // что и paintEnd: 1 - готово (out заполнен), 0 - ещё читается, -1 - очередь пуста / ошибка.
    bool paintEndAsync(int layerId);
    int paintPoll(std::vector<uint8_t>& out);
    // Прямая перезапись прямоугольника слоя (Undo/Redo): без смешивания, glTexSubImage2D.
    bool paintRestore(int layerId, int x, int y, int w, int h, const uint8_t* rgba);
    // GL-поток; 0 успех, отрицательные коды: неверные данные, слой/размер изменился,
    // очередь загрузок не пуста, лимит памяти/размера, ошибка CPU или GL.
    int replaceTextureChecked(int layerId, int expectedW, int expectedH, int w, int h, const uint8_t* rgba);
    // Рисует кадр. Возвращает true, если нужен ещё кадр (остались загрузки в очереди).
    bool drawFrame();
    // Экспорт: холст на весь кадр width x height (без полей, камеры и рамки выделения).
    // Вызывать с контекстом, в котором целью служит поверхность кодека. endExport возвращает вид.
    void beginExport(int width, int height);
    void endExport();
    // Снимок кадра в память: холст w x h (без камеры и рамки) через внутренний FBO; RGBA premultiplied, строка 0 - низ.
    // transparent: без фона холста (PNG/GIF с прозрачностью). Вызывать с текущим GL-контекстом.
    // Возвращает false при ошибке. При kCaptureStrict и недогруженных текстурах - true и пустой out.
    bool captureFrame(int w, int h, bool transparent, std::vector<uint8_t>& out, int flags = 0, int toTexture = -1);
    // Пакет 29: снимок прямо в текстуру [id] (без glReadPixels и копий в память): FBO -> копия с переворотом строк
    // (как у загруженных картинок) -> mip-уровни. Нужен экспорту для форм символов. Возвращает kCaptureToTex*.
    int captureToTexture(int id, int w, int h, bool transparent, int flags);
    static constexpr int kCaptureToTexOk = 1, kCaptureToTexNotReady = 0, kCaptureToTexFailed = -1, kCaptureToTexBudget = -2;
    void clearShadows();
    // Идёт жест камеры: результаты эффектов можно переиспользовать с преобразованием вида.
    void setInteractive(bool on);
    // Формы символов: слой-экземпляр [ids[i]] рисуется текстурой формы prim[i] (или fallback[i], пока основная не загружена)
    // в под-прямоугольнике своего квадрата rects[8*i..] (x, y, w, h для основной и для запасной; доли квадрата).
    // Список задаётся вместе со сценой, поэтому картинка формы и кадр сцены меняются в одном кадре GL.
    void setTextureAliases(const int* ids, const int* prim, const int* fallback, const float* rects, int n);
    // Спринт 45: обрезка слоёв - 4 числа (l, t, r, b в долях текстуры) на id; вне рамки слой не рисуется и не ловится.
    void setCrops(const int* ids, const float* rects, int n);
    // Удаляет текстуры форм (id не переиспользуются: поздние загрузки этих id отбрасываются).
    void dropTextures(const int* ids, int n);
    // Пакет 29: бюджет GPU-памяти под текстуры (байты; считает Kotlin по памяти устройства), см. TextureBudgetPolicy.h.
    void setTextureBudget(int64_t bytes) { budget_ = texbudget::clampBudget(bytes); }
    size_t textureBudget() const { return budget_; }
    size_t textureBytesUsed() const { return textureBytes_; }
    // Какие текстуры можно вытеснять при нехватке памяти (их Kotlin умеет восстановить). Список целиком заменяет прежний.
    void setEvictable(const int* ids, int n);
    // События памяти текстур с прошлого вызова: пары [вид, id]; вид 1 - текстура вытеснена (не в сцене, давно не
    // показывалась), 2 - загрузка отклонена: не хватило бюджета, 3 - больше GL_MAX_TEXTURE_SIZE. Очередь очищается.
    static constexpr int kTexEvicted = 1;
    static constexpr int kTexRejectedBudget = 2;
    static constexpr int kTexRejectedSize = 3;
    void takeTextureEvents(std::vector<int>& out) { out.swap(texEvents_); texEvents_.clear(); }
    // Цвет пикселя экрана (ARGB) после drawFrame: для пипетки. Координаты вида, ось y вниз.
    int readPixel(int x, int y) const;
    // Освобождает GL-объекты (контекст должен быть текущим).
    void release();

private:
    enum Op { kLayer = 0, kBeginGroup = 1, kEndGroup = 2 };
    enum Blend { kNormal = 0, kMultiply = 1, kScreen = 2, kOverlay = 3, kPass = 4, kSoftLight = 5, kAdd = 6, kDarken = 7, kLighten = 8, kColorDodge = 9, kColorBurn = 10 };
    static constexpr int kFlagClipped = 1;
    static constexpr int kFlagHitBbox = 2;   // hit-test по всей рамке (текст)
    // Пакет 26, «Маска по слою» у «Оси растяжения»: группа kFlagMaskSrc рисует маску (слой без растяжения) в свой FBO
    // и не накладывается; следующая группа kFlagMasked умножается на альфу этой маски. Слои внутри маски несут
    // kFlagMaskSrc: рисуются без шейдерных эффектов (кэш эффектов - по id слоя) и не ловятся касанием.
    static constexpr int kFlagMaskSrc = maskpolicy::kMaskSrc;
    static constexpr int kFlagMasked = maskpolicy::kMasked;
    static constexpr int kFlagMaskInvert = maskpolicy::kMaskInvert;   // спринт 38: маска слоя с инверсией

    struct Tex {
        GLuint id = 0;
        size_t bytes = 0;
        int w = 0, h = 0;
        std::vector<uint8_t> alpha;   // альфа превью (1 байт/пиксель) для hit-test на CPU
    };
    struct Item {
        int op, layerId, blend, flags;
        float m[6];      // a, b, c, d, tx, ty
        float opacity;
    };
    // Промежуточная цель рендера размером с экран (для групп и clipping).
    struct Fbo {
        GLuint fbo = 0;
        GLuint tex = 0;
    };
    // Состояние открытой группы при обходе потока операций.
    struct Frame {
        bool isolated;
        float opacity;
        int blend;
        GLuint parentFbo;
        Fbo fbo;
        int flags = 0;
        // пакет 27: кэш поддерева группы
        bool cacheable = false;
        bool keep = false;
        uint64_t cacheKey = 0;
        uint64_t hash = 0;
        int missingBefore = 0;
    };
    // Пакет 27: сохранённый FBO поддерева группы (см. GroupCachePolicy.h).
    struct GroupEntry {
        groupcache::State st;
        Fbo fbo;
    };
    bool groupCacheEnabled() const;
    uint64_t subtreeHash(size_t begin, size_t end, int& layers, bool& hasMask) const;
    uint64_t texHash(uint64_t h, int layerId) const;
    uint64_t groupKey(int groupId);              // id группы + номер её появления в кадре (одинаковые id не путаются)
    int groupKeptCount() const;
    void dropGroupCache();                       // удалить все FBO кэша групп (смена размера, экспорт, release)
    void gcGroupCache();                         // после кадра: освободить записи групп, не понадобившихся в этом кадре
    void touchTex(int layerId) { ++texGen_[layerId]; }

    void restoreCanvasScissor();
    void uploadPending();                        // не более kUploadBytesPerFrame за кадр
    void uploadShadows();                        // все теневые загрузки (снимок символа)
    bool makeTexture(const PendingUpload& up, size_t bytes, Tex& t);
    bool uploadViaPbo(const PendingUpload& up);  // Спринт 31: запись в привязанную текстуру через PBO
    GLuint uploadPbo_[2] = {0, 0};
    int uploadPboNext_ = 0;
    bool uploadPboBroken_ = false;
    const Tex* resolveTex(int layerId, float rect[4]) const;   // + под-прямоугольник квадрата слоя (форма символа)
    const Tex* findTex(int layerId) const;       // теневая текстура (в снимке символа) или текстура слоя
    struct TexAlias { int primary = -1, fallback = -1; float pr[4] = {0.f, 0.f, 1.f, 1.f}; float fr[4] = {0.f, 0.f, 1.f, 1.f}; };
    std::unordered_map<int, TexAlias> aliases_;
    std::unordered_map<int, std::array<float, 4>> crops_;   // спринт 45: обрезка слоёв
    std::unordered_set<int> droppedTex_;
    // --- кэш результата эффектов (см. FxCachePolicy.h) ---
    struct FxEntry {
        GLuint tex = 0, fbo = 0;
        int bx = 0, by = 0, bw = 0, bh = 0;      // рамка на экране (GL, начало снизу)
        float va[6] = {1.f, 0.f, 0.f, 1.f, 0.f, 0.f};
        uint64_t epoch = 0;
        int vw = 0, vh = 0;                      // размер экрана, для которого посчитана запись
        bool used = false;
    };
    void bumpEpoch() { ++epoch_; }
    bool fxCacheEnabled() const;
    bool drawFxFromCache(const Item& it, const float va[6], float reach, float opacity, int blend);
    void storeFxCache(const Item& it, GLuint srcFbo, const int box[4], const float va[6]);
    void dropFxEntry(int layerId);
    void dropFxCache();
    void gcFxCache();                            // после кадра: удалить записи слоёв, не нарисованных в этом кадре
    void viewTransform(float& scale, float& ox, float& oy) const;  // холст -> экран с камерой
    void viewAffine(float a[6]) const;  // холст -> экран: аффинная матрица (поворот/отражение вида)
    void drawSelection();
    void mvpForRect(float x, float y, float w, float h, GLfloat out[9]) const;
    // Единичный квадрат -> холст по аффинной матрице m[6] -> экран (камера) -> NDC.
    void mvpFor(const float m[6], GLfloat out[9]) const;
    // Единичный квадрат -> параллелограмм на экране: p0 + u*(e) + v*(f) (пиксели экрана) -> NDC.
    void mvpScreen(float ox, float oy, float ex, float ey, float fx, float fy, GLfloat out[9]) const;

    // --- цели рендера ---
    void bindTarget(GLuint fbo);                 // 0 = экран
    Fbo acquireFbo();                            // очищенный прозрачный FBO из пула
    void releaseFbo(const Fbo& f);
    void releaseTargets();                       // при смене размера экрана
    void ensureBackdrop();
    void copyBackdrop();                         // текущая цель -> текстура подложки

    // --- рисование ---
    void drawTexture(GLuint tex, const GLfloat mvp[9], float opacity, int blend, bool clipMode, const float* warp = nullptr);
    void drawQuadOrGrid(const float* warp);
    void drawLayer(const Item& it, float opacity, int blend, bool clipMode);
    void drawLayerPlain(const Item& it, float opacity, int blend, bool clipMode);
    void runEffect(GLuint srcTex, const float* e);   // e[10]: тип, p0..p7, время
    void compositeFbo(const Fbo& f, float opacity, int blend);
    void multiplyByMask(const Fbo& mask, bool invert = false);   // текущая цель *= альфа маски (или 1 - альфа, спринт 38)
    void drawItem(const Item& it, float opacity, int blend, bool clipMode);   // слой маски - без эффектов

    int width_ = 1, height_ = 1;
    int canvasW_ = 1, canvasH_ = 1;
    float zoom_ = 1.f, panX_ = 0.f, panY_ = 0.f;
    float rotDeg_ = 0.f;
    bool flipX_ = false, flipY_ = false;
    float bg_[4] = {0.85f, 0.85f, 0.85f, 1.f};
    bool exportMode_ = false;
    GLuint screenFbo_ = 0;            // куда рисует drawFrame (0 - экран; при снимке - внутренний FBO)
    GLuint exportFbo_ = 0, exportTex_ = 0;  // экспорт в Surface кодека: кадр рисуется в FBO, затем blit на поверхность
    bool exportToSurface_ = false;
    bool transparentCapture_ = false;
    int savedW_ = 1, savedH_ = 1;
    float savedZoom_ = 1.f, savedPanX_ = 0.f, savedPanY_ = 0.f;
    float savedRotDeg_ = 0.f;
    bool savedFlipX_ = false, savedFlipY_ = false;
    std::vector<float> selQuads_;   // по 8 float на рамку

    GLuint progSolid_ = 0, progTex_ = 0, progBlend_ = 0;
    GLuint vbo_ = 0, vao_ = 0;
    GLuint gridVbo_ = 0, gridIbo_ = 0, gridVao_ = 0;
    GLsizei gridIndexCount_ = 0;
    GLint uTexWarp_ = -1, uTexOff_ = -1, uBlWarp_ = -1, uBlOff_ = -1;
    GLint uTexMesh_ = -1, uBlMesh_ = -1, uTexPersp_ = -1, uTexH_ = -1, uBlPersp_ = -1, uBlH_ = -1;
    std::unordered_map<int, std::array<float, 9>> persps_;
    GLint uTexTurb_ = -1, uBlTurb_ = -1;
    GLint uTexCrop_ = -1, uBlCrop_ = -1;   // спринт 45: обрезка слоя
    // --- рисование ---
    GLuint progDab_ = 0;
    GLint uDabSize_ = -1, uDabColor_ = -1, uDabHard_ = -1, uDabShape_ = -1, uDabErase_ = -1;
    GLint uDabRound_ = -1, uDabCurve_ = -1, uDabGrainMode_ = -1, uDabGrain_ = -1, uDabGrainScale_ = -1, uDabGrainContrast_ = -1, uDabGrainSize_ = -1;
    GLuint grainTex_[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    BrushPreset paintPreset_;
    bool paintInit();
    GLuint grainTexture(int kind, const uint8_t* bytes);
    void paintDrawDabs(int mode, const float color[3], const BrushPreset& preset, const float* dabs, int count, int w, int h);
    GLuint dabVao_ = 0, dabVbo_ = 0;
    GLuint strokeTex_ = 0, strokeFbo_ = 0, backupTex_ = 0, backupFbo_ = 0, layerFbo_ = 0;
    int paintW_ = 0, paintH_ = 0, paintLayer_ = -1, paintMode_ = 0;
    bool paintActive_ = false;
    struct PaintRead { GLuint pbo = 0; GLsync fence = nullptr; int layerId = 0, x = 0, y = 0, w = 0, h = 0; };
    std::deque<PaintRead> paintReads_;
    float paintOpacity_ = 1.f;
    bool paintAlphaLock_ = false;   // кисть рисует только по уже непрозрачным пикселям слоя
    float paintColor_[3] = {0.f, 0.f, 0.f};
    int pbx0_ = 0, pby0_ = 0, pbx1_ = 0, pby1_ = 0;   // охват штриха (пиксели слоя), x1/y1 не включены
    bool paintEnsureTargets(int w, int h);
    void paintBake(int x, int y, int w, int h);
    void paintFreeTargets();
    GLuint progFx_ = 0;
    GLint uFxMvp_ = -1, uFxTex_ = -1, uFxSize_ = -1, uFxCanvas_ = -1, uFxView_ = -1, uFxMode_ = -1, uFxP0_ = -1, uFxP1_ = -1;
    std::unordered_map<int, std::vector<std::array<float, 10>>> effects_;
    std::unordered_map<int, std::array<float, 4>> turbs_;
    const float* curTurb_ = nullptr;    // параметры турбулентности рисуемого слоя
    const float* curCrop_ = nullptr;    // спринт 45: рамка обрезки рисуемого слоя (null - нет)
    const float* curPersp_ = nullptr;   // гомография слоя, который рисуется сейчас
    std::unordered_map<int, std::array<float, 32>> warps_;
    struct MeshGeom {
        std::vector<float> verts;        // по 4 float: позиция (u+dx, v+dy) и текстурные координаты (u, v)
        std::vector<GLushort> idx;
    };
    std::unordered_map<int, MeshGeom> meshes_;
    const MeshGeom* curMesh_ = nullptr;  // сетка слоя, который рисуется сейчас
    GLuint meshVao_ = 0, meshVbo_ = 0, meshIbo_ = 0;
    GLint uSolidMvp_ = -1, uSolidColor_ = -1;
    GLint uTexMvp_ = -1, uTexOpacity_ = -1, uTexSampler_ = -1, uTexLod_ = -1, uBlLod_ = -1;
    GLint uBlMvp_ = -1, uBlOpacity_ = -1, uBlTex_ = -1, uBlBackdrop_ = -1, uBlMode_ = -1, uBlClip_ = -1;

    GLuint curFbo_ = 0;
    bool canvasScissorOn_ = false;     // экспорт: сцена обрезана кадром (AABB холста на экране)
    ScreenRect canvasScissor_{};
    FrameStats stats_{};
    std::vector<Fbo> fboPool_;                   // свободные FBO
    int fboCreated_ = 0;
    GLuint backdropTex_ = 0, backdropFbo_ = 0;
    // Кадр редактора рисуется сюда и в конце копируется на окно (из оконной поверхности ничего не читаем).
    GLuint editorTex_ = 0, editorFbo_ = 0;
    int editorW_ = 0, editorH_ = 0;
    bool ensureEditorTarget();
    void releaseEditorTarget();

    std::unordered_map<int, Tex> textures_;
    std::unordered_map<int, Tex> shadowTex_;
    // --- пакет 29: бюджет и вытеснение текстур ---
    size_t budget_ = texbudget::kDefaultBudget;
    int maxTexSize_ = 0;                          // GL_MAX_TEXTURE_SIZE (запрашивается один раз в initGL)
    std::unordered_set<int> evictable_;           // разрешено вытеснять (Kotlin восстановит)
    std::unordered_set<int> modifiedTex_;         // изменены на месте (кисть, Undo, замена) - не вытесняются никогда
    std::unordered_map<int, uint64_t> texUse_;    // когда текстура последний раз была в сцене
    uint64_t useClock_ = 0;
    std::vector<int> texEvents_;                  // [вид, id] для Kotlin
    void pushTexEvent(int kind, int id);
    // Освободить место под [need] байт вытеснением (не трогая [keepId]); false - места не будет.
    bool ensureRoom(size_t need, int keepId);
    bool keepCaptureAsTexture(GLuint srcFbo, int id, int w, int h);
    void evictTexture(int id);
    void markModified(int id) { modifiedTex_.insert(id); }
    bool useShadows_ = false;
    size_t textureBytes_ = 0;
    std::unordered_map<int, FxEntry> fxCache_;
    size_t fxCacheBytes_ = 0;
    std::unordered_map<uint64_t, GroupEntry> groupCache_;
    std::unordered_map<int, int> groupOcc_;      // появлений id группы в текущем кадре
    std::unordered_map<int, uint32_t> texGen_;   // версия содержимого текстуры слоя (правка на месте, замена, загрузка)
    uint32_t texSalt_ = 0;                       // смена всех текстур разом (новый контекст, смена проекта)
    uint64_t epoch_ = 1;                         // любая смена сцены/текстур/размера делает кэш эффектов устаревшим
    bool interactive_ = false;
    std::vector<Item> scene_;
};

}  // namespace vt
