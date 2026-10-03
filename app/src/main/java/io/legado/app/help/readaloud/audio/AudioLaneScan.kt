package io.legado.app.help.readaloud.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * B33.4a · 音效与背景音章级扫描（本地规则层）：
 * 全章文本 → 规则层候选（唯一条目；resolved=本地可解析）。
 * 供预合成汇总（[AudioChapterPrelude]）与分析侧日志（SpeechAnalysisPipelineV3）共用。
 */
object AudioLaneScan {

    @Volatile
    private var appCtx: Context? = null

    /** 记住应用上下文（由引擎/词典预热时调用；供分析侧无 Context 场景使用） */
    internal fun remember(context: Context) {
        appCtx = context.applicationContext
    }

    /** 无 Context 版摘要（分析侧日志用；未就绪返回 null） */
    suspend fun summaryText(texts: List<String>): String? {
        val ctx = appCtx ?: return null
        return summaryText(ctx, texts)
    }

    suspend fun scan(context: Context, texts: List<String>): List<Triple<SynthLane, String, Boolean>> =
        withContext(Dispatchers.Default) {
            runCatching { AudioBuiltinRules.ensureLoaded(context.applicationContext) }
            buildList {
                texts.forEach { raw ->
                    val text = raw.trim()
                    if (text.length < 2) return@forEach
                    for (lane in listOf(DemoLanes.Lane.BGM, DemoLanes.Lane.AMBIENCE, DemoLanes.Lane.SFX)) {
                        val pick = AudioRuleEngine.pick(context, lane, text) ?: continue
                        add(Triple(toSynth(lane), pick.hit.label, pick.resolved != null))
                    }
                }
            }.distinctBy { it.first to it.second }
        }

    /** 一行文案：「bgm x条、环境声 x条、音效 x条」（分析侧日志用） */
    suspend fun summaryText(context: Context, texts: List<String>): String {
        val counts = HashMap<SynthLane, Int>()
        scan(context, texts).forEach { (lane, _, _) -> counts.merge(lane, 1, Int::plus) }
        return "bgm${counts[SynthLane.BGM] ?: 0}条、环境声${counts[SynthLane.AMB] ?: 0}条、音效${counts[SynthLane.SFX] ?: 0}条"
    }

    // ------------------------------------------------------------ B34.2·③ 预插标记建议

    /** 逐段建议（ratio=命中位；BGM 无位置=0） */
    data class Suggestion(val lane: SynthLane, val label: String, val ratio: Float)

    /** 无 Context 版（分析侧；未就绪返回 null） */
    suspend fun suggest(texts: List<String>): List<List<Suggestion>>? {
        val ctx = appCtx ?: return null
        return suggest(ctx, texts)
    }

    /** 逐段建议：每段每轨至多 1 个（与兜底口径一致；共享规则/词典/意图链） */
    suspend fun suggest(context: Context, texts: List<String>): List<List<Suggestion>> =
        withContext(Dispatchers.Default) {
            runCatching { AudioBuiltinRules.ensureLoaded(context.applicationContext) }
            texts.map { raw ->
                val text = raw.trim()
                if (text.length < 2) {
                    emptyList()
                } else {
                    buildList {
                        for (lane in listOf(DemoLanes.Lane.BGM, DemoLanes.Lane.AMBIENCE, DemoLanes.Lane.SFX)) {
                            val pick = AudioRuleEngine.pick(context, lane, text) ?: continue
                            add(Suggestion(toSynth(lane), pick.hit.label, pick.hit.posRatio))
                        }
                    }
                }
            }
        }

    private fun toSynth(lane: DemoLanes.Lane): SynthLane = when (lane) {
        DemoLanes.Lane.AMBIENCE -> SynthLane.AMB
        DemoLanes.Lane.BGM -> SynthLane.BGM
        else -> SynthLane.SFX
    }
}