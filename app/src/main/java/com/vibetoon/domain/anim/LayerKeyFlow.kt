package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Спринт 43: единый поток ключей слоя для волос и любых деформируемых слоёв (сетка + пины + кости + трансформация).
 * Требования с планшета:
 *  - первая правка на кадре > 0 добавляет недеформированный базовый ключ на кадре 0, если его нет;
 *  - правка на другом кадре фиксирует новый ключ - независимо от того, ставили ключ до правки или после;
 *  - кнопка «Add Key» ставит ключ слоя ЦЕЛИКОМ: трансформация и все деформеры слоя (вершины, пины, Warp, углы
 *    перспективы) с их текущими значениями - иначе «ключ до правки» не задавал начало изгиба и пряди начинали
 *    гнуться с кадра 0;
 *  - «Delete Key» и удаление выделенных ключей убирают ключи всех дорожек слоя на кадре; последняя форма деформера
 *    остаётся на месте (смещение добавляется к форме привязки, а не подменяет её).
 * Всё - чистые функции над неизменяемой [Animation]: Undo/Redo восстанавливает снимок целиком.
 */
object LayerKeyFlow {
    /**
     * Дорожки-смещения формы: значение дорожки ДОБАВЛЯЕТСЯ к форме привязки в base (вершины Mesh, точки Warp, углы
     * Perspective, пины). У трансформаций слоёв и параметров эффектов значение дорожки заменяет base.
     */
    fun isShapeDelta(id: Int): Boolean =
        (id >= WarpMath.ID_BASE && id < MeshMath.ID_BASE) ||
            (id >= MeshMath.ID_BASE && id < PerspectiveMath.ID_BASE) ||
            (id >= PerspectiveMath.ID_BASE && id < TurbMath.ID_BASE) ||
            PuppetMath.isPinId(id)

    /** Что положить в base, когда удалён последний ключ дорожки [id] со значением [last] (форма не прыгает). */
    fun baseAfterLastKey(id: Int, bind: LayerTransform?, last: LayerTransform): LayerTransform =
        if (isShapeDelta(id)) LayerTransform(dx = (bind?.dx ?: 0f) + last.dx, dy = (bind?.dy ?: 0f) + last.dy) else last

    /**
     * Деформеры слоя - группы дорожек, каждая ведёт себя как одно свойство ([DeformAutoKey]): вершины сетки
     * ([meshCount]), пины ([pinCount]), точки Warp ([warpPoints], 0 - Warp нет), углы перспективы ([perspective]).
     */
    fun deformerGroups(layerId: Int, meshCount: Int, pinCount: Int, warpPoints: Int, perspective: Boolean): List<List<Int>> {
        val out = ArrayList<List<Int>>(4)
        if (meshCount > 0) out += (0 until minOf(meshCount, MeshMath.MAX_VERTS)).map { MeshMath.vertexId(layerId, it) }
        if (pinCount > 0) out += (0 until minOf(pinCount, PuppetMath.MAX_PINS)).map { PuppetMath.pinId(layerId, it) }
        if (warpPoints > 0) out += (0 until minOf(warpPoints, WarpMath.POINTS)).map { WarpMath.pointId(layerId, it) }
        if (perspective) out += (0 until PerspectiveMath.CORNERS).map { PerspectiveMath.pointId(layerId, it) }
        return out
    }

    /**
     * Ключ деформера на кадре [frame] с текущими значениями всех его точек. Неанимированный деформер на кадре > 0
     * получает и базовый ключ исходной формы на кадре 0 (как автоключ первой правки).
     */
    fun keyDeformer(anim: Animation, group: List<Int>, frame: Int): Animation {
        if (group.isEmpty() || frame < 0) return anim
        val changes = LinkedHashMap<Int, LayerTransform>(group.size * 2)
        for (id in group) {
            if (anim.hasKey(id, frame)) continue
            val v = anim.tracks[id]?.evaluate(frame)
            changes[id] = LayerTransform(dx = v?.dx ?: 0f, dy = v?.dy ?: 0f)
        }
        return DeformAutoKey.key(anim, group, frame, changes)
    }

