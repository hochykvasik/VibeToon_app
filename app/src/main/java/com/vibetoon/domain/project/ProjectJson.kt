package com.vibetoon.domain.project

import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.Keyframe
import com.vibetoon.domain.render.LayerTransform

/**
 * Формат project.json (версия 1):
 * { format, version, id, name, width, height, fps, duration, background:"#AARRGGBB", createdAt, updatedAt,
 *   psd?:{file,name,width,height}, base:{"<id>":{dx,dy,sx,sy,rot,op}},
 *   tracks:{"<id>":[{f,dx,dy,sx,sy,rot,op,ease}]} }
 * Неизвестные и отсутствующие поля не ломают чтение; версия новее известной - ошибка.
 */
object ProjectJson {
    const val VERSION = 8
    private const val LEGACY_VERSION = 1
    const val FORMAT = "vibetoon-project"

    fun encode(d: ProjectData): String {
        val m = d.meta
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT
        // Старые сборки обязаны отказаться от открытия, а не молча удалить новый формат.
        root["version"] = if (d.stackOrder.isNotEmpty()) 8 else if (com.vibetoon.domain.anim.SceneCanvas.active(d.scenes)) 7 else if (d.scenes.isNotEmpty()) 6 else if (d.pivots.isNotEmpty()) 5 else if (d.denseWarps.isNotEmpty()) 4 else if (d.warpGridSizes.isNotEmpty()) 3 else if (d.groupDeformationAnchors.isNotEmpty()) 2 else LEGACY_VERSION
        root["id"] = m.id
        root["name"] = m.name
        root["width"] = m.width
        root["height"] = m.height
        root["fps"] = m.fps
        root["duration"] = m.durationFrames
        root["background"] = colorToString(m.background)
        root["createdAt"] = m.createdAt
        root["updatedAt"] = m.updatedAt
        if (!m.loop) root["loop"] = false
        if (m.magnet) root["magnet"] = true
        if (m.folder.isNotEmpty()) root["folder"] = m.folder
        d.psd?.let {
            root["psd"] = linkedMapOf("file" to it.file, "name" to it.name, "width" to it.width, "height" to it.height)
        }
        root["media"] = d.media.map(::mediaToMap)
        if (d.deleted.isNotEmpty()) root["deleted"] = d.deleted.sorted()
        if (d.markers.isNotEmpty()) root["markers"] = d.markers.sorted()
        if (d.clips.isNotEmpty()) root["clips"] = d.clips.entries.associate { (id, c) -> id.toString() to clipToMap(c) }
        if (d.links.isNotEmpty()) root["links"] = d.links.entries.associate { (c, par) -> c.toString() to par }
        if (d.bones.isNotEmpty()) root["bones"] = d.bones.map {
            linkedMapOf<String, Any?>("id" to it.id, "name" to it.name, "hx" to it.hx.toDouble(), "hy" to it.hy.toDouble(), "tx" to it.tx.toDouble(), "ty" to it.ty.toDouble(), "indep" to it.independentRot, "str" to it.strength.toDouble()).also { m -> if (it.layer != 0) m["ly"] = it.layer }   // спринт 38: слой костей
        }
        if (d.regroup.isNotEmpty()) root["regroup"] = d.regroup.map { listOf(it.first, it.second) }
        if (d.warps.isNotEmpty()) root["warps"] = d.warps.sorted()
        if (d.persps.isNotEmpty()) root["persps"] = d.persps.sorted()
        if (d.warpGridSizes.isNotEmpty()) {
            require(d.warpGridSizes.all { (id, size) -> id in d.warps && id >= 0 && size.valid() && size != com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT })
            root["warpGridSizes"] = d.warpGridSizes.toSortedMap().mapKeys { it.key.toString() }.mapValues { (_, size) -> listOf(size.width, size.height) }
        }
        if (d.denseWarps.isNotEmpty()) {
            require(d.denseWarps.all { (id, g) -> id >= 0 && id in d.warps && id !in d.groupDeformationAnchors && g.valid() })
            root["denseWarps"] = d.denseWarps.toSortedMap().mapKeys { it.key.toString() }.mapValues { (_, g) ->
                linkedMapOf("w" to g.width, "h" to g.height, "p" to g.offsets.map { it.toDouble() })
            }
        }
        if (d.groupDeformationAnchors.isNotEmpty()) {
            require(d.groupDeformationAnchors.all { (id, anchor) -> id >= 0 && anchor.valid() }) { "Invalid group deformation anchor" }
            root["groupDeformationAnchors"] = d.groupDeformationAnchors.toSortedMap().mapKeys { it.key.toString() }.mapValues { (_, a) ->
                listOf(a.x.toDouble(), a.y.toDouble(), a.width.toDouble(), a.height.toDouble())
            }
        }
        if (d.scenes.isNotEmpty()) root["scenes"] = d.scenes.map {
            linkedMapOf<String, Any?>("id" to it.id, "n" to it.name, "s" to it.start, "e" to it.end).apply {
                // пакет 24: свои слои сцены и переход в неё (поля только при использовании - иначе файл остаётся v6)
                if (it.layers.isNotEmpty()) put("ly", it.layers.sorted())
                if (it.transition != com.vibetoon.domain.anim.SceneTransition.CUT.key) { put("tr", it.transition); put("tf", it.transitionFrames) }
                it.background?.let { c -> put("bg", c.toLong() and 0xFFFFFFFFL) }
            }
        }
        if (d.stackOrder.isNotEmpty()) {
            // пакет 25: смешанный порядок PSD и медиа - дети группы снизу вверх
            require(d.stackOrder.keys.all { it >= -1 } && d.stackOrder.values.all { l -> l.all { it >= 0 } }) { "Invalid stack order" }
            root["order"] = d.stackOrder.toSortedMap().mapKeys { it.key.toString() }.mapValues { it.value }
        }
        if (d.pivots.isNotEmpty()) {
            require(d.pivots.all { (id, p) -> id >= 0 && p.first.isFinite() && p.second.isFinite() }) { "Invalid pivot" }
            root["pivots"] = d.pivots.toSortedMap().mapKeys { it.key.toString() }.mapValues { (_, p) -> listOf(p.first.toDouble(), p.second.toDouble()) }
        }
        if (d.pins.isNotEmpty()) root["pins"] = d.pins.entries.associate { (id, l) ->
            id.toString() to l.map { p -> linkedMapOf<String, Any?>("u" to p.u.toDouble(), "v" to p.v.toDouble(), "s" to p.stiff, "r" to p.radius.toDouble()) }
        }
        if (d.fx.isNotEmpty()) root["fx"] = d.fx.entries.associate { (id, l) ->
            id.toString() to l.map { e -> linkedMapOf<String, Any?>("t" to e.type, "p" to e.p.map { it.toDouble() }).also { if (e.slot >= 0) it["slot"] = e.slot } }
        }
        if (d.turbs.isNotEmpty()) root["turbs"] = d.turbs.entries.associate { (id, t) ->
            id.toString() to linkedMapOf<String, Any?>("i" to t.intensity.toDouble(), "s" to t.size.toDouble(), "c" to t.complexity, "b" to t.boil)
        }
        if (d.layerProps.isNotEmpty()) root["lprops"] = d.layerProps.entries.associate { (id, p) ->
            id.toString() to linkedMapOf<String, Any?>("o" to p.opacity.toDouble(), "b" to p.blend, "l" to p.alphaLock, "c" to p.clip).also { m ->
                if (p.exposure != 0f) m["ex"] = p.exposure.toDouble(); if (p.contrast != 0f) m["ct"] = p.contrast.toDouble()
                if (p.saturation != 0f) m["sa"] = p.saturation.toDouble(); if (p.hue != 0f) m["hu"] = p.hue.toDouble()
                if (!p.visible) m["vis"] = false
                if (p.locked) m["lk"] = true
                if (p.name != null) m["n"] = p.name
                if (p.depth != 0f) m["dp"] = p.depth.toDouble()
                // спринт 38: маска слоя и прикрепление к пути (поля только при использовании)
                if (p.mask >= 0) { m["mk"] = p.mask; if (p.maskInvert) m["mi"] = true; if (p.maskOff) m["mo"] = true }
                if (p.path >= 0) { m["pa"] = p.path; if (p.pathOrient) m["po"] = true }
                p.crop?.let { c -> m["cr"] = c.map { it.toDouble() } }   // спринт 45: обрезка слоя
            }
        }
        if (d.skins.isNotEmpty()) root["skins"] = d.skins.entries.associate { (id, m) ->
            id.toString() to m.entries.associate { (b, s) -> b.toString() to s.toDouble() }
        }
        if (d.meshes.isNotEmpty()) root["meshes"] = d.meshes.entries.associate { (id, m) ->
            id.toString() to linkedMapOf<String, Any?>("v" to m.verts.map { it.toDouble() }, "t" to m.tris.toList())
        }
        root["base"] = d.base.entries.associate { (id, t) -> id.toString() to transformToMap(t) }
        root["tracks"] = d.tracks.entries.associate { (id, track) -> id.toString() to track.keys.map(::keyToMap) }
        return MiniJson.stringify(root)
    }

