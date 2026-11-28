package io.legado.app.data.dao.readaloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.readaloud.RaBookIdentityEntity
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity

data class RaChapterLineCount(
    val chapterIndex: Int,
    val lineCount: Int,
)

data class RaAppearanceAggregate(
    val firstChapter: Int?,
    val lastChapter: Int?,
    val chapterCount: Int,
    val lineCount: Int,
)

data class RaChapterBuildCount(
    val chapterIndex: Int,
    val state: String,
)

@Dao
interface RaBuildDao {

    // ---------------- 书籍身份键 ----------------

    @Query("select * from ra_book_identity where bookUrl = :bookUrl limit 1")
    suspend fun getIdentity(bookUrl: String): RaBookIdentityEntity?

    @Query("select * from ra_book_identity where bookKey = :bookKey order by updatedAt desc")
    suspend fun identitiesOf(bookKey: String): List<RaBookIdentityEntity>

    @Query("select * from ra_book_identity order by updatedAt desc")
    suspend fun allIdentities(): List<RaBookIdentityEntity>

    @Query("select distinct bookKey from ra_book_identity")
    suspend fun distinctBookKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentity(entity: RaBookIdentityEntity)

    @Query("delete from ra_book_identity where bookUrl = :bookUrl")
    suspend fun deleteIdentity(bookUrl: String)

    // ---------------- 章节建库状态 ----------------

