package com.vibetoon.domain.render

import com.vibetoon.domain.anim.BoneSeg
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.anim.SkinMath
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.model.Node
import com.vibetoon.domain.model.SceneDomain
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.nativebridge.LayerInfo

/**
 * Сцена для рендерера: поток операций снизу вверх.
 * ops: на операцию 4 int = [op, layerId, blend, flags];
 * params: 8 float = [a, b, c, d, tx, ty, opacity, 0], где (a..ty) - аффинное преобразование
 * "единичный квадрат -> холст" (для групп используется только opacity).
 */
class SceneData(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val ops: IntArray,
    val params: FloatArray,
    /** Слои, для которых нужны текстуры, в порядке отрисовки. */
    val textureIds: IntArray,
    /** Рамка выделения узла (слоя или группы) на холсте: 4 угла = 8 float. */
    val selectionQuads: Map<Int, FloatArray> = emptyMap(),
    /** Мировая матрица РОДИТЕЛЯ узла (для перевода сдвига жеста в локальные координаты). */
    val parentWorlds: Map<Int, Affine2D> = emptyMap(),
    /** Дельта позы каждой кости (в осях холста): куда сдвинулись её сустав и конец относительно Bind Pose. */
    val boneDeltas: Map<Int, Affine2D> = emptyMap(),
    /** Warp: слои с деформацией и по 32 числа смещений 16 контрольных точек на слой. */
    val warpIds: IntArray = IntArray(0),
    val warpData: FloatArray = FloatArray(0),
    /** Mesh: слои с сеткой; на слой [число вершин, число треугольников]. */
    val meshIds: IntArray = IntArray(0),
    val meshCounts: IntArray = IntArray(0),
    /** По 4 числа на вершину: u, v, dx, dy (подряд для всех сеток). */
    val meshVerts: FloatArray = FloatArray(0),
    /** Индексы вершин по три на треугольник (подряд для всех сеток). */
    val meshTris: IntArray = IntArray(0),
    /** Perspective: слои и по 9 чисел гомографии (по столбцам) на слой. */
    val perspIds: IntArray = IntArray(0),
    val perspData: FloatArray = FloatArray(0),
    /** Turbulent Displace: слои и по 4 числа (intensity, size, complexity, seed) на слой. */
    val turbIds: IntArray = IntArray(0),
    val turbData: FloatArray = FloatArray(0),
    /** Эффекты: слои, число эффектов на слой и по 10 float на эффект (тип, p0..p7, время). */
    val fxIds: IntArray = IntArray(0),
    val fxCounts: IntArray = IntArray(0),
    val fxData: FloatArray = FloatArray(0),
    /** Current visible descendant bounds in group-local coordinates, before deformation. */
    val groupLocalBounds: Map<Int, GroupDeformationMesh.Bounds> = emptyMap(),
    /** Group local space -> canvas, for fixed-frame interaction. */
    val groupWorlds: Map<Int, Affine2D> = emptyMap()
) {
    /**
     * Формы символов: экземпляр рисуется текстурой своей формы (пока она не загружена - запасной формой),
     * по 8 чисел на экземпляр: под-прямоугольник квадрата (x, y, w, h) основной и запасной формы.
     * Ставится вместе со сценой, поэтому форма всегда соответствует кадру этой сцены (без рассинхрона при Play/скраббинге).
     */
    @Volatile var aliases: TextureAliases = TextureAliases.EMPTY

    /**
     * Спринт 45: обрезка слоёв (вкладка «Обрезка»): id и по 4 числа l, t, r, b в долях текстуры (LayerCrop.scene).
     * Едет вместе со сценой - холст, экспорт и снимки обрезаны одинаково.
     */
    @Volatile var cropIds: IntArray = IntArray(0)
    @Volatile var cropRects: FloatArray = FloatArray(0)

    /**
     * Спринт 46: полная рамка КАРТИНКИ обрезанных слоёв на холсте (selectionQuads у них - видимая часть). Кисть,
     * ластик, лассо и векторы работают в пикселях картинки, поэтому берут эту рамку (см. texQuad).
     */
    @Volatile var textureQuads: Map<Int, FloatArray> = emptyMap()

    /** Рамка картинки слоя: у обрезанного - полная, у остальных - та же, что рамка выделения. */
    fun texQuad(id: Int): FloatArray? = textureQuads[id] ?: selectionQuads[id]

    /** Спринт 46: служебные поля сцены (формы, обрезка, фон) переносятся в пересобранную сцену. */
    fun copyExtrasTo(o: SceneData): SceneData {
        o.aliases = aliases; o.cropIds = cropIds; o.cropRects = cropRects; o.textureQuads = textureQuads
        o.backgroundOverride = backgroundOverride; o.beforeDraw = beforeDraw
        return o
    }

    /** Пакет 24: фон кадра на время перехода «Через чёрный/белый» (null - фон проекта). Едет вместе со сценой - и в экспорте. */
    @Volatile var backgroundOverride: Int? = null

    /**
     * Пакет 29: загрузки текстур этого кадра экспорта (рисунки, кадры видео, текст с эффектами), которые применяются
     * в потоке отрисовки прямо перед кадром (см. ExportTextures). Повторный вызов ничего не делает. null - нечего.
     */
    @Volatile var beforeDraw: (() -> Unit)? = null

    companion object {
        const val PARAM_STRIDE = 8
    }
}

/** Неизменяемый набор форм символов для сцены (см. [SceneData.aliases]). */
class TextureAliases(val ids: IntArray, val primary: IntArray, val fallback: IntArray, val rects: FloatArray) {
    init { require(primary.size == ids.size && fallback.size == ids.size && rects.size == ids.size * 8) }
    companion object { val EMPTY = TextureAliases(IntArray(0), IntArray(0), IntArray(0), FloatArray(0)) }
}

/**
 * Локальная трансформация узла (слоя или группы): перенос, масштаб по осям и поворот
 * вокруг опорной точки узла. Масштаб применяется в осях узла до поворота.
 */
