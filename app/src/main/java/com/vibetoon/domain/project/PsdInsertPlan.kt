package com.vibetoon.domain.project

import com.vibetoon.domain.model.LayerBlend
import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind
import kotlin.math.min

/**
 * Пакет 23: вставка другого PSD в текущий проект (из библиотеки или меню «+») и настройка PSD Import Mode.
 * Слои PSD проекта по-прежнему одни (source.psd); вставленный PSD раскладывается в медиа-слои (картинки и группы),
 * поэтому живёт рядом с любыми другими слоями, не затрагивая слои и анимацию основного PSD.
 *
 * Чистая часть: какие узлы создаются и в каком порядке, как сводится картинка (операции рисования), кадры режима
 * «Слои как кадры», вписывание в холст и соответствие режимов наложения.
 */
object PsdInsertPlan {
    /** Режим импорта (Settings → Import → PSD Import Mode); код хранится в настройках. */
    enum class Mode(val code: Int, val title: String) {
        /** Слои и группы как в файле: имена, видимость, прозрачность, наложение, обтравка. */
        LAYERS(0, "Слои как в файле"),
        /** Один слой - сведённая картинка видимых слоёв. */
        FLAT(1, "Одним слоем"),
        /** Каждый верхний слой или группа - кадр покадрового слоя (снизу вверх). */
        FRAMES(2, "Слои как кадры");

        companion object {
            fun of(code: Int): Mode = entries.firstOrNull { it.code == code } ?: LAYERS
        }
    }

    /** Больше кадров за раз не создаётся (как у GIF и видео). */
    const val MAX_FRAMES = 300

    /** Узел для режима «Слои как в файле»; [parent] - id группы PSD или -1 (корень вставки). */
    data class Node(
        val psdId: Int, val group: Boolean, val parent: Int, val name: String,
        val opacity: Float, val visible: Boolean, val blend: String, val clipped: Boolean,
        val x: Int, val y: Int, val w: Int, val h: Int
    )

    enum class Role {
        /** Обычный слой. */
        PLAIN,
        /** Основа обтравки: следующие CLIPPED рисуются только по её пикселям, прозрачность и наложение - у всей связки. */
        BASE,
        /** Слой, обтравленный по последней BASE. */
        CLIPPED
    }

    /** Операция сведения: слой [psdId] с итоговой прозрачностью (с учётом групп) и режимом наложения. */
    data class Op(val psdId: Int, val opacity: Float, val blend: String, val role: Role)

    /** Кадр режима «Слои как кадры»: имя верхнего слоя или группы и его операции сведения. */
    data class Frame(val name: String, val ops: List<Op>)

    private fun children(layers: List<LayerInfo>): Map<Int, List<LayerInfo>> =
        layers.filter { it.kind != LayerKind.GROUP_END }.groupBy { it.parentId }.mapValues { (_, v) -> v.sortedBy { it.id } }

    private fun isGroup(l: LayerInfo) = l.kind == LayerKind.GROUP_OPEN || l.kind == LayerKind.GROUP_CLOSED

    /**
     * Узлы «Слоёв как в файле» в порядке добавления (снизу вверх, дети раньше своей группы - как в файле).
     * Пустые слои и группы без слоёв отбрасываются, скрытые сохраняются (глаз выключен).
     */
    fun tree(layers: List<LayerInfo>): List<Node> {
        val kids = children(layers)
        val out = ArrayList<Node>()
        fun visit(parent: Int, guard: Int): Boolean {
            if (guard > 64) return false
            var any = false
            for (l in kids[parent].orEmpty()) {
                if (isGroup(l)) {
                    val start = out.size
                    if (visit(l.id, guard + 1)) {
                        out += Node(l.id, true, parent, l.name, l.opacity, l.isVisible, l.blendMode, false, 0, 0, 0, 0)
                        any = true
                    } else while (out.size > start) out.removeAt(out.size - 1)
                } else if (!l.isEmpty) {
                    out += Node(l.id, false, parent, l.name, l.opacity, l.isVisible, l.blendMode, l.isClipped, l.x, l.y, l.width, l.height)
                    any = true
                }
            }
            return any
        }
        visit(-1, 0)
        return out
    }

    /** Ключ наложения для свойств слоя: поддерживаемые панелью слоёв режимы, иначе Normal. */
    fun layerBlend(key: String): String = if (key in LayerBlend.keys) key else "norm"

