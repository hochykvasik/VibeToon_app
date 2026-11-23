package com.vibetoon.domain.project

import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.ProjectSettings
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.render.LayerTransform
import kotlin.math.min
import kotlin.math.roundToInt

/** Параметры проекта: размер холста, частота кадров, длительность, фон. */
data class ProjectMeta(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int = 24,
    val durationFrames: Int = 120,
    /** Цвет фона в формате ARGB; 0 = прозрачный. */
    val background: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Зацикливание воспроизведения: true - после последнего кадра плейхед возвращается на кадр 0, false - остановка на последнем. */
    val loop: Boolean = true,
    /** Магнит таймлайна: плейхед при перетаскивании прилипает к ключам, маркерам и границам клипов. */
    val magnet: Boolean = false,
    /** Папка менеджера проектов (пусто - в корне). */
    val folder: String = ""
)

/** Ссылка на PSD внутри папки проекта (файл копируется в проект при импорте). */
data class PsdRef(val file: String, val name: String, val width: Int, val height: Int)

/** Id медиа-слоёв начинаются с этого числа, чтобы не пересекаться с индексами записей PSD. */
const val MEDIA_ID_BASE = 100_000

/** Id костей начинаются с этого числа (выше медиа-слоёв): кость - узел анимации, как слой. */
const val BONE_ID_BASE = 200_000

/**
 * Кость в позе привязки (Bind Pose), координаты холста: сустав (hx, hy) и конец (tx, ty).
 * Родитель кости хранится в ProjectData.links (кость -> родительская кость), поза - как LayerTransform
 * под id кости (поворот вокруг сустава), поэтому ключи и Undo работают так же, как у слоёв.
 */
data class Bone(
    val id: Int, val name: String, val hx: Float, val hy: Float, val tx: Float, val ty: Float,
    /** Независимый поворот: кость наследует от родителя позицию и масштаб, но не угол поворота. */
    val independentRot: Boolean = false,
    /** Strength 0..1: множитель веса этой кости при скининге Mesh. */
    val strength: Float = 1f
) {
    val length get() = kotlin.math.hypot(tx - hx, ty - hy)
}

/** CAMERA - невидимый слой-камера: его трансформация двигает и масштабирует весь холст (Pan & Zoom). */
enum class MediaKind { IMAGE, VIDEO, AUDIO, TEXT, CAMERA, GROUP, SYMBOL, NULLOBJ }

/**
 * Медиа-слой проекта. Файл лежит в папке проекта (assets/...). Для картинки и видео (первый кадр)
 * x, y, w, h - прямоугольник на холсте проекта в пикселях холста; у аудио размеров нет, это
 * невизуальная дорожка с длительностью.
 */
data class MediaItem(
    val id: Int,
    val kind: MediaKind,
    val name: String,
    val file: String,
    val srcWidth: Int = 0,
    val srcHeight: Int = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
    val durationMs: Long = 0L,
    /** Только для TEXT: содержимое, цвет ARGB, шрифт (ключ из FontKeys) и размер в пикселях холста. */
    val text: String = "",
    val textColor: Long = 0xFF000000L,
    val font: String = FontKeys.DEFAULT,
    val textSize: Float = 96f,
    /** Градиентная заливка текста: 2 цвета и больше (пусто - сплошной textColor) и угол в градусах. */
    val gradient: List<Long> = emptyList(),
    val gradientAngle: Float = 0f,
    /** Эффекты текста (печать, счётчик, тряска, контур, толщина, рандомизатор). */
    val fx: TextFx = TextFx(),
    /** Обводка букв: включена, толщина в px холста (0..50) и цвет ARGB. */
    val stroke: Boolean = false,
    val strokeWidth: Float = 4f,
    val strokeColor: Long = 0xFF000000L,
    /** Выравнивание строк в блоке (0 - слева, 1 - по центру, 2 - справа), множитель межстрочного интервала и межбуквенный интервал в em. */
    val textAlign: Int = 0,
    val lineSpacing: Float = 1f,
    val letterSpacing: Float = 0f,
    /** Покадровый слой (только IMAGE): рисунки-экспозиции; пусто - обычная картинка. */
    val cells: List<FrameCell> = emptyList(),
    /** GROUP: длительность > 0 делает группу определением символа (вложенной сцены) с таким числом кадров. */
    val symDur: Int = 0,
    /** SYMBOL (экземпляр на сцене): id группы-определения. */
    val symRef: Int = 0,
    /** GROUP-определение символа: маркеры кадров внутри символа (Rest, AI, E...). */
    val markers: List<FrameMarker> = emptyList(),
    /** SYMBOL (экземпляр): ключи времени (Time Remapping) - какой внутренний кадр символа показывать, Hold. */
    val timeKeys: List<TimeKey> = emptyList(),
    /** SYMBOL (экземпляр): без ключей времени крутить внутреннюю анимацию по кругу. false (по умолчанию) - Hold на первом кадре. */
    val symLoop: Boolean = false,
    /** IMAGE: векторный слой - контур/примитивы [vectors] перерисовываются в картинку слоя при каждой правке. */
    val vector: Boolean = false,
    val vectors: List<com.vibetoon.domain.vector.VectorShape> = emptyList(),
    /** IMAGE: слой «Фон» - всегда в самом низу стопки, под слоями PSD. */
    val background: Boolean = false,
    /** AUDIO: ключи громкости (кадр проекта -> 0..1). */
    val volKeys: List<VolKey> = emptyList(),
    /** AUDIO / VIDEO (пакет 11): звук слоя выключен - не звучит при воспроизведении, на линейке и в WAV. */
    val muted: Boolean = false
) {
    val isVisual get() = kind != MediaKind.AUDIO
    /** Слой со звуком: аудио-дорожка или видео (его звуковая дорожка). Ключи громкости [volKeys] - у обоих. */
    val hasSoundTrack get() = kind == MediaKind.AUDIO || kind == MediaKind.VIDEO
    val isText get() = kind == MediaKind.TEXT

    /** Название строки в списке слоёв: у текста это его первая строка. */
    val label: String
        get() = if (isText) text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(28) ?: "Текст" else name
}

