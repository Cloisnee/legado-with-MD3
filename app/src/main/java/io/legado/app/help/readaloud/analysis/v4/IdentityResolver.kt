package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.gateway.GenericNameClassifier
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaCharacterStatus
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLinkKind
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
        // EXTRACTED peers constrain grouping but cannot create identities or aliases by themselves.
        val publish: Boolean = true,
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
        val chapters: Set<Int>,
    ) {
        val chapterIndex: Int get() = chapters.min()
        fun key() = ArbitrationKey(speaker.id, chapters, speaker.surfaceNames, evidence.map { it.id },
            contextText, candidates.mapTo(HashSet()) { it.character.id })
    }

    /** 成员、证据和候选在提交时可能已改变，只有问题仍吻合的裁决可以复用。 */
    internal data class ArbitrationKey(
        val speakerId: String,
        val chapters: Set<Int>,
        val names: List<String>,
        val evidenceIds: List<String>,
        val contextText: String,
        val candidateIds: Set<String>,
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
    ): ChapterResult {
        val answers = prepareChapter(bookKey, chapterIndex, chapterLabel, speakers, evidence, contextBySpeaker, context)
        return bookLock(bookKey).withLock {
            applyChapter(bookKey, chapterIndex, speakers, mentions, evidence, contextBySpeaker, answers)
        }
    }

    /** 先只读召回并调用模型，发布事务中用当前数据重新校验。 */
    internal suspend fun prepareChapter(
        bookKey: String,
        chapterIndex: Int,
        chapterLabel: String,
        speakers: List<RaLocalSpeaker>,
        evidence: List<RaEvidence>,
        contextBySpeaker: Map<String, String>,
        context: AiSchedulerContext?,
    ): Map<ArbitrationKey, IdentityAnswer> {
        if (arbiter == null || speakers.isEmpty()) return emptyMap()
        val pending = ArrayList<Pending>()
        applyChapter(bookKey, chapterIndex, speakers, emptyList(), evidence, contextBySpeaker, emptyMap(), collect = pending)
        return decideAll(bookKey, chapterLabel.ifBlank { "第${chapterIndex + 1}章" }, pending, context)
    }

    internal suspend fun applyChapter(
        bookKey: String,
        chapterIndex: Int,
        speakers: List<RaLocalSpeaker>,
        mentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        contextBySpeaker: Map<String, String>,
        answers: Map<ArbitrationKey, IdentityAnswer>,
    ): ChapterResult = applyChapter(bookKey, chapterIndex, speakers, mentions, evidence, contextBySpeaker,
        answers, collect = null)

    private suspend fun applyChapter(
        bookKey: String,
        chapterIndex: Int,
        speakers: List<RaLocalSpeaker>,
        mentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        contextBySpeaker: Map<String, String>,
        answers: Map<ArbitrationKey, IdentityAnswer>,
        collect: MutableList<Pending>?,
        speakerChapters: Map<String, Set<Int>> = emptyMap(),
        initialOccupancy: Map<Int, Set<String>> = emptyMap(),
        rejectedAnchors: Map<String, Set<String>> = emptyMap(),
        separateFrom: Map<String, Set<String>> = emptyMap(),
    ): ChapterResult {
        if (speakers.isEmpty()) return ChapterResult(emptyMap(), emptyList())
        val cannotPairs = cannotPairs(bookKey)
        fun isCannot(a: String, b: String): Boolean = pairOf(a, b) in cannotPairs
        val occupancy = initialOccupancy.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        fun chaptersOf(speaker: RaLocalSpeaker): Set<Int> = speakerChapters[speaker.localKey] ?: setOf(chapterIndex)
        fun occupied(speaker: RaLocalSpeaker): Set<String> =
            chaptersOf(speaker).flatMap { occupancy[it].orEmpty() }.toSet()
        val claimed = HashMap<String, String>()
        fun blocked(speaker: RaLocalSpeaker): Set<String> = rejectedAnchors[speaker.localKey].orEmpty() +
            separateFrom[speaker.localKey].orEmpty().mapNotNull(claimed::get)
        fun isAllowed(speaker: RaLocalSpeaker, targetId: String): Boolean =
            targetId !in blocked(speaker) && chaptersOf(speaker).none { targetId in occupancy[it].orEmpty() }
        fun claim(speaker: RaLocalSpeaker, targetId: String) {
            claimed[speaker.localKey] = targetId
            chaptersOf(speaker).forEach { occupancy.getOrPut(it) { HashSet() }.add(targetId) }
        }
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
            val lookup = speaker.copy(surfaceNames = names)
            val reused = if (reuseConcurrent && speaker.roleType != "路人") {
                exactTargets(bookKey, lookup, emptyList())
                    .filter { isAllowed(speaker, it.id) && it.id !in blocked }
                    .singleOrNull()
            } else null
            if (reused != null) return characters.character(characters.finalCharacterId(reused.id)) ?: reused
            return characters.ensureCharacter(
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

        // 按出场顺序处理；确定性链接优先于候选召回
        speakers.forEach { speaker ->
            val currentChapter = chaptersOf(speaker).minOrNull() ?: chapterIndex
            val verifiedMain = evidence.filter {
                it.subjectLocalId == speaker.id && it.scope == RaEvidenceScope.MAIN_TEXT && it.verified
            }
            val contradictions = verifiedMain.objectsOf(RaEvidenceType.CONTRADICTION)
            val aliasEqualNames = verifiedMain.objectsOf(RaEvidenceType.ALIAS_EQUAL)

            // ①a ALIAS_EQUAL：把当前称呼与已有人物的非泛称别名连起来
            val aliasEqualTarget = uniqueAliasTarget(bookKey, aliasEqualNames, contradictions)
            if (aliasEqualTarget != null && isAllowed(speaker, aliasEqualTarget.id)) {
                claim(speaker, aliasEqualTarget.id)
                if (collect != null) return@forEach
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
                    excluded = blocked(speaker),
                )
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = currentChapter,
                    speaker = speaker,
                    target = aliasEqualTarget,
                    candidateIds = listOf(aliasEqualTarget.id),
                )
                return@forEach
            }

            // R6：路人不参与跨章确定性快链与候选裁决。
            // 只有原文明确的 ALIAS_EQUAL（A 即 B）才允许跨章认亲；否则一律本章新建，
            // 避免两章各一个「弟子A」因同名被合并成同一人物（01 §4.2）。
            if (speaker.roleType == "路人") {
                if (collect != null) return@forEach
                val character = createCharacter(currentChapter, speaker, RaCharacterStatus.ACTIVE)
                created.add(character)
                resolutions[speaker.localKey] = Resolution(
                    characterId = character.id,
                    resolution = RaResolution.RESOLVED,
                    created = true,
                )
                claim(speaker, character.id)
                return@forEach
            }

            // ①b 非泛称称呼精确命中且唯一 ACTIVE
            val exactTargets = exactTargets(bookKey, speaker, contradictions)
            if (exactTargets.size == 1 && isAllowed(speaker, exactTargets.first().id)) {
                val target = exactTargets.first()
                claim(speaker, target.id)
                if (collect != null) return@forEach
                resolutions[speaker.localKey] = link(
                    bookKey = bookKey,
                    chapterIndex = currentChapter,
                    speaker = speaker,
                    target = target,
                    candidateIds = listOf(target.id),
                )
                return@forEach
            }

            // ② 候选召回；排除同场已链接人物、本章明确反证的人物
            val blockedByContradiction = contradictionIds(bookKey, contradictions) + blocked(speaker)
            val candidates = recallCandidates(
                bookKey = bookKey,
                speaker = speaker,
                evidence = verifiedMain,
                exclude = occupied(speaker) + blockedByContradiction,
            )
            // 首次快链与候选召回之间，另一章可能刚发布了同一人物。
            // 只复核“原来无精确命中”的窄时序，沿用全部快链/反证/同章占用条件。
            if (exactTargets.isEmpty() && candidates.isNotEmpty()) {
                val published = exactTargets(bookKey, speaker, contradictions).singleOrNull()?.takeIf { target ->
                    isAllowed(speaker, target.id) && target.id !in blockedByContradiction &&
                        (speaker.characterId == null || !isCannot(speaker.characterId, target.id))
                }
                if (published != null) {
                    claim(speaker, published.id)
                    if (collect != null) return@forEach
                    resolutions[speaker.localKey] = link(
                        bookKey = bookKey,
                        chapterIndex = currentChapter,
                        speaker = speaker,
                        target = published,
                        candidateIds = listOf(published.id),
                    )
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
                val reusable = findReusableProvisional(bookKey, speaker, candidateIds, occupied(speaker) + blocked(speaker))
                if (collect != null) {
                    reusable?.let { claim(speaker, it.id) }
                    return@forEach
                }
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
                )
                claim(speaker, character.id)
                return@forEach
            }
            when {
                candidates.isEmpty() -> {
                    if (collect != null) return@forEach
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
                    )
                    claim(speaker, character.id)
                }
                else -> {
                    pending.add(
                        Pending(
                            speaker = speaker,
                            evidence = verifiedMain,
                            candidates = candidates,
                            contextText = contextBySpeaker[speaker.localKey].orEmpty(),
                            chapters = chaptersOf(speaker),
                        ),
                    )
                }
            }
        }

        if (collect != null) {
            collect.addAll(pending)
            return ChapterResult(emptyMap(), emptyList())
        }
        // 只消费事务外已完成、且仍与当前候选和证据一致的裁决。
        pending.forEach { item ->
            val answer = answers[item.key()]
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
                )
                claim(item.speaker, character.id)
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
                        target.id in occupied(item.speaker) &&
                        answer.evidenceQuotes.isNotEmpty()
                    if (target != null && (isAllowed(item.speaker, target.id) || linkedSameWithEvidence) &&
                        target.id in candidateIds && target.id !in blocked(item.speaker) && !blockedByOwnConstraint
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
                                    it.character.id !in blocked(item.speaker) &&
                                    !isCannot(it.character.id, target.id) && nameOverlap(it.character, item.speaker)
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
                        claim(item.speaker, target.id)
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
                        )
                        claim(item.speaker, character.id)
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
                    )
                    claim(item.speaker, character.id)
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
                    claim(item.speaker, character.id)
                }
            }
        }

        // 旁白提及：只做弱链接，不新建人物（01 §2）
        mentions.forEach { mention ->
            val target = exactTargets(
                bookKey = bookKey,
                speaker = mention.toSpeakerForLookup(),
                contradictions = emptyList(),
            ).singleOrNull { it.id !in occupancy[chapterIndex].orEmpty() }
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

    suspend fun resolveBatch(
        bookKey: String,
        chapters: List<BatchChapterInput>,
        context: AiSchedulerContext? = null,
    ): Map<String, Resolution> {
        val answers = prepareBatch(bookKey, chapters, context)
        return bookLock(bookKey).withLock { applyBatch(bookKey, chapters, answers) }
    }

    internal suspend fun prepareBatch(
        bookKey: String,
        chapters: List<BatchChapterInput>,
        context: AiSchedulerContext?,
    ): Map<ArbitrationKey, IdentityAnswer> {
        if (arbiter == null || chapters.isEmpty()) return emptyMap()
        val pending = ArrayList<Pending>()
        applyBatch(bookKey, chapters, emptyMap(), collect = pending)
        val first = chapters.minBy { it.chapterIndex }
        val last = chapters.maxOf { it.chapterIndex }
        val label = if (first.chapterIndex == last) first.chapterLabel else "第${first.chapterIndex + 1}~${last + 1}章"
        return decideAll(bookKey, label, pending, context)
    }

    internal suspend fun applyBatch(
        bookKey: String,
        chapters: List<BatchChapterInput>,
        answers: Map<ArbitrationKey, IdentityAnswer>,
    ): Map<String, Resolution> = applyBatch(bookKey, chapters, answers, collect = null)

    /** 同次响应的 bkey 是聚合线索，各章的原文和已确认身份优先。 */
    private suspend fun applyBatch(
        bookKey: String,
        chapters: List<BatchChapterInput>,
        answers: Map<ArbitrationKey, IdentityAnswer>,
        collect: MutableList<Pending>?,
    ): Map<String, Resolution> {
        if (chapters.isEmpty()) return emptyMap()
        val ordered = chapters.sortedBy { it.chapterIndex }
        val evidenceBySpeaker = ordered.flatMap { it.evidence }.groupBy { it.subjectLocalId }
        val publishChapters = ordered.filter { it.publish }.mapTo(HashSet()) { it.chapterIndex }
        val finalIds = ordered.flatMap { it.speakers }.mapNotNull { it.characterId }.distinct()
            .associateWith { characters.finalCharacterId(it) }
        val cannotPairs = cannotPairs(bookKey)
        val proposedGroups = LinkedHashMap<String, MutableList<Pair<Int, RaLocalSpeaker>>>()
        ordered.forEach { chapter ->
            chapter.speakers.forEach { speaker ->
                val key = if (speaker.roleType == "路人" || speaker.batchEntityId.isNullOrBlank()) {
                    "solo:${speaker.id}"
                } else {
                    "batch:${chapter.batchId ?: speaker.analysisId}#${speaker.batchEntityId}"
                }
                proposedGroups.getOrPut(key) { ArrayList() }.add(chapter.chapterIndex to speaker)
            }
        }
        val groups = LinkedHashMap<String, List<Pair<Int, RaLocalSpeaker>>>()
        val separated = HashMap<String, MutableSet<String>>()
        val contextTargets = HashMap<String, RaCharacter>()
        proposedGroups.forEach { (key, members) ->
            if (members.size == 1) {
                groups[key] = members
                return@forEach
            }
            val verifiedByMember = members.associate { (_, speaker) ->
                speaker.id to evidenceBySpeaker[speaker.id].orEmpty().filter {
                    it.verified && it.scope == RaEvidenceScope.MAIN_TEXT
                }
            }
            val names = members.associate { (_, speaker) ->
                speaker.id to RaNameUtils.normalizeAll(speaker.surfaceNames + speaker.primaryName)
            }
            val equalNames = verifiedByMember.mapValues { (_, evidence) ->
                RaNameUtils.normalizeAll(evidence.objectsOf(RaEvidenceType.ALIAS_EQUAL))
            }
            val denials = verifiedByMember.mapValues { (_, evidence) -> evidence.objectsOf(RaEvidenceType.CONTRADICTION) }
            val deniedNames = denials.mapValues { (_, texts) -> RaNameUtils.normalizeAll(texts) }
            val deniedIds = denials.mapValues { (_, texts) -> contradictionIds(bookKey, texts) }
            val exactByMember = members.associate { (_, speaker) ->
                speaker.id to exactTargets(bookKey, speaker, denials.getValue(speaker.id))
            }
            val targets = members.associate { (_, speaker) ->
                val verified = verifiedByMember.getValue(speaker.id)
                val contradictions = denials.getValue(speaker.id)
                val target = speaker.characterId?.let(finalIds::get)?.let { characters.character(it) }
                    ?: uniqueAliasTarget(bookKey, verified.objectsOf(RaEvidenceType.ALIAS_EQUAL), contradictions)
                    ?: exactByMember.getValue(speaker.id).singleOrNull()
                speaker.id to target
            }
            fun denies(a: String, b: String): Boolean {
                val denied = deniedNames.getValue(a)
                if (names.getValue(b).any { it in denied } || equalNames.getValue(b).any { it in denied }) return true
                val target = targets[b] ?: return false
                return target.id in deniedIds.getValue(a) || denials.getValue(a).any { matchesText(target, it) }
            }
            fun hasCannotCandidate(member: String, targetId: String?): Boolean =
                targetId != null && targets[member] == null && exactByMember.getValue(member).any {
                    pairOf(it.id, targetId) in cannotPairs
                }
            var conflict = false
            for (i in members.indices) {
                val (chapter, first) = members[i]
                for ((otherChapter, second) in members.subList(i + 1, members.size)) {
                    val a = first.id
                    val b = second.id
                    val idA = targets[a]?.id
                    val idB = targets[b]?.id
                    if (chapter == otherChapter || denies(a, b) || denies(b, a) ||
                        hasCannotCandidate(a, idB) || hasCannotCandidate(b, idA) ||
                        (idA != null && idB != null && idA != idB)
                    ) {
                        separated.getOrPut(a) { HashSet() }.add(b)
                        separated.getOrPut(b) { HashSet() }.add(a)
                        conflict = true
                    }
                }
            }
            if (!conflict) {
                groups[key] = members
            } else {
                // A conflicting bkey cannot join anybody by itself. Rebuild only explicit, compatible links.
                val components = members.map { mutableListOf(it) }.toMutableList()
                for ((_, speaker) in members) {
                    for (alias in equalNames.getValue(speaker.id)) {
                        val peer = members.singleOrNull { (_, other) ->
                            other.id != speaker.id && alias in names.getValue(other.id)
                        }?.second ?: continue
                        val first = components.first { group -> group.any { it.second.id == speaker.id } }
                        val second = components.first { group -> group.any { it.second.id == peer.id } }
                        if (first !== second && first.none { (_, a) -> second.any { (_, b) -> b.id in separated[a.id].orEmpty() } }) {
                            first.addAll(second)
                            components.remove(second)
                        }
                    }
                }
                components.forEach { group ->
                    group.sortBy { it.first }
                    groups["solo:${group.first().second.id}"] = group
                }
            }
            members.forEach { (chapter, speaker) ->
                if (chapter !in publishChapters) targets[speaker.id]?.let { contextTargets[speaker.id] = it }
            }
        }
        val syntheticSpeakers = ArrayList<RaLocalSpeaker>()
        val syntheticEvidence = ArrayList<RaEvidence>()
        val contexts = HashMap<String, String>()
        val out = LinkedHashMap<String, Resolution>()
        val groupsToResolve = LinkedHashMap<String, List<Pair<Int, RaLocalSpeaker>>>()
        val byChapter = ordered.associateBy { it.chapterIndex }
        val occupied = HashMap<Int, MutableSet<String>>()
        val rejected = HashMap<String, Set<String>>()
        val separateFrom = HashMap<String, Set<String>>()
        val groupBySpeaker = groups.flatMap { (key, members) -> members.map { it.second.id to key } }.toMap()

        groups.values.flatten().forEach { (chapter, speaker) ->
            val id = speaker.characterId?.let(finalIds::get) ?: return@forEach
            out[speaker.id] = Resolution(id, speaker.resolution, false)
            occupied.getOrPut(chapter) { HashSet() }.add(id)
        }
        groups.forEach { (key, members) ->
            val pending = members.filter { it.first in publishChapters && it.second.id !in out }
            if (pending.isEmpty()) return@forEach
            val separateIds = members.flatMap { (_, speaker) -> separated[speaker.id].orEmpty() }.toSet()
            val anchorIds = members.mapNotNull { (_, speaker) ->
                out[speaker.id]?.characterId ?: contextTargets[speaker.id]?.id
            }.toSet()
            val separatedTargets = separateIds.mapNotNull { out[it]?.characterId ?: contextTargets[it]?.id }.toSet()
            val candidate = anchorIds.singleOrNull()?.takeUnless { it in separatedTargets }?.let { characters.character(it) }
            if (candidate != null && pending.none { (chapter, _) -> candidate.id in occupied[chapter].orEmpty() }) {
                pending.forEach { (chapter, speaker) ->
                    if (collect == null) out[speaker.id] = link(bookKey, chapter, speaker, candidate, listOf(candidate.id))
                    occupied.getOrPut(chapter) { HashSet() }.add(candidate.id)
                }
                return@forEach
            }
            // 拒绝的锚点不再从称呼快链重新进入。
            val rejectedIds = anchorIds + separatedTargets
            if (rejectedIds.isNotEmpty()) rejected[key] = rejectedIds
            if (separateIds.isNotEmpty()) separateFrom[key] = separateIds.mapNotNullTo(HashSet(), groupBySpeaker::get)
            groupsToResolve[key] = pending
            val first = pending.first().second
            val names = pending.flatMap { (_, sp) -> sp.surfaceNames + sp.primaryName }
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
            pending.forEach { (_, speaker) ->
                evidenceBySpeaker[speaker.id].orEmpty().forEach { e ->
                    syntheticEvidence.add(e.copy(subjectLocalId = syntheticId))
                }
            }
            val text = pending.mapNotNull { (chapterIndex, sp) ->
                byChapter[chapterIndex]?.contextBySpeaker?.get(sp.localKey)
            }.joinToString("\n")
            if (text.isNotBlank()) contexts[key] = text.take(1200)
        }
        val result = applyChapter(
            bookKey = bookKey,
            chapterIndex = ordered.first().chapterIndex,
            speakers = syntheticSpeakers,
            mentions = emptyList(),
            evidence = syntheticEvidence,
            contextBySpeaker = contexts,
            answers = answers,
            collect = collect,
            speakerChapters = groupsToResolve.mapValues { (_, members) -> members.map { it.first }.toSet() },
            initialOccupancy = occupied,
            rejectedAnchors = rejected,
            separateFrom = separateFrom,
        )
        groupsToResolve.forEach { (key, members) ->
            val resolution = result.resolutions[key] ?: return@forEach
            members.forEach { (_, speaker) -> out[speaker.id] = resolution }
        }
        return out
    }

    private suspend fun decideAll(
        bookKey: String,
        chapterLabel: String,
        pending: List<Pending>,
        context: AiSchedulerContext?,
    ): Map<ArbitrationKey, IdentityAnswer> {
        val arbiter = arbiter ?: return emptyMap()
        if (pending.isEmpty()) return emptyMap()
        val out = LinkedHashMap<ArbitrationKey, IdentityAnswer>()
        pending.chunked(8).forEach { chunk ->
            val questions = chunk.mapIndexed { index, item ->
                IdentityQuestion(
                    key = "Q${index + 1}",
                    chapterLabel = chapterLabel.ifBlank { "第${item.chapterIndex + 1}章" },
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
                    out[chunk[index].key()] = answer
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

    private suspend fun cannotPairs(bookKey: String): Set<Pair<String, String>> =
        characters.linksOfBook(bookKey).filter { it.kind == RaLinkKind.CANNOT }
            .mapTo(HashSet()) { pairOf(it.aCharacterId, it.bCharacterId) }

    private fun pairOf(a: String, b: String) = if (a <= b) a to b else b to a

    private fun List<RaEvidence>.objectsOf(type: RaEvidenceType): List<String> =
        filter { it.type == type }.mapNotNull { it.objectText.trim().takeIf(String::isNotEmpty) }

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
        excluded: Set<String>,
    ) {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map(RaNameUtils::normalize)
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return
        val provisionals = runCatching { characters.provisionalCharacters(bookKey) }
            .getOrDefault(emptyList())
        provisionals.forEach { provisional ->
            if (provisional.id == target.id || provisional.id in excluded) return@forEach
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
