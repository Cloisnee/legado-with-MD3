package io.legado.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.readaloud.RaBuildDao
import io.legado.app.data.dao.readaloud.RaCharacterDao
import io.legado.app.data.dao.readaloud.RaDirectionDao
import io.legado.app.data.entities.readaloud.RaBookIdentityEntity
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaBuildInput
import io.legado.app.domain.gateway.RaBuildPublication
import io.legado.app.domain.gateway.RaIdentityCommit
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.domain.model.readaloud.v4.RaSegmentOverride
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class RaBuildRepository(
    private val database: AppDatabase,
    private val dao: RaBuildDao,
    private val characterDao: RaCharacterDao,
    private val directionDao: RaDirectionDao,
) : RaBuildGateway {

    // 只需要拦住同进程内尚未提交的任务。代次不落库，也不留在导出数据中。
    private class Deletions {
        var wholeBook = 0L
        val chapters = HashMap<Int, Long>()
        val rollbackFloors = java.util.TreeMap<Int, Long>()
    }

    private val deletionLock = Any()
    private val deletions = HashMap<String, Deletions>()
    private var generation = 0L

    override fun generation(): Long = synchronized(deletionLock) { generation }

    override fun canUseGeneration(bookKey: String, chapterIndex: Int, generation: Long): Boolean = synchronized(deletionLock) {
        val since = deletions[bookKey] ?: return@synchronized true
        generation >= maxOf(since.wholeBook, since.chapters[chapterIndex] ?: 0L,
            since.rollbackFloors.floorEntry(chapterIndex)?.value ?: 0L)
    }

    private fun recordDeletion(bookKey: String, chapters: List<Int>? = null, rollbackFrom: Int? = null) {
        synchronized(deletionLock) {
            val revision = ++generation
            val since = deletions.getOrPut(bookKey) { Deletions() }
            if (chapters == null) {
                since.wholeBook = revision
                since.chapters.clear()
                since.rollbackFloors.clear()
            } else if (rollbackFrom != null) {
                since.chapters.keys.removeAll { it >= rollbackFrom }
                since.rollbackFloors.tailMap(rollbackFrom, true).clear()
                since.rollbackFloors[rollbackFrom] = revision
            } else {
                chapters.forEach { since.chapters[it] = revision }
            }
        }
    }

    private fun matchesInput(build: RaChapterBuild, input: RaBuildInput?): Boolean = input == null ||
        (canUseGeneration(build.bookKey, build.chapterIndex, input.generation) && input.matchesContent())

    override suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int): RaChapterBuild? =
        withContext(Dispatchers.IO) {
            dao.getBuild(bookKey, sourceKey, chapterIndex)?.toDomain()
        }

    override suspend fun builds(bookKey: String, sourceKey: String): List<RaChapterBuild> =
        withContext(Dispatchers.IO) {
            dao.builds(bookKey, sourceKey).map(RaChapterBuildEntity::toDomain)
        }

    override suspend fun transitionBuild(expected: RaChapterBuild?, next: RaChapterBuild, input: RaBuildInput?): Boolean =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val current = dao.getBuild(next.bookKey, next.sourceKey, next.chapterIndex)
                if (current != expected?.toEntity() || (current != null && next.updatedAt < current.updatedAt) ||
                    !matchesInput(next, input)
                ) {
                    return@withTransaction false
                }
                dao.upsertBuild(next.toEntity())
                true
            }
        }

    override suspend fun readyBatchAnalyses(bookKey: String, sourceKey: String, batchIds: Set<String>): List<RaAnalysis> =
        withContext(Dispatchers.IO) {
            if (batchIds.isEmpty()) emptyList()
            else dao.readyBatchAnalyses(bookKey, sourceKey, batchIds).map(RaChapterAnalysisEntity::toDomain)
        }

    override suspend fun getAnalysis(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaAnalysis? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val build = dao.getBuild(bookKey, sourceKey, chapterIndex)
                ?: return@withTransaction null
            if (build.contentHash != contentHash || build.state !in publishedStates) return@withTransaction null
            build.analysisId?.let { dao.getAnalysisById(it) }?.takeIf {
                it.contentHash == contentHash && it.resolverVersion == resolverVersion &&
                    (build.state != RaChapterState.FAILED.storageValue || it.status == RaAnalysisStatus.FAILED.storageValue)
            }?.toDomain()
        }
    }

    override suspend fun analysisById(id: String): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.getAnalysisById(id)?.toDomain()
    }

    override suspend fun analysisByContentHash(
        bookKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.analysisByContentHash(bookKey, chapterIndex, contentHash, resolverVersion)?.toDomain()
    }

    override suspend fun latestAnalysisForSource(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.latestAnalysisForSource(bookKey, sourceKey, chapterIndex)?.toDomain()
    }

    override suspend fun <T> commitIdentity(
        targets: List<RaIdentityCommit>,
        write: suspend (List<RaIdentityCommit>) -> T,
    ): T = withContext(Dispatchers.IO) {
        database.withTransaction {
            val currentTargets = targets.filter { target ->
                val build = target.build
                val current = dao.getBuild(build.bookKey, build.sourceKey, build.chapterIndex)
                canPublish(target, current) && matchesInput(build, target.input)
            }
            write(currentTargets)
        }
    }

    override suspend fun saveAnalysisBundle(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild,
        attributes: List<RaAttributeObservation>,
        relations: List<RaRelationObservation>,
        expectedExtraction: RaChapterBuild?,
        input: RaBuildInput?,
    ): RaBuildPublication = withContext(Dispatchers.IO) {
        database.withTransaction {
            // 删除或换正文已撤销任务资格，连历史观察也不能由这些任务写回。
            if (!matchesInput(build, input)) return@withTransaction RaBuildPublication.IGNORED
            val current = dao.getBuild(build.bookKey, build.sourceKey, build.chapterIndex)
            val publish = canPublish(RaIdentityCommit(analysis, build, expectedExtraction), current)
            // 确定性 ID 可能复用。晚到结果只在历史 ID 尚不存在时入库，保护同 ID 的新段及人物外键。
            if (!publish && dao.getAnalysisById(analysis.id) != null) return@withTransaction RaBuildPublication.IGNORED
            val publishedSegments = if (publish && analysis.status != RaAnalysisStatus.EXTRACTED) {
                val overrides = dao.overrides(analysis.bookKey, analysis.chapterIndex)
                    .associateBy { it.paragraphIndex to it.textHash }
                if (overrides.isEmpty()) segments else segments.map { segment ->
                    overrides[segment.paragraphIndex to MD5Utils.md5Encode16(segment.text)]
                        ?.let { segment.applyOverride(it) } ?: segment
                }
            } else segments
            dao.saveAnalysisBundle(
                analysis = analysis.toEntity(),
                segments = publishedSegments.map(RaSegment::toEntity),
                localSpeakers = localSpeakers.map(RaLocalSpeaker::toEntity),
                localMentions = localMentions.map(RaLocalMention::toEntity),
                evidence = evidence.map(RaEvidence::toEntity),
            )
            if (!publish) return@withTransaction RaBuildPublication.HISTORY_ONLY
            dao.upsertBuild(build.toEntity())
            attributes.forEach { characterDao.insertAttribute(it.toEntity()) }
            relations.forEach { characterDao.insertRelation(it.toEntity()) }
            RaBuildPublication.PUBLISHED
        }
    }

    private suspend fun canPublish(target: RaIdentityCommit, current: RaChapterBuildEntity?): Boolean {
        val (analysis, build, expected) = target
        if (expected != null) {
            if (current != expected.toEntity() || current.state != RaChapterState.EXTRACTED.storageValue ||
                current.analysisId != analysis.id || current.contentHash != analysis.contentHash
            ) return false
            val extracted = dao.getAnalysisById(analysis.id) ?: return false
            return extracted.status == RaAnalysisStatus.EXTRACTED.storageValue &&
                extracted.resolverVersion == analysis.resolverVersion && extracted.contentHash == analysis.contentHash
        }
        val newer = current == null || build.updatedAt > current.updatedAt ||
            (build.updatedAt == current.updatedAt && current.state !in publishedStates)
        val wouldReplaceReady = current?.state == RaChapterState.IDENTITY_READY.storageValue &&
            current.contentHash == build.contentHash && build.state != RaChapterState.IDENTITY_READY &&
            current.analysisId?.let { dao.getAnalysisById(it) }?.resolverVersion == analysis.resolverVersion
        return newer && !wouldReplaceReady
    }

    private val publishedStates = setOf(RaChapterState.EXTRACTED, RaChapterState.IDENTITY_READY, RaChapterState.FAILED)
        .mapTo(HashSet()) { it.storageValue }

    private fun RaSegment.applyOverride(override: RaSegmentOverrideEntity): RaSegment = when {
        override.narrator -> copy(
            roleType = SpeechRoleType.Narrator,
            characterId = null,
            localSpeakerId = null,
            surfaceName = "",
            seq = 0,
            source = SpeechResolutionSource.User,
        )
        override.characterId != null -> copy(
            roleType = if (roleType == SpeechRoleType.Narrator) SpeechRoleType.Character else roleType,
            characterId = override.characterId,
            source = SpeechResolutionSource.User,
        )
        else -> this
    }

    override suspend fun segments(analysisId: String): List<RaSegment> = withContext(Dispatchers.IO) {
        dao.segments(analysisId).map(RaSegmentEntity::toDomain)
    }

    override suspend fun segmentsOfChapter(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ): List<RaSegment> = withContext(Dispatchers.IO) {
        val rows = dao.segmentsOfChapter(bookKey, sourceKey, chapterIndex)
        if (rows.isNotEmpty()) {
            rows.map(RaSegmentEntity::toDomain)
        } else {
            // 兼容旧数据/刚迁移：没有 build 指针时退回当前 sourceKey 的最新分析
            dao.latestAnalysisForSource(bookKey, sourceKey, chapterIndex)
                ?.let { dao.segments(it.id).map(RaSegmentEntity::toDomain) }
                .orEmpty()
        }
    }

    override suspend fun lineCounts(bookKey: String, sourceKey: String): Map<Int, Int> =
        withContext(Dispatchers.IO) {
            dao.lineCounts(bookKey, sourceKey).associate { it.chapterIndex to it.lineCount }
        }

    override suspend fun localSpeakers(analysisId: String): List<RaLocalSpeaker> =
        withContext(Dispatchers.IO) {
            dao.localSpeakers(analysisId).map(RaLocalSpeakerEntity::toDomain)
        }

    override suspend fun localMentions(analysisId: String): List<RaLocalMention> =
        withContext(Dispatchers.IO) {
            dao.localMentions(analysisId).map(RaLocalMentionEntity::toDomain)
        }

    override suspend fun evidence(analysisId: String): List<RaEvidence> = withContext(Dispatchers.IO) {
        dao.evidence(analysisId).map(RaEvidenceEntity::toDomain)
    }

    override suspend fun evidenceOfCharacter(
        bookKey: String,
        characterId: String,
        limit: Int,
    ): List<RaEvidence> = withContext(Dispatchers.IO) {
        dao.evidenceOfCharacter(bookKey, characterId, limit).map(RaEvidenceEntity::toDomain)
    }

    override suspend fun overrides(bookKey: String, chapterIndex: Int): List<RaSegmentOverride> =
        withContext(Dispatchers.IO) {
            dao.overrides(bookKey, chapterIndex).map { entity ->
                RaSegmentOverride(
                    bookKey = entity.bookKey,
                    chapterIndex = entity.chapterIndex,
                    paragraphIndex = entity.paragraphIndex,
                    textHash = entity.textHash,
                    characterId = entity.characterId,
                    narrator = entity.narrator,
                )
            }
        }

    override suspend fun updateLocalSpeakerResolution(
        localSpeakerId: String,
        characterId: String?,
        resolution: String,
    ) = withContext(Dispatchers.IO) {
        dao.updateLocalSpeaker(localSpeakerId, characterId, resolution, System.currentTimeMillis())
    }

    override suspend fun saveOverride(
        bookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        textHash: String,
        characterId: String?,
        narrator: Boolean,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val override = RaSegmentOverrideEntity(
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                textHash = textHash,
                characterId = characterId,
                narrator = narrator,
                updatedAt = System.currentTimeMillis(),
            )
            dao.upsertOverride(override)
            val matches = dao.currentSegmentsForOverride(bookKey, chapterIndex, paragraphIndex)
                .filter { MD5Utils.md5Encode16(it.text) == textHash }
                .map { it.toDomain().applyOverride(override).copy(updatedAt = override.updatedAt).toEntity() }
            if (matches.isNotEmpty()) dao.upsertSegments(matches)
        }
    }

    override suspend fun deleteChapters(
        bookKey: String,
        sourceKey: String,
        chapterIndexes: List<Int>,
        rollbackFrom: Int?,
    ) = withContext(Dispatchers.IO) {
        if (chapterIndexes.isEmpty() && rollbackFrom == null) return@withContext
        database.withTransaction {
            val targets = if (rollbackFrom == null) chapterIndexes else {
                (chapterIndexes + dao.allBuildsOf(bookKey).map { it.chapterIndex } +
                    dao.allAnalysesOf(bookKey).map { it.chapterIndex } + dao.overridesOfBook(bookKey).map { it.chapterIndex })
                    .filter { it >= rollbackFrom }.distinct()
            }
            recordDeletion(bookKey, targets, rollbackFrom)
            if (targets.isEmpty()) return@withTransaction
            val analysisIds = dao.analysesForChapters(bookKey, targets).map { it.id }
            val localSpeakerIds = if (analysisIds.isEmpty()) emptyList() else dao.localSpeakerIdsOf(analysisIds)
            val deletedEvidenceIds = if (analysisIds.isEmpty()) emptySet() else {
                dao.evidenceByAnalysisIds(analysisIds).map { it.id }.toSet()
            }
            val links = characterDao.linksOfBook(bookKey)
            if (analysisIds.isNotEmpty()) {
                directionDao.plansOfAnalyses(analysisIds).forEach { plan ->
                    directionDao.deletePlanCompletely(plan.id)
                }
                dao.deleteSegmentsByAnalysisIds(analysisIds)
                dao.deleteLocalSpeakersByAnalysisIds(analysisIds)
                dao.deleteLocalMentionsByAnalysisIds(analysisIds)
                dao.deleteEvidenceByAnalysisIds(analysisIds)
                dao.deleteAnalysesByIds(analysisIds)
            }
            dao.deleteBuildsAllSources(bookKey, targets)
            dao.deleteOverridesOfChapters(bookKey, targets)
            if (localSpeakerIds.isNotEmpty()) {
                characterDao.deleteDecisionsByLocalSpeakerIds(localSpeakerIds)
            }
            // 回滚/删章：清理这些章节贡献的观察与 AI 约束（用户手动编辑保留）
            characterDao.deleteAttributesFromChapters(bookKey, targets)
            characterDao.deleteRelationsFromChapters(bookKey, targets)
            // 别名按“是否仍有保留章节支持”删除；firstChapter 只是首次出现，不代表唯一贡献
            val survivingSpeakers = dao.localSpeakersOutsideChapters(bookKey, targets)
            characterDao.aliasesOfBook(bookKey)
                .filter { it.source == "ai" && it.firstChapter in targets }
                .forEach { alias ->
                    val supported = survivingSpeakers.any { speaker ->
                        speaker.characterId == alias.characterId &&
                            (speaker.primaryName == alias.surface ||
                                jsonArrayToList(speaker.surfaceNames).contains(alias.surface))
                    }
                    if (!supported) {
                        characterDao.deleteAlias(alias.characterId, alias.normalized)
                    }
                }
            links.filter { link ->
                val ids = jsonArrayToList(link.evidenceIds)
                link.source == "ai" &&
                    ids.isNotEmpty() &&
                    ids.all { it in deletedEvidenceIds }
            }.forEach { link ->
                characterDao.deleteLink(link.id)
                if (link.kind == "SAME") {
                    // 回滚区证据支撑的 AI 合并：撤销 redirect 并恢复 from 的 ACTIVE 状态。
                    // 按 ai_merge 快照恢复段/章内键/别名/观察/方案；旧快照缺失时才仅恢复状态。
                    val redirect = characterDao.redirect(link.aCharacterId)
                        ?: characterDao.redirect(link.bCharacterId)
                    if (redirect != null) {
                        val reason = redirect.reason
                        if (reason.startsWith("ai_merge|")) {
                            val snapshot = runCatching {
                                Gson().fromJson(
                                    reason.removePrefix("ai_merge|"),
                                    MergeSnapshot::class.java,
                                )
                            }.getOrNull()
                            if (snapshot != null) {
                                restoreMergeSnapshot(redirect.fromCharacterId, redirect.toCharacterId, snapshot)
                            }
                        }
                        characterDao.deleteRedirect(redirect.fromCharacterId)
                        characterDao.updateStatus(
                            redirect.fromCharacterId,
                            "ACTIVE",
                            System.currentTimeMillis(),
                        )
                    }
                }
            }
        }
    }

    private suspend fun restoreMergeSnapshot(
        fromCharacterId: String,
        toCharacterId: String,
        snapshot: MergeSnapshot,
    ) {
        val now = System.currentTimeMillis()
        if (snapshot.localSpeakerIds.isNotEmpty()) {
            dao.updateLocalSpeakerCharacterByIds(snapshot.localSpeakerIds, fromCharacterId, now)
        }
        if (snapshot.segmentIds.isNotEmpty()) {
            dao.updateSegmentCharacterByIds(snapshot.segmentIds, fromCharacterId, now)
        }
        // 只逆向本次合并实际移动的别名/方案；合并后新增的别名、声线、用户编辑全部保留
        val toOriginalAliasKeys = snapshot.toAliases.map { it.normalized }.toSet()
        val movedAliasKeys = snapshot.aliases
            .map { it.normalized }
            .filter { it !in toOriginalAliasKeys }
            .toSet()
        characterDao.aliasesOf(toCharacterId)
            .filter { it.normalized in movedAliasKeys && it.source != "user" }
            .forEach { characterDao.deleteAlias(toCharacterId, it.normalized) }
        // from 侧始终恢复原有别名；与 to 同 normalized 不冲突（别名表按 characterId 区分）
        val fromAliasNow = characterDao.aliasesOf(fromCharacterId).associateBy { it.normalized }
        snapshot.aliases.forEach { original ->
            val current = fromAliasNow[original.normalized]
            if (current == null || current.source != "user") {
                characterDao.upsertAliases(
                    listOf(original.copy(characterId = fromCharacterId, updatedAt = now)),
                )
            }
        }

        // 合并时可能删除 to 的默认方案或把 from 的方案移到 to：只处理这些 id
        snapshot.voiceProfiles.forEach { profile ->
            val current = characterDao.voiceProfile(profile.id)
            // 合并本身可能把 DEFAULT 改成 CUSTOM（双方均锁）；source=user 才视为用户后续修订
            val restored = if (current != null && current.source == "user" && profile.source != "user") {
                current.copy(characterId = fromCharacterId, updatedAt = now)
            } else {
                profile.copy(characterId = fromCharacterId, updatedAt = now)
            }
            characterDao.upsertVoiceProfile(restored)
        }
        snapshot.toVoiceProfiles.forEach { profile ->
            if (characterDao.voiceProfile(profile.id) == null) {
                characterDao.upsertVoiceProfile(profile.copy(characterId = toCharacterId, updatedAt = now))
            }
        }
        snapshot.attributes.forEach {
            characterDao.updateAttributeCharacter(it.id, fromCharacterId, now)
        }
        snapshot.relations.forEach {
            characterDao.updateRelationCharacter(it.id, fromCharacterId, now)
        }
        // 目标人物被合并改写的资料字段恢复
        characterDao.character(toCharacterId)?.let { target ->
            characterDao.upsertCharacter(
                target.copy(
                    firstChapter = snapshot.toFirstChapter,
                    lastChapter = snapshot.toLastChapter,
                    appearanceCount = snapshot.toAppearanceCount,
                    userEdited = snapshot.toUserEdited,
                    identityRevision = snapshot.toIdentityRevision,
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun deleteBook(bookKey: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            recordDeletion(bookKey)
            directionDao.deleteLinesOfBook(bookKey)
            directionDao.deleteAudioOfBook(bookKey)
            directionDao.deleteScenesOfBook(bookKey)
            directionDao.deletePlansOfBook(bookKey)
            directionDao.deleteScenesOfBookEntities(bookKey)
            characterDao.deleteVoiceProfilesOfBook(bookKey)
            characterDao.deleteAliasesOfBook(bookKey)
            val characters = characterDao.allCharacters(bookKey)
            characters.forEach { character ->
                characterDao.deleteAttributes(character.id)
                characterDao.deleteRelations(character.id)
            }
            characterDao.deleteLinksOfBook(bookKey)
            characterDao.deleteRedirectsOfBook(bookKey)
            characterDao.deleteDecisionsOfBook(bookKey)
            dao.deleteLocalMentionsOfBook(bookKey)
            dao.deleteLocalSpeakersOfBook(bookKey)
            dao.deleteEvidenceOfBook(bookKey)
            dao.deleteSegmentsOfBook(bookKey)
            dao.deleteAnalysesOfBook(bookKey)
            dao.deleteOverridesOfBook(bookKey)
            characters.forEach { characterDao.deleteCharacter(it.id) }
            dao.allBuildsOf(bookKey).forEach {
                dao.deleteBuilds(bookKey, it.sourceKey, listOf(it.chapterIndex))
            }
            dao.identitiesOf(bookKey).forEach { dao.deleteIdentity(it.bookUrl) }
        }
    }
}

internal fun RaChapterBuildEntity.toDomain() = RaChapterBuild(
    bookKey = bookKey,
    sourceKey = sourceKey,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    state = RaChapterState.fromStorage(state),
    analysisId = analysisId,
    failReason = failReason,
    attempts = attempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaChapterBuild.toEntity() = RaChapterBuildEntity(
    bookKey = bookKey,
    sourceKey = sourceKey,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    state = state.storageValue,
    analysisId = analysisId,
    failReason = failReason,
    attempts = attempts,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaChapterAnalysisEntity.toDomain() = RaAnalysis(
    id = id,
    bookKey = bookKey,
    sourceKey = sourceKey,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    resolverVersion = resolverVersion,
    status = RaAnalysisStatus.fromStorage(status),
    origin = RaAnalysisOrigin.fromStorage(origin),
    batchId = batchId,
    modelId = modelId,
    observationJson = observationJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaAnalysis.toEntity() = RaChapterAnalysisEntity(
    id = id,
    bookKey = bookKey,
    sourceKey = sourceKey,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    resolverVersion = resolverVersion,
    status = status.storageValue,
    origin = origin.storageValue,
    batchId = batchId,
    modelId = modelId,
    observationJson = observationJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaSegmentEntity.toDomain() = RaSegment(
    id = id,
    analysisId = analysisId,
    bookKey = bookKey,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    chapterPosition = chapterPosition,
    text = text,
    roleType = SpeechRoleType.fromStorage(roleType),
    seq = seq,
    localSpeakerId = localSpeakerId,
    characterId = characterId,
    surfaceName = surfaceName,
    confidence = confidence,
    source = SpeechResolutionSource.fromStorage(source),
    voiceEffect = voiceEffect,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaSegment.toEntity() = RaSegmentEntity(
    id = id,
    analysisId = analysisId,
    bookKey = bookKey,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    chapterPosition = chapterPosition,
    text = text,
    roleType = roleType.storageValue,
    seq = seq,
    localSpeakerId = localSpeakerId,
    characterId = characterId,
    surfaceName = surfaceName,
    confidence = confidence,
    source = source.storageValue,
    voiceEffect = voiceEffect,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaLocalSpeakerEntity.toDomain() = RaLocalSpeaker(
    id = id,
    analysisId = analysisId,
    localKey = localKey,
    surfaceNames = jsonArrayToList(surfaceNames),
    primaryName = primaryName,
    roleType = roleType,
    gender = gender,
    ageBand = ageBand,
    batchEntityId = batchEntityId,
    characterId = characterId,
    resolution = RaResolution.fromStorage(resolution),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaLocalSpeaker.toEntity() = RaLocalSpeakerEntity(
    id = id,
    analysisId = analysisId,
    localKey = localKey,
    surfaceNames = listToJsonArray(surfaceNames),
    primaryName = primaryName,
    roleType = roleType,
    gender = gender,
    ageBand = ageBand,
    batchEntityId = batchEntityId,
    characterId = characterId,
    resolution = resolution.storageValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaLocalMentionEntity.toDomain() = RaLocalMention(
    id = id,
    analysisId = analysisId,
    localKey = localKey,
    surfaceNames = jsonArrayToList(surfaceNames),
    primaryName = primaryName,
    roleType = roleType,
    gender = gender,
    ageBand = ageBand,
    batchEntityId = batchEntityId,
    characterId = characterId,
    resolution = RaResolution.fromStorage(resolution),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaLocalMention.toEntity() = RaLocalMentionEntity(
    id = id,
    analysisId = analysisId,
    localKey = localKey,
    surfaceNames = listToJsonArray(surfaceNames),
    primaryName = primaryName,
    roleType = roleType,
    gender = gender,
    ageBand = ageBand,
    batchEntityId = batchEntityId,
    characterId = characterId,
    resolution = resolution.storageValue,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaEvidenceEntity.toDomain() = RaEvidence(
    id = id,
    bookKey = bookKey,
    analysisId = analysisId,
    sourceChapter = sourceChapter,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    excerpt = excerpt,
    type = RaEvidenceType.fromStorage(type),
    subjectLocalId = subjectLocalId,
    objectText = objectText,
    scope = RaEvidenceScope.fromStorage(scope),
    verified = verified,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaEvidence.toEntity() = RaEvidenceEntity(
    id = id,
    bookKey = bookKey,
    analysisId = analysisId,
    sourceChapter = sourceChapter,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    excerpt = excerpt,
    type = type.storageValue,
    subjectLocalId = subjectLocalId,
    objectText = objectText,
    scope = scope.storageValue,
    verified = verified,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun jsonArrayToList(raw: String): List<String> = runCatching {
    val arr = JSONArray(raw)
    buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
}.getOrDefault(emptyList())

internal fun listToJsonArray(values: Collection<String>): String = runCatching {
    JSONArray().apply { values.forEach { put(it) } }.toString()
}.getOrDefault("[]")
