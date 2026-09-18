package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.usecase.AnalyzeChapterSpeechUseCase
import io.legado.app.domain.usecase.ResolveLocalSpeakersUseCase
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * W3 · 分析管线 V2（复刻自研脚本的发现式体系，本地规则先行 + AI 补全）。
 *
 *  - Stage1 话语识别：本地规则状态机（RuleBasedSpeechSegmenter 沿用），永远先出；
 *    选号/类别校正并入 Stage2 的 role 字段（一次调用完成，省一次请求）。
 *  - Stage2 归属+人物：前情提要 + 本章（[Sn] 分段）+ 后续预告 + 人物表 → 归属（从人物表选）+ 发现新人物
 *    （防幻觉校验：name 必须出现在人物表或本章正文）。
 *  - 情绪独立：独立模型队列并发调用，全失败=保留本地情绪先验。
 *  - 兜底：S2 队列空/失败 → 保留本地解析（source=Local，status=Partial）；情绪失败 → 保留本地。
 *  - 用户锁定段：重析时按 (paragraphIndex, text) 保留（userLocked 不被覆盖）。
 *  - 产出：ChapterSpeechAnalysis（resolverVersion=RESOLVER_VERSION）+ segments，直接被
 *    PrepareChapterSpeechPlanUseCase 消费（分析线与播放线解耦）。
 */
