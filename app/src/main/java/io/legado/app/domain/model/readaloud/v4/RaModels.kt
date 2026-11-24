package io.legado.app.domain.model.readaloud.v4

import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import java.security.SecureRandom

/**
 * V4 朗读分析：三层标识与领域模型。
 *
 * `localSpeakerId`（章内，重析可变）→ `characterId`（全书稳定）→ `voiceProfileId`（发声方案）→
 * `voiceId`（具体 TTS 条目）。名字只是显示/搜索属性，不承担身份。
 */

object RaIds {

    private val random = SecureRandom()
    private const val BASE32 = "abcdefghijklmnopqrstuvwxyz234567"

    private fun randomBase32(length: Int): String = buildString(length) {
        repeat(length) { append(BASE32[random.nextInt(BASE32.length)]) }
    }

    fun character(): String = "chr_" + randomBase32(12)
    fun voiceProfile(): String = "vp_" + randomBase32(12)
    fun scene(): String = "scn_" + randomBase32(12)
    fun book(): String = "bk_" + randomBase32(12)
    fun decision(): String = "dec_" + randomBase32(12)
}

/** 章节建库状态机（02 §2）。 */
enum class RaChapterState(val storageValue: String) {
    NO_CONTENT("NO_CONTENT"),
    CACHED("CACHED"),
    EXTRACTING("EXTRACTING"),
    EXTRACTED("EXTRACTED"),
    RESOLVING("RESOLVING"),
    IDENTITY_READY("IDENTITY_READY"),
    SKIPPED_EMPTY("SKIPPED_EMPTY"),
    FAILED("FAILED"),
    STALE("STALE");

    companion object {
        fun fromStorage(value: String): RaChapterState =
            entries.firstOrNull { it.storageValue == value } ?: NO_CONTENT
    }
}

enum class RaAnalysisStatus(val storageValue: String) {
    EXTRACTED("EXTRACTED"),
    RESOLVED("RESOLVED"),
    FAILED("FAILED");

    companion object {
        fun fromStorage(value: String): RaAnalysisStatus =
            entries.firstOrNull { it.storageValue == value } ?: FAILED
    }
}

enum class RaAnalysisOrigin(val storageValue: String) {
    FOREGROUND("FOREGROUND"),
    BACKGROUND_BATCH("BACKGROUND_BATCH"),
    USER_REANALYZE("USER_REANALYZE");

    companion object {
        fun fromStorage(value: String): RaAnalysisOrigin =
            entries.firstOrNull { it.storageValue == value } ?: FOREGROUND
    }
}

/** localSpeaker 的收敛状态；UNKNOWN 不等于“不同人”。 */
enum class RaResolution(val storageValue: String) {
    PENDING("PENDING"),
    RESOLVED("RESOLVED"),
    PROVISIONAL("PROVISIONAL"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(value: String): RaResolution =
            entries.firstOrNull { it.storageValue == value } ?: PENDING
    }
}

enum class RaCharacterStatus(val storageValue: String) {
    ACTIVE("ACTIVE"),
    PROVISIONAL("PROVISIONAL"),
    MERGED("MERGED");

    companion object {
        fun fromStorage(value: String): RaCharacterStatus =
            entries.firstOrNull { it.storageValue == value } ?: ACTIVE
    }
}

enum class RaVoiceProfileKind(val storageValue: String) {
    DEFAULT("DEFAULT"),
    AGE_PERIOD("AGE_PERIOD"),
    DISGUISE("DISGUISE"),
    BODY("BODY"),
    CUSTOM("CUSTOM");

    companion object {
        fun fromStorage(value: String): RaVoiceProfileKind =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT

        /** 同范围多方案命中时的优先级（01 §6.2）。 */
        fun priorityOf(kind: RaVoiceProfileKind): Int = when (kind) {
            DISGUISE -> 4
            BODY -> 3
            AGE_PERIOD -> 2
            CUSTOM -> 1
            DEFAULT -> 0
        }
    }
}

enum class RaEvidenceType(val storageValue: String) {
    ALIAS_EQUAL("ALIAS_EQUAL"),
    SPEAKER_ATTRIBUTION("SPEAKER_ATTRIBUTION"),
    RELATION("RELATION"),
    ATTRIBUTE("ATTRIBUTE"),
    CONTRADICTION("CONTRADICTION"),
    VOICE_STATE("VOICE_STATE"),
    APPEARANCE("APPEARANCE");

    companion object {
        fun fromStorage(value: String): RaEvidenceType =
            entries.firstOrNull { it.storageValue == value } ?: ATTRIBUTE
    }
}

enum class RaEvidenceScope(val storageValue: String) {
    MAIN_TEXT("MAIN_TEXT"),
    CONTEXT_CLUE("CONTEXT_CLUE");

    companion object {
        fun fromStorage(value: String): RaEvidenceScope =
            entries.firstOrNull { it.storageValue == value } ?: MAIN_TEXT
    }
}

enum class RaLinkKind(val storageValue: String) {
    SAME("SAME"),
    CANNOT("CANNOT");

