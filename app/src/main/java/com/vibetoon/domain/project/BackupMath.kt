package com.vibetoon.domain.project

/** Правила резервной копии .tsproj (ZIP): манифест, что входит в архив, безопасные пути при распаковке. */
object BackupMath {
    const val MANIFEST = "tsproj.json"

    fun manifest(): String = "{\"app\":\"VibeToon\",\"format\":1}"

    /** В копию не входят экспортированные файлы, временные файлы и кэш волн (они пересоздаются). */
    fun included(rel: String): Boolean =
        !rel.startsWith("exports/") && !rel.endsWith(".tmp") && !rel.endsWith(".wave")

    /** Путь внутри папки проекта или null, если запись пытается выйти за её пределы (../, абсолютный путь). */
    fun safePath(name: String): String? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.isEmpty() || n.split('/').any { it == ".." }) return null
        return n
    }
}
