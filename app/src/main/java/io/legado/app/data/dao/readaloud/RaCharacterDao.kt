package io.legado.app.data.dao.readaloud

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.readaloud.RaAttributeObsEntity
import io.legado.app.data.entities.readaloud.RaCharacterAliasEntity
import io.legado.app.data.entities.readaloud.RaCharacterEntity
import io.legado.app.data.entities.readaloud.RaCharacterRedirectEntity
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaIdentityLinkEntity
import io.legado.app.data.entities.readaloud.RaRelationObsEntity
import io.legado.app.data.entities.readaloud.RaVoiceProfileEntity

@Dao
interface RaCharacterDao {


    // ---------------- 删除清理（B5 / Q5） ----------------

    @Query("select r.* from ra_character_redirect r inner join ra_character c on c.id = r.fromCharacterId where c.bookKey = :bookKey")
    suspend fun redirectsOfBook(bookKey: String): List<RaCharacterRedirectEntity>

    @Query("delete from ra_character_redirect where fromCharacterId = :characterId or toCharacterId = :characterId")
    suspend fun deleteRedirectsForCharacter(characterId: String)

    @Query("delete from ra_character_redirect where fromCharacterId in (select id from ra_character where bookKey = :bookKey) or toCharacterId in (select id from ra_character where bookKey = :bookKey)")
    suspend fun deleteRedirectsOfBook(bookKey: String)

    @Query("delete from ra_identity_link where bookKey = :bookKey and (aCharacterId = :characterId or bCharacterId = :characterId)")
    suspend fun deleteLinksForCharacter(bookKey: String, characterId: String)

    @Query("delete from ra_identity_decision where localSpeakerId in (:localSpeakerIds)")
    suspend fun deleteDecisionsByLocalSpeakerIds(localSpeakerIds: List<String>)

    @Query("delete from ra_identity_decision where chosenCharacterId = :characterId")
    suspend fun deleteDecisionsByChosenCharacter(characterId: String)

    @Query("delete from ra_identity_decision where bookKey = :bookKey")
    suspend fun deleteDecisionsOfBook(bookKey: String)

    // ---------------- 人物 ----------------

    @Query("select * from ra_character where bookKey = :bookKey and status != 'MERGED' order by appearanceCount desc, firstChapter")
    suspend fun characters(bookKey: String): List<RaCharacterEntity>

    @Query("select * from ra_character where bookKey = :bookKey order by appearanceCount desc, firstChapter")
    suspend fun allCharacters(bookKey: String): List<RaCharacterEntity>

    @Query("select * from ra_character where id = :id limit 1")
    suspend fun character(id: String): RaCharacterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacter(entity: RaCharacterEntity)

