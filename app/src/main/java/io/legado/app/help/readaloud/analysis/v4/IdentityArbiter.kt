package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.data.repository.AiModelRepository
import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaVerdict
import io.legado.app.help.readaloud.analysis.AiSchedulerContext
import io.legado.app.help.readaloud.analysis.AiSpeechClient
import io.legado.app.help.readaloud.analysis.queueRefs
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.ValidateOutcome
import org.json.JSONArray
import org.json.JSONObject

/** 一个待裁决的章内说话人。 */
data class IdentityQuestion(
    val key: String,
    val chapterLabel: String,
    val speaker: RaLocalSpeaker,
    val evidence: List<RaEvidence>,
    val contextText: String,
    val candidates: List<RaCharacterCard>,
)

data class IdentityAnswer(
    val key: String,
    val verdict: RaVerdict,
    val characterId: String?,
    val confidence: Float,
    val reason: String,
    val evidenceQuotes: List<String>,
    val modelId: String = "",
)

interface IdentityArbiter {
    suspend fun decide(bookKey: String, questions: List<IdentityQuestion>): List<IdentityAnswer>?

    /** 带调度上下文的裁决入口；默认退化为无调度版本（测试桩、旧调用方）。 */
    suspend fun decide(
        bookKey: String,
        questions: List<IdentityQuestion>,
        context: AiSchedulerContext?,
    ): List<IdentityAnswer>? = decide(bookKey, questions)
}

/**
 * 身份裁决（resolve 队列）：一次请求可裁多个对象（最多 8 个）。
 * 无模型队列 / 调用失败返回 null，由 [IdentityResolver] 建 PROVISIONAL。
 */
class AiIdentityArbiter(
    private val aiModels: AiModelRepository,
    private val ai: AiSpeechClient,
    private val configStore: AnalysisConfigStore,
    private val cardProjector: CharacterCardProjector,
) : IdentityArbiter {

    override suspend fun decide(
        bookKey: String,
        questions: List<IdentityQuestion>,
    ): List<IdentityAnswer>? = decide(bookKey, questions, null)

    override suspend fun decide(
        bookKey: String,
        questions: List<IdentityQuestion>,
        context: AiSchedulerContext?,
    ): List<IdentityAnswer>? {
        if (questions.isEmpty()) return emptyList()
        val refs = runCatching { aiModels.queueRefs("resolve") }.getOrDefault(emptyList())
        if (refs.isEmpty()) return null
        val cfg = runCatching { configStore.load() }.getOrDefault(AnalysisConfigStore.Config())
        val head = cfg.resolvePrompt.ifBlank { V4Prompts.DEFAULT_RESOLVE_PROMPT }
        val user = buildString {
            append("【待判定对象】\n")
            questions.forEach { q ->
                append("${q.key}：${q.chapterLabel}中的「${q.speaker.primaryName}」")
                append("（章内键 ${q.speaker.localKey}）\n")
                val names = q.speaker.surfaceNames.filter { it.isNotBlank() }
                if (names.isNotEmpty()) append("  本批称呼：").append(names.joinToString("、")).append("\n")
                if (q.evidence.isNotEmpty()) {
                    append("  本批证据：\n")
                    q.evidence.take(5).forEach { e ->
                        append("    - ").append(e.type.storageValue).append("：")
                            .append(e.excerpt.take(80)).append("\n")
                    }
                }
                if (q.contextText.isNotBlank()) {
                    append("  本批相关原文：\n").append(q.contextText.take(1200)).append("\n")
                }
            }
            append("\n【候选人物】\n")
            val allCandidates = questions.flatMap { it.candidates }.distinctBy { it.characterId }
            allCandidates.forEach { card ->
                append(card.characterId).append("：")
                    .append(cardProjector.toResolverText(card)).append("\n")
            }
        }
        val promptFactory = { failHint: String ->
            buildString {
                append(head)
                if (failHint.isNotBlank()) {
                    append("\n【重要】你上一次的输出存在以下问题：").append(failHint)
                        .append("。请修正后重新输出完整JSON。\n")
                }
                append("\n=== 待裁决内容 ===\n").append(user)
            }
        }
        val maxTokens = cfg.maxOutputTokens.coerceIn(1024, 8192)
        var usedModel = ""
        val result = ai.completeValidated(
            refs = refs,
            system = "只输出 JSON。",
            promptFactory = promptFactory,
            maxTokens = maxTokens,
            logTag = "裁决",
            context = context,
            onModelUsed = { usedModel = it },
        ) { raw -> validateIdentityDecisions(raw, questions) }
        return result?.map { if (it.modelId.isBlank()) it.copy(modelId = usedModel) else it }
    }
}

