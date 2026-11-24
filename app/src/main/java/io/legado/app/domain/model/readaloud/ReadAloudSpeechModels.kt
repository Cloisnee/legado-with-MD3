package io.legado.app.domain.model.readaloud

import io.legado.app.domain.model.readaloud.v4.RaSegment

data class ReadAloudVoice(
    val id: String,
    val engineType: String,
    val engineId: String,
    val speakerId: String,
    val displayName: String,
    val traitsJson: String = "[]",
    val emotionCatalogJson: String = "[]",
    val managedBy: String = MANAGED_BY_USER,
    val enabled: Boolean = true,
    val available: Boolean = true,
    /** 用户主动排除：不进候选、不参与选音，但行要留着，见实体上的说明。 */
    val excluded: Boolean = false,
    val revision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_TTS_SERVER = "tts_server"
        const val MANAGED_BY_USER = "user"
        const val MANAGED_BY_CONFIGURED_TTS = "configured_tts"
    }
}

data class ReadAloudEngineSelection(
    val engineType: String,
    val engineId: String,
    val speakerId: String = "",
    val displayName: String = "",
)

data class VoiceCatalogEntry(
    val engineType: String,
    val engineId: String,
    val speakerId: String = "",
    val displayName: String,
    val traitsJson: String = "[]",
    val emotionCatalogJson: String = "[]",
    val sourceRevision: Long = 0L,
    val managedBy: String = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
)




/**
 * 声线库分组（配置列表一级分组 → 「已选中」声线库）：tags 保持列表顺序（首=置顶）。
 */
data class VoiceGroupInfo(
    val name: String,
    val roleType: String = "",
    val tags: List<String> = emptyList(),
) {
    /**
     * 生效类型：显式 roleType 优先（**冻结值**，见 [VoiceBankRoleType]）；
     * 为空（尚未冻结）才按组名/标签前缀推断，兜底核心。
     */
    fun effectiveRoleType(): String =
        VoiceBankRoleType.normalize(roleType)
            .ifEmpty { VoiceBankRoleType.infer(name, tags) }
}

data class CanonicalSpeechParagraph(
    val index: Int,
    val text: String,
    val chapterPosition: Int,
)

data class SpeechPlanItem(
    val segment: RaSegment,
    val voice: ReadAloudVoice?,
    val fallbackVoices: List<ReadAloudVoice>,
    val emotionTag: String = "",
    val instruction: String = "",
    val characterDisplayName: String = "",
)

enum class SpeechRoleType(val storageValue: String) {
    Narrator("narrator"),
    Character("character"),
    Thought("thought"),
    Unknown("unknown");

    companion object {
        fun fromStorage(value: String): SpeechRoleType =
            entries.firstOrNull { it.storageValue == value } ?: Unknown
    }
}

enum class SpeechResolutionSource(val storageValue: String) {
    Rule("rule"),
    Local("local"),
    Ai("ai"),
    User("user"),
    Fallback("fallback");

    companion object {
        fun fromStorage(value: String): SpeechResolutionSource =
            entries.firstOrNull { it.storageValue == value } ?: Fallback
    }
}
