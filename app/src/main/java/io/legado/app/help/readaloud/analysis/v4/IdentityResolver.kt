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
import io.legado.app.help.readaloud.analysis.AiSchedulerContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

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

    /** 批量收敛输入：一章的说话人/证据/裁决原文。 */
    data class BatchChapterInput(
        val chapterIndex: Int,
        val chapterLabel: String,
        val speakers: List<RaLocalSpeaker>,
        val evidence: List<RaEvidence>,
        val contextBySpeaker: Map<String, String> = emptyMap(),
        val batchId: String? = null,
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
        val chapterIndex: Int,
    )

    suspend fun resolveChapter(
        bookKey: String,
        chapterIndex: Int,
        chapterLabel: String,
        speakers: List<RaLocalSpeaker>,
        mentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        contextBySpeaker: Map<String, String> = emptyMap(),
        context: AiSchedulerContext? = null,
        speakerChapterIndex: Map<String, Int> = emptyMap(),
    ): ChapterResult {
        if (speakers.isEmpty()) return ChapterResult(emptyMap(), emptyList())
        val links = runCatching { characters.linksOfBook(bookKey) }.getOrDefault(emptyList())
        val cannotPairs = links.filter { it.kind == io.legado.app.domain.model.readaloud.v4.RaLinkKind.CANNOT }
            .map { if (it.aCharacterId <= it.bCharacterId) it.aCharacterId to it.bCharacterId else it.bCharacterId to it.aCharacterId }
            .toSet()
        fun isCannot(a: String, b: String): Boolean =
            cannotPairs.contains(if (a <= b) a to b else b to a)
        val linkedThisChapter = LinkedHashSet<String>()
        fun isAllowed(targetId: String): Boolean = targetId !in linkedThisChapter
        val resolutions = LinkedHashMap<String, Resolution>()
        val created = ArrayList<RaCharacter>()
        val pending = ArrayList<Pending>()

        suspend fun createCharacter(
            chapter: Int,
            speaker: RaLocalSpeaker,
            status: RaCharacterStatus,
            reuseConcurrent: Boolean = false,
            blocked: Set<String> = emptySet(),
        ): RaCharacter {
            val names = (speaker.surfaceNames + speaker.primaryName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            return bookLock(bookKey).withLock {
                val lookup = speaker.copy(surfaceNames = names)
                val reused = if (reuseConcurrent && speaker.roleType != "路人") {
                    exactTargets(bookKey, lookup, emptyList())
                        .filterNot { it.id in linkedThisChapter || it.id in blocked }
                        .singleOrNull()
                } else null
                if (reused != null) {
                    val finalId = runCatching { characters.finalCharacterId(reused.id) }
                        .getOrDefault(reused.id)
                    characters.character(finalId) ?: reused
                } else {
                    characters.ensureCharacter(
                        bookKey = bookKey,
                        displayName = speaker.primaryName.ifBlank { names.firstOrNull().orEmpty() },
                        roleType = speaker.roleType.ifBlank { "核心" },
                        gender = speaker.gender.ifBlank { "男" },
                        ageBand = speaker.ageBand.ifBlank { "男青年" },
                        chapterIndex = chapter,
                        aliases = names,
                        status = status.storageValue,
                        aliasSource = "ai",
                        attributeSource = "default",
                    )
                }
            }
        }

        // 按出场顺序处理；确定性链接优先于候选召回
        speakers.forEach { speaker ->
            val currentChapter = speakerChapterIndex[speaker.localKey] ?: chapterIndex
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
            if (aliasEqualTarget != null && isAllowed(aliasEqualTarget.id)) {
                val evidenceIds = verifiedMain.map { it.id }
                val decisionId = charDecision(
                    bookKey = bookKey,
                    speaker = speaker,
                    candidates = listOf(aliasEqualTarget.id),
                    verdict = RaVerdict.SAME,
                    chosen = aliasEqualTarget.id,
                    confidence = 1f,
                    reason = "ALIAS_EQUAL 确定性链接",
                    evidenceIds = evidenceIds,
                )
                // 新证据命中正式人物：回溯合并此前同称呼的临时人物
                mergeRelatedProvisionals(
                    bookKey = bookKey,
                    speaker = speaker,
                    target = aliasEqualTarget,
                    evidenceIds = evidenceIds,
                    decisionId = decisionId,
                    cannotPairs = cannotPairs,
                )
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = currentChapter,
                    speaker = speaker,
                    target = aliasEqualTarget,
                    candidateIds = listOf(aliasEqualTarget.id),
                )
                linkedThisChapter.add(aliasEqualTarget.id)
                return@forEach
            }

            // R6：路人不参与跨章确定性快链与候选裁决。
            // 只有原文明确的 ALIAS_EQUAL（A 即 B）才允许跨章认亲；否则一律本章新建，
            // 避免两章各一个「弟子A」因同名被合并成同一人物（01 §4.2）。
            if (speaker.roleType == "路人") {
                val character = createCharacter(currentChapter, speaker, RaCharacterStatus.ACTIVE)
                created.add(character)
                resolutions[speaker.localKey] = Resolution(
                    characterId = character.id,
                    resolution = RaResolution.RESOLVED,
                    created = true,
                    createdCharacter = character,
                )
                linkedThisChapter.add(character.id)
                return@forEach
            }

            // ①b 非泛称称呼精确命中且唯一 ACTIVE
            val exactTargets = exactTargets(bookKey, speaker, contradictions)
            if (exactTargets.size == 1 && isAllowed(exactTargets.first().id)) {
                val target = exactTargets.first()
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = currentChapter,
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
            // 首次快链与候选召回之间，另一章可能刚发布了同一人物。
            // 只复核“原来无精确命中”的窄时序，沿用全部快链/反证/同章占用条件。
            if (exactTargets.isEmpty() && candidates.isNotEmpty()) {
                val published = bookLock(bookKey).withLock {
                    exactTargets(bookKey, speaker, contradictions).singleOrNull()?.takeIf { target ->
                        isAllowed(target.id) && target.id !in blockedByContradiction &&
                            (speaker.characterId == null || !isCannot(speaker.characterId, target.id))
                    }
                }
                if (published != null) {
                    resolutions[speaker.localKey] = link(
                        bookKey = bookKey,
                        chapterIndex = currentChapter,
                        speaker = speaker,
                        target = published,
                        candidateIds = listOf(published.id),
                    )
                    linkedThisChapter.add(published.id)
                    return@forEach
                }
            }
            val sameNameCount = candidates.count {
                RaNameUtils.normalize(it.character.displayName) == RaNameUtils.normalize(speaker.primaryName)
            }
            if (sameNameCount >= 3) {
                // 同名已出现多个 ACTIVE：身份不足，不再新建 ACTIVE；
                // 仅当旧临时人物的候选集合与本章候选有交集时才复用（可追踪依据，不按名字硬并）
                val candidateIds = candidates.map { it.character.id }
                val reusable = findReusableProvisional(bookKey, speaker, candidateIds, linkedThisChapter)
                val character = reusable ?: createCharacter(currentChapter, speaker, RaCharacterStatus.PROVISIONAL)
                if (reusable == null) created.add(character)
                charDecision(
                    bookKey = bookKey,
                    speaker = speaker,
                    candidates = candidates.map { it.character.id },
                    verdict = RaVerdict.UNKNOWN,
                    chosen = character.id,
                    confidence = 0f,
                    reason = "同名多实体且无区分证据",
                    evidenceIds = verifiedMain.map { it.id },
                )
                resolutions[speaker.localKey] = Resolution(
                    characterId = character.id,
                    resolution = RaResolution.PROVISIONAL,
                    created = reusable == null,
                    candidateIds = candidateIds,
                    createdCharacter = if (reusable == null) character else null,
                )
                linkedThisChapter.add(character.id)
                return@forEach
            }
            when {
                candidates.isEmpty() -> {
                    // 同名对象被同章占用/排除，不等于“已证实是新人”：先建临时人物待定
                    val ambiguous = exactTargets.isNotEmpty()
                    val character = createCharacter(
                        currentChapter, speaker,
                        if (ambiguous) RaCharacterStatus.PROVISIONAL else RaCharacterStatus.ACTIVE,
                        reuseConcurrent = !ambiguous,
                        blocked = blockedByContradiction,
                    )
                    created.add(character)
                    if (ambiguous) {
                        charDecision(
                            bookKey = bookKey,
                            speaker = speaker,
                            candidates = exactTargets.map { it.id },
                            verdict = RaVerdict.UNKNOWN,
                            chosen = character.id,
                            confidence = 0f,
                            reason = "同名候选被同章占用，身份未定",
                            evidenceIds = verifiedMain.map { it.id },
                        )
                    }
                    resolutions[speaker.localKey] = Resolution(
                        characterId = character.id,
                        resolution = if (ambiguous) RaResolution.PROVISIONAL else RaResolution.RESOLVED,
                        created = true,
                        candidateIds = exactTargets.map { it.id },
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
                            chapterIndex = currentChapter,
                        ),
                    )
                }
            }
        }

        // ④ AI 三态裁决（每 8 个一批），失败/无队列 → PROVISIONAL
        val answers = decideAll(bookKey, chapterIndex, chapterLabel, pending, context)
        pending.forEach { item ->
            val answer = answers[item.speaker.localKey]
            val candidateIds = item.candidates.map { it.character.id }
            if (answer == null) {
                // A 策略：没有裁决能力时保持独立临时人物；不以同名强行复用
                val character = createCharacter(item.chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
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
                return@forEach
            }
            when {
                answer.verdict == RaVerdict.SAME &&
                    answer.confidence >= 0.75f &&
                    answer.characterId != null -> {
                    val target = characters.character(answer.characterId)
                    val blockedByOwnConstraint = target != null &&
                        item.speaker.characterId != null &&
                        isCannot(item.speaker.characterId, target.id)
                    val linkedSameWithEvidence = target != null &&
                        target.id in linkedThisChapter &&
                        answer.evidenceQuotes.isNotEmpty()
                    if (target != null && (isAllowed(target.id) || linkedSameWithEvidence) &&
                        !blockedByOwnConstraint
                    ) {
                        val decisionId = charDecision(
                            bookKey = bookKey,
                            speaker = item.speaker,
                            candidates = candidateIds,
                            verdict = RaVerdict.SAME,
                            chosen = target.id,
                            confidence = answer.confidence,
                            reason = answer.reason,
                            evidenceIds = item.evidence.map { it.id },
                            modelId = answer.modelId,
                        )
                        // A 策略回溯：若同一称呼已有独立临时人物，确认同人后带证据链合并
                        if (target.status == RaCharacterStatus.ACTIVE) {
                            item.candidates.firstOrNull {
                                it.character.status == RaCharacterStatus.PROVISIONAL &&
                                    nameOverlap(it.character, item.speaker)
                            }?.let { provisional ->
                                runCatching {
                                    characters.mergeCharactersAsAi(
                                        provisional.character.id,
                                        target.id,
                                        "AI 复审确认同人",
                                        item.evidence.map { it.id },
                                        decisionId,
                                    )
                                }
                            }
                        }
                        val resolution = link(
                            bookKey = bookKey,
                            chapterIndex = item.chapterIndex,
                            speaker = item.speaker,
                            target = target,
                            candidateIds = candidateIds,
                        )
                        resolutions[item.speaker.localKey] = resolution.copy(
                            candidateIds = candidateIds,
                        )
                        linkedThisChapter.add(target.id)
                    } else {
                        val character = createCharacter(item.chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
                        created.add(character)
                        charDecision(
                            bookKey = bookKey,
                            speaker = item.speaker,
                            candidates = candidateIds,
                            verdict = RaVerdict.UNKNOWN,
                            chosen = character.id,
                            confidence = answer.confidence,
                            reason = "SAME目标已被本章其他说话人占用或与本章约束冲突",
                            evidenceIds = item.evidence.map { it.id },
                            modelId = answer.modelId,
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
                answer.verdict == RaVerdict.DIFFERENT -> {
                    val evidenceIds = item.evidence.map { it.id }
                    val character = createCharacter(item.chapterIndex, item.speaker, RaCharacterStatus.ACTIVE)
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
                        modelId = answer.modelId,
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
                    // A 策略：UNKNOWN/低置信保留临时身份；同称呼组最多复用 3 次，达到上限后保持待定
                    val character = createCharacter(item.chapterIndex, item.speaker, RaCharacterStatus.PROVISIONAL)
                    created.add(character)
                    val linked = Resolution(
                        characterId = character.id,
                        resolution = RaResolution.PROVISIONAL,
                        created = true,
                        candidateIds = candidateIds,
                        createdCharacter = character,
                    )
                    charDecision(
                        bookKey = bookKey,
                        speaker = item.speaker,
                        candidates = candidateIds,
                        verdict = RaVerdict.UNKNOWN,
                        chosen = character.id,
                        confidence = answer.confidence,
                        reason = answer.reason,
                        evidenceIds = item.evidence.map { it.id },
                        modelId = answer.modelId,
                    )
                    resolutions[item.speaker.localKey] = linked
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
            ).singleOrNull { isAllowed(it.id) }
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

    /**
     * 批量收敛（03 §3.2 / 01 §5.3）：先把同一 `batchEntityId` 的跨章说话人聚合成一个实体，
     * 再走单章同一套确定性规则/候选召回/AI 三态；结果回填到每个成员的 localSpeaker id。
     */
    suspend fun resolveBatch(
        bookKey: String,
        chapters: List<BatchChapterInput>,
        context: AiSchedulerContext? = null,
    ): Map<String, Resolution> {
        if (chapters.isEmpty()) return emptyMap()
        val ordered = chapters.sortedBy { it.chapterIndex }
        val groups = LinkedHashMap<String, MutableList<Pair<Int, RaLocalSpeaker>>>()
        ordered.forEach { chapter ->
            chapter.speakers.forEach { speaker ->
                val rawKey = speaker.batchEntityId?.takeIf { it.isNotBlank() }
                val key = when {
                    // R6：路人默认不跨章聚合；只有单章内显式 ALIAS_EQUAL 才认亲。
                    speaker.roleType == "路人" -> "solo:${speaker.id}"
                    rawKey == null -> "solo:${speaker.id}"
                    rawKey.contains('#') -> rawKey
                    else -> "${chapter.batchId ?: speaker.analysisId}#$rawKey"
                }
                groups.getOrPut(key) { ArrayList() }.add(chapter.chapterIndex to speaker)
            }
        }
        val syntheticSpeakers = ArrayList<RaLocalSpeaker>()
        val syntheticEvidence = ArrayList<RaEvidence>()
        val contexts = HashMap<String, String>()
        val alreadyResolved = HashMap<String, Resolution>()
        groups.forEach { (key, members) ->
            val linkedIds = members.mapNotNull { it.second.characterId }.distinct()
            if (linkedIds.size == 1) {
                val id = characters.finalCharacterId(linkedIds.single())
                alreadyResolved[key] = Resolution(id, RaResolution.RESOLVED, false)
                return@forEach
            }
            val first = members.first().second
            val names = members.flatMap { (_, sp) -> sp.surfaceNames + sp.primaryName }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            // 用首个成员的真实 id 作为合成 id：证据可重映射，裁决记录也能随原章清理。
            val syntheticId = first.id
            syntheticSpeakers.add(
                first.copy(
                    localKey = key,
                    surfaceNames = names,
                    batchEntityId = null,
                    characterId = null,
                    resolution = RaResolution.PENDING,
                ),
            )
            val memberIds = members.map { it.second.id }.toSet()
            ordered.forEach { chapter ->
                chapter.evidence
                    .filter { it.subjectLocalId in memberIds }
                    .forEach { e -> syntheticEvidence.add(e.copy(subjectLocalId = syntheticId)) }
            }
            val text = members.mapNotNull { (chapterIndex, sp) ->
                ordered.firstOrNull { it.chapterIndex == chapterIndex }?.contextBySpeaker?.get(sp.localKey)
            }.joinToString("\n")
            if (text.isNotBlank()) contexts[key] = text.take(1200)
        }
        val chapterIndex = ordered.first().chapterIndex
        val chapterLabel = if (ordered.size == 1) {
            ordered.first().chapterLabel
        } else {
            "第${ordered.first().chapterIndex + 1}~${ordered.last().chapterIndex + 1}章"
        }
        val result = resolveChapter(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            chapterLabel = chapterLabel,
            speakers = syntheticSpeakers,
            mentions = emptyList(),
            evidence = syntheticEvidence,
            contextBySpeaker = contexts,
            context = context,
            speakerChapterIndex = groups.mapValues { it.value.first().first },
        )
        val out = LinkedHashMap<String, Resolution>()
        groups.forEach { (key, members) ->
            val resolution = alreadyResolved[key] ?: result.resolutions[key] ?: return@forEach
            members.forEach { (_, speaker) -> out[speaker.id] = resolution }
        }
        return out
    }

    private suspend fun decideAll(
        bookKey: String,
        chapterIndex: Int,
        chapterLabel: String,
        pending: List<Pending>,
        context: AiSchedulerContext?,
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
            val answers = try {
                arbiter.decide(bookKey, questions, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
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

    private suspend fun candidateMatchesRelation(
        candidateId: String,
        relationTargets: List<String>,
        relationTargetIds: Set<String>,
    ): Boolean {
        val relations = runCatching { characters.relationsOf(candidateId) }.getOrDefault(emptyList())
        return relations.any { relation ->
            (relation.targetCharacterId != null && relation.targetCharacterId in relationTargetIds) ||
                relationTargets.any { target ->
                    relation.targetText.isNotBlank() &&
                        (relation.targetText == target ||
                            relation.targetText.contains(target) ||
                            target.contains(relation.targetText))
                }
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
        val relationTargets = evidence
            .filter { it.type == RaEvidenceType.RELATION }
            .mapNotNull {
                it.objectText.substringBefore('|').trim().takeIf(String::isNotEmpty)
            }
        val relationTargetIds = if (relationTargets.isEmpty()) {
            emptySet()
        } else {
            runCatching {
                characters.charactersByAliases(
                    bookKey,
                    relationTargets.map(RaNameUtils::normalize).filter { it.isNotEmpty() }.distinct(),
                )
            }.getOrDefault(emptyList()).map { it.characterId }.toSet()
        }

        val scores = LinkedHashMap<String, Float>()
        val hitRows: List<RaCharacterAlias> = runCatching {
            characters.charactersByAliases(bookKey, (nonGeneric + generic).distinct())
        }.getOrDefault(emptyList())
        hitRows.forEach { row ->
            if (row.isGeneric) {
                // 泛称不因“同名”进入候选；只有候选已有与该关系对象重合的关系事实才作为弱候选
                if (relationTargets.isNotEmpty() &&
                    candidateMatchesRelation(row.characterId, relationTargets, relationTargetIds)
                ) {
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

    private suspend fun findReusableProvisional(
        bookKey: String,
        speaker: RaLocalSpeaker,
        candidateIds: List<String>,
        used: Set<String>,
    ): RaCharacter? {
        if (candidateIds.isEmpty()) return null
        val key = RaNameUtils.normalize(speaker.primaryName)
        if (key.isEmpty()) return null
        return runCatching { characters.provisionalCharacters(bookKey) }.getOrDefault(emptyList())
            .filter { it.id !in used && RaNameUtils.normalize(it.displayName) == key }
            .filter { provisional ->
                runCatching { characters.decisionsForCharacter(provisional.id) }
                    .getOrDefault(emptyList())
                    .any { decision -> decision.candidateIds.any { it in candidateIds } }
            }
            .minByOrNull { it.firstChapter }
    }

    private suspend fun mergeRelatedProvisionals(
        bookKey: String,
        speaker: RaLocalSpeaker,
        target: RaCharacter,
        evidenceIds: List<String>,
        decisionId: String,
        cannotPairs: Set<Pair<String, String>>,
    ) {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map(RaNameUtils::normalize)
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return
        val provisionals = runCatching { characters.provisionalCharacters(bookKey) }
            .getOrDefault(emptyList())
        provisionals.forEach { provisional ->
            if (provisional.id == target.id) return@forEach
            val pair = if (provisional.id <= target.id) {
                provisional.id to target.id
            } else {
                target.id to provisional.id
            }
            if (pair in cannotPairs) return@forEach
            val provisionalNames = (listOf(provisional.displayName) +
                runCatching { characters.aliasesOf(provisional.id) }.getOrDefault(emptyList()).map { it.surface })
                .map(RaNameUtils::normalize)
                .filter { it.isNotEmpty() }
                .toSet()
            if (provisionalNames.intersect(names).isEmpty()) return@forEach
            // 已核验的 MAIN_TEXT ALIAS_EQUAL 是确定性证据：不再要求目标曾进入旧候选集合
            runCatching {
                characters.mergeCharactersAsAi(
                    provisional.id,
                    target.id,
                    "新证据确认同人，回溯合并临时人物",
                    evidenceIds,
                    decisionId,
                )
            }
        }
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

    private val bookLocks = ConcurrentHashMap<String, Mutex>()

    private fun bookLock(bookKey: String): Mutex = bookLocks.getOrPut(bookKey) { Mutex() }

    private suspend fun charDecision(
        bookKey: String,
        speaker: RaLocalSpeaker,
        candidates: List<String>,
        verdict: RaVerdict,
        chosen: String?,
        confidence: Float,
        reason: String,
        evidenceIds: List<String>,
        modelId: String = "",
    ): String {
        return runCatching {
            characters.saveDecision(
                bookKey = bookKey,
                localSpeaker = speaker,
                candidateIds = candidates,
                verdict = verdict.storageValue,
                chosenCharacterId = chosen,
                confidence = confidence,
                reason = reason,
                modelId = modelId,
                evidenceIds = evidenceIds,
            )
        }.getOrDefault("")
    }

    private fun nameOverlap(character: RaCharacter, speaker: RaLocalSpeaker): Boolean {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map { RaNameUtils.normalize(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return false
        val own = RaNameUtils.normalize(character.displayName)
        return own.isNotEmpty() && names.any { it == own || it.contains(own) || own.contains(it) }
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
