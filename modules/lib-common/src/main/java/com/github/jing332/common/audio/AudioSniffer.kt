package com.github.jing332.common.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 插件合成音频「格式嗅探」与播放前归一化（对齐补丁版播放器直通语义的公共件）。
 *
 * 背景（真机白噪音事故根因）：插件返回的音频可以是任意容器格式（mp3/webm/ogg/wav/m4a/amr…），
 * 也可能是裸 PCM。旧实现只认 wav/mp3 魔数，其余一律「按裸 PCM 包 WAV 头」——
 * webm/ogg/amr 等压缩容器被整段当作 PCM 播放 → 滋滋白噪音（合成成功但播放一片噪音）。
 *
 * 现行口径（与补丁版播放器直通语义一致）：
 *  - 已知容器（wav / mp3(含 ID3) / ogg / webm / mp4(m4a) / flac / amr）→ 原样直通，播放端自行解码；
 *  - 未识别 → 视作裸 PCM，按条目声明采样率包 WAV 头。
 *
 * 注意：音频缓存文件沿用 `.mp3` 命名契约（内容可能是 wav/webm 等，播放端按内容嗅探解码）。
 */
object AudioSniffer {

    enum class AudioKind { WAV, MP3, OGG, WEBM, MP4, FLAC, AMR, UNKNOWN }

    /** 播放前归一化结果：bytes=实际落盘/播放字节；wrappedPcm=是否走了「裸 PCM 包 WAV」兜底 */
    data class Normalized(val bytes: ByteArray, val kind: AudioKind, val wrappedPcm: Boolean)

    fun sniff(b: ByteArray): AudioKind = when {
        b.size >= 12 && b.asciiAt(0, "RIFF") && b.asciiAt(8, "WAVE") -> AudioKind.WAV
        b.size >= 3 && b.asciiAt(0, "ID3") -> AudioKind.MP3
        b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0 -> AudioKind.MP3
        b.size >= 4 && b.asciiAt(0, "OggS") -> AudioKind.OGG
        b.size >= 4 && (b[0].toInt() and 0xFF) == 0x1A && (b[1].toInt() and 0xFF) == 0x45 &&
                (b[2].toInt() and 0xFF) == 0xDF && (b[3].toInt() and 0xFF) == 0xA3 -> AudioKind.WEBM
        b.size >= 8 && b.asciiAt(4, "ftyp") -> AudioKind.MP4
        b.size >= 4 && b.asciiAt(0, "fLaC") -> AudioKind.FLAC
        b.size >= 5 && b.asciiAt(0, "#!AMR") -> AudioKind.AMR
        else -> AudioKind.UNKNOWN
    }

    /** 已知可播放容器（可直通交给播放器解码） */
    fun isPlayableContainer(kind: AudioKind): Boolean = kind != AudioKind.UNKNOWN

    /** 播放前归一化：已知容器直通；未识别按裸 PCM 包 WAV 头 */
    fun normalizeForPlayback(bytes: ByteArray, sampleRate: Int): Normalized {
        val kind = sniff(bytes)
        return if (isPlayableContainer(kind)) {
            Normalized(bytes, kind, wrappedPcm = false)
        } else {
            Normalized(wrapPcmInWav(bytes, sampleRate), kind, wrappedPcm = true)
        }
    }

    /** 缓存/试听落盘文件后缀（与 [sniff] 结果对应） */
    fun extName(kind: AudioKind): String = when (kind) {
        AudioKind.WAV -> "wav"
        AudioKind.MP3 -> "mp3"
        AudioKind.OGG -> "ogg"
        AudioKind.WEBM -> "webm"
        AudioKind.MP4 -> "m4a"
        AudioKind.FLAC -> "flac"
        AudioKind.AMR -> "amr"
        AudioKind.UNKNOWN -> "bin"
    }

    /** 调试用：前 N 字节 hex 预览 */
    fun hexPreview(bytes: ByteArray, n: Int = 8): String =
        bytes.take(n).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** PCM(16bit 单声道) 包 WAV 头（对齐补丁版 wrapPcmInWav 语义） */
    fun wrapPcmInWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val sr = sampleRate.takeIf { it > 0 } ?: 24000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sr)
        header.putInt(sr * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }

    private fun ByteArray.asciiAt(offset: Int, s: String): Boolean {
        if (offset + s.length > size) return false
        for (i in s.indices) {
            if (this[offset + i] != s[i].code.toByte()) return false
        }
        return true
    }
}
