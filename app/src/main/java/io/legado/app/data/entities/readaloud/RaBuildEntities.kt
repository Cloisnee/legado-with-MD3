package io.legado.app.data.entities.readaloud

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ra_book_identity",
    primaryKeys = ["bookUrl"],
    indices = [
        Index(value = ["bookKey"]),
        Index(value = ["bookKey", "sourceKey"]),
    ],
)
data class RaBookIdentityEntity(
    val bookUrl: String,
    val bookKey: String,
    val sourceKey: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_chapter_build",
    primaryKeys = ["bookKey", "sourceKey", "chapterIndex"],
    indices = [
        Index(value = ["bookKey", "state"]),
    ],
)
data class RaChapterBuildEntity(
    val bookKey: String,
    val sourceKey: String,
    val chapterIndex: Int,
    @ColumnInfo(defaultValue = "")
    val contentHash: String = "",
    val state: String,
    val analysisId: String? = null,
    @ColumnInfo(defaultValue = "")
    val failReason: String = "",
    @ColumnInfo(defaultValue = "0")
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_chapter_analysis",
    primaryKeys = ["id"],
    indices = [
        Index(
            value = ["bookKey", "sourceKey", "chapterIndex", "contentHash", "resolverVersion"],
            unique = false,
        ),
        Index(value = ["bookKey", "chapterIndex", "updatedAt"]),
    ],
)
data class RaChapterAnalysisEntity(
    val id: String,
    val bookKey: String,
    val sourceKey: String,
    val chapterIndex: Int,
    val contentHash: String,
    val resolverVersion: String,
    val status: String,
    @ColumnInfo(defaultValue = "FOREGROUND")
    val origin: String = "FOREGROUND",
    val batchId: String? = null,
    @ColumnInfo(defaultValue = "")
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "")
    val observationJson: String = "",
)

@Entity(
    tableName = "ra_segment",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["analysisId"]),
        Index(value = ["bookKey", "chapterIndex", "paragraphIndex"]),
        Index(value = ["characterId"]),
    ],
)
data class RaSegmentEntity(
    val id: String,
    val analysisId: String,
    val bookKey: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val chapterPosition: Int,
    val text: String,
    val roleType: String,
    @ColumnInfo(defaultValue = "0")
    val seq: Int = 0,
    val localSpeakerId: String? = null,
    val characterId: String? = null,
    @ColumnInfo(defaultValue = "")
    val surfaceName: String = "",
    @ColumnInfo(defaultValue = "0")
    val confidence: Float = 0f,
    @ColumnInfo(defaultValue = "rule")
    val source: String = "rule",
    @ColumnInfo(defaultValue = "")
    val voiceEffect: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_local_speaker",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["analysisId"]),
        Index(value = ["characterId"]),
        Index(value = ["analysisId", "localKey"], unique = true),
    ],
)
data class RaLocalSpeakerEntity(
    val id: String,
    val analysisId: String,
    val localKey: String,
    /** JSON 字符串数组 */
    val surfaceNames: String,
    val primaryName: String,
    @ColumnInfo(defaultValue = "")
    val roleType: String = "",
    @ColumnInfo(defaultValue = "")
    val gender: String = "",
    @ColumnInfo(defaultValue = "")
    val ageBand: String = "",
    val batchEntityId: String? = null,
    val characterId: String? = null,
    val resolution: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_local_mention",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["analysisId"]),
        Index(value = ["characterId"]),
    ],
)
data class RaLocalMentionEntity(
    val id: String,
    val analysisId: String,
    val localKey: String,
    /** JSON 字符串数组 */
    val surfaceNames: String,
    val primaryName: String,
    @ColumnInfo(defaultValue = "")
    val roleType: String = "",
    @ColumnInfo(defaultValue = "")
    val gender: String = "",
    @ColumnInfo(defaultValue = "")
    val ageBand: String = "",
    val batchEntityId: String? = null,
    val characterId: String? = null,
    val resolution: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_evidence",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bookKey"]),
        Index(value = ["analysisId"]),
        Index(value = ["subjectLocalId"]),
    ],
)
data class RaEvidenceEntity(
    val id: String,
    val bookKey: String,
    val analysisId: String,
    val sourceChapter: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val excerpt: String,
    val type: String,
    @ColumnInfo(defaultValue = "")
    val subjectLocalId: String = "",
    @ColumnInfo(defaultValue = "")
    val objectText: String = "",
    @ColumnInfo(defaultValue = "MAIN_TEXT")
    val scope: String = "MAIN_TEXT",
    @ColumnInfo(defaultValue = "1")
    val verified: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_segment_override",
    primaryKeys = ["bookKey", "chapterIndex", "paragraphIndex", "textHash"],
    indices = [Index(value = ["characterId"])],
)
data class RaSegmentOverrideEntity(
    val bookKey: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val textHash: String,
    val characterId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val narrator: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