/**
 * Ключи шрифтов в project.json: системное семейство ("sans-serif", "serif", ...) или импортированный
 * файл ("file:Name.ttf" в общей папке шрифтов приложения). Неизвестный ключ - запасной шрифт.
 */
object FontKeys {
    const val DEFAULT = "sans-serif"
    const val FILE_PREFIX = "file:"
    /** Системные семейства Android (ключ -> название в списке). */
    val SYSTEM: List<Pair<String, String>> = listOf(
        "sans-serif" to "Sans-serif",
        "sans-serif-light" to "Sans-serif Light",
        "sans-serif-condensed" to "Sans-serif Condensed",
        "serif" to "Serif",
        "monospace" to "Monospace",
        "cursive" to "Cursive",
        "casual" to "Casual"
    )

    fun isFile(key: String) = key.startsWith(FILE_PREFIX)
    fun fileName(key: String) = key.removePrefix(FILE_PREFIX)
    fun fileKey(name: String) = FILE_PREFIX + name
}

/**
 * Клип слоя на таймлайне: слой виден на кадрах [start, end). Без записи слой виден весь проект.
 * speed, reverse, freeze - настройки ретайминга (применятся к видео и аудио при воспроизведении).
 */
data class Clip(
    val start: Int,
    val end: Int,
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val freeze: Boolean = false,
    /** Смещение в источнике (кадры проекта при скорости 1x) на первом кадре клипа: нужно после разрезания. */
    val offset: Int = 0,
    /** Зацикливание: после конца клип начинается заново и виден до конца проекта. */
    val loop: Boolean = false
) {
    val length get() = end - start

    fun visibleAt(frame: Int) = if (loop) frame >= start else frame in start until end

    /**
     * Пакет 15: момент источника звука/видео (в кадрах проекта при 1x, считая смещение) в момент [time] проекта
     * или null - клип молчит (до начала, после конца без цикла, стоп-кадр). Цикл повторяет клип с его начала
     * (как ключи анимации в [animFrame]); реверс - с конца отрезка источника.
     */
    fun sourceAt(time: Double): Double? {
        if (freeze || length <= 0) return null
        var rel = time - start
        if (rel < 0) return null
        if (loop) rel %= length.toDouble() else if (rel >= length) return null
        return if (!reverse) offset + rel * speed else offset + length * speed - rel * speed
    }

    /** Клип звучит хоть где-то в отрезке [from, from + frames): с циклом - до конца отрезка. */
    fun soundsIn(from: Int, frames: Int): Boolean =
        !freeze && length > 0 && start < from + frames && (loop || end > from)

    /**
     * Кадр анимации слоя (ключи лежат на кадрах проекта) для кадра проекта [frame]:
     * start + (frame - start) * speed; при loop - по модулю длины источника (length * speed).
     * Вне клипа (до начала) возвращается [frame] без изменений.
     */
    fun animFrame(frame: Int): Float {
        if (frame < start) return frame.toFloat()
        val local = (frame - start) * speed
        val l = (length * speed).coerceAtLeast(1f)   // длина источника: плашка 2x вдвое короче, а ключей в ней столько же
        val w = if (loop) ((local % l) + l) % l else local.coerceAtMost(l)
        return start + w
    }

    companion object {
        const val MIN_LENGTH = 1
        val SPEEDS = listOf(0.25f, 0.5f, 1f, 2f, 4f)

        /** Клип в границах проекта: start >= 0, end <= duration, длина не меньше MIN_LENGTH. */
        fun clamped(start: Int, end: Int, duration: Int, old: Clip? = null): Clip {
            val d = duration.coerceAtLeast(MIN_LENGTH)
            val s = start.coerceIn(0, d - MIN_LENGTH)
            val e = end.coerceIn(s + MIN_LENGTH, d)
            return (old ?: Clip(s, e)).copy(start = s, end = e)
        }
    }
}