    /**
     * «Add Key» слоя: ключ трансформации [transform] (если на кадре его ещё нет; null - не ставить) и ключи всех
     * деформеров [groups] (ключ из текущего значения: картинка не прыгает).
     */
    fun addKey(anim: Animation, layerId: Int, frame: Int, transform: LayerTransform?, groups: List<List<Int>>): Animation {
        var a = anim
        if (transform != null && !a.hasKey(layerId, frame)) a = a.withKey(layerId, frame, transform)
        for (g in groups) a = keyDeformer(a, g, frame)
        return a
    }

    /** Есть ли у слоя ключ на кадре на любой его дорожке (так же показывает ромб таймлайна). */
    fun hasKey(anim: Animation, layerId: Int, frame: Int): Boolean =
        anim.hasKey(layerId, frame) || TrackGroups.ids(layerId, anim.tracks).any { anim.hasKey(it, frame) }

    /**
     * Удаляет ключи всех дорожек слоя на кадре. Второе значение - обновления base для дорожек, у которых это был
     * последний ключ ([baseAfterLastKey]).
     */
    fun removeKey(anim: Animation, layerId: Int, frame: Int, base: Map<Int, LayerTransform>): Pair<Animation, Map<Int, LayerTransform>> {
        var a = anim
        val updates = LinkedHashMap<Int, LayerTransform>()
        for (id in TrackGroups.ids(layerId, a.tracks)) {
            if (!a.hasKey(id, frame)) continue
            val (next, last) = a.withoutKey(id, frame)
            a = next
            if (last != null) updates[id] = baseAfterLastKey(id, base[id], last)
        }
        return a to updates
    }

    /**
     * Куда пишется правка трансформации жестом или числом. null - в base (свойство не анимировано, автоключ выключен).
     *  - автоключ включён -> ключ кадра (с базовым ключом на кадре 0 у первой правки);
     *  - свойство уже анимировано -> ключ на кадре правки: и правка существующего ключа, и НОВЫЙ ключ на другом кадре
     *    (раньше правка на кадре без ключа была временной и пропадала при перемотке, если не нажать Add Key);
     *  - [canKey] = false (ретайминг клипа: кадр проекта не совпадает с кадром анимации) - только существующий ключ.
     */
    fun transformEdit(anim: Animation, id: Int, frame: Int, before: LayerTransform, value: LayerTransform,
                      effects: List<Effect>, auto: Boolean, canKey: Boolean): Animation? = when {
        auto && canKey -> AutoKeyEdits.transform(anim, id, frame, before, value, effects)
        anim.hasKey(id, frame) -> anim.withKey(id, frame, value)
        anim.hasTrack(id) && canKey -> EffectTracks.snapshot(id, effects, anim.withKey(id, frame, value), frame)
        else -> null
    }

    /**
     * Смещение вершины (доли слоя), при котором ПОКАЗАННАЯ вершина встаёт под палец: показанная позиция = исходная +
     * своё смещение + добавка пинов/пути. Без вычитания добавки пинов вершина пряди с пинами отскакивала от пальца.
     */
    fun vertexTarget(finger: Float, rest: Float, shownOffset: Float, ownOffset: Float): Float =
        finger - rest - (shownOffset - ownOffset)
}

/**
 * Спринт 43: «Выбрать все ключи слоя» - все кадры с ключами ТОЛЬКО активного слоя [layerId] (любые его дорожки:
 * трансформация, деформеры, эффекты, путь; у символа - ключи времени [timeKeyFrames]; у кости - её дорожка), без
 * других слоёв. Ограничение диапазоном [range] (start включительно, end исключительно), если задано.
 */
object LayerKeySelection {
    fun of(layerId: Int, tracks: Map<Int, AnimationTrack>, isBone: Boolean, timeKeyFrames: Collection<Int>, range: IntRange? = null): Set<Pair<Int, Int>> {
        val frames = HashSet<Int>()
        if (isBone) tracks[layerId]?.keys?.mapTo(frames) { it.frame } else frames += TrackGroups.frames(layerId, tracks)
        frames += timeKeyFrames
        return frames.filter { range == null || it in range }.map { layerId to it }.toSet()
    }
}
