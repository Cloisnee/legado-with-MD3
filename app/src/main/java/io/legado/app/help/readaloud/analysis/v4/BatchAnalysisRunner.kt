package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.help.readaloud.analysis.queueRefs
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaBuildInput
import io.legado.app.domain.gateway.RaBuildPublication
import io.legado.app.domain.gateway.RaIdentityCommit
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.help.readaloud.analysis.AiPriority
import io.legado.app.help.readaloud.analysis.AiRequestControl
import io.legado.app.help.readaloud.analysis.AiSchedulerContext
import io.legado.app.help.readaloud.analysis.AiSpeechClient
import io.legado.app.help.readaloud.analysis.AiTaskKind
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.ChapterTextProvider
import io.legado.app.help.readaloud.analysis.QuoteSpeechRules
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV4
import io.legado.app.help.readaloud.analysis.ValidateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * 后台批量分析（03 §3）：
 *  阶段1 多章一次请求 → 逐章校验、失败章单独重试；
 *  阶段2 NDJSON 流式 → 每章一行，校验通过即逐章事务提交（EXTRACTED）；
 *  批内收敛：同一 `bkey` 跨章聚合，走 IdentityResolver.resolveBatch，回填 IDENTITY_READY。
 *
 * 只处理调用方传入的已缓存正文；不下载、不写导演计划（V4-4）。
 */
