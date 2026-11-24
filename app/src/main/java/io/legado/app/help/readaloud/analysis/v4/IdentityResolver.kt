package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.gateway.GenericNameClassifier
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaCharacterStatus
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaVerdict

/**
 * V4-1 确定性最小解析器：
 *  - 非泛称称呼精确命中且唯一 ACTIVE → 链接，并把本章全部称呼补进别名表；
 *  - 多候选不新建：链接到最早出场的 ACTIVE，解析状态记 PROVISIONAL，写 UNKNOWN 裁决等 V4-2；
 *  - 无命中 → 新建 ACTIVE。
 * AI 三态裁决在 V4-2 接入；这里不扩原文窗口、不改写任何台词。
 */
class IdentityResolver(
    private val characters: RaCharacterGateway,
    private val genericNames: GenericNameClassifier,
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
            .distinct()
        val generic = names.filter { isGeneric(it) }.toSet()
        val normalizedNonGeneric = names
            .filterNot { it in generic }
            .map(RaNameUtils::normalize)
            .filter { it.isNotEmpty() }

        val hits: List<RaCharacterAlias> = if (normalizedNonGeneric.isEmpty()) {
            emptyList()
        } else {
            characters.charactersByAliases(bookKey, normalizedNonGeneric)
                .filterNot { it.isGeneric }
        }

        val candidates = LinkedHashMap<String, RaCharacter>()
        val active = LinkedHashMap<String, RaCharacter>()
        val provisional = LinkedHashMap<String, RaCharacter>()
        hits.forEach { hit ->
            val character = characters.character(hit.characterId) ?: return@forEach
            if (character.status == RaCharacterStatus.MERGED) return@forEach
            candidates[character.id] = character
            when (character.status) {
                RaCharacterStatus.ACTIVE -> active[character.id] = character
                RaCharacterStatus.PROVISIONAL -> provisional[character.id] = character
                RaCharacterStatus.MERGED -> Unit
            }
        }

        if (active.size == 1) {
            return link(bookKey, chapterIndex, speaker, names, active.values.first(), candidates, false)
        }
        if (active.isEmpty() && provisional.size == 1) {
            return link(bookKey, chapterIndex, speaker, names, provisional.values.first(), candidates, true)
        }
        if (active.size > 1) {
            // V4-1 不裁决：链接最早出场者，状态留 PROVISIONAL，等 V4-2 用证据修正
            val chosen = active.values.minWithOrNull(
                compareBy<RaCharacter> { it.firstChapter }.thenBy { it.id },
            ) ?: active.values.first()
            characters.saveDecision(
                bookKey = bookKey,
                localSpeaker = speaker,
                candidateIds = candidates.keys.toList(),
                verdict = RaVerdict.UNKNOWN.storageValue,
                chosenCharacterId = chosen.id,
                confidence = 0f,
                reason = "V4-1 多候选未裁决，暂链最早出场者",
                modelId = "",
            )
            return link(bookKey, chapterIndex, speaker, names, chosen, candidates, true)
        }
        if (provisional.size > 1) {
            val chosen = provisional.values.minWithOrNull(
                compareBy<RaCharacter> { it.firstChapter }.thenBy { it.id },
            ) ?: provisional.values.first()
            characters.saveDecision(
                bookKey = bookKey,
                localSpeaker = speaker,
                candidateIds = candidates.keys.toList(),
                verdict = RaVerdict.UNKNOWN.storageValue,
                chosenCharacterId = chosen.id,
                confidence = 0f,
                reason = "V4-1 多个临时人物未裁决，暂链最早出场者",
                modelId = "",
            )
            return link(bookKey, chapterIndex, speaker, names, chosen, candidates, true)
        }

        val created = create(bookKey, chapterIndex, speaker)
        return Resolution(created.id, RaResolution.RESOLVED, created = true)
    }

    private suspend fun link(
        bookKey: String,
        chapterIndex: Int,
        speaker: RaLocalSpeaker,
        names: List<String>,
        target: RaCharacter,
        candidates: Map<String, RaCharacter>,
        provisional: Boolean,
    ): Resolution {
        characters.attachAliasesIfAbsent(bookKey, target.id, names, chapterIndex, "ai")
        return Resolution(
            characterId = target.id,
            resolution = if (provisional) RaResolution.PROVISIONAL else RaResolution.RESOLVED,
            created = false,
            candidateIds = candidates.keys.toList(),
        )
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

    private suspend fun isGeneric(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.length <= 1) return true
        return genericNames.isGeneric(trimmed)
    }
}
