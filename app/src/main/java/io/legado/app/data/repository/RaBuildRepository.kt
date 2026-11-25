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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class RaBuildRepository(
    private val database: AppDatabase,
    private val dao: RaBuildDao,
    private val characterDao: RaCharacterDao,
    private val directionDao: RaDirectionDao,
) : RaBuildGateway {

    override suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int): RaChapterBuild? =
        withContext(Dispatchers.IO) {
            dao.getBuild(bookKey, sourceKey, chapterIndex)?.toDomain()
        }

    override suspend fun builds(bookKey: String, sourceKey: String): List<RaChapterBuild> =
        withContext(Dispatchers.IO) {
            dao.builds(bookKey, sourceKey).map(RaChapterBuildEntity::toDomain)
        }

    override suspend fun upsertBuild(build: RaChapterBuild) = withContext(Dispatchers.IO) {
        dao.upsertBuild(build.toEntity())
    }

    override suspend fun getAnalysis(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.getAnalysis(bookKey, sourceKey, chapterIndex, contentHash, resolverVersion)?.toDomain()
    }

    override suspend fun analysisById(id: String): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.getAnalysisById(id)?.toDomain()
    }

    override suspend fun latestAnalysisForSource(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.latestAnalysisForSource(bookKey, sourceKey, chapterIndex)?.toDomain()
    }

    override suspend fun saveAnalysisBundle(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
    ) = saveBundleInternal(
        analysis, segments, localSpeakers, localMentions, evidence, build,
        emptyList(), emptyList(),
    )

    override suspend fun saveAnalysisBundleWithObservations(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
        attributes: List<RaAttributeObservation>,
        relations: List<RaRelationObservation>,
    ) = saveBundleInternal(
        analysis, segments, localSpeakers, localMentions, evidence, build,
        attributes, relations,
    )

    private suspend fun saveBundleInternal(
        analysis: RaAnalysis,
        segments: List<RaSegment>,
        localSpeakers: List<RaLocalSpeaker>,
        localMentions: List<RaLocalMention>,
        evidence: List<RaEvidence>,
        build: RaChapterBuild?,
        attributes: List<RaAttributeObservation>,
        relations: List<RaRelationObservation>,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.saveAnalysisBundle(
                analysis = analysis.toEntity(),
                segments = segments.map(RaSegment::toEntity),
                localSpeakers = localSpeakers.map(RaLocalSpeaker::toEntity),
                localMentions = localMentions.map(RaLocalMention::toEntity),
                evidence = evidence.map(RaEvidence::toEntity),
            )
            build?.let { dao.upsertBuild(it.toEntity()) }
            attributes.forEach { characterDao.insertAttribute(it.toEntity()) }
            relations.forEach { characterDao.insertRelation(it.toEntity()) }
            Unit
        }
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

    override suspend fun updateSegmentSpeaker(
        segmentId: String,
        characterId: String?,
        surfaceName: String,
    ) = withContext(Dispatchers.IO) {
        dao.updateSegmentSpeaker(segmentId, characterId, surfaceName, System.currentTimeMillis())
    }

    override suspend fun markSegmentNarrator(segmentId: String) = withContext(Dispatchers.IO) {
        dao.markSegmentNarrator(segmentId, System.currentTimeMillis())
    }

    override suspend fun saveOverride(
        bookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        textHash: String,
        characterId: String?,
        narrator: Boolean,
    ) = withContext(Dispatchers.IO) {
        dao.upsertOverride(
            RaSegmentOverrideEntity(
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                textHash = textHash,
                characterId = characterId,
                narrator = narrator,
            ),
        )
    }

    override suspend fun deleteChapters(
        bookKey: String,
        sourceKey: String,
        chapterIndexes: List<Int>,
    ) = withContext(Dispatchers.IO) {
        if (chapterIndexes.isEmpty()) return@withContext
        val analyses = dao.analysesForChapters(bookKey, chapterIndexes)
        val analysisIds = analyses.map { it.id }
        val localSpeakerIds = if (analysisIds.isEmpty()) {
            emptyList()
        } else {
            dao.localSpeakerIdsOf(analysisIds)
        }
        val deletedEvidenceIds = if (analysisIds.isEmpty()) {
            emptySet()
        } else {
            dao.evidenceByAnalysisIds(analysisIds).map { it.id }.toSet()
        }
        val links = characterDao.linksOfBook(bookKey)
        database.withTransaction {
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
            dao.deleteBuildsAllSources(bookKey, chapterIndexes)
            dao.deleteOverridesOfChapters(bookKey, chapterIndexes)
            if (localSpeakerIds.isNotEmpty()) {
                characterDao.deleteDecisionsByLocalSpeakerIds(localSpeakerIds)
            }
            // 回滚/删章：清理这些章节贡献的观察与 AI 约束（用户手动编辑保留）
            characterDao.deleteAttributesFromChapters(bookKey, chapterIndexes)
            characterDao.deleteRelationsFromChapters(bookKey, chapterIndexes)
            // 别名按“是否仍有保留章节支持”删除；firstChapter 只是首次出现，不代表唯一贡献
            val survivingSpeakers = dao.localSpeakersOutsideChapters(bookKey, chapterIndexes)
            characterDao.aliasesOfBook(bookKey)
                .filter { it.source == "ai" && it.firstChapter in chapterIndexes }
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
        // 先清理由本次合并迁入 to 的别名/方案，再按双方快照恢复，避免 from/to 同时持有同一别名
        val toAliasKeys = snapshot.toAliases.map { it.normalized }.toSet()
        characterDao.aliasesOf(toCharacterId)
            .filter { it.normalized !in toAliasKeys }
            .forEach { characterDao.deleteAlias(toCharacterId, it.normalized) }
        snapshot.toAliases.forEach {
            characterDao.upsertAliases(listOf(it.copy(characterId = toCharacterId, updatedAt = now)))
        }
        val toVoiceIds = snapshot.toVoiceProfiles.map { it.id }.toSet()
        characterDao.voiceProfilesOf(toCharacterId)
            .filter { it.id !in toVoiceIds }
            .forEach { characterDao.deleteVoiceProfile(it.id) }
        snapshot.toVoiceProfiles.forEach {
            characterDao.upsertVoiceProfile(it.copy(characterId = toCharacterId, updatedAt = now))
        }
        val fromAliasKeys = snapshot.aliases.map { it.normalized }.toSet()
        characterDao.aliasesOf(fromCharacterId)
            .filter { it.normalized !in fromAliasKeys }
            .forEach { characterDao.deleteAlias(fromCharacterId, it.normalized) }
        snapshot.aliases.forEach {
            characterDao.upsertAliases(listOf(it.copy(characterId = fromCharacterId, updatedAt = now)))
        }
        val fromVoiceIds = snapshot.voiceProfiles.map { it.id }.toSet()
        characterDao.voiceProfilesOf(fromCharacterId)
            .filter { it.id !in fromVoiceIds }
            .forEach { characterDao.deleteVoiceProfile(it.id) }
        snapshot.voiceProfiles.forEach {
            characterDao.upsertVoiceProfile(it.copy(characterId = fromCharacterId, updatedAt = now))
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