/** Закреплённая рамка в локальных координатах группы, не экранная рамка и не деформер. */
data class GroupDeformationAnchor(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun valid(): Boolean = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
        width > 0f && height > 0f && (x + width).isFinite() && (y + height).isFinite()
}

/** Всё, что сохраняется в project.json. */
data class ProjectData(
    val meta: ProjectMeta,
    val psd: PsdRef? = null,
    /** Ручные трансформации слоёв и групп (без анимации), по id узла. */
    val base: Map<Int, LayerTransform> = emptyMap(),
    val tracks: Map<Int, AnimationTrack> = emptyMap(),
    /** Картинки, видео (первый кадр) и аудио, добавленные в проект. */
    val media: List<MediaItem> = emptyList(),
    /** Удалённые записи PSD (слои и группы с потомками): сам PSD-файл не меняется, они просто скрыты. */
    val deleted: Set<Int> = emptySet(),
    /** Клипы слоёв на таймлайне (границы видимости и ретайминг), по id узла. */
    val clips: Map<Int, Clip> = emptyMap(),
    /** Маркеры таймлайна (номера кадров) для быстрой навигации. */
    val markers: List<Int> = emptyList(),
    /** Привязки "слой -> родитель": ребёнок двигается вместе с родителем (id узлов PSD и медиа). */
    val links: Map<Int, Int> = emptyMap(),
    /** Скелет: кости в позе привязки. */
    val bones: List<Bone> = emptyList(),
    /** Перенесённые в другие группы слои PSD: (слой, новая группа или -1 = корень), в порядке переноса. */
    val regroup: List<Pair<Int, Int>> = emptyList(),
    /** Слои с Warp (сетка 4x4); смещения точек лежат в base и tracks под id WarpMath.pointId. */
    val warps: Set<Int> = emptySet(),
    /** Слои с Mesh: вершины и треугольники; смещения вершин лежат в base и tracks под id MeshMath.vertexId. */
    val meshes: Map<Int, com.vibetoon.domain.anim.Mesh> = emptyMap(),
    /** Слои с Perspective (4 угла); смещения углов лежат в base и tracks под id PerspectiveMath.pointId. */
    val persps: Set<Int> = emptySet(),
    /** Скининг: слой с Mesh -> (кость -> Strength); вершины сетки двигаются костями по авто-весам. */
    val skins: Map<Int, Map<Int, Float>> = emptyMap(),
    /** Turbulent Displace по слоям (эффект в шейдере, без ключей). */
    val turbs: Map<Int, com.vibetoon.domain.anim.Turb> = emptyMap(),
    /** Цепочки эффектов слоёв (по порядку применения). */
    val fx: Map<Int, List<com.vibetoon.domain.anim.Effect>> = emptyMap(),
    /** Пины Puppet Mesh по слоям; смещения пинов лежат в base и tracks под id PuppetMath.pinId. */
    val pins: Map<Int, List<com.vibetoon.domain.anim.Pin>> = emptyMap(),
    /** Свойства слоёв из панели слоёв: Opacity, Blend Mode, Alpha Lock, Clipping Mask (перекрывают PSD). */
    val layerProps: Map<Int, com.vibetoon.domain.model.LayerProps> = emptyMap(),
    /** Зарезервированные локальные рамки групп. Пока не включают деформацию. */
    val groupDeformationAnchors: Map<Int, GroupDeformationAnchor> = emptyMap(),
    /** Only non-default 2..4-point grids; v1/v2 always mean 4x4. */
    val warpGridSizes: Map<Int, com.vibetoon.domain.anim.CubicWarpGrid.Size> = emptyMap(),
    val denseWarps: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid> = emptyMap(),
    /** Переопределения опорных точек (пивотов) узлов: id -> (x, y) в координатах холста. */
    val pivots: Map<Int, Pair<Float, Float>> = emptyMap(),
    /** Сцены: именованные части таймлайна (пакет 9). */
    val scenes: List<com.vibetoon.domain.anim.SceneRange> = emptyList()
) {
    fun animation() = Animation(ProjectSettings(meta.fps, meta.durationFrames), tracks)
}

