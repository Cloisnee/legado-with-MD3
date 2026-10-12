package io.legado.app.domain.usecase

import android.app.Application
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.VoiceCatalogEntry

/**
 * 把 `<数据根>/_store/voices.json`（配置列表）里的 `tts_server` 声线**补写**进声线目录（Room 镜像表）。
 *
 * 批212d 重构：**只收「当前启用池」的条目**，且**身份以「条目 id」为核心**
 * （engineId = voices.json 条目 id——连根拔除 tagRuleId 参与目录/声线 ID）；
 * 不再无脑全量补写；不在此处退役（退役由阅读侧统一负责，本use case 仅保证批合成前目录可用）。
 *
 * 为什么需要：朗读播放时声线目录由阅读设置/阅读器初始化时同步；而音频管理页的**批量合成**是
 * 文件驱动、不经过阅读器，若此时镜像表尚未同步（典型场景：刚清过应用数据），
 * 批量合成会因「查不到声线」而**全部跳过**（日志表现为 `跳过 N`）。
 */
class SyncTtsServerVoicesUseCase(
    private val app: Application,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val syncVoices: SyncReadAloudVoicesUseCase,
    private val dataRepository: ReadAloudDataRepository,
) {

    suspend operator fun invoke(): Int {
        val banks = runCatching { dataRepository.loadActiveVoiceBanks() }.getOrDefault(emptyList()).toSet()
        if (banks.isEmpty()) return 0  // 未选池：不补写（防误伤）
        val entries = runCatching { parseEntries(banks) }.getOrElse {
            AppLog.putAudio("【音频缓存】声线目录解析失败: ${it.localizedMessage}", it)
            return 0
        }
        if (entries.isEmpty()) return 0
        val known = voiceGateway.getEnabledVoices()
            .filter { it.engineType == ReadAloudVoice.ENGINE_TTS_SERVER }
            .mapTo(hashSetOf()) { it.engineId to it.speakerId }
        val missing = entries.filterNot { (it.engineId to it.speakerId) in known }
        if (missing.isEmpty()) return 0
        syncVoices(
            entries = missing,
            managedSources = setOf(ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS),
            removeMissingEngineTypes = emptySet(),
        )
        AppLog.putAudio("【音频缓存】补写声线目录 ${missing.size} 条（批量合成前同步）")
        return missing.size
    }

    /** 只收启用池、条目开启的条目；身份= 条目 id（engineId 字段承载） */
    private fun parseEntries(banks: Set<String>): List<VoiceCatalogEntry> {
        val arr = TtsConfigStore.loadVoices(app)
        val out = ArrayList<VoiceCatalogEntry>()
        for (g in 0 until arr.length()) {
            val grp = arr.optJSONObject(g) ?: continue
            val gname = grp.optJSONObject("group")?.optString("name").orEmpty()
            if (gname !in banks) continue
            val list = grp.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val cfg = entry.optJSONObject("config") ?: continue
                val sr = cfg.optJSONObject("speechRule") ?: continue
                val tag = sr.optString("tag").trim()
                val entryId = entry.optString("id").trim()
                if (tag.isBlank() || entryId.isBlank()) continue
                if (!entry.optBoolean("isEnabled", true)) continue  // 条目开关：关闭=不入目录
                out.add(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_TTS_SERVER,
                        engineId = entryId,
                        speakerId = tag,
                        displayName = entry.optString("displayName").ifBlank { tag },
                        traitsJson = cfg.toString(),
                        managedBy = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
                    )
                )
            }
        }
        return out
    }
}
