package com.vibetoon.render

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.vibetoon.domain.export.CodecSupport
import com.vibetoon.domain.export.VideoCodec

/**
 * Спринт 32: что умеют кодировщики видео этого устройства (MediaCodecList). Опрос один раз за процесс - список
 * кодеков не меняется. Сами правила выбора и откатов - в чистом VideoExportPolicy.
 */
object VideoCodecs : CodecSupport {
    private class Enc(val name: String, val caps: MediaCodecInfo.CodecCapabilities, val surface: Boolean, val yuv: Boolean)

    private val encoders: Map<VideoCodec, List<Enc>> by lazy {
        val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        VideoCodec.values().associateWith { codec ->
            list.filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(codec.mime, true) } }.mapNotNull { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(codec.mime)
                    val formats = caps.colorFormats
                    Enc(info.name, caps,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in formats,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats)
                }.getOrNull()
            }
        }
    }

    /** Кодировщики [codec], подходящие по формату цвета ([color]) и размеру; аппаратные идут первыми (порядок списка). */
    fun candidates(codec: VideoCodec, width: Int, height: Int, color: Int): List<String> =
        encoders[codec].orEmpty().filter { e ->
            val colorOk = when (color) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> e.surface
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> e.yuv
                else -> color in e.caps.colorFormats
            }
            colorOk && sizeOk(e, width, height)
        }.map { it.name }

    private fun sizeOk(e: Enc, width: Int, height: Int): Boolean =
        runCatching { e.caps.videoCapabilities.isSizeSupported(width, height) }.getOrDefault(false)

    fun available(codec: VideoCodec): Boolean = encoders[codec].orEmpty().isNotEmpty()

    override fun supports(codec: VideoCodec, width: Int, height: Int): Boolean =
        encoders[codec].orEmpty().any { (it.surface || it.yuv) && sizeOk(it, width, height) }

    override fun maxFps(codec: VideoCodec, width: Int, height: Int): Double? =
        encoders[codec].orEmpty().filter { sizeOk(it, width, height) }.mapNotNull {
            runCatching { it.caps.videoCapabilities.getSupportedFrameRatesFor(width, height).upper }.getOrNull()
        }.maxOrNull()

    override fun maxBitrate(codec: VideoCodec): Int? =
        encoders[codec].orEmpty().mapNotNull { runCatching { it.caps.videoCapabilities.bitrateRange.upper }.getOrNull() }.maxOrNull()

    /** Профиль/уровень, который заявляет кодировщик [name] для [codec]. */
    fun profileLevels(name: String, codec: VideoCodec): Array<MediaCodecInfo.CodecProfileLevel> =
        encoders[codec].orEmpty().firstOrNull { it.name == name }?.caps?.profileLevels ?: emptyArray()
}
