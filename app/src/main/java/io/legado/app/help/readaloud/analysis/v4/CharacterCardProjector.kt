package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.v4.RaCardEvidenceRef
import io.legado.app.domain.model.readaloud.v4.RaCardRelationFact
import io.legado.app.domain.model.readaloud.v4.RaCardTimelineFact
import io.legado.app.domain.model.readaloud.v4.RaCardVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaCharacterCard
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType

/**
 * 人物卡投影（01 §7）。纯程序，无 AI。
 *
 * 输入 characterId，输出身份裁决/总导演共用的结构化卡片。
 * 所有数据来自 Room：人物、别名、属性/关系观察、发声方案、关键证据。
 */
class CharacterCardProjector(
    private val characters: RaCharacterGateway,
    private val build: RaBuildGateway,
) {

    suspend fun build(characterId: String): RaCharacterCard? {
        val character = characters.character(characterId) ?: return null
        val aliases = characters.aliasesOf(characterId)
        val attributes = characters.attributesOf(characterId)
        val relations = characters.relationsOf(characterId)
        val profiles = characters.voiceProfilesOf(characterId)
        val evidence = runCatching {
            build.evidenceOfCharacter(character.bookKey, characterId, 6)
        }.getOrDefault(emptyList())

        val timeline = attributes
            .filter { it.value.isNotBlank() }
            .sortedWith(compareBy({ it.fromChapter }, { it.key }))
            .take(10)
            .map {
                RaCardTimelineFact(
                    key = it.key,
                    value = it.value,
                    fromChapter = it.fromChapter,
                    source = it.source,
                )
            }
        val relationFacts = relations
            .filter { it.relation.isNotBlank() || it.targetText.isNotBlank() }
            .sortedBy { it.fromChapter }
            .take(8)
            .map {
                RaCardRelationFact(
                    relation = it.relation,
                    targetText = it.targetText,
                    fromChapter = it.fromChapter,
                )
            }
        val voiceFacts = profiles
            .sortedBy { it.kind.ordinal }
            .map { RaCardVoiceProfile(label = it.label, voiceTag = it.voiceTag, kind = it.kind.storageValue) }
        val evidenceRefs = evidence.map {
            RaCardEvidenceRef(type = it.type, chapter = it.sourceChapter, excerpt = it.excerpt.take(80))
        }
        return RaCharacterCard(
            characterId = character.id,
            displayName = character.displayName,
            aliases = aliases.sortedWith(compareBy({ it.isGeneric }, { it.firstChapter }))
                .map { it.surface }
                .distinct(),
            roleType = character.roleType,
            gender = character.gender,
            ageBand = character.ageBand,
            firstChapter = character.firstChapter,
            lastChapter = character.lastChapter,
            timeline = timeline,
            relations = relationFacts,
            voiceProfiles = voiceFacts,
            keyEvidence = evidenceRefs,
        )
    }

    /** 给身份裁决用，≤400 字。 */
    fun toResolverText(card: RaCharacterCard): String {
        val sb = StringBuilder()
        sb.append(card.displayName)
        if (card.aliases.isNotEmpty()) {
            sb.append("（别名：").append(card.aliases.take(6).joinToString("、")).append("）")
        }
        sb.append("｜").append(card.roleType).append("/").append(card.gender).append("/").append(card.ageBand)
        sb.append("｜出场：第").append(card.firstChapter + 1).append("~").append(card.lastChapter + 1).append("章")
        if (card.timeline.isNotEmpty()) {
            sb.append("｜经历：")
            sb.append(
                card.timeline.take(6).joinToString("；") {
                    "第${it.fromChapter + 1}章 ${it.key}=${it.value}"
                },
            )
        }
        if (card.relations.isNotEmpty()) {
            sb.append("｜关系：")
            sb.append(
                card.relations.take(4).joinToString("；") {
                    "${it.relation}${it.targetText}"
                },
            )
        }
        if (card.keyEvidence.isNotEmpty()) {
            sb.append("｜证据：")
            sb.append(
                card.keyEvidence.take(4).joinToString("；") {
                    "${typeLabel(it.type)}：${it.excerpt}"
                },
            )
        }
        return sb.toString().take(400)
    }

    /** 给总导演用，只取当前章之前的事实，≤300 字。 */
    fun toDirectorText(card: RaCharacterCard, chapterIndex: Int): String {
        val sb = StringBuilder()
        sb.append(card.displayName)
        if (card.aliases.isNotEmpty()) sb.append("（").append(card.aliases.take(4).joinToString("、")).append("）")
        sb.append("｜").append(card.roleType).append("/").append(card.gender).append("/").append(card.ageBand)
        val facts = card.timeline.filter { it.fromChapter <= chapterIndex }
        if (facts.isNotEmpty()) {
            sb.append("｜")
            sb.append(
                facts.take(5).joinToString("；") {
                    "第${it.fromChapter + 1}章 ${it.key}=${it.value}"
                },
            )
        }
        val rel = card.relations.filter { it.fromChapter <= chapterIndex }
        if (rel.isNotEmpty()) {
            sb.append("｜")
            sb.append(rel.take(3).joinToString("；") { "${it.relation}${it.targetText}" })
        }
        return sb.toString().take(300)
    }

    private fun typeLabel(type: RaEvidenceType): String = when (type) {
        RaEvidenceType.ALIAS_EQUAL -> "同人"
        RaEvidenceType.CONTRADICTION -> "反证"
        RaEvidenceType.RELATION -> "关系"
        RaEvidenceType.ATTRIBUTE -> "属性"
        RaEvidenceType.VOICE_STATE -> "变声"
        RaEvidenceType.APPEARANCE -> "外貌"
        RaEvidenceType.SPEAKER_ATTRIBUTION -> "归属"
    }
}
