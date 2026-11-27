package com.vibetoon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vibetoon.data.ProjectRepository
import com.vibetoon.domain.project.NewProjectSpec
import com.vibetoon.domain.project.ProjectMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Экраны приложения. Два экрана, поэтому обходимся простым состоянием без navigation-compose. */
sealed interface Screen {
    data object Projects : Screen
    /** [psd] - пакет 13: PSD из импорта на экране проектов, загружается сразу после открытия нового проекта. */
    data class Editor(val id: String, val psd: android.net.Uri? = null) : Screen
}

/** Пакет 13: состояние импорта проекта для окна на экране проектов. */
sealed interface ImportUi {
    data object Busy : ImportUi
    data class Done(
        val id: String, val name: String, val missing: List<String>, val fontsRestored: Int,
        val fontsMissing: List<String>, val renamed: Boolean, val sourceVersion: Int,
        /** Пакет 28: приложение-источник (пусто - VibeToon) и что не перенесено. */
        val app: String = "", val notes: List<String> = emptyList()
    ) : ImportUi
    data class Failed(val message: String) : ImportUi
}

/** Спринт 52: бюджет кэша превью экрана проектов (~40 карточек 480 px). */
private const val THUMB_BUDGET = 24L * 1024 * 1024

/** Список проектов и переход между экранами. Редактор живёт в [MainViewModel]. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Projects)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectMeta>>(emptyList())
    val projects: StateFlow<List<ProjectMeta>> = _projects.asStateFlow()

    private val _import = MutableStateFlow<ImportUi?>(null)
    /** Пакет 13: окно импорта (идёт / готово / ошибка); null - окна нет. */
    val importState: StateFlow<ImportUi?> = _import.asStateFlow()

    private val prefs = app.getSharedPreferences("vt_manager", android.content.Context.MODE_PRIVATE)
    private val starterMutex = kotlinx.coroutines.sync.Mutex()
    private val _starterId = MutableStateFlow(prefs.getString("starter_id", null))
    val starterId: StateFlow<String?> = _starterId.asStateFlow()
    val needsStarterRecovery: Boolean get() = prefs.contains("starter_id") && !prefs.getBoolean("starter_created", false)
    private val _sizes = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Размер каждого проекта на диске (байты) для сортировки и подписи на карточке. */
    val sizes: StateFlow<Map<String, Long>> = _sizes.asStateFlow()
    private val _folders = MutableStateFlow(prefs.getStringSet("folders", emptySet()).orEmpty().toSet())
    /** Папки, созданные пользователем (в том числе пустые). */
    val folders: StateFlow<Set<String>> = _folders.asStateFlow()
    private val _note = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Короткие сообщения о результате (очистка, импорт, экспорт) для строки-уведомления экрана проектов. */
    val note: kotlinx.coroutines.flow.SharedFlow<String> = _note

    private val _loaded = MutableStateFlow(false)
    /** Спринт 46: список проектов уже прочитан (до этого экран не показывает «Проектов пока нет» и не прячет баннер). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _sort = MutableStateFlow(com.vibetoon.domain.project.ProjectSort.of(prefs.getString("sort", null)))
    /** Спринт 46: сортировка библиотеки (сохраняется). */
    val sort: StateFlow<com.vibetoon.domain.project.ProjectSort> = _sort.asStateFlow()
    private val _order = MutableStateFlow(com.vibetoon.domain.project.ProjectListMath.decodeOrder(prefs.getString("manual_order", null)))
    /** Спринт 46: свой порядок проектов (перетаскивание карточек), id сверху вниз. */
    val manualOrder: StateFlow<List<String>> = _order.asStateFlow()

    private var refreshJob: kotlinx.coroutines.Job? = null
    /** Спринт 52: записи на диск экрана проектов (перенос, имя, удаление) идут по очереди, без гонок одного проекта. */
    private val diskLock = kotlinx.coroutines.sync.Mutex()

    private val thumbs = com.vibetoon.domain.perf.BudgetLru<String, androidx.compose.ui.graphics.ImageBitmap>(THUMB_BUDGET, { it.width.toLong() * it.height * 4L })

    // Спринт 41: init стоит ПОСЛЕ всех полей. Раньше он был выше _sizes/_folders/prefs, и refresh(), успевший
    // завершиться до их инициализации (быстрый диск, Dispatchers.Main.immediate), падал с NPE на _sizes.value.
    init {
        repo.cleanStaleImports()   // пакет 13: остатки прерванного импорта
        refresh()
    }

    fun setSort(s: com.vibetoon.domain.project.ProjectSort) {
        _sort.value = s
        prefs.edit().putString("sort", s.name).apply()
    }

    /**
     * Перетаскивание карточек: [visible] - новый порядок проектов этого вида (папки или корня). Остальные проекты
     * сохраняют места; сортировка становится «Свой порядок».
     */
    fun reorder(visible: List<String>) {
        val base = _order.value.ifEmpty {
            com.vibetoon.domain.project.ProjectListMath.sort(_projects.value, _sort.value, _sizes.value).map { it.id }
        }
        val next = com.vibetoon.domain.project.ProjectListMath.mergeOrder(base, visible)
        _order.value = next
        prefs.edit().putString("manual_order", com.vibetoon.domain.project.ProjectListMath.encodeOrder(next)).apply()
        if (_sort.value != com.vibetoon.domain.project.ProjectSort.MANUAL) setSort(com.vibetoon.domain.project.ProjectSort.MANUAL)
    }

    fun refresh() {
        // спринт 52: несколько обновлений подряд (перенос + выход из редактора) - работает только последнее
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // спринт 46: список публикуется сразу, размеры (обход папок) - следом; прежний список не сбрасывается
            val list = withContext(Dispatchers.IO) { repo.list() }
            _projects.value = list
            _loaded.value = true
            val alive = list.mapTo(HashSet()) { it.id }
            if (_order.value.any { it !in alive }) {
                _order.value = com.vibetoon.domain.project.ProjectListMath.pruneOrder(_order.value, alive)
                prefs.edit().putString("manual_order", com.vibetoon.domain.project.ProjectListMath.encodeOrder(_order.value)).apply()
            }
            // спринт 52: размеры - из кэша по штампу файлов; обходятся только изменившиеся проекты
            _sizes.value = withContext(Dispatchers.IO) { list.associate { it.id to repo.sizeOf(it.id) } }
            thumbs.removeIf { k -> k.substringBefore(':') !in alive }
        }
    }

    fun addFolder(name: String) {
        val n = com.vibetoon.domain.project.ProjectListMath.cleanFolderName(name)
        if (n.isEmpty()) return
        _folders.value = _folders.value + n
        prefs.edit().putStringSet("folders", _folders.value).apply()
    }

    fun deleteFolder(name: String) {
        _folders.value = _folders.value - name
        prefs.edit().putStringSet("folders", _folders.value).apply()
        val ids = _projects.value.filter { it.folder == name }.map { it.id }
        if (ids.isEmpty()) return
        _projects.value = com.vibetoon.domain.project.ProjectListMath.withFolder(_projects.value, ids, "")   // спринт 52: сразу на экране
        viewModelScope.launch {
            diskLock.withLock { withContext(Dispatchers.IO) { ids.forEach { runCatching { repo.moveToFolder(it, "") } } } }
            refresh()
        }
    }

    fun moveToFolder(id: String, folder: String) = moveToFolder(listOf(id), folder)

    /** Спринт 46: несколько проектов в папку (выделение долгим нажатием или перетаскивание на карточку папки). */
    fun moveToFolder(ids: Collection<String>, folder: String) {
        if (ids.isEmpty()) return
        if (folder.isNotBlank() && folder !in _folders.value) addFolder(folder)
        // спринт 52: карточки переезжают сразу (раньше экран ждал полного чтения и перезаписи project.json каждого
        // проекта, повторного разбора JSON для списка и обхода папок всех проектов для размеров)
        _projects.value = com.vibetoon.domain.project.ProjectListMath.withFolder(_projects.value, ids, folder)
        _note.tryEmit(if (folder.isBlank()) "Проектов перенесено из папки: ${ids.size}" else "Проектов перенесено в «$folder»: ${ids.size}")
        val todo = ids.toList()
        viewModelScope.launch {
            val failed = diskLock.withLock { withContext(Dispatchers.IO) { todo.count { runCatching { repo.moveToFolder(it, folder) }.getOrNull() == null } } }
            if (failed > 0) _note.tryEmit("Не удалось перенести проектов: $failed")
            refresh()   // заголовки и размеры уже в кэше репозитория - без повторного чтения
        }
    }

    /** Спринт 46: удаление выделенных проектов (после подтверждения на экране). */
    fun deleteMany(ids: Collection<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failed = diskLock.withLock { withContext(Dispatchers.IO) { ids.count { runCatching { repo.delete(it) }.isFailure } } }
            refresh()
            _note.tryEmit("Удалено проектов: ${ids.size - failed}")
            if (failed > 0) _note.tryEmit("Не удалось удалить проектов: $failed")
        }
    }

    /** Очистка кэша (волны звука, временные файлы) и осиротевших ассетов всех проектов. */
    fun cleanUp() {
        viewModelScope.launch {
            val (cache, orph) = withContext(Dispatchers.IO) { repo.clearCache() to repo.deleteOrphans() }
            refresh()
            _note.tryEmit("Очищено: кэш ${com.vibetoon.domain.project.ProjectListMath.sizeLabel(cache)}, лишних файлов ${orph.first} (${com.vibetoon.domain.project.ProjectListMath.sizeLabel(orph.second)})")
        }
    }

    fun exportBackup(id: String, uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { repo.exportBackup(id, it) } }.isSuccess
            }
            _note.tryEmit(if (ok) "Резервная копия .tsproj сохранена" else "Не удалось сохранить копию")
        }
    }

    /**
     * Пакет 13: импорт проекта (.tsproj, .zip, project.json, PSD). Новый проект добавляется рядом с существующими
     * (они не меняются); окно результата предлагает открыть его или отменить импорт.
     */
    fun importBackup(uri: android.net.Uri) {
        if (_import.value == ImportUi.Busy) return
        _import.value = ImportUi.Busy
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val names = repo.list().map { it.name }
                    repo.importProject({
                        cr.openInputStream(uri) ?: throw com.vibetoon.domain.project.ProjectArchive.ImportException(com.vibetoon.domain.project.ProjectArchive.Reason.READ_FAILED)
                    }, names, displayName(uri))
                }
            }
            refresh()
            result.onSuccess { r ->
                when (r) {
                    is ProjectRepository.Imported.Project -> {
                        val rep = r.report
                        if (rep.data.meta.folder.isNotBlank()) addFolder(rep.data.meta.folder)   // папка проекта появляется в менеджере
                        _import.value = ImportUi.Done(rep.data.meta.id, rep.data.meta.name, rep.missing, rep.fontsRestored, rep.fontsMissing, rep.renamed, rep.sourceVersion, rep.app, rep.notes)
                    }
                    is ProjectRepository.Imported.Psd -> {
                        // PSD: новый проект размером с PSD (не больше 4096 по длинной стороне), слои загрузятся в редакторе
                        val name = displayName(uri).substringBeforeLast('.').ifBlank { "PSD" }
                        val (w, h) = com.vibetoon.domain.project.ProjectArchive.psdCanvas(r.width, r.height)
                        val meta = withContext(Dispatchers.IO) {
                            repo.create(NewProjectSpec(com.vibetoon.domain.project.ProjectArchive.uniqueName(name, _projects.value.map { it.name }), w, h, 24, 0xFFFFFFFFL))
                        }
                        _import.value = null
                        _note.tryEmit("PSD откроется в новом проекте: слои загружаются")
                        _screen.value = Screen.Editor(meta.id, uri)
                    }
                }
            }.onFailure { t ->
                android.util.Log.w("VT/Import", "project import failed", t)
                val msg = (t as? com.vibetoon.domain.project.ProjectArchive.ImportException)?.text
                    ?: if (t is OutOfMemoryError) com.vibetoon.domain.project.ProjectArchive.Reason.TOO_LARGE.message
                    else com.vibetoon.domain.project.ProjectArchive.Reason.READ_FAILED.message
                _import.value = ImportUi.Failed(msg)
            }
        }
    }

    /** Открыть только что импортированный проект. */
    fun openImported(id: String) { _import.value = null; open(id) }

    /** Отменить импорт: импортированный проект удаляется целиком, остальные не затрагиваются. */
    fun undoImport(id: String) {
        _import.value = null
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            refresh()
            _note.tryEmit("Импорт отменён: проект удалён")
        }
    }

    fun dismissImport() { if (_import.value != ImportUi.Busy) _import.value = null }

    private fun displayName(uri: android.net.Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PSD"

    fun open(id: String) {
        // спринт 52: редактору - память; на экране проектов остаётся четверть кэша превью (последние карточки)
        thumbs.resize(THUMB_BUDGET / 4)
        // спринт 52: перенос в папку пишется в фоне - редактор читает проект, когда запись закончена (иначе его
        // сохранение вернуло бы прежнюю папку). Обычно очередь пуста, и проект открывается без ожидания.
        if (!diskLock.isLocked) { _screen.value = Screen.Editor(id); return }
        viewModelScope.launch {
            diskLock.withLock { }
            _screen.value = Screen.Editor(id)
        }
    }

    /** Спринт 56: открыть проект из уведомления/окна итога экспорта, если он ещё есть (могли удалить). */
    fun openIfExists(id: String) {
        if (_screen.value is Screen.Editor) return
        if (repo.thumbnailFile(id).parentFile?.isDirectory == true) open(id)
    }

    fun goProjects() {
        // превью закрытого проекта переснято при выходе - старое не показываем
        (_screen.value as? Screen.Editor)?.id?.let { id -> thumbs.removeIf { it.substringBefore(':') == id } }
        thumbs.resize(THUMB_BUDGET)
        _screen.value = Screen.Projects
        refresh()
    }

    /**
     * Спринт 52: декодированные превью карточек и папок (ключ «id:updatedAt:sample»). Раньше превью декодировалось с диска
     * при каждом появлении карточки - перенос в папку и обратно, открытие папки, возврат из редактора.
     */
    fun cachedThumb(key: String): androidx.compose.ui.graphics.ImageBitmap? = thumbs.get(key)
    fun putThumb(key: String, bmp: androidx.compose.ui.graphics.ImageBitmap) { thumbs.put(key, bmp) }

    /** onTrimMemory: превью отдают память (фон или нехватка - всё, умеренная нехватка - половину). */
    fun onTrimMemory(level: Int) {
        thumbs.resize(com.vibetoon.domain.perf.CellCachePolicy.trimmedBudget(thumbs.budgetBytes, level))
    }

    /** Возврат на передний план: прежний бюджет превью. */
    fun restoreCaches() { if (_screen.value is Screen.Projects) thumbs.resize(THUMB_BUDGET) }

    /** Создаёт проект с заданными параметрами и сразу открывает рабочий экран. */
    fun create(spec: NewProjectSpec) {
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { repo.create(spec) }
            open(meta.id)
        }
    }

    /** Создание идемпотентно и сериализовано: повторный запуск не перезаписывает правки пользователя. */
    private suspend fun starterProject(): ProjectMeta = starterMutex.withLock {
        val id = prefs.getString("starter_id", null) ?: java.util.UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("starter_id", it).commit()
        }
        val meta = withContext(Dispatchers.IO) { repo.createStarter(id) }
        prefs.edit().putBoolean("starter_created", true).apply()
        _starterId.value = meta.id
        meta
    }

    fun ensureStarterProject() {
        viewModelScope.launch {
            runCatching { starterProject() }.onSuccess { refresh() }
                .onFailure { _note.tryEmit("Не удалось создать учебный проект") }
        }
    }

    /** Открывает существующий учебный проект и запускает подсказки; если он удалён, создаёт новый. */
    fun startLesson(session: com.vibetoon.ui.shell.Coach.Session) {
        viewModelScope.launch {
            runCatching { starterProject() }.onSuccess { meta ->
                com.vibetoon.ui.shell.Coach.start(session)
                open(meta.id)
            }.onFailure { _note.tryEmit("Не удалось создать учебный проект") }
        }
    }

    // спринт 61: ошибки диска (нет места, файл занят, повреждённый project.json) - сообщение, а не вылет приложения;
    // операции идут под тем же замком диска, что и перенос в папку (без гонки записи заголовка)
    fun rename(id: String, name: String) {
        val clean = com.vibetoon.domain.project.ProjectListMath.cleanProjectName(name)
        if (clean.isEmpty()) { _note.tryEmit("Имя проекта не может быть пустым"); return }
        viewModelScope.launch {
            val ok = diskLock.withLock { withContext(Dispatchers.IO) { runCatching { repo.rename(id, clean) }.isSuccess } }
            if (!ok) _note.tryEmit("Не удалось переименовать проект")
            refresh()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            val copy = diskLock.withLock { withContext(Dispatchers.IO) { runCatching { repo.duplicate(id) }.getOrNull() } }
            if (copy == null) _note.tryEmit("Не удалось создать копию проекта")
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val ok = diskLock.withLock { withContext(Dispatchers.IO) { runCatching { repo.delete(id) }.isSuccess } }
            if (!ok) _note.tryEmit("Не удалось удалить проект")
            refresh()
        }
    }

    fun thumbnailFile(id: String): File = repo.thumbnailFile(id)
}
