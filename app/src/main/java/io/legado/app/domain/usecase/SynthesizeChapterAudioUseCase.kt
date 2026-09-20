package io.legado.app.domain.usecase

import android.app.Application
import com.github.jing332.tts.readaloud.TtsServerSynthesizer
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudAudioCacheRepository
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.help.readaloud.playback.ReadAloudAudioCacheKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 批量合成一章的朗读音频（音频管理页「缓存缺失条目」/「整本缓存」）。
 *
 * 与播放侧共用同一「命名契约」（[ReadAloudAudioCacheKeys]）与同一份本地剧本
 * （`chapter_cache.<书>.json` / `all_clean_text_<书>.txt`），因此批量产出的文件播放时可直接命中。
 *
 * 声线解析与播放侧同源（旁白组首个 / 角色记录标签 / 默认对话 duihuaA·B 章内稳定随机）；
 * 目前只合成 `tts_server` 引擎声线（系统/云/HTTP 引擎需各自运行时环境，播放时会按需合成）。
 */
class SynthesizeChapterAudioUseCase(
    private val app: Application,
    private val dataRepository: ReadAloudDataRepository,
    private val audioCache: ReadAloudAudioCacheRepository,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val settingsGateway: ReadAloudSettingsGateway,
    private val syncTtsServerVoices: SyncTtsServerVoicesUseCase,
) {

    data class Result(val done: Int, val failed: Int, val skipped: Int)

    suspend operator fun invoke(
        book: String,
        chapterIndex: Int,
        onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val lines = dataRepository.loadChapterScript(book, chapterIndex)
        if (lines.isEmpty()) {
            AppLog.putAudio("【音频缓存】批量合成 第${chapterIndex + 1}章 跳过：本章暂无本地剧本")
            return@withContext Result(0, 0, 0)
        }
        // 声线目录（Room 镜像表）可能尚未同步（典型：刚清过应用数据）→ 先补写，否则会全部跳过
        runCatching { syncTtsServerVoices() }
        AppLog.putAudio(
            "【音频缓存】批量合成 第${chapterIndex + 1}章 开始（剧本 ${lines.size} 条）"
        )
        val settings = settingsGateway.currentSettings
        val speechRate = ReadAloudAudioCacheKeys.speechRateScale(
            followSys = settings.ttsFollowSys,
            ttsSpeechRate = settings.ttsSpeechRate,
        )
        val voices = voiceGateway.getEnabledVoices()
            .filter { it.engineType == ReadAloudVoice.ENGINE_TTS_SERVER }
        fun byTag(tag: String): ReadAloudVoice? =
            tag.takeIf { it.isNotBlank() }?.let { t -> voices.firstOrNull { it.speakerId == t } }

        val groups = dataRepository.loadActiveVoiceGroups()
        val narrator = byTag(
            groups.firstOrNull {
                it.effectiveRoleType() == VoiceBankRoleType.NARRATOR && it.tags.isNotEmpty()
            }?.tags?.firstOrNull().orEmpty()
        )
        val duihuaGroup = groups.firstOrNull {
            it.effectiveRoleType() == VoiceBankRoleType.DEFAULT_DIALOG && it.tags.isNotEmpty()
        }
        // 与播放侧一致：章内稳定随机（种子 = bookUrl + 章序号）
        val bookUrl = dataRepository.loadBookUrl(book)
        val rnd = Random(bookUrl.hashCode() * 31 + chapterIndex)
        val duihuaA = byTag(
            duihuaGroup?.tags?.filter { it.startsWith("duihuaA") }
                ?.takeIf { it.isNotEmpty() }?.random(rnd).orEmpty()
        )
        val duihuaB = byTag(
            duihuaGroup?.tags?.filter { it.startsWith("duihuaB") }
                ?.takeIf { it.isNotEmpty() }?.random(rnd).orEmpty()
        )
        val characterVoices = dataRepository.loadBookRecords(book)
            .mapNotNull { r -> byTag(r.voice)?.let { r.name to it } }
            .toMap()

        val synthesizer = TtsServerSynthesizer(app)
        var done = 0
        var failed = 0
        var skipped = 0
        var skippedNoVoice = 0
        var skippedOtherEngine = 0
        val total = lines.size
        lines.forEachIndexed { index, row ->
            currentCoroutineContext().ensureActive()
            val text = row.text
            if (text.isBlank()) {
                skipped++
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            
            val isNarrator = row.speaker.isBlank() || row.speaker == NARRATOR_TAG
            val voice = if (isNarrator) {
                narrator
            } else {
                characterVoices[row.speaker] ?: duihuaA ?: duihuaB
            }
            if (voice == null) {
                skipped++
                skippedNoVoice++
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            if (voice.engineType != ReadAloudVoice.ENGINE_TTS_SERVER) {
                skipped++
                skippedOtherEngine++
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            val voiceKey = ReadAloudAudioCacheKeys.voiceKey(
                voice = voice,
                emotion = row.emotion,
                performance = null,
                roleType = if (isNarrator) SpeechRoleType.Narrator else SpeechRoleType.Character,
                httpTts = null,
            )
            val hash = ReadAloudAudioCacheKeys.contentHash(voiceKey, speechRate, text)
            val file = audioCache.cueFile(book, chapterIndex, index, hash)
            if (file.exists() && file.length() > 0L) {
                done++
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            val t0 = System.currentTimeMillis()
            val timeoutMs = settings.ttsSynthTimeoutSec.coerceIn(5, 120) * 1000L
            var outcome = synthesizer.synthesize(voice.engineId, voice.speakerId, text, file, timeoutMs)
            val maxRetry = settings.ttsMaxRetry.coerceIn(0, 10)
            var retried = 0
            while (!outcome.ok && retried < maxRetry) {
                retried++
                AppLog.putAudio(
                    "【音频缓存】批量合成失败，第 $retried/$maxRetry 次重试 #$index ${row.speaker}"
                )
                delay(500)
                outcome = synthesizer.synthesize(voice.engineId, voice.speakerId, text, file, timeoutMs)
            }
            if (outcome.ok) {
                done++
                AppLog.putAudio(
                    "【音频缓存】批量合成 #$index ${row.speaker} ${file.length() / 1024}KB" +
                        " ${System.currentTimeMillis() - t0}ms | ${text.take(24)}"
                )
            } else {
                failed++
                runCatching { file.delete() }
                AppLog.putAudio(
                    "【音频缓存】批量合成失败 第${chapterIndex + 1}章 #$index ${row.speaker}:" +
                        " ${outcome.reason}（已重试 $retried 次） | ${text.take(24)}"
                )
            }
            onProgress(done + failed + skipped, total)
        }
        AppLog.putAudio(
            "【音频缓存】批量合成 第${chapterIndex + 1}章 完成：新增/命中 $done，失败 $failed，" +
                "跳过 $skipped（无声线 $skippedNoVoice / 非内置引擎 $skippedOtherEngine）"
        )
        Result(done, failed, skipped)
    }

    companion object {
        const val NARRATOR_TAG = "旁白"
    }
}
