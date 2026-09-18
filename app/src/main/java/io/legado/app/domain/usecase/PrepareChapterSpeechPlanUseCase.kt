package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV2
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter

/**
 * Builds the persisted speech plan used by a read-aloud session.
 *
 * W3 V2 链路：
 *  1) 分析管线 V2 产物（AI 完整链，由调度器/审查页后台产出）存在 → 直接消费（即时出声）；
 *  2) 否则走本地规则快速链（先出声不等 AI），AI 由 AnalysisSchedulerV2 后台补全，
 *     完成后重新进入本章（或审查页刷新）即切换到 V2 剧本。
 */
class PrepareChapterSpeechPlanUseCase(
    private val analyzeChapterSpeech: AnalyzeChapterSpeechUseCase,
    private val resolveLocalSpeakers: ResolveLocalSpeakersUseCase,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        preferredDefaultVoiceId: String? = null,
        @Suppress("UNUSED_PARAMETER") analysisMode: SpeechAnalysisMode = SpeechAnalysisMode.Rule,
        @Suppress("UNUSED_PARAMETER") analysisReasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
        useMultiSpeaker: Boolean = true,
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()

        // ---- V2 优先：AI 完整链剧本直接消费 ----
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        val v2 = runCatching {
            chapterSpeechGateway.getAnalysis(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                contentHash = contentHash,
                resolverVersion = SpeechAnalysisPipelineV2.RESOLVER_VERSION,
            )
        }.onFailure {
            AppLog.put("读取 V2 剧本失败，走本地快速链: ${it.localizedMessage}", it)
        }.getOrNull()
        if (v2 != null && v2.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)) {
            val segments = runCatching { chapterSpeechGateway.getSegments(v2.id) }.getOrDefault(emptyList())
            if (segments.isNotEmpty()) {
                AppLog.put("多角色计划：消费 V2 剧本（${v2.status.storageValue}，${segments.size} 段）")
                return buildSpeechPlan(
                    bookUrl = bookUrl,
                    segments = segments,
                    preferredDefaultVoiceId = preferredDefaultVoiceId,
                    characterPerformances = characterPerformanceMap(bookUrl),
                    useMultiSpeaker = useMultiSpeaker,
                )
            }
        }

        // ---- 快速链：本地规则先行（先出声） ----
        val analysis = analyzeChapterSpeech(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            resolverVersion = RuleBasedSpeechSegmenter.VERSION,
        )
        val locallyResolved = resolveLocalSpeakers(
            analysisResult = analysis,
            paragraphs = paragraphs,
        )
        return buildSpeechPlan(
            bookUrl = bookUrl,
            segments = locallyResolved.segments,
            preferredDefaultVoiceId = preferredDefaultVoiceId,
            characterPerformances = locallyResolved.characterPerformances.associateBy { it.characterId },
            useMultiSpeaker = useMultiSpeaker,
        )
    }

    private suspend fun characterPerformanceMap(
        bookUrl: String,
    ): Map<String, CharacterPerformanceProfile> = runCatching {
        bookKnowledgeGateway.getCharacterProfiles(bookUrl, 200)
            .filter { it.status == BookCharacterProfile.STATUS_ACTIVE }
            .map { profile ->
                CharacterPerformanceProfile(
                    characterId = profile.id,
                    role = profile.role,
                    voiceGender = profile.voiceGender,
                    voiceAgeBand = profile.voiceAgeBand,
                    personality = profile.personality,
                    updatedAt = profile.updatedAt,
                )
            }.associateBy { it.characterId }
    }.getOrDefault(emptyMap())
}
