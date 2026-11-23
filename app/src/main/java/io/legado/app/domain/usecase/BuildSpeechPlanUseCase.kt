package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaSegment
import io.legado.app.help.readaloud.playback.SpeechVoiceResolver

/**
 * 播放计划：每句台词按 `characterId → voiceProfileId → voiceId` 取声线。
 * 名字不参与选音；重命名/加别名不会改变声音。
 */
class BuildSpeechPlanUseCase(
    private val characters: RaCharacterGateway,
    private val voices: SpeechVoiceResolver,
) {

    suspend operator fun invoke(
        bookKey: String,
        segments: List<RaSegment>,
        preferredDefaultVoiceId: String? = null,
        useMultiSpeaker: Boolean = true,
        emotionBySegment: Map<String, String> = emptyMap(),
        instructionBySegment: Map<String, String> = emptyMap(),
    ): List<SpeechPlanItem> {
        if (segments.isEmpty()) return emptyList()
        val narrator = voices.narrator()
        val narratorVoice = preferredDefaultVoiceId?.let { id -> null } ?: narrator
        val defaultVoice = narratorVoice

        val rawCharacters = characters.charactersRaw(bookKey).associateBy { it.id }
        val profiles = characters.voiceProfiles(bookKey)
        val profilesByCharacter = profiles.groupBy { it.characterId }
        val finalIds = HashMap<String, String>()
        val voiceCache = HashMap<String, ReadAloudVoice?>()

        suspend fun voiceOfTag(tag: String): ReadAloudVoice? {
            if (tag.isBlank()) return null
            return voiceCache.getOrPut(tag) { voices.byTag(tag) }
        }

        return segments.map { segment ->
            val role = segment.roleType
            val finalCharacterId = segment.characterId?.let { id ->
                finalIds.getOrPut(id) { characters.finalCharacterId(id) }
            }
            val character = finalCharacterId?.let(rawCharacters::get)
            val profile = if (useMultiSpeaker && role != SpeechRoleType.Narrator) {
                io.legado.app.domain.model.readaloud.v4.VoiceProfileSelector.select(
                    profilesByCharacter[finalCharacterId].orEmpty(),
                    segment.chapterIndex,
                    segment.paragraphIndex,
                )
            } else null
            val characterVoice = profile?.voiceTag?.let { voiceOfTag(it) }
            val gender = character?.gender ?: "男"
            val fallbackDialog = if (useMultiSpeaker) {
                voices.defaultDialog(gender, (segment.chapterIndex + 1) * 31 + segment.paragraphIndex)
            } else null
            val primary = when {
                !useMultiSpeaker -> defaultVoice
                role == SpeechRoleType.Narrator -> narratorVoice ?: defaultVoice
                else -> characterVoice ?: fallbackDialog ?: narratorVoice ?: defaultVoice
            }
            val fallbackVoices = if (!useMultiSpeaker) {
                emptyList()
            } else {
                buildList {
                    if (role != SpeechRoleType.Narrator) {
                        characters.voiceProfilesOf(finalCharacterId.orEmpty())
                            .filter { it.id != profile?.id }
                            .forEach { other -> voiceOfTag(other.voiceTag)?.let(::add) }
                        add(fallbackDialog)
                        add(narratorVoice)
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
            )
        }
    }
}
