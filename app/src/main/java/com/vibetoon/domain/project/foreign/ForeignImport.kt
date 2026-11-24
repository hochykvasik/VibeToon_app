package com.vibetoon.domain.project.foreign

import com.vibetoon.domain.project.MEDIA_ID_BASE
import com.vibetoon.domain.project.ProjectArchive
import com.vibetoon.domain.project.ProjectData
import java.io.File
import java.io.OutputStream

/**
 * Пакет 28: импорт проектов других приложений. Распакованный архив (или один JSON-файл) распознаётся по содержимому
 * и превращается в обычный проект VibeToon: project.json + картинки в assets/. Дальше работает общий путь импорта
 * пакета 13 (новый id, уникальное имя, проверка файлов, окно «Проект импортирован»).
 *
 * Поддерживается:
 *  - Procreate (.procreate): растровые слои и группы, имена, прозрачность, наложение, видимость, замок, обтравка,
 *    маски (запекаются в альфу), фон, поворот и отражение холста; файл Animation Assist - покадровый слой;
 *  - Lottie (.json, .lottie): размер, fps, длительность, слои-фигуры (векторные слои), заливки (solid), картинки,
 *    тексты, пустые слои (Transform Layer) и вложенные композиции, родители, ключи позиции/масштаба/поворота/прозрачности;
 *  - OpenRaster (.ora) - слои PNG из stack.xml; Krita (.kra) - сведённая картинка mergedimage.png;
 *  - ZIP с последовательностью кадров (PNG/JPEG/WebP) - покадровый слой (так переносятся экспорты ToonSquid, Rough
 *    Animator, FlipaClip и др.).
 * Неподдерживаемые части перечисляются в [Result.notes] - пользователь видит их в окне итога импорта.
 */
object ForeignImport {
    /** Источник: файлы распакованного архива по относительным путям (через «/»). */
    interface Source {
        fun names(): List<String>
        fun read(name: String): ByteArray?
        fun has(name: String): Boolean = read(name) != null
    }

    /** Приёмник картинок проекта: возвращает путь для MediaItem.file («assets/имя»). */
    interface Sink {
        fun write(name: String, block: (OutputStream) -> Unit): String
        fun put(name: String, bytes: ByteArray): String = write(name) { it.write(bytes) }
    }

    /** Итог преобразования: данные проекта (id и имя уточнит общий импорт), приложение-источник и что не перенесено. */
    data class Result(val data: ProjectData, val app: String, val notes: List<String> = emptyList())

    enum class Kind(val app: String) {
        PROCREATE("Procreate"), LOTTIE("Lottie"), DOTLOTTIE("Lottie"), ORA("OpenRaster"), KRITA("Krita"),
        SEQUENCE("Последовательность кадров"), TOONSQUID("ToonSquid"), SVG("SVG"), UNKNOWN("")
    }

    /** Сообщения отказа (RU/EN через таблицу перевода). */
    const val MSG_TOONSQUID = "Проект ToonSquid (.tsproj) не читается напрямую: экспортируйте его в ToonSquid в PSD, GIF, видео или последовательность PNG (ZIP) и импортируйте этот файл"
    const val MSG_UNKNOWN = "Формат не распознан. Поддерживаются: .tsproj/.zip VibeToon, PSD, Procreate, Lottie (.json/.lottie), OpenRaster (.ora), Krita (.kra), SVG и ZIP с кадрами PNG/JPEG"
    const val MSG_ROUGH = "Проект Rough Animator не читается напрямую: экспортируйте анимацию в Rough Animator как последовательность PNG (ZIP), GIF или видео и импортируйте этот файл"
    const val MSG_EMPTY = "В файле нет слоёв с пикселями"

    /** Сообщение для файла, который не распознан даже по первым байтам (null - общее сообщение причины). */
    fun unknownMessage(fileName: String): String? = when {
        fileName.lowercase().endsWith(".tsproj") -> MSG_TOONSQUID
        fileName.lowercase().substringAfterLast('.') == "roughanimator" -> MSG_ROUGH
        fileName.lowercase().substringAfterLast('.') in setOf("procreate", "lottie", "ora", "kra", "svg") -> null
        else -> MSG_UNKNOWN
    }

    class Unsupported(message: String) : IllegalArgumentException(message)

    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp")

