package io.legado.app.help.readaloud.playback

import io.legado.app.data.repository.ReadAloudVoicePoolRepository
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import kotlin.random.Random

/**
 * 把「配置列表标签」解析成可合成的具体声线条目；旁白/默认对话也从此处取。
 * 只认当前启用池（与试听链同口径）。
 */
class SpeechVoiceResolver(
    private val voiceGateway: ReadAloudVoiceGateway,
    private val pool: ReadAloudVoicePoolRepository,
) {

    suspend fun byTag(tag: String): ReadAloudVoice? {
        val value = tag.trim()
        if (value.isBlank()) return null
        val catalog = voiceGateway.getEnabledVoices()
            .filter { it.engineType == ReadAloudVoice.ENGINE_TTS_SERVER }
        val entries = runCatching { pool.loadActivePoolTagEntries() }.getOrDefault(emptyMap())
        val entry = entries[value] ?: return null
        val entryId = entry.first
        val config = entry.second
        val base = catalog.firstOrNull { it.speakerId == value && it.engineId == entryId }
        return base?.copy(traitsJson = config)
            ?: ReadAloudVoice(
                id = SpeechIdentity.voiceId(ReadAloudVoice.ENGINE_TTS_SERVER, entryId, value),
                engineType = ReadAloudVoice.ENGINE_TTS_SERVER,
                engineId = entryId,
                speakerId = value,
                displayName = value,
                traitsJson = config,
                managedBy = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
            )
    }

    suspend fun narrator(): ReadAloudVoice? {
        val groups = runCatching { pool.loadActiveVoiceGroups() }.getOrDefault(emptyList())
        val group = groups.firstOrNull {
            it.effectiveRoleType() == VoiceBankRoleType.NARRATOR && it.tags.isNotEmpty()
        } ?: return null
        return byTag(group.tags.first())
    }

    suspend fun defaultDialog(gender: String, seed: Int): ReadAloudVoice? {
        val groups = runCatching { pool.loadActiveVoiceGroups() }.getOrDefault(emptyList())
        val group = groups.firstOrNull {
            it.effectiveRoleType() == VoiceBankRoleType.DEFAULT_DIALOG && it.tags.isNotEmpty()
        } ?: return null
        val prefix = if (gender == "女") "duihuaB" else "duihuaA"
        val tags = group.tags.filter { it.startsWith(prefix) }.takeIf { it.isNotEmpty() }
            ?: group.tags
        val random = Random(seed)
        return byTag(tags.random(random))
    }

    suspend fun defaultDialog(gender: String): ReadAloudVoice? =
        defaultDialog(gender, Random.nextInt())
}