data class LayerTransform(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,  // радианы
    /** Множитель прозрачности поверх прозрачности из файла: 1 = как в PSD. */
    val opacity: Float = 1f
) {
    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
    }
}

object SceneBuilder {
    /** Деформация группы в фиксированных локальных координатах группы (не экранный AABB).
     * Якорь сохраняется в проекте; вызывается из редактора и экспорта через GroupSceneInputs. */
    data class GroupDeformation(
        val bounds: GroupDeformationMesh.Bounds,
        val warp: FloatArray? = null,
        val perspective: FloatArray? = null,
        val cells: Int = 24
    )

    const val OP_LAYER = 0
    const val OP_BEGIN_GROUP = 1
    const val OP_END_GROUP = 2

    const val BLEND_NORMAL = 0
    const val BLEND_MULTIPLY = 1
    const val BLEND_SCREEN = 2
    const val BLEND_OVERLAY = 3
    const val BLEND_SOFT_LIGHT = 5
    const val BLEND_PASS = 4
    const val BLEND_ADD = 6
    const val BLEND_DARKEN = 7
    const val BLEND_LIGHTEN = 8
    const val BLEND_COLOR_DODGE = 9
    const val BLEND_COLOR_BURN = 10

    const val FLAG_CLIPPED = 1
    /** Слой выделяется тапом по всей рамке, а не только по непрозрачным пикселям (тонкий текст). */
    const val FLAG_HIT_BBOX = 2
    /**
     * Пакет 26, «Маска по слою» у «Оси растяжения». Группа (OP_BEGIN/END_GROUP) с [FLAG_MASK_SRC] - маска: слой или
     * группа без своего растяжения рисуется в отдельный буфер и не накладывается; следующая группа с [FLAG_MASKED] -
     * растянутый слой (группа), который умножается на альфу маски. Слои внутри маски несут [FLAG_MASK_SRC]: рисуются
     * без шейдерных эффектов и не ловятся касанием.
     */
    const val FLAG_MASK_SRC = 4
    const val FLAG_MASKED = 8
    /**
     * Спринт 38, маска слоя: у группы под маской ([FLAG_MASKED]) - инверсия, буфер умножается на (1 - альфа маски):
     * нарисованное на маске скрывает слой. Маска слоя - тот же механизм пары «маска - группа под маской», что и в пакете 26.
     */
    const val FLAG_MASK_INVERT = 16

