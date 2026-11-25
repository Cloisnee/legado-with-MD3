package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.help.readaloud.analysis.QuoteSpeechRules
import io.legado.app.help.readaloud.analysis.SpeechSelectionAssembly
import io.legado.app.help.readaloud.analysis.ValidateOutcome
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单章与批量共用的观察包装配层（03 §2.2/§3）：
 * 段内片段切分、阶段1 选号校验与装配、编号渲染、前情/后文取文、
 * localSpeaker/mention/evidence 构造、属性/关系观察收集、seqmap 绑定。
 *
 * 这里只放纯逻辑；AI 调用、落库与身份裁决仍在 Pipeline/Resolver。
 */
object ObservationAssembly {

    data class TextUnit(val start: Int, val end: Int, val text: String)
    data class ParaUnits(val paraIndex: Int, val units: List<TextUnit>)
    data class SpRange(val para: Int, val start: Int, val end: Int)

    fun buildParaUnits(paragraphs: List<CanonicalSpeechParagraph>): List<ParaUnits> {
        return paragraphs.mapNotNull { p ->
            val units = QuoteSpeechRules.splitToUnits(p.text).map { u ->
                TextUnit(u.start, u.end, p.text.substring(u.start, u.end))
            }
            if (units.isEmpty()) null else ParaUnits(p.index, units)
        }
    }

    fun buildUnitsText(cands: List<ParaUnits>): String = buildString {
        cands.forEach { c ->
            append("〖第").append(c.paraIndex + 1).append("段〗\n")
            c.units.forEachIndexed { i, u ->
                append("[").append(i + 1).append("] ").append(u.text).append("\n")
            }
        }
    }

    /** B28：第1阶段话语校验——仅由标点/符号构成（无任何汉字/数字/字母）的"话语"不成立 → 转旁白（原地剔除，静默不重试） */
    fun pruneSymbolOnly(
        paragraphs: List<CanonicalSpeechParagraph>,
        ranges: MutableList<SpRange>,
    ): Int {
        val byIdx = paragraphs.associateBy { it.index }
        val before = ranges.size
        ranges.removeAll { r ->
            val pr = byIdx[r.para] ?: return@removeAll true
            val s = pr.text
            val st = r.start.coerceIn(0, s.length)
            val en = r.end.coerceIn(st, s.length)
            !(st < en && s.substring(st, en).any { it.isLetterOrDigit() })
        }
        return before - ranges.size
    }

    fun assemble(
        paragraphs: List<CanonicalSpeechParagraph>,
        ranges: List<SpRange>,
    ): List<RaSegment> {
        val out = ArrayList<RaSegment>()
        val byPara = ranges.groupBy { it.para }
        paragraphs.forEach { p ->
            val rs = (byPara[p.index] ?: emptyList()).sortedBy { it.start }
            var cursor = 0
            rs.forEach { r ->
                if (cursor < r.start) {
                    val t = p.text.substring(cursor, r.start)
                    if (t.isNotBlank()) out.add(seg(p, cursor, r.start, t, SpeechRoleType.Narrator))
                }
                val rs2 = r.start.coerceIn(0, p.text.length)
                val re2 = r.end.coerceIn(rs2, p.text.length)
                val t = p.text.substring(rs2, re2)
                if (t.isNotBlank()) {
                    // B31：心理活动标签（Thought）注入已拔除（云链路待 B32 一并处置；枚举保留兼容老数据）
                    out.add(seg(p, rs2, re2, t, SpeechRoleType.Character))
                }
                cursor = re2
            }
            if (cursor < p.text.length) {
                val t = p.text.substring(cursor)
                if (t.isNotBlank()) out.add(seg(p, cursor, p.text.length, t, SpeechRoleType.Narrator))
            }
        }
        return out
    }

    private fun seg(
        p: CanonicalSpeechParagraph,
        start: Int,
        end: Int,
        text: String,
        role: SpeechRoleType,
    ): RaSegment = RaSegment(
        id = "",
        analysisId = "",
        bookKey = "",
        chapterIndex = 0,
        paragraphIndex = p.index,
        start = start,
        end = end,
        chapterPosition = p.chapterPosition + start,
        text = text,
        roleType = role,
        surfaceName = "",
        source = SpeechResolutionSource.Rule,
    )

