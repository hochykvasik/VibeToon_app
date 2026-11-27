package com.vibetoon.domain.anim

import com.vibetoon.domain.render.LayerTransform

/**
 * Спринт 41: автоключи деформации «как в Alight Motion». Деформер слоя (все вершины сетки, точки Warp, углы
 * перспективы или пины) ведёт себя как ОДНО анимируемое свойство:
 *  - первая правка на кадре > 0, когда у деформера ещё нет ни одного ключа, сначала ставит ключ исходной
 *    (недеформированной) формы на кадре 0 - иначе единственный ключ держался бы на всех кадрах и анимации не было;
 *  - у уже анимированных точек на кадре правки появляется ключ с их текущим значением: форма на этом кадре
 *    фиксируется целиком, а не только у задетых точек;
 *  - точка, которую правят впервые, получает ключи прежнего значения на всех кадрах деформера и новое значение
 *    на кадре правки.
 * Значения точек - дельты к форме привязки (как в MainViewModel); у точки без дорожки дельта нулевая.
 * Плавность новых ключей берётся у соседних точек на том же кадре, чтобы деформер не «рассыпался» по кривым.
 */
object DeformAutoKey {
    fun key(anim: Animation, group: Collection<Int>, frame: Int, changes: Map<Int, LayerTransform>): Animation {
        if (changes.isEmpty() || frame < 0) return anim
        val tracks = HashMap(anim.tracks)
        val tracked = group.filter { anim.hasTrack(it) }
        // кадры деформера и плавность ключей на каждом кадре (по первой найденной точке)
        val frames = sortedSetOf<Int>()
        val styles = HashMap<Int, Keyframe>()
        for (id in tracked) for (k in anim.tracks.getValue(id).keys) { frames += k.frame; styles.putIfAbsent(k.frame, k) }
        if (frames.isEmpty() && frame > 0) frames += 0   // исходная форма на кадре 0

        fun put(id: Int, f: Int, value: LayerTransform) {
            val track = tracks[id] ?: AnimationTrack(id)
            val old = track.keyAt(f)
            val style = old ?: styles[f]
            tracks[id] = track.upsert(Keyframe.of(f, value, style?.easing ?: EasingType.LINEAR).copy(curve = style?.curve))
        }

        // уже анимированные и не задетые точки: значение на кадре фиксируется ключом
        for (id in tracked) if (id !in changes && !anim.hasKey(id, frame))
            put(id, frame, anim.tracks.getValue(id).evaluate(frame) ?: LayerTransform())
        for ((id, value) in changes) {
            if (!anim.hasTrack(id)) for (f in frames) if (f != frame) put(id, f, LayerTransform())
            put(id, frame, value)
        }
        return anim.copy(tracks = tracks)
    }

    /** Есть ли у деформера хотя бы один ключ. */
    fun animated(anim: Animation, group: Collection<Int>): Boolean = group.any { anim.hasTrack(it) }
}

/**
 * Спринт 41: ключи нескольких костей одним действием. Цепочка кости - все её предки-кости и все потомки-кости
 * (например, плечо → предплечье → кисть), чтобы поза руки фиксировалась на кадре целиком.
 */
object BoneChainKeys {
    /** Кости цепочки [boneId] в порядке «от корня»: предки, сама кость, потомки (обход в ширину). [links]: ребёнок -> родитель. */
    fun chain(boneId: Int, links: Map<Int, Int>, boneIds: Collection<Int>): List<Int> {
        val bones = boneIds.toHashSet()
        if (boneId !in bones) return emptyList()
        val up = ArrayList<Int>()
        var p = links[boneId]
        val seen = hashSetOf(boneId)
        while (p != null && p in bones && seen.add(p)) { up.add(0, p); p = links[p] }
        val down = ArrayList<Int>()
        val queue = ArrayDeque(listOf(boneId))
        val children = HashMap<Int, MutableList<Int>>()
        for ((c, parent) in links) if (c in bones && parent in bones) children.getOrPut(parent) { ArrayList() }.add(c)
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            for (c in children[b].orEmpty().sorted()) if (seen.add(c)) { down += c; queue += c }
        }
        return up + boneId + down
    }

    /**
     * Кости, которым ставится ключ вместе с [primary]: сама кость, другие выбранные кости [selected] и, если [chain],
     * вся её цепочка. Порядок: [primary], выбранные, цепочка; без повторов; только кости из [boneIds].
     */
    fun targets(primary: Int, selected: Collection<Int>, links: Map<Int, Int>, boneIds: Collection<Int>, chain: Boolean): List<Int> {
        val bones = boneIds.toHashSet()
        if (primary !in bones) return emptyList()
        val out = LinkedHashSet<Int>()
        out += primary
        for (id in selected) if (id in bones) out += id
        if (chain) out += chain(primary, links, bones)
        return out.toList()
    }

    /**
     * Ключи костей [ids] на кадре [frame] из их текущих поз [pose]. Кость без дорожки на кадре > 0 сначала получает
     * ключ своей прежней позы на кадре 0 (как автоключ), чтобы предыдущие кадры не менялись.
     */
    fun keyAll(anim: Animation, ids: Collection<Int>, frame: Int, pose: (Int) -> LayerTransform, before: (Int) -> LayerTransform = pose): Animation {
        var a = anim
        for (id in ids.distinct()) {
            if (frame > 0 && !a.hasTrack(id)) a = a.withKey(id, 0, before(id))
            a = a.withKey(id, frame, pose(id))
        }
        return a
    }
}
