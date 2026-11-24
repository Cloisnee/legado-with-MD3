package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.gateway.GenericNameClassifier
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaCharacterStatus
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaVerdict

/**
 * V4-2 完整身份解析（01 §5.3）：
 *  ① 确定性快速链接：本章 ALIAS_EQUAL 证据 / 非泛称称呼唯一命中，且无 CANNOT、无反证；
 *  ② 否则候选召回 Top-5（非泛称 1.0；泛称+关系 0.4；PROVISIONAL 关联 0.5）；
 *  ③ 无候选 → 新建 ACTIVE；
 *  ④ 有候选 → AI 三态裁决（resolve 队列，最多 8 个/请求）；
 *  ⑤ SAME≥0.75 链接；DIFFERENT 新建并写 CANNOT；UNKNOWN/低置信/失败 → PROVISIONAL。
 *
 * 解析器不读写台词文本，只维护 localSpeaker→characterId 与别名/约束。
 */
class IdentityResolver(
    private val characters: RaCharacterGateway,
    private val genericNames: GenericNameClassifier,
    private val cards: CharacterCardProjector? = null,
    private val arbiter: IdentityArbiter? = null,
) {

    data class Resolution(
        val characterId: String,
        val resolution: RaResolution,
        val created: Boolean,
        val candidateIds: List<String> = emptyList(),
        val createdCharacter: RaCharacter? = null,
    )

    data class ChapterResult(
        val resolutions: Map<String, Resolution>,
        val created: List<RaCharacter>,
    )

    private data class Candidate(
        val character: RaCharacter,
        val score: Float,
    )

    private data class Pending(
        val speaker: RaLocalSpeaker,
        val evidence: List<RaEvidence>,
        val candidates: List<Candidate>,
        val contextText: String,
    )

    suspend fun resolveChapter(
        bookKey: String,
        chapterIndex: Int,
        chapterLabel: String,
        speakers: List<RaLocalSpeaker>,
        mentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        contextBySpeaker: Map<String, String> = emptyMap(),
    ): ChapterResult {
        if (speakers.isEmpty()) return ChapterResult(emptyMap(), emptyList())
        val linkedThisChapter = LinkedHashSet<String>()
        val resolutions = LinkedHashMap<String, Resolution>()
        val created = ArrayList<RaCharacter>()
        val pending = ArrayList<Pending>()

        // 按出场顺序处理；确定性链接优先于候选召回
        speakers.forEach { speaker ->
            val speakerEvidence = evidence.filter { it.subjectLocalId == speaker.id }
            val verifiedMain = speakerEvidence.filter { it.scope.storageValue == "MAIN_TEXT" && it.verified }
            val contradictions = verifiedMain
                .filter { it.type == RaEvidenceType.CONTRADICTION }
                .mapNotNull { it.objectText.trim().takeIf(String::isNotEmpty) }
            val aliasEqualNames = verifiedMain
                .filter { it.type == RaEvidenceType.ALIAS_EQUAL }
                .mapNotNull { it.objectText.trim().takeIf(String::isNotEmpty) }

            // ①a ALIAS_EQUAL：把当前称呼与已有人物的非泛称别名连起来
            val aliasEqualTarget = uniqueAliasTarget(bookKey, aliasEqualNames, contradictions)
            if (aliasEqualTarget != null) {
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = chapterIndex,
                    speaker = speaker,
                    target = aliasEqualTarget,
                    candidateIds = listOf(aliasEqualTarget.id),
                )
                linkedThisChapter.add(aliasEqualTarget.id)
                return@forEach
            }

            // ①b 非泛称称呼精确命中且唯一 ACTIVE
            val exactTargets = exactTargets(bookKey, speaker, contradictions)
            if (exactTargets.size == 1) {
                val target = exactTargets.first()
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = chapterIndex,
                    speaker = speaker,
                    target = target,
                    candidateIds = listOf(target.id),
                )
                linkedThisChapter.add(target.id)
                return@forEach
            }

            // ② 候选召回；排除同场已链接人物、本章明确反证的人物
            val blockedByContradiction = contradictionIds(bookKey, contradictions)
            val candidates = recallCandidates(
                bookKey = bookKey,
                speaker = speaker,
                evidence = verifiedMain,
                exclude = linkedThisChapter + blockedByContradiction,
            )
            when {
                candidates.isEmpty() -> {
                    val character = create(bookKey, chapterIndex, speaker, RaCharacterStatus.ACTIVE)
                    created.add(character)
                    resolutions[speaker.localKey] = Resolution(
                        characterId = character.id,
                        resolution = RaResolution.RESOLVED,
                        created = true,
                        createdCharacter = character,
                    )
                    linkedThisChapter.add(character.id)
                }
                else -> {
                    pending.add(
                        Pending(
                            speaker = speaker,
                            evidence = verifiedMain,
                            candidates = candidates,
                            contextText = contextBySpeaker[speaker.localKey].orEmpty(),
                        ),
                    )
                }
            }
        }

        // ④ AI 三态裁决（每 8 个一批），失败/无队列 → PROVISIONAL
        val answers = decideAll(bookKey, chapterIndex, chapterLabel, pending)
        pending.forEach { item ->
            val answer = answers[item.speaker.localKey]
            val candidateIds = item.candidates.map { it.character.id }
            if (answer == null) {
                // 没有裁决能力时保守处理：非泛称精确命中/临时人物关联才暂链，否则新建 PROVISIONAL。
                // 这样“师兄、店伙计”这类泛称不会因召回候选而误并。
                val fallback = item.candidates.firstOrNull {
                    it.score >= 1.0f && it.character.status == RaCharacterStatus.ACTIVE
                } ?: item.candidates.firstOrNull {
                    it.score >= 0.5f && it.character.status == RaCharacterStatus.PROVISIONAL
                }
                if (fallback != null) {
                    val linked = link(
                        bookKey = bookKey,
                        chapterIndex = chapterIndex,
                        speaker = item.speaker,
                        target = fallback.character,
                        candidateIds = candidateIds,
                    )
                    charDecision(
                        bookKey = bookKey,
                        speaker = item.speaker,
                        candidates = candidateIds,
                        verdict = RaVerdict.UNKNOWN,
                        chosen = fallback.character.id,
                        confidence = 0f,
                        reason = "裁决未执行（未配置 resolve 模型或调用失败），暂链最早候选",
                        evidenceIds = item.evidence.map { it.id },
                    )
                    resolutions[item.speaker.localKey] = linked.copy(
                        resolution = RaResolution.PROVISIONAL,
                        candidateIds = candidateIds,
                    )
                    linkedThisChapter.add(fallback.character.id)
                } else {
                    val character = create(bookKey, chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
                    created.add(character)
                    charDecision(
                        bookKey = bookKey,
                        speaker = item.speaker,
                        candidates = candidateIds,
                        verdict = RaVerdict.UNKNOWN,
                        chosen = character.id,
                        confidence = 0f,
                        reason = "裁决未执行（未配置 resolve 模型或调用失败）",
                        evidenceIds = item.evidence.map { it.id },
                    )
                    resolutions[item.speaker.localKey] = Resolution(
                        characterId = character.id,
                        resolution = RaResolution.PROVISIONAL,
                        created = true,
                        candidateIds = candidateIds,
                        createdCharacter = character,
                    )
                    linkedThisChapter.add(character.id)
                }
                return@forEach
            }
            when {
                answer.verdict == RaVerdict.SAME &&
                    answer.confidence >= 0.75f &&
                    answer.characterId != null -> {
                    val target = characters.character(answer.characterId)
                    if (target != null) {
                        val resolution = link(
                            bookKey = bookKey,
                            chapterIndex = chapterIndex,
                            speaker = item.speaker,
                            target = target,
                            candidateIds = candidateIds,
                        )
                        resolutions[item.speaker.localKey] = resolution.copy(
                            candidateIds = candidateIds,
                        )
                        linkedThisChapter.add(target.id)
                    } else {
                        val character = create(bookKey, chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
                        created.add(character)
                        resolutions[item.speaker.localKey] = Resolution(
                            characterId = character.id,
                            resolution = RaResolution.PROVISIONAL,
                            created = true,
                            candidateIds = candidateIds,
                            createdCharacter = character,
                        )
                        linkedThisChapter.add(character.id)
                    }
                }
                answer.verdict == RaVerdict.DIFFERENT -> {
                    val evidenceIds = item.evidence.map { it.id }
                    val character = create(bookKey, chapterIndex, item.speaker, RaCharacterStatus.ACTIVE)
                    created.add(character)
                    // 新建人物与每个被否定候选建立 CANNOT
                    item.candidates.forEach { candidate ->
                        characters.saveCannotLink(
                            bookKey = bookKey,
                            aCharacterId = character.id,
                            bCharacterId = candidate.character.id,
                            source = "ai",
                            evidenceIds = evidenceIds,
                        )
                    }
                    charDecision(
                        bookKey = bookKey,
                        speaker = item.speaker,
                        candidates = candidateIds,
                        verdict = RaVerdict.DIFFERENT,
                        chosen = character.id,
                        confidence = answer.confidence,
                        reason = answer.reason,
                        evidenceIds = evidenceIds,
                    )
                    resolutions[item.speaker.localKey] = Resolution(
                        characterId = character.id,
                        resolution = RaResolution.RESOLVED,
                        created = true,
                        candidateIds = candidateIds,
                        createdCharacter = character,
                    )
                    linkedThisChapter.add(character.id)
                }
                else -> {
                    val character = create(bookKey, chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
                    created.add(character)
                    charDecision(
                        bookKey = bookKey,
                        speaker = item.speaker,
                        candidates = candidateIds,
                        verdict = answer.verdict,
                        chosen = character.id,
                        confidence = answer.confidence,
                        reason = answer.reason,
                        evidenceIds = item.evidence.map { it.id },
                    )
                    resolutions[item.speaker.localKey] = Resolution(
                        characterId = character.id,
                        resolution = RaResolution.PROVISIONAL,
                        created = true,
                        candidateIds = candidateIds,
                        createdCharacter = character,
                    )
                    linkedThisChapter.add(character.id)
                }
            }
        }

        // 旁白提及：只做弱链接，不新建人物（01 §2）
        mentions.forEach { mention ->
            val target = exactTargets(
                bookKey = bookKey,
                speaker = mention.toSpeakerForLookup(),
                contradictions = emptyList(),
            ).firstOrNull()
            if (target != null) {
                characters.attachAliasesIfAbsent(
                    bookKey = bookKey,
                    characterId = target.id,
                    names = mention.surfaceNames + mention.primaryName,
                    chapterIndex = chapterIndex,
                    source = "ai",
                )
            }
        }

        return ChapterResult(resolutions, created)
    }

    /** 兼容单 speaker 入口（旧单测/调用方）。 */
    suspend fun resolve(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
    ): Resolution {
        val result = resolveChapter(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            chapterLabel = "",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = emptyList(),
        )
        return result.resolutions[speaker.localKey]
            ?: Resolution("", RaResolution.PENDING, false)
    }

    private suspend fun decideAll(
        bookKey: String,
        chapterIndex: Int,
        chapterLabel: String,
        pending: List<Pending>,
    ): Map<String, IdentityAnswer> {
        val arbiter = arbiter ?: return emptyMap()
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, IdentityAnswer>()
        pending.chunked(8).forEach { chunk ->
            val questions = chunk.mapIndexed { index, item ->
                IdentityQuestion(
                    key = "Q${index + 1}",
                    chapterLabel = chapterLabel.ifBlank { "第${chapterIndex + 1}章" },
                    speaker = item.speaker,
                    evidence = item.evidence,
                    contextText = item.contextText,
                    candidates = buildCards(item.candidates),
                )
            }
            val answers = runCatching { arbiter.decide(bookKey, questions) }.getOrNull()
            if (answers != null) {
                val byQuestion = answers.associateBy { it.key }
                questions.forEachIndexed { index, question ->
                    val answer = byQuestion[question.key] ?: return@forEachIndexed
                    out[chunk[index].speaker.localKey] = answer
                }
            }
        }
        return out
    }

    private suspend fun buildCards(candidates: List<Candidate>): List<RaCharacterCard> {
        val projector = cards ?: return emptyList()
        return candidates.mapNotNull { candidate ->
            runCatching { projector.build(candidate.character.id) }.getOrNull()
        }
    }

    private suspend fun uniqueAliasTarget(
        bookKey: String,
        names: List<String>,
        contradictions: List<String>,
    ): RaCharacter? {
        if (names.isEmpty()) return null
        val normalized = names.map(RaNameUtils::normalize).filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return null
        val hits = characters.charactersByAliases(bookKey, normalized)
            .filterNot { it.isGeneric }
            .map { it.characterId }
            .distinct()
        val contradictionIds = contradictionIds(bookKey, contradictions)
        val targets = hits.mapNotNull { characters.character(it) }
            .filter { it.status != RaCharacterStatus.MERGED }
            .filterNot { target ->
                target.id in contradictionIds || contradictions.any { matchesText(target, it) }
            }
        return targets.singleOrNull()
    }

    private suspend fun exactTargets(
        bookKey: String,
        speaker: RaLocalSpeaker,
        contradictions: List<String>,
    ): List<RaCharacter> {
        val names = speaker.surfaceNames + speaker.primaryName
        val nonGeneric = names
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isGeneric(it) }
            .map(RaNameUtils::normalize)
            .filter { it.isNotEmpty() }
            .distinct()
        if (nonGeneric.isEmpty()) return emptyList()
        val hits = characters.charactersByAliases(bookKey, nonGeneric)
            .filterNot { it.isGeneric }
            .map { it.characterId }
            .distinct()
        val contradictionIds = contradictionIds(bookKey, contradictions)
        return hits.mapNotNull { characters.character(it) }
            .filter { it.status == RaCharacterStatus.ACTIVE }
            .filterNot { target ->
                target.id in contradictionIds || contradictions.any { matchesText(target, it) }
            }
    }

    private suspend fun recallCandidates(
        bookKey: String,
        speaker: RaLocalSpeaker,
        evidence: List<RaEvidence>,
        exclude: Set<String>,
    ): List<Candidate> {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val nonGeneric = names.filterNot { isGeneric(it) }.map(RaNameUtils::normalize).filter { it.isNotEmpty() }
        val generic = names.filter { isGeneric(it) }.map(RaNameUtils::normalize).filter { it.isNotEmpty() }
        val relationTexts = evidence
            .filter { it.type == RaEvidenceType.RELATION }
            .mapNotNull {
                it.objectText.substringBefore('|').trim().takeIf(String::isNotEmpty)
            }

        val scores = LinkedHashMap<String, Float>()
        val hitRows: List<RaCharacterAlias> = runCatching {
            characters.charactersByAliases(bookKey, (nonGeneric + generic).distinct())
        }.getOrDefault(emptyList())
        hitRows.forEach { row ->
            if (row.isGeneric) {
                // 泛称只在有明确关系/组织证据时进入候选（01 §5.3 ②）
                if (relationTexts.isNotEmpty()) {
                    scores[row.characterId] = maxOf(scores[row.characterId] ?: 0f, 0.4f)
                }
            } else {
                scores[row.characterId] = maxOf(scores[row.characterId] ?: 0f, 1.0f)
            }
        }
        // PROVISIONAL 关联：称呼有交集/包含时纳入再审（最多 3 次）
        runCatching { characters.provisionalCharacters(bookKey) }.getOrDefault(emptyList()).forEach { p ->
            if (p.id in exclude) return@forEach
            val related = (p.displayName in names) || names.any { n ->
                val a = RaNameUtils.normalize(n)
                val b = RaNameUtils.normalize(p.displayName)
                a.isNotEmpty() && b.isNotEmpty() && (a == b || a.contains(b) || b.contains(a))
            }
            if (!related) return@forEach
            val count = runCatching { characters.decisionsForCharacter(p.id).size }.getOrDefault(0)
            if (count >= 3) return@forEach
            scores[p.id] = maxOf(scores[p.id] ?: 0f, 0.5f)
        }
        // 泛称 + 关系/组织对象重合：把关系文本命中的人物也加入弱候选
        if (relationTexts.isNotEmpty()) {
            val relationNormalized = relationTexts.map(RaNameUtils::normalize)
                .filter { it.isNotEmpty() }
                .distinct()
            runCatching { characters.charactersByAliases(bookKey, relationNormalized) }
                .getOrDefault(emptyList())
                .forEach { row ->
                    if (row.characterId in exclude) return@forEach
                    scores[row.characterId] = maxOf(scores[row.characterId] ?: 0f, 0.4f)
                }
        }
        return scores.entries
            .mapNotNull { entry ->
                characters.character(entry.key)?.let { Candidate(it, entry.value) }
            }
            .filter { it.character.status != RaCharacterStatus.MERGED && it.character.id !in exclude }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.character.firstChapter }
                    .thenBy { it.character.id },
            )
            .take(5)
    }

    private suspend fun link(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
        target: RaCharacter,
        candidateIds: List<String>,
    ): Resolution {
        val finalId = runCatching { characters.finalCharacterId(target.id) }.getOrDefault(target.id)
        val finalTarget = runCatching { characters.character(finalId) }.getOrNull() ?: target
        characters.attachAliasesIfAbsent(
            bookKey = bookKey,
            characterId = finalTarget.id,
            names = (speaker.surfaceNames + speaker.primaryName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            chapterIndex = chapterIndex,
            source = "ai",
        )
        return Resolution(
            characterId = finalTarget.id,
            resolution = if (finalTarget.status == RaCharacterStatus.PROVISIONAL) {
                RaResolution.PROVISIONAL
            } else {
                RaResolution.RESOLVED
            },
            created = false,
            candidateIds = candidateIds,
        )
    }

    private suspend fun create(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
        status: RaCharacterStatus,
    ): RaCharacter {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return characters.ensureCharacter(
            bookKey = bookKey,
            displayName = speaker.primaryName.ifBlank { names.firstOrNull().orEmpty() },
            roleType = speaker.roleType.ifBlank { "核心" },
            gender = speaker.gender.ifBlank { "男" },
            ageBand = speaker.ageBand.ifBlank { "男青年" },
            chapterIndex = chapterIndex,
            aliases = names,
            status = status.storageValue,
            aliasSource = "ai",
            attributeSource = "default",
        )
    }

    private suspend fun charDecision(
        bookKey: String,
        speaker: RaLocalSpeaker,
        candidates: List<String>,
        verdict: RaVerdict,
        chosen: String?,
        confidence: Float,
        reason: String,
        evidenceIds: List<String>,
    ) {
        runCatching {
            characters.saveDecision(
                bookKey = bookKey,
                localSpeaker = speaker,
                candidateIds = candidates,
                verdict = verdict.storageValue,
                chosenCharacterId = chosen,
                confidence = confidence,
                reason = reason,
                modelId = "",
                evidenceIds = evidenceIds,
            )
        }
    }

    private fun matchesText(character: RaCharacter, text: String): Boolean {
        val n = RaNameUtils.normalize(text)
        if (n.isEmpty()) return false
        return RaNameUtils.normalize(character.displayName) == n
    }

    private suspend fun contradictionIds(bookKey: String, texts: List<String>): Set<String> {
        if (texts.isEmpty()) return emptySet()
        val normalized = texts.map(RaNameUtils::normalize).filter { it.isNotEmpty() }.distinct()
        if (normalized.isEmpty()) return emptySet()
        return runCatching { characters.charactersByAliases(bookKey, normalized) }
            .getOrDefault(emptyList())
            .map { it.characterId }
            .toSet()
    }

    private suspend fun isGeneric(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length <= 1) return true
        return genericNames.isGeneric(trimmed)
    }

    private fun RaLocalMention.toSpeakerForLookup(): RaLocalSpeaker = RaLocalSpeaker(
        id = id,
        analysisId = analysisId,
        localKey = localKey,
        surfaceNames = surfaceNames,
        primaryName = primaryName,
        roleType = roleType,
        gender = gender,
        ageBand = ageBand,
        batchEntityId = batchEntityId,
        characterId = characterId,
        resolution = resolution,
    )
}
