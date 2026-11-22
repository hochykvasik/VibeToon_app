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
    data class Editor(val id: String) : Screen
}

/** Список проектов и переход между экранами. Редактор живёт в [MainViewModel]. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProjectRepository(app)

    private val _screen = MutableStateFlow<Screen>(Screen.Projects)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectMeta>>(emptyList())
    val projects: StateFlow<List<ProjectMeta>> = _projects.asStateFlow()

    init {
        refresh()
    }

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

    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { repo.importBackup(it) } }.getOrNull()
            }
            refresh()
            _note.tryEmit(if (id != null) "Проект импортирован" else "Файл не похож на резервную копию VibeToon (.tsproj)")
        }
    }

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
