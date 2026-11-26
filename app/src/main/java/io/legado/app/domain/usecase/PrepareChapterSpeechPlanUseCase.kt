package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudCatalogRepository
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaDirectionGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.gateway.RaDirectionLine
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 构建朗读会话的播放计划：
 *  1) 本章已有 V4 分析（RESOLVED）→ 读台词 + 导演行 → 按 characterId/发声方案出声；
 *  2) 未就绪 → 按设置等待（“开播准备”前身），超时后用本地引号规则快速链出声。
 *
 * 文件真源已拔除：不再读取剧本文件、不再回填、不再按名字覆盖声线。
 */
class PrepareChapterSpeechPlanUseCase(
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val raBuild: RaBuildGateway,
    private val raDirection: RaDirectionGateway,
    private val catalog: ReadAloudCatalogRepository,
    private val pipeline: SpeechAnalysisPipelineV4,
    private val analysisConfig: AnalysisConfigStore,
) {

    suspend operator fun invoke(
        bookUrl: String,
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        useMultiSpeaker: Boolean = true,
        bookName: String = "",
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val chLabel = runCatching { catalog.chapterLabel(bookUrl, chapterIndex) }
            .getOrDefault("第${chapterIndex + 1}章")
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)

        var analysis = runCatching {
            raBuild.getAnalysis(
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = chapterIndex,
                contentHash = contentHash,
                resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("读取 V4 分析失败，走本地快速链: ${it.localizedMessage}", it)
        }.getOrNull()
        if (analysis?.status == RaAnalysisStatus.FAILED) {
            AppLog.putAudio("【V4·$chLabel·开播】本章分析失败 → 本地快速链（下次进入会重试）")
        } else if (analysis?.status != RaAnalysisStatus.RESOLVED) {
            val cfg = runCatching { analysisConfig.load() }.getOrElse {
                if (it is CancellationException) throw it
                AnalysisConfigStore.Config()
            }
            if (!cfg.fallbackDefaultVoice) {
                AppLog.putAudio("【V4·$chLabel·开播】等待身份分析（最长 ${cfg.waitAnalysisSec}s）")
                analysis = awaitAnalysisReady(
                    bookKey = bookKey,
                    sourceKey = sourceKey,
                    chapterIndex = chapterIndex,
                    contentHash = contentHash,
                    timeoutMs = cfg.waitAnalysisSec * 1000L,
                )
            }
        }
        if (analysis != null && analysis.status == RaAnalysisStatus.RESOLVED) {
            runCatching { pipeline.ensureLegacyForReady(bookUrl, bookName, analysis, paragraphs) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    AppLog.putAnalysis("【V4·$chLabel·前台补做】${error.localizedMessage}", error)
                }
            val segments = runCatching { raBuild.segments(analysis.id) }.getOrDefault(emptyList())
            if (segments.isNotEmpty()) {
                val plan = runCatching { raDirection.plan(analysis.id, MODULE) }.getOrNull()
                val lines = if (plan != null) {
                    runCatching { raDirection.lines(plan.id) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                AppLog.putAnalysis("【V4·$chLabel·开播】身份就绪：消费 ${segments.size} 段")
                return buildSpeechPlan(
                    bookKey = bookKey,
                    segments = segments,
                    useMultiSpeaker = useMultiSpeaker,
                    emotionBySegment = lines.emotionMap(),
                    instructionBySegment = lines.instructionMap(),
                )
            }
        }

        AppLog.putAudio(
            "【V4·$chLabel·开播】分析未就绪 → 本地快速链（引号规则 + 默认对话声线；分析完成后重进本章切换）",
        )
        val local = pipeline.quickLocalSegments(paragraphs)
        return buildSpeechPlan(
            bookKey = bookKey,
            segments = local,
            useMultiSpeaker = useMultiSpeaker,
        )
    }

    private suspend fun awaitAnalysisReady(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        timeoutMs: Long,
    ): RaAnalysis? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val analysis = runCatching {
                raBuild.getAnalysis(
                    bookKey = bookKey,
                    sourceKey = sourceKey,
                    chapterIndex = chapterIndex,
                    contentHash = contentHash,
                    resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                )
            }.getOrNull()
            if (analysis != null && analysis.status == RaAnalysisStatus.RESOLVED) return analysis
            if (System.currentTimeMillis() >= deadline) return null
            currentCoroutineContext().ensureActive()
            delay(1_000)
        }
    }

    private fun List<RaDirectionLine>.emotionMap(): Map<String, String> =
        mapNotNull { line -> line.emotionTag.takeIf { it.isNotBlank() }?.let { line.segmentId to it } }
            .toMap()

    private fun List<RaDirectionLine>.instructionMap(): Map<String, String> =
        mapNotNull { line -> line.instruction.takeIf { it.isNotBlank() }?.let { line.segmentId to it } }
            .toMap()

    companion object {
        const val MODULE = "PLAYBACK_BASE"
    }
}
