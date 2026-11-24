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

    // Спринт 41: init стоит ПОСЛЕ всех полей. Раньше он был выше _sizes/_folders/prefs, и refresh(), успевший
    // завершиться до их инициализации (быстрый диск, Dispatchers.Main.immediate), падал с NPE на _sizes.value.
    init {
        repo.cleanStaleImports()   // пакет 13: остатки прерванного импорта
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val (list, sz) = withContext(Dispatchers.IO) { val l = repo.list(); l to l.associate { it.id to repo.sizeOf(it.id) } }
            _projects.value = list
            _sizes.value = sz
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) { _projects.value.filter { it.folder == name }.forEach { repo.moveToFolder(it.id, "") } }
            refresh()
        }
    }

    fun moveToFolder(id: String, folder: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { repo.moveToFolder(id, folder) }; refresh() }
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
        _screen.value = Screen.Editor(id)
    }

    fun goProjects() {
        _screen.value = Screen.Projects
        refresh()
    }

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

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.rename(id, name) }
            refresh()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.duplicate(id) }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.delete(id) }
            refresh()
        }
    }

    fun thumbnailFile(id: String): File = repo.thumbnailFile(id)
}
