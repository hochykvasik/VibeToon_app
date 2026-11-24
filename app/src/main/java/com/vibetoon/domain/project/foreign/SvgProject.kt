package com.vibetoon.domain.project.foreign

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind
import com.vibetoon.domain.project.ProjectData
import com.vibetoon.domain.project.ProjectMeta
import com.vibetoon.domain.vector.SvgImport
import kotlin.math.max
import kotlin.math.min

/**
 * Спринт 36: файл SVG -> новый проект VibeToon. Холст - размер рисунка (мелкие иконки увеличиваются до 1024 px),
 * каждая группа верхнего уровня - отдельный векторный слой (картинка слоя дорисуется из фигур при открытии,
 * как у Lottie). Заметки о том, что не перенесено, попадают в итог импорта.
 */
object SvgProject {
    const val APP = "SVG"

    fun convert(text: String, title: String): ForeignImport.Result {
        val r = try { SvgImport.parse(text, title) } catch (e: SvgImport.SvgException) { throw ForeignImport.Unsupported(e.message ?: SvgImport.MSG_DAMAGED) }
        val (cw, ch) = SvgImport.canvasFor(r)
        val layers = SvgImport.scaleTo(r, cw, ch)
        if (layers.isEmpty()) throw ForeignImport.Unsupported(SvgImport.MSG_EMPTY)
        val ids = ForeignImport.Ids()
        val vw = (cw * min(1f, 1536f / max(cw, ch))).toInt().coerceAtLeast(1)
        val vh = (ch * min(1f, 1536f / max(cw, ch))).toInt().coerceAtLeast(1)
        // нижний слой списка рисуется первым: порядок документа SVG (первые элементы - снизу)
        val media = layers.map { l ->
            val id = ids.next()
            MediaItem(id, MediaKind.IMAGE, l.name, "assets/svg_$id.png", vw, vh, 0f, 0f, cw.toFloat(), ch.toFloat(), vector = true, vectors = l.shapes)
        }
        val meta = ProjectMeta("import", title, cw, ch)
        return ForeignImport.Result(ProjectData(meta, media = media), APP, r.notes)
    }
}
