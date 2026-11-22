package com.vibetoon.ui

import androidx.compose.ui.graphics.asImageBitmap
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import com.vibetoon.domain.paint.PaintBudget
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
import com.vibetoon.domain.project.SymbolMath
import com.vibetoon.domain.project.TimeKey
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
import com.vibetoon.domain.render.PivotMath
import com.vibetoon.domain.render.SceneBuilder
import com.vibetoon.domain.render.SceneData
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
import kotlinx.coroutines.sync.withLock
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
class WarpOverlay(val points: List<Float> = emptyList())

/** Mesh на экране: вершины (x, y в пикселях вида), рёбра (пары индексов вершин) и выбранная вершина. */
/** Пины Puppet Mesh на экране: центры (x, y), радиусы влияния (px экрана), жёсткость, выбранный. */
class PinOverlay(val points: FloatArray = FloatArray(0), val radius: FloatArray = FloatArray(0), val stiff: BooleanArray = BooleanArray(0), val selected: Int = -1)
data class PinInfo(val count: Int = 0, val selected: Int = -1, val stiff: Boolean = false, val radius: Float = 180f)

class MeshOverlay(val points: FloatArray = FloatArray(0), val edges: IntArray = IntArray(0), val active: Int = -1)

enum class MeshTool { ADD, MOVE, CUT, SMUDGE, DELETE, PIN_ADD, PIN_MOVE }

class BoneOverlay(val bones: List<BoneShape> = emptyList(), val draft: FloatArray? = null, val lasso: List<Float> = emptyList())

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
    val count: Int = 1
)

/** Цветокоррекция слоя (пока только значения в UI; шейдер позже). Диапазон каждого параметра -100..100. */
data class ColorAdjust(val exposure: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val hue: Float = 0f) {
    val isIdentity get() = exposure == 0f && contrast == 0f && saturation == 0f && hue == 0f
}

/** Начало id призраков кальки. */
const val GHOST_BASE = 900_000
/** Память под кэш картинок символов (полный размер холста): при смене маркера заливка идёт из памяти. */
const val SYMBOL_CACHE_BYTES = 96L * 1024 * 1024
/** Тип эффекта Тонировка для кальки: kFragFx, режим 20. */
private const val TINT_FX = 20f

/** Свойства выделенного текстового слоя для вкладки "Текст". */
data class TextState(
    val id: Int, val text: String, val color: Long, val font: String, val fontTitle: String, val size: Float,
    val gradient: List<Long> = emptyList(), val gradientAngle: Float = 0f,
    val fx: com.vibetoon.domain.project.TextFx = com.vibetoon.domain.project.TextFx(),
    val stroke: Boolean = false, val strokeWidth: Float = 4f, val strokeColor: Long = 0xFF000000L,
    val align: Int = 0, val lineSpacing: Float = 1f, val letterSpacing: Float = 0f
)

/** Дорожка аудио на таймлайне: имя и длина в кадрах проекта. Звук пока не декодируется. */
data class AudioTrackInfo(val id: Int, val name: String, val lengthFrames: Int)

/** Состояние экспорта в MP4. */
sealed interface ExportUi {
    data object Idle : ExportUi
    data class Running(val done: Int, val total: Int) : ExportUi
    data class Done(val file: File) : ExportUi
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
/** Консервативный предел стороны текстуры (ES 3.0 гарантирует >= 2048). */
private const val MAX_TEX = 2048
const val MAX_GRADIENT_STOPS = 5

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PsdRepository(app)
    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Декод слоёв строго по одному (пик памяти = один слой).
    private val executor = Executors.newSingleThreadExecutor()
    private val decodeDispatcher = executor.asCoroutineDispatcher()

    // ---- Проект: размер холста, fps и фон берутся из него; PSD вписывается в холст ----
    private val projects = ProjectRepository(app)
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
    private val _waveforms = MutableStateFlow<Map<Int, FloatArray>>(emptyMap())
    /** Пики громкости аудио-слоёв (100 значений в секунду) для волновой формы на таймлайне. */
    val waveforms: StateFlow<Map<Int, FloatArray>> = _waveforms.asStateFlow()
    private var lastScrubAt = 0L
    private var lastScrubFrame = -1

    private fun refreshWaveforms() {
        val pid = projectId
        _waveforms.value = if (pid == null) emptyMap() else media.filter { !it.isVisual }.mapNotNull { m -> audioEngine.peaksFor(projects.fileOf(pid, m.file))?.let { m.id to it } }.toMap()
    }

    /** Клипы слоёв на таймлайне: границы видимости и ретайминг. Нет записи - слой виден весь проект. */
    private val _clips = MutableStateFlow<Map<Int, Clip>>(emptyMap())
    val clips: StateFlow<Map<Int, Clip>> = _clips.asStateFlow()

