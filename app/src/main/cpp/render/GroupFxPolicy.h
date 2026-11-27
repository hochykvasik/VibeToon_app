#pragma once

// Спринт 50: шейдерные эффекты у группы целиком. Содержимое группы собирается в свой буфер (как для режима наложения),
// при закрытии группы цепочка её эффектов идёт над этим буфером, затем результат накладывается на родителя.
// Кэш поддерева такой группы не используется (эффекты зависят от времени), а хэш родительских групп учитывает эффекты
// вложенных групп - иначе кэш родителя показал бы застывший кадр.
namespace vt::groupfx {
constexpr int kMaskSrc = 4;   // как maskpolicy::kMaskSrc
// Флаг начала настоящей группы с эффектами (SceneBuilder.FLAG_GROUP_FX). Обёртки слоя под маской тоже открываются
// kBeginGroup с id слоя - без этого флага эффекты слоя применились бы второй раз.
constexpr int kGroupFx = 32;

// Применяются ли эффекты при закрытии группы: только у группы с флагом, не у прохода маски и при готовой программе.
constexpr bool applies(int groupFlags, bool hasEffects, bool hasProgram) {
    return hasEffects && hasProgram && (groupFlags & kGroupFx) != 0 && (groupFlags & kMaskSrc) == 0;
}
// Нужен ли группе свой буфер: эффекты требуют буфер всегда.
constexpr bool isolated(bool baseIsolated, bool effects) { return baseIsolated || effects; }
// Можно ли брать группу из кэша поддерева.
constexpr bool cacheable(bool baseCacheable, bool effects) { return baseCacheable && !effects; }
// Учитывать ли эффекты узла в хэше поддерева: и у слоёв, и у вложенных групп (op: 0 слой, 1 начало группы, 2 конец).
constexpr bool hashEffects(int op) { return op == 0 || op == 1; }
}  // namespace vt::groupfx