    /** Заголовок проекта из полей верхнего уровня (общий для [decode] и [decodeMeta]). */
    private fun metaOf(root: Map<String, Any?>): ProjectMeta {
        val id = root["id"] as? String ?: throw JsonException("Нет id проекта")
        return ProjectMeta(
            id = id,
            name = root.str("name", "Проект"),
            width = root.int("width", 1080).coerceAtLeast(1),
            height = root.int("height", 1080).coerceAtLeast(1),
            fps = com.vibetoon.domain.anim.FrameRates.clamp(root.int("fps", 24)),   // спринт 32: 1–120, как в редакторе
            durationFrames = root.int("duration", 120).coerceAtLeast(1),
            background = parseColor(root.str("background", "#00000000")),
            createdAt = root.long("createdAt", 0L),
            updatedAt = root.long("updatedAt", 0L),
            loop = root["loop"] as? Boolean ?: true,
            magnet = root["magnet"] as? Boolean ?: false,
            folder = root["folder"] as? String ?: ""
        )
    }

    /** Поля заголовка проекта - все они лежат на верхнем уровне project.json. */
    val META_KEYS = setOf("format", "version", "id", "name", "width", "height", "fps", "duration", "background",
        "createdAt", "updatedAt", "loop", "magnet", "folder")

    /**
     * Спринт 52: только заголовок проекта (для экрана проектов). Разбираются лишь поля верхнего уровня из [META_KEYS];
     * слои, ключи и клипы пропускаются без построения объектов - у большого старого проекта это в разы быстрее
     * [decode]. Структура файла при этом проверяется целиком, версия новее известной - ошибка, как в [decode].
     */
    fun decodeMeta(text: String): Result<ProjectMeta> = try {
        val head = HashMap<String, Any?>()
        for (m in MiniJson.topLevel(text)) if (m.key in META_KEYS) head[m.key] = MiniJson.parse(text.substring(m.valueStart, m.valueEnd))
        val version = head.int("version", 1)
        if (version > VERSION) Result.failure(IllegalStateException("Проект создан в более новой версии приложения (v$version)"))
        else Result.success(metaOf(head))
    } catch (e: JsonException) {
        Result.failure(IllegalStateException("Повреждённый файл проекта: ${e.message}"))
    } catch (e: RuntimeException) {
        // спринт 61: синтаксически верный, но несогласованный файл (не те типы, числа вне диапазона) - ошибка открытия, а не вылет
        Result.failure(IllegalStateException("Повреждённый файл проекта: Неверные данные"))
    }

