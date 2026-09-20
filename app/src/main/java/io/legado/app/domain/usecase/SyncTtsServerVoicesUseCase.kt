package io.legado.app.domain.usecase

import android.app.Application
import com.github.jing332.tts.store.TtsConfigStore
import io.legado.app.constant.AppLog
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.VoiceCatalogEntry

/**
 * 把 `<数据根>/_store/voices.json`（配置列表）里的 `tts_server` 声线**补写**进声线目录（Room 镜像表）。
 *
 * 为什么需要：朗读播放时声线目录由阅读设置/阅读器初始化时同步；而音频管理页的**批量合成**是
 * 文件驱动、不经过阅读器，若此时镜像表尚未同步（典型场景：刚清过应用数据），
 * 批量合成会因「查不到声线」而**全部跳过**（日志表现为 `跳过 N`）。
 *
 * 只做 upsert，不做删除（删除仍由 [SyncReadAloudVoicesUseCase] 在阅读侧统一负责），
 * 避免后台同步误删其它来源的声线。
 */
class SyncTtsServerVoicesUseCase(
    private val app: Application,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val syncVoices: SyncReadAloudVoicesUseCase,
) {

    suspend operator fun invoke(): Int {
        val entries = runCatching { parseEntries() }.getOrElse {
            AppLog.putAudio("【音频缓存】声线目录解析失败: ${it.localizedMessage}", it)
            return 0
        }
        if (entries.isEmpty()) return 0
        val enabled = voiceGateway.getEnabledVoices()
            .filter { it.engineType == ReadAloudVoice.ENGINE_TTS_SERVER }
        val known = enabled.mapTo(hashSetOf()) { it.engineId to it.speakerId }
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

    private fun parseEntries(): List<VoiceCatalogEntry> {
        val arr = TtsConfigStore.loadVoices(app)
        val out = ArrayList<VoiceCatalogEntry>()
        for (g in 0 until arr.length()) {
            val list = arr.optJSONObject(g)?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val cfg = entry.optJSONObject("config") ?: continue
                val sr = cfg.optJSONObject("speechRule") ?: continue
                val tag = sr.optString("tag")
                val ruleId = sr.optString("tagRuleId")
                if (tag.isBlank() || ruleId.isBlank()) continue
                out.add(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_TTS_SERVER,
                        engineId = ruleId,
                        speakerId = tag,
                        displayName = entry.optString("displayName")
                            .ifBlank { sr.optString("tagName", tag) },
                        traitsJson = cfg.toString(),
                        managedBy = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
                    )
                )
            }
        }
        return out
    }
}