class SpeechAnalysisPipelineV2(
    private val analyzeChapterSpeech: AnalyzeChapterSpeechUseCase,
    private val resolveLocalSpeakers: ResolveLocalSpeakersUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val aiModels: AiModelRepository,
    private val ai: AiSpeechClient,
) {

    companion object {
        const val RESOLVER_VERSION = "v2-pipeline-1"
        val EMOTIONS = listOf("平静", "喜悦", "愤怒", "悲伤", "惊讶", "恐惧", "低语", "冷漠", "温柔", "紧张")

        fun genderLabel(g: String) = when (g) {
            BookCharacterProfile.VOICE_GENDER_MALE -> "男"
            BookCharacterProfile.VOICE_GENDER_FEMALE -> "女"
            else -> "未知"
        }

        fun ageLabel(a: String) = when (a) {
            BookCharacterProfile.VOICE_AGE_CHILD -> "童"
            BookCharacterProfile.VOICE_AGE_TEEN -> "少年"
            BookCharacterProfile.VOICE_AGE_YOUNG_ADULT -> "青年"
            BookCharacterProfile.VOICE_AGE_ADULT -> "中年"
            BookCharacterProfile.VOICE_AGE_ELDERLY -> "老年"
            else -> "未知"
        }

        private fun gsonAliases(json: String): List<String> =
            runCatching {
                GSON.fromJsonArray<String>(json).getOrNull().orEmpty()
            }.getOrDefault(emptyList())
    }

    private data class CastEntry(val s: Int, val speaker: String, val role: String)
    private data class EmoEntry(val s: Int, val emotion: String)

    suspend fun run(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        prevText: String = "",
        nextText: String = "",
        force: Boolean = false,
    ): ChapterSpeechAnalysisResult? = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext null
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        val existing = runCatching {
            chapterSpeechGateway.getAnalysis(bookUrl, chapterIndex, contentHash, RESOLVER_VERSION)
        }.getOrNull()
        if (!force && existing != null &&
            existing.status in setOf(SpeechAnalysisStatus.Success, SpeechAnalysisStatus.Partial)
        ) {
            val cachedSegs = runCatching { chapterSpeechGateway.getSegments(existing.id) }
                .getOrDefault(emptyList())
            if (cachedSegs.isNotEmpty()) {
                return@withContext ChapterSpeechAnalysisResult(
                    analysis = existing,
                    segments = cachedSegs,
                    fromCache = true,
                )
            }
        }
        val lockedByKey = runCatching {
            existing?.let { chapterSpeechGateway.getSegments(it.id) }
        }.getOrNull().orEmpty()
            .filter { it.userLocked }
            .associateBy { it.paragraphIndex to it.text }

        // ---- Stage1：本地规则 + 本地解析（复用既有快速链，永远先出） ----
        val localResult = runCatching {
            val raw = analyzeChapterSpeech(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                resolverVersion = RuleBasedSpeechSegmenter.VERSION,
            )
            resolveLocalSpeakers(raw, paragraphs)
        }
        if (localResult.isFailure) {
            AppLog.put("分析V2·本地阶段失败: ${localResult.exceptionOrNull()?.localizedMessage}", localResult.exceptionOrNull())
            return@withContext null
        }
        val local = localResult.getOrNull() ?: return@withContext null
        var segments = local.segments
        val chapterText = paragraphs.joinToString("") { it.text }

        // ---- Stage2 + 情绪（两条独立模型队列并发） ----
        val cfgQueues = runCatching {
            Pair(aiModels.queueRefs("stage2"), aiModels.queueRefs("emotion"))
        }.getOrDefault(Pair(emptyList(), emptyList()))
        val s2Refs = cfgQueues.first
        val emoRefs = cfgQueues.second
        val profiles = runCatching {
            bookKnowledgeGateway.getCharacterProfiles(bookUrl, 80)
                .filter { it.status == BookCharacterProfile.STATUS_ACTIVE }
        }.getOrDefault(emptyList())

        var usedAi = false
        var emotionOk = true
        coroutineScope {
            val castDeferred = async {
                if (s2Refs.isEmpty() || segments.none { it.roleType != SpeechRoleType.Narrator }) {
                    null
                } else {
                    callStage2(segments, chapterText, profiles, prevText, nextText, s2Refs)
                }
            }
            val emoDeferred = async {
                if (emoRefs.isEmpty()) null else callEmotion(segments, emoRefs)
            }
            val cast = castDeferred.await()
            if (cast != null) {
                usedAi = true
                segments = applyCast(segments, cast, profiles, chapterText)
            }
            val emo = emoDeferred.await()
            if (emo != null) {
                segments = applyEmotion(segments, emo)
            } else if (emoRefs.isNotEmpty()) {
                emotionOk = false
            }
        }

        // ---- 保留用户锁定段 ----
        if (lockedByKey.isNotEmpty()) {
            segments = segments.map { seg ->
                lockedByKey[seg.paragraphIndex to seg.text] ?: seg
            }
        }

        // ---- 落库（analysisId 确定性：重析覆盖同键旧行） ----
        val analysisId = SpeechIdentity.analysisId(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
        )
        val characterRevision = profiles.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.updatedAt}" }
        val status = when {
            s2Refs.isNotEmpty() && !usedAi -> SpeechAnalysisStatus.Partial
            emoRefs.isNotEmpty() && !emotionOk -> SpeechAnalysisStatus.Partial
            else -> SpeechAnalysisStatus.Success
        }
        val analysis = ChapterSpeechAnalysis(
            id = analysisId,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = RESOLVER_VERSION,
            characterRevision = characterRevision,
            status = status,
        )
        runCatching {
            chapterSpeechGateway.saveAnalysis(analysis, segments)
        }.onFailure {
            AppLog.put("分析V2·落库失败: ${it.localizedMessage}", it)
        }
        AppLog.put(
            "分析V2完成（${bookUrl.takeLast(12)} 第${chapterIndex + 1}章）：AI=$usedAi 情绪=$emotionOk 段数=${segments.size} status=$status",
        )
        ChapterSpeechAnalysisResult(
            analysis = analysis,
            segments = segments,
            fromCache = false,
            characterPerformances = local.characterPerformances,
        )
    }

    private suspend fun callStage2(
        segments: List<ChapterSpeechSegment>,
        chapterText: String,
        profiles: List<BookCharacterProfile>,
        prevText: String,
        nextText: String,
        refs: List<AiSpeechClient.ModelRef>,
    ): List<CastEntry>? {
        val castLines = profiles.joinToString("\n") { p ->
            val aliases = runCatching {
                gsonAliases(p.aliasesJson).takeIf { it.isNotEmpty() }?.let { "；别名：${it.joinToString("/")}" }
            }.getOrNull().orEmpty()
            "- ${p.name}（${genderLabel(p.voiceGender)}/${ageLabel(p.voiceAgeBand)}）$aliases"
        }
        val segLines = segments.mapIndexed { i, seg -> "[S${i + 1}] ${seg.text}" }.joinToString("\n")
        val system = "你是有声书朗读导演，负责把小说文本中的台词归属到角色。只输出 JSON，不要输出任何解释。"
        val user = buildString {
            appendLine("【人物表】")
            if (castLines.isBlank()) appendLine("（无已知人物）") else appendLine(castLines)
            appendLine()
            appendLine("【前情提要】")
            appendLine(prevText.takeLast(600).ifBlank { "（无）" })
            appendLine()
            appendLine("【本章正文（已按旁白/台词切分，[Sn] 为段编号）】")
            appendLine(segLines)
            appendLine()
            appendLine("【下一章开头】")
            appendLine(nextText.take(400).ifBlank { "（无）" })
            appendLine()
            appendLine("任务：为每个 [Sn] 判断归属。")
            appendLine("规则：")
            appendLine("1. 旁白/环境描写/叙述 → role=narration，speaker 留空。")
            appendLine("2. 引号内台词 → role=dialogue；人物内心独白 → role=thought；两者 speaker 填说话人。")
            appendLine("3. speaker 必须是人物表中的名字；人物表没有但正文出现的新人物，用原文中的称呼。")
            appendLine("4. 禁止发明人物表与正文中都不存在的名字。")
            appendLine("输出 JSON：{\"cast\":[{\"s\":1,\"speaker\":\"\",\"role\":\"narration|dialogue|thought\"}]}，每段一条，按 s 升序，覆盖全部段。")
        }
        return ai.complete(refs, system, user) { raw ->
            val root = ai.extractJson(raw) ?: return@complete null
            val cast = root.optJSONArray("cast") ?: return@complete null
            val valid = cast.objects().mapNotNull { o ->
                val s = o.optInt("s", 0)
                val role = o.optString("role")
                if (s < 1 || s > segments.size) return@mapNotNull null
                if (role !in listOf("narration", "dialogue", "thought")) return@mapNotNull null
                CastEntry(s = s, speaker = o.optString("speaker").trim(), role = role)
            }
            if (valid.size < segments.size / 2) null else valid
        }
    }

    private suspend fun callEmotion(
        segments: List<ChapterSpeechSegment>,
        refs: List<AiSpeechClient.ModelRef>,
    ): List<EmoEntry>? {
        val dialogue = segments.filter { it.roleType != SpeechRoleType.Narrator }
        if (dialogue.isEmpty()) return null
        val lines = dialogue.mapIndexed { i, seg -> "[S${i + 1}] ${seg.text.take(120)}" }
            .joinToString("\n")
        val system = "你是台词情绪标注员。只输出 JSON。"
        val user = buildString {
            appendLine("【本章台词】")
            appendLine(lines)
            appendLine()
            appendLine("为每个 [Sn] 标注情绪，emotion 从：${EMOTIONS.joinToString("、")} 中选，拿不准用「平静」。")
            appendLine("输出 JSON：{\"e\":[{\"s\":1,\"emotion\":\"平静\"}]}，每段一条。")
        }
        return ai.complete(refs, system, user) { raw ->
            val root = ai.extractJson(raw) ?: return@complete null
            val arr = root.optJSONArray("e") ?: return@complete null
            val valid = arr.objects().mapNotNull { o ->
                val s = o.optInt("s", 0)
                val emo = o.optString("emotion").trim()
                if (s < 1 || s > segments.size) return@mapNotNull null
                if (emo !in EMOTIONS) return@mapNotNull null
                EmoEntry(s = s, emotion = emo)
            }
            if (valid.isEmpty()) null else valid
        }
    }

    private fun applyCast(
        segments: List<ChapterSpeechSegment>,
        cast: List<CastEntry>,
        profiles: List<BookCharacterProfile>,
        chapterText: String,
    ): List<ChapterSpeechSegment> {
        val byS = cast.associateBy { it.s }
        val profileByKey = buildMap {
            profiles.forEach { p ->
                put(p.name, p)
                runCatching { gsonAliases(p.aliasesJson) }.getOrNull()?.forEach { alias ->
                    if (alias.isNotBlank()) put(alias, p)
                }
            }
        }
        return segments.mapIndexed { i, seg ->
            val entry = byS[i + 1] ?: return@mapIndexed seg
            val speaker = entry.speaker
            if (speaker.isEmpty() || speaker == "旁白") {
                return@mapIndexed when (entry.role) {
                    "narration" -> seg.copy(roleType = SpeechRoleType.Narrator, source = SpeechResolutionSource.Ai)
                    else -> seg
                }
            }
            val profile = profileByKey[speaker]
            // 防幻觉：名字必须来自人物表或本章正文
            if (profile == null && !chapterText.contains(speaker)) return@mapIndexed seg
            val role = when (entry.role) {
                "thought" -> SpeechRoleType.Thought
                "narration" -> SpeechRoleType.Narrator
                else -> SpeechRoleType.Character
            }
            seg.copy(
                roleType = role,
                characterId = profile?.id,
                characterName = profile?.name ?: speaker,
                source = SpeechResolutionSource.Ai,
                confidence = 0.9f,
            )
        }
    }

    private fun applyEmotion(
        segments: List<ChapterSpeechSegment>,
        emo: List<EmoEntry>,
    ): List<ChapterSpeechSegment> {
        val byS = emo.associateBy { it.s }
        return segments.mapIndexed { i, seg ->
            byS[i + 1]?.let { seg.copy(emotion = it.emotion) } ?: seg
        }
    }
}
