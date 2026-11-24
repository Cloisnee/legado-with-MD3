package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.domain.model.readaloud.v4.VoiceProfileSelector
import io.legado.app.help.readaloud.playback.SpeechVoiceSource

/**
 * 播放计划：每句台词按 `characterId → voiceProfileId → voiceId` 取声线。
 * 名字不参与选音；重命名/加别名不会改变声音。
 * 无人物台词使用“整章稳定”的默认对话声线（同一章同一性别只取一次）。
 */
class BuildSpeechPlanUseCase(
    private val characters: RaCharacterGateway,
    private val voices: SpeechVoiceSource,
) {

    suspend operator fun invoke(
        bookKey: String,
        segments: List<RaSegment>,
        useMultiSpeaker: Boolean = true,
        emotionBySegment: Map<String, String> = emptyMap(),
        instructionBySegment: Map<String, String> = emptyMap(),
    ): List<SpeechPlanItem> {
        if (segments.isEmpty()) return emptyList()
        val narrator = voices.narrator()
        val defaultVoice = narrator

        val rawCharacters = characters.charactersRaw(bookKey).associateBy { it.id }
        val profilesByCharacter = characters.voiceProfiles(bookKey).groupBy { it.characterId }
        val finalIds = HashMap<String, String>()
        val voiceCache = HashMap<String, ReadAloudVoice?>()
        val dialogCache = HashMap<String, ReadAloudVoice?>()
        val chapterIndex = segments.first().chapterIndex
        val chapterSeed = bookKey.hashCode() * 31 + chapterIndex

        suspend fun voiceOfTag(tag: String): ReadAloudVoice? {
            if (tag.isBlank()) return null
            return voiceCache.getOrPut(tag) { voices.byTag(tag) }
        }

        suspend fun dialogVoice(gender: String): ReadAloudVoice? =
            dialogCache.getOrPut(gender) { voices.defaultDialog(gender, chapterSeed) }

        return segments.map { segment ->
            val role = segment.roleType
            val finalCharacterId = segment.characterId?.let { id ->
                finalIds.getOrPut(id) { characters.finalCharacterId(id) }
            }
            val character = finalCharacterId?.let(rawCharacters::get)
            val characterProfiles = profilesByCharacter[finalCharacterId].orEmpty()
            val profile = if (useMultiSpeaker && role != SpeechRoleType.Narrator) {
                VoiceProfileSelector.select(
                    characterProfiles,
                    segment.chapterIndex,
                    segment.paragraphIndex,
                )
            } else null
            val characterVoice = profile?.voiceTag?.let { voiceOfTag(it) }
            val fallbackDialog = if (useMultiSpeaker) {
                dialogVoice(character?.gender ?: "男")
            } else null
            val primary = when {
                !useMultiSpeaker -> defaultVoice
                role == SpeechRoleType.Narrator -> narrator ?: defaultVoice
                else -> characterVoice ?: fallbackDialog ?: narrator ?: defaultVoice
            }
            val fallbackVoices = if (!useMultiSpeaker) {
                emptyList()
            } else {
                buildList {
                    if (role != SpeechRoleType.Narrator) {
                        characterProfiles
                            .filter { it.id != profile?.id }
                            .forEach { other -> voiceOfTag(other.voiceTag)?.let(::add) }
                        add(fallbackDialog)
                        add(narrator)
                        add(defaultVoice)
                    }
                }.filterNotNull().distinctBy(ReadAloudVoice::id).filterNot { it.id == primary?.id }
            }
            SpeechPlanItem(
                segment = segment,
                voice = primary,
                fallbackVoices = fallbackVoices,
                emotionTag = emotionBySegment[segment.id].orEmpty(),
                instruction = instructionBySegment[segment.id].orEmpty(),
                characterDisplayName = if (role == SpeechRoleType.Narrator) {
                    ""
                } else {
                    character?.displayName ?: segment.surfaceName
                },
            )
        }
    }
}
