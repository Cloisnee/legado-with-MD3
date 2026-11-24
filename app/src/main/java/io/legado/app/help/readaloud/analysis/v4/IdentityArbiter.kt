package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.data.repository.AiModelRepository
import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaVerdict
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
        return ai.completeValidated(
            refs = refs,
            system = "只输出 JSON。",
            promptFactory = promptFactory,
            maxTokens = maxTokens,
            logTag = "裁决",
        ) { raw -> validate(raw, questions) }
    }

    private fun validate(
        raw: String,
        questions: List<IdentityQuestion>,
    ): ValidateOutcome<List<IdentityAnswer>> {
        val root = runCatching { JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }
            .getOrNull() ?: return ValidateOutcome(null, "返回不是JSON对象")
        val arr = root.optJSONArray("decisions") ?: root.optJSONArray("裁决")
            ?: return ValidateOutcome(null, "缺少decisions")
        val byKey = questions.associateBy { it.key }
        val out = LinkedHashMap<String, IdentityAnswer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("q").trim()
            val question = byKey[key] ?: continue
            val verdict = RaVerdict.fromStorage(o.optString("verdict").trim().uppercase())
            val candidateIds = question.candidates.map { it.characterId }.toSet()
            var characterId = o.optString("characterId").trim().takeIf { it.isNotEmpty() }
            if (verdict == RaVerdict.SAME && characterId !in candidateIds) {
                continue
            }
            var confidence = o.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
            val quotes = ArrayList<String>()
            val ev = o.optJSONArray("evidence")
            if (ev != null) {
                for (j in 0 until ev.length()) {
                    ev.optJSONObject(j)?.optString("quote")?.trim()
                        ?.takeIf { it.isNotEmpty() }?.let(quotes::add)
                }
            }
            if (verdict == RaVerdict.SAME && quotes.isEmpty()) {
                confidence = minOf(confidence, 0.6f)
            }
            val reason = o.optString("reason").trim().take(200)
            out[key] = IdentityAnswer(
                key = key,
                verdict = verdict,
                characterId = characterId,
                confidence = confidence,
                reason = reason,
                evidenceQuotes = quotes,
                modelId = "",
            )
        }
        // 输出缺失的 q 视为 UNKNOWN，避免一次漏答导致整批失败
        questions.forEach { q ->
            out.putIfAbsent(
                q.key,
                IdentityAnswer(
                    key = q.key,
                    verdict = RaVerdict.UNKNOWN,
                    characterId = null,
                    confidence = 0f,
                    reason = "裁决未返回该对象",
                    evidenceQuotes = emptyList(),
                ),
            )
        }
        return ValidateOutcome(out.values.toList())
    }
}