    /** 编号渲染（B31：[n]〔content（外层引号已去除）〕/ [N]旁白；一行一项、段间空行）——发给第2阶段/情绪 */
    fun renderNumbered(segments: List<RaSegment>): String {
        val sb = StringBuilder()
        var n = 0
        var lastP = -1
        segments.forEach { s ->
            if (lastP != -1 && s.paragraphIndex != lastP) sb.append("\n")
            if (s.roleType == SpeechRoleType.Narrator) {
                sb.append("[N]").append(s.text)
            } else {
                n++
                sb.append("[").append(n).append("]〔").append(QuoteSpeechRules.unwrapOuterBlock(s.text)).append("〕")
            }
            sb.append("\n")
            lastP = s.paragraphIndex
        }
        return sb.toString().trimEnd('\n')
    }

    /** 前情/后续取文：按完整段落取，累计不超过 limit（0..3000），不截断段落 */
    fun takeContextByParagraphs(text: String, limit: Int, fromTail: Boolean): String {
        if (limit <= 0 || text.isBlank()) return ""
        val paras = text.split("\n").filter { it.isNotBlank() }
        if (paras.isEmpty()) return ""
        val out = ArrayList<String>()
        var total = 0
        var i = if (fromTail) paras.size - 1 else 0
        val step = if (fromTail) -1 else 1
        while (i in paras.indices) {
            val p = paras[i]
            if (total + p.length > limit) break
            out.add(p)
            total += p.length
            i += step
        }
        return (if (fromTail) out.reversed() else out).joinToString("\n")
    }

    fun buildLocalSpeakers(
        analysisId: String,
        entries: List<Stage2Speaker>,
        batchEntityIdFromKey: Boolean = false,
    ): List<RaLocalSpeaker> = entries.map { entry ->
        RaLocalSpeaker(
            id = "$analysisId#${entry.key}",
            analysisId = analysisId,
            localKey = entry.key,
            surfaceNames = entry.allNames,
            primaryName = entry.name,
            roleType = entry.roleType,
            gender = entry.gender,
            ageBand = entry.ageBand,
            batchEntityId = if (batchEntityIdFromKey) entry.key else null,
            resolution = RaResolution.PENDING,
        )
    }

    fun buildLocalMentions(
        analysisId: String,
        entries: List<Stage2Speaker>,
        batchEntityIdFromKey: Boolean = false,
    ): List<RaLocalMention> = entries.map { entry ->
        RaLocalMention(
            id = "$analysisId#${entry.key}",
            analysisId = analysisId,
            localKey = entry.key,
            surfaceNames = entry.allNames,
            primaryName = entry.name,
            roleType = entry.roleType,
            gender = entry.gender,
            ageBand = entry.ageBand,
            batchEntityId = if (batchEntityIdFromKey) entry.key else null,
            resolution = RaResolution.PENDING,
        )
    }

    fun buildEvidence(
        analysisId: String,
        bookKey: String,
        entries: List<Stage2Evidence>,
    ): List<RaEvidence> = entries.map { e ->
        val subjectLocalId = "$analysisId#${e.subjectKey}"
        RaEvidence(
            id = SpeechIdentity.evidenceId(
                analysisId = analysisId,
                sourceChapter = e.chapter,
                paragraphIndex = e.paragraphIndex,
                start = e.start,
                type = e.type.storageValue,
                subjectLocalId = subjectLocalId,
                objectText = if (e.type == RaEvidenceType.RELATION && e.relation.isNotBlank()) {
                    "${e.objectText}|${e.relation}|${e.objectKey.orEmpty()}"
                } else {
                    e.objectText
                },
                quote = e.quote,
            ),
            bookKey = bookKey,
            analysisId = analysisId,
            sourceChapter = e.chapter,
            paragraphIndex = e.paragraphIndex,
            start = e.start,
            end = e.end,
            excerpt = e.quote,
            type = e.type,
            subjectLocalId = subjectLocalId,
            objectText = if (e.type == RaEvidenceType.RELATION && e.relation.isNotBlank()) {
                "${e.objectText}|${e.relation}|${e.objectKey.orEmpty()}"
            } else {
                e.objectText
            },
            scope = if (e.scope == RaEvidenceScope.CONTEXT_CLUE) {
                RaEvidenceScope.CONTEXT_CLUE
            } else {
                RaEvidenceScope.MAIN_TEXT
            },
            verified = e.verified,
        )
    }

