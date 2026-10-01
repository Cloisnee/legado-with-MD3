package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B33.4 前置 · 补缺链第二环：本地缺失 → **远程库自动补缺**（免费优先，命中即下载落库）。
 *
 * 调用点：[AudioSynthQueue] 处理每条任务时先走这里；命中则不再走 AI 合成。
 * 口径：同词一次（会话内缓存，含成功/失败）；18+ 包除开关开启外跳过；
 * 搜索顺序按轨微调（环境优先 matrix24 环境库；音效优先 core）。
 */
object AudioRemoteAuto {

    private val tried = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 尝试远程补缺；命中返回落库文件，未命中返回 null（调用方继续 AI 合成链） */
    suspend fun tryFetch(context: Context, lane: SynthLane, keyword: String): File? {
        val kw = keyword.trim()
        if (kw.isEmpty()) return null
        val candidates = listOf(kw, kw.removeSuffix("音效").removeSuffix("声效").trim())
            .filter { it.isNotBlank() && (it == kw || it.length >= 2) }
            .distinct()
        if (!tried.add(kw)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val packs = orderPacks(lane, AudioRemoteCatalog.manifest(context))
                    .filter { it.indexUrl.isNotBlank() && (it.defaultEnabled || adultEnabled()) }
                for (pack in packs) {
                    val list = runCatching { AudioRemoteCatalog.sounds(context, pack) }.getOrDefault(emptyList())
                    if (list.isEmpty()) continue
                    for (cand in candidates) {
                        val hit = AudioRemoteCatalog.search(list, cand, limit = 1).firstOrNull() ?: continue
                        val file = runCatching { AudioRemoteCatalog.download(context, hit) }.getOrNull() ?: continue
                        AppLog.putAudio("【合成】远程补缺：$kw → ${hit.name}（${pack.label}）")
                        return@withContext file
                    }
                }
                null
            }.getOrNull()
        }
    }

    private fun orderPacks(
        lane: SynthLane,
        packs: List<AudioRemoteCatalog.RemotePack>,
    ): List<AudioRemoteCatalog.RemotePack> {
        val preferred = when (lane) {
            SynthLane.AMB -> listOf("matrix24", "core")
            SynthLane.BGM -> listOf("matrix24", "core")
            SynthLane.SFX -> listOf("core", "matrix24")
        }
        return packs.sortedBy { p ->
            preferred.indexOf(p.id).let { if (it >= 0) it else preferred.size }
        }
    }

    private fun adultEnabled(): Boolean =
        runCatching { AppConfigStore.getBoolean(PreferKey.audioAdultEnabled) == true }.getOrDefault(false)
}
