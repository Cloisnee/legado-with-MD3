package io.legado.app.help.readaloud.playback

import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.utils.MD5Utils
import java.io.File

/**
 * 朗读音频缓存「命名契约」（播放侧与批量缓存侧唯一真源）。
 *
 * 目录布局（见 ReadAloudAudioCacheRepository）：
 *   `<数据根>/data/audio/<书名>/<章序号+1>/<条目序号4位>_<内容哈希16位>.mp3`
 *   章节标题：`title_<内容哈希16位>.mp3`
 *
 * 内容哈希 = md5_16("$voiceKey-|-$speechRate-|-$text")，即：
 *  文本变化 / 声线变化 / 源级语速变化 → 哈希变化 → 旧文件自然失效（不再需要索引文件）。
 */
object ReadAloudAudioCacheKeys {

    /** 章节标题条目序号（不参与 x/y 统计） */
    const val TITLE_SEG_INDEX = -1

    /** 声线指纹：与旧 `sourceKeyForCue` 完全等价（保持缓存失效语义不变） */
    fun voiceKey(
        voice: ReadAloudVoice,
        emotion: String,
        performance: CharacterPerformanceProfile?,
        roleType: SpeechRoleType,
        httpTts: HttpTTS?,
    ): String = when (voice.engineType) {
        ReadAloudVoice.ENGINE_SYSTEM ->
            "system:${voice.id}:${voice.revision}:${voice.engineId}:${voice.speakerId}"

        ReadAloudVoice.ENGINE_CLOUD ->
            "cloud:${voice.id}:${voice.revision}:" +
                    "${CloudTtsEmotionMapper.VERSION}:$emotion:" +
                    "${CharacterPerformanceInstructionBuilder.VERSION}:" +
                    "${performance?.characterId.orEmpty()}:" +
                    "${performance?.updatedAt ?: 0L}:" +
                    "${CloudTtsRoleInstructionMapper.VERSION}:" +
                    roleType.storageValue

        ReadAloudVoice.ENGINE_TTS_SERVER ->
            "tts_server:${voice.id}:${voice.revision}:${voice.engineId}:${voice.speakerId}:$emotion"

        else -> httpTts?.url.orEmpty()
    }

    /** 源级语速刻度（0..80，1 倍速 = 5）：与 HttpReadAloudService.speechRate 保持一致 */
    fun speechRateScale(followSys: Boolean, ttsSpeechRate: Int): Int =
        (if (followSys) 5 else ttsSpeechRate) + 5

    fun contentHash(voiceKey: String, speechRate: Int, text: String): String =
        MD5Utils.md5Encode16("$voiceKey-|-$speechRate-|-$text")

    fun cueFileName(segIndex: Int, hash: String): String =
        "${segIndex.toString().padStart(4, '0')}_$hash.mp3"

    fun titleFileName(hash: String): String = "title_$hash.mp3"

    /** 解析条目序号：剧本行文件返回 >=0；标题文件返回 [TITLE_SEG_INDEX]；其它返回 null */
    fun parseSegIndex(fileName: String): Int? {
        if (!fileName.endsWith(".mp3")) return null
        val base = fileName.removeSuffix(".mp3")
        if (base.startsWith("title_")) return TITLE_SEG_INDEX
        return base.substringBefore('_').toIntOrNull()
    }
}

/**
 * 缓存文件是否可用（存在、非空，且不是旧版「误包 WAV 头」的坏文件）。
 *
 * B21 白噪音修复：修复前的实现把 webm/ogg 等未识别字节按裸 PCM 包了 44 字节 WAV 头，
 * 播放为滋滋白噪音。此判定识别该产物（RIFF/WAVE 头 + data 起于压缩容器魔数）→ 归为无效，
 * 播放/批量合成路径据此自动重新合成，旧坏缓存无需手动清理。
 */
fun File.isUsableCacheFile(): Boolean {
    if (!exists() || length() <= 0L) return false
    return !isLegacyMisWrappedWav()
}

private fun File.isLegacyMisWrappedWav(): Boolean = runCatching {
    if (length() < 48L) return false
    val head = ByteArray(64)
    inputStream().use { ins ->
        var off = 0
        while (off < head.size) {
            val r = ins.read(head, off, head.size - off)
            if (r <= 0) break
            off += r
        }
        if (off < 48) return false
    }
    if (!head.asciiAt(0, "RIFF") || !head.asciiAt(8, "WAVE")) return false
    val d = 44
    head.asciiAt(d, "OggS") || head.asciiAt(d, "fLaC") || head.asciiAt(d, "#!AMR") ||
            head.asciiAt(d + 4, "ftyp") ||
            (head[d] == 0x1A.toByte() && head[d + 1] == 0x45.toByte() &&
                    head[d + 2] == 0xDF.toByte() && head[d + 3] == 0xA3.toByte())
}.getOrDefault(false)

private fun ByteArray.asciiAt(off: Int, s: String): Boolean {
    if (off + s.length > size) return false
    for (i in s.indices) {
        if (this[off + i] != s[i].code.toByte()) return false
    }
    return true
}
