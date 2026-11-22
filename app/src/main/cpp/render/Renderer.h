// Рендерер. Все методы вызываются ТОЛЬКО из GL-потока (потока GLSurfaceView).
// Слои-текстуры, изоляция групп через FBO, Multiply/Screen в шейдере, clipping-маски.
#pragma once
#include <GLES3/gl3.h>

#include "ViewMath.h"

#include <array>
#include <cstdint>
#include <deque>
#include <unordered_map>
#include <vector>

namespace vt {

class Renderer {
public:
    // Вызывать из onSurfaceCreated. Возвращает GL_MAX_TEXTURE_SIZE или -1 при ошибке.
    int initGL();
    void setViewport(int width, int height);
    // Размер холста PSD в пикселях (координаты слоёв задаются в этой системе).
    void setCanvas(int width, int height);
    // Поток операций снизу вверх. ops: n*4 int [op, layerId, blend, flags];
    // params: n*8 float [a, b, c, d, tx, ty, opacity, 0]: аффинное преобразование
    // "единичный квадрат -> холст" (canvas = (a*u + c*v + tx, b*u + d*v + ty)).
    void setScene(const int* ops, const float* params, int n);
    // Камера вида: zoom, pan (пиксели экрана), поворот вида (радианы, по часовой) и отражения (Flip Canvas).
    // Центр холста стоит в (центр экрана + pan); поворот и отражение - вокруг него. Экспорт их не видит.
    void setCamera(float zoom, float panX, float panY, float rotation = 0.f, bool flipX = false, bool flipY = false);
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
    // Рисует кадр. Возвращает true, если нужен ещё кадр (остались загрузки в очереди).
    bool drawFrame();
    // Экспорт: холст на весь кадр width x height (без полей, камеры и рамки выделения).
    // Вызывать с контекстом, в котором целью служит поверхность кодека. endExport возвращает вид.
    void beginExport(int width, int height);
    void endExport();
    // Снимок кадра в память: холст w x h (без камеры и рамки) через внутренний FBO; RGBA premultiplied, строка 0 - низ.
    // transparent: без фона холста (PNG/GIF с прозрачностью). Вызывать с текущим GL-контекстом.
    bool captureFrame(int w, int h, bool transparent, std::vector<uint8_t>& out);
    // Загружает всю очередь текстур (до maxRounds порций по kUploadBytesPerFrame). true - очередь пуста.
    bool drainUploads(int maxRounds = 64);
    // Кадр «целиком»: сначала все ожидающие текстуры, потом одна отрисовка (экспорт, снимки).
    void drawFrameComplete() { drainUploads(); drawFrame(); }
    // Предел стороны внеэкранного снимка: GL_MAX_TEXTURE_SIZE устройства, но не больше kMaxCaptureSide.
    int maxCaptureSide() const;
    // Цвет пикселя экрана (ARGB) после drawFrame: для пипетки. Координаты вида, ось y вниз.
    int readPixel(int x, int y) const;
    // Память текстур: {занято МБ, бюджет МБ, уменьшено из-за бюджета, не загружено из-за бюджета}.
    void textureStats(int out[4]) const;
    // Освобождает GL-объекты (контекст должен быть текущим).
    void release();

private:
    enum Op { kLayer = 0, kBeginGroup = 1, kEndGroup = 2 };
    enum Blend { kNormal = 0, kMultiply = 1, kScreen = 2, kOverlay = 3, kPass = 4, kSoftLight = 5, kAdd = 6, kDarken = 7, kLighten = 8, kColorDodge = 9, kColorBurn = 10 };
    static constexpr int kFlagClipped = 1;
    static constexpr int kFlagHitBbox = 2;   // hit-test по всей рамке (текст)

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
    };

    void uploadPending();                        // не более kUploadBytesPerFrame за кадр
    void deleteLayerTexture(int layerId);        // безопасное удаление: сначала отцепляется от FBO рисования
    void detachPaintTarget();                    // layerFbo_ больше не ссылается на текстуру слоя
    ViewAffine view() const;                     // холст -> экран с камерой (поворот и отражения тоже)
    void drawOutsideCanvas();                    // при повороте: закрашивает всё, что вылезло за холст
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

    int width_ = 1, height_ = 1;
    int canvasW_ = 1, canvasH_ = 1;
    float zoom_ = 1.f, panX_ = 0.f, panY_ = 0.f, rot_ = 0.f;
    bool flipX_ = false, flipY_ = false;
    float bg_[4] = {0.85f, 0.85f, 0.85f, 1.f};
    bool exportMode_ = false;
    GLuint screenFbo_ = 0;            // куда рисует drawFrame (0 - экран; при снимке - внутренний FBO)
    bool transparentCapture_ = false;
    int savedW_ = 1, savedH_ = 1;
    float savedZoom_ = 1.f, savedPanX_ = 0.f, savedPanY_ = 0.f, savedRot_ = 0.f;
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
    GLint uFxMvp_ = -1, uFxTex_ = -1, uFxSize_ = -1, uFxCanvas_ = -1, uFxView_ = -1, uFxLin_ = -1, uFxMode_ = -1, uFxP0_ = -1, uFxP1_ = -1;
    std::unordered_map<int, std::vector<std::array<float, 10>>> effects_;
    std::unordered_map<int, std::array<float, 4>> turbs_;
    const float* curTurb_ = nullptr;    // параметры турбулентности рисуемого слоя
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
    GLint uTexMvp_ = -1, uTexOpacity_ = -1, uTexSampler_ = -1;
    GLint uBlMvp_ = -1, uBlOpacity_ = -1, uBlTex_ = -1, uBlBackdrop_ = -1, uBlMode_ = -1, uBlClip_ = -1;

    GLuint curFbo_ = 0;
    std::vector<Fbo> fboPool_;                   // свободные FBO
    int fboCreated_ = 0;
    GLuint backdropTex_ = 0, backdropFbo_ = 0;
    int backdropW_ = 0, backdropH_ = 0;
    int budgetReduced_ = 0, budgetSkipped_ = 0;   // счётчики бюджета текстур (для предупреждения в UI)   // размер подложки (пересоздаётся при смене размера цели)
    // На время экспорта/снимка цели экранного размера откладываются (а не удаляются и создаются заново):
    // меньше create/delete имён GL, после снимка экран рисуется теми же буферами.
    std::vector<Fbo> stashPool_;
    int stashCreated_ = 0;
    GLuint stashBackdropTex_ = 0, stashBackdropFbo_ = 0;
    int stashBackdropW_ = 0, stashBackdropH_ = 0;
    bool stashed_ = false;
    // Постоянная цель снимков (пересоздаётся только при смене размера).
    GLuint captureTex_ = 0, captureFbo_ = 0;
    int captureW_ = 0, captureH_ = 0;
    int maxTex_ = 4096;

    std::unordered_map<int, Tex> textures_;
    size_t textureBytes_ = 0;
    std::vector<Item> scene_;
};

}  // namespace vt
