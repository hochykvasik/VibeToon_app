package com.vibetoon.ui

import androidx.compose.ui.graphics.asImageBitmap
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.sync.withLock
import com.vibetoon.data.AudioEngine
import com.vibetoon.data.ParseSummary
import com.vibetoon.data.FontEntry
import com.vibetoon.data.FontRegistry
import com.vibetoon.data.MediaImporter
import com.vibetoon.data.TextRenderer
import com.vibetoon.data.ProjectRepository
import com.vibetoon.data.PsdRepository
import com.vibetoon.domain.anim.Animation
import com.vibetoon.domain.anim.Mesh
import com.vibetoon.domain.anim.MeshMath
import com.vibetoon.domain.paint.BrushParams
import com.vibetoon.domain.paint.BrushPreset
import com.vibetoon.domain.paint.BrushPresets
import com.vibetoon.domain.paint.GrainMath
import com.vibetoon.domain.paint.DabStroker
import com.vibetoon.domain.paint.PaintOp
import com.vibetoon.domain.paint.StrokeMode
import com.vibetoon.domain.anim.TrackGroups
import com.vibetoon.domain.anim.KeyClipEntry
import com.vibetoon.domain.anim.BrushMath
import com.vibetoon.domain.anim.MeshGraph
import com.vibetoon.domain.anim.PerspectiveMath
import com.vibetoon.domain.anim.Turb
import com.vibetoon.domain.anim.Effect
import com.vibetoon.domain.anim.Pin
import com.vibetoon.domain.anim.PuppetMath
import com.vibetoon.domain.anim.EffectMath
import com.vibetoon.domain.anim.TurbMath
import com.vibetoon.domain.anim.WarpMath
import com.vibetoon.domain.anim.AnimationTrack
import com.vibetoon.domain.anim.EasingType
import com.vibetoon.domain.anim.ExportMath
import com.vibetoon.domain.anim.PlaybackMath
import com.vibetoon.domain.anim.ProjectSettings
import com.vibetoon.domain.anim.TimeFormat
import com.vibetoon.domain.model.Affine2D
import com.vibetoon.domain.model.GroupNode
import com.vibetoon.domain.model.LayerBlend
import com.vibetoon.domain.model.LayerProps
import com.vibetoon.domain.model.LayerTree
import com.vibetoon.domain.model.LeafNode
import com.vibetoon.domain.model.Node
import com.vibetoon.domain.model.SceneTree
import com.vibetoon.domain.model.TreeRow
import com.vibetoon.domain.project.BONE_ID_BASE
import com.vibetoon.domain.project.Bone
import com.vibetoon.domain.project.Clip
import com.vibetoon.domain.project.FontKeys
import com.vibetoon.domain.project.FrameCell
import com.vibetoon.domain.project.FrameMath
import com.vibetoon.domain.project.FrameMarker
import com.vibetoon.domain.project.MAX_SYMBOL_DEPTH
import com.vibetoon.domain.project.SymbolFormCache
import com.vibetoon.domain.project.SymbolForms
import com.vibetoon.domain.project.SymbolPlayback
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeKey
import com.vibetoon.domain.project.TimeEase
import com.vibetoon.domain.project.TimelineItemOps
import androidx.compose.ui.graphics.asImageBitmap
import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectMath
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.project.PsdRef
import com.vibetoon.domain.render.Camera
import com.vibetoon.domain.render.LayerTransform
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
import com.vibetoon.domain.render.TextureAliases
import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind
import com.vibetoon.nativebridge.PsdNative
import com.vibetoon.nativebridge.RenderNative
import com.vibetoon.render.ExportSpec
import com.vibetoon.render.RenderHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Свойства узла, которые правит инспектор. */
enum class Prop { X, Y, SCALE_X, SCALE_Y, ROTATION, OPACITY }

/**
 * Что показывает панель "Инспектор" для выделенного слоя или группы. Единицы: позиция в
 * пикселях холста (опорная точка узла + смещение), масштаб и прозрачность в процентах, поворот в градусах.
 */
enum class BoneTool { SELECT, ADD, LASSO }

/** Кость на экране (пиксели): сустав (hx, hy), конец (tx, ty). */
class BoneShape(val id: Int, val hx: Float, val hy: Float, val tx: Float, val ty: Float, val selected: Boolean)

/** Что рисуется поверх холста: кости и черновик новой кости (x0, y0, x1, y1). */
/** Контрольные точки Warp на экране: 16 пар (x, y) в пикселях вида. */
class WarpOverlay(val points: List<Float> = emptyList(), val width: Int = 4, val height: Int = 4)

/** Mesh на экране: вершины (x, y в пикселях вида), рёбра (пары индексов вершин) и выбранная вершина. */
/** Пины Puppet Mesh на экране: центры (x, y), радиусы влияния (px экрана), жёсткость, выбранный. */
class PinOverlay(val points: FloatArray = FloatArray(0), val radius: FloatArray = FloatArray(0), val stiff: BooleanArray = BooleanArray(0), val selected: Int = -1)
data class PinInfo(val count: Int = 0, val selected: Int = -1, val stiff: Boolean = false, val radius: Float = 180f)

class MeshOverlay(val points: FloatArray = FloatArray(0), val edges: IntArray = IntArray(0), val active: Int = -1)

enum class MeshTool { ADD, MOVE, CUT, SMUDGE, DELETE, PIN_ADD, PIN_MOVE }

class BoneOverlay(val bones: List<BoneShape> = emptyList(), val draft: FloatArray? = null, val lasso: List<Float> = emptyList())

/** Спринт 38: путь слоя-пути на экране (x, y подряд), рисуемый жест и выбран ли сам слой-путь. */
class PathOverlay(val pts: List<Float> = emptyList(), val closed: Boolean = false, val draft: List<Float> = emptyList(), val own: Boolean = false)

data class InspectorState(
    val id: Int,
    val name: String,
    val isGroup: Boolean,
    val x: Float,
    val y: Float,
    val scaleXPct: Float,
    val scaleYPct: Float,
    val rotationDeg: Float,
    val opacityPct: Float,
    val hasKey: Boolean,
    val easing: EasingType?,
    /** Сколько узлов выделено (мультивыделение): правка применяется ко всем. */
    val count: Int = 1,
    /** Опорная точка (пивот) главного узла в координатах холста. */
    val pivotX: Float = 0f,
    val pivotY: Float = 0f
)

/** Цветокоррекция слоя (пока только значения в UI; шейдер позже). Диапазон каждого параметра -100..100. */
data class ColorAdjust(val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val hue: Float = 0f) {
    val isIdentity get() = exposure == 0f && contrast == 0f && saturation == 0f && hue == 0f
}

/** Начало id призраков кальки. */
const val GHOST_BASE = 900_000
/** Тип эффекта Тонировка для кальки: kFragFx, режим 20. */
private const val TINT_FX = 20f

/** Свойства выделенного текстового слоя для вкладки "Текст". */
data class TextState(
    val id: Int, val text: String, val color: Long, val font: String, val fontTitle: String, val size: Float,
    val gradient: List<Long> = emptyList(), val gradientAngle: Float = 0f,
    val fx: com.vibetoon.domain.project.TextFx = com.vibetoon.domain.project.TextFx(),
    val stroke: Boolean = false, val strokeWidth: Float = 4f, val strokeColor: Long = 0xFF000000L,
    val align: Int = 0, val lineSpacing: Float = 1f, val letterSpacing: Float = 0f,
    /** Спринт 39: направление письма (TextBidi.AUTO/LTR/RTL) и итоговое направление текста для поля ввода. */
    val dir: Int = 0, val rtl: Boolean = false
)

/** Дорожка аудио на таймлайне: имя и длина в кадрах проекта. Звук пока не декодируется. */
data class AudioTrackInfo(val id: Int, val name: String, val lengthFrames: Int)

/** Состояние экспорта в MP4. */
sealed interface ExportUi {
    data object Idle : ExportUi
    /** [phase] - этап после кадров (пакет 12: «Звук»), null - рендер кадров. */
    data class Running(val done: Int, val total: Int, val phase: String? = null) : ExportUi
    /** [note] - дополнительная строка (пакет 12: звук в MP4 добавлен / не добавлен). */
    data class Done(val file: File, val gallerySaved: Boolean = false, val note: String? = null) : ExportUi
    data class Failed(val message: String) : ExportUi
}

sealed interface UiState {
    data object Idle : UiState
    data class Message(val text: String) : UiState
    data object Loading : UiState
    data class Rendering(val done: Int, val total: Int) : UiState
    data class Success(val records: Int, val ms: Long, val textures: Int, val mb: Float) : UiState
    data class Error(val message: String) : UiState
}

/** Высота превью-текстур: холст уменьшается до ~2048 px по высоте (память!). */
private const val PREVIEW_HEIGHT = 2048f
private const val ROTATE_DEAD_ZONE = 8f
private const val PIVOT_HIT_PX = 22f
/** Консервативный предел стороны текстуры (ES 3.0 гарантирует >= 2048). */
private const val MAX_TEX = 2048
const val MAX_GRADIENT_STOPS = 5

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PsdRepository(app)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _audioReadiness = MutableStateFlow<com.vibetoon.domain.project.AudioReadiness>(com.vibetoon.domain.project.AudioReadiness.Idle)
    val audioReadiness: StateFlow<com.vibetoon.domain.project.AudioReadiness> = _audioReadiness.asStateFlow()
    private var importJob: Job? = null
    private var openJob: Job? = null

    // Декод слоёв строго по одному (пик памяти = один слой).
    private val executor = Executors.newSingleThreadExecutor()
    private val decodeDispatcher = executor.asCoroutineDispatcher()

    // ---- Проект: размер холста, fps и фон берутся из него; PSD вписывается в холст ----
    private val projects = ProjectRepository(app)
    /** Спринт 30: стандартное имя нового объекта на текущем языке приложения (переводится один раз, при создании). */
    private fun dn(ru: String): String = com.vibetoon.ui.i18n.defaultName(getApplication<Application>(), ru)
    private var projectId: String? = null
    private var meta: ProjectMeta? = null
    private var psdRef: PsdRef? = null
    private var psdW = 1
    private var psdH = 1
    /** Вписывание PSD в холст проекта: матрица мира для корней дерева слоёв. */
    private var fit = Affine2D.IDENTITY
    private val _projectName = MutableStateFlow("")
    val projectName: StateFlow<String> = _projectName.asStateFlow()
    private var saveJob: Job? = null
    private var closing = false

    // ---- Состояние редактора: камера, выделение ----
    private var camera = Camera()
    private val _selected = MutableStateFlow<Int?>(null)
    val selected: StateFlow<Int?> = _selected.asStateFlow()
    /** Все выделенные узлы (слои, группы, аудио); [selected] - главный (последний выбранный). */
    private val _selection = MutableStateFlow<Set<Int>>(emptySet())
    val selection: StateFlow<Set<Int>> = _selection.asStateFlow()
    /** Режим мультивыделения: тап по слою добавляет его в выделение или убирает. */
    private val _multi = MutableStateFlow(false)
    val multiSelect: StateFlow<Boolean> = _multi.asStateFlow()
    private var multiMode: Boolean
        get() = _multi.value
        set(v) { _multi.value = v }
    /** Удалённые записи PSD (слои и группы с потомками). */
    private var deletedPsd: Set<Int> = emptySet()
    private val audioEngine = AudioEngine()
    private val _audioRevision = MutableStateFlow(0)
    /** Пакет 14: меняется, когда дорожка декодирована (окно экспорта пересчитывает состояние звука). */
    val audioRevision: StateFlow<Int> = _audioRevision.asStateFlow()

    private val _waveforms = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** Пики громкости аудио-слоёв (100 значений в секунду) для волновой формы на таймлайне. */
    val waveforms: StateFlow<Map<Int, FloatArray>> = _waveforms.asStateFlow()
    private var waveDrawWait: com.vibetoon.domain.project.WaveDrawBarrier? = null

    private var waveAckPosted = false

    /** Вызывается только после построения гистограммы соответствующей аудиодорожки в Canvas. */
    fun waveformDrawn(id: Int, peaks: FloatArray) {
        val waiting = waveDrawWait ?: return
        if (waiting.id != id || _waveforms.value[id] !== peaks || peaks.isEmpty() || waveAckPosted) return
        waveAckPosted = true
        android.view.Choreographer.getInstance().postFrameCallback {
            waveAckPosted = false
            if (waveDrawWait === waiting) waiting.acknowledge(id, peaks, _waveforms.value[id])
        }
    }

    private var lastScrubAt = 0L
    private var lastScrubFrame = -1

    private fun refreshWaveforms() {
        val pid = projectId
        _waveforms.value = if (pid == null) emptyMap() else media.filter { it.hasSoundTrack }.mapNotNull { m -> audioEngine.peaksFor(projects.fileOf(pid, m.file))?.let { m.id to it } }.toMap()
    }

    /** Пакет 11: громкость и «без звука» передаются звуковому движку для аудио и звука видео. */
    private fun pushAudioMix() {
        audioEngine.volumes = media.filter { it.hasSoundTrack && it.volKeys.isNotEmpty() }.associate { it.id to it.volKeys }
        audioEngine.muted = media.filter { it.hasSoundTrack && it.muted }.mapTo(HashSet()) { it.id }
    }

    /** Клипы слоёв на таймлайне: границы видимости и ретайминг. Нет записи - слой виден весь проект. */
    private val _clips = MutableStateFlow<Map<Int, Clip>>(emptyMap())
    val clips: StateFlow<Map<Int, Clip>> = _clips.asStateFlow()

    private val _markers = MutableStateFlow<List<Int>>(emptyList())
    // сцены (пакет 9): именованные части таймлайна
    private val _scenes = MutableStateFlow<List<com.vibetoon.domain.anim.SceneRange>>(emptyList())
    val scenes: StateFlow<List<com.vibetoon.domain.anim.SceneRange>> = _scenes.asStateFlow()
    private val _scenePlayOnly = MutableStateFlow(false)
    /** Play только внутри сцены под плейхедом (цикл/остановка по границам сцены). */
    val scenePlayOnly: StateFlow<Boolean> = _scenePlayOnly.asStateFlow()
    /** Экспорт только сцены под плейхедом (MP4, GIF, PNG-последовательность). */
    var exportSceneOnly: Boolean = false
        private set
    // пакет 10: активная (открытая) сцена - плейхед, Play, переходы и экспорт в её границах; null - весь таймлайн
    private val _activeScene = MutableStateFlow<Int?>(null)
    val activeScene: StateFlow<Int?> = _activeScene.asStateFlow()
    // пакет 10: «Режим ключей» - на таймлайне только слои с ключами, переход и выделение по ключам
    private val _keyMode = MutableStateFlow(false)
    val keyMode: StateFlow<Boolean> = _keyMode.asStateFlow()
    val markers: StateFlow<List<Int>> = _markers.asStateFlow()

    /** Привязки "слой -> родитель": ребёнок двигается вместе с родителем. */
    private val _links = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val links: StateFlow<Map<Int, Int>> = _links.asStateFlow()

    private val _bones = MutableStateFlow<List<Bone>>(emptyList())
    val bones: StateFlow<List<Bone>> = _bones.asStateFlow()
    private val _boneMode = MutableStateFlow(false)
    val boneMode: StateFlow<Boolean> = _boneMode.asStateFlow()
    private val _boneTool = MutableStateFlow(BoneTool.SELECT)
    val boneTool: StateFlow<BoneTool> = _boneTool.asStateFlow()
    /** true - Bind Pose (скелет подгоняется, картинка не деформируется); false - Animation. */
    private val _bindPose = MutableStateFlow(true)
    val bindPose: StateFlow<Boolean> = _bindPose.asStateFlow()
    private val _boneOverlay = MutableStateFlow(BoneOverlay())
    val boneOverlay: StateFlow<BoneOverlay> = _boneOverlay.asStateFlow()

    private val _colors = MutableStateFlow<Map<Int, ColorAdjust>>(emptyMap())
    val colors: StateFlow<Map<Int, ColorAdjust>> = _colors.asStateFlow()

    // ---- Undo / Redo: снимки редактируемого состояния ----
    private class Snapshot(
        val base: Map<Int, LayerTransform>, val animation: Animation, val media: List<MediaItem>,
        val deleted: Set<Int>, val clips: Map<Int, Clip>, val links: Map<Int, Int>, val bones: List<Bone>,
        val regroup: List<Pair<Int, Int>>, val warps: Set<Int>, val meshes: Map<Int, Mesh>, val persps: Set<Int>,
        val skins: Map<Int, Map<Int, Float>> = emptyMap(),
        val turbs: Map<Int, Turb> = emptyMap(),
        val fx: Map<Int, List<Effect>> = emptyMap(),
        val pins: Map<Int, List<Pin>> = emptyMap(),
        val lprops: Map<Int, LayerProps> = emptyMap(),
        /** Штрих кисти, создавший эту запись: Undo возвращает пиксели "до", Redo - "после". */
        val paintOp: PaintOp? = null,
        /** Полные снимки растрового слоя до/после изменения размеров. */
        val textureOp: com.vibetoon.domain.raster.ExpandedLayerHistory? = null,
        val groupAnchors: Map<Int, com.vibetoon.domain.project.GroupDeformationAnchor> = emptyMap(),
        val warpSizes: Map<Int, com.vibetoon.domain.anim.CubicWarpGrid.Size> = emptyMap(),
        val denseWarps: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid> = emptyMap(),
        val pivots: Map<Int, Pair<Float, Float>> = emptyMap(),
        val scenes: List<com.vibetoon.domain.anim.SceneRange> = emptyList(),
        val stackOrder: Map<Int, List<Int>> = emptyMap()
    ) {
        fun withPaint(op: PaintOp?) = Snapshot(base, animation, media, deleted, clips, links, bones, regroup, warps, meshes, persps, skins, turbs, fx, pins, lprops, op, textureOp, groupAnchors, warpSizes, denseWarps, pivots, scenes, stackOrder)
        fun withTexture(op: com.vibetoon.domain.raster.ExpandedLayerHistory?) = Snapshot(base, animation, media, deleted, clips, links, bones, regroup, warps, meshes, persps, skins, turbs, fx, pins, lprops, paintOp, op, groupAnchors, warpSizes, denseWarps, pivots, scenes, stackOrder)
    }
    // Пока нет UI для создания рамки и она не активирует деформер.
    private var groupDeformationAnchors: Map<Int, com.vibetoon.domain.project.GroupDeformationAnchor> = emptyMap()
    private var warpGridSizes: Map<Int, com.vibetoon.domain.anim.CubicWarpGrid.Size> = emptyMap()
    private var denseWarps: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid> = emptyMap()
    /** Переопределения опорных точек узлов (пивотов): id -> (x, y) в координатах холста. */
    private var pivotOverrides: Map<Int, Pair<Float, Float>> = emptyMap()
    private val rasterTransactionGate = com.vibetoon.domain.raster.RasterTransactionGate()
    // При неизвестном исходе GL нельзя сохранять проект с потенциально рассогласованной текстурой.
    private var rasterRecoveryRequired = false
    // Версии не удаляются: JSON и снимки истории могут ещё ссылаться на любую из них.
    private val versionedRasterIds = HashSet<Int>()
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()
    private var lastCheckKey: String? = null
    private var lastCheckTime = 0L
    /** Записи PSD (из файла) и все слои дерева (PSD + синтетические записи картинок и видео). */
    private var psdLayers: List<LayerInfo> = emptyList()
    private var regroup: List<Pair<Int, Int>> = emptyList()
    /** Пакет 25: явный порядок детей групп (смешанный порядок PSD и медиа), см. StackOrder. */
    private var stackOrder: Map<Int, List<Int>> = emptyMap()
    /** Свойства слоёв (Opacity, Blend, Alpha Lock, Clip) поверх данных PSD; сохраняются в project.json. */
    private val _layerProps = MutableStateFlow<Map<Int, LayerProps>>(emptyMap())
    val layerPropsState: StateFlow<Map<Int, LayerProps>> = _layerProps.asStateFlow()
    private val layerProps: Map<Int, LayerProps> get() = _layerProps.value
    private var layers: List<LayerInfo> = emptyList()
    val hasLayers: Boolean get() = layers.isNotEmpty() || media.isNotEmpty()
    /** Картинки, видео и аудио проекта; id начинаются с MEDIA_ID_BASE. */
    private val media = ArrayList<MediaItem>()
    private val importer = MediaImporter(app)
    /** Спринт 31: кэш, пул декодирования и предзагрузка рисунков покадровых слоёв. */
    private val cellCache = com.vibetoon.data.CellFrameCache(app, importer)
    private var lastCellFrame = 0
    private val fonts = FontRegistry(app)
    private val textRenderer = TextRenderer(fonts)
    private val _fontList = MutableStateFlow(fonts.list())
    val fontList: StateFlow<List<FontEntry>> = _fontList.asStateFlow()
    private val _textState = MutableStateFlow<TextState?>(null)
    /** Не null, когда выделен текстовый слой: показывает вкладку "Текст" в инспекторе. */
    val textState: StateFlow<TextState?> = _textState.asStateFlow()
    private var textJob: Job? = null
    /** Id текстовых слоёв: их выделяют тапом по всей рамке (у тонкого текста мало пикселей). */
    private var textIds: Set<Int> = emptySet()
    private val _audio = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackInfo>> = _audio.asStateFlow()
    /** Дерево узлов (группы и слои) для SceneBuilder; строится один раз при загрузке PSD. */
    private var roots: List<Node> = emptyList()
    private var scene: SceneData? = null
    private var canvasW = 1
    private var canvasH = 1
    private var viewW = 1f
    private var viewH = 1f
    data class GridView(val width: Float, val height: Float, val canvasWidth: Int, val canvasHeight: Int, val camera: Camera)
    private val _gridView = MutableStateFlow(GridView(1f, 1f, 1, 1, Camera()))
    val gridView: StateFlow<GridView> = _gridView.asStateFlow()
    private fun refreshGridView() { _gridView.value = GridView(viewW, viewH, canvasW, canvasH, camera) }
    /** Высота области холста в px (подсказки прячутся, когда холст слишком мал). */
    val canvasHeightPx: Float get() = viewH

    /** Сбрасывает временную подсказку (плашка снизу), чтобы она не висела вечно. */
    fun clearMessage(cur: UiState) { if (_state.value === cur) _state.value = UiState.Idle }

    // ---- Анимация (вся логика времени и интерполяции здесь, в Kotlin; C++ получает готовую сцену) ----
    private val _animation = MutableStateFlow(Animation())
    val animation: StateFlow<Animation> = _animation.asStateFlow()
    private val _frame = MutableStateFlow(0)
    val frame: StateFlow<Int> = _frame.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    /** Слои для строк таймлайна (сверху вниз, как в Photoshop). */
    private val _rows = MutableStateFlow<List<TreeRow>>(emptyList())
    val rows: StateFlow<List<TreeRow>> = _rows.asStateFlow()
    /** Свёрнутые группы дерева слоёв (по id папки). */
    private var collapsed: Set<Int> = emptySet()
    /** Цепочка в стиле Photoshop: true - масштаб пропорциональный, false - ScaleX и ScaleY отдельно. */
    private val _uniformScale = MutableStateFlow(true)
    val uniformScale: StateFlow<Boolean> = _uniformScale.asStateFlow()
    private val _psdName = MutableStateFlow("")
    val psdName: StateFlow<String> = _psdName.asStateFlow()

    private val _inspector = MutableStateFlow<InspectorState?>(null)
    /** Состояние инспектора: пересчитывается при выделении, жестах, смене кадра и вводе чисел. */
    val inspector: StateFlow<InspectorState?> = _inspector.asStateFlow()
    /** Опорные точки узлов (центр слоя или группы) в исходных координатах. */
    private var pivots: Map<Int, Pair<Float, Float>> = emptyMap()

    /** Ручная трансформация слоёв БЕЗ анимации (если у слоя есть трек, она игнорируется). */
    private val base = HashMap<Int, LayerTransform>()
    /** Временная правка слоя с треком на кадре без ключа; живёт до смены кадра или Add Key. */
    private val override = HashMap<Int, LayerTransform>()

    /** Кнопка "Тестовый PSD": копирует встроенный файл в папку проекта и загружает. */
    fun parseTestPsd() {
        val id = projectId ?: return
        loadPsd(keepState = false) { repo.parseTestPsd(projects.assetFile(id)) }
    }

    /** Кнопка "Загрузить PSD": файл из системного диалога копируется в папку проекта. */
    fun loadUserPsd(uri: Uri) {
        val id = projectId ?: return
        loadPsd(keepState = false) { repo.parseUri(uri, projects.assetFile(id)) }
    }

    /**
     * keepState = true при открытии сохранённого проекта (ручные трансформации и ключи остаются);
     * false при импорте нового PSD: id слоёв другие, прежняя анимация потеряла бы смысл.
     */
    private fun loadPsd(keepState: Boolean, source: suspend () -> Result<ParseSummary>) {
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        pause()  // Choreographer - только с главного потока
        _state.value = UiState.Loading
        viewModelScope.launch {
            source()
                .onSuccess { renderPsd(it, keepState) }
                .onFailure { _state.value = UiState.Error(it.message ?: it.javaClass.simpleName) }
        }
    }

    // ---------------------------------------------------------------- проект

    /** Открывает проект: размер холста, fps, фон, сохранённые трансформации и ключи, затем PSD. */
    fun openProject(id: String, psdAfterOpen: Uri? = null) {
        if (projectId == id || _selectionExtractBusy.value || rasterRecoveryRequired) return
        closing = false
        openJob?.cancel()
        openJob = viewModelScope.launch {
            rasterTransactionGate.run { resetEditor() }
            val data = withContext(Dispatchers.IO) { projects.load(id) }.getOrElse {
                _state.value = UiState.Error(it.message ?: "Не удалось открыть проект")
                return@launch
            }
            projectId = id
            meta = data.meta
            _loop.value = data.meta.loop; _magnet.value = data.meta.magnet
            psdRef = data.psd
            canvasW = data.meta.width
            canvasH = data.meta.height
            restoreGuides()
            base.putAll(data.base)
            deletedPsd = data.deleted
            _clips.value = data.clips
            _markers.value = data.markers
            _scenes.value = data.scenes
            _activeScene.value = null
            _links.value = data.links
            // спринт 38: кости удалённого (или неизвестного старой версии) слоя костей становятся свободными
            _bones.value = com.vibetoon.domain.anim.BoneLayers.orphaned(data.bones, data.media.filter { it.kind == MediaKind.BONES }.mapTo(HashSet()) { it.id })
            regroup = data.regroup
            stackOrder = data.stackOrder
            groupDeformationAnchors = data.groupDeformationAnchors.toMap()
            warpGridSizes = data.warpGridSizes.toMap()
            denseWarps = data.denseWarps.toMap()
            pivotOverrides = data.pivots.toMap()
            warpLayers = data.warps
            meshLayers = data.meshes
            perspLayers = data.persps
            skinLayers = data.skins
            turbLayers = data.turbs
            fxLayers = data.fx
            pinLayers = data.pins
            setProps(data.layerProps)
            clearHistory()
            _animation.value = data.animation()
            _projectName.value = data.meta.name
            media.clear()
            media.addAll(data.media)
            rebuildLayers()
            // Готовность View не означает готовность GL. Старые текстуры и очередь очищаются
            // на активном рендерере до загрузки изображений нового проекта.
            val view = try {
                kotlinx.coroutines.withTimeout(10_000) {
                    var active = awaitView()
                    while (true) {
                        active.awaitRendererReady()
                        if (RenderHost.view !== active) { active = awaitView(); continue }
                        RenderNative.clearPendingUploads()
                        active.clearTexturesAndWait()
                        if (RenderHost.view === active) break
                        active = awaitView()
                    }
                    active
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _state.value = UiState.Error("Не удалось подготовить холст")
                return@launch
            } catch (e: IllegalStateException) {
                _state.value = UiState.Error("Не удалось подготовить холст")
                return@launch
            }
            com.vibetoon.domain.render.ProjectRenderStartup.start(
                acquireReady = { view },
                clear = { active ->
                    active.setCamera(camera)
                    active.setBackground(data.meta.background.toInt())
                    val empty = SceneBuilder.buildTree(emptyList(), canvasW, canvasH)
                    scene = empty
                    active.setScene(empty)
                },
                restore = { restoreMediaTextures(id) },
                publish = { active ->
                    if (RenderHost.view === active && projectId == id && !closing) pushScene()
                }
            )
            if (RenderHost.view !== view || projectId != id || closing) return@launch
            val ref = data.psd
            if (ref != null) {
                psdW = ref.width
                psdH = ref.height
                fit = ProjectMath.fit(psdW, psdH, canvasW, canvasH)
                loadPsd(keepState = true) { repo.parseFile(projects.assetFile(id), ref.name) }
            }
            if (com.vibetoon.ui.shell.Lesson.pending) {
                com.vibetoon.ui.shell.Lesson.pending = false
                addLessonShapes()
            }
            if (psdAfterOpen != null && ref == null) loadUserPsd(psdAfterOpen)   // пакет 13: импорт PSD с экрана проектов
        }
    }

    /** Учебный проект: круг, звезда и сердце на трёх слоях (разные цвета и места), чтобы сразу было что двигать и анимировать. */
    private fun addLessonShapes() {
        val side = canvasW * 0.22f
        val set = listOf(
            Triple(com.vibetoon.domain.paint.ShapeKind.CIRCLE, 0xFFEF5350.toInt(), -side * 1.4f),
            Triple(com.vibetoon.domain.paint.ShapeKind.STAR, 0xFFFFCA28.toInt(), 0f),
            Triple(com.vibetoon.domain.paint.ShapeKind.HEART, 0xFF42A5F5.toInt(), side * 1.4f)
        )
        viewModelScope.launch {
            for ((k, c, dx) in set) { addShape(k, c, dx); delay(450) }
            selectLayer(null)
        }
    }

    /** Дожидаемся актуального View; вызывающий код ограничивает время ожидания. */
    private suspend fun awaitView(): com.vibetoon.render.VtGLSurfaceView {
        while (true) {
            RenderHost.view?.let { return it }
            delay(50)
        }
    }

    private fun resetEditor() {
        importJob?.cancel(); importJob = null
        waveDrawWait?.drawn?.cancel(); waveDrawWait = null
        _audioReadiness.value = com.vibetoon.domain.project.AudioReadiness.Idle
        pause()
        arapMemo.clear()
        clearSelection(); penCancel(); _rasterTool.value = null
        _loop.value = true; _magnet.value = false; vecLayer = -1; vecPts.clear(); vecPressures.clear(); vecGen.clear()
        markerThumbJobs.values.toList().forEach { it.cancel() }; markerThumbJobs.clear()
        _markerThumbs.value = emptyMap()
        resetForms(dropTextures = true)
        thumbGen++; thumbs.clear(); thumbPending.clear()   // миниатюры прошлого проекта не должны всплывать у новых слоёв
        stroker = null; clearShapeAssist(); _paintCursor.value = null; _paintTool.value = null
        paintSizes.clear(); paintDirty.clear(); versionedRasterIds.clear(); mediaIds.reset()
        deformCache.clear()   // спринт 43: готовые сетки прошлого проекта не держат память
        residency.clear(); everEdited.clear(); lastEvictable = null   // пакет 29: учёт памяти текстур - заново
        saveJob?.cancel()
        camera = Camera()
        RenderHost.view?.setCamera(camera)
        layers = emptyList()
        psdLayers = emptyList()
        media.clear()
        _audio.value = emptyList()
        textJob?.cancel()
        textIds = emptySet()
        _textState.value = null
        roots = emptyList()
        scene = null
        base.clear()
        override.clear()
        _animation.value = Animation()
        _frame.value = 0
        _rows.value = emptyList()
        collapsed = emptySet()
        pivots = emptyMap()
        psdRef = null
        deletedPsd = emptySet()
        _clips.value = emptyMap()
        _markers.value = emptyList()
        _scenes.value = emptyList()
        _activeScene.value = null
        _links.value = emptyMap()
        _bones.value = emptyList()
        regroup = emptyList()
        stackOrder = emptyMap()
        groupDeformationAnchors = emptyMap()
        warpGridSizes = emptyMap()
        denseWarps = emptyMap()
        pivotOverrides = emptyMap()
        warpLayers = emptySet()
        meshLayers = emptyMap()
        perspLayers = emptySet()
        skinLayers = emptyMap()
        turbLayers = emptyMap()
        fxLayers = emptyMap()
        pinLayers = emptyMap()
        setProps(emptyMap())
        pinSel = -1
        videoFrames.release()
        videoThumbs.clear()
        cellCache.clear()
        _perspTool.value = false
        _perspOverlay.value = FloatArray(0)
        _meshOverlay.value = MeshOverlay()
        _warpTool.value = false
        _keySel.value = emptySet()
        _boneMode.value = false
        _boneOverlay.value = BoneOverlay()
        _pathDraw.value = false; pathStroke = null; activeBoneLayer = null; _pathOverlay.value = PathOverlay()   // спринт 38
        _colors.value = emptyMap()
        clearHistory()
        audioEngine.setSources(emptyMap())
        _psdName.value = ""
        _projectName.value = ""
        _selected.value = null
        _selection.value = emptySet()
        multiMode = false
        _inspector.value = null
        _state.value = UiState.Idle
        baseEditHinted = false
        projectId = null
        meta = null
        fit = Affine2D.IDENTITY
        RenderHost.view?.setSelection(null)
    }

    /** Автосохранение: через 1.5 с после последней правки. */
    private fun markDirty() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1500)
            saveNow()
        }
    }

    private suspend fun saveNow(): Boolean = rasterTransactionGate.run {
        if (rasterRecoveryRequired) return@run false
        val m = meta ?: return@run false
        projectId ?: return@run false
        try {
            com.vibetoon.domain.project.PaintSaveBarrier.save(
                paint = { flushPaintSaves() },
                metadata = {
                    val data = ProjectData(m.copy(updatedAt = System.currentTimeMillis()), psdRef, HashMap(base), _animation.value.tracks, media.toList(), deletedPsd, _clips.value, _markers.value, _links.value, _bones.value, regroup, warpLayers, meshLayers, perspLayers, skinLayers, turbLayers, fxLayers, pinLayers, layerProps, groupDeformationAnchors.toMap(), warpGridSizes.toMap(), denseWarps.toMap(), pivotOverrides.toMap(), (if (layers.isEmpty()) _scenes.value else com.vibetoon.domain.anim.SceneCanvas.prune(_scenes.value, layers.mapTo(HashSet()) { it.id })),
                        stackOrder = if (layers.isEmpty()) stackOrder else com.vibetoon.domain.model.StackOrder.prune(stackOrder, layers.mapTo(HashSet()) { it.id }))
                    withContext(Dispatchers.IO) { projects.save(data) }
                    meta = data.meta
                    Log.i("VT/Project", "saved ${data.meta.id}: ${data.base.size} base, ${data.tracks.size} tracks")
                }
            ).also { ok -> onPaintSaveResult(ok) }
        } catch (t: kotlinx.coroutines.CancellationException) { throw t }
        catch (t: Exception) {
            Log.e("VT/Project", "save failed", t)
            _state.value = UiState.Error("Не удалось сохранить проект; повторите сохранение")
            false
        }
    }

    // ---------------------------------------------------------------- Спринт 41: сохранение рисунка без ложных ошибок
    private val paintSaveError = "Не удалось сохранить рисунок; проект не сохранён"
    private var paintSaveFailures = 0
    /** Последний отказ записи рисунка был временным (поверхность на паузе, текстура не готова, нет GL-вида). */
    @Volatile private var lastPaintFailureTransient = true
    private var paintRetryJob: kotlinx.coroutines.Job? = null
    /** Пиксели изменённых слоёв, прочитанные в GL-потоке ДО паузы поверхности (сворачивание). */
    private val pauseCapture = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

    private fun onPaintSaveResult(ok: Boolean) {
        if (ok) {
            paintSaveFailures = 0
            if ((_state.value as? UiState.Error)?.message == paintSaveError) _state.value = UiState.Idle
            return
        }
        paintSaveFailures++
        val background = appBackground || RenderHost.view?.surfacePaused == true
        if (com.vibetoon.domain.project.PaintSaveRetry.report(paintSaveFailures, lastPaintFailureTransient, background))
            _state.value = UiState.Error(paintSaveError)
        if (!background && lastPaintFailureTransient) {
            paintRetryJob?.cancel()
            paintRetryJob = viewModelScope.launch {
                delay(com.vibetoon.domain.project.PaintSaveRetry.delayMs(paintSaveFailures))
                if (!appBackground) { saveJob?.cancel(); saveNow() }
            }
        }
    }

    /**
     * Запись рисунка перед действием с кадрами (подмена рисунка, вставка, дублирование): временный отказ
     * повторяется до [com.vibetoon.domain.project.PaintSaveRetry.MAX_TRANSIENT] раз, прежде чем действие отменится.
     */
    private suspend fun flushPaintWithRetry(): Boolean {
        repeat(com.vibetoon.domain.project.PaintSaveRetry.MAX_TRANSIENT) { attempt ->
            if (flushPaintSavesSafely()) return true
            if (!lastPaintFailureTransient) return false
            delay(com.vibetoon.domain.project.PaintSaveRetry.delayMs(attempt + 1) / 2)
        }
        return false
    }

    /** Сообщение об отказе записи рисунка перед действием: в фоне и при временном отказе - без баннера. */
    private fun paintFlushFailed() {
        val background = appBackground || RenderHost.view?.surfacePaused == true
        if (!background) _state.value = UiState.Error(paintSaveError)
        else if (_state.value == UiState.Loading) _state.value = UiState.Idle
    }

    /**
     * Спринт 41: вызывается на ON_PAUSE ДО паузы поверхности (MainActivity ставит паузу GL после событий жизненного
     * цикла). Одно событие GL-потока читает пиксели всех изменённых слоёв: GLSurfaceView выполняет очередь событий до
     * подтверждения паузы, пока контекст ещё текущий. Сохранение после паузы берёт эти пиксели.
     */
    private fun capturePaintBeforePause() {
        val view = RenderHost.view ?: return
        val ids = synchronized(paintDirty) { paintDirty.toIntArray() }
        if (ids.isEmpty() || view.surfacePaused) return
        view.queueEvent {
            for (id in ids) {
                val b = try { view.layerPixelsNow(id) } catch (_: Throwable) { null }
                if (b != null && b.size > 8) pauseCapture[id] = b
            }
        }
    }

    /** Сохранение при сворачивании приложения. */
    fun flushSave() {
        viewModelScope.launch {
            saveJob?.cancel()
            saveNow()
        }
    }

    /**
     * Приложение в фоне. Пока поверхность GL снята, фоновая работа с GL-потоком (снимки символов, миниатюры маркеров,
     * повторы каждые 400 мс) не ставится в очередь: иначе при возврате главный поток ждёт GL-поток в
     * GLSurfaceView.onResume/surfaceChanged, пока тот разгребает накопленные снимки, - «Приложение не отвечает».
     */
    @Volatile private var appBackground = false
    private var restoreJob: kotlinx.coroutines.Job? = null

    /** Пауза приложения: остановить воспроизведение/экспорт, завершить штрих и сохранить. */
    fun onAppPaused() {
        appBackground = true
        pause()
        // Пакет 27: экспорт не отменяется - MP4 паркуется на текущем кадре в GL-потоке, GIF/PNG ждут возврата.
        if (stroker != null || vecLayer >= 0) paintUp()
        capturePaintBeforePause()   // спринт 41: пиксели читаются до паузы поверхности
        viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            saveJob?.cancel()
            saveNow()
        }
    }

    /** Возврат в приложение: перерисовать кадр; текстуры после пересоздания контекста восстанавливает [onGlContextRecreated]. */
    fun onAppResumed() {
        val wasBackground = appBackground
        appBackground = false
        pauseCapture.clear()   // спринт 41: после возврата пиксели читаются заново (могли появиться новые штрихи)
        RenderHost.view?.requestRender()
        if (!wasBackground || projectId == null) return
        if (paintDirty.isNotEmpty()) markDirty()   // отложенная в фоне запись рисунка
        // Отложенные в фоне снимки символов: после первого кадра, без спешки (главный поток не ждёт GL).
        viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            if (appBackground) return@launch
            formWaits.clear(); formFails.clear()
            updateSymbols(_frame.value)
        }
    }

    /** GL-контекст пересоздан: текстуры потеряны, загружаем их заново из файлов проекта. */
    fun onGlContextRecreated() {
        // Вызывается из GL-потока: всё состояние VM читаем и меняем на главном.
        viewModelScope.launch { scheduleGlRestore() }
    }

    private fun scheduleGlRestore() {
        val pid = projectId ?: return
        // Первое создание контекста при открытии проекта: текстуры грузит сам openProject (раньше всё грузилось дважды).
        if (openJob?.isActive == true) return
        restoreJob?.cancel()
        restoreJob = viewModelScope.launch {
            kotlinx.coroutines.delay(150)   // несколько пересозданий подряд (поворот, возврат) - один restore
            if (projectId != pid) return@launch
            resetForms(dropTextures = false)   // текстуры форм жили в старом контексте
            cellShown.clear(); textFxKeys.clear()
            residency.clear(); lastEvictable = null   // пакет 29: всё загружается заново
            for (m in media) if (m.kind == MediaKind.VIDEO) videoFrames.forget(m.id)
            runCatching { restoreMediaTextures(pid) }
            if (projectId != pid) return@launch
            updateCells(_frame.value); updateTextFx(_frame.value); updateSymbols(_frame.value); updateVideoFrames(_frame.value)
            pushEvictable()
            RenderHost.view?.requestRender()
        }
    }

    /** Выход из проекта: сохранение и миниатюра (вид без рамки и в исходном масштабе), затем [done]. */
    fun closeProject(done: () -> Unit) {
        if (closing || _selectionExtractBusy.value || rasterRecoveryRequired) return
        closing = true
        importJob?.cancel(); importJob = null
        openJob?.cancel(); openJob = null
        _audioReadiness.value = com.vibetoon.domain.project.AudioReadiness.Idle
        viewModelScope.launch {
            pause()
            saveJob?.cancel()
            val id = projectId
            if (id != null) {
                resetCamera()
                selectLayer(null)
                delay(250)   // дать GL нарисовать кадр без рамки выделения
                ThumbnailCapture.capture(RenderHost.view, canvasW, canvasH, projects.thumbnailFile(id))
                if (!saveNow()) { closing = false; return@launch }
            }
            resetEditor()
            done()
        }
    }

    /** Пакет 16: итог переноса замков/текста PSD для сообщения после загрузки (null - ничего не переносилось). */
    private var psdExtrasNote: String? = null

    /**
     * Пакет 16: замки слоёв PSD (`lspf`) -> замок / замок прозрачности VibeToon; текстовые слои PSD (`TySh`) ->
     * редактируемый текст (растровый слой PSD скрывается как удалённый, Undo не нужен: это новый импорт).
     * Текст ставится по рамке слоя PSD в его группу и на его место в стопке (пакет 25: смешанный порядок PSD и медиа).
     * Ошибка чтения или отрисовки одного слоя не мешает импорту: этот слой остаётся картинкой.
     */
    private fun applyPsdExtras(summary: ParseSummary, locks: Boolean, editableText: Boolean): String? {
        val extras = runCatching { java.io.File(summary.path).inputStream().use { com.vibetoon.domain.project.PsdLayerExtras.read(it) } }
            .getOrDefault(emptyList())
        if (extras.isEmpty()) return null
        val infoById = summary.layers.associateBy { it.id }
        val props = HashMap(layerProps)
        fun base(info: LayerInfo) = props[info.id] ?: LayerProps(info.opacity, info.blendMode, false, info.isClipped).normalized()
        var locked = 0; var texts = 0; var failed = 0
        val textFor = HashMap<Int, Int>()   // пакет 25: растровый слой PSD -> редактируемый текст на его месте
        for (e in extras) {
            val info = infoById[e.index] ?: continue
            if (info.kind == LayerKind.GROUP_END) continue
            val t = e.text
            if (editableText && t != null && info.kind == LayerKind.LAYER) {
                val ok = runCatching {
                    val pl = com.vibetoon.domain.project.PsdLayerExtras.placement(e, t, fit.a, fit.tx, fit.ty)
                    val id = nextMediaId()
                    val proto = MediaItem(
                        id, MediaKind.TEXT, info.name.ifBlank { dn("Текст") }, "",
                        text = t.text, textColor = t.color ?: 0xFF000000L,
                        textSize = pl.size.coerceIn(TextRenderer.MIN_SIZE, TextRenderer.MAX_SIZE), textAlign = t.align
                    )
                    val (w, h) = renderAndUpload(proto)
                    media += proto.copy(x = pl.x, y = pl.y, w = w, h = h)
                    val b0 = base(info)
                    val p = b0.copy(alphaLock = false, clip = false, visible = b0.visible && info.isVisible)   // скрытый в PSD текст остаётся скрытым
                    props[id] = if (locks && e.locked) p.copy(locked = true) else p
                    props.remove(info.id)
                    deletedPsd = deletedPsd + info.id
                    textFor[info.id] = id
                    texts++
                    if (locks && e.locked) locked++
                }.onFailure { Log.w("VT/Psd", "text layer ${info.id} stays raster", it) }.isSuccess
                if (ok) continue
                failed++
            }
            if (locks && (e.locked || e.alphaLock)) {
                props[info.id] = base(info).copy(locked = e.locked, alphaLock = e.alphaLock || base(info).alphaLock)
                locked++
            }
        }
        setProps(props)
        if (textFor.isNotEmpty()) {
            // текст - в ту же группу PSD и на место своего слоя (координаты холста внутри группы PSD переводит SceneDomain)
            val parents = summary.layers.filter { it.id in textFor }.map { it.parentId }.toSet()
            for (p in parents) {
                val sibs = summary.layers.filter { it.parentId == p && it.kind != LayerKind.GROUP_END }.map { it.id }
                stackOrder = stackOrder + (p to sibs.map { textFor[it] ?: it })
            }
            for (l in summary.layers) { val tid = textFor[l.id] ?: continue; if (l.parentId >= 0) regroup = regroup + (tid to l.parentId) }
        }
        return when {
            failed > 0 -> "Текстовые слои PSD стали редактируемым текстом: $texts; остались картинкой: $failed"
            texts > 0 && locked > 0 -> "Текстовые слои PSD стали редактируемым текстом: $texts; перенесено замков: $locked"
            texts > 0 -> "Текстовые слои PSD стали редактируемым текстом: $texts"
            locked > 0 -> "Перенесено замков слоёв PSD: $locked"
            else -> null
        }
    }

    private suspend fun renderPsd(summary: ParseSummary, keepState: Boolean) = withContext(decodeDispatcher) {
        var doc = 0L
        try {
            doc = PsdNative.openDocument(summary.path)
            val size = PsdNative.documentSize(doc)
            psdW = size[0]
            psdH = size[1]
            fit = ProjectMath.fit(psdW, psdH, canvasW, canvasH)
            psdLayers = summary.layers
            psdExtrasNote = null
            override.clear()
            if (!keepState) {
                // Новый PSD: id слоёв другие, прежние правки PSD теряют смысл. Правки медиа-слоёв остаются.
                deletedPsd = emptySet()
                stackOrder = com.vibetoon.domain.model.StackOrder.dropPsd(stackOrder, media.mapTo(HashSet()) { it.id })   // пакет 25
                _clips.value = _clips.value.filterKeys { it >= MEDIA_ID_BASE }
                clearHistory()
                base.keys.removeAll { it < MEDIA_ID_BASE }
                val anim = _animation.value
                val mediaIds = media.mapTo(HashSet()) { it.id }
                _animation.value = anim.copy(tracks = anim.tracks.filterKeys { id ->
                    id >= MEDIA_ID_BASE || (id < 0 && mediaIds.any { com.vibetoon.domain.anim.EffectTracks.belongs(id, it) })
                })
                fxLayers = fxLayers.filterKeys { it >= MEDIA_ID_BASE }
                _frame.value = 0
                // пакет 16: Import → Import Layer Locked States / Import Text as Pixel Layer
                val prefs = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)
                val takeLocks = prefs.getBoolean("import_layer_locks", false)
                val editableText = !prefs.getBoolean("import_text_as_pixels", true)
                if (takeLocks || editableText) psdExtrasNote = applyPsdExtras(summary, takeLocks, editableText)
            }
            _psdName.value = summary.name
            collapsed = LayerTree.defaultCollapsed(summary.layers)
            rebuildLayers()
            selectLayer(null)
            val scene = SceneBuilder.buildTree(roots, canvasW, canvasH, transformsNow(), fit, textIds, leafPivots = pivotOverrides)
            this@MainViewModel.scene = scene
            RenderHost.view?.setScene(scene)  // холст виден сразу, слои подгружаются по одному

            val scale = minOf(1f, PREVIEW_HEIGHT / psdH)   // превью считается от размера PSD
            var bytes = 0L
            var loaded = 0
            val psdTextureIds = scene.textureIds.filter { it < MEDIA_ID_BASE }   // у медиа свой путь загрузки
            for ((i, id) in psdTextureIds.withIndex()) {
                val b = PsdNative.loadTexture(doc, id, scale, MAX_TEX)
                if (b > 0) { bytes += b; loaded++ }
                RenderHost.view?.requestRender()
                _state.value = UiState.Rendering(i + 1, psdTextureIds.size)
            }
            // правки Liquify слоёв PSD (assets/liq_<id>.png) ложатся поверх исходных текстур
            projectId?.let { pid ->
                for (id in psdTextureIds) {
                    val f = projects.mediaFile(pid, "liq_$id.png")
                    if (!f.exists()) continue
                    if (!keepState) { f.delete(); continue }
                    val ticket = RenderNative.textureUploadTicket(id)
                    runCatching { importer.decodeImage(f) }.onSuccess { d ->
                        RenderNative.uploadBitmapIfCurrent(id, d.bitmap, ticket)
                        d.bitmap.recycle()
                    }
                }
            }
            Log.i("VT/Psd", "textures queued: $loaded of ${psdTextureIds.size}, ${bytes / 1048576f} MB RGBA")
            val note = psdExtrasNote.also { psdExtrasNote = null }
            _state.value = if (note != null) UiState.Message(note) else UiState.Success(summary.layers.size, summary.millis, loaded, bytes / 1048576f)
            psdRef = PsdRef("assets/source.psd", summary.name, psdW, psdH)
            markDirty()
        } catch (t: Throwable) {
            Log.e("VT/Psd", "render failed", t)
            _state.value = UiState.Error(t.message ?: t.javaClass.simpleName)
        } finally {
            if (doc != 0L) PsdNative.closeDocument(doc)
        }
    }

    // ---------------------------------------------------------------- медиа: картинки, видео, аудио

    /**
     * Слои дерева = записи PSD + синтетические записи картинок и видео (id >= MEDIA_ID_BASE, они
     * лежат корнями в координатах холста). Так дерево, выделение, инспектор, ключи и таймлайн
     * работают с ними так же, как с слоями PSD.
     */
    private fun rebuildLayers() {
        refreshSymbolState()
        _cellMap.value = media.filter { it.kind == MediaKind.IMAGE && it.cells.isNotEmpty() }.associate { it.id to it.cells.map { c -> c.start } }
        val parentOf = regroup.toMap()
        val mediaInfos = media.filter { it.isVisual }.map {
            if (it.kind == MediaKind.GROUP) LayerInfo(it.id, it.name, 0, 0, 0, 0, 1f, true, 1, parentOf[it.id] ?: -1, "norm", false)   // группа пользователя
            else LayerInfo(
                it.id, it.label, it.x.roundToInt(), it.y.roundToInt(),
                it.w.roundToInt().coerceAtLeast(1), it.h.roundToInt().coerceAtLeast(1),
                1f, true, 0, parentOf[it.id] ?: -1, "norm", false
            )
        }
        // слои, перенесённые в другие группы: меняют родителя и встают в конец списка (наверх внутри группы)
        val psdVisible = psdLayers.filter { it.id !in deletedPsd }
        val movedMap = regroup.toMap()
        val moved = regroup.mapNotNull { (id, p) -> psdVisible.firstOrNull { it.id == id }?.copy(parentId = p) }
        layers = com.vibetoon.domain.model.StackOrder.apply((psdVisible.filter { it.id !in movedMap } + moved + mediaInfos).map { l ->
            val p = layerProps[l.id] ?: return@map l
            l.copy(name = p.name ?: l.name, opacity = p.opacity, blendMode = p.blend, isClipped = p.clip && l.kind == LayerKind.LAYER)
        }, stackOrder)   // пакет 25: явный порядок детей групп - PSD и медиа вперемешку
        textIds = media.filter { it.isText }.map { it.id }.toSet()
        roots = SceneTree.build(layers, pivotOverrides)
        pivots = computePivots(roots)
        rebuildRows()
        val fps = _animation.value.settings.fps
        _audio.value = media.filter { !it.isVisual }.map {
            AudioTrackInfo(it.id, it.name, ProjectMath.framesFor(it.durationMs, fps))
        }
        val pid = projectId
        // пакет 11: звуковая дорожка видео тоже звучит (воспроизведение, линейка, WAV) и рисует волну на полосе клипа
        audioEngine.setSources(
            if (pid == null) emptyMap() else media.filter { it.hasSoundTrack }.associate { it.id to projects.fileOf(pid, it.file) }
        )
        pushAudioMix()
        audioEngine.onDecoded = { viewModelScope.launch { refreshWaveforms(); fixAudioDurations(); _audioRevision.value++ } }
        refreshWaveforms()
    }

    /**
     * Кнопка "+" -> Изображение / Видео / Аудио. Файл копируется в папку проекта; картинка и первый
     * кадр видео декодируются (с уменьшением до 2048 px) и ставятся в центр холста как слой;
     * аудио становится невизуальной дорожкой на таймлайне.
     */
    /** Импорт картинки как символа: картинка становится определением символа, на сцену ставится экземпляр. */
    fun importMediaAsSymbol(uri: Uri) {
        val pid = projectId ?: return
        if (isGifUri(uri)) { importGif(uri, asSymbol = true); return }   // пакет 15: анимированный GIF - символ по кругу
        if (insertIntoDrawingTarget() != null) { insertImageIntoDrawing(uri); return }   // пакет 17: в рисунок выбранного слоя
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = nextMediaId()
        _state.value = UiState.Loading
        importJob = launchImport {
            try {
                val item = withContext(decodeDispatcher) { importMediaBlocking(pid, id, MediaKind.IMAGE, uri) }
                if (projectId != pid || closing) return@launchImport
                checkpoint("add", force = true)
                // картинка -> определение символа (группа с одним слоем), экземпляр на сцену
                val gid = nextMediaId()
                val n = media.count { it.kind == MediaKind.GROUP && it.symDur > 0 } + 1
                media += MediaItem(gid, MediaKind.GROUP, dn("Символ $n"), "", symDur = 1)
                regroup = regroup + (item.id to gid)
                media += item
                val iid = nextMediaId()
                media += MediaItem(iid, MediaKind.SYMBOL, dn("Символ $n"), "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid)
                rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
                _state.value = UiState.Message("Импортировано как символ «Символ $n»: двойной тап открывает редактирование")
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                Log.e("VT/Media", "import as symbol failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось импортировать")
            }
        }
    }

    /**
     * Пакет 22: видео как символ. Кадры видео (по одному на кадр проекта, до [VideoSymbolPlan.MAX_FRAMES]) становятся
     * рисунками покадрового слоя внутри нового символа, экземпляр крутится по кругу; звук видео - отдельная дорожка.
     */
    fun importVideoAsSymbol(uri: Uri) {
        val pid = projectId ?: return
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = nextMediaId()
        _state.value = UiState.Loading
        importJob = launchImport {
            val written = ArrayList<java.io.File>()
            var inserted = false
            val src = java.io.File(getApplication<Application>().cacheDir, "vsym_${java.util.UUID.randomUUID()}.mp4")
            val tmp = java.io.File(getApplication<Application>().cacheDir, "vsym_${java.util.UUID.randomUUID()}.png")
            try {
                val fps = _animation.value.settings.fps
                val name = withContext(Dispatchers.IO) { importer.displayName(uri) }
                val maxSide = if (resolutionLimitOn()) minOf(com.vibetoon.domain.project.ImportLimit.maxSide(canvasW, canvasH), com.vibetoon.domain.project.VideoSymbolPlan.MAX_SIDE)
                    else com.vibetoon.domain.project.VideoSymbolPlan.MAX_SIDE
                val result = withContext(decodeDispatcher) {
                    importer.copy(uri, src)
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(src.absolutePath)
                        val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        val (vw, vh) = com.vibetoon.domain.project.VideoSymbolPlan.oriented(
                            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0, rot)
                        val hasAudio = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                        check(vw > 0 && vh > 0) { "В файле нет видео" }
                        val plan = com.vibetoon.domain.project.VideoSymbolPlan.plan(dur, fps)
                        val (dw, dh) = com.vibetoon.domain.project.VideoSymbolPlan.decodeSize(vw, vh, maxSide)
                        val cells = ArrayList<com.vibetoon.domain.project.FrameCell>()
                        var first: MediaItem? = null
                        for ((i, ms) in plan.timesMs.withIndex()) {
                            if (projectId != pid || closing) throw kotlinx.coroutines.CancellationException()
                            val us = ms * 1000L
                            var bmp = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST, dw, dh)
                                else r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue   // кадр не декодировался: держится предыдущий
                            if (bmp.config != android.graphics.Bitmap.Config.ARGB_8888) bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
                            try {
                                if (first == null) {
                                    tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                    val item = importMediaBlocking(pid, id, MediaKind.IMAGE, Uri.fromFile(tmp))
                                    written += projects.fileOf(pid, item.file)
                                    first = item
                                    cells += com.vibetoon.domain.project.FrameCell(i, item.file)
                                } else {
                                    val cellName = "m${id}_c${cells.size}.png"   // служебные кадры покадрового слоя
                                    val dest = projects.mediaFile(pid, cellName)
                                    dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                    written += dest
                                    cells += com.vibetoon.domain.project.FrameCell(i, "assets/$cellName")
                                }
                            } finally { bmp.recycle() }
                        }
                        Triple(checkNotNull(first) { "Не удалось получить кадры видео" }, cells.toList(), Triple(plan, hasAudio, dur))
                    } finally { runCatching { r.release() } }
                }
                val (item, cells, rest) = result
                val (plan, hasAudio, dur) = rest
                if (projectId != pid || closing) { withContext(Dispatchers.IO) { written.forEach { it.delete() } }; return@launchImport }
                // звук видео: тот же файл - аудиодорожка проекта (поверх символа, один раз от кадра 0)
                val audio = if (!hasAudio) null else withContext(Dispatchers.IO) {
                    val aid = nextMediaId()   // идентификаторы монотонны: не совпадёт со слоем кадров
                    val fileName = "m${aid}_${java.util.UUID.randomUUID()}.mp4"
                    val dest = projects.mediaFile(pid, fileName)
                    runCatching { src.copyTo(dest, overwrite = true); written += dest; MediaItem(aid, MediaKind.AUDIO, dn("${name.substringBeforeLast('.')} (звук)"), "assets/$fileName", durationMs = dur) }.getOrNull()
                }
                checkpoint("add", force = true)
                val title = name.substringBeforeLast('.').ifBlank { dn("Видео") }
                val layer = item.copy(name = title, cells = if (cells.size > 1) cells else emptyList())
                val total = plan.frames
                val gid = nextMediaId()
                val n = media.count { it.kind == MediaKind.GROUP && it.symDur > 0 } + 1
                media += MediaItem(gid, MediaKind.GROUP, dn("Символ $n"), "", symDur = total)
                regroup = regroup + (layer.id to gid)
                media += layer
                val iid = nextMediaId()
                media += MediaItem(iid, MediaKind.SYMBOL, dn("Символ $n"), "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid, symLoop = true)
                if (audio != null) media += audio
                // проект короче видео - продлевается, чтобы символ проигрался целиком
                val st = _animation.value.settings
                val extendPlan = com.vibetoon.domain.project.MediaAutoExtend.planFrames(st.durationFrames, total, autoExtendMediaOn())
                val extend = extendPlan != null
                if (extendPlan != null) {
                    meta = meta?.copy(durationFrames = extendPlan.frames)
                    _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, extendPlan.frames))
                }
                inserted = true
                rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
                // спринт 30: сообщение - целая фраза-шаблон из словаря, а не склейка кусков
                val base = if (plan.cut) "Видео обрезано до ${cells.size} кадров (больше за раз не импортируется)" else "Видео (${cells.size} кадров) импортировано как символ «Символ $n»: крутится по кругу"
                _state.value = UiState.Message(when {
                    audio != null && extend -> "$base. Звук - отдельная дорожка. Проект продлён до конца видео"
                    audio != null -> "$base. Звук - отдельная дорожка"
                    extend -> "$base. Проект продлён до конца видео"
                    else -> base
                })
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                Log.e("VT/Media", "import video as symbol failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось импортировать видео")
            } finally {
                if (!inserted) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { written.forEach { it.delete() } }
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { src.delete(); tmp.delete() }
            }
        }
    }

    /**
     * Пакет 11: секвенция картинок (несколько файлов) - один покадровый слой, рисунок на кадр, порядок по именам
     * (frame2 раньше frame10). [asSymbol] - слой кладётся в новый символ, экземпляр крутится по кругу.
     */
    fun importImageSequence(uris: List<Uri>, asSymbol: Boolean) {
        val pid = projectId ?: return
        if (uris.isEmpty()) return
        if (uris.size == 1) { if (asSymbol) importMediaAsSymbol(uris[0]) else importMedia(MediaKind.IMAGE, uris[0]); return }
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = nextMediaId()
        _state.value = UiState.Loading
        importJob = launchImport {
            val copied = ArrayList<java.io.File>()
            var inserted = false
            try {
                val names = withContext(Dispatchers.IO) { uris.map { importer.displayName(it) } }
                val order = com.vibetoon.domain.project.SequenceImport.order(names)
                val picked = order.take(com.vibetoon.domain.project.SequenceImport.MAX_FRAMES)
                val (first, cells) = withContext(decodeDispatcher) {
                    val item = importMediaBlocking(pid, id, MediaKind.IMAGE, uris[picked[0]])
                    copied += projects.fileOf(pid, item.file)
                    val files = arrayListOf(item.file)
                    for (k in 1 until picked.size) {
                        val name = "m${id}_c$k.png"   // кадры покадрового слоя: служебные файлы, в библиотеке не видны
                        val dest = projects.mediaFile(pid, name)
                        importer.copy(uris[picked[k]], dest)
                        copied += dest
                        files += "assets/$name"
                    }
                    item to com.vibetoon.domain.project.SequenceImport.cells(files)
                }
                if (projectId != pid || closing) { copied.forEach { it.delete() }; return@launchImport }
                checkpoint("add", force = true)
                val title = com.vibetoon.domain.project.SequenceImport.title(names[picked[0]])
                val layer = first.copy(name = title, cells = cells)
                val frames = cells.size
                val cut = order.size > picked.size
                if (asSymbol) {
                    val gid = nextMediaId()
                    val n = media.count { it.kind == MediaKind.GROUP && it.symDur > 0 } + 1
                    media += MediaItem(gid, MediaKind.GROUP, dn("Символ $n"), "", symDur = frames)
                    regroup = regroup + (layer.id to gid)
                    media += layer
                    val iid = nextMediaId()
                    media += MediaItem(iid, MediaKind.SYMBOL, dn("Символ $n"), "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid, symLoop = true)
                    inserted = true
                    rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
                    _state.value = UiState.Message(if (cut) "Секвенция обрезана до $frames кадров (больше за раз не импортируется)" else "Секвенция ($frames кадров) импортирована как символ «Символ $n»")
                } else {
                    val st = _animation.value.settings
                    val extend = frames > st.durationFrames
                    if (extend) {
                        val d = frames.coerceAtMost(TimeFormat.MAX_DURATION_FRAMES)
                        meta = meta?.copy(durationFrames = d)
                        _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, d))
                    }
                    media += layer
                    inserted = true
                    rebuildLayers(); pushScene(); selectLayer(layer.id); markDirty()
                    updateCells(_frame.value)
                    _state.value = UiState.Message(when {
                        cut -> "Секвенция обрезана до $frames кадров (больше за раз не импортируется)"
                        extend -> "Секвенция импортирована: $frames кадров. Проект продлён до конца секвенции"
                        else -> "Секвенция импортирована: $frames кадров"
                    })
                }
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                if (!inserted) withContext(Dispatchers.IO) { copied.forEach { it.delete() } }
                Log.e("VT/Media", "sequence import failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось импортировать секвенцию")
            }
        }
    }

    /** Пакет 15: выбранный файл - GIF (по типу или имени; без чтения файла на главном потоке). */
    private fun isGifUri(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.getType(uri) == "image/gif" || importer.displayName(uri).lowercase().endsWith(".gif")
    }.getOrDefault(false)

    /**
     * Пакет 15: анимированный GIF - покадровый слой (кадр GIF -> рисунок на своём кадре проекта по задержкам GIF)
     * или символ с этим слоем, который крутится по кругу. Кадры собираются декодером с учётом прозрачности и очистки;
     * GIF быстрее FPS проекта прореживается. Однокадровый GIF становится обычной картинкой.
     */
    fun importGif(uri: Uri, asSymbol: Boolean) {
        val pid = projectId ?: return
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = nextMediaId()
        _state.value = UiState.Loading
        importJob = launchImport {
            val written = ArrayList<java.io.File>()
            var inserted = false
            val tmp = java.io.File(getApplication<Application>().cacheDir, "gif_${java.util.UUID.randomUUID()}.png")
            try {
                val fps = _animation.value.settings.fps
                val name = withContext(Dispatchers.IO) { importer.displayName(uri) }
                val result = withContext(decodeDispatcher) {
                    val cells = ArrayList<com.vibetoon.domain.project.FrameCell>()
                    var first: MediaItem? = null
                    var gw = 0; var gh = 0
                    val placer = com.vibetoon.domain.project.GifDecoder.Placer(fps)
                    val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                    val info = input.use { stream ->
                        com.vibetoon.domain.project.GifDecoder.decode(stream, maxPixels = 2048 * 2048, onSize = { w, h -> gw = w; gh = h }) { _, argb, delay ->
                            val start = placer.place(delay) ?: return@decode   // GIF быстрее FPS проекта: кадр пропускается
                            if (projectId != pid || closing) throw kotlinx.coroutines.CancellationException()
                            val bmp = android.graphics.Bitmap.createBitmap(argb, gw, gh, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                if (first == null) {
                                    tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                    val item = importMediaBlocking(pid, id, MediaKind.IMAGE, Uri.fromFile(tmp))
                                    written += projects.fileOf(pid, item.file)
                                    first = item
                                    cells += com.vibetoon.domain.project.FrameCell(start, item.file)
                                } else {
                                    val cellName = "m${id}_c${cells.size}.png"   // служебные кадры покадрового слоя
                                    val dest = projects.mediaFile(pid, cellName)
                                    dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                                    written += dest
                                    cells += com.vibetoon.domain.project.FrameCell(start, "assets/$cellName")
                                }
                            } finally { bmp.recycle() }
                        }
                    }
                    Triple(checkNotNull(first) { "В GIF нет кадров" }, cells.toList(), info to placer.total)
                }
                val (item, cells, rest) = result
                val (info, total) = rest
                if (projectId != pid || closing) { withContext(Dispatchers.IO) { written.forEach { it.delete() } }; return@launchImport }
                checkpoint("add", force = true)
                val title = name.substringBeforeLast('.').ifBlank { "GIF" }
                val layer = item.copy(name = title, cells = if (cells.size > 1) cells else emptyList())
                val cut = info.frames >= com.vibetoon.domain.project.GifDecoder.MAX_FRAMES
                if (asSymbol && cells.size > 1) {
                    val gid = nextMediaId()
                    val n = media.count { it.kind == MediaKind.GROUP && it.symDur > 0 } + 1
                    media += MediaItem(gid, MediaKind.GROUP, dn("Символ $n"), "", symDur = total)
                    regroup = regroup + (layer.id to gid)
                    media += layer
                    val iid = nextMediaId()
                    media += MediaItem(iid, MediaKind.SYMBOL, dn("Символ $n"), "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid, symLoop = true)
                    inserted = true
                    rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
                    _state.value = UiState.Message(if (cut) "GIF обрезан до ${cells.size} кадров (больше за раз не импортируется)" else "GIF (${cells.size} кадров) импортирован как символ «Символ $n»: крутится по кругу")
                } else {
                    val st = _animation.value.settings
                    val extend = cells.size > 1 && total > st.durationFrames
                    if (extend) {
                        val d = total.coerceAtMost(TimeFormat.MAX_DURATION_FRAMES)
                        meta = meta?.copy(durationFrames = d)
                        _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, d))
                    }
                    media += layer
                    inserted = true
                    rebuildLayers(); pushScene(); selectLayer(layer.id); markDirty()
                    updateCells(_frame.value)
                    _state.value = UiState.Message(when {
                        cells.size <= 1 -> "GIF без анимации импортирован как картинка"
                        cut -> "GIF обрезан до ${cells.size} кадров (больше за раз не импортируется)"
                        extend -> "GIF импортирован: ${cells.size} кадров. Проект продлён до конца GIF"
                        else -> "GIF импортирован: ${cells.size} кадров"
                    })
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { if (!inserted) written.forEach { it.delete() } }
                throw t
            } catch (t: Throwable) {
                if (!inserted) withContext(Dispatchers.IO) { written.forEach { it.delete() } }
                Log.e("VT/Media", "gif import failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(
                    if (t is com.vibetoon.domain.project.GifDecoder.GifException) "GIF не прочитан: файл повреждён или слишком большой" else t.message ?: "Не удалось импортировать GIF")
            } finally {
                tmp.delete()
            }
        }
    }

    fun importMedia(kind: MediaKind, uri: Uri) {
        val pid = projectId ?: return
        if (kind == MediaKind.IMAGE && isGifUri(uri)) { importGif(uri, asSymbol = false); return }   // пакет 15
        if (kind == MediaKind.IMAGE && insertIntoDrawingTarget() != null) { insertImageIntoDrawing(uri); return }   // пакет 17
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = nextMediaId()
        _state.value = UiState.Loading
        _audioReadiness.value = if (kind == MediaKind.AUDIO) com.vibetoon.domain.project.AudioReadiness.Preparing("Аудио") else com.vibetoon.domain.project.AudioReadiness.Idle
        importJob = launchImport {
            var imported: MediaItem? = null
            var inserted = false
            try {
                val item = withContext(decodeDispatcher) { importMediaBlocking(pid, id, kind, uri) }
                imported = item
                if (item.kind == MediaKind.AUDIO) {
                    _audioReadiness.value = com.vibetoon.domain.project.AudioReadiness.Preparing(item.name)
                    val prepared = kotlinx.coroutines.withTimeoutOrNull(120_000) { audioEngine.awaitScrubReady(projects.fileOf(pid, item.file)) }
                        ?: throw IllegalStateException("Время подготовки аудио истекло")
                    prepared.getOrElse { throw IllegalStateException("Не удалось подготовить аудио", it) }
                    if (projectId != pid || closing) return@launchImport
                }
                checkpoint("add", force = true)   // один снимок откатывает и слой, и продление проекта
                // длинный звук: проект продлевается до его конца (не дольше предела хронометража)
                // спринт 41: то же правило для видеоклипа (раньше продлевалось только под аудио)
                val extended = extendForMedia(item)   // 1 - продлён до конца, 2 - до максимума (конец не поместился)
                if (item.kind == MediaKind.AUDIO) {
                    check(audioEngine.peaksFor(projects.fileOf(pid, item.file))?.isNotEmpty() == true) { "Не удалось подготовить волну аудио" }
                    waveDrawWait = com.vibetoon.domain.project.WaveDrawBarrier(item.id)
                }
                media += item
                inserted = true
                rebuildLayers()
                pushScene()
                if (item.isVisual) selectLayer(item.id)
                markDirty()
                if (item.kind == MediaKind.AUDIO) {
                    val drawn = kotlinx.coroutines.withTimeoutOrNull(20_000) { checkNotNull(waveDrawWait).drawn.await(); true } ?: false
                    waveDrawWait = null
                    if (!drawn) throw IllegalStateException("Волна не появилась на таймлайне: откройте дорожку; импортированный файл сохранён")
                }
                if (item.kind == MediaKind.AUDIO) _audioReadiness.value = com.vibetoon.domain.project.AudioReadiness.Ready(item.name)
                _state.value = UiState.Message(when {
                    extended == 2 && item.kind == MediaKind.VIDEO -> "Добавлено: ${item.name}. Проект продлён до максимума, конец видео не поместился"
                    extended == 1 && item.kind == MediaKind.VIDEO -> "Добавлено: ${item.name}. Проект продлён до конца видео"
                    extended == 2 -> "Добавлено: ${item.name}. Проект продлён до максимума, конец звука не поместился"
                    extended == 1 -> "Добавлено: ${item.name}. Проект продлён до конца звука"
                    else -> "Добавлено: ${item.name}"
                })
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (kind == MediaKind.AUDIO) {
                    _audioReadiness.value = com.vibetoon.domain.project.AudioReadiness.Failed(imported?.name ?: "Аудио")
                    imported?.let { item ->
                        if (!inserted) withContext(Dispatchers.IO) { projects.fileOf(pid, item.file).delete() }
                    }
                }
                waveDrawWait = null
                Log.e("VT/Media", "import failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось добавить файл")
            }
        }
    }

    /** Спринт 41: «Продлевать проект под длину аудио и видео» (по умолчанию вкл.). */
    private fun autoExtendMediaOn(): Boolean = getApplication<Application>()
        .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean(com.vibetoon.domain.project.MediaAutoExtend.PREF, true)

    /**
     * Спринт 41: проект продлевается до конца добавленного аудио ИЛИ видео (раньше - только аудио). Вызывать после
     * checkpoint: одна отмена убирает и слой, и продление. 0 - не продлён, 1 - до конца медиа, 2 - до предела длительности.
     */
    private fun extendForMedia(item: MediaItem): Int {
        if (item.kind != MediaKind.AUDIO && item.kind != MediaKind.VIDEO) return 0
        val st = _animation.value.settings
        val plan = com.vibetoon.domain.project.MediaAutoExtend.plan(st.durationFrames, item.durationMs, st.fps, autoExtendMediaOn()) ?: return 0
        meta = meta?.copy(durationFrames = plan.frames)
        _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, plan.frames))
        return if (plan.clipped) 2 else 1
    }

    private fun resolutionLimitOn(): Boolean = getApplication<Application>()
        .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("import_resolution_limit", false)

    private fun importMediaBlocking(pid: String, id: Int, kind: MediaKind, uri: Uri): MediaItem {
        val name = importer.displayName(uri)
        val ext = importer.extension(
            name,
            when (kind) { MediaKind.IMAGE -> "png"; MediaKind.VIDEO -> "mp4"; MediaKind.AUDIO -> "mp3"; MediaKind.TEXT -> error("Текст добавляется через addText"); MediaKind.CAMERA -> error("Камера добавляется через addCameraLayer"); MediaKind.NULLOBJ -> error("Transform Layer создаётся через addNullLayer"); MediaKind.PATH, MediaKind.BONES -> error("Слой пути и слой костей создаются из меню «＋»"); MediaKind.GROUP -> error("Группа создаётся через groupSelected"); MediaKind.SYMBOL -> error("Символ создаётся через createSymbol") }
        )
        // Файл импорта всегда уникален: PCM-кэш и отложенное декодирование не должны
        // ссылаться на новый файл с повторно использованным id после Undo/отмены.
        val fileName = "m${id}_${java.util.UUID.randomUUID()}.$ext"
        val dest = projects.mediaFile(pid, fileName)
        try {
            importer.copy(uri, dest)
            val rel = "assets/$fileName"
            if (kind == MediaKind.IMAGE && resolutionLimitOn())   // пакет 12: Import → Resolution Limit
                importer.downscaleFile(dest, com.vibetoon.domain.project.ImportLimit.maxSide(canvasW, canvasH))
            if (kind == MediaKind.AUDIO) {
                return MediaItem(id, kind, name, rel, durationMs = importer.audioDurationMs(dest))
            }
            val ticket = RenderNative.textureUploadTicket(id)
            val decoded = if (kind == MediaKind.IMAGE) importer.decodeImage(dest) else importer.decodeVideoFrame(dest)
            val r = ProjectMath.fitInside(decoded.srcWidth, decoded.srcHeight, canvasW, canvasH)
            val ok = RenderNative.uploadBitmapIfCurrent(id, decoded.bitmap, ticket)
            decoded.bitmap.recycle()
            if (!ok) error("Не удалось передать изображение в графику")
            return MediaItem(id, kind, name, rel, decoded.srcWidth, decoded.srcHeight, r[0], r[1], r[2], r[3], decoded.durationMs)
        } catch (t: Throwable) {
            dest.delete()   // не оставляем в проекте файл, который не удалось обработать
            throw t
        }
    }

    // ---------------------------------------------------------------- библиотека ассетов

    /**
     * Библиотека ассетов: картинки слоёв проекта (с названием слоя) и файлы удалённых слоёв; кэш (миниатюры маркеров,
     * Liquify, снимки выделения, кадры покадровых слоёв) скрыт - см. [com.vibetoon.domain.project.AssetCatalog].
     */
    fun assetLibrary(): List<com.vibetoon.domain.project.AssetCatalog.Entry> {
        val pid = projectId ?: return emptyList()
        val dir = projects.mediaDir(pid)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        val (used, cells) = assetRefs()
        return com.vibetoon.domain.project.AssetCatalog.library(files, used, cells)
    }

    /** Сколько служебных файлов скрыто из библиотеки (для подписи). */
    fun assetServiceCount(): Int {
        val pid = projectId ?: return 0
        val files = projects.mediaDir(pid).listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
        val (used, cells) = assetRefs()
        return com.vibetoon.domain.project.AssetCatalog.serviceCount(files, used, cells)
    }

    private fun assetRefs(): Pair<Map<String, String>, Set<String>> {
        val used = HashMap<String, String>()
        val cells = HashSet<String>()
        for (m in media) {
            if ((m.kind == MediaKind.VIDEO || m.kind == MediaKind.AUDIO) && m.file.isNotEmpty()) { used[m.file.substringAfterLast('/')] = m.name; continue }   // пакет 11
            if (m.kind != MediaKind.IMAGE || m.file.isEmpty()) continue
            if (m.cells.isNotEmpty()) { m.cells.forEach { cells += it.file.substringAfterLast('/') }; continue }
            used[m.file.substringAfterLast('/')] = m.name
        }
        return used to cells
    }

    /** Миниатюра ассета (~160 px), декодируется в фоне с уменьшением - без загрузки полной картинки в память. */
    suspend fun assetThumb(rel: String): androidx.compose.ui.graphics.ImageBitmap? {
        val pid = projectId ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = projects.fileOf(pid, rel)
                when (com.vibetoon.domain.project.AssetCatalog.typeOf(rel)) {
                    com.vibetoon.domain.project.AssetCatalog.Type.AUDIO, com.vibetoon.domain.project.AssetCatalog.Type.PSD -> return@runCatching null
                    com.vibetoon.domain.project.AssetCatalog.Type.VIDEO -> {
                        // пакет 11: первый кадр видео (уменьшенный)
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(f.absolutePath)
                            val b = r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching null
                            val k = 160f / maxOf(b.width, b.height).coerceAtLeast(1)
                            val sb = if (k < 1f) android.graphics.Bitmap.createScaledBitmap(b, maxOf(1, (b.width * k).toInt()), maxOf(1, (b.height * k).toInt()), true).also { if (it !== b) b.recycle() } else b
                            return@runCatching sb.asImageBitmap()
                        } finally { runCatching { r.release() } }
                    }
                    else -> Unit
                }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 160) sample *= 2
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }

    /** Вставка ассета из библиотеки как новый слой-картинку; видео и аудио (пакет 11) - новым видео-слоем / дорожкой. */
    fun insertAsset(relPath: String) {
        val pid = projectId ?: return
        when (com.vibetoon.domain.project.AssetCatalog.typeOf(relPath)) {
            com.vibetoon.domain.project.AssetCatalog.Type.VIDEO, com.vibetoon.domain.project.AssetCatalog.Type.AUDIO -> { insertMediaAsset(pid, relPath); return }
            com.vibetoon.domain.project.AssetCatalog.Type.PSD -> {
                // пакет 23: другой PSD из библиотеки раскладывается в медиа-слои по PSD Import Mode
                if (com.vibetoon.domain.project.PsdInsertPlan.isProjectPsd(relPath.substringAfterLast('/'))) _state.value = UiState.Message("PSD проекта уже разложен на слои: выберите другой PSD или «+ → PSD в текущий проект»")
                else if (importJob?.isActive != true && _state.value != UiState.Loading && _state.value !is UiState.Rendering) {
                    _state.value = UiState.Loading
                    insertPsdFile(pid, relPath, com.vibetoon.domain.project.PsdInsertPlan.titleOf(relPath), deleteOnFail = false)
                }
                return
            }
            else -> Unit
        }
        val id = nextMediaId()
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val decoded = importer.decodeImage(projects.fileOf(pid, relPath))
                    val r = ProjectMath.fitInside(decoded.srcWidth, decoded.srcHeight, canvasW, canvasH)
                    val ok = RenderNative.uploadBitmapIfCurrent(id, decoded.bitmap, ticket)
                    decoded.bitmap.recycle()
                    if (!ok) error("Не удалось передать изображение в графику")
                    MediaItem(id, MediaKind.IMAGE, relPath.substringAfterLast('/'), relPath, decoded.srcWidth, decoded.srcHeight, r[0], r[1], r[2], r[3])
                }
                if (projectId != pid || closing) return@launch
                checkpoint("add", force = true)
                media += item
                rebuildLayers(); pushScene(); selectLayer(item.id); markDirty()
                _state.value = UiState.Message("Вставлено из библиотеки: ${item.name}")
            } catch (t: Throwable) {
                Log.e("VT/Media", "insert asset failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось вставить ассет")
            }
        }
    }

    // ---------------------------------------------------------------- пакет 23: другой PSD в проекте, папки библиотеки

    /** + → «PSD в текущий проект»: файл копируется в assets проекта (виден в библиотеке) и вставляется по PSD Import Mode. */
    fun insertPsdFromUri(uri: Uri) {
        val pid = projectId ?: return
        if (importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        _state.value = UiState.Loading
        importJob = launchImport {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val name = importer.displayName(uri)
                    val fileName = com.vibetoon.domain.project.PsdInsertPlan.assetName(name, java.util.UUID.randomUUID().toString().replace("-", ""))
                    val dest = projects.mediaFile(pid, fileName)
                    try {
                        importer.copy(uri, dest)
                        val head = ByteArray(4).also { b -> dest.inputStream().use { it.read(b) } }
                        check(com.vibetoon.domain.project.ProjectArchive.detect(head) == com.vibetoon.domain.project.ProjectArchive.Kind.PSD) { "Это не PSD-файл" }
                    } catch (t: Throwable) { dest.delete(); throw t }
                    "assets/$fileName" to name
                }
            }
            if (projectId != pid || closing) { res.getOrNull()?.let { (rel, _) -> withContext(Dispatchers.IO) { projects.fileOf(pid, rel).delete() } }; return@launchImport }
            res.onSuccess { (rel, name) -> insertPsdFile(pid, rel, com.vibetoon.domain.project.PsdInsertPlan.titleOf(name), deleteOnFail = true) }
                .onFailure { _state.value = UiState.Error(it.message ?: "Не удалось вставить PSD") }
        }
    }

    /** Декодированный слой PSD -> Bitmap (без premultiply); null - слой пуст. */
    private fun psdLayerBitmap(doc: Long, layerId: Int, scale: Float, limit: Int): android.graphics.Bitmap? {
        val px = PsdNative.decodePixels(doc, layerId, scale, limit) ?: return null
        if (px.size < 3 || px[0] <= 0 || px[1] <= 0 || px.size < 2 + px[0] * px[1]) return null
        return android.graphics.Bitmap.createBitmap(px, 2, px[0], px[0], px[1], android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun writePng(pid: String, fileName: String, bmp: android.graphics.Bitmap, written: MutableList<java.io.File>): String {
        val dest = projects.mediaFile(pid, fileName)
        dest.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        written += dest
        return "assets/$fileName"
    }

    /**
     * Вставка PSD [rel] из assets проекта. Режим - Settings → Import → PSD Import Mode: слои и группы как в файле
     * (медиа-слои в общей группе с именем файла), одним сведённым слоем или кадрами покадрового слоя. Документ вписывается
     * в холст целиком; слои и анимация PSD проекта не меняются. Вызывается при состоянии Loading.
     */
    private fun insertPsdFile(pid: String, rel: String, title: String, deleteOnFail: Boolean) {
        val mode = com.vibetoon.domain.project.PsdInsertPlan.Mode.of(appPrefs().getInt("psd_import_mode", 0))
        val limit = if (resolutionLimitOn()) com.vibetoon.domain.project.ImportLimit.maxSide(canvasW, canvasH) else MAX_TEX
        val cw = canvasW; val ch = canvasH
        importJob = launchImport {
            val written = ArrayList<java.io.File>()
            var inserted = false
            var doc = 0L
            try {
                val file = projects.fileOf(pid, rel)
                val parsed = withContext(decodeDispatcher) {
                    check(file.isFile) { "Файл PSD не найден" }
                    val layers = PsdNative.parseLayers(file.absolutePath).toList()
                    doc = PsdNative.openDocument(file.absolutePath)
                    val size = PsdNative.documentSize(doc)
                    Triple(layers, size[0], size[1])
                }
                val (layers, dw, dh) = parsed
                check(dw > 0 && dh > 0) { "Не удалось прочитать PSD" }
                val f = com.vibetoon.domain.project.PsdInsertPlan.fit(dw, dh, cw, ch)
                val fx = f[0]; val fy = f[1]; val s = f[2]
                val ds = com.vibetoon.domain.project.PsdInsertPlan.decodeScale(s)
                val byId = layers.associateBy { it.id }
                val uniq = java.util.UUID.randomUUID().toString().take(8)
                fun piece(psdId: Int): com.vibetoon.data.PsdComposer.Piece? {
                    val l = byId[psdId] ?: return null
                    val b = psdLayerBitmap(doc, psdId, ds, limit) ?: return null
                    return com.vibetoon.data.PsdComposer.Piece(b, android.graphics.RectF(l.x * ds, l.y * ds, (l.x + l.width) * ds, (l.y + l.height) * ds))
                }
                val compW = kotlin.math.ceil(dw * ds).toInt().coerceAtLeast(1)
                val compH = kotlin.math.ceil(dh * ds).toInt().coerceAtLeast(1)
                val message: String
                val select: Int
                var extendTo = 0
                var cellsOf: Int? = null
                when (mode) {
                    com.vibetoon.domain.project.PsdInsertPlan.Mode.LAYERS -> {
                        val nodes = com.vibetoon.domain.project.PsdInsertPlan.tree(layers)
                        check(nodes.isNotEmpty()) { "В PSD нет слоёв с пикселями" }
                        val rootId = nextMediaId()
                        val ids = nodes.associate { it.psdId to nextMediaId() }
                        val tickets = nodes.filter { !it.group }.associate { n -> ids.getValue(n.psdId).let { it to RenderNative.textureUploadTicket(it) } }
                        val items = withContext(decodeDispatcher) {
                            val out = HashMap<Int, MediaItem>()
                            for (n in nodes) {
                                if (n.group) continue
                                if (projectId != pid || closing) throw kotlinx.coroutines.CancellationException()
                                val mid = ids.getValue(n.psdId)
                                val bmp = psdLayerBitmap(doc, n.psdId, ds, limit) ?: continue   // пустой/текстовый без пикселей - пропуск
                                try {
                                    val path = writePng(pid, "m${mid}_$uniq.png", bmp, written)
                                    RenderNative.uploadBitmapIfCurrent(mid, bmp, tickets.getValue(mid))
                                    out[n.psdId] = MediaItem(mid, MediaKind.IMAGE, n.name.ifBlank { dn("Слой") }, path, bmp.width, bmp.height,
                                        fx + n.x * s, fy + n.y * s, n.w * s, n.h * s)
                                } finally { bmp.recycle() }
                            }
                            out
                        }
                        if (projectId != pid || closing) return@launchImport
                        // группы без вставленных слоёв не создаются (дети в списке раньше своей группы)
                        val present = HashSet<Int>()
                        for (n in nodes) if ((!n.group && n.psdId in items) || (n.group && nodes.any { it.parent == n.psdId && it.psdId in present })) present += n.psdId
                        check(present.isNotEmpty()) { "В PSD нет слоёв с пикселями" }
                        checkpoint("add", force = true)
                        val newProps = HashMap<Int, com.vibetoon.domain.model.LayerProps>()
                        val links = ArrayList<Pair<Int, Int>>()
                        for (n in nodes) {
                            if (n.psdId !in present) continue
                            val mid = ids.getValue(n.psdId)
                            media += items[n.psdId] ?: MediaItem(mid, MediaKind.GROUP, n.name.ifBlank { dn("Группа") }, "")
                            links += mid to (if (n.parent < 0) rootId else ids.getValue(n.parent))
                            val p = com.vibetoon.domain.model.LayerProps(
                                opacity = n.opacity.coerceIn(0f, 1f),
                                blend = if (n.group) "norm" else com.vibetoon.domain.project.PsdInsertPlan.layerBlend(n.blend),
                                clip = n.clipped && !n.group, visible = n.visible)
                            if (p != com.vibetoon.domain.model.LayerProps()) newProps[mid] = p
                        }
                        media += MediaItem(rootId, MediaKind.GROUP, title, "")
                        regroup = regroup + links
                        if (newProps.isNotEmpty()) _layerProps.value = _layerProps.value + newProps
                        select = rootId
                        message = "PSD «$title» вставлен: слоёв ${items.size}"
                    }
                    com.vibetoon.domain.project.PsdInsertPlan.Mode.FLAT -> {
                        val ops = com.vibetoon.domain.project.PsdInsertPlan.composeOps(layers)
                        check(ops.isNotEmpty()) { "В PSD нет видимых слоёв с пикселями" }
                        val id = nextMediaId()
                        val ticket = RenderNative.textureUploadTicket(id)
                        val item = withContext(decodeDispatcher) {
                            val bmp = com.vibetoon.data.PsdComposer.compose(compW, compH, ops, ::piece)
                            try {
                                val path = writePng(pid, "m${id}_$uniq.png", bmp, written)
                                RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                                MediaItem(id, MediaKind.IMAGE, title, path, bmp.width, bmp.height, fx, fy, dw * s, dh * s)
                            } finally { bmp.recycle() }
                        }
                        if (projectId != pid || closing) return@launchImport
                        checkpoint("add", force = true)
                        media += item
                        select = id
                        message = "PSD «$title» вставлен одним слоем"
                    }
                    com.vibetoon.domain.project.PsdInsertPlan.Mode.FRAMES -> {
                        val frames = com.vibetoon.domain.project.PsdInsertPlan.frames(layers)
                        check(frames.isNotEmpty()) { "В PSD нет видимых слоёв с пикселями" }
                        val cut = com.vibetoon.domain.project.PsdInsertPlan.frameCandidates(layers) > frames.size
                        val id = nextMediaId()
                        val ticket = RenderNative.textureUploadTicket(id)
                        val item = withContext(decodeDispatcher) {
                            val cells = ArrayList<com.vibetoon.domain.project.FrameCell>()
                            var first: MediaItem? = null
                            for ((i, fr) in frames.withIndex()) {
                                if (projectId != pid || closing) throw kotlinx.coroutines.CancellationException()
                                val bmp = com.vibetoon.data.PsdComposer.compose(compW, compH, fr.ops, ::piece)
                                try {
                                    if (first == null) {
                                        val path = writePng(pid, "m${id}_$uniq.png", bmp, written)
                                        RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                                        first = MediaItem(id, MediaKind.IMAGE, title, path, bmp.width, bmp.height, fx, fy, dw * s, dh * s)
                                        cells += com.vibetoon.domain.project.FrameCell(i, path)
                                    } else cells += com.vibetoon.domain.project.FrameCell(i, writePng(pid, "m${id}_c${cells.size}.png", bmp, written))
                                } finally { bmp.recycle() }
                            }
                            checkNotNull(first).copy(cells = if (cells.size > 1) cells else emptyList())
                        }
                        if (projectId != pid || closing) return@launchImport
                        checkpoint("add", force = true)
                        media += item
                        if (item.cells.size > 1) cellsOf = item.id
                        extendTo = frames.size
                        select = id
                        message = if (cut) "PSD «$title» вставлен как покадровый слой: кадров ${frames.size} (больше за раз не импортируется)"
                            else "PSD «$title» вставлен как покадровый слой: кадров ${frames.size}"
                    }
                }
                val st = _animation.value.settings
                if (extendTo > st.durationFrames) {
                    val d = extendTo.coerceAtMost(TimeFormat.MAX_DURATION_FRAMES)
                    meta = meta?.copy(durationFrames = d)
                    _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, d))
                }
                inserted = true
                rebuildLayers(); pushScene(); selectLayer(select); markDirty()
                if (cellsOf != null) updateCells(_frame.value)
                _state.value = UiState.Message(message)
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                Log.e("VT/Media", "insert psd failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось вставить PSD")
            } finally {
                if (doc != 0L) runCatching { PsdNative.closeDocument(doc) }
                if (!inserted) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    written.forEach { it.delete() }
                    if (deleteOnFail) projects.fileOf(pid, rel).delete()
                }
            }
        }
    }

    /** Пользовательские папки библиотеки (файл проекта); файлы, которых нет в assets, не показываются. */
    fun libraryFolders(): com.vibetoon.domain.project.LibraryFolders {
        val pid = projectId ?: return com.vibetoon.domain.project.LibraryFolders()
        val text = runCatching { projects.fileOf(pid, com.vibetoon.domain.project.LibraryFolders.FILE).takeIf { it.isFile }?.readText() }.getOrNull()
        val existing = projects.mediaDir(pid).listFiles()?.filter { it.isFile }?.mapTo(HashSet()) { it.name }.orEmpty()
        return com.vibetoon.domain.project.LibraryFolders.decode(text).prune(existing)
    }

    /** Сохранить папки библиотеки; false - не записалось. */
    fun saveLibraryFolders(folders: com.vibetoon.domain.project.LibraryFolders): Boolean {
        val pid = projectId ?: return false
        return runCatching {
            val f = projects.fileOf(pid, com.vibetoon.domain.project.LibraryFolders.FILE)
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.writeText(folders.encode())
            if (!tmp.renameTo(f)) { f.writeText(folders.encode()); tmp.delete() }
            true
        }.getOrDefault(false)
    }

    /** Видео или аудио из библиотеки: тот же файл проекта (без копии), новый слой или дорожка. */
    private fun insertMediaAsset(pid: String, relPath: String) {
        if (importJob?.isActive == true) return
        val id = nextMediaId()
        val video = com.vibetoon.domain.project.AssetCatalog.typeOf(relPath) == com.vibetoon.domain.project.AssetCatalog.Type.VIDEO
        val ticket = RenderNative.textureUploadTicket(id)
        val title = media.firstOrNull { it.file == relPath }?.name ?: relPath.substringAfterLast('/')
        importJob = launchImport {
            try {
                val item = withContext(decodeDispatcher) {
                    val f = projects.fileOf(pid, relPath)
                    check(f.exists()) { "Файл ассета не найден" }
                    if (!video) MediaItem(id, MediaKind.AUDIO, title, relPath, durationMs = importer.audioDurationMs(f))
                    else {
                        val d = importer.decodeVideoFrame(f)
                        val r = ProjectMath.fitInside(d.srcWidth, d.srcHeight, canvasW, canvasH)
                        val ok = RenderNative.uploadBitmapIfCurrent(id, d.bitmap, ticket)
                        d.bitmap.recycle()
                        if (!ok) error("Не удалось передать изображение в графику")
                        MediaItem(id, MediaKind.VIDEO, title, relPath, d.srcWidth, d.srcHeight, r[0], r[1], r[2], r[3], d.durationMs)
                    }
                }
                if (projectId != pid || closing) return@launchImport
                checkpoint("add", force = true)
                val extended = extendForMedia(item)   // спринт 41: аудио и видео из библиотеки тоже продлевают проект
                media += item
                rebuildLayers(); pushScene(); if (item.isVisual) selectLayer(item.id); markDirty()
                _state.value = UiState.Message(if (extended > 0) "Вставлено из библиотеки: ${item.name}. Проект продлён" else "Вставлено из библиотеки: ${item.name}")
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                Log.e("VT/Media", "insert media asset failed", t)
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось вставить ассет")
            }
        }
    }

    // ---------------------------------------------------------------- текст и шрифты

    private val mediaIds = com.vibetoon.domain.project.MediaIdAllocator()
    private fun nextMediaId(): Int = mediaIds.next(buildList {
        addAll(media.map { it.id }); addAll(base.keys); addAll(override.keys)
        addAll(_clips.value.keys); addAll(layerProps.keys); addAll(_animation.value.tracks.keys)
        addAll(_links.value.keys); addAll(_links.value.values); addAll(regroup.flatMap { listOf(it.first, it.second) })
    })

    /** Рисует текст в Bitmap и ставит его в очередь загрузки текстуры; возвращает размер блока на холсте. */
    private fun renderAndUpload(m: MediaItem, frame: Int = _frame.value, uploadTicket: Long = RenderNative.textureUploadTicket(m.id)): Pair<Float, Float> {
        invalidateThumb(m.id)
        val fps = _animation.value.settings.fps.coerceAtLeast(1)
        val tSec = frame.toFloat() / fps
        textFxKeys[m.id] = m.fx.stateKey(tSec, fps)
        val r = textRenderer.renderFx(m.text, m.textColor.toInt(), m.font, m.textSize, m.gradient, m.gradientAngle, m.fx, tSec, fps, if (m.stroke) m.strokeWidth else 0f, m.strokeColor.toInt(), m.textAlign, m.lineSpacing, m.letterSpacing, m.textDir)
        val ok = RenderNative.uploadBitmapIfCurrent(m.id, r.bitmap, uploadTicket)
        r.bitmap.recycle()
        if (!ok) error("Не удалось передать текст в графику")
        return r.layoutWidth to r.layoutHeight
    }

    /**
     * "+" -> Текст: создаёт текстовый слой по центру холста. Текст рисуется как Bitmap (TextPaint и
     * StaticLayout) и загружается обычной текстурой, поэтому работают трансформации и ключи.
     */
    private fun cameraIdNow(): Int = media.firstOrNull { it.kind == MediaKind.CAMERA }?.id ?: -1

    /** Добавляет слой «Камера» (один на проект): его позиция, масштаб и поворот (с ключами) двигают и приближают весь холст. */
    fun addCameraLayer() {
        if (projectId == null) return
        if (cameraIdNow() >= 0) { _state.value = UiState.Message("Камера уже есть: выберите её в списке слоёв"); return }
        val id = nextMediaId()
        checkpoint("add", force = true)
        media += MediaItem(id, MediaKind.CAMERA, dn("Камера"), "", 0, 0, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
        rebuildLayers()
        pushScene()
        selectLayer(id)
        markDirty()
        _state.value = UiState.Message("Камера: X/Y - смещение кадра, Scale - приближение, Rotation - поворот; ключи на таймлайне")
    }

    /** Transform Layer (null-объект): невидимый слой-контейнер. Привяжите к нему другие слои кнопкой «Родитель» - они повторят его сдвиг, поворот и масштаб. */
    fun addNullLayer() {
        if (projectId == null || _editingSymbol.value != null) return
        val id = nextMediaId()
        val n = media.count { it.kind == MediaKind.NULLOBJ } + 1
        val s = minOf(canvasW, canvasH) * 0.25f
        checkpoint("add", force = true)
        media += MediaItem(id, MediaKind.NULLOBJ, "Transform $n", "", 0, 0, (canvasW - s) / 2f, (canvasH - s) / 2f, s, s)
        rebuildLayers(); pushScene(); selectLayer(id); markDirty()
        _state.value = UiState.Message("Transform Layer «Transform $n»: привяжите к нему слои через «Родитель», двигайте и вращайте его в Свойствах")
    }

    // ---------------------------------------------------------------- спринт 38: слой-путь, слой костей, маски слоёв

    /** Тип медиа-слоя (null - слой PSD или кость). */
    fun mediaKindOf(id: Int): MediaKind? = media.firstOrNull { it.id == id }?.kind

    private val _pathRevision = MutableStateFlow(0)
    /** Меняется при правке пути/прикрепления: панель перечитывает «Положение на пути». */
    val pathRevision: StateFlow<Int> = _pathRevision.asStateFlow()

    /** Слой костей, в который попадают новые кости (последний выбранный слой костей). */
    private var activeBoneLayer: Int? = null
    private fun boneLayerIds(): List<Int> = media.filter { it.kind == MediaKind.BONES }.map { it.id }
    private fun hiddenBoneLayers(): Set<Int> = media.filter { it.kind == MediaKind.BONES && layerProps[it.id]?.visible == false }.mapTo(HashSet()) { it.id }

    /** Слой костей: хозяин скелета в списке слоёв. Его трансформация двигает все его кости и привязанные к ним слои. */
    fun addBoneLayer() {
        if (projectId == null || _editingSymbol.value != null || _playing.value) return
        val id = nextMediaId()
        val n = media.count { it.kind == MediaKind.BONES } + 1
        val s = minOf(canvasW, canvasH) * 0.2f
        checkpoint("add", force = true)
        media += MediaItem(id, MediaKind.BONES, "${dn("Кости")} $n", "", 0, 0, (canvasW - s) / 2f, (canvasH - s) / 2f, s, s)
        activeBoneLayer = id
        // первый слой костей забирает свободные кости проекта (старые проекты получают слой-хозяина скелета)
        val adopt = n == 1 && _bones.value.any { it.layer == 0 }
        if (adopt) {
            val (b, l) = com.vibetoon.domain.anim.BoneLayers.adopt(_bones.value, _links.value, id)
            _bones.value = b
            _links.value = l
        }
        rebuildLayers(); pushScene(); selectLayer(id); markDirty()
        _state.value = UiState.Message(if (adopt) "Слой костей создан: в него перенесены кости проекта" else "Слой костей создан: режим «Кости» и «＋ Кость» рисуют кости этого слоя")
    }

    /** «Кости этого слоя»: слой становится активным, включается режим костей (Bind Pose для новых костей). */
    fun editBoneLayer(id: Int) {
        if (media.none { it.id == id && it.kind == MediaKind.BONES }) return
        activeBoneLayer = id
        if (layerProps[id]?.visible == false) updateLayerProps(id) { it.copy(visible = true) }
        setBoneMode(true)
    }

    /** «Собрать свободные кости»: кости без слоя переходят в слой костей [id]. */
    fun adoptFreeBones(id: Int) {
        if (_playing.value || media.none { it.id == id && it.kind == MediaKind.BONES }) return
        if (_bones.value.none { it.layer == 0 }) { _state.value = UiState.Message("Свободных костей нет"); return }
        checkpoint("bone-adopt", force = true)
        val (b, l) = com.vibetoon.domain.anim.BoneLayers.adopt(_bones.value, _links.value, id)
        _bones.value = b
        _links.value = l
        pushScene(); markDirty()
        _state.value = UiState.Message("Свободные кости перенесены в слой костей")
    }

    /** Число костей слоя костей [id] и свободных костей проекта. */
    fun boneLayerCounts(id: Int): Pair<Int, Int> = _bones.value.count { it.layer == id } to _bones.value.count { it.layer == 0 }

    /** Кость в позе привязки: точка холста -> координаты скелета (без трансформации слоя костей). */
    private fun boneRest(b: Bone, x: Float, y: Float): Pair<Float, Float> {
        if (b.layer == 0) return x to y
        val inv = scene?.boneDeltas?.get(b.layer)?.inverse() ?: return x to y
        return inv.applyX(x, y) to inv.applyY(x, y)
    }

    // ---- слой-путь

    /** Слой-путь: кривая, по которой едут прикреплённые слои. Сам не рисуется и не экспортируется. */
    fun addPathLayer() {
        if (projectId == null || _editingSymbol.value != null || _playing.value) return
        val id = nextMediaId()
        val n = media.count { it.kind == MediaKind.PATH } + 1
        checkpoint("add", force = true)
        media += pathItem(MediaItem(id, MediaKind.PATH, "${dn("Путь")} $n", ""), com.vibetoon.domain.anim.PathLayerMath.preset("arc", canvasW.toFloat(), canvasH.toFloat()))
        rebuildLayers(); pushScene(); selectLayer(id); markDirty()
        _state.value = UiState.Message("Слой-путь создан: нарисуйте путь пальцем или выберите готовый; слои прикрепляются в их свойствах")
    }

    private fun pathItem(m: MediaItem, shape: com.vibetoon.domain.vector.VectorShape): MediaItem {
        val p = com.vibetoon.domain.anim.PathLayerMath.path(shape) ?: return m.copy(vectors = listOf(shape))
        val b = com.vibetoon.domain.anim.PathLayerMath.bounds(p, 8f)
        return m.copy(vectors = listOf(shape), x = b[0], y = b[1], w = b[2], h = b[3])
    }

    private fun selectedPathLayer(): MediaItem? = _selected.value?.let { id -> media.firstOrNull { it.id == id && it.kind == MediaKind.PATH } }

    private fun replacePath(m: MediaItem, shape: com.vibetoon.domain.vector.VectorShape, note: String) {
        checkpoint("path-edit", force = true)
        val i = media.indexOfFirst { it.id == m.id }
        if (i < 0) return
        media[i] = pathItem(media[i], shape)
        rebuildLayers(); pushScene(); markDirty()
        _pathRevision.value++
        _state.value = UiState.Message(note)
    }

    /** Готовый путь выбранного слоя-пути: "line", "arc", "wave", "circle". */
    fun setPathPreset(kind: String) {
        if (_playing.value) return
        val m = selectedPathLayer() ?: run { _state.value = UiState.Message("Сначала выберите слой-путь"); return }
        replacePath(m, com.vibetoon.domain.anim.PathLayerMath.preset(kind, canvasW.toFloat(), canvasH.toFloat()), "Путь заменён")
    }

    private val _pathDraw = MutableStateFlow(false)
    /** «Нарисовать путь»: следующий жест одним пальцем по холсту становится путём выбранного слоя-пути. */
    val pathDraw: StateFlow<Boolean> = _pathDraw.asStateFlow()
    private var pathStroke: ArrayList<Float>? = null
    private val PATH_GRAB = 7

    fun setPathDraw(on: Boolean) {
        if (on && selectedPathLayer() == null) { _state.value = UiState.Message("Сначала выберите слой-путь"); return }
        if (on && _boneMode.value) setBoneMode(false)
        _pathDraw.value = on
        pathStroke = null
        refreshPathOverlay()
        _state.value = UiState.Message(if (on) "Проведите пальцем по холсту: новый путь заменит текущий" else "Рисование пути выключено")
    }

    private fun pathDown(sx: Float, sy: Float): Boolean {
        if (_playing.value || selectedPathLayer() == null) return false
        val (cx, cy) = toCanvas(sx, sy)
        pathStroke = arrayListOf(cx, cy)
        grab = PATH_GRAB
        refreshPathOverlay()
        return true
    }

    private fun pathMove(sx: Float, sy: Float) {
        val (cx, cy) = toCanvas(sx, sy)
        pathStroke?.let { it.add(cx); it.add(cy) }
        refreshPathOverlay()
    }

    private fun pathUp() {
        val pts = pathStroke
        pathStroke = null
        grab = 0
        val m = selectedPathLayer()
        if (m != null && pts != null) {
            // нарисованное - в координатах холста; путь хранится без трансформации слоя-пути
            val w = pathWorld(m)?.inverse()
            val local = if (w == null) pts.toList() else (0 until pts.size / 2).flatMap { k -> listOf(w.applyX(pts[k * 2], pts[k * 2 + 1]), w.applyY(pts[k * 2], pts[k * 2 + 1])) }
            val shape = com.vibetoon.domain.anim.PathLayerMath.fromStroke(local)
            if (shape != null) {
                replacePath(m, shape, "Путь нарисован")
                _pathDraw.value = false
            } else _state.value = UiState.Message("Слишком короткий путь: проведите пальцем дальше")
        }
        refreshPathOverlay()
    }

    /** Мир слоя-пути: точка пути (координаты холста без трансформации) -> холст с трансформацией слоя. */
    private fun pathWorld(m: MediaItem): Affine2D? {
        val q = scene?.selectionQuads?.get(m.id) ?: return null
        return com.vibetoon.domain.anim.PathLayerMath.quadMap(q, m.x.roundToInt().toFloat(), m.y.roundToInt().toFloat(),
            m.w.roundToInt().coerceAtLeast(1).toFloat(), m.h.roundToInt().coerceAtLeast(1).toFloat())
    }

    private val _pathOverlay = MutableStateFlow(PathOverlay())
    /** Путь на холсте (экранные координаты): у выбранного слоя-пути или у пути выбранного слоя, плюс рисуемый жест. */
    val pathOverlay: StateFlow<PathOverlay> = _pathOverlay.asStateFlow()

    private fun refreshPathOverlay() {
        val sel = _selected.value
        val sm = sel?.let { id -> media.firstOrNull { it.id == id } }
        val pathId = if (sm?.kind == MediaKind.PATH) sm.id else sel?.let { layerProps[it]?.path }?.takeIf { it >= 0 }
        val pm = pathId?.let { id -> media.firstOrNull { it.id == id && it.kind == MediaKind.PATH } }
        val draft = pathStroke?.let { s -> (0 until s.size / 2).flatMap { k -> toScreen(s[k * 2], s[k * 2 + 1]).toList() } }.orEmpty()
        val p = if (_boneMode.value) null else pm?.let { com.vibetoon.domain.anim.PathLayerMath.pathOf(it.vectors) }
        if (p == null || pm == null) {
            val cur = _pathOverlay.value
            if (draft.isEmpty() && cur.pts.isEmpty() && cur.draft.isEmpty()) return
            _pathOverlay.value = PathOverlay(draft = draft)
            return
        }
        val w = pathWorld(pm) ?: Affine2D.IDENTITY
        val pts = ArrayList<Float>(p.pts.size)
        for (k in 0 until p.count) {
            val s = toScreen(w.applyX(p.pts[k * 2], p.pts[k * 2 + 1]), w.applyY(p.pts[k * 2], p.pts[k * 2 + 1]))
            pts += s.first; pts += s.second
        }
        _pathOverlay.value = PathOverlay(pts, p.closed, draft, pm.id == sel)
    }

    /** Слои-пути проекта: id и имя (для меню «Прикрепить к пути»). */
    fun pathLayers(): List<Pair<Int, String>> = media.filter { it.kind == MediaKind.PATH }.map { it.id to layerName(it.id) }

    /** Положение слоя на пути (0..100 %) на текущем кадре. */
    fun pathProgressOf(id: Int): Float = effective(com.vibetoon.domain.anim.PathLayerMath.progressId(id)).dx

    fun pathKeyAt(id: Int): Boolean = _animation.value.hasKey(com.vibetoon.domain.anim.PathLayerMath.progressId(id), _frame.value)

    /** Прикрепляет слой [id] к слою-пути [pathId]: привязка к пути (едет с его трансформацией) и опорная точка - на путь. */
    fun attachToPath(id: Int, pathId: Int) {
        if (_playing.value) return
        val pm = media.firstOrNull { it.id == pathId && it.kind == MediaKind.PATH } ?: return
        val item = media.firstOrNull { it.id == id }
        if (id == pathId || id >= BONE_ID_BASE || (item != null && (item.kind == MediaKind.PATH || item.kind == MediaKind.BONES || item.kind == MediaKind.CAMERA || !item.isVisual))) {
            _state.value = UiState.Message("Этот слой нельзя прикрепить к пути"); return
        }
        if (pathId in linkDescendants(id)) { _state.value = UiState.Message("Нельзя: слой-путь привязан к этому слою"); return }
        checkpoint("path-attach", force = true)
        setProps(layerProps + (id to layerPropsOf(id).copy(path = pathId)))
        _links.value = _links.value + (id to pathId)
        val pid = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        if (base[pid] == null && !_animation.value.hasTrack(pid)) base[pid] = LayerTransform(dx = 0f)
        // собственный сдвиг становится смещением от точки пути: без ключей трансформации он сбрасывается - слой встаёт на путь
        if (!_animation.value.hasTrack(id)) base[id]?.let { base[id] = it.copy(dx = 0f, dy = 0f) }
        override.remove(id)
        rebuildLayers(); pushScene(); markDirty()
        _pathRevision.value++
        _state.value = UiState.Message("Слой прикреплён к пути «${layerName(pm.id)}»: двигайте «Положение на пути» и ставьте ключи")
    }

    /** Отцепляет слой от пути: привязка к пути и анимация положения снимаются (Undo вернёт). */
    fun detachFromPath(id: Int) {
        val path = layerProps[id]?.path ?: return
        if (path < 0 || _playing.value) return
        checkpoint("path-detach", force = true)
        setProps(layerProps + (id to layerPropsOf(id).copy(path = -1, pathOrient = false)))
        if (_links.value[id] == path) _links.value = _links.value - id
        val pid = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        base.remove(pid); override.remove(pid)
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks - pid)
        rebuildLayers(); pushScene(); markDirty()
        _pathRevision.value++
        _state.value = UiState.Message("Слой отцеплен от пути")
    }

    fun setPathOrient(id: Int, on: Boolean) {
        val p = layerProps[id] ?: return
        if (p.path < 0 || p.pathOrient == on || _playing.value) return
        checkpoint("path-orient", force = true)
        setProps(layerProps + (id to p.copy(pathOrient = on)))
        pushScene(); markDirty()
    }

    /** «Положение на пути» 0..100 %: правка ключа на кадре, автоключ или общее значение (как у трансформации). */
    fun setPathProgress(id: Int, value: Float) {
        if (_playing.value || (layerProps[id]?.path ?: -1) < 0 || !value.isFinite()) return
        val pid = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        val f = _frame.value
        checkpoint("pathpos$id:$f")
        val t = effective(pid).copy(dx = value.coerceIn(0f, 100f))
        val anim = _animation.value
        when {
            anim.hasKey(pid, f) || autoKeyEnabled() -> { _animation.value = anim.withKey(pid, f, t); override.remove(pid) }
            anim.hasTrack(pid) -> override[pid] = t
            else -> base[pid] = t
        }
        pushScene(); markDirty()
        _pathRevision.value++
    }

    fun addPathKey(id: Int) {
        if (_playing.value || (layerProps[id]?.path ?: -1) < 0) return
        val pid = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        val f = _frame.value
        if (_animation.value.hasKey(pid, f)) return
        checkpoint("pathkey", force = true)
        val t = effective(pid)
        override.remove(pid)
        _animation.value = _animation.value.withKey(pid, f, t)
        pushScene(); markDirty()
        _pathRevision.value++
    }

    fun deletePathKey(id: Int) {
        if (_playing.value) return
        val pid = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        val f = _frame.value
        if (!_animation.value.hasKey(pid, f)) return
        checkpoint("pathkeydel", force = true)
        val (next, last) = _animation.value.withoutKey(pid, f)
        _animation.value = next
        if (last != null) base[pid] = last   // последний ключ: слой остаётся на месте
        override.remove(pid)
        pushScene(); markDirty()
        _pathRevision.value++
    }

    // ---- маски слоёв

    /** Слой-маска слоя [id] или null. */
    fun maskOf(id: Int): Int? = layerProps[id]?.mask?.takeIf { it >= 0 }

    /** Цель слоя-маски [id] (null - слой не маска). */
    fun maskTargetOf(id: Int): Int? = com.vibetoon.domain.model.LayerMasks.targetOf(id, layerProps)

    private fun maskError(e: com.vibetoon.domain.model.LayerMasks.Error): String = when (e) {
        com.vibetoon.domain.model.LayerMasks.Error.SELF -> "Слой не может быть маской самого себя"
        com.vibetoon.domain.model.LayerMasks.Error.SOURCE_IS_TARGET -> "У этого слоя своя маска: он не может быть маской"
        com.vibetoon.domain.model.LayerMasks.Error.TARGET_IS_SOURCE -> "Слой-маска не может получить свою маску"
        com.vibetoon.domain.model.LayerMasks.Error.SOURCE_TAKEN -> "Этот слой уже маска другого слоя"
        com.vibetoon.domain.model.LayerMasks.Error.MISSING -> "Слой не найден"
    }

    private fun maskable(target: Int): Boolean {
        val item = media.firstOrNull { it.id == target }
        return target < BONE_ID_BASE && layers.any { it.id == target } && (item == null || (item.isVisual && !item.isRig && item.kind != MediaKind.CAMERA))
    }

    /**
     * «Добавить маску»: белый пиксельный слой «Маска: …» на весь холст становится маской слоя [target] и привязан к нему
     * (едет вместе со слоем). Ластик по маске скрывает слой, кисть возвращает; «Инверсия» - наоборот.
     */
    fun addLayerMask(target: Int) {
        val pid = projectId ?: return
        if (_playing.value || _editingSymbol.value != null) return
        if (!maskable(target)) { _state.value = UiState.Message("Маска недоступна для этого слоя"); return }
        if (maskOf(target) != null) { _state.value = UiState.Message("У слоя уже есть маска"); return }
        if (maskTargetOf(target) != null) { _state.value = UiState.Message("Слой-маска не может получить свою маску"); return }
        val scale = minOf(1f, 2048f / maxOf(canvasW, canvasH))
        val bw = (canvasW * scale).toInt().coerceAtLeast(1)
        val bh = (canvasH * scale).toInt().coerceAtLeast(1)
        val id = nextMediaId()
        val targetName = layerName(target)
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(0xFFFFFFFF.toInt())   // белая маска: слой виден целиком
                    val fileName = "m${id}_${java.util.UUID.randomUUID()}.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    MediaItem(id, MediaKind.IMAGE, "${dn("Маска")}: $targetName", "assets/$fileName", bw, bh, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
                }
                if (projectId != pid || closing || maskOf(target) != null || layers.none { it.id == target }) return@launch
                checkpoint("mask-add", force = true)
                media += item
                setProps(layerProps + (target to layerPropsOf(target).copy(mask = id, maskInvert = false, maskOff = false)))
                _links.value = _links.value + (id to target)
                rebuildLayers(); pushScene(); selectLayer(id); markDirty()
                _state.value = UiState.Message("Маска добавлена и выбрана: ластик скрывает слой, кисть возвращает")
                delay(400)
                requestPaintSize(id)
            } catch (t: Throwable) {
                Log.e("VT/Mask", "add mask failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось создать маску")
            }
        }
    }

    /** «Маска из слоя выше»: слой прямо над [target] (в той же группе) становится его маской. */
    fun maskFromLayerAbove(target: Int) {
        if (_playing.value) return
        if (!maskable(target)) { _state.value = UiState.Message("Маска недоступна для этого слоя"); return }
        val me = layers.firstOrNull { it.id == target } ?: return
        val siblings = layers.filter { it.parentId == me.parentId && it.kind != com.vibetoon.nativebridge.LayerKind.GROUP_END }
        val above = siblings.getOrNull(siblings.indexOfFirst { it.id == target } + 1)
        val src = above?.id
        if (above == null || src == null || above.kind != com.vibetoon.nativebridge.LayerKind.LAYER || media.any { it.id == src && (it.isRig || it.kind == MediaKind.CAMERA) }) {
            _state.value = UiState.Message("Над слоем нет подходящего слоя для маски"); return
        }
        com.vibetoon.domain.model.LayerMasks.check(target, src, layerProps)?.let { _state.value = UiState.Message(maskError(it)); return }
        checkpoint("mask-above", force = true)
        setProps(layerProps + (target to layerPropsOf(target).copy(mask = src, maskInvert = false, maskOff = false)))
        if (_links.value[src] == null && target !in linkDescendants(src)) _links.value = _links.value + (src to target)
        rebuildLayers(); pushScene(); markDirty()
        _state.value = UiState.Message("Слой «${layerName(src)}» стал маской: видно там, где он непрозрачный")
    }

    private fun editMask(target: Int, key: String, change: (com.vibetoon.domain.model.LayerProps) -> com.vibetoon.domain.model.LayerProps) {
        val p = layerProps[target] ?: return
        if (p.mask < 0 || _playing.value) return
        val next = change(p)
        if (next == p) return
        checkpoint(key, force = true)
        setProps(layerProps + (target to next))
        rebuildLayers(); pushScene(); markDirty()
    }

    fun setMaskEnabled(target: Int, on: Boolean) = editMask(target, "mask-on") { it.copy(maskOff = !on) }
    fun setMaskInvert(target: Int, on: Boolean) = editMask(target, "mask-inv") { it.copy(maskInvert = on) }

    /** «Отвязать маску»: слой снова виден целиком, слой-маска становится обычным слоем. */
    fun unlinkMask(target: Int) {
        val src = maskOf(target) ?: return
        if (_playing.value) return
        checkpoint("mask-unlink", force = true)
        setProps(layerProps + (target to layerPropsOf(target).copy(mask = -1, maskInvert = false, maskOff = false)))
        if (_links.value[src] == target) _links.value = _links.value - src
        rebuildLayers(); pushScene(); markDirty()
        _state.value = UiState.Message("Маска отвязана: теперь это обычный слой")
    }

    /** «Удалить маску»: слой-маска удаляется (Undo вернёт), слой виден целиком. */
    fun deleteMask(target: Int) {
        val src = maskOf(target) ?: return
        if (_playing.value) return
        multiMode = false
        applySelection(setOf(src), src)
        deleteSelected()
        selectLayer(target)
    }

    /** Глубина слоя для параллакса камеры. */
    fun setDepth(id: Int, depth: Float) = updateLayerProps(id) { it.copy(depth = depth) }

    fun addText(text: String) {
        val pid = projectId ?: return
        val id = nextMediaId()
        val proto = MediaItem(
            id, MediaKind.TEXT, dn("Текст"), "",
            text = text.ifBlank { dn("Текст") },
            textSize = (canvasH * 0.1f).coerceIn(TextRenderer.MIN_SIZE, TextRenderer.MAX_SIZE)
        )
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val (w, h) = withContext(decodeDispatcher) { renderAndUpload(proto, uploadTicket = ticket) }
                val item = proto.copy(x = (canvasW - w) / 2f, y = (canvasH - h) / 2f, w = w, h = h)
                if (projectId != pid || closing) return@launch
                checkpoint("add", force = true)
                media += item
                rebuildLayers()
                pushScene()
                selectLayer(item.id)
                markDirty()
                _state.value = UiState.Message("Добавлен текст")
            } catch (t: Throwable) {
                Log.e("VT/Text", "add text failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось добавить текст")
            }
        }
    }

    private fun refreshText() {
        val id = _selected.value
        val m = media.firstOrNull { it.id == id && it.isText }
        _textState.value = m?.let { TextState(it.id, it.text, it.textColor, it.font, fonts.title(it.font), it.textSize, it.gradient, it.gradientAngle, it.fx, it.stroke, it.strokeWidth, it.strokeColor, it.textAlign, it.lineSpacing, it.letterSpacing, it.textDir, com.vibetoon.domain.project.TextBidi.resolveRtl(it.textDir, it.text)) }
    }

    /** Правка свойства выделенного текста: сразу в модель, перерисовка Bitmap - с небольшой задержкой. */
    private fun editSelectedText(change: (MediaItem) -> MediaItem) {
        val id = _selected.value ?: return
        val i = media.indexOfFirst { it.id == id && it.isText }
        if (i < 0) return
        checkpoint("text$id")
        media[i] = change(media[i])
        refreshText()
        scheduleTextRender(id)
        markDirty()
    }

    fun setText(text: String) = editSelectedText { it.copy(text = text) }
    fun setTextColor(argb: Long) = editSelectedText { it.copy(textColor = argb) }
    /** Эффекты текста (печать, счётчик, тряска, контур, толщина, рандомизатор). */
    fun setTextFx(change: (com.vibetoon.domain.project.TextFx) -> com.vibetoon.domain.project.TextFx) = editSelectedText { it.copy(fx = change(it.fx)) }

    private val textFxKeys = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val textFxBusy = HashSet<Int>()

    /** При смене кадра перерисовывает текст с эффектами, зависящими от времени (только если картинка изменилась). */
    private fun updateTextFx(f: Int) {
        val fps = _animation.value.settings.fps.coerceAtLeast(1)
        val tSec = f.toFloat() / fps
        for (m in media) {
            if (!m.isText || !m.fx.animated) continue
            if (m.id in textFxBusy || textFxKeys[m.id] == m.fx.stateKey(tSec, fps)) continue
            textFxBusy += m.id
            viewModelScope.launch {
                try {
                    withContext(decodeDispatcher) { renderAndUpload(m, f) }
                    RenderHost.view?.requestRender()
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e("VT/Text", "text fx frame failed", t)
                } finally {
                    textFxBusy -= m.id
                }
                if (_frame.value != f) updateTextFx(_frame.value)   // пока рисовали, кадр ушёл вперёд
            }
        }
    }

    fun setTextStroke(on: Boolean) = editSelectedText { it.copy(stroke = on) }
    fun setTextStrokeWidth(px: Float) { if (px.isFinite()) editSelectedText { it.copy(strokeWidth = px.coerceIn(0f, TextRenderer.MAX_STROKE)) } }
    fun setTextStrokeColor(argb: Long) = editSelectedText { it.copy(strokeColor = argb) }

    fun setFont(key: String) = editSelectedText { it.copy(font = key) }
    /** Выравнивание строк: 0 - слева, 1 - по центру, 2 - справа. */
    fun setTextAlign(a: Int) = editSelectedText { it.copy(textAlign = a.coerceIn(0, 2)) }
    /**
     * Спринт 39: направление письма - авто (по первой букве), слева направо, справа налево (иврит, арабский).
     * Если фактическое направление сменилось, выравнивание «по началу строки» переезжает к новому началу.
     */
    fun setTextDirection(d: Int) = editSelectedText {
        val dir = com.vibetoon.domain.project.TextBidi.coerce(d)
        val wasRtl = com.vibetoon.domain.project.TextBidi.resolveRtl(it.textDir, it.text)
        val nowRtl = com.vibetoon.domain.project.TextBidi.resolveRtl(dir, it.text)
        it.copy(textDir = dir, textAlign = com.vibetoon.domain.project.TextBidi.alignOnDirectionChange(it.textAlign, wasRtl, nowRtl))
    }
    /** Межстрочный интервал (множитель 0.5..3) и межбуквенный (в em, -0.2..1). */
    fun setLineSpacing(v: Float) { if (v.isFinite()) editSelectedText { it.copy(lineSpacing = v.coerceIn(0.5f, 3f)) } }
    fun setLetterSpacing(v: Float) { if (v.isFinite()) editSelectedText { it.copy(letterSpacing = v.coerceIn(-0.2f, 1f)) } }
    /** Градиент текста: 2+ цветов (пустой список - сплошной цвет). */
    fun setTextGradient(colors: List<Long>) = editSelectedText { it.copy(gradient = if (colors.size >= 2) colors.take(MAX_GRADIENT_STOPS) else emptyList()) }
    fun setTextGradientAngle(deg: Float) { if (deg.isFinite()) editSelectedText { it.copy(gradientAngle = deg) } }

    // ---- пипетка: цвет пикселя холста ----
    private val _eyedropper = MutableStateFlow(false)
    val eyedropper: StateFlow<Boolean> = _eyedropper.asStateFlow()
    private var pickTarget = -1   // -1 - основной цвет текста, иначе номер цвета градиента

    fun startEyedropper(target: Int) {
        pickTarget = target
        _eyedropper.value = true
        _state.value = UiState.Message("Пипетка: коснитесь холста, чтобы взять цвет")
    }

    fun cancelEyedropper() { _eyedropper.value = false }

    // Пипетка удержанием не конкурирует со штрихом, выделением, костью или деформером.
    private var canvasLongPressClaimed = false
    fun beginCanvasGesture() { canvasLongPressClaimed = false }
    fun isCanvasLongPressClaimed() = canvasLongPressClaimed
    fun finishCanvasGesture(): Boolean {
        RenderHost.view?.setInteractive(false)
        return canvasLongPressClaimed.also { canvasLongPressClaimed = false }
    }
    fun canPickOnLongPress(x: Float, y: Float): Boolean = projectId != null && !_playing.value &&
        _state.value != UiState.Loading && _state.value !is UiState.Rendering &&
        _audioReadiness.value !is com.vibetoon.domain.project.AudioReadiness.Preparing && !_eyedropper.value && _paintTool.value == null && _rasterTool.value == null && !_boneMode.value &&
        !_meshMode.value && !_warpTool.value && !_perspTool.value && !_liquifyTool.value &&
        viewW > 0f && viewH > 0f && x in 0f..viewW && y in 0f..viewH &&
        toCanvas(x, y).let { it.first in 0f..canvasW.toFloat() && it.second in 0f..canvasH.toFloat() }

    fun pickBrushOnLongPress(x: Float, y: Float) {
        if (!canPickOnLongPress(x, y)) return
        val view = RenderHost.view ?: return
        canvasLongPressClaimed = true
        val pid = projectId
        view.sampleColor(x.toInt(), y.toInt()) { color ->
            viewModelScope.launch {
                if (projectId == pid && RenderHost.view === view && !closing) {
                    setBrushColor(color)
                    _pickedColor.tryEmit(color.toLong() and 0xFFFFFFFFL)
                    _state.value = UiState.Message("Цвет взят")
                }
            }
        }
    }

    private fun sampleAt(x: Float, y: Float) {
        _eyedropper.value = false
        RenderHost.view?.sampleColor(x.toInt(), y.toInt()) { argb ->
            viewModelScope.launch { applyPickedColor(argb.toLong() and 0xFFFFFFFFL) }   // из GL-потока - в главный
        }
    }

    private fun applyPickedColor(color: Long) {
        if (pickTarget <= -100) { setEffectColor(-(pickTarget + 100), color); return }
        val t = _textState.value ?: return
        if (pickTarget < 0) {
            setTextColor(color)
        } else if (pickTarget < t.gradient.size) {
            setTextGradient(t.gradient.toMutableList().also { it[pickTarget] = color })
        }
        _state.value = UiState.Message("Цвет взят")
    }

    fun setTextSize(px: Float) {
        if (px.isFinite()) editSelectedText { it.copy(textSize = px.coerceIn(TextRenderer.MIN_SIZE, TextRenderer.MAX_SIZE)) }
    }

    /**
     * Перерисовка текста с задержкой 150 мс (пока печатают, не рисуем на каждую букву). Центр блока
     * остаётся на месте, меняется только его размер, поэтому трансформации слоя не сбиваются.
     */
    private fun scheduleTextRender(id: Int) {
        textJob?.cancel()
        textJob = viewModelScope.launch {
            delay(150)
            val item = media.firstOrNull { it.id == id } ?: return@launch
            val (w, h) = try {
                withContext(decodeDispatcher) { renderAndUpload(item) }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.e("VT/Text", "text render failed", t)
                _state.value = UiState.Error("Текст не отрисовался: ${t.message}")
                return@launch
            }
            val i = media.indexOfFirst { it.id == id }
            if (i < 0) return@launch
            val m = media[i]
            val cx = m.x + m.w / 2f
            val cy = m.y + m.h / 2f
            media[i] = m.copy(x = cx - w / 2f, y = cy - h / 2f, w = w, h = h)
            rebuildLayers()
            pushScene()
            markDirty()
        }
    }

    /** Импорт шрифта TTF/OTF: копия в папку шрифтов приложения, шрифт сразу применяется к выделенному тексту. */
    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { fonts.import(uri) }
            r.onSuccess { entry ->
                _fontList.value = fonts.list()
                _state.value = UiState.Message("Шрифт добавлен: ${entry.title}")
                if (_textState.value != null) setFont(entry.key)
            }.onFailure { _state.value = UiState.Error(it.message ?: "Не удалось добавить шрифт") }
        }
    }

    /** Текстура медиа-слоя из файла проекта (текст рисуется заново из его свойств). */
    /** Возвращает размер отрисованного текста (для текста) или null. */
    private fun uploadTextureFor(pid: String, m: MediaItem): Pair<Float, Float>? {
        if (m.kind == MediaKind.CAMERA || m.kind == MediaKind.GROUP || m.kind == MediaKind.SYMBOL || m.isRig) return null   // у камеры и группы нет картинки
        if (m.kind == MediaKind.IMAGE && m.cells.isNotEmpty()) cellShown[m.id] = m.file
        if (m.vector && m.vectors.isNotEmpty() && projects.fileOf(pid, m.file).let { !it.isFile || it.length() == 0L }) return null   // пакет 28: перерисуется из примитивов
        if (m.isText) {
            return renderAndUpload(m)
        } else {
            val file = projects.fileOf(pid, m.file)
            val ticket = RenderNative.textureUploadTicket(m.id)
            val d = if (m.kind == MediaKind.IMAGE) importer.decodeImage(file) else importer.decodeVideoFrame(file)
            try { RenderNative.uploadBitmapIfCurrent(m.id, d.bitmap, ticket) } finally { d.bitmap.recycle() }
        }
        return null
    }

    private val RESTORE_QUEUE_LIMIT = 96L * 1024 * 1024

    /** При открытии проекта текстуры картинок и кадров видео создаются заново из файлов проекта. */
    private suspend fun restoreMediaTextures(pid: String) = withContext(decodeDispatcher) {
        val measured = HashMap<Int, Pair<Float, Float>>()
        for (m in media.toList()) {
            if (!m.isVisual) continue
            // Очередь загрузки не раздуваем: большой проект целиком в RAM давал OOM при возврате из фона.
            var waited = 0
            while (RenderNative.pendingUploadBytes() > RESTORE_QUEUE_LIMIT && waited < 400) {
                RenderHost.view?.requestRender(); kotlinx.coroutines.delay(15); waited++
            }
            runCatching { uploadTextureFor(pid, m) }.onFailure { Log.w("VT/Media", "texture for ${m.name} not restored: ${it.message}") }
                .getOrNull()?.let { size -> if (m.isText && (m.w <= 0f || m.h <= 0f)) measured[m.id] = size }
        }
        withContext(Dispatchers.Main) {
            // пакет 28: текст из Lottie приходит без рамки - она задаётся по отрисовке (x - начало строки с учётом выравнивания)
            if (measured.isNotEmpty() && projectId == pid) {
                for ((id, size) in measured) {
                    val i = media.indexOfFirst { it.id == id && it.isText && (it.w <= 0f || it.h <= 0f) }
                    if (i < 0) continue
                    val m = media[i]
                    val (w, h) = size
                    val x = when (m.textAlign) { 1 -> m.x - w / 2f; 2 -> m.x - w; else -> m.x }
                    media[i] = m.copy(x = x, w = w, h = h)
                }
                rebuildLayers(); pushScene(); markDirty()
            }
            // пакет 28: векторные слои без картинки (импорт Lottie) рисуются из примитивов и сохраняются
            for (m in media.toList()) if (m.vector && m.vectors.isNotEmpty() && projects.fileOf(pid, m.file).let { !it.isFile || it.length() == 0L }) renderVector(m, save = true)
            updateCells(_frame.value); refreshOnion()
        }
        RenderHost.view?.requestRender()
    }

    // ---------------------------------------------------------------- камера

    private fun rebuildRows() {
        _rows.value = LayerTree.flatten(shownLayers(), collapsed)
    }

    /** Слои, которые видны в панели: внутри символа - только его слои, в главной сцене - без содержимого символов. */
    private fun shownLayers(): List<LayerInfo> {
        val ctx = _editingSymbol.value
        val defs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.map { it.id }
        return when {
            ctx != null -> { val sub = symbolMembers(ctx) + ctx; layers.filter { it.id in sub }.map { if (it.id == ctx) it.copy(parentId = -1) else it } }   // внутри символа - только его слои
            defs.isEmpty() -> layers
            else -> { val hide = HashSet<Int>(); for (d in defs) { hide += symbolMembers(d); hide += d }; layers.filter { it.id !in hide } }   // содержимое символов в главной сцене не показывается
        }
    }

    /** Спринт 39: полное дерево (все папки развёрнуты) - для поиска и фильтров вида «Список». */
    fun treeRowsExpanded(): List<com.vibetoon.domain.model.TreeRow> = LayerTree.flatten(shownLayers(), emptySet())

    /** Спринт 39: «Свернуть все» / «Развернуть все» папки. */
    fun setAllGroupsCollapsed(collapse: Boolean) {
        collapsed = if (collapse) layers.filter { it.kind == LayerKind.GROUP_OPEN || it.kind == LayerKind.GROUP_CLOSED }.map { it.id }.toSet() else emptySet()
        rebuildRows()
    }

    /** Спринт 39: вид панели слоёв - «Миниатюры» (как раньше) или компактный «Список»; выбор хранится в настройках. */
    private val _layersList by lazy { MutableStateFlow(com.vibetoon.domain.model.LayerListView.isList(appPrefs().getString(com.vibetoon.domain.model.LayerListView.PREF_KEY, null))) }
    val layersList: StateFlow<Boolean> get() = _layersList

    fun setLayersListMode(list: Boolean) {
        _layersList.value = list
        appPrefs().edit().putString(com.vibetoon.domain.model.LayerListView.PREF_KEY, com.vibetoon.domain.model.LayerListView.store(list)).apply()
    }

    /** Спринт 39: слой на одну позицию выше/ниже среди соседей (вид «Список», без перетаскивания). Папки тоже. */
    fun moveLayerStep(id: Int, up: Boolean) {
        if (_playing.value) return
        val l = layers.firstOrNull { it.id == id } ?: return
        val byId = layers.associateBy { it.id }
        val sib = com.vibetoon.domain.model.StackOrder.children(layers, l.parentId)   // снизу вверх
            .filter { sid -> byId[sid]?.let { !(it.kind == LayerKind.LAYER && it.isEmpty) } == true || sid == id }
        val i = sib.indexOf(id)
        val target = sib.getOrNull(if (up) i + 1 else i - 1)
        if (i < 0 || target == null) { _state.value = UiState.Message(if (up) "Слой уже наверху группы" else "Слой уже внизу группы"); return }
        val next = com.vibetoon.domain.model.StackOrder.place(layers, stackOrder, id, target, above = up) ?: return
        checkpoint("reorder", force = true)
        stackOrder = next
        rebuildLayers()
        pushScene()
        markDirty()
    }

    /** Тап по группе в дереве слоёв: свернуть или развернуть. */
    fun toggleGroup(id: Int) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
        rebuildRows()
    }

    fun toggleUniformScale() {
        _uniformScale.value = !_uniformScale.value
    }

    fun onViewSize(w: Float, h: Float) { if (viewW != w || viewH != h) cancelPendingLasso(); viewW = w; viewH = h; refreshGridView(); refreshBoneOverlay(); refreshRasterOverlays() }

    /** Два пальца: zoom вокруг центра жеста + сдвиг. */
    fun onTransform(gx: Float, gy: Float, dz: Float, dx: Float, dy: Float, rotDeg: Float = 0f) {
        cancelPendingLasso()
        // Мёртвая зона: поворот включается, когда за жест набралось больше 8°, иначе pinch-zoom дрожит углом.
        var rot = 0f
        if (rotDeg.isFinite() && rotDeg != 0f && appSettings()?.rotateCanvas != false) {
            if (gestureRotating) rot = rotDeg
            else {
                gestureRotAcc += rotDeg
                if (kotlin.math.abs(gestureRotAcc) >= ROTATE_DEAD_ZONE) { gestureRotating = true; rot = gestureRotAcc }
            }
        }
        camera = camera.transformed(gx, gy, viewW / 2f, viewH / 2f, dz, dx, dy, rot)
        refreshGridView()
        RenderHost.view?.setInteractive(true)   // жест камеры: эффекты слоёв берутся из кэша, после жеста - точный кадр
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    /** Свободный поворот холста двумя пальцами: порог 8°, снап к 0/90/180/270° при отпускании. */
    fun rotateCameraBy(deg: Float) {
        if (kotlin.math.abs(deg) < 8f) return
        camera = camera.copy(rotDeg = camera.rotDeg + deg)
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    private var gestureRotAcc = 0f
    private var gestureRotating = false

    fun snapCameraRotation() {
        gestureRotAcc = 0f; gestureRotating = false
        val snapped = camera.snappedRot()
        if (snapped != camera.rotDeg) {
            camera = camera.copy(rotDeg = snapped)
            refreshGridView()
            RenderHost.view?.setCamera(camera)
            refreshBoneOverlay()
            refreshRasterOverlays()
        }
    }

    fun resetCameraRotation() {
        camera = camera.copy(rotDeg = 0f)
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    fun rotateCamera90() {
        camera = camera.copy(rotDeg = camera.rotDeg + 90f)
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    fun flipCameraX() {
        camera = camera.copy(flipX = !camera.flipX)
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    /** Пакет 11: состояние отражения вида (переключатели Flip Canvas в настройках). */
    fun cameraFlipped(): Pair<Boolean, Boolean> = camera.flipX to camera.flipY
    fun setCameraFlip(horizontal: Boolean, on: Boolean) {
        if (horizontal) { if (camera.flipX != on) flipCameraX() } else if (camera.flipY != on) flipCameraY()
    }

    fun flipCameraY() {
        camera = camera.copy(flipY = !camera.flipY)
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    fun resetCamera() {
        cancelPendingLasso()
        camera = Camera()
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
        Log.i("VT/Edit", "camera reset")
    }

    /** Следование — только камера редактора и только при Play; проект и экспорт не меняются. */
    private fun followSelectionDuringPlayback() {
        if (!_playing.value || !getApplication<Application>()
                .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("follow_camera", false)) return
        val id = _selected.value ?: return
        val sc = scene ?: return
        val bone = _bones.value.firstOrNull { it.id == id }
        val xy = if (bone != null) {
            val d = if (_boneMode.value && _bindPose.value) Affine2D.IDENTITY else sc.boneDeltas[id] ?: Affine2D.IDENTITY
            d.applyX(bone.hx, bone.hy) to d.applyY(bone.hx, bone.hy)
        } else {
            val q = sc.selectionQuads[id] ?: return
            if (q.size < 8) return
            ((q[0] + q[2] + q[4] + q[6]) / 4f) to ((q[1] + q[3] + q[5] + q[7]) / 4f)
        }
        val next = camera.follow(xy.first, xy.second, viewW, viewH, canvasW, canvasH)
        if (next == camera) return
        camera = next
        refreshGridView()
        RenderHost.view?.setCamera(camera)
        refreshBoneOverlay()
        refreshRasterOverlays()
    }

    /** Пока только корневые узлы: не показываем неточный путь для анимированных родителей, камер и ретайминга. */
    fun motionPathFor(id: Int): com.vibetoon.domain.anim.MotionPath? {
        if (_links.value[id] != null || cameraIdNow() >= 0) return null
        val track = _animation.value.tracks[id] ?: return null
        if (track.keys.size < 2 || track.keys.size > com.vibetoon.domain.anim.MotionPathMath.MAX_KEYS) return null
        val clip = _clips.value[id]
        if (clip != null && (clip.speed != 1f || clip.loop || clip.reverse || clip.freeze ||
                track.keys.any { !clip.visibleAt(it.frame) })) return null
        val bone = _bones.value.firstOrNull { it.id == id }
        if (bone != null) {
            if (_boneMode.value && _bindPose.value) return null
            return com.vibetoon.domain.anim.MotionPathMath.build(track, bone.hx, bone.hy, Affine2D.IDENTITY)
        }
        val layer = layers.firstOrNull { it.id == id } ?: return null
        if (layer.parentId != -1 || layerProps[id]?.visible == false) return null
        val pivot = pivots[id] ?: return null
        val parent = if (id < MEDIA_ID_BASE) fit else Affine2D.IDENTITY
        return com.vibetoon.domain.anim.MotionPathMath.build(track, pivot.first, pivot.second, parent)
    }

    // ---------------------------------------------------------------- выделение

    fun hasSelection(): Boolean = _selected.value != null

    private val tapSequence = java.util.concurrent.atomic.AtomicInteger()
    private val _marquee = MutableStateFlow<FloatArray?>(null)
    val marquee: StateFlow<FloatArray?> = _marquee.asStateFlow()

    fun canMarquee(selectOnCanvas: Boolean): Boolean = selectOnCanvas && projectId != null && !_playing.value &&
        _state.value != UiState.Loading && _state.value !is UiState.Rendering &&
        _audioReadiness.value !is com.vibetoon.domain.project.AudioReadiness.Preparing && !_eyedropper.value &&
        _paintTool.value == null && _rasterTool.value == null && !_boneMode.value && !_meshMode.value &&
        !_warpTool.value && !_perspTool.value && !_liquifyTool.value

    fun updateMarquee(x0: Float, y0: Float, x: Float, y: Float) { _marquee.value = floatArrayOf(x0, y0, x, y) }
    fun cancelMarquee() { _marquee.value = null }
    fun finishMarquee(selectOnCanvas: Boolean) {
        val r = _marquee.value ?: return
        _marquee.value = null
        tapSequence.incrementAndGet()
        if (!canMarquee(selectOnCanvas)) return
        val sc = scene ?: return
        val byId = layers.associateBy { it.id }
        val hidden = hiddenIds() + _clips.value.filterValues { !it.visibleAt(_frame.value) }.keys
        fun lockedByAncestor(id: Int): Boolean {
            var node = byId[id]
            val visited = HashSet<Int>()
            while (node != null && visited.add(node.id)) {
                if (isLockedId(node.id)) return true
                node = byId[node.parentId]
            }
            return false
        }
        val picked = layers.asSequence().filter { it.kind == LayerKind.LAYER && it.id < BONE_ID_BASE }
            .filter { LayerTree.isEffectivelyVisible(it.id, byId, hidden) && !lockedByAncestor(it.id) }
            .filter { layer ->
                val q = sc.selectionQuads[layer.id] ?: return@filter false
                if (q.size < 8) return@filter false
                val screen = FloatArray(8) { i -> if (i % 2 == 0) toScreen(q[i], q[i + 1]).first else toScreen(q[i - 1], q[i]).second }
                com.vibetoon.domain.input.MarqueePolicy.containsCenter(screen, r[0], r[1], r[2], r[3])
            }.map { it.id }.toCollection(LinkedHashSet())
        // Пустая рамка не уничтожает прежнее выделение.
        if (picked.isNotEmpty()) { multiMode = picked.size > 1; applySelection(picked, picked.last()) }
        _state.value = UiState.Message(if (picked.isEmpty()) "В рамке нет доступных слоёв" else "Выбрано слоёв: ${picked.size}")
    }

    /**
     * Тап: hit-test в C++ (GL-поток) возвращает верхний слой под пальцем. Как в Live2D, сначала
     * выделяется вся группа верхнего уровня; повторные тапы спускаются на уровень глубже
     * (вложенная группа, затем сам слой). Тап в пустоту снимает выделение.
     */
    fun onTap(x: Float, y: Float, selectOnCanvas: Boolean = true) {
        if (swallowTap) { swallowTap = false; return }
        if (_eyedropper.value) { sampleAt(x, y); return }
        if (_boneMode.value) { boneTap(x, y); return }
        if (!com.vibetoon.domain.input.EditorInputPolicy.canSelectCanvasLayer(selectOnCanvas)) return
        val seq = tapSequence.incrementAndGet()
        RenderHost.view?.hitTest(x, y) { hit -> viewModelScope.launch {
            if (tapSequence.get() != seq) return@launch
            val id = if (hit >= GHOST_BASE || (hit >= 0 && (isLockedId(hit) || layerProps[hit]?.visible == false || hit in symbolContextIds()))) -1 else hit   // закрытый и скрытый слои и контекст символа на холсте не выбираются
            val tapNow = SystemClock.uptimeMillis()
            val dbl = id >= 0 && id == lastTapId && tapNow - lastTapAt < 380
            lastTapId = id; lastTapAt = tapNow
            if (dbl && !multiMode && isSymbolInstance(id)) { enterSymbol(id); return@launch }
            if (multiMode) {
                if (id < 0) selectLayer(null) else toggleSelect(id)   // тап в пустоту выходит из мультивыделения
            } else {
                val quick = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("quick_selection", false)
                val target = if (id >= 0 && ancestorsOf(id).any { isLockedId(it) || layerProps[it]?.visible == false }) null else resolveSelection(id, _selected.value, quick)
                selectLayer(target)
            }
            Log.i("VT/Edit", "tap ($x,$y) -> layer $id, selected ${_selected.value}")
        } }
    }

    /** Цепочка от верхнего предка к слою [leaf]; null, если слоя нет. */
    private fun chainTo(leaf: Int): List<Int> {
        val byId = layers.associateBy { it.id }
        val chain = ArrayList<Int>()
        var cur = byId[leaf]
        while (cur != null) {
            chain += cur.id
            cur = byId[cur.parentId]
        }
        return chain.asReversed()
    }

    private fun resolveSelection(hitLeaf: Int, current: Int?, quick: Boolean = false): Int? =
        if (hitLeaf < 0) null else com.vibetoon.domain.input.EditorInputPolicy.tapTarget(chainTo(hitLeaf), current, quick)

    /** Обычное выделение одного узла (или снятие выделения при null); выходит из мультивыделения. */
    fun selectLayer(id: Int?) {
        multiMode = false
        applySelection(if (id == null) emptySet() else setOf(id), id)
    }

    /** Долгое нажатие на слой: добавить в выделение или убрать из него. */
    fun toggleSelect(id: Int) {
        multiMode = true
        val cur = _selection.value
        if (id in cur) {
            val next = cur - id
            applySelection(next, next.lastOrNull())
        } else {
            applySelection(cur + id, id)
        }
    }

    private fun applySelection(set: Set<Int>, primary: Int?) {
        if (_selected.value != primary || _selection.value != set) cancelPendingLasso()
        _selection.value = set
        _selected.value = primary
        if (primary != null && media.any { it.id == primary && it.kind == MediaKind.BONES }) activeBoneLayer = primary   // спринт 38
        if (_pathDraw.value && (primary == null || media.none { it.id == primary && it.kind == MediaKind.PATH })) { _pathDraw.value = false; pathStroke = null }
        refreshPathOverlay()
        if (_paintTool.value != null && primary != null) paintTargetId()?.let { requestPaintSize(it) }   // размер текстуры заранее: первый мазок не ждёт
        updateSelectionFrame()
        if (_warpTool.value) ensureWarp()
        if (_perspTool.value) ensurePersp()
        _skinned.value = warpTarget()?.let { it in skinLayers } == true
        _boneStrength.value = _bones.value.firstOrNull { it.id == primary }?.strength
        if (_liquifyTool.value) liqPrefetch()
        _turb.value = warpTarget()?.let { turbEffective(it) }
        refreshFxState()
    }

    /** Выделенные узлы, у которых есть трансформация (без аудиодорожек). */
    private fun transformTargets(): List<Int> {
        val known = layers.mapTo(HashSet()) { it.id }
        // кости - тоже узлы с трансформацией: без них «Add Key» у выбранной кости ничего не делал и поза не анимировалась
        _bones.value.forEach { known += it.id }
        return _selection.value.filter { it in known }
    }

    private fun updateSelectionFrame() {
        refreshWarpOverlay()
        refreshMeshOverlay()
        refreshPerspOverlay()
        refreshRasterOverlays()
        val quads = (_selection.value + _lassoLayers.value).mapNotNull { id -> scene?.selectionQuads?.get(id)?.let { q -> contentQuad(id, q) ?: q } }
        RenderHost.view?.setSelection(if (quads.isEmpty()) null else FloatArray(quads.sumOf { it.size }).also { out -> var o = 0; for (q in quads) { q.copyInto(out, o); o += q.size } })
        if (!_playing.value) refreshInspector()
    }

    private fun computePivots(nodes: List<com.vibetoon.domain.model.Node>): Map<Int, Pair<Float, Float>> {
        val out = HashMap<Int, Pair<Float, Float>>()
        fun visit(n: com.vibetoon.domain.model.Node) {
            when (n) {
                is LeafNode -> out[n.id] = pivotOverrides[n.id] ?: ((n.x + n.w / 2f) to (n.y + n.h / 2f))
                is GroupNode -> { out[n.id] = n.pivotX to n.pivotY; n.children.forEach(::visit) }
            }
        }
        nodes.forEach(::visit)
        return out
    }

    private fun refreshInspector() {
        val id = _selected.value
        refreshText()
        if (id == null) { _inspector.value = null; return }
        val info = layers.firstOrNull { it.id == id } ?: run { _inspector.value = null; return }
        val t = effective(id)
        val (px, py) = pivots[id] ?: (0f to 0f)
        _inspector.value = InspectorState(
            id = id,
            name = info.name,
            isGroup = info.kind == com.vibetoon.nativebridge.LayerKind.GROUP_OPEN ||
                info.kind == com.vibetoon.nativebridge.LayerKind.GROUP_CLOSED,
            x = px + t.dx,
            y = py + t.dy,
            scaleXPct = t.scaleX * 100f,
            scaleYPct = t.scaleY * 100f,
            rotationDeg = Math.toDegrees(t.rotation.toDouble()).toFloat(),
            opacityPct = t.opacity * 100f,
            hasKey = com.vibetoon.domain.anim.LayerKeyFlow.hasKey(_animation.value, id, _frame.value),
            easing = _animation.value.easingAt(id, _frame.value),
            count = transformTargets().size.coerceAtLeast(1),
            pivotX = px, pivotY = py
        )
    }

    /** Ввод числа в инспекторе: сразу двигает слой на холсте. Цепочка делает масштаб пропорциональным. */
    fun setProperty(prop: Prop, value: Float) {
        val prim = _selected.value ?: return
        if (_playing.value || !value.isFinite()) return
        val ids = transformTargets().ifEmpty { return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        checkpoint("prop$prop:${_frame.value}:${ids.sorted()}")
        val lo = LayerTransform.MIN_SCALE
        val hi = LayerTransform.MAX_SCALE
        val tp = effective(prim)
        val (px, py) = pivots[prim] ?: (0f to 0f)
        for (id in ids) {
            val t = effective(id)
            val next = when (prop) {
                // положение: у всех выделенных то же смещение, что у главного узла
                Prop.X -> t.copy(dx = t.dx + (value - (px + tp.dx)))
                Prop.Y -> t.copy(dy = t.dy + (value - (py + tp.dy)))
                Prop.SCALE_X -> (value / 100f).coerceIn(lo, hi).let { if (_uniformScale.value) t.copy(scaleX = it, scaleY = it) else t.copy(scaleX = it) }
                Prop.SCALE_Y -> (value / 100f).coerceIn(lo, hi).let { if (_uniformScale.value) t.copy(scaleX = it, scaleY = it) else t.copy(scaleY = it) }
                Prop.ROTATION -> t.copy(rotation = Math.toRadians(value.toDouble()).toFloat())
                Prop.OPACITY -> t.copy(opacity = (value / 100f).coerceIn(0f, 1f))
            }
            storeEdit(id, next)
        }
        pushScene()
        markDirty()
    }

    /** Кнопка f(x): плавность сегмента от ключа выделенного слоя на текущем кадре. */
    // ---- редактор кривых (универсальный: движение слоя, поворот кости, точки Warp/Mesh/Perspective, параметры эффектов)

    private var selectedCurveTargets: List<Pair<Int, Int>>? = null
    private val _curveEditor = MutableStateFlow(false)
    val curveEditor: StateFlow<Boolean> = _curveEditor.asStateFlow()

    /** Узлы анимации, чей ключ на текущем кадре правит редактор: выделенные слои/кости и точки деформеров слоя. */
    private fun curveTargets(): List<Int> {
        val out = LinkedHashSet<Int>()
        val f = _frame.value
        val tracks = _animation.value.tracks
        for (id in _selection.value) if (tracks[id]?.keyAt(f) != null) out += id
        for (id in _selection.value) if (id < BONE_ID_BASE) com.vibetoon.domain.anim.PathLayerMath.progressId(id).let { if (tracks[it]?.keyAt(f) != null) out += it }   // спринт 38: положение на пути
        warpTarget()?.let { t ->
            for ((id, tr) in tracks) {
                val mine = (WarpMath.isPointId(id) && (id - WarpMath.ID_BASE) / WarpMath.POINTS == t) ||
                    (PuppetMath.isPinId(id) && PuppetMath.layerOf(id) == t) ||
                    (MeshMath.isVertexId(id) && id < PuppetMath.ID_BASE && (id - MeshMath.ID_BASE) / MeshMath.MAX_VERTS == t) ||
                    (PerspectiveMath.isPointId(id) && id >= PerspectiveMath.ID_BASE && id < TurbMath.ID_BASE && (id - PerspectiveMath.ID_BASE) / PerspectiveMath.CORNERS == t) ||
                    (id >= TurbMath.ID_BASE && id < PuppetMath.ID_BASE && (id - TurbMath.ID_BASE) / TurbMath.PARAMS == t) ||
                    com.vibetoon.domain.anim.EffectTracks.belongs(id, t)
                if (mine && tr.keyAt(f) != null) out += id
            }
        }
        return out.toList()
    }

    private val _timeEaseDialog = MutableStateFlow(false)
    /** Диалог «Общая кривая» для ключей времени символов (общий переход для всех выделенных). */
    val timeEaseDialog: StateFlow<Boolean> = _timeEaseDialog.asStateFlow()

    private fun selectedTimeKeyTargets(): Map<Int, Set<Int>> {
        val all = media.filter { it.kind == MediaKind.SYMBOL && it.timeKeys.isNotEmpty() }
        val direct = com.vibetoon.domain.anim.SelectedKeyCurves.timeKeyTargets(_keySel.value, all.associate { it.id to it.timeKeys })
        if (direct.isNotEmpty()) return direct
        // выделены маркеры (формы) открытого символа: ключи времени его экземпляров, выбирающие эти формы
        val def = _editingSymbol.value ?: return emptyMap()
        val forms = _symMarkers.value.filter { it.frame in _markerSel.value }.mapTo(HashSet()) { it.target }
        if (forms.isEmpty()) return emptyMap()
        return com.vibetoon.domain.anim.SelectedKeyCurves.markerTimeKeyTargets(forms, all.filter { it.symRef == def }.associate { it.id to it.timeKeys })
    }

    fun openSelectedCurveEditor() {
        if (_playing.value) return
        // спринт 44: один ромб - это «Кривая ключа» (только он), а не общая кривая выделения
        if (_keySel.value.size == 1 && singleKey() != null) { openSingleKeyCurve(); return }
        _curveTitle.value = "Общая кривая"; _curveNote.value = null
        val targets = com.vibetoon.domain.anim.SelectedKeyCurves.targets(_animation.value, _keySel.value)
        val timeTargets = selectedTimeKeyTargets()
        if (targets.isEmpty() && timeTargets.isEmpty()) {
            _state.value = UiState.Message(
                if (_editingSymbol.value != null && _markerSel.value.isNotEmpty()) "Выделенные формы ещё не выбраны ни одним ключом времени: выберите маркер в панели символа на сцене"
                else "В выделении нет ключей: выделите ключи рамкой (кнопка выделения на таймлайне)"); return
        }
        if (targets.isEmpty()) { _timeEaseDialog.value = true; return }   // только ключи времени символа: общий переход
        selectedCurveTargets = targets
        _curveEditor.value = true
        if (timeTargets.isNotEmpty()) _state.value = UiState.Message("Кривая - для ключей слоёв; переход ключей времени символа задаётся кнопками «Переход»")
    }

    fun closeTimeEaseDialog() { _timeEaseDialog.value = false }

    /** Общий переход [ease] для всех выделенных ключей времени (всех экземпляров), одной записью Undo. */
    fun applyTimeEaseToSelection(ease: Int) {
        _timeEaseDialog.value = false
        if (_playing.value) return
        val t = selectedTimeKeyTargets()
        if (t.isEmpty()) { _state.value = UiState.Message("В выделении нет ключей"); return }
        checkpoint("timeease", force = true)
        for ((inst, frames) in t) {
            val i = media.indexOfFirst { it.id == inst }
            if (i >= 0) media[i] = media[i].copy(timeKeys = TimelineItemOps.withEase(media[i].timeKeys, frames, ease))
        }
        refreshSymbolState(); markDirty(); updateSymbols(_frame.value)
        _state.value = UiState.Message("Общий переход: ${timeEaseName(ease)} (ключей: ${t.values.sumOf { it.size }})")
    }

    // ---- спринт 44: кривая одного ключа (таймлайн: один ромб; свойства: ключ выбранного слоя)
    private val _curveTitle = MutableStateFlow("Кривая плавности")
    /** Заголовок редактора кривой: «Кривая ключа», «Общая кривая» или «Кривая плавности». */
    val curveTitle: StateFlow<String> = _curveTitle.asStateFlow()
    private val _curveNote = MutableStateFlow<String?>(null)
    /** Пояснение под заголовком: какой ключ правится (только для «Кривая ключа»). */
    val curveNote: StateFlow<String?> = _curveNote.asStateFlow()

    /** Ключ (слой/кость, кадр), который правит «Кривая ключа», или null (SingleKeyCurve.pick). */
    fun singleKey(): Pair<Int, Int>? = com.vibetoon.domain.anim.SingleKeyCurve.pick(
        _keySel.value, _selected.value, _frame.value, _animation.value.tracks, ::isBoneId)

    fun singleKeyInfo(): com.vibetoon.domain.anim.SingleKeyCurve.Info? = singleKey()?.let { (id, f) ->
        com.vibetoon.domain.anim.SingleKeyCurve.info(id, f, _animation.value.tracks, isBoneId(id))
    }

    /** Редактор кривой только для одного ключа: остальные ключи слоя и другие слои не меняются. */
    fun openSingleKeyCurve() {
        if (_playing.value) return
        val (id, f) = singleKey() ?: run { _state.value = UiState.Message("Выберите один ключ: тапните ◆ на таймлайне"); return }
        if (isLockedId(id)) { lockedNotice(); return }
        selectedCurveTargets = com.vibetoon.domain.anim.SingleKeyCurve.targets(id, f, _animation.value.tracks, isBoneId(id))
        _curveTitle.value = "Кривая ключа"
        _curveNote.value = "Только ключ кадра ${f + 1}: остальные ключи не меняются"
        _curveEditor.value = true
    }

    /** Пресет плавности только одному ключу (своя кривая сбрасывается), одной записью Undo. */
    fun setSingleKeyEasing(type: EasingType) {
        if (_playing.value) return
        val (id, f) = singleKey() ?: run { _state.value = UiState.Message("Выберите один ключ: тапните ◆ на таймлайне"); return }
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("keyease", force = true)
        _animation.value = com.vibetoon.domain.anim.SingleKeyCurve.withEasing(_animation.value, id, f, isBoneId(id), type)
        pushScene(); refreshInspector(); markDirty()
    }

    fun openCurveEditor(on: Boolean) {
        selectedCurveTargets = null
        _curveTitle.value = "Кривая плавности"; _curveNote.value = null
        // спринт 44: на таймлайне выбран один ромб - f(x) правит именно его, а не все ключи выделенного на кадре плейхеда
        if (on && _keySel.value.size == 1 && singleKey() != null) { openSingleKeyCurve(); return }
        if (on && curveTargets().isEmpty()) { _state.value = UiState.Message("На этом кадре нет ключа у выделенного объекта"); return }
        _curveEditor.value = on
    }

    /** Текущая кривая первого ключа или кривая его пресета плавности. */
    fun currentCurve(): com.vibetoon.domain.anim.Curve {
        val target = selectedCurveTargets?.firstOrNull() ?: curveTargets().firstOrNull()?.let { it to _frame.value }
            ?: return com.vibetoon.domain.anim.Curve.LINEAR
        val k = _animation.value.tracks[target.first]?.keyAt(target.second) ?: return com.vibetoon.domain.anim.Curve.LINEAR
        return k.curve?.let { com.vibetoon.domain.anim.Curve.parse(it) } ?: com.vibetoon.domain.anim.Curve.of(k.easing)
    }

    /** Применяет кривую ко всем ключам редактора на текущем кадре. [commit] - точка отмены (начало жеста). */
    fun setCurve(curve: com.vibetoon.domain.anim.Curve, commit: Boolean) {
        if (_playing.value) return
        val chosen = selectedCurveTargets
        if (commit) checkpoint("curve", force = true) else if (chosen == null) checkpoint("curve")
        var a = _animation.value
        val data = curve.toList()
        if (chosen != null) a = com.vibetoon.domain.anim.SelectedKeyCurves.apply(a, chosen, data)
        else for (id in curveTargets()) a = a.withCurve(id, _frame.value, data)
        _animation.value = a
        pushScene()
        markDirty()
    }

    fun setEasing(type: EasingType) {
        if (_playing.value) return
        checkpoint("easing", force = true)
        for (id in curveTargets()) {
            if (_animation.value.hasKey(id, _frame.value)) _animation.value = _animation.value.withEasing(id, _frame.value, type)
        }
        pushScene()
        refreshInspector()
        markDirty()
        Log.i("VT/Anim", "easing $type at frame ${_frame.value}")
    }

    /** Эффективная трансформация слоя на текущем кадре: правка кадра > трек > ручная > единичная. */
    private fun effective(id: Int): LayerTransform =
        override[id] ?: _clips.value[id]?.let { c -> _animation.value.evaluate(id, c.animFrame(_frame.value), base[id]) }
            ?: _animation.value.evaluate(id, _frame.value, base[id])

    /**
     * Спринт 37: трансформация призрака кальки. Обычно - текущая трансформация слоя (призрак едет вместе с рисунком).
     * «Out of Pegs» - трансформация слоя на кадре самого рисунка-призрака (с ключами и плашкой), без живой правки
     * текущего кадра: соседние рисунки стоят на своих местах, пока текущий двигают или трансформируют.
     */
    private fun ghostTransform(g: Ghost): LayerTransform {
        if (!_onionSettings.value.outOfPegs) return effective(g.owner)
        val f = g.frame
        return _clips.value[g.owner]?.let { c -> _animation.value.evaluate(g.owner, c.animFrame(f), base[g.owner]) }
            ?: _animation.value.evaluate(g.owner, f, base[g.owner])
    }

    /** Трансформации всех изменённых слоёв на текущем кадре - вход для SceneBuilder. */
    private fun transformsNow(): Map<Int, LayerTransform> {
        // Спринт 43: только узлы сцены (слои, группы, кости, призраки). Раньше сюда попадали id ВСЕХ вершин сеток, точек
        // Warp и пинов из base и дорожек - десятки тысяч лишних вычислений трансформации на каждом кадре Play/скраббинга.
        val node = com.vibetoon.domain.render.SceneHotPath::isNodeId
        val ids = HashSet<Int>().apply {
            for (id in base.keys) if (node(id)) add(id)
            for (id in override.keys) if (node(id)) add(id)
            for (id in _animation.value.tracks.keys) if (node(id)) add(id)
            addAll(_clips.value.keys); addAll(fxLayers.keys); addAll(hiddenIds()); addAll(ghosts.map { it.id })
        }
        val look = sceneLookNow()   // пакет 24: свои слои сцен и переходы
        ids.addAll(look.alpha.keys); ids.addAll(look.shiftX.keys)
        // вне клипа слой невидим: нулевая прозрачность (у группы скрывает и всех потомков)
        val rest = _boneMode.value && _bindPose.value   // Bind Pose: кости не деформируют картинку
        val ghostById = if (ghosts.isEmpty()) emptyMap() else ghosts.associateBy { it.id }
        // Пакет 22: скрытые символами слои (содержимое символов на главной сцене, вне открытого символа - всё прочее)
        // не рисуются; слои контекста символа видны с прозрачностью Symbol Context Opacity
        val symHidden = symbolHidden(_editingSymbol.value)
        val ctxIds = symbolContextIds()
        val ctxAlpha = symbolContextAlpha()
        return ids.filter { !(rest && it >= BONE_ID_BASE) }.associateWith { id ->
            ghostById[id]?.let { g -> return@associateWith ghostTransform(g).let { o -> o.copy(opacity = o.opacity * g.alpha) } }   // призрак кальки идёт за своим слоем
            val t0 = effective(id)
            val t = fxLayers[id]?.let { l -> t0.copy(rotation = t0.rotation + com.vibetoon.domain.anim.EffectTracks.extraRotation(id, l, _animation.value, _frame.value, _animation.value.settings.fps)) } ?: t0
            val c = _clips.value[id]
            if (layerProps[id]?.visible == false || (c != null && !c.visibleAt(_frame.value))) t.copy(opacity = 0f)   // глаз выключен: слой не рисуется
            else if (id in ctxIds) t.copy(opacity = t.opacity * ctxAlpha)
            else if (id in symHidden) t.copy(opacity = 0f)
            else withSceneLook(id, t, look, canvasW, fit)
        }
    }

    // ---------------------------------------------------------------- контекст символа (пакет 22)

    /** Экземпляр, через который открыт символ: по нему выравнивается главная сцена вокруг символа. */
    private var symbolEditInstance: Int? = null

    private fun appPrefs() = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)

    private fun symbolContextAlpha(): Float = com.vibetoon.domain.render.SymbolContext.opacity(
        appPrefs().getInt("symbol_context_opacity", com.vibetoon.domain.render.SymbolContext.DEFAULT_OPACITY_PERCENT))

    /**
     * Слои главной сцены, видимые вокруг открытого символа (Show Symbol Context): листья вне всех определений символов,
     * кроме самого экземпляра (его место занимает редактируемое содержимое), камеры, null-объектов и костей.
     */
    private fun symbolContextIds(): Set<Int> {
        val ctx = _editingSymbol.value ?: return emptySet()
        val inst = symbolEditInstance ?: return emptySet()
        if (media.none { it.id == inst && it.kind == MediaKind.SYMBOL && it.symRef == ctx }) return emptySet()
        if (!appPrefs().getBoolean("symbol_context", true)) return emptySet()
        val inside = HashSet<Int>()
        for (d in media) if (d.kind == MediaKind.GROUP && d.symDur > 0) inside += symbolMembers(d.id)
        val skip = media.filter { it.kind == MediaKind.CAMERA || it.isRig }.mapTo(HashSet()) { it.id }
        return layers.asSequence().filter { it.kind == LayerKind.LAYER && it.id < BONE_ID_BASE && it.id != inst && it.id !in inside && it.id !in skip }
            .map { it.id }.toHashSet()
    }

    /** Пакет 22: выравнивание контекста по экземпляру; без экземпляра или при вырожденной матрице контекст не показывается. */
    private fun applySymbolContext(sc: SceneData): SceneData {
        val ids = symbolContextIds()
        if (ids.isEmpty()) return sc
        val ctx = _editingSymbol.value ?: return sc
        val inst = media.firstOrNull { it.id == symbolEditInstance } ?: return sc
        val unit = com.vibetoon.domain.render.SymbolContext.layerMatrix(sc, inst.id) ?: return sc
        val parentOf = layers.associate { it.id to it.parentId }
        val shared = symbolSnapshotContext(ctx, parentOf).first
        val deep = com.vibetoon.domain.render.SymbolContext.deepest(shared) { parentOf[it] }
        val cam = cameraIdNow()
        // снимок формы: общие предки нейтральны, PSD по-прежнему вписан матрицей fit -> у группы PSD мир * fit⁻¹
        val formToEdit = deep?.let { g -> sc.groupWorlds[g]?.let { w -> if (g >= MEDIA_ID_BASE) w else w * (fit.inverse() ?: Affine2D.IDENTITY) } }
            ?: SceneBuilder.cameraView(if (cam >= 0) effective(cam) else null, canvasW, canvasH)
        val m = com.vibetoon.domain.render.SymbolContext.mainToEdit(formToEdit, unit, 0f, 0f, canvasW.toFloat(), canvasH.toFloat()) ?: return sc   // форма - снимок всего холста
        return com.vibetoon.domain.render.SymbolContext.apply(sc, ids, m)
    }

    /** Настройки контекста символа изменились: сцена перестраивается сразу. */
    fun refreshSymbolContext() { if (_editingSymbol.value != null) pushScene() }

    private fun independentBones(): Set<Int> = _bones.value.filter { it.independentRot }.mapTo(HashSet()) { it.id }

    private fun boneHeads(): Map<Int, Pair<Float, Float>> = _bones.value.associate { it.id to (it.hx to it.hy) }
    private fun boneTails(): Map<Int, Pair<Float, Float>> = _bones.value.associate { it.id to (it.tx to it.ty) }

    private fun buildEditorScene(warpIds: Set<Int> = warpLayers, perspIds: Set<Int> = perspLayers, useGroups: Boolean = true, dense: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid> = denseWarps): SceneData =
        buildEditorSceneRaw(warpIds, perspIds, useGroups, dense).also { it.backgroundOverride = sceneLookNow().background }   // пакет 24: фон сцены / переход через цвет

    /** Спринт 43: готовые сетки слоёв редактора по id слоя (изоляция слоёв и экономия Play/скраббинга). */
    private val deformCache = com.vibetoon.domain.render.DeformCache()

    private fun buildEditorSceneRaw(warpIds: Set<Int>, perspIds: Set<Int>, useGroups: Boolean, dense: Map<Int, com.vibetoon.domain.anim.DenseWarpGrid>): SceneData {
        val options = com.vibetoon.domain.render.SceneOptions.capture(media, layerProps)
        val inputs = com.vibetoon.domain.render.GroupSceneInputs.build(groupDeformationAnchors, warpIds, perspIds,
            _frame.value, base, _animation.value, _fx.value, warpGridSizes)
        return SceneBuilder.buildTree(sceneRoots(), canvasW, canvasH, transformsNow(), fit, textIds, _links.value, boneHeads(), inputs.warps,
            independentBones(), meshesNow(), inputs.perspectives, boneTails(), effectiveSkins(), turbNow(), fxNow(), cameraIdNow(),
            options.nullIds, options.depths, groupDeformations = if (useGroups) inputs.groups else emptyMap(), denseWarps = dense, leafPivots = pivotOverrides,
            stretches = com.vibetoon.domain.anim.EffectTracks.stretches(fxLayers, _animation.value, _frame.value),
            stretchMasks = com.vibetoon.domain.anim.EffectTracks.stretchMasks(fxLayers, _animation.value, _frame.value),
            layerMasks = options.layerMasks, maskSources = options.maskSources,
            pathPlaces = options.pathPlaces { lid -> effective(com.vibetoon.domain.anim.PathLayerMath.progressId(lid)).dx },
            deformCache = deformCache)
    }

    private fun pushScene() {
        refreshGridView()
        // Спринт 43: деформации больше не выключаются при «неподдерживаемом» сочетании - SceneBuilder складывает их
        // (DeformStack). Непредвиденная ошибка не роняет приложение и ничего не отключает: остаётся прежний кадр.
        val sc0 = try { buildEditorScene() } catch (e: RuntimeException) {
            Log.e("VT/Scene", "scene build failed", e)
            _state.value = UiState.Message("Кадр не обновлён: ошибка построения сцены")
            return
        }
        val sc = applySymbolContext(sc0)   // пакет 22: главная сцена вокруг открытого символа
        // формы символов едут вместе со сценой: на любом кадре экземпляр показывает форму именно этого кадра
        if (projectId != null && !appBackground) sc.aliases = buildAliases(_frame.value)
        scene = sc
        RenderHost.view?.setScene(sc)
        // Во время Play панели (инспектор, эффекты, турбулентность) не пересчитываются каждый кадр: их перерисовка в Compose
        // съедала UI-поток. Значения обновляются при паузе (pause() -> refreshPanelsAfterPlay).
        if (!_playing.value) {
            warpTarget()?.let { id -> if (id in turbLayers) _turb.value = turbEffective(id) }
            refreshFxState()
        }
        refreshBoneOverlay()
        updateSelectionFrame()
        reloadNonResident()   // пакет 29: вытесненные текстуры, снова попавшие в сцену
        if (!_playing.value) pushEvictable()
    }

    private fun refreshPanelsAfterPlay() {
        warpTarget()?.let { id -> if (id in turbLayers) _turb.value = turbEffective(id) }
        refreshFxState()
        refreshInspector()
    }

    /**
     * Куда записывается правка жестом:
     *  - у слоя есть трек и на кадре есть ключ -> правится этот ключ;
     *  - трек есть, ключа нет -> временная правка кадра (сохранится кнопкой Add Key);
     *  - трека нет -> ручная трансформация base.
     */
    private fun autoKeyEnabled(): Boolean = getApplication<Application>()
        .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("auto_keyframes", false)

    private fun canAutoKeyTransform(id: Int): Boolean {
        val frame = _frame.value
        val source = _clips.value[id]?.animFrame(frame) ?: frame.toFloat()
        return com.vibetoon.domain.anim.AutoKeyEdits.canKeyTransform(frame, source)
    }

    private fun storeEdit(id: Int, t: LayerTransform) {
        if (t == effective(id)) return
        val anim = _animation.value
        val auto = autoKeyEnabled()
        val canKey = canAutoKeyTransform(id)
        if (auto && !canKey) _state.value = UiState.Message("Автоключ трансформации недоступен при ретайминге клипа")
        // Спринт 43: у анимированного свойства правка на другом кадре сразу становится ключом (раньше - временная правка,
        // которая пропадала при перемотке без Add Key); первая правка с автоключом ставит и базовый ключ на кадре 0
        val keyed = com.vibetoon.domain.anim.LayerKeyFlow.transformEdit(anim, id, _frame.value, effective(id), t, fxLayers[id].orEmpty(), auto, canKey)
        when {
            keyed != null -> { _animation.value = keyed; override.remove(id) }
            anim.hasTrack(id) -> override[id] = t
            else -> base[id] = t
        }
    }

    private fun applyEdit(id: Int, t: LayerTransform) {
        storeEdit(id, t)
        pushScene()
        markDirty()
    }

    /** Один палец при выделенном слое: двигаем слой. */
    fun onDragLayer(dx: Float, dy: Float) = onTransformLayer(1f, 1f, 0f, dx, dy)

    /**
     * Два пальца при выделенном слое: масштаб по осям (dzX, dzY раз), поворот (градусы) и сдвиг (экранные px).
     * Во время воспроизведения правка слоя отключена.
     */
    fun onTransformLayer(dzX: Float, dzY: Float, rotationDeg: Float, dx: Float, dy: Float) {
        if (_playing.value) return
        val ids = transformTargets().ifEmpty { return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        checkpoint("gesture:${_frame.value}:${ids.sorted()}")
        if (!autoKeyEnabled() && !baseEditHinted && ids.any { !_animation.value.hasTrack(it) }) {
            baseEditHinted = true
            _state.value = UiState.Message("Слой без анимации: правка действует на все кадры. Чтобы анимировать, поставьте ключ кнопкой ◎ на таймлайне")
        }
        val s = Camera.fitScale(viewW, viewH, canvasW, canvasH) * camera.zoom
        // Сдвиг пальца: экран -> холст -> координаты РОДИТЕЛЯ каждого узла (родитель может быть повёрнут
        // или масштабирован), иначе вложенная группа убегала бы от пальца. Масштаб и поворот - вокруг
        // собственной опорной точки каждого выделенного узла; сдвиг одинаковый у всех.
        // С поворотом/отражением вида сдвиг пальца переводится в холст обратной матрицей камеры.
        val (o0x, o0y) = toCanvas(0f, 0f)
        val (o1x, o1y) = toCanvas(dx, dy)
        val cx = if (o1x.isFinite() && o0x.isFinite()) o1x - o0x else dx / s
        val cy = if (o1y.isFinite() && o0y.isFinite()) o1y - o0y else dy / s
        for (id in ids) {
            val inv = scene?.parentWorlds?.get(id)?.inverse() ?: Affine2D.IDENTITY
            val lx = inv.a * cx + inv.c * cy
            val ly = inv.b * cx + inv.d * cy
            val t = effective(id)
            storeEdit(
                id,
                t.copy(
                    dx = t.dx + lx,
                    dy = t.dy + ly,
                    scaleX = (t.scaleX * dzX).coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE),
                    scaleY = (t.scaleY * dzY).coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE),
                    rotation = t.rotation + Math.toRadians(rotationDeg.toDouble()).toFloat()
                )
            )
        }
        pushScene()
        markDirty()
    }

    /**
     * Reset Transform: если на кадре есть ключ, он сбрасывается в единичную трансформацию;
     * если есть временная правка, она отменяется; без анимации сбрасывается ручная трансформация.
     */
    fun resetLayerTransform() {
        if (_playing.value) return
        val ids = transformTargets().ifEmpty { return }
        checkpoint("reset", force = true)
        for (id in ids) {
            val anim = _animation.value
            if (autoKeyEnabled()) { storeEdit(id, LayerTransform()); continue }
            when {
                anim.hasKey(id, _frame.value) -> _animation.value = anim.withKey(id, _frame.value, LayerTransform())
                override.remove(id) != null -> {}
                else -> base.remove(id)
            }
        }
        pushScene()
        markDirty()
        Log.i("VT/Edit", "transform reset: $ids")
    }

    // ---------------------------------------------------------------- пивоты (опорные точки)

    /** Текущий пивот узла в координатах до трансформации (переопределение или центр по умолчанию). */
    fun pivotOf(id: Int): Pair<Float, Float>? = pivots[id]

    /** Пивот на холсте с учётом смещения узла и родителей. */
    private fun pivotWorld(id: Int): Pair<Float, Float>? {
        val (px, py) = pivots[id] ?: return null
        val pw = scene?.parentWorlds?.get(id) ?: return null
        return com.vibetoon.domain.render.PivotMath.world(pw, effective(id), px, py)
    }

    /** Пивот правится только в обычном режиме Transform (не кости/деформации/рисование). */
    private fun pivotToolActive(): Boolean = appSettings()?.pivotEditing != false && !_boneMode.value && !_warpTool.value && !_pathDraw.value &&
        !_meshMode.value && !_perspTool.value && !_liquifyTool.value && _paintTool.value == null && _rasterTool.value == null &&
        _selected.value.let { it != null && it < BONE_ID_BASE }

    /** Пивот в экранных координатах для оверлея. */
    fun pivotScreen(id: Int): Pair<Float, Float>? {
        if (!pivotToolActive()) return null
        val (wx, wy) = pivotWorld(id) ?: return null
        return toScreen(wx, wy)
    }

    /**
     * Перенос пивота узла без скачка картинки: смещение base/ключей компенсируется (PivotMath).
     * [keepOverride] = false снимает переопределение (пивот по умолчанию).
     */
    private fun applyPivot(id: Int, nx: Float, ny: Float, keepOverride: Boolean = true) {
        if (!nx.isFinite() || !ny.isFinite()) return
        val (ox, oy) = pivots[id] ?: return
        val pm = com.vibetoon.domain.render.PivotMath
        if (ox != nx || oy != ny) {
            base[id]?.let { base[id] = pm.compensate(it, ox, oy, nx, ny) }
            override[id]?.let { override[id] = pm.compensate(it, ox, oy, nx, ny) }
            val anim = _animation.value
            anim.tracks[id]?.let { tr ->
                if (tr.keys.isNotEmpty()) _animation.value = anim.copy(tracks = anim.tracks + (id to com.vibetoon.domain.anim.AnimationTrack(id, tr.keys.map { pm.compensate(it, ox, oy, nx, ny) })))
            }
        }
        pivotOverrides = if (keepOverride) pivotOverrides + (id to (nx to ny)) else pivotOverrides - id
        rebuildLayers()
        pushScene()
        refreshInspector()
        markDirty()
    }

    /** Пивот по умолчанию: центр рамки листа или центр рамки детей группы (без переопределения). */
    private fun defaultPivot(id: Int): Pair<Float, Float>? {
        if (id !in pivotOverrides) return pivots[id]
        val saved = pivotOverrides
        pivotOverrides = pivotOverrides - id
        val p = computePivots(com.vibetoon.domain.model.SceneTree.build(layers, pivotOverrides))[id]
        pivotOverrides = saved
        return p
    }

    private var pivotDragId: Int? = null
    private var pivotDragStart: Pair<Float, Float>? = null
    private var pivotDragHadOverride = false
    private var pivotChecked = false

    /** Начало перетаскивания пивота: точка должна быть рядом с пивотом на экране. */
    fun pivotDown(sx: Float, sy: Float): Boolean {
        if (_playing.value || !pivotToolActive()) return false
        val id = _selected.value ?: return false
        val start = pivots[id] ?: return false
        val (wx0, wy0) = pivotWorld(id) ?: return false
        val sp = toScreen(wx0, wy0)
        if (kotlin.math.hypot(sx - sp.first, sy - sp.second) > PIVOT_HIT_PX) return false
        if (isLockedId(id)) { lockedNotice(); return false }
        pivotChecked = false   // снимок истории - при первом сдвиге, тап по маркеру историю не засоряет
        pivotDragId = id
        pivotDragStart = start
        pivotDragHadOverride = id in pivotOverrides
        return true
    }

    /** Перетаскивание пивота. Снап (на холсте): центр холста и углы рамки узла, радиус 12 px экрана. */
    fun pivotMove(sx: Float, sy: Float) {
        val id = pivotDragId ?: return
        val (px, py) = pivots[id] ?: return
        if (!pivotChecked) { checkpoint("pivot", force = true); pivotChecked = true }
        var (x, y) = toCanvas(sx, sy)
        val snap = 12f / (Camera.fitScale(viewW, viewH, canvasW, canvasH) * camera.zoom).coerceAtLeast(1e-4f)
        val q = scene?.selectionQuads?.get(id)
        var snapped = false
        if (q != null && q.size >= 8) {
            val cxq = (q[0] + q[2] + q[4] + q[6]) / 4f; val cyq = (q[1] + q[3] + q[5] + q[7]) / 4f
            val cands = listOf(q[0] to q[1], q[2] to q[3], q[4] to q[5], q[6] to q[7], cxq to cyq)
            for ((qx, qy) in cands) if (kotlin.math.hypot(x - qx, y - qy) < snap) { x = qx; y = qy; snapped = true; break }
        }
        if (!snapped && kotlin.math.hypot(x - canvasW / 2f, y - canvasH / 2f) < snap) { x = canvasW / 2f; y = canvasH / 2f }
        val pw = scene?.parentWorlds?.get(id) ?: return
        val (lx, ly) = com.vibetoon.domain.render.PivotMath.toLocal(pw, effective(id), px, py, x, y) ?: return
        applyPivot(id, lx, ly)
    }

    /** Отпускание пивота. Temporary Pivots: перенос откатывается (картинка на месте, пивот прежний). */
    fun pivotUp() {
        val id = pivotDragId ?: return
        val start = pivotDragStart
        pivotDragId = null
        pivotDragStart = null
        if (pivotChecked && appSettings()?.temporaryPivots == true && start != null) applyPivot(id, start.first, start.second, keepOverride = pivotDragHadOverride)
    }

    /** Пивот из полей инспектора: точное значение, Undo через checkpoint. */
    fun setPivot(id: Int, x: Float, y: Float) {
        if (_playing.value || !x.isFinite() || !y.isFinite()) return
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("pivotField:$id")
        applyPivot(id, x, y)
    }

    /** «To center»: пивот в видимый центр рамки узла. */
    fun pivotToCenter() {
        val id = _selected.value ?: return
        if (_playing.value) return
        if (isLockedId(id)) { lockedNotice(); return }
        val q = scene?.selectionQuads?.get(id) ?: return
        val (px, py) = pivots[id] ?: return
        val pw = scene?.parentWorlds?.get(id) ?: return
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val (lx, ly) = com.vibetoon.domain.render.PivotMath.toLocal(pw, effective(id), px, py, cx, cy) ?: return
        checkpoint("pivotCenter", force = true)
        applyPivot(id, lx, ly)
    }

    /** Сброс пивота к значению по умолчанию (центр рамки) без скачка картинки. */
    fun pivotReset() {
        val id = _selected.value ?: return
        if (_playing.value) return
        if (id !in pivotOverrides) return
        if (isLockedId(id)) { lockedNotice(); return }
        val (dx, dy) = defaultPivot(id) ?: return
        checkpoint("pivotReset", force = true)
        applyPivot(id, dx, dy, keepOverride = false)
    }

    /** Один палец без выделения: двигаем камеру. */
    fun onPan(dx: Float, dy: Float) = onTransform(viewW / 2f, viewH / 2f, 1f, dx, dy)

    // ---------------------------------------------------------------- ключевые кадры

    /** Кнопка Add/Delete Key: переключает ключ выделенного слоя на текущем кадре. */
    fun toggleKey() {
        val prim = _selected.value ?: return
        if (_playing.value) return
        val ids = transformTargets().ifEmpty { return }
        val frame = _frame.value
        checkpoint("key", force = true)
        // Спринт 43: ключ слоя - все его дорожки (трансформация + сетка, пины, Warp, перспектива), как ромб таймлайна
        val flow = com.vibetoon.domain.anim.LayerKeyFlow
        val removing = flow.hasKey(_animation.value, prim, frame)
        for (id in ids) {
            val anim = _animation.value
            if (removing) {
                if (!flow.hasKey(anim, id, frame)) continue
                removeEffectKeys(id, frame)
                // Последний ключ удалён: слой и форма деформеров остаются на месте (смещения - к форме привязки)
                val (next, updates) = flow.removeKey(_animation.value, id, frame, base)
                _animation.value = next
                base.putAll(updates)
                Log.i("VT/Anim", "key deleted: layer $id frame $frame")
            } else {
                // Первый ключ создаётся из текущего положения, поэтому слой не прыгает; деформеры фиксируются целиком
                // (неанимированный деформер на кадре > 0 получает и базовый ключ исходной формы на кадре 0)
                val t = if (anim.hasKey(id, frame)) null else effective(id)
                override.remove(id)
                var next = flow.addKey(anim, id, frame, t, deformerGroupsOf(id))
                if (t != null) next = com.vibetoon.domain.anim.EffectTracks.snapshot(id, fxLayers[id].orEmpty(), next, frame)
                _animation.value = next
                Log.i("VT/Anim", "key added: layer $id frame $frame")
            }
        }
        pushScene()
        markDirty()
    }

    /** Спринт 43: деформеры слоя для ключа «слой целиком» (вершины, пины, точки Warp, углы перспективы). */
    private fun deformerGroupsOf(id: Int): List<List<Int>> {
        if (id >= BONE_ID_BASE) return emptyList()
        val warpPoints = if (id in warpLayers) (warpGridSizes[id] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT).let { it.width * it.height } else 0
        return com.vibetoon.domain.anim.LayerKeyFlow.deformerGroups(id, meshLayers[id]?.count ?: 0, pinLayers[id]?.size ?: 0, warpPoints, id in perspLayers)
    }

    // ---------------------------------------------------------------- Undo / Redo

    private fun snapshot() = Snapshot(HashMap(base), _animation.value, media.toList(), deletedPsd, _clips.value, _links.value, _bones.value, regroup, warpLayers, meshLayers, perspLayers, skinLayers, turbLayers, fxLayers, pinLayers, layerProps, groupAnchors = groupDeformationAnchors.toMap(), warpSizes = warpGridSizes.toMap(), denseWarps = denseWarps.toMap(), pivots = pivotOverrides.toMap(), scenes = _scenes.value, stackOrder = stackOrder)

    private fun updateHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private fun clearHistory() {
        undoStack.clear(); redoStack.clear(); lastCheckKey = null
        updateHistoryFlags()
    }

    /**
     * Запоминает состояние ДО правки. Подряд идущие правки одного вида (протяжка жестом, свайп по
     * полю, набор цифр) за 0.7 с склеиваются в одну запись отмены; [force] - всегда отдельная запись.
     */
    private fun checkpoint(key: String, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && key == lastCheckKey && now - lastCheckTime < 700) {
            lastCheckTime = now
            return
        }
        lastCheckKey = if (force) null else key
        lastCheckTime = now
        undoStack.addLast(snapshot())
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryFlags()
    }

    fun undo() {
        if (_selectionExtractBusy.value) return
        cancelPendingLasso()
        if (undoStack.isEmpty() || _playing.value) return
        val candidate = undoStack.last()
        if (candidate.textureOp != null) { restoreExpandedHistory(candidate, undo = true); return }
        val s = undoStack.removeLast()
        redoStack.addLast(snapshot().withPaint(s.paintOp))
        restore(s)
        s.paintOp?.let { applyPaint(it, useBefore = true) }
        updateHistoryFlags()
        Log.i("VT/Edit", "undo (left ${undoStack.size})")
    }

    fun redo() {
        if (_selectionExtractBusy.value) return
        cancelPendingLasso()
        if (redoStack.isEmpty() || _playing.value) return
        val candidate = redoStack.last()
        if (candidate.textureOp != null) { restoreExpandedHistory(candidate, undo = false); return }
        val s = redoStack.removeLast()
        undoStack.addLast(snapshot().withPaint(s.paintOp))
        restore(s)
        s.paintOp?.let { applyPaint(it, useBefore = false) }
        updateHistoryFlags()
        Log.i("VT/Edit", "redo (left ${redoStack.size})")
    }

    /** Вызывать только под rasterTransactionGate в NonCancellable: запущенный GL ещё владеет состоянием. */
    private suspend fun replaceExpandedTexture(
        view: com.vibetoon.render.VtGLSurfaceView,
        expected: com.vibetoon.domain.raster.ExpandedLayerSnapshot.State,
        desired: com.vibetoon.domain.raster.ExpandedLayerSnapshot.State
    ): Boolean {
        val reply = kotlinx.coroutines.CompletableDeferred<com.vibetoon.domain.raster.TextureReplaceResult>()
        val request = view.replaceTextureChecked(expected.media.id, expected.width, expected.height, desired.width, desired.height, desired.rgba) { reply.complete(it) }
        val first = kotlinx.coroutines.withTimeoutOrNull(4000) { reply.await() }
        if (first == null && request.cancelIfQueued()) return false
        return (first ?: reply.await()) == com.vibetoon.domain.raster.TextureReplaceResult.SUCCESS
    }

    /** История расширения фиксируется только после подтверждения GL; отказ не меняет стек. */
    private fun restoreExpandedHistory(target: Snapshot, undo: Boolean) {
        val op = target.textureOp ?: return
        val transition = op.transition(if (undo) com.vibetoon.domain.raster.ExpandedLayerHistory.Direction.UNDO else com.vibetoon.domain.raster.ExpandedLayerHistory.Direction.REDO)
        val view = RenderHost.view ?: return
        val pid = projectId ?: return
        val from = transition.expected
        val to = transition.target
        if (!com.vibetoon.domain.raster.RasterVersionPolicy.sameLayerIgnoringVersion(media.firstOrNull { it.id == from.media.id }, from.media) || from.media.id != to.media.id) {
            _state.value = UiState.Message("Слой изменился: восстановление отменено")
            return
        }
        _selectionExtractBusy.value = true
        viewModelScope.launch {
            try {
                // После постановки GL-операции не прерываем публикацию из-за отмены coroutine.
                withContext(kotlinx.coroutines.NonCancellable) {
                    rasterTransactionGate.run {
                        if (projectId != pid || RenderHost.view !== view || !com.vibetoon.domain.raster.RasterVersionPolicy.sameLayerIgnoringVersion(media.firstOrNull { it.id == from.media.id }, from.media)) return@run
                        val source = if (undo) undoStack else redoStack
                        val destination = if (undo) redoStack else undoStack
                        val previous = snapshot()
                        val undoBefore = undoStack.toList()
                        val redoBefore = redoStack.toList()
                        var publishing = false
                        val outcome = com.vibetoon.domain.raster.TextureTransaction.execute(
                            replace = { replaceExpandedTexture(view, from, to) },
                            publish = {
                                check(projectId == pid && RenderHost.view === view && source.lastOrNull() === target)
                                publishing = true
                                source.removeLast()
                                destination.addLast(previous.withTexture(op))
                                paintSizes[from.media.id] = intArrayOf(to.width, to.height)
                                clearSelection()
                                restore(target)
                                invalidateThumb(from.media.id)
                                paintDirty += from.media.id
                                markDirty()
                                updateHistoryFlags()
                                enforcePaintBudget()
                            },
                            rollback = {
                                if (projectId != pid || RenderHost.view !== view || !replaceExpandedTexture(view, to, from)) false
                                else {
                                    if (publishing) {
                                        undoStack.clear(); undoStack.addAll(undoBefore)
                                        redoStack.clear(); redoStack.addAll(redoBefore)
                                        paintSizes[from.media.id] = intArrayOf(from.width, from.height)
                                        restore(previous)
                                        invalidateThumb(from.media.id)
                                        paintDirty += from.media.id
                                        updateHistoryFlags()
                                    }
                                    true
                                }
                            }
                        )
                        when (outcome) {
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.COMMITTED -> Unit
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.REJECTED,
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.ROLLED_BACK ->
                                _state.value = UiState.Message("Не удалось восстановить текстуру; история не изменена")
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.RECOVERY_REQUIRED -> {
                                rasterRecoveryRequired = true
                                _state.value = UiState.Error("Не удалось безопасно восстановить текстуру; сохранение приостановлено")
                            }
                        }
                    }
                }
            } finally { if (!rasterRecoveryRequired) _selectionExtractBusy.value = false }
        }
    }

    private fun restore(snap: Snapshot) {
        val oldMedia = media.toList()
        lastCheckKey = null
        base.clear(); base.putAll(snap.base)
        override.clear()
        _animation.value = snap.animation
        _scenes.value = snap.scenes
        meta = meta?.copy(fps = snap.animation.settings.fps, durationFrames = snap.animation.settings.durationFrames)
        media.clear(); media.addAll(snap.media.map { item ->
            val current = oldMedia.firstOrNull { it.id == item.id }
            val versioned = item.id in versionedRasterIds || (current != null && com.vibetoon.domain.raster.RasterVersionPolicy.isVersionPath(current.file))
            com.vibetoon.domain.raster.RasterVersionPolicy.keepCurrentFile(item, current, versioned)
        })
        deletedPsd = snap.deleted
        _clips.value = snap.clips
        _links.value = snap.links
        _bones.value = snap.bones
        regroup = snap.regroup
        stackOrder = snap.stackOrder
        groupDeformationAnchors = snap.groupAnchors.toMap()
        warpGridSizes = snap.warpSizes.toMap()
        denseWarps = snap.denseWarps.toMap()
        pivotOverrides = snap.pivots.toMap()
        warpLayers = snap.warps
        meshLayers = snap.meshes
        perspLayers = snap.persps
        skinLayers = snap.skins
        turbLayers = snap.turbs
        fxLayers = snap.fx
        pinLayers = snap.pins
        setProps(snap.lprops)
        _pathRevision.value++   // спринт 38: панель «Положение на пути» перечитывает значение
        refreshFxState()
        _turb.value = warpTarget()?.let { turbEffective(it) }
        textJob?.cancel()
        rebuildLayers()
        val known = layers.mapTo(HashSet()) { it.id } + media.filter { !it.isVisual }.map { it.id } + _bones.value.map { it.id }
        val keep = _selection.value.filter { it in known }.toSet()
        _frame.value = _frame.value.coerceAtMost(snap.animation.settings.durationFrames - 1)
        applySelection(keep, if (_selected.value in keep) _selected.value else keep.lastOrNull())
        refreshSymbolState()
        invalidateFormsIfChanged()   // Undo/Redo ключей времени формы не пересъёмка; правка содержимого символа - пересъёмка
        pushScene()
        // текст, у которого изменилось содержимое, перерисовывается заново
        val changedText = media.filter { m ->
            m.isText && oldMedia.firstOrNull { it.id == m.id }.let { o ->
                o != null && (o.text != m.text || o.textColor != m.textColor || o.font != m.font || o.textSize != m.textSize ||
                    o.gradient != m.gradient || o.gradientAngle != m.gradientAngle || o.fx != m.fx ||
                    o.stroke != m.stroke || o.strokeWidth != m.strokeWidth || o.strokeColor != m.strokeColor ||
                    o.textAlign != m.textAlign || o.lineSpacing != m.lineSpacing || o.letterSpacing != m.letterSpacing || o.textDir != m.textDir)
            }
        }
        if (changedText.isNotEmpty()) rerenderTexts(changedText)
        for (m in media) if (m.vector && oldMedia.firstOrNull { it.id == m.id }?.vectors != m.vectors) renderVector(m, save = true)
        updateCells(_frame.value)
        refreshOnion()
        markDirty()
    }

    private fun rerenderTexts(items: List<MediaItem>) {
        viewModelScope.launch {
            for (m in items) {
                val (w, h) = try {
                    withContext(decodeDispatcher) { renderAndUpload(m) }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e("VT/Text", "text re-render failed", t)
                    continue
                }
                val i = media.indexOfFirst { it.id == m.id }
                if (i < 0) continue
                val cur = media[i]
                media[i] = cur.copy(x = cur.x + cur.w / 2f - w / 2f, y = cur.y + cur.h / 2f - h / 2f, w = w, h = h)
            }
            rebuildLayers()
            pushScene()
        }
    }

    // ---------------------------------------------------------------- клипы: границы и ретайминг

    private fun duration() = _animation.value.settings.durationFrames

    /** Клип узла (если записи нет - весь проект). */
    fun clipOf(id: Int): Clip = _clips.value[id] ?: defaultClip(id)

    /** Клип по умолчанию: слой виден весь проект, аудио звучит свою длину. */
    private fun defaultClip(id: Int): Clip {
        val d = duration()
        val m = media.firstOrNull { it.id == id }
        return if (m != null && !m.isVisual) {
            Clip(0, ProjectMath.framesFor(m.durationMs, _animation.value.settings.fps).coerceIn(1, d))
        } else Clip(0, d)
    }

    private fun storeClip(id: Int, clip: Clip) {
        val oldStart = _clips.value[id]?.start ?: 0
        _clips.value = if (clip == defaultClip(id)) _clips.value - id else _clips.value + (id to clip)
        // Спринт 37: Play Once идёт от начала плашки - сдвиг плашки меняет показанный кадр символа
        if (oldStart != (_clips.value[id]?.start ?: 0) && media.any { it.id == id && it.kind == MediaKind.SYMBOL && it.symOnce && it.timeKeys.isEmpty() })
            runCatching { updateSymbols(_frame.value) }
    }

    /** Перетаскивание клипа за середину полоски: начало меняется, длительность остаётся. */
    fun moveClipTo(id: Int, newStart: Int) {
        checkpoint("clipmove$id")
        val c = clipOf(id)
        // выделено несколько слоёв: все их клипы двигаются на одинаковое число кадров
        val group = if (id in _selection.value && _selection.value.size > 1) _selection.value else setOf(id)
        var delta = newStart - c.start
        for (g in group) {
            val gc = clipOf(g)
            delta = delta.coerceIn(-gc.start, (duration() - gc.end).coerceAtLeast(-gc.start))
        }
        for (g in group) {
            val gc = clipOf(g)
            storeClip(g, gc.copy(start = gc.start + delta, end = gc.end + delta))
        }
        pushScene()
        markDirty()
    }

    // ---- маркеры и быстрая навигация ----
    fun stepTimeline(direction: Int, byFrame: Boolean) {
        val id = _selected.value
        val points = if (id == null) emptySet() else {
            val cells = media.firstOrNull { it.id == id }?.cells.orEmpty().map { it.start }
            (cells + com.vibetoon.domain.anim.TrackGroups.frames(id, _animation.value.tracks)).toSet()
        }
        scrubTo(com.vibetoon.domain.anim.TimelineNavigation.step(_frame.value, direction, duration(), byFrame, points))
    }

    fun quickDelete(drawing: Boolean) {
        if (drawing) deleteFrame() else deleteSelected()
    }

    fun goStart() = scrubTo(activeRange()?.start ?: 0)
    fun goEnd() = scrubTo(activeRange()?.let { it.end - 1 } ?: (duration() - 1))

    fun toggleMarker() {
        val f = _frame.value
        val cur = _markers.value
        _markers.value = if (f in cur) cur - f else (cur + f).sorted()
        markDirty()
    }

    fun jumpMarker(forward: Boolean) {
        val f = _frame.value
        val target = if (forward) _markers.value.firstOrNull { it > f } else _markers.value.lastOrNull { it < f }
        if (target != null) scrubTo(target) else _state.value = UiState.Message("Маркеров в этом направлении нет")
    }

    /**
     * Ножницы: клип выделенного слоя делится на плейхеде на две независимые части (вторая - копия слоя
     * со своим клипом, трансформацией и ключами). Сейчас для картинок, видео, текста и аудио.
     */
    fun splitSelected() {
        val pid = projectId ?: return
        if (_playing.value) return
        val ids = _selection.value.ifEmpty { _selected.value?.let { setOf(it) }.orEmpty() }
        if (ids.isEmpty()) return
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        val frame = _frame.value
        // Нельзя разрезать часть выделения молча: сначала валидируем всю операцию.
        val items = ids.mapNotNull { id -> media.firstOrNull { it.id == id } }
        if (items.size != ids.size || items.any { it.kind !in setOf(MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.TEXT, MediaKind.AUDIO) }) {
            _state.value = UiState.Message("Разрезать пока можно картинки, видео, текст и аудио (слои PSD - позже)"); return
        }
        val halves = com.vibetoon.domain.project.ClipSplit.split(items.map { clipOf(it.id) }, frame)
        if (halves == null) {
            _state.value = UiState.Message("Поставьте плейхед внутри клипа каждого выбранного слоя"); return
        }
        checkpoint("split", force = true)
        val copies = ArrayList<MediaItem>()
        for ((item, parts) in items.zip(halves)) {
            val id = item.id
            val newId = nextMediaId()
            val copy = item.copy(id = newId)
            media += copy; copies += copy
            _clips.value = _clips.value + (id to parts.first) + (newId to parts.second)
            base[id]?.let { base[newId] = it }
            _animation.value.tracks[id]?.let { tr ->
                _animation.value = _animation.value.copy(tracks = _animation.value.tracks + (newId to AnimationTrack(newId, tr.keys)))
            }
            fxLayers[id]?.let { fxLayers = fxLayers + (newId to it); copyEffectTracks(id, newId) }
            layerProps[id]?.let { setProps(layerProps + (newId to it)) }
        }
        rebuildLayers(); pushScene()
        for (copy in copies.filter { it.isVisual }) viewModelScope.launch {
            withContext(decodeDispatcher) { runCatching { uploadTextureFor(pid, copy) } }
            RenderHost.view?.requestRender()
        }
        applySelection(copies.mapTo(LinkedHashSet()) { it.id }, copies.last().id)
        markDirty()
        _state.value = UiState.Message("Разрезано клипов: ${copies.size}")
    }

    /**
     * Дублировать: полная независимая копия выбранного медиа-слоя (картинка, видео, текст, рисунок) со своим клипом,
     * трансформацией, ключами, эффектами, свойствами слоя (blend, opacity) и цветокоррекцией. Файл рисунка копируется.
     */
    fun duplicateSelected() { val id = _selected.value ?: return; duplicateLayer(id, null) }

    /** Копия слоя-медиа; [above] - слой, на место которого встаёт копия (null - наверх). */
    fun duplicateLayer(id: Int, above: Int?) {
        val pid = projectId ?: return
        if (_playing.value) return
        val item = media.firstOrNull { it.id == id }
        if (item == null || !item.isVisual) { _state.value = UiState.Message("Дублировать можно картинки, видео, текст и рисунки"); return }
        if (item.kind == MediaKind.BONES) { _state.value = UiState.Message("Слой костей не дублируется: создайте новый и нарисуйте кости"); return }
        checkpoint("dup", force = true)
        val newId = nextMediaId()
        var copy = item.copy(id = newId, name = dn("${item.name} копия"))
        if (!item.isText && item.file.isNotEmpty()) {
            val ext = item.file.substringAfterLast('.', "png")
            val src = projects.fileOf(pid, item.file)
            val name = "m$newId.$ext"
            if (src.exists()) { runCatching { src.copyTo(projects.mediaFile(pid, name), overwrite = true) }.onSuccess { copy = copy.copy(file = "assets/$name") } }
        }
        media += copy
        _clips.value[id]?.let { _clips.value = _clips.value + (newId to it) }
        base[id]?.let { base[newId] = it }
        _animation.value.tracks[id]?.let { tr -> _animation.value = _animation.value.copy(tracks = _animation.value.tracks + (newId to AnimationTrack(newId, tr.keys))) }
        fxLayers[id]?.let { effects ->
            fxLayers = fxLayers + (newId to effects)
            copyEffectTracks(id, newId)
        }
        // спринт 38: маска не копируется (у слоя-маски одна цель); прикрепление к пути копируется вместе с положением на пути
        layerProps[id]?.let { setProps(layerProps + (newId to it.copy(mask = -1, maskInvert = false, maskOff = false))) }
        if ((layerProps[id]?.path ?: -1) >= 0) {
            val pFrom = com.vibetoon.domain.anim.PathLayerMath.progressId(id)
            val pTo = com.vibetoon.domain.anim.PathLayerMath.progressId(newId)
            base[pFrom]?.let { base[pTo] = it }
            _animation.value.tracks[pFrom]?.let { tr -> _animation.value = _animation.value.copy(tracks = _animation.value.tracks + (pTo to AnimationTrack(pTo, tr.keys))) }
            _links.value[id]?.let { _links.value = _links.value + (newId to it) }
        }
        rebuildLayers()
        pushScene()
        viewModelScope.launch {
            withContext(decodeDispatcher) { runCatching { uploadTextureFor(pid, copy) } }
            RenderHost.view?.requestRender()
        }
        if (above != null) dropLayer(newId, above)
        selectLayer(newId)
        markDirty()
        _state.value = UiState.Message("Слой продублирован")
    }

    // ---- глаз, замок, имя, копирование, очистка (контекстное меню слоя) ----

    private fun hiddenIds(): Set<Int> = layerProps.filterValues { !it.visible }.keys + symbolHidden(_editingSymbol.value)
    fun isLockedId(id: Int): Boolean = layerProps[id]?.locked == true
    private var lockNoticeAt = 0L
    /** Показать временную подсказку на плашке (гаснет сама). */
    fun showMessage(text: String) { _state.value = UiState.Message(text) }

    private var baseEditHinted = false

    private fun lockedNotice() {
        val now = System.currentTimeMillis()
        if (now - lockNoticeAt > 2000) { lockNoticeAt = now; _state.value = UiState.Message("Слой заблокирован: снимите замок в списке слоёв") }
    }

    fun toggleVisible(id: Int) = updateLayerProps(id) { it.copy(visible = !it.visible) }
    fun toggleLocked(id: Int) = updateLayerProps(id) { it.copy(locked = !it.locked) }
    fun renameLayer(id: Int, name: String) = updateLayerProps(id) { it.copy(name = name) }
    fun layerNameOrNull(id: Int): String? = layers.firstOrNull { it.id == id }?.name

    private var layerClipboard: Int? = null

    /** «Копировать»: запоминает слой; «Вставить выше» делает его копию над выбранным слоем. */
    fun copyLayer(id: Int) {
        if (layers.none { it.id == id && it.kind == LayerKind.LAYER }) { _state.value = UiState.Message("Копируются только обычные слои, не группы"); return }
        layerClipboard = id
        _state.value = UiState.Message("Слой «${layerNameOrNull(id) ?: id}» скопирован")
    }

    fun pasteAbove(target: Int) {
        val src = layerClipboard ?: run { _state.value = UiState.Message("Сначала скопируйте слой"); return }
        if (layers.none { it.id == src }) { _state.value = UiState.Message("Скопированный слой уже удалён"); return }
        if (media.any { it.id == src && it.isVisual }) duplicateLayer(src, target) else copyPsdRaster(src, target)
    }

    /** Копия слоя PSD: пиксели текстуры сохраняются в PNG и становятся слоем-картинкой над [above]. */
    private fun copyPsdRaster(src: Int, above: Int?) {
        val pid = projectId ?: return
        val info = layers.firstOrNull { it.id == src && it.kind == LayerKind.LAYER } ?: return
        val view = RenderHost.view ?: return
        viewModelScope.launch {
            val bytes = kotlinx.coroutines.withTimeoutOrNull(4000) {
                kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.layerPixels(src) { if (c.isActive) c.resumeWith(Result.success(it)) } }
            }
            if (bytes == null || bytes.size <= 8) { _state.value = UiState.Message("Слой ещё не загружен, повторите"); return@launch }
            val w = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8) or ((bytes[2].toInt() and 0xFF) shl 16)
            val h = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8) or ((bytes[6].toInt() and 0xFF) shl 16)
            val newId = nextMediaId()
            val file = "m$newId.png"
            withContext(Dispatchers.IO) { writeLayerPng(bytes, projects.mediaFile(pid, file)) }
            val item = MediaItem(newId, MediaKind.IMAGE, dn("${info.name} копия"), "assets/$file", w, h, info.x.toFloat(), info.y.toFloat(), info.width.toFloat(), info.height.toFloat())
            checkpoint("add", force = true)
            media += item
            layerProps[src]?.let { setProps(layerProps + (newId to it.copy(name = null))) }
            rebuildLayers()
            pushScene()
            withContext(decodeDispatcher) { runCatching { uploadTextureFor(pid, item) } }
            RenderHost.view?.requestRender()
            if (above != null) dropLayer(newId, above)
            selectLayer(newId)
            markDirty()
            _state.value = UiState.Message("Слой вставлен")
        }
    }

    /** «Очистить»: все пиксели слоя становятся прозрачными; слой, его ключи и свойства остаются; отменяется Undo. */
    fun clearLayer(id: Int) {
        if (_playing.value) return
        if (isLockedId(id)) { lockedNotice(); return }
        val view = RenderHost.view ?: return
        if (layers.none { it.id == id && it.kind == LayerKind.LAYER } || media.any { it.id == id && it.kind != MediaKind.IMAGE }) {
            _state.value = UiState.Message("Очистить можно слой-картинку или слой PSD"); return
        }
        viewModelScope.launch {
            val bytes = kotlinx.coroutines.withTimeoutOrNull(4000) {
                kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.layerPixels(id) { if (c.isActive) c.resumeWith(Result.success(it)) } }
            }
            if (bytes == null || bytes.size <= 8) { _state.value = UiState.Message("Слой ещё не загружен, повторите"); return@launch }
            fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
            val w = i32(0); val h = i32(4)
            if (w <= 0 || h <= 0 || bytes.size < 8 + w * h * 4) return@launch
            val before = bytes.copyOfRange(8, 8 + w * h * 4)
            val after = ByteArray(w * h * 4)
            view.paintRestore(id, 0, 0, w, h, after)
            val op = PaintOp(id, 0, 0, w, h, before, after)
            commitPaintOp(op)
            withContext(Dispatchers.Default) { op.compress() }
            enforcePaintBudget()
            _state.value = UiState.Message("Слой очищен")
        }
    }

    // ---- символы (вложенные сцены), маркеры кадров и Time Remapping ----

    private val _editingSymbol = MutableStateFlow<Int?>(null)
    /** Id группы-определения символа, открытого на редактирование; null - главная сцена. */
    val editingSymbol: StateFlow<Int?> = _editingSymbol.asStateFlow()
    private var lastTapId = -1
    private var lastTapAt = 0L

    private val _symMarkers = MutableStateFlow<List<FrameMarker>>(emptyList())
    /** Маркеры кадров открытого символа (пусто на главной сцене). */
    val symMarkers: StateFlow<List<FrameMarker>> = _symMarkers.asStateFlow()
    private val _markerSel = MutableStateFlow<Set<Int>>(emptySet())
    /** Выделенные маркеры открытого символа (кадры). Операции как у ключей: сдвиг, копия, вставка, дубликат, зеркало. */
    val markerSelection: StateFlow<Set<Int>> = _markerSel.asStateFlow()
    private val _timeKeys = MutableStateFlow<Map<Int, List<TimeKey>>>(emptyMap())
    /** Кадры ключей времени экземпляров символов: слой -> кадры (фиолетовые засечки на таймлайне). */
    val timeKeys: StateFlow<Map<Int, List<TimeKey>>> = _timeKeys.asStateFlow()
    private val _instMarkers = MutableStateFlow<Map<Int, List<FrameMarker>>>(emptyMap())
    /** Маркеры символа каждого экземпляра на сцене: подписи отрезков одинаковой формы на таймлайне. */
    val instanceMarkers: StateFlow<Map<Int, List<FrameMarker>>> = _instMarkers.asStateFlow()
    private val _markerThumbs = MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap())
    /** Миниатюры кадров с маркерами для окна «Выбрать маркер»: ключ "определение:кадр". */
    val markerThumbs: StateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>> = _markerThumbs.asStateFlow()

    fun symbolName(id: Int): String = media.firstOrNull { it.id == id }?.name ?: ""
    fun isAudioTrack(id: Int): Boolean = media.any { it.id == id && it.kind == MediaKind.AUDIO }

    /** Пакет 11: у слоя есть звук (аудио или видео) - вкладка «Громкость» и «Без звука». */
    fun hasSound(id: Int): Boolean = media.any { it.id == id && it.hasSoundTrack }
    fun isMuted(id: Int): Boolean = media.any { it.id == id && it.muted }
    /** У видео не нашлось звуковой дорожки (после попытки декодирования). */
    fun videoWithoutSound(id: Int): Boolean {
        val pid = projectId ?: return false
        val m = media.firstOrNull { it.id == id && it.kind == MediaKind.VIDEO } ?: return false
        return audioEngine.hasNoAudio(projects.fileOf(pid, m.file))
    }

    /** «Без звука» для аудио или видео: одной записью Undo, сохраняется в проекте. */
    fun setMuted(id: Int, on: Boolean) {
        val i = media.indexOfFirst { it.id == id && it.hasSoundTrack }
        if (i < 0 || media[i].muted == on) return
        checkpoint("mute$id", force = true)
        media[i] = media[i].copy(muted = on)
        pushAudioMix()
        if (_playing.value) { pause() }
        markDirty()
        _state.value = UiState.Message(if (on) "Звук слоя выключен" else "Звук слоя включён")
    }

    /** Громкость аудио-слоя на текущем кадре (0..1). */
    fun volumeAt(id: Int): Float = com.vibetoon.domain.project.VolumeMath.at(media.firstOrNull { it.id == id }?.volKeys.orEmpty(), _frame.value.toDouble())
    fun volumeKeyCount(id: Int): Int = media.firstOrNull { it.id == id }?.volKeys?.size ?: 0
    fun hasVolumeKeyHere(id: Int): Boolean = media.firstOrNull { it.id == id }?.volKeys?.any { it.frame == _frame.value } == true

    /** Ставит ключ громкости на текущий кадр (0..1); между ключами громкость меняется линейно. */
    fun setVolumeKey(id: Int, value: Float) {
        val i = media.indexOfFirst { it.id == id && it.hasSoundTrack }
        if (i < 0 || !value.isFinite()) return
        checkpoint("vol$id")
        media[i] = media[i].copy(volKeys = com.vibetoon.domain.project.VolumeMath.withKey(media[i].volKeys, com.vibetoon.domain.project.VolKey(_frame.value, value.coerceIn(0f, 1f))))
        pushAudioMix()
        markDirty()
    }

    fun removeVolumeKey(id: Int) {
        val i = media.indexOfFirst { it.id == id && it.hasSoundTrack }
        if (i < 0) return
        checkpoint("vol$id", force = true)
        media[i] = media[i].copy(volKeys = com.vibetoon.domain.project.VolumeMath.withoutKey(media[i].volKeys, _frame.value))
        pushAudioMix()
        markDirty()
    }

    fun isSymbolInstance(id: Int): Boolean = media.any { it.id == id && it.kind == MediaKind.SYMBOL }
    /** Маркеры символа, на который ссылается экземпляр [instId]. */
    fun markersOfInstance(instId: Int): List<FrameMarker> = media.firstOrNull { it.id == media.firstOrNull { m -> m.id == instId }?.symRef }?.markers.orEmpty()
    fun symbolRefOf(instId: Int): Int = media.firstOrNull { it.id == instId }?.symRef ?: 0
    fun timeKeysOf(instId: Int): List<TimeKey> = media.firstOrNull { it.id == instId }?.timeKeys.orEmpty()

    // Кэш участников символов: раньше дерево «слой -> родитель» строилось заново несколько раз на КАЖДЫЙ кадр
    // (hiddenIds, updateSymbols, symbolCycles), что на 50+ слоях заметно тормозило Play и скраббинг.
    private var memberCacheLayers: List<LayerInfo>? = null
    private val memberCache = HashMap<Int, Set<Int>>()
    private var hiddenCacheKey: Any? = null
    private var hiddenCache: Set<Int> = emptySet()

    private fun symbolMembers(gid: Int): Set<Int> {
        if (memberCacheLayers !== layers) { memberCache.clear(); memberCacheLayers = layers; hiddenCacheKey = null }
        return memberCache.getOrPut(gid) { SymbolMath.descendants(gid, layers.filter { it.parentId >= 0 }.associate { it.id to it.parentId }) }
    }

    private fun refreshSymbolState() {
        val ctx = _editingSymbol.value
        _symMarkers.value = if (ctx == null) emptyList() else media.firstOrNull { it.id == ctx }?.markers.orEmpty()
        _timeKeys.value = media.filter { it.kind == MediaKind.SYMBOL && it.timeKeys.isNotEmpty() }.associate { it.id to it.timeKeys.sortedBy { k -> k.frame } }
        _instMarkers.value = media.filter { it.kind == MediaKind.SYMBOL }.associate { inst -> inst.id to media.firstOrNull { it.id == inst.symRef }?.markers.orEmpty() }
        _markerSel.value = _markerSel.value.filterTo(HashSet()) { f -> _symMarkers.value.any { it.frame == f } }
    }

    /** Слои, скрытые на сцене: в главной сцене - содержимое символов (видны экземпляры), при редактировании символа [ctx] - всё кроме него. */
    private fun symbolHidden(ctx: Int?): Set<Int> {
        val defs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.map { it.id }
        if (defs.isEmpty() && ctx == null) return emptySet()
        val cams = media.filter { it.kind == MediaKind.CAMERA || it.isRig }.mapTo(HashSet()) { it.id }
        val key = listOf(layers, ctx, defs, cams)   // тот же список слоёв (сравнение по ссылке) - тот же результат
        if (key == hiddenCacheKey) return hiddenCache
        return symbolHiddenUncached(ctx, defs, cams).also { hiddenCache = it; hiddenCacheKey = key }
    }

    private fun symbolHiddenUncached(ctx: Int?, defs: List<Int>, cams: Set<Int>): Set<Int> {
        val leaves = layers.filter { it.kind == LayerKind.LAYER && it.id !in cams }.mapTo(HashSet()) { it.id }
        if (ctx != null) return leaves - symbolMembers(ctx)
        val inside = HashSet<Int>()
        for (d in defs) inside += symbolMembers(d)
        return leaves.filterTo(HashSet()) { it in inside }
    }

    /** Глубина вложенности символа: 1 - без вложенных, +1 за каждый уровень экземпляров внутри. */
    private fun symbolDepth(gid: Int, guard: Int = 0): Int {
        if (guard > MAX_SYMBOL_DEPTH + 1) return guard
        val mem = symbolMembers(gid)
        val inner = media.filter { it.kind == MediaKind.SYMBOL && it.id in mem }.maxOfOrNull { symbolDepth(it.symRef, guard + 1) } ?: 0
        return 1 + inner
    }

    /** Цикл без ключей времени — только при настоящей анимации участников (дорожки, покадровые слои, видео). */
    private fun symbolCycles(gid: Int, guard: Int = 0): Boolean {
        if (guard > MAX_SYMBOL_DEPTH) return false
        val mem = symbolMembers(gid)
        return _animation.value.tracks.any { (id, t) -> id in mem && SymbolMath.trackMoves(t.keys) } ||
            media.any { m -> m.id in mem && (m.cells.size > 1 || m.kind == MediaKind.VIDEO ||
                (m.kind == MediaKind.SYMBOL && (SymbolMath.timeKeysMove(m.timeKeys) || (m.timeKeys.isEmpty() && symbolCycles(m.symRef, guard + 1))))) }
    }

    /**
     * Внутренний кадр экземпляра на кадре сцены [f]: ключи времени главнее; без ключей - Hold на кадре 0. Цикл внутренней
     * анимации только по явному флагу экземпляра «Цикл» (раньше любой анимированный участник - кость, ключ слоя - запускал
     * самопроизвольное перелистывание форм).
     */
    private fun symbolKey(inst: MediaItem, def: MediaItem, f: Int): Int {
        val mode = symbolPlaybackOf(inst)
        // Спринт 37: Play Once идёт от начала плашки экземпляра и замирает на последнем кадре символа
        return SymbolForms.frameAt(inst.timeKeys, f, def.symDur, mode != SymbolPlayback.HOLD && symbolCycles(def.id), mode, symbolStart(inst.id), def.markers)
    }
    private fun symbolPlaybackOf(inst: MediaItem): Int = SymbolPlayback.mode(inst.symLoop, inst.symOnce)
    /** Начало плашки экземпляра на таймлайне (кадр, с которого Play Once проигрывает символ). */
    private fun symbolStart(instId: Int): Int = _clips.value[instId]?.start ?: 0

    /** Экземпляру нужны все внутренние кадры (цикл или плавный переход между ключами), а не только кадры ключей. */
    private fun needsAllFrames(inst: MediaItem, def: MediaItem): Boolean =
        (inst.timeKeys.isEmpty() && SymbolPlayback.needsAllFrames(symbolPlaybackOf(inst), false, symbolCycles(def.id))) ||
            inst.timeKeys.sortedBy { it.frame }.zipWithNext().any { (a, b) ->
                // переход между разными формами - Hold (SymbolForms.frameAt): промежуточные кадры не нужны
                a.ease != TimeEase.HOLD && a.value != b.value &&
                    (def.markers.none { !it.isAlias } || SymbolForms.formStart(def.markers, a.value) == SymbolForms.formStart(def.markers, b.value))
            }

    private fun computeSymbolDuration(gid: Int): Int {
        val mem = symbolMembers(gid)
        val frames = ArrayList<Int>()
        for ((id, t) in _animation.value.tracks) if (id in mem) frames += t.keys.maxOfOrNull { it.frame } ?: 0
        for (m in media) if (m.id in mem) {
            frames += m.cells.maxOfOrNull { it.start } ?: 0
            frames += m.timeKeys.maxOfOrNull { it.frame } ?: 0
        }
        val def = media.firstOrNull { it.id == gid }
        // Формы символа - это и клипы «Слоёв как кадров», и маркеры-ссылки: длительность не должна их обрезать
        // (иначе ключ на последнюю форму упирался в границу и показывал другую форму).
        frames += SymbolForms.lastFrame(def?.markers.orEmpty(), _clips.value.filterKeys { it in mem }.values, emptyList())
        return SymbolMath.duration(frames, 1)
    }

    private fun symbolDims(): Pair<Int, Int> {
        // Суперсемплинг 2x: края символа сглаживаются GPU-мипмапами при отрисовке в размер экземпляра.
        val k = minOf(2f, 8192f / maxOf(canvasW, canvasH))
        return maxOf(1, (canvasW * k).toInt()) to maxOf(1, (canvasH * k).toInt())
    }

    // ---- формы символов: готовая текстура на каждую форму, экземпляр на кадре просто показывает нужную ----
    //
    // Раньше каждая смена формы снималась заново и асинхронно заливалась в текстуру экземпляра: при Play и скраббинге
    // холст отставал от таймлайна (рамка в панели маркеров уже на новой форме, а рот старый или «случайный» - тот, чей
    // снимок закончился последним). Теперь каждая форма (символ, внутренний кадр) снимается один раз в свою текстуру,
    // а сцена каждого кадра несёт таблицу «экземпляр -> текстура формы» (SceneData.aliases). Таблица и сцена
    // применяются в GL одним кадром, поэтому на любом кадре видна ровно одна форма - та, что задана ключом.

    // пакет 29: бюджет форм - доля (3/8) бюджета текстур рендерера, который считается по памяти устройства
    private val FORM_BUDGET_BYTES get() = com.vibetoon.domain.render.TextureBudget.formBudget(com.vibetoon.render.TextureMemoryHost.budgetBytes)
    private val FORM_MAX_BYTES = 12L * 1024 * 1024      // одна форма после обрезки
    private val FORM_TEX_BASE = 1_500_000_000            // id текстур форм: не пересекаются с id слоёв, не переиспользуются
    private val formCache = SymbolFormCache(FORM_BUDGET_BYTES)
    private val formQueue = ArrayDeque<SymbolFormCache.Key>()
    private var formWorker: kotlinx.coroutines.Job? = null
    private val formShown = HashMap<Int, SymbolFormCache.Key>()   // экземпляр -> последняя показанная форма (запасная картинка)
    private val formWaits = HashMap<SymbolFormCache.Key, Int>()
    private val formFails = HashMap<SymbolFormCache.Key, Int>()
    private var nextFormTexId = FORM_TEX_BASE
    private var formSignature = 0
    private var formBudgetWarned = false

    // ---- пакет 29: память текстур (бюджет по устройству, вытеснение, предупреждение) ----
    private val residency = com.vibetoon.domain.render.TextureResidency()
    /** Слои, которые в этой сессии правились (кисть, Liquify, выделение): не вытесняются - содержимое может быть только в GPU. */
    private val everEdited = HashSet<Int>()
    private var lastEvictable: IntArray? = null
    @Volatile private var exportPinnedForms: Set<SymbolFormCache.Key> = emptySet()

    /** События памяти текстур от рендерера (из GL-потока). */
    fun onTextureEvents(raw: IntArray) {
        viewModelScope.launch(Dispatchers.Main) { handleTextureEvents(raw) }
    }

    private fun handleTextureEvents(raw: IntArray) {
        val all = com.vibetoon.domain.render.TextureEvents.decode(raw)
        if (all.isEmpty()) return
        // формы символов: вытесненная/отклонённая форма перестаёт быть готовой и снимется заново, когда понадобится
        // (отказ формы сообщает verifyFormTexture своим текстом)
        val (forms, layersEv) = all.partition { it.id >= FORM_TEX_BASE }
        var formsChanged = false
        for (e in forms) if (formCache.removeTex(e.id) != null) formsChanged = true
        if (formsChanged) refreshFormAliases()
        val out = residency.onEvents(layersEv, SystemClock.uptimeMillis())
        out.warning?.let { w ->
            if (_export.value !is ExportUi.Running && !appBackground) _state.value = UiState.Message(
                if (w.tooLarge) "Картинка слоя больше предела GPU: слой показан без обновления. Уменьшите её размер"
                else "Не хватает памяти GPU: часть слоёв временно показана без обновления. Скройте лишние слои - показ восстановится сам"
            )
        }
        if (out.evicted.isNotEmpty()) Log.i("VT/Tex", "evicted ${out.evicted.size} textures (${com.vibetoon.domain.render.TextureBudget.mb(com.vibetoon.render.TextureMemoryHost.usedBytes)}/${com.vibetoon.domain.render.TextureBudget.mb(com.vibetoon.render.TextureMemoryHost.budgetBytes)} MB)")
        reloadNonResident()
    }

    /** Вытесненные (и отклонённые, когда пришло время повтора) текстуры слоёв текущей сцены загружаются заново. */
    private fun reloadNonResident() {
        if (residency.nonResident.isEmpty()) return
        val pid = projectId ?: return
        val sc = scene ?: return
        val ids = residency.toReload(sc.textureIds, SystemClock.uptimeMillis())
        if (ids.isEmpty()) return
        val f = _frame.value
        var cells = false; var videos = false; var fx = false
        for (id in ids) {
            val m = media.firstOrNull { it.id == id } ?: continue
            when {
                m.kind == MediaKind.VIDEO -> { videoFrames.forget(id); videos = true }
                m.kind == MediaKind.IMAGE && m.cells.isNotEmpty() -> { cellShown.remove(id); cells = true }
                m.isText && m.fx.animated -> { textFxKeys.remove(id); fx = true }
                else -> viewModelScope.launch {
                    runCatching { withContext(decodeDispatcher) { uploadTextureFor(pid, m) } }
                        .onFailure { Log.w("VT/Tex", "texture ${m.id} not reloaded: ${it.message}") }
                    RenderHost.view?.requestRender()
                }
            }
        }
        if (videos) updateVideoFrames(f)
        if (cells) updateCells(f)
        if (fx) updateTextFx(f)
    }

    /** Сообщает рендереру, какие текстуры можно вытеснять (только восстановимые без потерь, см. TextureEviction). */
    private fun pushEvictable() {
        val view = RenderHost.view ?: return
        synchronized(paintDirty) { everEdited.addAll(paintDirty) }
        if (liqLayer >= 0) everEdited += liqLayer
        val items = ArrayList<Pair<Int, com.vibetoon.domain.render.TextureEviction.Source>>(media.size)
        for (m in media) {
            val src = when {
                m.kind == MediaKind.VIDEO -> com.vibetoon.domain.render.TextureEviction.Source.VIDEO
                m.isText -> com.vibetoon.domain.render.TextureEviction.Source.TEXT
                m.kind == MediaKind.IMAGE && m.vector -> com.vibetoon.domain.render.TextureEviction.Source.OTHER
                m.kind == MediaKind.IMAGE && m.cells.isNotEmpty() -> com.vibetoon.domain.render.TextureEviction.Source.CELLS
                m.kind == MediaKind.IMAGE -> com.vibetoon.domain.render.TextureEviction.Source.IMAGE_FILE
                else -> null
            } ?: continue
            items += m.id to src
        }
        val ids = com.vibetoon.domain.render.TextureEviction.evictable(items, everEdited, formCache.texIds(), formCache.texIdsOf(pinnedForms()))
        if (lastEvictable?.contentEquals(ids) == true) return
        lastEvictable = ids
        view.setEvictable(ids)
    }

    /** Строка в итог экспорта: сколько загрузок текстур не поместилось в память GPU за время экспорта. */
    private fun exportGpuNote(base: String?): String? {
        val n = residency.rejectedSinceMark
        val gpu = if (n > 0) "Не хватило памяти GPU: часть картинок слоёв в файле могла не обновиться ($n)" else null
        return listOfNotNull(base, gpu).joinToString("\n").ifEmpty { null }
    }

    /** Экземпляры, видимые в текущем контексте: на главной сцене - верхнего уровня, в открытом символе - вложенные в него. */
    private fun formInstances(): List<Pair<MediaItem, MediaItem>> {
        val ctx = _editingSymbol.value
        val pool = if (ctx != null) {
            val mem = symbolMembers(ctx)
            media.filter { it.kind == MediaKind.SYMBOL && it.id in mem }
        } else {
            val inside = HashSet<Int>()
            for (d in media) if (d.kind == MediaKind.GROUP && d.symDur > 0) inside += symbolMembers(d.id)
            media.filter { it.kind == MediaKind.SYMBOL && it.id !in inside }
        }
        return pool.mapNotNull { inst -> media.firstOrNull { it.id == inst.symRef && it.symDur > 0 }?.let { inst to it } }
    }

    /** Форму можно хранить обрезанной, только если экземпляр не деформирован (сетка/варп/перспектива берут весь квадрат). */
    private fun formCrop(instId: Int): Boolean = instId !in meshLayers && instId !in warpLayers && instId !in perspLayers && instId !in turbLayers

    private fun formKey(inst: MediaItem, def: MediaItem, f: Int) = SymbolFormCache.Key(def.id, symbolKey(inst, def, f), formCrop(inst.id))

    /** Таблица форм для сцены кадра [f]; недостающие формы ставятся в очередь (текущий кадр - первым). */
    private fun buildAliases(f: Int): TextureAliases {
        val list = formInstances()
        if (list.isEmpty()) return TextureAliases.EMPTY
        val ids = IntArray(list.size); val prim = IntArray(list.size); val fb = IntArray(list.size); val rects = FloatArray(list.size * 8)
        for ((i, p) in list.withIndex()) {
            val (inst, def) = p
            val key = formKey(inst, def, f)
            ids[i] = inst.id
            val fresh = formCache.fresh(key)
            if (fresh == null) requestForm(key, urgent = true)
            // запасная картинка: прошлая форма этого экземпляра, иначе любая готовая форма символа (не пустота)
            val back = formShown[inst.id]?.let { k -> formCache.any(k)?.let { k to it } } ?: formCache.any(key)?.let { key to it } ?: formCache.anyOf(def.id, key.crop)
            val main = fresh ?: back?.second
            prim[i] = main?.texId ?: -1
            fb[i] = back?.second?.texId ?: -1
            main?.rect?.copyInto(rects, i * 8)
            back?.second?.rect?.copyInto(rects, i * 8 + 4)
            if (fresh != null) formShown[inst.id] = key
            if (_playing.value) {   // упреждение: формы ближайших кадров снимаются заранее
                val step = maxOf(1, _animation.value.settings.fps / 8)
                for (d in 1..8) requestForm(formKey(inst, def, f + d * step), urgent = false)
            }
        }
        return TextureAliases(ids, prim, fb, rects)
    }

    /** Формы на экране сейчас (не вытесняются по бюджету). */
    private fun pinnedForms(): Set<SymbolFormCache.Key> {
        val out = HashSet<SymbolFormCache.Key>(formShown.values)
        out += exportPinnedForms   // пакет 29: формы, которые использует идущий экспорт
        val f = _frame.value
        for ((inst, def) in formInstances()) out += formKey(inst, def, f)
        return out
    }

    private fun requestForm(key: SymbolFormCache.Key, urgent: Boolean) {
        if (formCache.fresh(key) != null || (formFails[key] ?: 0) >= 3) return
        if (urgent) { formQueue.remove(key); formQueue.addFirst(key) } else if (key !in formQueue) formQueue.addLast(key)
        kickFormWorker()
    }

    /** Переставить таблицу форм в текущую сцену (форма готова / ключи времени изменились) без пересборки сцены. */
    private fun refreshFormAliases() {
        val sc = scene ?: return
        val a = buildAliases(_frame.value)
        val o = sc.aliases
        if (a.ids contentEquals o.ids && a.primary contentEquals o.primary && a.fallback contentEquals o.fallback && a.rects contentEquals o.rects) return
        sc.aliases = a
        RenderHost.view?.setScene(sc)
    }

    /** Заранее снять формы всех ключей и маркеров видимых экземпляров: Play и скраббинг потом не ждут снимков. */
    private fun prewarmForms() {
        val f = _frame.value
        for ((inst, def) in formInstances()) {
            val crop = formCrop(inst.id)
            requestForm(formKey(inst, def, f), urgent = true)
            for (k in inst.timeKeys) requestForm(formKey(inst, def, k.frame), urgent = false)
            for (m in def.markers) requestForm(SymbolFormCache.Key(def.id, m.target, crop), urgent = false)
        }
    }

    /** Подставляет в экземпляры символов нужные формы на кадре [f] (мгновенно, если форма уже снята). */
    private fun updateSymbols(f: Int) {
        if (projectId == null || appBackground) return
        refreshFormAliases()
        if (!_playing.value) prewarmForms()
    }

    /** Содержимое символов изменилось: формы снимаются заново, старые остаются запасной картинкой до готовности новых. */
    private fun invalidateForms() {
        formCache.invalidate()
        formQueue.clear(); formWaits.clear(); formFails.clear()
        formSignature = symbolContentSignature()
        regenFormsSoon()
    }

    /** То же после Undo/Redo, но только если символы действительно изменились (иначе формы не пересъёмка). */
    private fun invalidateFormsIfChanged() {
        if (symbolContentSignature() != formSignature) invalidateForms()
    }

    /** Текстуры потеряны (новый GL-контекст, другой проект): кэш форм пуст, запасных картинок нет. */
    private fun resetForms(dropTextures: Boolean) {
        formWorker?.cancel(); formWorker = null
        val ids = formCache.clear()
        if (dropTextures && ids.isNotEmpty()) RenderHost.view?.dropTextures(ids.toIntArray())
        formQueue.clear(); formShown.clear(); formWaits.clear(); formFails.clear()
        formSignature = 0; formBudgetWarned = false
    }

    private fun regenFormsSoon() {
        if (projectId == null) return
        refreshFormAliases()
        if (!_playing.value) prewarmForms()
    }

    /** Отпечаток содержимого всех символов (участники, клипы, треки, видимость): решает, нужна ли пересъёмка форм. */
    private fun symbolContentSignature(): Int {
        val ids = HashSet<Int>()
        for (d in media) if (d.kind == MediaKind.GROUP && d.symDur > 0) { ids += d.id; ids += symbolMembers(d.id) }
        if (ids.isEmpty()) return 0
        var h = canvasW * 31 + canvasH
        for (m in media) if (m.id in ids) h += m.hashCode() * 7 + m.id
        for ((id, c) in _clips.value) if (id in ids) h += (id * 131) xor c.hashCode()
        for ((id, t) in _animation.value.tracks) if (id in ids) h += (id * 137) xor t.hashCode()
        for ((id, t) in base) if (id in ids) h += (id * 139) xor t.hashCode()
        for ((id, p) in layerProps) if (id in ids) h += (id * 149) xor p.hashCode()
        for ((id, l) in fxLayers) if (id in ids) h += (id * 151) xor l.hashCode()
        // контекст экземпляров (группа, унаследованные эффекты) меняет снимок форм - пакет 9
        val parents = layers.associate { it.id to it.parentId }
        for (d in media) if (d.kind == MediaKind.GROUP && d.symDur > 0) h += (d.id * 157) xor symbolSnapshotContext(d.id, parents).hashCode()
        return h
    }

    private fun dropFormTexturesLater(ids: List<Int>) {
        if (ids.isEmpty()) return
        // после того как новая таблица форм дошла до GL: иначе один кадр мог бы сослаться на удалённую текстуру
        viewModelScope.launch { kotlinx.coroutines.delay(300); RenderHost.view?.dropTextures(ids.toIntArray()) }
    }

    /** Снимает формы из очереди по одной (снимки сериализованы мьютексом символов). */
    private fun kickFormWorker() {
        if (formWorker?.isActive == true || appBackground || projectId == null) return
        formWorker = viewModelScope.launch {
            while (true) {
                if (appBackground || projectId == null) break
                val key = formQueue.removeFirstOrNull() ?: break
                if (formCache.fresh(key) != null) continue
                val def = media.firstOrNull { it.id == key.def && it.symDur > 0 } ?: continue
                val gen = formCache.gen
                val strict = (formWaits[key] ?: 0) < SYMBOL_WAIT_LIMIT
                val bmp = try {
                    symbolBitmap(def.id, key.local, 0, strict)
                } catch (t: SymbolNotReady) {
                    // текстуры участников ещё грузятся: прежняя картинка остаётся, снимок чуть позже
                    formWaits[key] = (formWaits[key] ?: 0) + 1
                    if (key !in formQueue) formQueue.addLast(key)
                    kotlinx.coroutines.delay(300)
                    continue
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w("VT/Symbols", "form ${def.name}:${key.local} not drawn: ${t.message}"); null
                }
                if (bmp == null) {
                    val n = (formFails[key] ?: 0) + 1
                    formFails[key] = n
                    if (n >= 3 && !appBackground) _state.value = UiState.Message("Не удалось нарисовать символ «${def.name}»: прежняя картинка сохранена, повтор при следующем кадре")
                    else if (key !in formQueue) formQueue.addLast(key)
                    continue
                }
                if (gen != formCache.gen) {   // символ правили во время снимка - снимок уже устарел
                    bmp.recycle(); if (key !in formQueue) formQueue.addFirst(key); continue
                }
                formWaits.remove(key); formFails.remove(key)
                val prepared = withContext(Dispatchers.Default) { prepareForm(bmp, key.crop) }
                val id = nextFormTexId++
                val uploaded = runCatching { RenderNative.uploadBitmap(id, prepared.first) }.getOrDefault(false)
                val bytes = prepared.first.width.toLong() * prepared.first.height * 4 * 4 / 3
                prepared.first.recycle()
                if (!uploaded) { formFails[key] = 3; continue }
                formCache.budgetBytes = FORM_BUDGET_BYTES
                val drop = formCache.put(key, SymbolFormCache.Entry(id, prepared.second, bytes, gen), pinnedForms())
                dropFormTexturesLater(drop)
                verifyFormTexture(key, id, 0)
                refreshFormAliases()
                if (!_playing.value) pushEvictable()
                if (!_playing.value) for ((inst, d) in formInstances()) if (d.id == key.def) invalidateThumb(inst.id)
            }
        }
    }

    /** Форма действительно попала в GPU? (бюджет текстур мог её отклонить - тогда не ссылаться на пустоту) */
    private fun verifyFormTexture(key: SymbolFormCache.Key, id: Int, tries: Int) {
        val view = RenderHost.view ?: return
        view.layerSizeAfterFrame(id) { size: IntArray? ->
            viewModelScope.launch {
                if (size != null || formCache.any(key)?.texId != id) return@launch
                if (RenderNative.pendingUploadBytes() > 0 && tries < 40) {   // ещё в очереди загрузки
                    kotlinx.coroutines.delay(50); verifyFormTexture(key, id, tries + 1); return@launch
                }
                formCache.remove(key)
                formFails[key] = 3
                if (!formBudgetWarned) {
                    formBudgetWarned = true
                    _state.value = UiState.Message("Не хватает памяти GPU для всех форм символа: часть форм показывается с задержкой")
                }
                refreshFormAliases()
            }
        }
        view.requestRender()
    }

    /**
     * Готовит снимок формы к хранению: обрезка по непрозрачному содержимому (рот - доли процента холста) и уменьшение,
     * если форма всё равно больше [FORM_MAX_BYTES]. Возвращает картинку и её прямоугольник в долях квадрата экземпляра.
     */
    private fun prepareForm(bmp: android.graphics.Bitmap, crop: Boolean): Pair<android.graphics.Bitmap, FloatArray> {
        val w = bmp.width; val h = bmp.height
        var out = bmp
        var rect = floatArrayOf(0f, 0f, 1f, 1f)
        if (crop) {
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            val b = SymbolForms.opaqueBounds(px, w, h)
            if (b == null) {   // пустая форма (на кадре ничего не видно): прозрачный пиксель
                out = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.recycle()
                return out to floatArrayOf(0f, 0f, 1f, 1f)
            }
            if (b[0] > 0 || b[1] > 0 || b[2] < w || b[3] < h) {
                out = android.graphics.Bitmap.createBitmap(bmp, b[0], b[1], b[2] - b[0], b[3] - b[1])
                rect = floatArrayOf(b[0].toFloat() / w, b[1].toFloat() / h, (b[2] - b[0]).toFloat() / w, (b[3] - b[1]).toFloat() / h)
                if (out !== bmp) bmp.recycle()
            }
        }
        val bytes = out.width.toLong() * out.height * 4
        if (bytes > FORM_MAX_BYTES) {
            val k = kotlin.math.sqrt(FORM_MAX_BYTES.toDouble() / bytes)
            val scaled = android.graphics.Bitmap.createScaledBitmap(out, maxOf(1, (out.width * k).toInt()), maxOf(1, (out.height * k).toInt()), true)
            if (scaled !== out) out.recycle()
            out = scaled
        }
        return out to rect
    }

    /** Какую форму экземпляр показывает на кадре [frame] (для подсветки в панели маркеров - та же функция, что у холста). */
    fun shownMarkerAt(instId: Int, frame: Int): FrameMarker? {
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return null
        val def = media.firstOrNull { it.id == inst.symRef && it.symDur > 0 } ?: return null
        return SymbolForms.shownMarker(def.markers, symbolKey(inst, def, frame))
    }

    /**
     * Другие видимые экземпляры того же символа почти в том же месте: частая причина «двух ртов» (второй экземпляр
     * без ключей держит первую форму). Показывается предупреждением в панели маркеров.
     */
    fun overlappingInstances(instId: Int): Int {
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return 0
        val hidden = hiddenIds()
        return formInstances().count { (o, _) ->
            o.id != inst.id && o.symRef == inst.symRef && o.id !in hidden &&
                kotlin.math.abs(o.x - inst.x) < 4f && kotlin.math.abs(o.y - inst.y) < 4f &&
                kotlin.math.abs(o.w - inst.w) < 4f && kotlin.math.abs(o.h - inst.h) < 4f
        }
    }

    /** Снимок символа ещё рано делать: у видимых участников нет текстур (идёт загрузка). */
    private class SymbolNotReady : Exception("symbol textures are still loading")

    /** Сколько раз подряд снимок формы ждёт загрузки текстур; после [SYMBOL_WAIT_LIMIT] снимаем как есть. */
    private val SYMBOL_WAIT_LIMIT = 20

    private val symbolMutex = kotlinx.coroutines.sync.Mutex()

    /** Картинки вложенных экземпляров символа [defId] на его внутреннем кадре [local] (рекурсивно, глубина до MAX_SYMBOL_DEPTH). */
    private suspend fun nestedSymbolBitmaps(defId: Int, local: Int, depth: Int, strict: Boolean): List<Pair<Int, android.graphics.Bitmap>> {
        if (depth >= MAX_SYMBOL_DEPTH) return emptyList()
        val mem = symbolMembers(defId)
        val out = ArrayList<Pair<Int, android.graphics.Bitmap>>()
        try {
            for (n in media.filter { it.kind == MediaKind.SYMBOL && it.id in mem }) {
                val nd = media.firstOrNull { it.id == n.symRef && it.symDur > 0 } ?: continue
                val bmp = symbolBitmapLocked(nd.id, symbolKey(n, nd, local), depth + 1, strict) ?: continue
                out += n.id to bmp
            }
        } catch (t: Throwable) {
            out.forEach { it.second.recycle() }
            throw t
        }
        return out
    }

    /** Картинка вложенной сцены [defId] на внутреннем кадре [local] (прозрачный фон). Сериализовано: вложенные снимки не гоняются. */
    private suspend fun symbolBitmap(defId: Int, local: Int, depth: Int, strict: Boolean = true): android.graphics.Bitmap? =
        symbolMutex.withLock { symbolBitmapLocked(defId, local, depth, strict) }

    /**
     * Изолированный снимок одного внутреннего кадра символа: рисунки участников (кадры, тексты с эффектами) и картинки
     * вложенных экземпляров загружаются как ТЕНЕВЫЕ текстуры, которые видит только этот снимок, и удаляются сразу после
     * него. Общие текстуры слоёв редактора не меняются - нет гонки с кадрами холста и другими экземплярами
     * (раньше из-за неё пропадал рот или были видны сразу две формы).
     */
    private suspend fun symbolBitmapLocked(defId: Int, local: Int, depth: Int, strict: Boolean): android.graphics.Bitmap? {
        if (!glUsable()) return null   // спринт 41: в фоне при идущем импорте/экспорте снимок делается во внеэкранном контексте
        val nested = nestedSymbolBitmaps(defId, local, depth, strict)
        try {
            val fps = _animation.value.settings.fps
            var (w, h) = symbolDims()
            val flags = RenderNative.CAPTURE_SHADOW or (if (strict) RenderNative.CAPTURE_STRICT else 0)
            suspend fun attempt(cw: Int, ch: Int): ByteArray? {
                for ((id, b) in nested) RenderNative.uploadShadowBitmap(id, b)
                val scene = withContext(Dispatchers.Default) { buildSceneAt(fps, null, defId)(local) }   // теневые рисунки участников
                return captureNow(scene, true, cw, ch, flags)
            }
            var bytes = attempt(w, h)
            if (bytes != null && bytes.isEmpty()) throw SymbolNotReady()
            // OOM/лимит GPU: аккуратно уменьшаемся вдвое, прежняя текстура остаётся до успеха.
            while (bytes == null && (w > 1 || h > 1) && glUsable()) {
                w = maxOf(1, w / 2); h = maxOf(1, h / 2)
                bytes = attempt(w, h)
                if (bytes != null && bytes.isEmpty()) throw SymbolNotReady()
            }
            if (bytes == null) return null
            val b = bytes
            return withContext(Dispatchers.Default) { captureBitmap(b, w, h) }
        } finally {
            nested.forEach { it.second.recycle() }
        }
    }

    /**
     * Формы символов для экспорта (пакет 29). Раньше каждая форма снималась в память (glReadPixels), сжималась в PNG,
     * а на каждом кадре экспорта PNG декодировался и снова грузился в текстуру экземпляра. Теперь:
     *  1) готовые формы редактора (текстуры на GPU) берутся как есть, если их не уменьшали;
     *  2) недостающие формы снимаются прямо в текстуру (FBO -> текстура, без копий в память) - один раз;
     *  3) только если формам не хватает памяти GPU - прежний путь через PNG для оставшихся форм.
     * Экземпляр на кадре экспорта показывает текстуру формы через таблицу форм сцены (как холст при Play).
     * [frame] - только кадр проекта (снимок кадра).
     */
    private suspend fun prerenderSymbols(frame: Int? = null): com.vibetoon.domain.project.ExportSymbolForms.Set {
        residency.mark()   // пакет 29: отказы памяти GPU считаются за этот экспорт
        val inside = HashSet<Int>()
        for (d in media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }) inside += symbolMembers(d.id)
        val top = media.filter { it.kind == MediaKind.SYMBOL && it.id !in inside }
        val defs = top.map { it.symRef }.distinct().mapNotNull { id -> media.firstOrNull { it.id == id && it.symDur > 0 } }
        if (defs.isEmpty()) return com.vibetoon.domain.project.ExportSymbolForms.Set.EMPTY
        check(flushPaintSavesSafely()) { "Не удалось сохранить рисунок" }
        val (dimW, dimH) = symbolDims()
        val minLong = maxOf(canvasW, canvasH)
        var room = FORM_BUDGET_BYTES
        var gpuOff = false
        val forms = HashMap<SymbolFormCache.Key, com.vibetoon.domain.project.ExportSymbolForms.Form>()
        val pinned = HashSet<SymbolFormCache.Key>()
        val owned = ArrayList<Int>()
        val pngs = HashMap<Int, List<ByteArray?>>()
        var editorForms = 0; var gpuForms = 0; var pngForms = 0
        try {
            for (d in defs) {
                val insts = top.filter { it.symRef == d.id }
                val crops = insts.map { formCrop(it.id) }.toSet()
                val n = d.symDur.coerceAtLeast(1)
                val keys: Collection<Int> = if (frame != null) insts.map { symbolKey(it, d, frame) }.toSet()
                    else if (insts.any { needsAllFrames(it, d) }) (0 until n).toList()
                    else (insts.flatMap { i -> i.timeKeys.map { it.value.coerceIn(0, n - 1) } } + 0).toSet()   // статичный символ: только кадры ключей
                val list = MutableList<ByteArray?>(n) { null }
                for (k in keys) {
                    if (k !in list.indices) continue
                    // 1) готовые формы редактора
                    var missing = false
                    for (c in crops) {
                        val key = SymbolFormCache.Key(d.id, k, c)
                        val e = formCache.fresh(key)
                        if (e != null && com.vibetoon.domain.project.ExportSymbolForms.editorFormFullRes(e.rect, dimW, dimH, FORM_MAX_BYTES)) {
                            forms[key] = com.vibetoon.domain.project.ExportSymbolForms.Form(e.texId, e.rect); pinned += key; editorForms++
                        } else missing = true
                    }
                    if (!missing) continue
                    // 2) снимок прямо в текстуру (полный квадрат: годится и для обрезанных, и для деформируемых экземпляров)
                    if (!gpuOff) {
                        val sz = com.vibetoon.domain.project.ExportSymbolForms.gpuSize(dimW, dimH, room, minLong)
                        if (sz == null) gpuOff = true
                        else {
                            val id = nextFormTexId++
                            val r = symbolToTexture(d.id, k, id, sz.first, sz.second)
                            if (r == 1) {
                                owned += id; gpuForms++
                                room -= com.vibetoon.domain.project.ExportSymbolForms.textureBytes(sz.first, sz.second)
                                val form = com.vibetoon.domain.project.ExportSymbolForms.Form(id, com.vibetoon.domain.project.ExportSymbolForms.FULL_RECT)
                                for (c in crops) forms.putIfAbsent(SymbolFormCache.Key(d.id, k, c), form)
                                continue
                            }
                            if (r == -2) gpuOff = true   // памяти GPU не хватает - остальные формы прежним путём
                        }
                    }
                    // 3) запасной путь: PNG в памяти, картинка грузится в текстуру экземпляра на кадрах с этой формой
                    val b = symbolBitmap(d.id, k, 0, strict = false) ?: continue
                    list[k] = withContext(Dispatchers.Default) {
                        val bo = java.io.ByteArrayOutputStream()
                        b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo); b.recycle()
                        bo.toByteArray()
                    }
                    pngForms++
                }
                pngs[d.id] = list
            }
        } catch (t: Throwable) {
            if (owned.isNotEmpty()) RenderHost.view?.dropTextures(owned.toIntArray())
            throw t
        }
        Log.i("VT/Export", "symbol forms: editor $editorForms, gpu $gpuForms, png $pngForms")
        exportOwnedForms.addAll(owned)
        exportPinnedForms = pinned
        pushEvictable()
        return com.vibetoon.domain.project.ExportSymbolForms.Set(forms, pngs, owned, pinned)
    }

    /** Текстуры форм, созданные для текущего экспорта: удаляются после него (restoreAfterExport). */
    private val exportOwnedForms: MutableList<Int> = java.util.Collections.synchronizedList(ArrayList())

    /**
     * Снимок формы символа [defId] (внутренний кадр [local]) прямо в текстуру [texId] размером [w] x [h]: те же теневые
     * текстуры участников и вложенных экземпляров, что у обычного снимка, но картинка остаётся на GPU.
     * 1 - готово, -2 - не хватает памяти GPU, иначе ошибка.
     */
    private suspend fun symbolToTexture(defId: Int, local: Int, texId: Int, w: Int, h: Int): Int = symbolMutex.withLock {
        val view = RenderHost.view
        if (!glUsable() || view == null) return@withLock -1
        val nested = nestedSymbolBitmaps(defId, local, 0, false)
        try {
            for ((id, b) in nested) RenderNative.uploadShadowBitmap(id, b)
            val fps = _animation.value.settings.fps
            val scene = withContext(Dispatchers.Default) { buildSceneAt(fps, null, defId)(local) }   // теневые рисунки участников
            val bg = meta?.background?.toInt() ?: 0
            kotlinx.coroutines.withTimeoutOrNull(20000) {
                kotlinx.coroutines.suspendCancellableCoroutine<Int> { c ->
                    view.captureToTexture(scene, texId, w, h, true, bg, RenderNative.CAPTURE_SHADOW) { if (c.isActive) c.resumeWith(Result.success(it)) }
                }
            } ?: -1
        } finally {
            nested.forEach { it.second.recycle() }
        }
    }

    /**
     * «Создать символ»: выбранные слои (или выбранная группа) становятся определением символа - группой со своей вложенной
     * сценой (слои, ключи, кадры), а на сцене появляется экземпляр. Правка определения обновляет все экземпляры. Символы
     * можно вкладывать друг в друга (до [MAX_SYMBOL_DEPTH] уровней).
     */
    fun createSymbol(fromId: Int? = null, asFrames: Boolean = false) {
        if (_playing.value) return
        if (_editingSymbol.value != null) { _state.value = UiState.Message("Создавайте символ на главной сцене: выйдите из редактирования символа"); return }
        if (fromId != null && fromId !in _selection.value) selectLayer(fromId)
        val sel = _selection.value.filter { it < BONE_ID_BASE }.toSet()
        if (sel.isEmpty()) { _state.value = UiState.Message("Выберите слои для символа"); return }
        val all = HashSet<Int>(sel)
        for (s in sel) all += symbolMembers(s)
        if (media.any { it.id in all && it.kind == MediaKind.GROUP && it.symDur > 0 }) { _state.value = UiState.Message("Определение символа нельзя класть в символ: берите его экземпляр"); return }
        val deepest = media.filter { it.kind == MediaKind.SYMBOL && it.id in all }.maxOfOrNull { symbolDepth(it.symRef) } ?: 0
        if (deepest + 1 > MAX_SYMBOL_DEPTH) { _state.value = UiState.Message("Слишком глубокая вложенность символов (максимум $MAX_SYMBOL_DEPTH)"); return }
        val single = sel.singleOrNull()?.let { s -> media.firstOrNull { it.id == s && it.kind == MediaKind.GROUP } }
        // «Слои как кадры»: выбранные слои по порядку снизу вверх (только обычные слои, не группы)
        val frameLeaves = if (asFrames && single == null) sel.filter { id -> layers.any { it.id == id && it.kind == LayerKind.LAYER } }.sortedBy { id -> layers.indexOfFirst { it.id == id } } else emptyList()
        val plan = if (frameLeaves.size >= 2) SymbolMath.framesPlan(frameLeaves, frameLeaves.map { id -> layers.first { it.id == id }.name }, frameName = { "${dn(SymbolMath.DEFAULT_FRAME)} $it" }) else null
        val gid: Int
        if (single != null) gid = single.id
        else {
            val before = media.size
            groupSelected()
            if (media.size == before) return   // группа не собралась, причина уже в сообщении
            gid = _selected.value ?: return
        }
        checkpoint("symbol", force = true)
        val n = media.count { it.kind == MediaKind.GROUP && it.symDur > 0 } + 1
        val gi = media.indexOfFirst { it.id == gid }
        if (gi < 0) return
        media[gi] = media[gi].copy(name = dn("Символ $n"), symDur = plan?.duration ?: computeSymbolDuration(gid), markers = plan?.markers ?: emptyList())
        plan?.clips?.forEach { (id, s, e) -> storeClip(id, Clip(s, e)) }   // слой виден только на своём кадре
        val iid = nextMediaId()
        // Новый экземпляр - без ключей времени и без цикла: на всех кадрах первая форма (Hold), пока пользователь сам
        // не выберет маркер. Выделение ключей сбрасывается - операции ключей не задевают новый экземпляр.
        media += MediaItem(iid, MediaKind.SYMBOL, dn("Символ $n"), "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid,
            timeKeys = emptyList(), symLoop = false)
        // Пакет 9: экземпляр встаёт в ту же группу, где лежали выбранные слои (рот - в папке головы), и получает общий
        // эффект группы («Тряска»): двигается и трясётся вместе с головой при просмотре, Play и экспорте
        val ctx = com.vibetoon.domain.project.SymbolPlacement.instanceParent(layers.firstOrNull { it.id == gid }?.parentId)
        if (ctx >= 0) regroup = regroup.filter { it.first != iid } + (iid to ctx)
        _keySel.value = emptySet()
        rebuildLayers()
        if (ctx >= 0 && inheritGroupFx(iid, ctx)) refreshFxState()
        pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
        if (plan != null) regenMarkerThumbs(gid, force = false)
        _state.value = UiState.Message(
            if (plan != null) "Символ «Символ $n»: слои стали кадрами (${plan.duration}), маркеры по именам слоёв. Двойной тап открывает его"
            else "Символ «Символ $n» создан: двойной тап по нему открывает редактирование"
        )
    }

    /** Войти в символ: холст и список слоёв показывают только его вложенную сцену. [id] - экземпляр или определение. */
    fun enterSymbol(id: Int) {
        if (_playing.value) return
        val m = media.firstOrNull { it.id == id }
        val gid = when { m == null -> return; m.kind == MediaKind.SYMBOL -> m.symRef; m.kind == MediaKind.GROUP && m.symDur > 0 -> m.id; else -> { _state.value = UiState.Message("Это не символ: сначала выберите «Создать символ»"); return } }
        val def = media.firstOrNull { it.id == gid && it.symDur > 0 } ?: return
        _editingSymbol.value = gid
        // пакет 22: экземпляр для контекста - открытый двойным тапом, иначе первый экземпляр на главной сцене
        val inside = HashSet<Int>().also { st -> media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.forEach { st += symbolMembers(it.id) } }
        symbolEditInstance = if (m.kind == MediaKind.SYMBOL) m.id else media.firstOrNull { it.kind == MediaKind.SYMBOL && it.symRef == gid && it.id !in inside }?.id
        selectLayer(null)
        refreshSymbolState(); rebuildRows(); pushScene()
        _state.value = UiState.Message("Редактирование «${def.name}»: правки видны во всех экземплярах")
    }

    /** Back to Main Scene: длительность символа пересчитывается, экземпляры перерисовываются. */
    fun exitSymbol() {
        val gid = _editingSymbol.value ?: return
        val i = media.indexOfFirst { it.id == gid }
        if (i >= 0) media[i] = media[i].copy(symDur = computeSymbolDuration(gid))
        _editingSymbol.value = null
        symbolEditInstance = null
        selectLayer(null)
        invalidateForms()   // содержимое могли поменять: формы пересъёмка (старые - запасная картинка до готовности)
        refreshSymbolState(); rebuildRows(); pushScene(); updateSymbols(_frame.value); markDirty()
        regenMarkerThumbs(gid, force = true)
    }

    /** Маркер кадра на текущем кадре открытого символа (на том же кадре метка заменяется). */
    fun addFrameMarker(name: String) {
        val gid = _editingSymbol.value ?: return
        val i = media.indexOfFirst { it.id == gid }
        val clean = SymbolForms.uniqueName(media.getOrNull(i)?.markers.orEmpty(), name.trim().take(24), _frame.value)
        if (clean.isBlank() || i < 0) return
        checkpoint("fmarker", force = true)
        media[i] = media[i].copy(markers = SymbolMath.withMarker(media[i].markers, FrameMarker(_frame.value, clean)))
        refreshSymbolState(); markDirty()
        regenMarkerThumbs(gid, force = false)
        _state.value = UiState.Message("Маркер «$clean»: кадр ${_frame.value + 1}")
    }

    fun removeFrameMarker(frame: Int) {
        val gid = _editingSymbol.value ?: return
        val i = media.indexOfFirst { it.id == gid }
        if (i < 0) return
        checkpoint("fmarker", force = true)
        media[i] = media[i].copy(markers = SymbolMath.withoutMarker(media[i].markers, frame))
        refreshSymbolState(); markDirty()
    }

    private fun editInstance(id: Int, message: String?, change: (MediaItem) -> MediaItem) {
        val i = media.indexOfFirst { it.id == id && it.kind == MediaKind.SYMBOL }
        if (i < 0) return
        // Во время Play кадр бежит: тап по маркеру ставил ключ на случайный кадр, и символ «сам» расставлялся по таймлайну.
        if (_playing.value) { _state.value = UiState.Message("Остановите воспроизведение: ключ времени ставится на кадр паузы"); return }
        checkpoint("timekey", force = true)
        media[i] = change(media[i])
        refreshSymbolState(); markDirty()
        updateSymbols(_frame.value)
        if (message != null) _state.value = UiState.Message(message)
    }

    /** «Выбрать маркер»: на текущем кадре сцены ключ времени экземпляра = кадр маркера, интерполяция Hold. */
    fun pickMarker(instId: Int, marker: FrameMarker) =
        editInstance(instId, "«${marker.name}» с кадра ${_frame.value + 1} (Hold)") { it.copy(timeKeys = SymbolMath.withKey(it.timeKeys, TimeKey(_frame.value, marker.target, TimeEase.HOLD))) }
    fun removeTimeKey(instId: Int) = editInstance(instId, "Ключ времени удалён") { it.copy(timeKeys = SymbolMath.withoutKey(it.timeKeys, _frame.value)) }

    /** Цикл внутренней анимации экземпляра без ключей времени (по умолчанию выкл. - Hold на первом кадре). */
    fun setSymbolLoop(instId: Int, on: Boolean) =
        editInstance(instId, if (on) "Символ без ключей крутит свою анимацию по кругу" else "Символ без ключей держит первый кадр (Hold)") { it.copy(symLoop = on) }
    fun symbolLoops(instId: Int): Boolean = media.firstOrNull { it.id == instId }?.symLoop == true

    /** Спринт 37: режим экземпляра без ключей времени - Hold / Цикл / Один раз (Play Once, замирает на последнем кадре). */
    fun setSymbolPlayback(instId: Int, mode: Int) {
        val (loop, once) = SymbolPlayback.flags(mode)
        val msg = when (mode) {
            SymbolPlayback.LOOP -> "Символ без ключей крутит свою анимацию по кругу"
            SymbolPlayback.ONCE -> "Символ без ключей проиграется один раз и замрёт на последнем кадре"
            else -> "Символ без ключей держит первый кадр (Hold)"
        }
        editInstance(instId, msg) { it.copy(symLoop = loop, symOnce = once) }
    }
    fun symbolPlayback(instId: Int): Int = media.firstOrNull { it.id == instId }?.let { symbolPlaybackOf(it) } ?: SymbolPlayback.HOLD

    /** Переход ключа времени на текущем кадре (или выделенных ключей времени): Hold / Linear / Smooth / Ease in / Ease out. */
    fun setTimeKeyEase(instId: Int, ease: Int) {
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return
        val picked = _keySel.value.filter { it.first == instId }.mapTo(HashSet()) { it.second }.ifEmpty { setOf(_frame.value) }
        val frames = picked.filterTo(HashSet()) { f -> inst.timeKeys.any { it.frame == f } }
        if (frames.isEmpty()) { _state.value = UiState.Message("На этом кадре нет ключа времени: сначала выберите маркер"); return }
        editInstance(instId, "Переход: ${timeEaseName(ease)} (ключей: ${frames.size})") { it.copy(timeKeys = TimelineItemOps.withEase(it.timeKeys, frames, ease)) }
    }
    fun timeKeyEaseAt(instId: Int, frame: Int): Int? = media.firstOrNull { it.id == instId }?.timeKeys?.lastOrNull { it.frame <= frame }?.ease
    fun timeEaseName(e: Int): String = when (e) {
        TimeEase.LINEAR -> "Линейно"; TimeEase.SMOOTH -> "Плавно"; TimeEase.EASE_IN -> "Плавное начало"; TimeEase.EASE_OUT -> "Плавный конец"; else -> "Hold (резко)"
    }

    // ---- маркеры кадров открытого символа как ключи: выделение, сдвиг, копия/вставка, дубликат, зеркало, удаление

    private var markerClip: List<FrameMarker> = emptyList()
    private val _hasMarkerClip = MutableStateFlow(false)
    val hasMarkerClip: StateFlow<Boolean> = _hasMarkerClip.asStateFlow()

    fun selectMarkers(frames: Set<Int>) { _markerSel.value = frames.filterTo(HashSet()) { f -> _symMarkers.value.any { it.frame == f } } }
    fun toggleMarkerSelected(frame: Int) { selectMarkers(if (frame in _markerSel.value) _markerSel.value - frame else _markerSel.value + frame) }
    fun clearMarkerSelection() { if (_markerSel.value.isNotEmpty()) _markerSel.value = emptySet() }

    /**
     * Правка маркеров открытого символа. [mapping] - куда переехали кадры маркеров (сдвиг, зеркало): вместе с маркером
     * переезжает его форма (клипы и рисунки участников), ключи времени всех экземпляров этой формы и ссылки на неё
     * (см. [SymbolForms]). Всё одной записью истории - Undo/Redo возвращает маркеры, формы и ключи вместе.
     */
    private fun editMarkers(tag: String, message: String?, change: (List<FrameMarker>) -> List<FrameMarker>?, nextSel: Set<Int>?, mapping: Map<Int, Int> = emptyMap()): Boolean {
        val gid = _editingSymbol.value ?: return false
        if (_playing.value) return false
        val i = media.indexOfFirst { it.id == gid }
        if (i < 0) return false
        val old = media[i].markers
        val changed = change(old) ?: run { _state.value = UiState.Message("Маркеры не изменены: занятый кадр или выход за границы символа"); return false }
        val mv = SymbolForms.moves(old, changed, mapping)
        val next = SymbolForms.normalize(SymbolForms.remapAliases(changed, mv))
        if (next == old) return false
        val mem = symbolMembers(gid)
        val cellMoves = HashMap<Int, List<FrameCell>>()
        if (mv.isNotEmpty()) for (m in media) if (m.id in mem && m.cells.isNotEmpty()) {
            val moved = SymbolForms.moveCells(m.cells, mv)
                ?: run { _state.value = UiState.Message("Маркеры не сдвинуты: на целевом кадре слоя «${m.name}» уже есть другой рисунок"); return false }
            if (moved !== m.cells) cellMoves[m.id] = moved
        }
        checkpoint(tag, force = true)
        media[i] = media[i].copy(markers = next)
        if (mv.isNotEmpty()) {
            for ((id, cells) in cellMoves) {
                val k = media.indexOfFirst { it.id == id }
                if (k >= 0) { media[k] = media[k].copy(cells = cells); cellShown.remove(id) }
            }
            _clips.value = SymbolForms.moveClips(_clips.value, mem, old, next, mv, maxOf(media[i].symDur, duration()))
            for (k in media.indices) {
                val it = media[k]
                if (it.kind == MediaKind.SYMBOL && it.symRef == gid && it.timeKeys.isNotEmpty()) media[k] = it.copy(timeKeys = SymbolForms.remapKeys(it.timeKeys, mv))
            }
            invalidateForms()
            pushScene(); updateCells(_frame.value)
        }
        if (nextSel != null) _markerSel.value = nextSel
        refreshSymbolState(); markDirty()
        regenMarkerThumbs(gid, force = true)
        if (message != null) _state.value = UiState.Message(message)
        return true
    }

    private fun markerLast(): Int = maxOf(duration() - 1, 0)

    fun moveSelectedMarkers(delta: Int) {
        val sel = _markerSel.value
        if (sel.isEmpty() || delta == 0) return
        val d = TimelineItemOps.clampDelta(sel, delta, markerLast())
        editMarkers("fmarker-move", null, { TimelineItemOps.move(it, sel, d, markerLast(), TimelineItemOps.MARKERS) }, sel.mapTo(HashSet()) { it + d }, sel.associateWith { it + d })
    }
    fun deleteSelectedMarkers() {
        val sel = _markerSel.value
        if (sel.isEmpty()) return
        editMarkers("fmarker-del", "Маркеров удалено: ${sel.size}", { TimelineItemOps.delete(it, sel, TimelineItemOps.MARKERS) }, emptySet())
    }
    fun copySelectedMarkers() {
        val gid = _editingSymbol.value ?: return
        val all = media.firstOrNull { it.id == gid }?.markers.orEmpty()
        // копии - ссылки на форму исходного маркера: вставленный маркер показывает ту же форму, а не пустой кадр
        markerClip = SymbolForms.asAliases(TimelineItemOps.copy(all, _markerSel.value, TimelineItemOps.MARKERS), all.filter { it.frame in _markerSel.value })
        _hasMarkerClip.value = markerClip.isNotEmpty()
        _state.value = UiState.Message(if (markerClip.isEmpty()) "Маркеры не выбраны" else "Скопировано маркеров: ${markerClip.size}")
    }
    fun cutSelectedMarkers() { copySelectedMarkers(); if (markerClip.isNotEmpty()) deleteSelectedMarkers() }
    fun pasteMarkers() {
        val clip = markerClip
        if (clip.isEmpty()) return
        val at = _frame.value
        editMarkers("fmarker-paste", "Вставлено маркеров: ${clip.size}", { TimelineItemOps.paste(it, clip, at, markerLast(), TimelineItemOps.MARKERS) },
            TimelineItemOps.pastedFrames(clip, at, TimelineItemOps.MARKERS))
    }
    fun duplicateSelectedMarkers() {
        val gid = _editingSymbol.value ?: return
        val all = media.firstOrNull { it.id == gid }?.markers.orEmpty()
        val clip = SymbolForms.asAliases(TimelineItemOps.copy(all, _markerSel.value, TimelineItemOps.MARKERS), all.filter { it.frame in _markerSel.value })
        val at = (_markerSel.value.filter { f -> all.any { it.frame == f } }.maxOrNull() ?: run { _state.value = UiState.Message("Маркеры не выбраны"); return }) + 1
        val placed = TimelineItemOps.paste(all, clip, at, markerLast(), TimelineItemOps.MARKERS)
            ?: run { _state.value = UiState.Message("Дубликат не помещается в длительность"); return }
        val r = placed to TimelineItemOps.pastedFrames(clip, at, TimelineItemOps.MARKERS)
        editMarkers("fmarker-dup", "Маркеры продублированы: ${r.second.size}", { r.first }, r.second)
    }
    fun mirrorSelectedMarkers() {
        val sel = _markerSel.value
        if (sel.size < 2) return
        val lo = sel.min(); val hi = sel.max()
        editMarkers("fmarker-mirror", "Маркеры отзеркалены: формы поменялись местами вместе с ними", { TimelineItemOps.mirror(it, sel, TimelineItemOps.MARKERS) }, sel.mapTo(HashSet()) { lo + hi - it }, sel.associateWith { lo + hi - it })
    }
    fun renameMarker(frame: Int, name: String) {
        val clean = name.trim().take(24)
        if (clean.isEmpty()) return
        editMarkers("fmarker-rename", null, { list -> val n = SymbolForms.uniqueName(list, clean, frame); list.map { if (it.frame == frame) it.copy(name = n) else it } }, null)
    }
    fun clearTimeKeys(instId: Int) = editInstance(instId, "Ключи времени очищены: символ держит первый кадр") { it.copy(timeKeys = emptyList()) }

    /** Миниатюры маркеров символа экземпляра [instId]: сначала из файлов проекта, недостающие дорисовываются в фоне. */
    fun loadMarkerThumbs(instId: Int) {
        val pid = projectId ?: return
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return
        val def = media.firstOrNull { it.id == inst.symRef && it.symDur > 0 } ?: return
        viewModelScope.launch {
            for (mk in def.markers) {
                val key = "${def.id}:${mk.frame}"
                if (_markerThumbs.value.containsKey(key)) continue
                val f = projects.mediaFile(pid, thumbName(def.id, mk.frame))
                if (mk.thumb.isEmpty() || !f.exists()) continue
                val bmp = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } ?: continue
                if (projectId != pid) { bmp.recycle(); return@launch }
                _markerThumbs.value = _markerThumbs.value + (key to bmp.asImageBitmap())
            }
        }
        regenMarkerThumbs(def.id, force = false)
    }

    // ---- миниатюры маркеров и панель выбора маркера ----

    private val _markerPanel = MutableStateFlow<Int?>(null)
    /** Экземпляр символа, для которого открыта плавающая панель маркеров. */
    val markerPanel: StateFlow<Int?> = _markerPanel.asStateFlow()
    fun openMarkerPanel(instId: Int) {
        if (!isSymbolInstance(instId)) return
        val def = media.firstOrNull { it.id == symbolRefOf(instId) }
        if (def == null || def.markers.isEmpty()) { _state.value = UiState.Message("У символа нет маркеров: откройте его двойным тапом и поставьте маркеры кнопкой «Маркеры»"); return }
        _markerPanel.value = instId
    }
    fun closeMarkerPanel() { _markerPanel.value = null }

    /** Значение времени экземпляра на кадре [frame] (внутренний кадр символа) или null, если ключей нет. */
    fun timeValueAt(instId: Int, frame: Int): Int? = media.firstOrNull { it.id == instId }?.let { SymbolMath.timeAt(it.timeKeys, frame) }

    fun currentMarkerName(instId: Int, frame: Int): String? {
        val v = timeValueAt(instId, frame) ?: return null
        return SymbolForms.labelFor(markersOfInstance(instId), v)?.name ?: "кадр ${v + 1}"
    }

    /** Ручной ключ времени (иконка «ключ»): фиксирует то, что символ показывает сейчас, на текущем кадре. */
    fun addTimeKeyHere(instId: Int) {
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return
        val def = media.firstOrNull { it.id == inst.symRef && it.symDur > 0 } ?: return
        val v = symbolKey(inst, def, _frame.value)
        editInstance(instId, "Ключ времени (Hold) на кадре ${_frame.value + 1}") { it.copy(timeKeys = SymbolMath.withKey(it.timeKeys, TimeKey(_frame.value, v))) }
    }

    // Новый кэш не перезаписывает старые миниатюры в существующих проектах.
    private fun thumbName(defId: Int, frame: Int) = "st2_${defId}_$frame.png"

    private fun cropMarkerThumb(b: android.graphics.Bitmap): android.graphics.Bitmap {
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        val rect = com.vibetoon.domain.project.ThumbnailBounds.padded(pixels, b.width, b.height)
            ?: return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val cropped = android.graphics.Bitmap.createBitmap(b, rect.x, rect.y, rect.width, rect.height)
        val k = 320f / maxOf(cropped.width, cropped.height)
        val result = android.graphics.Bitmap.createScaledBitmap(cropped, maxOf(1, (cropped.width * k).toInt()), maxOf(1, (cropped.height * k).toInt()), true)
        if (cropped !== b && cropped !== result) cropped.recycle()
        return if (result === b) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else result
    }

    private val markerThumbJobs = HashMap<Int, kotlinx.coroutines.Job>()

    /** Миниатюры маркеров (PNG ~96 px в папке проекта; ссылка хранится в маркере). Недостающие или устаревшие (force) рисуются заново. */
    private fun regenMarkerThumbs(defId: Int, force: Boolean) {
        val pid = projectId ?: return
        val previous = markerThumbJobs[defId]
        if (previous?.isActive == true && !force) return
        val job = viewModelScope.launch {
            if (previous?.isActive == true) previous.join()
            if (projectId != pid) return@launch
            val def = media.firstOrNull { it.id == defId && it.symDur > 0 } ?: return@launch
            val got = HashMap<Int, String>()
            try {
                for (mk in def.markers) {
                    if (projectId != pid) return@launch
                    val f = projects.mediaFile(pid, thumbName(defId, mk.frame))
                    if (!force && mk.thumb.isNotEmpty() && f.exists()) continue
                    val b = try { symbolBitmap(defId, mk.target, 0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w("VT/Symbols", "marker capture", e); null } ?: continue
                    val small = withContext(Dispatchers.Default) {
                        try { cropMarkerThumb(b) } finally { if (!b.isRecycled) b.recycle() }
                    }
                    if (projectId != pid) { small.recycle(); return@launch }
                    withContext(Dispatchers.IO) { f.outputStream().use { check(small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                    if (projectId != pid) { small.recycle(); return@launch }
                    _markerThumbs.value = _markerThumbs.value + ("$defId:${mk.frame}" to small.asImageBitmap())
                    got[mk.frame] = "assets/${f.name}"
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Log.w("VT/Symbols", "marker thumbnail generation failed", e) }
            if (projectId == pid && got.isNotEmpty()) {
                val i = media.indexOfFirst { it.id == defId }
                if (i >= 0) { media[i] = media[i].copy(markers = media[i].markers.map { m -> got[m.frame]?.let { m.copy(thumb = it) } ?: m }); refreshSymbolState(); markDirty() }
            }
        }
        markerThumbJobs[defId] = job
        job.invokeOnCompletion { if (markerThumbJobs[defId] === job) markerThumbJobs.remove(defId) }
    }

    // ---- покадровые слои (рисунки-экспозиции) и калька (Onion Skin) ----

    private val _cellMap = MutableStateFlow<Map<Int, List<Int>>>(emptyMap())
    /** Кадры, на которых начинаются рисунки покадровых слоёв: слой -> номера кадров (для засечек на таймлайне). */
    val cellMap: StateFlow<Map<Int, List<Int>>> = _cellMap.asStateFlow()

    private val cellShown = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val cellBusy = HashSet<Int>()
    private val ghostShown = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private class Ghost(val id: Int, val owner: Int, val r: Float, val g: Float, val b: Float, val alpha: Float, val file: String,
                        val tint: Boolean = true, val frame: Int = 0)
    private var ghosts: List<Ghost> = emptyList()

    private val _onion = MutableStateFlow(false)
    val onion: StateFlow<Boolean> = _onion.asStateFlow()
    // Спринт 37: отдельные глубина/непрозрачность/цвет для рисунков до и после, затухание, тонировка, Out of Pegs.
    // Настройки редактора - в настройках приложения «vt_app» (ключ onion_settings): переживают перезапуск и
    // попадают в резервную копию настроек.
    private val _onionSettings = MutableStateFlow(runCatching { com.vibetoon.domain.project.OnionSkin.decode(guidePrefs().getString("onion_settings", null)) }.getOrDefault(com.vibetoon.domain.project.OnionSettings()))
    val onionSettings: StateFlow<com.vibetoon.domain.project.OnionSettings> = _onionSettings.asStateFlow()

    fun toggleOnion() {
        _onion.value = !_onion.value
        _state.value = UiState.Message(if (_onion.value) "Калька включена: долгое нажатие на кнопку - цвета, глубина и прозрачность до/после (нужен покадровый слой)" else "Калька выключена")
        refreshOnion()
    }
    fun setOnionDepth(d: Int) = setOnionSettings(_onionSettings.value.copy(before = d, after = d))
    fun setOnionAlpha(a: Float) { if (a.isFinite()) setOnionSettings(_onionSettings.value.copy(beforeAlpha = a, afterAlpha = a)) }
    fun setOnionSettings(next: com.vibetoon.domain.project.OnionSettings) {
        val n = com.vibetoon.domain.project.OnionSkin.normalized(next)
        if (n == _onionSettings.value) return
        _onionSettings.value = n
        runCatching { guidePrefs().edit().putString("onion_settings", com.vibetoon.domain.project.OnionSkin.encode(n)).apply() }
        refreshOnion()
        pushScene(); RenderHost.view?.requestRender()   // Out of Pegs / тонировка меняют сцену и без новых текстур
    }
    fun resetOnionSettings() = setOnionSettings(com.vibetoon.domain.project.OnionSettings())

    private fun ghostBase(owner: Int) = GHOST_BASE + (owner - MEDIA_ID_BASE).coerceAtLeast(0) * com.vibetoon.domain.project.OnionSkin.SLOTS_PER_LAYER

    /** Корни сцены: слои проекта и (если включена калька) призраки соседних рисунков выбранного покадрового слоя. */
    private fun sceneRoots(): List<com.vibetoon.domain.model.Node> {
        if (ghosts.isEmpty()) return roots
        val extra = ghosts.mapNotNull { g ->
            val m = media.firstOrNull { it.id == g.owner } ?: return@mapNotNull null
            com.vibetoon.domain.model.LeafNode(g.id, "onion", true, 1f, "norm", false, m.x.roundToInt(), m.y.roundToInt(), m.w.roundToInt().coerceAtLeast(1), m.h.roundToInt().coerceAtLeast(1))
        }
        return roots + extra
    }

    /** Пересчитывает призраков кальки для выбранного покадрового слоя и подгружает их текстуры. */
    private fun refreshOnion() {
        val m = frameLayer()
        val list = ArrayList<Ghost>()
        if (_onion.value && m != null && m.cells.size > 1) {
            val base = ghostBase(m.id)
            // Спринт 37: призраки по настройкам кальки (до/после - свои глубина, непрозрачность и цвет)
            for (g in com.vibetoon.domain.project.OnionSkin.plan(m.cells, _frame.value, _onionSettings.value))
                list += Ghost(base + g.slot, m.id, g.r, g.g, g.b, g.alpha, g.cell.file, g.tint, g.cell.start)
        }
        val same = list.size == ghosts.size && list.indices.all { ghosts[it].id == list[it].id && ghosts[it].file == list[it].file && ghosts[it].alpha == list[it].alpha &&
            ghosts[it].r == list[it].r && ghosts[it].g == list[it].g && ghosts[it].b == list[it].b && ghosts[it].tint == list[it].tint && ghosts[it].frame == list[it].frame }
        ghosts = list
        val pid = projectId ?: return
        val need = list.filter { ghostShown[it.id] != it.file }
        if (same && need.isEmpty()) return   // ничего не поменялось: без корутины и пересборки сцены на каждом кадре
        val tickets = need.associate { it.id to RenderNative.textureUploadTicket(it.id) }
        viewModelScope.launch {
            if (need.isNotEmpty()) {
                // Спринт 31: призраки кальки - соседние рисунки, обычно уже в кэше предзагрузки
                withContext(cellCache.dispatcher) {
                    for (g in need) runCatching {
                        val d = cellCache.load(projects.fileOf(pid, g.file))
                        val uploaded = RenderNative.uploadBitmapIfCurrent(g.id, d.bitmap, tickets.getValue(g.id))
                        if (d.owned) d.bitmap.recycle()
                        if (uploaded) ghostShown[g.id] = g.file
                    }
                }
            }
            if (!same || need.isNotEmpty()) { pushScene(); RenderHost.view?.requestRender() }
        }
    }

    private fun frameLayer(): MediaItem? {
        val id = _selected.value ?: return null
        return media.firstOrNull { it.id == id && it.kind == MediaKind.IMAGE }
    }

    private fun frameLayerOrNotice(): MediaItem? {
        val m = frameLayer()
        if (m == null) _state.value = UiState.Message("Выберите слой-картинку (кнопка «Новый слой») - на нём ведётся покадровая анимация")
        else if (isLockedId(m.id)) { lockedNotice(); return null }
        return m
    }

    /** При смене кадра в текстуру покадрового слоя подставляется рисунок этого кадра (старый сначала сохраняется в файл). */
    private fun updateCells(f: Int) {
        val pid = projectId ?: return
        updateSymbols(f)
        for (m in media) {
            if (m.kind != MediaKind.IMAGE) continue
            // пакет 17: кадров не осталось (Undo «Добавить пустой кадр» / «Вставить в рисунок») - вернуть файл слоя
            val file = FrameMath.shownFile(m.cells, f, m.file, cellShown[m.id]) ?: continue
            val plain = m.cells.isEmpty()
            if (plain && cellShown[m.id] == m.file) { cellShown.remove(m.id); continue }
            val act = FrameCell(0, file)
            if (cellShown[m.id] == act.file || m.id in cellBusy) continue
            cellBusy += m.id
            val ticket = RenderNative.textureUploadTicket(m.id)
            viewModelScope.launch {
                try {
                    if (!flushPaintWithRetry()) {
                        paintFlushFailed()
                        // временный отказ (свёрнуто, текстура грузится): рисунок кадра подменится при следующей попытке
                        if (lastPaintFailureTransient) viewModelScope.launch { delay(1200); if (!appBackground) updateCells(_frame.value) }
                        return@launch
                    } // не подменяем рисунок, пока прежний не сохранён
                    // Спринт 31: рисунок из кэша (предзагружен) или декод на пуле потоков: слои не ждут друг друга
                    val d = withContext(cellCache.dispatcher) { cellCache.load(projects.fileOf(pid, act.file)) }
                    val uploaded = RenderNative.uploadBitmapIfCurrent(m.id, d.bitmap, ticket)
                    if (d.owned) d.bitmap.recycle()   // закэшированный Bitmap не освобождается: он нужен следующему показу
                    if (!uploaded) return@launch
                    // обычный слой (без кадров) дальше рисуется в свой файл: запись о показанном кадре не нужна
                    if (plain) cellShown.remove(m.id) else cellShown[m.id] = act.file
                    invalidateThumb(m.id)
                    RenderHost.view?.requestRender()
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w("VT/Frames", "cell ${act.file} not shown: ${t.message}")
                } finally {
                    cellBusy -= m.id
                }
                if (_frame.value != f) updateCells(_frame.value)
            }
        }
        prefetchCells(pid, f)
    }

    /**
     * Спринт 31: заранее декодировать следующие рисунки покадровых слоёв по направлению воспроизведения
     * (вперёд при Play, назад при шаге назад). Глубина зависит от бюджета кэша и числа слоёв с кадрами.
     */
    private fun prefetchCells(pid: String, f: Int) {
        val dir = com.vibetoon.domain.perf.CellCachePolicy.direction(lastCellFrame, f, _playing.value)
        lastCellFrame = f
        if (!cellCache.prefetchEnabled) return
        val layered = media.filter { it.kind == MediaKind.IMAGE && it.cells.size > 1 }
        if (layered.isEmpty()) return
        // размер рисунка для оценки памяти: самый крупный слой с кадрами (нет размера - холст)
        val sample = layered.maxBy { it.srcWidth.toLong() * it.srcHeight }
        val w = if (sample.srcWidth > 0) sample.srcWidth else canvasW
        val h = if (sample.srcHeight > 0) sample.srcHeight else canvasH
        val depth = cellCache.prefetchDepth(w.coerceAtLeast(1), h.coerceAtLeast(1), layered.size)
        if (depth <= 0) return
        val files = ArrayList<java.io.File>()
        for (m in layered) {
            for (name in com.vibetoon.domain.perf.CellCachePolicy.prefetchFiles(m.cells, f, dir, depth, _loop.value)) files += projects.fileOf(pid, name)
        }
        cellCache.prefetch(files)
    }

    /** Спринт 31: система просит освободить память (фон, нехватка) - кэш рисунков уменьшается или очищается. */
    fun onTrimMemory(level: Int) { cellCache.trim(level) }

    /** Спринт 31: возврат на передний план - прежний бюджет кэша рисунков. */
    fun restoreCaches() { cellCache.restoreBudget() }

    private suspend fun restoreAfterExport() {
        releaseExportResources()
        textFxKeys.clear(); cellShown.clear()
        updateTextFx(_frame.value)
        updateCells(_frame.value)
        // пакет 29: экспорт ставил в видео-слои кадры своих моментов - вернуть кадр плейхеда
        for (m in media) if (m.kind == MediaKind.VIDEO) videoFrames.forget(m.id)
        updateVideoFrames(_frame.value)
    }

    /** Пакет 29: закрыть декодеры видео экспорта, освободить неприменённые картинки, удалить формы, снятые для экспорта. */
    private fun releaseExportResources() {
        closeExportSessions()
        val owned = synchronized(exportOwnedForms) { exportOwnedForms.toList().also { exportOwnedForms.clear() } }
        if (owned.isNotEmpty()) RenderHost.view?.dropTextures(owned.toIntArray())
        exportPinnedForms = emptySet()
        pushEvictable()
    }

    /**
     * Пакет 29: загрузки картинок кадров экспорта (рисунки, кадры видео, текст с эффектами, запасные картинки символов).
     * Сборка сцены их готовит, поток отрисовки применяет прямо перед своим кадром (SceneData.beforeDraw).
     */
    private inner class ExportSession(longSide: Int) : java.io.Closeable {
        val textures = com.vibetoon.domain.export.ExportTextures<android.graphics.Bitmap>(
            // билет берётся в момент применения: кадры собираются заранее, и билет сборки устарел бы к отрисовке
            upload = { id, b, _ -> RenderNative.uploadBitmapIfCurrent(id, b, RenderNative.textureUploadTicket(id)) },
            release = { it.recycle() }
        )
        val video = com.vibetoon.data.ExportVideoFrames(longSide, longSide)
        private val batches = java.util.concurrent.ConcurrentLinkedQueue<com.vibetoon.domain.export.ExportTextures.Batch<android.graphics.Bitmap>>()

        fun track(b: com.vibetoon.domain.export.ExportTextures.Batch<android.graphics.Bitmap>) {
            batches.removeAll { it.isApplied }
            batches += b
        }

        override fun close() {
            for (b in batches) textures.discard(b)   // кадры, которые так и не нарисовали (отмена)
            batches.clear()
            video.close()
            if (video.decoded + video.failed > 0) Log.i("VT/Export", "video frames: ${video.decoded} decoded, ${video.failed} failed; uploads ${textures.uploads}, late ${textures.lateLoads}")
        }
    }

    private val exportSessions = ArrayList<ExportSession>()

    /** Закрыть сессии загрузок собранных сцен (декодеры видео, неприменённые картинки); формы экспорта остаются. */
    private fun closeExportSessions() {
        val sessions = synchronized(exportSessions) { exportSessions.toList().also { exportSessions.clear() } }
        sessions.forEach { runCatching { it.close() } }
    }

    /** Пакет 17: Import → Insert Images into Existing Drawing включено и выбран подходящий слой-картинка. */
    private fun insertIntoDrawingTarget(): MediaItem? {
        val on = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("import_into_drawing", false)
        if (!on) return null
        val m = frameLayer() ?: return null
        return m.takeIf { com.vibetoon.domain.project.DrawingInsert.accepts(it.kind, it.vector, isLockedId(it.id)) }
    }

    /**
     * Пакет 17: картинка вписывается (пропорции, по центру) в рисунок выбранного слоя на плейхеде. Результат пишется в
     * новый файл кадра, а рисунок на плейхеде заменяется им: прежний файл не меняется, поэтому Undo возвращает рисунок.
     * Обычный слой-картинка при этом становится покадровым с одним рисунком.
     */
    fun insertImageIntoDrawing(uri: Uri) {
        val pid = projectId ?: return
        val m0 = insertIntoDrawingTarget() ?: return
        if (_playing.value || importJob?.isActive == true || _state.value == UiState.Loading || _state.value is UiState.Rendering) return
        _state.value = UiState.Loading
        importJob = launchImport {
            var out: java.io.File? = null
            var committed = false
            val tmp = java.io.File(getApplication<Application>().cacheDir, "insert_${java.util.UUID.randomUUID()}")
            try {
                if (!flushPaintWithRetry()) { paintFlushFailed(); return@launchImport }
                val m = media.firstOrNull { it.id == m0.id && it.kind == MediaKind.IMAGE } ?: run { _state.value = UiState.Idle; return@launchImport }
                val frame = _frame.value
                val cells0 = m.cells.ifEmpty { listOf(FrameCell(0, m.file)) }
                val act = FrameMath.active(cells0, frame) ?: run { _state.value = UiState.Idle; return@launchImport }
                val name = newCellName(m.copy(cells = cells0))
                val dest = projects.mediaFile(pid, name)
                out = dest
                withContext(decodeDispatcher) {
                    val opts = android.graphics.BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                    val base = android.graphics.BitmapFactory.decodeFile(projects.fileOf(pid, act.file).absolutePath, opts)
                        ?: error("Не удалось прочитать рисунок слоя")
                    try {
                        importer.copy(uri, tmp)
                        val src = importer.decodeImage(tmp)
                        try {
                            val r = com.vibetoon.domain.project.DrawingInsert.fitRect(src.bitmap.width, src.bitmap.height, base.width, base.height)
                            android.graphics.Canvas(base).drawBitmap(src.bitmap, null, android.graphics.RectF(r[0], r[1], r[2], r[3]),
                                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
                        } finally { src.bitmap.recycle() }
                        dest.outputStream().use { check(base.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "PNG не записан" } }
                    } finally { base.recycle() }
                }
                // слой могли изменить, пока шла вставка: тогда ничего не трогаем
                if (projectId != pid || closing || media.firstOrNull { it.id == m.id } != m || _frame.value != frame) {
                    withContext(Dispatchers.IO) { dest.delete() }
                    if (projectId == pid && !closing) _state.value = UiState.Message("Слой изменился во время вставки: картинка не вставлена")
                    return@launchImport
                }
                commitCells(m.id, FrameMath.replaceActive(m.cells, frame, "assets/$name", m.file), frame, "Картинка вставлена в рисунок «${layerName(m.id)}» на кадре ${frame + 1}")
                committed = true
            } catch (t: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { if (!committed) out?.delete() }
                throw t
            } catch (t: Throwable) {
                Log.e("VT/Media", "insert into drawing failed", t)
                if (!committed) withContext(Dispatchers.IO) { out?.delete() }
                if (projectId == pid && !closing) _state.value = UiState.Error(t.message ?: "Не удалось вставить картинку в рисунок")
            } finally {
                tmp.delete()
            }
        }
    }

    private fun commitCells(id: Int, cells: List<FrameCell>, goto: Int, message: String) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0) return
        checkpoint("frames", force = true)
        if (!cellShown.containsKey(id)) cellShown[id] = media[i].file
        media[i] = media[i].copy(cells = cells)
        rebuildLayers()
        markDirty()
        if (goto != _frame.value) setFrame(goto) else { updateCells(goto); refreshOnion() }
        _state.value = UiState.Message(message)
    }

    private fun newCellName(m: MediaItem): String {
        var k = m.cells.size + 1
        while (m.cells.any { it.file.endsWith("m${m.id}_c$k.png") }) k++
        return "m${m.id}_c$k.png"
    }

    /** «Добавить пустой кадр»: новый чистый рисунок на плейхеде (если кадр занят - на ближайшем свободном). */
    fun addEmptyFrame() {
        val pid = projectId ?: return
        val m = frameLayerOrNotice() ?: return
        if (_playing.value) return
        viewModelScope.launch {
            if (!flushPaintWithRetry()) { paintFlushFailed(); return@launch }
            val cells0 = m.cells.ifEmpty { listOf(FrameCell(0, m.file)) }
            val target = FrameMath.freeStart(cells0, _frame.value, duration()) ?: run { _state.value = UiState.Message("Нет свободного кадра до конца проекта"); return@launch }
            val name = newCellName(m.copy(cells = cells0))
            val size = paintSizes[m.id]?.let { it[0] to it[1] } ?: (m.srcWidth.coerceAtLeast(1) to m.srcHeight.coerceAtLeast(1))
            withContext(Dispatchers.IO) {
                val bmp = android.graphics.Bitmap.createBitmap(size.first, size.second, android.graphics.Bitmap.Config.ARGB_8888)
                projects.mediaFile(pid, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            commitCells(m.id, FrameMath.with(cells0, FrameCell(target, "assets/$name")), target, "Пустой кадр добавлен: ${target + 1}")
        }
    }

    /** «Дублировать кадр»: копия рисунка, видимого на плейхеде, встаёт на следующий свободный кадр. */
    fun duplicateFrame() {
        val pid = projectId ?: return
        val m = frameLayerOrNotice() ?: return
        if (_playing.value) return
        viewModelScope.launch {
            if (!flushPaintWithRetry()) { paintFlushFailed(); return@launch }
            val cells0 = m.cells.ifEmpty { listOf(FrameCell(0, m.file)) }
            val cur = FrameMath.active(cells0, _frame.value) ?: return@launch
            val target = FrameMath.freeStart(cells0, _frame.value + 1, duration()) ?: run { _state.value = UiState.Message("Нет свободного кадра до конца проекта"); return@launch }
            val name = newCellName(m.copy(cells = cells0))
            val ok = withContext(Dispatchers.IO) { runCatching { projects.fileOf(pid, cur.file).copyTo(projects.mediaFile(pid, name), overwrite = true) }.isSuccess }
            if (!ok) { _state.value = UiState.Message("Не удалось скопировать рисунок"); return@launch }
            commitCells(m.id, FrameMath.with(cells0, FrameCell(target, "assets/$name")), target, "Кадр продублирован: ${target + 1}")
        }
    }

    /** «Удалить кадр»: рисунок, видимый на плейхеде, убирается; предыдущая экспозиция держится дальше. */
    fun deleteFrame() {
        val m = frameLayerOrNotice() ?: return
        if (_playing.value) return
        if (m.cells.size <= 1) { _state.value = UiState.Message("Последний рисунок удалить нельзя: у слоя должен остаться хотя бы один"); return }
        commitCells(m.id, FrameMath.without(m.cells, _frame.value), _frame.value, "Кадр удалён, держится предыдущий")
    }

    // ---- слияние, маска, растрирование, снимки холста (PNG/JPEG/GIF) ----

    private fun captureDims(): Pair<Int, Int> {
        val k = minOf(1f, 2048f / maxOf(canvasW, canvasH))
        return maxOf(1, (canvasW * k).toInt()) to maxOf(1, (canvasH * k).toInt())
    }

    /** Снимок холста в GL-потоке: RGBA premultiplied, строка 0 - низ. null - не вышло. */
    private suspend fun captureNow(scene: SceneData?, transparent: Boolean, w: Int, h: Int, flags: Int = 0): ByteArray? {
        // В фоне поверхность GL снята: снимок дал бы мусор, а очередь GL-событий задержала бы onResume (ANR).
        // Спринт 41: при работающей службе фоновой работы снимок идёт во внеэкранном контексте (VtGLSurfaceView).
        if (!glUsable()) return null
        val view = RenderHost.view ?: return null
        val bg = meta?.background?.toInt() ?: 0
        return kotlinx.coroutines.withTimeoutOrNull(20000) {
            kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.capture(scene, w, h, transparent, bg, flags) { if (c.isActive) c.resumeWith(Result.success(it)) } }
        }
    }

    private fun flipRows(b: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(b.size); val row = w * 4
        for (y in 0 until h) System.arraycopy(b, (h - 1 - y) * row, out, y * row, row)
        return out
    }

    private fun captureBitmap(b: ByteArray, w: Int, h: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(flipRows(b, w, h)))
        return bmp
    }

    /** Premultiplied RGBA (строка 0 - низ) -> прямой ARGB (строка 0 - верх) для GIF. */
    private fun captureArgb(b: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = ((h - 1 - y) * w + x) * 4
            val a = b[o + 3].toInt() and 0xFF
            var r = b[o].toInt() and 0xFF; var g = b[o + 1].toInt() and 0xFF; var bl = b[o + 2].toInt() and 0xFF
            if (a in 1..254) { r = minOf(255, r * 255 / a); g = minOf(255, g * 255 / a); bl = minOf(255, bl * 255 / a) }
            out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or bl
        }
        return out
    }

    /** Слияние слоёв [ids] в один растровый слой: рисунок на текущем кадре со всеми режимами наложения; исходные слои удаляются. */
    fun mergeLayers(ids: Set<Int>) {
        val pid = projectId ?: return
        if (_playing.value) return
        val leaf = ids.filter { id -> layers.any { it.id == id && it.kind == LayerKind.LAYER } }.toSet()
        if (leaf.size < 2) { _state.value = UiState.Message("Для слияния нужно минимум два обычных слоя"); return }
        if (leaf.any { isLockedId(it) }) { lockedNotice(); return }
        viewModelScope.launch {
            val st = _animation.value.settings
            val (w, h) = captureDims()
            val scene = withContext(Dispatchers.Default) { buildSceneAt(st.fps, leaf)(_frame.value) }
            val bytes = captureNow(scene, true, w, h)
            closeExportSessions()   // пакет 29: декодеры и неприменённые картинки сборки
            if (bytes == null) { _state.value = UiState.Message("Не удалось собрать слой, повторите"); return@launch }
            val newId = nextMediaId()
            val file = "m$newId.png"
            withContext(Dispatchers.IO) {
                val bmp = captureBitmap(bytes, w, h)
                projects.mediaFile(pid, file).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            val item = MediaItem(newId, MediaKind.IMAGE, dn("Слияние (${leaf.size})"), "assets/$file", w, h, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
            checkpoint("merge", force = true)
            // место слитого слоя: там, где стоял самый нижний из сливаемых слоёв-медиа (иначе - над всеми медиа)
            val insertAt = media.indexOfFirst { it.id in leaf }.let { if (it < 0) media.size else it }
            // пакет 25: слитый слой (снимок в координатах холста, поэтому в корне) встаёт на место самого нижнего из
            // сливаемых, а если тот был в группе - над верхней группой, в которой он лежал (слой PSD или медиа - всё равно)
            val lowest = com.vibetoon.domain.model.StackOrder.visual(layers).firstOrNull { it in leaf }
            val parentOf = layers.associate { it.id to it.parentId }
            var anchor = lowest
            while (anchor != null && (parentOf[anchor] ?: -1) >= 0) anchor = parentOf[anchor]
            val rootBefore = com.vibetoon.domain.model.StackOrder.children(layers, -1)
            applySelection(leaf, leaf.last())
            deleteSelected(cp = false)
            media.add(insertAt.coerceIn(0, media.size), item)
            if (anchor != null && anchor in rootBefore) {
                val list = if (anchor == lowest) com.vibetoon.domain.model.StackOrder.substitute(rootBefore, leaf, anchor, newId)
                    else rootBefore.filter { it !in leaf }.flatMap { if (it == anchor) listOf(it, newId) else listOf(it) }
                stackOrder = stackOrder + (-1 to list)
            }
            rebuildLayers()
            withContext(decodeDispatcher) { runCatching { uploadTextureFor(pid, item) } }
            pushScene()
            RenderHost.view?.requestRender()
            selectLayer(newId)
            markDirty()
            _state.value = UiState.Message("Слито слоёв: ${leaf.size}")
        }
    }

    fun mergeSelected() = mergeLayers(_selection.value)

    /**
     * «Объединить в группу»: выделенные слои переезжают в новую группу (она получает трансформацию Pan/Scale/Rotate
     * для всех вложенных). Пакет 25: слои PSD и медиа можно собрать в одну группу - внутри они остаются в прежнем видимом
     * порядке (вперемешку), группа встаёт на место верхнего из них. Группа со слоями PSD живёт в координатах PSD
     * (медиа внутри переводит SceneDomain), только из медиа - в координатах холста.
     */
    fun groupSelected() {
        if (_playing.value) return
        val ids = _selection.value.filter { it < BONE_ID_BASE }.toSet()
        if (ids.isEmpty()) { _state.value = UiState.Message("Отметьте слои для группы"); return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        val psd = ids.filter { it < MEDIA_ID_BASE }
        val med = ids.filter { it >= MEDIA_ID_BASE }
        val byId = layers.associateBy { it.id }
        val topParent = byId[ids.first()]?.parentId ?: -1
        var gid: Int
        if (psd.isNotEmpty()) { gid = MEDIA_ID_BASE - 1; while (media.any { it.id == gid }) gid-- } else gid = nextMediaId()
        // группа не может войти в саму себя
        if (ids.any { it in ancestorsOf(topParent) }) { _state.value = UiState.Message("Нельзя: группа внутри выбранного слоя"); return }
        checkpoint("group", force = true)
        val count = media.count { it.kind == MediaKind.GROUP } + 1
        val before = layers
        media += MediaItem(gid, MediaKind.GROUP, dn("Группа $count"), "")
        regroup = regroup.filter { it.first !in ids && it.first != gid } + ids.map { it to gid } + (if (topParent >= 0) listOf(gid to topParent) else emptyList())
        rebuildLayers()
        // пакет 25: внутри группы - прежний видимый порядок (PSD и медиа вперемешку), группа - на месте верхнего слоя
        stackOrder = com.vibetoon.domain.model.StackOrder.group(before, layers, stackOrder, ids, gid, topParent)
        rebuildLayers()
        pushScene()
        selectLayer(gid)
        markDirty()
        _state.value = UiState.Message(if (psd.isNotEmpty() && med.isNotEmpty()) "Группа создана: слоёв ${ids.size} (PSD и картинки вперемешку)" else "Группа создана: слоёв ${ids.size}")
    }

    fun mergeDown(id: Int) {
        val rows = _rows.value
        val i = rows.indexOfFirst { it.layer.id == id }
        val below = rows.getOrNull(i + 1)?.layer
        if (i < 0 || below == null || below.kind != LayerKind.LAYER) { _state.value = UiState.Message("Под слоем нет обычного слоя, с которым можно слить"); return }
        mergeLayers(setOf(id, below.id))
    }

    fun mergeVisible() {
        mergeLayers(layers.filter { it.kind == LayerKind.LAYER && layerProps[it.id]?.visible != false }.map { it.id }.toSet())
    }

    /** «Маска вкл/выкл»: обтравочная маска (слой рисуется только по непрозрачным пикселям слоя под ним). */
    fun toggleClipMask(id: Int) = updateLayerProps(id) { it.copy(clip = !it.clip) }

    /** «Растрировать»: текстовый слой становится обычной картинкой (эффекты текста застывают на текущем кадре). */
    fun rasterizeLayer(id: Int) {
        val pid = projectId ?: return
        val i = media.indexOfFirst { it.id == id }
        val m = media.getOrNull(i)
        if (m == null || !m.isText) { _state.value = UiState.Message("Слой уже растровый"); return }
        viewModelScope.launch {
            val fps = _animation.value.settings.fps.coerceAtLeast(1)
            val file = "m$id.png"
            val size = withContext(decodeDispatcher) {
                val r = textRenderer.renderFx(m.text, m.textColor.toInt(), m.font, m.textSize, m.gradient, m.gradientAngle, m.fx, _frame.value.toFloat() / fps, fps, if (m.stroke) m.strokeWidth else 0f, m.strokeColor.toInt(), m.textAlign, m.lineSpacing, m.letterSpacing, m.textDir)
                projects.mediaFile(pid, file).outputStream().use { r.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                RenderNative.uploadBitmap(id, r.bitmap)
                val sz = r.bitmap.width to r.bitmap.height
                r.bitmap.recycle()
                sz
            }
            val j = media.indexOfFirst { it.id == id }
            if (j < 0) return@launch
            checkpoint("raster", force = true)
            media[j] = media[j].copy(kind = MediaKind.IMAGE, file = "assets/$file", srcWidth = size.first, srcHeight = size.second, text = "", fx = com.vibetoon.domain.project.TextFx(), stroke = false)
            rebuildLayers()
            pushScene()
            RenderHost.view?.requestRender()
            refreshText()
            markDirty()
            _state.value = UiState.Message("Слой растрирован")
        }
    }

    private fun saveToGallery(file: java.io.File, mime: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val resolver = getApplication<Application>().contentResolver
        val video = mime.startsWith("video/")
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/VibeToon" else "Pictures/VibeToon")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return com.vibetoon.domain.export.GalleryCopy.publish(
            create = { resolver.insert(collection, values) },
            write = { uri ->
                val out = resolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open gallery output")
                out.use { target -> file.inputStream().use { it.copyTo(target) } }
            },
            finish = { uri -> resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0 },
            discard = { uri -> resolver.delete(uri, null, null); Unit }
        )
    }

    /** Экспорт текущего кадра (плейхеда) в PNG (с прозрачным фоном) или JPEG (с фоном холста), полное разрешение холста. */
    fun exportStill(jpeg: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        viewModelScope.launch {
            _export.value = ExportUi.Running(0, 1)
            var file: File? = null
            try {
                val (w, h) = captureDims()
                val symC = prerenderSymbols(_frame.value)
                val still = withContext(Dispatchers.Default) { buildSceneAt(_animation.value.settings.fps, symCache = symC)(_frame.value) }
                val bytes = captureNow(still, !jpeg, w, h) ?: error("Не удалось снять кадр")
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                val output = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-f${_frame.value}-$stamp-${java.util.UUID.randomUUID().toString().take(8)}.${if (jpeg) "jpg" else "png"}")
                file = output
                val gallerySaved = withContext(Dispatchers.IO) {
                    val bmp = captureBitmap(bytes, w, h)
                    try {
                        com.vibetoon.domain.export.ExportFiles.write(output) { out ->
                            check(bmp.compress(if (jpeg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 95, out))
                        }
                    } finally { bmp.recycle() }
                    saveToGallery(output, if (jpeg) "image/jpeg" else "image/png")
                }
                _export.value = ExportUi.Done(output, gallerySaved, exportGpuNote(null))
            } catch (t: kotlinx.coroutines.CancellationException) {
                file?.let { com.vibetoon.domain.export.ExportFiles.discard(it) }
                _export.value = ExportUi.Idle
                throw t
            } catch (t: Exception) {
                file?.let { com.vibetoon.domain.export.ExportFiles.discard(it) }
                Log.e("VT/Export", "still failed", t)
                _export.value = ExportUi.Failed(t.message ?: "Не удалось снять кадр")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { restoreAfterExport() } }
            }
        }
    }

    /** Экспорт анимированного GIF: [shortSide] - короткая сторона, [fps] 12/24/30, [transparent] - без фона холста. */
    fun exportGif(shortSide: Int, fpsRequested: Int, transparent: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        // спринт 32: GIF не воспроизводит чаще 50 fps (задержка в 1/100 с) - число кадров считается от реальной частоты
        val fps = fpsRequested.coerceIn(com.vibetoon.domain.anim.FrameRates.MIN, com.vibetoon.domain.anim.FrameRates.GIF_MAX)
        val (w, h, frames) = exportInfo(shortSide, fps)
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp-${java.util.UUID.randomUUID().toString().take(8)}.gif")
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            try {
                file.parentFile?.mkdirs()
                val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols(), range = exportRange())
                com.vibetoon.domain.export.ExportFiles.write(file) { out ->
                    val enc = com.vibetoon.domain.export.GifEncoder(out, w, h, fps, transparent)
                    for (i in 0 until frames) {
                        if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                        val scene = withContext(Dispatchers.Default) { sceneAt(i) }
                        val bytes = captureExportFrame(scene, transparent, w, h, cancel) ?: error("Не удалось снять кадр $i")
                        withContext(Dispatchers.Default) { enc.addFrame(captureArgb(bytes, w, h)) }
                        _export.value = ExportUi.Running(i + 1, frames)
                    }
                    enc.finish()
                }
                val gallerySaved = withContext(Dispatchers.IO) { saveToGallery(file, "image/gif") }
                _export.value = ExportUi.Done(file, gallerySaved, exportGpuNote(null))
            } catch (t: kotlinx.coroutines.CancellationException) {
                file.delete(); _export.value = ExportUi.Idle
            } catch (t: Throwable) {
                Log.e("VT/Export", "gif failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать GIF")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { restoreAfterExport() } }
            }
        }
    }

    /** PNG-последовательность в ZIP: кадры 00001.png... с прозрачным фоном (альфа сохраняется), [shortSide] - короткая сторона, [fps] - частота кадров. */
    fun exportPngZip(shortSide: Int, fps: Int, transparent: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        val (w, h, frames) = exportInfo(shortSide, fps)
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp-${java.util.UUID.randomUUID().toString().take(8)}-png.zip")
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            try {
                file.parentFile?.mkdirs()
                val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols(), range = exportRange())
                com.vibetoon.domain.export.ExportFiles.write(file) { out ->
                    java.util.zip.ZipOutputStream(out).use { zip ->
                        val base = safeName(_projectName.value)
                        for (i in 0 until frames) {
                            if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                            val scene = withContext(Dispatchers.Default) { sceneAt(i) }
                            val bytes = captureExportFrame(scene, transparent, w, h, cancel) ?: error("Не удалось снять кадр $i")
                            withContext(Dispatchers.Default) {
                                val bmp = android.graphics.Bitmap.createBitmap(captureArgb(bytes, w, h), w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                try {
                                    zip.putNextEntry(java.util.zip.ZipEntry("%s_%05d.png".format(base, i + 1)))
                                    check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip))
                                    zip.closeEntry()
                                } finally { bmp.recycle() }
                            }
                            _export.value = ExportUi.Running(i + 1, frames)
                        }
                    }
                }
                _export.value = ExportUi.Done(file, note = exportGpuNote(null))
            } catch (t: kotlinx.coroutines.CancellationException) {
                file.delete(); _export.value = ExportUi.Idle
            } catch (t: Throwable) {
                Log.e("VT/Export", "png zip failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать ZIP")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { restoreAfterExport() } }
            }
        }
    }

    /**
     * Спринт 32: видео MOV с альфа-каналом (кадры PNG внутри QuickTime, без потерь) и, если есть, звуком PCM 16 бит.
     * Android не умеет ProRes 4444, поэтому файл собирает свой MovWriter; [shortSide] - короткая сторона, [fps] 1–120.
     */
    fun exportMov(shortSide: Int, fps: Int, transparent: Boolean, withAudio: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        val outFps = com.vibetoon.domain.anim.FrameRates.clamp(fps)
        val (w, h) = ExportMath.size(canvasW, canvasH, shortSide)
        val st = _animation.value.settings
        val frames = ExportMath.frameCount(exportRange()?.length ?: st.durationFrames, st.fps, outFps)
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp-${java.util.UUID.randomUUID().toString().take(8)}.mov")
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            var writer: com.vibetoon.domain.export.MovWriter? = null
            try {
                file.parentFile?.mkdirs()
                val range = exportRange()
                val sceneAt = buildSceneAt(outFps, symCache = prerenderSymbols(), range = range)
                val audioStart = range?.start ?: 0
                val audioLen = range?.length ?: st.durationFrames
                val audioStatus = if (withAudio) exportAudioStatus() else null
                val audioSrc = if (audioStatus == null) emptyList() else
                    prepareExportAudio(audioStatus).takeIf { com.vibetoon.domain.export.AudioMix.audibleIn(it, audioStart, audioLen) }.orEmpty()
                val rate = if (audioSrc.isEmpty()) 0 else audioEngine.mixSampleRate()
                val total = if (rate > 0) com.vibetoon.domain.export.AudioMix.sampleCount(frames, outFps, rate) else 0
                val mov = withContext(Dispatchers.IO) { com.vibetoon.domain.export.MovWriter(file, w, h, outFps, rate) }
                writer = mov
                for (i in 0 until frames) {
                    if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                    val scene = withContext(Dispatchers.Default) { sceneAt(i) }
                    val bytes = captureExportFrame(scene, transparent, w, h, cancel) ?: error("Не удалось снять кадр $i")
                    withContext(Dispatchers.Default) {
                        val bmp = android.graphics.Bitmap.createBitmap(captureArgb(bytes, w, h), w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val png = try {
                            java.io.ByteArrayOutputStream(w * h).also { check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
                        } finally { bmp.recycle() }
                        mov.addFrame(png)
                        if (rate > 0) {
                            val (from, n) = com.vibetoon.domain.export.AudioMix.frameSamples(i, outFps, rate)
                            if (n > 0) mov.addAudio(com.vibetoon.domain.export.AudioMix.render(audioSrc, audioStart, st.fps, rate, from, n, total), n)
                        }
                    }
                    _export.value = ExportUi.Running(i + 1, frames)
                }
                withContext(Dispatchers.IO) { mov.finish() }
                writer = null
                val gallerySaved = withContext(Dispatchers.IO) { saveToGallery(file, "video/quicktime") }
                val sound = when {
                    rate > 0 -> "Со звуком: PCM 16 бит, стерео"
                    audioStatus?.ready == true -> "Звук не удалось подготовить (файл не декодируется) - видео сохранено без звука"
                    else -> null
                }
                val alpha = if (transparent) "MOV с альфа-каналом (PNG без потерь): откройте в After Effects, Premiere, Final Cut или DaVinci Resolve" else null
                _export.value = ExportUi.Done(file, gallerySaved, exportGpuNote(listOfNotNull(alpha, sound).joinToString("\n").ifEmpty { null }))
            } catch (t: kotlinx.coroutines.CancellationException) {
                writer?.abort(); file.delete(); _export.value = ExportUi.Idle
            } catch (t: Throwable) {
                Log.e("VT/Export", "mov failed", t)
                writer?.abort(); file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать MOV")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { restoreAfterExport() } }
            }
        }
    }

    /**
     * Многослойный PSD: кадр на плейхеде, каждый слой снят отдельно (с трансформацией, эффектами и прозрачностью текущего кадра) и обрезан по содержимому,
     * иерархия групп и режимы наложения сохранены. Текст, видео, символы и векторные слои попадают как растр.
     */
    fun exportPsd() {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        val st = _animation.value.settings
        val w = canvasW; val h = canvasH; val frame = _frame.value
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp.psd")
        val tmpDir = projects.fileOf(pid, "exports/.psdtmp")
        // спринт 38: служебные слои (Transform, путь, кости) и слои-маски в PSD не пишутся - маска уже применена к своему слою
        val skip = symbolHidden(null) + media.filter { it.isRig }.map { it.id } + com.vibetoon.domain.model.LayerMasks.sources(layerProps)
        val order = ArrayList<Pair<com.vibetoon.domain.model.Node, Int>>()   // 0 - слой, 1 - начало группы (папка), 3 - конец группы
        fun flat(nodes: List<com.vibetoon.domain.model.Node>) {
            for (n in nodes) {
                if (n.id in skip) continue
                when (n) {
                    is com.vibetoon.domain.model.GroupNode -> { order += n to 3; flat(n.children); order += n to 1 }
                    else -> order += n to 0
                }
            }
        }
        flat(roots)
        val total = order.count { it.second == 0 } + 1
        _export.value = ExportUi.Running(0, total)
        viewModelScope.launch {
            try {
                tmpDir.deleteRecursively(); tmpDir.mkdirs()
                val symCache = prerenderSymbols()
                val recs = ArrayList<com.vibetoon.domain.export.PsdLayer>()
                var done = 0
                for ((n, kind) in order) {
                    val props = layerProps[n.id]
                    val name = layerNameOrNull(n.id) ?: n.name
                    val blend = (props?.blend ?: "norm").let { if (it in LayerBlend.keys) it else "norm" }
                    val visible = props?.visible != false
                    if (kind == 3) { recs += com.vibetoon.domain.export.PsdLayer("</Layer group>", 0, 0, 0, 0, kind = com.vibetoon.domain.export.PsdLayer.END); continue }
                    if (kind == 1) { recs += com.vibetoon.domain.export.PsdLayer(name, 0, 0, 0, 0, blend, 255, visible, com.vibetoon.domain.export.PsdLayer.GROUP_OPEN); continue }
                    var rec = com.vibetoon.domain.export.PsdLayer(name, 0, 0, 0, 0, blend, 255, visible)
                    if (visible) {
                        val scene = withContext(Dispatchers.Default) { buildSceneAt(st.fps, setOf(n.id), symCache = symCache)(frame) }
                        val bytes = captureNow(scene, true, w, h)
                        closeExportSessions()   // пакет 29: у каждого слоя PSD своя сборка - декодеры не копятся
                        if (bytes != null) {
                            rec = withContext(Dispatchers.Default) {
                                val argb = captureArgb(bytes, w, h)
                                var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
                                for (y in 0 until h) for (x in 0 until w) if ((argb[y * w + x] ushr 24) != 0) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
                                if (x1 < 0) rec
                                else {
                                    val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
                                    val f = java.io.File(tmpDir, "l${recs.size}.bin")
                                    val plane = bw * bh
                                    val buf = ByteArray(plane * 4)
                                    for (yy in 0 until bh) for (xx in 0 until bw) {
                                        val p = argb[(y0 + yy) * w + (x0 + xx)]; val i = yy * bw + xx
                                        buf[i] = (p ushr 24).toByte(); buf[plane + i] = (p shr 16).toByte(); buf[2 * plane + i] = (p shr 8).toByte(); buf[3 * plane + i] = p.toByte()
                                    }
                                    f.writeBytes(buf)
                                    com.vibetoon.domain.export.PsdLayer(name, x0, y0, bw, bh, blend, 255, visible, com.vibetoon.domain.export.PsdLayer.NORMAL, f)
                                }
                            }
                        }
                    }
                    recs += rec
                    done++
                    _export.value = ExportUi.Running(done, total)
                }
                // итоговое изображение: все слои вместе
                val fullScene = withContext(Dispatchers.Default) { buildSceneAt(st.fps, null, symCache = symCache)(frame) }
                val comp = captureNow(fullScene, true, w, h)
                val compPlanes = comp?.let { b ->
                    withContext(Dispatchers.Default) {
                        val argb = captureArgb(b, w, h); val plane = w * h
                        ByteArray(plane * 4).also { o -> for (i in 0 until plane) { val p = argb[i]; o[i] = (p ushr 24).toByte(); o[plane + i] = (p shr 16).toByte(); o[2 * plane + i] = (p shr 8).toByte(); o[3 * plane + i] = p.toByte() } }
                    }
                }
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { com.vibetoon.domain.export.PsdWriter.write(it, w, h, recs, compPlanes) }
                }
                _export.value = ExportUi.Done(file, note = exportGpuNote(null))
            } catch (t: Throwable) {
                Log.e("VT/Export", "psd failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать PSD")
            } finally {
                tmpDir.deleteRecursively()
            }
            restoreAfterExport()
        }
    }

    /** Только аудио: все дорожки проекта сведены в один WAV (стерео 44.1 кГц) на всю длительность проекта или на отрезок сцены. */
    fun exportAudioOnly() {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        val st = _animation.value.settings
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp.wav")
        _export.value = ExportUi.Running(0, 1)
        viewModelScope.launch {
            try {
                val range = exportRange()
                val src = prepareExportAudio(exportAudioStatus())   // пакет 14: то же правило, что у MP4 и окна экспорта
                val mix = if (src.isEmpty()) null else withContext(Dispatchers.Default) {
                    val rate = audioEngine.mixSampleRate()
                    val len = range?.length ?: st.durationFrames
                    com.vibetoon.domain.export.AudioMix.renderAll(src, range?.start ?: 0, st.fps, rate, com.vibetoon.domain.export.AudioMix.sampleCount(len, st.fps, rate))
                }
                if (mix == null) { _export.value = ExportUi.Failed("В проекте нет аудио или оно ещё декодируется: подождите пару секунд и повторите"); return@launch }
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.outputStream().buffered().use { com.vibetoon.domain.export.WavMath.write(it, mix, audioEngine.mixSampleRate(), 2) }
                }
                _export.value = ExportUi.Done(file)
            } catch (t: Throwable) {
                Log.e("VT/Export", "audio failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать аудио")
            }
        }
    }

    /** Резервная копия текущего проекта (.tsproj) в выбранный пользователем файл. */
    fun exportProjectBackup(uri: Uri, ctx: android.content.Context) {
        val pid = projectId ?: return
        viewModelScope.launch {
            if (!saveNow()) return@launch
            val ok = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { projects.exportBackup(pid, it) } }.isSuccess }
            _state.value = UiState.Message(if (ok) "Резервная копия .tsproj сохранена" else "Не удалось сохранить копию")
        }
    }

    fun canvasSizeNow(): Pair<Int, Int> = canvasW to canvasH
    fun hasPsd(): Boolean = psdRef != null

    /** Resize холста: все слои, ключи и кости масштабируются вместе с холстом (картинки растягиваются). Не отменяется через Undo. */
    fun resizeCanvas(newW: Int, newH: Int) {
        if (_playing.value) return
        val w = ProjectMath.clampSize(newW); val h = ProjectMath.clampSize(newH)
        if (w == canvasW && h == canvasH) return
        applyCanvasMap(w, h, com.vibetoon.domain.project.CanvasMap.resize(canvasW, canvasH, w, h))
        _state.value = UiState.Message("Холст $w×$h: слои масштабированы. Отмена через Undo недоступна")
    }

    /** Crop холста: остаётся рамка (x, y, w, h), слои сдвигаются на её левый верхний угол. Для проектов с PSD недоступен. */
    fun cropCanvas(x: Int, y: Int, w: Int, h: Int) {
        if (_playing.value) return
        if (psdRef != null) { _state.value = UiState.Message("Обрезка недоступна в проекте с PSD: используйте Resize"); return }
        if (!com.vibetoon.domain.project.CanvasMap.validCrop(canvasW, canvasH, x, y, w, h)) { _state.value = UiState.Message("Рамка выходит за холст или меньше ${ProjectMath.MIN_SIZE} px"); return }
        applyCanvasMap(w, h, com.vibetoon.domain.project.CanvasMap.crop(x, y))
        _state.value = UiState.Message("Холст обрезан до $w×$h. Отмена через Undo недоступна")
    }

    private fun applyCanvasMap(newW: Int, newH: Int, m: com.vibetoon.domain.project.CanvasMap) {
        val vectorIds = ArrayList<Int>()
        val texts = ArrayList<Int>()
        for (i in media.indices) {
            val it0 = media[i]
            when {
                it0.kind == MediaKind.SYMBOL || it0.vector -> {
                    // экземпляр символа и векторный слой всегда занимают весь холст; векторные примитивы переносятся вместе с холстом
                    val scale = minOf(1f, (if (it0.vector) 1536f else 2048f) / maxOf(newW, newH))
                    media[i] = it0.copy(
                        x = 0f, y = 0f, w = newW.toFloat(), h = newH.toFloat(),
                        srcWidth = if (it0.vector) (newW * scale).toInt().coerceAtLeast(1) else it0.srcWidth,
                        srcHeight = if (it0.vector) (newH * scale).toInt().coerceAtLeast(1) else it0.srcHeight,
                        vectors = it0.vectors.map { v ->
                            val p = v.pts.mapIndexed { k, f -> if (k % 2 == 0) m.x(f) else m.y(f) }
                            v.copy(pts = p, width = v.width * minOf(m.sx, m.sy))
                        }
                    )
                    if (it0.vector) vectorIds += it0.id
                }
                it0.isVisual && it0.kind != MediaKind.GROUP -> {
                    media[i] = it0.copy(x = m.x(it0.x), y = m.y(it0.y), w = m.w(it0.w), h = m.h(it0.h),
                        textSize = if (it0.isText) m.text(it0.textSize) else it0.textSize)
                    if (it0.isText) texts += it0.id
                }
            }
        }
        // смещения трансформаций слоёв и костей (id до WarpMath.ID_BASE; точки warp/mesh/пины живут в долях слоя и не трогаются)
        val limit = com.vibetoon.domain.anim.WarpMath.ID_BASE
        val nb = HashMap<Int, LayerTransform>()
        for ((id, t) in base) nb[id] = if (id < limit) t.copy(dx = m.dx(t.dx), dy = m.dy(t.dy)) else t
        base.clear(); base.putAll(nb)
        override.clear()
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.mapValues { (id, tr) ->
            if (id >= limit) tr else com.vibetoon.domain.anim.AnimationTrack(tr.layerId, tr.keys.map { k -> k.copy(dx = m.dx(k.dx), dy = m.dy(k.dy)) })
        })
        _bones.value = _bones.value.map { b -> b.copy(hx = m.x(b.hx), hy = m.y(b.hy), tx = m.x(b.tx), ty = m.y(b.ty)) }
        _guides.value = com.vibetoon.domain.paint.DrawingGuides.rescale(_guides.value, canvasW, canvasH, newW, newH)
        canvasW = newW; canvasH = newH
        saveGuides()
        meta = meta?.copy(width = newW, height = newH)
        if (psdRef != null) fit = ProjectMath.fit(psdW, psdH, canvasW, canvasH)
        resetCamera()
        invalidateForms()
        rebuildLayers(); pushScene(); updateSymbols(_frame.value)
        val items = media.filter { it.id in texts }
        if (items.isNotEmpty()) rerenderTexts(items)
        for (id in vectorIds) media.firstOrNull { it.id == id }?.let { renderVector(it, save = true) }
        markDirty()
    }

    /** Тянут края полоски слоя на таймлайне: слой виден на кадрах [start, end). */
    fun setClip(id: Int, start: Int, end: Int) {
        checkpoint("clip$id")
        storeClip(id, Clip.clamped(start, end, duration(), clipOf(id)))
        pushScene()
        markDirty()
    }

    private fun editClip(change: (Clip) -> Clip) {
        val id = _selected.value ?: return
        checkpoint("clipprop$id", force = true)
        storeClip(id, change(clipOf(id)))
        pushScene()
        markDirty()
    }

    fun setClipSpeed(speed: Float) = editClip {
        val len = com.vibetoon.domain.project.retimedLength(it.length, it.speed, speed, duration() - it.start)
        it.copy(speed = speed, end = it.start + len)
    }
    fun toggleClipReverse() = editClip { it.copy(reverse = !it.reverse) }
    fun toggleClipFreeze() = editClip { it.copy(freeze = !it.freeze) }
    fun toggleClipLoop() = editClip { it.copy(loop = !it.loop) }

    /** Единая точка записи свойств слоёв: заодно обновляет производное состояние цветокоррекции. */
    private fun setProps(m: Map<Int, LayerProps>) {
        _layerProps.value = m
        _colors.value = m.filterValues { it.hasColor }.mapValues { (_, p) -> ColorAdjust(p.exposure, p.contrast, p.saturation, p.hue) }
    }

    fun resetColor() {
        val id = _selected.value ?: return
        updateLayerProps(id) { it.copy(exposure = 0f, contrast = 0f, saturation = 0f, hue = 0f) }
    }

    /** Ползунки цветокоррекции, -100..100 (экспозиция ±2 EV, тон ±180°); хранятся в lprops проекта. */
    fun setColor(kind: Int, value: Float) {
        val id = _selected.value ?: return
        updateLayerProps(id) {
            when (kind) {
                0 -> it.copy(exposure = value)
                1 -> it.copy(contrast = value)
                2 -> it.copy(saturation = value)
                else -> it.copy(hue = value)
            }
        }
    }

    // ---------------------------------------------------------------- Warp (сетка 4x4 контрольных точек)

    private val _fx = MutableStateFlow(true)
    /** FX вкл: правки точек пишутся в ключи; выкл (Bind Pose): меняется базовая форма, анимация не показывается. */
    val fx: StateFlow<Boolean> = _fx.asStateFlow()
    private var warpLayers: Set<Int> = emptySet()
    private val _warpTool = MutableStateFlow(false)
    val warpTool: StateFlow<Boolean> = _warpTool.asStateFlow()
    private val _warpOverlay = MutableStateFlow(WarpOverlay())
    val warpOverlay: StateFlow<WarpOverlay> = _warpOverlay.asStateFlow()

    /** Warp удалённых слоёв: сетка, форма привязки и ключи точек. */
    private fun dropWarpData(layerIds: Set<Int>) {
        val gone = layerIds.filter { it in warpLayers }
        if (gone.isEmpty()) return
        val ids = gone.flatMap { l -> (0 until WarpMath.POINTS).map { WarpMath.pointId(l, it) } }.toSet()
        warpLayers = warpLayers - gone.toSet()
        warpGridSizes = warpGridSizes - gone.toSet()
        denseWarps = denseWarps - gone.toSet()
        for (id in ids) base.remove(id)
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
    }

    fun toggleFx() {
        _fx.value = !_fx.value
        pushScene()
        _state.value = UiState.Message(if (_fx.value) "FX вкл: правки точек пишутся в ключевые кадры" else "FX выкл (Bind Pose): правки меняют базовую форму")
    }

    fun setWarpTool(on: Boolean) {
        if (_warpTool.value == on) return
        _warpTool.value = on
        if (on) ensureWarp()
        refreshWarpOverlay()
    }

    /** Временный явный шаг подготовки: только сохраняет рамку, деформация ещё не включена. */
    fun prepareGroupDeformationAnchor() {
        if (_playing.value || _selection.value.size != 1) return
        val id = _selected.value ?: return
        val group = layers.firstOrNull { it.id == id } ?: return
        if (group.kind != LayerKind.GROUP_OPEN && group.kind != LayerKind.GROUP_CLOSED) return
        if (isLockedId(id)) { lockedNotice(); return }
        if (id in groupDeformationAnchors) {
            _state.value = UiState.Message("Область группы уже сохранена")
            return
        }
        val bounds = scene?.groupLocalBounds?.get(id)
        if (bounds == null) {
            _state.value = UiState.Message("Нет видимых непустых слоёв для области группы")
            return
        }
        val anchor = com.vibetoon.domain.project.GroupDeformationAnchor(bounds.x, bounds.y, bounds.w, bounds.h)
        if (!anchor.valid()) return
        checkpoint("group-deformation-anchor", force = true)
        groupDeformationAnchors = groupDeformationAnchors + (id to anchor)
        markDirty()
        _state.value = UiState.Message("Область группы сохранена: теперь нажмите «Включить для группы»")
    }

    fun clearGroupDeformationAnchor() {
        if (_playing.value || _selection.value.size != 1) return
        val id = _selected.value ?: return
        if (id !in groupDeformationAnchors) return
        if (id in warpLayers || id in perspLayers) { _state.value = UiState.Message("Сначала сбросьте деформации группы"); return }
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("group-deformation-anchor-clear", force = true)
        groupDeformationAnchors = groupDeformationAnchors - id
        markDirty()
        _state.value = UiState.Message("Область группы удалена")
    }

    private fun selectedDeformerGroup(): Int? {
        val id = _selected.value ?: return null
        if (_selection.value.size != 1 || id >= BONE_ID_BASE) return null
        val kind = layers.firstOrNull { it.id == id }?.kind
        return id.takeIf { kind == LayerKind.GROUP_OPEN || kind == LayerKind.GROUP_CLOSED }
    }

    private fun deformerTarget(): Int? {
        val id = selectedDeformerGroup()
        if (id != null && id in groupDeformationAnchors &&
            ((_warpTool.value && id in warpLayers) || (_perspTool.value && id in perspLayers))) return id
        return warpTarget()
    }

    private fun deformerQuad(id: Int): FloatArray? {
        val anchor = groupDeformationAnchors[id] ?: return scene?.selectionQuads?.get(id)
        val world = scene?.groupWorlds?.get(id) ?: return null
        val xs = floatArrayOf(anchor.x, anchor.x + anchor.width, anchor.x + anchor.width, anchor.x)
        val ys = floatArrayOf(anchor.y, anchor.y, anchor.y + anchor.height, anchor.y + anchor.height)
        return FloatArray(8) { i -> if (i % 2 == 0) world.applyX(xs[i / 2], ys[i / 2]) else world.applyY(xs[i / 2], ys[i / 2]) }
    }

    fun enableGroupWarp() = enableGroupDeformer(true)
    fun enableGroupPerspective() = enableGroupDeformer(false)
    private fun enableGroupDeformer(warp: Boolean) {
        if (_playing.value) return
        val id = selectedDeformerGroup() ?: return
        if (isLockedId(id)) { lockedNotice(); return }
        if (id !in groupDeformationAnchors) {
            val bounds = scene?.groupLocalBounds?.get(id)
            if (bounds == null) { _state.value = UiState.Message("Нет видимых непустых слоёв для области группы"); return }
            val anchor = com.vibetoon.domain.project.GroupDeformationAnchor(bounds.x, bounds.y, bounds.w, bounds.h)
            if (!anchor.valid()) { _state.value = UiState.Message("Некорректная область группы"); return }
            groupDeformationAnchors = groupDeformationAnchors + (id to anchor)
        }
        if (if (warp) id in warpLayers else id in perspLayers) return
        val nextWarp = if (warp) warpLayers + id else warpLayers
        val nextPersp = if (warp) perspLayers else perspLayers + id
        // спринт 43: пробной сборки сцены и отказа больше нет - групповой деформер складывается с деформерами слоёв
        checkpoint("group-deformation-enable", force = true)
        warpLayers = nextWarp; perspLayers = nextPersp
        pushScene(); markDirty()
    }

    /** Слой, к которому применяется Warp: один выделенный обычный слой (не группа, не кость). */
    private fun warpTarget(): Int? {
        val id = _selected.value ?: return null
        if (id >= BONE_ID_BASE || _selection.value.size != 1) return null
        val l = layers.firstOrNull { it.id == id } ?: return null
        return if (l.kind == com.vibetoon.nativebridge.LayerKind.LAYER) id else null
    }

    /** При включённом инструменте Warp выделенный слой получает сетку (смещения нулевые, картинка не меняется). */
    private fun ensureWarp() {
        val t = warpTarget() ?: return
        if (t in warpLayers) return
        checkpoint("warp-on", force = true)
        warpLayers = warpLayers + t
        pushScene()
        markDirty()
    }

    /** Убирает деформацию выделенного слоя: сетка, форма привязки и ключи точек удаляются. */
    fun resetWarp() {
        val t = deformerTarget() ?: return
        if (t !in warpLayers) return
        checkpoint("warp-reset", force = true)
        warpLayers = warpLayers - t
        warpGridSizes = warpGridSizes - t
        denseWarps = denseWarps - t
        val ids = (0 until WarpMath.POINTS).map { WarpMath.pointId(t, it) }.toSet()
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        pushScene()
        markDirty()
        _state.value = UiState.Message("Warp слоя сброшен")
    }

    /** Углы единичного квадрата слоя на холсте: p00, p10, p01 (афинное отображение) -> (u, v) по точке холста. */
    private fun unitOf(q: FloatArray, c: Pair<Float, Float>): Pair<Float, Float>? {
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val det = ex * fy - ey * fx
        if (kotlin.math.abs(det) < 1e-6f) return null
        val px = c.first - q[0]; val py = c.second - q[1]
        return ((px * fy - py * fx) / det) to ((ex * py - ey * px) / det)
    }

    fun changeWarpGrid(horizontal: Boolean, delta: Int) {
        if (_playing.value || delta !in listOf(-1, 1)) return
        val id = deformerTarget() ?: return
        if (id !in warpLayers || isLockedId(id)) return
        val grid = denseWarps[id]
        val old = warpGridSizes[id] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT
        val currentW = grid?.width ?: old.width; val currentH = grid?.height ?: old.height
        val nextW = currentW + if (horizontal) delta else 0
        val nextH = currentH + if (horizontal) 0 else delta
        if (nextW !in 2..33 || nextH !in 2..33) return
        if (grid != null || nextW > 4 || nextH > 4) {
            if (selectedDeformerGroup() != null || _fx.value) { _state.value = UiState.Message("Плотный Warp: только обычный слой и FX выкл"); return }
            val ids = (0 until WarpMath.POINTS).map { WarpMath.pointId(id, it) }
            if (ids.any { _animation.value.hasTrack(it) || it in override }) { _state.value = UiState.Message("Плотный Warp недоступен при ключах точек"); return }
            if (nextW <= 4 && nextH <= 4) {
                if (grid == null || grid.offsets.any { it != 0f }) { _state.value = UiState.Message("Сетка не изменена: форма или ключи несовместимы"); return }
                val target = com.vibetoon.domain.anim.CubicWarpGrid.Size(nextW, nextH)
                val converted = com.vibetoon.domain.anim.CubicWarpGrid.migrate(id, old, target, base, _animation.value)
                if (converted == null) { _state.value = UiState.Message("Сетка не изменена: форма или ключи несовместимы"); return }
                checkpoint("dense-warp-grid:$id", force = true)
                base.clear(); base.putAll(converted.base); _animation.value = converted.animation
                warpGridSizes = if (target == com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT) warpGridSizes - id else warpGridSizes + (id to target)
                denseWarps = denseWarps - id
            } else {
                val proposed = if (grid == null) com.vibetoon.domain.anim.DenseWarpGrid.zero(nextW, nextH) else grid.resized(nextW, nextH)
                if (proposed == null || !proposed.valid()) { _state.value = UiState.Message("Сетка не изменена: форма или ключи несовместимы"); return }
                val candidate = denseWarps + (id to proposed)
                checkpoint("dense-warp-grid:$id", force = true)
                denseWarps = candidate
            }
            pushScene(); markDirty(); return
        }
        val target = if (horizontal) old.copy(width = old.width + delta) else old.copy(height = old.height + delta)
        if (!target.valid()) return
        val ids = (0 until WarpMath.POINTS).map { WarpMath.pointId(id, it) }
        if (ids.any { it in override }) { _state.value = UiState.Message("Изменение сетки недоступно: есть временные правки точек"); return }
        val converted = com.vibetoon.domain.anim.CubicWarpGrid.migrate(id, old, target, base, _animation.value)
        if (converted == null) { _state.value = UiState.Message("Сетка не изменена: форма или ключи несовместимы"); return }
        checkpoint("warp-grid:$id", force = true)
        base.clear(); base.putAll(converted.base)
        _animation.value = converted.animation
        warpGridSizes = if (target == com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT) warpGridSizes - id else warpGridSizes + (id to target)
        pushScene(); markDirty()
    }

    private fun refreshWarpOverlay() {
        val t = deformerTarget()
        if (!_warpTool.value || _boneMode.value || t == null || t !in warpLayers) {
            if (_warpOverlay.value.points.isNotEmpty()) _warpOverlay.value = WarpOverlay()
            return
        }
        val q = deformerQuad(t) ?: run { _warpOverlay.value = WarpOverlay(); return }
        val grid = denseWarps[t]
        if (grid != null) {
            val cubic = com.vibetoon.domain.anim.CubicWarpGrid.cubic(t, warpGridSizes[t] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT, _frame.value, base, _animation.value, _fx.value)
            val ex = q[2] - q[0]; val ey = q[3] - q[1]; val fx = q[6] - q[0]; val fy = q[7] - q[1]
            val pts = FloatArray(grid.width * grid.height * 2)
            for (k in 0 until grid.width * grid.height) {
                val u = (k % grid.width).toFloat() / (grid.width - 1); val v = (k / grid.width).toFloat() / (grid.height - 1)
                val b = com.vibetoon.domain.anim.WarpGridMath.evaluate(cubic, 4, u, v) ?: return
                val x = u + b.first + grid.offsets[k * 2]; val y = v + b.second + grid.offsets[k * 2 + 1]
                toScreen(q[0] + x * ex + y * fx, q[1] + x * ey + y * fy).let { pts[k * 2] = it.first; pts[k * 2 + 1] = it.second }
            }
            _warpOverlay.value = WarpOverlay(pts.toList(), grid.width, grid.height); return
        }
        val size = warpGridSizes[t] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT
        val off = com.vibetoon.domain.anim.CubicWarpGrid.raw(t, size, _frame.value, base, _animation.value, _fx.value)
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val pts = FloatArray(size.width * size.height * 2)
        for (k in 0 until size.width * size.height) {
            val u = (k % size.width).toFloat() / (size.width - 1) + off[k * 2]
            val v = (k / size.width).toFloat() / (size.height - 1) + off[k * 2 + 1]
            toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy).let { pts[k * 2] = it.first; pts[k * 2 + 1] = it.second }
        }
        _warpOverlay.value = WarpOverlay(pts.toList(), size.width, size.height)
    }

    private var groupHandleCheckpointed = false

    private fun warpDown(sx: Float, sy: Float): Boolean {
        val t = deformerTarget() ?: return false
        if (t !in warpLayers || _playing.value) return false
        val p = _warpOverlay.value.points
        val width = _warpOverlay.value.width; val height = _warpOverlay.value.height
        if (p.size < width * height * 2) return false
        var best = -1
        var bestD = 44f
        for (k in 0 until width * height) {
            val d = kotlin.math.hypot(sx - p[k * 2], sy - p[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        groupHandleCheckpointed = false
        grab = 20 + best
        return true
    }

    private fun warpMove(sx: Float, sy: Float) {
        val k = grab - 20
        val t = deformerTarget() ?: return
        val q = deformerQuad(t) ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        val grid = denseWarps[t]
        if (grid != null) {
            if (_fx.value) { _state.value = UiState.Message("Плотный Warp: только обычный слой и FX выкл"); return }
            if (k !in 0 until grid.width * grid.height) return
            val x = (k % grid.width).toFloat() / (grid.width - 1); val y = (k / grid.width).toFloat() / (grid.height - 1)
            val cubic = com.vibetoon.domain.anim.CubicWarpGrid.cubic(t, warpGridSizes[t] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT, _frame.value, base, _animation.value, false)
            val b = com.vibetoon.domain.anim.WarpGridMath.evaluate(cubic, 4, x, y) ?: return
            val dx = (u - x - b.first).coerceIn(-1.5f, 1.5f); val dy = (v - y - b.second).coerceIn(-1.5f, 1.5f)
            if (dx == grid.offsets[k * 2] && dy == grid.offsets[k * 2 + 1]) return
            if (!groupHandleCheckpointed) { checkpoint("dense-warp:$t", force = true); groupHandleCheckpointed = true }
            val offsets = grid.offsets.toMutableList(); offsets[k * 2] = dx; offsets[k * 2 + 1] = dy
            denseWarps = denseWarps + (t to grid.copy(offsets = offsets.toList()))
            pushScene(); markDirty(); return
        }
        val size = warpGridSizes[t] ?: com.vibetoon.domain.anim.CubicWarpGrid.DEFAULT
        if (k !in 0 until size.width * size.height) return
        val ox = (u - (k % size.width).toFloat() / (size.width - 1)).coerceIn(-1.5f, 1.5f)
        val oy = (v - (k / size.width).toFloat() / (size.height - 1)).coerceIn(-1.5f, 1.5f)
        if (t in groupDeformationAnchors) {
            if (!groupHandleCheckpointed) { checkpoint("group-warp:$t", force = true); groupHandleCheckpointed = true }
        } else checkpoint("warp$t-$k")
        val id = WarpMath.pointId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)   // FX выкл: базовая форма (Bind Pose), ключи не создаются
        } else {
            val bind = base[id]
            // FX вкл: смещение записывается ключом на текущем кадре (дельта относительно формы привязки).
            // Спринт 41: автоключ деформера - исходная форма на кадре 0 и фиксация остальных точек на кадре.
            val group = (0 until size.width * size.height).map { WarpMath.pointId(t, it) }
            _animation.value = com.vibetoon.domain.anim.DeformAutoKey.key(_animation.value, group, _frame.value,
                mapOf(id to LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f))))
        }
        pushScene()
        markDirty()
    }

    // ---------------------------------------------------------------- Perspective (4 угла, гомография)

    private var perspLayers: Set<Int> = emptySet()
    private val _perspTool = MutableStateFlow(false)
    val perspTool: StateFlow<Boolean> = _perspTool.asStateFlow()
    private val _perspOverlay = MutableStateFlow(FloatArray(0))
    /** 4 угла слоя на экране (x, y в пикселях вида); пусто - инструмент не активен. */
    val perspOverlay: StateFlow<FloatArray> = _perspOverlay.asStateFlow()
    private val PERSP_GRAB = 30_000
    private data class PerspSideDrag(val layer: Int, val frame: Int, val fx: Boolean, val quad: FloatArray,
                                     val start: Pair<Float, Float>, val offsets: FloatArray, var checkpointed: Boolean = false)
    private var perspSideDrag: PerspSideDrag? = null

    private fun perspNow(): Map<Int, FloatArray> = perspLayers.associateWith {
        PerspectiveMath.matrix(PerspectiveMath.offsets(it, _frame.value, base, _animation.value, _fx.value))
    }

    private fun perspIds(l: Int) = (0 until PerspectiveMath.CORNERS).map { PerspectiveMath.pointId(l, it) }.toSet()

    private fun dropPerspData(layerIds: Set<Int>) {
        val gone = layerIds.filter { it in perspLayers }
        if (gone.isEmpty()) return
        val ids = gone.flatMapTo(HashSet()) { perspIds(it) }
        perspLayers = perspLayers - gone.toSet()
        for (id in ids) base.remove(id)
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
    }

    fun setPerspTool(on: Boolean) {
        if (_perspTool.value == on) return
        _perspTool.value = on
        if (on) ensurePersp()
        refreshPerspOverlay()
    }

    private fun ensurePersp() {
        val t = warpTarget() ?: return
        if (t in perspLayers) return
        checkpoint("persp-on", force = true)
        perspLayers = perspLayers + t
        pushScene()
        markDirty()
    }

    fun resetPersp() {
        val t = deformerTarget() ?: return
        if (t !in perspLayers) return
        checkpoint("persp-reset", force = true)
        perspLayers = perspLayers - t
        val ids = perspIds(t)
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        pushScene()
        markDirty()
        _state.value = UiState.Message("Перспектива слоя сброшена")
    }

    private fun refreshPerspOverlay() {
        val t = deformerTarget()
        val q = if (t != null) deformerQuad(t) else null
        if (!_perspTool.value || _boneMode.value || t == null || t !in perspLayers || q == null) {
            if (_perspOverlay.value.isNotEmpty()) _perspOverlay.value = FloatArray(0)
            return
        }
        val off = PerspectiveMath.offsets(t, _frame.value, base, _animation.value, _fx.value)
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val pts = FloatArray(8)
        for (k in 0 until 4) {
            val u = PerspectiveMath.cornerX(k) + off[k * 2]
            val v = PerspectiveMath.cornerY(k) + off[k * 2 + 1]
            toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy).let { pts[k * 2] = it.first; pts[k * 2 + 1] = it.second }
        }
        _perspOverlay.value = com.vibetoon.domain.anim.PerspectiveHandles.screenHandles(pts) ?: FloatArray(0)
    }

    private fun perspDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        val t = deformerTarget() ?: return false
        if (t !in perspLayers || _playing.value) return false
        val p = _perspOverlay.value
        if (p.size < 16) return false
        var best = -1
        var bestD = 48f
        for (k in 0 until com.vibetoon.domain.anim.PerspectiveHandles.HANDLES) {
            val d = kotlin.math.hypot(sx - p[k * 2], sy - p[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        perspSideDrag = if (best >= 4) {
            val quad = deformerQuad(t)?.copyOf() ?: return false
            val start = unitOf(quad, toCanvas(sx, sy)) ?: return false
            PerspSideDrag(t, _frame.value, _fx.value, quad, start, PerspectiveMath.offsets(t, _frame.value, base, _animation.value, _fx.value))
        } else null
        groupHandleCheckpointed = false
        grab = PERSP_GRAB + best
        swallowTap = true
        return true
    }

    private fun perspMove(sx: Float, sy: Float) {
        val k = grab - PERSP_GRAB
        val t = deformerTarget() ?: return
        val q = deformerQuad(t) ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        if (k >= com.vibetoon.domain.anim.PerspectiveHandles.CORNERS) {
            val drag = perspSideDrag ?: return
            if (drag.layer != t || drag.frame != _frame.value || drag.fx != _fx.value || !drag.quad.contentEquals(q)) return
            val moved = com.vibetoon.domain.anim.PerspectiveHandles.moveEdge(drag.offsets, k, u - drag.start.first, v - drag.start.second) ?: return
            val (a, b) = com.vibetoon.domain.anim.PerspectiveHandles.edgeCorners(k) ?: return
            val current = PerspectiveMath.offsets(t, _frame.value, base, _animation.value, _fx.value)
            if (current.contentEquals(moved)) return
            if (!drag.checkpointed) { checkpoint("persp-edge:$t:$k", force = true); drag.checkpointed = true }
            for (corner in listOf(a, b)) {
                val id = PerspectiveMath.pointId(t, corner)
                val ox = moved[corner * 2]; val oy = moved[corner * 2 + 1]
                if (!_fx.value) base[id] = LayerTransform(dx = ox, dy = oy)
                else {
                    val bind = base[id]
                    // Первый ключ после нуля не должен менять исходную форму на предыдущих кадрах.
                    if (_frame.value > 0 && !_animation.value.hasTrack(id))
                        _animation.value = _animation.value.withKey(id, 0, LayerTransform())
                    _animation.value = _animation.value.withKey(id, _frame.value,
                        LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
                }
            }
            pushScene(); markDirty()
            return
        }
        val ox = (u - PerspectiveMath.cornerX(k)).coerceIn(-1.5f, 1.5f)
        val oy = (v - PerspectiveMath.cornerY(k)).coerceIn(-1.5f, 1.5f)
        if (t in groupDeformationAnchors) {
            if (!groupHandleCheckpointed) { checkpoint("group-persp:$t", force = true); groupHandleCheckpointed = true }
        } else checkpoint("persp$t-$k")
        val id = PerspectiveMath.pointId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)
        } else {
            val bind = base[id]
            // спринт 41: автоключ углов перспективы - исходная форма на кадре 0, остальные углы фиксируются на кадре
            _animation.value = com.vibetoon.domain.anim.DeformAutoKey.key(_animation.value,
                (0 until PerspectiveMath.CORNERS).map { PerspectiveMath.pointId(t, it) }, _frame.value,
                mapOf(id to LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f))))
        }
        pushScene()
        markDirty()
    }

    // ---------------------------------------------------------------- Спринт 36: наклон (Skew) целого слоя/группы

    /**
     * Текущий наклон выбранного слоя/группы (X, Y в градусах) на текущем кадре; null - нет подходящей цели.
     * Наклон хранится в углах «Перспективы», поэтому учитывает и её ключи (при включённой анимации).
     */
    fun layerSkewNow(): Pair<Float, Float>? {
        val t = deformerTarget() ?: return null
        val q = deformerQuad(t) ?: return null
        val off = PerspectiveMath.offsets(t, _frame.value, base, _animation.value, _fx.value)
        return com.vibetoon.domain.anim.LayerSkew.measure(off, kotlin.math.hypot(q[2] - q[0], q[3] - q[1]), kotlin.math.hypot(q[6] - q[0], q[7] - q[1]))
    }

    /**
     * Наклон слоя/группы: [kxDeg]/[kyDeg] (null - ось не меняется). Пишется в углы перспективы: без анимации - в форму
     * слоя, при включённой анимации - ключом на текущем кадре (первый ключ после нуля ставит ноль на кадре 0, чтобы
     * прежние кадры не менялись). Одна операция Undo/Redo, сохраняется в проект.
     */
    fun skewLayer(kxDeg: Float?, kyDeg: Float?) {
        if (_playing.value) return
        val t = deformerTarget() ?: run { _state.value = UiState.Message("Выберите один слой (или группу с включённой перспективой)"); return }
        if (isLockedId(t)) { lockedNotice(); return }
        val q = deformerQuad(t) ?: return
        val w = kotlin.math.hypot(q[2] - q[0], q[3] - q[1]); val h = kotlin.math.hypot(q[6] - q[0], q[7] - q[1])
        val off = PerspectiveMath.offsets(t, _frame.value, base, _animation.value, _fx.value)
        val next = com.vibetoon.domain.anim.LayerSkew.withSkew(off, w, h, kxDeg, kyDeg)
            ?: run { _state.value = UiState.Message("Наклон вне допустимого диапазона (до ±75°) для этого слоя"); return }
        if (next.contentEquals(off)) return
        checkpoint("layer-skew:$t", force = true)
        if (t !in perspLayers) perspLayers = perspLayers + t
        writePerspOffsets(t, next)
        pushScene(); markDirty(); refreshPerspOverlay()
        _state.value = UiState.Message("Наклон слоя изменён (Отменить возвращает прежнюю форму)")
    }

    /** Убирает наклон слоя, сохраняя прочее искажение углов. */
    fun straightenLayer() = skewLayer(0f, 0f)

    private fun writePerspOffsets(t: Int, off: FloatArray) {
        for (corner in 0 until PerspectiveMath.CORNERS) {
            val id = PerspectiveMath.pointId(t, corner)
            val ox = off[corner * 2]; val oy = off[corner * 2 + 1]
            if (!_fx.value) base[id] = LayerTransform(dx = ox, dy = oy)
            else {
                val bind = base[id]
                if (_frame.value > 0 && !_animation.value.hasTrack(id))
                    _animation.value = _animation.value.withKey(id, 0, LayerTransform())
                _animation.value = _animation.value.withKey(id, _frame.value,
                    LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
            }
        }
    }

    // ---------------------------------------------------------------- Liquify (деструктивная кисть)

    private val _liquifyTool = MutableStateFlow(false)
    val liquifyTool: StateFlow<Boolean> = _liquifyTool.asStateFlow()
    private val _liqRadius = MutableStateFlow(90f)
    /** Радиус кисти Liquify в пикселях экрана. */
    val liqRadius: StateFlow<Float> = _liqRadius.asStateFlow()
    private val _liqUndoCount = MutableStateFlow(0)
    val liqUndoCount: StateFlow<Int> = _liqUndoCount.asStateFlow()
    private val LIQ_GRAB = 40_000
    private var liqLayer = -1
    private var liqW = 0
    private var liqH = 0
    private var liqPx = IntArray(0)
    private var liqBmp: android.graphics.Bitmap? = null
    private var liqFile: java.io.File? = null
    private var liqLast: Pair<Float, Float>? = null
    private var liqDirty = false
    private val liqUndo = ArrayDeque<IntArray>()

    /** Пиксели слоя PSD, прочитанные из текстуры (слой, ширина, высота, ARGB без premultiply); готовятся заранее из GL-потока. */
    private class LiqPsdPixels(val id: Int, val w: Int, val h: Int, val px: IntArray)
    @Volatile private var liqPsdCache: LiqPsdPixels? = null
    @Volatile private var liqPsdPending = -1

    fun setLiquifyTool(on: Boolean) {
        if (_liquifyTool.value == on) return
        _liquifyTool.value = on
        if (!on) { liqRelease(); liqPsdCache = null }
        else {
            _state.value = UiState.Message("Liquify: выберите слой и проведите пальцем (правка необратимо меняет пиксели слоя)")
            liqPrefetch()
        }
    }

    fun setLiqRadius(r: Float) { _liqRadius.value = r.coerceIn(30f, 260f) }

    private fun liqRelease() {
        liqSave()
        liqBmp?.recycle(); liqBmp = null
        liqPx = IntArray(0); liqLayer = -1; liqFile = null
        liqUndo.clear(); _liqUndoCount.value = 0
    }

    /** Цель Liquify: слой-картинка (файл проекта) или слой PSD (файл правки assets/liq_<id>.png). */
    private class LiqTarget(val id: Int, val file: java.io.File, val psd: Boolean)

    private fun liqTarget(): LiqTarget? {
        val t = warpTarget() ?: return null
        val pid = projectId ?: return null
        val m = media.firstOrNull { it.id == t }
        if (m != null) {
            if (m.kind != MediaKind.IMAGE) return null
            return LiqTarget(t, projects.mediaFile(pid, m.file.removePrefix("assets/")), false)
        }
        if (t >= MEDIA_ID_BASE) return null
        return LiqTarget(t, projects.mediaFile(pid, "liq_$t.png"), true)
    }

    /** Для слоя PSD без правок заранее читает пиксели текстуры (GL-поток), чтобы первый мазок не ждал. */
    private fun liqPrefetch() {
        if (!_liquifyTool.value) return
        val tg = liqTarget() ?: return
        if (!tg.psd || tg.file.exists() || liqPsdCache?.id == tg.id || liqPsdPending == tg.id) return
        val view = RenderHost.view ?: return
        liqPsdPending = tg.id
        view.layerPixels(tg.id) { bytes ->
            liqPsdPending = -1
            if (bytes != null && bytes.size > 8) {
                fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
                val w = i32(0); val h = i32(4)
                if (w > 0 && h > 0 && bytes.size >= 8 + w * h * 4) {
                    val px = IntArray(w * h)
                    for (k in 0 until w * h) {
                        val o = 8 + k * 4
                        val a = bytes[o + 3].toInt() and 0xFF
                        var r = bytes[o].toInt() and 0xFF; var g = bytes[o + 1].toInt() and 0xFF; var b = bytes[o + 2].toInt() and 0xFF
                        if (a in 1..254) { r = minOf(255, r * 255 / a); g = minOf(255, g * 255 / a); b = minOf(255, b * 255 / a) }
                        px[k] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    liqPsdCache = LiqPsdPixels(tg.id, w, h, px)
                }
            }
        }
    }

    /** Загружает пиксели слоя в рабочий буфер (один раз на слой). */
    private fun liqPrepare(tg: LiqTarget): Boolean {
        if (liqLayer == tg.id && liqBmp != null) return true
        liqRelease()
        var w: Int; var h: Int; var px: IntArray
        if (tg.file.exists()) {
            val d = try { importer.decodeImage(tg.file) } catch (e: Exception) { _state.value = UiState.Message("Не удалось открыть картинку"); return false }
            w = d.bitmap.width; h = d.bitmap.height
            px = IntArray(w * h)
            d.bitmap.getPixels(px, 0, w, 0, 0, w, h)
            d.bitmap.recycle()
        } else {
            val c = liqPsdCache
            if (!tg.psd || c == null || c.id != tg.id) {
                _state.value = UiState.Message("Слой готовится, повторите мазок")
                liqPrefetch()
                return false
            }
            w = c.w; h = c.h; px = c.px.copyOf()
        }
        liqW = w; liqH = h; liqPx = px; liqLayer = tg.id; liqFile = tg.file
        liqBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        return true
    }

    private fun liquifyDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        if (_playing.value) return false
        val tg = liqTarget() ?: run { _state.value = UiState.Message("Liquify: выберите один слой (картинка или слой PSD)"); return false }
        val q = scene?.selectionQuads?.get(tg.id) ?: return false
        if (!liqPrepare(tg)) return false
        val p = unitOf(q, toCanvas(sx, sy)) ?: return false
        liqLast = p
        liqDirty = false
        liqUndo.addLast(liqPx.copyOf())
        while (liqUndo.size > 3) liqUndo.removeFirst()
        _liqUndoCount.value = liqUndo.size
        grab = LIQ_GRAB
        swallowTap = true
        return true
    }

    private fun liquifyMove(sx: Float, sy: Float) {
        val q = scene?.selectionQuads?.get(liqLayer) ?: return
        val last = liqLast ?: return
        val p = unitOf(q, toCanvas(sx, sy)) ?: return
        // длина стороны слоя на экране -> радиус кисти в пикселях картинки
        val ex = toScreen(q[2], q[3]).first - toScreen(q[0], q[1]).first; val ey = toScreen(q[2], q[3]).second - toScreen(q[0], q[1]).second
        val screenW = kotlin.math.hypot(ex, ey).coerceAtLeast(1f)
        val r = _liqRadius.value / screenW * liqW
        val dx = (p.first - last.first) * liqW
        val dy = (p.second - last.second) * liqH
        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) < 0.5f) return
        val n = com.vibetoon.domain.anim.LiquifyMath.push(liqPx, liqW, liqH, p.first * liqW, p.second * liqH, r, dx, dy)
        liqLast = p
        if (n > 0) { liqDirty = true; liqUpload() }
    }

    private fun liqUpload() {
        val b = liqBmp ?: return
        invalidateThumb(liqLayer)
        b.setPixels(liqPx, 0, liqW, 0, 0, liqW, liqH)
        RenderNative.uploadBitmap(liqLayer, b)
    }

    /** Записывает правку в файл (PNG): у картинки это сам файл, у слоя PSD - assets/liq_<id>.png (применяется при открытии проекта). */
    private fun liqSave() {
        val b = liqBmp ?: return
        if (!liqDirty) return
        liqDirty = false
        val file = liqFile ?: return
        val copy = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { file.outputStream().use { copy.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            copy.recycle()
        }
    }

    private fun finishLiquify() { liqLast = null; liqSave() }

    /** Откатывает последний мазок Liquify (до 3 мазков). */
    fun liquifyUndo() {
        val prev = liqUndo.removeLastOrNull() ?: return
        if (liqBmp == null || prev.size != liqW * liqH) return
        liqPx = prev
        liqDirty = true
        liqUpload()
        liqSave()
        _liqUndoCount.value = liqUndo.size
    }

    // ---------------------------------------------------------------- Рисование (Кисть и Ластик)

    enum class PaintTool { BRUSH, ERASER }

    private val _paintTool = MutableStateFlow<PaintTool?>(null)
    /** Активный инструмент рисования (null - не рисуем). */
    val paintTool: StateFlow<PaintTool?> = _paintTool.asStateFlow()
    private val _brush = MutableStateFlow(BrushParams())
    /** Настройки кисти (размер в пикселях слоя, жёсткость, Flow, Opacity штриха, режим Маркер/Аэрограф, цвет). */
    val brush: StateFlow<BrushParams> = _brush.asStateFlow()
    private val _eraser = MutableStateFlow(BrushParams(size = 48f, hardness = 0.9f, mode = StrokeMode.ERASE))
    val eraser: StateFlow<BrushParams> = _eraser.asStateFlow()
    private val _paintCursor = MutableStateFlow<FloatArray?>(null)
    /** Круг кисти под пальцем: x, y, радиус в пикселях экрана (null - нет касания). */
    val paintCursor: StateFlow<FloatArray?> = _paintCursor.asStateFlow()

    private val _gradientMode = MutableStateFlow(false)
    val gradientMode = _gradientMode.asStateFlow()
    private val _gradientAlpha = MutableStateFlow(false)
    val gradientAlpha = _gradientAlpha.asStateFlow()
    fun toggleGradient() {
        if (stroker != null) paintCancel()
        _gradientMode.value = !_gradientMode.value
        if (_gradientMode.value) _rulerMode.value = com.vibetoon.domain.paint.RulerPath.Mode.OFF
    }
    fun toggleGradientAlpha() { if (stroker != null) paintCancel(); _gradientAlpha.value = !_gradientAlpha.value }

    private val _rulerMode = MutableStateFlow(com.vibetoon.domain.paint.RulerPath.Mode.OFF)
    val rulerMode = _rulerMode.asStateFlow()
    private val _rulerGuide = MutableStateFlow<List<Float>>(emptyList())
    val rulerGuide = _rulerGuide.asStateFlow()
    private var rulerQuad: FloatArray? = null
    private var rulerCamera: Camera? = null
    private var rulerViewW = 0f
    private var rulerViewH = 0f
    private var rulerParams: BrushParams? = null
    private var rulerPressure = 1f

    fun setRulerMode(mode: com.vibetoon.domain.paint.RulerPath.Mode) {
        if (stroker != null) paintCancel()
        _rulerMode.value = mode
        if (mode != com.vibetoon.domain.paint.RulerPath.Mode.OFF) _gradientMode.value = false
        _rulerGuide.value = emptyList()
    }
    private fun clearRulerGesture() { _rulerGuide.value = emptyList(); rulerQuad = null; rulerCamera = null; rulerParams = null }

    // ---- Sprint 33: направляющие рисования (изометрия, перспектива 1/2/3, эллипс) и симметрия ----
    private val _guides = MutableStateFlow(com.vibetoon.domain.paint.DrawingGuides.State())
    /** Направляющие в координатах холста: только редактор, в экспорт и проект не попадают. */
    val guides: StateFlow<com.vibetoon.domain.paint.DrawingGuides.State> = _guides.asStateFlow()
    private val _guideEdit = MutableStateFlow(false)
    /** «Править направляющие»: касание ручки тянет точку схода/центр/радиус вместо мазка. */
    val guideEdit: StateFlow<Boolean> = _guideEdit.asStateFlow()
    private var guideStroke: com.vibetoon.domain.paint.DrawingGuides.Stroke? = null
    private var mirrorStrokers: List<DabStroker> = emptyList()
    private var guideDrag: com.vibetoon.domain.paint.DrawingGuides.Handle? = null

    private fun guidePrefs() = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)
    private fun saveGuides() {
        runCatching { guidePrefs().edit().putString("drawing_guides", com.vibetoon.domain.paint.DrawingGuides.encode(_guides.value, canvasW, canvasH)).apply() }
    }
    /** При открытии проекта: направляющие из настроек, пропорционально перенесённые на текущий холст. */
    private fun restoreGuides() {
        val decoded = runCatching { com.vibetoon.domain.paint.DrawingGuides.decode(guidePrefs().getString("drawing_guides", null)) }.getOrNull()
        _guides.value = if (decoded == null) com.vibetoon.domain.paint.DrawingGuides.State()
            else com.vibetoon.domain.paint.DrawingGuides.rescale(decoded.state, decoded.width, decoded.height, canvasW, canvasH)
        _guideEdit.value = false
        // Спринт 37: настройки кальки перечитываются при открытии проекта (например, после импорта резервной копии настроек)
        runCatching { _onionSettings.value = com.vibetoon.domain.project.OnionSkin.decode(guidePrefs().getString("onion_settings", null)) }
    }
    private fun guidesChanged(next: com.vibetoon.domain.paint.DrawingGuides.State) {
        if (stroker != null) paintCancel()
        guideDrag = null
        _guides.value = next
        if (!next.active) _guideEdit.value = false
        saveGuides()
    }
    fun setGuideKind(kind: com.vibetoon.domain.paint.DrawingGuides.Kind) =
        guidesChanged(com.vibetoon.domain.paint.DrawingGuides.withKind(_guides.value, kind, canvasW, canvasH))
    fun setSymmetry(symmetry: com.vibetoon.domain.paint.DrawingGuides.Symmetry) =
        guidesChanged(com.vibetoon.domain.paint.DrawingGuides.withSymmetry(_guides.value, symmetry, canvasW, canvasH))
    fun setRadialCount(count: Int) {
        if (count == _guides.value.radial) return
        guidesChanged(com.vibetoon.domain.paint.DrawingGuides.withRadial(_guides.value, count))
    }
    fun toggleGuideSnap() = guidesChanged(_guides.value.copy(snap = !_guides.value.snap))
    fun toggleGuideEdit() { _guideEdit.value = !_guideEdit.value && _guides.value.active }
    /** Геометрия по умолчанию для текущего вида и центр симметрии в центре холста. */
    fun resetGuides() {
        val g = _guides.value
        val kind = com.vibetoon.domain.paint.DrawingGuides.withKind(g, g.kind, canvasW, canvasH)
        val sym = if (g.symmetry == com.vibetoon.domain.paint.DrawingGuides.Symmetry.OFF) kind
            else com.vibetoon.domain.paint.DrawingGuides.withSymmetry(kind.copy(symmetry = com.vibetoon.domain.paint.DrawingGuides.Symmetry.OFF), g.symmetry, canvasW, canvasH)
        guidesChanged(sym)
    }

    /** Точки штриха с направляющими (холст) -> штампы исходного и зеркальных штрихов в пикселях слоя. */
    private fun emitGuided(points: List<List<com.vibetoon.domain.paint.DrawingGuides.Point>>, q: FloatArray, size: IntArray, pressure: Float) {
        val main = stroker ?: return
        points.forEachIndexed { i, list ->
            val st = if (i == 0) main else mirrorStrokers.getOrNull(i - 1)
            if (st != null) for (pt in list) {
                val u = unitOf(q, pt.x to pt.y) ?: continue
                sendDabs(st.add(u.first * size[0], u.second * size[1], pressure))
            }
        }
    }

    private var stroker: DabStroker? = null
    private var strokeLayer = -1
    private var strokeSize = intArrayOf(0, 0)
    private var assistPoints: MutableList<com.vibetoon.domain.paint.ShapeAssist.Point>? = null
    private var assistAnchorX = 0f
    private var assistAnchorY = 0f
    private var assistLastMove = 0L
    private var assistQuad: FloatArray? = null
    private var assistParams: BrushParams? = null
    private var assistCamera: Camera? = null
    private var assistViewW = 0f
    private var assistViewH = 0f

    private fun clearShapeAssist() { assistPoints = null; assistQuad = null; assistParams = null; assistCamera = null }
    private fun recordShapePoint(sx: Float, sy: Float, pressure: Float) {
        val points = assistPoints ?: return
        val slop = 4f * getApplication<Application>().resources.displayMetrics.density
        if (kotlin.math.hypot(sx - assistAnchorX, sy - assistAnchorY) >= slop) {
            assistAnchorX = sx; assistAnchorY = sy; assistLastMove = SystemClock.uptimeMillis()
        }
        val last = points.last()
        if (kotlin.math.hypot(sx - last.x, sy - last.y) < 1f) return
        if (points.size >= com.vibetoon.domain.paint.ShapeAssist.MAX_POINTS) { clearShapeAssist(); return }
        points.add(com.vibetoon.domain.paint.ShapeAssist.Point(sx, sy, pressure))
    }

    /** Заменяет ещё незавершённый растровый штрих до commit: в истории одна операция. */
    private fun assistedLine(view: com.vibetoon.render.VtGLSurfaceView, layer: Int): DabStroker? {
        val points = assistPoints ?: return null
        val q = assistQuad ?: return null
        val params = assistParams ?: return null
        if (scene?.selectionQuads?.get(layer)?.contentEquals(q) != true || paintTargetId() != layer ||
            camera != assistCamera || viewW != assistViewW || viewH != assistViewH) return null
        val density = getApplication<Application>().resources.displayMetrics.density
        val held = SystemClock.uptimeMillis() - assistLastMove
        val path = com.vibetoon.domain.paint.ShapeAssist.line(points, held, density)
            ?: com.vibetoon.domain.paint.ShapeAssist.closed(points, held, density)
            ?: com.vibetoon.domain.paint.ShapeAssist.triangle(points, held, density) ?: return null
        val ends = path.map { p ->
            val u = unitOf(q, toCanvas(p.x, p.y)) ?: return null
            Triple(u.first * strokeSize[0], u.second * strokeSize[1], p.pressure)
        }
        view.paintEnd(false) { }
        val c = params.color
        view.paintBegin(layer, params.mode.gl, params.opacity, layerProps[layer]?.alphaLock == true,
            ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, params.shape.gl, params.hardness,
            params.grain.gl, params.grainMode.gl, params.grainScale, params.grainContrast, params.roundness, params.curve, GrainMath.texture(params.grain)) { }
        val st = DabStroker(params)
        sendDabs(st.begin(ends[0].first, ends[0].second, ends[0].third))
        for (point in ends.drop(1)) sendDabs(st.add(point.first, point.second, point.third))
        return st
    }
    private val paintSizes = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()
    private val paintDirty: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet<Int>())
    @Volatile private var stylusDown = false
    @Volatile private var stylusLast = 0L

    fun setPaintTool(t: PaintTool?) {
        if (_paintTool.value == t) return
        if (stroker != null) paintCancel()
        clearRulerGesture()
        _gradientMode.value = false
        _paintTool.value = t
        if (t != null) {
            ensurePixelTarget()?.let { requestPaintSize(it) }
            requestBrushPreviews()
            _state.value = UiState.Message(if (t == PaintTool.BRUSH) "Кисть: рисуйте на слое-картинке (или создайте «Новый слой»)" else "Ластик: стирает пиксели слоя-картинки")
        }
    }

    fun paintToolActive(): Boolean = _paintTool.value != null && !_playing.value

    fun paintParams(): BrushParams = if (_paintTool.value == PaintTool.ERASER) _eraser.value else _brush.value

    /** Меняет настройки активного инструмента (кисти или ластика). */
    fun updatePaint(change: (BrushParams) -> BrushParams) {
        if (_paintTool.value == PaintTool.ERASER) _eraser.value = change(_eraser.value) else _brush.value = change(_brush.value)
    }

    /** Горячие клавиши физической клавиатуры. */
    fun onHotkey(a: com.vibetoon.domain.HotkeyAction) {
        if (_selectionExtractBusy.value) return
        when (a) {
            com.vibetoon.domain.HotkeyAction.UNDO -> undo()
            com.vibetoon.domain.HotkeyAction.REDO -> redo()
            com.vibetoon.domain.HotkeyAction.PLAY_PAUSE -> togglePlay()
            com.vibetoon.domain.HotkeyAction.BRUSH -> setPaintTool(PaintTool.BRUSH)
            com.vibetoon.domain.HotkeyAction.ERASER -> setPaintTool(PaintTool.ERASER)
            com.vibetoon.domain.HotkeyAction.SELECT -> setPaintTool(null)
            com.vibetoon.domain.HotkeyAction.BRUSH_SMALLER -> updatePaint { it.copy(size = com.vibetoon.domain.Hotkeys.resize(it.size, false)) }
            com.vibetoon.domain.HotkeyAction.BRUSH_BIGGER -> updatePaint { it.copy(size = com.vibetoon.domain.Hotkeys.resize(it.size, true)) }
            com.vibetoon.domain.HotkeyAction.DELETE -> if (_keySel.value.isNotEmpty()) deleteSelectedKeys() else deleteSelected()
        }
    }

    fun setBrushColor(argb: Int) { _brush.value = _brush.value.copy(color = argb) }

    // ---- библиотека кистей: выезжающая панель с превью ----
    private val _brushMenu = MutableStateFlow(false)
    val brushMenu: StateFlow<Boolean> = _brushMenu.asStateFlow()
    /** Превью кистей (S-образный мазок 256 x 64), рисуются в Offscreen FBO и кэшируются по id пресета. */
    val brushPreviews = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val previewPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun toggleBrushMenu() {
        _brushMenu.value = !_brushMenu.value
        if (_brushMenu.value) { reloadCustomBrushes(); requestBrushPreviews() }
    }
    fun closeBrushMenu() { _brushMenu.value = false }

    /** Пресеты для меню активного инструмента: кисти или ластики. */
    fun presetsForTool(): List<BrushPreset> {
        val eraser = _paintTool.value == PaintTool.ERASER
        val own = _customBrushes.value.filter { (it.params.mode == StrokeMode.ERASE) == eraser }
        return (if (eraser) BrushPresets.erasers else BrushPresets.brushes) + own
    }

    // ---- спринт 39: свои кисти (сохранить текущую, импорт/экспорт файлом); хранятся в «vt_app» и попадают в резервную копию ----
    private val _customBrushes = MutableStateFlow<List<BrushPreset>>(emptyList())
    val customBrushes: StateFlow<List<BrushPreset>> = _customBrushes.asStateFlow()
    private var customBrushesLoaded = false

    private fun reloadCustomBrushes() {
        val list = com.vibetoon.domain.paint.BrushExchange.load(appPrefs().getString(com.vibetoon.domain.paint.BrushExchange.PREF_KEY, null))
        if (customBrushesLoaded && list != _customBrushes.value) for (b in _customBrushes.value) brushPreviews.remove(b.id)   // после восстановления копии превью строятся заново
        customBrushesLoaded = true
        _customBrushes.value = list
    }

    /** Последняя записанная самим редактором строка своих кистей (слушатель настроек не перечитывает собственную запись). */
    private var ownBrushesJson: String? = null

    private fun storeCustomBrushes(list: List<BrushPreset>) {
        _customBrushes.value = list
        val json = com.vibetoon.domain.paint.BrushExchange.encode(list)
        ownBrushesJson = json
        appPrefs().edit().putString(com.vibetoon.domain.paint.BrushExchange.PREF_KEY, json).apply()
    }

    /** Пресет по id: встроенный или свой (для подписи кнопки кистей). */
    fun presetById(id: String): BrushPreset? {
        if (!customBrushesLoaded) reloadCustomBrushes()
        return BrushPresets.byId(id) ?: _customBrushes.value.firstOrNull { it.id == id }
    }

    fun isCustomBrush(id: String): Boolean = com.vibetoon.domain.paint.BrushExchange.isCustom(id)

    /** «Сохранить текущую как свою»: настройки активной кисти или ластика (без цвета) - в список своих. */
    fun saveCurrentBrush() {
        if (!customBrushesLoaded) reloadCustomBrushes()
        val cur = _customBrushes.value
        if (cur.size >= com.vibetoon.domain.paint.BrushExchange.MAX_BRUSHES) { _state.value = UiState.Message("Своих кистей уже 100: удалите ненужные"); return }
        val p = paintParams()
        val b = com.vibetoon.domain.paint.BrushExchange.fromCurrent(p, com.vibetoon.domain.paint.BrushExchange.suggestName(p, cur), cur.map { it.id }.toSet())
        storeCustomBrushes(cur + b)
        if (p.mode == StrokeMode.ERASE) _eraser.value = _eraser.value.copy(presetId = b.id) else _brush.value = _brush.value.copy(presetId = b.id)
        requestBrushPreviews()
        _state.value = UiState.Message("Кисть сохранена: ${b.title}")
    }

    fun deleteCustomBrush(id: String) {
        val cur = _customBrushes.value
        val b = cur.firstOrNull { it.id == id } ?: return
        storeCustomBrushes(cur - b)
        brushPreviews.remove(id)
        _state.value = UiState.Message("Кисть удалена: ${b.title}")
    }

    /** Экспорт своих кистей; если своих нет - текущие настройки кисти/ластика одной кистью. */
    fun exportBrushesTo(uri: Uri) {
        if (!customBrushesLoaded) reloadCustomBrushes()
        val list = _customBrushes.value.ifEmpty {
            val p = paintParams()
            listOf(com.vibetoon.domain.paint.BrushExchange.fromCurrent(p, com.vibetoon.domain.paint.BrushExchange.suggestName(p, emptyList()), emptySet()))
        }
        val text = com.vibetoon.domain.paint.BrushExchange.encode(list)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            }
            _state.value = UiState.Message(if (ok) "Кисти экспортированы: ${list.size}" else "Не удалось сохранить файл кистей")
        }
    }

    /** Импорт кистей из файла: проверка, повреждённые записи пропускаются, существующие кисти не затираются. */
    fun importBrushesFrom(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openInputStream(uri)?.use { com.vibetoon.domain.paint.BrushExchange.readLimited(it) ?: "\u0000too-big" } }.getOrNull()
            }
            if (text == null) { _state.value = UiState.Message("Не удалось открыть файл"); return@launch }
            if (text == "\u0000too-big") { _state.value = UiState.Message(com.vibetoon.domain.paint.ExchangeError.TOO_BIG.ru); return@launch }
            if (!customBrushesLoaded) reloadCustomBrushes()
            val cur = _customBrushes.value
            val r = com.vibetoon.domain.paint.BrushExchange.decode(text, cur.map { it.id }.toSet())
            if (!r.ok) { _state.value = UiState.Message(com.vibetoon.domain.paint.BrushExchange.report(r)); return@launch }
            val merged = com.vibetoon.domain.paint.BrushExchange.merge(cur, r.items)
            storeCustomBrushes(merged)
            requestBrushPreviews()
            val added = merged.size - cur.size
            _state.value = UiState.Message(
                if (added < r.items.size) "Импортировано кистей: $added, не поместилось: ${r.items.size - added} (не больше 100)"
                else com.vibetoon.domain.paint.BrushExchange.report(r)
            )
        }
    }

    /** Выбор пресета: размер, форма, жёсткость, режим, зерно берутся из него; цвет остаётся текущим. */
    fun selectPreset(p: BrushPreset) {
        if (p.params.mode == StrokeMode.ERASE) _eraser.value = p.params
        else { _paintTool.value = PaintTool.BRUSH; _brush.value = p.params.copy(color = _brush.value.color) }
        _brushMenu.value = false
    }

    fun requestBrushPreviews() {
        val view = RenderHost.view ?: return
        val w = 256; val h = 64
        if (!customBrushesLoaded) reloadCustomBrushes()
        for (pr in BrushPresets.all() + _customBrushes.value) {
            if (brushPreviews.containsKey(pr.id) || !previewPending.add(pr.id)) continue
            val p = pr.params
            val dabs = BrushPresets.previewDabs(p, w, h)
            view.brushPreview(
                p.mode.gl, 0f, 0f, 0f, p.shape.gl, p.hardness, p.grain.gl, p.grainMode.gl, p.grainScale, p.grainContrast, p.roundness, p.curve,
                GrainMath.texture(p.grain), dabs, dabs.size / DabStroker.STRIDE, w, h
            ) { bytes ->
                previewPending.remove(pr.id)
                if (bytes == null || bytes.size < w * h * 4) return@brushPreview
                val px = IntArray(w * h)
                for (i in px.indices) {
                    val o = i * 4
                    px[i] = ((bytes[o + 3].toInt() and 0xFF) shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                        ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
                }
                val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.isPremultiplied = true
                viewModelScope.launch { brushPreviews[pr.id] = bmp.asImageBitmap() }
            }
        }
    }

    /**
     * Перо: пока оно касается экрана или только что касалось, касания пальцем игнорируются (ладонь не рисует).
     * Спринт 44: состояние кормит общий наблюдатель контакта пера на холсте (любой инструмент), а не только ветка
     * кисти - защита действует и для растровых инструментов (выделение, заливка), и после штриха пером по слою без
     * пикселей. Наведение (hover) защиту не запускает - иначе щипок камеры не работал бы, пока перо рядом.
     */
    fun paintStylus(down: Boolean) { stylusDown = down; stylusLast = SystemClock.uptimeMillis() }
    fun paintPalmBlocked(): Boolean = com.vibetoon.domain.input.EditorInputPolicy.palmBlocked(stylusDown, stylusLast, SystemClock.uptimeMillis())

    // Stylus sprint: кривая нажима, стабилизация 1–10 и ластик на обратном конце пера
    private var stabilizer: com.vibetoon.domain.input.StrokeStabilizer? = null
    private var strokeCurve: com.vibetoon.domain.input.PressureCurve = com.vibetoon.domain.input.PressureCurve.LINEAR
    private var strokeStylus = false
    private var tipRestore: PaintTool? = null
    private val _livePressure = MutableStateFlow<FloatArray?>(null)
    /** Нажим текущего штриха пером: [сырой, после кривой]; null - пером сейчас не рисуют. */
    val livePressure: StateFlow<FloatArray?> = _livePressure.asStateFlow()

    private fun stylusPrefs() = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)

    /** Нажим кисти: кривая чувствительности применяется только к перу; палец и мышь всегда 1.0. */
    private fun curvedPressure(raw: Float): Float {
        if (!strokeStylus) return 1f
        val mapped = strokeCurve.map(raw).coerceIn(com.vibetoon.domain.input.StylusSample.MIN_PRESSURE, 1f)
        // спринт 44: индикатор обновляется, только если нажим заметно изменился (не 240 новых массивов в секунду)
        val prev = _livePressure.value
        if (com.vibetoon.domain.input.EditorInputPolicy.hudChanged(prev?.getOrNull(0), prev?.getOrNull(1), raw, mapped)) _livePressure.value = floatArrayOf(raw, mapped)
        return mapped
    }

    /** Обратный конец пера (TOOL_TYPE_ERASER): на время штриха кисть становится ластиком, если это включено в настройках. */
    fun stylusTip(eraser: Boolean) { stylusStart(eraser, false, Float.NaN, Float.NaN) }

    /**
     * Спринт 44: начало касания пером - обратный конец ([eraserEnd]) и кнопка на корпусе ([barrel], StylusButtons).
     * Ластик: кисть на время штриха становится ластиком и возвращается после него. Пипетка: касание берёт цвет с холста
     * в кисть и штрих не начинается - тогда возвращается false.
     */
    fun stylusStart(eraserEnd: Boolean, barrel: Boolean, x: Float, y: Float): Boolean {
        val prefs = runCatching { stylusPrefs() }.getOrNull()
        val action = com.vibetoon.domain.input.StylusButtons.Action.of(
            prefs?.getInt(com.vibetoon.domain.input.StylusButtons.PREF, com.vibetoon.domain.input.StylusButtons.DEFAULT.id) ?: com.vibetoon.domain.input.StylusButtons.DEFAULT.id)
        when (com.vibetoon.domain.input.StylusButtons.start(eraserEnd, prefs?.getBoolean("stylus_eraser_tip", true) ?: true, barrel, action)) {
            com.vibetoon.domain.input.StylusButtons.Start.ERASE -> if (tipRestore == null && _paintTool.value == PaintTool.BRUSH) {
                tipRestore = PaintTool.BRUSH
                _paintTool.value = PaintTool.ERASER
            }
            com.vibetoon.domain.input.StylusButtons.Start.PICK_COLOR -> { if (x.isFinite() && y.isFinite()) stylusPickColor(x, y); return false }
            com.vibetoon.domain.input.StylusButtons.Start.DRAW -> Unit
        }
        return true
    }

    /** Пипетка кнопкой пера: цвет точки холста под пером становится цветом кисти (как долгое нажатие без инструмента). */
    private fun stylusPickColor(x: Float, y: Float) {
        if (_playing.value || viewW <= 0f || viewH <= 0f || x !in 0f..viewW || y !in 0f..viewH) return
        val view = RenderHost.view ?: return
        val pid = projectId
        view.sampleColor(x.toInt(), y.toInt()) { color ->
            viewModelScope.launch {
                if (projectId == pid && RenderHost.view === view && !closing) {
                    setBrushColor(color)
                    _pickedColor.tryEmit(color.toLong() and 0xFFFFFFFFL)
                    _state.value = UiState.Message("Цвет взят кнопкой пера")
                }
            }
        }
    }

    /** Конец штриха обратным концом пера: инструмент возвращается к кисти. */
    fun stylusTipEnd() {
        val restore = tipRestore ?: return
        tipRestore = null
        if (_paintTool.value == PaintTool.ERASER) _paintTool.value = restore
    }

    // Спринт 40: наклон пера меняет кисть (TiltMath): толщина, штриховка или плоское перо
    private var tiltSettings = com.vibetoon.domain.input.TiltMath.Settings()
    private var tiltNorm = Float.NaN
    private var tiltOrientation = Float.NaN

    /** Наклон и направление пера текущего события (StylusTracker); [first] - начало штриха (сброс сглаживания). */
    fun paintTilt(tiltRadians: Float, orientationRadians: Float, first: Boolean = false) {
        val t = com.vibetoon.domain.input.TiltMath.norm(tiltRadians)
        tiltNorm = if (first) t else com.vibetoon.domain.input.TiltMath.smooth(tiltNorm, t)
        if (orientationRadians.isFinite()) tiltOrientation = orientationRadians
    }

    /** Наклон действует только на штрих пером с включённым режимом; палец и мышь - без изменений. */
    private fun tiltActive(): Boolean = strokeStylus && tiltSettings.active && tiltNorm.isFinite()

    /** Множитель радиуса для текущего наклона (векторная кисть «По нажиму» тоже его учитывает). */
    private fun tiltSizeFactor(): Float = if (tiltActive()) com.vibetoon.domain.input.TiltMath.sizeFactor(tiltSettings, tiltNorm) else 1f

    /** Передаёт текущий наклон штамповщику (и зеркальным штрихам симметрии - без поворота). */
    private fun applyTilt(st: DabStroker?) {
        if (st == null) return
        if (!tiltActive()) { st.setTilt(1f, 1f, Float.NaN); return }
        val ts = tiltSettings
        st.setTilt(com.vibetoon.domain.input.TiltMath.sizeFactor(ts, tiltNorm), com.vibetoon.domain.input.TiltMath.flowFactor(ts, tiltNorm),
            com.vibetoon.domain.input.TiltMath.angle(ts, tiltOrientation, Math.toRadians(camera.rotDeg.toDouble()).toFloat(), tiltNorm))
        for (m in mirrorStrokers) m.setTilt(com.vibetoon.domain.input.TiltMath.sizeFactor(ts, tiltNorm), com.vibetoon.domain.input.TiltMath.flowFactor(ts, tiltNorm), Float.NaN)
    }

    /** Плоское перо: штамп сплющивается по наклону в начале штриха (форма штампа задаётся на весь штрих). */
    private fun tiltParams(p: BrushParams): BrushParams =
        if (tiltActive() && tiltSettings.mode == com.vibetoon.domain.input.TiltMath.Mode.ANGLE)
            p.copy(roundness = com.vibetoon.domain.input.TiltMath.roundness(tiltSettings, tiltNorm, p.roundness)) else p

    /** Начало штриха: нажим проходит через кривую, точка - через стабилизатор (до направляющих и симметрии). */
    fun paintDown(sx: Float, sy: Float, pressure: Float, stylus: Boolean = stylusDown): Boolean {
        val prefs = runCatching { stylusPrefs() }.getOrNull()
        // спринт 44: тип штриха - от указателя, начавшего штрих (а не «перо где-то касается»)
        strokeStylus = stylus
        tiltSettings = com.vibetoon.domain.input.TiltMath.settings(prefs?.getInt(com.vibetoon.domain.input.TiltMath.PREF_MODE, 0) ?: 0,
            prefs?.getInt(com.vibetoon.domain.input.TiltMath.PREF_STRENGTH, com.vibetoon.domain.input.TiltMath.DEFAULT_STRENGTH) ?: com.vibetoon.domain.input.TiltMath.DEFAULT_STRENGTH)
        if (!strokeStylus) { tiltNorm = Float.NaN; tiltOrientation = Float.NaN }
        strokeCurve = com.vibetoon.domain.input.PressureCurve.decode(prefs?.getString("pressure_curve", null))
        val level = prefs?.getInt("stabilization", com.vibetoon.domain.input.StrokeStabilizer.DEFAULT_LEVEL) ?: com.vibetoon.domain.input.StrokeStabilizer.DEFAULT_LEVEL
        val density = getApplication<Application>().resources.displayMetrics.density
        val p = curvedPressure(pressure)
        val ok = paintDownCore(sx, sy, p)
        // направляющие ручки, линейка и градиент работают по точным точкам - без стабилизации
        stabilizer = if (ok && guideDrag == null && rulerQuad == null && level > com.vibetoon.domain.input.StrokeStabilizer.MIN_LEVEL)
            com.vibetoon.domain.input.StrokeStabilizer(level, density).also { it.begin(sx, sy, p) } else null
        if (!ok) _livePressure.value = null
        return ok
    }

    /** Следующая (в том числе историческая) точка штриха: стабилизатор может выдать несколько точек или ни одной. */
    fun paintMove(sx: Float, sy: Float, pressure: Float) {
        val p = curvedPressure(pressure)
        val st = stabilizer
        if (st == null) { paintMoveCore(sx, sy, p); return }
        for (s in st.add(sx, sy, p)) paintMoveCore(s.x, s.y, s.pressure)
    }

    /** Конец штриха: стабилизатор догоняет хвост до точки отпускания, затем штрих запекается. */
    fun paintUp() {
        stabilizer?.let { st -> for (s in st.finish()) paintMoveCore(s.x, s.y, s.pressure) }
        stabilizer = null
        _livePressure.value = null
        paintUpCore()
    }

    /** Слой, на котором рисуем: выбранная картинка (в том числе созданная кнопкой «Новый слой») или слой PSD; текст и видео - нет. */
    /** Снимок настроек пиксельных режимов из SharedPreferences (CompositionLocal во ViewModel недоступен). */
    private class PixelModes(val vectorWorkflow: Boolean, val autoPixelEdit: Boolean, val onlyEraseSelectedPaths: Boolean,
                             val temporaryPivots: Boolean, val pivotEditing: Boolean, val rotateCanvas: Boolean)
    private fun appSettings(): PixelModes? = runCatching {
        val p = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)
        PixelModes(p.getBoolean("vector_workflow", false), p.getBoolean("auto_pixel_edit", false),
            p.getBoolean("only_erase_selected_paths", false), p.getBoolean("temporary_pivots", false),
            p.getBoolean("pivot_editing", true), p.getBoolean("rotate_canvas_2f", true))
    }.getOrNull()

    /** Авто-режим пиксельного редактирования: при выборе кисти/ластика слой выбирается или создаётся сам. */
    private fun ensurePixelTarget(): Int? {
        val st = appSettings() ?: return paintTargetId()
        if (!st.autoPixelEdit) return paintTargetId()
        val current = paintTargetId()
        if (current != null) return current
        // векторный режим: кисть на векторном слое - выбираем/создаём векторный
        if (st.vectorWorkflow && _paintTool.value == PaintTool.BRUSH) {
            val vec = media.firstOrNull { it.vector && it.kind == MediaKind.IMAGE }
            if (vec != null) { selectLayer(vec.id); return null }
            addVectorLayer()
            return null
        }
        // пиксельный режим: верхний растровый слой или новый
        val raster = media.lastOrNull { it.kind == MediaKind.IMAGE && !it.vector }
        if (raster != null) { selectLayer(raster.id); return raster.id }
        addBlankLayer("Слой")
        return null
    }

    /** «Стирать/размазывать только выбранные слои-контуры»: ластик и смаз действуют только на выбранных векторных слоях. */
    private fun onlyPathLayers(): Boolean = appSettings()?.onlyEraseSelectedPaths == true

    private fun paintTargetId(): Int? {
        val t = warpTarget() ?: return null
        if (isLockedId(t)) return null   // замок: по слою рисовать нельзя
        val m = media.firstOrNull { it.id == t }
        if (m != null) return if (m.kind == MediaKind.IMAGE && !m.vector) t else null
        return if (t < MEDIA_ID_BASE) t else null
    }

    private fun requestPaintSize(id: Int) {
        if (paintSizes.containsKey(id)) return
        RenderHost.view?.layerSize(id) { s -> if (s != null && s.size >= 2) paintSizes[id] = s }
    }

    private fun sendDabs(d: FloatArray) {
        if (d.isEmpty()) return
        RenderHost.view?.paintDabs(d, d.size / DabStroker.STRIDE)
    }

    private fun updatePaintCursor(sx: Float, sy: Float, q: FloatArray, params: BrushParams) {
        val w = strokeSize[0].coerceAtLeast(1)
        val ex = toScreen(q[2], q[3]).first - toScreen(q[0], q[1]).first; val ey = toScreen(q[2], q[3]).second - toScreen(q[0], q[1]).second
        val scale = kotlin.math.hypot(ex, ey) / w
        _paintCursor.value = floatArrayOf(sx, sy, (params.size / 2f * scale).coerceAtLeast(2f))
    }

    /** Наведение пера: только отображение контура, без создания штриха или изменения слоя. */
    fun paintHover(sx: Float, sy: Float) {
        if (stroker != null || vecLayer >= 0 || !paintToolActive()) return
        val id = paintTargetId() ?: run { clearPaintHover(); return }
        val size = paintSizes[id] ?: run { requestPaintSize(id); clearPaintHover(); return }
        val q = scene?.selectionQuads?.get(id) ?: run { clearPaintHover(); return }
        val unit = unitOf(q, toCanvas(sx, sy)) ?: run { clearPaintHover(); return }
        if (unit.first !in 0f..1f || unit.second !in 0f..1f) { clearPaintHover(); return }
        val ex = toScreen(q[2], q[3]).first - toScreen(q[0], q[1]).first; val ey = toScreen(q[2], q[3]).second - toScreen(q[0], q[1]).second
        val radius = com.vibetoon.domain.input.EditorInputPolicy.cursorRadius(paintParams().size, kotlin.math.hypot(ex, ey), size[0])
        _paintCursor.value = floatArrayOf(sx, sy, radius)
    }

    fun clearPaintHover() { if (stroker == null && vecLayer < 0) _paintCursor.value = null }

    /** Начало штриха; false - рисовать нечем (нет подходящего слоя) и жест идёт обычным путём. */
    private fun paintDownCore(sx: Float, sy: Float, pressure: Float): Boolean {
        swallowTap = false
        if (_paintTool.value == null || _playing.value) return false
        guideDrag = null
        if (_guideEdit.value && _guides.value.active) {
            val c0 = toCanvas(sx, sy)
            val c1 = toCanvas(sx + 28f * getApplication<Application>().resources.displayMetrics.density, sy)
            val radius = kotlin.math.hypot(c1.first - c0.first, c1.second - c0.second)
            val handle = com.vibetoon.domain.paint.DrawingGuides.hit(_guides.value, com.vibetoon.domain.paint.DrawingGuides.Point(c0.first, c0.second), radius)
            if (handle != null) { guideDrag = handle; swallowTap = true; return true }
        }
        selectedVectorId()?.let { vid ->
            if (_gradientMode.value || _rulerMode.value != com.vibetoon.domain.paint.RulerPath.Mode.OFF) {
                _state.value = UiState.Message("Направляющие и градиент работают только на растровом слое"); return false
            }
            if (isLockedId(vid)) { lockedNotice(); return false }
            if (_paintTool.value == PaintTool.ERASER) { _state.value = UiState.Message("На векторном слое ластика нет: «Убрать последнюю» удаляет примитив"); return false }
            return vectorDown(vid, sx, sy, pressure)
        }
        val id = ensurePixelTarget() ?: run {
            _state.value = UiState.Message("Рисовать можно на слое-картинке: выберите его или создайте «Новый слой»")
            return false
        }
        val size = paintSizes[id] ?: run {
            requestPaintSize(id)
            _state.value = UiState.Message("Слой готовится, повторите мазок")
            return false
        }
        if (_gradientMode.value && (id in warpLayers || id in meshLayers || id in perspLayers || id in skinLayers || id in denseWarps ||
                groupDeformationAnchors.keys.any { group -> (group in warpLayers || group in perspLayers) && id in symbolMembers(group) } ||
                size[0].toLong() * size[1] > 4194304L)) {
            _state.value = UiState.Message("Градиент недоступен для деформированного слоя или текстуры больше 4 Мп")
            return false
        }
        val q = scene?.selectionQuads?.get(id) ?: return false
        val view = RenderHost.view ?: return false
        val p = unitOf(q, toCanvas(sx, sy)) ?: return false
        val params = tiltParams(paintParams())   // спринт 40: плоское перо сплющивает штамп по наклону
        strokeLayer = id
        strokeSize = size
        clearShapeAssist()
        clearRulerGesture()
        guideStroke = null; mirrorStrokers = emptyList()
        val guideState = _guides.value
        val guided = !_gradientMode.value && _rulerMode.value == com.vibetoon.domain.paint.RulerPath.Mode.OFF &&
            (guideState.snapping || guideState.symmetry != com.vibetoon.domain.paint.DrawingGuides.Symmetry.OFF)
        if (_gradientMode.value || _rulerMode.value != com.vibetoon.domain.paint.RulerPath.Mode.OFF) {
            rulerQuad = q.copyOf(); rulerCamera = camera; rulerViewW = viewW; rulerViewH = viewH
            rulerParams = params; rulerPressure = pressure
            _rulerGuide.value = listOf(sx, sy, sx, sy)
        }
        if (!guided && !_gradientMode.value && _rulerMode.value == com.vibetoon.domain.paint.RulerPath.Mode.OFF && _paintTool.value == PaintTool.BRUSH && getApplication<Application>()
                .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("shape_assist", false)) {
            assistPoints = arrayListOf(com.vibetoon.domain.paint.ShapeAssist.Point(sx, sy, pressure))
            assistAnchorX = sx; assistAnchorY = sy; assistLastMove = SystemClock.uptimeMillis()
            assistQuad = q.copyOf(); assistParams = params; assistCamera = camera; assistViewW = viewW; assistViewH = viewH
        }
        val st = DabStroker(params)
        stroker = st
        val c = params.color
        view.paintBegin(
            id, if (_gradientMode.value) 0 else params.mode.gl, params.opacity, layerProps[id]?.alphaLock == true || (_gradientMode.value && _gradientAlpha.value),
            ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, params.shape.gl, params.hardness,
            params.grain.gl, params.grainMode.gl, params.grainScale, params.grainContrast, params.roundness, params.curve, GrainMath.texture(params.grain)
        ) { }
        if (guided) {
            // Sprint 33: притяжение к направляющей и зеркальные штрихи в одной операции Undo
            val density = getApplication<Application>().resources.displayMetrics.density
            val c0 = toCanvas(sx, sy); val lockAt = toCanvas(sx + 12f * density, sy); val stepAt = toCanvas(sx + 2f, sy)
            val gs = com.vibetoon.domain.paint.DrawingGuides.Stroke(guideState,
                kotlin.math.hypot(lockAt.first - c0.first, lockAt.second - c0.second),
                kotlin.math.hypot(stepAt.first - c0.first, stepAt.second - c0.second))
            guideStroke = gs
            mirrorStrokers = List(gs.count - 1) { DabStroker(params) }
            applyTilt(st)
            emitGuided(gs.begin(com.vibetoon.domain.paint.DrawingGuides.Point(c0.first, c0.second)), q, size, pressure)
        } else if (!_gradientMode.value && _rulerMode.value == com.vibetoon.domain.paint.RulerPath.Mode.OFF) {
            applyTilt(st)
            sendDabs(st.begin(p.first * size[0], p.second * size[1], pressure))
        }
        updatePaintCursor(sx, sy, q, params)
        swallowTap = true
        return true
    }

    /** Следующая точка штриха после стабилизации. */
    private fun paintMoveCore(sx: Float, sy: Float, pressure: Float) {
        guideDrag?.let { handle ->
            val c = toCanvas(sx, sy)
            _guides.value = com.vibetoon.domain.paint.DrawingGuides.moveHandle(_guides.value, handle, com.vibetoon.domain.paint.DrawingGuides.Point(c.first, c.second))
            return
        }
        if (vecLayer >= 0) { vectorMove(sx, sy, pressure); return }
        val st = stroker ?: return
        applyTilt(st)
        val q = scene?.selectionQuads?.get(strokeLayer) ?: return
        val p = unitOf(q, toCanvas(sx, sy)) ?: return
        if (rulerQuad != null) {
            val g = _rulerGuide.value
            if (g.size == 4) _rulerGuide.value = listOf(g[0], g[1], sx, sy)
            return
        }
        guideStroke?.let { gs ->
            val c = toCanvas(sx, sy)
            emitGuided(gs.move(com.vibetoon.domain.paint.DrawingGuides.Point(c.first, c.second)), q, strokeSize, pressure)
            updatePaintCursor(sx, sy, q, paintParams())
            return
        }
        recordShapePoint(sx, sy, pressure)
        sendDabs(st.add(p.first * strokeSize[0], p.second * strokeSize[1], pressure))
        updatePaintCursor(sx, sy, q, paintParams())
    }

    /** Конец штриха: слой запекается, "до" и "после" уходят в Undo (сжатие в фоне), слой помечается на сохранение. */
    private fun paintUpCore() {
        if (guideDrag != null) { guideDrag = null; saveGuides(); return }
        if (vecLayer >= 0) { vectorUp(); return }
        val original = stroker ?: return
        val layer = strokeLayer
        val view = RenderHost.view
        if (rulerQuad != null) {
            val q = rulerQuad!!; val guide = _rulerGuide.value; val params = rulerParams
            val valid = guide.size == 4 && params != null && view != null && paintTargetId() == layer &&
                camera == rulerCamera && viewW == rulerViewW && viewH == rulerViewH && scene?.selectionQuads?.get(layer)?.contentEquals(q) == true
            if (_gradientMode.value) {
                if (!valid || guide.any { !it.isFinite() } || kotlin.math.hypot(guide[2] - guide[0], guide[3] - guide[1]) < 2f) { paintCancel(); return }
                val screenQuad = FloatArray(8) { index -> if (index % 2 == 0) toScreen(q[index], q[index + 1]).first else toScreen(q[index - 1], q[index]).second }
                val geometry = com.vibetoon.domain.paint.GradientGeometry.create(screenQuad, guide[0], guide[1], guide[2], guide[3])
                    ?: run { paintCancel(); return }
                clearRulerGesture(); clearShapeAssist(); stroker = null; _paintCursor.value = null
                val cellAtStroke = cellShown[layer]
                view!!.paintGradientEnd(layer, geometry) { bytes ->
                    if (bytes == null) { viewModelScope.launch(Dispatchers.Main) { _state.value = UiState.Message("Не удалось применить градиент") }; return@paintGradientEnd }
                    viewModelScope.launch(Dispatchers.Default) {
                        val op = PaintOp.parse(layer, bytes) ?: return@launch
                        op.cellFile = cellAtStroke
                        withContext(Dispatchers.Main) { commitPaintOp(op) }
                        op.compress()
                        withContext(Dispatchers.Main) { enforcePaintBudget() }
                    }
                }
                return
            }
            val path = if (valid) com.vibetoon.domain.paint.RulerPath.path(_rulerMode.value,
                com.vibetoon.domain.paint.RulerPath.Point(guide[0], guide[1]), com.vibetoon.domain.paint.RulerPath.Point(guide[2], guide[3])) else null
            val mapped = path?.map { point -> unitOf(q, toCanvas(point.x, point.y)) }
            if (mapped == null || mapped.any { it == null }) {
                paintCancel(); return
            }
            val st = DabStroker(params!!)
            val first = mapped.first()!!
            sendDabs(st.begin(first.first * strokeSize[0], first.second * strokeSize[1], rulerPressure))
            for (point in mapped.drop(1)) {
                val p = point!!; sendDabs(st.add(p.first * strokeSize[0], p.second * strokeSize[1], rulerPressure))
            }
            sendDabs(st.end())
            // Sprint 33: симметрия повторяет и линию/круг линейки
            val symmetry = _guides.value
            if (symmetry.symmetry != com.vibetoon.domain.paint.DrawingGuides.Symmetry.OFF && path != null) {
                val images = path.map { point -> val c = toCanvas(point.x, point.y); com.vibetoon.domain.paint.DrawingGuides.mirrorImages(symmetry, com.vibetoon.domain.paint.DrawingGuides.Point(c.first, c.second)) }
                for (k in 1 until com.vibetoon.domain.paint.DrawingGuides.mirrorCount(symmetry)) {
                    val mirror = DabStroker(params)
                    for (image in images) {
                        val pt = image.getOrNull(k) ?: continue
                        val u = unitOf(q, pt.x to pt.y) ?: continue
                        sendDabs(mirror.add(u.first * strokeSize[0], u.second * strokeSize[1], rulerPressure))
                    }
                    sendDabs(mirror.end())
                }
            }
            clearRulerGesture(); clearShapeAssist(); stroker = null; _paintCursor.value = null
            val cellAtStroke = cellShown[layer]
            view!!.paintEndSoft(layer) { bytes ->
                if (bytes != null) viewModelScope.launch(Dispatchers.Default) {
                    val op = PaintOp.parse(layer, bytes) ?: return@launch
                    op.cellFile = cellAtStroke
                    withContext(Dispatchers.Main) { commitPaintOp(op) }
                    op.compress()
                    withContext(Dispatchers.Main) { enforcePaintBudget() }
                }
            }
            return
        }
        val st = if (view != null) assistedLine(view, layer) ?: original else original
        clearShapeAssist()
        stroker = null
        _paintCursor.value = null
        val cellAtStroke = cellShown[layer]
        sendDabs(st.end())
        for (mirror in mirrorStrokers) sendDabs(mirror.end())
        mirrorStrokers = emptyList(); guideStroke = null
        RenderHost.view?.paintEndSoft(layer) { bytes ->
            if (bytes != null) viewModelScope.launch(Dispatchers.Default) {
                val op = PaintOp.parse(layer, bytes) ?: return@launch
                op.cellFile = cellAtStroke
                withContext(Dispatchers.Main) { commitPaintOp(op) }
                op.compress()
                withContext(Dispatchers.Main) { enforcePaintBudget() }
            }
        }
    }

    /** Штрих прерван (второй палец, смена инструмента): слой возвращается к состоянию до штриха. */
    fun paintCancel() {
        stabilizer = null; _livePressure.value = null
        if (guideDrag != null) { guideDrag = null; saveGuides(); return }
        if (vecLayer >= 0) { vectorCancel(); return }
        if (stroker == null) return
        stroker = null
        guideStroke = null; mirrorStrokers = emptyList()
        clearShapeAssist()
        clearRulerGesture()
        _paintCursor.value = null
        RenderHost.view?.paintEnd(false) { }
    }

    private fun commitPaintOp(op: PaintOp) {
        checkpoint("paint", force = true)
        val s = undoStack.removeLast()
        undoStack.addLast(s.withPaint(op))
        invalidateThumb(op.layerId)
        paintDirty += op.layerId
        markDirty()
    }

    /** Общий бюджет для патчей и полных снимков. Удаляем только непрерывные старые префиксы. */
    private fun enforcePaintBudget() {
        fun bytes(s: Snapshot) = (s.paintOp?.sizeBytes ?: 0L) + (s.textureOp?.sizeBytes ?: 0L)
        val trim = com.vibetoon.domain.paint.HistoryMemoryBudget.trim(undoStack.map(::bytes), redoStack.map(::bytes))
        if (trim.undo == 0 && trim.redo == 0) return
        repeat(trim.undo) { undoStack.removeFirst() }
        repeat(trim.redo) { redoStack.removeFirst() }
        updateHistoryFlags()
        _state.value = UiState.Message("Память Undo для рисунка заполнена: старые штрихи больше не отменяются")
    }

    /** Undo/Redo штриха: прямая перезапись прямоугольника слоя пикселями "до" или "после" (без смешивания). */
    private fun applyPaint(op: PaintOp, useBefore: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val data = if (useBefore) op.before() else op.after()
            if (data == null) { _state.value = UiState.Message("Этот штрих уже нельзя отменить: память Undo освобождена"); return@launch }
            if (!showCellOf(op)) { _state.value = UiState.Message("Рисунок этого штриха удалён: откатывать нечего"); return@launch }
            RenderHost.view?.paintRestore(op.layerId, op.x, op.y, op.w, op.h, data)
            invalidateThumb(op.layerId)
            paintDirty += op.layerId
            withContext(Dispatchers.Main) { markDirty() }
        }
    }

    /**
     * Frame-safe Undo: если штрих нанесён на другой рисунок покадрового слоя, плейхед переходит на кадр этого рисунка
     * и ждёт, пока его текстура окажется на слое. false - рисунка больше нет (кадр удалён).
     */
    private suspend fun showCellOf(op: PaintOp): Boolean {
        val file = op.cellFile ?: return true
        if (cellShown[op.layerId] == file) return true
        val cell = media.firstOrNull { it.id == op.layerId }?.cells?.firstOrNull { it.file == file } ?: return false
        withContext(Dispatchers.Main) { setFrame(cell.start) }
        var waited = 0
        while (cellShown[op.layerId] != file && waited < 4000) { kotlinx.coroutines.delay(25); waited += 25 }
        return cellShown[op.layerId] == file
    }

    /** Файл слоя, куда записывается нарисованное: у картинки это сам файл, у слоя PSD - assets/liq_<id>.png (применяется при открытии). */
    private fun paintFile(pid: String, id: Int): java.io.File? {
        val m = media.firstOrNull { it.id == id }
        if (m != null) return if (m.kind == MediaKind.IMAGE) projects.mediaFile(pid, (cellShown[m.id] ?: m.file).removePrefix("assets/")) else null   // у покадрового слоя - файл показанного рисунка
        return if (id < MEDIA_ID_BASE) projects.mediaFile(pid, "liq_$id.png") else null
    }

    /** Остальные вызовы не должны освобождать paintDirty одновременно с saveNow. */
    private suspend fun flushPaintSavesSafely(): Boolean = rasterTransactionGate.run {
        if (rasterRecoveryRequired) false else flushPaintSaves()
    }

    /** Сохраняет изменённые слои (PNG) из пикселей текстуры; вызывается при каждом автосохранении и при выходе из проекта. */
    private suspend fun flushPaintSaves(): Boolean {
        val pid = projectId ?: return false
        val ids = synchronized(paintDirty) { val c = paintDirty.toList(); paintDirty.clear(); c }
        if (ids.isEmpty()) return true
        lastPaintFailureTransient = true
        val view = RenderHost.view ?: run { paintDirty.addAll(ids); return false }
        var ok = true
        var permanent = false
        try {
            for (id in ids) {
                val file = paintFile(pid, id)
                if (file == null) { paintDirty += id; ok = false; permanent = true; continue }
                // спринт 41: при свёрнутом приложении - пиксели, прочитанные до паузы; иначе чтение без ожидания 4 с
                val bytes = pauseCapture.remove(id) ?: if (view.surfacePaused) null else kotlinx.coroutines.withTimeoutOrNull(4000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c ->
                        view.layerPixels(id) { if (c.isActive) c.resumeWith(Result.success(it)) }
                    }
                }
                if (bytes == null || bytes.size <= 8) {
                    paintDirty += id; ok = false; continue
                }
                val original = media.firstOrNull { it.id == id }
                val versioned = original != null && original.kind == MediaKind.IMAGE && original.cells.isEmpty() &&
                    (id in versionedRasterIds || com.vibetoon.domain.raster.RasterVersionPolicy.isVersionPath(original.file))
                if (versioned && original != null && !com.vibetoon.domain.raster.RasterVersionPolicy.matchesDimensions(bytes, original.srcWidth, original.srcHeight)) {
                    paintDirty += id; ok = false; permanent = true; continue
                }
                val newPath = if (versioned) "assets/raster_${java.util.UUID.randomUUID()}.png" else null
                val destination = if (newPath != null) projects.fileOf(pid, newPath) else file
                val written = withContext(Dispatchers.IO) {
                    try { writeLayerPng(bytes, destination); true }
                    catch (t: Exception) { Log.e("VT/Project", "PNG save failed", t); false }
                }
                if (!written) { paintDirty += id; ok = false; permanent = true; continue }
                cellCache.invalidate(destination)   // спринт 31: в кэше рисунков не остаётся версии до правки
                if (newPath != null) {
                    val index = media.indexOfFirst { it.id == id }
                    if (projectId != pid || index < 0 || media[index] != original) {
                        paintDirty += id; ok = false; continue
                    }
                    media[index] = media[index].copy(file = newPath)
                    versionedRasterIds.add(id)
                }
            }
        } catch (t: Throwable) {
            // Повторно проверяем все снимки, если операция была прервана в середине.
            paintDirty.addAll(ids)
            throw t
        }
        if (!ok) lastPaintFailureTransient = !permanent
        return ok
    }

    /** Пиксели RGBA -> PNG: не уничтожаем исходный файл при ошибке кодирования. */
    private fun writeLayerPng(bytes: ByteArray, file: java.io.File) {
        fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        require(bytes.size >= 8)
        val w = i32(0); val h = i32(4)
        require(w > 0 && h > 0 && w.toLong() * h * 4 + 8 <= bytes.size)
        val px = IntArray(w * h)
        for (k in 0 until w * h) {
            val o = 8 + k * 4
            val a = bytes[o + 3].toInt() and 0xFF
            var r = bytes[o].toInt() and 0xFF; var g = bytes[o + 1].toInt() and 0xFF; var b = bytes[o + 2].toInt() and 0xFF
            if (a in 1..254) { r = minOf(255, r * 255 / a); g = minOf(255, g * 255 / a); b = minOf(255, b * 255 / a) }
            px[k] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val temp = java.io.File(file.parentFile, file.name + "." + java.util.UUID.randomUUID() + ".tmp")
        try {
            temp.outputStream().use { stream ->
                check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
                stream.fd.sync()
            }
            val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(temp.absolutePath, info)
            check(info.outWidth == w && info.outHeight == h && info.outMimeType == "image/png") { "PNG verification failed" }
            check(temp.renameTo(file)) { "PNG replacement failed" }
        } finally { bmp.recycle(); temp.delete() }
    }

    // ---------------------------------------------------------------- свойства слоя (этап 3)

    /** Текущие свойства слоя: правка пользователя или значения из PSD. */
    fun layerPropsOf(id: Int): LayerProps = layerProps[id]
        ?: layers.firstOrNull { it.id == id }?.let { LayerProps(it.opacity, it.blendMode, false, it.isClipped).normalized() }
        ?: LayerProps()

    /** Меняет свойства слоя: Opacity, режим наложения, Alpha Lock, обтравка. Undo склеивает правки одного слоя. */
    fun updateLayerProps(id: Int, change: (LayerProps) -> LayerProps) {
        val cur = layerPropsOf(id)
        val next = change(cur).normalized()
        if (next == cur) return
        checkpoint("lprops$id")
        setProps(layerProps + (id to next))
        rebuildLayers()
        pushScene()
        markDirty()
    }

    /** «Новый слой»: пустая прозрачная картинка размером с холст (не больше 2048 по длинной стороне), на ней можно рисовать. */
    fun addPaintLayer() {
        val pid = projectId ?: return
        val scale = minOf(1f, 2048f / maxOf(canvasW, canvasH))
        val bw = (canvasW * scale).toInt().coerceAtLeast(1)
        val bh = (canvasH * scale).toInt().coerceAtLeast(1)
        val id = nextMediaId()
        val count = media.count { it.name.startsWith("Рисунок") } + 1
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    val fileName = "m${id}_${java.util.UUID.randomUUID()}.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    MediaItem(id, MediaKind.IMAGE, "Рисунок $count", "assets/$fileName", bw, bh, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
                }
                if (projectId != pid || closing) return@launch
                checkpoint("add", force = true)
                media += item
                rebuildLayers()
                pushScene()
                selectLayer(item.id)
                markDirty()
                _state.value = UiState.Message("Новый слой «${item.name}»: рисуйте кистью")
                delay(400)
                requestPaintSize(item.id)
            } catch (t: Throwable) {
                Log.e("VT/Paint", "add paint layer failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось создать слой")
            }
        }
    }

    // ---------------------------------------------------------------- слои меню «＋»: анимационный, векторный, группа, фон

    /** Пустой растровый слой на весь холст (прозрачный или залитый [fillArgb]); [animated] сразу даёт покадровую структуру, [vector] - слой примитивов. */
    private fun addBlankLayer(prefix: String, vector: Boolean = false, animated: Boolean = false, fillArgb: Int = 0, atBottom: Boolean = false, lock: Boolean = false) {
        val pid = projectId ?: return
        val scale = minOf(1f, (if (vector) 1536f else 2048f) / maxOf(canvasW, canvasH))
        val bw = (canvasW * scale).toInt().coerceAtLeast(1)
        val bh = (canvasH * scale).toInt().coerceAtLeast(1)
        val id = nextMediaId()
        val shown = dn(prefix)   // спринт 30: «Слой 2» / «Layer 2» на языке приложения; счёт учитывает оба варианта
        val count = media.count { it.name.startsWith(prefix) || it.name.startsWith(shown) } + 1
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    if (fillArgb != 0) bmp.eraseColor(fillArgb)
                    val fileName = "m${id}_${java.util.UUID.randomUUID()}.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    MediaItem(id, MediaKind.IMAGE, "$shown $count", "assets/$fileName", bw, bh, 0f, 0f, canvasW.toFloat(), canvasH.toFloat(),
                        cells = if (animated) listOf(FrameCell(0, "assets/$fileName")) else emptyList(), vector = vector, background = atBottom)
                }
                if (projectId != pid || closing) return@launch
                checkpoint("add", force = true)
                if (atBottom) media.add(0, item) else media += item
                rebuildLayers()
                // пакет 25: фон - под всеми слоями, и под слоями PSD тоже (явный порядок корня)
                if (atBottom && (layers.any { it.parentId == -1 && it.id < MEDIA_ID_BASE } || stackOrder.containsKey(-1))) {
                    stackOrder = com.vibetoon.domain.model.StackOrder.bottom(layers, stackOrder, item.id)
                    rebuildLayers()
                }
                pushScene(); selectLayer(item.id); markDirty()
                if (lock) toggleLocked(item.id)
                _state.value = UiState.Message(when {
                    vector -> "Векторный слой «${item.name}»: рисуйте кистью (путь, линия, прямоугольник, эллипс) или добавляйте «Фигуры»"
                    animated -> "Анимационный слой «${item.name}»: рисуйте, «Добавить пустой кадр» ставит следующий рисунок"
                    lock -> "Фон «${item.name}» залит цветом фона проекта и заблокирован (замок снимается в списке слоёв)"
                    else -> "Новый слой «${item.name}»"
                })
                delay(400)
                requestPaintSize(item.id)
            } catch (t: Throwable) {
                Log.e("VT/Layers", "add layer failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось создать слой")
            }
        }
    }

    fun addAnimationLayer() = addBlankLayer("Анимация", animated = true)
    fun addVectorLayer() = addBlankLayer("Вектор", vector = true)
    /** Фон: сплошная заливка цветом фона проекта (прозрачный фон - белым), на самом дне и под замком. */
    fun addBackgroundLayer() {
        val bg = meta?.background ?: 0L
        addBlankLayer("Фон", fillArgb = if ((bg ushr 24) == 0L) 0xFFFFFFFF.toInt() else bg.toInt(), atBottom = true, lock = true)
    }

    /** Пустая группа: контейнер, в который слои переносятся ручкой ≡ (бросок слоя на группу). */
    fun addEmptyGroup() {
        if (projectId == null || _editingSymbol.value != null) return
        val id = nextMediaId()
        val n = media.count { it.kind == MediaKind.GROUP && it.symDur == 0 } + 1
        checkpoint("group", force = true)
        media += MediaItem(id, MediaKind.GROUP, dn("Группа $n"), "")
        rebuildLayers(); pushScene(); selectLayer(id); markDirty()
        _state.value = UiState.Message("Пустая группа «Группа $n»: перетащите в неё слои ручкой ≡")
    }

    // ---------------------------------------------------------------- векторный слой

    private val _vecKind = MutableStateFlow(com.vibetoon.domain.vector.VecKind.PATH)
    /** Что рисует жест на векторном слое: путь, линия, прямоугольник, эллипс. */
    val vecKind: StateFlow<com.vibetoon.domain.vector.VecKind> = _vecKind.asStateFlow()
    private val _vecFill = MutableStateFlow(false)
    val vecFill: StateFlow<Boolean> = _vecFill.asStateFlow()
    fun setVecKind(k: com.vibetoon.domain.vector.VecKind) { _vecKind.value = k }
    /** Спринт 40: векторная кисть для «Путь» (ровная, по нажиму, тушь, каллиграфия); выбор хранится в vt_app. */
    private val _vecBrush = MutableStateFlow(com.vibetoon.domain.vector.VectorBrush.Style.of(
        runCatching { getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getString(com.vibetoon.domain.vector.VectorBrush.PREF, null) }.getOrNull()))
    val vecBrush: StateFlow<com.vibetoon.domain.vector.VectorBrush.Style> = _vecBrush.asStateFlow()
    fun setVecBrush(style: com.vibetoon.domain.vector.VectorBrush.Style) {
        _vecBrush.value = style
        runCatching { getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).edit().putString(com.vibetoon.domain.vector.VectorBrush.PREF, style.id).apply() }
    }

    /**
     * Спринт 40: «Импорт копии» настроек пишет в vt_app, пока редактор открыт. Слушатель сразу обновляет вид панели слоёв
     * («Миниатюры»/«Список»), векторную кисть и свои кисти - без повторного открытия редактора.
     */
    private val appPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onAppPrefChanged(key) }
    init { runCatching { appPrefs().registerOnSharedPreferenceChangeListener(appPrefsListener) } }

    private fun onAppPrefChanged(key: String?) {
        val p = runCatching { appPrefs() }.getOrNull() ?: return
        if (key == null || key == com.vibetoon.domain.model.LayerListView.PREF_KEY) {
            val list = com.vibetoon.domain.model.LayerListView.isList(p.getString(com.vibetoon.domain.model.LayerListView.PREF_KEY, null))
            if (_layersList.value != list) { _layersList.value = list; rebuildRows() }
        }
        if (key == null || key == com.vibetoon.domain.vector.VectorBrush.PREF) {
            val vb = com.vibetoon.domain.vector.VectorBrush.Style.of(p.getString(com.vibetoon.domain.vector.VectorBrush.PREF, null))
            if (_vecBrush.value != vb) _vecBrush.value = vb
        }
        if ((key == null || key == com.vibetoon.domain.paint.BrushExchange.PREF_KEY) && customBrushesLoaded &&
            p.getString(com.vibetoon.domain.paint.BrushExchange.PREF_KEY, null) != ownBrushesJson) reloadCustomBrushes()
    }
    fun toggleVecFill() { _vecFill.value = !_vecFill.value }

    fun isVectorLayer(id: Int) = media.any { it.id == id && it.vector }
    private fun selectedVectorId(): Int? = _selected.value?.takeIf { isVectorLayer(it) }
    /** Число примитивов выбранного векторного слоя (для кнопок «Убрать последнюю»/«Очистить»). */
    fun vectorCount(id: Int) = media.firstOrNull { it.id == id }?.vectors?.size ?: 0

    private var vecLayer = -1
    private val vecPts = ArrayList<Float>()
    /** Нажим (после кривой, с наклоном) по точкам жеста - для векторной кисти. */
    private val vecPressures = ArrayList<Float>()
    private var vecPreviewAt = 0L
    private val vecGen = HashMap<Int, Int>()

    private fun vecStroke(): Long = (brush.value.color.toInt() or (0xFF shl 24)).toLong() and 0xFFFFFFFFL

    /** Штрих-примитив жеста: «Путь» - через векторную кисть (толщина по узлам), остальные виды - как раньше. */
    private fun vecShape(): com.vibetoon.domain.vector.VectorShape? {
        val fill = if (_vecFill.value) vecStroke() and 0x00FFFFFFL or 0x66000000L else 0L
        return if (_vecKind.value == com.vibetoon.domain.vector.VecKind.PATH)
            com.vibetoon.domain.vector.VectorBrush.stroke(_vecBrush.value, vecPts.toList(), vecPressures.toList(), vecStroke(), fill, brush.value.size)
        else com.vibetoon.domain.vector.VectorMath.fromGesture(_vecKind.value, vecPts.toList(), vecStroke(), fill, brush.value.size)
    }

    private fun vectorDown(id: Int, sx: Float, sy: Float, pressure: Float = 1f): Boolean {
        val q = scene?.selectionQuads?.get(id) ?: return false
        val p = unitOf(q, toCanvas(sx, sy)) ?: return false
        vecLayer = id
        vecPts.clear(); vecPts += p.first * canvasW; vecPts += p.second * canvasH
        vecPressures.clear(); vecPressures += pressure * tiltSizeFactor()
        swallowTap = true
        return true
    }

    private fun vectorMove(sx: Float, sy: Float, pressure: Float = 1f) {
        val q = scene?.selectionQuads?.get(vecLayer) ?: return
        val p = unitOf(q, toCanvas(sx, sy)) ?: return
        vecPts += p.first * canvasW; vecPts += p.second * canvasH
        vecPressures += pressure * tiltSizeFactor()
        val now = SystemClock.uptimeMillis()
        if (now - vecPreviewAt < 70) return
        vecPreviewAt = now
        val m = media.firstOrNull { it.id == vecLayer } ?: return
        val tmp = vecShape()
        renderVector(m, save = false, extra = tmp)   // резиновая линия: примитив виден во время жеста
    }

    private fun vectorUp() {
        val id = vecLayer
        vecLayer = -1
        val m = media.firstOrNull { it.id == id } ?: return
        val shape = vecShape()   // спринт 40: векторная кисть; правка идёт в Undo через setVectors -> checkpoint
        vecPts.clear(); vecPressures.clear()
        if (shape == null) { renderVector(m, save = false); return }   // тап без движения: убрать резиновую линию
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, shape))
    }

    private fun vectorCancel() {
        val id = vecLayer
        vecLayer = -1; vecPts.clear(); vecPressures.clear()
        media.firstOrNull { it.id == id }?.let { renderVector(it, save = false) }
    }

    private fun setVectors(id: Int, list: List<com.vibetoon.domain.vector.VectorShape>) {
        val i = media.indexOfFirst { it.id == id }
        if (i < 0) return
        checkpoint("vec", force = true)
        media[i] = media[i].copy(vectors = list)
        renderVector(media[i], save = true)
        markDirty()
        invalidateThumb(id)
    }

    fun removeLastVector() {
        val id = selectedVectorId() ?: return
        if (isLockedId(id)) { lockedNotice(); return }
        val m = media.firstOrNull { it.id == id } ?: return
        if (m.vectors.isEmpty()) return
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withoutLast(m.vectors))
    }

    fun clearVectors() {
        val id = selectedVectorId() ?: return
        if (isLockedId(id)) { lockedNotice(); return }
        if (vectorCount(id) == 0) return
        setVectors(id, emptyList())
    }

    /** Фигура-пресет («Фигуры» при выбранном векторном слое): добавляется примитивом по центру холста, дальше её можно убрать/очистить. */
    private fun addVectorPreset(id: Int, kind: com.vibetoon.domain.paint.ShapeKind) {
        val m = media.firstOrNull { it.id == id } ?: return
        val side = (minOf(canvasW, canvasH) * 0.4f).coerceAtLeast(64f)
        val c = vecStroke()
        val s = com.vibetoon.domain.vector.VectorMath.preset(kind.name, canvasW / 2f, canvasH / 2f, side, c, if (_vecFill.value) (c and 0x00FFFFFFL) or 0xFF000000L else 0L, brush.value.size)
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, s))
        _state.value = UiState.Message("Фигура «${kind.title}» добавлена в векторный слой")
    }

    /** Перерисовывает векторный слой из примитивов: [save] - ещё и в файл слоя (PNG), [extra] - временный примитив жеста. */
    private fun renderVector(m: MediaItem, save: Boolean, extra: com.vibetoon.domain.vector.VectorShape? = null) {
        val pid = projectId ?: return
        val gen = (vecGen[m.id] ?: 0) + 1
        vecGen[m.id] = gen
        val shapes = if (extra != null) m.vectors + extra else m.vectors
        val cw = canvasW; val ch = canvasH
        val ticket = RenderNative.textureUploadTicket(m.id)
        viewModelScope.launch {
            try {
                withContext(decodeDispatcher) {
                    if (vecGen[m.id] != gen) return@withContext   // за время ожидания пришла более новая правка
                    val bmp = com.vibetoon.data.VectorRaster.render(shapes, m.srcWidth, m.srcHeight, cw, ch)
                    if (vecGen[m.id] == gen) {
                        if (save) projects.mediaFile(pid, m.file.removePrefix("assets/")).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        RenderNative.uploadBitmapIfCurrent(m.id, bmp, ticket)
                    }
                    bmp.recycle()
                }
                if (save) invalidateThumb(m.id)
                RenderHost.view?.requestRender()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w("VT/Vector", "vector layer not drawn: ${t.message}")
            }
        }
    }

    // ---------------------------------------------------------------- зацикливание и магнит таймлайна

    private val _loop = MutableStateFlow(true)
    /** Зацикливание воспроизведения (хранится в проекте). */
    val loop: StateFlow<Boolean> = _loop.asStateFlow()
    private val _magnet = MutableStateFlow(false)
    /** Магнит: плейхед прилипает к ключам, маркерам и границам клипов (хранится в проекте). */
    val magnet: StateFlow<Boolean> = _magnet.asStateFlow()

    fun toggleLoop() {
        _loop.value = !_loop.value
        meta = meta?.copy(loop = _loop.value)
        markDirty()
        _state.value = UiState.Message(if (_loop.value) "Цикл включён: после конца проигрывание идёт с начала" else "Цикл выключен: проигрывание остановится на последнем кадре")
    }

    fun toggleMagnet() {
        _magnet.value = !_magnet.value
        meta = meta?.copy(magnet = _magnet.value)
        markDirty()
        _state.value = UiState.Message(if (_magnet.value) "Магнит включён: плейхед прилипает к ключам, маркерам и краям клипов" else "Магнит выключен")
    }

    /** Кадр после магнита: к ключам и клипам выбранных слоёв, маркерам, началу и концу проекта в пределах [threshold] кадров. */
    fun snapFrame(f: Int, threshold: Int): Int {
        if (!_magnet.value) return f
        val t = HashSet<Int>()
        t += 0; t += duration() - 1
        t += _markers.value
        for (id in _selection.value.ifEmpty { setOfNotNull(_selected.value) }) {
            _clips.value[id]?.let { t += it.start; t += it.end - 1 }
            _animation.value.tracks[id]?.keys?.forEach { t += it.frame }
        }
        return com.vibetoon.domain.anim.SnapMath.snap(f, t, threshold)
    }

    /** Фигура: пресет растеризуется текущим цветом кисти в новый слой по центру холста; дальше как любая картинка (Warp, Mesh, кости). */
    fun addShape(kind: com.vibetoon.domain.paint.ShapeKind, argbOverride: Int? = null, dxOverride: Float = 0f) {
        if (argbOverride == null) selectedVectorId()?.let { if (!isLockedId(it)) { addVectorPreset(it, kind); return } }
        val pid = projectId ?: return
        val id = nextMediaId()
        val argb = argbOverride ?: brush.value.color.toInt()
        val side = (minOf(canvasW, canvasH) * 0.4f).coerceAtLeast(64f)
        val ticket = RenderNative.textureUploadTicket(id)
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val n = 512
                    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
                    val cv = android.graphics.Canvas(bmp)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = argb or (0xFF shl 24); style = android.graphics.Paint.Style.FILL }
                    for (poly in com.vibetoon.domain.paint.ShapeMath.polygons(kind)) {
                        val path = android.graphics.Path()
                        for (i in poly.indices step 2) if (i == 0) path.moveTo(poly[0] * n, poly[1] * n) else path.lineTo(poly[i] * n, poly[i + 1] * n)
                        path.close(); cv.drawPath(path, paint)
                    }
                    val fileName = "m$id.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmapIfCurrent(id, bmp, ticket)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать фигуру в графике")
                    MediaItem(id, MediaKind.IMAGE, "Фигура: ${kind.title}", "assets/$fileName", n, n,
                        (canvasW - side) / 2f + dxOverride, (canvasH - side) / 2f, side, side)
                }
                if (projectId != pid || closing) return@launch
                checkpoint("add", force = true)
                media += item
                rebuildLayers(); pushScene(); selectLayer(item.id); markDirty()
                _state.value = UiState.Message("Фигура «${kind.title}» добавлена")
            } catch (t: Throwable) {
                Log.e("VT/Shape", "add shape failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось добавить фигуру")
            }
        }
    }

    // ---------------------------------------------------------------- растровые инструменты: заливка, пипетка, выделение, смазка, перо

    private val _rasterTool = MutableStateFlow<RasterTool?>(null)
    val rasterTool: StateFlow<RasterTool?> = _rasterTool.asStateFlow()
    private val _selMode = MutableStateFlow(SelMode.RECT)
    val selMode: StateFlow<SelMode> = _selMode.asStateFlow()
    private val _fillParams = MutableStateFlow(FillParams())
    val fillParams: StateFlow<FillParams> = _fillParams.asStateFlow()
    private val _smudgeParams = MutableStateFlow(SmudgeParams())
    val smudgeParams: StateFlow<SmudgeParams> = _smudgeParams.asStateFlow()
    private val _toolDrag = MutableStateFlow<ToolDrag?>(null)
    val toolDrag: StateFlow<ToolDrag?> = _toolDrag.asStateFlow()
    private val _selOverlay = MutableStateFlow(SelOverlay())
    val selOverlay: StateFlow<SelOverlay> = _selOverlay.asStateFlow()
    private val _hasSel = MutableStateFlow(false)
    /** Есть ли выделение (включает кнопки «Очистить / Инверсия / Снять»). */
    val hasSel: StateFlow<Boolean> = _hasSel.asStateFlow()
    private val _penCount = MutableStateFlow(0)
    val penCount: StateFlow<Int> = _penCount.asStateFlow()
    private val _pickedColor = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 4)
    /** Цвет, выбранный пипеткой на холсте (ARGB): интерфейс подставляет его в основной цвет. */
    val pickedColor: kotlinx.coroutines.flow.SharedFlow<Long> = _pickedColor

    fun setFillParams(f: (FillParams) -> FillParams) { _fillParams.value = f(_fillParams.value) }
    fun setSmudgeParams(f: (SmudgeParams) -> SmudgeParams) { _smudgeParams.value = f(_smudgeParams.value) }
    fun setSelMode(m: SelMode) { if (m != _selMode.value) cancelPendingLasso(); _selMode.value = m }

    // ---- Sprint 33: сочетание выделений, «Выделить всё», расширение/сужение, рамка трансформации ----
    private val _selCombine = MutableStateFlow(com.vibetoon.domain.raster.SelectionOps.Combine.REPLACE)
    /** Как новый контур сочетается с текущим выделением (заменить, добавить, вычесть, пересечь). */
    val selCombine: StateFlow<com.vibetoon.domain.raster.SelectionOps.Combine> = _selCombine.asStateFlow()
    fun setSelCombine(c: com.vibetoon.domain.raster.SelectionOps.Combine) { _selCombine.value = c }

    private var selBox: com.vibetoon.domain.raster.TransformBox.Box? = null
    private var selBoxStart: com.vibetoon.domain.raster.TransformBox.Box? = null
    private var selBoxFrom: com.vibetoon.domain.raster.TransformBox.P? = null
    private var selBoxHandle: com.vibetoon.domain.raster.TransformBox.Handle? = null
    private val _selTransform = MutableStateFlow<FloatArray?>(null)
    /** Рамка трансформации на экране: 4 угла (по часовой от левого верхнего) и опорная точка - 10 чисел; null - рамки нет. */
    val selTransform: StateFlow<FloatArray?> = _selTransform.asStateFlow()
    private val _selTransformMode = MutableStateFlow(com.vibetoon.domain.raster.TransformBox.Mode.FREE)
    val selTransformMode: StateFlow<com.vibetoon.domain.raster.TransformBox.Mode> = _selTransformMode.asStateFlow()
    private val _selTransformProportional = MutableStateFlow(false)
    val selTransformProportional: StateFlow<Boolean> = _selTransformProportional.asStateFlow()

    fun beginSelectionTransform() {
        val s = sel ?: return
        if (_pendingLasso.value || _selectionExtractBusy.value || _playing.value) return
        val b = com.vibetoon.domain.raster.RasterMath.bounds(s.mask, s.w, s.h) ?: return
        selBox = com.vibetoon.domain.raster.TransformBox.initial(b); selBoxStart = null; selBoxHandle = null
        _state.value = UiState.Message("Трансформ: углы и стороны меняют размер, наклон или искажение; внутри рамки - перенос, снаружи - поворот; «Применить» переносит пиксели")
        refreshSelOverlay()
    }
    fun cancelSelectionTransform() {
        selBox = null; selBoxStart = null; selBoxFrom = null; selBoxHandle = null
        _selTransform.value = null
        if (rasterMode == 7) rasterMode = 0
    }
    fun setSelTransformMode(m: com.vibetoon.domain.raster.TransformBox.Mode) { _selTransformMode.value = m }
    fun toggleSelTransformProportional() { _selTransformProportional.value = !_selTransformProportional.value }
    /** Опорная точка по сетке 3x3 рамки (0..2 по каждой оси). */
    fun setSelTransformAnchor(ax: Int, ay: Int) {
        val b = selBox ?: return
        selBox = com.vibetoon.domain.raster.TransformBox.withAnchor(b, ax, ay); refreshSelOverlay()
    }
    /** Числовой ввод поверх текущей рамки относительно опоры. */
    fun applySelTransformNumeric(n: com.vibetoon.domain.raster.TransformBox.Numeric) {
        val b = selBox ?: return
        val r = com.vibetoon.domain.raster.TransformBox.numeric(b, n)
        if (r == null || !com.vibetoon.domain.raster.TransformBox.valid(r)) { _state.value = UiState.Message("Недопустимые значения трансформации"); return }
        selBox = r; refreshSelOverlay()
    }
    /** Переносит пиксели в рамку одной операцией Undo; при ошибке рамка остаётся для правки. */
    fun applySelectionTransform() {
        val s = sel ?: return
        val b = selBox ?: return
        if (_selectionExtractBusy.value) { _state.value = UiState.Message("Подождите завершения операции"); return }
        if (!com.vibetoon.domain.raster.TransformBox.valid(b)) { _state.value = UiState.Message("Рамка вырождена, перекручена или слишком мала/велика: исправьте её или отмените"); return }
        if (com.vibetoon.domain.raster.TransformBox.isIdentity(b)) { cancelSelectionTransform(); return }
        moveSelectionExpanded(s, 0, 0, warp = b)
    }

    private fun selBoxMove(sx: Float, sy: Float) {
        val s = sel ?: return
        val start = selBoxStart ?: return; val from = selBoxFrom ?: return; val handle = selBoxHandle ?: return
        val lp = layerPoint(s.id, sx, sy) ?: return
        selBox = com.vibetoon.domain.raster.TransformBox.drag(start, handle, _selTransformMode.value, from, com.vibetoon.domain.raster.TransformBox.P(lp.first.toDouble(), lp.second.toDouble()), _selTransformProportional.value)
        refreshSelOverlay()
    }

    /** Выделяет весь слой-картинку. */
    fun selectAllPixels() {
        cancelPendingLasso()
        val id = paintTargetId() ?: run { _state.value = UiState.Message("Инструмент работает на слое-картинке: выберите его или создайте «Новый слой»"); return }
        val size = paintSizes[id] ?: run { requestPaintSize(id); _state.value = UiState.Message("Слой готовится, повторите"); return }
        setSelection(Sel(id, size[0], size[1], com.vibetoon.domain.raster.SelectionOps.all(size[0], size[1])))
    }

    /** Расширяет (px > 0) или сужает (px < 0) выделение. */
    fun growSelection(px: Int) {
        val s = sel ?: return
        if (_pendingLasso.value || selBox != null || _selectionExtractBusy.value) return
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) { com.vibetoon.domain.raster.SelectionOps.grow(s.mask, s.w, s.h, px) } ?: return@launch
            if (sel !== s) return@launch
            if (com.vibetoon.domain.raster.RasterMath.count(m) == 0) clearSelection() else setSelection(Sel(s.id, s.w, s.h, m))
        }
    }

    /** Новая маска с учётом режима сочетания (только для того же слоя и размера). */
    private suspend fun applyCombined(id: Int, w: Int, h: Int, mask: ByteArray) {
        val op = _selCombine.value
        val old = sel?.takeIf { it.id == id && it.w == w && it.h == h }?.mask
        val merged = if (op == com.vibetoon.domain.raster.SelectionOps.Combine.REPLACE) mask else withContext(Dispatchers.Default) { com.vibetoon.domain.raster.SelectionOps.combine(old, mask, op) }
        if (com.vibetoon.domain.raster.RasterMath.count(merged) == 0) clearSelection() else setSelection(Sel(id, w, h, merged))
    }

    fun rasterToolActive(): Boolean = _rasterTool.value != null && !_playing.value

    fun setRasterTool(t: RasterTool?) {
        if (_rasterTool.value == t) return
        if (_rasterTool.value == RasterTool.SELECT) clearSelection()
        if (_rasterTool.value == RasterTool.PATH) penCancel()
        rasterCancel()
        cancelVectorTransform()
        _rasterTool.value = t
        if (t != null) {
            ensurePixelTarget()?.let { requestPaintSize(it) }
            _state.value = UiState.Message(when (t) {
                RasterTool.FILL -> "Заливка: коснитесь замкнутой области (порог, расширение и зазор - в панели над холстом)"
                RasterTool.PIPETTE -> "Пипетка: коснитесь холста, чтобы взять цвет"
                RasterTool.SELECT -> "Выделение: обведите область или коснитесь палочкой; внутри выделения тяните, чтобы перенести"
                RasterTool.SMUDGE -> "Смазка: проведите по рисунку слоя-картинки"
                RasterTool.PATH -> "Перо: тапами ставьте точки на векторном слое, протяжка от новой точки вытягивает ручки кривой, «Готово» завершает контур, тап по первой точке замыкает"
            })
        }
    }

    private class LayerPx(val bytes: ByteArray, val w: Int, val h: Int)

    /** Снимок пикселей слоя (8 байт заголовка + RGBA premultiplied); читается на GL-потоке, считать по нему можно в любом. */
    private suspend fun readLayer(id: Int): LayerPx? {
        val view = RenderHost.view ?: return null
        val b = kotlinx.coroutines.withTimeoutOrNull(4000) {
            kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.layerPixels(id) { if (c.isActive) c.resumeWith(Result.success(it)) } }
        } ?: return null
        if (b.size <= 8) return null
        fun i32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        val w = i32(0); val h = i32(4)
        if (w <= 0 || h <= 0 || b.size < 8 + w * h * 4) return null
        return LayerPx(b, w, h)
    }

    /** Точка экрана -> пиксель слоя [id] (учитывает поворот, масштаб и положение слоя). */
    private fun layerPoint(id: Int, sx: Float, sy: Float): Pair<Float, Float>? {
        val size = paintSizes[id] ?: return null
        val q = scene?.selectionQuads?.get(id) ?: return null
        val p = unitOf(q, toCanvas(sx, sy)) ?: return null
        return p.first * size[0] to p.second * size[1]
    }

    /** Записывает результат правки в слой и в Undo (как штрих кисти): сжатие в фоне, один Undo - одна правка. */
    private fun commitRaster(id: Int, r: com.vibetoon.domain.raster.IRect, before: ByteArray, after: ByteArray) {
        RenderHost.view?.paintRestore(id, r.x, r.y, r.w, r.h, after) { }
        val op = PaintOp(id, r.x, r.y, r.w, r.h, before, after)
        op.cellFile = cellShown[id]
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) { commitPaintOp(op) }
            op.compress()
            withContext(Dispatchers.Main) { enforcePaintBudget() }
        }
    }

    private var rasterBusy = false
    private var rasterMode = 0   // 1 - выделение рисуется, 2 - перенос выделения, 3 - смазка

    fun rasterDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        val t = _rasterTool.value ?: return false
        if (_playing.value) return false
        if (t == RasterTool.PIPETTE) {
            RenderHost.view?.sampleColor(sx.toInt(), sy.toInt()) { argb ->
                val c = argb.toLong() and 0xFFFFFFFFL
                setBrushColor(argb); _pickedColor.tryEmit(c)
            }
            swallowTap = true
            return true
        }
        if (t == RasterTool.PATH) return penDown(sx, sy)
        if (t == RasterTool.SMUDGE && onlyPathLayers()) {
            _state.value = UiState.Message("Смаз действует только на выбранных слоях-контурах: выберите векторный слой")
            return false
        }
        val id = paintTargetId() ?: run {
            _state.value = UiState.Message("Инструмент работает на слое-картинке: выберите его или создайте «Новый слой»")
            return false
        }
        if (paintSizes[id] == null) { requestPaintSize(id); _state.value = UiState.Message("Слой готовится, повторите"); return false }
        swallowTap = true
        return when (t) {
            RasterTool.FILL -> { fillAt(id, sx, sy); true }
            RasterTool.SMUDGE -> pxSmudgeDown(id, sx, sy)
            RasterTool.SELECT -> selDown(id, sx, sy)
            else -> false
        }
    }

    fun rasterMove(sx: Float, sy: Float) {
        if (rasterMode == 5 && kotlin.math.hypot(sx - closeTapX, sy - closeTapY) > 12f * getApplication<Application>().resources.displayMetrics.density) closeTapMoved = true
        when (rasterMode) { 1 -> selDrawMove(sx, sy); 2 -> selMoveMove(sx, sy); 3 -> pxSmudgeMove(sx, sy); 4 -> vecNodeMove(sx, sy); 6 -> penDragMove(sx, sy); 7 -> selBoxMove(sx, sy); 8 -> vecBoxMove(sx, sy) }
    }

    fun rasterUp() {
        when (rasterMode) { 1 -> selDrawUp(); 2 -> selMoveUp(); 3 -> pxSmudgeUp(); 4 -> vecNodeUp(); 5 -> if (!closeTapMoved) finishPendingLasso(); 7 -> { selBoxStart = null; selBoxHandle = null }; 8 -> { vecBoxStart = null; vecBoxHandle = null } }
        rasterMode = 0
    }

    /** Жест прерван вторым пальцем (камера). */
    fun rasterCancel() {
        when (rasterMode) { 1 -> { _toolDrag.value = null; selPts.clear(); selLayerPts.clear() }; 2 -> { selShiftX = 0f; selShiftY = 0f; refreshSelOverlay() }; 3 -> pxSmudgeUp(); 4 -> vecNodeCancel(); 6 -> penDragCancel(); 7 -> { selBoxStart?.let { selBox = it }; selBoxStart = null; selBoxHandle = null; refreshSelOverlay() }; 8 -> { vecBoxStart?.let { vecBox = it }; vecBoxStart = null; vecBoxHandle = null; refreshVecBoxOverlay(); previewVecBox(force = true) } }
        rasterMode = 0
        cancelPendingLasso()
    }

    // ---- заливка ----

    private fun fillAt(id: Int, sx: Float, sy: Float) {
        val pt = layerPoint(id, sx, sy) ?: return
        if (rasterBusy) return
        rasterBusy = true
        val fp = _fillParams.value
        val color = _brush.value.color or (0xFF shl 24)
        viewModelScope.launch {
            try {
                val lp = readLayer(id) ?: run { _state.value = UiState.Message("Слой ещё не загружен, повторите"); return@launch }
                // тяжёлая часть (flood fill, расширение) - в фоновом потоке, интерфейс не ждёт
                val res = withContext(Dispatchers.Default) {
                    val region = com.vibetoon.domain.raster.RasterMath.floodRegion(lp.bytes, 8, lp.w, lp.h, pt.first.toInt(), pt.second.toInt(), fp.threshold, fp.gap)
                    com.vibetoon.domain.raster.RasterMath.fillResult(lp.bytes, 8, lp.w, lp.h, region, color, fp.expand, fp.gap)
                }
                if (res == null) _state.value = UiState.Message("Здесь нечего заливать: область уже этого цвета или точка попала на линию")
                else commitRaster(id, res.rect, res.before, res.after)
            } finally { rasterBusy = false }
        }
    }

    // ---- выделение ----

    private class Sel(val id: Int, val w: Int, val h: Int, var mask: ByteArray)
    @Volatile private var sel: Sel? = null
    private val selPts = ArrayList<Float>()       // экранные точки лассо / рамки
    private val selLayerPts = ArrayList<Float>()  // те же точки в пикселях слоя
    private var selDragId = -1
    private val _pendingLasso = MutableStateFlow(false)
    val pendingLasso: StateFlow<Boolean> = _pendingLasso.asStateFlow()
    private var pendingQuad: FloatArray? = null
    private var pendingFrame = -1
    private var closeTapX = 0f
    private var closeTapY = 0f
    private var closeTapMoved = false
    private fun lassoRadius() = 24f * getApplication<Application>().resources.displayMetrics.density

    /** Открытый контур — только черновик, предыдущая маска и проект не меняются. */
    fun cancelPendingLasso() {
        if (!_pendingLasso.value) return
        _pendingLasso.value = false
        pendingQuad = null
        if (rasterMode == 1 || rasterMode == 5) rasterMode = 0
        selPts.clear(); selLayerPts.clear()
        _toolDrag.value = null
    }

    private fun pendingLassoValid(id: Int): Boolean = _pendingLasso.value && id == selDragId &&
        _frame.value == pendingFrame && manualLassoClose() && _selMode.value == SelMode.LASSO &&
        pendingQuad?.contentEquals(scene?.selectionQuads?.get(id)) == true

    private fun finishPendingLasso() {
        if (!pendingLassoValid(selDragId)) { cancelPendingLasso(); return }
        val pts = selLayerPts.toFloatArray()
        val id = selDragId
        cancelPendingLasso()
        if (pts.size >= 6) createSelectionMask(id, SelMode.LASSO, pts)
    }
    private var selMoveStartL: Pair<Float, Float>? = null
    private var selMoveStartS = 0f to 0f
    private var selShiftX = 0f
    private var selShiftY = 0f

    private fun manualLassoClose(): Boolean = getApplication<Application>()
        .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("manual_selection_close", false)

    private fun selDown(id: Int, sx: Float, sy: Float): Boolean {
        val lp0 = layerPoint(id, sx, sy) ?: return false
        val box = selBox
        if (box != null) {
            // Sprint 33: рамка трансформации забирает касание (угол, сторона, опора, перенос или поворот)
            val s0 = sel
            if (s0 == null || s0.id != id || _selectionExtractBusy.value) return true
            val lp1 = layerPoint(id, sx + 24f * getApplication<Application>().resources.displayMetrics.density, sy)
            val radius = if (lp1 == null) 12.0 else kotlin.math.hypot(lp1.first - lp0.first, lp1.second - lp0.second).toDouble().coerceAtLeast(2.0)
            val from = com.vibetoon.domain.raster.TransformBox.P(lp0.first.toDouble(), lp0.second.toDouble())
            selBoxHandle = com.vibetoon.domain.raster.TransformBox.hit(box, from, radius); selBoxStart = box; selBoxFrom = from
            rasterMode = 7
            return true
        }
        if (_pendingLasso.value) {
            if (!pendingLassoValid(id)) cancelPendingLasso()
            else {
                when (com.vibetoon.domain.raster.LassoClosure.resume(selPts.toFloatArray(), sx, sy, lassoRadius())) {
                    com.vibetoon.domain.raster.LassoClosure.Resume.CLOSE -> {
                        rasterMode = 5; closeTapX = sx; closeTapY = sy; closeTapMoved = false
                    }
                    com.vibetoon.domain.raster.LassoClosure.Resume.CONTINUE -> { rasterMode = 1 }
                    com.vibetoon.domain.raster.LassoClosure.Resume.MISS -> {
                        rasterMode = 0
                        _state.value = UiState.Message("Продолжите от конца контура или коснитесь начала; отмена — в панели инструмента")
                    }
                }
                return true
            }
        }
        val s = sel
        if (s != null && s.id == id && _selCombine.value == com.vibetoon.domain.raster.SelectionOps.Combine.REPLACE) {
            val x = lp0.first.toInt(); val y = lp0.second.toInt()
            if (x in 0 until s.w && y in 0 until s.h && s.mask[y * s.w + x].toInt() != 0) {
                // тянем внутри выделения: переносим его содержимое
                rasterMode = 2; selMoveStartL = lp0; selMoveStartS = sx to sy; lastMoveSx = sx; lastMoveSy = sy; selShiftX = 0f; selShiftY = 0f
                return true
            }
        }
        if (_selMode.value == SelMode.WAND) {
            val thr = _fillParams.value.threshold
            viewModelScope.launch {
                val lp = readLayer(id) ?: return@launch
                val mask = withContext(Dispatchers.Default) {
                    com.vibetoon.domain.raster.RasterMath.wandMask(lp.bytes, 8, lp.w, lp.h, lp0.first.toInt(), lp0.second.toInt(), thr)
                }
                applyCombined(id, lp.w, lp.h, mask)
            }
            return true
        }
        rasterMode = 1; selDragId = id
        selPts.clear(); selLayerPts.clear()
        selPts += sx; selPts += sy; selLayerPts += lp0.first; selLayerPts += lp0.second
        return true
    }

    private fun selDrawMove(sx: Float, sy: Float) {
        val lp = layerPoint(selDragId, sx, sy) ?: return
        when (_selMode.value) {
            SelMode.RECT, SelMode.OVAL -> {
                if (selPts.size > 2) { selPts[2] = sx; selPts[3] = sy; selLayerPts[2] = lp.first; selLayerPts[3] = lp.second }
                else { selPts += sx; selPts += sy; selLayerPts += lp.first; selLayerPts += lp.second }
                _toolDrag.value = ToolDrag(if (_selMode.value == SelMode.RECT) 0 else 1, selPts.toFloatArray())
            }
            SelMode.LASSO -> {
                val n = selPts.size
                if (n >= 2 && n < 20_000 && kotlin.math.hypot(sx - selPts[n - 2], sy - selPts[n - 1]) >= 4f) {
                    selPts += sx; selPts += sy; selLayerPts += lp.first; selLayerPts += lp.second
                    _toolDrag.value = ToolDrag(if (manualLassoClose()) 5 else 2, selPts.toFloatArray())
                }
            }
            else -> {}
        }
    }

    private fun selDrawUp() {
        val id = selDragId
        val pts = selLayerPts.toFloatArray()
        val screen = selPts.toFloatArray()
        val mode = _selMode.value
        // касание без протяжки вне выделения снимает его
        if (mode == SelMode.LASSO) {
            when (com.vibetoon.domain.raster.LassoClosure.classify(screen, manualLassoClose(), lassoRadius())) {
                com.vibetoon.domain.raster.LassoClosure.Result.TAP -> { tapWithoutContour(); return }
                com.vibetoon.domain.raster.LassoClosure.Result.OPEN -> {
                    if (selPts.size >= 20_000) { cancelPendingLasso(); selPts.clear(); selLayerPts.clear(); _toolDrag.value = null; _state.value = UiState.Message("Контур слишком длинный; начните заново"); return }
                    _pendingLasso.value = true
                    pendingQuad = scene?.selectionQuads?.get(id)?.copyOf()
                    pendingFrame = _frame.value
                    _toolDrag.value = ToolDrag(5, screen)
                    _state.value = UiState.Message("Продолжите от конца контура или коснитесь начала; отмена — в панели инструмента")
                    return
                }
                com.vibetoon.domain.raster.LassoClosure.Result.CLOSE -> Unit
            }
        } else {
            val moved = screen.size >= 4 && kotlin.math.hypot(screen[screen.size - 2] - screen[0], screen[screen.size - 1] - screen[1]) > 6f
            if (!moved) { tapWithoutContour(); return }
        }
        _pendingLasso.value = false; pendingQuad = null
        selPts.clear(); selLayerPts.clear(); _toolDrag.value = null
        createSelectionMask(id, mode, pts)
    }

    private fun createSelectionMask(id: Int, mode: SelMode, pts: FloatArray) {
        val size = paintSizes[id] ?: return
        viewModelScope.launch {
            val mask = withContext(Dispatchers.Default) {
                when (mode) {
                    SelMode.RECT -> com.vibetoon.domain.raster.RasterMath.rectMask(size[0], size[1], pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
                    SelMode.OVAL -> com.vibetoon.domain.raster.RasterMath.ovalMask(size[0], size[1], pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
                    else -> com.vibetoon.domain.raster.RasterMath.polygonMask(size[0], size[1], pts)
                }
            }
            applyCombined(id, size[0], size[1], mask)
        }
    }

    /** Касание без контура: в режиме «Новое» снимает выделение, в остальных режимах ничего не меняет. */
    private fun tapWithoutContour() {
        if (_selCombine.value == com.vibetoon.domain.raster.SelectionOps.Combine.REPLACE) clearSelection()
        else { selPts.clear(); selLayerPts.clear(); _toolDrag.value = null }
    }

    private fun selMoveMove(sx: Float, sy: Float) {
        lastMoveSx = sx; lastMoveSy = sy
        selShiftX = sx - selMoveStartS.first; selShiftY = sy - selMoveStartS.second
        refreshSelOverlay()   // контур едет за пальцем, пиксели переносятся при отпускании
    }

    private fun selMoveUp() {
        val s = sel ?: return
        val start = selMoveStartL ?: return
        selShiftX = 0f; selShiftY = 0f
        val end = layerPoint(s.id, selMoveStartS.first + (lastMoveSx - selMoveStartS.first), selMoveStartS.second + (lastMoveSy - selMoveStartS.second)) ?: run { refreshSelOverlay(); return }
        val dx = Math.round(end.first - start.first); val dy = Math.round(end.second - start.second)
        if (dx == 0 && dy == 0) { refreshSelOverlay(); return }
        val box = com.vibetoon.domain.raster.RasterMath.bounds(s.mask, s.w, s.h) ?: return
        if (!com.vibetoon.domain.raster.RasterMath.moveFits(box, s.w, s.h, dx, dy)) {
            refreshSelOverlay()
            if (EXPANDED_MOVE_ENABLED) { moveSelectionExpanded(s, dx, dy); return }
            val pid = projectId
            val frame = _frame.value
            viewModelScope.launch {
                // Предпроверка не выделяет расширенный буфер и не меняет слой.
                val plan = withContext(Dispatchers.Default) {
                    val budget = com.vibetoon.data.RasterMemory.available(getApplication())
                    com.vibetoon.domain.raster.ExpandedSelectionMove.plan(s.w, s.h, s.mask, dx, dy, budget)
                }
                if (projectId != pid || _frame.value != frame || sel !== s || paintTargetId() != s.id) return@launch
                _state.value = UiState.Message(if (plan == null)
                    "Перенос превышает безопасный предел размера или памяти; пиксели не изменены"
                else "Перенос за границу слоя пока не поддерживается; пиксели не изменены")
            }
            return
        }
        // Независимый PNG проводим через ту же проверяемую транзакцию и внутри текстуры:
        // иначе история и автосохранение могли опередить асинхронный GL-patch.
        val moveSource = media.firstOrNull { it.id == s.id }
        if (moveSource != null && moveSource.file.endsWith(".png", ignoreCase = true) && safeSelectionSource(moveSource, separating = false)) {
            moveSelectionExpanded(s, dx, dy)
            return
        }
        val pid = projectId
        val frame = _frame.value
        viewModelScope.launch {
            val lp = readLayer(s.id) ?: return@launch
            if (lp.w != s.w || lp.h != s.h) { refreshSelOverlay(); return@launch }
            val (res, shifted) = withContext(Dispatchers.Default) {
                val r = com.vibetoon.domain.raster.RasterMath.moveResult(lp.bytes, 8, lp.w, lp.h, s.mask, dx, dy)
                val m = ByteArray(s.mask.size)
                for (y in 0 until s.h) for (x in 0 until s.w) if (s.mask[y * s.w + x].toInt() != 0) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until s.w && ny in 0 until s.h) m[ny * s.w + nx] = 1
                }
                r to m
            }
            if (projectId != pid || _frame.value != frame || sel !== s || paintTargetId() != s.id || _playing.value) return@launch
            if (res != null) commitRaster(s.id, res.rect, res.before, res.after)
            setSelection(Sel(s.id, s.w, s.h, shifted))
        }
    }

    private data class SelectionGestureStart(val selection: Sel, val a: com.vibetoon.domain.raster.FreeSelectionTransform.Point,
        val b: com.vibetoon.domain.raster.FreeSelectionTransform.Point, val quad: FloatArray, val camera: Camera, val frame: Int, val viewW: Float, val viewH: Float)
    private var selectionGestureStart: SelectionGestureStart? = null
    /** Starts only after the first finger landed inside the existing pixel mask. */
    fun beginSelectionGesture(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        if (rasterMode != 2 || _rasterTool.value != RasterTool.SELECT || _selectionExtractBusy.value) return false
        val s = sel ?: return false
        val src = media.firstOrNull { it.id == s.id } ?: return false
        if (!src.file.endsWith(".png", ignoreCase = true) || !safeSelectionSource(src, separating = false)) return false
        val a = layerPoint(s.id, ax, ay) ?: return false; val b = layerPoint(s.id, bx, by) ?: return false
        if (kotlin.math.hypot(a.first - b.first, a.second - b.second) < 4f) return false
        val q = scene?.selectionQuads?.get(s.id)?.copyOf() ?: return false
        selectionGestureStart = SelectionGestureStart(s, com.vibetoon.domain.raster.FreeSelectionTransform.Point(a.first, a.second),
            com.vibetoon.domain.raster.FreeSelectionTransform.Point(b.first, b.second), q, camera, _frame.value, viewW, viewH)
        selShiftX = 0f; selShiftY = 0f; refreshSelOverlay(); rasterMode = 0
        return true
    }
    fun endSelectionGesture(ax: Float, ay: Float, bx: Float, by: Float, cancel: Boolean) {
        val start = selectionGestureStart ?: return
        selectionGestureStart = null
        val s = start.selection
        if (cancel || sel !== s || camera != start.camera || _frame.value != start.frame || viewW != start.viewW || viewH != start.viewH ||
            scene?.selectionQuads?.get(s.id)?.contentEquals(start.quad) != true) return
        val a = layerPoint(s.id, ax, ay) ?: return; val b = layerPoint(s.id, bx, by) ?: return
        val gesture = com.vibetoon.domain.raster.FreeSelectionTransform.Gesture(start.a, start.b,
            com.vibetoon.domain.raster.FreeSelectionTransform.Point(a.first, a.second), com.vibetoon.domain.raster.FreeSelectionTransform.Point(b.first, b.second))
        val matrix = com.vibetoon.domain.raster.FreeSelectionTransform.matrix(gesture)
        if (matrix == null) { _state.value = UiState.Message("Жест выделения отменён: недопустимый масштаб"); return }
        if (kotlin.math.abs(matrix.a - 1.0) < .001 && kotlin.math.abs(matrix.b) < .001 && kotlin.math.abs(matrix.tx) < .25 && kotlin.math.abs(matrix.ty) < .25) return
        moveSelectionExpanded(s, 0, 0, gesture = gesture)
    }

    private val EXPANDED_MOVE_ENABLED = true

    fun mirrorSelectedPixels(horizontal: Boolean) {
        val selected = sel ?: return
        moveSelectionExpanded(selected, 0, 0, if (horizontal) com.vibetoon.domain.raster.SelectionMirror.Operation.FLIP_X else com.vibetoon.domain.raster.SelectionMirror.Operation.FLIP_Y)
    }
    fun rotateSelectedPixels(clockwise: Boolean) {
        val selected = sel ?: return
        moveSelectionExpanded(selected, 0, 0, if (clockwise) com.vibetoon.domain.raster.SelectionMirror.Operation.ROTATE_CW else com.vibetoon.domain.raster.SelectionMirror.Operation.ROTATE_CCW)
    }

    fun scaleSelectedPixels(enlarge: Boolean) {
        val selected = sel ?: return
        moveSelectionExpanded(selected, 0, 0, if (enlarge) com.vibetoon.domain.raster.SelectionMirror.Operation.SCALE_DOUBLE else com.vibetoon.domain.raster.SelectionMirror.Operation.SCALE_HALF)
    }

    private fun moveSelectionExpanded(s: Sel, dx: Int, dy: Int, selectionOperation: com.vibetoon.domain.raster.SelectionMirror.Operation? = null,
                                      gesture: com.vibetoon.domain.raster.FreeSelectionTransform.Gesture? = null,
                                      warp: com.vibetoon.domain.raster.TransformBox.Box? = null) {
        if (_selectionExtractBusy.value || _playing.value || _pendingLasso.value) return
        val pid = projectId ?: return
        val view = RenderHost.view ?: return
        val frame = _frame.value
        val src = media.firstOrNull { it.id == s.id }
        val sourceTransform = base[s.id] ?: LayerTransform()
        if (src == null || !src.file.endsWith(".png", ignoreCase = true) || !safeSelectionSource(src, separating = false) || _selected.value != s.id || _selection.value.size != 1) {
            _state.value = UiState.Message(if (src != null && !src.file.endsWith(".png", ignoreCase = true))
                "Для переноса за границы нужен PNG; GIF и другие форматы пока не поддерживаются"
            else selectionSourceMessage(src, separating = false))
            return
        }
        _selectionExtractBusy.value = true
        viewModelScope.launch {
            try {
                withContext(kotlinx.coroutines.NonCancellable) {
                    rasterTransactionGate.run {
                        val budget = com.vibetoon.data.RasterMemory.available(getApplication())
                        val withinSize = selectionOperation != null || gesture != null || warp != null || withContext(Dispatchers.Default) {
                            com.vibetoon.domain.raster.ExpandedSelectionMove.plan(s.w, s.h, s.mask, dx, dy, Long.MAX_VALUE) != null
                        }
                        val plan = if (selectionOperation == null && gesture == null && warp == null && withinSize) withContext(Dispatchers.Default) {
                            com.vibetoon.domain.raster.ExpandedSelectionMove.plan(s.w, s.h, s.mask, dx, dy, budget)
                        } else null
                        // Компактный путь может поместиться и тогда, когда обычный план слишком затратен.
                        // Перед чтением полной GL-текстуры оставляем запас на неё и полный Undo.
                        if (plan == null && budget < s.w.toLong() * s.h * 9L + 8L * 1024 * 1024) {
                            _state.value = UiState.Message("Недостаточно запаса памяти для переноса; пиксели не изменены")
                            return@run
                        }
                        val lp = readLayer(s.id)
                        if (lp == null) {
                            _state.value = UiState.Message("Слой ещё не загружен, повторите"); return@run
                        }
                        if (lp.w != s.w || lp.h != s.h) return@run
                        val change = try {
                            withContext(Dispatchers.Default) {
                                if (warp != null) com.vibetoon.domain.raster.SelectionWarp.prepare(src, lp.bytes, 8, lp.w, lp.h, s.mask, warp, budget)
                                else if (gesture != null) com.vibetoon.domain.raster.FreeSelectionTransform.prepare(src, lp.bytes, 8, lp.w, lp.h, s.mask, gesture, budget)
                                else if (selectionOperation != null) com.vibetoon.domain.raster.SelectionMirror.prepare(src, lp.bytes, 8, lp.w, lp.h, s.mask, selectionOperation, budget)
                                else if (plan != null) com.vibetoon.domain.raster.ExpandedLayerSnapshot.prepare(src, lp.bytes, 8, lp.w, lp.h, s.mask, dx, dy, budget)
                                else com.vibetoon.domain.raster.ExpandedLayerSnapshot.prepareTrimmed(src, lp.bytes, 8, lp.w, lp.h, s.mask, dx, dy, budget)
                            }
                        } catch (_: OutOfMemoryError) { null }
                        if (change == null) {
                            Log.i("VT/Sel", "expanded move rejected: source=${s.w}x${s.h}, dx=$dx dy=$dy, cpuBudget=$budget, withinSize=$withinSize")
                            _state.value = UiState.Message("Операция не выполнена: граница слоя, предел 2048 или недостаточно памяти; пиксели не изменены")
                            return@run
                        }
                        val nextTransform = com.vibetoon.domain.raster.ExpandedTransform.compensate(change, sourceTransform)
                        if (nextTransform == null) {
                            _state.value = UiState.Message("Невозможно безопасно сохранить масштаб слоя; пиксели не изменены")
                            return@run
                        }
                        if (projectId != pid || RenderHost.view !== view || _frame.value != frame || sel !== s ||
                            _selected.value != s.id || _selection.value.size != 1 || _playing.value ||
                            media.firstOrNull { it.id == s.id } != src || (base[s.id] ?: LayerTransform()) != sourceTransform || !safeSelectionSource(src, separating = false)) return@run
                        val previous = snapshot()
                        val undoBefore = undoStack.toList()
                        val redoBefore = redoStack.toList()
                        val history = com.vibetoon.domain.raster.ExpandedLayerHistory(change)
                        var publishing = false
                        val outcome = com.vibetoon.domain.raster.TextureTransaction.execute(
                            replace = { replaceExpandedTexture(view, change.before, change.after) },
                            publish = {
                                check(projectId == pid && RenderHost.view === view && _frame.value == frame && sel === s && !_playing.value &&
                                    media.firstOrNull { it.id == s.id } == src && (base[s.id] ?: LayerTransform()) == sourceTransform && safeSelectionSource(src, separating = false))
                                publishing = true
                                val index = media.indexOfFirst { it.id == s.id }
                                media[index] = change.after.media
                                if (nextTransform == LayerTransform()) base.remove(s.id) else base[s.id] = nextTransform
                                versionedRasterIds.add(s.id)
                                paintSizes[s.id] = intArrayOf(change.after.width, change.after.height)
                                undoStack.addLast(previous.withTexture(history))
                                if (undoStack.size > 50) undoStack.removeFirst()
                                redoStack.clear(); lastCheckKey = null
                                rebuildLayers(); pushScene()
                                setSelection(Sel(s.id, change.after.width, change.after.height, change.selection))
                                invalidateThumb(s.id); paintDirty += s.id
                                markDirty(); updateHistoryFlags(); enforcePaintBudget()
                            },
                            rollback = {
                                if (projectId != pid || RenderHost.view !== view || !replaceExpandedTexture(view, change.after, change.before)) false
                                else {
                                    if (publishing) {
                                        undoStack.clear(); undoStack.addAll(undoBefore)
                                        redoStack.clear(); redoStack.addAll(redoBefore)
                                        paintSizes[s.id] = intArrayOf(s.w, s.h)
                                        restore(previous); setSelection(s)
                                        invalidateThumb(s.id); paintDirty += s.id; updateHistoryFlags()
                                    }
                                    true
                                }
                            }
                        )
                        when (outcome) {
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.COMMITTED -> Unit
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.REJECTED,
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.ROLLED_BACK ->
                                _state.value = UiState.Message("Не удалось восстановить текстуру; история не изменена")
                            com.vibetoon.domain.raster.TextureTransaction.Outcome.RECOVERY_REQUIRED -> {
                                rasterRecoveryRequired = true
                                _state.value = UiState.Error("Не удалось безопасно восстановить текстуру; сохранение приостановлено")
                            }
                        }
                    }
                }
            } finally { if (!rasterRecoveryRequired) _selectionExtractBusy.value = false }
        }
    }

    private var lastMoveSx = 0f
    private var lastMoveSy = 0f

    private fun setSelection(s: Sel?) {
        cancelSelectionTransform()
        sel = s
        _hasSel.value = s != null
        if (s == null) { _selOverlay.value = SelOverlay(); return }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) { selBitmap(s) }
            selBmp = bmp
            refreshSelOverlay()
        }
    }

    private var selBmp: androidx.compose.ui.graphics.ImageBitmap? = null

    /** Маска -> маленький полупрозрачный рисунок с «шагающей» границей (без перегрузки: не больше ~320 px по длинной стороне). */
    private fun selBitmap(s: Sel): androidx.compose.ui.graphics.ImageBitmap {
        val step = maxOf(1, maxOf(s.w, s.h) / 320)
        val bw = (s.w / step).coerceAtLeast(1); val bh = (s.h / step).coerceAtLeast(1)
        val px = IntArray(bw * bh)
        fun inside(x: Int, y: Int): Boolean = x in 0 until bw && y in 0 until bh && s.mask[(y * step).coerceAtMost(s.h - 1) * s.w + (x * step).coerceAtMost(s.w - 1)].toInt() != 0
        for (y in 0 until bh) for (x in 0 until bw) {
            if (!inside(x, y)) continue
            val edge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)
            px[y * bw + x] = if (edge) (if (((x + y) / 3) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF1E1E1E.toInt()) else 0x443B82F6
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        selScaleX = s.w.toFloat() / bw; selScaleY = s.h.toFloat() / bh
        return bmp.asImageBitmap()
    }
    private var selScaleX = 1f
    private var selScaleY = 1f

    /** Три угла слоя выделения на экране (+ сдвиг при переносе). Вызывается при каждой смене сцены и камеры. */
    private fun refreshSelOverlay() {
        val s = sel
        val bmp = selBmp
        if (s == null || bmp == null || _rasterTool.value != RasterTool.SELECT) { if (_selOverlay.value.bmp != null) _selOverlay.value = SelOverlay(); _selTransform.value = null; return }
        val q = scene?.selectionQuads?.get(s.id) ?: run { clearSelection(); return }   // слой удалён - выделение снимается
        val box = selBox
        _selTransform.value = if (box == null) null else FloatArray(10).also { out ->
            (box.corners + box.pivot).forEachIndexed { i, pt ->
                val u = (pt.x / s.w).toFloat(); val v = (pt.y / s.h).toFloat()
                val cx = q[0] + u * (q[2] - q[0]) + v * (q[6] - q[0]); val cy = q[1] + u * (q[3] - q[1]) + v * (q[7] - q[1])
                val sc = toScreen(cx, cy); out[i * 2] = sc.first; out[i * 2 + 1] = sc.second
            }
        }
        val c = floatArrayOf(
            toScreen(q[0], q[1]).first + selShiftX, toScreen(q[0], q[1]).second + selShiftY,
            toScreen(q[2], q[3]).first + selShiftX, toScreen(q[2], q[3]).second + selShiftY,
            toScreen(q[6], q[7]).first + selShiftX, toScreen(q[6], q[7]).second + selShiftY
        )
        _selOverlay.value = SelOverlay(bmp, c)
    }

    fun clearSelection() { cancelPendingLasso(); cancelSelectionTransform(); sel = null; selBmp = null; _hasSel.value = false; _selOverlay.value = SelOverlay(); _toolDrag.value = null }

    fun invertSelection() {
        val s = sel ?: return
        viewModelScope.launch {
            val m = withContext(Dispatchers.Default) { com.vibetoon.domain.raster.RasterMath.invert(s.mask) }
            setSelection(Sel(s.id, s.w, s.h, m))
        }
    }

    /** «Очистить»: содержимое под выделением становится прозрачным (в Undo одной записью). */
    fun clearSelectionContent() {
        val s = sel ?: return
        if (_pendingLasso.value || _selectionExtractBusy.value || paintTargetId() != s.id) return
        val pid = projectId
        val frame = _frame.value
        viewModelScope.launch {
            val lp = readLayer(s.id) ?: return@launch
            if (lp.w != s.w || lp.h != s.h) return@launch
            val res = withContext(Dispatchers.Default) { com.vibetoon.domain.raster.RasterMath.clearResult(lp.bytes, 8, lp.w, lp.h, s.mask) }
            if (projectId != pid || _frame.value != frame || sel !== s || paintTargetId() != s.id) return@launch
            if (res == null) _state.value = UiState.Message("Под выделением пусто") else commitRaster(s.id, res.rect, res.before, res.after)
        }
    }

    private val _selectionExtractBusy = MutableStateFlow(false)
    val selectionExtractBusy: StateFlow<Boolean> = _selectionExtractBusy.asStateFlow()

    /** У переносов и отделения общий набор ограничений, но причина отказа показывается отдельно. */
    private fun selectionSourceRejection(src: MediaItem, separating: Boolean = true): com.vibetoon.domain.raster.SelectionSourcePolicy.Reason? {
        val id = src.id
        val layer = layers.firstOrNull { it.id == id }
        val facts = com.vibetoon.domain.raster.SelectionSourcePolicy.Facts(
            raster = src.kind == MediaKind.IMAGE && !src.vector && !src.background && src.cells.isEmpty(),
            rootAndVisible = if (separating) com.vibetoon.domain.raster.GroupSelectionPolicy.supports(
                id, layers, media.filter { it.kind == MediaKind.GROUP }.mapTo(HashSet()) { it.id },
                hiddenIds() + _clips.value.filterValues { !it.visibleAt(_frame.value) }.keys,
                layerProps.filterValues { it.locked }.keys
            ) else layer != null && layer.parentId == -1 && layer.isVisible && layerPropsOf(id).visible,
            defaultProperties = layer != null && if (separating) layerPropsOf(id).copy(name = null) == LayerProps()
                else com.vibetoon.domain.raster.SelectionSourcePolicy.moveSafeProperties(layerPropsOf(id)),
            linked = id in _links.value || id in _links.value.values,
            clip = id in _clips.value,
            animated = id in _animation.value.tracks,
            transformed = !com.vibetoon.domain.raster.SelectionSourcePolicy.copySafeStaticTransform(base[id] ?: LayerTransform()) ||
                (override[id] ?: LayerTransform()) != LayerTransform(),
            deformed = id in warpLayers || id in meshLayers || id in perspLayers || id in skinLayers || id in turbLayers || id in pinLayers,
            effects = !fxLayers[id].isNullOrEmpty(),
            sceneClipping = separating && layers.any { layerPropsOf(it.id).clip }
        )
        return com.vibetoon.domain.raster.SelectionSourcePolicy.rejection(facts)
    }

    private fun safeSelectionSource(src: MediaItem, separating: Boolean = true) = selectionSourceRejection(src, separating) == null

    private fun selectionSourceMessage(src: MediaItem?, separating: Boolean = true): String {
        if (src == null) return "Выберите растровый слой"
        return when (selectionSourceRejection(src, separating)) {
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.UNSUPPORTED_TYPE -> "Этот тип слоя пока не поддерживается"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.GROUP_OR_VISIBILITY -> if (separating) "Слой и его группы должны быть видимы и разблокированы; группы PSD пока не поддерживаются" else "Слой должен быть видимым и находиться вне группы"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.PROPERTIES -> "Свойства слоя пока не поддерживаются для этой операции"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.LINKS -> "Связанные слои пока не поддерживаются"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.CLIP -> "Слой с изменённым клипом пока не поддерживается"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.ANIMATION -> "Слой с ключами анимации пока не поддерживается"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.TRANSFORM -> "Трансформация слоя пока не поддерживается"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.DEFORMER -> "Деформация слоя пока не поддерживается"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.EFFECTS -> "Эффекты слоя пока не поддерживаются"
            com.vibetoon.domain.raster.SelectionSourcePolicy.Reason.CLIPPING_IN_SCENE -> "Обтравочные слои в проекте пока не поддерживаются"
            null -> "Выберите один слой PNG"
        }
    }

    /** Cut и Copy используют одну маску; PSD, анимация и деформеры блокируются до сквозной поддержки. */
    fun selectionToNewLayer(partName: String) = extractSelection(cut = true, partName = partName)
    fun copySelectionToNewLayer(partName: String) = extractSelection(cut = false, partName = partName)

    private fun extractSelection(cut: Boolean, partName: String) {
        if (_selectionExtractBusy.value) { _state.value = UiState.Message("Подождите завершения операции"); return }
        if (_playing.value) { _state.value = UiState.Message("Остановите воспроизведение перед отделением"); return }
        if (_pendingLasso.value) { _state.value = UiState.Message("Сначала замкните или отмените контур лассо"); return }
        val s = sel ?: run { _state.value = UiState.Message("Сначала выделите область"); return }
        val pid = projectId ?: return
        val renderView = RenderHost.view ?: run { _state.value = UiState.Message("Слой ещё не загружен, повторите"); return }
        val frame = _frame.value
        val sourceTransform = base[s.id] ?: LayerTransform()
        val sourceParent = layers.firstOrNull { it.id == s.id }?.parentId ?: return
        val sourceQuad = scene?.selectionQuads?.get(s.id)?.copyOf()
        fun sourceUnchanged(): Boolean = layers.firstOrNull { it.id == s.id }?.parentId == sourceParent &&
            sourceQuad != null && sourceQuad.contentEquals(scene?.selectionQuads?.get(s.id))
        val src = media.firstOrNull { it.id == s.id }
        if (src == null || !src.file.endsWith(".png", ignoreCase = true) || !safeSelectionSource(src) || _selected.value != s.id || _selection.value.size != 1) {
            _state.value = UiState.Message(if (src != null && !src.file.endsWith(".png", ignoreCase = true))
                "Для отделения нужен PNG; GIF и другие форматы пока не поддерживаются" else selectionSourceMessage(src)); return
        }
        _selectionExtractBusy.value = true
        val fileName = "selection_${java.util.UUID.randomUUID()}.png"
        viewModelScope.launch {
            var bitmap: android.graphics.Bitmap? = null
            var committed = false
            try {
                rasterTransactionGate.run extraction@{
                val budget = com.vibetoon.data.RasterMemory.available(getApplication())
                val estimate = s.w.toLong() * s.h * 25L + 8L * 1024 * 1024
                if (estimate > budget) { _state.value = UiState.Message("Недостаточно запаса памяти для отделения; пиксели не изменены"); return@extraction }
                val lp = readLayer(s.id) ?: error("Не удалось прочитать пиксели слоя")
                if (lp.w != s.w || lp.h != s.h) error("Размер слоя изменился: выделите область заново")
                val part = withContext(Dispatchers.Default) {
                    com.vibetoon.domain.raster.SelectionExtract.extract(lp.bytes, 8, lp.w, lp.h, s.mask)
                } ?: run { _state.value = UiState.Message("Под выделением пусто или слой слишком велик"); return@extraction }
                bitmap = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(part.argb, s.w, s.h, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        projects.mediaFile(pid, fileName).outputStream().use {
                            check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) { "Не удалось сохранить выделение" }
                        }
                        bmp
                    } catch (t: Throwable) { bmp.recycle(); throw t }
                }
                if (projectId != pid || RenderHost.view !== renderView || sel !== s || _selected.value != s.id || _playing.value || _frame.value != frame ||
                    media.firstOrNull { it.id == s.id } != src || (base[s.id] ?: LayerTransform()) != sourceTransform || !sourceUnchanged() || !safeSelectionSource(src)) {
                    _state.value = UiState.Message("Слой изменился: повторите операцию"); return@extraction
                }
                val newId = nextMediaId()
                // JNI копирует большой bitmap и ждёт mutex очереди: это нельзя делать на UI-потоке.
                val uploaded = withContext(decodeDispatcher) {
                    val ticket = RenderNative.textureUploadTicket(newId)
                    RenderNative.uploadBitmapIfCurrent(newId, bitmap ?: error("Не удалось создать изображение"), ticket)
                }
                if (!uploaded) error("Не удалось создать слой в графике")
                val uploadedSize = kotlinx.coroutines.withTimeoutOrNull(4000) {
                    kotlinx.coroutines.suspendCancellableCoroutine<IntArray?> { c ->
                        renderView.layerSizeAfterFrame(newId) { if (c.isActive) c.resumeWith(Result.success(it)) }
                    }
                }
                if (uploadedSize == null || uploadedSize.size < 2 || uploadedSize[0] != s.w || uploadedSize[1] != s.h) {
                    _state.value = UiState.Message("Не удалось загрузить новый слой; пиксели не изменены")
                    return@extraction
                }
                if (projectId != pid || RenderHost.view !== renderView || sel !== s || _selected.value != s.id || _playing.value || _frame.value != frame ||
                    media.firstOrNull { it.id == s.id } != src || (base[s.id] ?: LayerTransform()) != sourceTransform || !sourceUnchanged() || !safeSelectionSource(src)) {
                    _state.value = UiState.Message("Слой изменился: повторите операцию"); return@extraction
                }
                val item = MediaItem(newId, MediaKind.IMAGE, "${src.name} — $partName", "assets/$fileName", s.w, s.h, src.x, src.y, src.w, src.h)
                var previous: Snapshot? = null
                var undoBefore: List<Snapshot> = emptyList(); var redoBefore: List<Snapshot> = emptyList()
                val change = part.cut
                // Полные снимки позволяют Undo/Redo ждать подтверждённую замену GL до публикации дерева.
                val history = if (cut) withContext(Dispatchers.Default) {
                    val before = lp.bytes.copyOfRange(8, 8 + s.w * s.h * 4)
                    val after = before.copyOf()
                    for (y in 0 until change.rect.h) System.arraycopy(change.after, y * change.rect.w * 4, after,
                        ((change.rect.y + y) * s.w + change.rect.x) * 4, change.rect.w * 4)
                    com.vibetoon.domain.raster.ExpandedLayerHistory(com.vibetoon.domain.raster.ExpandedLayerSnapshot.Change(
                        com.vibetoon.domain.raster.ExpandedLayerSnapshot.State(src, s.w, s.h, before),
                        com.vibetoon.domain.raster.ExpandedLayerSnapshot.State(src, s.w, s.h, after), ByteArray(0)))
                } else null
                var published = false
                withContext(kotlinx.coroutines.NonCancellable) {
                    suspend fun patch(data: ByteArray): Boolean? {
                        val reply = kotlinx.coroutines.CompletableDeferred<Boolean>()
                        val req = renderView.paintRestoreChecked(s.id, change.rect.x, change.rect.y, change.rect.w, change.rect.h, data) { reply.complete(it) }
                        val first = kotlinx.coroutines.withTimeoutOrNull(4000) { reply.await() }
                        if (first == null && req.cancelIfQueued()) return null
                        return first ?: reply.await()
                    }
                    val outcome = com.vibetoon.domain.raster.TextureTransaction.execute(
                        replace = {
                            if (!cut) true else when (patch(change.after)) {
                                true -> true
                                null -> false // отменено до начала GL
                                false -> {
                                    // GL мог изменить часть текстуры до ошибки: сначала восстанавливаем её.
                                    if (patch(change.before) == true) false else error("GL rollback failed")
                                }
                            }
                        },
                        publish = {
                            check(projectId == pid && RenderHost.view === renderView && sel === s && _frame.value == frame &&
                                media.firstOrNull { it.id == s.id } == src && (base[s.id] ?: LayerTransform()) == sourceTransform && sourceUnchanged() && safeSelectionSource(src))
                            previous = snapshot()
                            undoBefore = undoStack.toList(); redoBefore = redoStack.toList()
                            published = true
                            checkpoint("extract-selection", force = true)
                            if (history != null) {
                                val saved = undoStack.removeLast()
                                undoStack.addLast(saved.withTexture(history))
                                invalidateThumb(s.id); paintDirty += s.id
                                // PNG источника версионируется до публикации нового дерева в JSON.
                                versionedRasterIds.add(s.id)
                            }
                            media.add(media.indexOfFirst { it.id == s.id } + 1, item)
                            if (sourceParent != -1) regroup = regroup.filter { it.first != newId } + (newId to sourceParent)
                            if (sourceTransform != LayerTransform()) base[newId] = sourceTransform
                            rebuildLayers(); pushScene()
                            clearSelection(); selectLayer(newId)
                            contentBoxScheduled(newId); markDirty(); RenderHost.view?.requestRender()
                        },
                        rollback = {
                            if (projectId != pid || RenderHost.view !== renderView || (cut && patch(change.before) != true)) false
                            else {
                                if (published) {
                                    undoStack.clear(); undoStack.addAll(undoBefore)
                                    redoStack.clear(); redoStack.addAll(redoBefore)
                                    restore(checkNotNull(previous)); setSelection(s)
                                    if (cut) { invalidateThumb(s.id); paintDirty += s.id }
                                    updateHistoryFlags()
                                }
                                true
                            }
                        }
                    )
                    when (outcome) {
                        com.vibetoon.domain.raster.TextureTransaction.Outcome.COMMITTED -> {
                            committed = true
                            if (history != null) enforcePaintBudget()
                            _state.value = UiState.Message(if (cut) "Выделение вырезано в новый слой" else "Выделение скопировано в новый слой")
                        }
                        com.vibetoon.domain.raster.TextureTransaction.Outcome.REJECTED,
                        com.vibetoon.domain.raster.TextureTransaction.Outcome.ROLLED_BACK ->
                            _state.value = UiState.Message("Не удалось отделить выделение; пиксели не изменены")
                        com.vibetoon.domain.raster.TextureTransaction.Outcome.RECOVERY_REQUIRED -> {
                            rasterRecoveryRequired = true
                            _state.value = UiState.Error("Не удалось безопасно восстановить текстуру; сохранение приостановлено")
                        }
                    }
                }
                }
            } catch (t: kotlinx.coroutines.CancellationException) { throw t }
            catch (t: Throwable) {
                Log.e("VT/Sel", "extract selection failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось создать слой")
            } finally {
                bitmap?.recycle()
                if (!committed) runCatching { projects.mediaFile(pid, fileName).delete() }
                if (!rasterRecoveryRequired) _selectionExtractBusy.value = false
            }
        }
    }

    // ---- смазка ----

    private var smudgeCh: kotlinx.coroutines.channels.Channel<FloatArray>? = null

    private fun pxSmudgeDown(id: Int, sx: Float, sy: Float): Boolean {
        val first = layerPoint(id, sx, sy) ?: return false
        val ch = kotlinx.coroutines.channels.Channel<FloatArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        smudgeCh = ch
        rasterMode = 3
        ch.trySend(floatArrayOf(first.first, first.second))
        val sp = _smudgeParams.value
        val view = RenderHost.view
        viewModelScope.launch {
            val lp = readLayer(id)
            if (lp == null) { ch.cancel(); return@launch }
            val orig = lp.bytes.copyOf()
            val r = (sp.size / 2f).coerceIn(2f, 160f)
            var dirty: com.vibetoon.domain.raster.IRect? = null
            var pushed: com.vibetoon.domain.raster.IRect? = null
            var lastX = Float.NaN; var lastY = 0f
            var lastPush = 0L
            fun rectBytes(src: ByteArray, rc: com.vibetoon.domain.raster.IRect): ByteArray {
                val out = ByteArray(rc.w * rc.h * 4)
                for (yy in 0 until rc.h) System.arraycopy(src, 8 + ((rc.y + yy) * lp.w + rc.x) * 4, out, yy * rc.w * 4, rc.w * 4)
                return out
            }
            // обработка точек идёт в фоне: палец пишет в очередь, интерфейс ничего не считает
            withContext(Dispatchers.Default) {
                for (pt in ch) {
                    if (lastX.isNaN()) { lastX = pt[0]; lastY = pt[1]; continue }
                    val dx = pt[0] - lastX; val dy = pt[1] - lastY
                    val dist = kotlin.math.hypot(dx, dy)
                    if (dist < 0.5f) continue
                    val steps = kotlin.math.ceil(dist / (r * 0.25f)).toInt().coerceAtLeast(1)
                    val sdx = dx / steps; val sdy = dy / steps
                    for (i in 1..steps) {
                        val rc = com.vibetoon.domain.raster.RasterMath.smudgeStep(lp.bytes, 8, lp.w, lp.h, lastX + sdx * i, lastY + sdy * i, r, sdx, sdy, sp.strength)
                        if (rc != null) dirty = com.vibetoon.domain.raster.RasterMath.union(dirty, rc)
                    }
                    lastX = pt[0]; lastY = pt[1]
                    val now = SystemClock.uptimeMillis()
                    val d = dirty
                    if (d != null && now - lastPush > 45) {
                        lastPush = now
                        view?.paintRestore(id, d.x, d.y, d.w, d.h, rectBytes(lp.bytes, d)) { }
                        pushed = d
                    }
                }
            }
            val d = dirty
            if (d != null) {
                val after = withContext(Dispatchers.Default) { rectBytes(lp.bytes, d) }
                val before = withContext(Dispatchers.Default) { rectBytes(orig, d) }
                commitRaster(id, d, before, after)
            }
        }
        return true
    }

    private fun pxSmudgeMove(sx: Float, sy: Float) {
        val id = paintTargetId() ?: return
        val p = layerPoint(id, sx, sy) ?: return
        smudgeCh?.trySend(floatArrayOf(p.first, p.second))
    }

    private fun pxSmudgeUp() { smudgeCh?.close(); smudgeCh = null }

    // ---- перо: контур по точкам на векторном слое ----

    private val penPts = ArrayList<Float>()   // пиксели холста
    /** Пакет 20: ручки точек контура в работе (по 4 на точку, относительно точки); протяжка при постановке точки. */
    private var penHandles: List<Float> = emptyList()
    private var penDragX = 0f
    private var penDragY = 0f

    // ---- правка узлов готовых векторных примитивов: тап выбирает узел, перетаскивание двигает, кнопки удаляют ----

    private var nodeShape = -1
    private var nodePt = -1
    private var nodeSelId = -1
    private var nodeOriginal: MediaItem? = null
    private var nodeDraft: List<com.vibetoon.domain.vector.VectorShape>? = null
    private var nodeSelectionSnapshot: List<com.vibetoon.domain.vector.VectorShape>? = null
    /** Пакет 19: перетаскивается ручка Безье выбранного узла (0 - вход, 1 - выход), -1 - сам узел. */
    private var nodeHandle = -1

    private fun canvasToScreen(q: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        val u = x / canvasW; val v = y / canvasH
        return toScreen(q[0] + u * (q[2] - q[0]) + v * (q[6] - q[0]), q[1] + u * (q[3] - q[1]) + v * (q[7] - q[1]))
    }

    private fun vecNodeAt(id: Int, sx: Float, sy: Float): Pair<Int, Int>? {
        val m = media.firstOrNull { it.id == id } ?: return null
        val q = scene?.selectionQuads?.get(id) ?: return null
        var best: Pair<Int, Int>? = null
        var bestD = 34f
        for ((i, s) in m.vectors.withIndex()) for (k in 0 until s.pts.size / 2) {
            val (px, py) = canvasToScreen(q, s.pts[k * 2], s.pts[k * 2 + 1])
            val d = kotlin.math.hypot(sx - px, sy - py)
            if (d < bestD) { bestD = d; best = i to k }
        }
        return best
    }

    /** Ближайшая ручка Безье выбранного контура: (узел, 0/1) и расстояние на экране; ручки показываются только у него. */
    private fun vecHandleAt(id: Int, sx: Float, sy: Float): Pair<Pair<Int, Int>, Float>? {
        if (id != nodeSelId) return null
        val m = media.firstOrNull { it.id == id } ?: return null
        val s = m.vectors.getOrNull(nodeShape) ?: return null
        if (!com.vibetoon.domain.vector.VectorBezier.hasHandles(s)) return null
        val q = scene?.selectionQuads?.get(id) ?: return null
        var best: Pair<Int, Int>? = null
        var bestD = 34f
        for (k in 0 until s.pts.size / 2) {
            if (!com.vibetoon.domain.vector.VectorBezier.nodeHasHandles(s, k)) continue
            for (w in 0..1) {
                val (hx, hy) = com.vibetoon.domain.vector.VectorBezier.handleAt(s, k, w) ?: continue
                val (px, py) = canvasToScreen(q, hx, hy)
                val d = kotlin.math.hypot(sx - px, sy - py)
                if (d < bestD) { bestD = d; best = k to w }
            }
        }
        return best?.let { it to bestD }
    }

    private fun vecNodeDist(id: Int, hit: Pair<Int, Int>, sx: Float, sy: Float): Float {
        val q = scene?.selectionQuads?.get(id) ?: return Float.MAX_VALUE
        val s = media.firstOrNull { it.id == id }?.vectors?.getOrNull(hit.first) ?: return Float.MAX_VALUE
        val (px, py) = canvasToScreen(q, s.pts[hit.second * 2], s.pts[hit.second * 2 + 1])
        return kotlin.math.hypot(sx - px, sy - py)
    }

    private fun vecNodeDown(id: Int, sx: Float, sy: Float): Boolean {
        val hit0 = vecNodeAt(id, sx, sy)
        val hh = vecHandleAt(id, sx, sy)
        // ручка берётся, если она ближе ближайшего узла (короткая ручка у самого узла не мешает выбрать узел)
        val hit = if (hh != null && (hit0 == null || hh.second < vecNodeDist(id, hit0, sx, sy))) {
            nodeHandle = hh.first.second; nodeShape to hh.first.first
        } else { nodeHandle = -1; hit0 }
        if (hit == null) { nodeShape = -1; nodePt = -1; nodeHandle = -1; refreshPenOverlay(); return false }
        nodeShape = hit.first; nodePt = hit.second; nodeSelId = id
        nodeOriginal = media.firstOrNull { it.id == id } ?: return false
        nodeDraft = nodeOriginal!!.vectors
        nodeSelectionSnapshot = nodeOriginal!!.vectors.toList()
        rasterMode = 4
        swallowTap = true
        refreshPenOverlay()
        return true
    }

    private fun vecNodeMove(sx: Float, sy: Float) {
        val original = nodeOriginal ?: return
        if (selectedVectorId() != original.id || isLockedId(original.id) || media.firstOrNull { it.id == original.id } != original) return
        val draft = nodeDraft ?: return
        val shape = draft.getOrNull(nodeShape) ?: return
        if (nodePt !in 0 until shape.pts.size / 2) return
        val q = scene?.selectionQuads?.get(original.id) ?: return
        val p = unitOf(q, toCanvas(sx, sy)) ?: return
        nodeDraft = (if (nodeHandle >= 0) com.vibetoon.domain.vector.VectorBezier.moveHandle(draft, nodeShape, nodePt, nodeHandle, p.first * canvasW, p.second * canvasH)
            else com.vibetoon.domain.vector.VectorMath.moveNode(draft, nodeShape, nodePt, p.first * canvasW, p.second * canvasH)) ?: return
        refreshPenOverlay()
        val now = SystemClock.uptimeMillis()
        if (now - vecPreviewAt >= 70) { vecPreviewAt = now; renderVector(original.copy(vectors = nodeDraft!!), save = false) }
    }

    private fun vecNodeCancel() {
        val id = nodeOriginal?.id
        nodeOriginal = null; nodeDraft = null
        if (id != null) media.firstOrNull { it.id == id }?.let { renderVector(it, save = false) }
        refreshPenOverlay()
    }

    private fun vecNodeUp() {
        val original = nodeOriginal ?: return
        val draft = nodeDraft
        nodeOriginal = null; nodeDraft = null
        val current = media.firstOrNull { it.id == original.id }
        if (draft != null && draft != original.vectors && current == original && selectedVectorId() == original.id && !isLockedId(original.id)) {
            setVectors(original.id, draft)
            nodeSelectionSnapshot = draft.toList()
        } else current?.let { renderVector(it, save = false) }
        refreshPenOverlay()
    }

    private fun canEditSelectedVectorNode(): Boolean {
        val item = media.firstOrNull { it.id == nodeSelId } ?: return false
        return selectedVectorId() == nodeSelId && !isLockedId(nodeSelId) &&
            com.vibetoon.domain.vector.VectorMath.selectionStillValid(nodeSelectionSnapshot, item.vectors, nodeShape, nodePt)
    }

    fun restyleSelectedVector() {
        if (!canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        val item = media.firstOrNull { it.id == nodeSelId } ?: return
        val shape = item.vectors.getOrNull(nodeShape) ?: return
        val updated = com.vibetoon.domain.vector.VectorMath.restyle(shape, vecStroke(), brush.value.size) ?: return
        if (shape == updated) return
        val result = item.vectors.toMutableList().also { it[nodeShape] = updated }
        setVectors(nodeSelId, result)
        nodeSelectionSnapshot = result.toList()
        _state.value = UiState.Message("Цвет и толщина контура обновлены")
    }

    /** Удаляет выбранный узел примитива (пока остаётся минимум точек); у линии, рамки и эллипса узел удалить нельзя - удаляется весь примитив. */
    fun deleteVectorNode() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || !canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        val s = media[i].vectors.getOrNull(nodeShape) ?: return
        if (nodePt !in 0 until s.pts.size / 2) { _state.value = UiState.Message("Сначала коснитесь узла примитива"); return }
        if (s.kind == com.vibetoon.domain.vector.VecKind.BOOL) { _state.value = UiState.Message("Узлы фигуры булевой операции можно двигать; чтобы удалить узел, сначала нажмите «Разобрать»"); return }
        val canDrop = (s.kind == com.vibetoon.domain.vector.VecKind.PATH || s.kind == com.vibetoon.domain.vector.VecKind.POLY || s.kind == com.vibetoon.domain.vector.VecKind.POLYGON) &&
            s.pts.size / 2 > (if (s.kind == com.vibetoon.domain.vector.VecKind.POLYGON) 3 else 2)
        if (!canDrop) { deleteVectorShape(); return }
        setVectors(nodeSelId, media[i].vectors.toMutableList().also { it[nodeShape] = com.vibetoon.domain.vector.VectorBezier.withoutNode(s, nodePt) })
        nodePt = -1
        refreshPenOverlay()
    }

    /**
     * Пакет 18: булева операция выбранной фигуры (узел которой выбран) с фигурой под ней в списке слоя:
     * union / subtract (нижняя минус выбранная) / intersect / exclude. Undo/Redo - как у любой правки вектора.
     */
    fun booleanVector(op: String) {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || !canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        val r = com.vibetoon.domain.vector.VectorBool.combine(media[i].vectors, nodeShape, op)
        when (r.error) {
            null -> {
                setVectors(nodeSelId, r.shapes)
                nodeShape = r.index; nodePt = 0
                nodeSelectionSnapshot = r.shapes.toList()
                refreshPenOverlay()
                _state.value = UiState.Message(when (op) {
                    com.vibetoon.domain.vector.VectorBool.UNION -> "Фигуры объединены"
                    com.vibetoon.domain.vector.VectorBool.SUBTRACT -> "Выбранная фигура вычтена из фигуры под ней"
                    com.vibetoon.domain.vector.VectorBool.INTERSECT -> "Осталось пересечение фигур"
                    else -> "Осталось то, что не перекрывается"
                })
            }
            com.vibetoon.domain.vector.VectorBool.Error.NO_SHAPE_BELOW -> _state.value = UiState.Message("Под выбранной фигурой нет другой фигуры: операция берёт выбранную и предыдущую нарисованную")
            com.vibetoon.domain.vector.VectorBool.Error.NOT_AREA -> _state.value = UiState.Message("Линии и ломаные не участвуют в булевых операциях: нужны замкнутые фигуры")
            else -> _state.value = UiState.Message("Сначала коснитесь узла примитива")
        }
    }

    /** Пакет 18: «Разобрать» - фигура булевой операции снова становится двумя фигурами с текущими узлами. */
    fun releaseVectorShape() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || !canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        val r = com.vibetoon.domain.vector.VectorBool.release(media[i].vectors, nodeShape)
        if (r.error != null) { _state.value = UiState.Message("Разобрать можно только фигуру булевой операции"); return }
        setVectors(nodeSelId, r.shapes)
        nodeShape = -1; nodePt = -1
        nodeSelectionSnapshot = r.shapes.toList()
        refreshPenOverlay()
        _state.value = UiState.Message("Фигура разобрана на две")
    }

    /** Пакет 19: «Сгладить узел» / «Угол» / «Сгладить контур» - ручки Безье контуров пера. */
    fun bezierVectorNode(action: Int) {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || !canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        val s = media[i].vectors.getOrNull(nodeShape) ?: return
        if (s.kind == com.vibetoon.domain.vector.VecKind.BOOL) { _state.value = UiState.Message("У фигуры булевой операции ручки не правятся: сначала нажмите «Разобрать»"); return }
        if (!com.vibetoon.domain.vector.VectorBezier.supports(s)) { _state.value = UiState.Message("Ручки кривых есть только у контуров пера; у линии, прямоугольника, эллипса и фигур их нет"); return }
        val updated = when (action) {
            0 -> com.vibetoon.domain.vector.VectorBezier.smoothNode(s, nodePt)
            1 -> com.vibetoon.domain.vector.VectorBezier.cornerNode(s, nodePt)
            3 -> com.vibetoon.domain.vector.VectorBezier.setFree(s, nodePt, !com.vibetoon.domain.vector.VectorBezier.isFree(s, nodePt))   // пакет 21
            else -> com.vibetoon.domain.vector.VectorBezier.smoothAll(s)
        }
        if (updated == null || updated == s) {
            _state.value = UiState.Message(when (action) {
                1 -> "У узла уже нет ручек"
                3 -> "У узла нет ручек: сначала «Сгладить узел» или вытяните ручки пером"
                else -> "Узел нельзя сгладить: нужен соседний узел в другом месте"
            })
            return
        }
        val result = media[i].vectors.toMutableList().also { it[nodeShape] = updated }
        setVectors(nodeSelId, result)
        nodeSelectionSnapshot = result.toList()
        nodeHandle = -1
        refreshPenOverlay()
        _state.value = UiState.Message(when (action) {
            0 -> "Узел сглажен: потяните ручку, чтобы изменить изгиб"
            1 -> "Узел стал углом"
            3 -> if (com.vibetoon.domain.vector.VectorBezier.isFree(updated, nodePt)) "Ручки узла независимы: каждая двигается отдельно" else "Ручки узла связаны: вторая снова напротив первой"
            else -> "Контур сглажен"
        })
    }

    fun deleteVectorShape() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || !canEditSelectedVectorNode()) { _state.value = UiState.Message("Сначала коснитесь узла выбранного незаблокированного слоя"); return }
        setVectors(nodeSelId, media[i].vectors.filterIndexed { k, _ -> k != nodeShape })
        nodeShape = -1; nodePt = -1
        refreshPenOverlay()
    }

    // ---- Спринт 36: наклон / искажение / свободная трансформация векторных фигур (рамка как у выделения) ----

    private var vecBox: com.vibetoon.domain.raster.TransformBox.Box? = null
    private var vecBoxStart: com.vibetoon.domain.raster.TransformBox.Box? = null
    private var vecBoxFrom: com.vibetoon.domain.raster.TransformBox.P? = null
    private var vecBoxHandle: com.vibetoon.domain.raster.TransformBox.Handle? = null
    private var vecBoxLayer = -1
    /** Индексы фигур в рамке; пусто - весь слой. */
    private var vecBoxIndices: Set<Int> = emptySet()
    private var vecBoxShapes: List<com.vibetoon.domain.vector.VectorShape>? = null
    private val _vecTransform = MutableStateFlow<FloatArray?>(null)
    /** Рамка трансформации векторных фигур на экране: 4 угла и опора (10 чисел); null - рамки нет. */
    val vecTransform: StateFlow<FloatArray?> = _vecTransform.asStateFlow()

    /**
     * Рамка вокруг выбранной фигуры ([wholeLayer] = false, нужен выбранный узел) или всех фигур слоя. Режимы рамки
     * («Свободно», «Наклон», «Искажение»), «Пропорционально» и опорная точка - общие с рамкой выделения.
     */
    fun beginVectorTransform(wholeLayer: Boolean) {
        if (_playing.value) return
        val id = selectedVectorId() ?: run { _state.value = UiState.Message("Выберите векторный слой"); return }
        if (isLockedId(id)) { lockedNotice(); return }
        val m = media.firstOrNull { it.id == id } ?: return
        if (m.vectors.isEmpty()) { _state.value = UiState.Message("В векторном слое нет фигур"); return }
        penCancel()
        val idx: Set<Int> = if (wholeLayer) emptySet() else {
            if (!canEditSelectedVectorNode() || nodeShape !in m.vectors.indices) { _state.value = UiState.Message("Сначала коснитесь узла фигуры (или выберите «Все фигуры слоя»)"); return }
            setOf(nodeShape)
        }
        val b = com.vibetoon.domain.vector.VectorWarp.bounds(m.vectors, idx) ?: return
        val x0 = kotlin.math.floor(b[0]).toInt(); val y0 = kotlin.math.floor(b[1]).toInt()
        val rect = com.vibetoon.domain.raster.IRect(x0, y0, (kotlin.math.ceil(b[2]).toInt() - x0).coerceAtLeast(1), (kotlin.math.ceil(b[3]).toInt() - y0).coerceAtLeast(1))
        vecBox = com.vibetoon.domain.raster.TransformBox.initial(rect); vecBoxStart = null; vecBoxHandle = null; vecBoxFrom = null
        vecBoxLayer = id; vecBoxIndices = idx; vecBoxShapes = m.vectors
        _state.value = UiState.Message("Трансформ фигур: углы и стороны меняют размер, наклон или искажение; внутри рамки - перенос, снаружи - поворот; «Применить» сохраняет")
        refreshVecBoxOverlay(); refreshPenOverlay()
    }

    fun cancelVectorTransform() {
        val id = vecBoxLayer
        val had = vecBox != null
        vecBox = null; vecBoxStart = null; vecBoxFrom = null; vecBoxHandle = null; vecBoxLayer = -1; vecBoxIndices = emptySet(); vecBoxShapes = null
        _vecTransform.value = null
        if (rasterMode == 8) rasterMode = 0
        if (had) media.firstOrNull { it.id == id }?.let { renderVector(it, save = false) }   // убрать предпросмотр
    }

    fun setVecTransformAnchor(ax: Int, ay: Int) {
        val b = vecBox ?: return
        vecBox = com.vibetoon.domain.raster.TransformBox.withAnchor(b, ax, ay); refreshVecBoxOverlay()
    }

    /** Числа (масштаб, поворот, наклон X/Y, сдвиг, отражение) поверх текущей рамки относительно опоры. */
    fun applyVecTransformNumeric(n: com.vibetoon.domain.raster.TransformBox.Numeric) {
        val b = vecBox ?: return
        val r = com.vibetoon.domain.raster.TransformBox.numeric(b, n)
        if (r == null || !com.vibetoon.domain.raster.TransformBox.valid(r)) { _state.value = UiState.Message("Недопустимые значения трансформации"); return }
        vecBox = r; refreshVecBoxOverlay(); previewVecBox(force = true)
    }

    /** Фигуры через текущую рамку (null - рамка вырождена или фигуры не переводятся). */
    private fun vecBoxResult(): List<com.vibetoon.domain.vector.VectorShape>? {
        val b = vecBox ?: return null
        val shapes = vecBoxShapes ?: return null
        if (!com.vibetoon.domain.raster.TransformBox.valid(b)) return null
        val f = com.vibetoon.domain.vector.VectorWarp.boxMap(b) ?: return null
        return com.vibetoon.domain.vector.VectorWarp.apply(shapes, vecBoxIndices, f, com.vibetoon.domain.vector.VectorWarp.widthScaleFor(b))
    }

    private fun previewVecBox(force: Boolean = false) {
        val m = media.firstOrNull { it.id == vecBoxLayer } ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - vecPreviewAt < 70) return
        vecPreviewAt = now
        renderVector(m.copy(vectors = vecBoxResult() ?: return), save = false)
    }

    /** Сохраняет фигуры по рамке одной правкой (Undo/Redo, файл проекта); при ошибке рамка остаётся для правки. */
    fun applyVectorTransform() {
        val b = vecBox ?: return
        val id = vecBoxLayer
        val m = media.firstOrNull { it.id == id }
        if (m == null || selectedVectorId() != id || isLockedId(id) || m.vectors != vecBoxShapes) {
            cancelVectorTransform(); _state.value = UiState.Message("Слой изменился: трансформация отменена"); return
        }
        if (com.vibetoon.domain.raster.TransformBox.isIdentity(b)) { cancelVectorTransform(); return }
        if (!com.vibetoon.domain.raster.TransformBox.valid(b)) { _state.value = UiState.Message("Рамка вырождена, перекручена или слишком мала/велика: исправьте её или отмените"); return }
        val result = vecBoxResult() ?: run { _state.value = UiState.Message("Фигуры нельзя так исказить: уменьшите искажение"); return }
        vecBox = null; vecBoxStart = null; vecBoxFrom = null; vecBoxHandle = null; vecBoxLayer = -1; vecBoxIndices = emptySet(); vecBoxShapes = null
        _vecTransform.value = null
        setVectors(id, result)
        nodeSelectionSnapshot = result.toList()
        nodeHandle = -1
        refreshPenOverlay()
        _state.value = UiState.Message("Фигуры трансформированы: прямоугольники, эллипсы и фигуры стали контурами, их узлы правятся пером")
    }

    private fun vecBoxCanvas(id: Int, sx: Float, sy: Float): com.vibetoon.domain.raster.TransformBox.P? {
        val q = scene?.selectionQuads?.get(id) ?: return null
        val p = unitOf(q, toCanvas(sx, sy)) ?: return null
        return com.vibetoon.domain.raster.TransformBox.P(p.first.toDouble() * canvasW, p.second.toDouble() * canvasH)
    }

    private fun vecBoxDown(sx: Float, sy: Float): Boolean {
        val box = vecBox ?: return false
        swallowTap = true
        val id = vecBoxLayer
        if (selectedVectorId() != id) { cancelVectorTransform(); return true }
        val from = vecBoxCanvas(id, sx, sy) ?: return true
        val p1 = vecBoxCanvas(id, sx + 24f * getApplication<Application>().resources.displayMetrics.density, sy)
        val radius = if (p1 == null) 12.0 else kotlin.math.hypot(p1.x - from.x, p1.y - from.y).coerceAtLeast(2.0)
        vecBoxHandle = com.vibetoon.domain.raster.TransformBox.hit(box, from, radius); vecBoxStart = box; vecBoxFrom = from
        rasterMode = 8
        return true
    }

    private fun vecBoxMove(sx: Float, sy: Float) {
        val start = vecBoxStart ?: return; val from = vecBoxFrom ?: return; val handle = vecBoxHandle ?: return
        val to = vecBoxCanvas(vecBoxLayer, sx, sy) ?: return
        vecBox = com.vibetoon.domain.raster.TransformBox.drag(start, handle, _selTransformMode.value, from, to, _selTransformProportional.value)
        refreshVecBoxOverlay()
        previewVecBox()
    }

    private fun refreshVecBoxOverlay() {
        val box = vecBox
        if (box == null) { if (_vecTransform.value != null) _vecTransform.value = null; return }
        if (_rasterTool.value != RasterTool.PATH || selectedVectorId() != vecBoxLayer) { cancelVectorTransform(); return }
        val q = scene?.selectionQuads?.get(vecBoxLayer) ?: run { cancelVectorTransform(); return }
        _vecTransform.value = FloatArray(10).also { out ->
            (box.corners + box.pivot).forEachIndexed { i, pt ->
                val sc = canvasToScreen(q, pt.x.toFloat(), pt.y.toFloat()); out[i * 2] = sc.first; out[i * 2 + 1] = sc.second
            }
        }
    }

    // ---- Спринт 36: «Вставить SVG…» в открытый проект ----

    private val _svgNotes = MutableStateFlow<List<String>>(emptyList())
    /** Итог последней вставки SVG: что не перенесено (пусто - заметок нет / окно закрыто). */
    val svgNotes: StateFlow<List<String>> = _svgNotes.asStateFlow()
    fun dismissSvgNotes() { _svgNotes.value = emptyList() }

    /**
     * Фигуры файла SVG - новыми векторными слоями (группа верхнего уровня - слой): по центру холста, крупный рисунок
     * уменьшается до холста. Одна операция Undo/Redo; что не перенесено - в окне итога.
     */
    fun insertSvg(uri: android.net.Uri, fileName: String = "") {
        val pid = projectId ?: return
        if (_playing.value) return
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val shown = fileName.ifBlank {
                        runCatching {
                            getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        }.getOrNull().orEmpty()
                    }
                    val title = shown.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { "SVG" }
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val k = input.read(buf); if (k < 0) break
                            out.write(buf, 0, k)
                            if (out.size() > 64 * 1024 * 1024) throw com.vibetoon.domain.vector.SvgImport.SvgException(com.vibetoon.domain.vector.SvgImport.MSG_TOO_BIG)
                        }
                        out.toByteArray()
                    } ?: throw java.io.IOException("open")
                    val text = bytes.toString(Charsets.UTF_8)
                    if (!com.vibetoon.domain.vector.SvgImport.looksLike(text)) throw com.vibetoon.domain.vector.SvgImport.SvgException(com.vibetoon.domain.vector.SvgImport.MSG_NOT_SVG)
                    val r = com.vibetoon.domain.vector.SvgImport.parse(text, title)
                    r to com.vibetoon.domain.vector.SvgImport.fit(r, canvasW, canvasH)
                }
            }
            if (projectId != pid || closing) return@launch
            val (r, layersIn) = parsed.getOrElse { e ->
                _state.value = UiState.Message(when (e) {
                    is com.vibetoon.domain.vector.SvgImport.SvgException -> e.message ?: com.vibetoon.domain.vector.SvgImport.MSG_DAMAGED
                    is OutOfMemoryError -> com.vibetoon.domain.vector.SvgImport.MSG_TOO_BIG
                    else -> "Не удалось прочитать выбранный файл"
                })
                return@launch
            }
            if (layersIn.isEmpty()) { _state.value = UiState.Message(com.vibetoon.domain.vector.SvgImport.MSG_EMPTY); return@launch }
            val scale = minOf(1f, 1536f / maxOf(canvasW, canvasH))
            val bw = (canvasW * scale).toInt().coerceAtLeast(1)
            val bh = (canvasH * scale).toInt().coerceAtLeast(1)
            checkpoint("svg", force = true)
            val added = ArrayList<MediaItem>()
            for (l in layersIn) {
                val id = nextMediaId()
                val item = MediaItem(id, MediaKind.IMAGE, l.name, "assets/svg_${id}_${java.util.UUID.randomUUID().toString().take(8)}.png", bw, bh, 0f, 0f,
                    canvasW.toFloat(), canvasH.toFloat(), vector = true, vectors = l.shapes)
                media += item
                added += item
            }
            rebuildLayers(); pushScene()
            for (item in added) renderVector(item, save = true)
            selectLayer(added.last().id); markDirty()
            _state.value = UiState.Message("SVG вставлен: слоёв: ${added.size}, фигур: ${added.sumOf { it.vectors.size }}")
            _svgNotes.value = r.notes
        }
    }

    private fun penDown(sx: Float, sy: Float): Boolean {
        if (vecBox != null) return vecBoxDown(sx, sy)   // спринт 36: рамка трансформации фигур забирает касание
        if (penPts.isEmpty()) selectedVectorId()?.let { vid -> if (!isLockedId(vid) && vecNodeDown(vid, sx, sy)) return true }
        val id = selectedVectorId() ?: run {
            _state.value = UiState.Message("Перо рисует на векторном слое: «＋» → «Векторный», затем снова выберите «Путь»")
            return false
        }
        if (isLockedId(id)) { lockedNotice(); return false }
        val q = scene?.selectionQuads?.get(id) ?: return false
        val p = unitOf(q, toCanvas(sx, sy)) ?: return false
        swallowTap = true
        val cx = p.first * canvasW; val cy = p.second * canvasH
        if (penPts.size >= 6) {
            // тап по первой точке замыкает контур
            val fxy = toScreen(q[0] + (penPts[0] / canvasW) * (q[2] - q[0]) + (penPts[1] / canvasH) * (q[6] - q[0]), q[1] + (penPts[0] / canvasW) * (q[3] - q[1]) + (penPts[1] / canvasH) * (q[7] - q[1]))
            val fx = fxy.first; val fy = fxy.second
            if (kotlin.math.hypot(sx - fx, sy - fy) < 28f) { penFinish(true); return true }
        }
        penPts += cx; penPts += cy
        penHandles = penHandles.take(penPts.size * 2 - 4) + listOf(0f, 0f, 0f, 0f)
        penDragX = cx; penDragY = cy
        rasterMode = 6   // протяжка вытягивает ручки этой точки
        _penCount.value = penPts.size / 2
        refreshPenOverlay()
        return true
    }

    /** Пакет 20: протяжка после постановки точки - выход ручки по пальцу, вход зеркально (короткая протяжка - без ручек). */
    private fun penDragMove(sx: Float, sy: Float) {
        val id = selectedVectorId() ?: return
        val q = scene?.selectionQuads?.get(id) ?: return
        val p = unitOf(q, toCanvas(sx, sy)) ?: return
        val k = penPts.size / 2 - 1
        if (k < 0) return
        val (ax, ay) = canvasToScreen(q, penDragX, penDragY)
        val minScreen = 10f * getApplication<Application>().resources.displayMetrics.density
        val dx = p.first * canvasW - penDragX; val dy = p.second * canvasH - penDragY
        penHandles = if (kotlin.math.hypot(sx - ax, sy - ay) < minScreen) com.vibetoon.domain.vector.VectorBezier.penPull(penHandles, k, 0f, 0f, 1f)
            else com.vibetoon.domain.vector.VectorBezier.penPull(penHandles, k, dx, dy, 0f)
        refreshPenOverlay()
    }

    /** Жест прерван (второй палец): ручки последней точки сбрасываются. */
    private fun penDragCancel() {
        val k = penPts.size / 2 - 1
        if (k >= 0) penHandles = com.vibetoon.domain.vector.VectorBezier.penPull(penHandles, k, 0f, 0f, 1f)
        refreshPenOverlay()
    }

    fun penFinish(closed: Boolean) {
        val id = selectedVectorId() ?: return
        if (isLockedId(id)) { lockedNotice(); return }
        val m = media.firstOrNull { it.id == id } ?: return
        if (penPts.size < 4) { _state.value = UiState.Message("Для контура нужно хотя бы две точки"); return }
        val c = vecStroke()
        val fill = if (closed && _vecFill.value) (c and 0x00FFFFFFL) or 0xFF000000L else 0L
        val shape = com.vibetoon.domain.vector.VectorBezier.fromPen(closed, penPts.toList(), penHandles, c, fill, brush.value.size)
        penPts.clear(); penHandles = emptyList(); _penCount.value = 0; _toolDrag.value = null
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, shape))
        _state.value = UiState.Message(if (closed) "Контур замкнут" else "Контур добавлен")
    }

    fun penUndoPoint() {
        if (penPts.size >= 2) { penPts.removeAt(penPts.size - 1); penPts.removeAt(penPts.size - 1) }
        penHandles = penHandles.take(penPts.size * 2)
        _penCount.value = penPts.size / 2
        refreshPenOverlay()
    }

    fun penCancel() { penPts.clear(); penHandles = emptyList(); _penCount.value = 0; _toolDrag.value = null }

    private fun refreshPenOverlay() {
        val id = selectedVectorId()
        val q = if (id != null) scene?.selectionQuads?.get(id) else null
        if (q != null && penPts.isEmpty() && _rasterTool.value == RasterTool.PATH && id != null) {
            // нет контура в работе: показываем узлы готовых примитивов, выбранный подсвечен
            val m = media.firstOrNull { it.id == id }
            val shapes = if (nodeOriginal?.id == id) nodeDraft.orEmpty() else m?.vectors.orEmpty()
            if (shapes.isNotEmpty()) {
                val flat = ArrayList<Float>(); var sel = -1
                for ((i, s) in shapes.withIndex()) for (k in 0 until s.pts.size / 2) {
                    val (px, py) = canvasToScreen(q, s.pts[k * 2], s.pts[k * 2 + 1])
                    if (i == nodeShape && k == nodePt && id == nodeSelId) sel = flat.size / 2
                    flat += px; flat += py
                }
                // пакет 19: ручки Безье выбранного контура
                val hs = ArrayList<Float>(); var selH = -1; val free = ArrayList<Boolean>()
                shapes.getOrNull(nodeShape)?.takeIf { id == nodeSelId && com.vibetoon.domain.vector.VectorBezier.hasHandles(it) }?.let { s ->
                    for (k in 0 until s.pts.size / 2) {
                        if (!com.vibetoon.domain.vector.VectorBezier.nodeHasHandles(s, k)) continue
                        if (k == nodePt && nodeHandle >= 0) selH = (hs.size / 6) * 2 + nodeHandle
                        free += com.vibetoon.domain.vector.VectorBezier.isFree(s, k)
                        val (nx, ny) = canvasToScreen(q, s.pts[k * 2], s.pts[k * 2 + 1]); hs += nx; hs += ny
                        for (w in 0..1) { val (hx, hy) = com.vibetoon.domain.vector.VectorBezier.handleAt(s, k, w)!!; val (px, py) = canvasToScreen(q, hx, hy); hs += px; hs += py }
                    }
                }
                _toolDrag.value = ToolDrag(4, flat.toFloatArray(), sel = sel, handles = if (hs.isEmpty()) null else hs.toFloatArray(), selHandle = selH, freeHandles = free.toBooleanArray())
                return
            }
        }
        if (q == null || penPts.isEmpty() || _rasterTool.value != RasterTool.PATH) { if (_toolDrag.value?.kind == 3 || _toolDrag.value?.kind == 4) _toolDrag.value = null; return }
        val out = FloatArray(penPts.size)
        for (i in 0 until penPts.size / 2) {
            val u = penPts[i * 2] / canvasW; val v = penPts[i * 2 + 1] / canvasH
            toScreen(q[0] + u * (q[2] - q[0]) + v * (q[6] - q[0]), q[1] + u * (q[3] - q[1]) + v * (q[7] - q[1])).let { out[i * 2] = it.first; out[i * 2 + 1] = it.second }
        }
        // пакет 20: контур в работе с ручками - кривая и ручки на экране
        val pen = com.vibetoon.domain.vector.VectorBezier.fromPen(false, penPts.toList(), penHandles, 0L, 0L, 0f)
        if (!com.vibetoon.domain.vector.VectorBezier.hasHandles(pen)) { _toolDrag.value = ToolDrag(3, out); return }
        val flat = com.vibetoon.domain.vector.VectorBezier.flatten(pen, 16)
        val curve = FloatArray(flat.size)
        for (i in 0 until flat.size / 2) canvasToScreen(q, flat[i * 2], flat[i * 2 + 1]).let { curve[i * 2] = it.first; curve[i * 2 + 1] = it.second }
        val hs = ArrayList<Float>()
        for (k in 0 until penPts.size / 2) {
            if (!com.vibetoon.domain.vector.VectorBezier.nodeHasHandles(pen, k)) continue
            hs += out[k * 2]; hs += out[k * 2 + 1]
            for (w in 0..1) { val (hx, hy) = com.vibetoon.domain.vector.VectorBezier.handleAt(pen, k, w)!!; val (px, py) = canvasToScreen(q, hx, hy); hs += px; hs += py }
        }
        _toolDrag.value = ToolDrag(3, out, handles = hs.toFloatArray(), curve = curve)
    }

    /** Обновление экранных оверлеев растровых инструментов при смене сцены и камеры. */
    private fun refreshRasterOverlays() {
        if (_rasterTool.value == null) return
        refreshSelOverlay(); refreshPenOverlay(); refreshVecBoxOverlay()
    }

    // ---------------------------------------------------------------- Puppet Mesh (пины)

    private var pinLayers: Map<Int, List<Pin>> = emptyMap()
    private var pinSel = -1
    private var pinsStiffDefault = false
    private var pinRadiusDefault = 180f
    private val PIN_GRAB = 50_000
    /** Спринт 43: насколько (в долях слоя) точку пути можно вынести за край слоя. */
    private val PIN_OUTSIDE = 0.5f
    private val _pinOverlay = MutableStateFlow(PinOverlay())
    val pinOverlay: StateFlow<PinOverlay> = _pinOverlay.asStateFlow()
    private val _pinInfo = MutableStateFlow(PinInfo())
    /** Состояние панели пинов: число пинов, номер выбранного, тип и радиус. */
    val pinInfo: StateFlow<PinInfo> = _pinInfo.asStateFlow()

    private fun layerSize(id: Int): Pair<Float, Float>? = layers.firstOrNull { it.id == id }?.let { it.width.toFloat() to it.height.toFloat() }

    /** Смещения вершин слоя с учётом ручных правок вершин и пинов на кадре. */
    private fun fullMeshOffsets(id: Int, m: Mesh, frame: Int, fx: Boolean): FloatArray {
        val v = brushLive?.takeIf { it.first == id && it.second.size == m.count * 2 }?.second ?: MeshMath.offsets(id, m, frame, base, _animation.value, fx)
        val pins = pinLayers[id] ?: return v
        val (w, h) = layerSize(id) ?: return v
        val poff = PuppetMath.offsets(id, pins.size, frame, base, _animation.value, fx)
        // pushScene вызывается на каждый кадр и жест; ARAP-решение пересчитывается только если сетка, пины или их смещения изменились
        // спринт 43: путь через пины считается с учётом масштаба слоя (нормали верны у растянутого слоя)
        val lt = effective(id); val sx = lt.scaleX; val sy = lt.scaleY
        val hit = arapMemo[id]
        if (hit != null && hit.mesh === m && hit.pins === pins && hit.w == w && hit.h == h && hit.sx == sx && hit.sy == sy && hit.v.contentEquals(v) && hit.poff.contentEquals(poff)) return hit.out
        val out = PuppetMath.addTo(m, v, pins, poff, w, h, sx, sy)
        arapMemo[id] = ArapMemo(m, pins, v.copyOf(), poff, w, h, sx, sy, out)
        return out
    }

    private class ArapMemo(val mesh: Mesh, val pins: List<Pin>, val v: FloatArray, val poff: FloatArray, val w: Float, val h: Float, val sx: Float, val sy: Float, val out: FloatArray)
    private val arapMemo = HashMap<Int, ArapMemo>()

    private fun refreshPinInfo() {
        val t = warpTarget()
        val pins = if (t != null) pinLayers[t].orEmpty() else emptyList()
        if (pinSel >= pins.size) pinSel = pins.size - 1
        val sel = pins.getOrNull(pinSel)
        _pinInfo.value = PinInfo(pins.size, pinSel, sel?.stiff ?: pinsStiffDefault, sel?.radius ?: pinRadiusDefault)
    }

    private fun refreshPinOverlay() {
        val t = warpTarget()
        val q = if (t != null) scene?.selectionQuads?.get(t) else null
        val pins = if (t != null) pinLayers[t] else null
        if (!_meshMode.value || _boneMode.value || t == null || q == null || pins.isNullOrEmpty()) {
            if (_pinOverlay.value.points.isNotEmpty()) _pinOverlay.value = PinOverlay()
            refreshPinInfo()
            return
        }
        val off = PuppetMath.offsets(t, pins.size, _frame.value, base, _animation.value, _fx.value)
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val (lw, _) = layerSize(t) ?: (1f to 1f)
        val screenW = kotlin.math.hypot(toScreen(q[2], q[3]).first - toScreen(q[0], q[1]).first, toScreen(q[2], q[3]).second - toScreen(q[0], q[1]).second)
        val pts = FloatArray(pins.size * 2)
        val rad = FloatArray(pins.size)
        for ((i, p) in pins.withIndex()) {
            val u = p.u + off[i * 2]; val v = p.v + off[i * 2 + 1]
            toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy).let { pts[i * 2] = it.first; pts[i * 2 + 1] = it.second }
            rad[i] = p.radius / lw * screenW
        }
        _pinOverlay.value = PinOverlay(pts, rad, BooleanArray(pins.size) { pins[it].stiff }, pinSel)
        refreshPinInfo()
    }

    /** Пишет смещение пина: FX выкл - форма привязки, FX вкл - ключ на текущем кадре (дельта к привязке). */
    private fun setPinOffset(t: Int, k: Int, ox: Float, oy: Float) {
        val id = PuppetMath.pinId(t, k)
        if (!_fx.value) base[id] = LayerTransform(dx = ox, dy = oy)
        else {
            val bind = base[id]
            // спринт 41: автоключ пинов - исходное положение на кадре 0, остальные пины фиксируются на кадре
            val group = (0 until (pinLayers[t]?.size ?: (k + 1))).map { PuppetMath.pinId(t, it) }
            _animation.value = com.vibetoon.domain.anim.DeformAutoKey.key(_animation.value, group, _frame.value,
                mapOf(id to LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f))))
        }
    }

    private fun pinDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        val t = warpTarget() ?: return false
        val pins = pinLayers[t] ?: return false
        if (_playing.value) return false
        val ov = _pinOverlay.value
        var best = -1; var bd = 64f
        for (i in pins.indices) {
            val d = kotlin.math.hypot(sx - ov.points.getOrElse(i * 2) { -9999f }, sy - ov.points.getOrElse(i * 2 + 1) { -9999f })
            if (d < bd) { bd = d; best = i }
        }
        if (best < 0) return false
        pinSel = best
        grab = PIN_GRAB + best
        swallowTap = true
        checkpoint("pin-move", force = true)
        refreshPinOverlay()
        return true
    }

    private fun pinMove(sx: Float, sy: Float) {
        val k = grab - PIN_GRAB
        val t = warpTarget() ?: return
        val pins = pinLayers[t] ?: return
        val p = pins.getOrNull(k) ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        // смещение, при котором пин оказывается под пальцем (в долях слоя), с защитой от «улёта»
        setPinOffset(t, k, (u - p.u).coerceIn(-1.5f, 1.5f), (v - p.v).coerceIn(-1.5f, 1.5f))
        pushScene()
        markDirty()
    }

    /** Add Point: тап ставит пин (если сетки нет, она создаётся по контуру слоя). Пин привязывается к исходной точке под пальцем. */
    private fun pinAddAt(sx: Float, sy: Float): Boolean {
        val t = warpTarget() ?: return false
        if (_playing.value) return false
        val m = meshLayers[t]
        if (m == null) { generateMeshContour(); _state.value = UiState.Message("Сетка создана по контуру: тапните ещё раз, чтобы поставить пин"); swallowTap = true; return true }
        val q = scene?.selectionQuads?.get(t) ?: return false
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return false
        // спринт 43: точку пути можно поставить и вне сетки/слоя (корень пряди «в голове» - мягкий корень, как в Live2D)
        if (u !in -PIN_OUTSIDE..(1f + PIN_OUTSIDE) || v !in -PIN_OUTSIDE..(1f + PIN_OUTSIDE)) return false
        val cur = pinLayers[t].orEmpty()
        if (cur.size >= PuppetMath.MAX_PINS) { _state.value = UiState.Message("Не больше ${PuppetMath.MAX_PINS} пинов"); return true }
        val (w, h) = layerSize(t) ?: return false
        val off = PuppetMath.offsets(t, cur.size, _frame.value, base, _animation.value, _fx.value)
        val d = PuppetMath.fieldAt(u, v, cur, off, w, h)
        checkpoint("pin-add", force = true)
        pinLayers = pinLayers + (t to cur + Pin((u - d[0]).coerceIn(-PIN_OUTSIDE, 1f + PIN_OUTSIDE), (v - d[1]).coerceIn(-PIN_OUTSIDE, 1f + PIN_OUTSIDE), pinsStiffDefault, pinRadiusDefault))
        pinSel = cur.size
        swallowTap = true
        pushScene(); markDirty()
        _state.value = UiState.Message("Пин ${cur.size + 1}: тяните его, рисунок следует за точкой")
        return true
    }

    /** Переносит смещения и ключи пинов слоя после удаления пина [removed] (индексы выше сдвигаются на 1). */
    private fun shiftPinData(t: Int, removed: Int, count: Int) {
        val tracks = HashMap(_animation.value.tracks)
        val baseCopy = HashMap<Int, LayerTransform>()
        val trackCopy = HashMap<Int, com.vibetoon.domain.anim.AnimationTrack>()
        for (k in 0 until count) {
            val id = PuppetMath.pinId(t, k)
            base.remove(id)?.let { baseCopy[k] = it }
            override.remove(id)
            tracks.remove(id)?.let { trackCopy[k] = it }
        }
        for ((k, tr) in trackCopy) if (k != removed) {
            val nk = if (k > removed) k - 1 else k
            val nid = PuppetMath.pinId(t, nk)
            tracks[nid] = com.vibetoon.domain.anim.AnimationTrack(nid, tr.keys)
        }
        for ((k, b) in baseCopy) if (k != removed) base[PuppetMath.pinId(t, if (k > removed) k - 1 else k)] = b
        _animation.value = _animation.value.copy(tracks = tracks)
    }

    fun pinDeleteSelected() {
        val t = warpTarget() ?: return
        val pins = pinLayers[t] ?: return
        val k = pinSel
        if (k !in pins.indices) { _state.value = UiState.Message("Выберите пин (тап по зелёной точке)"); return }
        checkpoint("pin-del", force = true)
        shiftPinData(t, k, pins.size)
        val rest = pins.filterIndexed { i, _ -> i != k }
        pinLayers = if (rest.isEmpty()) pinLayers - t else pinLayers + (t to rest)
        pinSel = (k - 1).coerceAtLeast(if (rest.isEmpty()) -1 else 0)
        pushScene(); markDirty()
    }

    fun pinDuplicate() {
        val t = warpTarget() ?: return
        val pins = pinLayers[t] ?: return
        val p = pins.getOrNull(pinSel) ?: run { _state.value = UiState.Message("Выберите пин"); return }
        if (pins.size >= PuppetMath.MAX_PINS) return
        checkpoint("pin-dup", force = true)
        val nk = pins.size
        pinLayers = pinLayers + (t to pins + p.copy(u = (p.u + 0.04f).coerceAtMost(1f), v = (p.v + 0.04f).coerceAtMost(1f)))
        // копия получает то же смещение, что у оригинала на этом кадре
        val off = PuppetMath.offsets(t, pins.size, _frame.value, base, _animation.value, false)
        base[PuppetMath.pinId(t, nk)] = LayerTransform(dx = off[pinSel * 2], dy = off[pinSel * 2 + 1])
        pinSel = nk
        pushScene(); markDirty()
    }

    /** Reset to rest: все пины слоя возвращаются в исходное положение (смещения и ключи пинов удаляются). */
    fun pinResetAll() {
        val t = warpTarget() ?: return
        val pins = pinLayers[t] ?: return
        checkpoint("pin-reset", force = true)
        val ids = (0 until pins.size).map { PuppetMath.pinId(t, it) }.toSet()
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        pushScene(); markDirty()
        _state.value = UiState.Message("Пины возвращены в исходное положение")
    }

    /** Move (мягкий) / Stiff (жёсткий) для выбранного пина и значение по умолчанию для новых. */
    fun setPinStiff(on: Boolean) {
        pinsStiffDefault = on
        val t = warpTarget()
        val pins = if (t != null) pinLayers[t] else null
        val p = pins?.getOrNull(pinSel)
        if (t != null && pins != null && p != null) {
            checkpoint("pin-type", force = true)
            pinLayers = pinLayers + (t to pins.toMutableList().also { it[pinSel] = p.copy(stiff = on) })
            pushScene(); markDirty()
        } else refreshPinInfo()
    }

    /** Радиус выбранного пина (и значение по умолчанию для новых) прямым значением, 30..2000: для регулятора «потяни». */
    fun setPinRadius(r: Float) {
        val nr = r.coerceIn(30f, 2000f)
        pinRadiusDefault = nr
        val t = warpTarget()
        val pins = if (t != null) pinLayers[t] else null
        val p = pins?.getOrNull(pinSel)
        if (t != null && pins != null && p != null) {
            checkpoint("pin-radius")
            pinLayers = pinLayers + (t to pins.toMutableList().also { it[pinSel] = p.copy(radius = nr) })
            pushScene(); markDirty()
        } else refreshPinInfo()
    }

    fun changePinRadius(sign: Float) {
        // шаг пропорционален радиусу: от 30 до 2000 за несколько нажатий
        val delta = sign * maxOf(30f, pinRadiusDefault * 0.2f)
        pinRadiusDefault = (pinRadiusDefault + delta).coerceIn(30f, 2000f)
        val t = warpTarget()
        val pins = if (t != null) pinLayers[t] else null
        val p = pins?.getOrNull(pinSel)
        if (t != null && pins != null && p != null) {
            checkpoint("pin-radius")
            pinLayers = pinLayers + (t to pins.toMutableList().also { it[pinSel] = p.copy(radius = (p.radius + sign * maxOf(30f, p.radius * 0.2f)).coerceIn(30f, 2000f)) })
            pushScene(); markDirty()
        } else refreshPinInfo()
    }

    /** Add Key для выбранного пина: ключ на текущем кадре с текущим смещением (FX должен быть включён). */
    fun pinAddKey() {
        val t = warpTarget() ?: return
        val pins = pinLayers[t] ?: return
        if (pinSel !in pins.indices || _playing.value) { _state.value = UiState.Message("Выберите пин"); return }
        if (!_fx.value) { _state.value = UiState.Message("Включите FX (кнопка со звёздами): ключи пинов пишутся в режиме анимации"); return }
        checkpoint("pin-key", force = true)
        val id = PuppetMath.pinId(t, pinSel)
        val cur = _animation.value.tracks[id]?.evaluate(_frame.value) ?: LayerTransform()
        _animation.value = com.vibetoon.domain.anim.DeformAutoKey.key(_animation.value, pins.indices.map { PuppetMath.pinId(t, it) }, _frame.value,
            mapOf(id to LayerTransform(dx = cur.dx, dy = cur.dy)))
        pushScene(); markDirty()
        _state.value = UiState.Message("Ключ пина на кадре ${_frame.value}")
    }

    private fun dropPinData(layerIds: Set<Int>) {
        for (t in layerIds) {
            val pins = pinLayers[t] ?: continue
            val ids = (0 until pins.size).map { PuppetMath.pinId(t, it) }.toSet()
            for (id in ids) { base.remove(id); override.remove(id) }
            _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
            pinLayers = pinLayers - t
        }
    }

    // ---------------------------------------------------------------- Mesh (треугольная сетка слоя)

    private var meshLayers: Map<Int, Mesh> = emptyMap()
    // ---------------------------------------------------------------- миниатюры слоёв

    /** Миниатюры слоёв 48x48 (id -> картинка); Compose перерисовывает строки при появлении новой. */
    val thumbs = androidx.compose.runtime.mutableStateMapOf<Int, androidx.compose.ui.graphics.ImageBitmap>()
    private val thumbPending = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /** Просит у GL-потока миниатюру слоя; если текстура ещё не загружена, вернётся позже (повторный вызов). */
    @Volatile private var thumbGen = 0

    fun requestThumb(id: Int) {
        if (thumbs.containsKey(id) || id >= BONE_ID_BASE) return
        if (!thumbPending.add(id)) return
        val view = RenderHost.view ?: run { thumbPending.remove(id); return }
        val size = 48
        val gen = thumbGen
        view.layerThumb(id, size) { bytes ->
            thumbPending.remove(id)
            if (gen != thumbGen) return@layerThumb
            if (bytes == null || bytes.size < size * size * 4) return@layerThumb
            val px = IntArray(size * size)
            for (i in px.indices) {
                val o = i * 4
                px[i] = ((bytes[o + 3].toInt() and 0xFF) shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
            }
            val bmp = android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.isPremultiplied = true
            viewModelScope.launch { thumbs[id] = bmp.asImageBitmap() }
        }
    }

    /** Содержимое слоя изменилось (текст, Liquify): миниатюра пересоздаётся. */
    private fun invalidateThumb(id: Int) { thumbs.remove(id); contentBox.remove(id); contentBoxScheduled(id) }

    // ---- границы содержимого слоя (по альфе): рамка выделения, лассо и привязка к костям работают по нарисованному, а не по всему холсту ----

    private val contentBox = java.util.concurrent.ConcurrentHashMap<Int, FloatArray>()   // пустой массив - слой пуст, [-1] - данных нет (не текстура)
    private val NO_BOX = floatArrayOf(-1f)

    private fun contentBoxScheduled(id: Int) {
        if (id >= BONE_ID_BASE && id < MEDIA_ID_BASE) return
        viewModelScope.launch { ensureBox(id); updateSelectionFrame() }
    }

    /** Считает границы содержимого слоя в фоне (карта альфы уже лежит в C++, читается без текстуры); результат кэшируется до следующей правки слоя. */
    private suspend fun ensureBox(id: Int): FloatArray {
        contentBox[id]?.let { return it }
        val view = RenderHost.view ?: return NO_BOX
        val bytes = kotlinx.coroutines.withTimeoutOrNull(3000) {
            kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.layerAlpha(id) { if (c.isActive) c.resumeWith(Result.success(it)) } }
        }
        val box = if (bytes == null || bytes.size <= 8) NO_BOX else withContext(Dispatchers.Default) {
            fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
            val w = i32(0); val h = i32(4)
            if (w <= 0 || h <= 0 || bytes.size < 8 + w * h) NO_BOX else com.vibetoon.domain.raster.RasterMath.alphaBox(bytes, 8, w, h)
        }
        contentBox[id] = box
        return box
    }

    /** Четырёхугольник содержимого слоя на экране холста: null - слой пуст; весь четырёхугольник слоя, если границы ещё не посчитаны или недоступны. */
    private fun contentQuad(id: Int, q: FloatArray): FloatArray? {
        val b = contentBox[id]
        if (b == null) { contentBoxScheduled(id); return q }
        if (b.isEmpty()) return null
        if (b.size < 4) return q
        return com.vibetoon.domain.raster.RasterMath.subQuad(q, b)
    }

    // ---------------------------------------------------------------- эффекты слоёв (FBO-цепочка)

    private var fxLayers: Map<Int, List<Effect>> = emptyMap()

    private fun copyEffectTracks(from: Int, to: Int) {
        if (from !in 0..com.vibetoon.domain.anim.EffectTracks.MAX_LAYER || to !in 0..com.vibetoon.domain.anim.EffectTracks.MAX_LAYER) return
        var anim = _animation.value
        for (id in TrackGroups.ids(from, anim.tracks).filter { com.vibetoon.domain.anim.EffectTracks.belongs(it, from) }) {
            val target = TrackGroups.remap(id, from, to) ?: continue
            val tr = anim.tracks[id] ?: continue
            anim = anim.copy(tracks = anim.tracks + (target to AnimationTrack(target, tr.keys)))
        }
        _animation.value = anim
    }
    private fun selectedEffectGroup(): Int? {
        val id = _selected.value ?: return null
        if (_selection.value.size != 1) return null
        val layer = layers.firstOrNull { it.id == id } ?: return null
        return id.takeIf { layer.kind == LayerKind.GROUP_OPEN || layer.kind == LayerKind.GROUP_CLOSED }
    }

    private fun groupEffectPlan(id: Int, allowAnimated: Boolean = false): com.vibetoon.domain.anim.GroupEffects.Plan {
        val plan = com.vibetoon.domain.anim.GroupEffects.plan(id, layers, fxLayers, _animation.value.tracks.keys,
            fxUnsupported(),
            layerProps.filterValues { it.locked }.keys, layerProps.filterValues { it.clip }.keys, allowAnimated)
        if (allowAnimated && plan.rejection == null && !com.vibetoon.domain.anim.GroupEffectKeys.synchronized(plan.ids, plan.effects, _animation.value))
            return plan.copy(rejection = com.vibetoon.domain.anim.GroupEffects.Rejection.ANIMATED)
        return plan
    }

    private val _groupFxInfo = MutableStateFlow("")
    val groupFxInfo: StateFlow<String> = _groupFxInfo.asStateFlow()
    private val _groupFxBlocked = MutableStateFlow(false)
    val groupFxBlocked: StateFlow<Boolean> = _groupFxBlocked.asStateFlow()

    private fun editGroupFx(key: String, force: Boolean = false, change: (List<Effect>) -> List<Effect>): Boolean {
        val group = selectedEffectGroup() ?: return false
        if (_playing.value) { _state.value = UiState.Message("Остановите воспроизведение перед редактированием эффектов группы"); return true }
        if (autoKeyEnabled()) { _state.value = UiState.Message("Групповые эффекты с автоключом пока не поддерживаются"); return true }
        val plan = groupEffectPlan(group)
        if (plan.rejection != null) { _state.value = UiState.Message("Групповые эффекты недоступны: проверьте одинаковость цепочек, ключи, обтравку и блокировки"); return true }
        val next = change(plan.effects)
        if (next == plan.effects) return true
        checkpoint("$key:$group", force)
        fxLayers = com.vibetoon.domain.anim.GroupEffects.apply(fxLayers, plan.ids, next)
        pushScene(); markDirty()
        return true
    }

    /** Ключи пока находятся на дочерних дорожках, не на собственной строке группы. */
    fun keyGroupEffects() {
        val group = selectedEffectGroup() ?: return
        if (_playing.value) return
        val plan = groupEffectPlan(group, allowAnimated = true)
        val next = if (plan.rejection == null) com.vibetoon.domain.anim.GroupEffectKeys.key(plan.ids, plan.effects, _animation.value, _frame.value) else null
        if (next == null) { _state.value = UiState.Message("Нельзя поставить групповые ключи: проверьте эффекты и совпадение дорожек"); return }
        checkpoint("group-fx-key", force = true)
        _animation.value = next
        pushScene(); markDirty()
        _state.value = UiState.Message("Ключи эффектов поставлены на вложенных слоях")
    }

    fun moveGroupEffectKeys(delta: Int) {
        val group = selectedEffectGroup() ?: return
        if (_playing.value) return
        val plan = groupEffectPlan(group, allowAnimated = true)
        val next = if (plan.rejection == null) com.vibetoon.domain.anim.GroupEffectKeys.move(plan.ids, plan.effects, _animation.value, _frame.value, delta) else null
        if (next == null) { _state.value = UiState.Message("Ключи группы нельзя сдвинуть: нет ключа, предел или совпадение кадров"); return }
        checkpoint("group-fx-key-move", force = true)
        _animation.value = next
        setFrame(_frame.value + delta)
        pushScene(); markDirty()
    }

    fun deleteGroupEffectKeys() {
        val group = selectedEffectGroup() ?: return
        if (_playing.value) return
        val plan = groupEffectPlan(group, allowAnimated = true)
        val result = if (plan.rejection == null) com.vibetoon.domain.anim.GroupEffectKeys.delete(plan.ids, plan.effects, _animation.value, _frame.value) else null
        if (result == null) { _state.value = UiState.Message("Нет совпадающих ключей эффектов группы на этом кадре"); return }
        checkpoint("group-fx-key-delete", force = true)
        _animation.value = result.animation
        fxLayers = com.vibetoon.domain.anim.GroupEffects.apply(fxLayers, plan.ids, result.effects)
        pushScene(); markDirty()
    }

    private fun editGroupEffectValues(index: Int, changes: Map<Int, Float>, tag: String, force: Boolean): Boolean {
        val group = selectedEffectGroup() ?: return false
        val plan = groupEffectPlan(group, allowAnimated = true)
        if (plan.rejection != null) { _state.value = UiState.Message("Групповые эффекты недоступны: проверьте одинаковость цепочек, ключи, обтравку и блокировки"); return true }
        val animated = autoKeyEnabled() || com.vibetoon.domain.anim.GroupEffectKeys.hasKeys(plan.ids, _animation.value)
        if (!animated) return false
        val current = com.vibetoon.domain.anim.EffectTracks.at(plan.ids.first(), plan.effects, _animation.value, _frame.value.toFloat()).getOrNull(index) ?: return true
        if (changes.all { (param, value) -> param in current.p.indices && EffectMath.clamp(current.type, param, value) == current.p[param] }) return true
        val next = com.vibetoon.domain.anim.GroupEffectKeys.key(plan.ids, plan.effects, _animation.value, _frame.value, index, changes)
        if (next == null) { _state.value = UiState.Message("Нельзя поставить групповые ключи: проверьте эффекты и совпадение дорожек"); return true }
        checkpoint("group-$tag:$group:${_frame.value}", force)
        _animation.value = next
        pushScene(); markDirty()
        return true
    }

    private val _layerFx = MutableStateFlow<List<Effect>>(emptyList())
    /** Цепочка эффектов выделенного слоя (по порядку применения). */
    val layerEffects: StateFlow<List<Effect>> = _layerFx.asStateFlow()
    private val _fxLibrary = MutableStateFlow(false)
    /** Открыта ли «Библиотека эффектов». */
    val fxLibrary: StateFlow<Boolean> = _fxLibrary.asStateFlow()

    fun openFxLibrary(on: Boolean) {
        if (on && warpTarget() == null && selectedEffectGroup() == null) { _state.value = UiState.Message("Выберите один слой, чтобы добавить эффект"); return }
        _fxLibrary.value = on
    }

    private fun refreshFxState() {
        val group = selectedEffectGroup()
        val plan = group?.let { groupEffectPlan(it, allowAnimated = true) }
        _groupFxBlocked.value = group != null && plan?.rejection != null
        _groupFxInfo.value = when {
            group == null -> ""
            plan?.rejection != null -> "Группа: Вращение, Качание и Ось растяжения работают у самой группы; остальные эффекты недоступны - цепочки различаются или есть неподдерживаемые слои, ключи, обтравка или блокировка"
            else -> "Группа: Вращение, Качание и Ось растяжения - у самой группы (вокруг её пивота, ключи на строке группы); остальные эффекты - на вложенных слоях"
        }
        val id = warpTarget()
        _layerFx.value = if (group != null) {
            // свои трансформации группы (сверху) + общая цепочка вложенных слоёв
            val own = com.vibetoon.domain.anim.EffectTracks.at(group, com.vibetoon.domain.anim.GroupEffects.own(fxLayers, group), _animation.value, _frame.value.toFloat())
            val nested = if (plan?.rejection == null && plan?.ids?.isNotEmpty() == true) com.vibetoon.domain.anim.EffectTracks.at(plan.ids.first(), plan.effects, _animation.value, _frame.value.toFloat()) else emptyList()
            own + nested
        } else if (id == null) emptyList() else com.vibetoon.domain.anim.EffectTracks.at(id, fxLayers[id].orEmpty(), _animation.value, _frame.value.toFloat())
    }

    private fun fxNow(): Map<Int, List<FloatArray>> {
        val base = sceneLookFx(fxRecords(fxLayers, _colors.value, _animation.value, _frame.value, _animation.value.settings.fps), sceneLookNow(), leafIdsForLook())
        if (ghosts.isEmpty()) return base
        val out = HashMap(base)
        for (g in ghosts) if (g.tint) out[g.id] = listOf(floatArrayOf(TINT_FX, g.r, g.g, g.b, 0.85f, 0f, 0f, 0f, 0f, 0f))   // оттенок кадров-ориентиров (спринт 37: цвета до/после из настроек)
        return out
    }

    /** Записи эффектов слоёв: цветокоррекция слоя - неявный первый эффект «Цветокоррекция». Общий путь для экрана и экспорта. */
    private fun fxRecords(fx: Map<Int, List<Effect>>, colors: Map<Int, ColorAdjust>, anim: Animation, frame: Int, fps: Int): Map<Int, List<FloatArray>> {
        val sec = frame.toFloat() / fps.coerceAtLeast(1)
        val adj = colors.filterValues { !it.isIdentity }
        if (fx.isEmpty() && adj.isEmpty()) return emptyMap()
        val out = HashMap<Int, List<FloatArray>>()
        for (id in fx.keys + adj.keys) {
            val own = adj[id]?.let { listOf(Effect(EffectMath.COLOR, listOf(it.exposure / 50f, it.contrast / 100f, it.saturation / 100f, it.hue * 1.8f, 0f, 0f, 0f, 0f))) }.orEmpty()
            val r = EffectMath.records(own + com.vibetoon.domain.anim.EffectTracks.at(id, fx[id].orEmpty(), anim, frame.toFloat()), sec)
            if (r.isNotEmpty()) out[id] = r
        }
        return out
    }

    private fun editFx(key: String, force: Boolean = false, target: Int? = null, change: (List<Effect>) -> List<Effect>) {
        val t = target ?: warpTarget() ?: return
        val cur = fxLayers[t].orEmpty()
        val next = change(cur)
        if (next == cur) return
        checkpoint(key, force)
        fxLayers = if (next.isEmpty()) fxLayers - t else fxLayers + (t to next)
        refreshFxState()
        pushScene()
        markDirty()
    }

    /** Добавляет эффект выбранного типа в конец цепочки выделенного слоя. */
    fun addEffect(type: Int) {
        val e = EffectMath.create(type) ?: return
        val group = selectedEffectGroup()
        if (group != null && com.vibetoon.domain.anim.GroupEffects.addsToGroupItself(type)) {
            // Вращение/Качание/Ось растяжения - на саму группу: её матрица вокруг пивота группы, дети наследуют целиком
            if (_playing.value) { _state.value = UiState.Message("Остановите воспроизведение перед редактированием эффектов группы"); return }
            if (isLockedId(group)) { lockedNotice(); return }
            if (fxLayers[group].orEmpty().size >= EffectMath.MAX_PER_LAYER) { _state.value = UiState.Message("На слое не больше ${EffectMath.MAX_PER_LAYER} эффектов"); return }
            editFx("group-own-fx-add", force = true, target = group) { com.vibetoon.domain.anim.EffectTracks.assignSlots(it + e) }
            _fxLibrary.value = false
            return
        }
        if (editGroupFx("group-fx-add", force = true) { list ->
            if (list.size >= EffectMath.MAX_PER_LAYER) {
                _state.value = UiState.Message("На слое не больше ${EffectMath.MAX_PER_LAYER} эффектов"); list
            } else com.vibetoon.domain.anim.EffectTracks.assignSlots(list + e)
        }) { _fxLibrary.value = false; return }
        val t = warpTarget() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        if ((fxLayers[t]?.size ?: 0) >= EffectMath.MAX_PER_LAYER) { _state.value = UiState.Message("На слое не больше ${EffectMath.MAX_PER_LAYER} эффектов"); return }
        editFx("fx-add", force = true) { com.vibetoon.domain.anim.EffectTracks.assignSlots(it + e) }
        _fxLibrary.value = false
    }

    /** Строка инспектора выделенной группы -> (свой эффект группы?, индекс в части); null - выделена не группа. */
    private fun groupRoute(index: Int): Pair<Boolean, Int>? = selectedEffectGroup()?.let {
        com.vibetoon.domain.anim.GroupEffects.route(index, com.vibetoon.domain.anim.GroupEffects.own(fxLayers, it).size)
    }

    fun removeEffect(index: Int) {
        val route = groupRoute(index)
        if (route != null && !route.first) { editGroupFx("group-fx-del", force = true) { list -> list.filterIndexed { i, _ -> i != route.second } }; return }
        val layer = (if (route != null) selectedEffectGroup() else warpTarget()) ?: return
        val idx = route?.second ?: index
        val list = fxLayers[layer].orEmpty()
        val e = list.getOrNull(idx) ?: return
        checkpoint("fx-del", force = true)
        if (layer in 0..com.vibetoon.domain.anim.EffectTracks.MAX_LAYER && e.slot in 0 until com.vibetoon.domain.anim.EffectTracks.SLOTS) {
            val ids = com.vibetoon.domain.anim.EffectTracks.ids(layer, e.slot).toSet()
            _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        }
        val rest = list.filterIndexed { i, _ -> i != idx }
        fxLayers = if (rest.isEmpty()) fxLayers - layer else fxLayers + (layer to rest)
        refreshFxState()
        pushScene(); markDirty()
    }

    fun moveEffect(index: Int, dir: Int) {
        val reorder: (List<Effect>) -> List<Effect> = { l ->
            val j = index + dir
            if (index !in l.indices || j !in l.indices) l else l.toMutableList().also { val x = it[index]; it[index] = it[j]; it[j] = x }
        }
        val group = selectedEffectGroup()
        if (group != null) {
            val own = com.vibetoon.domain.anim.GroupEffects.own(fxLayers, group).size
            if (!com.vibetoon.domain.anim.GroupEffects.canMove(index, dir, own, _layerFx.value.size)) return
            val (isOwn, i) = com.vibetoon.domain.anim.GroupEffects.route(index, own)
            val local: (List<Effect>) -> List<Effect> = { l ->
                val j = i + dir
                if (i !in l.indices || j !in l.indices) l else l.toMutableList().also { val x = it[i]; it[i] = it[j]; it[j] = x }
            }
            if (isOwn) editFx("group-own-fx-move", force = true, target = group, change = local)
            else editGroupFx("group-fx-move", force = true, change = local)
            return
        }
        editFx("fx-move", force = true, change = reorder)
    }

    private fun editEffectValues(index: Int, changes: Map<Int, Float>, tag: String, force: Boolean = false) {
        if (_playing.value) return
        val route = groupRoute(index)
        if (route == null || !route.first) {
            val gi = route?.second ?: index   // общая цепочка вложенных слоёв группы
            if (editGroupEffectValues(gi, changes, tag, force)) return
            if (editGroupFx("group-$tag", force) { list ->
                val effect = list.getOrNull(gi)
                if (effect == null || changes.keys.any { it !in effect.p.indices }) list else {
                    val updated = effect.copy(p = effect.p.toMutableList().also { p ->
                        for ((param, value) in changes) p[param] = EffectMath.clamp(effect.type, param, value)
                    })
                    list.toMutableList().also { it[gi] = updated }
                }
            }) return
        }
        // свой эффект слоя или собственная трансформация группы (ключи - на дорожках самой группы)
        val layer = (if (route != null) selectedEffectGroup() else warpTarget()) ?: return
        val idx = route?.second ?: index
        val list = fxLayers[layer].orEmpty()
        val effect = list.getOrNull(idx) ?: return
        if (changes.keys.any { it !in effect.p.indices }) return
        val tracks = com.vibetoon.domain.anim.EffectTracks
        val currentEffect = tracks.at(layer, list, _animation.value, _frame.value.toFloat()).getOrNull(idx) ?: return
        if (changes.all { (param, value) -> EffectMath.clamp(effect.type, param, value) == currentEffect.p[param] }) return
        checkpoint("$tag:${_frame.value}", force)
        val auto = autoKeyEnabled()
        val canAuto = layer in 0..tracks.MAX_LAYER && effect.slot in 0 until tracks.SLOTS && changes.keys.all { it < tracks.PARAMS } && canAutoKeyTransform(layer)
        if (auto && canAuto) {
            _animation.value = com.vibetoon.domain.anim.AutoKeyEdits.effect(_animation.value, layer, _frame.value, effective(layer), list, effect, changes)
            override.remove(layer)
            pushScene(); markDirty()
            return
        }
        if (auto && !canAuto) _state.value = UiState.Message("Автоключ этого параметра эффекта недоступен")
        val animated = layer in 0..tracks.MAX_LAYER && effect.slot in 0 until tracks.SLOTS &&
            (_animation.value.hasKey(layer, _frame.value) || tracks.ids(layer, effect.slot).any { _animation.value.hasTrack(it) })
        if (animated) {
            // Каждый ромб содержит состояние всех параметров: перенос/удаление ключа атомарны.
            var anim = tracks.snapshot(layer, list, _animation.value, _frame.value)
            for ((param, value) in changes) anim = anim.withKey(tracks.id(layer, effect.slot, param), _frame.value,
                LayerTransform(dx = EffectMath.clamp(effect.type, param, value)))
            _animation.value = anim
        } else {
            val updated = effect.copy(p = effect.p.toMutableList().also { p ->
                for ((param, value) in changes) p[param] = EffectMath.clamp(effect.type, param, value)
            })
            fxLayers = fxLayers + (layer to list.toMutableList().also { it[idx] = updated })
        }
        pushScene(); markDirty()
    }

    fun setEffectParam(index: Int, param: Int, v: Float) = editEffectValues(index, mapOf(param to v), "fx-p$index-$param")

    /** Цвет эффекта: три параметра записываются одной операцией Undo. */
    fun setEffectColor(index: Int, argb: Long) {
        val e = _layerFx.value.getOrNull(index) ?: return
        val at = EffectMath.def(e.type)?.colorAt?.takeIf { it >= 0 } ?: return
        editEffectValues(index, mapOf(at to ((argb shr 16) and 0xFF) / 255f,
            at + 1 to ((argb shr 8) and 0xFF) / 255f, at + 2 to (argb and 0xFF) / 255f), "fx-color", true)
    }

    /** При удалении последнего ключа сохраняем последнее значение как статическое. */
    private fun removeEffectKeys(layer: Int, frame: Int) {
        val tracks = com.vibetoon.domain.anim.EffectTracks
        if (layer !in 0..tracks.MAX_LAYER) return
        val effects = fxLayers[layer].orEmpty().map { e ->
            if (e.slot !in 0 until tracks.SLOTS) e else e.copy(p = e.p.mapIndexed { param, value ->
                if (param >= tracks.PARAMS) value else {
                    val id = tracks.id(layer, e.slot, param)
                    val (next, last) = _animation.value.withoutKey(id, frame)
                    _animation.value = next
                    last?.dx ?: value
                }
            })
        }
        if (effects.isNotEmpty()) fxLayers = fxLayers + (layer to effects)
    }

    /** Пипетка для цвета эффекта: следующий тап по холсту берёт цвет. */
    fun pickEffectColor(index: Int) {
        pickTarget = -100 - index
        _eyedropper.value = true
        _state.value = UiState.Message("Пипетка: коснитесь холста, чтобы взять цвет для эффекта")
    }

    /** Turbulent Displace слоёв: слой -> параметры. */
    private var turbLayers: Map<Int, Turb> = emptyMap()
    private val _turb = MutableStateFlow<Turb?>(null)
    /** Турбулентность выделенного слоя (null - выключена). */
    val turb: StateFlow<Turb?> = _turb.asStateFlow()

    private fun turbNow(): Map<Int, FloatArray> =
        turbLayers.mapValues { (id, t) -> TurbMath.params(TurbMath.at(id, t, _frame.value, _animation.value), _frame.value) }

    private fun turbEffective(id: Int): Turb? = turbLayers[id]?.let { TurbMath.at(id, it, _frame.value, _animation.value) }

    /** Включает/выключает Turbulent Displace на выделенном слое. */
    fun toggleTurb() {
        val t = warpTarget() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        checkpoint("turb", force = true)
        turbLayers = if (t in turbLayers) turbLayers - t else turbLayers + (t to Turb())
        if (t !in turbLayers) {   // выключили: ключи параметров слоя удаляются
            val ids = (0 until TurbMath.PARAMS).map { TurbMath.paramId(t, it) }.toSet()
            _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        }
        _turb.value = turbEffective(t)
        pushScene()
        markDirty()
    }

    /** Меняет один параметр: 0 - Intensity (%), 1 - Size, 2 - Complexity, 3 - Line Boil (кадров). */
    fun setTurbParam(which: Int, v: Float) {
        val t = warpTarget() ?: return
        val cur = turbLayers[t] ?: return
        checkpoint("turb-$which")
        if (_fx.value) {
            // FX вкл: значение пишется ключом на текущем кадре (дельта к базе)
            val baseV = when (which) { 0 -> cur.intensity; 1 -> cur.size; 2 -> cur.complexity.toFloat(); else -> cur.boil.toFloat() }
            _animation.value = _animation.value.withKey(TurbMath.paramId(t, which), _frame.value, LayerTransform(dx = v - baseV))
        } else {
            val n = when (which) {
                0 -> cur.copy(intensity = v)
                1 -> cur.copy(size = v)
                2 -> cur.copy(complexity = v.toInt())
                else -> cur.copy(boil = v.toInt())
            }.clamped()
            turbLayers = turbLayers + (t to n)
        }
        _turb.value = turbEffective(t)
        pushScene()
        markDirty()
    }

    /** Скининг слоёв с Mesh: слой -> (кость -> Strength). */
    private var skinLayers: Map<Int, Map<Int, Float>> = emptyMap()
    private val _skinned = MutableStateFlow(false)
    /** У выделенного слоя включён скининг. */
    val skinned: StateFlow<Boolean> = _skinned.asStateFlow()

    /** Привязывает сетку выделенного слоя ко всем костям проекта (авто-веса) или снимает привязку. */
    fun toggleSkin() {
        val t = warpTarget() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        if (t !in meshLayers) { _state.value = UiState.Message("Сначала создайте сетку («Сгенерировать»)"); return }
        checkpoint("skin", force = true)
        if (t in skinLayers) {
            skinLayers = skinLayers - t
            _state.value = UiState.Message("Привязка к костям снята")
        } else {
            val bs = _bones.value
            if (bs.isEmpty()) { _state.value = UiState.Message("Нет костей: создайте скелет"); return }
            skinLayers = skinLayers + (t to bs.associate { it.id to 1f })
            _state.value = UiState.Message("Сетка привязана к костям (${bs.size}): вершины двигаются по весам")
        }
        _skinned.value = t in skinLayers
        pushScene()
        markDirty()
    }
    private val _meshMode = MutableStateFlow(false)
    val meshMode: StateFlow<Boolean> = _meshMode.asStateFlow()
    private val _meshTool = MutableStateFlow(MeshTool.PIN_MOVE)
    val meshTool: StateFlow<MeshTool> = _meshTool.asStateFlow()
    private val _meshDensity = MutableStateFlow(8)
    /** Число ячеек по длинной стороне слоя при «Сгенерировать». */
    val meshDensity: StateFlow<Int> = _meshDensity.asStateFlow()
    private val _meshOverlay = MutableStateFlow(MeshOverlay())
    val meshOverlay: StateFlow<MeshOverlay> = _meshOverlay.asStateFlow()
    private val meshEdgeCache = java.util.IdentityHashMap<Mesh, IntArray>()
    private var swallowTap = false
    private var meshActive = -1

    private fun meshesNow(): Map<Int, Pair<Mesh, FloatArray>> =
        meshLayers.mapValues { (id, m) -> m to fullMeshOffsets(id, m, _frame.value, _fx.value) }

    /** Сетка удалённых слоёв: вершины, форма привязки и ключи. */
    private fun dropMeshData(layerIds: Set<Int>) {
        val gone = layerIds.filter { it in meshLayers }
        if (gone.isEmpty()) return
        val ids = gone.flatMapTo(HashSet()) { MeshMath.ids(it, meshLayers.getValue(it)) }
        meshLayers = meshLayers - gone.toSet()
        dropPinData(gone.toSet())
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
    }

    fun setMeshMode(on: Boolean) {
        if (_meshMode.value == on) return
        _meshMode.value = on
        meshActive = -1
        refreshMeshOverlay()
        if (on) _state.value = UiState.Message("Сетка: выберите слой, затем «Сгенерировать» или «+Сетка» (тап добавляет вершину)")
    }

    fun setMeshTool(t: MeshTool) { _meshTool.value = t; meshActive = -1; refreshMeshOverlay() }

    private val _meshHigh = MutableStateFlow(false)
    /** Пресет «Авто-сетки»: false - Standard (мало вершин), true - High (много, до 1024). */
    val meshHigh: StateFlow<Boolean> = _meshHigh.asStateFlow()
    fun setMeshHigh(on: Boolean) { _meshHigh.value = on }

    fun setMeshDensity(n: Int) { _meshDensity.value = n.coerceIn(2, 40) }

    private fun meshTargetLayer(): LayerInfo? = warpTarget()?.let { id -> layers.firstOrNull { it.id == id } }

    /** Создаёт (или пересоздаёт) равномерную сетку на выделенном слое; прежние смещения вершин сбрасываются. */
    fun generateMesh() {
        val l = meshTargetLayer() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        checkpoint("mesh-gen", force = true)
        meshLayers[l.id]?.let { old -> clearMeshOffsets(MeshMath.ids(l.id, old)) }
        meshLayers = meshLayers + (l.id to MeshMath.generate(l.width, l.height, _meshDensity.value))
        meshActive = -1
        pushScene()
        markDirty()
        _state.value = UiState.Message("Сетка: ${meshLayers.getValue(l.id).count} вершин")
    }

    /** Убирает сетку выделенного слоя вместе с формой привязки и ключами вершин. */
    fun resetMesh() {
        val t = warpTarget() ?: return
        val m = meshLayers[t] ?: return
        checkpoint("mesh-reset", force = true)
        clearMeshOffsets(MeshMath.ids(t, m))
        meshLayers = meshLayers - t
        dropPinData(setOf(t))
        skinLayers = skinLayers - t
        _skinned.value = false
        meshActive = -1
        pushScene()
        markDirty()
        _state.value = UiState.Message("Сетка слоя удалена")
    }

    private fun clearMeshOffsets(ids: Set<Int>) {
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
    }

    // спринт 43: кэш рёбер ограничен - старые сетки (после каждой перегенерации) не копятся в памяти
    private fun meshEdges(m: Mesh): IntArray = (if (meshEdgeCache.size > 256 && m !in meshEdgeCache) meshEdgeCache.also { it.clear() } else meshEdgeCache).getOrPut(m) {
        val seen = HashSet<Long>()
        val out = ArrayList<Int>()
        for (t in 0 until m.tris.size / 3) for (e in 0 until 3) {
            val a = m.tris[t * 3 + e]; val b = m.tris[t * 3 + (e + 1) % 3]
            if (seen.add(minOf(a, b).toLong() * 100000L + maxOf(a, b))) { out.add(a); out.add(b) }
        }
        out.toIntArray()
    }

    /** Итоговые смещения вершин слоя из текущей сцены (с учётом скининга) или null. */
    private fun sceneMeshOffsets(id: Int, count: Int): FloatArray? {
        val sc = scene ?: return null
        var vo = 0
        for (i in sc.meshIds.indices) {
            val n = sc.meshCounts[i * 2]
            if (sc.meshIds[i] == id) {
                if (n < count) return null
                return FloatArray(n * 2) { j -> sc.meshVerts[(vo + j / 2) * 4 + 2 + j % 2] }
            }
            vo += n
        }
        return null
    }

    private fun refreshMeshOverlay() {
        refreshPinOverlay()
        val t = warpTarget()
        val m = if (t != null) meshLayers[t] else null
        val q = if (t != null) scene?.selectionQuads?.get(t) else null
        if (!_meshMode.value || _boneMode.value || m == null || q == null) {
            if (_meshOverlay.value.points.isNotEmpty()) _meshOverlay.value = MeshOverlay()
            return
        }
        // у слоя со скинингом показываем итоговые позиции вершин из сцены (смещения + кости)
        val off = (if (t!! in skinLayers) sceneMeshOffsets(t, m.count) else null)
            ?: fullMeshOffsets(t, m, _frame.value, _fx.value)
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val pts = FloatArray(m.count * 2)
        for (k in 0 until m.count) {
            val u = m.u(k) + off[k * 2]
            val v = m.v(k) + off[k * 2 + 1]
            toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy).let { pts[k * 2] = it.first; pts[k * 2 + 1] = it.second }
        }
        _meshOverlay.value = MeshOverlay(pts, meshEdges(m), meshActive)
    }

    /** Палец опустился в режиме Mesh; true - жест забирает вершина. */
    private fun meshDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        val t = warpTarget() ?: return false
        if (_meshTool.value == MeshTool.PIN_ADD && meshLayers[t] == null) return pinAddAt(sx, sy)
        val m = meshLayers[t] ?: return false
        if (_playing.value) return false
        val ov = _meshOverlay.value
        if (ov.points.size < m.count * 2) return false
        if (_meshTool.value == MeshTool.PIN_ADD) return pinAddAt(sx, sy)
        if (_meshTool.value == MeshTool.PIN_MOVE) return pinDown(sx, sy)
        if (_meshTool.value == MeshTool.ADD) { if (meshAddAt(t, m, sx, sy)) { swallowTap = true; return true }; return false }
        if (_meshTool.value == MeshTool.DELETE) { if (meshDeleteAt(t, m, ov.points, sx, sy)) { swallowTap = true; return true }; return false }
        if (_meshTool.value == MeshTool.CUT || _meshTool.value == MeshTool.SMUDGE) {
            val q = scene?.selectionQuads?.get(t) ?: return false
            val p = unitOf(q, toCanvas(sx, sy)) ?: return false
            meshLast = p
            cutRemoved.clear()
            grab = if (_meshTool.value == MeshTool.CUT) MESH_CUT else MESH_SMUDGE
            checkpoint(if (grab == MESH_CUT) "mesh-cut" else "mesh-smudge", force = true)
            if (grab == MESH_SMUDGE) {
                brushLive = t to MeshMath.offsets(t, m, _frame.value, base, _animation.value, _fx.value)
                brushDirty.clear()
                _brushCircle.value = floatArrayOf(sx, sy, _brushRadius.value)
            }
            swallowTap = true
            return true
        }
        var best = -1
        var bestD = 60f
        for (k in 0 until m.count) {
            val d = kotlin.math.hypot(sx - ov.points[k * 2], sy - ov.points[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        grab = MESH_GRAB + best
        meshActive = best
        swallowTap = true
        refreshMeshOverlay()
        return true
    }

    /**
     * Добавляет вершину в точку касания: ищем треугольник под пальцем на ДЕФОРМИРОВАННОЙ сетке и по барицентрическим
     * координатам находим исходное положение; вершина встаёт точно на видимую поверхность (форма привязки по соседям).
     */
    private var meshLast: Pair<Float, Float>? = null
    private val cutRemoved = HashSet<Int>()

    /**
     * Записывает итоговые смещения вершин [offsets] (k -> (ox, oy)): FX выкл - форма привязки, FX вкл - ключ на кадре
     * (дельта к привязке). Спринт 41: ключи сетки - автоключ «как в Alight Motion» ([com.vibetoon.domain.anim.DeformAutoKey]):
     * первая правка на кадре > 0 сначала ставит исходную форму ключом на кадре 0 (раньше единственный ключ держался на всех
     * кадрах - ресницы не возвращались при перемотке назад), у остальных анимированных вершин форма на кадре фиксируется.
     */
    private fun setVertexOffsets(t: Int, m: Mesh, offsets: Map<Int, Pair<Float, Float>>) {
        if (offsets.isEmpty()) return
        val changes = HashMap<Int, LayerTransform>(offsets.size)
        for ((k, o) in offsets) {
            // защита от NaN и от «улёта» вершины при быстром жесте: смещение держим в разумных пределах
            val ox = if (o.first.isFinite()) o.first.coerceIn(-2f, 2f) else 0f
            val oy = if (o.second.isFinite()) o.second.coerceIn(-2f, 2f) else 0f
            val id = MeshMath.vertexId(t, k)
            if (!_fx.value) base[id] = LayerTransform(dx = ox, dy = oy)
            else {
                val bind = base[id]
                changes[id] = LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f))
            }
        }
        if (changes.isNotEmpty())
            _animation.value = com.vibetoon.domain.anim.DeformAutoKey.key(_animation.value, MeshMath.ids(t, m), _frame.value, changes)
    }

    /** Cut: отрезок движения пальца удаляет задетые треугольники (по деформированной сетке); вершины чистятся в конце жеста. */
    private fun cutMove(sx: Float, sy: Float) {
        val t = warpTarget() ?: return
        val m = meshLayers[t] ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        val (pu, pv) = meshLast ?: return
        meshLast = u to v
        val off = MeshMath.offsets(t, m, _frame.value, base, _animation.value, _fx.value)
        val hit = MeshMath.trianglesHit(m, off, pu, pv, u, v)
        if (hit.isEmpty()) return
        if (m.tris.size / 3 - hit.size < 1) return   // последний треугольник не режем: для удаления сетки есть «Удалить»
        meshLayers = meshLayers + (t to MeshMath.removeTriangles(m, hit.toSet()))
        cutRemoved.addAll(hit)
        pushScene()
        markDirty()
    }

    /** Конец жеста Cut: вершины без треугольников удаляются, индексы сдвигаются - id вершин (база и ключи) переносятся. */
    private fun finishCut() {
        val t = warpTarget()
        val m = if (t != null) meshLayers[t] else null
        val had = cutRemoved.isNotEmpty()
        cutRemoved.clear()
        if (t == null || m == null || !had) return
        val (nm, map) = MeshMath.compact(m)
        if (nm.count == m.count) return
        remapMeshIds(t, m, nm, map)
        pushScene()
        markDirty()
    }

    /** Переносит базовые смещения и ключи вершин на новые индексы после чистки сетки ([map]: старый -> новый, -1 удалена). */
    private fun remapMeshIds(t: Int, m: Mesh, nm: Mesh, map: IntArray) {
        val oldBase = HashMap<Int, LayerTransform>()
        val oldTracks = HashMap<Int, com.vibetoon.domain.anim.AnimationTrack>()
        val tracks = HashMap(_animation.value.tracks)
        for (k in 0 until m.count) {
            val id = MeshMath.vertexId(t, k)
            base.remove(id)?.let { oldBase[k] = it }
            override.remove(id)
            tracks.remove(id)?.let { oldTracks[k] = it }
        }
        for ((k, tr) in oldTracks) if (map[k] >= 0) {
            val nid = MeshMath.vertexId(t, map[k])
            tracks[nid] = com.vibetoon.domain.anim.AnimationTrack(nid, tr.keys)
        }
        for ((k, b) in oldBase) if (map[k] >= 0) base[MeshMath.vertexId(t, map[k])] = b
        _animation.value = _animation.value.copy(tracks = tracks)
        meshLayers = meshLayers + (t to nm)
        meshActive = -1
    }

    /**
     * Delete Point: тап рядом с вершиной (в радиусе захвата) удаляет только её; дыра вокруг перестраивается
     * треугольниками, остальная форма не меняется. Ключи и смещения удалённой вершины убираются.
     */
    private fun meshDeleteAt(t: Int, m: Mesh, pts: FloatArray, sx: Float, sy: Float): Boolean {
        var best = -1
        var bestD = 60f
        for (k in 0 until m.count) {
            val d = kotlin.math.hypot(sx - pts[k * 2], sy - pts[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        val l = layers.firstOrNull { it.id == t } ?: return false
        val rebuilt = MeshMath.removeVertex(m, best, l.width.toFloat(), l.height.toFloat()) ?: return false
        if (rebuilt.tris.isEmpty()) { _state.value = UiState.Message("Нельзя удалить последний треугольник: используйте «Удалить» сетку"); return true }
        checkpoint("mesh-del-point", force = true)
        val (nm, map) = MeshMath.compact(rebuilt)
        remapMeshIds(t, m, nm, map)
        pushScene()
        markDirty()
        _state.value = UiState.Message("Точка удалена, сетка перестроена (${nm.count} вершин)")
        return true
    }

    private val _brushMode = MutableStateFlow(BrushMath.Mode.GRAB)
    val brushMode: StateFlow<BrushMath.Mode> = _brushMode.asStateFlow()
    fun setBrushMode(m: BrushMath.Mode) { _brushMode.value = m }

    private val _brushRadius = MutableStateFlow(110f)
    /** Радиус кисти по вершинам в экранных пикселях. */
    val brushRadius: StateFlow<Float> = _brushRadius.asStateFlow()
    fun setBrushRadius(r: Float) { _brushRadius.value = r.coerceIn(30f, 600f) }
    fun changeBrushRadius(sign: Float) { _brushRadius.value = (_brushRadius.value + sign * maxOf(15f, _brushRadius.value * 0.2f)).coerceIn(30f, 600f) }

    private val brushAdj = java.util.WeakHashMap<Mesh, Array<IntArray>>()
    /** Буфер мазка: пока палец на экране, вершины меняются только здесь (без записи в анимацию), ключи пишутся один раз на отпускании. */
    private var brushLive: Pair<Int, FloatArray>? = null
    private val brushDirty = HashSet<Int>()
    private var brushLastPush = 0L
    private val _brushCircle = MutableStateFlow<FloatArray?>(null)
    /** Круг кисти под пальцем: x, y, радиус в экранных пикселях (null - кисть не активна). */
    val brushCircle: StateFlow<FloatArray?> = _brushCircle.asStateFlow()

    /**
     * Deform Brush: мазок пальца двигает ВЕРШИНЫ сетки по режиму ([BrushMath.Mode]): ядро Вендланда, шаги по 25% радиуса,
     * сглаживание поля смещений после каждого шага (сетка не складывается, форма не худеет). Во время жеста мутируется
     * локальный буфер, сцена обновляется не чаще ~60 Гц; в таймлайн всё пишется одной пачкой в [commitBrush].
     */
    private fun smudgeMove(sx: Float, sy: Float) {
        val t = warpTarget() ?: return
        val m = meshLayers[t] ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val l = layers.firstOrNull { it.id == t } ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        val (pu, pv) = meshLast ?: return
        meshLast = u to v
        _brushCircle.value = floatArrayOf(sx, sy, _brushRadius.value)
        val w = l.width.toFloat(); val h = l.height.toFloat()
        val (ru, rv) = unitOf(q, toCanvas(sx + _brushRadius.value, sy)) ?: return
        val radiusPx = kotlin.math.hypot((ru - u) * w, (rv - v) * h)
        val live = brushLive?.takeIf { it.first == t && it.second.size == m.count * 2 }
            ?: (t to MeshMath.offsets(t, m, _frame.value, base, _animation.value, _fx.value)).also { brushLive = it }
        val adj = brushAdj.getOrPut(m) { MeshGraph.adjacency(m.count, m.tris) }
        val touched = BrushMath.stroke(m, adj, live.second, w, h, pu, pv, u, v, radiusPx, 1f, _brushMode.value)
        if (touched.isEmpty()) return
        brushDirty.addAll(touched)
        val now = System.nanoTime()
        if (now - brushLastPush >= 16_000_000L) { brushLastPush = now; pushScene() }
    }

    /** Конец мазка: одна запись изменённых вершин (форма привязки или ключи кадра), буфер сбрасывается. */
    private fun commitBrush() {
        val live = brushLive
        val dirty = brushDirty.toList()
        brushLive = null; brushDirty.clear(); _brushCircle.value = null
        if (live == null || dirty.isEmpty()) { refreshMeshOverlay(); return }
        val (t, off) = live
        val m = meshLayers[t] ?: run { refreshMeshOverlay(); return }
        if (off.size < m.count * 2) { refreshMeshOverlay(); return }
        setVertexOffsets(t, m, dirty.filter { it in 0 until m.count }.associateWith { k -> off[k * 2] to off[k * 2 + 1] })
        pushScene(); markDirty()
    }

    /** «Генерация по контуру»: сетка только по непрозрачным ячейкам слоя (альфа берётся из текстуры в GL-потоке). */
    /** Сетка как в Clip Studio: плотные точки по контуру слоя и редкие внутри, треугольники только внутри формы. */
    fun generateMeshContour() {
        val l = meshTargetLayer() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        val view = RenderHost.view ?: return
        view.layerAlpha(l.id) { bytes ->
            viewModelScope.launch {
                if (bytes == null || bytes.size < 9) { generateMesh(); return@launch }
                fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
                val aw = i32(0); val ah = i32(4)
                val mesh = MeshMath.fromContourAuto(l.width, l.height, bytes.copyOfRange(8, bytes.size), aw, ah, _meshHigh.value)
                if (mesh == null) { _state.value = UiState.Message("Слой полностью прозрачен"); return@launch }
                checkpoint("mesh-gen-contour", force = true)
                meshLayers[l.id]?.let { old -> clearMeshOffsets(MeshMath.ids(l.id, old)) }
                meshLayers = meshLayers + (l.id to mesh)
                skinLayers = skinLayers - l.id
                meshActive = -1
                pushScene()
                markDirty()
                _state.value = UiState.Message("Сетка по контуру: ${mesh.count} вершин")
            }
        }
    }

    fun generateMeshFromAlpha() {
        val l = meshTargetLayer() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        val view = RenderHost.view ?: return
        val density = _meshDensity.value
        view.layerAlpha(l.id) { bytes ->
            viewModelScope.launch {
                if (bytes == null || bytes.size < 9) { _state.value = UiState.Message("Слой ещё не загружен"); return@launch }
                fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
                val aw = i32(0); val ah = i32(4)
                val mesh = MeshMath.fromAlpha(l.width, l.height, bytes.copyOfRange(8, bytes.size), aw, ah, density)
                if (mesh == null) { _state.value = UiState.Message("Слой полностью прозрачен"); return@launch }
                checkpoint("mesh-gen-alpha", force = true)
                meshLayers[l.id]?.let { old -> clearMeshOffsets(MeshMath.ids(l.id, old)) }
                meshLayers = meshLayers + (l.id to mesh)
                meshActive = -1
                pushScene()
                markDirty()
                _state.value = UiState.Message("Сетка по контуру: ${mesh.count} вершин")
            }
        }
    }

    private fun meshAddAt(t: Int, m: Mesh, sx: Float, sy: Float): Boolean {
        val l = layers.firstOrNull { it.id == t } ?: return false
        val q = scene?.selectionQuads?.get(t) ?: return false
        val (pu, pv) = unitOf(q, toCanvas(sx, sy)) ?: return false
        val cur = MeshMath.offsets(t, m, _frame.value, base, _animation.value, _fx.value)
        val bind = MeshMath.offsets(t, m, _frame.value, base, _animation.value, false)
        for (i in 0 until m.tris.size / 3) {
            val a = m.tris[i * 3]; val b = m.tris[i * 3 + 1]; val c = m.tris[i * 3 + 2]
            val ax = m.u(a) + cur[a * 2]; val ay = m.v(a) + cur[a * 2 + 1]
            val bx = m.u(b) + cur[b * 2]; val by = m.v(b) + cur[b * 2 + 1]
            val cx = m.u(c) + cur[c * 2]; val cy = m.v(c) + cur[c * 2 + 1]
            val det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (kotlin.math.abs(det) < 1e-12f) continue
            val w1 = ((by - cy) * (pu - cx) + (cx - bx) * (pv - cy)) / det
            val w2 = ((cy - ay) * (pu - cx) + (ax - cx) * (pv - cy)) / det
            val w3 = 1f - w1 - w2
            if (w1 < -1e-4f || w2 < -1e-4f || w3 < -1e-4f) continue
            val ru = w1 * m.u(a) + w2 * m.u(b) + w3 * m.u(c)
            val rv = w1 * m.v(a) + w2 * m.v(b) + w3 * m.v(c)
            val nm = MeshMath.addVertex(m, ru, rv, l.width, l.height) ?: return false
            checkpoint("mesh-add", force = true)
            val k = nm.count - 1
            base[MeshMath.vertexId(t, k)] = LayerTransform(
                dx = w1 * bind[a * 2] + w2 * bind[b * 2] + w3 * bind[c * 2],
                dy = w1 * bind[a * 2 + 1] + w2 * bind[b * 2 + 1] + w3 * bind[c * 2 + 1]
            )
            meshLayers = meshLayers + (t to nm)
            meshActive = k
            grab = MESH_GRAB + k
            pushScene()
            markDirty()
            return true
        }
        return false
    }

    private fun meshMove(sx: Float, sy: Float) {
        if (grab == MESH_CUT) { cutMove(sx, sy); return }
        if (grab == MESH_SMUDGE) { smudgeMove(sx, sy); return }
        val k = grab - MESH_GRAB
        val t = warpTarget() ?: return
        val m = meshLayers[t] ?: return
        if (k !in 0 until m.count) return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvas(sx, sy)) ?: return
        // спринт 43: у пряди с пинами показанная вершина = своё смещение + добавка пинов/пути - вычитаем добавку,
        // чтобы вершина вставала ровно под палец и форма сохранялась ключом, а не отскакивала
        var ox = u - m.u(k); var oy = v - m.v(k)
        if (!pinLayers[t].isNullOrEmpty()) {
            val shown = fullMeshOffsets(t, m, _frame.value, _fx.value)
            val own = MeshMath.offsets(t, m, _frame.value, base, _animation.value, _fx.value)
            if (shown.size >= m.count * 2 && own.size >= m.count * 2) {
                ox = com.vibetoon.domain.anim.LayerKeyFlow.vertexTarget(u, m.u(k), shown[k * 2], own[k * 2])
                oy = com.vibetoon.domain.anim.LayerKeyFlow.vertexTarget(v, m.v(k), shown[k * 2 + 1], own[k * 2 + 1])
            }
        }
        ox = ox.coerceIn(-1.5f, 1.5f); oy = oy.coerceIn(-1.5f, 1.5f)
        checkpoint("mesh$t-$k")
        // FX выкл: меняется Bind Pose сетки; FX вкл: ключ кадра с исходной формой на кадре 0 (спринт 41)
        setVertexOffsets(t, m, mapOf(k to (ox to oy)))
        pushScene()
        markDirty()
    }

    // ---------------------------------------------------------------- кости

    private class BoneHit(val id: Int, val part: Int)   // part: 0 тело, 1 сустав, 2 конец
    private val MESH_GRAB = 10_000
    private val MESH_CUT = 19_998
    private val MESH_SMUDGE = 19_999
    private var grab = 0          // 0 нет, 1 сустав, 2 конец, 3 черновик новой кости
    private var lastCx = 0f
    private var lastCy = 0f
    private var draft: FloatArray? = null   // x0, y0, x1, y1 в координатах холста

    private var savedLayerSel: Pair<Set<Int>, Int?>? = null

    private val _boneStrength = MutableStateFlow<Float?>(null)
    /** Strength выбранной кости (null - кость не выбрана). */
    val boneStrength: StateFlow<Float?> = _boneStrength.asStateFlow()

    /** Привязки слоёв с учётом Strength каждой кости (вес вершины = Strength слоя * Strength кости * длина/расстояние). */
    private fun effectiveSkins(): Map<Int, Map<Int, Float>> {
        if (skinLayers.isEmpty()) return skinLayers
        val st = _bones.value.associate { it.id to it.strength }
        return skinLayers.mapValues { (_, m) -> m.mapValues { (b, s) -> s * (st[b] ?: 1f) } }
    }

    fun setBoneStrength(v: Float) {
        val id = _selected.value ?: return
        if (_bones.value.none { it.id == id }) return
        checkpoint("bone-strength")
        val s = v.coerceIn(0f, 1f)
        _bones.value = _bones.value.map { if (it.id == id) it.copy(strength = s) else it }
        _boneStrength.value = s
        pushScene()
        markDirty()
    }

    fun setBoneMode(on: Boolean) {
        if (_boneMode.value == on) return
        pause()
        // выделение слоёв переживает вход в режим костей и выход из него (иначе сетка «слетает»)
        val sel = _selection.value
        val prim = _selected.value
        if (on) savedLayerSel = if (sel.none { it >= BONE_ID_BASE }) sel to prim else null
        if (on) { _pathDraw.value = false; pathStroke = null }   // спринт 38
        _boneMode.value = on
        val back = savedLayerSel
        if (!on && back != null) {
            savedLayerSel = null
            applySelection(back.first, back.second)
        } else applySelection(emptySet(), null)
        draft = null
        pushScene()
        _state.value = UiState.Message(if (on) "Режим костей: «＋ Кость» и протяжка пальцем рисуют кость" else "Режим объектов")
    }

    fun setBoneTool(t: BoneTool) {
        if (t == BoneTool.ADD && !_bindPose.value) { _state.value = UiState.Message("Кости создаются в режиме Bind Pose"); return }
        _boneTool.value = t
    }

    fun setBindPose(on: Boolean) {
        if (_bindPose.value == on) return
        _bindPose.value = on
        if (!on && _boneTool.value == BoneTool.ADD) _boneTool.value = BoneTool.SELECT
        pushScene()
        _state.value = UiState.Message(if (on) "Bind Pose: скелет настраивается, картинка не деформируется" else "Animation: кость двигает привязанные слои")
    }

    private fun selectedBone(): Bone? = _selected.value?.let { id -> _bones.value.firstOrNull { it.id == id } }

    /** Bind Pose: трансформация слоя костей кости (свободные кости - без неё). */
    private fun bindDelta(b: Bone): Affine2D = if (b.layer == 0) Affine2D.IDENTITY else scene?.boneDeltas?.get(b.layer) ?: Affine2D.IDENTITY

    // экран <-> холст (те же формулы, что у камеры, с поворотом/отражением вида)
    private fun toScreen(x: Float, y: Float) = camera.toScreen(x, y, viewW, viewH, canvasW, canvasH)
    private fun toCanvas(sx: Float, sy: Float) = camera.toCanvas(sx, sy, viewW, viewH, canvasW, canvasH)

    /** Положение кости на экране: в Bind Pose - по данным, в Animation - с учётом позы. */
    private fun shapes(): List<BoneShape> {
        val rest = _bindPose.value
        val sel = _selected.value
        // спринт 38: кости скрытого слоя костей не рисуются и не ловятся; в Bind Pose кости слоя едут с его трансформацией
        return com.vibetoon.domain.anim.BoneLayers.visible(_bones.value, hiddenBoneLayers()).map { b ->
            val d = if (rest) bindDelta(b) else scene?.boneDeltas?.get(b.id) ?: Affine2D.IDENTITY
            BoneShape(
                b.id,
                toScreen(d.applyX(b.hx, b.hy), d.applyY(b.hx, b.hy)).first, toScreen(d.applyX(b.hx, b.hy), d.applyY(b.hx, b.hy)).second,
                toScreen(d.applyX(b.tx, b.ty), d.applyY(b.tx, b.ty)).first, toScreen(d.applyX(b.tx, b.ty), d.applyY(b.tx, b.ty)).second,
                b.id == sel
            )
        }
    }

    private fun refreshBoneOverlay() {
        refreshPathOverlay()
        refreshWarpOverlay()
        refreshMeshOverlay()
        refreshPerspOverlay()
        if (!_boneMode.value) {
            if (_boneOverlay.value.bones.isNotEmpty() || _boneOverlay.value.draft != null || _boneOverlay.value.lasso.isNotEmpty()) _boneOverlay.value = BoneOverlay()
            return
        }
        val dr = draft?.let { val p0 = toScreen(it[0], it[1]); val p1 = toScreen(it[2], it[3]); floatArrayOf(p0.first, p0.second, p1.first, p1.second) }
        _boneOverlay.value = BoneOverlay(shapes(), dr, lassoPts.map { it })
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val l2 = vx * vx + vy * vy
        val t = if (l2 < 1e-6f) 0f else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0f, 1f)
        return kotlin.math.hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    /** Отвязывает выбранную кость от родителя (становится корневой), положение в позе привязки не меняется. */
    fun detachBone() {
        val b = selectedBone() ?: run { _state.value = UiState.Message("Сначала выберите кость (инструмент «Кости»)"); return }
        val parent = _links.value[b.id]
        if (parent == null || (b.layer != 0 && parent == b.layer)) { _state.value = UiState.Message("У кости нет родителя"); return }
        checkpoint("bone-detach", force = true)
        _links.value = if (b.layer != 0) _links.value + (b.id to b.layer) else _links.value - b.id   // корень слоя костей остаётся в слое
        pushScene()
        markDirty()
        _state.value = UiState.Message("${b.name}: теперь корневая кость")
    }

    private fun hitBone(sx: Float, sy: Float, reach: Float): BoneHit? {
        val list = shapes()
        // у выбранной кости сначала проверяются ручки сустава и конца
        list.firstOrNull { it.selected }?.let {
            if (kotlin.math.hypot(sx - it.tx, sy - it.ty) <= reach) return BoneHit(it.id, 2)
            if (kotlin.math.hypot(sx - it.hx, sy - it.hy) <= reach) return BoneHit(it.id, 1)
        }
        val best = list.minByOrNull { distToSegment(sx, sy, it.hx, it.hy, it.tx, it.ty) } ?: return null
        return if (distToSegment(sx, sy, best.hx, best.hy, best.tx, best.ty) <= reach) BoneHit(best.id, 0) else null
    }

    private fun boneTap(sx: Float, sy: Float) {
        if (_boneTool.value != BoneTool.SELECT) return
        val hit = hitBone(sx, sy, 32f)
        if (hit == null) applySelection(emptySet(), null) else applySelection(setOf(hit.id), hit.id)
        refreshBoneOverlay()
    }

    /** Палец опустился в режиме костей; true - жест забирает кость (иначе палец двигает камеру). */
    fun boneDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        if (_pathDraw.value && !_boneMode.value) return pathDown(sx, sy)   // спринт 38: рисование пути слоя-пути
        if (_liquifyTool.value && !_boneMode.value && !_meshMode.value) return liquifyDown(sx, sy)
        if (_perspTool.value && !_boneMode.value && !_meshMode.value) return perspDown(sx, sy)
        if (_meshMode.value && !_boneMode.value) return meshDown(sx, sy)
        if (_warpTool.value && !_boneMode.value) return warpDown(sx, sy)
        if (!_boneMode.value || _playing.value) return false
        toCanvas(sx, sy).let { lastCx = it.first; lastCy = it.second }
        when (_boneTool.value) {
            BoneTool.ADD -> {
                if (!_bindPose.value) return false
                var x = lastCx
                var y = lastCy
                // начало новой кости прилипает к концу выбранной (цепочка костей)
                selectedBone()?.let { p ->
                    val d = bindDelta(p)
                    val ex = d.applyX(p.tx, p.ty); val ey = d.applyY(p.tx, p.ty)
                    if (kotlin.math.hypot(sx - toScreen(ex, ey).first, sy - toScreen(ex, ey).second) < 40f) { x = ex; y = ey }
                }
                draft = floatArrayOf(x, y, x, y)
                grab = 3
                return true
            }
            BoneTool.LASSO -> {
                lassoPts = arrayListOf(sx, sy)
                grab = 4
                refreshBoneOverlay()
                return true
            }
            BoneTool.SELECT -> {
                val sel = selectedBone() ?: return false
                val h = hitBone(sx, sy, 36f)
                if (h == null || h.id != sel.id || h.part == 0) return false
                grab = h.part
                return true
            }
        }
    }

    fun boneMove(sx: Float, sy: Float) {
        if (grab == 0) return
        if (grab == PATH_GRAB) { pathMove(sx, sy); return }
        if (grab >= PIN_GRAB) { pinMove(sx, sy); return }
        if (grab == LIQ_GRAB) { liquifyMove(sx, sy); return }
        if (grab >= PERSP_GRAB) { perspMove(sx, sy); return }
        if (grab >= MESH_GRAB) { meshMove(sx, sy); return }
        if (grab >= 20) { warpMove(sx, sy); return }
        if (grab == 4) { lassoPts.add(sx); lassoPts.add(sy); refreshBoneOverlay(); return }
        val (cx, cy) = toCanvas(sx, sy)
        if (grab == 3) {
            draft?.let { it[2] = cx; it[3] = cy }
            refreshBoneOverlay()
            return
        }
        val b = selectedBone() ?: return
        checkpoint("bone$grab${b.id}:${_frame.value}")
        if (_bindPose.value) editBindPose(b, cx, cy) else editAnimPose(b, cx, cy)
        lastCx = cx
        lastCy = cy
        pushScene()
        markDirty()
    }

    fun boneUp() {
        if (grab == PATH_GRAB) { pathUp(); return }
        if (grab == LIQ_GRAB) { finishLiquify(); grab = 0; return }
        if (grab >= 20) { if (grab == MESH_CUT) finishCut(); if (grab == MESH_SMUDGE) commitBrush(); perspSideDrag = null; grab = 0; return }
        if (grab == 4) { finishLasso(); grab = 0; return }
        val d = draft
        if (grab == 3 && d != null) {
            val len = kotlin.math.hypot(toScreen(d[2], d[3]).first - toScreen(d[0], d[1]).first, toScreen(d[2], d[3]).second - toScreen(d[0], d[1]).second)
            if (len >= 24f) createBone(d[0], d[1], d[2], d[3])
        }
        draft = null
        grab = 0
        refreshBoneOverlay()
    }

    fun boneCancel() {
        if (grab == PATH_GRAB) { pathStroke = null; grab = 0; refreshPathOverlay(); return }
        if (grab == LIQ_GRAB) finishLiquify()
        if (grab == MESH_CUT) finishCut()
        if (grab == MESH_SMUDGE) commitBrush()
        lassoPts = ArrayList()
        draft = null
        grab = 0
        refreshBoneOverlay()
    }

    // ---- Лассо: ручная привязка слоёв к кости (изоляция: слой реагирует только на выбранную кость)

    private var lassoPts: ArrayList<Float> = ArrayList()
    private val _lassoLayers = MutableStateFlow<Set<Int>>(emptySet())
    /** Слои, обведённые лассо и ожидающие привязки. */
    val lassoLayers: StateFlow<Set<Int>> = _lassoLayers.asStateFlow()

    private fun pointInPolygon(x: Float, y: Float, poly: List<Float>): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Только видимые на текущем кадре слои, включая видимость их групп. */
    private fun bindableLayerIds(): Set<Int> {
        val byId = layers.associateBy { it.id }
        val hidden = hiddenIds() + _clips.value.filterValues { !it.visibleAt(_frame.value) }.keys
        return layers.asSequence()
            .filter { it.kind == LayerKind.LAYER && it.id < BONE_ID_BASE }
            .filter { LayerTree.isEffectivelyVisible(it.id, byId, hidden) }
            .map { it.id }.toSet()
    }

    /** Лассо замкнуто: выбираются слои, центр рамки которых попал внутрь контура. */
    private fun finishLasso() {
        val poly = lassoPts.toList()
        lassoPts = ArrayList()
        if (poly.size < 6) { refreshBoneOverlay(); return }
        val pid = projectId
        val frame = _frame.value
        viewModelScope.launch {
            val ids = bindableLayerIds()
            for (id in ids) ensureBox(id)   // границы содержимого посчитаны до выбора
            if (projectId != pid || _frame.value != frame || !_boneMode.value) return@launch
            val sc = scene ?: return@launch
            val picked = HashSet<Int>()
            // Видимость могла измениться, пока читалась альфа-карта.
            for (id in ids intersect bindableLayerIds()) {
                val q = sc.selectionQuads[id] ?: continue
                val cq = contentQuad(id, q) ?: continue
                val cx = (cq[0] + cq[2] + cq[4] + cq[6]) / 4f
                val cy = (cq[1] + cq[3] + cq[5] + cq[7]) / 4f
                if (pointInPolygon(toScreen(cx, cy).first, toScreen(cx, cy).second, poly)) picked += id
            }
            _lassoLayers.value = picked
            updateSelectionFrame()
            refreshBoneOverlay()
            _state.value = UiState.Message(
                if (picked.isEmpty()) "Лассо: слоёв внутри контура нет" else "Обведено слоёв: ${picked.size}. Выберите кость (инструмент «Выбрать») и нажмите «Привязать»"
            )
        }
    }

    fun clearLasso() { _lassoLayers.value = emptySet(); updateSelectionFrame() }

    /** Привязывает обведённые слои к выбранной кости вручную. Эти слои больше не трогает автопривязка. */
    fun bindLassoToBone() {
        val bone = selectedBone() ?: run { _state.value = UiState.Message("Сначала выберите кость (инструмент «Выбрать»)"); return }
        val set = _lassoLayers.value intersect bindableLayerIds()
        if (set.isEmpty()) { _lassoLayers.value = emptySet(); updateSelectionFrame(); _state.value = UiState.Message("Сначала обведите видимые слои лассо"); return }
        if (!_bindPose.value) { _state.value = UiState.Message("Привязка работает в режиме Bind Pose"); return }
        checkpoint("lasso-bind", force = true)
        // Skinning сетки имеет приоритет над link в SceneBuilder. Ручная привязка
        // обещает одну кость, поэтому отключаем skinning у выбранных слоёв.
        skinLayers = skinLayers - set
        _links.value = _links.value + set.associateWith { bone.id }
        _lassoLayers.value = emptySet()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Привязано слоёв: ${set.size} к «${bone.name}» (вручную)")
    }

    /** Все непосредственные привязки, включая слои в свёрнутых группах и аудиодорожки. */
    fun boundLayers(boneId: Int, links: Map<Int, Int>): List<com.vibetoon.domain.project.BoundLayer> {
        val visual = layers.asReversed().filter { it.kind != LayerKind.GROUP_END }.map {
            com.vibetoon.domain.project.BoundLayer(it.id, it.name, it.kind != LayerKind.LAYER)
        }
        val audio = media.filter { !it.isVisual }.map { com.vibetoon.domain.project.BoundLayer(it.id, it.name) }
        return com.vibetoon.domain.project.BoneBindings.layers(boneId, links, visual + audio)
    }

    fun setBoneIndependent(id: Int, on: Boolean) {
        checkpoint("bone-indep", force = true)
        _bones.value = _bones.value.map { if (it.id == id) it.copy(independentRot = on) else it }
        pushScene()
        markDirty()
    }

    /** Пакет 8: имя кости из свойств (видно на таймлайне и в списке костей, сохраняется в проекте, Undo/Redo). */
    fun renameBone(id: Int, input: String) {
        val b = _bones.value.firstOrNull { it.id == id } ?: return
        val name = com.vibetoon.domain.project.BoneRename.sanitize(input, b.name) ?: return
        checkpoint("bone-name", force = true)
        _bones.value = com.vibetoon.domain.project.BoneRename.apply(_bones.value, id, name)
        refreshBoneOverlay()
        markDirty()
    }

    /**
     * Пакет 8: выделение ключей с таймлайна (долгое нажатие, рамка, повторный тап). Если все выделенные ключи костей
     * принадлежат одной кости - она становится выбранной, и свойства показывают её ключ (раньше оставалась прежняя кость).
     */
    fun selectKeysFocused(keys: Set<Pair<Int, Int>>) {
        val focus = com.vibetoon.domain.anim.QuickDeselect.focusBone(keys, _bones.value.map { it.id })
        if (focus != null && !_playing.value && selectedBone()?.id != focus) selectBoneRow(focus)
        _keySel.value = keys
        refreshInspector()
    }

    // ---- ключи костей (пакет 7): ромбы ◆ на таймлайне и кнопки в свойствах кости

    /** Кадры ключей кости по возрастанию. */
    fun boneKeyFrames(boneId: Int): List<Int> = com.vibetoon.domain.anim.BoneKeys.frames(boneId, _animation.value.tracks)

    /** Тап по строке кости на таймлайне: режим «Кости» (Animation) и выбор кости. */
    fun selectBoneRow(boneId: Int) {
        if (_bones.value.none { it.id == boneId }) return
        pause()
        if (!_boneMode.value) setBoneMode(true)
        if (_bindPose.value) setBindPose(false)   // ключи позы живут в режиме Animation
        applySelection(setOf(boneId), boneId)
        refreshBoneOverlay()
    }

    /** Тап по ромбу ◆ кости: кость выбрана, ключ выделен, плейхед на кадре ключа. */
    fun selectBoneKey(boneId: Int, frame: Int) {
        if (_bones.value.none { it.id == boneId }) return
        selectBoneRow(boneId)
        scrubTo(frame)
        _keySel.value = setOf(boneId to frame)
        refreshInspector()
    }

    private fun activeBoneKeyFrame(b: Bone): Int? =
        com.vibetoon.domain.anim.BoneKeys.activeFrame(b.id, boneKeyFrames(b.id), _keySel.value, _frame.value)

    /** «Добавить ключ»: ключ позы выбранной кости на текущем кадре (из текущей позы - кость не прыгает). */
    /**
     * «Добавить ключ» кости. Спринт 41: ключ получают сразу все выбранные кости и (настройка «Ключи всей цепочке костей»,
     * по умолчанию вкл.) вся цепочка выбранной кости - поза руки (плечо, предплечье, кисть) фиксируется на кадре целиком.
     * Кость без ключей при первом ключе на кадре > 0 получает ключ исходной позы на кадре 0: прежние кадры не меняются
     * (раньше единственный ключ держал позу на всём таймлайне).
     */
    fun addBoneKey() {
        val b = selectedBone() ?: run { _state.value = UiState.Message("Сначала выберите кость (инструмент «Выбрать»)"); return }
        if (_playing.value) return
        val f = _frame.value
        val targets = boneKeyTargets(b)
        val need = targets.filter { !_animation.value.hasKey(it, f) }
        if (need.isEmpty()) { _keySel.value = targets.mapTo(HashSet()) { it to f }; _state.value = UiState.Message("На этом кадре ключ кости уже есть"); return }
        checkpoint("bonekey", force = true)
        val poses = need.associateWith { effective(it) }
        _animation.value = com.vibetoon.domain.anim.BoneChainKeys.keyAll(_animation.value, need, f, { poses.getValue(it) }, { boneRestPose(it) })
        need.forEach { override.remove(it) }
        _keySel.value = targets.mapTo(HashSet()) { it to f }
        pushScene()
        refreshInspector()
        markDirty()
        if (need.size > 1) _state.value = UiState.Message("Ключи костей на кадре: {}".replace("{}", need.size.toString()))
    }

    /** Спринт 41: настройка «Ключи всей цепочке костей» (по умолчанию вкл.). */
    private fun boneChainKeysOn(): Boolean = getApplication<Application>()
        .getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("bone_chain_keys", true)

    /** Кости, которым ставится ключ вместе с [b]: выбранные кости и (настройка) вся цепочка [b]. */
    private fun boneKeyTargets(b: Bone): List<Int> {
        val ids = _bones.value.map { it.id }
        return com.vibetoon.domain.anim.BoneChainKeys.targets(b.id, _selection.value, _links.value, ids, boneChainKeysOn())
    }

    /** Поза кости вне ключей (кадры до первого ключа): поза покоя из base, иначе без смещения. */
    private fun boneRestPose(id: Int): LayerTransform = base[id] ?: LayerTransform()

    /**
     * Спринт 41: правка позы кости в режиме «Анимация» при включённом «Автодобавлении ключей» - автоключ как в Alight Motion. Перетаскивание кости ставит ключ на
     * текущем кадре (первый ключ на кадре > 0 - вместе с ключом исходной позы на кадре 0), а кости цепочки и другие
     * выбранные кости получают ключ своей текущей позы на этом кадре: иерархия не «плывёт» между ключами разных костей.
     * Раньше без ключа на кадре правка жила только до перемотки (override), а единственный ключ держал позу везде.
     */
    private fun storeBoneEdit(b: Bone, t: LayerTransform) {
        val id = b.id
        if (t == effective(id)) return
        // без «Автодобавления ключей» - прежний ручной режим (кнопка «Добавить ключ» ставит ключ цепочке)
        if (!autoKeyEnabled() || !canAutoKeyTransform(id)) { storeEdit(id, t); return }
        val f = _frame.value
        var anim = _animation.value
        val others = boneKeyTargets(b).filter { it != id && !anim.hasKey(it, f) }
        if (others.isNotEmpty()) {
            val poses = others.associateWith { effective(it) }
            anim = com.vibetoon.domain.anim.BoneChainKeys.keyAll(anim, others, f, { poses.getValue(it) }, { boneRestPose(it) })
            others.forEach { override.remove(it) }
        }
        _animation.value = com.vibetoon.domain.anim.BoneChainKeys.keyAll(anim, listOf(id), f, { t }, { boneRestPose(it) })
        override.remove(id)
    }

    /** «Удалить ключ»: выделенный ключ кости (или ключ на текущем кадре); поза остаётся на месте. */
    fun deleteBoneKey() {
        val b = selectedBone() ?: return
        if (_playing.value) return
        val f = activeBoneKeyFrame(b) ?: run { _state.value = UiState.Message("Выберите ключ кости на таймлайне"); return }
        checkpoint("bonekeydel", force = true)
        removeKeys(setOf(b.id to f))
        _keySel.value = _keySel.value - (b.id to f)
        override.remove(b.id)
        pushScene()
        refreshInspector()
        markDirty()
    }

    /** «Предыдущий/Следующий ключ» кости: плейхед прыгает на ключ, ключ выделяется. */
    fun stepBoneKey(direction: Int) {
        val b = selectedBone() ?: return
        val frames = boneKeyFrames(b.id)
        val target = if (direction < 0) com.vibetoon.domain.anim.BoneKeys.prev(frames, _frame.value)
            else com.vibetoon.domain.anim.BoneKeys.next(frames, _frame.value)
        if (target == null) { _state.value = UiState.Message("Ключей кости в этом направлении нет"); return }
        scrubTo(target)
        _keySel.value = setOf(b.id to target)
        refreshInspector()
    }

    /** Плавность перехода от выделенного ключа кости к следующему. */
    fun setBoneKeyEasing(type: EasingType) {
        val b = selectedBone() ?: return
        if (_playing.value) return
        val f = activeBoneKeyFrame(b) ?: run { _state.value = UiState.Message("Выберите ключ кости на таймлайне"); return }
        checkpoint("bonekeyease", force = true)
        _animation.value = _animation.value.withEasing(b.id, f, type)
        pushScene()
        refreshInspector()
        markDirty()
    }

    /** Свой график (кривая Безье) для выделенного ключа кости: плейхед на ключ и общий редактор кривой. */
    fun openBoneKeyCurve() {
        val b = selectedBone() ?: return
        if (_playing.value) return
        val f = activeBoneKeyFrame(b) ?: run { _state.value = UiState.Message("Выберите ключ кости на таймлайне"); return }
        if (_frame.value != f) scrubTo(f)
        // спринт 44: только ключ этой кости - раньше редактор брал ключи всех выделенных костей и слоёв на кадре
        val targets = com.vibetoon.domain.anim.SingleKeyCurve.targets(b.id, f, _animation.value.tracks, true)
        if (targets.isEmpty()) { _state.value = UiState.Message("Выберите ключ кости на таймлайне"); return }
        selectedCurveTargets = targets
        _curveTitle.value = "Кривая ключа"
        _curveNote.value = "Только ключ кадра ${f + 1}: остальные ключи не меняются"
        _curveEditor.value = true
    }

    private fun nextBoneId(): Int = maxOf(BONE_ID_BASE, (_bones.value.maxOfOrNull { it.id } ?: 0) + 1)

    private fun createBone(x0: Float, y0: Float, x1: Float, y1: Float) {
        checkpoint("bone-new", force = true)
        // родитель - выбранная кость, но только если новая начата от её конца (цепочка плечо -> предплечье);
        // отдельная кость в другом месте (вторая рука) - корневая, иначе движение одной руки тянет другую
        val sel = selectedBone()
        // спринт 38: кость попадает в выбранный (активный) или единственный слой костей; её точки - в позе привязки слоя
        val layer = sel?.layer ?: com.vibetoon.domain.anim.BoneLayers.targetLayer(activeBoneLayer, boneLayerIds())
        val probe = Bone(0, "", 0f, 0f, 0f, 0f, layer = layer)
        val (r0x, r0y) = boneRest(probe, x0, y0)
        val (r1x, r1y) = boneRest(probe, x1, y1)
        val parent = sel?.takeIf { kotlin.math.hypot(r0x - it.tx, r0y - it.ty) < 3f }
        val id = nextBoneId()
        _bones.value = _bones.value + Bone(id, "Кость ${_bones.value.size + 1}", r0x, r0y, r1x, r1y, layer = layer)
        if (parent != null) _links.value = _links.value + (id to parent.id)   // выбранная кость становится родителем
        else if (layer != 0) _links.value = _links.value + (id to layer)      // корневая кость слоя костей едет с ним
        applySelection(setOf(id), id)
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (parent != null) "Кость создана, родитель: ${parent.name}" else "Корневая кость создана")
    }

    /** Bind Pose: сустав переносит кость целиком с потомками, конец меняет длину и направление. */
    private fun editBindPose(b: Bone, cx0: Float, cy0: Float) {
        val kids = linkDescendants(b.id)
        val (cx, cy) = boneRest(b, cx0, cy0)   // спринт 38: слой костей мог быть сдвинут/повёрнут
        val (lx, ly) = boneRest(b, lastCx, lastCy)
        if (grab == 1) {
            val dx = cx - lx
            val dy = cy - ly
            _bones.value = _bones.value.map {
                if (it.id == b.id || it.id in kids) it.copy(hx = it.hx + dx, hy = it.hy + dy, tx = it.tx + dx, ty = it.ty + dy) else it
            }
        } else {
            // дети, присоединённые к концу, едут за ним
            _bones.value = _bones.value.map {
                when {
                    it.id == b.id -> it.copy(tx = cx, ty = cy)
                    _links.value[it.id] == b.id && kotlin.math.hypot(it.hx - b.tx, it.hy - b.ty) < 2f -> it.copy(hx = cx, hy = cy)
                    else -> it
                }
            }
        }
    }

    /** Animation: конец кости поворачивает её вокруг сустава, сустав сдвигает; поза пишется как у слоя (ключи работают). */
    private fun editAnimPose(b: Bone, cx: Float, cy: Float) {
        val parentBone = _links.value[b.id]
        val up = parentBone?.let { scene?.boneDeltas?.get(it) } ?: Affine2D.IDENTITY
        val inv = up.inverse() ?: Affine2D.IDENTITY
        val px = inv.applyX(cx, cy)   // указатель в осях родителя кости
        val py = inv.applyY(cx, cy)
        val t = effective(b.id)
        if (grab == 1) {
            storeBoneEdit(b, t.copy(dx = px - b.hx, dy = py - b.hy))
        } else {
            val want = kotlin.math.atan2(py - (b.hy + t.dy), px - (b.hx + t.dx)) - kotlin.math.atan2(b.ty - b.hy, b.tx - b.hx)
            var diff = want - t.rotation
            val twoPi = (2 * Math.PI).toFloat()
            while (diff > Math.PI) diff -= twoPi
            while (diff < -Math.PI) diff += twoPi
            storeBoneEdit(b, t.copy(rotation = t.rotation + diff))
        }
    }

    private fun deleteBones(ids: List<Int>) {
        pause()
        checkpoint("bone-delete", force = true)
        val all = HashSet(ids)
        all += ids.flatMap { linkDescendants(it) }.filter { it >= BONE_ID_BASE }
        _bones.value = _bones.value.filter { it.id !in all }
        _links.value = _links.value.filter { (c, p) -> c !in all && p !in all }   // привязанные слои возвращаются в исходное положение
        for (id in all) { base.remove(id); override.remove(id) }
        val anim = _animation.value
        _animation.value = anim.copy(tracks = anim.tracks.filterKeys { it !in all })
        applySelection(emptySet(), null)
        pushScene()
        markDirty()
        _state.value = UiState.Message("Костей удалено: ${all.size}")
    }

    /**
     * Автопривязка (Bind Pose): каждый свободный слой привязывается к кости, чей отрезок сильнее всего
     * пересекает рамку слоя. Группы пропускаются, привязываются их слои.
     */
    fun autoBind() {
        val pid = projectId
        val frame = _frame.value
        viewModelScope.launch {
            for (id in bindableLayerIds()) ensureBox(id)
            if (projectId != pid || _frame.value != frame || !_boneMode.value) return@launch
            autoBindNow()
        }
    }

    private fun autoBindNow() {
        if (!_bindPose.value) { _state.value = UiState.Message("Автопривязка работает в режиме Bind Pose"); return }
        val bones = _bones.value
        if (bones.isEmpty()) { _state.value = UiState.Message("Сначала создайте кости"); return }
        val sc = scene ?: return
        val visible = bindableLayerIds()
        val picks = HashMap<Int, Int>()
        for (l in layers) {
            // Скинненный слой уже привязан к костям, а link для него не применяется.
            if (l.id !in visible || l.id in _links.value || l.id in skinLayers) continue
            val q = sc.selectionQuads[l.id]?.let { contentQuad(l.id, it) } ?: continue   // по нарисованному, пустые слои пропускаются
            val minX = minOf(q[0], q[2], q[4], q[6])
            val maxX = maxOf(q[0], q[2], q[4], q[6])
            val minY = minOf(q[1], q[3], q[5], q[7])
            val maxY = maxOf(q[1], q[3], q[5], q[7])
            var best = -1
            var bestScore = 0
            for (b in bones) {
                var score = 0
                for (i in 0..32) {
                    val f = i / 32f
                    val x = b.hx + (b.tx - b.hx) * f
                    val y = b.hy + (b.ty - b.hy) * f
                    if (x in minX..maxX && y in minY..maxY) score++
                }
                if (score > bestScore) { bestScore = score; best = b.id }
            }
            if (best >= 0) picks[l.id] = best
        }
        if (picks.isEmpty()) { _state.value = UiState.Message("Нет свободных слоёв, которые пересекают кости"); return }
        checkpoint("autobind", force = true)
        _links.value = _links.value + picks
        pushScene()
        markDirty()
        _state.value = UiState.Message("Автопривязка: слоёв привязано ${picks.size}")
    }

    // ---------------------------------------------------------------- привязка (Parenting)

    /** Id всех узлов, чьи цепочки родителей проходят через [id] (его потомки по привязкам). */
    private fun linkDescendants(id: Int): Set<Int> {
        val out = HashSet<Int>()
        do {
            val before = out.size
            for ((c, p) in _links.value) if (p == id || p in out) out += c
        } while (out.size != before)
        return out
    }

    /** Кандидаты в родители для выделенных слоёв: любые узлы, кроме них самих и их потомков по привязкам. */
    fun parentCandidates(): List<Pair<Int, String>> {
        val banned = HashSet<Int>()
        for (id in _selection.value) { banned += id; banned += linkDescendants(id) }
        return _bones.value.filter { it.id !in banned }.map { it.id to it.name } + layers.filter {
            it.id !in banned && it.kind != com.vibetoon.nativebridge.LayerKind.GROUP_END
        }.map { it.id to it.name }
    }

    fun parentOf(id: Int): Int? = _links.value[id]

    /** Тип слоя для списка слоёв: "Текст", "Картинка", "Видео", "Группа" или "Пиксели" (слой PSD). */
    fun layerType(id: Int): String {
        media.firstOrNull { it.id == id }?.let {
            return when (it.kind) {
                MediaKind.TEXT -> "Текст"; MediaKind.IMAGE -> if (it.vector) "Вектор" else "Картинка"; MediaKind.VIDEO -> "Видео"; MediaKind.AUDIO -> "Аудио"; MediaKind.CAMERA -> "Камера"; MediaKind.NULLOBJ -> "Transform Layer"; MediaKind.PATH -> "Путь"; MediaKind.BONES -> "Слой костей"; MediaKind.GROUP -> "Группа"; MediaKind.SYMBOL -> "Символ"
            }
        }
        val k = layers.firstOrNull { it.id == id }?.kind
        return if (k == com.vibetoon.nativebridge.LayerKind.GROUP_OPEN || k == com.vibetoon.nativebridge.LayerKind.GROUP_CLOSED) "Группа" else "Пиксели"
    }

    fun layerName(id: Int): String =
        _bones.value.firstOrNull { it.id == id }?.name ?: layers.firstOrNull { it.id == id }?.name ?: "#$id"

    /**
     * Привязывает все выделенные слои к [parentId] (null - отвязать). Ребёнок повторяет трансформацию
     * родителя поверх своей собственной; цикл привязок невозможен.
     */
    fun setParent(parentId: Int?) {
        val selected = _selection.value
        if (selected.isEmpty() || _playing.value) return
        val toBone = parentId != null && _bones.value.any { it.id == parentId }
        val byId = layers.associateBy { it.id }
        val hidden = hiddenIds() + _clips.value.filterValues { !it.visibleAt(_frame.value) }.keys
        // Группу по-прежнему можно привязать к кости, если она видима.
        val ids = if (toBone) selected.filterTo(HashSet()) {
            it in byId && LayerTree.isEffectivelyVisible(it, byId, hidden)
        } else selected
        if (ids.isEmpty()) {
            _state.value = UiState.Message("Для привязки к кости выберите видимые слои")
            return
        }
        if (parentId != null && parentId in ids) return
        val banned = HashSet<Int>().also { b -> ids.forEach { b += it; b += linkDescendants(it) } }
        if (parentId != null && parentId in banned) {
            _state.value = UiState.Message("Нельзя: родитель сам привязан к этому слою")
            return
        }
        checkpoint("parent", force = true)
        if (toBone) skinLayers = skinLayers - ids
        _links.value = if (parentId == null) _links.value - ids else _links.value + ids.associateWith { parentId }
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (parentId == null) "Привязка снята" else "Привязано к: ${layerName(parentId)}")
    }

    // ---------------------------------------------------------------- лесенка

    /**
     * Лесенка: клипы выделенных слоёв (в порядке выделения) выстраиваются со сдвигом на 1 кадр:
     * первый остаётся на месте, каждый следующий начинается на кадр позже предыдущего. Длины сохраняются.
     */
    fun staggerSelected() {
        if (_playing.value) return
        val ids = _selection.value.toList()
        if (ids.size < 2) {
            _state.value = UiState.Message("Выделите два слоя или больше (кнопка мультивыбора)")
            return
        }
        checkpoint("stagger", force = true)
        val d = duration()
        // выстраиваем встык: начало каждого клипа = концу предыдущего (Start_i = End_{i-1}); первый остаётся на месте
        val clips = ids.map { clipOf(it) }
        val starts = com.vibetoon.domain.anim.SnapMath.chainStarts(clips.map { it.start }.first(), clips.map { it.end - it.start })
        var cut = 0
        ids.forEachIndexed { i, id ->
            val c = clips[i]
            val len = c.end - c.start
            val start = starts[i].coerceAtMost((d - len).coerceAtLeast(0))
            if (start != starts[i]) cut++
            storeClip(id, c.copy(start = start, end = start + len))
        }
        pushScene()
        markDirty()
        _state.value = UiState.Message(
            if (cut == 0) "Лесенка: ${ids.size} клипов встык, конец каждого = началу следующего"
            else "Лесенка: ${ids.size} клипов встык; $cut не поместились в проект и упёрлись в его конец (увеличьте длительность)"
        )
    }

    // ---------------------------------------------------------------- перенос слоёв по группам (Drag & Drop)

    /**
     * Пакет 9: слой [leaf] без своих эффектов, попавший в группу [parent], получает общую шейдерную цепочку эффектов
     * ближайшей группы-предка (как будто эффект группы добавили после него) вместе с ключами параметров.
     * Эффекты-трансформации (Вращение/Качание/Ось растяжения) группы живут на самой группе и наследуются матрицей.
     */
    private fun inheritGroupFx(leaf: Int, parent: Int): Boolean {
        if (fxLayers[leaf].orEmpty().isNotEmpty()) return false
        val parentOf = layers.associate { it.id to it.parentId }
        val others = layers.filter { it.id != leaf }
        val anim = _animation.value
        for (g in listOf(parent) + com.vibetoon.domain.project.SymbolPlacement.ancestors(parent) { parentOf[it] }) {
            val plan = com.vibetoon.domain.anim.GroupEffects.plan(g, others, fxLayers, anim.tracks.keys, fxUnsupported(),
                layerProps.filterValues { it.locked }.keys, layerProps.filterValues { it.clip }.keys, allowAnimated = true)
            if (plan.rejection != null || plan.ids.isEmpty()) continue
            val shader = plan.effects.filter { !com.vibetoon.domain.anim.EffectMath.isTransform(it.type) }
            if (shader.isEmpty()) continue
            if (!com.vibetoon.domain.anim.GroupEffectKeys.synchronized(plan.ids, plan.effects, anim)) continue
            fxLayers = fxLayers + (leaf to shader)
            _animation.value = com.vibetoon.domain.anim.EffectTracks.copyLayerTracks(anim, plan.ids.first(), leaf, shader)
            return true
        }
        return false
    }

    /** Слои, которым пакетные эффекты группы не раздаются (камера, null-объект). Экземпляры символов - раздаются (пакет 9). */
    private fun fxUnsupported(): Set<Int> = media.filter { it.kind == MediaKind.CAMERA || it.isRig }.mapTo(HashSet()) { it.id }

    /**
     * Контекст снимка форм символа [def]: общие предки определения и всех экземпляров (их трансформации экземпляр
     * получает в сцене сам - в снимке они нейтрализуются) и общая цепочка эффектов экземпляров (снимается с участников).
     */
    private fun symbolSnapshotContext(def: Int, parents: Map<Int, Int>? = null): Pair<Set<Int>, List<com.vibetoon.domain.anim.Effect>> {
        val insts = media.filter { it.kind == MediaKind.SYMBOL && it.symRef == def }
        if (insts.isEmpty()) return emptySet<Int>() to emptyList()
        val parentOf = parents ?: layers.associate { it.id to it.parentId }
        val neutral = com.vibetoon.domain.project.SymbolPlacement.sharedContext(def, insts.map { it.id }) { parentOf[it] }
        val live = com.vibetoon.domain.project.SymbolPlacement.liveChain(insts.map { fxLayers[it.id].orEmpty() })
        return neutral to live
    }

    /** Предки узла (включая сам узел) по текущему дереву слоёв. */
    private fun ancestorsOf(id: Int): Set<Int> {
        val byId = layers.associateBy { it.id }
        val out = HashSet<Int>()
        var cur = byId[id]
        while (cur != null && out.add(cur.id)) cur = byId[cur.parentId]
        return out
    }

    /**
     * Drop слоя [dragId] на строку [targetId]: на группу - слой становится её дочерним (наверху),
     * на обычный слой - встаёт в тот же родитель, что и цель (так слой "вытаскивается" из группы).
     * [targetId] = null - бросили в пустое место: слой уходит в корень. Пакет 25: слои PSD и медиа
     * (картинки, видео, текст, символы) кладутся в любые группы и встают вперемешку; координаты переводит SceneDomain.
     */
    fun dropLayer(dragId: Int, targetId: Int?) {
        if (_playing.value) return
        val byId = layers.associateBy { it.id }
        val src = byId[dragId] ?: return
        // порядок: бросок на слой (не группу) ставит слой на его место; на группу - внутрь группы
        val tgt = targetId?.let { byId[it] }
        if (tgt != null && tgt.id != dragId && tgt.kind == com.vibetoon.nativebridge.LayerKind.LAYER) {
            // пакет 25: слой PSD и медиа в одной группе (или группа уже с явным порядком) - общий порядок вперемешку
            if (src.parentId == tgt.parentId && ((dragId >= MEDIA_ID_BASE) != (tgt.id >= MEDIA_ID_BASE) || stackOrder.containsKey(src.parentId))) {
                reorderStack(dragId, tgt.id); return
            }
            // одна группа - порядок; цель в другой группе (например, слой в папке головы) - перенос в её группу
            if (dragId >= MEDIA_ID_BASE && tgt.id >= MEDIA_ID_BASE && src.parentId == tgt.parentId) { reorderMedia(dragId, tgt.id); return }
            if (dragId < MEDIA_ID_BASE && tgt.id < MEDIA_ID_BASE && !stackOrder.containsKey(tgt.parentId)) {
                if (tgt.parentId >= 0 && dragId in ancestorsOf(tgt.parentId)) {
                    _state.value = UiState.Message("Нельзя: группа не может войти сама в себя")
                    return
                }
                reorderPsd(dragId, tgt); return
            }
        }
        val newParent = when {
            targetId == null -> -1
            targetId == dragId -> return
            else -> {
                val t = byId[targetId] ?: return
                val tIsGroup = t.kind == com.vibetoon.nativebridge.LayerKind.GROUP_OPEN || t.kind == com.vibetoon.nativebridge.LayerKind.GROUP_CLOSED
                if (dragId >= MEDIA_ID_BASE) {
                    // Пакет 9: картинки, рисунки, вектор и экземпляры символов - в любые группы, включая группы PSD
                    // (рот-символ в папке головы); координаты переводит SceneBuilder (SceneDomain)
                    if (tIsGroup) t.id else t.parentId
                } else if (tIsGroup) t.id else t.parentId   // пакет 25: слои PSD - и в группы картинок/текста (SceneDomain переводит координаты)
            }
        }
        if (newParent == src.parentId) return
        if (newParent >= 0 && dragId in ancestorsOf(newParent)) {
            _state.value = UiState.Message("Нельзя: группа не может войти сама в себя")
            return
        }
        val inst = media.firstOrNull { it.id == dragId && it.kind == MediaKind.SYMBOL }
        if (inst != null) {
            val parentOf = layers.associate { it.id to it.parentId }
            if (!com.vibetoon.domain.project.SymbolPlacement.canPlace(inst.symRef, newParent) { parentOf[it] }) {
                _state.value = UiState.Message("Нельзя: экземпляр символа не может лежать внутри своего определения")
                return
            }
        }
        checkpoint("regroup", force = true)
        regroup = regroup.filter { it.first != dragId } + (dragId to newParent)
        rebuildLayers()
        // пакет 25: брошен на слой другой группы - встаёт над ним (PSD и медиа вперемешку), а не просто наверх группы
        if (tgt != null && tgt.kind == LayerKind.LAYER && tgt.parentId == newParent) {
            com.vibetoon.domain.model.StackOrder.place(layers, stackOrder, dragId, tgt.id, above = true)?.let { stackOrder = it; rebuildLayers() }
        }
        // слой в группе с общим эффектом (например, «Тряска» головы) получает тот же эффект с ключами
        val inherited = if (newParent >= 0 && src.kind == LayerKind.LAYER) inheritGroupFx(dragId, newParent) else false
        if (inst != null) invalidateForms()   // контекст экземпляра поменялся: формы символа переснимаются
        pushScene()
        if (inherited) refreshFxState()
        markDirty()
        _state.value = UiState.Message(when {
            newParent < 0 -> "Слой вынесен из группы"
            inherited -> "Слой перенесён в группу и получил её эффекты"
            else -> "Слой перенесён в «${layerName(newParent)}»"
        })
    }

    /** Пакет 25: общий порядок детей группы (PSD и медиа вперемешку): тянутый снизу встаёт над целью, сверху - под неё. */
    private fun reorderStack(dragId: Int, targetId: Int) {
        val next = com.vibetoon.domain.model.StackOrder.drop(layers, stackOrder, dragId, targetId) ?: return
        checkpoint("reorder", force = true)
        stackOrder = next
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Порядок слоёв изменён")
    }

    /** Пакет 25: «На самый верх группы» / «На самый низ группы» - для слоёв PSD и медиа одинаково. */
    fun moveLayerToEdge(id: Int, top: Boolean) {
        if (_playing.value) return
        val next = com.vibetoon.domain.model.StackOrder.toEdge(layers, stackOrder, id, top)
            ?: run { _state.value = UiState.Message(if (top) "Слой уже наверху группы" else "Слой уже внизу группы"); return }
        checkpoint("reorder", force = true)
        stackOrder = next
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (top) "Слой перенесён наверх группы" else "Слой перенесён вниз группы")
    }

    /** Картинки и рисунки лежат в списке media: порядок списка = порядок отрисовки (снизу вверх). */
    private fun reorderMedia(dragId: Int, targetId: Int) {
        val si = media.indexOfFirst { it.id == dragId }
        val ti = media.indexOfFirst { it.id == targetId }
        if (si < 0 || ti < 0 || si == ti) return
        checkpoint("reorder", force = true)
        val item = media.removeAt(si)
        val t2 = media.indexOfFirst { it.id == targetId }
        media.add(if (si > ti) t2 else t2 + 1, item)   // тянем вниз - под целевой слой, вверх - над ним
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Порядок слоёв изменён")
    }

    /**
     * Слой PSD ставится на место целевого слоя [tgt] внутри его группы. Порядок записей PSD не меняется,
     * поэтому нужная часть списка братьев (с точки вставки и выше) переносится в [regroup] в новом порядке:
     * перенесённые слои идут в конец списка (наверх группы).
     */
    private fun reorderPsd(dragId: Int, tgt: LayerInfo) {
        val p = tgt.parentId
        val sibs = layers.filter { it.parentId == p && it.kind != LayerKind.GROUP_END && it.id < MEDIA_ID_BASE }.map { it.id }
        val srcPos = sibs.indexOf(dragId)
        val tgtPos = sibs.indexOf(tgt.id)
        if (tgtPos < 0) return
        val rest = sibs.filter { it != dragId }
        val tIdx = rest.indexOf(tgt.id)
        val ins = if (srcPos >= 0 && srcPos > tgtPos) tIdx else tIdx + 1
        val order = rest.toMutableList().also { it.add(ins, dragId) }
        if (order == sibs) return
        val origParent = psdLayers.associate { it.id to it.parentId }
        val firstForeign = order.indexOfFirst { origParent[it] != p }.let { if (it < 0) Int.MAX_VALUE else it }
        val suffix = order.drop(minOf(ins, firstForeign))
        checkpoint("reorder", force = true)
        val all = order.toSet()
        regroup = regroup.filter { it.first !in all } + suffix.map { it to p }
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Порядок слоёв изменён")
    }

    // ---------------------------------------------------------------- выделение ключей на таймлайне

    private val _keySel = MutableStateFlow<Set<Pair<Int, Int>>>(emptySet())
    /** Выделенные ключи: пары (id узла, кадр). */
    val keySelection: StateFlow<Set<Pair<Int, Int>>> = _keySel.asStateFlow()

    fun selectKeys(keys: Set<Pair<Int, Int>>) { _keySel.value = keys }
    fun clearKeySelection() { if (_keySel.value.isNotEmpty()) _keySel.value = emptySet() }

    /**
     * Ключи времени экземпляров символов участвуют в общем выделении ключей (пара «экземпляр, кадр»): сдвиг, растяжение,
     * реверс, копия/вставка и удаление работают с ними так же, как с ключами трансформации.
     * [op] получает ключи экземпляра и выделенные кадры; null - операция невозможна (занятый кадр/границы) - ничего не меняется.
     */
    private fun planTimeKeys(sel: Set<Pair<Int, Int>>, op: (List<TimeKey>, Set<Int>) -> List<TimeKey>?): Map<Int, List<TimeKey>>? {
        val out = HashMap<Int, List<TimeKey>>()
        for ((inst, frames) in sel.groupBy({ it.first }, { it.second })) {
            val m = media.firstOrNull { it.id == inst && it.kind == MediaKind.SYMBOL } ?: continue
            val fs = frames.filterTo(HashSet()) { f -> m.timeKeys.any { it.frame == f } }
            if (fs.isEmpty()) continue
            out[inst] = op(m.timeKeys, fs) ?: return null
        }
        return out
    }

    private fun applyTimeKeys(plan: Map<Int, List<TimeKey>>) {
        if (plan.isEmpty()) return
        for ((inst, keys) in plan) {
            val i = media.indexOfFirst { it.id == inst }
            if (i >= 0) media[i] = media[i].copy(timeKeys = keys)
        }
        refreshSymbolState()
        updateSymbols(_frame.value)
    }

    /** Кадры ключей времени экземпляра (для жестов таймлайна). */
    fun timeKeyFrames(id: Int): List<Int> = _timeKeys.value[id]?.map { it.frame }.orEmpty()

    private var timeKeyClip: List<Pair<Int, TimeKey>> = emptyList()

    /** Сдвигает выделенные ключи на [delta] кадров (все дорожки слоя вместе, граница проекта останавливает всех). */
    fun moveSelectedKeys(delta: Int, singleGesture: Boolean = false) {
        val sel = _keySel.value
        if (sel.isEmpty() || delta == 0 || _playing.value) return
        val last = duration() - 1
        val minF = sel.minOf { it.second }
        val maxF = sel.maxOf { it.second }
        val d = delta.coerceIn(-minF, last - maxF)
        if (d == 0) return
        val tkPlan = planTimeKeys(sel) { keys, fs -> TimelineItemOps.move(keys, fs, d, last, TimelineItemOps.TIME_KEYS)?.takeIf { it.size == keys.size } }
        if (tkPlan == null || !com.vibetoon.domain.anim.KeyMovePolicy.canMove(_animation.value, sel, d)) {
            _state.value = UiState.Message("На целевом кадре уже есть ключ: перенос отменён")
            return
        }
        checkpoint("keymove", force = singleGesture)
        applyTimeKeys(tkPlan)
        var anim = _animation.value
        val byLayer = sel.groupBy({ it.first }, { it.second })
        for ((layer, frames) in byLayer) {
            for (id in TrackGroups.ids(layer, anim.tracks)) {
                val track = anim.tracks[id] ?: continue
                val moving = track.keys.filter { it.frame in frames }
                if (moving.isEmpty()) continue
                var t = AnimationTrack(id, track.keys.filter { it.frame !in frames })
                for (k in moving) t = t.upsert(k.copy(frame = k.frame + d))
                anim = anim.copy(tracks = anim.tracks + (id to t))
            }
        }
        _animation.value = anim
        _keySel.value = sel.mapTo(HashSet()) { it.first to it.second + d }
        override.clear()
        pushScene()
        markDirty()
    }

    /** Растягивает (×2) или сжимает (÷2) выделенные ключи во времени относительно самого раннего; кадры за пределами проекта ограничиваются его границами. */
    fun stretchSelectedKeys(factor: Float) = remapSelectedKeys("keystretch") { f, lo, _ -> com.vibetoon.domain.anim.KeyMath.stretchFrame(f, lo, factor) }

    /** Реверс выделенных ключей: порядок значений меняется на обратный внутри их диапазона. */
    fun reverseSelectedKeys() {
        val sel = _keySel.value
        if (sel.size < 2 || _playing.value) return
        checkpoint("keyreverse", force = true)
        var anim = _animation.value
        val byLayer = sel.groupBy({ it.first }, { it.second })
        val picked = HashSet<Pair<Int, Int>>()
        val tkPlan = planTimeKeys(sel) { keys, fs -> TimelineItemOps.mirrorTimeKeys(keys, fs) }.orEmpty()
        for ((inst, _) in tkPlan) {
            val fs = sel.filter { it.first == inst }.map { it.second }
            val lo = fs.min(); val hi = fs.max()
            fs.forEach { picked += inst to (lo + hi - it) }
        }
        applyTimeKeys(tkPlan)
        for ((layer, frames) in byLayer) for (id in TrackGroups.ids(layer, anim.tracks)) {
            val track = anim.tracks[id] ?: continue
            val chosen = track.keys.filter { it.frame in frames }
            if (chosen.size < 2) continue
            var t = AnimationTrack(id, track.keys.filter { it.frame !in frames })
            for (k in com.vibetoon.domain.anim.KeyMath.reverse(chosen)) { t = t.upsert(k); picked += layer to k.frame }
            anim = anim.copy(tracks = anim.tracks + (id to t))
        }
        _animation.value = anim; _keySel.value = picked
        override.clear(); pushScene(); markDirty()
        _state.value = UiState.Message("Реверс ключей: ${sel.size}")
    }

    private fun remapSelectedKeys(tag: String, map: (frame: Int, lo: Int, hi: Int) -> Int) {
        val sel = _keySel.value
        if (sel.size < 2 || _playing.value) return
        val last = duration() - 1
        val lo = sel.minOf { it.second }; val hi = sel.maxOf { it.second }
        val tkPlan = planTimeKeys(sel) { keys, fs ->
            val moved = keys.map { if (it.frame in fs) it.copy(frame = map(it.frame, lo, hi).coerceIn(0, last)) else it }
            moved.takeIf { m -> m.map { it.frame }.toSet().size == m.size }?.sortedBy { it.frame }
        }
        if (tkPlan == null || !com.vibetoon.domain.anim.KeyMovePolicy.canRemap(_animation.value, sel) { map(it, lo, hi).coerceIn(0, last) }) {
            _state.value = UiState.Message("Ключи совпадают после изменения: операция отменена")
            return
        }
        checkpoint(tag, force = true)
        var anim = _animation.value
        val byLayer = sel.groupBy({ it.first }, { it.second })
        val picked = HashSet<Pair<Int, Int>>()
        for ((inst, _) in tkPlan) sel.filter { it.first == inst }.forEach { picked += inst to map(it.second, lo, hi).coerceIn(0, last) }
        applyTimeKeys(tkPlan)
        for ((layer, frames) in byLayer) for (id in TrackGroups.ids(layer, anim.tracks)) {
            val track = anim.tracks[id] ?: continue
            val moving = track.keys.filter { it.frame in frames }
            if (moving.isEmpty()) continue
            var t = AnimationTrack(id, track.keys.filter { it.frame !in frames })
            for (k in moving) { val nf = map(k.frame, lo, hi).coerceIn(0, last); t = t.upsert(k.copy(frame = nf)); picked += layer to nf }
            anim = anim.copy(tracks = anim.tracks + (id to t))
        }
        _animation.value = anim; _keySel.value = picked
        override.clear(); pushScene(); markDirty()
        _state.value = UiState.Message("Ключи изменены: ${sel.size}")
    }

    fun deleteSelectedKeys() {
        val sel = _keySel.value
        if (sel.isEmpty() || _playing.value) return
        checkpoint("keydelete", force = true)
        applyTimeKeys(planTimeKeys(sel) { keys, fs -> TimelineItemOps.delete(keys, fs, TimelineItemOps.TIME_KEYS) }.orEmpty())
        removeKeys(sel)
        _keySel.value = emptySet()
        override.clear()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Ключей удалено: ${sel.size}")
    }

    /** Убирает ключи всех дорожек слоя на указанных кадрах; если дорожка опустела, слой остаётся на последнем значении. */
    private fun removeKeys(sel: Set<Pair<Int, Int>>) {
        for ((layer, frame) in sel) {
            removeEffectKeys(layer, frame)
            for (id in TrackGroups.ids(layer, _animation.value.tracks)) {
                val (next, lastValue) = _animation.value.withoutKey(id, frame)
                _animation.value = next
                // спринт 43: смещение формы (вершина, пин, точка Warp) добавляется к форме привязки, а не подменяет её
                if (lastValue != null) base[id] = com.vibetoon.domain.anim.LayerKeyFlow.baseAfterLastKey(id, base[id], lastValue)
            }
        }
    }

    // ---- буфер обмена ключей: копировать / вырезать / вставить пачкой

    private var keyClip: List<KeyClipEntry> = emptyList()
    private val _hasKeyClip = MutableStateFlow(false)
    /** В буфере есть скопированные ключи. */
    val hasKeyClip: StateFlow<Boolean> = _hasKeyClip.asStateFlow()

    fun copySelectedKeys() {
        val sel = _keySel.value
        if (sel.isEmpty()) return
        val base0 = sel.minOf { it.second }
        val out = ArrayList<KeyClipEntry>()
        for ((layer, frame) in sel) for (id in TrackGroups.ids(layer, _animation.value.tracks)) {
            _animation.value.tracks[id]?.keyAt(frame)?.let { out += KeyClipEntry(id, frame - base0, it, layer) }
        }
        keyClip = out
        timeKeyClip = sel.flatMap { (layer, frame) ->
            media.firstOrNull { it.id == layer && it.kind == MediaKind.SYMBOL }?.timeKeys?.filter { it.frame == frame }.orEmpty().map { layer to it.copy(frame = frame - base0) }
        }
        val total = out.size + timeKeyClip.size
        _hasKeyClip.value = total > 0
        _state.value = UiState.Message(if (total == 0) "В выделении нет ключей" else "Скопировано ключей: $total")
    }

    fun cutSelectedKeys() {
        if (_playing.value) return
        copySelectedKeys()
        if (keyClip.isNotEmpty() || timeKeyClip.isNotEmpty()) deleteSelectedKeys()
    }

    /**
     * Вставляет скопированные ключи так, чтобы самый ранний встал на текущий кадр. Куда: на те же дорожки; если
     * скопирован один слой, а сейчас выделен другой (не группа), ключи (в том числе вершины сетки и пины) переносятся на него.
     */
    fun pasteKeys() = pasteKeysAt(_frame.value)

    /** Дубликат выделенных ключей сразу после выделения (самый ранний встаёт на «последний выделенный + 1»). */
    fun duplicateSelectedKeys() {
        val sel = _keySel.value
        if (sel.isEmpty() || _playing.value) return
        copySelectedKeys()
        pasteKeysAt(sel.maxOf { it.second } + 1)
    }

    /** Вставка ключей времени буфера с кадра [cur]: на тот же экземпляр или на выделенный экземпляр символа. */
    private fun pasteTimeKeysAt(cur: Int): Set<Pair<Int, Int>>? {
        val tclip = timeKeyClip
        if (tclip.isEmpty()) return emptySet()
        val srcs = tclip.map { it.first }.toSet()
        val target = _selected.value?.takeIf { sel -> srcs.size == 1 && sel != srcs.first() && media.any { it.id == sel && it.kind == MediaKind.SYMBOL } }
        val last = duration() - 1
        val plan = HashMap<Int, List<TimeKey>>()
        val picked = HashSet<Pair<Int, Int>>()
        for ((src, entries) in tclip.groupBy({ it.first }, { it.second })) {
            val inst = target ?: src
            val m = media.firstOrNull { it.id == inst && it.kind == MediaKind.SYMBOL } ?: return null
            plan[inst] = TimelineItemOps.paste(m.timeKeys, entries, cur, last, TimelineItemOps.TIME_KEYS) ?: return null
            entries.forEach { picked += inst to (cur + it.frame) }
        }
        applyTimeKeys(plan)
        return picked
    }

    private fun pasteKeysAt(cur: Int) {
        val clip = keyClip
        if (_playing.value) return
        if (clip.isEmpty()) {
            if (timeKeyClip.isEmpty()) return
            checkpoint("keypaste", force = true)
            val picked = pasteTimeKeysAt(cur) ?: run { _state.value = UiState.Message("Ключи времени не вставлены: выход за границы или символ удалён"); return }
            _keySel.value = picked; markDirty()
            _state.value = UiState.Message("Вставлено ключей времени: ${picked.size}")
            return
        }
        val srcLayers = clip.map { it.layer }.toSet()
        val target = _selected.value?.takeIf { srcLayers.size == 1 && it != srcLayers.first() && layers.any { l -> l.id == it } }
        val knownTargets = layers.mapTo(HashSet()) { it.id }.apply { addAll(_bones.value.map { it.id }) }
        if ((target == null && srcLayers.any { it !in knownTargets }) || (target != null && target !in knownTargets)) {
            _state.value = UiState.Message("Ключи не вставлены: слой удалён")
            return
        }
        // Different deformer topologies and FX slots cannot be mapped safely by track number alone.
        if (target != null && clip.any { it.trackId != it.layer }) {
            _state.value = UiState.Message("Вставка на другой слой доступна только для трансформации; деформации и эффекты не изменены")
            return
        }
        val planned = clip.map { e ->
            val id = if (target != null) TrackGroups.remap(e.trackId, srcLayers.first(), target) else e.trackId
            if (id == null) { _state.value = UiState.Message("Ключи не вставлены: несовместимая дорожка"); return }
            Triple(e, id, cur.toLong() + e.rel.toLong())
        }
        if (!com.vibetoon.domain.anim.KeyPastePolicy.canPaste(_animation.value,
                planned.map { com.vibetoon.domain.anim.KeyPastePolicy.Destination(it.second, it.third) })) {
            _state.value = UiState.Message("Ключи не вставлены: занятый кадр или выход за границы")
            return
        }
        checkpoint("keypaste", force = true)
        var anim = _animation.value
        val picked = HashSet<Pair<Int, Int>>()
        for ((e, id, frame) in planned) {
            val f = frame.toInt()
            val tr = anim.tracks[id] ?: AnimationTrack(id)
            anim = anim.copy(tracks = anim.tracks + (id to tr.upsert(e.key.copy(frame = f))))
            picked += (target ?: e.layer) to f
        }
        val count = planned.size
        _animation.value = anim
        pasteTimeKeysAt(cur)?.let { picked += it }
        _keySel.value = picked
        override.clear()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Вставлено ключей: $count")
    }

    // ---------------------------------------------------------------- удаление слоёв

    /** Корзина: удаляет выделенные слои, группы (с потомками), текст, картинки, видео и аудиодорожки. */
    fun deleteSelected(cp: Boolean = true) {
        projectId ?: return
        val picked = _selection.value
        if (picked.isEmpty()) return
        if (picked.any { it >= BONE_ID_BASE }) { deleteBones(picked.filter { it >= BONE_ID_BASE }); return }
        pause()
        if (cp) checkpoint("delete", force = true)
        // группа уносит всех потомков
        val all = HashSet(picked)
        do {
            val before = all.size
            for (l in layers) if (l.parentId in all) all += l.id
        } while (all.size != before)
        deletedPsd = deletedPsd + all.filter { it < MEDIA_ID_BASE }
        val shownCount = all.size
        val removedMedia = media.filter { it.id in all }
        media.removeAll(removedMedia)
        // спринт 38: слой костей уносит свои кости; маски и прикрепления к удалённым слоям снимаются (цель снова видна целиком)
        val boneLayersGone = removedMedia.filter { it.kind == MediaKind.BONES }.mapTo(HashSet()) { it.id }
        if (boneLayersGone.isNotEmpty()) {
            val (b, l, goneBones) = com.vibetoon.domain.anim.BoneLayers.removeLayers(_bones.value, _links.value, boneLayersGone)
            _bones.value = b
            _links.value = l
            all += goneBones
        }
        for (id in all.toList()) if (id < BONE_ID_BASE) all += com.vibetoon.domain.anim.PathLayerMath.progressId(id)
        if (layerProps.values.any { it.mask in all || it.path in all } || layerProps.keys.any { it in all }) setProps(com.vibetoon.domain.model.LayerMasks.cleared(layerProps, all))
        for (id in all) { base.remove(id); override.remove(id) }
        _links.value = _links.value.filter { (c, p) -> c !in all && p !in all }
        groupDeformationAnchors = groupDeformationAnchors - all
        dropWarpData(all)
        dropMeshData(all)
        dropPerspData(all)
        val anim = _animation.value
        _animation.value = anim.copy(tracks = anim.tracks.filterKeys { id -> id !in all && all.none { com.vibetoon.domain.anim.EffectTracks.belongs(id, it) } })
        fxLayers = fxLayers.filterKeys { it !in all }
        textJob?.cancel()
        multiMode = false
        applySelection(emptySet(), null)
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Удалено: $shownCount")
        Log.i("VT/Edit", "deleted $shownCount nodes: $all")
    }

    // ---------------------------------------------------------------- настройки таймлайна

    /** Длительность проекта (кадры) и FPS. Ключи остаются на своих номерах кадров. */
    fun setTimeline(durationFrames: Int, fps: Int) {
        val m = meta ?: return
        pause()
        checkpoint("timeline", force = true)
        val d = durationFrames.coerceIn(1, TimeFormat.MAX_DURATION_FRAMES)
        val f = fps.coerceIn(1, TimeFormat.MAX_FPS)
        meta = m.copy(fps = f, durationFrames = d)
        _animation.value = _animation.value.copy(settings = ProjectSettings(f, d))
        _scenes.value = com.vibetoon.domain.anim.SceneMath.normalize(_scenes.value, d)   // сцены за новым концом обрезаются
        override.clear()
        _frame.value = _frame.value.coerceAtMost(d - 1)
        rebuildLayers()   // длина аудио в кадрах зависит от fps
        pushScene()
        markDirty()
        Log.i("VT/Anim", "timeline: $d frames at $f fps")
    }

    // ---------------------------------------------------------------- сцены (пакет 9)
    //
    // Сцена - именованная часть общего таймлайна [start, end). Содержимое общее; сцена задаёт отрезок для перехода,
    // проигрывания (Play только в сцене, с циклом) и экспорта (MP4/GIF/PNG только текущей сцены). Undo/Redo, сохранение.


    fun currentScene(): com.vibetoon.domain.anim.SceneRange? = com.vibetoon.domain.anim.SceneMath.at(_scenes.value, _frame.value)

    // ---------------------------------------------------------------- холсты сцен и переходы (пакет 24)
    //
    // Свой слой сцены (верхний узел: слой, группа, медиа) виден только в своих сценах, общий - во всех. Переход в сцену
    // из соседней предыдущей: растворение / через чёрный / через белый / сдвиг. Свой фон сцены. Редактор, Play и экспорт
    // (MP4/GIF/PNG) применяют один и тот же SceneCanvas.look; в контексте символа не действует.

    private fun sceneLookNow(): com.vibetoon.domain.anim.SceneCanvas.Look {
        val sc = _scenes.value
        if (_editingSymbol.value != null || !com.vibetoon.domain.anim.SceneCanvas.active(sc)) return com.vibetoon.domain.anim.SceneCanvas.Look.NONE
        return com.vibetoon.domain.anim.SceneCanvas.look(sc, _frame.value, meta?.background?.toInt() ?: 0)
    }

    /** Множитель прозрачности и сдвиг (доли ширины холста; у узлов PSD - в единицах PSD через fit) по виду кадра. */
    private fun withSceneLook(id: Int, t: LayerTransform, look: com.vibetoon.domain.anim.SceneCanvas.Look, cw: Int, fitM: Affine2D): LayerTransform {
        if (look.isNone) return t
        val a = look.alpha[id]
        val sh = look.shiftX[id]
        if (a == null && sh == null) return t
        val unit = if (id < MEDIA_ID_BASE) kotlin.math.hypot(fitM.a, fitM.b).takeIf { it > 1e-6f } ?: 1f else 1f
        return t.copy(opacity = t.opacity * (a ?: 1f), dx = t.dx + (sh ?: 0f) * cw / unit)
    }

    /** Переход через цвет: в конец цепочки эффектов каждого слоя - оттенок цвета перехода с силой затемнения. */
    private fun sceneLookFx(fx: Map<Int, List<FloatArray>>, look: com.vibetoon.domain.anim.SceneCanvas.Look, leaves: Set<Int>): Map<Int, List<FloatArray>> {
        if (look.overlay <= 0f || leaves.isEmpty()) return fx
        val c = if (look.overlayWhite) 1f else 0f
        val out = HashMap(fx)
        for (id in leaves) out[id] = fx[id].orEmpty() + listOf(floatArrayOf(TINT_FX, c, c, c, look.overlay.coerceIn(0f, 1f), 0f, 0f, 0f, 0f, 0f))
        return out
    }

    private fun leafIdsForLook(): Set<Int> = layers.asSequence().filter { it.kind == LayerKind.LAYER && it.id < BONE_ID_BASE }.mapTo(HashSet()) { it.id }

    /** Верхние узлы, которые можно сделать своими для сцены (без камеры и null-объектов): id и имя. */
    fun sceneLayerCandidates(): List<Pair<Int, String>> {
        val skip = media.filter { it.kind == MediaKind.CAMERA || it.isRig }.mapTo(HashSet()) { it.id }
        return roots.filter { it.id < BONE_ID_BASE && it.id !in skip }.reversed().map { it.id to layerName(it.id) }   // сверху вниз, как в панели слоёв
    }

    fun sceneLayerVisible(sceneId: Int, layer: Int): Boolean = com.vibetoon.domain.anim.SceneCanvas.visibleIn(_scenes.value, layer, sceneId)
    fun sceneLayerShared(layer: Int): Boolean = _scenes.value.none { layer in it.layers }

    fun setSceneLayer(sceneId: Int, layer: Int, visible: Boolean) {
        if (_playing.value) return
        val next = com.vibetoon.domain.anim.SceneCanvas.toggle(_scenes.value, layer, sceneId, visible)
            ?: run { _state.value = UiState.Message("Слой должен остаться хотя бы в одной сцене: используйте глаз в панели слоёв"); return }
        applyScenes(next, "scene-layer")
    }

    fun shareSceneLayer(layer: Int) {
        if (_playing.value || sceneLayerShared(layer)) return
        applyScenes(com.vibetoon.domain.anim.SceneCanvas.share(_scenes.value, layer), "scene-layer")
    }

    /** «+ Сцена с чистым холстом»: новая сцена в конце, все нынешние общие верхние слои остаются только в прежних сценах. */
    fun addSceneCleanCanvas() {
        if (_playing.value) return
        val before = _scenes.value.map { it.id }.toSet()
        addScene()
        val added = _scenes.value.firstOrNull { it.id !in before } ?: return
        val next = com.vibetoon.domain.anim.SceneCanvas.cleanCanvas(_scenes.value, sceneLayerCandidates().map { it.first }, added.id)
        if (next != _scenes.value) {
            _scenes.value = next   // тот же шаг Undo, что и добавление сцены
            pushScene()
            markDirty()
        }
        _state.value = UiState.Message("Сцена с чистым холстом: новые слои в ней можно сделать своими в «Холст и переход»")
    }

    fun setSceneTransition(sceneId: Int, type: com.vibetoon.domain.anim.SceneTransition, frames: Int) {
        if (_playing.value) return
        val next = com.vibetoon.domain.anim.SceneCanvas.setTransition(_scenes.value, sceneId, type, frames) ?: return
        if (next == _scenes.value) return
        applyScenes(next, "scene-transition:$sceneId")
    }

    fun setSceneBackground(sceneId: Int, argb: Int?) {
        if (_playing.value) return
        val next = com.vibetoon.domain.anim.SceneCanvas.setBackground(_scenes.value, sceneId, argb) ?: return
        applyScenes(next, "scene-bg")
    }

    fun projectBackground(): Int = meta?.background?.toInt() ?: 0

    private fun applyScenes(next: List<com.vibetoon.domain.anim.SceneRange>, key: String) {
        checkpoint(key, force = true)
        _scenes.value = com.vibetoon.domain.anim.SceneMath.normalize(next, _animation.value.settings.durationFrames)
        pushScene()   // пакет 24: холсты сцен и переходы видны сразу
        markDirty()
    }

    /** «+ Новая сцена» в конце таймлайна (2 секунды); без сцен весь текущий таймлайн становится «Сцена 1». */
    fun addScene() {
        if (_playing.value) return
        val st = _animation.value.settings
        val r = com.vibetoon.domain.anim.SceneMath.add(_scenes.value, st.durationFrames, (st.fps * 2).coerceAtLeast(1))
            ?: run { _state.value = UiState.Message("Слишком много сцен"); return }
        if (r.duration > TimeFormat.MAX_DURATION_FRAMES) { _state.value = UiState.Message("Проект достиг максимальной длительности"); return }
        checkpoint("scene", force = true)
        if (r.duration != st.durationFrames) {
            meta = meta?.copy(durationFrames = r.duration)
            _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, r.duration))
            rebuildLayers()
        }
        _scenes.value = r.scenes
        pushScene()
        markDirty()
        if (r.scenes.any { it.id == r.sceneId }) { _activeScene.value = r.sceneId; r.scenes.first { it.id == r.sceneId }.let { scrubTo(it.start) } }
        _state.value = UiState.Message("Сцена добавлена в конец таймлайна")
    }

    /** «Разрезать на плейхеде»: сцена под плейхедом делится на две. */
    fun splitSceneAtPlayhead() {
        if (_playing.value) return
        val next = com.vibetoon.domain.anim.SceneMath.split(_scenes.value, _animation.value.settings.durationFrames, _frame.value)
            ?: run { _state.value = UiState.Message("Поставьте плейхед внутрь сцены, не на её первый кадр"); return }
        applySceneChange(next, "Сцена разрезана на плейхеде")
    }

    private fun applySceneChange(next: List<com.vibetoon.domain.anim.SceneRange>, message: String) {
        applyScenes(next, "scene")
        _state.value = UiState.Message(message)
    }

    fun renameScene(id: Int, name: String) {
        val next = com.vibetoon.domain.anim.SceneMath.rename(_scenes.value, id, name) ?: return
        applyScenes(next, "scene-name")
    }

    /** Удалить сцену: только метку, кадры и ключи остаются (отрезок присоединяется к соседней сцене). */
    fun removeScene(id: Int) {
        if (_playing.value || _scenes.value.none { it.id == id }) return
        if (_activeScene.value == id) _activeScene.value = null
        applySceneChange(com.vibetoon.domain.anim.SceneMath.remove(_scenes.value, id), "Сцена удалена, кадры остались на таймлайне")
    }

    /** Переключиться на сцену: плейхед на её первый кадр. */
    fun selectScene(id: Int) {
        val sc = _scenes.value.firstOrNull { it.id == id } ?: return
        pause()
        _activeScene.value = id
        scrubTo(sc.start)
        _state.value = UiState.Message("Открыта сцена: Play, переходы и экспорт - в её границах")
    }

    /** «Весь таймлайн»: выйти из сцены, плейхед и Play снова по всему проекту. */
    fun showAllScenes() {
        if (_activeScene.value == null) return
        _activeScene.value = null
        _state.value = UiState.Message("Показан весь таймлайн")
    }

    /** Активная сцена (если открыта и существует) - не в режиме редактирования символа (у него свои кадры). */
    fun activeRange(): com.vibetoon.domain.anim.SceneRange? {
        val id = _activeScene.value ?: return null
        if (_editingSymbol.value != null) return null
        return _scenes.value.firstOrNull { it.id == id }
    }

    /** Предыдущая / следующая сцена. */
    fun stepScene(dir: Int) {
        val sc = com.vibetoon.domain.anim.SceneMath.step(_scenes.value, _frame.value, dir)
            ?: run { _state.value = UiState.Message("Сцен в этом направлении нет"); return }
        pause()
        if (_activeScene.value != null) { selectScene(sc.id); return }
        scrubTo(sc.start)
    }

    fun setScenePlayOnly(on: Boolean) { _scenePlayOnly.value = on }
    fun setExportSceneOnly(on: Boolean) { exportSceneOnly = on }

    /** Отрезок экспорта: текущая сцена (если выбран экспорт сцены и плейхед в сцене) или null - весь проект. */
    private fun exportRange(): com.vibetoon.domain.anim.SceneRange? = if (exportSceneOnly) (activeRange() ?: currentScene()) else null

    // ---------------------------------------------------------------- режим ключей (пакет 10)

    fun toggleKeyMode() {
        _keyMode.value = !_keyMode.value
        _state.value = UiState.Message(if (_keyMode.value) "Режим ключей: показаны только слои с ключами" else "Режим ключей выключен")
    }

    private fun allKeyFrames(): Set<Int> {
        val tracks = _animation.value.tracks
        return com.vibetoon.domain.anim.KeyMode.frames(layers.map { it.id }, tracks,
            _timeKeys.value.mapValues { (_, l) -> l.map { it.frame } }, _bones.value.map { it.id })
    }

    /** «◀ Ключ / Ключ ▶»: ближайший кадр с ключом любого слоя или кости (в границах открытой сцены). */
    fun jumpAnyKey(forward: Boolean) {
        val r = activeRange()
        val target = com.vibetoon.domain.anim.KeyMode.step(allKeyFrames(), _frame.value, forward, r?.start ?: 0, (r?.end ?: duration()) - 1)
        if (target != null) scrubTo(target) else _state.value = UiState.Message("Ключей в этом направлении нет")
    }

    /** Выделить все ключи на кадре плейхеда (слои, ключи времени символов, кости). */
    fun selectKeysAtFrame() {
        val f = _frame.value
        val tracks = _animation.value.tracks
        val sel = HashSet<Pair<Int, Int>>()
        for (l in layers) if (f in com.vibetoon.domain.anim.TrackGroups.frames(l.id, tracks) || _timeKeys.value[l.id]?.any { it.frame == f } == true) sel += l.id to f
        for (b in _bones.value) if (tracks[b.id]?.keyAt(f) != null) sel += b.id to f
        if (sel.isEmpty()) { _state.value = UiState.Message("На этом кадре ключей нет"); return }
        _keySel.value = sel
    }

    /** Выделить все ключи (в границах открытой сцены). */
    fun selectAllKeys() {
        val r = activeRange()
        val tracks = _animation.value.tracks
        val sel = HashSet<Pair<Int, Int>>()
        fun inRange(f: Int) = r == null || f in r.start until r.end
        for (l in layers) {
            for (f in com.vibetoon.domain.anim.TrackGroups.frames(l.id, tracks)) if (inRange(f)) sel += l.id to f
            _timeKeys.value[l.id]?.forEach { if (inRange(it.frame)) sel += l.id to it.frame }
        }
        for (b in _bones.value) tracks[b.id]?.keys?.forEach { if (inRange(it.frame)) sel += b.id to it.frame }
        if (sel.isEmpty()) { _state.value = UiState.Message("Ключей нет"); return }
        _keySel.value = sel
    }

    /**
     * Спринт 43: «Выбрать все ключи слоя» - ключи только активного слоя (все его дорожки), чтобы применить кривую
     * интерполяции ко всем сразу. Другие слои не выделяются.
     */
    fun selectLayerKeys() {
        val id = _selected.value ?: run { _state.value = UiState.Message("Выберите слой"); return }
        val r = activeRange()
        val sel = com.vibetoon.domain.anim.LayerKeySelection.of(id, _animation.value.tracks, isBoneId(id),
            _timeKeys.value[id]?.map { it.frame }.orEmpty(), r?.let { it.start until it.end })
        if (sel.isEmpty()) { _state.value = UiState.Message("У слоя нет ключей"); return }
        _keySel.value = sel
        _state.value = UiState.Message("Выбрано ключей слоя: ${sel.size}")
    }

    // ---------------------------------------------------------------- список ключей (пакет 11)

    private fun isBoneId(id: Int) = _bones.value.any { it.id == id }

    /** Ключи слоя или кости [id] по кадрам (для окна «Список ключей»). */
    fun keyListOf(id: Int): List<com.vibetoon.domain.anim.KeyList.Row> =
        com.vibetoon.domain.anim.KeyList.rows(id, _animation.value.tracks, isBoneId(id))

    /** Интерполяция одного ключа (все дорожки узла на этом кадре), одной записью Undo. */
    fun setKeyEasingAt(id: Int, frame: Int, type: EasingType) {
        if (_playing.value) return
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("keylist", force = true)
        _animation.value = com.vibetoon.domain.anim.KeyList.withEasing(_animation.value, id, frame, type, isBoneId(id))
        pushScene(); refreshInspector(); markDirty()
    }

    /** Одна интерполяция всем ключам узла. */
    fun setAllKeysEasing(id: Int, type: EasingType) {
        if (_playing.value) return
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("keylist", force = true)
        _animation.value = com.vibetoon.domain.anim.KeyList.withEasingAll(_animation.value, id, type, isBoneId(id))
        pushScene(); refreshInspector(); markDirty()
        _state.value = UiState.Message("Интерполяция всем ключам: ${keyListOf(id).size}")
    }

    /** Перейти к ключу: плейхед на кадр, ключ выделяется на таймлайне. */
    fun goToKey(id: Int, frame: Int) {
        pause()
        val r = activeRange()
        if (r != null && frame !in r.start until r.end) showAllScenes()   // ключ вне открытой сцены - весь таймлайн
        setFrame(frame)
        _keySel.value = setOf(id to frame)
    }

    /** Удалить ключ узла на кадре (все дорожки, как ромб на таймлайне). */
    fun deleteKeyAt(id: Int, frame: Int) {
        if (_playing.value) return
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("keydelete", force = true)
        removeKeys(setOf(id to frame))
        _keySel.value = _keySel.value - (id to frame)
        override.clear(); pushScene(); refreshInspector(); markDirty()
    }

    // ---------------------------------------------------------------- иерархия трансформаций (пакет 10)

    /** Привязать узел [child] к [parent] (null - отвязать) из окна «Иерархия трансформаций». */
    fun setParentOf(child: Int, parent: Int?) {
        if (_playing.value) return
        if (parent != null && (parent == child || parent in com.vibetoon.domain.anim.TransformHierarchy.descendants(child, _links.value))) {
            _state.value = UiState.Message("Нельзя: родитель сам привязан к этому слою"); return
        }
        if (_links.value[child] == parent) return
        checkpoint("parent", force = true)
        if (parent != null && _bones.value.any { it.id == parent }) skinLayers = skinLayers - child
        _links.value = if (parent == null) _links.value - child else _links.value + (child to parent)
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (parent == null) "Привязка снята" else "Привязано к: ${layerName(parent)}")
    }

    /** Кандидаты в родители узла [child]: всё, кроме него и его потомков по привязкам. */
    fun parentCandidatesOf(child: Int): List<Pair<Int, String>> {
        val banned = com.vibetoon.domain.anim.TransformHierarchy.descendants(child, _links.value) + child
        return _bones.value.filter { it.id !in banned }.map { it.id to it.name } +
            layers.filter { it.id !in banned && it.kind != LayerKind.GROUP_END }.map { it.id to it.name }
    }

    /** Группы-предки узла снизу вверх (они тоже передают трансформацию). */
    fun groupChain(id: Int): List<Int> {
        val parentOf = layers.associate { it.id to it.parentId }
        return com.vibetoon.domain.project.SymbolPlacement.ancestors(id) { parentOf[it] }
    }

    /** Порядок узлов для дерева привязок: кости, затем слои сверху вниз. */
    fun hierarchyOrder(): List<Int> = _bones.value.map { it.id } + layers.reversed().map { it.id }

    fun unlinkAll() {
        if (_links.value.isEmpty() || _playing.value) return
        checkpoint("parent", force = true)
        _links.value = emptyMap()
        pushScene(); markDirty()
        _state.value = UiState.Message("Все привязки сняты")
    }

    // ---------------------------------------------------------------- расширение клипа (пакет 10)

    /** «Расширить»: клипы выделенных слоёв тянутся до конца открытой сцены (или проекта). */
    fun extendSelectedClips() {
        if (_playing.value) return
        val ids = _selection.value.ifEmpty { _selected.value?.let { setOf(it) }.orEmpty() }.filter { it < BONE_ID_BASE }
        if (ids.isEmpty()) { _state.value = UiState.Message("Выберите слой"); return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        val r = activeRange()
        val lo = r?.start ?: 0
        val hi = r?.end ?: duration()
        val changes = ids.mapNotNull { id ->
            val c = clipOf(id)
            val m = media.firstOrNull { it.id == id }
            if (m != null && !m.isVisual) return@mapNotNull null
            val plain = c.speed == 1f && !c.reverse && !c.freeze && c.offset == 0 && !c.loop && m?.kind != MediaKind.VIDEO
            val (s0, e0) = com.vibetoon.domain.anim.ClipExtend.extend(c.start, c.end, lo, hi, plain)
            if (s0 == c.start && e0 == c.end) null else id to c.copy(start = s0, end = e0)
        }
        if (changes.isEmpty()) { _state.value = UiState.Message("Клип уже занимает всю сцену"); return }
        checkpoint("clipextend", force = true)
        changes.forEach { (id, c) -> storeClip(id, c) }
        pushScene(); markDirty()
        _state.value = UiState.Message(if (r != null) "Клип расширен до конца сцены" else "Клип расширен до конца проекта")
    }

    // ---------------------------------------------------------------- время и Play

    /** Перемотка (ползунок, поле кадра). Временные правки кадра сбрасываются. */
    fun setFrame(frame: Int) {
        val r = activeRange()
        val f = if (r != null) frame.coerceIn(r.start, r.end - 1) else frame.coerceIn(0, _animation.value.settings.durationFrames - 1)
        if (f == _frame.value) return
        val t0 = if (com.vibetoon.render.RenderMetrics.enabled) System.nanoTime() else 0L
        cancelPendingLasso()
        override.clear()
        _frame.value = f
        pushScene()
        followSelectionDuringPlayback()
        updateVideoFrames(f)
        updateTextFx(f)
        updateCells(f)
        refreshOnion()
        if (t0 != 0L) recordFrameMetrics((System.nanoTime() - t0) / 1e6f)
    }

    // ---- метрики производительности (Настройки -> «Панель метрик»): время сборки кадра на UI-потоке и счётчики GL

    private val frameTimer = com.vibetoon.domain.render.FrameTimer(120)
    private val drawTimer = com.vibetoon.domain.render.FrameTimer(120)
    private var lastHudAt = 0L
    private val _perfHud = MutableStateFlow<String?>(null)
    /** Строка панели метрик или null (панель выключена). */
    val perfHud: StateFlow<String?> = _perfHud.asStateFlow()

    init {
        if (getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("perf_hud", false)) setPerfHud(true)
    }

    fun setPerfHud(on: Boolean) {
        com.vibetoon.render.RenderMetrics.enabled = on
        frameTimer.clear(); drawTimer.clear()
        _perfHud.value = if (on) "Метрики: прокрутите или запустите Play" else null
    }

    private fun recordFrameMetrics(ms: Float) {
        frameTimer.add(ms)
        drawTimer.add(com.vibetoon.render.RenderMetrics.drawMs)
        val now = SystemClock.uptimeMillis()
        if (now - lastHudAt < 500) return
        lastHudAt = now
        val budget = 1000f / _animation.value.settings.fps.coerceAtLeast(1)
        val m = com.vibetoon.render.RenderMetrics
        fun f1(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
        // Строка-шаблон с {}: панель переводится через L() как остальные сообщения.
        _perfHud.value = "Кадр (UI): ср ${f1(frameTimer.average())} / p95 ${f1(frameTimer.percentile(0.95f))} мс · GL: ${f1(drawTimer.average())} мс · " +
            "медленных ${(frameTimer.overBudget(budget) * 100).toInt()}% · слоёв ${m.drawn}, вне экрана ${m.culled} · FX в рамке ${m.scissoredFx}, проходов ${m.fxPasses}, из кэша ${m.fxCached} · групп из кэша ${m.groupCached} · рисунков из кэша ${cellCache.hitPercent()}%"
    }

    private val videoFrames = com.vibetoon.data.VideoFrames { RenderHost.view?.requestRender() }

    /** Кадр источника для видео-слоя на кадре проекта [f]: учитывает начало клипа, скорость, реверс, стоп-кадр и смещение. */
    private fun videoSourceMs(clip: Clip, f: Int, fps: Int): Long = com.vibetoon.domain.project.VideoTime.sourceMs(clip, f.toFloat(), fps)

    // ---- пакет 11: плёнка видео на полосе клипа (кадры в фоне, кэш)
    private val stripRedrawPosted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _filmstripVersion = MutableStateFlow(0)
    /** Меняется, когда в кэше плёнки появились новые кадры: таймлайн перерисовывает полосы видео. */
    val filmstripVersion: StateFlow<Int> = _filmstripVersion.asStateFlow()
    private val videoThumbs = com.vibetoon.data.VideoThumbs {
        if (stripRedrawPosted.compareAndSet(false, true)) viewModelScope.launch {
            kotlinx.coroutines.delay(60)   // пачка готовых кадров - одна перерисовка
            stripRedrawPosted.set(false)
            _filmstripVersion.value++
        }
    }

    /** id видео-слоёв (для плёнки и волны звука на таймлайне). */
    fun videoIds(): Set<Int> = media.filter { it.kind == MediaKind.VIDEO }.mapTo(HashSet()) { it.id }

    /** Ширина / высота кадра видео (для ширины плиток плёнки). */
    fun videoAspect(id: Int): Float {
        val m = media.firstOrNull { it.id == id && it.kind == MediaKind.VIDEO } ?: return 16f / 9f
        return if (m.srcWidth > 0 && m.srcHeight > 0) (m.srcWidth.toFloat() / m.srcHeight).coerceIn(0.3f, 4f) else 16f / 9f
    }

    /** Видимые плитки плёнки видео-слоя [id] при клипе [clip] (пиксели таймлайна). */
    fun filmstripTiles(id: Int, clip: Clip, frameW: Float, tileW: Float, vis0: Float, vis1: Float): List<com.vibetoon.domain.project.Filmstrip.Tile> {
        val m = media.firstOrNull { it.id == id && it.kind == MediaKind.VIDEO } ?: return emptyList()
        return com.vibetoon.domain.project.Filmstrip.tiles(clip, _animation.value.settings.fps, frameW, tileW, vis0, vis1, m.durationMs)
    }

    /** Кадр плёнки (или null - он декодируется в фоне, придёт перерисовка). */
    fun filmstripFrame(id: Int, ms: Long): androidx.compose.ui.graphics.ImageBitmap? {
        val pid = projectId ?: return null
        val m = media.firstOrNull { it.id == id && it.kind == MediaKind.VIDEO } ?: return null
        return videoThumbs.get(projects.fileOf(pid, m.file), ms, m.srcWidth, m.srcHeight)
    }

    /** При смене кадра подставляет в текстуры видео-слоёв нужный кадр источника. */
    private fun updateVideoFrames(f: Int) {
        val pid = projectId ?: return
        val fps = _animation.value.settings.fps
        val playing = _playing.value
        val visible = media.filter { it.kind == MediaKind.VIDEO && clipOf(it.id).visibleAt(f) }
        // спринт 31: при нескольких видео сразу кадр при воспроизведении меньше - декод не отстаёт
        val side = com.vibetoon.domain.perf.VideoDecodePolicy.maxSide(playing, visible.size)
        for (m in visible) {
            val clip = clipOf(m.id)
            videoFrames.request(
                m.id, projects.mediaFile(pid, m.file.removePrefix("assets/")), videoSourceMs(clip, f, fps),
                m.srcWidth, m.srcHeight, side
            )
        }
    }

    fun stopScrubPreview() { audioEngine.cancelScrub() }

    /** Пользователь тянет ползунок: воспроизведение останавливается. */
    fun scrubTo(frame: Int) {
        pause()
        setFrame(frame)
        // скраббинг звука: короткий фрагмент на новом кадре (не чаще раза в 45 мс)
        val now = SystemClock.uptimeMillis()
        val f = _frame.value
        if (getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)
                .getBoolean("audio_while_scrubbing", true) && f != lastScrubFrame && now - lastScrubAt >= 45) {
            lastScrubAt = now; lastScrubFrame = f
            audioEngine.scrub(f, _animation.value.settings.fps, ::clipOf)
        }
    }

    private var frameCallback: Choreographer.FrameCallback? = null
    private var playbackStartFrame = 0

    /** Сброс применяется только к явной паузе, а не к скраббингу, смене проекта или концу видео. */
    fun togglePlay() {
        if (_playing.value) {
            pause()
            val reset = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE)
                .getBoolean("reset_frame_after_pause", false)
            val target = com.vibetoon.domain.anim.PausePolicy.frameAfterStop(_frame.value, playbackStartFrame, reset, explicitPause = true)
            if (target != _frame.value) setFrame(target)
        } else play()
    }

    /**
     * Воспроизведение: кадр считается по часам Choreographer (время, а не число вызовов),
     * цикл по длительности. Сцена пересобирается только при смене целого кадра.
     */
    fun play() {
        if (_playing.value) return
        cancelPendingLasso()
        prewarmForms()   // формы всех ключей снимаются до/в начале проигрывания
        _playing.value = true
        followSelectionDuringPlayback()
        val settings = _animation.value.settings
        val looping = _loop.value
        // пакет 9: «Play только в сцене» - проигрывается сцена под плейхедом
        val scene = activeRange() ?: if (_scenePlayOnly.value) currentScene() else null
        // без цикла старт с последнего кадра начинает проигрывание заново
        val startFrame = when {
            scene != null -> if (!looping && _frame.value >= scene.end - 1) scene.start else _frame.value
            !looping && _frame.value >= settings.durationFrames - 1 -> 0
            else -> _frame.value
        }
        val lastFrame = if (scene != null) scene.end - 1 else settings.durationFrames - 1
        playbackStartFrame = startFrame
        var startNanos = -1L
        var lastF = startFrame
        audioEngine.play(startFrame, settings.fps, ::clipOf, lastFrame + 1)
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!_playing.value) return
                if (startNanos < 0) startNanos = frameTimeNanos
                val f = if (scene != null) com.vibetoon.domain.anim.SceneMath.playFrame((frameTimeNanos - startNanos) * settings.fps / 1_000_000_000L, startFrame, scene, looping)
                    else PlaybackMath.frameAt(frameTimeNanos - startNanos, settings.fps, startFrame, settings.durationFrames, looping)
                if (f < lastF) audioEngine.play(f, settings.fps, ::clipOf, lastFrame + 1)   // цикл: звук с начала
                lastF = f
                if (f != _frame.value) {
                    setFrame(f)
                    if (f % settings.fps == 0) Log.i("VT/Anim", "play frame $f")
                }
                if (!looping && f >= lastFrame) { pause(); return }   // конец сцены: стоп на последнем кадре
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
        Log.i("VT/Anim", "play from frame $startFrame")
    }

    fun pause() {
        if (!_playing.value && frameCallback == null) return
        _playing.value = false
        audioEngine.pause()
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        refreshPanelsAfterPlay()
        Log.i("VT/Anim", "pause at frame ${_frame.value}")
    }

    // ---------------------------------------------------------------- экспорт в MP4

    private val _export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val export: StateFlow<ExportUi> = _export.asStateFlow()
    private var exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    // ---------------------------------------------------------------- Спринт 41: импорт и экспорт в фоне
    // Служба переднего плана (com.vibetoon.work.BackgroundWorkService) держит процесс, пока идёт экспорт или импорт:
    // свернули приложение - работа продолжается (экспорт MP4/GIF/PNG рисуется во внеэкранном контексте GL), в шторке
    // виден процент. init стоит ПОСЛЕ объявления _export (порядок инициализации).
    private var exportWork = 0L
    private var exportWorkStarted = false

    init {
        viewModelScope.launch {
            _export.collect { st ->
                val ctx = getApplication<Application>()
                if (st is ExportUi.Running) {
                    if (!exportWorkStarted) {
                        exportWorkStarted = true
                        exportWork = com.vibetoon.work.BackgroundWork.begin(ctx, "Экспорт")   // 0 - фон выключен в настройках
                        RenderHost.view?.backgroundWorkChanged()
                    }
                    com.vibetoon.work.BackgroundWork.progress(ctx, exportWork, st.done, st.total)
                } else if (exportWorkStarted) {
                    exportWorkStarted = false
                    com.vibetoon.work.BackgroundWork.end(ctx, exportWork)
                    exportWork = 0L
                }
            }
        }
    }

    /** Импорт (видео, GIF, секвенции, PSD, медиа) под службой фоновой работы: не сбрасывается при сворачивании. */
    private fun launchImport(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
        val ctx = getApplication<Application>()
        val work = com.vibetoon.work.BackgroundWork.begin(ctx, "Импорт")
        val job = viewModelScope.launch(block = block)
        job.invokeOnCompletion {
            android.os.Handler(android.os.Looper.getMainLooper()).post { com.vibetoon.work.BackgroundWork.end(ctx, work) }
        }
        return job
    }

    /** GL можно использовать: приложение на экране или в фоне работает служба импорта/экспорта. */
    private fun glUsable(): Boolean = !appBackground || RenderHost.view?.backgroundRenderAllowed == true

    /** Размер кадра MOV (спринт 32): пропорции холста, чётные стороны, без выравнивания по 16 - PNG его не требует. */
    fun exportEvenSize(shortSide: Int): Pair<Int, Int> = ExportMath.size(canvasW, canvasH, shortSide)

    /** Размер видео и число кадров для диалога. */
    fun exportInfo(shortSide: Int, fps: Int): Triple<Int, Int, Int> {
        val (w, h) = ExportMath.videoSize(canvasW, canvasH, shortSide)
        val st = _animation.value.settings
        return Triple(w, h, ExportMath.frameCount(exportRange()?.length ?: st.durationFrames, st.fps, fps))
    }

    /**
     * Рендер всей анимации в MP4 (со звуком - пакет 12) в папку проекта. Идёт в GL-потоке прямо в кодек, быстрее
     * реального времени. Сцена каждого кадра строится из снимка анимации: ручные правки кадра не влияют.
     */
    /** Строит функцию «номер кадра результата -> сцена» (копии данных проекта: можно звать из любого потока). */
    private fun buildSceneAt(fps: Int, onlyLayers: Set<Int>? = null, symbol: Int? = null,
                             symCache: com.vibetoon.domain.project.ExportSymbolForms.Set = com.vibetoon.domain.project.ExportSymbolForms.Set.EMPTY,
                             range: com.vibetoon.domain.anim.SceneRange? = null): (Int) -> SceneData {
        val outsideIds: Set<Int> = if (onlyLayers == null) emptySet() else layers.filter { it.kind == LayerKind.LAYER && it.id !in onlyLayers }.map { it.id }.toSet()
        val st = _animation.value.settings
        val anim = _animation.value
        val adjCopy = _colors.value
        val baseCopy = HashMap(base)
        val clipsCopy = _clips.value
        val fxIdsExp = fxLayers.keys
        val hiddenExp = layerProps.filterValues { !it.visible }.keys + symbolHidden(symbol)   // в контексте символа видны только его слои
        // Пакет 9: снимок формы символа без общих с экземплярами предков (их трансформации, привязки и эффекты экземпляр
        // получает в сцене сам) и без общей цепочки эффектов экземпляров у участников - иначе двойной сдвиг/тряска
        val snapCtx = if (symbol != null) symbolSnapshotContext(symbol) else null
        val neutral = snapCtx?.first.orEmpty()
        // пакет 24: свои слои сцен и переходы - в экспорте те же, что в редакторе (не в снимках символов и слоёв)
        val scenesExp = if (symbol == null && onlyLayers == null && com.vibetoon.domain.anim.SceneCanvas.active(_scenes.value)) _scenes.value else emptyList()
        val ownedExp = com.vibetoon.domain.anim.SceneCanvas.owned(scenesExp)
        val bgExp = meta?.background?.toInt() ?: 0
        val leafExp = if (scenesExp.isEmpty()) emptySet() else leafIdsForLook()
        val fitExp = fit; val cwExp = canvasW
        val ids = baseCopy.keys + anim.tracks.keys.filter { it >= 0 } + clipsCopy.keys + fxIdsExp + adjCopy.keys + hiddenExp + outsideIds + neutral + ownedExp
        val r = roots; val cw = canvasW; val ch = canvasH; val f = fit; val tids = textIds; val lnk = if (neutral.isEmpty()) _links.value else _links.value - neutral; val bnd = boneHeads(); val wl = warpLayers; val indep = independentBones(); val ml = meshLayers; val pl = perspLayers; val btl = boneTails(); val sk = effectiveSkins(); val tl = turbLayers; val fxl = if (snapCtx == null || symbol == null) fxLayers else com.vibetoon.domain.project.SymbolPlacement.snapshotFx(fxLayers, symbolMembers(symbol), snapCtx.second, neutral); val camId = if (symbol != null) -1 else cameraIdNow(); val pinl = pinLayers; val lsz = layers.associate { it.id to (it.width.toFloat() to it.height.toFloat()) }
        // Фиксируем те же опции, что и в редакторе: null-объекты не рисуются,
        // а глубина родительских групп применяется и при экспорте кадров.
        val sceneOptions = com.vibetoon.domain.render.SceneOptions.capture(media, layerProps)
        val groupAnchorsExp = groupDeformationAnchors.toMap()
        val warpSizesExp = warpGridSizes.toMap()
        val denseExp = denseWarps.toMap()
        val symMembers = if (symbol != null) symbolMembers(symbol) else null
        val fxTexts = media.filter { it.isText && it.fx.animated && (symMembers == null || it.id in symMembers) }
        val cellLayers = media.filter { it.kind == MediaKind.IMAGE && it.cells.isNotEmpty() && (symMembers == null || it.id in symMembers) }
        val symInside = HashSet<Int>().also { st0 -> media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.forEach { st0 += symbolMembers(it.id) } }
        val symInst = if (symbol == null) media.filter { it.kind == MediaKind.SYMBOL && it.id !in symInside } else emptyList()
        val symDefs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.associate { it.id to (it.symDur to symbolCycles(it.id)) }
        val symMarks = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.associate { it.id to it.markers }
        val symModes = symInst.associate { it.id to SymbolPlayback.mode(it.symLoop, it.symOnce) }   // спринт 37: Hold / Loop / Play Once
        val symCropExp = symInst.associate { it.id to formCrop(it.id) }
        val pidExp = projectId
        val shadow = symbol != null   // снимок символа: свои (теневые) рисунки участников, текстуры редактора не трогаем
        // Пакет 29: видео-слои - кадр источника на каждом кадре экспорта (раньше весь ролик стоял один кадр)
        val videoExp = if (shadow || pidExp == null) emptyList() else media.filter { it.kind == MediaKind.VIDEO && it.id !in hiddenExp && it.id !in outsideIds }
            .map { Triple(it, clipOf(it.id), projects.mediaFile(pidExp, it.file.removePrefix("assets/"))) }
        val session = if (shadow) null else ExportSession(maxOf(cw, ch)).also { synchronized(exportSessions) { exportSessions += it } }
        val sceneRange = if (symbol == null) range else null   // пакет 9: экспорт только текущей сцены
        val exportDeformCache = com.vibetoon.domain.render.DeformCache()   // спринт 43: свой кэш сеток у экспорта
        val sceneAtFn = { i: Int ->
            // Внутренний кадр символа берётся как есть (раньше обрезался длительностью проекта - формы за её концом
            // снимались как последний кадр).
            val pf = if (symbol != null) i.coerceAtLeast(0)
                else if (sceneRange != null) com.vibetoon.domain.anim.SceneMath.exportFrame(i, st.fps, fps, sceneRange)
                else ExportMath.projectFrame(i, st.fps, fps, st.durationFrames)
            val lookExp = if (scenesExp.isEmpty()) com.vibetoon.domain.anim.SceneCanvas.Look.NONE else com.vibetoon.domain.anim.SceneCanvas.look(scenesExp, pf, bgExp)
            // Пакет 29: картинки этого кадра не ставятся в очередь загрузки сразу (сцена следующего кадра MP4 собирается,
            // пока рисуется текущий), а готовятся здесь и применяются перед отрисовкой именно этого кадра (beforeDraw).
            val plan = ArrayList<com.vibetoon.domain.export.ExportTextures.Item<android.graphics.Bitmap>>()
            val fpsP = st.fps.coerceAtLeast(1)
            for (cm in cellLayers) {   // покадровые слои: в текстуре рисунок этого кадра
                val act = FrameMath.active(cm.cells, pf) ?: continue
                if (pidExp == null) continue
                if (shadow) {
                    runCatching {
                        val d = importer.decodeImage(projects.fileOf(pidExp, act.file))
                        RenderNative.uploadShadowBitmap(cm.id, d.bitmap); d.bitmap.recycle()
                    }
                    continue
                }
                plan += session!!.textures.plan(cm.id, "cell:" + act.file, 0L) { importer.decodeImage(projects.fileOf(pidExp, act.file)).bitmap }
            }
            if (session != null) for ((vm, clip, file) in videoExp) {   // видео: кадр источника на моменте этого кадра
                if (!clip.visibleAt(pf)) continue
                val ms = videoSourceMs(clip, pf, fpsP)
                plan += session.textures.plan(vm.id, com.vibetoon.domain.export.ExportVideo.Key(file.path, ms), 0L) {
                    session.video.frame(file, ms, vm.srcWidth, vm.srcHeight)
                }
            }
            val formAliases = ArrayList<Pair<Int, com.vibetoon.domain.project.ExportSymbolForms.Form>>()
            for (si in symInst) {   // экземпляры символов: готовая форма на GPU (или запасная картинка) на этом кадре
                val def = symDefs[si.symRef] ?: continue
                val mode = symModes[si.id] ?: SymbolPlayback.HOLD
                val key = SymbolForms.frameAt(si.timeKeys, pf, def.first, def.second && mode != SymbolPlayback.HOLD, mode, clipsCopy[si.id]?.start ?: 0, symMarks[si.symRef].orEmpty())   // то же правило, что в редакторе
                val form = symCache.form(si.symRef, key, symCropExp[si.id] == true)
                if (form != null) { formAliases += si.id to form; continue }
                val png = symCache.png(si.symRef, key) ?: continue
                if (session == null) continue
                plan += session.textures.plan(si.id, "sym:${si.symRef}:$key", 0L) { android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size) }
            }
            for (tm in fxTexts) {   // текст с эффектами: картинка этого кадра заливается в текстуру до отрисовки
                val tSec = pf.toFloat() / fpsP
                val render = { textRenderer.renderFx(tm.text, tm.textColor.toInt(), tm.font, tm.textSize, tm.gradient, tm.gradientAngle, tm.fx, tSec, fpsP, if (tm.stroke) tm.strokeWidth else 0f, tm.strokeColor.toInt(), tm.textAlign, tm.lineSpacing, tm.letterSpacing, tm.textDir).bitmap }
                if (shadow) { val b = render(); RenderNative.uploadShadowBitmap(tm.id, b); b.recycle(); continue }
                plan += session!!.textures.plan(tm.id, "fx:" + tm.fx.stateKey(tSec, fpsP), 0L, render)
            }
            val batch = if (session != null && plan.isNotEmpty()) session.textures.batch(plan).also { session.track(it) } else null
            val groupInputs = com.vibetoon.domain.render.GroupSceneInputs.build(groupAnchorsExp, wl, pl, pf, baseCopy, anim, true, warpSizesExp)
            try {
            SceneBuilder.buildTree(r, cw, ch, ids.associateWith { id ->
                if (id in neutral) return@associateWith (if (id in hiddenExp) LayerTransform(opacity = 0f) else LayerTransform())
                val t0 = clipsCopy[id]?.let { c -> anim.evaluate(id, c.animFrame(pf), baseCopy[id]) } ?: anim.evaluate(id, pf, baseCopy[id])
                val t = fxl[id]?.let { l -> t0.copy(rotation = t0.rotation + com.vibetoon.domain.anim.EffectTracks.extraRotation(id, l, anim, pf, st.fps)) } ?: t0
                val c = clipsCopy[id]
                if (id in hiddenExp || id in outsideIds || (c != null && !c.visibleAt(pf))) t.copy(opacity = 0f) else withSceneLook(id, t, lookExp, cwExp, fitExp)
            }, f, tids, lnk, bnd, groupInputs.warps, indep,
                ml.mapValues { (lid, m) ->
                    val v = MeshMath.offsets(lid, m, pf, baseCopy, anim, true)
                    val ps = pinl[lid]
                    val sz = lsz[lid]
                    m to (if (ps != null && sz != null) anim.evaluate(lid, pf, baseCopy[lid]).let { lt ->
                        PuppetMath.addTo(m, v, ps, PuppetMath.offsets(lid, ps.size, pf, baseCopy, anim, true), sz.first, sz.second, lt.scaleX, lt.scaleY)
                    } else v)
                },
                groupInputs.perspectives, btl, sk, tl.mapValues { (id, t) -> TurbMath.params(TurbMath.at(id, t, pf, anim), pf) },
                sceneLookFx(fxRecords(fxl, adjCopy, anim, pf, st.fps), lookExp, leafExp), camId, sceneOptions.nullIds, sceneOptions.depths, groupDeformations = groupInputs.groups, denseWarps = denseExp, leafPivots = pivotOverrides,
                stretches = com.vibetoon.domain.anim.EffectTracks.stretches(fxl, anim, pf),
                stretchMasks = com.vibetoon.domain.anim.EffectTracks.stretchMasks(fxl, anim, pf),
                layerMasks = sceneOptions.layerMasks, maskSources = sceneOptions.maskSources,
                pathPlaces = sceneOptions.pathPlaces { lid -> com.vibetoon.domain.anim.PathLayerMath.progressId(lid).let { pidP -> anim.evaluate(pidP, pf, baseCopy[pidP]).dx } },
                deformCache = exportDeformCache).also { sc ->
                sc.backgroundOverride = lookExp.background
                if (formAliases.isNotEmpty()) sc.aliases = com.vibetoon.domain.project.ExportSymbolForms.aliases(formAliases)
                if (batch != null && session != null) sc.beforeDraw = { session.textures.commit(batch) }
            }
            } catch (e: IllegalArgumentException) {
                // спринт 43: «неподдерживаемых» сочетаний деформаций больше нет; сюда попадает только настоящая ошибка
                throw IllegalStateException("Экспорт остановлен: ошибка построения кадра $i", e)
            }
        }
        return sceneAtFn
    }

    /** Спринт 32: что реально запишется в MP4 (кодек, размер, частота, пояснения) по кодекам этого устройства. */
    fun videoPlan(shortSide: Int, fps: Int, codec: com.vibetoon.domain.export.VideoCodec): com.vibetoon.domain.export.VideoPlan =
        com.vibetoon.domain.export.VideoExportPolicy.plan(canvasW, canvasH, shortSide, fps, codec, com.vibetoon.render.VideoCodecs)

    /** Есть ли на устройстве кодировщик HEVC (для подписи в окне экспорта). */
    fun hevcAvailable(): Boolean = com.vibetoon.render.VideoCodecs.available(com.vibetoon.domain.export.VideoCodec.HEVC)

    fun startExport(shortSide: Int, fpsRequested: Int, withAudio: Boolean = true, codecRequested: com.vibetoon.domain.export.VideoCodec = com.vibetoon.domain.export.VideoCodec.AVC) {
        val pid = projectId ?: return
        val view = RenderHost.view ?: return
        if (_export.value is ExportUi.Running) return
        val plan = videoPlan(shortSide, fpsRequested, codecRequested)
        if (!plan.possible) { _export.value = ExportUi.Failed(plan.notes.firstOrNull() ?: "Не удалось записать видео"); return }
        pause()
        val st = _animation.value.settings
        val fps = plan.fps
        val (w0, h0, frames) = exportInfo(plan.shortSide, fps)
        // размер из плана (он же videoSize), но на всякий случай - тот, что посчитан для числа кадров
        val w = if (plan.width > 0) plan.width else w0
        val h = if (plan.height > 0) plan.height else h0
        val planNote = plan.notes.joinToString("\n").ifEmpty { null }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp-${java.util.UUID.randomUUID().toString().take(8)}.mp4")

        val bg = meta?.background?.toInt() ?: 0
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            try {
                val range = exportRange()
                val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols(), range = range)
                // Пакет 12: снимок звука на момент запуска (дорожки, клипы, громкость, «Без звука»); отрезок - как у картинки
                val audioStart = range?.start ?: 0
                val audioLen = range?.length ?: st.durationFrames
                val audioStatus = if (withAudio) exportAudioStatus() else null
                val audioSrc = if (audioStatus == null) emptyList() else
                    prepareExportAudio(audioStatus).takeIf { com.vibetoon.domain.export.AudioMix.audibleIn(it, audioStart, audioLen) }.orEmpty()
                // звук был, но подготовить его не удалось - не молчим, а говорим об этом в итоге
                val audioProblem = if (audioStatus?.ready == true && audioSrc.isEmpty()) "Звук не удалось подготовить (файл не декодируется) - видео сохранено без звука" else null
                val compatible = getApplication<Application>().getSharedPreferences("vt_app", android.content.Context.MODE_PRIVATE).getBoolean("compatible_video_export", false)
                view.exportVideo(
                    ExportSpec(w, h, fps, frames, file, compatible, plan.codec, plan.rateHint, plan.bitrate), bg, sceneAt,
                    onProgress = { _export.value = ExportUi.Running(it, frames) }, cancel = cancel
                ) { result ->
                    viewModelScope.launch {
                        runCatching { restoreAfterExport() }
                        result.onSuccess {
                            try {
                                var note: String? = audioProblem
                                val codecNote = "Кодек: {}, {}×{}, {} fps".replaceFirst("{}", plan.codec.label).replaceFirst("{}", "$w").replaceFirst("{}", "$h").replaceFirst("{}", "$fps")
                                if (audioSrc.isNotEmpty()) {
                                    _export.value = ExportUi.Running(0, 100, "Звук")
                                    val rate = audioEngine.mixSampleRate()
                                    val total = com.vibetoon.domain.export.AudioMix.sampleCount(frames, fps, rate)
                                    val added = withContext(Dispatchers.Default) {
                                        com.vibetoon.data.Mp4Audio.addTrack(file, rate, total, { from, n ->
                                            com.vibetoon.domain.export.AudioMix.render(audioSrc, audioStart, st.fps, rate, from, n, total)
                                        }, cancel) { p -> _export.value = ExportUi.Running((p * 100).toInt(), 100, "Звук") }
                                    }
                                    if (cancel.get()) {
                                        com.vibetoon.domain.export.ExportFiles.discard(file)
                                        _export.value = ExportUi.Idle
                                        return@launch
                                    }
                                    note = if (added.isSuccess) "Со звуком: AAC, стерео 44,1 кГц" else "Звук добавить не удалось - видео сохранено без звука"
                                }
                                val gallerySaved = withContext(Dispatchers.IO) { saveToGallery(file, "video/mp4") }
                                _export.value = ExportUi.Done(file, gallerySaved, exportGpuNote(listOfNotNull(codecNote, planNote, note).joinToString("\n")))
                            } catch (t: kotlinx.coroutines.CancellationException) {
                                _export.value = ExportUi.Idle; throw t
                            } catch (t: Exception) {
                                _export.value = ExportUi.Failed(t.message ?: "Не удалось сохранить видео")
                            }
                        }.onFailure {
                            com.vibetoon.domain.export.ExportFiles.discard(file)
                            _export.value = if (cancel.get()) ExportUi.Idle else ExportUi.Failed(it.message ?: "Не удалось записать видео")
                        }
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                com.vibetoon.domain.export.ExportFiles.discard(file)
                _export.value = ExportUi.Idle
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { restoreAfterExport() } }
                throw t
            } catch (t: Exception) {
                com.vibetoon.domain.export.ExportFiles.discard(file)
                _export.value = ExportUi.Failed(t.message ?: "Не удалось записать видео")
                runCatching { restoreAfterExport() }
            }
        }
    }

    /** Пакет 12: есть ли в проекте звучащие дорожки (аудио или видео со звуком, не «Без звука») - для переключателя «Звук в видео». */
    fun projectHasSound(): Boolean = exportAudioStatus().hasTracks

    /** Звуковые дорожки проекта (аудио и видео-слои) -> файлы: тот же список, что получает аудио-движок. */
    private fun soundFiles(pid: String): Map<Int, java.io.File> =
        com.vibetoon.domain.export.ExportAudio.soundItems(media).associate { it.id to projects.fileOf(pid, it.file) }

    /**
     * Пакет 14: звук для экспорта - одно правило для окна экспорта, MP4 и WAV: дорожки берутся из проекта,
     * отрезок - как у картинки (весь проект или сцена).
     */
    fun exportAudioStatus(): com.vibetoon.domain.export.ExportAudio.Status {
        val pid = projectId ?: return com.vibetoon.domain.export.ExportAudio.status(emptyList(), 0, 0)
        val range = exportRange()
        val items = com.vibetoon.domain.export.ExportAudio.soundItems(media)
        val tracks = items.map { m ->
            val f = projects.fileOf(pid, m.file)
            com.vibetoon.domain.export.ExportAudio.Track(m.id, m.label, m.kind == MediaKind.VIDEO, m.muted, f.isFile && f.length() > 0,
                audioEngine.hasNoAudio(f), clipOf(m.id), m.volKeys)
        } + exportExtraTracks(items.mapTo(HashSet()) { it.id })
        return com.vibetoon.domain.export.ExportAudio.status(tracks, range?.start ?: 0, range?.length ?: duration())
    }

    /**
     * Пакет 14: у части MP3 (VBR без заголовка, нестандартные теги) метаданные не дают длительность, и дорожка получала
     * клип в 1 кадр - звук обрывался, а экспорт видел её «вне отрезка». После декодирования длительность берётся из PCM.
     */
    private fun fixAudioDurations() {
        val pid = projectId ?: return
        var changed = false
        for (i in media.indices) {
            val m = media[i]
            if (m.kind != MediaKind.AUDIO || m.durationMs > 0L || m.file.isBlank()) continue
            val d = audioEngine.decodedDurationMs(projects.fileOf(pid, m.file))
            if (d > 0L) { media[i] = m.copy(durationMs = d); changed = true }
        }
        if (!changed) return
        val fps = _animation.value.settings.fps
        _audio.value = media.filter { !it.isVisual }.map { AudioTrackInfo(it.id, it.name, ProjectMath.framesFor(it.durationMs, fps)) }
        markDirty()
    }

    /** Дорожки, которые звучат при Play, но которых нет в списке звуковых слоёв проекта: тоже идут в экспорт (что слышно - то и в файле). */
    private fun exportExtraTracks(known: Set<Int>): List<com.vibetoon.domain.export.ExportAudio.Track> =
        audioEngine.sourceFiles().filterKeys { it !in known }.map { (id, f) ->
            com.vibetoon.domain.export.ExportAudio.Track(id, f.name, false, id in audioEngine.muted, f.isFile && f.length() > 0, audioEngine.hasNoAudio(f), clipOf(id))
        }

    /**
     * Источники сведения для экспорта: движок сверяется с проектом, нужные файлы декодируются (ждём до 2 минут),
     * в сведение идут только дорожки, которые окно показало как звучащие. Пусто - звука нет.
     */
    private suspend fun prepareExportAudio(status: com.vibetoon.domain.export.ExportAudio.Status): List<com.vibetoon.domain.project.ScrubAudio.Source> {
        val pid = projectId ?: return emptyList()
        if (!status.ready) return emptyList()
        val all = audioEngine.sourceFiles() + soundFiles(pid)
        audioEngine.ensureSources(all)
        pushAudioMix()
        audioEngine.awaitDecoded(status.audibleIds.mapNotNull { all[it] })
        val src = audioEngine.exportSources(::clipOf, status.audibleIds)
        Log.i("VT/Export", "export audio: ${src.size}/${status.audibleCount} tracks ready")
        return src
    }

    fun cancelExport() {
        exportCancel.set(true)
        RenderHost.view?.kickExport()   // экспорт MP4 мог быть запаркован на паузе: задача отмены освободит кодек
    }

    /**
     * Пакет 27: кадр GIF/PNG-экспорта. Пока приложение свёрнуто, снимок не делается (поверхность GL снята) - ждём
     * возврата и снимаем тот же кадр; null при активном приложении - настоящая ошибка снимка.
     */
    private suspend fun captureExportFrame(scene: SceneData?, transparent: Boolean, w: Int, h: Int, cancel: java.util.concurrent.atomic.AtomicBoolean): ByteArray? {
        while (true) {
            while (!glUsable()) {
                if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                kotlinx.coroutines.delay(200)
            }
            if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
            val bytes = captureNow(scene, transparent, w, h)
            if (bytes != null || !appBackground) return bytes
            kotlinx.coroutines.delay(200)   // в фоне снимок не вышел (переход состояния): повтор без занятого цикла
        }
    }
    fun dismissExport() { if (_export.value !is ExportUi.Running) _export.value = ExportUi.Idle }

    private fun safeName(n: String) = n.replace(Regex("""[^\p{L}\p{N}_-]+"""), "_").trim('_').ifEmpty { "video" }.take(40)

    override fun onCleared() {
        runCatching { appPrefs().unregisterOnSharedPreferenceChangeListener(appPrefsListener) }
        pause()
        audioEngine.release()
        videoThumbs.release()
        videoFrames.shutdown()
        cellCache.shutdown()
        executor.shutdown()
    }
}