    private val _markers = MutableStateFlow<List<Int>>(emptyList())
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
        val paintOp: PaintOp? = null
    ) {
        fun withPaint(op: PaintOp?) = Snapshot(base, animation, media, deleted, clips, links, bones, regroup, warps, meshes, persps, skins, turbs, fx, pins, lprops, op)
    }
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
    /** Свойства слоёв (Opacity, Blend, Alpha Lock, Clip) поверх данных PSD; сохраняются в project.json. */
    private val _layerProps = MutableStateFlow<Map<Int, LayerProps>>(emptyMap())
    val layerPropsState: StateFlow<Map<Int, LayerProps>> = _layerProps.asStateFlow()
    private val layerProps: Map<Int, LayerProps> get() = _layerProps.value
    private var layers: List<LayerInfo> = emptyList()
    val hasLayers: Boolean get() = layers.isNotEmpty() || media.isNotEmpty()
    /** Картинки, видео и аудио проекта; id начинаются с MEDIA_ID_BASE. */
    private val media = ArrayList<MediaItem>()
    private val importer = MediaImporter(app)
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
        if (_state.value == UiState.Loading || _state.value is UiState.Rendering) return
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
    fun openProject(id: String) {
        if (projectId == id) return
        closing = false
        opening = true
        viewModelScope.launch {
          try {
            resetEditor()
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
            base.putAll(data.base)
            deletedPsd = data.deleted
            _clips.value = data.clips
            _markers.value = data.markers
            _links.value = data.links
            _bones.value = data.bones
            regroup = data.regroup
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
            RenderNative.clearPendingUploads()   // текстуры прежнего проекта: id слоёв пересекаются
            RenderHost.view?.clearTextures()
            awaitView()
            glView = RenderHost.view
            RenderHost.view?.setBackground(data.meta.background.toInt())
            val empty = SceneBuilder.buildTree(emptyList(), canvasW, canvasH)
            scene = empty
            RenderHost.view?.setScene(empty)
            restoreMediaTextures(id)
            pushScene()
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
          } finally {
            opening = false
          }
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

    /** GL-вид создаётся Compose-ом чуть позже открытия проекта: ждём его (до ~5 с). */
    private suspend fun awaitView() {
        repeat(100) {
            if (RenderHost.view != null) return
            delay(50)
        }
    }

    private fun resetEditor() {
        pause()
        arapMemo.clear()
        clearSelection(); penCancel(); _rasterTool.value = null
        _loop.value = true; _magnet.value = false; vecLayer = -1; vecPts.clear(); vecGen.clear()
        thumbGen++; thumbs.clear(); thumbPending.clear()   // миниатюры прошлого проекта не должны всплывать у новых слоёв
        resetSymbolImages(); _markerThumbs.value = emptyMap()   // id символов разных проектов совпадают
        stroker = null; _paintCursor.value = null; _paintTool.value = null
        paintSizes.clear(); paintDirty.clear()
        saveJob?.cancel()
        camera = Camera()
        RenderHost.view?.setCamera(camera)
        rawRotationDeg = 0f; _viewRotation.value = 0f; _viewFlip.value = false to false
        vecErase = null; vecSmudgeWork = null; vecSmudgeLast = null; pivotLayer = -1; _pivotOverlay.value = null
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
        _links.value = emptyMap()
        _bones.value = emptyList()
        regroup = emptyList()
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
        _perspTool.value = false
        _perspOverlay.value = FloatArray(0)
        _meshOverlay.value = MeshOverlay()
        _warpTool.value = false
        _keySel.value = emptySet()
        _boneMode.value = false
        _boneOverlay.value = BoneOverlay()
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

    private suspend fun saveNow() {
        val m = meta ?: return
        projectId ?: return
        flushPaintSaves()
        val data = ProjectData(m.copy(updatedAt = System.currentTimeMillis()), psdRef, HashMap(base), _animation.value.tracks, media.toList(), deletedPsd, _clips.value, _markers.value, _links.value, _bones.value, regroup, warpLayers, meshLayers, perspLayers, skinLayers, turbLayers, fxLayers, pinLayers, layerProps)
        meta = data.meta
        withContext(Dispatchers.IO) { projects.save(data) }
        Log.i("VT/Project", "saved ${data.meta.id}: ${data.base.size} base, ${data.tracks.size} tracks")
    }

    /** Сохранение при сворачивании приложения. */
    fun flushSave() {
        viewModelScope.launch {
            saveJob?.cancel()
            saveNow()
        }
    }

    // ---------------------------------------------------------------- жизненный цикл GL (Sprint 6.9.1)

    /**
     * Приложение уходит в фон (ON_PAUSE). Сначала в очередь GL ставятся чтения ещё не сохранённых рисунков
     * (очередь GLSurfaceView выполняется до обработки паузы, контекст ещё жив), затем вид ставится на паузу:
     * тяжёлые GL-задачи больше не принимаются, идущий экспорт останавливается. Раньше вид не получал onPause вовсе,
     * и снимки символов/экспорт продолжали занимать GL-поток, пока система уничтожала поверхность - отсюда ANR.
     */
    fun onHostPause() {
        val view = RenderHost.view ?: return
        val pid = projectId
        if (pid != null) {
            val ids = synchronized(paintDirty) { val c = paintDirty.toList(); paintDirty.clear(); c }
            for (id in ids) {
                val file = paintFile(pid, id) ?: continue
                view.layerPixels(id) { bytes ->
                    if (bytes == null || bytes.size <= 8) paintDirty += id
                    else viewModelScope.launch(Dispatchers.IO) { writeLayerPng(bytes, file) }
                }
            }
        }
        view.onPause()
    }

    /** Возврат из фона (ON_RESUME): вид снова рисует, пропущенные на паузе символы перерисовываются. */
    fun onHostResume() {
        val view = RenderHost.view ?: return
        view.onResume()
        symRetryAt.clear()
        if (projectId != null && _editingSymbol.value == null) updateSymbols(_frame.value)
    }

    private var glRestoreJob: Job? = null
    /** Вид, в контекст которого залиты текстуры проекта. */
    private var glView: Any? = null
    @Volatile private var opening = false

    /**
     * Создан новый GL-контекст (вызов из GL-потока). Если это не первый контекст вида - старый потерян вместе со
     * всеми текстурами: картинки, кадры, тексты, символы и слои PSD заливаются заново из файлов проекта и памяти.
     * Раньше здесь был TODO, и после возврата из фона холст оставался наполовину чёрным.
     */
    fun onGlContextReady(recreated: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            val v = RenderHost.view
            // новый вид (Activity пересоздана) с первым контекстом тоже пуст: openProject того же id ничего не делает
            if (!recreated && (v == null || v === glView)) {
                // первый контекст того же вида: текстуры уже в очереди, но символы могли быть пропущены (поверхности ещё не было)
                if (projectId != null && _editingSymbol.value == null) updateSymbols(_frame.value)
                return@launch
            }
            if (opening) return@launch   // проект ещё открывается: openProject сам зальёт текстуры
            glView = v
            val pid = projectId ?: return@launch
            glRestoreJob?.cancel()
            glRestoreJob = viewModelScope.launch {
                Log.i("VT/GL", "context recreated: reloading textures of $pid")
                textFxKeys.clear(); cellShown.clear()
                symShown.clear(); symTries.clear(); symRetryAt.clear()   // картинки символов в памяти остаются годными
                RenderHost.view?.setBackground((meta?.background ?: 0xFFFFFFFFL).toInt())
                scene?.let { RenderHost.view?.setScene(it) }
                restoreMediaTextures(pid)   // медиа, кадры, затем updateCells -> updateSymbols
                updateTextFx(_frame.value)
                val ref = psdRef
                if (ref != null) {
                    if (_state.value == UiState.Loading || _state.value is UiState.Rendering) {
                        // PSD как раз загружается: его текстуры уже идут в новый контекст через общую очередь
                    } else loadPsd(keepState = true) { repo.parseFile(projects.assetFile(pid), ref.name) }
                }
                pushScene()
                RenderHost.view?.requestRender()
            }
        }
    }

    /** Выход из проекта: сохранение и миниатюра (вид без рамки и в исходном масштабе), затем [done]. */
    fun closeProject(done: () -> Unit) {
        if (closing) return
        closing = true
        viewModelScope.launch {
            pause()
            saveJob?.cancel()
            val id = projectId
            if (id != null) {
                resetCamera()
                selectLayer(null)
                delay(250)   // дать GL нарисовать кадр без рамки выделения
                ThumbnailCapture.capture(RenderHost.view, canvasW, canvasH, projects.thumbnailFile(id))
                saveNow()
            }
            resetEditor()
            done()
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
            override.clear()
            if (!keepState) {
                // Новый PSD: id слоёв другие, прежние правки PSD теряют смысл. Правки медиа-слоёв остаются.
                deletedPsd = emptySet()
                _clips.value = _clips.value.filterKeys { it >= MEDIA_ID_BASE }
                clearHistory()
                base.keys.removeAll { it < MEDIA_ID_BASE }
                val anim = _animation.value
                _animation.value = anim.copy(tracks = anim.tracks.filterKeys { it >= MEDIA_ID_BASE })
                _frame.value = 0
            }
            _psdName.value = summary.name
            collapsed = LayerTree.defaultCollapsed(summary.layers)
            rebuildLayers()
            selectLayer(null)
            val scene = SceneBuilder.buildTree(roots, canvasW, canvasH, transformsNow(), fit, textIds)
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
                    runCatching { importer.decodeImage(f) }.onSuccess { d ->
                        RenderNative.uploadBitmap(id, d.bitmap)
                        d.bitmap.recycle()
                    }
                }
            }
            Log.i("VT/Psd", "textures queued: $loaded of ${psdTextureIds.size}, ${bytes / 1048576f} MB RGBA")
            // символы, снятые пока слои PSD ещё грузились, могли получиться без них: картинки снимаются заново
            withContext(Dispatchers.Main) { resetSymbolImages(); updateSymbols(_frame.value) }
            _state.value = UiState.Success(summary.layers.size, summary.millis, loaded, bytes / 1048576f)
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
        layers = (psdVisible.filter { it.id !in movedMap } + moved + mediaInfos).map { l ->
            val p = layerProps[l.id] ?: return@map l
            l.copy(name = p.name ?: l.name, opacity = p.opacity, blendMode = p.blend, isClipped = p.clip && l.kind == LayerKind.LAYER)
        }
        textIds = media.filter { it.isText }.map { it.id }.toSet()
        roots = SceneTree.build(layers)
        pivots = computePivots(roots)
        rebuildRows()
        val fps = _animation.value.settings.fps
        _audio.value = media.filter { !it.isVisual }.map {
            AudioTrackInfo(it.id, it.name, ProjectMath.framesFor(it.durationMs, fps))
        }
        val pid = projectId
        audioEngine.setSources(
            if (pid == null) emptyMap() else media.filter { !it.isVisual }.associate { it.id to projects.fileOf(pid, it.file) }
        )
        audioEngine.volumes = media.filter { !it.isVisual && it.volKeys.isNotEmpty() }.associate { it.id to it.volKeys }
        audioEngine.onDecoded = { viewModelScope.launch { refreshWaveforms() } }
        refreshWaveforms()
    }

    /**
     * Кнопка "+" -> Изображение / Видео / Аудио. Файл копируется в папку проекта; картинка и первый
     * кадр видео декодируются (с уменьшением до 2048 px) и ставятся в центр холста как слой;
     * аудио становится невизуальной дорожкой на таймлайне.
     */
    fun importMedia(kind: MediaKind, uri: Uri) {
        val pid = projectId ?: return
        if (_state.value == UiState.Loading || _state.value is UiState.Rendering) return
        val id = maxOf(MEDIA_ID_BASE - 1, media.maxOfOrNull { it.id } ?: 0) + 1
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) { importMediaBlocking(pid, id, kind, uri) }
                checkpoint("add", force = true)   // один снимок откатывает и слой, и продление проекта
                // длинный звук: проект продлевается до его конца (не дольше предела хронометража)
                var note = ""
                if (item.kind == MediaKind.AUDIO) {
                    val st = _animation.value.settings
                    val need = ProjectMath.framesFor(item.durationMs, st.fps)
                    if (need > st.durationFrames) {
                        val d = need.coerceAtMost(TimeFormat.MAX_DURATION_FRAMES)
                        meta = meta?.copy(durationFrames = d)
                        _animation.value = _animation.value.copy(settings = ProjectSettings(st.fps, d))
                        note = if (need > d) ". Проект продлён до максимума, конец звука не поместился" else ". Проект продлён до конца звука"
                    }
                }
                media += item
                rebuildLayers()
                pushScene()
                if (item.isVisual) selectLayer(item.id)
                markDirty()
                _state.value = UiState.Message("Добавлено: ${item.name}$note")
                // Import Images as Symbols: картинка сразу оборачивается в символ (экземпляры - в «Библиотеке ассетов»)
                if (prefs.importAsSymbols && item.kind == MediaKind.IMAGE && _editingSymbol.value == null) createSymbol(item.id)
            } catch (t: Throwable) {
                Log.e("VT/Media", "import failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось добавить файл")
            }
        }
    }

    private fun importMediaBlocking(pid: String, id: Int, kind: MediaKind, uri: Uri): MediaItem {
        val name = importer.displayName(uri)
        val ext = importer.extension(
            name,
            when (kind) { MediaKind.IMAGE -> "png"; MediaKind.VIDEO -> "mp4"; MediaKind.AUDIO -> "mp3"; MediaKind.TEXT -> error("Текст добавляется через addText"); MediaKind.CAMERA -> error("Камера добавляется через addCameraLayer"); MediaKind.NULLOBJ -> error("Transform Layer создаётся через addNullLayer"); MediaKind.GROUP -> error("Группа создаётся через groupSelected"); MediaKind.SYMBOL -> error("Символ создаётся через createSymbol") }
        )
        val fileName = "m$id.$ext"
        val dest = projects.mediaFile(pid, fileName)
        importer.copy(uri, dest)
        try {
            val rel = "assets/$fileName"
            if (kind == MediaKind.AUDIO) {
                return MediaItem(id, kind, name, rel, durationMs = importer.audioDurationMs(dest))
            }
            val decoded = if (kind == MediaKind.IMAGE) importer.decodeImage(dest) else importer.decodeVideoFrame(dest)
            val r = ProjectMath.fitInside(decoded.srcWidth, decoded.srcHeight, canvasW, canvasH)
            val ok = RenderNative.uploadBitmap(id, decoded.bitmap)
            decoded.bitmap.recycle()
            if (!ok) error("Не удалось передать изображение в графику")
            return MediaItem(id, kind, name, rel, decoded.srcWidth, decoded.srcHeight, r[0], r[1], r[2], r[3], decoded.durationMs)
        } catch (t: Throwable) {
            dest.delete()   // не оставляем в проекте файл, который не удалось обработать
            throw t
        }
    }

    // ---------------------------------------------------------------- текст и шрифты

    private fun nextMediaId() = maxOf(MEDIA_ID_BASE - 1, media.maxOfOrNull { it.id } ?: 0) + 1

    /** Рисует текст в Bitmap и ставит его в очередь загрузки текстуры; возвращает размер блока на холсте. */
    private fun renderAndUpload(m: MediaItem, frame: Int = _frame.value): Pair<Float, Float> {
        invalidateThumb(m.id)
        val fps = _animation.value.settings.fps.coerceAtLeast(1)
        val tSec = frame.toFloat() / fps
        textFxKeys[m.id] = m.fx.stateKey(tSec, fps)
        val r = textRenderer.renderFx(m.text, m.textColor.toInt(), m.font, m.textSize, m.gradient, m.gradientAngle, m.fx, tSec, fps, if (m.stroke) m.strokeWidth else 0f, m.strokeColor.toInt(), m.textAlign, m.lineSpacing, m.letterSpacing)
        val ok = RenderNative.uploadBitmap(m.id, r.bitmap)
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
        media += MediaItem(id, MediaKind.CAMERA, "Камера", "", 0, 0, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
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

    /** Глубина слоя для параллакса камеры. */
    fun setDepth(id: Int, depth: Float) = updateLayerProps(id) { it.copy(depth = depth) }

    fun addText(text: String) {
        if (projectId == null) return
        val id = nextMediaId()
        val proto = MediaItem(
            id, MediaKind.TEXT, "Текст", "",
            text = text.ifBlank { "Текст" },
            textSize = (canvasH * 0.1f).coerceIn(TextRenderer.MIN_SIZE, TextRenderer.MAX_SIZE)
        )
        viewModelScope.launch {
            try {
                val (w, h) = withContext(decodeDispatcher) { renderAndUpload(proto) }
                val item = proto.copy(x = (canvasW - w) / 2f, y = (canvasH - h) / 2f, w = w, h = h)
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
        _textState.value = m?.let { TextState(it.id, it.text, it.textColor, it.font, fonts.title(it.font), it.textSize, it.gradient, it.gradientAngle, it.fx, it.stroke, it.strokeWidth, it.strokeColor, it.textAlign, it.lineSpacing, it.letterSpacing) }
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
        val skip = if (_editingSymbol.value == null) allSymbolMembers() else emptySet()
        for (m in media) {
            if (!m.isText || !m.fx.animated || m.id in skip) continue
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
    private fun uploadTextureFor(pid: String, m: MediaItem) {
        if (m.kind == MediaKind.CAMERA || m.kind == MediaKind.GROUP || m.kind == MediaKind.SYMBOL || m.kind == MediaKind.NULLOBJ) return   // у камеры и группы нет картинки
        if (m.kind == MediaKind.IMAGE && m.cells.isNotEmpty()) cellShown[m.id] = m.file
        if (m.isText) {
            renderAndUpload(m)
        } else {
            val file = projects.fileOf(pid, m.file)
            val d = if (m.kind == MediaKind.IMAGE) importer.decodeImage(file) else importer.decodeVideoFrame(file)
            RenderNative.uploadBitmap(m.id, d.bitmap)
            d.bitmap.recycle()
        }
    }

    /** При открытии проекта текстуры картинок и кадров видео создаются заново из файлов проекта. */
    private suspend fun restoreMediaTextures(pid: String) = withContext(decodeDispatcher) {
        for (m in media.toList()) {
            if (!m.isVisual) continue
            runCatching { uploadTextureFor(pid, m) }.onFailure { Log.w("VT/Media", "texture for ${m.name} not restored: ${it.message}") }
        }
        withContext(Dispatchers.Main) { updateCells(_frame.value); refreshOnion() }
        RenderHost.view?.requestRender()
    }

    // ---------------------------------------------------------------- камера

    private fun rebuildRows() {
        val ctx = _editingSymbol.value
        val defs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.map { it.id }
        val shown = when {
            ctx != null -> { val sub = symbolMembers(ctx) + ctx; layers.filter { it.id in sub }.map { if (it.id == ctx) it.copy(parentId = -1) else it } }   // внутри символа - только его слои
            defs.isEmpty() -> layers
            else -> { val hide = HashSet<Int>(); for (d in defs) { hide += symbolMembers(d); hide += d }; layers.filter { it.id !in hide } }   // содержимое символов в главной сцене не показывается
        }
        _rows.value = LayerTree.flatten(shown, collapsed)
    }

    /** Тап по группе в дереве слоёв: свернуть или развернуть. */
    fun toggleGroup(id: Int) {
        collapsed = if (id in collapsed) collapsed - id else collapsed + id
        rebuildRows()
    }

    fun toggleUniformScale() {
        _uniformScale.value = !_uniformScale.value
    }

    fun onViewSize(w: Float, h: Float) { viewW = w; viewH = h; refreshBoneOverlay() }

    // ---- вид холста: zoom, pan, поворот (Canvas Rotation), отражение (Flip Canvas). На экспорт не влияет.
    private val _viewRotation = MutableStateFlow(0f)
    /** Поворот вида в градусах (-180..180], для подписи «37°» и кнопки сброса. */
    val viewRotation: StateFlow<Float> = _viewRotation.asStateFlow()
    private val _viewFlip = MutableStateFlow(false to false)
    /** Отражение вида: (по горизонтали, по вертикали). */
    val viewFlip: StateFlow<Pair<Boolean, Boolean>> = _viewFlip.asStateFlow()
    /** Угол без притяжения к 0/90/180/270: копится жестом, на экран идёт притянутый. */
    private var rawRotationDeg = 0f

    private fun applyCamera(c: Camera) {
        camera = c
        RenderHost.view?.setCamera(camera)
        _viewRotation.value = camera.rotationDeg
        _viewFlip.value = camera.flipX to camera.flipY
        refreshBoneOverlay()
        updateSelectionFrame()   // экранные ручки Warp/Mesh/Perspective/выделения/опорной точки
    }

    /** Два пальца: zoom и поворот вида [drDeg] вокруг центра жеста + сдвиг. */
    fun onTransform(gx: Float, gy: Float, dz: Float, dx: Float, dy: Float, drDeg: Float = 0f) {
        var dr = 0f
        if (drDeg != 0f && drDeg.isFinite()) {
            // поворот копится «сырым», а на экран идёт с притяжением к кратным 90° (легко вернуть ровно 0°)
            val before = Camera.snapDeg(rawRotationDeg)
            rawRotationDeg += drDeg
            dr = Camera.snapDeg(rawRotationDeg) - before
        }
        applyCamera(camera.transformed(gx, gy, viewW, viewH, canvasW, canvasH, dz, dx, dy, dr))
    }

    /** Сброс поворота вида на 0° (масштаб и положение остаются): кнопка «0°» и двойной тап по индикатору. */
    fun resetViewRotation() {
        rawRotationDeg = 0f
        applyCamera(camera.withRotation(0f, viewW, viewH, canvasW, canvasH))
        Log.i("VT/Edit", "view rotation reset")
    }

    /** Поворот вида на ±90° кнопками. */
    fun rotateViewBy(deg: Float) {
        rawRotationDeg = Camera.snapDeg(rawRotationDeg) + deg
        applyCamera(camera.withRotation(Camera.degToRad(rawRotationDeg), viewW, viewH, canvasW, canvasH))
    }

    /** Flip Canvas: отражение только вида (рисунок и экспорт не меняются). */
    fun toggleViewFlipX() = applyCamera(camera.withFlip(!camera.flipX, camera.flipY, viewW, viewH, canvasW, canvasH))
    fun toggleViewFlipY() = applyCamera(camera.withFlip(camera.flipX, !camera.flipY, viewW, viewH, canvasW, canvasH))

    fun resetCamera() {
        rawRotationDeg = 0f
        applyCamera(Camera())
        Log.i("VT/Edit", "camera reset")
    }

    // ---------------------------------------------------------------- выделение

    fun hasSelection(): Boolean = _selected.value != null

    /**
     * Тап: hit-test в C++ (GL-поток) возвращает верхний слой под пальцем. Как в Live2D, сначала
     * выделяется вся группа верхнего уровня; повторные тапы спускаются на уровень глубже
     * (вложенная группа, затем сам слой). Тап в пустоту снимает выделение.
     */
    fun onTap(x: Float, y: Float) {
        if (swallowTap) { swallowTap = false; return }
        if (_eyedropper.value) { sampleAt(x, y); return }
        if (_boneMode.value) { boneTap(x, y); return }
        RenderHost.view?.hitTest(x, y) { hit ->
            val id = if (hit >= GHOST_BASE || (hit >= 0 && (isLockedId(hit) || layerProps[hit]?.visible == false))) -1 else hit   // закрытый и скрытый слои на холсте не выбираются
            val tapNow = SystemClock.uptimeMillis()
            val dbl = id >= 0 && id == lastTapId && tapNow - lastTapAt < 380
            lastTapId = id; lastTapAt = tapNow
            if (dbl && !multiMode && isSymbolInstance(id)) { enterSymbol(id); return@hitTest }
            if (multiMode) {
                if (id < 0) selectLayer(null) else toggleSelect(id)   // тап в пустоту выходит из мультивыделения
            } else selectLayer(resolveSelection(id, _selected.value))
            Log.i("VT/Edit", "tap ($x,$y) -> layer $id, selected ${_selected.value}")
        }
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

    private fun resolveSelection(hitLeaf: Int, current: Int?): Int? {
        if (hitLeaf < 0) return null
        val chain = chainTo(hitLeaf)
        if (chain.isEmpty()) return null
        val i = if (current == null) -1 else chain.indexOf(current)
        return if (i < 0) chain.first() else chain[minOf(i + 1, chain.lastIndex)]
    }

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
        _selection.value = set
        _selected.value = primary
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
        refreshPivotOverlay()
        refreshWarpOverlay()
        refreshMeshOverlay()
        refreshPerspOverlay()
        refreshRasterOverlays()
        val quads = (_selection.value + _lassoLayers.value).mapNotNull { id -> scene?.selectionQuads?.get(id)?.let { q -> contentQuad(id, q) ?: q } }
        RenderHost.view?.setSelection(if (quads.isEmpty()) null else quads.fold(FloatArray(0)) { a, b -> a + b })
        refreshInspector()
    }

    /** Центры рамок узлов (опорная точка без пользовательского сдвига). */
    private fun computePivots(nodes: List<com.vibetoon.domain.model.Node>): Map<Int, Pair<Float, Float>> {
        val out = HashMap<Int, Pair<Float, Float>>()
        fun visit(n: com.vibetoon.domain.model.Node) {
            when (n) {
                is LeafNode -> out[n.id] = (n.x + n.w / 2f) to (n.y + n.h / 2f)
                is GroupNode -> { out[n.id] = n.pivotX to n.pivotY; n.children.forEach(::visit) }
            }
        }
        nodes.forEach(::visit)
        return out
    }

    /** Пользовательские сдвиги опорных точек (Layer Pivot Editing) для SceneBuilder. */
    private fun pivotOffsetsNow(): Map<Int, Pair<Float, Float>> =
        layerProps.filterValues { it.hasPivot }.mapValues { it.value.pivotX to it.value.pivotY }

    /** Действующая опорная точка узла (координаты родителя): центр рамки + сдвиг. */
    private fun pivotOf(id: Int): Pair<Float, Float> {
        val c = pivots[id] ?: (0f to 0f)
        val p = layerProps[id] ?: return c
        return (c.first + p.pivotX) to (c.second + p.pivotY)
    }

    private fun refreshInspector() {
        val id = _selected.value
        refreshText()
        if (id == null) { _inspector.value = null; return }
        val info = layers.firstOrNull { it.id == id } ?: run { _inspector.value = null; return }
        val t = effective(id)
        val (px, py) = pivotOf(id)
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
            hasKey = _animation.value.hasKey(id, _frame.value),
            easing = _animation.value.easingAt(id, _frame.value),
            count = transformTargets().size.coerceAtLeast(1)
        )
    }

    /** Ввод числа в инспекторе: сразу двигает слой на холсте. Цепочка делает масштаб пропорциональным. */
    fun setProperty(prop: Prop, value: Float) {
        val prim = _selected.value ?: return
        if (_playing.value || !value.isFinite()) return
        val ids = transformTargets().ifEmpty { return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        checkpoint("prop$prop")
        val lo = LayerTransform.MIN_SCALE
        val hi = LayerTransform.MAX_SCALE
        val tp = effective(prim)
        val (px, py) = pivotOf(prim)
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

    private val _curveEditor = MutableStateFlow(false)
    val curveEditor: StateFlow<Boolean> = _curveEditor.asStateFlow()

    /** Узлы анимации, чей ключ на текущем кадре правит редактор: выделенные слои/кости и точки деформеров слоя. */
    private fun curveTargets(): List<Int> {
        val out = LinkedHashSet<Int>()
        val f = _frame.value
        val tracks = _animation.value.tracks
        for (id in _selection.value) if (tracks[id]?.keyAt(f) != null) out += id
        warpTarget()?.let { t ->
            for ((id, tr) in tracks) {
                val mine = (WarpMath.isPointId(id) && (id - WarpMath.ID_BASE) / WarpMath.POINTS == t) ||
                    (PuppetMath.isPinId(id) && PuppetMath.layerOf(id) == t) ||
                    (MeshMath.isVertexId(id) && id < PuppetMath.ID_BASE && (id - MeshMath.ID_BASE) / MeshMath.MAX_VERTS == t) ||
                    (PerspectiveMath.isPointId(id) && id >= PerspectiveMath.ID_BASE && id < TurbMath.ID_BASE && (id - PerspectiveMath.ID_BASE) / PerspectiveMath.CORNERS == t) ||
                    (id >= TurbMath.ID_BASE && id < PuppetMath.ID_BASE && (id - TurbMath.ID_BASE) / TurbMath.PARAMS == t)
                if (mine && tr.keyAt(f) != null) out += id
            }
        }
        return out.toList()
    }

    fun openCurveEditor(on: Boolean) {
        if (on && curveTargets().isEmpty()) { _state.value = UiState.Message("На этом кадре нет ключа у выделенного объекта"); return }
        _curveEditor.value = on
    }

    /** Текущая кривая первого ключа или кривая его пресета плавности. */
    fun currentCurve(): com.vibetoon.domain.anim.Curve {
        val id = curveTargets().firstOrNull() ?: return com.vibetoon.domain.anim.Curve.LINEAR
        val k = _animation.value.tracks[id]?.keyAt(_frame.value) ?: return com.vibetoon.domain.anim.Curve.LINEAR
        return k.curve?.let { com.vibetoon.domain.anim.Curve.parse(it) } ?: com.vibetoon.domain.anim.Curve.of(k.easing)
    }

    /** Применяет кривую ко всем ключам редактора на текущем кадре. [commit] - точка отмены (начало жеста). */
    fun setCurve(curve: com.vibetoon.domain.anim.Curve, commit: Boolean) {
        if (_playing.value) return
        if (commit) checkpoint("curve", force = true) else checkpoint("curve")
        var a = _animation.value
        val data = curve.toList()
        for (id in curveTargets()) a = a.withCurve(id, _frame.value, data)
        _animation.value = a
        pushScene()
        markDirty()
    }

    fun setEasing(type: EasingType) {
        if (_playing.value) return
        checkpoint("easing", force = true)
        for (id in transformTargets()) {
            if (_animation.value.hasKey(id, _frame.value)) _animation.value = _animation.value.withEasing(id, _frame.value, type)
        }
        refreshInspector()
        markDirty()
        Log.i("VT/Anim", "easing $type at frame ${_frame.value}")
    }

    /** Эффективная трансформация слоя на текущем кадре: правка кадра > трек > ручная > единичная. */
    private fun effective(id: Int): LayerTransform =
        override[id] ?: _clips.value[id]?.let { c -> _animation.value.evaluate(id, c.animFrame(_frame.value), base[id]) }
            ?: _animation.value.evaluate(id, _frame.value, base[id])

    /** Трансформации всех изменённых слоёв на текущем кадре - вход для SceneBuilder. */
    private fun transformsNow(): Map<Int, LayerTransform> {
        val ids = HashSet<Int>().apply {
            addAll(base.keys); addAll(override.keys); addAll(_animation.value.tracks.keys); addAll(_clips.value.keys); addAll(fxLayers.keys); addAll(hiddenIds()); addAll(ghosts.map { it.id })
        }
        // вне клипа слой невидим: нулевая прозрачность (у группы скрывает и всех потомков)
        val rest = _boneMode.value && _bindPose.value   // Bind Pose: кости не деформируют картинку
        return ids.filter { !(rest && it >= BONE_ID_BASE) }.associateWith { id ->
            ghosts.firstOrNull { it.id == id }?.let { g -> return@associateWith effective(g.owner).let { o -> o.copy(opacity = o.opacity * g.alpha) } }   // призрак кальки идёт за своим слоем
            val t0 = effective(id)
            val t = fxLayers[id]?.let { l -> t0.copy(rotation = t0.rotation + EffectMath.spinAngle(l, _frame.value, _animation.value.settings.fps)) } ?: t0
            val c = _clips.value[id]
            if (layerProps[id]?.visible == false || (c != null && !c.visibleAt(_frame.value))) t.copy(opacity = 0f) else t   // глаз выключен: слой не рисуется
        }
    }

    private fun independentBones(): Set<Int> = _bones.value.filter { it.independentRot }.mapTo(HashSet()) { it.id }

    private fun boneHeads(): Map<Int, Pair<Float, Float>> = _bones.value.associate { it.id to (it.hx to it.hy) }
    private fun boneTails(): Map<Int, Pair<Float, Float>> = _bones.value.associate { it.id to (it.tx to it.ty) }

    private fun pushScene() {
        val sc = SceneBuilder.buildTree(sceneRoots(), canvasW, canvasH, transformsNow(), fit, textIds, _links.value, boneHeads(), warpsNow(), independentBones(), meshesNow(), perspNow(), boneTails(), effectiveSkins(), turbNow(), fxNow(), cameraIdNow(),
            media.filter { it.kind == MediaKind.NULLOBJ }.mapTo(HashSet()) { it.id },
            layerProps.filterValues { it.depth != 0f }.mapValues { it.value.depth }, pivotOffsetsNow())
        scene = sc
        RenderHost.view?.setScene(sc)
        warpTarget()?.let { id -> if (id in turbLayers) _turb.value = turbEffective(id) }
        refreshBoneOverlay()
        updateSelectionFrame()   // заодно обновляет инспектор
    }

    /**
     * Куда записывается правка жестом:
     *  - у слоя есть трек и на кадре есть ключ -> правится этот ключ;
     *  - трек есть, ключа нет -> временная правка кадра (сохранится кнопкой Add Key);
     *  - трека нет -> ручная трансформация base.
     */
    private fun storeEdit(id: Int, t: LayerTransform) {
        val anim = _animation.value
        when {
            anim.hasKey(id, _frame.value) -> _animation.value = anim.withKey(id, _frame.value, t)
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
    fun onTransformLayer(dzX: Float, dzY: Float, rotationDeg: Float, dx: Float, dy: Float, gestureX: Float = Float.NaN, gestureY: Float = Float.NaN) {
        if (_playing.value) return
        val ids = transformTargets().ifEmpty { return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        checkpoint("gesture")
        if (!baseEditHinted && ids.any { !_animation.value.hasTrack(it) }) {
            baseEditHinted = true
            _state.value = UiState.Message("Слой без анимации: правка действует на все кадры. Чтобы анимировать, поставьте ключ кнопкой ◎ на таймлайне")
        }
        // Сдвиг пальца: экран -> холст (с учётом поворота и отражения вида) -> координаты РОДИТЕЛЯ каждого узла
        // (родитель может быть повёрнут или масштабирован), иначе вложенная группа убегала бы от пальца. Масштаб и
        // поворот - вокруг опорной точки (pivot) каждого выделенного узла; сдвиг одинаковый у всех.
        val (cx, cy) = screenDeltaToCanvas(dx, dy)
        val rotCanvas = rotationDeg * camera.handedness   // при отражённом виде жест «по часовой» в осях холста идёт против
        val tempPivot = tempPivotFor(gestureX, gestureY)
        for (id in ids) {
            val inv = scene?.parentWorlds?.get(id)?.inverse() ?: Affine2D.IDENTITY
            val lx = inv.a * cx + inv.c * cy
            val ly = inv.b * cx + inv.d * cy
            val t = effective(id)
            val moved = t.copy(
                dx = t.dx + lx,
                dy = t.dy + ly,
                scaleX = (t.scaleX * dzX).coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE),
                scaleY = (t.scaleY * dzY).coerceIn(LayerTransform.MIN_SCALE, LayerTransform.MAX_SCALE),
                rotation = t.rotation + Math.toRadians(rotCanvas.toDouble()).toFloat()
            )
            // временная опорная точка (Temporary Pivots): поворот/масштаб вокруг точки между пальцами, а не вокруг pivot слоя
            val next = if (tempPivot != null && (dzX != 1f || dzY != 1f || rotCanvas != 0f)) {
                val inv2 = scene?.parentWorlds?.get(id)?.inverse() ?: Affine2D.IDENTITY
                val gpx = inv2.applyX(tempPivot.first, tempPivot.second); val gpy = inv2.applyY(tempPivot.first, tempPivot.second)
                PivotMath.aroundPoint(t, moved, pivotOf(id), gpx, gpy)
            } else moved
            storeEdit(id, next)
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

    // ---------------------------------------------------------------- настройки редактора (Settings -> Tools/Gestures/Import)

    /** Значения переключателей настроек, которые влияют на логику; UI синхронизирует их из AppSettings. */
    data class EditorPrefs(
        val rotateCanvas: Boolean = true,
        val pivotEditing: Boolean = true,
        val temporaryPivots: Boolean = false,
        val autoPixelEdit: Boolean = true,
        val onlySelectedPath: Boolean = false,
        val vectorWorkflow: Boolean = false,
        val importAsSymbols: Boolean = false,
        val compatExport: Boolean = false
    )
    private var prefs = EditorPrefs()
    fun setEditorPrefs(p: EditorPrefs) {
        if (p == prefs) return
        prefs = p
        refreshPivotOverlay()
    }
    fun editorPrefs(): EditorPrefs = prefs

    /** Temporary Pivots: точка между пальцами (холст) или null - крутим вокруг опорной точки слоя. */
    private fun tempPivotFor(gx: Float, gy: Float): Pair<Float, Float>? =
        if (prefs.temporaryPivots && gx.isFinite() && gy.isFinite()) toCanvasX(gx, gy) to toCanvasY(gx, gy) else null

    // ---------------------------------------------------------------- опорная точка слоя (Layer Pivot Editing)

    private val _pivotOverlay = MutableStateFlow<FloatArray?>(null)
    /** Опорная точка выделенного слоя на экране: x, y, признак «сдвинута» (1/0), признак «тащится» (1/0); null - не показывать. */
    val pivotOverlay: StateFlow<FloatArray?> = _pivotOverlay.asStateFlow()
    private val PIVOT_GRAB = 60_000
    private val PIVOT_HIT_PX = 36f
    private val PIVOT_SNAP_PX = 14f
    private var pivotLayer = -1

    /** Узел сцены по id (для рамки слоя при притяжении опорной точки). */
    private fun nodeById(id: Int): com.vibetoon.domain.model.Node? {
        fun find(list: List<com.vibetoon.domain.model.Node>): com.vibetoon.domain.model.Node? {
            for (n in list) {
                if (n.id == id) return n
                if (n is GroupNode) find(n.children)?.let { return it }
            }
            return null
        }
        return find(sceneRoots())
    }

    /** Слой, чья опорная точка правится: одиночное выделение без активных инструментов, не кость и не камера. */
    private fun pivotTarget(): Int? {
        if (!prefs.pivotEditing || _playing.value) return null
        if (_boneMode.value || _warpTool.value || _meshMode.value || _perspTool.value || _liquifyTool.value || _paintTool.value != null || rasterToolActive()) return null
        val id = _selection.value.singleOrNull() ?: return null
        if (id >= BONE_ID_BASE || id == cameraIdNow()) return null
        return id.takeIf { pivots.containsKey(it) }
    }

    /** Экранная позиция опорной точки узла: родитель (мир) * (P + d). */
    private fun pivotScreen(id: Int): Pair<Float, Float>? {
        val sc = scene ?: return null
        val pw = sc.parentWorlds[id] ?: Affine2D.IDENTITY
        val (lx, ly) = PivotMath.displayed(effective(id), pivotOf(id))
        val cx = pw.applyX(lx, ly); val cy = pw.applyY(lx, ly)
        return toScreen(cx, cy)
    }

    private fun refreshPivotOverlay() {
        val id = pivotTarget() ?: run { _pivotOverlay.value = null; return }
        val (x, y) = pivotScreen(id) ?: run { _pivotOverlay.value = null; return }
        _pivotOverlay.value = floatArrayOf(x, y, if (layerProps[id]?.hasPivot == true) 1f else 0f, if (grab == PIVOT_GRAB) 1f else 0f)
    }

    private fun pivotDown(sx: Float, sy: Float): Boolean {
        val id = pivotTarget() ?: return false
        if (isLockedId(id)) return false
        val (x, y) = pivotScreen(id) ?: return false
        if (kotlin.math.hypot(x - sx, y - sy) > PIVOT_HIT_PX) return false
        checkpoint("pivot$id", force = true)
        pivotLayer = id
        grab = PIVOT_GRAB
        swallowTap = true
        refreshPivotOverlay()
        return true
    }

    private fun pivotMove(sx: Float, sy: Float) {
        val id = pivotLayer.takeIf { it >= 0 } ?: return
        val sc = scene ?: return
        val inv = (sc.parentWorlds[id] ?: Affine2D.IDENTITY).inverse() ?: return
        val cx = toCanvasX(sx, sy); val cy = toCanvasY(sx, sy)
        val lx = inv.applyX(cx, cy); val ly = inv.applyY(cx, cy)
        val t = effective(id)
        var np = PivotMath.pivotForPoint(t, pivotOf(id), lx, ly) ?: return
        // притяжение к углам/серединам/центру рамки слоя (радиус ~ 14 px экрана)
        val n = nodeById(id)
        val box = when (n) {
            is LeafNode -> floatArrayOf(n.x.toFloat(), n.y.toFloat(), (n.x + n.w).toFloat(), (n.y + n.h).toFloat())
            else -> pivots[id]?.let { c -> floatArrayOf(c.first, c.second, c.first, c.second) }
        }
        val pxPerUnit = (viewM().let { kotlin.math.hypot(it.a, it.b) } * (sc.parentWorlds[id] ?: Affine2D.IDENTITY).let { kotlin.math.hypot(it.a, it.b) } *
            kotlin.math.abs(t.scaleX)).coerceAtLeast(1e-4f)
        if (box != null) np = PivotMath.snapToBox(np.first, np.second, box, PIVOT_SNAP_PX / pxPerUnit)
        movePivot(id, np)
    }

    private fun pivotUp() {
        val id = pivotLayer
        pivotLayer = -1
        grab = 0
        refreshPivotOverlay()
        if (id >= 0) Log.i("VT/Edit", "pivot of $id -> ${layerProps[id]?.pivotX}, ${layerProps[id]?.pivotY}")
    }

    /**
     * Переносит опорную точку узла в [np] (координаты узла до трансформации). Сдвиг base/override/всех ключей
     * компенсируется ([PivotMath.compensate]), поэтому на ключевых кадрах картинка не двигается.
     */
    private fun movePivot(id: Int, np: Pair<Float, Float>) {
        val old = pivotOf(id)
        if (old == np) return
        val center = pivots[id] ?: return
        base[id]?.let { base[id] = PivotMath.compensate(it, old, np) }
        override[id]?.let { override[id] = PivotMath.compensate(it, old, np) }
        val anim = _animation.value
        anim.tracks[id]?.let { tr -> _animation.value = anim.copy(tracks = anim.tracks + (id to PivotMath.compensateTrack(tr, old, np))) }
        val cur = layerPropsOf(id)
        setProps(layerProps + (id to cur.copy(pivotX = np.first - center.first, pivotY = np.second - center.second).normalized()))
        pushScene()
        markDirty()
    }

    /** Кнопка «Опорная точка по центру»: возвращает pivot в центр рамки слоя (с компенсацией, слой не двигается). */
    fun resetPivot() {
        val id = _selected.value ?: return
        if (layerProps[id]?.hasPivot != true) return
        if (isLockedId(id)) { lockedNotice(); return }
        checkpoint("pivot$id", force = true)
        pivots[id]?.let { movePivot(id, it) }
        _state.value = UiState.Message("Опорная точка возвращена в центр слоя")
    }

    /** Опорная точка выделенного слоя сдвинута (для кнопки сброса). */
    fun selectedHasPivot(): Boolean = _selected.value?.let { layerProps[it]?.hasPivot } == true

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
        val removing = _animation.value.hasKey(prim, frame)
        for (id in ids) {
            val anim = _animation.value
            if (removing) {
                if (!anim.hasKey(id, frame)) continue
                val (next, lastValue) = anim.withoutKey(id, frame)
                _animation.value = next
                // Последний ключ удалён: слой остаётся на месте как ручная трансформация
                if (lastValue != null) base[id] = lastValue
                Log.i("VT/Anim", "key deleted: layer $id frame $frame")
            } else {
                if (anim.hasKey(id, frame)) continue
                // Первый ключ создаётся из текущего положения, поэтому слой не прыгает
                val t = effective(id)
                override.remove(id)
                _animation.value = anim.withKey(id, frame, t)
                Log.i("VT/Anim", "key added: layer $id frame $frame dx=${t.dx} dy=${t.dy} scaleX=${t.scaleX} scaleY=${t.scaleY} rot=${t.rotation}")
            }
        }
        pushScene()
        markDirty()
    }

    // ---------------------------------------------------------------- Undo / Redo

    private fun snapshot() = Snapshot(HashMap(base), _animation.value, media.toList(), deletedPsd, _clips.value, _links.value, _bones.value, regroup, warpLayers, meshLayers, perspLayers, skinLayers, turbLayers, fxLayers, pinLayers, layerProps)

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
        if (undoStack.isEmpty() || _playing.value) return
        val s = undoStack.removeLast()
        redoStack.addLast(snapshot().withPaint(s.paintOp))
        restore(s)
        s.paintOp?.let { applyPaint(it, useBefore = true) }
        updateHistoryFlags()
        Log.i("VT/Edit", "undo (left ${undoStack.size})")
    }

    fun redo() {
        if (redoStack.isEmpty() || _playing.value) return
        val s = redoStack.removeLast()
        undoStack.addLast(snapshot().withPaint(s.paintOp))
        restore(s)
        s.paintOp?.let { applyPaint(it, useBefore = false) }
        updateHistoryFlags()
        Log.i("VT/Edit", "redo (left ${redoStack.size})")
    }

    private fun restore(snap: Snapshot) {
        val oldMedia = media.toList()
        lastCheckKey = null
        resetSymbolImages()
        base.clear(); base.putAll(snap.base)
        override.clear()
        _animation.value = snap.animation
        meta = meta?.copy(fps = snap.animation.settings.fps, durationFrames = snap.animation.settings.durationFrames)
        media.clear(); media.addAll(snap.media)
        deletedPsd = snap.deleted
        _clips.value = snap.clips
        _links.value = snap.links
        _bones.value = snap.bones
        regroup = snap.regroup
        warpLayers = snap.warps
        meshLayers = snap.meshes
        perspLayers = snap.persps
        skinLayers = snap.skins
        turbLayers = snap.turbs
        fxLayers = snap.fx
        pinLayers = snap.pins
        setProps(snap.lprops)
        refreshFxState()
        _turb.value = warpTarget()?.let { turbEffective(it) }
        textJob?.cancel()
        rebuildLayers()
        val known = layers.mapTo(HashSet()) { it.id } + media.filter { !it.isVisual }.map { it.id } + _bones.value.map { it.id }
        val keep = _selection.value.filter { it in known }.toSet()
        _frame.value = _frame.value.coerceAtMost(snap.animation.settings.durationFrames - 1)
        applySelection(keep, if (_selected.value in keep) _selected.value else keep.lastOrNull())
        pushScene()
        // текст, у которого изменилось содержимое, перерисовывается заново
        val changedText = media.filter { m ->
            m.isText && oldMedia.firstOrNull { it.id == m.id }.let { o ->
                o != null && (o.text != m.text || o.textColor != m.textColor || o.font != m.font || o.textSize != m.textSize ||
                    o.gradient != m.gradient || o.gradientAngle != m.gradientAngle || o.fx != m.fx ||
                    o.stroke != m.stroke || o.strokeWidth != m.strokeWidth || o.strokeColor != m.strokeColor ||
                    o.textAlign != m.textAlign || o.lineSpacing != m.lineSpacing || o.letterSpacing != m.letterSpacing)
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
        _clips.value = if (clip == defaultClip(id)) _clips.value - id else _clips.value + (id to clip)
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
    fun goStart() = scrubTo(0)
    fun goEnd() = scrubTo(duration() - 1)

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
        val id = _selected.value ?: return
        if (_playing.value) return
        val item = media.firstOrNull { it.id == id }
        if (item == null) {
            _state.value = UiState.Message("Разрезать пока можно картинки, видео, текст и аудио (слои PSD - позже)")
            return
        }
        val frame = _frame.value
        val clip = clipOf(id)
        if (frame <= clip.start || frame >= clip.end) {
            _state.value = UiState.Message("Поставьте плейхед внутри клипа выбранного слоя")
            return
        }
        checkpoint("split", force = true)
        val newId = nextMediaId()
        val len1 = frame - clip.start
        val len2 = clip.end - frame
        // смещения источника: вперёд - вторая часть сдвигается на len1; реверс - первая часть берёт "хвост"
        val off1: Int
        val off2: Int
        if (!clip.reverse) {
            off1 = clip.offset
            off2 = clip.offset + Math.round(len1 * clip.speed)
        } else {
            off1 = clip.offset + Math.round(len2 * clip.speed)
            off2 = clip.offset
        }
        val first = clip.copy(end = frame, offset = off1)
        val second = clip.copy(start = frame, offset = off2)
        media += item.copy(id = newId)
        _clips.value = _clips.value + (id to first) + (newId to second)
        base[id]?.let { base[newId] = it }
        _animation.value.tracks[id]?.let { tr ->
            _animation.value = _animation.value.copy(tracks = _animation.value.tracks + (newId to AnimationTrack(newId, tr.keys)))
        }
        rebuildLayers()
        pushScene()
        val copy = media.first { it.id == newId }
        if (copy.isVisual) {
            viewModelScope.launch {
                withContext(decodeDispatcher) { runCatching { uploadTextureFor(pid, copy) } }
                RenderHost.view?.requestRender()
            }
        }
        selectLayer(newId)
        markDirty()
        _state.value = UiState.Message("Клип разрезан на кадре $frame")
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
        checkpoint("dup", force = true)
        val newId = nextMediaId()
        var copy = item.copy(id = newId, name = item.name + " копия")
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
        fxLayers[id]?.let { fxLayers = fxLayers + (newId to it) }
        layerProps[id]?.let { setProps(layerProps + (newId to it)) }
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
            val item = MediaItem(newId, MediaKind.IMAGE, info.name + " копия", "assets/$file", w, h, info.x.toFloat(), info.y.toFloat(), info.width.toFloat(), info.height.toFloat())
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

    /** Какой экземпляр символа в какой внутренний кадр уже отрисован в его текстуру. */
    private val symShown = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val symBusy = HashSet<Int>()
    private val symTries = HashMap<Int, Int>()
    // ---- Sprint 6.9.1: надёжность символов ----
    /** Один снимок символа за раз: сборка вложенной сцены заливает текстуры участников, параллельные снимки их путали. */
    private val symbolLock = kotlinx.coroutines.sync.Mutex()
    /** Ревизия содержимого символов: растёт при выходе из редактирования, Undo/Redo, смене холста, открытии проекта. */
    @Volatile private var symRev = 0
    /** Готовые картинки внутренних кадров (ключ SymbolMath.cacheKey): смена маркера - заливка из памяти, без снимка GL. */
    private val symImages = LinkedHashMap<String, android.graphics.Bitmap>(16, 0.75f, true)
    private var symImagesBytes = 0L
    private val symRetryAt = HashMap<Int, Long>()
    private val _editingSymbol = MutableStateFlow<Int?>(null)
    /** Id группы-определения символа, открытого на редактирование; null - главная сцена. */
    val editingSymbol: StateFlow<Int?> = _editingSymbol.asStateFlow()
    private var lastTapId = -1
    private var lastTapAt = 0L

    private val _symMarkers = MutableStateFlow<List<FrameMarker>>(emptyList())
    /** Маркеры кадров открытого символа (пусто на главной сцене). */
    val symMarkers: StateFlow<List<FrameMarker>> = _symMarkers.asStateFlow()
    private val _timeKeys = MutableStateFlow<Map<Int, List<Int>>>(emptyMap())
    /** Кадры ключей времени экземпляров символов: слой -> кадры (фиолетовые засечки на таймлайне). */
    val timeKeys: StateFlow<Map<Int, List<Int>>> = _timeKeys.asStateFlow()
    private val _markerThumbs = MutableStateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap())
    /** Миниатюры кадров с маркерами для окна «Выбрать маркер»: ключ "определение:кадр". */
    val markerThumbs: StateFlow<Map<String, androidx.compose.ui.graphics.ImageBitmap>> = _markerThumbs.asStateFlow()

    fun symbolName(id: Int): String = media.firstOrNull { it.id == id }?.name ?: ""
    fun isAudioTrack(id: Int): Boolean = media.any { it.id == id && it.kind == MediaKind.AUDIO }

    /** Громкость аудио-слоя на текущем кадре (0..1). */
    fun volumeAt(id: Int): Float = com.vibetoon.domain.project.VolumeMath.at(media.firstOrNull { it.id == id }?.volKeys.orEmpty(), _frame.value.toDouble())
    fun volumeKeyCount(id: Int): Int = media.firstOrNull { it.id == id }?.volKeys?.size ?: 0
    fun hasVolumeKeyHere(id: Int): Boolean = media.firstOrNull { it.id == id }?.volKeys?.any { it.frame == _frame.value } == true

    /** Ставит ключ громкости на текущий кадр (0..1); между ключами громкость меняется линейно. */
    fun setVolumeKey(id: Int, value: Float) {
        val i = media.indexOfFirst { it.id == id && it.kind == MediaKind.AUDIO }
        if (i < 0 || !value.isFinite()) return
        checkpoint("vol$id")
        media[i] = media[i].copy(volKeys = com.vibetoon.domain.project.VolumeMath.withKey(media[i].volKeys, com.vibetoon.domain.project.VolKey(_frame.value, value.coerceIn(0f, 1f))))
        audioEngine.volumes = media.filter { !it.isVisual && it.volKeys.isNotEmpty() }.associate { it.id to it.volKeys }
        markDirty()
    }

    fun removeVolumeKey(id: Int) {
        val i = media.indexOfFirst { it.id == id && it.kind == MediaKind.AUDIO }
        if (i < 0) return
        checkpoint("vol$id", force = true)
        media[i] = media[i].copy(volKeys = com.vibetoon.domain.project.VolumeMath.withoutKey(media[i].volKeys, _frame.value))
        audioEngine.volumes = media.filter { !it.isVisual && it.volKeys.isNotEmpty() }.associate { it.id to it.volKeys }
        markDirty()
    }

    fun isSymbolInstance(id: Int): Boolean = media.any { it.id == id && it.kind == MediaKind.SYMBOL }
    /** Маркеры символа, на который ссылается экземпляр [instId]. */
    fun markersOfInstance(instId: Int): List<FrameMarker> = media.firstOrNull { it.id == media.firstOrNull { m -> m.id == instId }?.symRef }?.markers.orEmpty()
    fun symbolRefOf(instId: Int): Int = media.firstOrNull { it.id == instId }?.symRef ?: 0
    fun timeKeysOf(instId: Int): List<TimeKey> = media.firstOrNull { it.id == instId }?.timeKeys.orEmpty()

    private fun symbolMembers(gid: Int): Set<Int> = SymbolMath.descendants(gid, layers.filter { it.parentId >= 0 }.associate { it.id to it.parentId })

    private fun refreshSymbolState() {
        val ctx = _editingSymbol.value
        _symMarkers.value = if (ctx == null) emptyList() else media.firstOrNull { it.id == ctx }?.markers.orEmpty()
        _timeKeys.value = media.filter { it.kind == MediaKind.SYMBOL && it.timeKeys.isNotEmpty() }.associate { it.id to it.timeKeys.map { k -> k.frame } }
    }

    /** Слои, скрытые на сцене: в главной сцене - содержимое символов (видны экземпляры), при редактировании символа [ctx] - всё кроме него. */
    private fun symbolHidden(ctx: Int?): Set<Int> {
        val defs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.map { it.id }
        if (defs.isEmpty() && ctx == null) return emptySet()
        val cams = media.filter { it.kind == MediaKind.CAMERA || it.kind == MediaKind.NULLOBJ }.mapTo(HashSet()) { it.id }
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

    /** Меняется ли вложенная сцена по времени: иначе её достаточно нарисовать один раз. */
    private fun symbolAnimated(gid: Int, guard: Int = 0): Boolean {
        if (guard > MAX_SYMBOL_DEPTH) return false
        val mem = symbolMembers(gid)
        return _animation.value.tracks.keys.any { it in mem } || _clips.value.keys.any { it in mem } ||
            media.any { m -> m.id in mem && (m.cells.size > 1 || (m.isText && m.fx.animated) || m.kind == MediaKind.VIDEO ||
                (m.kind == MediaKind.SYMBOL && (m.timeKeys.isNotEmpty() || symbolAnimated(m.symRef, guard + 1)))) }
    }

    /** Внутренний кадр экземпляра на кадре сцены [f]: ключи времени (Hold) главнее, иначе цикл localFrame = f % длительность. */
    private fun symbolKey(inst: MediaItem, def: MediaItem, f: Int): Int =
        SymbolMath.timeAt(inst.timeKeys, f)?.coerceIn(0, maxOf(0, def.symDur - 1))
            ?: if (symbolAnimated(def.id)) SymbolMath.localFrame(f, def.symDur) else 0

    private fun computeSymbolDuration(gid: Int): Int {
        val mem = symbolMembers(gid)
        val frames = ArrayList<Int>()
        for ((id, t) in _animation.value.tracks) if (id in mem) frames += t.keys.maxOfOrNull { it.frame } ?: 0
        for (m in media) if (m.id in mem) {
            frames += m.cells.maxOfOrNull { it.start } ?: 0
            frames += m.timeKeys.maxOfOrNull { it.frame } ?: 0
        }
        val def = media.firstOrNull { it.id == gid }
        frames += def?.markers?.maxOfOrNull { it.frame } ?: 0
        return SymbolMath.duration(frames, _animation.value.settings.durationFrames)
    }

    /** Размер картинки символа = размер холста (экземпляр занимает весь холст); раньше потолок 1024 px давал мыло. */
    private fun symbolDims(): Pair<Int, Int> = SymbolMath.renderSize(canvasW, canvasH, RenderHost.view?.maxCaptureSide ?: 4096)

    private var budgetWarned = 0
    /** Бюджет памяти текстур: раз в сессию предупреждаем, если слои пришлось уменьшить или не удалось загрузить. */
    private fun checkTextureBudget() {
        RenderHost.view?.textureStats { st ->
            if (st == null || st.size < 4) return@textureStats
            val issues = st[2] + st[3]
            if (issues <= budgetWarned) return@textureStats
            viewModelScope.launch {
                if (budgetWarned == 0) _state.value = UiState.Message("Память видеокарты почти заполнена (${st[0]} из ${st[1]} МБ): часть слоёв показана в уменьшенном размере")
                budgetWarned = issues
            }
        }
    }

    /** Содержимое символов изменилось: старые картинки не годятся, экземпляры перерисуются. */
    private fun resetSymbolImages() {
        symShown.clear(); symTries.clear(); symRetryAt.clear()
        symRev++
        synchronized(symImages) { symImages.clear(); symImagesBytes = 0L }
    }

    /** Все участники всех определений символов (на главной сцене они скрыты; их текстуры нужны только снимкам символов). */
    private fun allSymbolMembers(): Set<Int> {
        val out = HashSet<Int>()
        for (d in media) if (d.kind == MediaKind.GROUP && d.symDur > 0) out += symbolMembers(d.id)
        return out
    }

    /** Картинка внутреннего кадра [local] символа [defId]: из кэша или новым снимком. Вызывать под [symbolLock]. */
    private suspend fun symbolImage(defId: Int, local: Int): android.graphics.Bitmap? {
        val key = SymbolMath.cacheKey(defId, local, symRev)
        synchronized(symImages) { symImages[key] }?.let { return it }
        flushPaintSaves()   // нарисованное в символе должно попасть в файлы кадров до снимка
        val bmp = symbolBitmap(defId, local, 0) ?: return null
        synchronized(symImages) {
            symImages[key] = bmp
            symImagesBytes += bmp.byteCount
            val it = symImages.entries.iterator()
            while (symImagesBytes > SYMBOL_CACHE_BYTES && symImages.size > 1 && it.hasNext()) {
                val e = it.next()
                if (e.key == key) continue
                symImagesBytes -= e.value.byteCount
                it.remove()   // без recycle: картинку может читать заливка/миниатюра, память освободит GC
            }
        }
        return bmp
    }

    /**
     * Подставляет в экземпляры символов картинку вложенной сцены на кадре [f].
     * Sprint 6.9.1: снимки идут по одному ([symbolLock]), картинки кэшируются; при сбое прежняя текстура остаётся (экземпляр
     * не пустеет), повтор с паузой 0.4-5 с без пометки «показан»; на паузе приложения снимки не ставятся вовсе.
     */
    private fun updateSymbols(f: Int) {
        if (projectId == null || _editingSymbol.value != null) return
        if (RenderHost.view?.active != true) return   // после возврата onHostResume / onGlContextReady вызовут снова
        val inside = allSymbolMembers()
        for (inst in media.filter { it.kind == MediaKind.SYMBOL && it.id !in inside }) {   // вложенные экземпляры рисуются внутри родителя
            val def = media.firstOrNull { it.id == inst.symRef && it.symDur > 0 } ?: continue
            val key = symbolKey(inst, def, f)
            if (symShown[inst.id] == key || inst.id in symBusy) continue
            val retryAt = symRetryAt[inst.id]
            if (retryAt != null && android.os.SystemClock.uptimeMillis() < retryAt) continue
            symBusy += inst.id
            val instId = inst.id; val defId = def.id; val defName = def.name
            viewModelScope.launch {
                var ok = false
                var paused = false
                try {
                    symbolLock.withLock {
                        val bmp = symbolImage(defId, key)
                        if (bmp != null) {
                            withContext(Dispatchers.Default) { RenderNative.uploadBitmap(instId, bmp) }
                            restoreMemberTextures(defId)
                            ok = true
                        } else paused = RenderHost.view?.active != true
                    }
                    if (ok) { invalidateThumb(instId); RenderHost.view?.requestRender(); checkTextureBudget() }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w("VT/Symbols", "symbol $defName not drawn: ${t.message}")
                } finally {
                    symBusy -= instId
                }
                when {
                    ok -> { symShown[instId] = key; symTries.remove(instId); symRetryAt.remove(instId) }
                    paused -> Unit   // вид на паузе: не ошибка, повтор после возврата
                    else -> {
                        val tries = (symTries[instId] ?: 0) + 1
                        symTries[instId] = tries
                        val wait = SymbolMath.retryDelayMs(tries)
                        symRetryAt[instId] = android.os.SystemClock.uptimeMillis() + wait
                        if (tries == 3) _state.value = UiState.Message("Символ «$defName» пока не перерисовался: показан прежний кадр, идут повторы")
                        kotlinx.coroutines.delay(wait)
                    }
                }
                if (_editingSymbol.value == null) updateSymbols(_frame.value)
            }
        }
    }

    /** Текстуры участников (рисунки кадров, тексты с эффектами) остались на внутреннем кадре символа: вернуть кадр сцены. */
    // Sprint 6.9.1: на главной сцене участники скрыты, поэтому их текстуры не заливаются заново после каждого снимка
    // (раньше updateCells главного кадра и снимок внутреннего кадра писали в одни и те же id наперегонки - маркеры
    // «переставали переключаться», в символ попадал не тот рот). Сбрасываются только отметки, а заливка - при входе в символ.
    private fun restoreMemberTextures(defId: Int) {
        val mem = symbolMembers(defId)
        for (m in media) if (m.id in mem) {
            if (m.cells.isNotEmpty()) cellShown.remove(m.id)
            if (m.isText && m.fx.animated) textFxKeys.remove(m.id)
        }
        if (_editingSymbol.value != null) { updateCells(_frame.value); updateTextFx(_frame.value) }
    }

    /** Вложенные экземпляры символа [defId] на его внутреннем кадре [local]: их текстуры рисуются рекурсивно (глубина до MAX_SYMBOL_DEPTH). */
    private suspend fun drawNestedSymbols(defId: Int, local: Int, depth: Int) {
        if (depth >= MAX_SYMBOL_DEPTH) return
        val mem = symbolMembers(defId)
        for (n in media.filter { it.kind == MediaKind.SYMBOL && it.id in mem }) {
            val nd = media.firstOrNull { it.id == n.symRef && it.symDur > 0 } ?: continue
            val bmp = symbolBitmap(nd.id, symbolKey(n, nd, local), depth + 1) ?: continue
            RenderNative.uploadBitmap(n.id, bmp); bmp.recycle()
        }
    }

    /** Картинка вложенной сцены [defId] на внутреннем кадре [local] (прозрачный фон). */
    private suspend fun symbolBitmap(defId: Int, local: Int, depth: Int): android.graphics.Bitmap? {
        drawNestedSymbols(defId, local, depth)
        val fps = _animation.value.settings.fps
        val (w, h) = symbolDims()
        val scene = withContext(Dispatchers.Default) { buildSceneAt(fps, null, defId)(local) }
        val bytes = captureNow(scene, true, w, h) ?: return null
        return withContext(Dispatchers.Default) { captureBitmap(bytes, w, h) }   // переворот строк и Bitmap - не в UI-потоке
    }

    /** Предварительная отрисовка вложенных сцен для экспорта: определение -> PNG каждого внутреннего кадра (null - не снялся). [frame] - только кадр проекта. */
    private suspend fun prerenderSymbols(frame: Int? = null): Map<Int, List<ByteArray?>> {
        val inside = HashSet<Int>()
        for (d in media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }) inside += symbolMembers(d.id)
        val top = media.filter { it.kind == MediaKind.SYMBOL && it.id !in inside }
        val defs = top.map { it.symRef }.distinct().mapNotNull { id -> media.firstOrNull { it.id == id && it.symDur > 0 } }
        if (defs.isEmpty()) return emptyMap()
        flushPaintSaves()
        val st = _animation.value.settings
        val out = HashMap<Int, List<ByteArray?>>()
        for (d in defs) {
            val insts = top.filter { it.symRef == d.id }
            val n = d.symDur.coerceAtLeast(1)
            val keys: Collection<Int> = if (frame != null) insts.map { symbolKey(it, d, frame) }.toSet()
                else if (symbolAnimated(d.id) || insts.any { it.timeKeys.isNotEmpty() }) (0 until n).toList() else listOf(0)
            val list = MutableList<ByteArray?>(n) { null }
            for (k in keys) {
                if (k !in list.indices) continue
                val b = symbolLock.withLock { symbolImage(d.id, k) } ?: continue
                list[k] = withContext(Dispatchers.Default) {
                    val bo = java.io.ByteArrayOutputStream()
                    b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo)   // картинка из кэша: не recycle
                    bo.toByteArray()
                }
            }
            restoreMemberTextures(d.id)
            out[d.id] = list
        }
        return out
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
        val plan = if (frameLeaves.size >= 2) SymbolMath.framesPlan(frameLeaves, frameLeaves.map { id -> layers.first { it.id == id }.name }) else null
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
        media[gi] = media[gi].copy(name = "Символ $n", symDur = plan?.duration ?: computeSymbolDuration(gid), markers = plan?.markers ?: emptyList())
        plan?.clips?.forEach { (id, s, e) -> storeClip(id, Clip(s, e)) }   // слой виден только на своём кадре
        val iid = nextMediaId()
        media += MediaItem(iid, MediaKind.SYMBOL, "Символ $n", "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = gid)
        rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
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
        selectLayer(null)
        refreshSymbolState(); rebuildRows(); pushScene()
        restoreMemberTextures(gid)   // участники получают рисунки текущего кадра (на главной сцене они не обновлялись)
        _state.value = UiState.Message("Редактирование «${def.name}»: правки видны во всех экземплярах")
    }

    /** Back to Main Scene: длительность символа пересчитывается, экземпляры перерисовываются. */
    fun exitSymbol() {
        val gid = _editingSymbol.value ?: return
        val i = media.indexOfFirst { it.id == gid }
        if (i >= 0) media[i] = media[i].copy(symDur = computeSymbolDuration(gid))
        _editingSymbol.value = null
        selectLayer(null)
        resetSymbolImages()   // содержимое могло измениться: кэш картинок и отметки сбрасываются
        refreshSymbolState(); rebuildRows(); pushScene(); updateSymbols(_frame.value); markDirty()
        regenMarkerThumbs(gid, force = true)
    }

    /** Маркер кадра на текущем кадре открытого символа (на том же кадре метка заменяется). */
    fun addFrameMarker(name: String) {
        val gid = _editingSymbol.value ?: return
        val clean = name.trim().take(24)
        val i = media.indexOfFirst { it.id == gid }
        if (clean.isEmpty() || i < 0) return
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
        checkpoint("timekey", force = true)
        media[i] = change(media[i])
        symShown.remove(id)
        refreshSymbolState(); markDirty()
        updateSymbols(_frame.value)
        if (message != null) _state.value = UiState.Message(message)
    }

    /** «Выбрать маркер»: на текущем кадре сцены ключ времени экземпляра = кадр маркера, интерполяция Hold. */
    fun pickMarker(instId: Int, marker: FrameMarker) =
        editInstance(instId, "«${marker.name}» с кадра ${_frame.value + 1}") { it.copy(timeKeys = SymbolMath.withKey(it.timeKeys, TimeKey(_frame.value, marker.frame))) }
    fun removeTimeKey(instId: Int) = editInstance(instId, "Ключ времени удалён") { it.copy(timeKeys = SymbolMath.withoutKey(it.timeKeys, _frame.value)) }
    fun clearTimeKeys(instId: Int) = editInstance(instId, "Ключи времени очищены: символ снова идёт по кругу") { it.copy(timeKeys = emptyList()) }

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
                val bmp = withContext(Dispatchers.IO) { if (thumbOutdated(f)) null else android.graphics.BitmapFactory.decodeFile(f.absolutePath) } ?: continue
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
        return markersOfInstance(instId).firstOrNull { it.frame == v }?.name ?: "кадр ${v + 1}"
    }

    /** Ручной ключ времени (иконка «ключ»): фиксирует то, что символ показывает сейчас, на текущем кадре. */
    fun addTimeKeyHere(instId: Int) {
        val inst = media.firstOrNull { it.id == instId && it.kind == MediaKind.SYMBOL } ?: return
        val def = media.firstOrNull { it.id == inst.symRef && it.symDur > 0 } ?: return
        val v = SymbolMath.timeAt(inst.timeKeys, _frame.value) ?: SymbolMath.localFrame(_frame.value, def.symDur)
        editInstance(instId, "Ключ времени (Hold) на кадре ${_frame.value + 1}") { it.copy(timeKeys = SymbolMath.withKey(it.timeKeys, TimeKey(_frame.value, v))) }
    }

    private fun thumbName(defId: Int, frame: Int) = "st${defId}_$frame.png"

    /** Миниатюра из прошлых версий (96 px) - мыльная, её нужно перерисовать. */
    private fun thumbOutdated(f: java.io.File): Boolean {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        return maxOf(o.outWidth, o.outHeight) < SymbolMath.THUMB_SIDE * 3 / 4
    }

    /**
     * Миниатюры маркеров (PNG до [SymbolMath.THUMB_SIDE] px в папке проекта; ссылка хранится в маркере). Недостающие,
     * старые 96-пиксельные или устаревшие (force) рисуются заново - асинхронно: снимок под [symbolLock] из той же картинки,
     * что идёт в экземпляр (данные символа не трогаются), масштаб и PNG - в фоновых потоках.
     */
    private fun regenMarkerThumbs(defId: Int, force: Boolean) {
        val pid = projectId ?: return
        viewModelScope.launch {
            val def = media.firstOrNull { it.id == defId && it.symDur > 0 } ?: return@launch
            val got = HashMap<Int, String>()
            for (mk in def.markers) {
                val f = projects.mediaFile(pid, thumbName(defId, mk.frame))
                if (!force && mk.thumb.isNotEmpty() && f.exists() && !withContext(Dispatchers.IO) { thumbOutdated(f) }) continue
                if (RenderHost.view?.active != true) break   // на паузе не снимаем: дорисуются при следующем открытии панели
                val small = symbolLock.withLock {
                    val b = runCatching { symbolImage(defId, mk.frame) }.getOrNull() ?: return@withLock null
                    withContext(Dispatchers.Default) {
                        val (tw, th) = SymbolMath.thumbSize(b.width, b.height)
                        android.graphics.Bitmap.createScaledBitmap(b, tw, th, true)   // b остаётся в кэше нетронутой
                    }
                } ?: continue
                withContext(Dispatchers.IO) { f.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                _markerThumbs.value = _markerThumbs.value + ("$defId:${mk.frame}" to small.asImageBitmap())
                got[mk.frame] = "assets/${f.name}"
            }
            restoreMemberTextures(defId)
            if (got.isNotEmpty()) {
                val i = media.indexOfFirst { it.id == defId }
                if (i >= 0) { media[i] = media[i].copy(markers = media[i].markers.map { m -> got[m.frame]?.let { m.copy(thumb = it) } ?: m }); refreshSymbolState(); markDirty() }
            }
        }
    }


    // ---- покадровые слои (рисунки-экспозиции) и калька (Onion Skin) ----

    private val _cellMap = MutableStateFlow<Map<Int, List<Int>>>(emptyMap())
    /** Кадры, на которых начинаются рисунки покадровых слоёв: слой -> номера кадров (для засечек на таймлайне). */
    val cellMap: StateFlow<Map<Int, List<Int>>> = _cellMap.asStateFlow()

    private val cellShown = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val cellBusy = HashSet<Int>()
    private val ghostShown = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private class Ghost(val id: Int, val owner: Int, val r: Float, val g: Float, val b: Float, val alpha: Float, val file: String)
    private var ghosts: List<Ghost> = emptyList()

    private val _onion = MutableStateFlow(false)
    val onion: StateFlow<Boolean> = _onion.asStateFlow()
    private val _onionDepth = MutableStateFlow(1)
    val onionDepth: StateFlow<Int> = _onionDepth.asStateFlow()
    private val _onionAlpha = MutableStateFlow(0.35f)
    val onionAlpha: StateFlow<Float> = _onionAlpha.asStateFlow()

    fun toggleOnion() {
        _onion.value = !_onion.value
        _state.value = UiState.Message(if (_onion.value) "Калька включена: предыдущий рисунок красный, следующий зелёный (нужен покадровый слой)" else "Калька выключена")
        refreshOnion()
    }
    fun setOnionDepth(d: Int) { _onionDepth.value = d.coerceIn(1, 3); refreshOnion() }
    fun setOnionAlpha(a: Float) { if (a.isFinite()) { _onionAlpha.value = a.coerceIn(0.1f, 0.9f); refreshOnion() } }

    private fun ghostBase(owner: Int) = GHOST_BASE + (owner - MEDIA_ID_BASE).coerceAtLeast(0) * 8

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
            val (prev, next) = FrameMath.neighbors(m.cells, _frame.value, _onionDepth.value)
            val base = ghostBase(m.id)
            prev.forEachIndexed { i, c -> list += Ghost(base + i, m.id, 1f, 0.2f, 0.2f, FrameMath.ghostAlpha(_onionAlpha.value, i), c.file) }
            next.forEachIndexed { i, c -> list += Ghost(base + 4 + i, m.id, 0.2f, 1f, 0.2f, FrameMath.ghostAlpha(_onionAlpha.value, i), c.file) }
        }
        val same = list.size == ghosts.size && list.indices.all { ghosts[it].id == list[it].id && ghosts[it].file == list[it].file && ghosts[it].alpha == list[it].alpha }
        ghosts = list
        val pid = projectId ?: return
        viewModelScope.launch {
            val need = list.filter { ghostShown[it.id] != it.file }
            if (need.isNotEmpty()) {
                withContext(decodeDispatcher) {
                    for (g in need) runCatching {
                        val d = importer.decodeImage(projects.fileOf(pid, g.file))
                        RenderNative.uploadBitmap(g.id, d.bitmap); d.bitmap.recycle(); ghostShown[g.id] = g.file
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
        // участники символов на главной сцене скрыты: их текстуру заливает снимок символа, а не кадр сцены
        val skip = if (_editingSymbol.value == null) allSymbolMembers() else emptySet()
        for (m in media) {
            if (m.kind != MediaKind.IMAGE || m.cells.isEmpty() || m.id in skip) continue
            val act = FrameMath.active(m.cells, f) ?: continue
            if (cellShown[m.id] == act.file || m.id in cellBusy) continue
            cellBusy += m.id
            viewModelScope.launch {
                try {
                    flushPaintSaves()   // нарисованное на прошлом рисунке уходит в его файл до замены текстуры
                    val d = withContext(decodeDispatcher) { importer.decodeImage(projects.fileOf(pid, act.file)) }
                    RenderNative.uploadBitmap(m.id, d.bitmap); d.bitmap.recycle()
                    cellShown[m.id] = act.file
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
    }

    private suspend fun restoreAfterExport() {
        textFxKeys.clear(); cellShown.clear(); symShown.clear()
        updateTextFx(_frame.value)
        updateCells(_frame.value)
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
            flushPaintSaves()
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
            flushPaintSaves()
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
    private suspend fun captureNow(scene: SceneData?, transparent: Boolean, w: Int, h: Int): ByteArray? {
        val view = RenderHost.view ?: return null
        val bg = meta?.background?.toInt() ?: 0
        return kotlinx.coroutines.withTimeoutOrNull(20000) {
            kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c -> view.capture(scene, w, h, transparent, bg) { if (c.isActive) c.resumeWith(Result.success(it)) } }
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
            if (bytes == null) { _state.value = UiState.Message("Не удалось собрать слой, повторите"); return@launch }
            val newId = nextMediaId()
            val file = "m$newId.png"
            withContext(Dispatchers.IO) {
                val bmp = captureBitmap(bytes, w, h)
                projects.mediaFile(pid, file).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            val item = MediaItem(newId, MediaKind.IMAGE, "Слияние (${leaf.size})", "assets/$file", w, h, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
            checkpoint("merge", force = true)
            // место слитого слоя: там, где стоял самый нижний из сливаемых слоёв-медиа (иначе - над всеми медиа)
            val insertAt = media.indexOfFirst { it.id in leaf }.let { if (it < 0) media.size else it }
            applySelection(leaf, leaf.last())
            deleteSelected(cp = false)
            media.add(insertAt.coerceIn(0, media.size), item)
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
     * для всех вложенных). Слои PSD и слои-медиа живут в разных системах координат, поэтому группа собирается
     * либо только из слоёв PSD, либо только из медиа-слоёв.
     */
    fun groupSelected() {
        if (_playing.value) return
        val ids = _selection.value.filter { it < BONE_ID_BASE }.toSet()
        if (ids.isEmpty()) { _state.value = UiState.Message("Отметьте слои для группы"); return }
        if (ids.any { isLockedId(it) }) { lockedNotice(); return }
        val psd = ids.filter { it < MEDIA_ID_BASE }
        val med = ids.filter { it >= MEDIA_ID_BASE }
        if (psd.isNotEmpty() && med.isNotEmpty()) { _state.value = UiState.Message("Слои PSD и картинки/текст нельзя смешивать в одной группе: соберите их по отдельности"); return }
        val byId = layers.associateBy { it.id }
        val topParent = byId[ids.first()]?.parentId ?: -1
        var gid: Int
        if (psd.isNotEmpty()) { gid = MEDIA_ID_BASE - 1; while (media.any { it.id == gid }) gid-- } else gid = nextMediaId()
        // группа не может войти в саму себя
        if (ids.any { it in ancestorsOf(topParent) }) { _state.value = UiState.Message("Нельзя: группа внутри выбранного слоя"); return }
        checkpoint("group", force = true)
        val count = media.count { it.kind == MediaKind.GROUP } + 1
        media += MediaItem(gid, MediaKind.GROUP, "Группа $count", "")
        regroup = regroup.filter { it.first !in ids && it.first != gid } + ids.map { it to gid } + (if (topParent >= 0) listOf(gid to topParent) else emptyList())
        rebuildLayers()
        pushScene()
        selectLayer(gid)
        markDirty()
        _state.value = UiState.Message("Группа создана: слоёв ${ids.size}")
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
                val r = textRenderer.renderFx(m.text, m.textColor.toInt(), m.font, m.textSize, m.gradient, m.gradientAngle, m.fx, _frame.value.toFloat() / fps, fps, if (m.stroke) m.strokeWidth else 0f, m.strokeColor.toInt(), m.textAlign, m.lineSpacing, m.letterSpacing)
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

    private fun saveToGallery(file: java.io.File, mime: String) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        runCatching {
            val resolver = getApplication<Application>().contentResolver
            val video = mime.startsWith("video/")
            val v = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (video) "Movies/VibeToon" else "Pictures/VibeToon")
            }
            val uri = resolver.insert(if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        }
    }

    /** Экспорт текущего кадра (плейхеда) в PNG (с прозрачным фоном) или JPEG (с фоном холста), полное разрешение холста. */
    fun exportStill(jpeg: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        viewModelScope.launch {
            _export.value = ExportUi.Running(0, 1)
            val (w, h) = captureDims()
            val symC = prerenderSymbols(_frame.value)
            val still = withContext(Dispatchers.Default) { buildSceneAt(_animation.value.settings.fps, symCache = symC)(_frame.value) }   // без призраков кальки
            val bytes = captureNow(still, !jpeg, w, h)
            if (bytes == null) { _export.value = ExportUi.Failed("Не удалось снять кадр"); return@launch }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-f${_frame.value}-$stamp.${if (jpeg) "jpg" else "png"}")
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val bmp = captureBitmap(bytes, w, h)
                file.outputStream().use { bmp.compress(if (jpeg) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 95, it) }
                bmp.recycle()
                saveToGallery(file, if (jpeg) "image/jpeg" else "image/png")
            }
            _export.value = ExportUi.Done(file)
        }
    }

    /** Экспорт анимированного GIF: [shortSide] - короткая сторона, [fps] 12/24/30, [transparent] - без фона холста. */
    fun exportGif(shortSide: Int, fps: Int, transparent: Boolean) {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        val (w, h, frames) = exportInfo(shortSide, fps)
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp.gif")
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            try {
                file.parentFile?.mkdirs()
                val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols())
                val out = java.io.BufferedOutputStream(file.outputStream())
                val enc = com.vibetoon.domain.export.GifEncoder(out, w, h, fps, transparent)
                for (i in 0 until frames) {
                    if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                    val scene = withContext(Dispatchers.Default) { sceneAt(i) }
                    val bytes = captureNow(scene, transparent, w, h) ?: error("Не удалось снять кадр $i")
                    withContext(Dispatchers.Default) { enc.addFrame(captureArgb(bytes, w, h)) }
                    _export.value = ExportUi.Running(i + 1, frames)
                }
                enc.finish(); out.close()
                withContext(Dispatchers.IO) { saveToGallery(file, "image/gif") }
                _export.value = ExportUi.Done(file)
            } catch (t: kotlinx.coroutines.CancellationException) {
                file.delete(); _export.value = ExportUi.Idle
            } catch (t: Throwable) {
                Log.e("VT/Export", "gif failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать GIF")
            }
            restoreAfterExport()
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
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp-png.zip")
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
            try {
                file.parentFile?.mkdirs()
                val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols())
                val zip = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(file.outputStream()))
                val base = safeName(_projectName.value)
                for (i in 0 until frames) {
                    if (cancel.get()) throw java.util.concurrent.CancellationException("cancel")
                    val scene = withContext(Dispatchers.Default) { sceneAt(i) }
                    val bytes = captureNow(scene, transparent, w, h) ?: error("Не удалось снять кадр $i")
                    withContext(Dispatchers.Default) {
                        val bmp = android.graphics.Bitmap.createBitmap(captureArgb(bytes, w, h), w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        zip.putNextEntry(java.util.zip.ZipEntry("%s_%05d.png".format(base, i + 1)))
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                        bmp.recycle()
                    }
                    _export.value = ExportUi.Running(i + 1, frames)
                }
                zip.close()
                _export.value = ExportUi.Done(file)
            } catch (t: kotlinx.coroutines.CancellationException) {
                file.delete(); _export.value = ExportUi.Idle
            } catch (t: Throwable) {
                Log.e("VT/Export", "png zip failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать ZIP")
            }
            restoreAfterExport()
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
        val skip = symbolHidden(null)
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
                _export.value = ExportUi.Done(file)
            } catch (t: Throwable) {
                Log.e("VT/Export", "psd failed", t)
                file.delete(); _export.value = ExportUi.Failed(t.message ?: "Не удалось записать PSD")
            } finally {
                tmpDir.deleteRecursively()
            }
            restoreAfterExport()
        }
    }

    /** Только аудио: все дорожки проекта сведены в один WAV (стерео 44.1 кГц) на всю длительность проекта. */
    fun exportAudioOnly() {
        val pid = projectId ?: return
        if (_export.value is ExportUi.Running) return
        val st = _animation.value.settings
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp.wav")
        _export.value = ExportUi.Running(0, 1)
        viewModelScope.launch {
            try {
                val mix = withContext(Dispatchers.Default) { audioEngine.mixdown(st.durationFrames, st.fps, ::clipOf) }
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
            saveNow()
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
        canvasW = newW; canvasH = newH
        meta = meta?.copy(width = newW, height = newH)
        if (psdRef != null) fit = ProjectMath.fit(psdW, psdH, canvasW, canvasH)
        resetCamera()
        resetSymbolImages()
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

    private fun warpsNow(): Map<Int, FloatArray> =
        warpLayers.associateWith { WarpMath.offsets(it, _frame.value, base, _animation.value, _fx.value) }

    /** Убирает деформацию выделенного слоя: сетка, форма привязки и ключи точек удаляются. */
    fun resetWarp() {
        val t = warpTarget() ?: return
        if (t !in warpLayers) return
        checkpoint("warp-reset", force = true)
        warpLayers = warpLayers - t
        val ids = (0 until WarpMath.POINTS).map { WarpMath.pointId(t, it) }.toSet()
        for (id in ids) { base.remove(id); override.remove(id) }
        _animation.value = _animation.value.copy(tracks = _animation.value.tracks.filterKeys { it !in ids })
        pushScene()
        markDirty()
        _state.value = UiState.Message("Warp слоя сброшен")
    }

    /** Углы единичного квадрата слоя на холсте: p00, p10, p01 (афинное отображение) -> (u, v) по точке холста. */
    private fun unitOf(q: FloatArray, cx: Float, cy: Float): Pair<Float, Float>? {
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val det = ex * fy - ey * fx
        if (kotlin.math.abs(det) < 1e-6f) return null
        val px = cx - q[0]; val py = cy - q[1]
        return ((px * fy - py * fx) / det) to ((ex * py - ey * px) / det)
    }

    private fun refreshWarpOverlay() {
        val t = warpTarget()
        if (!_warpTool.value || _boneMode.value || t == null || t !in warpLayers) {
            if (_warpOverlay.value.points.isNotEmpty()) _warpOverlay.value = WarpOverlay()
            return
        }
        val q = scene?.selectionQuads?.get(t) ?: run { _warpOverlay.value = WarpOverlay(); return }
        val off = WarpMath.offsets(t, _frame.value, base, _animation.value, _fx.value)
        val ex = q[2] - q[0]; val ey = q[3] - q[1]
        val fx = q[6] - q[0]; val fy = q[7] - q[1]
        val pts = FloatArray(WarpMath.POINTS * 2)
        for (k in 0 until WarpMath.POINTS) {
            val u = WarpMath.latticeX(k) + off[k * 2]
            val v = WarpMath.latticeY(k) + off[k * 2 + 1]
            val sp_ = toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy)
            pts[k * 2] = sp_.first
            pts[k * 2 + 1] = sp_.second
        }
        _warpOverlay.value = WarpOverlay(pts.toList())
    }

    private fun warpDown(sx: Float, sy: Float): Boolean {
        val t = warpTarget() ?: return false
        if (t !in warpLayers || _playing.value) return false
        val p = _warpOverlay.value.points
        if (p.size < WarpMath.POINTS * 2) return false
        var best = -1
        var bestD = 44f
        for (k in 0 until WarpMath.POINTS) {
            val d = kotlin.math.hypot(sx - p[k * 2], sy - p[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        grab = 20 + best
        return true
    }

    private fun warpMove(sx: Float, sy: Float) {
        val k = grab - 20
        val t = warpTarget() ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        val ox = (u - WarpMath.latticeX(k)).coerceIn(-1.5f, 1.5f)
        val oy = (v - WarpMath.latticeY(k)).coerceIn(-1.5f, 1.5f)
        checkpoint("warp$t-$k")
        val id = WarpMath.pointId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)   // FX выкл: базовая форма (Bind Pose), ключи не создаются
        } else {
            val bind = base[id]
            // FX вкл: смещение записывается ключом на текущем кадре (дельта относительно формы привязки)
            _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
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
        val t = warpTarget() ?: return
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
        val t = warpTarget()
        val q = if (t != null) scene?.selectionQuads?.get(t) else null
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
            val sp_ = toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy)
            pts[k * 2] = sp_.first
            pts[k * 2 + 1] = sp_.second
        }
        _perspOverlay.value = pts
    }

    private fun perspDown(sx: Float, sy: Float): Boolean {
        swallowTap = false
        val t = warpTarget() ?: return false
        if (t !in perspLayers || _playing.value) return false
        val p = _perspOverlay.value
        if (p.size < 8) return false
        var best = -1
        var bestD = 48f
        for (k in 0 until 4) {
            val d = kotlin.math.hypot(sx - p[k * 2], sy - p[k * 2 + 1])
            if (d < bestD) { bestD = d; best = k }
        }
        if (best < 0) return false
        grab = PERSP_GRAB + best
        swallowTap = true
        return true
    }

    private fun perspMove(sx: Float, sy: Float) {
        val k = grab - PERSP_GRAB
        val t = warpTarget() ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        val ox = (u - PerspectiveMath.cornerX(k)).coerceIn(-1.5f, 1.5f)
        val oy = (v - PerspectiveMath.cornerY(k)).coerceIn(-1.5f, 1.5f)
        checkpoint("persp$t-$k")
        val id = PerspectiveMath.pointId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)
        } else {
            val bind = base[id]
            _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
        }
        pushScene()
        markDirty()
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
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
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
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        // длина стороны слоя на экране -> радиус кисти в пикселях картинки
        val ex = toScreenX(q[2], q[3]) - toScreenX(q[0], q[1]); val ey = toScreenY(q[2], q[3]) - toScreenY(q[0], q[1])
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

    private var stroker: DabStroker? = null
    private var strokeLayer = -1
    private var strokeSize = intArrayOf(0, 0)
    private val paintSizes = java.util.concurrent.ConcurrentHashMap<Int, IntArray>()
    private val paintDirty: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet<Int>())
    @Volatile private var stylusDown = false
    @Volatile private var stylusLast = 0L

    fun setPaintTool(t: PaintTool?) {
        if (_paintTool.value == t) return
        if (stroker != null) paintCancel()
        _paintTool.value = t
        refreshPivotOverlay()
        if (t != null) {
            paintTargetId()?.let { requestPaintSize(it) }
            requestBrushPreviews()
            val auto = if (prefs.autoPixelEdit) autoPixelTarget(t) else null
            _state.value = UiState.Message(auto ?: when {
                selectedVectorId() != null && t == PaintTool.ERASER -> if (prefs.onlySelectedPath) "Ластик по контурам: только выделенные векторные слои" else "Ластик по контурам: все видимые векторные слои"
                selectedVectorId() != null -> "Кисть на векторном слое: рисует контуры"
                t == PaintTool.BRUSH -> "Кисть: рисуйте на слое-картинке (или создайте «Новый слой»)"
                else -> "Ластик: стирает пиксели слоя-картинки"
            })
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
        if (_brushMenu.value) requestBrushPreviews()
    }
    fun closeBrushMenu() { _brushMenu.value = false }

    /** Пресеты для меню активного инструмента: кисти или ластики. */
    fun presetsForTool(): List<BrushPreset> = if (_paintTool.value == PaintTool.ERASER) BrushPresets.erasers else BrushPresets.brushes

    /** Выбор пресета: размер, форма, жёсткость, режим, зерно берутся из него; цвет остаётся текущим. */
    fun selectPreset(p: BrushPreset) {
        if (p.params.mode == StrokeMode.ERASE) _eraser.value = p.params
        else { _paintTool.value = PaintTool.BRUSH; _brush.value = p.params.copy(color = _brush.value.color) }
        _brushMenu.value = false
    }

    fun requestBrushPreviews() {
        val view = RenderHost.view ?: return
        val w = 256; val h = 64
        for (pr in BrushPresets.all()) {
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

    /** Перо: пока оно касается экрана или только что касалось, касания пальцем игнорируются (ладонь не рисует). */
    fun paintStylus(down: Boolean) { stylusDown = down; stylusLast = SystemClock.uptimeMillis() }
    fun paintPalmBlocked(): Boolean = stylusDown || SystemClock.uptimeMillis() - stylusLast < 1200

    /** Слой, на котором рисуем: выбранная картинка (в том числе созданная кнопкой «Новый слой») или слой PSD; текст и видео - нет. */
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
        val ex = toScreenX(q[2], q[3]) - toScreenX(q[0], q[1]); val ey = toScreenY(q[2], q[3]) - toScreenY(q[0], q[1])
        val scale = kotlin.math.hypot(ex, ey) / w
        _paintCursor.value = floatArrayOf(sx, sy, (params.size / 2f * scale).coerceAtLeast(2f))
    }

    /** Начало штриха; false - рисовать нечем (нет подходящего слоя) и жест идёт обычным путём. */
    fun paintDown(sx: Float, sy: Float, pressure: Float): Boolean {
        swallowTap = false
        if (_paintTool.value == null || _playing.value) return false
        selectedVectorId()?.let { vid ->
            if (isLockedId(vid)) { lockedNotice(); return false }
            if (_paintTool.value == PaintTool.ERASER) return vecEraseDown(sx, sy)
            return vectorDown(vid, sx, sy)
        }
        val id = paintTargetId() ?: run {
            _state.value = UiState.Message("Рисовать можно на слое-картинке: выберите его или создайте «Новый слой»")
            return false
        }
        val size = paintSizes[id] ?: run {
            requestPaintSize(id)
            _state.value = UiState.Message("Слой готовится, повторите мазок")
            return false
        }
        val q = scene?.selectionQuads?.get(id) ?: return false
        val view = RenderHost.view ?: return false
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
        val params = paintParams()
        strokeLayer = id
        strokeSize = size
        val st = DabStroker(params)
        stroker = st
        val c = params.color
        view.paintBegin(
            id, params.mode.gl, params.opacity, layerProps[id]?.alphaLock == true,
            ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, params.shape.gl, params.hardness,
            params.grain.gl, params.grainMode.gl, params.grainScale, params.grainContrast, params.roundness, params.curve, GrainMath.texture(params.grain)
        ) { }
        sendDabs(st.begin(p.first * size[0], p.second * size[1], pressure))
        updatePaintCursor(sx, sy, q, params)
        swallowTap = true
        return true
    }

    /** Следующая (в том числе историческая) точка штриха. */
    fun paintMove(sx: Float, sy: Float, pressure: Float) {
        if (vecLayer >= 0) { vectorMove(sx, sy); return }
        if (vecErase != null) { vecEraseMove(sx, sy); return }
        val st = stroker ?: return
        val q = scene?.selectionQuads?.get(strokeLayer) ?: return
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        sendDabs(st.add(p.first * strokeSize[0], p.second * strokeSize[1], pressure))
        updatePaintCursor(sx, sy, q, paintParams())
    }

    /** Конец штриха: слой запекается, "до" и "после" уходят в Undo (сжатие в фоне), слой помечается на сохранение. */
    fun paintUp() {
        if (vecLayer >= 0) { vectorUp(); return }
        if (vecErase != null) { vecEraseUp(); return }
        val st = stroker ?: return
        stroker = null
        _paintCursor.value = null
        val layer = strokeLayer
        val cellAtStroke = cellShown[layer]
        sendDabs(st.end())
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
        if (vecLayer >= 0) { vectorCancel(); return }
        if (vecErase != null) { vecEraseCancel(); return }
        if (stroker == null) return
        stroker = null
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

    /** Лимит памяти Undo: самые старые штрихи вытесняются вместе со всеми записями под ними. */
    private fun enforcePaintBudget() {
        val ops = undoStack.mapNotNull { it.paintOp } + redoStack.mapNotNull { it.paintOp }
        val n = PaintBudget.overflow(ops.map { it.sizeBytes })
        if (n == 0) return
        for (i in 0 until n) ops[i].evict()
        val lastEvicted = undoStack.indexOfLast { it.paintOp?.evicted == true }
        repeat(lastEvicted + 1) { undoStack.removeFirstOrNull() }
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

    /** Сохраняет изменённые слои (PNG) из пикселей текстуры; вызывается при каждом автосохранении и при выходе из проекта. */
    private suspend fun flushPaintSaves() {
        val pid = projectId ?: return
        val ids = synchronized(paintDirty) { val c = paintDirty.toList(); paintDirty.clear(); c }
        if (ids.isEmpty()) return
        val view = RenderHost.view ?: run { paintDirty.addAll(ids); return }
        for (id in ids) {
            val file = paintFile(pid, id) ?: continue
            val bytes = kotlinx.coroutines.withTimeoutOrNull(4000) {
                kotlinx.coroutines.suspendCancellableCoroutine<ByteArray?> { c ->
                    view.layerPixels(id) { if (c.isActive) c.resumeWith(Result.success(it)) }
                }
            }
            if (bytes == null || bytes.size <= 8) { paintDirty += id; continue }
            withContext(Dispatchers.IO) { writeLayerPng(bytes, file) }
        }
    }

    /** Пиксели текстуры (8 байт размера + RGBA premultiplied) -> PNG (ARGB без premultiply). */
    private fun writeLayerPng(bytes: ByteArray, file: java.io.File) {
        fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        val w = i32(0); val h = i32(4)
        if (w <= 0 || h <= 0 || bytes.size < 8 + w * h * 4) return
        val px = IntArray(w * h)
        for (k in 0 until w * h) {
            val o = 8 + k * 4
            val a = bytes[o + 3].toInt() and 0xFF
            var r = bytes[o].toInt() and 0xFF; var g = bytes[o + 1].toInt() and 0xFF; var b = bytes[o + 2].toInt() and 0xFF
            if (a in 1..254) { r = minOf(255, r * 255 / a); g = minOf(255, g * 255 / a); b = minOf(255, b * 255 / a) }
            px[k] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        runCatching { file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        bmp.recycle()
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
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    val fileName = "m$id.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmap(id, bmp)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    MediaItem(id, MediaKind.IMAGE, "Рисунок $count", "assets/$fileName", bw, bh, 0f, 0f, canvasW.toFloat(), canvasH.toFloat())
                }
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
        val count = media.count { it.name.startsWith(prefix) } + 1
        viewModelScope.launch {
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
                    if (fillArgb != 0) bmp.eraseColor(fillArgb)
                    val fileName = "m$id.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmap(id, bmp)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    MediaItem(id, MediaKind.IMAGE, "$prefix $count", "assets/$fileName", bw, bh, 0f, 0f, canvasW.toFloat(), canvasH.toFloat(),
                        cells = if (animated) listOf(FrameCell(0, "assets/$fileName")) else emptyList(), vector = vector, background = atBottom)
                }
                checkpoint("add", force = true)
                if (atBottom) media.add(0, item) else media += item
                rebuildLayers(); pushScene(); selectLayer(item.id); markDirty()
                if (lock) toggleLocked(item.id)
                _state.value = UiState.Message(when {
                    vector -> "Векторный слой «${item.name}»: рисуйте кистью (путь, линия, прямоугольник, эллипс) или добавляйте «Фигуры»"
                    animated -> "Анимационный слой «${item.name}»: рисуйте, «Добавить пустой кадр» ставит следующий рисунок"
                    lock -> "Фон «${item.name}» залит цветом фона проекта и заблокирован (замок снимается в списке слоёв)" + if (psdRef != null) ". В проекте с PSD он ложится над слоями PSD" else ""
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
        media += MediaItem(id, MediaKind.GROUP, "Группа $n", "")
        rebuildLayers(); pushScene(); selectLayer(id); markDirty()
        _state.value = UiState.Message("Пустая группа «Группа $n»: перетащите в неё слои ручкой ≡")
    }

    // ---------------------------------------------------------------- Auto Pixel Edit Mode

    /** Можно ли рисовать пикселями по слою [id] (как [paintTargetId], но для любого слоя). */
    private fun isPaintable(id: Int): Boolean {
        if (isLockedId(id) || id in hiddenIds()) return false
        val info = layers.firstOrNull { it.id == id } ?: return false
        if (info.kind != LayerKind.LAYER) return false
        val m = media.firstOrNull { it.id == id }
        return if (m != null) m.kind == MediaKind.IMAGE && !m.vector else id < MEDIA_ID_BASE
    }

    /**
     * Выбор кисти/ластика при включённом Auto Pixel Edit Mode: если выделенный слой не годится для рисования
     * (группа, текст, символ, камера, ничего не выделено), выбирается верхний видимый незапертый слой-картинка,
     * а если такого нет - создаётся новый («Рисунок N»; при Vector Workflow для кисти - векторный слой).
     * Векторный слой не трогаем: на нём кисть рисует контуры, ластик стирает контуры. Возвращает текст подсказки или null.
     */
    private fun autoPixelTarget(t: PaintTool): String? {
        if (projectId == null || _playing.value) return null
        if (selectedVectorId() != null) return null
        if (paintTargetId() != null) return null
        val wantVector = prefs.vectorWorkflow && t == PaintTool.BRUSH
        val candidates = layers.asReversed().map { it.id }
        val pick = if (wantVector) candidates.firstOrNull { isVectorLayer(it) && !isLockedId(it) && it !in hiddenIds() }
            else candidates.firstOrNull { isPaintable(it) }
        if (pick != null) {
            selectLayer(pick)
            requestPaintSize(pick)
            val name = layers.firstOrNull { it.id == pick }?.name ?: ""
            return "Авто-режим: рисуем на слое «$name»"
        }
        if (t == PaintTool.ERASER) return "Стирать нечего: нет слоя-картинки"
        if (wantVector) addVectorLayer() else addPaintLayer()
        return if (wantVector) "Авто-режим: создан векторный слой" else "Авто-режим: создан новый слой для рисования"
    }

    // ---------------------------------------------------------------- ластик и палец по контурам векторных слоёв

    /** Слои-контуры, которые трогают ластик/палец: только выделенные (Only Erase/Smudge Selected Path Layers) или все видимые. */
    private fun pathTargets(): List<Int> {
        val hidden = hiddenIds()
        val ok = { id: Int -> isVectorLayer(id) && !isLockedId(id) && id !in hidden && scene?.selectionQuads?.containsKey(id) == true }
        return if (prefs.onlySelectedPath) _selection.value.filter(ok) else layers.map { it.id }.filter(ok)
    }

    /** Точка экрана -> пиксели холста векторного слоя [id] (слой занимает весь холст, но может быть сдвинут). */
    private fun vecPoint(id: Int, sx: Float, sy: Float): Pair<Float, Float>? {
        val q = scene?.selectionQuads?.get(id) ?: return null
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return null
        return p.first * canvasW to p.second * canvasH
    }

    /** Радиус в пикселях холста слоя для размера [size] из настроек инструмента (размер - в пикселях холста). */
    private fun vecRadius(size: Float) = (size / 2f).coerceAtLeast(2f)

    private var vecErase: HashMap<Int, ArrayList<Float>>? = null
    private var vecErasePreviewAt = 0L

    private fun vecEraseDown(sx: Float, sy: Float): Boolean {
        val targets = pathTargets()
        if (targets.isEmpty()) { _state.value = UiState.Message(if (prefs.onlySelectedPath) "Выделите векторные слои: включено «Стирать только выбранные слои-контуры»" else "Нет видимых незапертых векторных слоёв"); return false }
        val m = HashMap<Int, ArrayList<Float>>()
        for (id in targets) vecPoint(id, sx, sy)?.let { (x, y) -> m[id] = arrayListOf(x, y) }
        vecErase = m
        swallowTap = true
        return true
    }

    private fun vecEraseMove(sx: Float, sy: Float) {
        val m = vecErase ?: return
        for ((id, pts) in m) vecPoint(id, sx, sy)?.let { (x, y) -> pts += x; pts += y }
        val now = SystemClock.uptimeMillis()
        if (now - vecErasePreviewAt < 90) return
        vecErasePreviewAt = now
        val r = vecRadius(_eraser.value.size)
        for ((id, pts) in m) {
            val item = media.firstOrNull { it.id == id } ?: continue
            val next = com.vibetoon.domain.vector.VectorMath.erase(item.vectors, pts, r)
            if (next != item.vectors) renderVector(item.copy(vectors = next), save = false)   // предпросмотр стирания
        }
    }

    private fun vecEraseUp() {
        val m = vecErase ?: return
        vecErase = null
        val r = vecRadius(_eraser.value.size)
        var changed = 0
        var first = true
        for ((id, pts) in m) {
            val i = media.indexOfFirst { it.id == id }
            if (i < 0) continue
            val next = com.vibetoon.domain.vector.VectorMath.erase(media[i].vectors, pts, r)
            if (next == media[i].vectors) continue
            if (first) { checkpoint("vec-erase", force = true); first = false }   // один шаг Undo на весь мазок
            media[i] = media[i].copy(vectors = next)
            renderVector(media[i], save = true)
            invalidateThumb(id)
            changed++
        }
        if (changed > 0) markDirty()
    }

    private fun vecEraseCancel() {
        val m = vecErase ?: return
        vecErase = null
        for (id in m.keys) media.firstOrNull { it.id == id }?.let { renderVector(it, save = false) }
    }

    private var vecSmudgeLast: HashMap<Int, Pair<Float, Float>>? = null
    private var vecSmudgeWork: HashMap<Int, List<com.vibetoon.domain.vector.VectorShape>>? = null

    private fun vecSmudgeDown(sx: Float, sy: Float): Boolean {
        val targets = pathTargets()
        if (targets.isEmpty()) { _state.value = UiState.Message(if (prefs.onlySelectedPath) "Выделите векторные слои: включено «Размазывать только выбранные слои-контуры»" else "Нет видимых незапертых векторных слоёв"); return false }
        val last = HashMap<Int, Pair<Float, Float>>()
        val work = HashMap<Int, List<com.vibetoon.domain.vector.VectorShape>>()
        for (id in targets) {
            val p = vecPoint(id, sx, sy) ?: continue
            last[id] = p
            work[id] = media.firstOrNull { it.id == id }?.vectors ?: continue
        }
        vecSmudgeLast = last; vecSmudgeWork = work
        rasterMode = 5
        swallowTap = true
        return true
    }

    private fun vecSmudgeMove(sx: Float, sy: Float) {
        val last = vecSmudgeLast ?: return
        val work = vecSmudgeWork ?: return
        val r = vecRadius(_smudgeParams.value.size * 1.5f)
        val strength = _smudgeParams.value.strength
        for (id in work.keys.toList()) {
            val p = vecPoint(id, sx, sy) ?: continue
            val (lx, ly) = last[id] ?: continue
            work[id] = com.vibetoon.domain.vector.VectorMath.smudge(work.getValue(id), lx, ly, p.first - lx, p.second - ly, r, strength)
            last[id] = p
        }
        val now = SystemClock.uptimeMillis()
        if (now - vecErasePreviewAt < 70) return
        vecErasePreviewAt = now
        for ((id, list) in work) media.firstOrNull { it.id == id }?.let { renderVector(it.copy(vectors = list), save = false) }
    }

    private fun vecSmudgeUp() {
        val work = vecSmudgeWork ?: return
        vecSmudgeWork = null; vecSmudgeLast = null
        var first = true
        for ((id, list) in work) {
            val i = media.indexOfFirst { it.id == id }
            if (i < 0 || media[i].vectors == list) continue
            if (first) { checkpoint("vec-smudge", force = true); first = false }
            media[i] = media[i].copy(vectors = list)
            renderVector(media[i], save = true)
            invalidateThumb(id)
        }
        if (!first) markDirty()
    }

    // ---------------------------------------------------------------- Библиотека ассетов (Sprint 6.9, основа)

    /** Элемент библиотеки ассетов проекта: определение символа (префаб) или картинка. */
    data class AssetEntry(val id: Int, val name: String, val kind: String, val uses: Int)

    /** Ассеты текущего проекта: символы (сколько экземпляров на сцене) и импортированные картинки. */
    fun assetLibrary(): List<AssetEntry> {
        val defs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.map { d ->
            AssetEntry(d.id, d.name, "symbol", media.count { it.kind == MediaKind.SYMBOL && it.symRef == d.id })
        }
        val imgs = media.filter { it.kind == MediaKind.IMAGE && !it.vector && it.cells.isEmpty() && !it.background }.map { AssetEntry(it.id, it.name, "image", 1) }
        return defs + imgs
    }

    /** «Вставить экземпляр»: новый экземпляр символа-префаба по центру холста (правка определения меняет все экземпляры). */
    fun insertSymbolInstance(defId: Int) {
        if (projectId == null || _playing.value) return
        if (_editingSymbol.value != null) { _state.value = UiState.Message("Вставляйте экземпляры на главной сцене: выйдите из редактирования символа"); return }
        val def = media.firstOrNull { it.id == defId && it.kind == MediaKind.GROUP && it.symDur > 0 } ?: return
        val iid = nextMediaId()
        checkpoint("add", force = true)
        val n = media.count { it.kind == MediaKind.SYMBOL && it.symRef == defId } + 1
        media += MediaItem(iid, MediaKind.SYMBOL, "${def.name} #$n", "", x = 0f, y = 0f, w = canvasW.toFloat(), h = canvasH.toFloat(), symRef = defId)
        rebuildLayers(); pushScene(); selectLayer(iid); updateSymbols(_frame.value); markDirty()
        _state.value = UiState.Message("Экземпляр «${def.name}» добавлен")
    }

    /** Картинку из библиотеки - в символ (префаб), чтобы переиспользовать экземплярами. */
    fun assetToSymbol(imageId: Int) {
        if (media.none { it.id == imageId && it.kind == MediaKind.IMAGE }) return
        createSymbol(imageId)
    }

    // ---------------------------------------------------------------- векторный слой

    private val _vecKind = MutableStateFlow(com.vibetoon.domain.vector.VecKind.PATH)
    /** Что рисует жест на векторном слое: путь, линия, прямоугольник, эллипс. */
    val vecKind: StateFlow<com.vibetoon.domain.vector.VecKind> = _vecKind.asStateFlow()
    private val _vecFill = MutableStateFlow(false)
    val vecFill: StateFlow<Boolean> = _vecFill.asStateFlow()
    fun setVecKind(k: com.vibetoon.domain.vector.VecKind) { _vecKind.value = k }
    fun toggleVecFill() { _vecFill.value = !_vecFill.value }

    fun isVectorLayer(id: Int) = media.any { it.id == id && it.vector }
    private fun selectedVectorId(): Int? = _selected.value?.takeIf { isVectorLayer(it) }
    /** Число примитивов выбранного векторного слоя (для кнопок «Убрать последнюю»/«Очистить»). */
    fun vectorCount(id: Int) = media.firstOrNull { it.id == id }?.vectors?.size ?: 0

    private var vecLayer = -1
    private val vecPts = ArrayList<Float>()
    private var vecPreviewAt = 0L
    private val vecGen = HashMap<Int, Int>()

    private fun vecStroke(): Long = (brush.value.color.toInt() or (0xFF shl 24)).toLong() and 0xFFFFFFFFL

    private fun vectorDown(id: Int, sx: Float, sy: Float): Boolean {
        val q = scene?.selectionQuads?.get(id) ?: return false
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
        vecLayer = id
        vecPts.clear(); vecPts += p.first * canvasW; vecPts += p.second * canvasH
        swallowTap = true
        return true
    }

    private fun vectorMove(sx: Float, sy: Float) {
        val q = scene?.selectionQuads?.get(vecLayer) ?: return
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        vecPts += p.first * canvasW; vecPts += p.second * canvasH
        val now = SystemClock.uptimeMillis()
        if (now - vecPreviewAt < 70) return
        vecPreviewAt = now
        val m = media.firstOrNull { it.id == vecLayer } ?: return
        val tmp = com.vibetoon.domain.vector.VectorMath.fromGesture(_vecKind.value, vecPts.toList(), vecStroke(), if (_vecFill.value) vecStroke() and 0x00FFFFFFL or 0x66000000L else 0L, brush.value.size)
        renderVector(m, save = false, extra = tmp)   // резиновая линия: примитив виден во время жеста
    }

    private fun vectorUp() {
        val id = vecLayer
        vecLayer = -1
        val m = media.firstOrNull { it.id == id } ?: return
        val shape = com.vibetoon.domain.vector.VectorMath.fromGesture(
            _vecKind.value, vecPts.toList(), vecStroke(), if (_vecFill.value) vecStroke() and 0x00FFFFFFL or 0x66000000L else 0L, brush.value.size
        )
        vecPts.clear()
        if (shape == null) { renderVector(m, save = false); return }   // тап без движения: убрать резиновую линию
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, shape))
    }

    private fun vectorCancel() {
        val id = vecLayer
        vecLayer = -1; vecPts.clear()
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
        val m = media.firstOrNull { it.id == id } ?: return
        if (m.vectors.isEmpty()) return
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withoutLast(m.vectors))
    }

    fun clearVectors() {
        val id = selectedVectorId() ?: return
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
        viewModelScope.launch {
            try {
                withContext(decodeDispatcher) {
                    if (vecGen[m.id] != gen) return@withContext   // за время ожидания пришла более новая правка
                    val bmp = com.vibetoon.data.VectorRaster.render(shapes, m.srcWidth, m.srcHeight, cw, ch)
                    if (vecGen[m.id] == gen) {
                        if (save) projects.mediaFile(pid, m.file.removePrefix("assets/")).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        RenderNative.uploadBitmap(m.id, bmp)
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
                    val ok = RenderNative.uploadBitmap(id, bmp)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать фигуру в графике")
                    MediaItem(id, MediaKind.IMAGE, "Фигура: ${kind.title}", "assets/$fileName", n, n,
                        (canvasW - side) / 2f + dxOverride, (canvasH - side) / 2f, side, side)
                }
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
    fun setSelMode(m: SelMode) { _selMode.value = m }

    fun rasterToolActive(): Boolean = _rasterTool.value != null && !_playing.value

    fun setRasterTool(t: RasterTool?) {
        if (_rasterTool.value == t) return
        if (_rasterTool.value == RasterTool.SELECT) clearSelection()
        if (_rasterTool.value == RasterTool.PATH) penCancel()
        rasterCancel()
        _rasterTool.value = t
        refreshPivotOverlay()
        if (t != null) {
            paintTargetId()?.let { requestPaintSize(it) }
            _state.value = UiState.Message(when (t) {
                RasterTool.FILL -> "Заливка: коснитесь замкнутой области (порог, расширение и зазор - в панели над холстом)"
                RasterTool.PIPETTE -> "Пипетка: коснитесь холста, чтобы взять цвет"
                RasterTool.SELECT -> "Выделение: обведите область или коснитесь палочкой; внутри выделения тяните, чтобы перенести"
                RasterTool.SMUDGE -> "Смазка: проведите по рисунку слоя-картинки"
                RasterTool.PATH -> "Перо: тапами ставьте точки на векторном слое, «Готово» завершает контур, тап по первой точке замыкает"
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
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return null
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
        if (t == RasterTool.SMUDGE && selectedVectorId() != null) return vecSmudgeDown(sx, sy)
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
        when (rasterMode) { 1 -> selDrawMove(sx, sy); 2 -> selMoveMove(sx, sy); 3 -> pxSmudgeMove(sx, sy); 4 -> vecNodeMove(sx, sy); 5 -> vecSmudgeMove(sx, sy) }
    }

    fun rasterUp() {
        when (rasterMode) { 1 -> selDrawUp(); 2 -> selMoveUp(); 3 -> pxSmudgeUp(); 4 -> vecNodeUp(); 5 -> vecSmudgeUp() }
        rasterMode = 0
    }

    /** Жест прерван вторым пальцем (камера). */
    fun rasterCancel() {
        when (rasterMode) { 1 -> { _toolDrag.value = null; selPts.clear() }; 2 -> { selShiftX = 0f; selShiftY = 0f; refreshSelOverlay() }; 3 -> pxSmudgeUp(); 5 -> vecSmudgeUp() }
        rasterMode = 0
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
    private var selMoveStartL: Pair<Float, Float>? = null
    private var selMoveStartS = 0f to 0f
    private var selShiftX = 0f
    private var selShiftY = 0f

    private fun selDown(id: Int, sx: Float, sy: Float): Boolean {
        val lp0 = layerPoint(id, sx, sy) ?: return false
        if (_selMode.value == SelMode.WAND) {
            val thr = _fillParams.value.threshold
            viewModelScope.launch {
                val lp = readLayer(id) ?: return@launch
                val mask = withContext(Dispatchers.Default) {
                    com.vibetoon.domain.raster.RasterMath.wandMask(lp.bytes, 8, lp.w, lp.h, lp0.first.toInt(), lp0.second.toInt(), thr)
                }
                setSelection(Sel(id, lp.w, lp.h, mask))
            }
            return true
        }
        val s = sel
        if (s != null && s.id == id) {
            val x = lp0.first.toInt(); val y = lp0.second.toInt()
            if (x in 0 until s.w && y in 0 until s.h && s.mask[y * s.w + x].toInt() != 0) {
                // тянем внутри выделения: переносим его содержимое
                rasterMode = 2; selMoveStartL = lp0; selMoveStartS = sx to sy; selShiftX = 0f; selShiftY = 0f
                return true
            }
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
                if (kotlin.math.hypot(sx - selPts[n - 2], sy - selPts[n - 1]) >= 4f) {
                    selPts += sx; selPts += sy; selLayerPts += lp.first; selLayerPts += lp.second
                    _toolDrag.value = ToolDrag(2, selPts.toFloatArray())
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
        selPts.clear(); selLayerPts.clear()
        _toolDrag.value = null
        // касание без протяжки вне выделения снимает его
        val moved = screen.size >= 4 && kotlin.math.hypot(screen[screen.size - 2] - screen[0], screen[screen.size - 1] - screen[1]) > 6f
        if (!moved) { clearSelection(); return }
        val size = paintSizes[id] ?: return
        viewModelScope.launch {
            val mask = withContext(Dispatchers.Default) {
                when (mode) {
                    SelMode.RECT -> com.vibetoon.domain.raster.RasterMath.rectMask(size[0], size[1], pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
                    SelMode.OVAL -> com.vibetoon.domain.raster.RasterMath.ovalMask(size[0], size[1], pts[0], pts[1], pts[pts.size - 2], pts[pts.size - 1])
                    else -> com.vibetoon.domain.raster.RasterMath.polygonMask(size[0], size[1], pts)
                }
            }
            if (com.vibetoon.domain.raster.RasterMath.count(mask) == 0) clearSelection() else setSelection(Sel(id, size[0], size[1], mask))
        }
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
        viewModelScope.launch {
            val lp = readLayer(s.id) ?: return@launch
            val (res, shifted) = withContext(Dispatchers.Default) {
                val r = com.vibetoon.domain.raster.RasterMath.moveResult(lp.bytes, 8, lp.w, lp.h, s.mask, dx, dy)
                val m = ByteArray(s.mask.size)
                for (y in 0 until s.h) for (x in 0 until s.w) if (s.mask[y * s.w + x].toInt() != 0) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until s.w && ny in 0 until s.h) m[ny * s.w + nx] = 1
                }
                r to m
            }
            if (res != null) commitRaster(s.id, res.rect, res.before, res.after)
            setSelection(Sel(s.id, s.w, s.h, shifted))
        }
    }

    private var lastMoveSx = 0f
    private var lastMoveSy = 0f

    private fun setSelection(s: Sel?) {
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
        if (s == null || bmp == null || _rasterTool.value != RasterTool.SELECT) { if (_selOverlay.value.bmp != null) _selOverlay.value = SelOverlay(); return }
        val q = scene?.selectionQuads?.get(s.id) ?: run { clearSelection(); return }   // слой удалён - выделение снимается
        val c = floatArrayOf(
            toScreenX(q[0], q[1]) + selShiftX, toScreenY(q[0], q[1]) + selShiftY,
            toScreenX(q[2], q[3]) + selShiftX, toScreenY(q[2], q[3]) + selShiftY,
            toScreenX(q[6], q[7]) + selShiftX, toScreenY(q[6], q[7]) + selShiftY
        )
        _selOverlay.value = SelOverlay(bmp, c)
    }

    fun clearSelection() { sel = null; selBmp = null; _hasSel.value = false; _selOverlay.value = SelOverlay(); _toolDrag.value = null }

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
        viewModelScope.launch {
            val lp = readLayer(s.id) ?: return@launch
            val res = withContext(Dispatchers.Default) { com.vibetoon.domain.raster.RasterMath.clearResult(lp.bytes, 8, lp.w, lp.h, s.mask) }
            if (res == null) _state.value = UiState.Message("Под выделением пусто") else commitRaster(s.id, res.rect, res.before, res.after)
        }
    }

    /**
     * «В новый слой»: пиксели под выделением вырезаются из слоя и становятся новым слоем-картинкой того же размера прямо над исходным
     * (рука, глаз и т.п. отделяются от общего рисунка для привязки к кости). Вырезание попадает в Undo исходного слоя.
     */
    fun selectionToNewLayer() {
        val s = sel ?: return
        val pid = projectId ?: return
        val srcIdx = media.indexOfFirst { it.id == s.id }
        if (srcIdx < 0) { _state.value = UiState.Message("«В новый слой» работает на слое-картинке (не на слое PSD)"); return }
        val src = media[srcIdx]
        val newId = nextMediaId()
        viewModelScope.launch {
            val lp = readLayer(s.id) ?: return@launch
            val parts = withContext(Dispatchers.Default) {
                val b = com.vibetoon.domain.raster.RasterMath.bounds(s.mask, s.w, s.h) ?: return@withContext null
                val px = IntArray(s.w * s.h)
                var any = false
                for (k in 0 until s.w * s.h) {
                    if (s.mask[k].toInt() == 0) continue
                    val o = 8 + k * 4
                    val a = lp.bytes[o + 3].toInt() and 0xFF
                    if (a == 0) continue
                    any = true
                    var r = lp.bytes[o].toInt() and 0xFF; var g = lp.bytes[o + 1].toInt() and 0xFF; var bl = lp.bytes[o + 2].toInt() and 0xFF
                    if (a < 255) { r = minOf(255, r * 255 / a); g = minOf(255, g * 255 / a); bl = minOf(255, bl * 255 / a) }
                    px[k] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                }
                if (!any) null else Triple(px, b, com.vibetoon.domain.raster.RasterMath.clearResult(lp.bytes, 8, lp.w, lp.h, s.mask))
            }
            if (parts == null) { _state.value = UiState.Message("Под выделением пусто"); return@launch }
            val (px, _, cut) = parts
            try {
                val item = withContext(decodeDispatcher) {
                    val bmp = android.graphics.Bitmap.createBitmap(px, s.w, s.h, android.graphics.Bitmap.Config.ARGB_8888)
                    val fileName = "m$newId.png"
                    projects.mediaFile(pid, fileName).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val ok = RenderNative.uploadBitmap(newId, bmp)
                    bmp.recycle()
                    if (!ok) error("Не удалось создать слой в графике")
                    val n = media.count { it.name.startsWith("Часть") } + 1
                    MediaItem(newId, MediaKind.IMAGE, "Часть $n", "assets/$fileName", s.w, s.h, src.x, src.y, src.w, src.h)
                }
                checkpoint("add", force = true)
                media.add((media.indexOfFirst { it.id == s.id } + 1).coerceAtMost(media.size), item)
                rebuildLayers(); pushScene()
                if (cut != null) commitRaster(s.id, cut.rect, cut.before, cut.after)
                clearSelection()
                selectLayer(newId)
                contentBoxScheduled(newId)
                markDirty()
                delay(400)
                requestPaintSize(newId)
                _state.value = UiState.Message("Выделение вынесено в слой «${item.name}»: теперь его можно привязать к кости")
            } catch (t: Throwable) {
                Log.e("VT/Sel", "to new layer failed", t)
                _state.value = UiState.Error(t.message ?: "Не удалось создать слой")
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

    // ---- правка узлов готовых векторных примитивов: тап выбирает узел, перетаскивание двигает, кнопки удаляют ----

    private var nodeShape = -1
    private var nodePt = -1
    private var nodeSelId = -1

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

    private fun vecNodeDown(id: Int, sx: Float, sy: Float): Boolean {
        val hit = vecNodeAt(id, sx, sy)
        if (hit == null) { nodeShape = -1; nodePt = -1; refreshPenOverlay(); return false }
        nodeShape = hit.first; nodePt = hit.second; nodeSelId = id
        checkpoint("vecnode", force = true)
        rasterMode = 4
        swallowTap = true
        refreshPenOverlay()
        return true
    }

    private fun vecNodeMove(sx: Float, sy: Float) {
        val id = nodeSelId
        val i = media.indexOfFirst { it.id == id }
        if (i < 0 || nodeShape < 0) return
        val q = scene?.selectionQuads?.get(id) ?: return
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        val m = media[i]
        val s = m.vectors.getOrNull(nodeShape) ?: return
        val pts = s.pts.toMutableList()
        pts[nodePt * 2] = p.first * canvasW; pts[nodePt * 2 + 1] = p.second * canvasH
        media[i] = m.copy(vectors = m.vectors.toMutableList().also { it[nodeShape] = s.copy(pts = pts) })
        refreshPenOverlay()
        val now = SystemClock.uptimeMillis()
        if (now - vecPreviewAt >= 70) { vecPreviewAt = now; renderVector(media[i], save = false) }
    }

    private fun vecNodeUp() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i >= 0) { renderVector(media[i], save = true); invalidateThumb(nodeSelId); markDirty() }
        refreshPenOverlay()
    }

    /** Удаляет выбранный узел примитива (пока остаётся минимум точек); у линии, рамки и эллипса узел удалить нельзя - удаляется весь примитив. */
    fun deleteVectorNode() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || nodeShape < 0) { _state.value = UiState.Message("Сначала коснитесь узла примитива"); return }
        val s = media[i].vectors.getOrNull(nodeShape) ?: return
        val canDrop = (s.kind == com.vibetoon.domain.vector.VecKind.PATH || s.kind == com.vibetoon.domain.vector.VecKind.POLY || s.kind == com.vibetoon.domain.vector.VecKind.POLYGON) &&
            s.pts.size / 2 > (if (s.kind == com.vibetoon.domain.vector.VecKind.POLYGON) 3 else 2)
        if (!canDrop) { deleteVectorShape(); return }
        val pts = s.pts.toMutableList(); pts.removeAt(nodePt * 2 + 1); pts.removeAt(nodePt * 2)
        setVectors(nodeSelId, media[i].vectors.toMutableList().also { it[nodeShape] = s.copy(pts = pts) })
        nodePt = -1
        refreshPenOverlay()
    }

    fun deleteVectorShape() {
        val i = media.indexOfFirst { it.id == nodeSelId }
        if (i < 0 || nodeShape < 0) { _state.value = UiState.Message("Сначала коснитесь узла примитива"); return }
        setVectors(nodeSelId, media[i].vectors.filterIndexed { k, _ -> k != nodeShape })
        nodeShape = -1; nodePt = -1
        refreshPenOverlay()
    }

    private fun penDown(sx: Float, sy: Float): Boolean {
        if (penPts.isEmpty()) selectedVectorId()?.let { vid -> if (!isLockedId(vid) && vecNodeDown(vid, sx, sy)) return true }
        val id = selectedVectorId() ?: run {
            _state.value = UiState.Message("Перо рисует на векторном слое: «＋» → «Векторный», затем снова выберите «Путь»")
            return false
        }
        if (isLockedId(id)) { lockedNotice(); return false }
        val q = scene?.selectionQuads?.get(id) ?: return false
        val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
        swallowTap = true
        val cx = p.first * canvasW; val cy = p.second * canvasH
        if (penPts.size >= 6) {
            // тап по первой точке замыкает контур
            val (fx, fy) = toScreen(q[0] + (penPts[0] / canvasW) * (q[2] - q[0]) + (penPts[1] / canvasH) * (q[6] - q[0]),
                q[1] + (penPts[0] / canvasW) * (q[3] - q[1]) + (penPts[1] / canvasH) * (q[7] - q[1]))
            if (kotlin.math.hypot(sx - fx, sy - fy) < 28f) { penFinish(true); return true }
        }
        penPts += cx; penPts += cy
        _penCount.value = penPts.size / 2
        refreshPenOverlay()
        return true
    }

    fun penFinish(closed: Boolean) {
        val id = selectedVectorId() ?: return
        val m = media.firstOrNull { it.id == id } ?: return
        if (penPts.size < 4) { _state.value = UiState.Message("Для контура нужно хотя бы две точки"); return }
        val c = vecStroke()
        val fill = if (closed && _vecFill.value) (c and 0x00FFFFFFL) or 0xFF000000L else 0L
        val shape = com.vibetoon.domain.vector.VectorShape(
            if (closed && penPts.size >= 6) com.vibetoon.domain.vector.VecKind.POLYGON else com.vibetoon.domain.vector.VecKind.POLY,
            penPts.toList(), c, fill, brush.value.size
        )
        penPts.clear(); _penCount.value = 0; _toolDrag.value = null
        setVectors(id, com.vibetoon.domain.vector.VectorMath.withShape(m.vectors, shape))
        _state.value = UiState.Message(if (closed) "Контур замкнут" else "Контур добавлен")
    }

    fun penUndoPoint() {
        if (penPts.size >= 2) { penPts.removeAt(penPts.size - 1); penPts.removeAt(penPts.size - 1) }
        _penCount.value = penPts.size / 2
        refreshPenOverlay()
    }

    fun penCancel() { penPts.clear(); _penCount.value = 0; _toolDrag.value = null }

    private fun refreshPenOverlay() {
        val id = selectedVectorId()
        val q = if (id != null) scene?.selectionQuads?.get(id) else null
        if (q != null && penPts.isEmpty() && _rasterTool.value == RasterTool.PATH && id != null) {
            // нет контура в работе: показываем узлы готовых примитивов, выбранный подсвечен
            val m = media.firstOrNull { it.id == id }
            val shapes = m?.vectors.orEmpty()
            if (shapes.isNotEmpty()) {
                val flat = ArrayList<Float>(); var sel = -1
                for ((i, s) in shapes.withIndex()) for (k in 0 until s.pts.size / 2) {
                    val (px, py) = canvasToScreen(q, s.pts[k * 2], s.pts[k * 2 + 1])
                    if (i == nodeShape && k == nodePt && id == nodeSelId) sel = flat.size / 2
                    flat += px; flat += py
                }
                _toolDrag.value = ToolDrag(4, flat.toFloatArray(), sel = sel)
                return
            }
        }
        if (q == null || penPts.isEmpty() || _rasterTool.value != RasterTool.PATH) { if (_toolDrag.value?.kind == 3 || _toolDrag.value?.kind == 4) _toolDrag.value = null; return }
        val out = FloatArray(penPts.size)
        for (i in 0 until penPts.size / 2) {
            val u = penPts[i * 2] / canvasW; val v = penPts[i * 2 + 1] / canvasH
            val sp_ = toScreen(q[0] + u * (q[2] - q[0]) + v * (q[6] - q[0]), q[1] + u * (q[3] - q[1]) + v * (q[7] - q[1]))
            out[i * 2] = sp_.first
            out[i * 2 + 1] = sp_.second
        }
        _toolDrag.value = ToolDrag(3, out)
    }

    /** Обновление экранных оверлеев растровых инструментов при смене сцены и камеры. */
    private fun refreshRasterOverlays() {
        if (_rasterTool.value == null) return
        refreshSelOverlay(); refreshPenOverlay()
    }

    // ---------------------------------------------------------------- Puppet Mesh (пины)

    private var pinLayers: Map<Int, List<Pin>> = emptyMap()
    private var pinSel = -1
    private var pinsStiffDefault = false
    private var pinRadiusDefault = 180f
    private val PIN_GRAB = 50_000
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
        val hit = arapMemo[id]
        if (hit != null && hit.mesh === m && hit.pins === pins && hit.w == w && hit.h == h && hit.v.contentEquals(v) && hit.poff.contentEquals(poff)) return hit.out
        val out = PuppetMath.addTo(m, v, pins, poff, w, h)
        arapMemo[id] = ArapMemo(m, pins, v.copyOf(), poff, w, h, out)
        return out
    }

    private class ArapMemo(val mesh: Mesh, val pins: List<Pin>, val v: FloatArray, val poff: FloatArray, val w: Float, val h: Float, val out: FloatArray)
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
        val screenW = kotlin.math.hypot(toScreenX(q[2], q[3]) - toScreenX(q[0], q[1]), toScreenY(q[2], q[3]) - toScreenY(q[0], q[1]))
        val pts = FloatArray(pins.size * 2)
        val rad = FloatArray(pins.size)
        for ((i, p) in pins.withIndex()) {
            val u = p.u + off[i * 2]; val v = p.v + off[i * 2 + 1]
            val sp_ = toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy)
            pts[i * 2] = sp_.first
            pts[i * 2 + 1] = sp_.second
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
            _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
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
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
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
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
        if (u !in 0f..1f || v !in 0f..1f) return false
        val cur = pinLayers[t].orEmpty()
        if (cur.size >= PuppetMath.MAX_PINS) { _state.value = UiState.Message("Не больше ${PuppetMath.MAX_PINS} пинов"); return true }
        val (w, h) = layerSize(t) ?: return false
        val off = PuppetMath.offsets(t, cur.size, _frame.value, base, _animation.value, _fx.value)
        val d = PuppetMath.fieldAt(u, v, cur, off, w, h)
        checkpoint("pin-add", force = true)
        pinLayers = pinLayers + (t to cur + Pin((u - d[0]).coerceIn(0f, 1f), (v - d[1]).coerceIn(0f, 1f), pinsStiffDefault, pinRadiusDefault))
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
        _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = cur.dx, dy = cur.dy))
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
    private val _layerFx = MutableStateFlow<List<Effect>>(emptyList())
    /** Цепочка эффектов выделенного слоя (по порядку применения). */
    val layerEffects: StateFlow<List<Effect>> = _layerFx.asStateFlow()
    private val _fxLibrary = MutableStateFlow(false)
    /** Открыта ли «Библиотека эффектов». */
    val fxLibrary: StateFlow<Boolean> = _fxLibrary.asStateFlow()

    fun openFxLibrary(on: Boolean) {
        if (on && warpTarget() == null) { _state.value = UiState.Message("Выберите один слой, чтобы добавить эффект"); return }
        _fxLibrary.value = on
    }

    private fun refreshFxState() { _layerFx.value = warpTarget()?.let { fxLayers[it] }.orEmpty() }

    private fun fxNow(): Map<Int, List<FloatArray>> {
        val base = fxRecords(fxLayers, _colors.value, _frame.value.toFloat() / _animation.value.settings.fps.coerceAtLeast(1))
        if (ghosts.isEmpty()) return base
        val out = HashMap(base)
        for (g in ghosts) out[g.id] = listOf(floatArrayOf(TINT_FX, g.r, g.g, g.b, 0.85f, 0f, 0f, 0f, 0f, 0f))   // красный/зелёный оттенок кадров-ориентиров
        return out
    }

    /** Записи эффектов слоёв: цветокоррекция слоя - неявный первый эффект «Цветокоррекция». Общий путь для экрана и экспорта. */
    private fun fxRecords(fx: Map<Int, List<Effect>>, colors: Map<Int, ColorAdjust>, sec: Float): Map<Int, List<FloatArray>> {
        val adj = colors.filterValues { !it.isIdentity }
        if (fx.isEmpty() && adj.isEmpty()) return emptyMap()
        val out = HashMap<Int, List<FloatArray>>()
        for (id in fx.keys + adj.keys) {
            val own = adj[id]?.let { listOf(Effect(EffectMath.COLOR, listOf(it.exposure / 50f, it.contrast / 100f, it.saturation / 100f, it.hue * 1.8f, 0f, 0f, 0f, 0f))) }.orEmpty()
            val r = EffectMath.records(own + fx[id].orEmpty(), sec)
            if (r.isNotEmpty()) out[id] = r
        }
        return out
    }

    private fun editFx(key: String, force: Boolean = false, change: (List<Effect>) -> List<Effect>) {
        val t = warpTarget() ?: return
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
        val t = warpTarget() ?: run { _state.value = UiState.Message("Выберите один слой"); return }
        if ((fxLayers[t]?.size ?: 0) >= EffectMath.MAX_PER_LAYER) { _state.value = UiState.Message("На слое не больше ${EffectMath.MAX_PER_LAYER} эффектов"); return }
        editFx("fx-add", force = true) { it + e }
        _fxLibrary.value = false
    }

    fun removeEffect(index: Int) = editFx("fx-del", force = true) { l -> l.filterIndexed { i, _ -> i != index } }

    fun moveEffect(index: Int, dir: Int) = editFx("fx-move", force = true) { l ->
        val j = index + dir
        if (index !in l.indices || j !in l.indices) l else l.toMutableList().also { val x = it[index]; it[index] = it[j]; it[j] = x }
    }

    fun setEffectParam(index: Int, param: Int, v: Float) = editFx("fx-p$index-$param") { l ->
        val e = l.getOrNull(index) ?: return@editFx l
        l.toMutableList().also { it[index] = e.copy(p = e.p.toMutableList().also { p -> p[param] = EffectMath.clamp(e.type, param, v) }) }
    }

    /** Цвет r, g, b эффекта (ARGB) в три подряд идущих параметра, начиная с colorAt типа. */
    fun setEffectColor(index: Int, argb: Long) {
        val e = _layerFx.value.getOrNull(index) ?: return
        val at = EffectMath.def(e.type)?.colorAt?.takeIf { it >= 0 } ?: return
        editFx("fx-color", force = true) { l ->
            val cur = l[index]
            l.toMutableList().also {
                it[index] = cur.copy(p = cur.p.toMutableList().also { p ->
                    p[at] = ((argb shr 16) and 0xFF) / 255f; p[at + 1] = ((argb shr 8) and 0xFF) / 255f; p[at + 2] = (argb and 0xFF) / 255f
                })
            }
        }
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

    private fun meshEdges(m: Mesh): IntArray = meshEdgeCache.getOrPut(m) {
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
            val sp_ = toScreen(q[0] + u * ex + v * fx, q[1] + u * ey + v * fy)
            pts[k * 2] = sp_.first
            pts[k * 2 + 1] = sp_.second
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
            val p = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
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

    /** Записывает итоговое смещение вершины: FX выкл - форма привязки, FX вкл - ключ на кадре (дельта к привязке). */
    private fun setVertexOffset(t: Int, k: Int, ox0: Float, oy0: Float) {
        // защита от NaN и от «улёта» вершины при быстром жесте: смещение держим в разумных пределах
        val ox = if (ox0.isFinite()) ox0.coerceIn(-2f, 2f) else 0f
        val oy = if (oy0.isFinite()) oy0.coerceIn(-2f, 2f) else 0f
        val id = MeshMath.vertexId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)
        } else {
            val bind = base[id]
            _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
        }
    }

    /** Cut: отрезок движения пальца удаляет задетые треугольники (по деформированной сетке); вершины чистятся в конце жеста. */
    private fun cutMove(sx: Float, sy: Float) {
        val t = warpTarget() ?: return
        val m = meshLayers[t] ?: return
        val q = scene?.selectionQuads?.get(t) ?: return
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
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
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        val (pu, pv) = meshLast ?: return
        meshLast = u to v
        _brushCircle.value = floatArrayOf(sx, sy, _brushRadius.value)
        val w = l.width.toFloat(); val h = l.height.toFloat()
        val (ru, rv) = unitOf(q, toCanvasX(sx + _brushRadius.value, sy), toCanvasY(sx + _brushRadius.value, sy)) ?: return
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
        for (k in dirty) setVertexOffset(t, k, off[k * 2], off[k * 2 + 1])
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
        val (pu, pv) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return false
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
        val (u, v) = unitOf(q, toCanvasX(sx, sy), toCanvasY(sx, sy)) ?: return
        val ox = (u - m.u(k)).coerceIn(-1.5f, 1.5f)
        val oy = (v - m.v(k)).coerceIn(-1.5f, 1.5f)
        checkpoint("mesh$t-$k")
        val id = MeshMath.vertexId(t, k)
        if (!_fx.value) {
            base[id] = LayerTransform(dx = ox, dy = oy)   // FX выкл: меняется Bind Pose сетки
        } else {
            val bind = base[id]
            _animation.value = _animation.value.withKey(id, _frame.value, LayerTransform(dx = ox - (bind?.dx ?: 0f), dy = oy - (bind?.dy ?: 0f)))
        }
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

    // экран <-> холст (те же формулы, что у камеры: экран = C + (p - C) * zoom + pan)
    private fun fitS() = Camera.fitScale(viewW, viewH, canvasW, canvasH).coerceAtLeast(1e-4f)
    // Холст <-> экран через матрицу вида: учитывает zoom, pan, поворот вида и отражения (Flip Canvas).
    private var viewCacheKey: Any? = null
    private var viewCache = Affine2D.IDENTITY
    private var viewInvCache = Affine2D.IDENTITY
    private fun viewM(): Affine2D {
        val key = listOf(camera, viewW, viewH, canvasW, canvasH)
        if (key != viewCacheKey) {
            viewCache = camera.viewMatrix(viewW, viewH, canvasW, canvasH)
            viewInvCache = viewCache.inverse() ?: Affine2D.IDENTITY
            viewCacheKey = key
        }
        return viewCache
    }
    private fun viewInv(): Affine2D { viewM(); return viewInvCache }
    private fun toScreen(x: Float, y: Float): Pair<Float, Float> = viewM().let { it.applyX(x, y) to it.applyY(x, y) }
    private fun toScreenX(x: Float, y: Float) = viewM().applyX(x, y)
    private fun toScreenY(x: Float, y: Float) = viewM().applyY(x, y)
    private fun toCanvasX(sx: Float, sy: Float) = viewInv().applyX(sx, sy)
    private fun toCanvasY(sx: Float, sy: Float) = viewInv().applyY(sx, sy)
    /** Вектор экрана (сдвиг пальца) -> вектор холста (без переноса). */
    private fun screenDeltaToCanvas(dx: Float, dy: Float): Pair<Float, Float> = viewInv().let { it.a * dx + it.c * dy to it.b * dx + it.d * dy }

    /** Положение кости на экране: в Bind Pose - по данным, в Animation - с учётом позы. */
    private fun shapes(): List<BoneShape> {
        val rest = _bindPose.value
        val sel = _selected.value
        return _bones.value.map { b ->
            val d = if (rest) Affine2D.IDENTITY else scene?.boneDeltas?.get(b.id) ?: Affine2D.IDENTITY
            BoneShape(
                b.id,
                toScreenX(d.applyX(b.hx, b.hy), d.applyY(b.hx, b.hy)), toScreenY(d.applyX(b.hx, b.hy), d.applyY(b.hx, b.hy)),
                toScreenX(d.applyX(b.tx, b.ty), d.applyY(b.tx, b.ty)), toScreenY(d.applyX(b.tx, b.ty), d.applyY(b.tx, b.ty)),
                b.id == sel
            )
        }
    }

    private fun refreshBoneOverlay() {
        refreshWarpOverlay()
        refreshMeshOverlay()
        refreshPerspOverlay()
        if (!_boneMode.value) {
            if (_boneOverlay.value.bones.isNotEmpty() || _boneOverlay.value.draft != null || _boneOverlay.value.lasso.isNotEmpty()) _boneOverlay.value = BoneOverlay()
            return
        }
        val dr = draft?.let { floatArrayOf(toScreenX(it[0], it[1]), toScreenY(it[0], it[1]), toScreenX(it[2], it[3]), toScreenY(it[2], it[3])) }
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
        if (_links.value[b.id] == null) { _state.value = UiState.Message("У кости нет родителя"); return }
        checkpoint("bone-detach", force = true)
        _links.value = _links.value - b.id
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
        if (pivotDown(sx, sy)) return true   // Layer Pivot Editing: палец на опорной точке выделенного слоя
        if (_liquifyTool.value && !_boneMode.value && !_meshMode.value) return liquifyDown(sx, sy)
        if (_perspTool.value && !_boneMode.value && !_meshMode.value) return perspDown(sx, sy)
        if (_meshMode.value && !_boneMode.value) return meshDown(sx, sy)
        if (_warpTool.value && !_boneMode.value) return warpDown(sx, sy)
        if (!_boneMode.value || _playing.value) return false
        lastCx = toCanvasX(sx, sy)
        lastCy = toCanvasY(sx, sy)
        when (_boneTool.value) {
            BoneTool.ADD -> {
                if (!_bindPose.value) return false
                var x = lastCx
                var y = lastCy
                // начало новой кости прилипает к концу выбранной (цепочка костей)
                selectedBone()?.let { p ->
                    if (kotlin.math.hypot(sx - toScreenX(p.tx, p.ty), sy - toScreenY(p.tx, p.ty)) < 40f) { x = p.tx; y = p.ty }
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
        if (grab == PIVOT_GRAB) { pivotMove(sx, sy); return }
        if (grab >= PIN_GRAB) { pinMove(sx, sy); return }
        if (grab == LIQ_GRAB) { liquifyMove(sx, sy); return }
        if (grab >= PERSP_GRAB) { perspMove(sx, sy); return }
        if (grab >= MESH_GRAB) { meshMove(sx, sy); return }
        if (grab >= 20) { warpMove(sx, sy); return }
        if (grab == 4) { lassoPts.add(sx); lassoPts.add(sy); refreshBoneOverlay(); return }
        val cx = toCanvasX(sx, sy)
        val cy = toCanvasY(sx, sy)
        if (grab == 3) {
            draft?.let { it[2] = cx; it[3] = cy }
            refreshBoneOverlay()
            return
        }
        val b = selectedBone() ?: return
        checkpoint("bone$grab${b.id}")
        if (_bindPose.value) editBindPose(b, cx, cy) else editAnimPose(b, cx, cy)
        lastCx = cx
        lastCy = cy
        pushScene()
        markDirty()
    }

    fun boneUp() {
        if (grab == PIVOT_GRAB) { pivotUp(); grab = 0; return }
        if (grab == LIQ_GRAB) { finishLiquify(); grab = 0; return }
        if (grab >= 20) { if (grab == MESH_CUT) finishCut(); if (grab == MESH_SMUDGE) commitBrush(); grab = 0; return }
        if (grab == 4) { finishLasso(); grab = 0; return }
        val d = draft
        if (grab == 3 && d != null) {
            val len = kotlin.math.hypot(toScreenX(d[2], d[3]) - toScreenX(d[0], d[1]), toScreenY(d[2], d[3]) - toScreenY(d[0], d[1]))
            if (len >= 24f) createBone(d[0], d[1], d[2], d[3])
        }
        draft = null
        grab = 0
        refreshBoneOverlay()
    }

    fun boneCancel() {
        if (grab == PIVOT_GRAB) pivotUp()
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

    /** Лассо замкнуто: выбираются слои, центр рамки которых попал внутрь контура. */
    private fun finishLasso() {
        val poly = lassoPts.toList()
        lassoPts = ArrayList()
        if (poly.size < 6) { refreshBoneOverlay(); return }
        val sc = scene ?: return
        viewModelScope.launch {
            val ids = layers.filter { it.kind == com.vibetoon.nativebridge.LayerKind.LAYER && it.id < BONE_ID_BASE }.map { it.id }
            for (id in ids) ensureBox(id)   // границы содержимого посчитаны до выбора: центр берётся у нарисованного, а не у всего холста
            val picked = HashSet<Int>()
            for (id in ids) {
                val q = sc.selectionQuads[id] ?: continue
                val cq = contentQuad(id, q) ?: continue
                val cx = (cq[0] + cq[2] + cq[4] + cq[6]) / 4f
                val cy = (cq[1] + cq[3] + cq[5] + cq[7]) / 4f
                if (pointInPolygon(toScreenX(cx, cy), toScreenY(cx, cy), poly)) picked += id
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
        val set = _lassoLayers.value
        if (set.isEmpty()) { _state.value = UiState.Message("Сначала обведите слои лассо"); return }
        if (!_bindPose.value) { _state.value = UiState.Message("Привязка работает в режиме Bind Pose"); return }
        checkpoint("lasso-bind", force = true)
        _links.value = _links.value + set.associateWith { bone.id }
        _lassoLayers.value = emptySet()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Привязано слоёв: ${set.size} к «${bone.name}» (вручную)")
    }

    /** Сколько слоёв привязано к кости (для инспектора кости). */
    fun boundCount(boneId: Int): Int = _links.value.count { (c, p) -> p == boneId && c < BONE_ID_BASE }

    fun setBoneIndependent(id: Int, on: Boolean) {
        checkpoint("bone-indep", force = true)
        _bones.value = _bones.value.map { if (it.id == id) it.copy(independentRot = on) else it }
        pushScene()
        markDirty()
    }

    private fun nextBoneId(): Int = maxOf(BONE_ID_BASE, (_bones.value.maxOfOrNull { it.id } ?: 0) + 1)

    private fun createBone(x0: Float, y0: Float, x1: Float, y1: Float) {
        checkpoint("bone-new", force = true)
        // родитель - выбранная кость, но только если новая начата от её конца (цепочка плечо -> предплечье);
        // отдельная кость в другом месте (вторая рука) - корневая, иначе движение одной руки тянет другую
        val sel = selectedBone()
        val parent = sel?.takeIf { kotlin.math.hypot(x0 - it.tx, y0 - it.ty) < 3f }
        val id = nextBoneId()
        _bones.value = _bones.value + Bone(id, "Кость ${_bones.value.size + 1}", x0, y0, x1, y1)
        if (parent != null) _links.value = _links.value + (id to parent.id)   // выбранная кость становится родителем
        applySelection(setOf(id), id)
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (parent != null) "Кость создана, родитель: ${parent.name}" else "Корневая кость создана")
    }

    /** Bind Pose: сустав переносит кость целиком с потомками, конец меняет длину и направление. */
    private fun editBindPose(b: Bone, cx: Float, cy: Float) {
        val kids = linkDescendants(b.id)
        if (grab == 1) {
            val dx = cx - lastCx
            val dy = cy - lastCy
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
            storeEdit(b.id, t.copy(dx = px - b.hx, dy = py - b.hy))
        } else {
            val want = kotlin.math.atan2(py - (b.hy + t.dy), px - (b.hx + t.dx)) - kotlin.math.atan2(b.ty - b.hy, b.tx - b.hx)
            var diff = want - t.rotation
            val twoPi = (2 * Math.PI).toFloat()
            while (diff > Math.PI) diff -= twoPi
            while (diff < -Math.PI) diff += twoPi
            storeEdit(b.id, t.copy(rotation = t.rotation + diff))
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
        viewModelScope.launch {
            for (l in layers) if (l.kind == com.vibetoon.nativebridge.LayerKind.LAYER) ensureBox(l.id)
            autoBindNow()
        }
    }

    private fun autoBindNow() {
        if (!_bindPose.value) { _state.value = UiState.Message("Автопривязка работает в режиме Bind Pose"); return }
        val bones = _bones.value
        if (bones.isEmpty()) { _state.value = UiState.Message("Сначала создайте кости"); return }
        val sc = scene ?: return
        val picks = HashMap<Int, Int>()
        for (l in layers) {
            if (l.kind != com.vibetoon.nativebridge.LayerKind.LAYER || l.id in _links.value) continue
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
        return _bones.value.filter { it.id !in banned }.map { it.id to "Кость ${it.name}" } + layers.filter {
            it.id !in banned && it.kind != com.vibetoon.nativebridge.LayerKind.GROUP_END
        }.map { it.id to it.name }
    }

    fun parentOf(id: Int): Int? = _links.value[id]

    /** Тип слоя для списка слоёв: "Текст", "Картинка", "Видео", "Группа" или "Пиксели" (слой PSD). */
    fun layerType(id: Int): String {
        media.firstOrNull { it.id == id }?.let {
            return when (it.kind) {
                MediaKind.TEXT -> "Текст"; MediaKind.IMAGE -> if (it.vector) "Вектор" else "Картинка"; MediaKind.VIDEO -> "Видео"; MediaKind.AUDIO -> "Аудио"; MediaKind.CAMERA -> "Камера"; MediaKind.NULLOBJ -> "Transform Layer"; MediaKind.GROUP -> "Группа"; MediaKind.SYMBOL -> "Символ"
            }
        }
        val k = layers.firstOrNull { it.id == id }?.kind
        return if (k == com.vibetoon.nativebridge.LayerKind.GROUP_OPEN || k == com.vibetoon.nativebridge.LayerKind.GROUP_CLOSED) "Группа" else "Пиксели"
    }

    fun layerName(id: Int): String =
        _bones.value.firstOrNull { it.id == id }?.let { "Кость ${it.name}" } ?: layers.firstOrNull { it.id == id }?.name ?: "#$id"

    /**
     * Привязывает все выделенные слои к [parentId] (null - отвязать). Ребёнок повторяет трансформацию
     * родителя поверх своей собственной; цикл привязок невозможен.
     */
    fun setParent(parentId: Int?) {
        val ids = _selection.value
        if (ids.isEmpty() || _playing.value) return
        if (parentId != null && parentId in ids) return
        val banned = HashSet<Int>().also { b -> ids.forEach { b += it; b += linkDescendants(it) } }
        if (parentId != null && parentId in banned) {
            _state.value = UiState.Message("Нельзя: родитель сам привязан к этому слою")
            return
        }
        checkpoint("parent", force = true)
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
     * [targetId] = null - бросили в пустое место: слой уходит в корень. Работает для слоёв PSD;
     * картинки, видео и текст лежат в координатах холста и в группы PSD не кладутся.
     */
    fun dropLayer(dragId: Int, targetId: Int?) {
        if (_playing.value) return
        val byId = layers.associateBy { it.id }
        val src = byId[dragId] ?: return
        // порядок: бросок на слой (не группу) ставит слой на его место; на группу - внутрь группы
        val tgt = targetId?.let { byId[it] }
        if (tgt != null && tgt.id != dragId && tgt.kind == com.vibetoon.nativebridge.LayerKind.LAYER) {
            if (dragId >= MEDIA_ID_BASE && tgt.id >= MEDIA_ID_BASE) { reorderMedia(dragId, tgt.id); return }
            if (dragId < MEDIA_ID_BASE && tgt.id < MEDIA_ID_BASE) {
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
                    // картинки, рисунки, вектор: можно в свои группы и между собой, в группы PSD - нельзя
                    if (t.id < MEDIA_ID_BASE) { _state.value = UiState.Message("Картинки, видео и текст пока нельзя класть в группы PSD"); return }
                    if (tIsGroup) t.id else t.parentId
                } else if (t.id >= MEDIA_ID_BASE) -1
                else if (tIsGroup) t.id else t.parentId
            }
        }
        if (newParent == src.parentId) return
        if (newParent >= 0 && dragId in ancestorsOf(newParent)) {
            _state.value = UiState.Message("Нельзя: группа не может войти сама в себя")
            return
        }
        checkpoint("regroup", force = true)
        regroup = regroup.filter { it.first != dragId } + (dragId to newParent)
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message(if (newParent < 0) "Слой вынесен из группы" else "Слой перенесён в «${layerName(newParent)}»")
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

    /** Сдвигает выделенные ключи на [delta] кадров (все дорожки слоя вместе, граница проекта останавливает всех). */
    fun moveSelectedKeys(delta: Int) {
        val sel = _keySel.value
        if (sel.isEmpty() || delta == 0 || _playing.value) return
        val last = duration() - 1
        val minF = sel.minOf { it.second }
        val maxF = sel.maxOf { it.second }
        val d = delta.coerceIn(-minF, last - maxF)
        if (d == 0) return
        checkpoint("keymove")
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
        checkpoint(tag, force = true)
        var anim = _animation.value
        val byLayer = sel.groupBy({ it.first }, { it.second })
        val picked = HashSet<Pair<Int, Int>>()
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
        removeKeys(sel)
        _keySel.value = emptySet()
        override.clear()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Ключей удалено: ${sel.size}")
    }

    /** Убирает ключи всех дорожек слоя на указанных кадрах; если дорожка опустела, слой остаётся на последнем значении. */
    private fun removeKeys(sel: Set<Pair<Int, Int>>) {
        for ((layer, frame) in sel) for (id in TrackGroups.ids(layer, _animation.value.tracks)) {
            val (next, lastValue) = _animation.value.withoutKey(id, frame)
            _animation.value = next
            if (lastValue != null) base[id] = lastValue
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
        _hasKeyClip.value = out.isNotEmpty()
        _state.value = UiState.Message(if (out.isEmpty()) "В выделении нет ключей" else "Скопировано ключей: ${out.size}")
    }

    fun cutSelectedKeys() {
        if (_playing.value) return
        copySelectedKeys()
        if (keyClip.isNotEmpty()) deleteSelectedKeys()
    }

    /**
     * Вставляет скопированные ключи так, чтобы самый ранний встал на текущий кадр. Куда: на те же дорожки; если
     * скопирован один слой, а сейчас выделен другой (не группа), ключи (в том числе вершины сетки и пины) переносятся на него.
     */
    fun pasteKeys() {
        val clip = keyClip
        if (clip.isEmpty() || _playing.value) return
        val srcLayers = clip.map { it.layer }.toSet()
        val target = _selected.value?.takeIf { srcLayers.size == 1 && it != srcLayers.first() && layers.any { l -> l.id == it } }
        val cur = _frame.value
        val last = duration() - 1
        checkpoint("keypaste", force = true)
        var anim = _animation.value
        val picked = HashSet<Pair<Int, Int>>()
        var count = 0
        for (e in clip) {
            val id = if (target != null) TrackGroups.remap(e.trackId, srcLayers.first(), target) ?: continue else e.trackId
            val f = cur + e.rel
            if (f > last) continue
            val tr = anim.tracks[id] ?: AnimationTrack(id)
            anim = anim.copy(tracks = anim.tracks + (id to tr.upsert(e.key.copy(frame = f))))
            picked += (target ?: e.layer) to f
            count++
        }
        _animation.value = anim
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
        val removedMedia = media.filter { it.id in all }
        media.removeAll(removedMedia)
        for (id in all) { base.remove(id); override.remove(id) }
        _links.value = _links.value.filter { (c, p) -> c !in all && p !in all }
        dropWarpData(all)
        dropMeshData(all)
        dropPerspData(all)
        val anim = _animation.value
        _animation.value = anim.copy(tracks = anim.tracks.filterKeys { it !in all })
        textJob?.cancel()
        multiMode = false
        applySelection(emptySet(), null)
        rebuildLayers()
        pushScene()
        markDirty()
        _state.value = UiState.Message("Удалено: ${all.size}")
        Log.i("VT/Edit", "deleted ${all.size} nodes: $all")
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
        override.clear()
        _frame.value = _frame.value.coerceAtMost(d - 1)
        rebuildLayers()   // длина аудио в кадрах зависит от fps
        pushScene()
        markDirty()
        Log.i("VT/Anim", "timeline: $d frames at $f fps")
    }

    // ---------------------------------------------------------------- время и Play

    /** Перемотка (ползунок, поле кадра). Временные правки кадра сбрасываются. */
    fun setFrame(frame: Int) {
        val f = frame.coerceIn(0, _animation.value.settings.durationFrames - 1)
        if (f == _frame.value) return
        override.clear()
        _frame.value = f
        pushScene()
        updateVideoFrames(f)
        updateTextFx(f)
        updateCells(f)
        refreshOnion()
    }

    private val videoFrames = com.vibetoon.data.VideoFrames { RenderHost.view?.requestRender() }

    /** Кадр источника для видео-слоя на кадре проекта [f]: учитывает начало клипа, скорость, реверс, стоп-кадр и смещение. */
    private fun videoSourceMs(clip: Clip, f: Int, fps: Int): Long {
        val local = (f - clip.start).coerceIn(0, (clip.length - 1).coerceAtLeast(0))
        val step = when {
            clip.freeze -> 0f
            clip.reverse -> (clip.length - 1 - local) * clip.speed
            else -> local * clip.speed
        }
        return ((clip.offset + step) * 1000f / fps.coerceAtLeast(1)).toLong().coerceAtLeast(0L)
    }

    /** При смене кадра подставляет в текстуры видео-слоёв нужный кадр источника. */
    private fun updateVideoFrames(f: Int) {
        val pid = projectId ?: return
        val fps = _animation.value.settings.fps
        val playing = _playing.value
        for (m in media) {
            if (m.kind != MediaKind.VIDEO) continue
            val clip = clipOf(m.id)
            if (!clip.visibleAt(f)) continue
            videoFrames.request(
                m.id, projects.mediaFile(pid, m.file.removePrefix("assets/")), videoSourceMs(clip, f, fps),
                m.srcWidth, m.srcHeight, if (playing) 640 else 1280
            )
        }
    }

    /** Пользователь тянет ползунок: воспроизведение останавливается. */
    fun scrubTo(frame: Int) {
        pause()
        setFrame(frame)
        // скраббинг звука: короткий фрагмент на новом кадре (не чаще раза в 45 мс)
        val now = SystemClock.uptimeMillis()
        val f = _frame.value
        if (f != lastScrubFrame && now - lastScrubAt >= 45) {
            lastScrubAt = now; lastScrubFrame = f
            audioEngine.scrub(f, _animation.value.settings.fps, ::clipOf)
        }
    }

    private var frameCallback: Choreographer.FrameCallback? = null

    fun togglePlay() = if (_playing.value) pause() else play()

    /**
     * Воспроизведение: кадр считается по часам Choreographer (время, а не число вызовов),
     * цикл по длительности. Сцена пересобирается только при смене целого кадра.
     */
    fun play() {
        if (_playing.value) return
        _playing.value = true
        val settings = _animation.value.settings
        val looping = _loop.value
        // без цикла старт с последнего кадра начинает проигрывание заново
        val startFrame = if (!looping && _frame.value >= settings.durationFrames - 1) 0 else _frame.value
        var startNanos = -1L
        var lastF = startFrame
        audioEngine.play(startFrame, settings.fps, ::clipOf)
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!_playing.value) return
                if (startNanos < 0) startNanos = frameTimeNanos
                val f = PlaybackMath.frameAt(frameTimeNanos - startNanos, settings.fps, startFrame, settings.durationFrames, looping)
                if (f < lastF) audioEngine.play(f, settings.fps, ::clipOf)   // цикл: звук с начала
                lastF = f
                if (f != _frame.value) {
                    setFrame(f)
                    if (f % settings.fps == 0) Log.i("VT/Anim", "play frame $f")
                }
                if (!looping && f >= settings.durationFrames - 1) { pause(); return }   // конец сцены: стоп на последнем кадре
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
        Log.i("VT/Anim", "pause at frame ${_frame.value}")
    }

    // ---------------------------------------------------------------- экспорт в MP4

    private val _export = MutableStateFlow<ExportUi>(ExportUi.Idle)
    val export: StateFlow<ExportUi> = _export.asStateFlow()
    private var exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Размер видео и число кадров для диалога. */
    fun exportInfo(shortSide: Int, fps: Int): Triple<Int, Int, Int> {
        val (w, h) = encoderSize(ExportMath.size(canvasW, canvasH, shortSide))
        val st = _animation.value.settings
        return Triple(w, h, ExportMath.frameCount(st.durationFrames, st.fps, fps))
    }

    private val encoderSizes = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
    /** Размер, который примет кодировщик устройства (кратно 16, в его пределах); опрос кодеков кэшируется. */
    private fun encoderSize(s: Pair<Int, Int>): Pair<Int, Int> =
        encoderSizes.getOrPut(s) { runCatching { com.vibetoon.render.VideoExporter.supportedSize(s.first, s.second) }.getOrDefault(s) }

    /**
     * Рендер всей анимации в MP4 (без звука) в папку проекта. Идёт в GL-потоке прямо в кодек, быстрее
     * реального времени. Сцена каждого кадра строится из снимка анимации: ручные правки кадра не влияют.
     */
    /** Строит функцию «номер кадра результата -> сцена» (копии данных проекта: можно звать из любого потока). */
    private fun buildSceneAt(fps: Int, onlyLayers: Set<Int>? = null, symbol: Int? = null, symCache: Map<Int, List<ByteArray?>> = emptyMap()): (Int) -> SceneData {
        val outsideIds: Set<Int> = if (onlyLayers == null) emptySet() else layers.filter { it.kind == LayerKind.LAYER && it.id !in onlyLayers }.map { it.id }.toSet()
        val st = _animation.value.settings
        val anim = _animation.value
        val adjCopy = _colors.value
        val baseCopy = HashMap(base)
        val clipsCopy = _clips.value
        val fxIdsExp = fxLayers.keys
        val hiddenExp = layerProps.filterValues { !it.visible }.keys + symbolHidden(symbol)   // в контексте символа видны только его слои
        val ids = baseCopy.keys + anim.tracks.keys + clipsCopy.keys + fxIdsExp + adjCopy.keys + hiddenExp + outsideIds
        val r = roots; val cw = canvasW; val ch = canvasH; val f = fit; val tids = textIds; val lnk = _links.value; val bnd = boneHeads(); val wl = warpLayers; val indep = independentBones(); val ml = meshLayers; val pl = perspLayers; val btl = boneTails(); val sk = effectiveSkins(); val tl = turbLayers; val fxl = fxLayers; val camId = if (symbol != null) -1 else cameraIdNow(); val pinl = pinLayers; val lsz = layers.associate { it.id to (it.width.toFloat() to it.height.toFloat()) }
        val symMembers = if (symbol != null) symbolMembers(symbol) else null
        // те же нуль-объекты, глубины и опорные точки, что в редакторе: экспорт совпадает с превью
        val nullsExp = media.filter { it.kind == MediaKind.NULLOBJ }.mapTo(HashSet()) { it.id }
        val depthsExp = layerProps.filterValues { it.depth != 0f }.mapValues { it.value.depth }
        val pivotsExp = pivotOffsetsNow()
        val fxTexts = media.filter { it.isText && it.fx.animated && (symMembers == null || it.id in symMembers) }
        val cellLayers = media.filter { it.kind == MediaKind.IMAGE && it.cells.isNotEmpty() && (symMembers == null || it.id in symMembers) }
        val symInside = HashSet<Int>().also { st0 -> media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.forEach { st0 += symbolMembers(it.id) } }
        val symInst = if (symbol == null) media.filter { it.kind == MediaKind.SYMBOL && it.id !in symInside } else emptyList()
        val symDefs = media.filter { it.kind == MediaKind.GROUP && it.symDur > 0 }.associate { it.id to (it.symDur to symbolAnimated(it.id)) }
        val symExp = HashMap<Int, Int>()
        val cellExp = HashMap<Int, String>()
        val pidExp = projectId
        val sceneAtFn = { i: Int ->
            val pf = ExportMath.projectFrame(i, st.fps, fps, st.durationFrames)
            for (cm in cellLayers) {   // покадровые слои: в текстуре рисунок этого кадра
                val act = FrameMath.active(cm.cells, pf) ?: continue
                if (cellExp[cm.id] == act.file || pidExp == null) continue
                runCatching {
                    val d = importer.decodeImage(projects.fileOf(pidExp, act.file))
                    RenderNative.uploadBitmap(cm.id, d.bitmap); d.bitmap.recycle(); cellExp[cm.id] = act.file
                }
            }
            for (si in symInst) {   // экземпляры символов: готовая картинка вложенной сцены на этом кадре
                val frames = symCache[si.symRef] ?: continue
                val def = symDefs[si.symRef] ?: continue
                val key = (SymbolMath.timeAt(si.timeKeys, pf)?.coerceIn(0, maxOf(0, def.first - 1))) ?: if (def.second) SymbolMath.localFrame(pf, def.first) else 0
                if (symExp[si.id] == key) continue
                val png = frames.getOrNull(key) ?: continue
                runCatching {
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                    RenderNative.uploadBitmap(si.id, bmp); bmp.recycle(); symExp[si.id] = key
                }
            }
            for (tm in fxTexts) {   // текст с эффектами: картинка этого кадра заливается в текстуру до отрисовки
                val rt = textRenderer.renderFx(tm.text, tm.textColor.toInt(), tm.font, tm.textSize, tm.gradient, tm.gradientAngle, tm.fx, pf.toFloat() / st.fps.coerceAtLeast(1), st.fps.coerceAtLeast(1), if (tm.stroke) tm.strokeWidth else 0f, tm.strokeColor.toInt(), tm.textAlign, tm.lineSpacing, tm.letterSpacing)
                RenderNative.uploadBitmap(tm.id, rt.bitmap)
                rt.bitmap.recycle()
            }
            SceneBuilder.buildTree(r, cw, ch, ids.associateWith { id ->
                val t0 = clipsCopy[id]?.let { c -> anim.evaluate(id, c.animFrame(pf), baseCopy[id]) } ?: anim.evaluate(id, pf, baseCopy[id])
                val t = fxl[id]?.let { l -> t0.copy(rotation = t0.rotation + EffectMath.spinAngle(l, pf, st.fps)) } ?: t0
                val c = clipsCopy[id]
                if (id in hiddenExp || id in outsideIds || (c != null && !c.visibleAt(pf))) t.copy(opacity = 0f) else t
            }, f, tids, lnk, bnd, wl.associateWith { WarpMath.offsets(it, pf, baseCopy, anim, true) }, indep,
                ml.mapValues { (lid, m) ->
                    val v = MeshMath.offsets(lid, m, pf, baseCopy, anim, true)
                    val ps = pinl[lid]
                    val sz = lsz[lid]
                    m to (if (ps != null && sz != null) PuppetMath.addTo(m, v, ps, PuppetMath.offsets(lid, ps.size, pf, baseCopy, anim, true), sz.first, sz.second) else v)
                },
                pl.associateWith { PerspectiveMath.matrix(PerspectiveMath.offsets(it, pf, baseCopy, anim, true)) }, btl, sk, tl.mapValues { (id, t) -> TurbMath.params(TurbMath.at(id, t, pf, anim), pf) },
                fxRecords(fxl, adjCopy, pf.toFloat() / st.fps.coerceAtLeast(1)), camId, nullsExp, depthsExp, pivotsExp)
        }
        return sceneAtFn
    }

    fun startExport(shortSide: Int, fps: Int) {
        val pid = projectId ?: return
        val view = RenderHost.view ?: return
        if (_export.value is ExportUi.Running) return
        pause()
        val st = _animation.value.settings
        val (w, h, frames) = exportInfo(shortSide, fps)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = projects.fileOf(pid, "exports/${safeName(_projectName.value)}-$stamp.mp4")

        val bg = meta?.background?.toInt() ?: 0
        exportCancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = exportCancel
        _export.value = ExportUi.Running(0, frames)
        viewModelScope.launch {
        val sceneAt = buildSceneAt(fps, symCache = prerenderSymbols())
        view.exportVideo(
            ExportSpec(w, h, fps, frames, file, compat = prefs.compatExport), bg, sceneAt,
            onProgress = { _export.value = ExportUi.Running(it, frames) }, cancel = cancel
        ) { result ->
            viewModelScope.launch { restoreAfterExport() }   // тексты с эффектами и покадровые слои возвращаются на текущий кадр
            result.onSuccess {
                Log.i("VT/Export", "done ${file.length() / 1024} KB $w x $h $frames frames")
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { saveToGallery(file, "video/mp4") }
                    _export.value = ExportUi.Done(file)
                }
            }.onFailure {
                file.delete()
                _export.value = when {
                    it is com.vibetoon.render.ExportInterruptedException -> ExportUi.Failed(it.message ?: "Экспорт прерван")
                    cancel.get() -> ExportUi.Idle
                    else -> ExportUi.Failed(it.message ?: "Не удалось записать видео")
                }
            }
        }
        }
    }

    fun cancelExport() { exportCancel.set(true) }
    fun dismissExport() { if (_export.value !is ExportUi.Running) _export.value = ExportUi.Idle }

    private fun safeName(n: String) = n.replace(Regex("""[^\p{L}\p{N}_-]+"""), "_").trim('_').ifEmpty { "video" }.take(40)

    override fun onCleared() {
        pause()
        audioEngine.release()
        executor.shutdown()
    }
}
