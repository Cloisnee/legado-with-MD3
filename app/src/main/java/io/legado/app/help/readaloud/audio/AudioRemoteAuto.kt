package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

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
        if (!tried.add(kw)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val packs = orderPacks(lane, AudioRemoteCatalog.manifest(context))
                    .filter { it.indexUrl.isNotBlank() && (it.defaultEnabled || adultEnabled()) }
                for (pack in packs) {
                    val list = runCatching { AudioRemoteCatalog.sounds(context, pack) }.getOrDefault(emptyList())
                    if (list.isEmpty()) continue
                    // B33.4a 口径：按标签/分组过滤候选池——音效/环境声/BGM 各自只在对应类内选
                    val pool = list.filter { laneBucket(it) == bucketOf(lane) }
                    if (pool.isEmpty()) continue
                    val hit = AudioRemoteMatcher.pick(pool, kw, lane) ?: continue
                    val file = runCatching { AudioRemoteCatalog.download(context, hit) }.getOrNull() ?: continue
                    return@withContext file
                }
                null
            }.getOrNull()
        }
    }

    /** 远程条目 → 三分类（0=环境声 1=BGM 2=音效；与音频库落库口径一致） */
    internal fun laneBucket(s: AudioRemoteCatalog.RemoteSound): Int {
        val cat = s.category.lowercase()
        val cn = s.categoryName
        val sub = s.subType.lowercase()
        val tags = s.tags.map { it.lowercase() }
        val bgm = cat == "bgm" || cat.startsWith("bgm") || cn.contains("bgm", ignoreCase = true) ||
            tags.any { it == "bgm" || it.startsWith("bgm") }
        if (bgm) return 1
        val env = cn.contains("环境") || cat == "scene" || cat.startsWith("scene") ||
            sub == "amb" || sub == "ambience" ||
            tags.any { it == "scene" || it == "amb" || it == "ambience" || it == "environment" }
        return if (env) 0 else 2
    }

    private fun bucketOf(lane: SynthLane): Int = when (lane) {
        SynthLane.AMB -> 0
        SynthLane.BGM -> 1
        SynthLane.SFX -> 2
    }

    private fun orderPacks(
        lane: SynthLane,
        packs: List<AudioRemoteCatalog.RemotePack>,
    ): List<AudioRemoteCatalog.RemotePack> {
        val preferred = when (lane) {
            SynthLane.AMB -> listOf("matrix24", "horror_thriller_v1", "core")
            SynthLane.BGM -> listOf("bgm")
            SynthLane.SFX -> listOf("mingwuyan", "core", "matrix24")
        }
        return packs.sortedBy { p ->
            preferred.indexOf(p.id).let { if (it >= 0) it else preferred.size }
        }
    }

    private fun adultEnabled(): Boolean =
        runCatching { AppConfigStore.getBoolean(PreferKey.audioAdultEnabled) == true }.getOrDefault(false)
}

/**
 * B33.3c-附3 · 远程条目匹配（标签类型感知；替代「contains 第一个」的粗匹配）：
 *  - 归一化：统一去掉「音效/声效/声音/声/音」等后缀后比较；
 *  - 分层：精确（归一化相等）> 加/减一个柔和后缀 > 包含；
 *  - 环境声轨额外加「环境契合」优先层：既在环境分组又含"环境/氛围"字 > 仅含名 > 仅分组；
 *  - 同层内：与标签长度差最小者优先；完全同优 → 随机（打散后稳定排序）。
 */
internal object AudioRemoteMatcher {

    private val SOFT_SUFFIXES = listOf("音效", "声效")

    private fun norm(raw: String): String {
        var t = raw.trim()
        SOFT_SUFFIXES.forEach { suf ->
            if (t.length > suf.length && t.endsWith(suf)) {
                t = t.removeSuffix(suf)
                return@forEach
            }
        }
        return t.trim()
    }

    fun pick(list: List<AudioRemoteCatalog.RemoteSound>, keyword: String, lane: SynthLane): AudioRemoteCatalog.RemoteSound? {
        val kw = norm(keyword)
        if (kw.isBlank()) return null
        data class Cand(
            val s: AudioRemoteCatalog.RemoteSound,
            val fit: Int,
            val exact: Int,
            val boundary: Int,
            val lenDiff: Int,
        )

        val cands = ArrayList<Cand>()
        list.forEach sLoop@{ s ->
            val names = (listOf(s.name) + s.aliases).filter { it.isNotBlank() }
            var exact = -1
            var boundary = 0
            var lenDiff = Int.MAX_VALUE
            names.forEach { raw ->
                val n = norm(raw)
                if (n.isEmpty()) return@forEach
                when {
                    n == kw -> {
                        exact = maxOf(exact, 3)
                        lenDiff = minOf(lenDiff, 0)
                    }
                    n == "${kw}声" || n == "${kw}音" || kw == "${n}声" || kw == "${n}音" -> {
                        exact = maxOf(exact, 2)
                        lenDiff = minOf(lenDiff, abs(n.length - kw.length))
                    }
                    n.contains(kw) || kw.contains(n) -> {
                        exact = maxOf(exact, 1)
                        lenDiff = minOf(lenDiff, abs(n.length - kw.length))
                        if (n.startsWith(kw) || n.endsWith(kw) || kw.startsWith(n) || kw.endsWith(n)) {
                            boundary = 1
                        }
                    }
                }
            }
            if (exact < 0) return@sLoop
            val fit = if (lane == SynthLane.AMB) {
                val envGroup = s.category == "scene" || s.categoryName.contains("环境")
                val envName = s.name.contains("环境") || s.name.contains("氛围") ||
                    s.name.lowercase().contains("amb")
                when {
                    envGroup && envName -> 2
                    envName -> 1
                    envGroup -> 0
                    else -> -1
                }
            } else {
                0
            }
            cands += Cand(s, fit, exact, boundary, lenDiff)
        }
        if (cands.isEmpty()) return null
        val cmp = compareByDescending<Cand> { it.fit }
            .thenByDescending { it.exact }
            .thenByDescending { it.boundary }
            .thenBy { it.lenDiff }
        return cands.shuffled().sortedWith(cmp).first().s
    }
}