    /** 给裁决的“本批相关原文”：每人取首句所在段 + 前后各 1 段，≤1200 字。 */
    fun buildContextBySpeaker(
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<RaSegment>,
        seqMap: Map<Int, String>,
        speakers: List<RaLocalSpeaker>,
    ): Map<String, String> {
        val byKey = speakers.associateBy { it.localKey }
        val out = HashMap<String, String>()
        segments.filter { it.seq > 0 }.forEach { segment ->
            val key = seqMap[segment.seq] ?: return@forEach
            if (key == "N" || out.containsKey(key)) return@forEach
            byKey[key] ?: return@forEach
            val index = paragraphs.indexOfFirst { it.index == segment.paragraphIndex }
            if (index < 0) return@forEach
            val from = (index - 1).coerceAtLeast(0)
            val to = (index + 1).coerceAtMost(paragraphs.lastIndex)
            out[key] = paragraphs.subList(from, to + 1)
                .joinToString("\n") { it.text }
                .take(1200)
        }
        return out
    }

    /** 属性/关系观察与章节分析同事务写入（default 来源不写）。 */
    fun collectObservations(
        bookKey: String,
        chapterIndex: Int,
        payload: Stage2Payload?,
        keyToCharacter: Map<String, String>,
        evidence: List<RaEvidence>,
    ): Pair<List<RaAttributeObservation>, List<RaRelationObservation>> {
        val attributes = ArrayList<RaAttributeObservation>()
        val relations = ArrayList<RaRelationObservation>()
        if (payload == null) return attributes to relations
        payload.speakers.forEach { speaker ->
            val characterId = keyToCharacter[speaker.key] ?: return@forEach
            if (speaker.genderSource != "default" && speaker.gender.isNotBlank()) {
                attributes.add(
                    RaAttributeObservation(
                        id = "att_${characterId}_gender_$chapterIndex",
                        bookKey = bookKey,
                        characterId = characterId,
                        key = "gender",
                        value = speaker.gender,
                        fromChapter = chapterIndex,
                        source = speaker.genderSource,
                    ),
                )
            }
            if (speaker.ageSource != "default" && speaker.ageBand.isNotBlank()) {
                attributes.add(
                    RaAttributeObservation(
                        id = "att_${characterId}_ageBand_$chapterIndex",
                        bookKey = bookKey,
                        characterId = characterId,
                        key = "ageBand",
                        value = speaker.ageBand,
                        fromChapter = chapterIndex,
                        source = speaker.ageSource,
                    ),
                )
            }
        }
        evidence.filter { it.scope == RaEvidenceScope.MAIN_TEXT && it.verified }.forEach { e ->
            val localKey = e.subjectLocalId.substringAfterLast('#')
            val characterId = keyToCharacter[localKey] ?: return@forEach
            when (e.type) {
                RaEvidenceType.RELATION -> {
                    if (e.objectText.isNotBlank()) {
                        val parts = e.objectText.split('|', limit = 3)
                        val targetKey = parts.getOrElse(2) { "" }
                        relations.add(
                            RaRelationObservation(
                                id = "rel_${e.id}",
                                bookKey = bookKey,
                                characterId = characterId,
                                relation = parts.getOrElse(1) { "关系" },
                                targetCharacterId = keyToCharacter[targetKey],
                                targetText = parts[0],
                                fromChapter = chapterIndex,
                                evidenceId = e.id,
                            ),
                        )
                    }
                }
                RaEvidenceType.ATTRIBUTE -> {
                    if (e.objectText.isNotBlank()) {
                        attributes.add(
                            RaAttributeObservation(
                                id = "att_${e.id}",
                                bookKey = bookKey,
                                characterId = characterId,
                                key = "identity",
                                value = e.objectText.take(60),
                                fromChapter = chapterIndex,
                                evidenceId = e.id,
                                source = "explicit",
                            ),
                        )
                    }
                }
                else -> Unit
            }
        }
        return attributes to relations
    }

