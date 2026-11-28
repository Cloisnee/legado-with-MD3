package io.legado.app.data.entities.readaloud

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ra_scene",
    primaryKeys = ["id"],
    indices = [Index(value = ["bookKey", "name"])],
)
data class RaSceneEntity(
    val id: String,
    val bookKey: String,
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val firstChapter: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val lastChapter: Int = 0,
    @ColumnInfo(defaultValue = "{}")
    val attributesJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_direction_plan",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["analysisId", "module"], unique = true),
        Index(value = ["bookKey", "chapterIndex"]),
    ],
)
data class RaDirectionPlanEntity(
    val id: String,
    val analysisId: String,
    val bookKey: String,
    val chapterIndex: Int,
    val module: String,
    val status: String,
    @ColumnInfo(defaultValue = "1")
    val directorRevision: Long = 1L,
    @ColumnInfo(defaultValue = "0")
    val identityRevisionSnapshot: Long = 0L,
    @ColumnInfo(defaultValue = "FOREGROUND")
    val origin: String = "FOREGROUND",
    @ColumnInfo(defaultValue = "")
    val failReason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_direction_line",
    primaryKeys = ["planId", "segmentId"],
    indices = [Index(value = ["planId"])],
)
data class RaDirectionLineEntity(
    val planId: String,
    val segmentId: String,
    @ColumnInfo(defaultValue = "")
    val emotionTag: String = "",
    @ColumnInfo(defaultValue = "")
    val instruction: String = "",
    @ColumnInfo(defaultValue = "")
    val voiceStateHint: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_direction_audio",
    primaryKeys = ["planId", "idx"],
    indices = [Index(value = ["planId"])],
)
data class RaDirectionAudioEntity(
    val planId: String,
    val idx: Int,
    val type: String,
    @ColumnInfo(defaultValue = "0")
    val para: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val frag: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val posRatio: Float = 0f,
    @ColumnInfo(defaultValue = "")
    val tag: String = "",
    @ColumnInfo(defaultValue = "")
    val desc: String = "",
    @ColumnInfo(defaultValue = "0")
    val delayMs: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val hold: Int = 0,
    @ColumnInfo(defaultValue = "")
    val theme: String = "",
    @ColumnInfo(defaultValue = "")
    val scene: String = "",
    @ColumnInfo(defaultValue = "")
    val mood: String = "",
    @ColumnInfo(defaultValue = "")
    val speed: String = "",
    @ColumnInfo(defaultValue = "")
    val anchor: String = "",
    @ColumnInfo(defaultValue = "")
    val sceneKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_direction_scene",
    primaryKeys = ["planId", "sceneKey"],
    indices = [Index(value = ["planId"])],
)
data class RaDirectionSceneEntity(
    val planId: String,
    val sceneKey: String,
    val sceneId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val fromPara: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val toPara: Int = 0,
    @ColumnInfo(defaultValue = "")
    val location: String = "",
    @ColumnInfo(defaultValue = "")
    val time: String = "",
    @ColumnInfo(defaultValue = "")
    val weather: String = "",
    @ColumnInfo(defaultValue = "")
    val lighting: String = "",
    @ColumnInfo(defaultValue = "")
    val atmosphere: String = "",
    @ColumnInfo(defaultValue = "")
    val soundscape: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