    @Query(
        "select * from ra_chapter_build where bookKey = :bookKey and sourceKey = :sourceKey " +
            "and chapterIndex = :chapterIndex limit 1"
    )
    suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int): RaChapterBuildEntity?

    @Query(
        "select * from ra_chapter_build where bookKey = :bookKey and sourceKey = :sourceKey " +
            "order by chapterIndex"
    )
    suspend fun builds(bookKey: String, sourceKey: String): List<RaChapterBuildEntity>

    @Query("select * from ra_chapter_build where bookKey = :bookKey order by chapterIndex")
    suspend fun allBuildsOf(bookKey: String): List<RaChapterBuildEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBuild(entity: RaChapterBuildEntity)

    @Query(
        "delete from ra_chapter_build where bookKey = :bookKey and sourceKey = :sourceKey " +
            "and chapterIndex in (:chapterIndexes)"
    )
    suspend fun deleteBuilds(bookKey: String, sourceKey: String, chapterIndexes: List<Int>)

    // ---------------- 章节分析 ----------------

    @Query("select * from ra_chapter_analysis where id = :id limit 1")
    suspend fun getAnalysisById(id: String): RaChapterAnalysisEntity?

    @Query(
        "select * from ra_chapter_analysis where bookKey = :bookKey and sourceKey = :sourceKey " +
            "and chapterIndex = :chapterIndex and contentHash = :contentHash " +
            "and resolverVersion = :resolverVersion order by updatedAt desc limit 1"
    )
    suspend fun getAnalysis(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaChapterAnalysisEntity?

    @Query(
        "select * from ra_chapter_analysis where bookKey = :bookKey and chapterIndex = :chapterIndex " +
            "and contentHash = :contentHash and resolverVersion = :resolverVersion and status = 'RESOLVED' " +
            "order by updatedAt desc limit 1"
    )
    suspend fun analysisByContentHash(
        bookKey: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): RaChapterAnalysisEntity?

    @Query(
        "select * from ra_chapter_analysis where bookKey = :bookKey and sourceKey = :sourceKey " +
            "and chapterIndex = :chapterIndex order by updatedAt desc limit 1"
    )
    suspend fun latestAnalysisForSource(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ): RaChapterAnalysisEntity?

    @Query(
        "select * from ra_chapter_analysis where bookKey = :bookKey " +
            "and sourceKey = :sourceKey order by chapterIndex, updatedAt desc"
    )
    suspend fun analyses(bookKey: String, sourceKey: String): List<RaChapterAnalysisEntity>

    @Query(
        "select a.* from ra_chapter_analysis a inner join ra_chapter_build b " +
            "on b.bookKey = a.bookKey and b.sourceKey = a.sourceKey and b.chapterIndex = a.chapterIndex " +
            "and b.analysisId = a.id and b.contentHash = a.contentHash " +
            "where a.bookKey = :bookKey and a.sourceKey = :sourceKey and a.batchId in (:batchIds) " +
            "and ((b.state = 'IDENTITY_READY' and a.status = 'RESOLVED') " +
            "or (b.state = 'EXTRACTED' and a.status = 'EXTRACTED')) order by a.chapterIndex"
    )
    suspend fun currentBatchAnalyses(
        bookKey: String, sourceKey: String, batchIds: Set<String>,
    ): List<RaChapterAnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(entity: RaChapterAnalysisEntity)

    @Query("select * from ra_chapter_analysis where bookKey = :bookKey order by chapterIndex, updatedAt desc")
    suspend fun allAnalysesOf(bookKey: String): List<RaChapterAnalysisEntity>

    @Query("delete from ra_chapter_analysis where bookKey = :bookKey")
    suspend fun deleteAnalysesOfBook(bookKey: String)

    // ---------------- 台词分段 ----------------

    @Query("select * from ra_segment where analysisId = :analysisId order by paragraphIndex, start, end")
    suspend fun segments(analysisId: String): List<RaSegmentEntity>

    @Query(
        "select s.* from ra_segment s inner join ra_chapter_build b " +
            "on b.bookKey = s.bookKey and b.chapterIndex = s.chapterIndex and b.analysisId = s.analysisId " +
            "where b.bookKey = :bookKey and b.sourceKey = :sourceKey and b.chapterIndex = :chapterIndex " +
            "order by s.paragraphIndex, s.start, s.end"
    )
    suspend fun segmentsOfChapter(
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ): List<RaSegmentEntity>

    @Query("select * from ra_segment where bookKey = :bookKey order by chapterIndex, paragraphIndex, start")
    suspend fun allSegmentsOf(bookKey: String): List<RaSegmentEntity>

    @Query(
        "select s.chapterIndex as chapterIndex, count(*) as lineCount from ra_segment s " +
            "inner join ra_chapter_build b on b.bookKey = s.bookKey " +
            "and b.chapterIndex = s.chapterIndex and b.analysisId = s.analysisId " +
            "where b.bookKey = :bookKey and b.sourceKey = :sourceKey " +
            "group by s.chapterIndex"
    )
    suspend fun lineCounts(bookKey: String, sourceKey: String): List<RaChapterLineCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(entities: List<RaSegmentEntity>)

    @Query("delete from ra_segment where analysisId = :analysisId")
    suspend fun deleteSegments(analysisId: String)

    @Query(
        "select distinct s.* from ra_segment s inner join ra_chapter_build b " +
            "on b.bookKey = s.bookKey and b.chapterIndex = s.chapterIndex and b.analysisId = s.analysisId " +
            "inner join ra_chapter_analysis a on a.id = s.analysisId " +
            "where s.bookKey = :bookKey and s.chapterIndex = :chapterIndex and s.paragraphIndex = :paragraphIndex " +
            "and a.status = 'RESOLVED'"
    )
    suspend fun currentSegmentsForOverride(bookKey: String, chapterIndex: Int, paragraphIndex: Int): List<RaSegmentEntity>

    @Query(
        "update ra_segment set characterId = :toCharacterId, updatedAt = :updatedAt " +
            "where characterId = :fromCharacterId"
    )
    suspend fun reassignSegmentCharacter(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("update ra_segment set characterId = null, updatedAt = :updatedAt where characterId = :characterId")
    suspend fun clearSegmentCharacter(characterId: String, updatedAt: Long)

    @Query("delete from ra_segment where bookKey = :bookKey")
    suspend fun deleteSegmentsOfBook(bookKey: String)

    @Query(
        "select min(s.chapterIndex) as firstChapter, max(s.chapterIndex) as lastChapter, " +
            "count(distinct s.chapterIndex) as chapterCount, count(*) as lineCount " +
            "from ra_segment s inner join ra_chapter_build b " +
            "on b.bookKey = s.bookKey and b.chapterIndex = s.chapterIndex and b.analysisId = s.analysisId " +
            "where s.characterId = :characterId"
    )
    suspend fun appearanceOf(characterId: String): RaAppearanceAggregate

    // ---------------- 章内说话人 ----------------

    @Query("select * from ra_local_speaker where analysisId = :analysisId")
    suspend fun localSpeakers(analysisId: String): List<RaLocalSpeakerEntity>

    @Query("select * from ra_local_speaker where analysisId in (select id from ra_chapter_analysis where bookKey = :bookKey)")
    suspend fun localSpeakersOfBook(bookKey: String): List<RaLocalSpeakerEntity>

    @Query("select * from ra_local_speaker where characterId = :characterId")
    suspend fun localSpeakersOfCharacter(characterId: String): List<RaLocalSpeakerEntity>

    @Query(
        "select s.* from ra_local_speaker s inner join ra_chapter_build b on b.analysisId = s.analysisId " +
            "where b.bookKey = :bookKey and b.chapterIndex not in (:chapters)",
    )
    suspend fun localSpeakersOutsideChapters(
        bookKey: String,
        chapters: List<Int>,
    ): List<RaLocalSpeakerEntity>

    @Query(
        "select s.* from ra_local_speaker s inner join ra_chapter_build b on b.analysisId = s.analysisId " +
            "where s.characterId = :characterId and b.state = 'IDENTITY_READY'",
    )
    suspend fun currentSpeakersOfCharacter(characterId: String): List<RaLocalSpeakerEntity>

    @Query("update ra_local_speaker set characterId = :characterId, updatedAt = :updatedAt where id in (:ids)")
    suspend fun updateLocalSpeakerCharacterByIds(
        ids: List<String>,
        characterId: String,
        updatedAt: Long,
    )

    @Query("select * from ra_segment where characterId = :characterId")
    suspend fun segmentsOfCharacter(characterId: String): List<RaSegmentEntity>

    @Query("update ra_segment set characterId = :characterId, updatedAt = :updatedAt where id in (:ids)")
    suspend fun updateSegmentCharacterByIds(
        ids: List<String>,
        characterId: String,
        updatedAt: Long,
    )

    @Query("select * from ra_local_speaker where id = :id limit 1")
    suspend fun localSpeaker(id: String): RaLocalSpeakerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalSpeakers(entities: List<RaLocalSpeakerEntity>)

    @Query(
        "update ra_local_speaker set characterId = :characterId, resolution = :resolution, " +
            "updatedAt = :updatedAt where id = :id"
    )
    suspend fun updateLocalSpeaker(
        id: String,
        characterId: String?,
        resolution: String,
        updatedAt: Long,
    )

    @Query(
        "update ra_local_speaker set characterId = :toCharacterId, updatedAt = :updatedAt, " +
            "resolution = case when :confirmBatchLink and resolution = 'BATCH_LINKED' then 'RESOLVED' else resolution end " +
            "where characterId = :fromCharacterId"
    )
    suspend fun reassignLocalSpeakerCharacter(
        fromCharacterId: String,
        toCharacterId: String,
        updatedAt: Long,
        confirmBatchLink: Boolean,
    )

    @Query("delete from ra_local_speaker where analysisId = :analysisId")
    suspend fun deleteLocalSpeakers(analysisId: String)

    @Query("delete from ra_local_speaker where analysisId in (select id from ra_chapter_analysis where bookKey = :bookKey)")
    suspend fun deleteLocalSpeakersOfBook(bookKey: String)

    // ---------------- 旁白提及 ----------------

    @Query("select * from ra_local_mention where analysisId = :analysisId")
    suspend fun localMentions(analysisId: String): List<RaLocalMentionEntity>

    @Query("select * from ra_local_mention where analysisId in (select id from ra_chapter_analysis where bookKey = :bookKey)")
    suspend fun localMentionsOfBook(bookKey: String): List<RaLocalMentionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalMentions(entities: List<RaLocalMentionEntity>)

    @Query("delete from ra_local_mention where analysisId = :analysisId")
    suspend fun deleteLocalMentions(analysisId: String)

    // ---------------- 证据 ----------------

    @Query("select * from ra_evidence where analysisId = :analysisId order by sourceChapter, paragraphIndex")
    suspend fun evidence(analysisId: String): List<RaEvidenceEntity>

    @Query("select * from ra_evidence where bookKey = :bookKey")
    suspend fun evidenceOfBook(bookKey: String): List<RaEvidenceEntity>

    @Query(
        "select * from ra_evidence where bookKey = :bookKey and type = :type " +
            "order by sourceChapter desc limit :limit"
    )
    suspend fun evidenceOfType(bookKey: String, type: String, limit: Int): List<RaEvidenceEntity>

    @Query(
        "select * from ra_evidence where bookKey = :bookKey and subjectLocalId in " +
            "(select id from ra_local_speaker where characterId = :characterId) " +
            "order by case type when 'ALIAS_EQUAL' then 0 when 'CONTRADICTION' then 1 else 2 end, " +
            "sourceChapter desc limit :limit",
    )
    suspend fun evidenceOfCharacter(
        bookKey: String,
        characterId: String,
        limit: Int,
    ): List<RaEvidenceEntity>

    @Query("select * from ra_evidence where analysisId in (:analysisIds)")
    suspend fun evidenceByAnalysisIds(analysisIds: List<String>): List<RaEvidenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvidence(entities: List<RaEvidenceEntity>)

    @Query("delete from ra_evidence where analysisId = :analysisId")
    suspend fun deleteEvidence(analysisId: String)

    @Query("delete from ra_evidence where bookKey = :bookKey")
    suspend fun deleteEvidenceOfBook(bookKey: String)

    // ---------------- 用户台词覆盖 ----------------

    @Query(
        "select * from ra_segment_override where bookKey = :bookKey and chapterIndex = :chapterIndex"
    )
    suspend fun overrides(bookKey: String, chapterIndex: Int): List<RaSegmentOverrideEntity>

    @Query(
        "select * from ra_segment_override where bookKey = :bookKey and chapterIndex = :chapterIndex " +
            "and paragraphIndex = :paragraphIndex and textHash = :textHash limit 1"
    )
    suspend fun override(
        bookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        textHash: String,
    ): RaSegmentOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(entity: RaSegmentOverrideEntity)

    @Query("delete from ra_segment_override where bookKey = :bookKey and chapterIndex = :chapterIndex")
    suspend fun deleteOverrides(bookKey: String, chapterIndex: Int)

    @Query("delete from ra_segment_override where bookKey = :bookKey")
    suspend fun deleteOverridesOfBook(bookKey: String)

    @Query("select * from ra_segment_override where bookKey = :bookKey")
    suspend fun overridesOfBook(bookKey: String): List<RaSegmentOverrideEntity>


    // ---------------- 按 analysisId 的精确删除（B5） ----------------

    @Query("select * from ra_chapter_analysis where bookKey = :bookKey and chapterIndex in (:chapters)")
    suspend fun analysesForChapters(
        bookKey: String,
        chapters: List<Int>,
    ): List<RaChapterAnalysisEntity>

    @Query("select id from ra_local_speaker where analysisId in (:analysisIds)")
    suspend fun localSpeakerIdsOf(analysisIds: List<String>): List<String>

    @Query("delete from ra_segment where analysisId in (:analysisIds)")
    suspend fun deleteSegmentsByAnalysisIds(analysisIds: List<String>)

    @Query("delete from ra_local_speaker where analysisId in (:analysisIds)")
    suspend fun deleteLocalSpeakersByAnalysisIds(analysisIds: List<String>)

    @Query("delete from ra_local_mention where analysisId in (:analysisIds)")
    suspend fun deleteLocalMentionsByAnalysisIds(analysisIds: List<String>)

    @Query("delete from ra_evidence where analysisId in (:analysisIds)")
    suspend fun deleteEvidenceByAnalysisIds(analysisIds: List<String>)

    @Query("delete from ra_chapter_analysis where id in (:analysisIds)")
    suspend fun deleteAnalysesByIds(analysisIds: List<String>)

    @Query("delete from ra_chapter_build where bookKey = :bookKey and chapterIndex in (:chapters)")
    suspend fun deleteBuildsAllSources(bookKey: String, chapters: List<Int>)

    @Query("delete from ra_segment_override where bookKey = :bookKey and chapterIndex in (:chapters)")
    suspend fun deleteOverridesOfChapters(bookKey: String, chapters: List<Int>)

    @Query("delete from ra_local_mention where analysisId in (select id from ra_chapter_analysis where bookKey = :bookKey)")
    suspend fun deleteLocalMentionsOfBook(bookKey: String)

    // ---------------- 组合事务 ----------------

    @Transaction
    suspend fun saveAnalysisBundle(
        analysis: RaChapterAnalysisEntity,
        segments: List<RaSegmentEntity>,
        localSpeakers: List<RaLocalSpeakerEntity>,
        localMentions: List<RaLocalMentionEntity>,
        evidence: List<RaEvidenceEntity>,
    ) {
        upsertAnalysis(analysis)
        deleteSegments(analysis.id)
        if (segments.isNotEmpty()) upsertSegments(segments)
        deleteLocalSpeakers(analysis.id)
        if (localSpeakers.isNotEmpty()) upsertLocalSpeakers(localSpeakers)
        deleteLocalMentions(analysis.id)
        if (localMentions.isNotEmpty()) upsertLocalMentions(localMentions)
        deleteEvidence(analysis.id)
        if (evidence.isNotEmpty()) upsertEvidence(evidence)
    }

}
