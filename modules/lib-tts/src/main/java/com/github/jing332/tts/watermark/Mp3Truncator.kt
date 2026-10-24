package com.github.jing332.tts.watermark

/**
 * MP3 帧索引与按帧截断（纯逻辑，无 Android 依赖，可单测）。
 *
 * 水印只切尾巴，所以只需要"时间 → 字节偏移"映射；按帧边界截断即无重编码损失。
 */
object Mp3Truncator {

    class Mp3Head(val dataStart: Int, val frameCount: Int, val durationSec: Double)

    private val BR_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BR_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val BR_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val BR_V1_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
    private val SR_TAB = arrayOf(
        intArrayOf(11025, 12000, 8000, 0),
        intArrayOf(0, 0, 0, 0),
        intArrayOf(22050, 24000, 16000, 0),
        intArrayOf(44100, 48000, 32000, 0)
    )

    private class FrameInfo(val len: Int, val sr: Int)

    private fun frameAt(bytes: ByteArray, i: Int): FrameInfo? {
        if (i + 4 > bytes.size) return null
        if ((bytes[i].toInt() and 0xFF) != 0xFF ||
            (bytes[i + 1].toInt() and 0xE0) != 0xE0
        ) return null
        val h = ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
        val ver = (h shr 19) and 3
        val layer = (h shr 17) and 3
        val brI = (h shr 12) and 0xF
        val srI = (h shr 10) and 3
        val pad = (h shr 9) and 1
        if (ver == 1 || layer == 0 || brI == 0xF || srI == 3) return null
        val table = when {
            layer == 1 -> if (ver == 3) BR_V1_L3 else BR_V2_L3
            layer == 2 -> BR_V1_L2
            else -> BR_V1_L1
        }
        val br = table[brI]
        val sr = SR_TAB[ver][srI]
        if (br == 0 || sr == 0) return null
        val len = if (layer == 1) {
            144 * br * 1000 / sr + pad
        } else {
            (if (ver == 3 || layer == 1) 144 else 72) * br * 1000 / sr + pad
        }
        if (len <= 4) return null
        return FrameInfo(len, sr)
    }

    /** 解析 ID3v2 头长度并建立索引；非 MP3 返回 null */
    fun head(bytes: ByteArray): Mp3Head? {
        val start = if (bytes.size > 10 &&
            bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()
        ) {
            (((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)) + 10
        } else 0
        if (start >= bytes.size - 4) return null
        var i = start
        var count = 0
        var dur = 0.0
        while (i < bytes.size - 4) {
            val f = frameAt(bytes, i) ?: break
            if (i + f.len > bytes.size) break
            i += f.len
            count++
            dur += 1152.0 / f.sr
        }
        if (count < 8) return null
        return Mp3Head(start, count, dur)
    }

    /** 返回第一个"时间 ≥ cutSec"的帧起始偏移 */
    fun offsetAt(bytes: ByteArray, head: Mp3Head, cutSec: Double): Int? {
        var i = head.dataStart
        var t = 0.0
        while (i < bytes.size - 4) {
            val f = frameAt(bytes, i) ?: break
            if (i + f.len > bytes.size) break
            if (t >= cutSec) return i
            t += 1152.0 / f.sr
            i += f.len
        }
        return null
    }
}