class BatchAnalysisRunner(
    private val raBuild: RaBuildGateway,
    private val raCharacters: RaCharacterGateway,
    private val resolver: IdentityResolver,
    private val casting: VoiceCastingService,
    private val aiModels: AiModelRepository,
    private val ai: AiSpeechClient,
    private val configStore: AnalysisConfigStore,
    private val wordLibrary: WordLibraryRepository,
    private val pipeline: SpeechAnalysisPipelineV4,
) {

    data class ChapterText(
        val chapterIndex: Int,
        val chapterLabel: String,
        val paragraphs: List<CanonicalSpeechParagraph>,
        val prevText: String,
        val nextText: String,
        val contentHash: String,
        val loadedAt: Long = System.currentTimeMillis(),
        val input: RaBuildInput? = null,
        val cachedBody: ChapterTextProvider.CachedBody? = null,
    )

    data class Outcome(
        val ready: List<Int> = emptyList(),
        val failed: List<Int> = emptyList(),
        val extracted: List<Int> = emptyList(),
    )

    private data class Convergence(
        val ready: List<Int> = emptyList(),
        val extracted: List<Int> = emptyList(),
    )

    private data class ChapterWork(
        val text: ChapterText,
        val units: List<ObservationAssembly.ParaUnits>,
        val unitCounts: Map<Int, Int>,
    )

    private data class Extracted(
        val text: ChapterText,
        val analysisId: String,
        val payload: Stage2Payload,
        val mentions: List<RaLocalMention>,
        val evidence: List<RaEvidence>,
        val contextBySpeaker: Map<String, String> = emptyMap(),
        val batchId: String? = null,
        val build: RaChapterBuild,
    )

    private data class ChapterSubmission(
        val chapter: Int, val json: JSONObject, val entities: Map<String, JSONObject>,
        val responseId: String, val modelId: String,
    )

    private data class Stage1Result(
        val ok: Map<Int, Map<Int, BooleanArray>>,
        val failed: Map<Int, String>,
    )

    suspend fun run(
        bookKey: String,
        sourceKey: String,
        chapters: List<ChapterText>,
        batchId: String,
        priority: AiPriority = AiPriority.P3_BACKGROUND,
        onChapterReady: (suspend (chapterIndex: Int, identityReady: Boolean) -> Unit)? = null,
        control: AiRequestControl? = null,
    ): Outcome {
        val currentChapters = eligibleChapters(bookKey, chapters)
        if (currentChapters.isEmpty()) return Outcome()
        val cfg = runCatching { configStore.load() }.getOrDefault(AnalysisConfigStore.Config())
        val bareWords = runCatching { wordLibrary.bareWords() }.getOrDefault(emptySet())
        val specialWords = runCatching { wordLibrary.specialWords() }.getOrDefault(emptySet())
        val works = currentChapters.map { text ->
            val units = ObservationAssembly.buildParaUnits(text.paragraphs)
            val counts = units.associate { it.paraIndex to it.units.size }.toMutableMap()
            text.paragraphs.forEach { counts.putIfAbsent(it.index, 0) }
            ChapterWork(
                text = text,
                units = units,
                unitCounts = counts,
            )
        }.associateBy { it.text.chapterIndex }

        val stage1Refs = runCatching { aiModels.queueRefs("stage1") }.getOrDefault(emptyList())
        val stage2Refs = runCatching { aiModels.queueRefs("stage2") }.getOrDefault(emptyList())
        val s1Context = AiSchedulerContext(
            kind = AiTaskKind.STAGE1,
            priority = priority,
            bookKey = bookKey,
            chapters = currentChapters.map { it.chapterIndex },
            preemptible = !priority.foreground,
            control = control,
            sourceKey = sourceKey,
        )
        val s2Context = s1Context.copy(kind = AiTaskKind.STAGE2)

        val failed = LinkedHashMap<Int, String>()
        val stage1Ok = if (stage1Refs.isEmpty()) {
            AppLog.putAnalysis("【V4·后台】未配置 stage1 模型 → 本地引号规则")
            works.values.associate { it.text.chapterIndex to localRanges(it.text.paragraphs) }
        } else {
            runStage1(
                cfg = cfg,
                works = works,
                refs = stage1Refs,
                context = s1Context,
                failed = failed,
            )
        }

        val segmentsByChapter = HashMap<Int, List<RaSegment>>()
        works.values.forEach { work ->
            val ranges = stage1Ok[work.text.chapterIndex] ?: return@forEach
            segmentsByChapter[work.text.chapterIndex] =
                ObservationAssembly.assemble(work.text.paragraphs, ranges)
        }

        val extracted = ConcurrentHashMap<Int, Extracted>()
        val stage2Chapters = works.values.filter { work ->
            work.text.chapterIndex !in failed && work.text.chapterIndex in segmentsByChapter
        }
        val directReady = ArrayList<Int>()
        val superseded = HashSet<Int>()
        val stage2WithDialogue = stage2Chapters.filter { work ->
            segmentsByChapter[work.text.chapterIndex].orEmpty()
                .any { it.roleType != SpeechRoleType.Narrator }
        }
        val noDialogue = stage2Chapters.filterNot { it in stage2WithDialogue }
        noDialogue.forEach { work ->
            val chapter = work.text.chapterIndex
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                try {
                    if (commitNoDialogue(bookKey, sourceKey, work, segmentsByChapter[chapter].orEmpty(), batchId) ==
                        RaBuildPublication.PUBLISHED
                    ) {
                        directReady.add(chapter)
                        onChapterReady?.invoke(chapter, true)
                    } else superseded.add(chapter)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failed[chapter] = "落库失败"
                }
            }
        }
        if (stage2WithDialogue.isNotEmpty()) {
            if (stage2Refs.isEmpty()) {
                stage2WithDialogue.forEach { failed[it.text.chapterIndex] = "未配置 stage2 模型" }
            } else {
                runStage2(
                    bookKey = bookKey,
                    sourceKey = sourceKey,
                    cfg = cfg,
                    works = stage2WithDialogue,
                    segmentsByChapter = segmentsByChapter,
                    refs = stage2Refs,
                    context = s2Context,
                    bareWords = bareWords,
                    specialWords = specialWords,
                    batchId = batchId,
                    failed = failed,
                    extracted = extracted,
                    superseded = superseded,
                    onChapterReady = onChapterReady,
                )
            }
        }

        // 批内收敛
        val convergeResult = converge(bookKey, extracted.values.toList(), priority, onChapterReady, control)
        return Outcome(
            ready = (directReady + convergeResult.ready).distinct().sorted(),
            failed = failed.keys.filterNot { it in superseded || extracted.containsKey(it) || it in directReady }.sorted(),
            extracted = convergeResult.extracted,
        )
    }

    /** 纯旁白章：无需阶段2，直接落 RESOLVED + IDENTITY_READY。 */
    private suspend fun commitNoDialogue(
        bookKey: String,
        sourceKey: String,
        work: ChapterWork,
        segments: List<RaSegment>,
        batchId: String,
    ): RaBuildPublication {
        val text = work.text
        val analysisId = SpeechIdentity.analysisId(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
        )
        val now = System.currentTimeMillis()
        val older = raBuild.getBuild(bookKey, sourceKey, text.chapterIndex)
        return raBuild.saveAnalysisBundle(
            analysis = RaAnalysis(
                id = analysisId,
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                status = RaAnalysisStatus.RESOLVED,
                origin = RaAnalysisOrigin.BACKGROUND_BATCH,
                batchId = batchId,
                modelId = "",
                createdAt = now,
                updatedAt = now,
            ),
            segments = segments.map { segment ->
                segment.copy(
                    id = SpeechIdentity.segmentId(
                        analysisId,
                        segment.paragraphIndex,
                        segment.start,
                        segment.end,
                    ),
                    analysisId = analysisId,
                    bookKey = bookKey,
                    chapterIndex = text.chapterIndex,
                    createdAt = now,
                    updatedAt = now,
                )
            },
            localSpeakers = emptyList(),
            localMentions = emptyList(),
            evidence = emptyList(),
            build = RaChapterBuild(
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                state = RaChapterState.IDENTITY_READY,
                analysisId = analysisId,
                failReason = "",
                attempts = older?.attempts ?: 0,
                createdAt = older?.createdAt ?: now,
                updatedAt = text.loadedAt,
            ),
            input = text.input,
        )
    }

    /**
     * 收敛已 EXTRACTED 的章节（批次中断/收敛异常后的恢复路径），不重新调用 AI。
     */
    suspend fun convergeExisting(
        bookKey: String,
        sourceKey: String,
        chapters: List<ChapterText>,
        priority: AiPriority = AiPriority.P3_BACKGROUND,
        onChapterReady: (suspend (Int, Boolean) -> Unit)? = null,
        control: AiRequestControl? = null,
    ): Outcome {
        val items = eligibleChapters(bookKey, chapters).mapNotNull { text ->
            val build = raBuild.getBuild(bookKey, sourceKey, text.chapterIndex)
                ?: return@mapNotNull null
            if (build.state != RaChapterState.EXTRACTED) return@mapNotNull null
            val analysis = build.analysisId?.let { raBuild.analysisById(it) }
            if (analysis == null || analysis.status != RaAnalysisStatus.EXTRACTED ||
                build.contentHash != text.contentHash || analysis.contentHash != text.contentHash ||
                analysis.resolverVersion != SpeechAnalysisPipelineV4.RESOLVER_VERSION ||
                analysis.observationJson.isBlank()
            ) {
                raBuild.transitionBuild(build, build.copy(state = RaChapterState.STALE,
                    failReason = "正文或解析版本已变化", updatedAt = text.loadedAt), input = text.input)
                return@mapNotNull null
            }
            val analysisId = analysis.id
            val speakers = raBuild.localSpeakers(analysisId)
            val mentions = raBuild.localMentions(analysisId)
            val evidence = raBuild.evidence(analysisId)
            val segments = raBuild.segments(analysisId)
            val seqMap = segments.mapNotNull { segment ->
                if (segment.seq <= 0 || segment.localSpeakerId == null) return@mapNotNull null
                val localKey = speakers.firstOrNull { it.id == segment.localSpeakerId }?.localKey
                    ?: return@mapNotNull null
                segment.seq to localKey
            }.toMap()
            val restored = runCatching {
                val snapshot = JSONObject(analysis.observationJson)
                fun restoredEntries(key: String): List<Stage2Speaker> {
                    val entries = snapshot.getJSONArray(key)
                    return (0 until entries.length()).map { index ->
                        val entry = entries.getJSONObject(index)
                        val aliases = entry.getJSONArray("aliases")
                        Stage2Speaker(
                            key = entry.getString("key"), name = entry.getString("name"),
                            aliases = (0 until aliases.length()).map(aliases::getString),
                            roleType = entry.getString("roletype"), gender = entry.getString("gender"),
                            ageBand = entry.getString("age"), genderSource = entry.getString("genderSource"),
                            ageSource = entry.getString("ageSource"),
                        )
                    }
                }
                val payload = Stage2Payload(seqMap, restoredEntries("speakers"), restoredEntries("mentions"), emptyList())
                val savedContexts = snapshot.getJSONObject("contexts")
                val contexts = savedContexts.keys().asSequence().associateWith(savedContexts::getString)
                payload to contexts
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                raBuild.transitionBuild(build, build.copy(state = RaChapterState.STALE,
                    failReason = "观察包无法恢复", updatedAt = text.loadedAt), input = text.input)
                return@mapNotNull null
            }
            val (payload, contexts) = restored
            Extracted(
                text = text,
                analysisId = analysisId,
                payload = payload,
                mentions = mentions,
                evidence = evidence,
                contextBySpeaker = contexts,
                batchId = analysis.batchId,
                build = build,
            )
        }
        if (items.isEmpty()) return Outcome()
        val result = converge(bookKey, items, priority, onChapterReady, control)
        return Outcome(ready = result.ready, extracted = result.extracted)
    }

    private fun eligibleChapters(bookKey: String, chapters: List<ChapterText>): List<ChapterText> {
        val fallback = RaBuildInput(raBuild.generation())
        return chapters.mapNotNull { text ->
            val input = text.input ?: fallback
            if (raBuild.canUseGeneration(bookKey, text.chapterIndex, input.generation) && input.matchesContent()) {
                text.copy(input = input)
            } else null
        }
    }

    // ---------------- 阶段1 ----------------

    private suspend fun runStage1(
        cfg: AnalysisConfigStore.Config,
        works: Map<Int, ChapterWork>,
        refs: List<AiSpeechClient.ModelRef>,
        context: AiSchedulerContext,
        failed: MutableMap<Int, String>,
    ): Map<Int, List<ObservationAssembly.SpRange>> {
        val out = HashMap<Int, List<ObservationAssembly.SpRange>>()
        var pending = works.values.filter { it.units.isNotEmpty() }
        // 无片段（纯旁白章）直接全旁白
        works.values.filter { it.units.isEmpty() }.forEach { out[it.text.chapterIndex] = emptyList() }

        repeat(2) { round ->
            if (pending.isEmpty()) return@repeat
            val byChapter = pending.associateBy { it.text.chapterIndex }
            val text = buildString {
                pending.sortedBy { it.text.chapterIndex }.forEach { work ->
                    append("=== ch=").append(work.text.chapterIndex + 1).append(" ===\n")
                    append(ObservationAssembly.buildUnitsText(work.units)).append('\n')
                }
            }
            val base = cfg.stage1Prompt.ifBlank { SpeechAnalysisPipelineV4.defaultPrompt("stage1") }
            val head = V4Prompts.stage1BatchPrompt(base)
            val promptFactory = { failHint: String ->
                buildString {
                    append(head)
                    if (failHint.isNotBlank()) {
                        append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                            .append("。请修正后重新输出完整JSON。\n\n")
                    }
                    append("\n=== 待分析文本 ===\n").append(text)
                }
            }
            val totalTimeout = min(cfg.timeoutSec * 1000L * pending.size, 1_800_000L)
            val result = ai.completeValidated(
                refs = refs,
                system = "只输出 JSON。",
                promptFactory = promptFactory,
                maxTokens = cfg.maxOutputTokens,
                logTag = "后台·阶段1（${pending.size}章）",
                context = context,
                totalTimeoutMs = totalTimeout,
            ) { raw ->
                validateBatchStage1(
                    extractJson = ai::extractJson,
                    raw = raw,
                    chapterUnitCounts = byChapter.mapValues { it.value.unitCounts },
                )
            }
            if (result == null) {
                AppLog.putAnalysis("【V4·后台·阶段1】第${round + 1}轮整批失败（${pending.size}章）")
                return@repeat
            }
            result.ok.forEach { (chapter, selection) ->
                val work = byChapter[chapter] ?: return@forEach
                val paras = work.units.map { c ->
                    io.legado.app.help.readaloud.analysis.SpeechSelectionAssembly.ParaUnits(
                        c.paraIndex,
                        c.units.map { u ->
                            io.legado.app.help.readaloud.analysis.SpeechSelectionAssembly.Unit(
                                u.start,
                                u.end,
                                u.text,
                            )
                        },
                    )
                }
                val asm = io.legado.app.help.readaloud.analysis.SpeechSelectionAssembly.assemble(
                    paras,
                    selection,
                )
                out[chapter] = asm.spans.map { ObservationAssembly.SpRange(it.para, it.start, it.end) }
            }
            result.failed.forEach { (chapter, reason) ->
                AppLog.putAnalysis("【V4·后台·阶段1】第${chapter + 1}章校验失败：${reason.take(120)}")
            }
            pending = pending.filter { it.text.chapterIndex !in result.ok }
        }
        pending.forEach { work ->
            if (work.text.chapterIndex !in out) {
                failed[work.text.chapterIndex] = "阶段1失败"
            }
        }
        return out
    }

    private fun validateBatchStage1(
        extractJson: (String) -> JSONObject?,
        raw: String,
        chapterUnitCounts: Map<Int, Map<Int, Int>>,
    ): ValidateOutcome<Stage1Result> {
        val root = extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val arr = root.optJSONArray("章节") ?: root.optJSONArray("chapters")
            ?: return ValidateOutcome(null, "缺少章节列表")
        val ok = LinkedHashMap<Int, Map<Int, BooleanArray>>()
        val errors = LinkedHashMap<Int, String>()
        val requested = chapterUnitCounts.keys
        for (i in 0 until arr.length()) {
            val chapterObj = arr.optJSONObject(i) ?: continue
            val displayCh = chapterObj.optInt("ch", -1)
            val chapter = displayCh - 1
            if (chapter !in requested) {
                continue
            }
            if (ok.containsKey(chapter)) {
                errors[chapter] = "章节重复"
                continue
            }
            val counts = chapterUnitCounts[chapter].orEmpty()
            if (counts.isEmpty()) {
                ok[chapter] = emptyMap()
                continue
            }
            val paraJson = JSONObject().apply {
                put("段落", chapterObj.optJSONArray("段落") ?: JSONArray())
            }
            val result = ObservationAssembly.validateSelection(extractJson, paraJson.toString(), counts)
            if (result.data != null) ok[chapter] = result.data else errors[chapter] = result.failReason
        }
        requested.forEach { chapter ->
            if (chapter !in ok && chapter !in errors) errors[chapter] = "输出缺少该章"
        }
        return ValidateOutcome(Stage1Result(ok, errors))
    }

    private fun localRanges(paragraphs: List<CanonicalSpeechParagraph>): List<ObservationAssembly.SpRange> {
        val ranges = ArrayList<ObservationAssembly.SpRange>()
        paragraphs.forEach { p ->
            QuoteSpeechRules.quoteSpans(p.text).forEach { span ->
                ranges.add(ObservationAssembly.SpRange(p.index, span.first, span.last + 1))
            }
        }
        ObservationAssembly.pruneSymbolOnly(paragraphs, ranges)
        return ranges
    }

    // ---------------- 阶段2 ----------------

    private suspend fun runStage2(
        bookKey: String,
        sourceKey: String,
        cfg: AnalysisConfigStore.Config,
        works: List<ChapterWork>,
        segmentsByChapter: Map<Int, List<RaSegment>>,
        refs: List<AiSpeechClient.ModelRef>,
        context: AiSchedulerContext,
        bareWords: Set<String>,
        specialWords: Set<String>,
        batchId: String,
        failed: MutableMap<Int, String>,
        extracted: MutableMap<Int, Extracted>,
        superseded: MutableSet<Int>,
        onChapterReady: (suspend (chapterIndex: Int, identityReady: Boolean) -> Unit)? = null,
    ) {
        val estimatedOutput = works.sumOf { work ->
            val count = segmentsByChapter[work.text.chapterIndex].orEmpty().count {
                it.roleType != SpeechRoleType.Narrator
            }
            count * 60 + minOf(count, 12) * 80 + 400
        }
        if (works.size > 1 && estimatedOutput > cfg.maxOutputTokens * 0.8) {
            works.chunked((works.size + 1) / 2).forEach { part ->
                runStage2(bookKey, sourceKey, cfg, part, segmentsByChapter, refs,
                    context.copy(chapters = part.map { it.text.chapterIndex }), bareWords, specialWords,
                    batchId, failed, extracted, superseded, onChapterReady)
            }
            return
        }
        val ordered = works.sortedBy { it.text.chapterIndex }
        val first = ordered.first().text
        val last = ordered.last().text
        val prev = ObservationAssembly.takeContextByParagraphs(first.prevText, cfg.prevLimit, fromTail = true)
        val next = ObservationAssembly.takeContextByParagraphs(last.nextText, cfg.nextLimit, fromTail = false)
        val allParagraphs = ordered.flatMap { it.text.paragraphs }
        val known = runCatching {
            pipeline.buildKnownCharacters(bookKey, allParagraphs, last.chapterIndex, limit = 20)
        }.getOrDefault("")

        fun buildUser(active: List<ChapterWork>): String = buildString {
            if (prev.isNotBlank()) {
                append("【批次前情】\n")
                prev.lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    append("[来源 ch=${first.chapterIndex} p=${index + 1}] ").append(line).append('\n')
                }
                append('\n')
            }
            append("【批次正文】\n")
            active.forEach { work ->
                append("=== ch=").append(work.text.chapterIndex + 1).append(" ===\n")
                append(
                    ObservationAssembly.renderNumbered(
                        segmentsByChapter[work.text.chapterIndex].orEmpty(),
                    ),
                ).append('\n')
            }
            if (next.isNotBlank()) {
                append("\n【批次后续】\n")
                next.lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    append("[来源 ch=${last.chapterIndex + 2} p=${index + 1}] ").append(line).append('\n')
                }
            }
            if (known.isNotBlank()) append("\n【已知人物参考】\n").append(known).append('\n')
        }
        val base = cfg.stage2Prompt.ifBlank { V4Prompts.DEFAULT_STAGE2_PROMPT }
        val head = V4Prompts.stage2BatchPrompt(base)
        val totalTimeout = min(cfg.timeoutSec * 1000L * ordered.size, 1_800_000L)
        val byChapter = ordered.associateBy { it.text.chapterIndex }
        var pending = ordered.map { it.text.chapterIndex }.toSet()

        repeat(2) { round ->
            val invalid = ordered.filter { it.text.chapterIndex in pending &&
                it.text.input?.let { input ->
                    !raBuild.canUseGeneration(bookKey, it.text.chapterIndex, input.generation) || !input.matchesContent()
                } == true
            }.map { it.text.chapterIndex }
            superseded.addAll(invalid)
            pending = pending - superseded
            if (pending.isEmpty()) return@repeat
            val active = ordered.filter { it.text.chapterIndex in pending }
            val promptFactory = { failHint: String ->
                buildString {
                    append(head)
                    if (failHint.isNotBlank()) {
                        append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                            .append("。请修正后重新输出。\n\n")
                    }
                    append("\n=== 待分析文本 ===\n").append(buildUser(active.filterNot { it.text.chapterIndex in extracted }))
                }
            }
            val commitChannel =
                Channel<ChapterSubmission>(Channel.UNLIMITED)
            val entities = LinkedHashMap<String, JSONObject>()
            var responseId = ""
            var modelId = ""
            lateinit var parser: NdjsonChapterStream
            parser = NdjsonChapterStream(
                // 解析器使用 1 基显示章号；宿主索引只在回调入口转换一次。
                requestedChapters = pending.map { it + 1 }.toSet(),
                autoAcknowledge = false,
                onEntities = { obj ->
                    val arr = obj.optJSONArray("entities") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val key = e.optString("bkey").trim()
                        if (key.isNotEmpty()) entities[key] = e
                    }
                },
                onChapter = { displayCh, obj ->
                    val ch = displayCh - 1
                    if (ch in pending) {
                        // 快照实体表：后续模型重试改写 entities 不影响本章提交。
                        commitChannel.trySend(ChapterSubmission(ch, obj, entities.toMap(), responseId, modelId))
                    }
                },
            )
            coroutineScope {
                val commitJob = launch {
                    for (submission in commitChannel) {
                        val ch = submission.chapter
                        if (ch in extracted || ch in superseded) continue
                        val work = byChapter[ch] ?: continue
                        val result = try { commitChapter(
                            bookKey = bookKey,
                            sourceKey = sourceKey,
                            work = work,
                            segments = segmentsByChapter[ch].orEmpty(),
                            obj = submission.json,
                            entities = submission.entities,
                            cfg = cfg,
                            bareWords = bareWords,
                            specialWords = specialWords,
                            batchId = submission.responseId,
                            modelId = submission.modelId,
                            contextPrev = prev,
                            contextNext = next,
                            contextPrevChapter = first.chapterIndex,
                            contextNextChapter = last.chapterIndex + 2,
                        ) } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            null
                        }
                        when (result) {
                            is ChapterCommit.Published -> {
                                extracted[ch] = result.extraction
                                failed.remove(ch)
                                parser.acknowledge(ch + 1)
                                onChapterReady?.invoke(ch, false)
                            }
                            ChapterCommit.Superseded -> {
                                superseded.add(ch)
                                failed.remove(ch)
                            }
                            null -> failed[ch] = "阶段2校验或提交失败"
                        }
                    }
                }
                try {
                    ai.completeValidated(
                        refs = refs,
                        system = "只输出 JSON。",
                        promptFactory = promptFactory,
                        maxTokens = cfg.maxOutputTokens,
                        logTag = "后台·阶段2（${pending.size}章）",
                        context = context,
                        onChunk = parser::feed,
                        onAttemptStart = {
                            parser.resetAttempt()
                            entities.clear()
                            responseId = "$batchId:${java.util.UUID.randomUUID()}"
                        },
                        onAttemptModel = { modelId = it.model.id },
                        totalTimeoutMs = totalTimeout,
                    ) { raw -> ValidateOutcome(raw) }
                    val streamResult = parser.finish()
                    if (streamResult.needsFallback || streamResult.received.isEmpty()) {
                        val fallback = runCatching { parser.consumeWholeJson(streamResult.raw) }
                            .getOrDefault(false)
                        if (!fallback) {
                            AppLog.putAnalysis("【V4·后台·阶段2】第${round + 1}轮未按协议输出，转入拆批")
                        }
                    }
                } finally {
                    commitChannel.close()
                    commitJob.join()
                }
            }
            pending = pending.filter { it !in extracted && it !in superseded }.toSet()
            if (pending.isNotEmpty()) {
                AppLog.putAnalysis(
                    "【V4·后台·阶段2】第${round + 1}轮缺 ${pending.size} 章（${pending.sorted().joinToString(",")}）",
                )
            }
        }
        pending.forEach { if (it !in extracted) failed[it] = "阶段2失败" }
    }

    private sealed interface ChapterCommit {
        data class Published(val extraction: Extracted) : ChapterCommit
        data object Superseded : ChapterCommit
    }

    private suspend fun commitChapter(
        bookKey: String,
        sourceKey: String,
        work: ChapterWork,
        segments: List<RaSegment>,
        obj: JSONObject,
        entities: Map<String, JSONObject>,
        cfg: AnalysisConfigStore.Config,
        bareWords: Set<String>,
        specialWords: Set<String>,
        batchId: String,
        modelId: String,
        contextPrev: String,
        contextNext: String,
        contextPrevChapter: Int,
        contextNextChapter: Int,
    ): ChapterCommit? {
        val text = work.text
        val analysisId = SpeechIdentity.analysisId(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
        )
        val json = batchChapterJson(obj, entities)
        if (!batchKeysDeclared(obj, entities)) {
            AppLog.putAnalysis("【V4·${text.chapterLabel}·阶段2】引用了实体表未定义的 bkey，丢弃该章")
            return null
        }
        val expected = segments.count { it.roleType != SpeechRoleType.Narrator }
        val validator = Stage2Validator(
            paragraphs = text.paragraphs,
            chapterIndex = text.chapterIndex,
            prevText = contextPrev,
            nextText = contextNext,
            prevChapter = contextPrevChapter.takeIf { contextPrev.isNotBlank() },
            nextChapter = contextNextChapter.takeIf { contextNext.isNotBlank() },
            isBareClassWord = { word ->
                val w = word.trim()
                w.isNotEmpty() && (w.length <= 1 || w in bareWords)
            },
            isSpecialClassWord = { word -> word.trim() in specialWords },
            log = { message -> AppLog.putAnalysis("【V4·${text.chapterLabel}·阶段2】$message") },
        )
        val outcome = validator.validate(json, expected)
        val payload = outcome.data ?: run {
            AppLog.putAnalysis("【V4·${text.chapterLabel}·阶段2】校验失败：${outcome.failReason.take(160)}")
            return null
        }
        val body = text.paragraphs.joinToString("\n") { it.text }
        if ((payload.speakers + payload.mentions).any { entry ->
                (entry.roleType != "路人" && !body.contains(entry.name)) ||
                    entry.aliases.any { !body.contains(it) }
            }) {
            AppLog.putAnalysis("【V4·${text.chapterLabel}·阶段2】校验失败：主名或别名缺少本章来源")
            return null
        }
        val speakers = ObservationAssembly.buildLocalSpeakers(
            analysisId, payload.speakers, true, batchId,
        )
        val bound = SpeakerBinder.bind(
            ObservationAssembly.applyStage2(segments, payload.seqMap),
            speakers,
            emptyMap(),
            payload.seqMap,
        )
        val mentions = ObservationAssembly.buildLocalMentions(
            analysisId, payload.mentions, true, batchId,
        )
        val evidence = ObservationAssembly.buildEvidence(analysisId, bookKey, payload.evidence)
        val contexts = ObservationAssembly.buildContextBySpeaker(text.paragraphs, bound, payload.seqMap, speakers)
        fun packEntries(entries: List<Stage2Speaker>) = JSONArray().apply {
            entries.forEach { speaker ->
                put(JSONObject().apply {
                    put("key", speaker.key); put("name", speaker.name)
                    put("aliases", JSONArray(speaker.aliases)); put("roletype", speaker.roleType)
                    put("gender", speaker.gender); put("age", speaker.ageBand)
                    put("genderSource", speaker.genderSource); put("ageSource", speaker.ageSource)
                })
            }
        }
        val snapshot = JSONObject().apply {
            put("speakers", packEntries(payload.speakers)); put("mentions", packEntries(payload.mentions))
            put("contexts", JSONObject(contexts))
            text.cachedBody?.let {
                put("bodyUrl", it.bookUrl)
                put("bodyHash", it.hash)
            }
        }.toString()
        val now = System.currentTimeMillis()
        val older = raBuild.getBuild(bookKey, sourceKey, text.chapterIndex)
        val extractionBuild = RaChapterBuild(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
            state = RaChapterState.EXTRACTED,
            analysisId = analysisId,
            failReason = "",
            attempts = older?.attempts ?: 0,
            createdAt = older?.createdAt ?: now,
            updatedAt = text.loadedAt,
        )
        val publication = raBuild.saveAnalysisBundle(
            analysis = RaAnalysis(
                id = analysisId,
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                status = RaAnalysisStatus.EXTRACTED,
                origin = RaAnalysisOrigin.BACKGROUND_BATCH,
                batchId = batchId,
                modelId = modelId,
                observationJson = snapshot,
                createdAt = now,
                updatedAt = now,
            ),
            segments = bound.map { segment ->
                segment.copy(
                    id = SpeechIdentity.segmentId(
                        analysisId,
                        segment.paragraphIndex,
                        segment.start,
                        segment.end,
                    ),
                    analysisId = analysisId,
                    bookKey = bookKey,
                    chapterIndex = text.chapterIndex,
                    createdAt = now,
                    updatedAt = now,
                )
            },
            localSpeakers = speakers,
            localMentions = mentions,
            evidence = evidence,
            build = extractionBuild,
            input = text.input,
        )
        if (publication != RaBuildPublication.PUBLISHED) return ChapterCommit.Superseded
        AppLog.putAnalysis(
            "【V4·${text.chapterLabel}·阶段2】完成：话语 $expected、人物 ${speakers.size}、证据 ${evidence.size}",
        )
        return ChapterCommit.Published(Extracted(
            text = text,
            analysisId = analysisId,
            payload = payload,
            mentions = mentions,
            evidence = evidence,
            contextBySpeaker = contexts,
            batchId = batchId,
            build = extractionBuild,
        ))
    }

    /** 本章 seqmap 引用的每个说话人键都必须在本次响应的实体表中定义。 */
    private fun batchKeysDeclared(obj: JSONObject, entities: Map<String, JSONObject>): Boolean {
        val sm = obj.optJSONObject("seqmap") ?: return false
        val keys = sm.keys()
        var declared = true
        while (keys.hasNext()) {
            val key = sm.optString(keys.next()).trim()
            if (key.isEmpty() || key == "N") continue
            if (!entities.containsKey(key) ||
                obj.optJSONObject("localSpeakers")?.optJSONObject(key)?.optString("name").isNullOrBlank()
            ) {
                declared = false
                break
            }
        }
        return declared
    }

    /** 把批量 chapter 行转换成单章阶段2可校验的 JSON（bkey 原样保留）。 */
    private fun batchChapterJson(obj: JSONObject, entities: Map<String, JSONObject>): String {
        val root = JSONObject()
        root.put("seqmap", obj.optJSONObject("seqmap") ?: JSONObject())
        val local = obj.optJSONObject("localSpeakers") ?: JSONObject()
        val usedKeys = LinkedHashSet<String>()
        val sm = obj.optJSONObject("seqmap")
        if (sm != null) {
            val keys = sm.keys()
            while (keys.hasNext()) {
                val v = sm.optString(keys.next()).trim()
                if (v.isNotEmpty() && v != "N") usedKeys.add(v)
            }
        }
        val speakers = JSONArray()
        usedKeys.forEach { key ->
            val entity = entities[key]
            val localEntry = local.optJSONObject(key)
            val name = localEntry?.optString("name").orEmpty()
                .ifBlank { entity?.optString("name").orEmpty() }
                .ifBlank { key }
            speakers.put(
                JSONObject().apply {
                    put("key", key)
                    put("name", name)
                    put("aliases", localEntry?.optJSONArray("aliases") ?: entity?.optJSONArray("aliases") ?: JSONArray())
                    put("roletype", entity?.optString("roletype").orEmpty())
                    put("gender", entity?.optString("gender").orEmpty())
                    put("age", entity?.optString("age").orEmpty())
                    put("genderSource", entity?.optString("genderSource").orEmpty())
                    put("ageSource", entity?.optString("ageSource").orEmpty())
                },
            )
        }
        root.put("speakers", speakers)
        root.put("mentions", obj.optJSONArray("mentions") ?: JSONArray())
        root.put("evidence", obj.optJSONArray("evidence") ?: JSONArray())
        return root.toString()
    }

    // ---------------- 批内收敛 ----------------

    private val resolutionLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun converge(
        bookKey: String,
        extracted: List<Extracted>,
        priority: AiPriority,
        onChapterReady: (suspend (Int, Boolean) -> Unit)?,
        control: AiRequestControl?,
    ): Convergence {
        val locked = ArrayList<Mutex>()
        try {
            // 同响应的不同章节分次收敛时，也要先看到上一次提交的身份锚点。
            extracted.map { it.batchId?.let { batchId -> "batch:$batchId" } ?: it.analysisId }
                .distinct().sorted().forEach { id ->
                val mutex = resolutionLocks.getOrPut(id) { Mutex() }
                mutex.lock()
                locked.add(mutex)
            }
            val ready = ArrayList<Int>()
            val pending = extracted.filter { item ->
                val input = item.text.input
                if (input != null && (!raBuild.canUseGeneration(bookKey, item.text.chapterIndex, input.generation) ||
                        !input.matchesContent())
                ) return@filter false
                val current = raBuild.getBuild(bookKey, item.build.sourceKey, item.text.chapterIndex)
                if (current?.analysisId != item.analysisId || current.contentHash != item.text.contentHash) {
                    return@filter false
                }
                if (current.state == RaChapterState.IDENTITY_READY) ready.add(item.text.chapterIndex)
                current == item.build && current.state == RaChapterState.EXTRACTED
            }
            val result = convergeLocked(bookKey, pending, priority, onChapterReady, control)
            return result.copy(ready = (ready + result.ready).distinct().sorted())
        } finally {
            withContext(NonCancellable) { locked.asReversed().forEach { it.unlock() } }
        }
    }

    private suspend fun convergeLocked(
        bookKey: String,
        extracted: List<Extracted>,
        priority: AiPriority,
        onChapterReady: (suspend (Int, Boolean) -> Unit)?,
        control: AiRequestControl?,
    ): Convergence {
        if (extracted.isEmpty()) return Convergence()
        val sourceKey = extracted.first().build.sourceKey
        val targets = extracted.mapNotNull { item ->
            val analysis = raBuild.analysisById(item.analysisId) ?: return@mapNotNull null
            RaIdentityCommit(
                analysis.copy(status = RaAnalysisStatus.RESOLVED, updatedAt = System.currentTimeMillis()),
                item.build.copy(state = RaChapterState.IDENTITY_READY, failReason = ""),
                expectedExtraction = item.build,
                input = item.text.input,
            )
        }
        val itemsById = extracted.associateBy { it.analysisId }
        val context = AiSchedulerContext(
            kind = AiTaskKind.RESOLVE,
            priority = priority,
            bookKey = bookKey,
            chapters = extracted.map { it.text.chapterIndex },
            preemptible = !priority.foreground,
            control = control,
            sourceKey = sourceKey,
        )
        val ready = try {
            val answers = resolver.prepareBatch(bookKey, convergenceInputs(bookKey, sourceKey, extracted), context)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val committed = raBuild.commitIdentity(targets) { eligible ->
                    if (eligible.isEmpty()) return@commitIdentity emptyList()
                    val active = eligible.map { itemsById.getValue(it.analysis.id) }
                    // 按事务内仍有效的观察包和锚点收敛，部分失效的章不会贡献别名或证据。
                    val inputs = convergenceInputs(bookKey, sourceKey, active)
                    val resolutions = resolver.applyBatch(bookKey, inputs, answers)
                    val speakersByChapter = inputs.filter { it.publish }.associate { input ->
                        input.chapterIndex to input.speakers.map { speaker ->
                            val resolution = resolutions.getValue(speaker.id)
                            speaker.copy(characterId = resolution.characterId, resolution = resolution.resolution)
                        }
                    }
                    val characters = active.flatMap { speakersByChapter.getValue(it.text.chapterIndex) }
                        .mapNotNull { it.characterId }.distinct().mapNotNull { raCharacters.character(it) }
                    casting.ensureDefaultProfiles(bookKey, characters, "后台")
                    for (target in eligible) {
                        val item = itemsById.getValue(target.analysis.id)
                        val speakers = speakersByChapter.getValue(item.text.chapterIndex)
                        val charBySpeaker = speakers.associate { it.id to it.characterId }
                        val segments = raBuild.segments(item.analysisId).map { segment ->
                            val characterId = segment.localSpeakerId?.let(charBySpeaker::get)
                            if (characterId == null) segment else segment.copy(characterId = characterId)
                        }
                        check(segments.none { it.roleType != SpeechRoleType.Narrator &&
                            (it.seq <= 0 || it.localSpeakerId == null || it.characterId == null) })
                        val keyToCharacter = speakers.mapNotNull { speaker ->
                            speaker.characterId?.let { speaker.localKey to it }
                        }.toMap()
                        val (attributes, relations) = ObservationAssembly.collectObservations(
                            bookKey, item.text.chapterIndex, item.payload, keyToCharacter, item.evidence,
                        )
                        val publication = raBuild.saveAnalysisBundle(
                            analysis = target.analysis,
                            segments = segments, localSpeakers = speakers, localMentions = item.mentions, evidence = item.evidence,
                            build = target.build, attributes = attributes, relations = relations,
                            expectedExtraction = target.expectedExtraction,
                            input = target.input,
                        )
                        check(publication == RaBuildPublication.PUBLISHED)
                    }
                    characters.forEach { raCharacters.recomputeAppearance(it.id) }
                    eligible.map { it.build.chapterIndex }
                }
                // 等提交确认后才通知与扣额，Room 回滚不会发出 READY。
                committed.forEach { onChapterReady?.invoke(it, true) }
                committed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.putAnalysis("【V4·后台·收敛】失败：${e.localizedMessage}，章节保留 EXTRACTED", e)
            return Convergence(extracted = extracted.map { it.text.chapterIndex })
        }
        AppLog.putAnalysis("【V4·后台·收敛】完成：${ready.size} 章 → IDENTITY_READY")
        return Convergence(ready, extracted.map { it.text.chapterIndex }.filterNot { it in ready })
    }

    private suspend fun convergenceInputs(
        bookKey: String,
        sourceKey: String,
        extracted: List<Extracted>,
    ): List<IdentityResolver.BatchChapterInput> {
        val scopes = extracted.mapNotNull { it.batchId }.toSet()
        val pendingChapters = extracted.mapTo(HashSet()) { it.text.chapterIndex }
        val generation = extracted.minOf { requireNotNull(it.text.input).generation }
        val context = raBuild.currentBatchAnalyses(bookKey, sourceKey, scopes, pendingChapters, generation)
            .filter { it.resolverVersion == SpeechAnalysisPipelineV4.RESOLVER_VERSION }
        return extracted.map { item ->
            IdentityResolver.BatchChapterInput(
                chapterIndex = item.text.chapterIndex,
                chapterLabel = item.text.chapterLabel,
                speakers = raBuild.localSpeakers(item.analysisId),
                evidence = item.evidence,
                contextBySpeaker = item.contextBySpeaker,
                batchId = item.batchId,
            )
        } + context.map { analysis ->
            IdentityResolver.BatchChapterInput(
                analysis.chapterIndex, "第${analysis.chapterIndex + 1}章", raBuild.localSpeakers(analysis.id),
                raBuild.evidence(analysis.id), batchId = analysis.batchId, publish = false,
            )
        }
    }
}
