package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile

interface RaCharacterGateway {

    suspend fun characters(bookKey: String): List<RaCharacterView>

    suspend fun charactersRaw(bookKey: String): List<RaCharacter>

    suspend fun character(id: String): RaCharacter?

    /** 跟随合并重定向得到最终人物 ID。 */
    suspend fun finalCharacterId(id: String): String

    suspend fun aliasesOf(characterId: String): List<RaCharacterAlias>

    suspend fun charactersByAliases(
        bookKey: String,
        normalizedNames: Collection<String>,
    ): List<RaCharacterAlias>

    suspend fun ensureCharacter(
        bookKey: String,
        displayName: String,
        roleType: String,
        gender: String,
        ageBand: String,
        chapterIndex: Int,
        aliases: List<String>,
        status: String,
        aliasSource: String,
        attributeSource: String,
    ): RaCharacter

    suspend fun replaceAliases(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    )

    suspend fun setDefaultVoice(characterId: String, voiceTag: String, voiceEntryId: String)

    suspend fun attachAliases(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    )

    suspend fun recomputeAppearance(characterId: String)

    suspend fun updateDisplayName(characterId: String, name: String)

    suspend fun updateAttributes(
        characterId: String,
        roleType: String,
        gender: String,
        ageBand: String,
    )

    suspend fun deleteCharacters(characterIds: Collection<String>)

    suspend fun mergeCharacters(fromCharacterId: String, toCharacterId: String, reason: String)

    suspend fun saveDecision(
        bookKey: String,
        localSpeaker: RaLocalSpeaker,
        candidateIds: List<String>,
        verdict: String,
        chosenCharacterId: String?,
        confidence: Float,
        reason: String,
        modelId: String,
    )

    // ---- 发声方案 ----

    suspend fun voiceProfiles(bookKey: String): List<RaVoiceProfile>

    suspend fun voiceProfilesOf(characterId: String): List<RaVoiceProfile>

    suspend fun saveVoiceProfile(profile: RaVoiceProfile)

    suspend fun updateVoiceProfileVoice(profileId: String, voiceTag: String, voiceEntryId: String, source: String)

    suspend fun setVoiceProfileLocked(profileId: String, locked: Boolean)

    suspend fun deleteVoiceProfile(profileId: String)
}
