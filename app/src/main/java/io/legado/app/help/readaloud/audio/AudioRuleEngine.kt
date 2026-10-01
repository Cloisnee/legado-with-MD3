package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.help.readaloud.audio.AudioRuleStore.RuleData

/**
 * B33.3d · 音效规则层（兜底驱动）匹配器。
 *
 * 候选优先级：**用户自定规则**（素材「匹配规则」字段） > **内置示例规则**（DemoLanes） > **CNB 意图规则**（AC 匹配）。
 * 命中后沿「soundId → 别名 → 名称」解析到本地库素材；全部无法解析 → 返回最优候选交给引擎走「缺失 → 合成」。
 *
 * 与引擎的契约：[AudioLaneEngine] 每行调用 [pick]，拿到「已解析的素材」或「待补缺的展示名」。
 */
object AudioRuleEngine {

    enum class Source(val label: String) {
        USER("自定规则"),
        SEED("示例规则"),
        INTENT("意图规则"),
    }

    data class Hit(
        val lane: DemoLanes.Lane,
        val source: Source,
        /** 名称解析候选（soundId 对应名称 + 别名；示例/自定即素材名） */
        val keywords: List<String>,
        /** soundId 解析候选（意图规则特有；优先按 sidecar 串联已入库素材） */
        val soundIds: List<String>,
        val gain: Float,
        val delayMs: Long,
        val holdCues: Int,
        /** 缺失上报用的展示名 */
        val label: String,
        val intentId: String = "",
    )

    data class Picked(val hit: Hit, val resolved: AudioLibrary.ResolvedAsset?)

    private const val MAX_CANDIDATES = 10
    private const val MAX_INTENT_HITS = 6
    private const val USER_RULES_TTL_MS = 30_000L

    // ------------------------------------------------------------ 用户自定规则（素材「匹配规则」字段）

    private class UserRule(val lane: DemoLanes.Lane, val regex: Regex, val name: String)

    @Volatile private var userRulesAt = 0L

    @Volatile private var userRules: List<UserRule> = emptyList()

    private fun userRules(): List<UserRule> {
        val now = System.currentTimeMillis()
        if (now - userRulesAt < USER_RULES_TTL_MS) return userRules
        userRulesAt = now
        userRules = AudioLibrary.snapshot()
            .filter { it.enabled && it.pattern.isNotBlank() }
            .mapNotNull { a ->
                runCatching { Regex(a.pattern) }
                    .getOrNull()
                    ?.let { rx -> UserRule(laneOfAsset(a), rx, a.name) }
            }
        return userRules
    }

    internal fun laneOfAsset(asset: AudioLibrary.AudioAsset): DemoLanes.Lane = when (asset.category) {
        "BGM" -> DemoLanes.Lane.BGM
        "环境声" -> DemoLanes.Lane.AMBIENCE
        else -> DemoLanes.Lane.SFX
    }

    internal fun laneForType(type: String): DemoLanes.Lane =
        if (type == "scene") DemoLanes.Lane.AMBIENCE else DemoLanes.Lane.SFX

    // ------------------------------------------------------------ 匹配

    /** 返回可播（已解析）或待补缺的最优命中；无命中返回 null */
    fun pick(context: Context, lane: DemoLanes.Lane, text: String): Picked? {
        val candidates = candidates(lane, text)
        if (candidates.isEmpty()) return null
        var first: Picked? = null
        for (h in candidates) {
            val r = resolveHit(context, h)
            if (first == null) first = Picked(h, r)
            if (r != null) return Picked(h, r)
        }
        return first
    }

