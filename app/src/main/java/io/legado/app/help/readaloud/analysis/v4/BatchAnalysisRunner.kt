package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.help.readaloud.analysis.queueRefs
import io.legado.app.domain.gateway.RaBuildGateway
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
import io.legado.app.help.readaloud.analysis.AiSchedulerContext
import io.legado.app.help.readaloud.analysis.AiSpeechClient
import io.legado.app.help.readaloud.analysis.AiTaskKind
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.QuoteSpeechRules
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV4
import io.legado.app.help.readaloud.analysis.ValidateOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    )

    data class Outcome(
        val ready: List<Int> = emptyList(),
        val failed: List<Int> = emptyList(),
        val extracted: List<Int> = emptyList(),
    )

    private data class ChapterWork(
        val text: ChapterText,
        val units: List<ObservationAssembly.ParaUnits>,
        val unitCounts: Map<Int, Int>,
        var segments: List<RaSegment> = emptyList(),
    )

    private data class Extracted(
        val text: ChapterText,
        val analysisId: String,
        val segments: List<RaSegment>,
        val payload: Stage2Payload?,
        val mentions: List<RaLocalMention>,
        val evidence: List<RaEvidence>,
        val contextBySpeaker: Map<String, String> = emptyMap(),
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
    ): Outcome {
        if (chapters.isEmpty()) return Outcome()
        val cfg = runCatching { configStore.load() }.getOrDefault(AnalysisConfigStore.Config())
        val bareWords = runCatching { wordLibrary.bareWords() }.getOrDefault(emptySet())
        val specialWords = runCatching { wordLibrary.specialWords() }.getOrDefault(emptySet())
        val works = chapters.map { text ->
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
            chapters = chapters.map { it.chapterIndex },
            preemptible = true,
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

        val extracted = LinkedHashMap<Int, Extracted>()
        val stage2Chapters = works.values.filter { work ->
            work.text.chapterIndex !in failed && work.text.chapterIndex in segmentsByChapter
        }
        val directReady = ArrayList<Int>()
        val stage2WithDialogue = stage2Chapters.filter { work ->
            segmentsByChapter[work.text.chapterIndex].orEmpty()
                .any { it.roleType != SpeechRoleType.Narrator }
        }
        val noDialogue = stage2Chapters.filterNot { it in stage2WithDialogue }
        noDialogue.forEach { work ->
            if (commitNoDialogue(bookKey, sourceKey, work, segmentsByChapter[work.text.chapterIndex].orEmpty(), batchId)) {
                directReady.add(work.text.chapterIndex)
            } else {
                failed[work.text.chapterIndex] = "落库失败"
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
                )
            }
        }

        // 批内收敛
        val convergeResult = converge(bookKey, extracted.values.toList())
        return Outcome(
            ready = (directReady + convergeResult.first).distinct().sorted(),
            failed = failed.keys.toList().sorted(),
            extracted = convergeResult.second,
        )
    }

    /** 纯旁白章：无需阶段2，直接落 RESOLVED + IDENTITY_READY。 */
    private suspend fun commitNoDialogue(
        bookKey: String,
        sourceKey: String,
        work: ChapterWork,
        segments: List<RaSegment>,
        batchId: String,
    ): Boolean = runCatching {
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
        raBuild.saveAnalysisBundle(
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
                updatedAt = now,
            ),
        )
        true
    }.getOrDefault(false)

    /**
     * 收敛已 EXTRACTED 的章节（批次中断/收敛异常后的恢复路径），不重新调用 AI。
     */
    suspend fun convergeExisting(
        bookKey: String,
        sourceKey: String,
        chapters: List<ChapterText>,
    ): Outcome {
        val items = chapters.mapNotNull { text ->
            val build = raBuild.getBuild(bookKey, sourceKey, text.chapterIndex)
                ?: return@mapNotNull null
            if (build.state != RaChapterState.EXTRACTED) return@mapNotNull null
            val analysisId = build.analysisId ?: return@mapNotNull null
            val analysis = raBuild.analysisById(analysisId) ?: return@mapNotNull null
            if (analysis.status != RaAnalysisStatus.EXTRACTED) return@mapNotNull null
            Extracted(
                text = text,
                analysisId = analysisId,
                segments = raBuild.segments(analysisId),
                payload = null,
                mentions = raBuild.localMentions(analysisId),
                evidence = raBuild.evidence(analysisId),
            )
        }
        if (items.isEmpty()) return Outcome()
        val (ready, extracted) = converge(bookKey, items)
        return Outcome(ready = ready, extracted = extracted)
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
            if (result.data != null) ok[chapter] = result.data!! else errors[chapter] = result.failReason
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
    ) {
        val ordered = works.sortedBy { it.text.chapterIndex }
        val first = ordered.first().text
        val last = ordered.last().text
        val prev = ObservationAssembly.takeContextByParagraphs(first.prevText, cfg.prevLimit, fromTail = true)
        val next = ObservationAssembly.takeContextByParagraphs(last.nextText, cfg.nextLimit, fromTail = false)
        val allParagraphs = ordered.flatMap { it.text.paragraphs }
        val known = runCatching {
            pipeline.buildKnownCharacters(bookKey, allParagraphs, last.chapterIndex, limit = 20)
        }.getOrDefault("")

        val user = buildString {
            if (prev.isNotBlank()) {
                append("【批次前情】\n")
                prev.lines().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    append("[来源 ch=${first.chapterIndex} p=${index + 1}] ").append(line).append('\n')
                }
                append('\n')
            }
            append("【批次正文】\n")
            ordered.forEach { work ->
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
        val promptFactory = { failHint: String ->
            buildString {
                append(head)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出。\n\n")
                }
                append("\n=== 待分析文本 ===\n").append(user)
            }
        }
        val totalTimeout = min(cfg.timeoutSec * 1000L * ordered.size, 1_800_000L)
        val byChapter = ordered.associateBy { it.text.chapterIndex }
        var modelUsed = ""
        var pending = ordered.map { it.text.chapterIndex }.toSet()

        repeat(2) { round ->
            if (pending.isEmpty()) return@repeat
            val commitChannel = Channel<Pair<Int, JSONObject>>(Channel.UNLIMITED)
            val entities = LinkedHashMap<String, JSONObject>()
            val parser = NdjsonChapterStream(
                requestedChapters = pending,
                onEntities = { obj ->
                    val arr = obj.optJSONArray("entities") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val key = e.optString("bkey").trim()
                        if (key.isNotEmpty()) entities[key] = e
                    }
                },
                onChapter = { ch, obj -> commitChannel.trySend(ch to obj) },
            )
            coroutineScope {
                val commitJob = launch {
                    for ((ch, obj) in commitChannel) {
                        val work = byChapter[ch] ?: continue
                        val result = commitChapter(
                            bookKey = bookKey,
                            sourceKey = sourceKey,
                            work = work,
                            segments = segmentsByChapter[ch].orEmpty(),
                            obj = obj,
                            entities = entities,
                            cfg = cfg,
                            bareWords = bareWords,
                            specialWords = specialWords,
                            batchId = batchId,
                            modelId = modelUsed,
                        )
                        if (result != null) {
                            extracted[ch] = result
                        } else {
                            failed[ch] = "阶段2校验失败"
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
                        onAttemptStart = parser::resetAttempt,
                        totalTimeoutMs = totalTimeout,
                        onModelUsed = { modelUsed = it },
                    ) { raw -> ValidateOutcome(raw) }
                    val streamResult = parser.finish()
                    if (streamResult.needsFallback || streamResult.committed.isEmpty()) {
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
            pending = pending.filter { it !in extracted }.toSet()
            if (pending.isNotEmpty()) {
                AppLog.putAnalysis(
                    "【V4·后台·阶段2】第${round + 1}轮缺 ${pending.size} 章（${pending.sorted().joinToString(",")}）",
                )
            }
        }
        pending.forEach { failed[it] = "阶段2失败" }
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
    ): Extracted? {
        val text = work.text
        val analysisId = SpeechIdentity.analysisId(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
        )
        val json = batchChapterJson(obj, entities)
        val expected = segments.count { it.roleType != SpeechRoleType.Narrator }
        val validator = Stage2Validator(
            paragraphs = text.paragraphs,
            chapterIndex = text.chapterIndex,
            prevText = ObservationAssembly.takeContextByParagraphs(
                text.prevText, cfg.prevLimit, fromTail = true,
            ),
            nextText = ObservationAssembly.takeContextByParagraphs(
                text.nextText, cfg.nextLimit, fromTail = false,
            ),
            prevChapter = if (text.prevText.isNotBlank()) text.chapterIndex - 1 else null,
            nextChapter = if (text.nextText.isNotBlank()) text.chapterIndex + 1 else null,
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
        val speakers = ObservationAssembly.buildLocalSpeakers(analysisId, payload.speakers, true)
        val bound = SpeakerBinder.bind(segments, speakers, emptyMap(), payload.seqMap)
        val mentions = ObservationAssembly.buildLocalMentions(analysisId, payload.mentions, true)
        val evidence = ObservationAssembly.buildEvidence(analysisId, bookKey, payload.evidence)
        val now = System.currentTimeMillis()
        val older = raBuild.getBuild(bookKey, sourceKey, text.chapterIndex)
        raBuild.saveAnalysisBundle(
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
            build = RaChapterBuild(
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                state = RaChapterState.EXTRACTED,
                analysisId = analysisId,
                failReason = "",
                attempts = older?.attempts ?: 0,
                createdAt = older?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        AppLog.putAnalysis(
            "【V4·${text.chapterLabel}·阶段2】完成：话语 $expected、人物 ${speakers.size}、证据 ${evidence.size}",
        )
        return Extracted(
            text = text,
            analysisId = analysisId,
            segments = bound,
            payload = payload,
            mentions = mentions,
            evidence = evidence,
            contextBySpeaker = ObservationAssembly.buildContextBySpeaker(
                text.paragraphs,
                bound,
                payload.seqMap,
                speakers,
            ),
        )
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

    private suspend fun converge(bookKey: String, extracted: List<Extracted>): Pair<List<Int>, List<Int>> {
        if (extracted.isEmpty()) return emptyList<Int>() to emptyList()
        val inputs = extracted.map { item ->
            IdentityResolver.BatchChapterInput(
                chapterIndex = item.text.chapterIndex,
                chapterLabel = item.text.chapterLabel,
                speakers = raBuild.localSpeakers(item.analysisId),
                evidence = raBuild.evidence(item.analysisId),
                contextBySpeaker = item.contextBySpeaker,
            )
        }
        val resolutions = runCatching { resolver.resolveBatch(bookKey, inputs) }.getOrElse { e ->
            if (e is CancellationException) throw e
            AppLog.putAnalysis("【V4·后台·收敛】失败：${e.localizedMessage}，章节保留 EXTRACTED", e)
            return emptyList<Int>() to extracted.map { it.text.chapterIndex }
        }
        val ready = ArrayList<Int>()
        extracted.forEach { item ->
            val speakers = raBuild.localSpeakers(item.analysisId).map { speaker ->
                val resolution = resolutions[speaker.id]
                if (resolution == null) {
                    speaker
                } else {
                    speaker.copy(
                        characterId = resolution.characterId,
                        resolution = resolution.resolution,
                    )
                }
            }
            val charBySpeaker = speakers.associate { it.id to it.characterId }
            val segments = raBuild.segments(item.analysisId).map { segment ->
                val characterId = segment.localSpeakerId?.let(charBySpeaker::get)
                if (characterId == null) segment else segment.copy(characterId = characterId)
            }
            val keyToCharacter = speakers.associate { it.localKey to it.characterId }
                .filterValues { it != null }
                .mapValues { it.value!! }
            val observations = item.payload?.let { payload ->
                ObservationAssembly.collectObservations(
                    bookKey = bookKey,
                    chapterIndex = item.text.chapterIndex,
                    payload = payload,
                    keyToCharacter = keyToCharacter,
                    evidence = item.evidence,
                )
            } ?: (emptyList<io.legado.app.domain.model.readaloud.v4.RaAttributeObservation>() to
                emptyList<io.legado.app.domain.model.readaloud.v4.RaRelationObservation>())
            val (attributes, relations) = observations
            val now = System.currentTimeMillis()
            val analysis = raBuild.analysisById(item.analysisId)
            if (analysis != null) {
                val older = raBuild.getBuild(bookKey, analysis.sourceKey, item.text.chapterIndex)
                raBuild.saveAnalysisBundleWithObservations(
                    analysis = analysis.copy(
                        status = RaAnalysisStatus.RESOLVED,
                        updatedAt = now,
                    ),
                    segments = segments,
                    localSpeakers = speakers,
                    localMentions = item.mentions,
                    evidence = item.evidence,
                    build = RaChapterBuild(
                        bookKey = bookKey,
                        sourceKey = analysis.sourceKey,
                        chapterIndex = item.text.chapterIndex,
                        contentHash = analysis.contentHash,
                        state = RaChapterState.IDENTITY_READY,
                        analysisId = item.analysisId,
                        failReason = "",
                        attempts = older?.attempts ?: 0,
                        createdAt = older?.createdAt ?: now,
                        updatedAt = now,
                    ),
                    attributes = attributes,
                    relations = relations,
                )
            }
            ready.add(item.text.chapterIndex)
        }
        val characters = resolutions.values.map { it.characterId }.distinct()
            .mapNotNull { raCharacters.character(it) }
        if (characters.isNotEmpty()) {
            casting.ensureDefaultProfiles(bookKey, characters, "后台")
            characters.forEach { character ->
                runCatching { raCharacters.recomputeAppearance(character.id) }
            }
        }
        AppLog.putAnalysis("【V4·后台·收敛】完成：${ready.size} 章 → IDENTITY_READY")
        return ready to emptyList()
    }
}
