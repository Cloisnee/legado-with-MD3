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
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaBuildInput
import io.legado.app.domain.gateway.RaBuildPublication
import io.legado.app.domain.gateway.RaIdentityCommit
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.BATCH_BINDING_CONFIRMATION
import io.legado.app.domain.model.readaloud.v4.BATCH_BINDING_REVISION
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.domain.model.readaloud.v4.RaSegmentOverride
import io.legado.app.domain.model.readaloud.v4.RaTextAliasEdge
import io.legado.app.domain.model.readaloud.v4.quotedSubjects
import io.legado.app.domain.model.readaloud.v4.TEXT_ALIAS_PROOF
import io.legado.app.help.book.BookHelp
import io.legado.app.help.readaloud.audio.AudioPlanStore
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

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
    private val deletedUrls = HashMap<String, Long>()
    private var generation = 0L

    override fun generation(): Long = synchronized(deletionLock) { generation }

    override fun canUseGeneration(bookKey: String, chapterIndex: Int, generation: Long): Boolean = synchronized(deletionLock) {
        val since = deletions[bookKey] ?: return@synchronized true
        generation >= maxOf(since.wholeBook, since.chapters[chapterIndex] ?: 0L,
            since.rollbackFloors.floorEntry(chapterIndex)?.value ?: 0L)
    }

    override fun canResolveBookUrl(bookUrl: String, generation: Long): Boolean = synchronized(deletionLock) {
        generation >= (deletedUrls[bookUrl] ?: 0L)
    }

    private fun recordDeletion(
        bookKey: String,
        chapters: List<Int>? = null,
        rollbackFrom: Int? = null,
        bookUrls: Set<String> = emptySet(),
    ) {
        synchronized(deletionLock) {
            val revision = ++generation
            val since = deletions.getOrPut(bookKey) { Deletions() }
            if (chapters == null) {
                since.wholeBook = revision
                bookUrls.forEach { deletedUrls[it] = revision }
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

    override suspend fun currentBatchAnalyses(
        bookKey: String,
        sourceKey: String,
        batchIds: Set<String>,
        excludedChapters: Set<Int>,
        generation: Long,
    ): List<RaAnalysis> = withContext(Dispatchers.IO) {
        if (batchIds.isEmpty()) return@withContext emptyList()
        val current = dao.currentBatchAnalyses(bookKey, sourceKey, batchIds).filter {
            it.chapterIndex !in excludedChapters && canUseGeneration(bookKey, it.chapterIndex, generation)
        }
        matchingBatchBodies(sourceKey, current).map(RaChapterAnalysisEntity::toDomain)
    }

    private suspend fun matchingBatchBodies(
        sourceKey: String,
        current: List<RaChapterAnalysisEntity>,
    ): List<RaChapterAnalysisEntity> {
        val snapshots = current.associateWith { analysis ->
            runCatching { JSONObject(analysis.observationJson) }.getOrNull()
        }
        val byUrl = current.groupBy { snapshots[it]?.optString("bodyUrl").orEmpty() }
        val urls = byUrl.keys.filter { it.isNotBlank() }.toSet()
        val books = if (urls.isEmpty()) emptyMap() else database.bookDao.getBooksByUrls(urls).associateBy { it.bookUrl }
        return byUrl.flatMap { (url, chapters) ->
            if (url.isEmpty()) return@flatMap chapters // Programmatic inputs without a reader cache.
            val book = books[url]?.takeIf { sourceKeyOf(it) == sourceKey } ?: return@flatMap emptyList()
            val metadata = database.bookChapterDao.getChaptersByIndexes(url, chapters.map { it.chapterIndex })
                .associateBy { it.index }
            chapters.filter { analysis ->
                val chapter = metadata[analysis.chapterIndex] ?: return@filter false
                val hash = snapshots[analysis]?.optString("bodyHash") ?: return@filter false
                try {
                    BookHelp.getContent(book, chapter, cacheEpub = false)?.let(SpeechIdentity::cachedBodyHash) == hash
                } catch (_: IOException) {
                    false
                }
            }
        }
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
                    (build.state != RaChapterState.FAILED.storageValue || it.status == RaAnalysisStatus.FAILED.storageValue) &&
                    hasCurrentOrigin(it)
            }?.toDomain()
        }
    }

    override suspend fun analysisById(id: String): RaAnalysis? = withContext(Dispatchers.IO) {
        dao.getAnalysisById(id)?.toDomain()
    }

    override suspend fun reuseAnalysis(
        build: RaChapterBuild,
        resolverVersion: String,
        input: RaBuildInput,
    ): String? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val (bookKey, sourceKey, chapter, hash) = build
            if (!matchesInput(build, input)) return@withTransaction null
            val candidates = dao.currentAnalysesByContentHash(bookKey, chapter, hash, resolverVersion)
            if (candidates.any { it.sourceKey == sourceKey && hasCurrentOrigin(it) }) return@withTransaction null
            val donor = candidates.firstNotNullOfOrNull {
                if (it.sourceKey == sourceKey) null else reuseDonor(it, historical = false)
            }
                ?: dao.historicalAnalysesByContentHash(bookKey, sourceKey, chapter, hash, resolverVersion)
                    .firstNotNullOfOrNull { reuseDonor(it, historical = true) }
                ?: return@withTransaction null
            val (old, oldSegments, speakers) = donor
            if (!canPublish(RaIdentityCommit(old.toDomain(), build), dao.getBuild(bookKey, sourceKey, chapter))) {
                return@withTransaction null
            }
            // A new publication must not pick up a READY plan from a previous copy of this text.
            val id = SpeechIdentity.analysisId(bookKey, sourceKey, chapter, hash, resolverVersion) + ":reuse_${UUID.randomUUID()}"
            val observation = observationOf(old).apply {
                // Copies refer to the exact original analysis version, even through multiple source switches.
                if (!has("reusedFromAnalysisId")) {
                    put("reusedFromAnalysisId", old.id)
                    put("reusedFromCreatedAt", old.createdAt)
                }
                remove("bodyUrl")
                remove("bodyHash")
            }
            val now = System.currentTimeMillis()
            val mentions = dao.localMentions(old.id)
            val localIds = speakers.associate { it.id to "$id#${it.localKey}" } +
                mentions.associate { it.id to "$id#${it.localKey}" }
            val publication = saveAnalysisBundle(
                analysis = old.toDomain().copy(id = id, sourceKey = sourceKey, origin = RaAnalysisOrigin.CROSS_SOURCE_REUSE,
                    batchId = null, observationJson = observation.toString(), createdAt = now, updatedAt = now),
                segments = oldSegments.map { segment ->
                    segment.toDomain().copy(id = SpeechIdentity.segmentId(id, segment.paragraphIndex, segment.start, segment.end),
                        analysisId = id, localSpeakerId = segment.localSpeakerId?.let(localIds::get), createdAt = now, updatedAt = now)
                },
                localSpeakers = speakers.map { it.toDomain().copy(id = localIds.getValue(it.id), analysisId = id) },
                localMentions = mentions.map { it.toDomain().copy(id = localIds.getValue(it.id), analysisId = id) },
                evidence = dao.evidence(old.id).map { proof ->
                    val subject = localIds[proof.subjectLocalId] ?: proof.subjectLocalId
                    proof.toDomain().copy(id = SpeechIdentity.evidenceId(id, proof.sourceChapter, proof.paragraphIndex,
                        proof.start, proof.type, subject, proof.objectText, proof.excerpt),
                        analysisId = id, subjectLocalId = subject, createdAt = now, updatedAt = now)
                },
                build = build.copy(analysisId = id),
                input = input,
            )
            old.sourceKey.takeIf { publication == RaBuildPublication.PUBLISHED }
        }
    }

    private data class ReuseDonor(
        val analysis: RaChapterAnalysisEntity,
        val segments: List<RaSegmentEntity>,
        val speakers: List<RaLocalSpeakerEntity>,
    )

    private suspend fun hasCurrentOrigin(analysis: RaChapterAnalysisEntity): Boolean {
        if (analysis.origin != RaAnalysisOrigin.CROSS_SOURCE_REUSE.storageValue) return true
        val lineage = observationOf(analysis)
        val original = dao.getAnalysisById(lineage.optString("reusedFromAnalysisId")) ?: return false
        return original.createdAt == lineage.optLong("reusedFromCreatedAt") &&
            dao.latestOriginalAnalysis(analysis.bookKey, original.sourceKey, analysis.chapterIndex,
                analysis.contentHash, analysis.resolverVersion)?.id == original.id
    }

    private suspend fun reuseDonor(analysis: RaChapterAnalysisEntity, historical: Boolean): ReuseDonor? {
        if (!hasCurrentOrigin(analysis)) return null
        val segments = dao.segments(analysis.id)
        if (segments.isEmpty()) return null
        val speakers = dao.localSpeakers(analysis.id)
        if (historical) {
            // Historical versions did not receive later corrections or user overrides. Only republish
            // independently resolved speakers whose primary name still identifies them unambiguously.
            val speakersById = speakers.associateBy { it.id }
            if (segments.any { segment ->
                    segment.source == SpeechResolutionSource.User.storageValue ||
                        (segment.roleType != SpeechRoleType.Narrator.storageValue &&
                            (segment.seq <= 0 || segment.characterId == null ||
                                speakersById[segment.localSpeakerId]?.characterId != segment.characterId))
                }
            ) return null
            for (speaker in speakers) {
                val character = speaker.characterId?.let { characterDao.character(it) } ?: return null
                if (speaker.resolution != RaResolution.RESOLVED.storageValue || character.bookKey != analysis.bookKey ||
                    character.status != "ACTIVE"
                ) return null
                val matches = characterDao.aliasesByNormalized(analysis.bookKey, listOf(RaNameUtils.normalize(speaker.primaryName)))
                    .filterNot { it.isGeneric || it.source == RaCharacterAlias.BATCH_LINK_SOURCE }
                    .filter { candidate ->
                        characterDao.character(candidate.characterId)?.status == "ACTIVE"
                    }
                if (matches.map { it.characterId }.distinct().singleOrNull() != character.id ||
                    matches.none { it.source != RaCharacterAlias.BATCH_PROOF_SOURCE }
                ) return null
            }
        }
        return ReuseDonor(analysis, segments, speakers)
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

    override suspend fun commitDirection(
        analysis: RaAnalysis,
        input: RaBuildInput,
        write: suspend () -> Unit,
    ): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        database.withTransaction {
            val current = dao.getBuild(analysis.bookKey, analysis.sourceKey, analysis.chapterIndex)
                ?: return@withTransaction false
            val state = when (analysis.status) {
                RaAnalysisStatus.RESOLVED -> RaChapterState.IDENTITY_READY
                RaAnalysisStatus.FAILED -> RaChapterState.FAILED
                RaAnalysisStatus.EXTRACTED -> return@withTransaction false
            }
            val saved = dao.getAnalysisById(analysis.id) ?: return@withTransaction false
            if (current.analysisId != analysis.id || current.contentHash != analysis.contentHash ||
                current.state != state.storageValue || saved != analysis.toEntity() ||
                !matchesInput(current.toDomain(), input) || !hasCurrentOrigin(saved)
            ) return@withTransaction false
            write()
            true
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

    override suspend fun reviseBatchIdentity(
        analysis: RaAnalysis,
        generation: Long,
        speakers: List<RaLocalSpeaker>,
        attributes: List<RaAttributeObservation>,
        relations: List<RaRelationObservation>,
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val current = dao.getBuild(analysis.bookKey, analysis.sourceKey, analysis.chapterIndex)
            val saved = analysis.toEntity()
            check(analysis.batchId != null && analysis.status == RaAnalysisStatus.RESOLVED &&
                current?.analysisId == analysis.id && current.contentHash == analysis.contentHash &&
                current.state == RaChapterState.IDENTITY_READY.storageValue && dao.getAnalysisById(analysis.id) == saved &&
                canUseGeneration(analysis.bookKey, analysis.chapterIndex, generation) &&
                matchingBatchBodies(analysis.sourceKey, listOf(saved)).isNotEmpty())

            val stored = dao.localSpeakers(analysis.id).associateBy { it.id }
            val revised = speakers.associateBy { it.id }
            val previous = speakers.map { replacement ->
                val old = stored.getValue(replacement.id)
                check(old.resolution == RaResolution.BATCH_LINKED.storageValue && old.characterId != null &&
                    replacement.analysisId == analysis.id && replacement.characterId != null &&
                    (old.characterId != replacement.characterId || old.resolution != replacement.resolution.storageValue))
                old
            }
            val changes = previous.associate { old -> old.localKey to (old to revised.getValue(old.id).toEntity()) }
            reviseAutomaticBindings(saved, changes)
            for (copy in reuseCopies(saved)) reviseAutomaticBindings(copy, changes)
            attributes.forEach { characterDao.insertAttribute(it.toEntity()) }
            relations.forEach { characterDao.insertRelation(it.toEntity()) }

            val movedByCharacter = previous.filter { revised.getValue(it.id).characterId != it.characterId }
                .groupBy { requireNotNull(it.characterId) }
            for ((characterId, moved) in movedByCharacter) {
                val oldNames = RaNameUtils.normalizeAll(moved.flatMap { jsonArrayToList(it.surfaceNames) + it.primaryName })
                val liveNames = RaNameUtils.normalizeAll(dao.currentSpeakersOfCharacter(characterId).flatMap {
                    jsonArrayToList(it.surfaceNames) + it.primaryName
                })
                characterDao.aliasesOf(characterId).filter {
                    it.source == RaCharacterAlias.BATCH_LINK_SOURCE && it.normalized in oldNames && it.normalized !in liveNames
                }.forEach { characterDao.deleteAlias(characterId, it.normalized) }
            }
        }
    }

    private fun observationOf(analysis: RaChapterAnalysisEntity): JSONObject =
        runCatching { JSONObject(analysis.observationJson) }.getOrElse { JSONObject() }

    private suspend fun reuseCopies(origin: RaChapterAnalysisEntity) =
        dao.currentAnalysesByContentHash(origin.bookKey, origin.chapterIndex, origin.contentHash, origin.resolverVersion)
            .filter {
                val observation = observationOf(it)
                observation.optString("reusedFromAnalysisId") == origin.id &&
                    observation.optLong("reusedFromCreatedAt", -1) == origin.createdAt
            }

    private suspend fun reviseAutomaticBindings(
        analysis: RaChapterAnalysisEntity,
        changes: Map<String, Pair<RaLocalSpeakerEntity, RaLocalSpeakerEntity>>,
    ) {
        val now = System.currentTimeMillis()
        val updates = dao.localSpeakers(analysis.id).mapNotNull { speaker ->
            val (old, next) = changes[speaker.localKey] ?: return@mapNotNull null
            if (speaker.characterId != old.characterId || speaker.resolution != old.resolution) return@mapNotNull null
            speaker to speaker.copy(characterId = next.characterId, resolution = next.resolution, updatedAt = now)
        }
        if (updates.isEmpty()) return
        dao.upsertLocalSpeakers(updates.map { it.second })
        val moved = updates.filter { (old, next) -> old.characterId != next.characterId }.associateBy { it.first.id }
        if (moved.isEmpty()) return
        val segments = dao.segments(analysis.id).mapNotNull { segment ->
            val (old, next) = moved[segment.localSpeakerId] ?: return@mapNotNull null
            if (segment.source == SpeechResolutionSource.User.storageValue || segment.characterId != old.characterId) return@mapNotNull null
            segment.copy(characterId = next.characterId, updatedAt = now)
        }
        if (segments.isNotEmpty()) dao.upsertSegments(segments)
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
        bookName: String,
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
            val urls = dao.identitiesOf(bookKey).map { it.bookUrl }.toSet()
            if (bookName.isNotBlank()) AudioPlanStore.delete(bookName, urls, targets.toSet(), rollbackFrom)
            val localSpeakerIds = if (analysisIds.isEmpty()) emptySet() else dao.localSpeakerIdsOf(analysisIds).toSet()
            val deletedEvidenceIds = if (analysisIds.isEmpty()) emptySet() else dao.evidenceByAnalysisIds(analysisIds)
                .mapTo(HashSet()) { it.id }
            // Read names while the owners and proof excerpts still exist, even when both will be deleted.
            val lostProofs = if (analysisIds.isEmpty()) emptyList()
                else pruneDecisionEvidence(bookKey, localSpeakerIds, deletedEvidenceIds)
            val links = characterDao.linksOfBook(bookKey)
            directionDao.plansOfChapters(bookKey, targets).forEach { directionDao.deletePlanCompletely(it.id) }
            if (analysisIds.isNotEmpty()) {
                dao.deleteSegmentsByAnalysisIds(analysisIds)
                dao.deleteLocalSpeakersByAnalysisIds(analysisIds)
                dao.deleteLocalMentionsByAnalysisIds(analysisIds)
                dao.deleteEvidenceByAnalysisIds(analysisIds)
                dao.deleteAnalysesByIds(analysisIds)
            }
            dao.deleteBuildsAllSources(bookKey, targets)
            dao.deleteOverridesOfChapters(bookKey, targets)
            // 回滚/删章：清理这些章节贡献的观察与 AI 约束（用户手动编辑保留）
            characterDao.deleteAttributesFromChapters(bookKey, targets)
            characterDao.deleteRelationsFromChapters(bookKey, targets)
            // 别名按“是否仍有保留章节支持”删除；firstChapter 只是首次出现，不代表唯一贡献
            val survivingSpeakers = dao.localSpeakersOutsideChapters(bookKey, targets)
            characterDao.aliasesOfBook(bookKey)
                .filter {
                    it.source in ordinaryAutomaticAliases && it.firstChapter in targets
                }
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
            revokeUnsupportedProofs(bookKey, lostProofs)
        }
    }

    private val ordinaryAutomaticAliases = setOf("ai", RaCharacterAlias.BATCH_LINK_SOURCE)

    private data class PublishedProof(
        val decision: RaIdentityDecisionEntity,
        val speaker: RaLocalSpeakerEntity,
        val aliasNames: Set<String>,
        val evidenceIds: List<String>,
        val edge: RaTextAliasEdge? = null,
        val legacyChapter: Int? = null,
    ) {
        val characterId: String get() = requireNotNull(decision.chosenCharacterId)
        val binding: Boolean get() = decision.isBindingProof()
    }

    private suspend fun readProof(
        decision: RaIdentityDecisionEntity,
        evidence: Map<String, RaEvidenceEntity>,
    ): PublishedProof? {
        if (!decision.isProof()) return null
        val actor = decision.chosenCharacterId ?: return null
        val speaker = dao.localSpeaker(decision.localSpeakerId) ?: return null
        if (speaker.characterId != actor) return null
        val ids = jsonArrayToList(decision.evidenceIds)
        if (ids.isEmpty()) return null
        val ownNames = jsonArrayToList(speaker.surfaceNames) + speaker.primaryName
        if (decision.isBindingProof()) {
            return PublishedProof(decision, speaker, RaNameUtils.normalizeAll(ownNames).toSet(), ids)
        }
        val edge = RaTextAliasEdge.fromReason(decision.reason)
        if (edge != null) {
            val excerpt = ids.singleOrNull()?.let(evidence::get)?.toDomain() ?: return null
            if (!excerpt.isVerifiedEqualityFor(speaker) || RaNameUtils.normalize(excerpt.objectText) != edge.other ||
                edge.subject !in excerpt.quotedSubjects(listOf(edge.subject))
            ) return null
            return PublishedProof(decision, speaker, setOf(edge.subject, edge.other), ids, edge)
        }
        val legacy = ids.mapNotNull(evidence::get).map { it.toDomain() }.filter { it.isVerifiedEqualityFor(speaker) }
        if (decision.reason == LEGACY_ALIAS_LINK) {
            // 旧版从客体快链时，把新主体写成普通 ai 别名。只有证据所在章新增的称呼受影响。
            val introduced = legacy.flatMap { it.quotedSubjects(ownNames) }.toSet()
            val chapter = legacy.firstOrNull()?.sourceChapter?.minus(1) ?: return null
            val excerpt = legacy.singleOrNull()
            val subject = introduced.singleOrNull()
            val edge = if (subject != null && excerpt != null && ids.size == 1) {
                RaTextAliasEdge(subject, RaNameUtils.normalize(excerpt.objectText))
            } else null
            return PublishedProof(decision, speaker, introduced, ids, edge, legacyChapter = chapter)
        }
        val excerpt = legacy.singleOrNull() ?: return null
        val subject = excerpt.quotedSubjects(listOf(speaker.primaryName)).singleOrNull() ?: return null
        val other = RaNameUtils.normalize(excerpt.objectText)
        return PublishedProof(decision, speaker, setOf(subject, other), ids, RaTextAliasEdge(subject, other))
    }

    private fun RaEvidence.isVerifiedEqualityFor(speaker: RaLocalSpeakerEntity) =
        verified && scope == RaEvidenceScope.MAIN_TEXT && type == RaEvidenceType.ALIAS_EQUAL && subjectLocalId == speaker.id

    private suspend fun pruneDecisionEvidence(
        bookKey: String,
        deletedSpeakers: Set<String>,
        deletedEvidence: Set<String>,
    ): List<PublishedProof> {
        val lost = ArrayList<PublishedProof>()
        for (decision in characterDao.decisionsOfBook(bookKey)) {
            val ids = jsonArrayToList(decision.evidenceIds)
            val retained = ids.filterNot { it in deletedEvidence }
            val removeOwner = decision.localSpeakerId in deletedSpeakers
            if (!removeOwner && retained.size == ids.size) continue
            if (decision.isProof()) {
                val excerpts = if (decision.isBindingProof()) emptyMap() else dao.evidenceByIds(ids).associateBy { it.id }
                readProof(decision, excerpts)?.let(lost::add)
            }
            // Never shorten a proof path. A separate complete path is a separate decision.
            if (removeOwner || retained.isEmpty() || decision.isProof()) {
                characterDao.deleteDecision(decision.id)
            } else {
                characterDao.updateDecisionEvidenceIds(decision.id, listToJsonArray(retained))
            }
        }
        return lost
    }

    private suspend fun revokeUnsupportedProofs(bookKey: String, lost: List<PublishedProof>) {
        if (lost.isEmpty()) return
        val lostByActor = lost.groupBy { it.characterId }
        val paths = characterDao.decisionsOfBook(bookKey)
            .filter { it.chosenCharacterId in lostByActor && it.isProof() }
            .associateWith { jsonArrayToList(it.evidenceIds) }
        val evidence = paths.values.flatten().distinct().chunked(500)
            .flatMap { dao.evidenceByIds(it) }.associateBy { it.id }
        val remaining = paths.mapNotNull { (decision, ids) ->
            if (ids.any { it !in evidence }) null else readProof(decision, evidence)
        }.groupBy { it.characterId }
        val now = System.currentTimeMillis()
        for ((actor, lostPaths) in lostByActor) {
            val aliases = characterDao.aliasesOf(actor)
            val candidates = remaining[actor].orEmpty()
            val legacyNames = (lostPaths + candidates).filter { it.legacyChapter != null }
                .flatMapTo(HashSet()) { proof -> proof.aliasNames.map { it to proof.legacyChapter } }
            val roots = aliases.filterNot { alias ->
                alias.isGeneric || alias.source in dependentAliases ||
                    (alias.source == "ai" && (alias.normalized to alias.firstChapter) in legacyNames)
            }.mapTo(HashSet()) { it.normalized }
            val reachable = reachableAliases(roots, candidates)
            val complete = candidates.filter { proof -> proof.binding || proof.edge?.subject in reachable }
            val liveNames = complete.flatMapTo(HashSet()) { it.aliasNames }
            val liveBindings = complete.filter { it.binding }.mapTo(HashSet()) { it.speaker.id }
            for (path in lostPaths.filter { it.binding }.distinctBy { it.speaker.id }) {
                val speaker = dao.localSpeaker(path.speaker.id) ?: continue
                if (speaker.characterId != actor || speaker.id in liveBindings ||
                    speaker.resolution != RaResolution.RESOLVED.storageValue
                ) continue
                val primary = RaNameUtils.normalize(speaker.primaryName)
                if (primary in reachable) {
                    val chain = generateSequence(reachable[primary]) { reachable[it.first] }.map { it.second }.toList()
                    if (chain.isNotEmpty()) characterDao.insertDecision(path.decision.copy(
                        evidenceIds = listToJsonArray(chain.asReversed()), createdAt = now,
                    ))
                    continue
                }
                val analysis = dao.getAnalysisById(speaker.analysisId) ?: continue
                val weak = speaker.copy(resolution = RaResolution.BATCH_LINKED.storageValue)
                dao.updateLocalSpeaker(speaker.id, actor, weak.resolution, now)
                for (copy in reuseCopies(analysis)) reviseAutomaticBindings(copy, mapOf(speaker.localKey to (speaker to weak)))
            }
            val affectedNames = (lostPaths + candidates.filter { it.edge != null }).flatMapTo(HashSet()) { it.aliasNames }
            val downgraded = aliases.filter { alias ->
                val dependent = alias.source == RaCharacterAlias.BATCH_PROOF_SOURCE ||
                    (alias.source == "ai" && (alias.normalized to alias.firstChapter) in legacyNames)
                dependent && alias.normalized in affectedNames && alias.normalized !in liveNames && alias.normalized !in reachable
            }.map { it.copy(source = RaCharacterAlias.BATCH_LINK_SOURCE, updatedAt = now) }
            if (downgraded.isNotEmpty()) characterDao.upsertAliases(downgraded)
        }
    }

    /** One search per affected character, over only the accepted and surviving evidence edges. */
    private fun reachableAliases(roots: Set<String>, proofs: List<PublishedProof>): Map<String, Pair<String, String>?> {
        val edges = HashMap<String, MutableList<Pair<String, String>>>()
        for (proof in proofs) {
            val (a, b) = proof.edge ?: continue
            val evidenceId = proof.evidenceIds.single()
            edges.getOrPut(a) { ArrayList() }.add(b to evidenceId)
            edges.getOrPut(b) { ArrayList() }.add(a to evidenceId)
        }
        val reachable = HashMap<String, Pair<String, String>?>()
        roots.forEach { reachable[it] = null }
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            for ((neighbor, evidenceId) in edges[name].orEmpty()) {
                if (neighbor in reachable) continue
                reachable[neighbor] = name to evidenceId
                pending.add(neighbor)
            }
        }
        return reachable
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

    override suspend fun deleteBook(bookKey: String, bookName: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val identities = dao.identitiesOf(bookKey)
            val urls = identities.map { it.bookUrl }.toSet()
            recordDeletion(bookKey, bookUrls = urls)
            if (bookName.isNotBlank()) AudioPlanStore.delete(bookName, urls)
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
            identities.forEach { dao.deleteIdentity(it.bookUrl) }
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

private const val LEGACY_ALIAS_LINK = "ALIAS_EQUAL 确定性链接"

private val dependentAliases = setOf(RaCharacterAlias.BATCH_LINK_SOURCE, RaCharacterAlias.BATCH_PROOF_SOURCE)

private fun RaIdentityDecisionEntity.isBindingProof() =
    reason == BATCH_BINDING_CONFIRMATION || reason == BATCH_BINDING_REVISION

private fun RaIdentityDecisionEntity.isProof() = isBindingProof() || reason == TEXT_ALIAS_PROOF ||
    reason == LEGACY_ALIAS_LINK || RaTextAliasEdge.fromReason(reason) != null
