package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterStatus
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaVerdict
import io.legado.app.data.repository.WordLibraryRepository

/**
 * V4-1 确定性最小解析器：名字/别名精确命中且唯一 → 链接；否则新建。
 * AI 三态裁决在 V4-2 接入；这里不扩原文窗口、不改写任何台词。
 */
class IdentityResolver(
    private val characters: RaCharacterGateway,
    private val wordLibrary: WordLibraryRepository,
) {

    data class Resolution(
        val characterId: String,
        val resolution: RaResolution,
        val created: Boolean,
        val candidateIds: List<String> = emptyList(),
    )

    suspend fun resolve(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
    ): Resolution {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val normalized = RaNameUtils.normalizeAll(names)
        val genericNames = names.filter { isGeneric(it) }.toSet()
        val normalizedNonGeneric = names
            .filterNot { it in genericNames }
            .map(RaNameUtils::normalize)
            .filter { it.isNotEmpty() }

        val hits: List<RaCharacterAlias> = if (normalizedNonGeneric.isEmpty()) {
            emptyList()
        } else {
            characters.charactersByAliases(bookKey, normalizedNonGeneric)
                .filterNot { it.isGeneric }
        }

        // 一个 normal 可能同时命中多个人物；按 characterId 去重并排除已合并/未知人物
        val candidateIds = LinkedHashSet<String>()
        val activeIds = LinkedHashSet<String>()
        val provisionalIds = LinkedHashSet<String>()
        hits.forEach { hit ->
            val character = characters.character(hit.characterId) ?: return@forEach
            if (character.status == RaCharacterStatus.MERGED) return@forEach
            candidateIds.add(character.id)
            when (character.status) {
                RaCharacterStatus.ACTIVE -> activeIds.add(character.id)
                RaCharacterStatus.PROVISIONAL -> provisionalIds.add(character.id)
                RaCharacterStatus.MERGED -> Unit
            }
        }

        if (activeIds.size == 1) {
            val id = activeIds.first()
            characters.touchAppearance(id, chapterIndex)
            return Resolution(id, RaResolution.RESOLVED, created = false, candidateIds = candidateIds.toList())
        }
        if (activeIds.isEmpty() && provisionalIds.size == 1) {
            val id = provisionalIds.first()
            characters.touchAppearance(id, chapterIndex)
            return Resolution(id, RaResolution.PROVISIONAL, created = false, candidateIds = candidateIds.toList())
        }
        if (activeIds.size > 1) {
            // V4-1 不裁决：先建临时人物，证据足够后再合并
            val created = create(bookKey, chapterIndex, speaker)
            characters.saveDecision(
                bookKey = bookKey,
                localSpeaker = speaker,
                candidateIds = candidateIds.toList(),
                verdict = RaVerdict.UNKNOWN.storageValue,
                chosenCharacterId = null,
                confidence = 0f,
                reason = "V4-1 多候选未裁决",
                modelId = "",
            )
            return Resolution(created.id, RaResolution.PROVISIONAL, created = true, candidateIds = candidateIds.toList())
        }

        val created = create(bookKey, chapterIndex, speaker)
        return Resolution(created.id, RaResolution.RESOLVED, created = true)
    }

    private suspend fun create(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
    ): RaCharacter {
        val names = (speaker.surfaceNames + speaker.primaryName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return characters.ensureCharacter(
            bookKey = bookKey,
            displayName = speaker.primaryName.ifBlank { names.firstOrNull().orEmpty() },
            roleType = speaker.roleType.ifBlank { "核心" },
            gender = speaker.gender.ifBlank { "男" },
            ageBand = speaker.ageBand.ifBlank { "男青年" },
            chapterIndex = chapterIndex,
            aliases = names,
            status = RaCharacterStatus.ACTIVE.storageValue,
            aliasSource = "ai",
            attributeSource = "inferred",
        )
    }

    suspend fun isGeneric(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length <= 1) return true
        return wordLibrary.isBareClassWord(trimmed)
    }
}
