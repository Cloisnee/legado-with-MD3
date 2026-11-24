package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.domain.model.readaloud.v4.RaIdentityDecision
import io.legado.app.domain.model.readaloud.v4.RaIdentityLink
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile

interface RaCharacterGateway {

    suspend fun characters(bookKey: String): List<RaCharacterView>

    suspend fun charactersRaw(bookKey: String): List<RaCharacter>

    suspend fun provisionalCharacters(bookKey: String): List<RaCharacter>

    suspend fun attributesOf(characterId: String): List<RaAttributeObservation>

    suspend fun relationsOf(characterId: String): List<RaRelationObservation>

    suspend fun character(id: String): RaCharacter?

    /** 跟随合并重定向得到最终人物 ID。 */
    suspend fun finalCharacterId(id: String): String

    suspend fun aliasesOf(characterId: String): List<RaCharacterAlias>

    suspend fun aliasesOfBook(bookKey: String): List<RaCharacterAlias> = emptyList()

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

    suspend fun attachAliasesIfAbsent(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    )

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

    /** AI 复审确认同人时的合并；默认等价于用户合并，假实现可只实现三参版本。 */
    suspend fun mergeCharactersAsAi(fromCharacterId: String, toCharacterId: String, reason: String) {
        mergeCharactersAsAi(fromCharacterId, toCharacterId, reason, emptyList(), "")
    }

    suspend fun mergeCharactersAsAi(
        fromCharacterId: String,
        toCharacterId: String,
        reason: String,
        evidenceIds: List<String>,
        decisionId: String,
    ) {
        mergeCharacters(fromCharacterId, toCharacterId, reason)
    }

    suspend fun updateStatus(characterId: String, status: String) {}

    suspend fun linksOfBook(bookKey: String): List<RaIdentityLink>

    suspend fun saveLink(link: RaIdentityLink)

    suspend fun saveCannotLink(
        bookKey: String,
        aCharacterId: String,
        bCharacterId: String,
        source: String,
        evidenceIds: List<String> = emptyList(),
    )

    suspend fun decisionsForCharacter(characterId: String): List<RaIdentityDecision>

    suspend fun saveAttribute(observation: RaAttributeObservation)

    suspend fun saveRelation(observation: RaRelationObservation)

    suspend fun saveDecision(
        bookKey: String,
        localSpeaker: RaLocalSpeaker,
        candidateIds: List<String>,
        verdict: String,
        chosenCharacterId: String?,
        confidence: Float,
        reason: String,
        modelId: String,
        evidenceIds: List<String> = emptyList(),
    ): String

    // ---- 发声方案 ----

    suspend fun voiceProfiles(bookKey: String): List<RaVoiceProfile>

    suspend fun voiceProfilesOf(characterId: String): List<RaVoiceProfile>

    suspend fun saveVoiceProfile(profile: RaVoiceProfile)

    suspend fun updateVoiceProfileVoice(profileId: String, voiceTag: String, voiceEntryId: String, source: String)

    suspend fun setVoiceProfileLocked(profileId: String, locked: Boolean)

    suspend fun deleteVoiceProfile(profileId: String)
}
