package io.legado.app.data.entities.readaloud

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "ra_character",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bookKey", "status"]),
        Index(value = ["bookKey", "displayName"]),
    ],
)
data class RaCharacterEntity(
    val id: String,
    val bookKey: String,
    val displayName: String,
    @ColumnInfo(defaultValue = "核心")
    val roleType: String = "核心",
    @ColumnInfo(defaultValue = "男")
    val gender: String = "男",
    @ColumnInfo(defaultValue = "男青年")
    val ageBand: String = "男青年",
    val status: String,
    @ColumnInfo(defaultValue = "0")
    val firstChapter: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val lastChapter: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val appearanceCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val userEdited: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val identityRevision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_character_alias",
    primaryKeys = ["bookKey", "normalized", "characterId"],
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["bookKey", "normalized"]),
    ],
)
data class RaCharacterAliasEntity(
    val bookKey: String,
    val normalized: String,
    val characterId: String,
    val surface: String,
    @ColumnInfo(defaultValue = "ai")
    val source: String = "ai",
    @ColumnInfo(defaultValue = "0")
    val isGeneric: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val firstChapter: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_attribute_obs",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["bookKey", "key"]),
    ],
)
data class RaAttributeObsEntity(
    val id: String,
    val bookKey: String,
    val characterId: String,
    val key: String,
    val value: String,
    @ColumnInfo(defaultValue = "0")
    val fromChapter: Int = 0,
    @ColumnInfo(defaultValue = "")
    val evidenceId: String = "",
    /** explicit / inferred / user / default */
    val source: String,
    @ColumnInfo(defaultValue = "1")
    val confidence: Float = 1f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_relation_obs",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["targetCharacterId"]),
        Index(value = ["bookKey"]),
    ],
)
data class RaRelationObsEntity(
    val id: String,
    val bookKey: String,
    val characterId: String,
    val relation: String,
    val targetCharacterId: String? = null,
    @ColumnInfo(defaultValue = "")
    val targetText: String = "",
    @ColumnInfo(defaultValue = "0")
    val fromChapter: Int = 0,
    @ColumnInfo(defaultValue = "")
    val evidenceId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_identity_link",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bookKey", "aCharacterId"]),
        Index(value = ["bookKey", "bCharacterId"]),
    ],
)
data class RaIdentityLinkEntity(
    val id: String,
    val bookKey: String,
    /** 规范化为 aCharacterId < bCharacterId */
    val aCharacterId: String,
    val bCharacterId: String,
    val kind: String,
    val source: String,
    @ColumnInfo(defaultValue = "[]")
    val evidenceIds: String = "[]",
    @ColumnInfo(defaultValue = "")
    val decisionId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_identity_decision",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bookKey", "createdAt"]),
        Index(value = ["localSpeakerId"]),
    ],
)
data class RaIdentityDecisionEntity(
    val id: String,
    val bookKey: String,
    val localSpeakerId: String,
    @ColumnInfo(defaultValue = "[]")
    val candidateIds: String = "[]",
    val verdict: String,
    val chosenCharacterId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val confidence: Float = 0f,
    @ColumnInfo(defaultValue = "")
    val reason: String = "",
    @ColumnInfo(defaultValue = "[]")
    val evidenceIds: String = "[]",
    @ColumnInfo(defaultValue = "")
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_character_redirect",
    primaryKeys = ["fromCharacterId"],
    indices = [Index(value = ["toCharacterId"])],
)
data class RaCharacterRedirectEntity(
    val fromCharacterId: String,
    val toCharacterId: String,
    @ColumnInfo(defaultValue = "")
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "ra_voice_profile",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["bookKey", "characterId"]),
        Index(value = ["bookKey", "voiceTag"]),
    ],
)
data class RaVoiceProfileEntity(
    val id: String,
    val bookKey: String,
    val characterId: String,
    val kind: String,
    val label: String,
    @ColumnInfo(defaultValue = "")
    val castingKey: String = "",
    @ColumnInfo(defaultValue = "")
    val voiceTag: String = "",
    @ColumnInfo(defaultValue = "")
    val voiceEntryId: String = "",
    @ColumnInfo(defaultValue = "")
    val rangeJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val locked: Boolean = false,
    @ColumnInfo(defaultValue = "auto")
    val source: String = "auto",
    @ColumnInfo(defaultValue = "0")
    val voiceRevision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
