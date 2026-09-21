package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV3
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * Builds the persisted speech plan used by a read-aloud session.
 *
 * V4（脚本复刻）链路：
 *  1) 分析管线 V3 产物（调度器后台产出）存在 → 直接消费；声线按 角色记录库（三池分配结果）挂载；
 *  2) 否则本地规则 v2 快速链先出声（引号包裹判定），AI 由调度器后台补全，
 *     完成后重新进入本章（或审查页刷新）即切换到 V3 剧本。
 */
class PrepareChapterSpeechPlanUseCase(
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val recordsStore: ReadAloudDataRepository,
    private val pipeline: SpeechAnalysisPipelineV3,
    private val analysisConfig: AnalysisConfigStore,
) {

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        preferredDefaultVoiceId: String? = null,
        useMultiSpeaker: Boolean = true,
        bookName: String = "",
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val overrides = voiceOverrides(bookName, bookUrl, chapterIndex)

        // ---- V3 优先：脚本复刻管线产物直接消费（即时出声） ----
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        val v3 = runCatching {
            chapterSpeechGateway.getAnalysis(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                contentHash = contentHash,
                resolverVersion = SpeechAnalysisPipelineV3.RESOLVER_VERSION,
            )
        }.onFailure {
            AppLog.put("读取 V3 剧本失败，走本地快速链: ${it.localizedMessage}", it)
        }.getOrNull()
        if (v3 != null && v3.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)) {
            val segments = runCatching { chapterSpeechGateway.getSegments(v3.id) }.getOrDefault(emptyList())
            if (segments.isNotEmpty()) {
                AppLog.put("多角色计划：消费 V3 剧本（${v3.status.storageValue}，${segments.size} 段）")
                return buildSpeechPlan(
                    bookUrl = bookUrl,
                    segments = segments,
                    preferredDefaultVoiceId = preferredDefaultVoiceId,
                    useMultiSpeaker = useMultiSpeaker,
                    voiceOverrides = overrides,
                )
            }
        }

        // ---- B8.3：DB 未命中 → 本地剧本文件回填（清应用数据后免重析 + 声线/情绪随剧本还原） ----
        val restored = runCatching {
            pipeline.restoreFromScriptFiles(
                bookUrl = bookUrl,
                bookName = bookName,
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                logMiss = false,
            )
        }.onFailure {
            AppLog.putAnalysis("本地剧本回填异常: ${it.localizedMessage}", it)
        }.getOrNull()
        if (restored != null && restored.segments.isNotEmpty()) {
            AppLog.put("多角色计划：本地剧本回填（${restored.segments.size} 段）")
            return buildSpeechPlan(
                bookUrl = bookUrl,
                segments = restored.segments,
                preferredDefaultVoiceId = preferredDefaultVoiceId,
                useMultiSpeaker = useMultiSpeaker,
                voiceOverrides = overrides,
            )
        }

        // ---- B10.4·S1：按「先用默认声线出声」开关决定：等待分析就绪 或 立即快速链出声 ----
        val cfg = runCatching { analysisConfig.load() }.getOrDefault(AnalysisConfigStore.Config())
        if (!cfg.fallbackDefaultVoice) {
            AppLog.putAudio("【音频缓存】第${chapterIndex + 1}章 等待分析就绪（最长 ${cfg.waitAnalysisSec} 秒）…")
            val waited = awaitAnalysisReady(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                contentHash = contentHash,
                timeoutMs = cfg.waitAnalysisSec * 1000L,
            )
            if (waited != null) {
                val segments = runCatching { chapterSpeechGateway.getSegments(waited.id) }
                    .getOrDefault(emptyList())
                if (segments.isNotEmpty()) {
                    AppLog.put("多角色计划：等待后就绪，消费 V3 剧本（${segments.size} 段）")
                    // B10.4.3：等待期间分析刚写入角色声线分配——重算覆盖表，避免整章落到「默认对话」声线
                    val freshOverrides = voiceOverrides(bookName, bookUrl, chapterIndex)
                    return buildSpeechPlan(
                        bookUrl = bookUrl,
                        segments = segments,
                        preferredDefaultVoiceId = preferredDefaultVoiceId,
                        useMultiSpeaker = useMultiSpeaker,
                        voiceOverrides = freshOverrides,
                    )
                }
            }
            AppLog.putAudio(
                "【音频缓存】第${chapterIndex + 1}章 等待分析未就绪（超时），先用默认声线出声" +
                    "（分析完成后重进本章即切换）"
            )
        } else {
            // 快速链：本地规则 v2 先行（先出声不等 AI）
            // 此时无人物归属，话语会落到「默认对话」声线；分析完成后重进本章即切换到 V3 剧本
            AppLog.putAudio(
                "【音频缓存】第${chapterIndex + 1}章 朗读分析未就绪，先用默认声线出声" +
                    "（分析完成后重进本章即切换）"
            )
        }
        val local = pipeline.quickLocalSegments(paragraphs)
        return buildSpeechPlan(
            bookUrl = bookUrl,
            segments = local,
            preferredDefaultVoiceId = preferredDefaultVoiceId,
            useMultiSpeaker = useMultiSpeaker,
            voiceOverrides = overrides,
        )
    }

    /** 等待本章 V3 分析就绪（最长 [timeoutMs]）；由设置开关「关=等待分析后再出声」调用 */
    private suspend fun awaitAnalysisReady(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        timeoutMs: Long,
    ): ChapterSpeechAnalysis? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val v3 = runCatching {
                chapterSpeechGateway.getAnalysis(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    contentHash = contentHash,
                    resolverVersion = SpeechAnalysisPipelineV3.RESOLVER_VERSION,
                )
            }.getOrNull()
            if (v3 != null && v3.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)) {
                return v3
            }
            if (System.currentTimeMillis() >= deadline) return null
            currentCoroutineContext().ensureActive()
            delay(1_000)
        }
    }

    /** 角色记录库 + 声线库分组 → 声线覆盖表：narrator / duihuaA / duihuaB + 角色名→标签 */
    private suspend fun voiceOverrides(
        bookName: String,
        bookUrl: String,
        chapterIndex: Int,
    ): Map<String, ReadAloudVoice> {
        if (bookName.isBlank()) return emptyMap()
        return runCatching {
            val catalog = voiceGateway.getEnabledVoices()
                .filter { it.engineType == ReadAloudVoice.ENGINE_TTS_SERVER }
            fun byTag(tag: String): ReadAloudVoice? = tag.takeIf { it.isNotBlank() }
                ?.let { t -> catalog.firstOrNull { v -> v.speakerId == t } }
            val groups = recordsStore.loadActiveVoiceGroups()
            val narratorGroup = groups.firstOrNull {
                it.effectiveRoleType() == VoiceBankRoleType.NARRATOR && it.tags.isNotEmpty()
            }
            val duihuaGroup = groups.firstOrNull {
                it.effectiveRoleType() == VoiceBankRoleType.DEFAULT_DIALOG && it.tags.isNotEmpty()
            }
            // 默认对话：章内稳定随机取一（同一章多次进入结果一致；跨章自然变化）
            val rnd = Random(bookUrl.hashCode() * 31 + chapterIndex)
            val duihuaA = duihuaGroup?.tags?.filter { it.startsWith("duihuaA") }
                ?.takeIf { it.isNotEmpty() }?.random(rnd)
            val duihuaB = duihuaGroup?.tags?.filter { it.startsWith("duihuaB") }
                ?.takeIf { it.isNotEmpty() }?.random(rnd)
            buildMap {
                // 旁白：组内置顶者（列表首个）作为发音人
                byTag(narratorGroup?.tags?.firstOrNull().orEmpty())
                    ?.let { put(BookVoiceBinding.SUBJECT_NARRATOR, it) }
                byTag(duihuaA.orEmpty())?.let { put(BookVoiceBinding.SUBJECT_UNKNOWN_MALE, it) }
                byTag(duihuaB.orEmpty())?.let { put(BookVoiceBinding.SUBJECT_UNKNOWN_FEMALE, it) }
                recordsStore.loadBookRecords(bookName).forEach { r ->
                    byTag(r.voice)?.let { put(r.name, it) }
                }
            }
        }.onFailure {
            AppLog.put("声线覆盖表构建失败: ${it.localizedMessage}", it)
        }.getOrDefault(emptyMap())
    }
}
