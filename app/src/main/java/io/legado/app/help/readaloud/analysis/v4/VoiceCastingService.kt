package io.legado.app.help.readaloud.analysis.v4

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudVoicePoolRepository
import io.legado.app.domain.gateway.RaCharacterGateway
import io.legado.app.domain.model.readaloud.VoiceGroupInfo
import io.legado.app.domain.model.readaloud.VoiceBankRoleType
import io.legado.app.domain.model.readaloud.v4.RaCharacter
import io.legado.app.domain.model.readaloud.v4.RaIds
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 从“声线池”为人物创建默认发声方案（原 V3 assignVoices 算法整体迁移）。
 * 输入为角色的当前属性；已用声线来自本书全部 voiceProfile。
 */
class VoiceCastingService(
    private val voicePool: ReadAloudVoicePoolRepository,
    private val characters: RaCharacterGateway,
) {

    private val bookLocks = ConcurrentHashMap<String, Mutex>()

    /** 为一批人物补齐 DEFAULT 方案；只为没有方案的人物选音。 */
    suspend fun ensureDefaultProfiles(
        bookKey: String,
        charactersInChapter: Collection<RaCharacter>,
        chapterLabel: String,
    ) {
        if (charactersInChapter.isEmpty()) return
        // 同书“查方案 + 写方案”串行化，避免前后台并发各建一个 DEFAULT。
        bookLocks.getOrPut(bookKey) { Mutex() }.withLock {
            ensureDefaultProfilesLocked(bookKey, charactersInChapter, chapterLabel)
        }
    }

    private suspend fun ensureDefaultProfilesLocked(
        bookKey: String,
        charactersInChapter: Collection<RaCharacter>,
        chapterLabel: String,
    ) {
        if (charactersInChapter.isEmpty()) return
        val existing = characters.voiceProfiles(bookKey)
        val existingByCharacter = existing.groupBy { it.characterId }
        val need = charactersInChapter.filter { character ->
            existingByCharacter[character.id].orEmpty().none { it.kind == RaVoiceProfileKind.DEFAULT }
        }
        if (need.isEmpty()) return

        val groups = runCatching { voicePool.loadActiveVoiceGroups() }.getOrDefault(emptyList())
        val tagEntries = runCatching { voicePool.loadActivePoolTagEntries() }.getOrDefault(emptyMap())
        val used = existing.mapNotNull { it.voiceTag.takeIf(String::isNotBlank) }.toMutableSet()

        fun poolTags(roletype: String): List<String> =
            groups.filter { it.effectiveRoleType() == roletype && it.tags.isNotEmpty() }
                .flatMap(VoiceGroupInfo::tags)
                .distinct()

        fun pickRandom(candidates: List<String>): String {
            val fresh = candidates.filter { it !in used }
            val selected = if (fresh.isNotEmpty()) fresh.random() else candidates.random()
            used.add(selected)
            return selected
        }

        val now = System.currentTimeMillis()
        var assigned = 0
        var fallback = 0
        need.forEach { character ->
            val roleType = character.roleType.ifBlank { "核心" }
            var tag = ""
            var entryId = ""
            if (groups.isNotEmpty()) {
                val prefix = voicePool.expectedVoicePrefix(roleType, character.gender, character.ageBand)
                val direct = poolTags(roleType).filter { it.startsWith(prefix) }
                val selected = when {
                    direct.isNotEmpty() -> pickRandom(direct)
                    roleType == "特殊" -> {
                        val youth = if (character.gender == "女") "女青年" else "男青年"
                        val core = poolTags("核心").filter { it.startsWith(youth) }
                        core.takeIf { it.isNotEmpty() }?.let(::pickRandom).orEmpty()
                    }
                    else -> ""
                }.ifBlank {
                    val dialog = if (character.gender == "女") "duihuaB" else "duihuaA"
                    val fallbackTags = poolTags("默认对话").filter { it.startsWith(dialog) }
                    fallbackTags.takeIf { it.isNotEmpty() }?.random().orEmpty()
                }
                if (selected.isNotBlank()) {
                    tag = selected
                    entryId = tagEntries[selected]?.first.orEmpty()
                    assigned++
                    if (!selected.startsWith(prefix)) fallback++
                }
            }
            characters.saveVoiceProfile(
                RaVoiceProfile(
                    id = RaIds.voiceProfile(),
                    bookKey = bookKey,
                    characterId = character.id,
                    kind = RaVoiceProfileKind.DEFAULT,
                    label = "默认",
                    castingKey = "$roleType|${character.gender}|${character.ageBand}",
                    voiceTag = tag,
                    voiceEntryId = entryId,
                    source = "auto",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (need.isNotEmpty()) {
            AppLog.putAnalysis(
                "【V4·$chapterLabel·收敛】发声方案：新建 ${need.size} 个，选音 $assigned" +
                    if (fallback > 0) "（默认对话兜底 $fallback）" else "",
            )
        }
    }
}
