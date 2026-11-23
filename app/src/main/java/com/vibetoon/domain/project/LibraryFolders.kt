package com.vibetoon.domain.project

/**
 * Пакет 23: пользовательские папки библиотеки ассетов. Папка - имя и файлы assets (по имени файла); файл лежит
 * не больше чем в одной папке. Хранится в папке проекта (library_folders.json): входит в резервную копию .tsproj
 * и в дубликат проекта, в библиотеке не показывается. Пустые папки сохраняются.
 */
data class LibraryFolders(val folders: List<Folder> = emptyList()) {
    data class Folder(val name: String, val files: List<String> = emptyList())

    val names: List<String> get() = folders.map { it.name }

    fun folderOf(file: String): String? = folders.firstOrNull { file in it.files }?.name

    fun filesIn(name: String): List<String> = folders.firstOrNull { it.name == name }?.files.orEmpty()

    /** Новая папка; null - пустое имя или такая папка уже есть (без учёта регистра), либо папок слишком много. */
    fun add(name: String): LibraryFolders? {
        val n = clean(name) ?: return null
        if (folders.size >= MAX_FOLDERS || folders.any { it.name.equals(n, ignoreCase = true) }) return null
        return copy(folders = folders + Folder(n))
    }

    /** Переименование; null - нет такой папки, новое имя пустое или занято другой папкой. */
    fun rename(old: String, new: String): LibraryFolders? {
        val n = clean(new) ?: return null
        if (folders.none { it.name == old }) return null
        if (folders.any { it.name != old && it.name.equals(n, ignoreCase = true) }) return null
        return copy(folders = folders.map { if (it.name == old) it.copy(name = n) else it })
    }

    /** Удаление папки; файлы остаются в проекте и библиотеке. */
    fun delete(name: String): LibraryFolders = copy(folders = folders.filter { it.name != name })

    /** Положить файл в папку [folder] (из прежней он убирается); null - убрать из всех папок. */
    fun assign(file: String, folder: String?): LibraryFolders {
        if (folder != null && folders.none { it.name == folder }) return this
        return copy(folders = folders.map { f ->
            val without = f.files - file
            if (f.name == folder) f.copy(files = without + file) else f.copy(files = without)
        })
    }

    /** Убрать из папок файлы, которых больше нет в assets. */
    fun prune(existing: Set<String>): LibraryFolders = copy(folders = folders.map { f -> f.copy(files = f.files.filter { it in existing }) })

    fun encode(): String = MiniJson.stringify(linkedMapOf(
        "version" to VERSION,
        "folders" to folders.map { linkedMapOf("name" to it.name, "files" to it.files) }
    ))

    companion object {
        const val FILE = "library_folders.json"
        const val VERSION = 1
        const val MAX_NAME = 40
        const val MAX_FOLDERS = 50

        fun clean(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifEmpty { null }

        /** Чтение; повреждённый файл даёт пустой список папок (библиотека работает как без них). */
        fun decode(text: String?): LibraryFolders {
            if (text.isNullOrBlank()) return LibraryFolders()
            val root = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return LibraryFolders()
            val list = root["folders"] as? List<*> ?: return LibraryFolders()
            var out = LibraryFolders()
            for (f in list) {
                val m = f as? Map<*, *> ?: continue
                out = out.add(m["name"] as? String ?: continue) ?: continue
                val name = out.folders.last().name
                for (file in (m["files"] as? List<*>).orEmpty()) {
                    val s = file as? String ?: continue
                    if (s.isNotBlank() && !s.contains('/') && out.folderOf(s) == null) out = out.assign(s, name)
                }
            }
            return out
        }
    }
}
