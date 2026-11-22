package com.vibetoon.domain.export

import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Запись многослойного PSD (Adobe Photoshop, версия 1, RGB 8 бит, без сжатия).
 * Слои идут снизу вверх; группа - это маркер конца «</Layer group>» (kind = END), слои внутри и сама папка (kind = GROUP_OPEN).
 * Пиксели слоя лежат во временном файле [data]: плоскости A, R, G, B подряд, каждая w*h байт (не premultiplied).
 */
class PsdLayer(
    val name: String,
    val left: Int, val top: Int, val w: Int, val h: Int,
    val blend: String = "norm",
    val opacity: Int = 255,
    val visible: Boolean = true,
    val kind: Int = NORMAL,
    val data: File? = null
) {
    companion object { const val NORMAL = 0; const val GROUP_OPEN = 1; const val END = 3 }
}

object PsdWriter {
    private fun DataOutputStream.str4(s: String) { write(s.toByteArray(Charsets.ISO_8859_1), 0, 4) }

    /** Имя слоя в Pascal-формате (ASCII, кириллица заменяется на «?»: настоящее имя лежит в блоке luni), с выравниванием до 4 байт. */
    fun pascalName(name: String): ByteArray {
        val ascii = name.map { if (it.code in 32..126) it.code.toByte() else '?'.code.toByte() }.take(255)
        val len = 1 + ascii.size
        val padded = (len + 3) / 4 * 4
        val out = ByteArray(padded)
        out[0] = ascii.size.toByte()
        ascii.forEachIndexed { i, b -> out[1 + i] = b }
        return out
    }

    private fun infoBlock(key: String, payload: ByteArray): ByteArray {
        val even = payload.size + (payload.size and 1)
        val bo = java.io.ByteArrayOutputStream()
        val d = DataOutputStream(bo)
        d.str4("8BIM"); d.str4(key); d.writeInt(even); d.write(payload); if (even != payload.size) d.write(0)
        return bo.toByteArray()
    }

    private fun unicodeName(name: String): ByteArray {
        val bo = java.io.ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.writeInt(name.length); for (c in name) d.writeShort(c.code)
        return bo.toByteArray()
    }

    /** Блок «extra data» записи слоя: пустые маски, имя и (для групп) разделитель lsct. */
    private fun extra(l: PsdLayer): ByteArray {
        val bo = java.io.ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.writeInt(0)   // layer mask data
        d.writeInt(0)   // blending ranges
        d.write(pascalName(l.name))
        if (l.kind != PsdLayer.NORMAL) d.write(infoBlock("lsct", java.nio.ByteBuffer.allocate(4).putInt(l.kind).array()))
        d.write(infoBlock("luni", unicodeName(if (l.kind == PsdLayer.END) "</Layer group>" else l.name)))
        return bo.toByteArray()
    }

    private fun channelLen(l: PsdLayer): Int = 2 + l.w * l.h

    /**
     * Пишет файл. [composite] - итоговое изображение холста, плоскости A, R, G, B (по w*h байт каждая), может быть null (тогда пустое).
     * [out] не закрывается.
     */
    fun write(out: OutputStream, width: Int, height: Int, layers: List<PsdLayer>, composite: ByteArray?) {
        val d = DataOutputStream(out.buffered(1 shl 16))
        // заголовок
        d.str4("8BPS"); d.writeShort(1); d.write(ByteArray(6)); d.writeShort(4); d.writeInt(height); d.writeInt(width); d.writeShort(8); d.writeShort(3)
        d.writeInt(0)   // color mode data
        d.writeInt(0)   // image resources
        // записи слоёв
        val recs = java.io.ByteArrayOutputStream(); val r = DataOutputStream(recs)
        for (l in layers) {
            r.writeInt(l.top); r.writeInt(l.left); r.writeInt(l.top + l.h); r.writeInt(l.left + l.w)
            r.writeShort(4)
            for (id in intArrayOf(-1, 0, 1, 2)) { r.writeShort(id); r.writeInt(channelLen(l)) }
            r.str4("8BIM"); r.str4(l.blend.padEnd(4).take(4))
            r.writeByte(l.opacity); r.writeByte(0)
            r.writeByte((if (l.visible) 0 else 2) or 8); r.writeByte(0)
            val ex = extra(l)
            r.writeInt(ex.size); r.write(ex)
        }
        var chanBytes = 0L
        for (l in layers) chanBytes += 4L * channelLen(l)
        var infoLen = 2L + recs.size() + chanBytes
        val pad = (infoLen and 1L).toInt()
        infoLen += pad
        d.writeInt((4 + infoLen + 4).toInt())   // длина секции слоёв и масок: длина layer info + само поле длины + глобальная маска
        d.writeInt(infoLen.toInt())
        d.writeShort(-layers.size)               // отрицательное число: первый альфа-канал - прозрачность итогового изображения
        d.write(recs.toByteArray())
        for (l in layers) {
            val src = l.data?.takeIf { l.w > 0 && l.h > 0 }
            val plane = l.w * l.h
            val buf = ByteArray(maxOf(plane, 1))
            src?.inputStream()?.buffered(1 shl 16)?.use { ins ->
                for (c in 0 until 4) {
                    d.writeShort(0)
                    var read = 0
                    while (read < plane) { val n = ins.read(buf, 0, minOf(buf.size, plane - read)); if (n <= 0) break; d.write(buf, 0, n); read += n }
                    if (read < plane) d.write(ByteArray(plane - read))
                }
            } ?: run { for (c in 0 until 4) { d.writeShort(0); if (plane > 0) d.write(ByteArray(plane)) } }
        }
        if (pad == 1) d.write(0)
        d.writeInt(0)   // global layer mask info
        // итоговое изображение: порядок каналов R, G, B, A
        d.writeShort(0)
        val plane = width * height
        val order = intArrayOf(1, 2, 3, 0)
        for (c in order) {
            if (composite != null && composite.size >= plane * 4) d.write(composite, c * plane, plane) else d.write(ByteArray(plane))
        }
        d.flush()
    }
}
