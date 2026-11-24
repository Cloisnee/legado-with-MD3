package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaNameUtils
import io.legado.app.domain.model.readaloud.v4.RaSegment

/** 话语序号 → 章内说话人；阶段2 的 seqMap 是唯一依据，不用别名反查。 */
object SpeakerBinder {

    fun bind(
        segments: List<RaSegment>,
        speakers: List<RaLocalSpeaker>,
        characterByKey: Map<String, String>,
        seqMap: Map<Int, String>,
    ): List<RaSegment> {
        val byPrimary = speakers.associateBy { RaNameUtils.normalize(it.primaryName) }
        val byLocalKey = speakers.associateBy { it.localKey }
        return segments.map { segment ->
            if (segment.roleType == SpeechRoleType.Narrator) {
                segment.copy(seq = 0, localSpeakerId = null, characterId = null)
            } else {
                val reference = seqMap[segment.seq] ?: segment.surfaceName
                val speaker = byPrimary[RaNameUtils.normalize(reference)] ?: byLocalKey[reference]
                segment.copy(
                    localSpeakerId = speaker?.id,
                    characterId = speaker?.localKey?.let(characterByKey::get),
                    surfaceName = speaker?.primaryName ?: segment.surfaceName,
                )
            }
        }
    }
}