fun validateIdentityDecisions(
    raw: String,
    questions: List<IdentityQuestion>,
): ValidateOutcome<List<IdentityAnswer>> {
        val root = runCatching {
            JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        }.getOrNull() ?: return ValidateOutcome(null, "返回不是JSON对象")
        val arr = root.optJSONArray("decisions") ?: root.optJSONArray("裁决")
            ?: return ValidateOutcome(null, "缺少decisions")
        val byKey = questions.associateBy { it.key }
        val out = LinkedHashMap<String, IdentityAnswer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return ValidateOutcome(null, "decisions存在非对象条目")
            val key = o.optString("q").trim()
            val question = byKey[key] ?: return ValidateOutcome(null, "未知q=$key")
            if (out.containsKey(key)) return ValidateOutcome(null, "q重复：$key")
            val verdict = when (o.optString("verdict").trim().uppercase()) {
                "SAME" -> RaVerdict.SAME
                "DIFFERENT" -> RaVerdict.DIFFERENT
                "UNKNOWN" -> RaVerdict.UNKNOWN
                else -> return ValidateOutcome(null, "$key verdict非法")
            }
            if (!o.has("confidence")) return ValidateOutcome(null, "$key 缺少confidence")
            val rawConfidence = o.optDouble("confidence", Double.NaN)
            if (rawConfidence.isNaN() || rawConfidence < 0.0 || rawConfidence > 1.0) {
                return ValidateOutcome(null, "$key confidence越界")
            }
            var confidence = rawConfidence.toFloat()
            val candidateIds = question.candidates.map { it.characterId }.toSet()
            val characterId = o.optString("characterId").trim().takeIf { it.isNotEmpty() }
            if (verdict == RaVerdict.SAME && characterId !in candidateIds) {
                return ValidateOutcome(null, "$key SAME的characterId不在候选列表")
            }
            val quotes = ArrayList<String>()
            val ev = o.optJSONArray("evidence")
            if (ev != null) {
                for (j in 0 until ev.length()) {
                    val quoteObj = ev.optJSONObject(j) ?: continue
                    val quote = quoteObj.optString("quote").trim()
                        .takeIf { it.isNotEmpty() } ?: continue
                    val ch = quoteObj.optInt("ch", -1)
                    val p = quoteObj.optInt("p", -1)
                    if (isVerifiableQuote(question, quote, ch, p)) quotes.add(quote)
                }
            }
            if (verdict == RaVerdict.SAME && quotes.isEmpty()) {
                confidence = minOf(confidence, 0.6f)
            }
            out[key] = IdentityAnswer(
                key = key,
                verdict = verdict,
                characterId = characterId,
                confidence = confidence,
                reason = o.optString("reason").trim().take(200),
                evidenceQuotes = quotes,
                modelId = "",
            )
        }
        val missing = questions.map { it.key }.filter { it !in out.keys }
        if (missing.isNotEmpty()) return ValidateOutcome(null, "缺少q=${missing.joinToString(",")}")
        return ValidateOutcome(questions.map { out.getValue(it.key) })
    }

    private fun isVerifiableQuote(
        question: IdentityQuestion,
        quote: String,
        ch: Int,
        p: Int,
    ): Boolean {
        return question.evidence.any { e ->
            e.sourceChapter == ch && e.paragraphIndex == p - 1 &&
                (e.excerpt.contains(quote) || e.objectText.contains(quote))
        }
    }