    @Query("update ra_character set displayName = :name, userEdited = 1, identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateDisplayName(id: String, name: String, updatedAt: Long)

    @Query("update ra_character set roleType = :roleType, userEdited = 1, identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateRoleType(id: String, roleType: String, updatedAt: Long)

    @Query("update ra_character set gender = :gender, userEdited = 1, identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateGender(id: String, gender: String, updatedAt: Long)

    @Query("update ra_character set ageBand = :ageBand, userEdited = 1, identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateAgeBand(id: String, ageBand: String, updatedAt: Long)

    @Query("update ra_character set status = :status, identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("update ra_character set identityRevision = identityRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun bumpIdentityRevision(id: String, updatedAt: Long)

    @Query("update ra_character set firstChapter = :firstChapter, lastChapter = :lastChapter, appearanceCount = :appearanceCount, updatedAt = :updatedAt where id = :id")
    suspend fun refreshAppearance(
        id: String,
        firstChapter: Int,
        lastChapter: Int,
        appearanceCount: Int,
        updatedAt: Long,
    )

    @Query("delete from ra_character where id = :id")
    suspend fun deleteCharacter(id: String)

    @Query("select * from ra_character where bookKey = :bookKey and status = 'ACTIVE'")
    suspend fun activeCharacters(bookKey: String): List<RaCharacterEntity>

    // ---------------- 别名 ----------------

    @Query("select * from ra_character_alias where characterId = :characterId order by isGeneric, firstChapter")
    suspend fun aliasesOf(characterId: String): List<RaCharacterAliasEntity>

    @Query("select * from ra_character_alias where bookKey = :bookKey")
    suspend fun aliasesOfBook(bookKey: String): List<RaCharacterAliasEntity>

    @Query("select * from ra_character_alias where bookKey = :bookKey and normalized in (:normalized)")
    suspend fun aliasesByNormalized(bookKey: String, normalized: List<String>): List<RaCharacterAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(entities: List<RaCharacterAliasEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAliasesIgnore(entities: List<RaCharacterAliasEntity>)

    @Query("delete from ra_character_alias where characterId = :characterId and normalized = :normalized")
    suspend fun deleteAlias(characterId: String, normalized: String)

    @Query("update ra_character_alias set characterId = :toCharacterId, updatedAt = :updatedAt where characterId = :fromCharacterId")
    suspend fun reassignAliases(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("update ra_character_alias set characterId = :toCharacterId, updatedAt = :updatedAt where bookKey = :bookKey and normalized = :normalized and characterId = :fromCharacterId")
    suspend fun moveAlias(bookKey: String, normalized: String, fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("delete from ra_character_alias where bookKey = :bookKey")
    suspend fun deleteAliasesOfBook(bookKey: String)

    @Query("delete from ra_character_alias where characterId = :characterId")
    suspend fun deleteAliasesOfCharacter(characterId: String)

    // ---------------- 属性/关系观察 ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttribute(entity: RaAttributeObsEntity)

    @Query("select * from ra_attribute_obs where characterId = :characterId order by fromChapter, createdAt")
    suspend fun attributesOf(characterId: String): List<RaAttributeObsEntity>

    @Query("select * from ra_attribute_obs where bookKey = :bookKey")
    suspend fun attributesOfBook(bookKey: String): List<RaAttributeObsEntity>

    @Query("update ra_attribute_obs set characterId = :toCharacterId, updatedAt = :updatedAt where characterId = :fromCharacterId")
    suspend fun reassignAttributes(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("delete from ra_attribute_obs where characterId = :characterId")
    suspend fun deleteAttributes(characterId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelation(entity: RaRelationObsEntity)

    @Query("select * from ra_relation_obs where characterId = :characterId order by fromChapter")
    suspend fun relationsOf(characterId: String): List<RaRelationObsEntity>

    @Query("select * from ra_relation_obs where bookKey = :bookKey")
    suspend fun relationsOfBook(bookKey: String): List<RaRelationObsEntity>

    @Query("update ra_relation_obs set characterId = :toCharacterId, updatedAt = :updatedAt where characterId = :fromCharacterId")
    suspend fun reassignRelations(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("update ra_relation_obs set targetCharacterId = :toCharacterId, updatedAt = :updatedAt where targetCharacterId = :fromCharacterId")
    suspend fun reassignRelationTargets(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("delete from ra_relation_obs where characterId = :characterId")
    suspend fun deleteRelations(characterId: String)

    // ---------------- 同人/非同人约束 ----------------

    @Query("select * from ra_identity_link where bookKey = :bookKey and (aCharacterId = :characterId or bCharacterId = :characterId)")
    suspend fun linksOf(bookKey: String, characterId: String): List<RaIdentityLinkEntity>

    @Query("select * from ra_identity_link where bookKey = :bookKey")
    suspend fun linksOfBook(bookKey: String): List<RaIdentityLinkEntity>

    @Query("select * from ra_identity_link where bookKey = :bookKey and aCharacterId = :a and bCharacterId = :b limit 1")
    suspend fun linkBetween(bookKey: String, a: String, b: String): RaIdentityLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(entity: RaIdentityLinkEntity)

    @Query("update ra_identity_link set bCharacterId = :toCharacterId, updatedAt = :updatedAt where bCharacterId = :fromCharacterId")
    suspend fun reassignLinksB(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("update ra_identity_link set aCharacterId = :toCharacterId, updatedAt = :updatedAt where aCharacterId = :fromCharacterId")
    suspend fun reassignLinksA(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("delete from ra_identity_link where bookKey = :bookKey and aCharacterId = bCharacterId")
    suspend fun deleteSelfLinks(bookKey: String)

    @Query("delete from ra_identity_link where bookKey = :bookKey")
    suspend fun deleteLinksOfBook(bookKey: String)

    // ---------------- 裁决记录 ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDecision(entity: RaIdentityDecisionEntity)

    @Query("select * from ra_identity_decision where localSpeakerId = :localSpeakerId order by createdAt desc")
    suspend fun decisionsOf(localSpeakerId: String): List<RaIdentityDecisionEntity>

    @Query("select * from ra_identity_decision where bookKey = :bookKey")
    suspend fun decisionsOfBook(bookKey: String): List<RaIdentityDecisionEntity>

    // ---------------- 重定向 ----------------

    @Query("select * from ra_character_redirect where fromCharacterId = :from limit 1")
    suspend fun redirect(from: String): RaCharacterRedirectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRedirect(entity: RaCharacterRedirectEntity)

    @Query("update ra_character_redirect set toCharacterId = :toCharacterId, createdAt = :createdAt where toCharacterId = :fromCharacterId")
    suspend fun reassignRedirects(fromCharacterId: String, toCharacterId: String, createdAt: Long)

    @Query("delete from ra_character_redirect where fromCharacterId = :from")
    suspend fun deleteRedirect(from: String)

    // ---------------- 发声方案 ----------------

    @Query("select * from ra_voice_profile where characterId = :characterId order by kind, createdAt")
    suspend fun voiceProfilesOf(characterId: String): List<RaVoiceProfileEntity>

    @Query("select * from ra_voice_profile where bookKey = :bookKey")
    suspend fun voiceProfilesOfBook(bookKey: String): List<RaVoiceProfileEntity>

    @Query("select * from ra_voice_profile where id = :id limit 1")
    suspend fun voiceProfile(id: String): RaVoiceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVoiceProfile(entity: RaVoiceProfileEntity)

    @Query("update ra_voice_profile set voiceTag = :voiceTag, voiceEntryId = :voiceEntryId, source = :source, voiceRevision = voiceRevision + 1, updatedAt = :updatedAt where id = :id")
    suspend fun updateVoiceProfileVoice(
        id: String,
        voiceTag: String,
        voiceEntryId: String,
        source: String,
        updatedAt: Long,
    )

    @Query("update ra_voice_profile set locked = :locked, updatedAt = :updatedAt where id = :id")
    suspend fun updateVoiceProfileLocked(id: String, locked: Boolean, updatedAt: Long)

    @Query("update ra_voice_profile set characterId = :toCharacterId, updatedAt = :updatedAt where characterId = :fromCharacterId")
    suspend fun reassignVoiceProfiles(fromCharacterId: String, toCharacterId: String, updatedAt: Long)

    @Query("delete from ra_voice_profile where id = :id")
    suspend fun deleteVoiceProfile(id: String)

    @Query("delete from ra_voice_profile where characterId = :characterId")
    suspend fun deleteVoiceProfilesOfCharacter(characterId: String)

    @Query("delete from ra_voice_profile where bookKey = :bookKey")
    suspend fun deleteVoiceProfilesOfBook(bookKey: String)
}