    /** Тип по списку файлов архива. */
    fun detect(src: Source): Kind {
        val names = src.names()
        val lower = names.map { it.lowercase() }
        if ("document.archive" in lower) return Kind.PROCREATE
        val mime = src.read("mimetype")?.toString(Charsets.US_ASCII)?.trim()
        if (mime == "image/openraster" || ("stack.xml" in lower && mime != "application/x-krita")) return Kind.ORA
        if (mime == "application/x-krita" || "maindoc.xml" in lower) return Kind.KRITA
        if ("manifest.json" in lower && lower.any { it.startsWith("animations/") && it.endsWith(".json") }) return Kind.DOTLOTTIE
        val jsons = names.filter { it.lowercase().endsWith(".json") }
        if (jsons.size == 1 && names.none { it.lowercase().substringAfterLast('.') in IMAGE_EXT && !it.lowercase().startsWith("images/") }) {
            val t = src.read(jsons[0])?.toString(Charsets.UTF_8)
            if (t != null && LottieImport.looksLike(t)) return Kind.LOTTIE
        }
        // спринт 36: один SVG (файл или ZIP с ним) - векторный проект
        val svgs = names.filter { it.lowercase().endsWith(".svg") }
        if (svgs.size == 1 && names.size <= 2 && src.read(svgs[0])?.toString(Charsets.UTF_8)?.let { com.vibetoon.domain.vector.SvgImport.looksLike(it) } == true) return Kind.SVG
        val images = names.filter { it.substringAfterLast('.').lowercase() in IMAGE_EXT }
        if (images.size >= 1 && images.size >= names.size - 3) return Kind.SEQUENCE
        // ToonSquid: тот же .tsproj, но без project.json VibeToon - узнаём по служебным именам или по расширению
        if (lower.any { "toonsquid" in it } || lower.any { it.endsWith(".tsdata") || it.endsWith(".tsframe") }) return Kind.TOONSQUID
        return Kind.UNKNOWN
    }

    /** Преобразование распакованного архива. [fileName] - имя выбранного файла (имя проекта и подсказка по расширению). */
    fun convert(src: Source, sink: Sink, fileName: String): Result {
        val title = fileName.substringAfterLast('/').substringBeforeLast('.').trim().ifEmpty { "Импорт" }
        return when (detect(src)) {
            Kind.PROCREATE -> ProcreateImport.convert(src, sink, title)
            Kind.ORA -> OraImport.convertOra(src, sink, title)
            Kind.KRITA -> OraImport.convertKra(src, sink, title)
            Kind.DOTLOTTIE -> LottieImport.convertDotLottie(src, sink, title)
            Kind.LOTTIE -> {
                val json = src.names().first { it.lowercase().endsWith(".json") }
                LottieImport.convert(src.read(json)!!.toString(Charsets.UTF_8), src, sink, title)
            }
            Kind.SEQUENCE -> SequenceImport.convert(src, sink, title)
            Kind.SVG -> SvgProject.convert(src.read(src.names().first { it.lowercase().endsWith(".svg") })!!.toString(Charsets.UTF_8), title)
            Kind.TOONSQUID -> throw Unsupported(MSG_TOONSQUID)
            Kind.UNKNOWN -> throw Unsupported(unknownMessage(fileName) ?: MSG_UNKNOWN)
        }
    }

    /**
     * Распакованный архив или JSON [src] -> папка проекта [projectDir] (project.json + assets/). Ошибки - понятные
     * [ProjectArchive.ImportException]: неподдерживаемый формат (с подсказкой), повреждённый файл, нехватка места/памяти.
     */
    fun toProjectDir(src: Source, projectDir: File, fileName: String): Result {
        val result = try {
            convert(src, DirSink(File(projectDir, "assets")), fileName)
        } catch (e: Unsupported) {
            throw ProjectArchive.ImportException(ProjectArchive.Reason.NOT_PROJECT, e, e.message)
        } catch (e: ProjectArchive.ImportException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ProjectArchive.ImportException(if (ProjectArchive.isNoSpace(e)) ProjectArchive.Reason.NO_SPACE else ProjectArchive.Reason.READ_FAILED, e)
        } catch (e: OutOfMemoryError) {
            throw ProjectArchive.ImportException(ProjectArchive.Reason.TOO_LARGE, e)
        } catch (e: Exception) {
            throw ProjectArchive.ImportException(ProjectArchive.Reason.FOREIGN_DAMAGED, e)
        }
        projectDir.mkdirs()
        File(projectDir, "project.json").writeText(com.vibetoon.domain.project.ProjectJson.encode(result.data))
        return result
    }

    /** Последовательные id медиа-слоёв нового проекта. */
    class Ids { private var next = MEDIA_ID_BASE; fun next() = next++ }

    /** Источник поверх папки (распакованный архив). */
    class DirSource(private val root: File) : Source {
        private val list by lazy { root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList() }
        override fun names() = list
        override fun read(name: String): ByteArray? {
            val exact = File(root, name)
            if (exact.isFile) return exact.readBytes()
            val alt = list.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
            return File(root, alt).readBytes()
        }
        override fun has(name: String) = File(root, name).isFile || list.any { it.equals(name, ignoreCase = true) }
    }

    /** Приёмник поверх папки assets/ нового проекта. */
    class DirSink(private val assets: File) : Sink {
        override fun write(name: String, block: (OutputStream) -> Unit): String {
            assets.mkdirs()
            File(assets, name).outputStream().buffered().use(block)
            return "assets/$name"
        }
    }

    /** Источник в памяти (тесты, один JSON). */
    class MapSource(private val files: Map<String, ByteArray>) : Source {
        override fun names() = files.keys.toList()
        override fun read(name: String) = files[name] ?: files.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    class MapSink : Sink {
        val files = LinkedHashMap<String, ByteArray>()
        override fun write(name: String, block: (OutputStream) -> Unit): String {
            val b = java.io.ByteArrayOutputStream(); block(b); files[name] = b.toByteArray(); return "assets/$name"
        }
    }

    /** Безопасное имя файла картинки. */
    fun safeName(s: String): String = s.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(32).ifEmpty { "layer" }
}