    /**
     * Операции сведения поддерева [root] (-1 - весь файл) снизу вверх. Скрытые слои и группы пропускаются; прозрачность
     * групп перемножается; обтравленные слои идут за своей основой (у скрытой основы пропадают и они). Обтравка к группе
     * не поддерживается (как и в редакторе): такой слой рисуется обычным.
     */
    fun composeOps(layers: List<LayerInfo>, root: Int = -1): List<Op> {
        val kids = children(layers)
        val out = ArrayList<Op>()
        fun visit(list: List<LayerInfo>, alpha: Float, guard: Int) {
            if (guard > 64) return
            var baseIdx = -1          // индекс открытой основы в out или -1
            var baseHidden = false    // основа скрыта: её обтравленные слои тоже
            var chainOpen = false     // после основы шли только обтравленные слои
            for (l in list) {
                if (isGroup(l)) {
                    chainOpen = false; baseIdx = -1; baseHidden = false
                    if (l.isVisible) visit(kids[l.id].orEmpty(), alpha * l.opacity, guard + 1)
                    continue
                }
                if (l.isClipped && chainOpen) {
                    if (baseHidden || !l.isVisible || l.isEmpty) continue
                    if (baseIdx >= 0) {
                        if (out[baseIdx].role == Role.PLAIN) out[baseIdx] = out[baseIdx].copy(role = Role.BASE)
                        out += Op(l.id, l.opacity, l.blendMode, Role.CLIPPED)
                    }
                    continue
                }
                chainOpen = true
                baseHidden = !l.isVisible || l.isEmpty
                if (baseHidden) { baseIdx = -1; continue }
                out += Op(l.id, alpha * l.opacity, l.blendMode, Role.PLAIN)
                baseIdx = out.size - 1
            }
        }
        if (root < 0) visit(kids[-1].orEmpty(), 1f, 0)
        else {
            val r = layers.firstOrNull { it.id == root } ?: return emptyList()
            if (!r.isVisible) return emptyList()
            if (isGroup(r)) visit(kids[r.id].orEmpty(), r.opacity, 1) else visit(listOf(r), 1f, 0)
        }
        return out
    }

    /**
     * Кадры «Слоёв как кадры»: каждый видимый верхний слой или группа (снизу вверх) - кадр; обтравленные верхние слои
     * входят в кадр своей основы. Пустые кадры отбрасываются, кадров не больше [MAX_FRAMES].
     */
    fun frames(layers: List<LayerInfo>): List<Frame> {
        val kids = children(layers)
        val out = ArrayList<Frame>()
        var current: Pair<LayerInfo, MutableList<LayerInfo>>? = null
        fun flush() {
            val c = current ?: return
            current = null
            val (head, rest) = c
            val ops = if (isGroup(head)) composeOps(layers, head.id)
                else composeOps(listOf(head.copy(parentId = -1)) + rest.map { it.copy(parentId = -1) })
            if (ops.isNotEmpty() && out.size < MAX_FRAMES) out += Frame(head.name, ops)
        }
        for (l in kids[-1].orEmpty()) {
            if (!isGroup(l) && l.isClipped && current != null && !isGroup(current!!.first)) { current!!.second += l; continue }
            flush()
            if (l.isVisible) current = l to ArrayList()
        }
        flush()
        return out
    }

    /** Сколько видимых верхних кадров было до обрезки (для сообщения). */
    fun frameCandidates(layers: List<LayerInfo>): Int =
        children(layers)[-1].orEmpty().count { it.isVisible && (isGroup(it) || (!it.isClipped && !it.isEmpty)) }

    /** Вписывание документа в холст целиком (по центру, с увеличением или уменьшением): [x, y, масштаб]. */
    fun fit(docW: Int, docH: Int, canvasW: Int, canvasH: Int): FloatArray {
        if (docW <= 0 || docH <= 0) return floatArrayOf(0f, 0f, 1f)
        val s = min(canvasW.toFloat() / docW, canvasH.toFloat() / docH)
        return floatArrayOf((canvasW - docW * s) / 2f, (canvasH - docH * s) / 2f, s)
    }

    /** Масштаб декодирования: не больше 1 (пиксели не увеличиваются) - рамка слоя на холсте задаётся отдельно. */
    fun decodeScale(fitScale: Float): Float = if (fitScale.isFinite() && fitScale > 0f) min(1f, fitScale) else 1f

    /** Режим наложения PSD -> имя android.graphics.BlendMode (API 29+); null - обычное наложение. */
    fun blendName(key: String): String? = when (key) {
        "mul " -> "MULTIPLY"; "scrn" -> "SCREEN"; "over" -> "OVERLAY"; "dark", "dkCl" -> "DARKEN"; "lite", "lgCl" -> "LIGHTEN"
        "div " -> "COLOR_DODGE"; "idiv" -> "COLOR_BURN"; "hLit" -> "HARD_LIGHT"; "sLit" -> "SOFT_LIGHT"; "diff" -> "DIFFERENCE"
        "smud" -> "EXCLUSION"; "lddg" -> "PLUS"; "hue " -> "HUE"; "sat " -> "SATURATION"; "colr" -> "COLOR"; "lum " -> "LUMINOSITY"
        else -> null
    }

    /** До API 29: имя PorterDuff.Mode для тех режимов, что в нём есть; null - обычное наложение. */
    fun porterDuffName(blendName: String?): String? = when (blendName) {
        "MULTIPLY" -> "MULTIPLY"; "SCREEN" -> "SCREEN"; "OVERLAY" -> "OVERLAY"; "DARKEN" -> "DARKEN"; "LIGHTEN" -> "LIGHTEN"; "PLUS" -> "ADD"
        else -> null
    }

    /** Имя файла PSD в assets проекта: читаемое имя + короткий уникальный хвост; source.psd занят PSD проекта. */
    fun assetName(displayName: String, unique: String): String {
        val base = displayName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").trim('_').take(40).ifEmpty { "psd" }
        return "${base}_${unique.take(6)}.psd"
    }

    /** Название для слоя-группы: имя файла без расширения и уникального хвоста [assetName]. */
    fun titleOf(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').replace(Regex("_[0-9a-fA-F]{6}$"), "").ifEmpty { "PSD" }

    /** PSD проекта (его слои уже на холсте) - не вставляется второй раз. */
    fun isProjectPsd(fileName: String) = fileName.equals("source.psd", ignoreCase = true)
}
