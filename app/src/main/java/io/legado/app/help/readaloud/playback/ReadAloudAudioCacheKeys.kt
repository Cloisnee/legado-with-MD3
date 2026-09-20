package io.legado.app.help.readaloud.playback

import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.utils.MD5Utils

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
