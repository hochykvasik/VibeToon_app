package com.vibetoon.domain.export

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Спринт 32: видео QuickTime (.mov) с альфа-каналом без системных кодеков. На Android нет ни кодировщика ProRes 4444,
 * ни муксера MOV, поэтому файл собирается здесь: кадры - PNG (кодек QuickTime «png », глубина 32 = RGB + альфа,
 * без потерь), звук - несжатый PCM 16 бит little-endian («sowt»). Такой MOV открывают After Effects, Premiere,
 * Final Cut Pro, DaVinci Resolve, ffmpeg/VLC.
 *
 * Порядок: ftyp, wide+mdat (данные кадров и звука вперемешку, по мере поступления), moov в конце. Если данных
 * больше 4 ГБ, атом wide превращается в 64-битный заголовок mdat, а таблица смещений - в co64.
 * Не потокобезопасен: [addFrame]/[addAudio] из одного потока, затем [finish] или [abort].
 */
class MovWriter(
    private val file: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Частота звука; 0 - без звуковой дорожки. Звук всегда стерео, 16 бит. */
    val sampleRate: Int = 0
) : AutoCloseable {
    init {
        require(width in 1..65535 && height in 1..65535) { "Некорректный размер видео" }
        require(fps in 1..1000) { "Некорректная частота кадров" }
        require(sampleRate == 0 || sampleRate in 8000..65535) { "Некорректная частота звука" }
    }

    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
    private val wideAt: Long
    private val mdatAt: Long
    private val frameOffsets = ArrayList<Long>()
    private val frameSizes = ArrayList<Int>()
    private val audioOffsets = ArrayList<Long>()
    private val audioCounts = ArrayList<Int>()   // PCM-кадров (сэмпл на все каналы) в каждом куске
    private var audioTotal = 0L
    private var finished = false

    /** Масштаб времени видео: кратный fps, не меньше 600 (как в файлах QuickTime), один кадр = [frameDelta]. */
    val timeScale: Int = fps * ((599 + fps) / fps)
    val frameDelta: Int get() = timeScale / fps

    init {
        // ftyp qt
        raf.write(be32(20)); raf.write("ftyp".toByteArray()); raf.write("qt  ".toByteArray()); raf.write(be32(0x200)); raf.write("qt  ".toByteArray())
        wideAt = raf.filePointer
        raf.write(be32(8)); raf.write("wide".toByteArray())
        mdatAt = raf.filePointer
        raf.write(be32(0)); raf.write("mdat".toByteArray())   // размер допишется в finish
    }

    val frames: Int get() = frameSizes.size
    val audioSamples: Long get() = audioTotal

    /** Кадр - готовый файл PNG (RGBA, неумноженная альфа). */
    fun addFrame(png: ByteArray) {
        check(!finished) { "MOV уже закрыт" }
        require(png.size >= 8 && png[1] == 'P'.code.toByte() && png[2] == 'N'.code.toByte() && png[3] == 'G'.code.toByte()) { "Кадр MOV должен быть PNG" }
        frameOffsets += raf.filePointer
        frameSizes += png.size
        raf.write(png)
    }

    /** Кусок звука: стерео-пары 16 бит (L, R, L, R...), как выдаёт AudioMix.render. */
    fun addAudio(interleaved: ShortArray, count: Int = interleaved.size / 2) {
        check(!finished) { "MOV уже закрыт" }
        check(sampleRate > 0) { "MOV создан без звука" }
        if (count <= 0) return
        val bytes = ByteArray(count * 4)
        for (i in 0 until count * 2) {
            val v = interleaved[i].toInt()
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        audioOffsets += raf.filePointer
        audioCounts += count
        audioTotal += count
        raf.write(bytes)
    }

    /** Дописывает moov и закрывает файл. */
    fun finish() {
        check(!finished) { "MOV уже закрыт" }
        check(frames > 0) { "В MOV нет ни одного кадра" }
        val mdatEnd = raf.filePointer
        val mdatSize = mdatEnd - mdatAt
        if (mdatSize <= 0xFFFFFFFFL) {
            raf.seek(mdatAt); raf.write(be32(mdatSize.toInt()))
        } else {
            // wide (8 байт) + заголовок mdat (8 байт) -> 64-битный заголовок mdat (16 байт) с того же места
            raf.seek(wideAt); raf.write(be32(1)); raf.write("mdat".toByteArray()); raf.write(be64(mdatEnd - wideAt))
        }
        raf.seek(mdatEnd)
        raf.write(moov())
        finished = true
        raf.close()
    }

    /** Прерывание: файл закрывается и удаляется. */
    fun abort() {
        if (!finished) { finished = true; runCatching { raf.close() } }
        file.delete()
    }

    override fun close() { if (!finished) abort() }

    // ---------------------------------------------------------------- атомы

    private val movieScale = 1000
    private val videoDurationMedia: Long get() = frames.toLong() * frameDelta
    private fun toMovie(media: Long, scale: Int): Long = media * movieScale / scale

    private fun moov(): ByteArray {
        val videoMovie = toMovie(videoDurationMedia, timeScale)
        val audioMovie = if (sampleRate > 0 && audioTotal > 0) toMovie(audioTotal, sampleRate) else 0L
        val tracks = if (audioMovie > 0) 2 else 1
        return atom("moov") {
            write(mvhd(maxOf(videoMovie, audioMovie), tracks + 1))
            write(videoTrak(videoMovie))
            if (audioMovie > 0) write(audioTrak(audioMovie))
        }
    }

    private fun mvhd(duration: Long, nextTrack: Int) = atom("mvhd") {
        writeInt(0); writeInt(0); writeInt(0)          // версия/флаги, создание, изменение
        writeInt(movieScale); writeInt(duration.toInt())
        writeInt(0x00010000); writeShort(0x0100)        // скорость 1.0, громкость 1.0
        write(ByteArray(10))
        matrix()
        writeInt(0); writeInt(0); writeInt(0); writeInt(0); writeInt(0); writeInt(0)   // preview, poster, selection, current
        writeInt(nextTrack)
    }

    private fun DataOutputStream.matrix() {
        for (v in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) writeInt(v)
    }

    private fun tkhd(id: Int, duration: Long, audio: Boolean) = atom("tkhd") {
        writeInt(0x0000000F)                            // включена, в фильме, в превью, в постере
        writeInt(0); writeInt(0)
        writeInt(id); writeInt(0)
        writeInt(duration.toInt())
        writeInt(0); writeInt(0)
        writeShort(0); writeShort(0)                    // слой, группа
        writeShort(if (audio) 0x0100 else 0); writeShort(0)
        matrix()
        writeInt(if (audio) 0 else width shl 16); writeInt(if (audio) 0 else height shl 16)
    }

    private fun mdhd(scale: Int, duration: Long) = atom("mdhd") {
        writeInt(0); writeInt(0); writeInt(0)
        writeInt(scale); writeInt(duration.toInt())
        writeShort(0x7FFF)                              // язык не указан (Macintosh)
        writeShort(0)
    }

    private fun hdlr(type: String, subtype: String, name: String) = atom("hdlr") {
        writeInt(0); writeBytes(type); writeBytes(subtype)
        writeInt(0); writeInt(0); writeInt(0)
        writeByte(name.length); writeBytes(name)        // Pascal-строка, как в QuickTime
    }

    private fun dinf() = atom("dinf") {
        write(atom("dref") {
            writeInt(0); writeInt(1)
            write(atom("alis") { writeInt(1) })           // данные в этом же файле
        })
    }

    private fun videoTrak(duration: Long) = atom("trak") {
        write(tkhd(1, duration, audio = false))
        write(edts(duration))
        write(atom("mdia") {
            write(mdhd(timeScale, videoDurationMedia))
            write(hdlr("mhlr", "vide", "VideoHandler"))
            write(atom("minf") {
                write(atom("vmhd") { writeInt(1); writeShort(0x40); writeShort(0x8000); writeShort(0x8000); writeShort(0x8000) })
                write(hdlr("dhlr", "alis", "DataHandler"))
                write(dinf())
                write(atom("stbl") {
                    write(atom("stsd") { writeInt(0); writeInt(1); write(pngDescription()) })
                    write(atom("stts") { writeInt(0); writeInt(1); writeInt(frames); writeInt(frameDelta) })
                    write(atom("stsc") { writeInt(0); writeInt(1); writeInt(1); writeInt(1); writeInt(1) })
                    write(atom("stsz") { writeInt(0); writeInt(0); writeInt(frames); for (s in frameSizes) writeInt(s) })
                    write(chunkOffsets(frameOffsets))
                })
            })
        })
    }

    private fun edts(duration: Long) = atom("edts") {
        write(atom("elst") { writeInt(0); writeInt(1); writeInt(duration.toInt()); writeInt(0); writeInt(0x00010000) })
    }

    /** Описание кадров: кодек «png », глубина 32 (с альфой), без таблицы цветов. */
    private fun pngDescription() = atom("png ") {
        write(ByteArray(6)); writeShort(1)              // зарезервировано, индекс ссылки на данные
        writeShort(0); writeShort(0)                    // версия, ревизия
        writeBytes("appl")                              // производитель
        writeInt(0); writeInt(0x400)                    // временное качество, пространственное - без потерь
        writeShort(width); writeShort(height)
        writeInt(72 shl 16); writeInt(72 shl 16)
        writeInt(0); writeShort(1)                      // размер данных, кадров в сэмпле
        val name = "PNG"
        writeByte(name.length); writeBytes(name); write(ByteArray(31 - name.length))
        writeShort(32)                                  // глубина: 32 = millions of colors + alpha
        writeShort(-1)                                  // без таблицы цветов
    }

    private fun audioTrak(duration: Long) = atom("trak") {
        write(tkhd(2, duration, audio = true))
        write(edts(duration))
        write(atom("mdia") {
            write(mdhd(sampleRate, audioTotal))
            write(hdlr("mhlr", "soun", "SoundHandler"))
            write(atom("minf") {
                write(atom("smhd") { writeInt(0); writeShort(0); writeShort(0) })
                write(hdlr("dhlr", "alis", "DataHandler"))
                write(dinf())
                write(atom("stbl") {
                    write(atom("stsd") { writeInt(0); writeInt(1); write(pcmDescription()) })
                    write(atom("stts") { writeInt(0); writeInt(1); writeInt(audioTotal.toInt()); writeInt(1) })
                    val runs = sampleToChunkRuns(audioCounts)
                    write(atom("stsc") {
                        writeInt(0); writeInt(runs.size)
                        for ((first, count) in runs) { writeInt(first); writeInt(count); writeInt(1) }
                    })
                    write(atom("stsz") { writeInt(0); writeInt(4); writeInt(audioTotal.toInt()) })
                    write(chunkOffsets(audioOffsets))
                })
            })
        })
    }

    /** Звук QuickTime версии 0: «sowt» = PCM 16 бит little-endian, 2 канала. */
    private fun pcmDescription() = atom("sowt") {
        write(ByteArray(6)); writeShort(1)
        writeShort(0); writeShort(0); writeInt(0)       // версия, ревизия, производитель
        writeShort(2); writeShort(16)                   // каналы, бит
        writeShort(0); writeShort(0)                    // compression id, packet size
        writeInt(sampleRate shl 16)                     // 16.16
    }

    private fun chunkOffsets(offsets: List<Long>): ByteArray =
        if (offsets.any { it > 0xFFFFFFFFL }) atom("co64") { writeInt(0); writeInt(offsets.size); for (o in offsets) writeLong(o) }
        else atom("stco") { writeInt(0); writeInt(offsets.size); for (o in offsets) writeInt(o.toInt()) }

    companion object {
        /** Сжатие stsc: (номер первого куска с 1, сэмплов в куске) - новая запись только при смене числа сэмплов. */
        fun sampleToChunkRuns(counts: List<Int>): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            counts.forEachIndexed { i, c -> if (out.isEmpty() || out.last().second != c) out += (i + 1) to c }
            return out
        }

        private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun be64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }

        private fun atom(type: String, body: DataOutputStream.() -> Unit): ByteArray {
            val bos = ByteArrayOutputStream()
            val d = DataOutputStream(bos)
            d.writeInt(0); d.writeBytes(type)
            d.body()
            d.flush()
            val bytes = bos.toByteArray()
            val n = bytes.size
            bytes[0] = (n ushr 24).toByte(); bytes[1] = (n ushr 16).toByte(); bytes[2] = (n ushr 8).toByte(); bytes[3] = n.toByte()
            return bytes
        }
    }
}