    /**
     * Спринт 52: правка полей заголовка прямо в тексте project.json (папка, имя, время изменения) - без разбора и
     * повторной записи всего проекта. [values]: ключ из [META_KEYS] -> новое значение (String, Number, Boolean); null -
     * удалить поле. Остальной текст не меняется байт в байт. null - структура не распознана (вызывающий делает полную
     * перезапись через [decode]/[encode]).
     */
    fun withTopLevel(text: String, values: Map<String, Any?>): String? {
        if (values.keys.any { it !in META_KEYS || it == "id" || it == "format" || it == "version" }) return null
        return try {
            var t = text
            for ((key, value) in values) {
                val ms = MiniJson.topLevel(t)
                val pretty = ms.firstOrNull()?.let { t.getOrNull(it.keyStart - 1) == ' ' } ?: true
                val m = ms.firstOrNull { it.key == key }
                val enc = if (value == null) null else MiniJson.stringify(value, pretty = false)
                t = when {
                    m != null && enc != null -> t.substring(0, m.valueStart) + enc + t.substring(m.valueEnd)
                    m != null -> {
                        // поле удаляется вместе с запятой перед ним (или после него, если оно первое)
                        var a = m.keyStart
                        while (a > 0 && t[a - 1].isWhitespace()) a--
                        if (a > 0 && t[a - 1] == ',') t.substring(0, a - 1) + t.substring(m.valueEnd)
                        else {
                            var b = m.valueEnd
                            while (b < t.length && t[b].isWhitespace()) b++
                            if (b < t.length && t[b] == ',') b++
                            while (b < t.length && t[b].isWhitespace()) b++
                            t.substring(0, m.keyStart) + t.substring(b)
                        }
                    }
                    enc == null -> t
                    else -> {
                        val anchor = ms.lastOrNull { it.key in META_KEYS } ?: return null
                        val sep = if (pretty) ",\n  " else ","
                        t.substring(0, anchor.valueEnd) + sep + MiniJson.stringify(key) + (if (pretty) ": " else ":") + enc + t.substring(anchor.valueEnd)
                    }
                }
            }
            t
        } catch (e: JsonException) { null }
    }