    internal fun candidates(lane: DemoLanes.Lane, text: String): List<Hit> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Hit>(8)
        // 1) 用户自定规则（数量少，直接跑）
        for (u in userRules()) {
            if (u.lane == lane && u.regex.containsMatchIn(text)) {
                out.add(
                    Hit(
                        lane = lane, source = Source.USER,
                        keywords = listOf(u.name), soundIds = emptyList(),
                        gain = 0.8f, delayMs = 0L, holdCues = 0, label = u.name,
                    )
                )
                if (out.size >= MAX_CANDIDATES) return out
            }
        }
        // 2) 内置示例规则
        DemoLanes.match(lane, text)?.let { r ->
            out.add(
                Hit(
                    lane = lane, source = Source.SEED,
                    keywords = listOf(r.keyword), soundIds = emptyList(),
                    gain = r.gain, delayMs = r.delayMs, holdCues = r.holdCues, label = r.keyword,
                )
            )
        }
        // 3) CNB 意图规则（AC 命中 → 类型分道 → 排序）
        val data = AudioRuleStore.current() ?: return out
        out.addAll(intentHits(data, lane, text))
        return out.take(MAX_CANDIDATES)
    }

    internal fun intentHits(data: RuleData, lane: DemoLanes.Lane, text: String): List<Hit> {
        val matches = data.matcher.matchAll(text)
        if (matches.isEmpty()) return emptyList()
        // intent 下标 → 最长命中长度
        val best = HashMap<Int, Int>()
        for (m in matches) {
            val ii = data.intentIndexByPattern.getOrNull(m.patternIndex) ?: continue
            val prev = best[ii]
            if (prev == null || m.length > prev) best[ii] = m.length
        }
        if (best.isEmpty()) return emptyList()
        val ordered = best.entries.sortedWith(
            compareByDescending<Map.Entry<Int, Int>> { data.intents[it.key].priority }
                .thenByDescending { it.value }
                .thenBy { it.key }
        )
        val hits = ArrayList<Hit>()
        for ((ii, _) in ordered) {
            if (hits.size >= MAX_INTENT_HITS) break
            val intent = data.intents.getOrNull(ii) ?: continue
            if (laneForType(intent.type) != lane) continue
            val sids = data.soundIdsByIntent[intent.id].orEmpty()
                .filter { it.isNotBlank() && it !in data.excludedSoundIds }
            if (sids.isEmpty()) continue
            val names = LinkedHashSet<String>()
            sids.forEach { sid ->
                val meta = data.soundById[sid]
                if (meta != null) names.addAll(meta.names) else names.add(sid)
            }
            val label = data.soundById[sids.first()]?.name ?: sids.first()
            hits.add(
                Hit(
                    lane = lane, source = Source.INTENT,
                    keywords = names.take(8), soundIds = sids,
                    gain = intent.volume, delayMs = 0L, holdCues = 0,
                    label = label, intentId = intent.id,
                )
            )
        }
        return hits
    }

    // ------------------------------------------------------------ 解析到本地素材

    fun resolveHit(context: Context, hit: Hit): AudioLibrary.ResolvedAsset? {
        // 1) soundId 直连（下载条目 sidecar 自带 soundId）
        for (sid in hit.soundIds) {
            AudioLibrary.resolveBySoundId(context, sid)?.let { return it }
        }
        // 2) 别名表 → soundId → 直连；3) 名称链（精确 → 别名 → 包含）
        for (kw in hit.keywords) {
            AudioRuleStore.current()?.soundIdOfAlias(kw)?.let { sid ->
                AudioLibrary.resolveBySoundId(context, sid)?.let { return it }
            }
            AudioLibrary.resolve(context, kw)?.let { return it }
        }
        return null
    }
}

/**
 * B33.3d · 兜底层启用口径（「四条件」，B33 方案 §8.1）：
 *
 *  ① 本章无 AI 音频计划（未分析 / 无标记）
 *  ② AI 分析失败（回退）
 *  ③ 未配置音频导演模型（AI 不可用）
 *  ④ 首章 或 非连续章节（跳读 / 快速播放链）
 *
 * 任一满足 → 本地规则层驱动四轨；AI 计划就绪且非上述情形 → 由计划层接管（B33.4 接线）。
 */
object AudioFallbackPolicy {

    fun shouldUseRules(
        hasPlan: Boolean,
        aiFailed: Boolean = false,
        aiConfigured: Boolean = true,
        isFirstChapter: Boolean = false,
        isNonContiguous: Boolean = false,
    ): Boolean = !hasPlan || aiFailed || !aiConfigured || isFirstChapter || isNonContiguous
}
