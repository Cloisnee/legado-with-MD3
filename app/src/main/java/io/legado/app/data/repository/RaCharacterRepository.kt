package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.readaloud.RaBuildDao
import io.legado.app.data.dao.readaloud.RaCharacterDao
import io.legado.app.data.entities.readaloud.RaAttributeObsEntity
import io.legado.app.data.entities.readaloud.RaCharacterAliasEntity
import io.legado.app.data.entities.readaloud.RaCharacterEntity
import io.legado.app.data.entities.readaloud.RaCharacterRedirectEntity
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaIdentityLinkEntity
import io.legado.app.data.entities.readaloud.RaRelationObsEntity
import io.legado.app.data.entities.readaloud.RaVoiceProfileEntity
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaAttributeObservation
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterStatus
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.domain.model.readaloud.v4.RaIdentityDecision
import io.legado.app.domain.model.readaloud.v4.RaIdentityLink
import io.legado.app.domain.model.readaloud.v4.RaIds
import io.legado.app.domain.model.readaloud.v4.RaLinkKind
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaRelationObservation
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaVerdict
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RaCharacterRepository(
    private val database: AppDatabase,
    private val dao: RaCharacterDao,
    private val buildDao: RaBuildDao,
    private val wordLibrary: WordLibraryRepository,
) : RaCharacterGateway {

    override suspend fun characters(bookKey: String): List<RaCharacterView> = withContext(Dispatchers.IO) {
        val characters = dao.characters(bookKey)
        if (characters.isEmpty()) return@withContext emptyList()
        val aliases = dao.aliasesOfBook(bookKey).groupBy { it.characterId }
        val profiles = dao.voiceProfilesOfBook(bookKey)
            .filter { it.kind == RaVoiceProfileKind.DEFAULT.storageValue }
            .associateBy { it.characterId }
        characters.map { character ->
            val aliasList = aliases[character.id].orEmpty()
                .sortedWith(compareBy({ it.isGeneric }, { it.firstChapter }))
            RaCharacterView(
                id = character.id,
                name = character.displayName,
                aliases = aliasList.joinToString("|") { it.surface },
                roletype = character.roleType,
                gender = character.gender,
                age = character.ageBand,
                voice = profiles[character.id]?.voiceTag.orEmpty(),
                lastAppearanceChapter = character.lastChapter,
                appearanceCount = character.appearanceCount,
                appearanceChapters = listOf(character.firstChapter, character.lastChapter)
                    .distinct()
                    .filter { it >= 0 },
                status = character.status,
            )
        }
    }

    override suspend fun charactersRaw(bookKey: String): List<RaCharacter> = withContext(Dispatchers.IO) {
        dao.characters(bookKey).map(RaCharacterEntity::toDomain)
    }

    override suspend fun provisionalCharacters(bookKey: String): List<RaCharacter> =
        withContext(Dispatchers.IO) {
            dao.provisionalCharacters(bookKey).map(RaCharacterEntity::toDomain)
        }

    override suspend fun attributesOf(characterId: String): List<RaAttributeObservation> =
        withContext(Dispatchers.IO) {
            dao.attributesOf(characterId).map(RaAttributeObsEntity::toDomain)
        }

    override suspend fun relationsOf(characterId: String): List<RaRelationObservation> =
        withContext(Dispatchers.IO) {
            dao.relationsOf(characterId).map(RaRelationObsEntity::toDomain)
        }

    override suspend fun character(id: String): RaCharacter? = withContext(Dispatchers.IO) {
        dao.character(id)?.toDomain()
    }

    override suspend fun finalCharacterId(id: String): String = withContext(Dispatchers.IO) {
        var current = id
        repeat(8) {
            val redirect = dao.redirect(current) ?: return@withContext current
            current = redirect.toCharacterId
        }
        current
    }

    override suspend fun aliasesOf(characterId: String): List<RaCharacterAlias> =
        withContext(Dispatchers.IO) {
            dao.aliasesOf(characterId).map(RaCharacterAliasEntity::toDomain)
        }

    override suspend fun aliasesOfBook(bookKey: String): List<RaCharacterAlias> =
        withContext(Dispatchers.IO) {
            dao.aliasesOfBook(bookKey).map(RaCharacterAliasEntity::toDomain)
        }

    override suspend fun charactersByAliases(
        bookKey: String,
        normalizedNames: Collection<String>,
    ): List<RaCharacterAlias> = withContext(Dispatchers.IO) {
        val names = normalizedNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return@withContext emptyList()
        dao.aliasesByNormalized(bookKey, names).map(RaCharacterAliasEntity::toDomain)
    }

    override suspend fun ensureCharacter(
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
    ): RaCharacter = withContext(Dispatchers.IO) {
        val baseName = displayName.trim()
        val effectiveName = if (roleType == "路人" && !baseName.contains("【第")) {
            baseName + "【第${chapterIndex + 1}章】"
        } else {
            baseName
        }
        val now = System.currentTimeMillis()
        val entity = RaCharacterEntity(
            id = RaIds.character(),
            bookKey = bookKey,
            displayName = effectiveName,
            roleType = roleType.ifBlank { "核心" },
            gender = gender.ifBlank { "男" },
            ageBand = ageBand.ifBlank { "男青年" },
            status = status,
            firstChapter = chapterIndex,
            lastChapter = chapterIndex,
            appearanceCount = 1,
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.upsertCharacter(entity)
            val surfaces = LinkedHashSet<String>()
            aliases.map { it.trim() }.filter { it.isNotEmpty() }.forEach(surfaces::add)
            if (baseName.isNotEmpty()) surfaces.add(baseName)
            surfaces.forEach { surface ->
                val normalized = RaNameUtils.normalize(surface)
                if (normalized.isEmpty()) return@forEach
                dao.upsertAliases(
                    listOf(
                        RaCharacterAliasEntity(
                            bookKey = bookKey,
                            normalized = normalized,
                            characterId = entity.id,
                            surface = surface,
                            source = aliasSource,
                            isGeneric = wordLibrary.isBareClassWord(surface) || surface.length <= 1,
                            firstChapter = chapterIndex,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                )
            }
            if (gender.isNotBlank() && attributeSource != "default") {
                dao.insertAttribute(
                    RaAttributeObsEntity(
                        id = "att_${entity.id}_gender_$chapterIndex",
                        bookKey = bookKey,
                        characterId = entity.id,
                        key = "gender",
                        value = gender,
                        fromChapter = chapterIndex,
                        source = attributeSource,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            if (ageBand.isNotBlank() && attributeSource != "default") {
                dao.insertAttribute(
                    RaAttributeObsEntity(
                        id = "att_${entity.id}_age_$chapterIndex",
                        bookKey = bookKey,
                        characterId = entity.id,
                        key = "ageBand",
                        value = ageBand,
                        fromChapter = chapterIndex,
                        source = attributeSource,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
        entity.toDomain()
    }

    override suspend fun attachAliasesIfAbsent(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = ArrayList<RaCharacterAliasEntity>()
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { surface ->
            val normalized = RaNameUtils.normalize(surface)
            if (normalized.isEmpty()) return@forEach
            rows.add(
                RaCharacterAliasEntity(
                    bookKey = bookKey,
                    normalized = normalized,
                    characterId = characterId,
                    surface = surface,
                    source = source,
                    isGeneric = wordLibrary.isBareClassWord(surface) || surface.length <= 1,
                    firstChapter = chapterIndex,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (rows.isNotEmpty()) dao.insertAliasesIgnore(rows)
    }

    override suspend fun attachAliases(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rows = ArrayList<RaCharacterAliasEntity>()
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { surface ->
            val normalized = RaNameUtils.normalize(surface)
            if (normalized.isEmpty()) return@forEach
            rows.add(
                RaCharacterAliasEntity(
                    bookKey = bookKey,
                    normalized = normalized,
                    characterId = characterId,
                    surface = surface,
                    source = source,
                    isGeneric = wordLibrary.isBareClassWord(surface) || surface.length <= 1,
                    firstChapter = chapterIndex,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (rows.isNotEmpty()) dao.upsertAliases(rows)
    }

    override suspend fun replaceAliases(
        bookKey: String,
        characterId: String,
        names: Collection<String>,
        chapterIndex: Int,
        source: String,
    ) = withContext(Dispatchers.IO) {
        val desired = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val desiredNormalized = desired.map(RaNameUtils::normalize).toSet()
        val current = dao.aliasesOf(characterId)
        current.forEach { alias ->
            if (alias.normalized !in desiredNormalized) dao.deleteAlias(characterId, alias.normalized)
        }
        attachAliases(bookKey, characterId, desired, chapterIndex, source)
    }

    override suspend fun setDefaultVoice(
        characterId: String,
        voiceTag: String,
        voiceEntryId: String,
    ) = withContext(Dispatchers.IO) {
        val character = dao.character(characterId) ?: return@withContext
        val now = System.currentTimeMillis()
        val profiles = dao.voiceProfilesOf(characterId)
        val default = profiles.firstOrNull { it.kind == RaVoiceProfileKind.DEFAULT.storageValue }
        if (default != null) {
            dao.updateVoiceProfileVoice(default.id, voiceTag, voiceEntryId, "user", now)
        } else {
            dao.upsertVoiceProfile(
                RaVoiceProfileEntity(
                    id = RaIds.voiceProfile(),
                    bookKey = character.bookKey,
                    characterId = characterId,
                    kind = RaVoiceProfileKind.DEFAULT.storageValue,
                    label = "默认",
                    castingKey = "${character.roleType}|${character.gender}|${character.ageBand}",
                    voiceTag = voiceTag,
                    voiceEntryId = voiceEntryId,
                    source = "user",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun recomputeAppearance(characterId: String) = withContext(Dispatchers.IO) {
        val character = dao.character(characterId) ?: return@withContext
        val aggregate = buildDao.appearanceOf(characterId)
        dao.refreshAppearance(
            id = characterId,
            firstChapter = aggregate.firstChapter ?: character.firstChapter,
            lastChapter = aggregate.lastChapter ?: character.lastChapter,
            appearanceCount = aggregate.chapterCount,
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun updateDisplayName(characterId: String, name: String) =
        withContext(Dispatchers.IO) {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@withContext
            dao.updateDisplayName(characterId, trimmed, System.currentTimeMillis())
        }

    override suspend fun updateAttributes(
        characterId: String,
        roleType: String,
        gender: String,
        ageBand: String,
    ) = withContext(Dispatchers.IO) {
        val character = dao.character(characterId) ?: return@withContext
        val now = System.currentTimeMillis()
        if (roleType.isNotBlank() && roleType != character.roleType) {
            dao.updateRoleType(characterId, roleType, now)
        }
        if (gender.isNotBlank() && gender != character.gender) {
            dao.updateGender(characterId, gender, now)
            dao.insertAttribute(
                RaAttributeObsEntity(
                    id = "att_${characterId}_gender_${now}",
                    bookKey = character.bookKey,
                    characterId = characterId,
                    key = "gender",
                    value = gender,
                    fromChapter = character.lastChapter,
                    source = "user",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (ageBand.isNotBlank() && ageBand != character.ageBand) {
            dao.updateAgeBand(characterId, ageBand, now)
            dao.insertAttribute(
                RaAttributeObsEntity(
                    id = "att_${characterId}_age_${now}",
                    bookKey = character.bookKey,
                    characterId = characterId,
                    key = "ageBand",
                    value = ageBand,
                    fromChapter = character.lastChapter,
                    source = "user",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    override suspend fun deleteCharacters(characterIds: Collection<String>) =
        withContext(Dispatchers.IO) {
            if (characterIds.isEmpty()) return@withContext
            val now = System.currentTimeMillis()
            database.withTransaction {
                characterIds.forEach { id ->
                    val character = dao.character(id)
                    dao.deleteRedirectsForCharacter(id)
                    dao.deleteDecisionsByChosenCharacter(id)
                    character?.let { dao.deleteLinksForCharacter(it.bookKey, id) }
                    dao.voiceProfilesOf(id).forEach { dao.deleteVoiceProfile(it.id) }
                    dao.deleteAttributes(id)
                    dao.deleteRelations(id)
                    dao.deleteAliasesOfCharacter(id)
                    buildDao.clearSegmentCharacter(id, now)
                    dao.deleteCharacter(id)
                }
            }
        }

    override suspend fun mergeCharacters(
        fromCharacterId: String,
        toCharacterId: String,
        reason: String,
    ) = mergeCharactersInternal(fromCharacterId, toCharacterId, reason, "user")

    override suspend fun mergeCharactersAsAi(
        fromCharacterId: String,
        toCharacterId: String,
        reason: String,
    ) = mergeCharactersInternal(fromCharacterId, toCharacterId, reason, "ai")

    private suspend fun mergeCharactersInternal(
        fromCharacterId: String,
        toCharacterId: String,
        reason: String,
        source: String,
    ) = withContext(Dispatchers.IO) {
            val from = finalCharacterId(fromCharacterId)
            val to = finalCharacterId(toCharacterId)
            if (from == to) return@withContext
            val fromCharacter = dao.character(from) ?: return@withContext
            val toCharacter = dao.character(to) ?: return@withContext
            val now = System.currentTimeMillis()
            database.withTransaction {
                // 1. 台词与章内说话人外键
                buildDao.reassignSegmentCharacter(from, to, now)
                buildDao.reassignLocalSpeakerCharacter(from, to, now)
                // 2. 别名迁移（同 normalized 已存在时删除来源行）
                val targetNormalized = dao.aliasesOf(to).map { it.normalized }.toSet()
                dao.aliasesOf(from).forEach { alias ->
                    if (alias.normalized in targetNormalized) {
                        dao.deleteAlias(from, alias.normalized)
                    } else {
                        dao.moveAlias(fromCharacter.bookKey, alias.normalized, from, to, now)
                    }
                }
                // 3. 观察迁移
                dao.reassignAttributes(from, to, now)
                dao.reassignRelations(from, to, now)
                dao.reassignRelationTargets(from, to, now)
                // 4. 发声方案冲突（01 §6.4）
                mergeVoiceProfiles(from, to, fromCharacter.displayName, now)
                // 5. 重定向与状态
                dao.upsertRedirect(
                    RaCharacterRedirectEntity(
                        fromCharacterId = from,
                        toCharacterId = to,
                        reason = reason,
                        createdAt = now,
                    ),
                )
                dao.reassignRedirects(from, to, now)
                dao.updateStatus(from, RaCharacterStatus.MERGED.storageValue, now)
                dao.upsertCharacter(
                    toCharacter.copy(
                        firstChapter = minOf(fromCharacter.firstChapter, toCharacter.firstChapter),
                        lastChapter = maxOf(fromCharacter.lastChapter, toCharacter.lastChapter),
                        userEdited = fromCharacter.userEdited || toCharacter.userEdited,
                        updatedAt = now,
                    ),
                )
                dao.bumpIdentityRevision(to, now)
                // 6. 先迁移 from 的其它约束，清掉迁移产生的自环，再记录本次合并的 SAME
                dao.reassignLinksA(from, to, now)
                dao.reassignLinksB(from, to, now)
                dao.deleteSelfLinks(toCharacter.bookKey)
                val a = minOf(from, to)
                val b = maxOf(from, to)
                dao.upsertLink(
                    RaIdentityLinkEntity(
                        id = "link_${a}_${b}_SAME",
                        bookKey = toCharacter.bookKey,
                        aCharacterId = a,
                        bCharacterId = b,
                        kind = RaLinkKind.SAME.storageValue,
                        source = source,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            recomputeAppearance(to)
        }

    override suspend fun updateStatus(characterId: String, status: String) =
        withContext(Dispatchers.IO) {
            dao.updateStatus(characterId, status, System.currentTimeMillis())
        }

    private suspend fun mergeVoiceProfiles(
        fromCharacterId: String,
        toCharacterId: String,
        fromDisplayName: String,
        now: Long,
    ) {
        val fromProfiles = dao.voiceProfilesOf(fromCharacterId)
        val toProfiles = dao.voiceProfilesOf(toCharacterId)
        val fromDefault = fromProfiles.firstOrNull { it.kind == RaVoiceProfileKind.DEFAULT.storageValue }
        val toDefault = toProfiles.firstOrNull { it.kind == RaVoiceProfileKind.DEFAULT.storageValue }
        fromProfiles.filter { it.kind != RaVoiceProfileKind.DEFAULT.storageValue }.forEach {
            dao.upsertVoiceProfile(it.copy(characterId = toCharacterId, updatedAt = now))
        }
        when {
            fromDefault == null -> Unit
            toDefault == null -> dao.upsertVoiceProfile(
                fromDefault.copy(characterId = toCharacterId, updatedAt = now),
            )
            fromDefault.locked && !toDefault.locked -> {
                dao.deleteVoiceProfile(toDefault.id)
                dao.upsertVoiceProfile(fromDefault.copy(characterId = toCharacterId, updatedAt = now))
            }
            fromDefault.locked && toDefault.locked -> {
                dao.upsertVoiceProfile(
                    fromDefault.copy(
                        characterId = toCharacterId,
                        kind = RaVoiceProfileKind.CUSTOM.storageValue,
                        label = "合并前·$fromDisplayName",
                        updatedAt = now,
                    ),
                )
            }
            toDefault.locked -> dao.deleteVoiceProfile(fromDefault.id)
            else -> {
                // 都未锁：保留最早创建的（先被听到的声音）
                if (fromDefault.createdAt < toDefault.createdAt) {
                    dao.deleteVoiceProfile(toDefault.id)
                    dao.upsertVoiceProfile(fromDefault.copy(characterId = toCharacterId, updatedAt = now))
                } else {
                    dao.deleteVoiceProfile(fromDefault.id)
                }
            }
        }
    }

    override suspend fun linksOfBook(bookKey: String): List<RaIdentityLink> =
        withContext(Dispatchers.IO) {
            dao.linksOfBook(bookKey).map(RaIdentityLinkEntity::toDomain)
        }

    override suspend fun saveLink(link: RaIdentityLink) = withContext(Dispatchers.IO) {
        val a = minOf(link.aCharacterId, link.bCharacterId)
        val b = maxOf(link.aCharacterId, link.bCharacterId)
        if (a == b) return@withContext
        dao.upsertLink(
            RaIdentityLinkEntity(
                id = link.id.ifBlank { "link_${a}_${b}_${link.kind.storageValue}" },
                bookKey = link.bookKey,
                aCharacterId = a,
                bCharacterId = b,
                kind = link.kind.storageValue,
                source = link.source,
                evidenceIds = listToJsonArray(link.evidenceIds),
                decisionId = link.decisionId,
                createdAt = link.createdAt,
                updatedAt = link.updatedAt,
            ),
        )
    }

    override suspend fun saveCannotLink(
        bookKey: String,
        aCharacterId: String,
        bCharacterId: String,
        source: String,
        evidenceIds: List<String>,
    ) = withContext(Dispatchers.IO) {
        val a = minOf(aCharacterId, bCharacterId)
        val b = maxOf(aCharacterId, bCharacterId)
        if (a == b) return@withContext
        val now = System.currentTimeMillis()
        dao.upsertLink(
            RaIdentityLinkEntity(
                id = "link_${a}_${b}_CANNOT",
                bookKey = bookKey,
                aCharacterId = a,
                bCharacterId = b,
                kind = RaLinkKind.CANNOT.storageValue,
                source = source,
                evidenceIds = listToJsonArray(evidenceIds),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun decisionsForCharacter(characterId: String): List<RaIdentityDecision> =
        withContext(Dispatchers.IO) {
            dao.decisionsForCharacter(characterId).map(RaIdentityDecisionEntity::toDomain)
        }

    override suspend fun saveAttribute(observation: RaAttributeObservation) =
        withContext(Dispatchers.IO) {
            dao.insertAttribute(observation.toEntity())
        }

    override suspend fun saveRelation(observation: RaRelationObservation) =
        withContext(Dispatchers.IO) {
            dao.insertRelation(observation.toEntity())
        }

    override suspend fun saveDecision(
        bookKey: String,
        localSpeaker: RaLocalSpeaker,
        candidateIds: List<String>,
        verdict: String,
        chosenCharacterId: String?,
        confidence: Float,
        reason: String,
        modelId: String,
        evidenceIds: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val id = RaIds.decision()
        dao.insertDecision(
            RaIdentityDecisionEntity(
                id = id,
                bookKey = bookKey,
                localSpeakerId = localSpeaker.id,
                candidateIds = listToJsonArray(candidateIds),
                verdict = verdict,
                chosenCharacterId = chosenCharacterId,
                confidence = confidence,
                reason = reason.take(200),
                evidenceIds = listToJsonArray(evidenceIds),
                modelId = modelId,
            ),
        )
        id
    }

    override suspend fun voiceProfiles(bookKey: String): List<RaVoiceProfile> =
        withContext(Dispatchers.IO) {
            dao.voiceProfilesOfBook(bookKey).map(RaVoiceProfileEntity::toDomain)
        }

    override suspend fun voiceProfilesOf(characterId: String): List<RaVoiceProfile> =
        withContext(Dispatchers.IO) {
            dao.voiceProfilesOf(characterId).map(RaVoiceProfileEntity::toDomain)
        }

    override suspend fun saveVoiceProfile(profile: RaVoiceProfile) = withContext(Dispatchers.IO) {
        dao.upsertVoiceProfile(profile.toEntity())
    }

    override suspend fun updateVoiceProfileVoice(
        profileId: String,
        voiceTag: String,
        voiceEntryId: String,
        source: String,
    ) = withContext(Dispatchers.IO) {
        dao.updateVoiceProfileVoice(
            profileId,
            voiceTag,
            voiceEntryId,
            source,
            System.currentTimeMillis(),
        )
    }

    override suspend fun setVoiceProfileLocked(profileId: String, locked: Boolean) =
        withContext(Dispatchers.IO) {
            dao.updateVoiceProfileLocked(profileId, locked, System.currentTimeMillis())
        }

    override suspend fun deleteVoiceProfile(profileId: String) = withContext(Dispatchers.IO) {
        dao.deleteVoiceProfile(profileId)
    }
}

internal fun RaCharacterEntity.toDomain() = RaCharacter(
    id = id,
    bookKey = bookKey,
    displayName = displayName,
    roleType = roleType,
    gender = gender,
    ageBand = ageBand,
    status = RaCharacterStatus.fromStorage(status),
    firstChapter = firstChapter,
    lastChapter = lastChapter,
    appearanceCount = appearanceCount,
    userEdited = userEdited,
    identityRevision = identityRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaCharacterAliasEntity.toDomain() = RaCharacterAlias(
    bookKey = bookKey,
    normalized = normalized,
    characterId = characterId,
    surface = surface,
    source = source,
    isGeneric = isGeneric,
    firstChapter = firstChapter,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaVoiceProfileEntity.toDomain() = RaVoiceProfile(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    kind = RaVoiceProfileKind.fromStorage(kind),
    label = label,
    castingKey = castingKey,
    voiceTag = voiceTag,
    voiceEntryId = voiceEntryId,
    rangeJson = rangeJson,
    locked = locked,
    source = source,
    voiceRevision = voiceRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaVoiceProfile.toEntity() = RaVoiceProfileEntity(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    kind = kind.storageValue,
    label = label,
    castingKey = castingKey,
    voiceTag = voiceTag,
    voiceEntryId = voiceEntryId,
    rangeJson = rangeJson,
    locked = locked,
    source = source,
    voiceRevision = voiceRevision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaIdentityLinkEntity.toDomain() = RaIdentityLink(
    id = id,
    bookKey = bookKey,
    aCharacterId = aCharacterId,
    bCharacterId = bCharacterId,
    kind = RaLinkKind.fromStorage(kind),
    source = source,
    evidenceIds = jsonArrayToList(evidenceIds),
    decisionId = decisionId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaIdentityDecisionEntity.toDomain() = RaIdentityDecision(
    id = id,
    bookKey = bookKey,
    localSpeakerId = localSpeakerId,
    candidateIds = jsonArrayToList(candidateIds),
    verdict = RaVerdict.fromStorage(verdict),
    chosenCharacterId = chosenCharacterId,
    confidence = confidence,
    reason = reason,
    evidenceIds = jsonArrayToList(evidenceIds),
    modelId = modelId,
    createdAt = createdAt,
)

internal fun RaAttributeObsEntity.toDomain() = RaAttributeObservation(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    key = key,
    value = value,
    fromChapter = fromChapter,
    evidenceId = evidenceId,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaAttributeObservation.toEntity() = RaAttributeObsEntity(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    key = key,
    value = value,
    fromChapter = fromChapter,
    evidenceId = evidenceId,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaRelationObsEntity.toDomain() = RaRelationObservation(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    relation = relation,
    targetCharacterId = targetCharacterId,
    targetText = targetText,
    fromChapter = fromChapter,
    evidenceId = evidenceId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RaRelationObservation.toEntity() = RaRelationObsEntity(
    id = id,
    bookKey = bookKey,
    characterId = characterId,
    relation = relation,
    targetCharacterId = targetCharacterId,
    targetText = targetText,
    fromChapter = fromChapter,
    evidenceId = evidenceId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