    fun decode(text: String): Result<ProjectData> = try {
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(text) as? Map<String, Any?> ?: throw JsonException("Ожидался объект")
        val version = root.int("version", 1)
        if (version > VERSION) {
            Result.failure(IllegalStateException("Проект создан в более новой версии приложения (v$version)"))
        } else {
            val meta = metaOf(root)
            val psd = root.obj("psd")?.let {
                PsdRef(it.str("file", ""), it.str("name", "PSD"), it.int("width", 1), it.int("height", 1))
            }
            val base = HashMap<Int, LayerTransform>()
            root.obj("base")?.forEach { (key, v) ->
                val i = key.toIntOrNull()
                @Suppress("UNCHECKED_CAST")
                val o = v as? Map<String, Any?>
                if (i != null && o != null) base[i] = transformFromMap(o)
            }
            val tracks = HashMap<Int, AnimationTrack>()
            root.obj("tracks")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                var track = AnimationTrack(i)
                (v as? List<*>)?.forEach { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? Map<String, Any?>)?.let { track = track.upsert(keyFromMap(it)) }
                }
                if (!track.isEmpty()) tracks[i] = track
            }
            val media = (root["media"] as? List<*>).orEmpty().mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                (item as? Map<String, Any?>)?.let(::mediaFromMap)
            }
            val deleted = (root["deleted"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.toSet()
            val clips = HashMap<Int, Clip>()
            root.obj("clips")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                val o = v as? Map<String, Any?> ?: return@forEach
                val s = o.int("s", 0).coerceAtLeast(0)
                clips[i] = Clip(
                    s, o.int("e", meta.durationFrames).coerceAtLeast(s + 1),
                    o.dbl("speed", 1.0).toFloat(), o["rev"] == true, o["freeze"] == true, o.int("off", 0), o["loop"] == true,
                    o.dbl("kspeed", 1.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
                )
            }
            val markers = (root["markers"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.filter { it >= 0 }.distinct().sorted()
            val links = HashMap<Int, Int>()
            root.obj("links")?.forEach { (key, v) ->
                val c = key.toIntOrNull(); val par = (v as? Number)?.toInt()
                if (c != null && par != null && c != par) links[c] = par
            }
            val bones = (root["bones"] as? List<*>).orEmpty().mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                (item as? Map<String, Any?>)?.let {
                    Bone(it.int("id", -1), it.str("name", "Bone"), it.dbl("hx", 0.0).toFloat(), it.dbl("hy", 0.0).toFloat(),
                        it.dbl("tx", 0.0).toFloat(), it.dbl("ty", 0.0).toFloat(), it["indep"] == true, it.dbl("str", 1.0).toFloat().coerceIn(0f, 1f), it.int("ly", 0).coerceAtLeast(0))
                }
            }.filter { it.id >= BONE_ID_BASE }
            val regroup = (root["regroup"] as? List<*>).orEmpty().mapNotNull { item ->
                val l = item as? List<*> ?: return@mapNotNull null
                val a = (l.getOrNull(0) as? Number)?.toInt() ?: return@mapNotNull null
                val b = (l.getOrNull(1) as? Number)?.toInt() ?: return@mapNotNull null
                a to b
            }
            val groupAnchors = HashMap<Int, GroupDeformationAnchor>()
            if (root.containsKey("groupDeformationAnchors")) {
                if (version < 2) throw JsonException("Области деформации требуют формат версии 2")
                val entries = root.obj("groupDeformationAnchors") ?: throw JsonException("Некорректные области групп")
                for ((key, value) in entries) {
                    val id = key.toIntOrNull()?.takeIf { it >= 0 } ?: throw JsonException("Некорректный id группы")
                    val values = value as? List<*> ?: throw JsonException("Некорректная область группы")
                    if (values.size != 4) throw JsonException("Некорректная область группы")
                    val numbers = values.map { (it as? Number)?.toFloat() ?: throw JsonException("Некорректная координата области") }
                    val anchor = GroupDeformationAnchor(numbers[0], numbers[1], numbers[2], numbers[3])
                    if (!anchor.valid() || groupAnchors.put(id, anchor) != null) throw JsonException("Некорректная область группы")
                }
            }
            val warps = (root["warps"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.toSet()
            val warpSizes = HashMap<Int, com.vibetoon.domain.anim.CubicWarpGrid.Size>()
            if (root.containsKey("warpGridSizes")) {
                if (version < 3) throw JsonException("Размер сетки Warp требует формат версии 3")
                val entries = root.obj("warpGridSizes") ?: throw JsonException("Некорректные размеры сетки Warp")
                for ((key, value) in entries) {
                    val id = key.toIntOrNull()?.takeIf { it >= 0 && it in warps } ?: throw JsonException("Некорректный id Warp")
                    val v = value as? List<*> ?: throw JsonException("Некорректный размер Warp")
                    if (v.size != 2 || v.any { it !is Number || it.toDouble() != it.toInt().toDouble() }) throw JsonException("Некорректный размер Warp")
                    val size = com.vibetoon.domain.anim.CubicWarpGrid.Size((v[0] as Number).toInt(), (v[1] as Number).toInt())
                    if (!size.valid() || size == com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT || warpSizes.put(id, size) != null) throw JsonException("Некорректный размер Warp")
                }
            }
            val dense = HashMap<Int, com.vibetoon.domain.anim.DenseWarpGrid>()
            if (root.containsKey("denseWarps")) {
                if (version < 4) throw JsonException("Плотный Warp требует формат версии 4")
                val entries = root.obj("denseWarps") ?: throw JsonException("Некорректная сетка Warp")
                for ((key, value) in entries) {
                    val id = key.toIntOrNull()?.takeIf { it >= 0 && it in warps && it !in groupAnchors } ?: throw JsonException("Некорректный id Warp")
                    val m = value as? Map<*, *> ?: throw JsonException("Некорректная сетка Warp")
                    fun dimension(k: String): Int {
                        val n = m[k] as? Number ?: throw JsonException("Некорректный размер Warp")
                        if (n.toDouble() != n.toInt().toDouble()) throw JsonException("Некорректный размер Warp")
                        return n.toInt()
                    }
                    val w = dimension("w"); val h = dimension("h")
                    if (w !in 2..33 || h !in 2..33) throw JsonException("Некорректный размер Warp")
                    val points = m["p"] as? List<*> ?: throw JsonException("Нет точек Warp")
                    if (points.size != w * h * 2) throw JsonException("Некорректное число точек Warp")
                    val grid = com.vibetoon.domain.anim.DenseWarpGrid(w, h, points.map { (it as? Number)?.toFloat() ?: throw JsonException("Некорректная точка Warp") })
                    if (!grid.valid() || dense.put(id, grid) != null) throw JsonException("Некорректная сетка Warp")
                }
            }
            val meshes = HashMap<Int, com.vibetoon.domain.anim.Mesh>()
            root.obj("meshes")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                @Suppress("UNCHECKED_CAST")
                val o = v as? Map<String, Any?> ?: return@forEach
                val vv = (o["v"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toFloat() }
                val tt = (o["t"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }
                val n = vv.size / 2
                if (n in 3..com.vibetoon.domain.anim.MeshMath.MAX_VERTS && tt.isNotEmpty() && tt.size % 3 == 0 && tt.all { it in 0 until n })
                    meshes[i] = com.vibetoon.domain.anim.Mesh(vv.toFloatArray(), tt.toIntArray())
            }
            val skins = HashMap<Int, Map<Int, Float>>()
            root.obj("skins")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                val o = v as? Map<*, *> ?: return@forEach
                val m = HashMap<Int, Float>()
                for ((bk, sv) in o) {
                    val b = (bk as? String)?.toIntOrNull() ?: continue
                    val s = (sv as? Number)?.toFloat() ?: continue
                    if (s > 0f) m[b] = s
                }
                if (m.isNotEmpty()) skins[i] = m
            }
            val turbs = HashMap<Int, com.vibetoon.domain.anim.Turb>()
            root.obj("turbs")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                val o = v as? Map<*, *> ?: return@forEach
                fun f(k: String, d: Float) = (o[k] as? Number)?.toFloat() ?: d
                turbs[i] = com.vibetoon.domain.anim.Turb(f("i", 3f), f("s", 6f), f("c", 2f).toInt(), f("b", 0f).toInt()).clamped()
            }
            val fx = HashMap<Int, List<com.vibetoon.domain.anim.Effect>>()
            root.obj("fx")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                val list = (v as? List<*>).orEmpty().mapNotNull { item ->
                    val o = item as? Map<*, *> ?: return@mapNotNull null
                    val t = (o["t"] as? Number)?.toInt() ?: return@mapNotNull null
                    if (com.vibetoon.domain.anim.EffectMath.def(t) == null) return@mapNotNull null
                    val p = (o["p"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toFloat() }
                    com.vibetoon.domain.anim.Effect(t, List(8) { k -> com.vibetoon.domain.anim.EffectMath.clamp(t, k, p.getOrElse(k) { 0f }) }, (o["slot"] as? Number)?.toInt() ?: -1)
                }.take(com.vibetoon.domain.anim.EffectMath.MAX_PER_LAYER)
                if (list.isNotEmpty()) fx[i] = com.vibetoon.domain.anim.EffectTracks.assignSlots(list)
            }
            val pivots = HashMap<Int, Pair<Float, Float>>()
            if (root.containsKey("pivots")) {
                if (version < 5) throw JsonException("Опорные точки требуют формат версии 5")
                val entries = root.obj("pivots") ?: throw JsonException("Некорректные опорные точки")
                for ((key, value) in entries) {
                    val id = key.toIntOrNull()?.takeIf { it >= 0 } ?: throw JsonException("Некорректный id пивота")
                    val values = value as? List<*> ?: throw JsonException("Некорректная опорная точка")
                    if (values.size != 2) throw JsonException("Некорректная опорная точка")
                    val x = (values[0] as? Number)?.toFloat() ?: throw JsonException("Некорректная координата пивота")
                    val y = (values[1] as? Number)?.toFloat() ?: throw JsonException("Некорректная координата пивота")
                    if (!x.isFinite() || !y.isFinite() || pivots.put(id, x to y) != null) throw JsonException("Некорректная опорная точка")
                }
            }
            val pins = HashMap<Int, List<com.vibetoon.domain.anim.Pin>>()
            root.obj("pins")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                val list = (v as? List<*>).orEmpty().mapNotNull { item ->
                    val o = item as? Map<*, *> ?: return@mapNotNull null
                    fun f(k: String, d: Float) = (o[k] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: d
                    com.vibetoon.domain.anim.Pin(f("u", 0f).coerceIn(-0.5f, 1.5f), f("v", 0f).coerceIn(-0.5f, 1.5f), o["s"] == true, f("r", 180f).coerceIn(10f, 2000f))
                }.take(com.vibetoon.domain.anim.PuppetMath.MAX_PINS)
                if (list.isNotEmpty()) pins[i] = list
            }
            val lprops = HashMap<Int, com.vibetoon.domain.model.LayerProps>()
            root.obj("lprops")?.forEach { (key, v) ->
                val i = key.toIntOrNull() ?: return@forEach
                val o = v as? Map<*, *> ?: return@forEach
                lprops[i] = com.vibetoon.domain.model.LayerProps(
                    (o["o"] as? Number)?.toFloat() ?: 1f, o["b"] as? String ?: "norm", o["l"] == true, o["c"] == true,
                    (o["ex"] as? Number)?.toFloat() ?: 0f, (o["ct"] as? Number)?.toFloat() ?: 0f,
                    (o["sa"] as? Number)?.toFloat() ?: 0f, (o["hu"] as? Number)?.toFloat() ?: 0f,
                    visible = o["vis"] != false, locked = o["lk"] == true, name = o["n"] as? String,
                    depth = (o["dp"] as? Number)?.toFloat() ?: 0f,
                    mask = (o["mk"] as? Number)?.toInt() ?: -1, maskInvert = o["mi"] == true, maskOff = o["mo"] == true,
                    path = (o["pa"] as? Number)?.toInt() ?: -1, pathOrient = o["po"] == true,
                    crop = (o["cr"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }
                ).normalized()
            }
            val scenes = com.vibetoon.domain.anim.SceneMath.normalize((root["scenes"] as? List<*>).orEmpty().mapNotNull { item ->
                val o = item as? Map<*, *> ?: return@mapNotNull null
                com.vibetoon.domain.anim.SceneRange((o["id"] as? Number)?.toInt() ?: return@mapNotNull null, o["n"] as? String ?: "",
                    (o["s"] as? Number)?.toInt() ?: return@mapNotNull null, (o["e"] as? Number)?.toInt() ?: return@mapNotNull null,
                    layers = (o["ly"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt()?.takeIf { id -> id >= 0 } }.toSet(),
                    transition = com.vibetoon.domain.anim.SceneTransition.of(o["tr"] as? String).key,
                    transitionFrames = ((o["tf"] as? Number)?.toInt() ?: 0).coerceIn(0, com.vibetoon.domain.anim.SceneCanvas.MAX_TRANSITION),
                    background = (o["bg"] as? Number)?.toLong()?.takeIf { it in 0L..0xFFFFFFFFL }?.toInt())
            }, meta.durationFrames)
            val stackOrder = LinkedHashMap<Int, List<Int>>()
            (root["order"] as? Map<*, *>)?.forEach { (k, v) ->
                val parent = (k as? String)?.toIntOrNull()?.takeIf { it >= -1 } ?: return@forEach
                val list = (v as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt()?.takeIf { id -> id >= 0 } }.distinct()
                if (list.isNotEmpty()) stackOrder[parent] = list
            }
            Result.success(ProjectData(meta, psd, base, tracks, media, deleted, clips, markers, links, bones, regroup, warps, meshes,
                (root["persps"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() }.toSet(), skins, turbs, fx, pins, lprops, groupAnchors, warpSizes, dense, pivots, scenes, stackOrder))
        }
    } catch (e: JsonException) {
        Result.failure(IllegalStateException("Повреждённый файл проекта: ${e.message}"))
    } catch (e: RuntimeException) {
        // спринт 61: синтаксически верный, но несогласованный файл (не те типы, числа вне диапазона) - ошибка открытия, а не вылет
        Result.failure(IllegalStateException("Повреждённый файл проекта: Неверные данные"))
    }

    fun colorToString(argb: Long) = "#%08X".format(argb and 0xFFFFFFFFL)

    /** Векторная фигура; у BOOL (пакет 18) - ещё операция "op" и части "parts" (вложенные фигуры). */
    fun vectorJson(v: com.vibetoon.domain.vector.VectorShape): Map<String, Any?> = linkedMapOf<String, Any?>(
        "k" to v.kind.name, "p" to v.pts.map { f -> f.toDouble() }, "s" to colorToString(v.stroke), "w" to v.width.toDouble()
    ).also { mm ->
        if (v.fill != 0L) mm["f"] = colorToString(v.fill); if (v.preset.isNotEmpty()) mm["n"] = v.preset
        if (v.op.isNotEmpty()) mm["op"] = v.op
        if (v.parts.isNotEmpty()) mm["parts"] = v.parts.map { vectorJson(it) }
        if (v.handles.isNotEmpty()) mm["h"] = v.handles.map { f -> f.toDouble() }
        if (v.freeHandles.isNotEmpty()) mm["fh"] = v.freeHandles.map { b -> if (b) 1 else 0 }
        // спринт 40: векторная кисть - толщина по узлам и стиль
        if (v.widths.isNotEmpty()) mm["vw"] = v.widths.map { f -> (Math.round(f * 1000.0) / 1000.0) }
        if (v.brush.isNotEmpty()) mm["vb"] = v.brush
    }

    /** Чтение фигуры; неизвестный вид, мало точек или BOOL без двух частей - фигура пропускается. */
    fun vectorOf(c: Any?, depth: Int = 0): com.vibetoon.domain.vector.VectorShape? {
        val m = c as? Map<*, *> ?: return null
        if (depth > 16) return null
        val kind = runCatching { com.vibetoon.domain.vector.VecKind.valueOf(m["k"] as? String ?: return null) }.getOrNull() ?: return null
        val pts = (m["p"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toFloat() }
        if (pts.size < 4) return null
        val parts = (m["parts"] as? List<*>).orEmpty().mapNotNull { vectorOf(it, depth + 1) }
        val op = m["op"] as? String ?: ""
        // ручки Безье (пакет 19): только при точном числе значений, иначе контур читается прямыми;
        // независимые ручки (пакет 21) - флаг на узел, только вместе с ручками
        val handles = (m["h"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toFloat()?.takeIf { f -> f.isFinite() } }.takeIf { it.size == pts.size * 2 } ?: emptyList()
        if (kind == com.vibetoon.domain.vector.VecKind.BOOL && (parts.size != 2 || op !in com.vibetoon.domain.vector.VectorBool.OPS)) return null
        return com.vibetoon.domain.vector.VectorShape(
            kind, pts, parseColor(m["s"] as? String ?: "#FF000000"), (m["f"] as? String)?.let { parseColor(it) } ?: 0L,
            (m["w"] as? Number)?.toFloat() ?: 8f, m["n"] as? String ?: "", op, parts,
            handles,
            (m["fh"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt()?.let { b -> b != 0 } }
                .takeIf { handles.isNotEmpty() && it.size == pts.size / 2 && it.any { b -> b } } ?: emptyList(),
            // спринт 40: толщины векторной кисти - только у пути и только по одной на узел, иначе ровная линия
            (m["vw"] as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toFloat()?.takeIf { f -> f.isFinite() && f > 0f }?.coerceAtMost(com.vibetoon.domain.vector.VectorBrush.MAX_FACTOR) }
                .takeIf { kind == com.vibetoon.domain.vector.VecKind.PATH && it.size == pts.size / 2 } ?: emptyList(),
            (m["vb"] as? String)?.takeIf { s -> com.vibetoon.domain.vector.VectorBrush.Style.values().any { it.id == s } } ?: ""
        )
    }

    fun parseColor(s: String): Long {
        val hex = s.removePrefix("#")
        val v = hex.toLongOrNull(16) ?: return 0L
        return if (hex.length <= 6) v or 0xFF000000L else v   // без альфы = непрозрачный
    }

    // ---- чтение полей с запасными значениями ----
    private fun Map<String, Any?>.str(k: String, d: String) = this[k] as? String ?: d
    private fun Map<String, Any?>.int(k: String, d: Int) = (this[k] as? Number)?.toInt() ?: d
    private fun Map<String, Any?>.long(k: String, d: Long) = (this[k] as? Number)?.toLong() ?: d
    private fun Map<String, Any?>.dbl(k: String, d: Double) = (this[k] as? Number)?.toDouble() ?: d
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(k: String) = this[k] as? Map<String, Any?>

    private fun mediaToMap(m: MediaItem) = linkedMapOf<String, Any?>(
        "id" to m.id, "kind" to m.kind.name, "name" to m.name, "file" to m.file,
        "srcW" to m.srcWidth, "srcH" to m.srcHeight,
        "x" to m.x.toDouble(), "y" to m.y.toDouble(), "w" to m.w.toDouble(), "h" to m.h.toDouble(),
        "durationMs" to m.durationMs
    ).also {
        if (m.symDur > 0) it["sdur"] = m.symDur
        if (m.symRef != 0) it["sref"] = m.symRef
        if (m.markers.isNotEmpty()) it["mk"] = m.markers.map { k -> linkedMapOf<String, Any?>("f" to k.frame, "n" to k.name).also { mm -> if (k.thumb.isNotEmpty()) mm["t"] = k.thumb; if (k.isAlias) mm["fm"] = k.form } }
        if (m.timeKeys.isNotEmpty()) it["tk"] = m.timeKeys.map { k -> linkedMapOf<String, Any?>("f" to k.frame, "v" to k.value).also { mm -> if (k.ease != TimeEase.HOLD) mm["e"] = k.ease; k.curve?.let { c -> mm["c"] = c } } }
        if (m.symLoop) it["sl"] = true
        if (m.symOnce) it["so"] = true
        if (m.vector) it["vector"] = true
        if (m.volKeys.isNotEmpty()) it["vk"] = m.volKeys.map { k -> linkedMapOf<String, Any?>("f" to k.frame, "v" to k.value.toDouble()) }
        if (m.background) it["bg"] = true
        if (m.muted) it["mu"] = true
        if (m.vectors.isNotEmpty()) it["vec"] = m.vectors.map { v -> vectorJson(v) }
        if (m.cells.isNotEmpty()) it["cells"] = m.cells.map { c -> linkedMapOf<String, Any?>("s" to c.start, "f" to c.file).also { o -> if (c.hold > 0) o["h"] = c.hold } }
        if (m.isText) {
            it["text"] = m.text
            it["color"] = colorToString(m.textColor)
            it["font"] = m.font
            it["size"] = m.textSize.toDouble()
            if (m.gradient.size >= 2) {
                it["grad"] = m.gradient.map(::colorToString)
                it["gradAngle"] = m.gradientAngle.toDouble()
            }
            if (m.fx.active) it["tfx"] = textFxToMap(m.fx)
            if (m.textAlign != 0) it["ta"] = m.textAlign
            if (m.textDir != 0) it["tdir"] = m.textDir   // спринт 39: необязательный ключ, VERSION не меняется
            if (m.lineSpacing != 1f) it["tls"] = m.lineSpacing.toDouble()
            if (m.letterSpacing != 0f) it["tlt"] = m.letterSpacing.toDouble()
            if (m.stroke) { it["stk"] = true; it["stkW"] = m.strokeWidth.toDouble(); it["stkC"] = colorToString(m.strokeColor) }
        }
    }

    private fun mediaFromMap(o: Map<String, Any?>): MediaItem? {
        val kind = MediaKind.values().firstOrNull { it.name == o.str("kind", "") } ?: return null   // неизвестный тип пропускаем
        val id = (o["id"] as? Number)?.toInt() ?: return null
        return MediaItem(
            id = id, kind = kind, name = o.str("name", "media"), file = o.str("file", ""),
            srcWidth = o.int("srcW", 0), srcHeight = o.int("srcH", 0),
            x = o.dbl("x", 0.0).toFloat(), y = o.dbl("y", 0.0).toFloat(),
            w = o.dbl("w", 0.0).toFloat(), h = o.dbl("h", 0.0).toFloat(),
            durationMs = o.long("durationMs", 0L),
            text = o.str("text", ""),
            textColor = parseColor(o.str("color", "#FF000000")),
            font = o.str("font", FontKeys.DEFAULT),
            textSize = o.dbl("size", 96.0).toFloat(),
            gradient = (o["grad"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.let(::parseColor) },
            gradientAngle = o.dbl("gradAngle", 0.0).toFloat(),
            fx = (o["tfx"] as? Map<*, *>)?.let { textFxFromMap(it) } ?: TextFx(),
            stroke = o["stk"] as? Boolean ?: false,
            strokeWidth = o.dbl("stkW", 4.0).toFloat(),
            strokeColor = parseColor(o.str("stkC", "#FF000000")),
            textAlign = o.int("ta", 0).coerceIn(0, 2),
            textDir = TextBidi.coerce(o.int("tdir", 0)),
            lineSpacing = o.dbl("tls", 1.0).toFloat().coerceIn(0.5f, 3f),
            letterSpacing = o.dbl("tlt", 0.0).toFloat().coerceIn(-0.2f, 1f),
            cells = (o["cells"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { FrameCell((it["s"] as? Number)?.toInt() ?: 0, it["f"] as? String ?: return@let null, ((it["h"] as? Number)?.toInt() ?: 0).coerceIn(0, 100_000)) } },
            vector = o["vector"] as? Boolean ?: false,
            volKeys = (o["vk"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { m -> VolKey((m["f"] as? Number)?.toInt() ?: 0, ((m["v"] as? Number)?.toFloat() ?: 1f).coerceIn(0f, 1f)) } }.sortedBy { it.frame },
            background = o["bg"] as? Boolean ?: false,
            muted = o["mu"] as? Boolean ?: false,
            vectors = (o["vec"] as? List<*>).orEmpty().mapNotNull { c -> vectorOf(c) },
            symDur = o.int("sdur", 0), symRef = o.int("sref", 0),
            markers = (o["mk"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { m -> FrameMarker((m["f"] as? Number)?.toInt() ?: 0, m["n"] as? String ?: return@let null, m["t"] as? String ?: "", (m["fm"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: -1).normalized() } },
            timeKeys = (o["tk"] as? List<*>).orEmpty().mapNotNull { c -> (c as? Map<*, *>)?.let { m -> TimeKey((m["f"] as? Number)?.toInt() ?: 0, (m["v"] as? Number)?.toInt() ?: 0, ((m["e"] as? Number)?.toInt() ?: TimeEase.HOLD).takeIf { e -> TimeEase.valid(e) } ?: TimeEase.HOLD,
                (m["c"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { com.vibetoon.domain.anim.Curve.parse(it) != null }) } },
            symLoop = o["sl"] == true,
            symOnce = o["so"] == true
        )
    }

    private fun textFxToMap(f: TextFx) = linkedMapOf<String, Any?>(
        "prog" to f.progress, "progDur" to f.progressDur.toDouble(),
        "cnt" to f.counter, "cntFrom" to f.countFrom.toDouble(), "cntTo" to f.countTo.toDouble(), "cntDur" to f.countDur.toDouble(), "tc" to f.timecode,
        "shake" to f.shake.toDouble(), "shakeHz" to f.shakeHz.toDouble(),
        "bend" to f.pathBend.toDouble(),
        "wt" to f.weight, "wtFrom" to f.weightFrom, "wtTo" to f.weightTo, "wtDur" to f.weightDur.toDouble(),
        "rnd" to f.random, "rndDur" to f.randomDur.toDouble()
    )

    @Suppress("UNCHECKED_CAST")
    private fun textFxFromMap(m: Map<*, *>): TextFx {
        val o = m as Map<String, Any?>
        val d = TextFx()
        return TextFx(
            progress = o["prog"] as? Boolean ?: d.progress, progressDur = o.dbl("progDur", d.progressDur.toDouble()).toFloat(),
            counter = o["cnt"] as? Boolean ?: d.counter, countFrom = o.dbl("cntFrom", d.countFrom.toDouble()).toFloat(),
            countTo = o.dbl("cntTo", d.countTo.toDouble()).toFloat(), countDur = o.dbl("cntDur", d.countDur.toDouble()).toFloat(),
            timecode = o["tc"] as? Boolean ?: d.timecode,
            shake = o.dbl("shake", 0.0).toFloat(), shakeHz = o.dbl("shakeHz", d.shakeHz.toDouble()).toFloat(),
            pathBend = o.dbl("bend", 0.0).toFloat(),
            weight = o["wt"] as? Boolean ?: d.weight, weightFrom = o.int("wtFrom", d.weightFrom), weightTo = o.int("wtTo", d.weightTo),
            weightDur = o.dbl("wtDur", d.weightDur.toDouble()).toFloat(),
            random = o["rnd"] as? Boolean ?: d.random, randomDur = o.dbl("rndDur", d.randomDur.toDouble()).toFloat()
        )
    }

    private fun clipToMap(c: Clip) = linkedMapOf<String, Any?>(
        "s" to c.start, "e" to c.end, "speed" to c.speed.toDouble(), "rev" to c.reverse, "freeze" to c.freeze, "off" to c.offset, "loop" to c.loop
    ).also { if (c.keySpeed != 1f) it["kspeed"] = c.keySpeed.toDouble() }

    private fun transformToMap(t: LayerTransform) = linkedMapOf<String, Any?>(
        "dx" to t.dx.toDouble(), "dy" to t.dy.toDouble(),
        "sx" to t.scaleX.toDouble(), "sy" to t.scaleY.toDouble(),
        "rot" to t.rotation.toDouble(), "op" to t.opacity.toDouble()
    )

    private fun transformFromMap(o: Map<String, Any?>) = LayerTransform(
        dx = o.dbl("dx", 0.0).toFloat(),
        dy = o.dbl("dy", 0.0).toFloat(),
        scaleX = o.dbl("sx", 1.0).toFloat(),
        scaleY = o.dbl("sy", 1.0).toFloat(),
        rotation = o.dbl("rot", 0.0).toFloat(),
        opacity = o.dbl("op", 1.0).toFloat()
    )

    private fun keyToMap(k: Keyframe) = linkedMapOf<String, Any?>(
        "f" to k.frame,
        "dx" to k.dx.toDouble(), "dy" to k.dy.toDouble(),
        "sx" to k.scaleX.toDouble(), "sy" to k.scaleY.toDouble(),
        "rot" to k.rotation.toDouble(), "op" to k.opacity.toDouble(),
        "ease" to k.easing.name,
        "curve" to k.curve?.map { it.toDouble() }
    )

    private fun keyFromMap(o: Map<String, Any?>) = Keyframe(
        frame = o.int("f", 0),
        dx = o.dbl("dx", 0.0).toFloat(),
        dy = o.dbl("dy", 0.0).toFloat(),
        scaleX = o.dbl("sx", 1.0).toFloat(),
        scaleY = o.dbl("sy", 1.0).toFloat(),
        rotation = o.dbl("rot", 0.0).toFloat(),
        opacity = o.dbl("op", 1.0).toFloat(),
        // неизвестный тип плавности (из будущей версии) читаем как линейный
        easing = EasingType.values().firstOrNull { it.name == o.str("ease", "") } ?: EasingType.LINEAR,
        curve = (o["curve"] as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { com.vibetoon.domain.anim.Curve.parse(it) != null }
    )
}