    companion object {
        fun fromStorage(value: String): RaLinkKind =
            entries.firstOrNull { it.storageValue == value } ?: CANNOT
    }
}

enum class RaVerdict(val storageValue: String) {
    SAME("SAME"),
    DIFFERENT("DIFFERENT"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromStorage(value: String): RaVerdict =
            entries.firstOrNull { it.storageValue == value } ?: UNKNOWN
    }
}

data class RaBookSummary(
    val bookKey: String,
    val bookUrl: String,
    val sourceKey: String,
    val name: String,
    val author: String = "",
    val chapterCount: Int = 0,
)

data class RaChapterBuild(
    val bookKey: String,
    val sourceKey: String,
    val chapterIndex: Int,
    val contentHash: String = "",
    val state: RaChapterState = RaChapterState.NO_CONTENT,
    val analysisId: String? = null,
    val failReason: String = "",
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaAnalysis(
    val id: String,
    val bookKey: String,
    val sourceKey: String,
    val chapterIndex: Int,
    val contentHash: String,
    val resolverVersion: String,
    val status: RaAnalysisStatus,
    val origin: RaAnalysisOrigin = RaAnalysisOrigin.FOREGROUND,
    val batchId: String? = null,
    val modelId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaSegment(
    val id: String,
    val analysisId: String,
    val bookKey: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val chapterPosition: Int,
    val text: String,
    val roleType: SpeechRoleType,
    val seq: Int = 0,
    val localSpeakerId: String? = null,
    val characterId: String? = null,
    val surfaceName: String = "",
    val confidence: Float = 0f,
    val source: SpeechResolutionSource = SpeechResolutionSource.Rule,
    val voiceEffect: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaLocalSpeaker(
    val id: String,
    val analysisId: String,
    val localKey: String,
    val surfaceNames: List<String>,
    val primaryName: String,
    val roleType: String = "",
    val gender: String = "",
    val ageBand: String = "",
    val batchEntityId: String? = null,
    val characterId: String? = null,
    val resolution: RaResolution = RaResolution.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaEvidence(
    val id: String,
    val bookKey: String,
    val analysisId: String,
    val sourceChapter: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val excerpt: String,
    val type: RaEvidenceType,
    val subjectLocalId: String = "",
    val objectText: String = "",
    val scope: RaEvidenceScope = RaEvidenceScope.MAIN_TEXT,
    val verified: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaCharacter(
    val id: String,
    val bookKey: String,
    val displayName: String,
    val roleType: String = "核心",
    val gender: String = "男",
    val ageBand: String = "男青年",
    val status: RaCharacterStatus = RaCharacterStatus.ACTIVE,
    val firstChapter: Int = 0,
    val lastChapter: Int = 0,
    val appearanceCount: Int = 0,
    val userEdited: Boolean = false,
    val identityRevision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaCharacterAlias(
    val bookKey: String,
    val normalized: String,
    val characterId: String,
    val surface: String,
    val source: String = "ai",
    val isGeneric: Boolean = false,
    val firstChapter: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RaVoiceProfile(
    val id: String,
    val bookKey: String,
    val characterId: String,
    val kind: RaVoiceProfileKind = RaVoiceProfileKind.DEFAULT,
    val label: String = "默认",
    val castingKey: String = "",
    val voiceTag: String = "",
    val voiceEntryId: String = "",
    val rangeJson: String = "",
    val locked: Boolean = false,
    val source: String = "auto",
    val voiceRevision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** UI/播放侧的最终可见人物（Room 投影；不含任何文件真源）。 */
data class RaCharacterView(
    val id: String,
    val name: String,
    val aliases: String = "",
    val roletype: String = "核心",
    val gender: String = "男",
    val age: String = "男青年",
    val voice: String = "",
    val lastAppearanceChapter: Int = -1,
    val appearanceCount: Int = 0,
    val appearanceChapters: List<Int> = emptyList(),
    val status: String = RaCharacterStatus.ACTIVE.storageValue,
) {
    fun aliasList(): List<String> =
        aliases.split('|').map { it.trim() }.filter { it.isNotEmpty() }
}

/** 剧本审查页一行（替代旧 [io.legado.app.data.repository.ScriptLineRow]）。 */
data class RaScriptLineRow(
    val segmentId: String,
    val absIndex: Int,
    val paragraphIndex: Int = 0,
    val speaker: String,
    val characterId: String? = null,
    val text: String,
    val emotion: String = "",
    val audio: String = "",
)

data class RaSegmentOverride(
    val bookKey: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val textHash: String,
    val characterId: String? = null,
    val narrator: Boolean = false,
)

data class RaPipelineResult(
    val analysis: RaAnalysis,
    val segments: List<RaSegment>,
    val fromCache: Boolean,
)

data class RaAnalysisBundle(
    val analysis: RaAnalysis,
    val segments: List<RaSegment>,
    val localSpeakers: List<RaLocalSpeaker>,
    val evidence: List<RaEvidence>,
)
