package com.vibetoon.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.vibetoon.nativebridge.LayerInfo
import com.vibetoon.nativebridge.LayerKind
import com.vibetoon.nativebridge.PsdNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val TAG_PSD = "VT/Psd"

data class ParseSummary(val layers: List<LayerInfo>, val millis: Long, val path: String, val name: String)

class PsdRepository(private val context: Context) {

    /** Копирует assets/test/test.psd в filesDir (если нет или другой размер) и парсит. */
    suspend fun parseTestPsd(dest: File? = null): Result<ParseSummary> =
        parse("test.psd") { copyAsset("test/test.psd", dest ?: File(context.filesDir, "test.psd")) }

    /** Парсит уже лежащий на диске PSD (файл внутри папки проекта). */
    suspend fun parseFile(file: File, name: String): Result<ParseSummary> = parse(name) { file }

    /**
     * Парсит PSD, выбранный пользователем (системный диалог OpenDocument). Файл копируется
     * потоком в filesDir/user.psd: нативному парсеру нужен путь к обычному файлу, а Uri
     * из SAF в C++ не открыть.
     */
    suspend fun parseUri(uri: Uri, dest: File? = null): Result<ParseSummary> =
        parse(displayName(uri)) { copyUri(uri, dest ?: File(context.filesDir, "user.psd")) }

    private suspend fun parse(name: String, getFile: () -> File): Result<ParseSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = getFile()
                val t0 = System.nanoTime()
                val layers = PsdNative.parseLayers(file.absolutePath).toList()
                val ms = (System.nanoTime() - t0) / 1_000_000
                logLayers(layers, ms)
                ParseSummary(layers, ms, file.absolutePath, name)
            }.onFailure { Log.e(TAG_PSD, "parse failed", it) }
        }

    private fun copyAsset(assetPath: String, out: File): File {
        out.parentFile?.mkdirs()
        // Всегда перезаписываем: сравнение длины с available() у сжатого asset ненадёжно
        context.assets.open(assetPath).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun copyUri(uri: Uri, out: File): File {
        out.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Не удалось открыть файл")
        input.use { src -> out.outputStream().use { src.copyTo(it) } }
        Log.i(TAG_PSD, "copied ${out.length() / 1048576} MB from $uri")
        return out
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "PSD"

    private fun logLayers(layers: List<LayerInfo>, ms: Long) {
        for (l in layers) {
            Log.i(
                TAG_PSD,
                "[%3d] %-12s vis=%d op=%.2f rect=(%d,%d %dx%d) blend=%s clip=%d parent=%d \"%s\"".format(
                    l.id, l.kind, if (l.isVisible) 1 else 0, l.opacity,
                    l.x, l.y, l.width, l.height, l.blendMode,
                    if (l.isClipped) 1 else 0, l.parentId, l.name
                )
            )
        }
        val groups = layers.count { it.kind == LayerKind.GROUP_OPEN || it.kind == LayerKind.GROUP_CLOSED }
        val dividers = layers.count { it.kind == LayerKind.GROUP_END }
        val real = layers.count { it.kind == LayerKind.LAYER }
        Log.i(
            TAG_PSD,
            "SUMMARY records=${layers.size} layers=$real groups=$groups dividers=$dividers " +
                "hidden=${layers.count { !it.isVisible }} empty=${layers.count { it.isEmpty }} jni+parse=$ms ms"
        )
    }
}
