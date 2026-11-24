package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudAudioCacheRepository
import io.legado.app.data.repository.ReadAloudCatalogRepository
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaDirectionGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudEmotion
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.playback.LoudnessNormalizer
import io.legado.app.help.readaloud.playback.ReadAloudAudioCacheKeys
import io.legado.app.help.readaloud.playback.isUsableCacheFile
import com.github.jing332.tts.readaloud.TtsServerSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批量合成一章的朗读音频（音频管理页「缓存缺失条目」/「整本缓存」）。
 *
 * 与播放侧共用同一 V4 播放计划（characterId → voiceProfile → voiceId）、同一缓存命名契约。
 * 只合成 tts_server 引擎声线；系统声线句先跳过。
 */
class SynthesizeChapterAudioUseCase(
    private val audioCache: ReadAloudAudioCacheRepository,
    private val catalog: ReadAloudCatalogRepository,
    private val raBuild: RaBuildGateway,
    private val raDirection: RaDirectionGateway,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val settingsGateway: ReadAloudSettingsGateway,
    private val loudness: LoudnessNormalizer,
) {

    data class Result(
        val done: Int,
        val failed: Int,
        val skipped: Int,
        val files: List<File> = emptyList(),
        val skippedOtherEngine: Int = 0,
    )

    suspend operator fun invoke(
        book: String,
        chapterIndex: Int,
        onProgress: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
        speechRateOverride: Int? = null,
    ): Result = withContext(Dispatchers.IO) {
        val summary = catalog.booksWithData().firstOrNull { it.name == book || it.bookKey == book }
        if (summary == null) {
            AppLog.putAudio("【音频缓存】批量合成跳过：未找到书籍 $book")
            return@withContext Result(0, 0, 0)
        }
        val chLabel = catalog.chapterLabel(summary.bookUrl, chapterIndex)
        val analysis = raBuild.getBuild(summary.bookKey, summary.sourceKey, chapterIndex)?.analysisId
            ?.let { raBuild.analysisById(it) }
            ?: raBuild.latestAnalysisForSource(summary.bookKey, summary.sourceKey, chapterIndex)
        if (analysis == null) {
            AppLog.putAudio("【音频缓存·$chLabel】批量合成跳过：本章暂无分析")
            return@withContext Result(0, 0, 0)
        }
        val segments = raBuild.segments(analysis.id)
        if (segments.isEmpty()) {
            AppLog.putAudio("【音频缓存·$chLabel】批量合成跳过：本章无台词")
            return@withContext Result(0, 0, 0)
        }
        val directionPlan = raDirection.plan(analysis.id, PrepareChapterSpeechPlanUseCase.MODULE)
        val lines = directionPlan?.let { raDirection.lines(it.id) }.orEmpty()
        val speechPlan = buildSpeechPlan(
            bookKey = summary.bookKey,
            segments = segments,
            useMultiSpeaker = true,
            emotionBySegment = lines.mapNotNull {
                it.emotionTag.takeIf(String::isNotBlank)?.let { tag -> it.segmentId to tag }
            }.toMap(),
            instructionBySegment = lines.mapNotNull {
                it.instruction.takeIf(String::isNotBlank)?.let { text -> it.segmentId to text }
            }.toMap(),
        )
        val settings = settingsGateway.currentSettings
        val speechRate = speechRateOverride ?: ReadAloudAudioCacheKeys.speechRateScale(
            followSys = settings.ttsFollowSys,
            ttsSpeechRate = settings.ttsSpeechRate,
        )
        val synthesizer = TtsServerSynthesizer(audioCache.appContext())
        var done = 0
        var failed = 0
        var skipped = 0
        var skippedNoVoice = 0
        var skippedOtherEngine = 0
        val total = speechPlan.size
        val files = ArrayList<File>()
        speechPlan.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val text = item.segment.text
            if (text.isBlank()) {
                skipped++
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            val voice = item.voice
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
                emotion = item.emotionTag,
                instruction = item.instruction,
                roleType = item.segment.roleType,
            )
            val hash = ReadAloudAudioCacheKeys.contentHash(voiceKey, speechRate, text)
            val file = audioCache.cueFile(book, chapterIndex, index, hash)
            if (file.isUsableCacheFile()) {
                done++
                files.add(file)
                onProgress(done + failed + skipped, total)
                return@forEachIndexed
            }
            val t0 = System.currentTimeMillis()
            val timeoutMs = settings.ttsSynthTimeoutSec.coerceIn(5, 120) * 1000L
            val emotion = if (item.segment.roleType == SpeechRoleType.Narrator) {
                ""
            } else {
                ReadAloudEmotion.forPlugin(item.emotionTag)
            }
            var outcome = synthesizer.synthesize(
                voice.traitsJson,
                text,
                file,
                timeoutMs,
                emotion,
                settings.watermarkRetry,
            )
            val maxRetry = settings.ttsMaxRetry.coerceIn(0, 10)
            var retried = 0
            while (!outcome.ok && retried < maxRetry) {
                retried++
                AppLog.putAudio("【音频缓存·$chLabel】批量合成失败，第 $retried/$maxRetry 次重试 #$index")
                delay(500)
                outcome = synthesizer.synthesize(
                    voice.traitsJson,
                    text,
                    file,
                    timeoutMs,
                    emotion,
                    settings.watermarkRetry,
                )
            }
            if (outcome.ok) {
                done++
                files.add(file)
                AppLog.putAudio(
                    "【音频缓存·$chLabel】批量合成 #$index ${voice.displayName} ${file.length() / 1024}KB" +
                        " ${System.currentTimeMillis() - t0}ms | ${text.take(24)}",
                )
                loudness.measureAsync(voice, file)
            } else {
                failed++
                runCatching { file.delete() }
                AppLog.putAudio(
                    "【音频缓存·$chLabel】批量合成失败 #$index ${voice.displayName}:" +
                        " ${outcome.reason}（已重试 $retried 次） | ${text.take(24)}",
                )
            }
            onProgress(done + failed + skipped, total)
        }
        AppLog.putAudio(
            "【音频缓存·$chLabel】批量合成完成：新增/命中 $done，失败 $failed，" +
                "跳过 $skipped（无声线 $skippedNoVoice / 非内置引擎 $skippedOtherEngine）",
        )
        Result(done, failed, skipped, files, skippedOtherEngine)
    }
}
