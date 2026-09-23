package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.ChapterSpeechAnalysisEntity
import io.legado.app.data.entities.ChapterSpeechSegmentEntity

@Dao
interface ChapterSpeechDao {

    @Query(
        "select * from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex and contentHash = :contentHash " +
            "and resolverVersion = :resolverVersion limit 1"
    )
    suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysisEntity?

    @Query(
        "select * from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex " +
            "order by updatedAt desc limit 1"
    )
    suspend fun getLatestAnalysis(
        bookUrl: String,
        chapterIndex: Int,
    ): ChapterSpeechAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysisEntity)

    @Query(
        "select * from chapter_speech_segments where analysisId = :analysisId " +
            "order by paragraphIndex, start, end"
    )
    suspend fun getSegments(analysisId: String): List<ChapterSpeechSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<ChapterSpeechSegmentEntity>)

    @Query("delete from chapter_speech_segments where analysisId = :analysisId")
    suspend fun deleteSegments(analysisId: String)

    @Transaction
    suspend fun replaceSegments(
        analysisId: String,
        segments: List<ChapterSpeechSegmentEntity>,
    ) {
        deleteSegments(analysisId)
        if (segments.isNotEmpty()) upsertSegments(segments)
    }

    @Transaction
    suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysisEntity,
        segments: List<ChapterSpeechSegmentEntity>,
    ) {
        upsertAnalysis(analysis)
        replaceSegments(analysis.id, segments)
    }

    @Query("delete from chapter_speech_segments where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun deleteChapterSegments(bookUrl: String, chapterIndex: Int)

    @Query("delete from chapter_speech_analysis where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun deleteChapterAnalyses(bookUrl: String, chapterIndex: Int)

    @Transaction
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) {
        deleteChapterSegments(bookUrl, chapterIndex)
        deleteChapterAnalyses(bookUrl, chapterIndex)
    }

    // ---- B25：章节删除（回滚≥floor 连续尾段；与剧本/缓存/账本/人物同一集合） ----

    @Query("delete from chapter_speech_segments where bookUrl = :bookUrl and chapterIndex >= :floor")
    suspend fun deleteSegmentsFrom(bookUrl: String, floor: Int)

    @Query("delete from chapter_speech_analysis where bookUrl = :bookUrl and chapterIndex >= :floor")
    suspend fun deleteAnalysesFrom(bookUrl: String, floor: Int)

    // ---- 整本书删除（B10.3·U7：含换源遗留旧 bookUrl 键） ----

    @Query("select distinct bookUrl from chapter_speech_analysis")
    suspend fun distinctAnalysisBookUrls(): List<String>

    @Query("select distinct bookUrl from chapter_speech_segments")
    suspend fun distinctSegmentBookUrls(): List<String>

    @Query("delete from chapter_speech_analysis where bookUrl = :bookUrl")
    suspend fun deleteBookAnalyses(bookUrl: String)

    @Query("delete from chapter_speech_segments where bookUrl = :bookUrl")
    suspend fun deleteBookSegments(bookUrl: String)
}