    /** Вид камеры (без параллакса): тот же, что у корней сцены; null - камеры нет. */
    fun cameraView(cam: LayerTransform?, canvasWidth: Int, canvasHeight: Int): Affine2D {
        if (cam == null) return Affine2D.IDENTITY
        val cx = canvasWidth / 2f
        val cy = canvasHeight / 2f
        val z = cam.scaleX.coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE)
        return Affine2D.translate(cx, cy) * Affine2D.scale(z, z) * Affine2D.rotate(-cam.rotation) * Affine2D.translate(-(cx + cam.dx), -(cy + cam.dy))
    }

    fun blendCode(key: String): Int = when (key) {
        "norm" -> BLEND_NORMAL
        "mul " -> BLEND_MULTIPLY
        "scrn" -> BLEND_SCREEN
        "over" -> BLEND_OVERLAY
        "sLit" -> BLEND_SOFT_LIGHT
        "lddg" -> BLEND_ADD
        "dark" -> BLEND_DARKEN
        "lite" -> BLEND_LIGHTEN
        "div " -> BLEND_COLOR_DODGE
        "idiv" -> BLEND_COLOR_BURN
        "pass" -> BLEND_PASS
        else -> BLEND_NORMAL // остальные режимы PSD пока рисуются как Normal
    }

    /**
     * Смещения вершин сетки слоя со скинингом: вес кости - по позе привязки (u, v без смещения кадра), движение - от
     * смещённой точки. Без скининга (или без костей) возвращает [off0] как есть.
     */
    private fun skinnedOffsets(
        id: Int, m: com.vibetoon.domain.anim.Mesh, off0: FloatArray, skinRest: Map<Int, Affine2D>, skins: Map<Int, Map<Int, Float>>,
        bones: Map<Int, Pair<Float, Float>>, boneTails: Map<Int, Pair<Float, Float>>, delta: (Int) -> Affine2D
    ): FloatArray {
        val rest = skinRest[id] ?: return off0
        val inv = rest.inverse() ?: return off0
        val segs = ArrayList<BoneSeg>()
        val deltas = ArrayList<Affine2D>()
        for ((bid, st) in skins[id].orEmpty()) {
            val h = bones[bid] ?: continue
            val tl = boneTails[bid] ?: continue
            segs += BoneSeg(bid, h.first, h.second, tl.first, tl.second, st)
            deltas += delta(bid)
        }
        if (segs.isEmpty()) return off0
        val off = off0.copyOf()
        for (k in 0 until m.count) {
            val u = m.u(k) + off0[k * 2]
            val v = m.v(k) + off0[k * 2 + 1]
            val bx = rest.applyX(m.u(k), m.v(k)); val by = rest.applyY(m.u(k), m.v(k))
            val px = rest.applyX(u, v); val py = rest.applyY(u, v)
            val w = SkinMath.weights(bx, by, segs)
            if (w.isEmpty()) continue
            var sx = 0f; var sy = 0f
            for ((bi, wi) in w) { sx += wi * deltas[bi].applyX(px, py); sy += wi * deltas[bi].applyY(px, py) }
            off[k * 2] = inv.applyX(sx, sy) - m.u(k)
            off[k * 2 + 1] = inv.applyY(sx, sy) - m.v(k)
        }
        return off
    }

    /** Склейка массивов без боксинга (раньше flatMap { asList() } создавал объект на каждое число каждого кадра). */
    internal fun concat(parts: Collection<FloatArray>): FloatArray {
        var n = 0
        for (p in parts) n += p.size
        val out = FloatArray(n); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    /** Удобная перегрузка: дерево строится из плоского списка записей PSD. */
    fun build(
        layers: List<LayerInfo>,
        canvasWidth: Int,
        canvasHeight: Int,
        transforms: Map<Int, LayerTransform> = emptyMap()
    ): SceneData = buildTree(SceneTree.build(layers), canvasWidth, canvasHeight, transforms)

    /**
     * Обходит дерево и считает мировые матрицы: world = parentWorld * local, где
     * local = T(pivot + d) * R * S * T(-pivot). Поэтому трансформация группы двигает всех детей
     * как единый объект (детей не трогаем), а у детей повёрнутой группы появляется корректный перекос.
     * Скрытая группа вместе с содержимым в сцену не попадает.
     */
    fun buildTree(
        roots: List<Node>,
        canvasWidth: Int,
        canvasHeight: Int,
        transforms: Map<Int, LayerTransform> = emptyMap(),
        /** Матрица мира для корней дерева: вписывание PSD в холст проекта. */
        rootWorld0: Affine2D = Affine2D.IDENTITY,
        /** Узлы, для которых hit-test по рамке (текст). */
        bboxHitIds: Set<Int> = emptySet(),
        /** Привязки "слой -> родитель" (любые узлы): ребёнок повторяет сдвиг/поворот/масштаб родителя. */
        links: Map<Int, Int> = emptyMap(),
        /** Кости: id -> сустав в позе привязки (координаты холста). Поза кости - LayerTransform под её id. */
        bones: Map<Int, Pair<Float, Float>> = emptyMap(),
        /** Warp: id слоя -> 32 смещения (16 точек). */
        warps: Map<Int, FloatArray> = emptyMap(),
        /** Кости с независимым поворотом (не наследуют угол родителя). */
        independentBones: Set<Int> = emptySet(),
        /** Mesh: id слоя -> сетка и смещения её вершин на кадре (2 числа на вершину). */
        meshes: Map<Int, Pair<com.vibetoon.domain.anim.Mesh, FloatArray>> = emptyMap(),
        /** Perspective: id слоя -> 9 чисел гомографии (см. PerspectiveMath.matrix). */
        persp: Map<Int, FloatArray> = emptyMap(),
        /** Концы костей в позе привязки (координаты холста), для весов скининга. */
        boneTails: Map<Int, Pair<Float, Float>> = emptyMap(),
        /** Скининг: id слоя с Mesh -> (id кости -> Strength). Такой слой не следует за links, его вершины двигают кости. */
        skins: Map<Int, Map<Int, Float>> = emptyMap(),
        /** Turbulent Displace: id слоя -> 4 числа (см. TurbMath.params). */
        turbs: Map<Int, FloatArray> = emptyMap(),
        /** Эффекты слоёв: id слоя -> записи по 10 float (см. EffectMath.records). */
        fx: Map<Int, List<FloatArray>> = emptyMap(),
        /** Слой-камера (или -1): его трансформация задаёт вид всего холста, сам слой не рисуется. */
        cameraId: Int = -1,
        /** Transform Layer (null-объект): не рисуется, только передаёт трансформацию дочерним слоям. */
        nullIds: Set<Int> = emptySet(),
        /** Глубина слоя (параллакс камеры): id -> число; не заданные - 0. */
        depths: Map<Int, Float> = emptyMap(),
        /** Экспериментальный вызов для уже подготовленных общих сеток; приложение пока не передаёт их. */
        preparedGroupMeshes: Map<Int, GroupDeformationMesh.Result> = emptyMap(),
        /** Explicit group inputs; bounds live in group-local coordinates and must remain fixed. */
        groupDeformations: Map<Int, GroupDeformation> = emptyMap(),
        denseWarps: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid> = emptyMap(),
        /** Переопределения опорных точек листьев: id -> (x, y) в координатах холста. */
        leafPivots: Map<Int, Pair<Float, Float>> = emptyMap(),
        /** «Ось растяжения»: id -> линейная матрица в осях слоя вокруг опорной точки (дети группы растягиваются вместе с ней). */
        stretches: Map<Int, Affine2D> = emptyMap(),
        /** Пакет 26: слои и группы с включённой «Маской по слою» у «Оси растяжения» (растянутое видно только в своей форме). */
        stretchMasks: Set<Int> = emptySet(),
        /** Спринт 38: маски слоёв - цель (слой или группа) -> слой-маска и инверсия. */
        layerMasks: Map<Int, com.vibetoon.domain.model.LayerMasks.Mask> = emptyMap(),
        /** Спринт 38: слои-маски (и выключенных масок): сами по себе не рисуются, рамка для выбора остаётся. */
        maskSources: Set<Int> = emptySet(),
        /** Спринт 38: слои на пути - id -> [x, y, угол] (координаты холста): опорная точка слоя встаёт в точку пути. */
        pathPlaces: Map<Int, FloatArray> = emptyMap(),
        /** Спринт 43: кэш готовых сеток слоёв (редактор и экспорт держат свои); null - без кэша. */
        deformCache: DeformCache? = null,
        /**
         * Спринт 46: обрезка слоёв (вкладка «Обрезка»): квадрат слоя в сцене = видимая рамка, поэтому рамка выделения,
         * углы перспективы, Mesh/Warp, касания и групповые рамки строятся по видимой части. Таблица уходит в сцену
         * (cropIds/cropRects) - рендерер берёт текстуру из той же рамки (CropPolicy.h: uvRect).
         */
        crops: Map<Int, com.vibetoon.domain.model.LayerCrop> = emptyMap()
    ): SceneData {
        // Камера: dx, dy - смещение центра кадра, scaleX - приближение, rotation - поворот вида
        val cam = transforms[cameraId]
        val camView = cameraView(cam, canvasWidth, canvasHeight)
        // Параллакс: слой с глубиной d видит камеру ослабленной в 1/(1+d) раз: сдвиг и приближение (z-1) меньше, дальние слои "стоят" почти на месте
        fun camViewFor(d: Float): Affine2D {
            if (cam == null || d == 0f) return camView
            val k = 1f / (1f + d)
            val cx = canvasWidth / 2f; val cy = canvasHeight / 2f
            val z = (1f + (cam.scaleX.coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE) - 1f) * k).coerceAtLeast(LayerTransform.MIN_SCALE)
            return Affine2D.translate(cx, cy) * Affine2D.scale(z, z) * Affine2D.rotate(-cam.rotation) * Affine2D.translate(-(cx + cam.dx * k), -(cy + cam.dy * k))
        }
        val rootWorld = camView * rootWorld0
        val skinRest = HashMap<Int, Affine2D>()
        val ops = IntBuilder()
        val params = FloatBuilder()
        val textureIds = ArrayList<Int>()
        val quads = HashMap<Int, FloatArray>()
        val parentWorlds = HashMap<Int, Affine2D>()
        val groupLocalBounds = HashMap<Int, GroupDeformationMesh.Bounds>()
        val groupWorlds = HashMap<Int, Affine2D>()
        // Спринт 43: стек активных групповых деформеров (от внешнего к внутреннему) и слои под ними - деформации
        // складываются (DeformStack), а не отклоняются исключением, которое раньше выключало их во всей сцене.
        val groupChain = ArrayList<DeformStack.GroupLevel>()
        val leafStacks = HashMap<Int, Pair<List<DeformStack.GroupLevel>, Affine2D>>()
        val leafWorlds = HashMap<Int, Affine2D>()
        val denseLeaves = LinkedHashSet<Int>()
        var maskPass = 0   // > 0 - идёт проход маски группы: слои помечаются FLAG_MASK_SRC

        fun add(op: Int, id: Int, blend: Int, flags: Int, m: Affine2D, opacity: Float) {
            ops.add(op); ops.add(id); ops.add(blend); ops.add(if (op == OP_LAYER && maskPass > 0) flags or FLAG_MASK_SRC else flags)
            params.add(m.a); params.add(m.b); params.add(m.c); params.add(m.d); params.add(m.tx); params.add(m.ty); params.add(opacity); params.add(0f)
        }

        // Спринт 46: прямоугольник слоя на холсте с учётом обрезки (единичный квадрат -> видимая часть картинки).
        // Опорная точка по умолчанию остаётся центром всей картинки - обрезка ничего не сдвигает на холсте.
        val texQuads = HashMap<Int, FloatArray>()
        fun rectOf(n: LeafNode): Affine2D {
            val c = if (crops.isEmpty()) null else crops[n.id]
            return if (c == null) Affine2D.translate(n.x.toFloat(), n.y.toFloat()) * Affine2D.scale(n.w.toFloat(), n.h.toFloat())
            else CropGeometry.rect(n.x.toFloat(), n.y.toFloat(), n.w.toFloat(), n.h.toFloat(), c)
        }
        /** Полная рамка картинки обрезанного слоя (для рисования кистью и выделений в пикселях картинки). */
        fun rememberTex(n: LeafNode, world: Affine2D) {
            if (crops.isEmpty() || n.id !in crops) return
            texQuads[n.id] = cornersOfStatic(world * Affine2D.translate(n.x.toFloat(), n.y.toFloat()) * Affine2D.scale(n.w.toFloat(), n.h.toFloat()))
        }

        val fitInv0 = rootWorld0.inverse() ?: Affine2D.IDENTITY
        fun local(t: LayerTransform, px: Float, py: Float, id: Int = Int.MIN_VALUE): Affine2D {
            val pl = if (pathPlaces.isEmpty()) null else pathPlaces[id]
            val m = if (pl == null) Affine2D.translate(px + t.dx, py + t.dy) * Affine2D.rotate(t.rotation) * Affine2D.scale(t.scaleX, t.scaleY)
            else {
                // спринт 38: слой на пути - опорная точка в точке пути (путь в координатах холста; у слоя PSD - через вписывание)
                val media = id >= MEDIA_ID_BASE
                val x = if (media) pl[0] else fitInv0.applyX(pl[0], pl[1])
                val y = if (media) pl[1] else fitInv0.applyY(pl[0], pl[1])
                Affine2D.translate(x + t.dx, y + t.dy) * Affine2D.rotate(t.rotation + pl[2]) * Affine2D.scale(t.scaleX, t.scaleY)
            }
            val st = if (stretches.isEmpty()) null else stretches[id]
            return (if (st == null) m else m * st) * Affine2D.translate(-px, -py)
        }

        fun cornersOf(m: Affine2D): FloatArray {
            val xs = floatArrayOf(0f, 1f, 1f, 0f)
            val ys = floatArrayOf(0f, 0f, 1f, 1f)
            val out = FloatArray(8)
            for (i in 0 until 4) {
                out[i * 2] = m.applyX(xs[i], ys[i])
                out[i * 2 + 1] = m.applyY(xs[i], ys[i])
            }
            return out
        }

        fun bounds(list: List<FloatArray>): FloatArray {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (q in list) for (i in 0 until 4) {
                minX = minOf(minX, q[i * 2]); maxX = maxOf(maxX, q[i * 2])
                minY = minOf(minY, q[i * 2 + 1]); maxY = maxOf(maxY, q[i * 2 + 1])
            }
            return floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY)
        }

        // Пакет 8+: слои разных пространств в одной группе. Слои PSD заданы в координатах PSD (в холст их вписывает
        // rootWorld0), медиа-слои (картинки, рисунки, экземпляры символов, id >= MEDIA_ID_BASE) - сразу в координатах
        // холста. Медиа-слой внутри группы PSD (рот-символ в папке головы) получает мир группы * rootWorld0⁻¹, слой PSD
        // внутри медиа-группы - мир группы * rootWorld0: трансформации группы наследуются, а собственные координаты
        // слоя не искажаются вписыванием PSD.
        val fitInv = rootWorld0.inverse() ?: Affine2D.IDENTITY
        fun crossed(parentWorld: Affine2D, parentMedia: Boolean, id: Int): Affine2D {
            val media = SceneDomain.isMedia(id)
            return when {
                media == parentMedia -> parentWorld
                media -> parentWorld * fitInv
                else -> parentWorld * rootWorld0
            }
        }

        // Предпроход: для каждого узла локальная матрица и мир его родителя в дереве (без привязок).
        val pw0 = HashMap<Int, Affine2D>()
        val loc0 = HashMap<Int, Affine2D>()
        val leafNodes = HashMap<Int, LeafNode>()
        if (links.isNotEmpty() || bones.isNotEmpty() || layerMasks.isNotEmpty()) {
            fun pre(nodes: List<Node>, parentWorld0: Affine2D, parentMedia: Boolean) {
                for (n in nodes) {
                    val parentWorld = crossed(parentWorld0, parentMedia, n.id)
                    val t = transforms[n.id] ?: LayerTransform()
                    val l = when (n) {
                        is GroupNode -> local(t, n.pivotX, n.pivotY, n.id)
                        is LeafNode -> local(t, leafPivots[n.id]?.first ?: (n.x + n.w / 2f), leafPivots[n.id]?.second ?: (n.y + n.h / 2f), n.id)
                    }
                    pw0[n.id] = parentWorld
                    loc0[n.id] = l
                    if (n is LeafNode) leafNodes[n.id] = n
                    if (n is GroupNode) pre(n.children, parentWorld * l, SceneDomain.isMedia(n.id))
                }
            }
            val (m0, p0) = roots.partition { it.id >= MEDIA_ID_BASE }
            pre(p0, rootWorld, false)
            pre(m0, camView, true)
            for ((id, h) in bones) {
                pw0[id] = Affine2D.IDENTITY
                loc0[id] = local(transforms[id] ?: LayerTransform(), h.first, h.second)
            }
        }
        val deltaMemo = HashMap<Int, Affine2D>()
        val inProgress = HashSet<Int>()

        /** Изменение мира узла из-за его трансформации и трансформаций цепочки родителей (в осях холста). */
        fun delta(id: Int): Affine2D {
            deltaMemo[id]?.let { return it }
            val loc = loc0[id] ?: return Affine2D.IDENTITY
            if (!inProgress.add(id)) return Affine2D.IDENTITY   // цикл привязок
            val pw = pw0.getValue(id)
            var up = links[id]?.let { delta(it) } ?: Affine2D.IDENTITY
            val parentId = links[id]
            val head = bones[id]
            if (id in independentBones && parentId != null && parentId in bones && head != null) {
                // позиция и масштаб родителя наследуются, угол - нет: из линейной части дельты вычитается её поворот
                val dp = delta(parentId)
                val theta = Math.atan2(dp.b.toDouble(), dp.a.toDouble()).toFloat()
                val lin = Affine2D(dp.a, dp.b, dp.c, dp.d, 0f, 0f) * Affine2D.rotate(-theta)
                up = Affine2D.translate(dp.applyX(head.first, head.second), dp.applyY(head.first, head.second)) * lin *
                    Affine2D.translate(-head.first, -head.second)
            }
            val d = up * pw * loc * (pw.inverse() ?: Affine2D.IDENTITY)
            inProgress.remove(id)
            deltaMemo[id] = d
            return d
        }
        fun linkDelta(id: Int): Affine2D = links[id]?.let { delta(it) } ?: Affine2D.IDENTITY

        // Спринт 38: мир родителя каждого слоя-маски так же, как его посчитает обход (с привязками групп и глубиной камеры):
        // маска рисуется перед своей целью, даже если сама лежит выше в стопке или в другой группе.
        val maskParent = HashMap<Int, Affine2D>()
        if (layerMasks.isNotEmpty()) {
            val wanted = layerMasks.values.mapTo(HashSet()) { it.source }
            fun scan(nodes: List<Node>, parentWorld0: Affine2D, parentMedia: Boolean) {
                for (n in nodes) {
                    val pw = crossed(parentWorld0, parentMedia, n.id)
                    when (n) {
                        is GroupNode -> if (n.visible) scan(n.children, linkDelta(n.id) * pw * local(transforms[n.id] ?: LayerTransform(), n.pivotX, n.pivotY, n.id), SceneDomain.isMedia(n.id))
                        is LeafNode -> if (n.id in wanted) maskParent[n.id] = pw
                    }
                }
            }
            for (r in roots) {
                val media = r.id >= MEDIA_ID_BASE
                scan(listOf(r), camViewFor(depths[r.id] ?: 0f) * (if (media) Affine2D.IDENTITY else rootWorld0), media)
            }
        }

        /** Рамка слоя в мире (для служебных и маскирующих слоёв, которые не рисуются). */
        fun leafWorldQuad(n: LeafNode, parentWorld: Affine2D): FloatArray {
            val t = transforms[n.id] ?: LayerTransform()
            val skinned = n.id in skins && n.id in meshes
            val pw = (if (skinned) Affine2D.IDENTITY else linkDelta(n.id)) * parentWorld
            val w = pw * local(t, leafPivots[n.id]?.first ?: (n.x + n.w / 2f), leafPivots[n.id]?.second ?: (n.y + n.h / 2f), n.id)
            rememberTex(n, w)
            return cornersOf(w * rectOf(n))
        }

        /**
         * Спринт 38: проход маски слоя - буфер с альфой слоя-маски (без шейдерных эффектов). Пустой слой-маска даёт пустой
         * буфер (цель скрыта, с инверсией - видна целиком). false - слоя-маски нет в сцене: цель рисуется без маски.
         */
        fun emitLayerMask(mask: com.vibetoon.domain.model.LayerMasks.Mask): Boolean {
            val src = leafNodes[mask.source] ?: return false
            val parent = maskParent[src.id] ?: return false
            add(OP_BEGIN_GROUP, src.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
            if (!src.isEmpty) {
                val t = transforms[src.id] ?: LayerTransform()
                val skinned = src.id in skins && src.id in meshes
                val pw = (if (skinned) Affine2D.IDENTITY else linkDelta(src.id)) * parent
                val world = pw * local(t, leafPivots[src.id]?.first ?: (src.x + src.w / 2f), leafPivots[src.id]?.second ?: (src.y + src.h / 2f), src.id)
                val m = world * rectOf(src)
                if (skinned) skinRest[src.id] = m
                // прозрачность слоя-маски из панели слоёв ослабляет маску; глаз и плашка таймлайна маску не выключают
                add(OP_LAYER, src.id, BLEND_NORMAL, FLAG_MASK_SRC, m, src.opacity)
                textureIds += src.id
            }
            add(OP_END_GROUP, src.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
            return true
        }
        fun maskFlags(mask: com.vibetoon.domain.model.LayerMasks.Mask) = FLAG_MASKED or (if (mask.invert) FLAG_MASK_INVERT else 0)

        /** Обход списка братьев; в [out] накапливаются рамки видимых слоёв (для рамки группы). */
        fun walk(nodes: List<Node>, parentWorld0: Affine2D, out: MutableList<FloatArray>, parentMedia: Boolean, clipInit: Boolean? = null): Boolean? {
            // Видима ли база текущей clipping-цепочки; null - базы-слоя нет (начало списка или
            // между ними группа). Clipping к группе-базе пока не поддерживается: такой слой
            // рисуется как обычный, а не пропадает. [clipInit] - состояние цепочки предыдущего отрезка корней (пакет 25).
            var clipBaseVisible: Boolean? = clipInit
            // Пакет 26: открытая группа под маской растянутого слоя; слои с обтравкой по нему остаются внутри неё
            var openMasked = false
            fun closeMasked() {
                if (openMasked) add(OP_END_GROUP, -1, BLEND_NORMAL, FLAG_MASKED, Affine2D.IDENTITY, 1f)
                openMasked = false
            }
            for (n in nodes) {
                if (openMasked && !(n is LeafNode && n.clipped)) closeMasked()
                val t = transforms[n.id] ?: LayerTransform()
                val parentWorld = crossed(parentWorld0, parentMedia, n.id)
                when (n) {
                    is GroupNode -> {
                        if (!n.visible) continue
                        clipBaseVisible = null
                        val pw = linkDelta(n.id) * parentWorld
                        val world = pw * local(t, n.pivotX, n.pivotY, n.id)
                        parentWorlds[n.id] = pw
                        groupWorlds[n.id] = world
                        val chainDepth = groupChain.size
                        // Спринт 43: групповой деформер добавляется в стек; вложенные деформеры, эффекты и прозрачность групп
                        // на пути больше не запрещены. Пустой деформер ничего не меняет; вырожденная группа (масштаб 0)
                        // не видна вовсе, поэтому её деформация тоже ничего не меняет.
                        groupDeformations[n.id]?.let { deformation ->
                            val inv = world.inverse()
                            if ((deformation.warp != null || deformation.perspective != null) && inv != null)
                                groupChain += DeformStack.GroupLevel(deformation, world, inv)
                        }
                        // Пакет 26: «Маска по слою» - сначала маска: та же группа без своего растяжения (в отдельный буфер)
                        val gMasked = n.id in stretchMasks && stretches[n.id] != null && n.id !in groupDeformations
                        if (gMasked) {
                            maskPass++
                            add(OP_BEGIN_GROUP, n.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
                            walk(n.children, pw * local(t, n.pivotX, n.pivotY), ArrayList(), SceneDomain.isMedia(n.id))
                            add(OP_END_GROUP, n.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
                            maskPass--
                        }
                        // Спринт 38: маска слоя у группы (если у группы нет «Маски по слою» растяжения)
                        val gLayerMask = if (!gMasked && maskPass == 0) layerMasks[n.id] else null
                        val gLm = gLayerMask != null && emitLayerMask(gLayerMask)
                        val gFlag = if (gMasked) FLAG_MASKED else if (gLm) maskFlags(gLayerMask!!) else 0
                        add(OP_BEGIN_GROUP, n.id, blendCode(n.blend), gFlag, Affine2D.IDENTITY, n.opacity * t.opacity)
                        val childQuads = ArrayList<FloatArray>()
                        walk(n.children, world, childQuads, SceneDomain.isMedia(n.id))
                        while (groupChain.size > chainDepth) groupChain.removeAt(groupChain.size - 1)
                        add(OP_END_GROUP, n.id, blendCode(n.blend), gFlag, Affine2D.IDENTITY, n.opacity * t.opacity)
                        if (childQuads.isNotEmpty()) {
                            quads[n.id] = bounds(childQuads)
                            val inverse = world.inverse()
                            if (inverse != null) {
                                val localQuads = childQuads.map { q ->
                                    FloatArray(8).also { result ->
                                        for (i in 0..3) {
                                            result[i * 2] = inverse.applyX(q[i * 2], q[i * 2 + 1])
                                            result[i * 2 + 1] = inverse.applyY(q[i * 2], q[i * 2 + 1])
                                        }
                                    }
                                }
                                if (localQuads.all { q -> q.all(Float::isFinite) }) {
                                    val b = bounds(localQuads)
                                    val w = b[2] - b[0]; val h = b[5] - b[1]
                                    if (w.isFinite() && h.isFinite() && w > 0f && h > 0f)
                                        groupLocalBounds[n.id] = GroupDeformationMesh.Bounds(b[0], b[1], w, h)
                                }
                            }
                            out += childQuads
                        }
                    }
                    is LeafNode -> {
                        if (n.id == cameraId) continue   // камера не рисуется
                        if (n.id in nullIds) {
                            // Transform Layer: невидим, но у него есть рамка (для выбора и жестов) и своя трансформация для детей
                            val pwN = linkDelta(n.id) * parentWorld
                            val worldN = pwN * local(t, leafPivots[n.id]?.first ?: (n.x + n.w / 2f), leafPivots[n.id]?.second ?: (n.y + n.h / 2f), n.id)
                            parentWorlds[n.id] = pwN
                            rememberTex(n, worldN)
                            val mN = worldN * rectOf(n)
                            val qN = cornersOf(mN)
                            quads[n.id] = qN; out += qN
                            continue
                        }
                        if (n.id in maskSources) {
                            // Спринт 38: слой-маска сам не рисуется (и не рвёт цепочку обтравки); рамка - для выбора в панели
                            val qM = leafWorldQuad(n, parentWorld)
                            quads[n.id] = qM
                            continue
                        }
                        if (n.isEmpty) {
                            if (!n.clipped) clipBaseVisible = false
                            continue
                        }
                        if (!n.clipped) clipBaseVisible = n.visible
                        val clipped = n.clipped && clipBaseVisible != null
                        // Clipped-слой виден только если видима его база
                        if (!n.visible || (clipped && clipBaseVisible == false)) continue
                        val skinned = n.id in skins && n.id in meshes
                        val pw = (if (skinned) Affine2D.IDENTITY else linkDelta(n.id)) * parentWorld
                        val lpx = leafPivots[n.id]?.first ?: (n.x + n.w / 2f)
                        val lpy = leafPivots[n.id]?.second ?: (n.y + n.h / 2f)
                        val world = pw * local(t, lpx, lpy, n.id)
                        parentWorlds[n.id] = pw
                        // единичный квадрат -> прямоугольник слоя -> мир
                        val m = world * rectOf(n)   // спринт 46: у обрезанного слоя - видимая рамка
                        rememberTex(n, world)
                        if (skinned) skinRest[n.id] = m
                        leafWorlds[n.id] = m
                        // Спринт 43: слой под групповыми деформерами запоминает их стек (от внутреннего к внешнему); своя
                        // сетка, Warp, перспектива, эффекты и обтравка слоя сохраняются и складываются с групповыми.
                        if (groupChain.isNotEmpty()) leafStacks[n.id] = groupChain.asReversed().toList() to m
                        if (denseWarps[n.id]?.valid() == true) denseLeaves += n.id
                        val hitFlag = if (n.id in bboxHitIds) FLAG_HIT_BBOX else 0
                        val leafMask = if (!clipped && maskPass == 0 && !(n.id in stretchMasks && stretches[n.id] != null)) layerMasks[n.id] else null
                        if (leafMask != null && emitLayerMask(leafMask)) {
                            // Спринт 38: маска слоя - буфер маски, затем слой в группе под маской; прозрачность и режим наложения
                            // слоя - у этой группы, слои с обтравкой по нему остаются внутри (обтравливаются по видимой части)
                            add(OP_BEGIN_GROUP, n.id, blendCode(n.blend), maskFlags(leafMask), Affine2D.IDENTITY, n.opacity * t.opacity)
                            add(OP_LAYER, n.id, BLEND_NORMAL, hitFlag, m, 1f)
                            openMasked = true
                        } else if (n.id in stretchMasks && stretches[n.id] != null && !clipped) {
                            // Пакет 26: «Маска по слою» - маска (слой без растяжения), затем растянутый слой под ней; прозрачность
                            // и режим наложения слоя - у группы под маской (как у основы обтравки)
                            val m0 = pw * local(t, lpx, lpy) * rectOf(n)
                            add(OP_BEGIN_GROUP, n.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
                            add(OP_LAYER, n.id, BLEND_NORMAL, FLAG_MASK_SRC, m0, 1f)
                            add(OP_END_GROUP, n.id, BLEND_NORMAL, FLAG_MASK_SRC, Affine2D.IDENTITY, 1f)
                            add(OP_BEGIN_GROUP, n.id, blendCode(n.blend), FLAG_MASKED, Affine2D.IDENTITY, n.opacity * t.opacity)
                            add(OP_LAYER, n.id, BLEND_NORMAL, hitFlag, m, 1f)
                            openMasked = true
                        } else add(
                            OP_LAYER, n.id, blendCode(n.blend),
                            (if (clipped) FLAG_CLIPPED else 0) or hitFlag,
                            m, n.opacity * t.opacity
                        )
                        textureIds += n.id
                        val q = cornersOf(m)
                        quads[n.id] = q
                        out += q
                    }
                }
            }
            closeMasked()
            return clipBaseVisible
        }

        // PSD вписывается в холст матрицей rootWorld; медиа-слои (id >= MEDIA_ID_BASE) заданы сразу в координатах холста.
        // Пакет 25: корни рисуются в своём порядке - слои PSD и медиа вперемешку (по умолчанию медиа идут после PSD, как
        // раньше). Подряд идущие корни одного пространства и одной глубины обходятся вместе (цепочки обтравки не рвутся),
        // у каждой группы глубин своя матрица камеры.
        // На стыке PSD/медиа одной глубины цепочка обтравки продолжается: картинка с Clip над слоем PSD обтравливается по нему.
        var ri = 0
        var prevDepth: Float? = null
        var clipState: Boolean? = null
        while (ri < roots.size) {
            val media = roots[ri].id >= MEDIA_ID_BASE
            val d = depths[roots[ri].id] ?: 0f
            var rj = ri + 1
            while (rj < roots.size && (roots[rj].id >= MEDIA_ID_BASE) == media && (depths[roots[rj].id] ?: 0f) == d) rj++
            clipState = walk(roots.subList(ri, rj), camViewFor(d) * (if (media) Affine2D.IDENTITY else rootWorld0), ArrayList(), media,
                if (prevDepth == d) clipState else null)
            prevDepth = d
            ri = rj
        }
        // Спринт 43: итоговые сетки. Каждый слой собирается отдельно (DeformStack) и кэшируется по своему id - новая
        // сетка не затирает соседние, а «неподдерживаемых» сочетаний больше нет: деформации складываются по порядку.
        val nativeWarps = LinkedHashMap(warps)
        val nativePersp = LinkedHashMap(persp)
        val order = LinkedHashSet<Int>()
        order += meshes.keys; order += preparedGroupMeshes.keys; order += denseLeaves; order += leafStacks.keys
        val mIds = IntArray(order.size)
        val mCounts = IntArray(order.size * 2)
        val mVerts = FloatBuilder()
        val mTris = IntBuilder()
        var mi = 0
        for (id in order) {
            val prepared = preparedGroupMeshes[id]
            val stack = leafStacks[id]
            val own = meshes[id]
            var rm: com.vibetoon.domain.anim.Mesh
            var ro: FloatArray
            var clampOwn = false
            val leafDeformed = id in warps || id in persp || id in denseLeaves || stack != null
            if (prepared != null && own == null && !leafDeformed) {
                // экспериментальный вызов: уже отсемплированная общая сетка (без Loop-сглаживания)
                rm = prepared.mesh; ro = prepared.offsets
            } else if (prepared != null && own == null) {
                // спринт 43: готовая групповая сетка + деформеры самого слоя - складываются (раньше - отказ сцены)
                val dense = if (id in denseLeaves) denseWarps[id] else null
                val input = DeformStack.Input(prepared.mesh, prepared.offsets, 0, warps[id], dense, persp[id],
                    stack?.first.orEmpty(), stack?.second ?: leafWorlds[id])
                val r = deformCache?.compose(id, input) ?: DeformStack.compose(input)
                rm = r.mesh; ro = r.offsets
                nativeWarps.remove(id); nativePersp.remove(id)
            } else {
                val off = if (own != null) skinnedOffsets(id, own.first, own.second, skinRest, skins, bones, boneTails, ::delta) else null
                val dense = if (id in denseLeaves) denseWarps[id] else null
                val cubic = if (own != null || dense != null || stack != null) warps[id] else null
                // перспектива слоя запекается только под групповыми деформерами (иначе - шейдер, после сетки)
                val bakePersp = if (stack != null) persp[id] else null
                val input = DeformStack.Input(own?.first, off, if (own != null) (if (own.first.count <= 300) 2 else 1) else 0,
                    cubic, dense, bakePersp, stack?.first.orEmpty(), stack?.second ?: leafWorlds[id],
                    cells = stack?.first?.maxOfOrNull { it.deformation.cells.coerceIn(1, 30) } ?: DeformStack.GROUP_CELLS)
                val r = deformCache?.compose(id, input) ?: DeformStack.compose(input)
                rm = r.mesh; ro = r.offsets
                if (cubic != null) nativeWarps.remove(id)
                if (bakePersp != null) nativePersp.remove(id)
                clampOwn = own != null && stack == null && cubic == null && dense == null
            }
            mIds[mi] = id
            mCounts[mi * 2] = rm.count
            mCounts[mi * 2 + 1] = rm.tris.size / 3
            for (k in 0 until rm.count) {
                // NaN/Inf и «улетевшие» смещения рисуют артефакты на весь экран: обрезаем
                fun safe(x: Float) = if (x.isFinite()) (if (clampOwn) x.coerceIn(-3f, 3f) else x) else 0f
                mVerts.add(rm.u(k)); mVerts.add(rm.v(k)); mVerts.add(safe(ro.getOrElse(k * 2) { 0f })); mVerts.add(safe(ro.getOrElse(k * 2 + 1) { 0f }))
            }
            mTris.addAll(rm.tris)
            mi++
        }
        deformCache?.retain(order)
        return SceneData(
            canvasWidth, canvasHeight, ops.toArray(), params.toArray(), textureIds.distinct().toIntArray(),
            // спринт 38: и дельты слоёв костей - родителей корневых костей (id < BONE_ID_BASE): поза в Animation считается от них
            quads, parentWorlds, (bones.keys + bones.keys.mapNotNull { links[it] }.filter { it !in bones }).associateWith { delta(it) },
            nativeWarps.keys.toIntArray(), concat(nativeWarps.values),
            mIds, mCounts, mVerts.toArray(), mTris.toArray(),
            nativePersp.keys.toIntArray(), concat(nativePersp.values),
            turbs.keys.toIntArray(), concat(turbs.values),
            fx.keys.toIntArray(), fx.values.map { it.size }.toIntArray(), concat(fx.values.flatten()),
            groupLocalBounds, groupWorlds
        ).also { sd ->
            // спринт 46: таблица обрезки едет в той же сцене, по которой построена геометрия (никакого рассинхрона)
            if (crops.isNotEmpty()) {
                val ids = IntArray(crops.size); val rects = FloatArray(crops.size * 4)
                var i = 0
                for ((id, c) in crops) { ids[i] = id; rects[i * 4] = c.l; rects[i * 4 + 1] = c.t; rects[i * 4 + 2] = c.r; rects[i * 4 + 3] = c.b; i++ }
                sd.cropIds = ids; sd.cropRects = rects
                sd.textureQuads = texQuads
            }
        }
    }

    /** Углы матрицы единичного квадрата (по часовой с левого верхнего): 8 float. */
    internal fun cornersOfStatic(m: Affine2D): FloatArray {
        val xs = floatArrayOf(0f, 1f, 1f, 0f)
        val ys = floatArrayOf(0f, 0f, 1f, 1f)
        return FloatArray(8) { i -> if (i % 2 == 0) m.applyX(xs[i / 2], ys[i / 2]) else m.applyY(xs[i / 2], ys[i / 2]) }
    }
}

/** Спринт 43: растущий массив float без боксинга (поток сцены собирается на каждом кадре Play). */
class FloatBuilder(capacity: Int = 256) {
    private var a = FloatArray(capacity.coerceAtLeast(8))
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun toArray(): FloatArray = a.copyOf(size)
}

/** Спринт 43: растущий массив int без боксинга. */
class IntBuilder(capacity: Int = 256) {
    private var a = IntArray(capacity.coerceAtLeast(8))
    var size = 0; private set
    fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
    fun addAll(v: IntArray) {
        if (size + v.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + v.size))
        v.copyInto(a, size); size += v.size
    }
    fun toArray(): IntArray = a.copyOf(size)
}
