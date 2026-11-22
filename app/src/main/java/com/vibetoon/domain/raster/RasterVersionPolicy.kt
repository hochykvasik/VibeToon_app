package com.vibetoon.domain.raster

import com.vibetoon.domain.project.MediaItem
import com.vibetoon.domain.project.MediaKind

/** Смена имени версии не является правкой геометрии или заменой логического слоя. */
object RasterVersionPolicy {
    fun sameLayerIgnoringVersion(actual: MediaItem?, expected: MediaItem): Boolean =
        actual != null && actual.id == expected.id && actual.copy(file = expected.file) == expected

    fun keepCurrentFile(snapshot: MediaItem, current: MediaItem?, versioned: Boolean): MediaItem =
        if (versioned && current != null && snapshot.id == current.id &&
            snapshot.kind == MediaKind.IMAGE && current.kind == MediaKind.IMAGE &&
            snapshot.cells.isEmpty() && current.cells.isEmpty()) snapshot.copy(file = current.file) else snapshot

    /** Данные GL содержат little-endian ширину и высоту, затем RGBA. */
    fun matchesDimensions(bytes: ByteArray, width: Int, height: Int): Boolean {
        if (bytes.size < 8 || width <= 0 || height <= 0) return false
        fun i32(o: Int): Int = (bytes[o].toInt() and 255) or ((bytes[o + 1].toInt() and 255) shl 8) or
            ((bytes[o + 2].toInt() and 255) shl 16) or ((bytes[o + 3].toInt() and 255) shl 24)
        return i32(0) == width && i32(4) == height && width.toLong() * height * 4 + 8 <= bytes.size.toLong()
    }

    fun isVersionPath(path: String): Boolean = path.startsWith("assets/raster_") && path.endsWith(".png")
}