/** Что вводит пользователь в листе "Новый проект". */
data class NewProjectSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val background: Long
)

data class AspectPreset(val label: String, val w: Int, val h: Int)

object ProjectMath {
    val ASPECTS = listOf(
        AspectPreset("16:9", 16, 9),
        AspectPreset("9:16", 9, 16),
        AspectPreset("4:5", 4, 5),
        AspectPreset("1:1", 1, 1),
        AspectPreset("4:3", 4, 3)
    )
    val FPS_OPTIONS = listOf(24, 30, 60)
    const val DEFAULT_SHORT_SIDE = 1080
    const val MIN_SIZE = 64
    const val MAX_SIZE = 4096

    /** Размер холста по пропорциям: меньшая сторона = [shortSide] (1080p), стороны чётные для кодека. */
    fun sizeFor(ratioW: Int, ratioH: Int, shortSide: Int = DEFAULT_SHORT_SIDE): Pair<Int, Int> {
        val w: Int
        val h: Int
        if (ratioW >= ratioH) {
            h = shortSide
            w = (shortSide.toDouble() * ratioW / ratioH).roundToInt()
        } else {
            w = shortSide
            h = (shortSide.toDouble() * ratioH / ratioW).roundToInt()
        }
        return even(w) to even(h)
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v + 1

    fun clampSize(v: Int) = v.coerceIn(MIN_SIZE, MAX_SIZE)

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Подпись пропорций: 1920x1080 -> "16:9", 1080x1350 -> "4:5". */
    fun ratioLabel(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "-"
        val g = gcd(w, h)
        val rw = w / g
        val rh = h / g
        // слишком "некрасивые" пропорции (кастомные размеры) показываем как есть, а не 479:270
        return if (rw > 40 || rh > 40) "%.2f:1".format(w.toDouble() / h) else "$rw:$rh"
    }

    fun durationSeconds(frames: Int, fps: Int): Double = if (fps > 0) frames.toDouble() / fps else 0.0

    /**
     * Размещение картинки или кадра видео на холсте: по центру, целиком внутри [factor] холста,
     * без увеличения сверх исходного размера. Возвращает [x, y, w, h] в пикселях холста.
     */
    fun fitInside(srcW: Int, srcH: Int, canvasW: Int, canvasH: Int, factor: Float = 0.8f): FloatArray {
        if (srcW <= 0 || srcH <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
        val s = min(1f, min(canvasW * factor / srcW, canvasH * factor / srcH))
        val w = srcW * s
        val h = srcH * s
        return floatArrayOf((canvasW - w) / 2f, (canvasH - h) / 2f, w, h)
    }

    /** Длина аудио в кадрах проекта. */
    fun framesFor(durationMs: Long, fps: Int): Int = ((durationMs * fps) / 1000L).toInt().coerceAtLeast(1)

    /**
     * Вписывание PSD в холст проекта: масштаб по меньшему отношению, центр PSD в центре холста.
     * Применяется как матрица "мира" для корней дерева слоёв.
     */
    fun fit(psdW: Int, psdH: Int, canvasW: Int, canvasH: Int): Affine2D {
        if (psdW <= 0 || psdH <= 0) return Affine2D.IDENTITY
        val s = min(canvasW.toFloat() / psdW, canvasH.toFloat() / psdH)
        return Affine2D.translate(canvasW / 2f, canvasH / 2f) *
            Affine2D.scale(s, s) *
            Affine2D.translate(-psdW / 2f, -psdH / 2f)
    }
}

/** Цвета фона для листа "Новый проект" (ARGB). */
object BackgroundPresets {
    val ALL: List<Pair<String, Long>> = listOf(
        "Прозрачный" to 0x00000000L,
        "Белый" to 0xFFFFFFFFL,
        "Чёрный" to 0xFF000000L,
        "Серый" to 0xFF808080L,
        "Розовый" to 0xFFF8BBD0L,
        "Голубой" to 0xFFB3E5FCL
    )
}

/** Новая длина плашки при смене скорости с [old] на [new]: время источника остаётся тем же (2x - вдвое короче). */
fun retimedLength(length: Int, old: Float, new: Float, maxLength: Int): Int =
    Math.round(length * old / new).coerceIn(Clip.MIN_LENGTH, maxLength.coerceAtLeast(Clip.MIN_LENGTH))
