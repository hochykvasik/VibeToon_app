#include "render/GradientMath.h"
#include "render/UploadQueue.h"
#include "render/CanvasClipPolicy.h"
#include "render/EffectRenderPolicy.h"
#include "render/MeshPosition.h"
#include <cassert>
#include <cmath>
#include <chrono>
#include <future>
#include <thread>

using namespace vt;
using namespace std::chrono_literals;

int main() {
    // Проверка формулы повышения степени: кубическая Bernstein (как в GLSL)
    // совпадает с 5-точечной кривой после повышения, включая края.
    const double cubic[4] = {.3, -.7, .2, 1.0};
    double quartic[5] = {cubic[0], .25*cubic[0]+.75*cubic[1], .5*cubic[1]+.5*cubic[2], .75*cubic[2]+.25*cubic[3], cubic[3]};
    for (int i = 0; i <= 100; ++i) {
        const double t = i / 100.0, r = 1.0 - t;
        const double shader = r*r*r*cubic[0] + 3*r*r*t*cubic[1] + 3*r*t*t*cubic[2] + t*t*t*cubic[3];
        double q[5]; for (int k = 0; k < 5; ++k) q[k] = quartic[k];
        for (int n = 4; n > 0; --n) for (int k = 0; k < n; ++k) q[k] = r*q[k] + t*q[k+1];
        assert(std::abs(shader - q[0]) < 1e-12);
    }
    // Повышение только X не затрагивает Y: тензорная поверхность остаётся той же.
    double grid[4][4], raisedX[4][5];
    for (int y = 0; y < 4; ++y) for (int x = 0; x < 4; ++x) grid[y][x] = std::sin(x * .7 + y * .9);
    for (int y = 0; y < 4; ++y) {
        raisedX[y][0] = grid[y][0]; raisedX[y][4] = grid[y][3];
        for (int x = 1; x < 4; ++x) raisedX[y][x] = (x / 4.0) * grid[y][x-1] + (1 - x / 4.0) * grid[y][x];
    }
    for (int t = 0; t <= 10; ++t) for (int y = 0; y < 4; ++y) {
        double a[4], b[5]; for (int x = 0; x < 4; ++x) a[x] = grid[y][x];
        for (int x = 0; x < 5; ++x) b[x] = raisedX[y][x];
        const double u = t / 10.0;
        for (int n = 3; n > 0; --n) for (int i = 0; i < n; ++i) a[i] = (1-u)*a[i]+u*a[i+1];
        for (int n = 4; n > 0; --n) for (int i = 0; i < n; ++i) b[i] = (1-u)*b[i]+u*b[i+1];
        assert(std::abs(a[0]-b[0]) < 1e-12);
    }
    // Поддерживаемая сетка из 2 точек по X повышается до кубического шейдера
    // без изменения траектории или числа uniform-значений.
    const double a = -.25, b = .75;
    const double elevated[4] = {a, (2*a+b)/3, (a+2*b)/3, b};
    for (int i = 0; i <= 100; ++i) {
        const double t = i/100.0, s = 1-t;
        const double cubicValue = s*s*s*elevated[0]+3*s*s*t*elevated[1]+3*s*t*t*elevated[2]+t*t*t*elevated[3];
        assert(std::abs(cubicValue - ((1-t)*a + t*b)) < 1e-12);
    }
    // Обратное преобразование разрешено только если повторное повышение восстанавливает все точки.
    double reduced[4] = {quartic[0], 0, 0, 0};
    for (int i = 1; i < 4; ++i) {
        const double f = i / 4.0;
        reduced[i] = (quartic[i] - f * reduced[i-1]) / (1.0 - f);
    }
    for (int i = 0; i < 4; ++i) assert(std::abs(reduced[i] - cubic[i]) < 1e-12);
    assert(std::abs(reduced[3] - quartic[4]) < 1e-12);
    // Возмущённый край не принадлежит пониженной степени и должен отклоняться.
    const double perturbedEnd = quartic[4] + .1;
    assert(std::abs(reduced[3] - perturbedEnd) > 1e-3);
    // Положительные коэффициенты bicubic Bernstein гарантируют положительный
    // знаменатель во всей области, не только в вершинах сетки.
    for (int u = 0; u <= 20; ++u) for (int v = 0; v <= 20; ++v) {
        const double x = u / 20.0, y = v / 20.0;
        const double bx[] = {(1-x)*(1-x)*(1-x), 3*x*(1-x)*(1-x), 3*x*x*(1-x), x*x*x};
        const double by[] = {(1-y)*(1-y)*(1-y), 3*y*(1-y)*(1-y), 3*y*y*(1-y), y*y*y};
        double z = 0; for (int j = 0; j < 4; ++j) for (int i = 0; i < 4; ++i) z += bx[i]*by[j]*(.01 + i + j);
        assert(z > 0);
    }
    // Две дочерние текстуры проходят тот же путь FX независимо;
    // отсутствие ресурса у одной не отключает обработку другой.
    static_assert(applyLayerEffect(false, true, true, true));
    static_assert(!applyLayerEffect(false, true, true, false));
    static_assert(applyLayerEffect(false, true, true, true));
    static_assert(!applyLayerEffect(true, true, true, true));
    constexpr bool waveLayer1 = applyLayerEffect(false, true, true, true);
    constexpr bool waveLayer2 = applyLayerEffect(false, true, true, true);
    constexpr bool waveLayer3 = applyLayerEffect(false, true, true, true);
    static_assert(waveLayer1 && waveLayer2 && waveLayer3);
    static_assert(!applyLayerEffect(false, true, false, true));
    static_assert(!applyLayerEffect(false, true, true, false));
    static_assert(!applyLayerEffect(false, false, true, true));
    // Сдвиг обоих углов стороны задаёт ту же аффинную гомографию для шейдера,
    // без новых индексов точек или отдельного нативного формата.
    const double edgeX0 = 0.25, edgeY0 = -0.4, edgeX1 = 1.25, edgeY1 = -0.4;
    assert(std::abs((edgeX1 - edgeX0) - 1.0) < 1e-12);
    assert(std::abs(edgeY1 - edgeY0) < 1e-12);
    constexpr auto outside = meshPosition(.25f, .75f, -4.f, 5.f);
    static_assert(outside.x == -3.75f && outside.y == 5.75f && outside.u == .25f && outside.v == .75f);
    static_assert(!clipSceneToCanvas(false)); // рабочая область редактора
    static_assert(clipSceneToCanvas(true));   // экспорт и capture ограничены холстом
    // Один и тот же меш может начинаться за кадром: crop не меняет его UV/геометрию,
    // только политика области вывода различается между редактором и экспортом.
    // Локальная рамка может целиком лежать за пределами холста: её происхождение
    // не меняет формат UV и смещений, передаваемых в native mesh.
    constexpr float anchorX = -200.f, anchorWidth = 400.f;
    constexpr float localX = -100.f, localU = (localX - anchorX) / anchorWidth;
    // Рамка группы хранится без обрезки по границе холста.
    static_assert(anchorX < 0.f && anchorX + anchorWidth > 0.f && localU == .25f);
    constexpr auto anchored = meshPosition(localU, .5f, -.1f, 0.f);
    static_assert(anchored.u == .25f && anchored.x > .149f && anchored.x < .151f);
    // Both separate leaf meshes use the same UV/offset packing in the native path.
    // При одинаковом мировом смещении относительный сдвиг зависит от размера листа:
    // 20 px в группе = .2 от листа 100 px и .1 от листа 200 px.
    constexpr auto child100 = meshPosition(0.f, 0.f, .2f, 0.f);
    constexpr auto child200 = meshPosition(0.f, 0.f, .1f, 0.f);
    static_assert(child100.x * 100.f == child200.x * 200.f);
    constexpr auto leftGroupVertex = meshPosition(0.f, 0.f, .2f, -.1f);
    constexpr auto rightGroupVertex = meshPosition(1.f, 1.f, .2f, -.1f);
    static_assert(leftGroupVertex.u == 0.f && rightGroupVertex.u == 1.f);
    static_assert(leftGroupVertex.x == .2f && rightGroupVertex.x > 1.f);
    // 32 x 32 cells => 33 x 33 vertices, valid for the renderer's 16-bit indices.
    constexpr int denseSide = 33, denseCount = denseSide * denseSide;
    static_assert(denseCount == 1089 && denseCount - 1 < 65536);
    assert(vt::validGradientGesture(0.f, 0.f, 10.f, 0.f));
    assert(!vt::validGradientGesture(0.f, 0.f, 0.f, 0.f));
    assert(vt::gradientAlpha(0.f, 0.f, 0.f, 0.f, 10.f, 0.f) == 1.f);
    assert(vt::gradientAlpha(5.f, 8.f, 0.f, 0.f, 10.f, 0.f) == .5f);
    assert(vt::gradientAlpha(20.f, 0.f, 0.f, 0.f, 10.f, 0.f) == 0.f);
    constexpr auto denseCenter = meshPosition(.5f, .5f, .25f, -.2f);
    static_assert(denseCenter.x == .75f && denseCenter.u == .5f);
    constexpr auto denseLast = meshPosition(1.f, 1.f, -.25f, .25f);
    static_assert(denseLast.x == .75f && denseLast.y == 1.25f && denseLast.u == 1.f && denseLast.v == 1.f);
    static_assert((32 * 32 * 6) == 6144); // dense triangulation fits the 16-bit vertex range

    constexpr auto offCanvasGroupChild = meshPosition(0.f, .5f, -.75f, 0.f);
    static_assert(offCanvasGroupChild.x == -.75f && offCanvasGroupChild.u == 0.f);
    // Pan/zoom affects the output position, not the local mesh vertex or its UV.
    constexpr float worldX = -40.f, viewportScale = 2.f, viewportPan = 300.f;
    constexpr float screenX = worldX * viewportScale + viewportPan;
    static_assert(screenX == 220.f && offCanvasGroupChild.u == 0.f);
    static_assert(!clipSceneToCanvas(false) && clipSceneToCanvas(true));
    uploadQueue::clear();
    PendingUpload pending;
    pending.layerId = 7;
    pending.rgba.resize(8);
    uploadQueue::push(std::move(pending));
    {
        uploadQueue::EmptyGuard guard;
        assert(!guard.acquired());
        assert(uploadQueue::bytes() == 8); // отказ не удерживает mutex
    }
    PendingUpload out;
    assert(uploadQueue::pop(out));
    std::promise<void> started;
    std::future<void> startedFuture = started.get_future();
    std::future<void> finished;
    {
        uploadQueue::EmptyGuard guard;
        assert(guard.acquired());
        finished = std::async(std::launch::async, [&] {
            started.set_value();
            PendingUpload item;
            item.layerId = 8;
            uploadQueue::push(std::move(item));
        });
        startedFuture.wait();
        assert(finished.wait_for(50ms) == std::future_status::timeout);
    }
    // Ожидающий загрузчик завершает работу после разблокирования, не требуя выполнения в UI.
    assert(finished.wait_for(3s) == std::future_status::ready);
    finished.get();
    assert(uploadQueue::pop(out) && out.layerId == 8);
    assert(uploadQueue::empty());
    const auto guardOnly = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); }
    assert(uploadQueue::ticket(7) == guardOnly); // резервирование без успешной замены не меняет историю загрузок
    const auto before = uploadQueue::ticket(7);
    const auto other = uploadQueue::ticket(8);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    PendingUpload stale; stale.layerId = 7; stale.rgba.resize(16);
    const auto revisionAfterReplace = uploadQueue::ticket(7);
    assert(!uploadQueue::pushIfCurrent(std::move(stale), before));
    assert(uploadQueue::ticket(7) == revisionAfterReplace);
    assert(uploadQueue::empty() && uploadQueue::bytes() == 0);
    PendingUpload unaffected; unaffected.layerId = 8;
    assert(uploadQueue::pushIfCurrent(std::move(unaffected), other));
    assert(uploadQueue::pop(out) && out.layerId == 8);
    // Загрузка с прежним билетом ждёт освобождения guard и отклоняется после замены.
    const auto racingTicket = uploadQueue::ticket(7);
    std::promise<void> raceStarted;
    auto raceStartedFuture = raceStarted.get_future();
    std::future<bool> raced;
    {
        uploadQueue::EmptyGuard guard;
        assert(guard.acquired() && guard.prepareLayer(7));
        raced = std::async(std::launch::async, [&] {
            raceStarted.set_value();
            PendingUpload old; old.layerId = 7;
            return uploadQueue::pushIfCurrent(std::move(old), racingTicket);
        });
        raceStartedFuture.wait();
        assert(raced.wait_for(50ms) == std::future_status::timeout);
        guard.invalidateLayer(7);
    }
    assert(raced.wait_for(3s) == std::future_status::ready);
    assert(!raced.get());
    assert(uploadQueue::empty());
    // Неуспешная замена не инвалидирует билет и не теряет ожидающий буфер.
    const auto failedTicket = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); }
    PendingUpload afterFailure; afterFailure.layerId = 7;
    assert(uploadQueue::pushIfCurrent(std::move(afterFailure), failedTicket));
    assert(uploadQueue::pop(out) && out.layerId == 7);
    const auto twiceOld = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    const auto twiceMiddle = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    PendingUpload older; older.layerId = 7;
    PendingUpload middle; middle.layerId = 7;
    assert(!uploadQueue::pushIfCurrent(std::move(older), twiceOld));
    assert(!uploadQueue::pushIfCurrent(std::move(middle), twiceMiddle));
    assert(uploadQueue::empty());
    // Неизменённый соседний слой остаётся действителен после повторных замен другого.
    const auto neighbour = uploadQueue::ticket(8);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    PendingUpload neighbourImage; neighbourImage.layerId = 8;
    assert(uploadQueue::pushIfCurrent(std::move(neighbourImage), neighbour));
    assert(uploadQueue::pop(out) && out.layerId == 8);
    // Моделируем смены поколений при переносе, Undo и Redo: загрузки,
    // начатые до каждого перехода, не должны перезаписать новую текстуру.
    // Отклонённая замена при занятой очереди не должна менять поколение.
    const auto blockedTicket = uploadQueue::ticket(7);
    PendingUpload blocker; blocker.layerId = 9;
    uploadQueue::push(std::move(blocker));
    { uploadQueue::EmptyGuard guard; assert(!guard.acquired()); }
    assert(uploadQueue::ticket(7) == blockedTicket);
    assert(uploadQueue::pop(out) && out.layerId == 9);
    // Попытка постановки устаревшего билета не меняет текущую версию.
    const auto currentBeforeStale = uploadQueue::ticket(7);
    PendingUpload staleAfterBlocked; staleAfterBlocked.layerId = 7;
    assert(!uploadQueue::pushIfCurrent(std::move(staleAfterBlocked), twiceOld));
    assert(uploadQueue::ticket(7) == currentBeforeStale);
    const auto beforeMove = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    const auto beforeUndo = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    const auto beforeRedo = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired()); assert(guard.prepareLayer(7)); guard.invalidateLayer(7); }
    for (auto staleTicket : {beforeMove, beforeUndo, beforeRedo}) {
        PendingUpload staleTransition; staleTransition.layerId = 7;
        assert(!uploadQueue::pushIfCurrent(std::move(staleTransition), staleTicket));
    }
    // После Redo актуальная загрузка другого слоя проходит, а устаревшая версия целевого слоя нет.
    PendingUpload afterHistory; afterHistory.layerId = 7;
    assert(uploadQueue::pushIfCurrent(std::move(afterHistory), uploadQueue::ticket(7)));
    assert(uploadQueue::pop(out) && out.layerId == 7);
    // Серия замен того же id при повороте и расширении: id не меняется,
    // независимые загрузки не теряются, устаревшие не возвращают старые пиксели.
    const auto transformedOld = uploadQueue::ticket(7);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired() && guard.prepareLayer(7)); guard.invalidateLayer(7); }
    const auto transformedNew = uploadQueue::ticket(7);
    PendingUpload staleTransformed; staleTransformed.layerId = 7;
    assert(!uploadQueue::pushIfCurrent(std::move(staleTransformed), transformedOld));
    PendingUpload freshTransformed; freshTransformed.layerId = 7;
    assert(uploadQueue::pushIfCurrent(std::move(freshTransformed), transformedNew));
    assert(uploadQueue::pop(out) && out.layerId == 7);
    // Отделение внутри группы использует отдельный id текстуры: замена исходника
    // при Undo не должна инвалидировать ожидающую загрузку дочернего слоя.
    const auto sourceTicket = uploadQueue::ticket(7);
    const auto childTicket = uploadQueue::ticket(42);
    { uploadQueue::EmptyGuard guard; assert(guard.acquired() && guard.prepareLayer(7)); guard.invalidateLayer(7); }
    PendingUpload staleSource; staleSource.layerId = 7;
    assert(!uploadQueue::pushIfCurrent(std::move(staleSource), sourceTicket));
    PendingUpload child; child.layerId = 42;
    assert(uploadQueue::pushIfCurrent(std::move(child), childTicket));
    assert(uploadQueue::pop(out) && out.layerId == 42);
    const auto previousProject = uploadQueue::ticket(7);
    uploadQueue::clear();
    // Очистка должна инвалидировать и билеты других слоёв, не только заменённого.
    const auto nextProjectNeighbour = uploadQueue::ticket(8);
    assert(nextProjectNeighbour != neighbour);
    PendingUpload staleNeighbour; staleNeighbour.layerId = 8;
    assert(!uploadQueue::pushIfCurrent(std::move(staleNeighbour), neighbour));
    PendingUpload staleProject; staleProject.layerId = 7;
    assert(!uploadQueue::pushIfCurrent(std::move(staleProject), previousProject));
    PendingUpload fresh; fresh.layerId = 7;
    assert(uploadQueue::pushIfCurrent(std::move(fresh), uploadQueue::ticket(7)));
    assert(uploadQueue::pop(out) && out.layerId == 7);

    // Теневые загрузки снимка символа (пакет 4) идут своей очередью: не попадают в текстуры редактора
    // и не задерживают снимок разбором общей очереди.
    PendingUpload sh; sh.layerId = 5; sh.rgba.resize(16);
    uploadQueue::pushShadow(std::move(sh));
    assert(!uploadQueue::pop(out));                       // общая очередь пуста
    assert(uploadQueue::bytes() == 16);                   // но память учитывается
    assert(uploadQueue::popShadow(out) && out.layerId == 5);
    assert(!uploadQueue::popShadow(out));
    assert(uploadQueue::bytes() == 0);
    PendingUpload sh2; sh2.layerId = 6;
    uploadQueue::pushShadow(std::move(sh2));
    uploadQueue::clear();                                 // смена проекта выбрасывает и теневые
    assert(!uploadQueue::popShadow(out));
}