    fun applyStage2(
        segments: List<RaSegment>,
        seqMap: Map<Int, String>,
    ): List<RaSegment> {
        var n = 0
        return segments.map { s ->
            if (s.roleType == SpeechRoleType.Narrator) {
                s
            } else {
                n++
                val reference = seqMap[n]
                if (reference.isNullOrBlank() || reference == "N") {
                    s.copy(
                        roleType = SpeechRoleType.Narrator,
                        surfaceName = "",
                        characterId = null,
                        localSpeakerId = null,
                        source = SpeechResolutionSource.Ai,
                        seq = 0,
                    )
                } else {
                    s.copy(
                        surfaceName = "",
                        characterId = null,
                        source = SpeechResolutionSource.Ai,
                        confidence = 0.9f,
                        seq = n,
                    )
                }
            }
        }
    }

    /** 判定结构校验（B31.1：段号/长度/缺段 全量收集 → 带理由 failHint 重试；宽容解析 串/数组/布尔） */
    fun validateSelection(
        extractJson: (String) -> JSONObject?,
        raw: String,
        unitCounts: Map<Int, Int>,
    ): ValidateOutcome<Map<Int, BooleanArray>> {
        val root = extractJson(raw) ?: return ValidateOutcome(null, "返回不是JSON对象")
        val list = root.optJSONArray("段落") ?: root.optJSONArray("段") ?: root.optJSONArray("paragraphs")
            ?: return ValidateOutcome(null, "缺少段落列表字段")
        val totalParas = unitCounts.size
        val map = HashMap<Int, BooleanArray>()
        val seen = HashSet<Int>()
        val errs = ArrayList<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i)
            if (item == null) {
                errs.add("存在非对象的段落条目")
                continue
            }
            val nRaw: Any? = if (item.has("段号")) item.opt("段号") else item.opt("id")
            if (nRaw == null) {
                errs.add("存在缺少段号的条目")
                continue
            }
            val n = nRaw.toString().trim().toIntOrNull()
            if (n == null) {
                errs.add("段号不是整数：「$nRaw」")
                continue
            }
            if (n < 1 || n > totalParas) {
                errs.add("段号越界：$n（全文共${totalParas}段）")
                continue
            }
            if (n in seen) {
                errs.add("段号重复：$n")
                continue
            }
            seen.add(n)
            val maxU = unitCounts[n - 1] ?: 0
            val rawFlag: Any? = when {
                item.has("判定") -> item.opt("判定")
                item.has("flags") -> item.opt("flags")
                item.has("标记") -> item.opt("标记")
                else -> null
            }
            val flags = SpeechSelectionAssembly.parseFlags(jsonToPlainFlags(rawFlag), maxU)
            if (flags == null) {
                errs.add("第${n}段判定缺失或长度不符（本段共${maxU}片）")
                continue
            }
            map[n - 1] = flags
        }
        // B31.1：缺段=校验失败——带理由打回重析（不再宽容转旁白）
        val missing = unitCounts.filter { (para, cnt) -> cnt > 0 && para !in map }.keys.sorted()
        if (missing.isNotEmpty()) {
            val shown = missing.take(8).joinToString("、") { (it + 1).toString() }
            val tail = if (missing.size > 8) "…等${missing.size}个" else ""
            errs.add("缺少判定的段落：$shown$tail（每段都必须输出与片段数等长的判定数组）")
        }
        if (errs.isNotEmpty()) return ValidateOutcome(null, errs.joinToString("；"))
        return ValidateOutcome(map)
    }

    /** JSONArray → 纯 Kotlin 结构（供 [SpeechSelectionAssembly.parseFlags] 宽容解析） */
    private fun jsonToPlainFlags(v: Any?): Any? = when (v) {
        is JSONArray -> (0 until v.length()).map { v.opt(it) }
        else -> v
    }

}
