package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B33.4-前置 · 「音效与背景音」章节预合成闭环（日志体系）：
 *
 * 当前章（现场模式）[startChapter]：开章触发 → 剧本统计 → 远程命中 → 入合成队列；
 * 全部到终态且在本章播完前 → 「合成总结·完毕」；本章播完仍有未完成 → 「合成总结·部分」（章尾结算）。
 * 预合成模式 [prepareAhead]（后续章）：本章人声完成后调用 → 「预合成 N 条」→ 远程/合成 → 全部到终态 → 「预合成总结」。
 *
 * 口径（甲方 2026-10-01 确认）：
 *  - 上一章人声+音效背景音全部处理完（终态）才预合成下一章——串行同步闸门由调用方保证（prepareAhead 挂起等待）；
 *  - 预合成完成的章节播放时静默（[isPreparedAhead] → 引擎 quiet，不打触发/驻留/闸门日志）。
 */
class AudioChapterPrelude(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val queue: () -> AudioSynthQueue?,
    private val enabled: () -> Boolean = { true },
) {

    private class Prep(val key: String, val totals: Map<SynthLane, Int>) {
        val remoteHit = HashMap<SynthLane, Int>()
        val pending = HashMap<SynthLane, MutableList<String>>()
        val enqueued = HashMap<SynthLane, MutableList<String>>()

        fun pendingText(): String =
            "bgm${pending[SynthLane.BGM]?.size ?: 0}条、环境声${pending[SynthLane.AMB]?.size ?: 0}条、音效${pending[SynthLane.SFX]?.size ?: 0}条"
    }

    @Volatile
    private var activePrep: Prep? = null

    private var activeJob: Job? = null

    /** 预合成完成的章（bookUrl|idx → 播放时静默） */
    private val quietChapters = HashSet<String>()

    fun isPreparedAhead(chapterKey: String): Boolean =
        runCatching { chapterKey in quietChapters }.getOrDefault(false)

    private fun chapterNo(chapterKey: String): String {
        val idx = chapterKey.substringAfterLast('|').toIntOrNull()
        return if (idx != null && idx >= 0) "第${idx + 1}章" else chapterKey
    }

    private fun laneOrderText(counts: Map<SynthLane, Int>): String =
        "bgm${counts[SynthLane.BGM] ?: 0}条、环境声${counts[SynthLane.AMB] ?: 0}条、音效${counts[SynthLane.SFX] ?: 0}条"

    /** 当前章：开章触发（不阻塞播放；章尾未完成 → 部分总结） */
    fun startChapter(chapterKey: String, texts: List<String>) {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        finishChapter()
        activeJob = scope.launch {
            runCatching { runPrep(chapterKey, texts, ahead = false) }
        }
    }

    /** 预合成模式：本章人声完成后调用；全部到终态才返回（串行同步闸门） */
    suspend fun prepareAhead(chapterKey: String, texts: List<String>) {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        runCatching { runPrep(chapterKey, texts, ahead = true) }
    }

    /** 章/服务结束：仍有未完成 → 部分总结 */
    fun finishChapter() {
        val prep = activePrep ?: return
        activePrep = null
        activeJob?.cancel()
        activeJob = null
        val q = queue() ?: return
        val done = HashMap<SynthLane, Int>()
        val undone = HashMap<SynthLane, Int>()
        prep.enqueued.forEach { (lane, labels) ->
            labels.forEach { l ->
                if (q.entryStatus(lane, l) == "done") done.merge(lane, 1, Int::plus) else undone.merge(lane, 1, Int::plus)
            }
        }
        if (done.isEmpty() && undone.isEmpty()) return
        AppLog.putAudio(
            "【音效与背景音·合成总结·${chapterNo(prep.key)}】本章结束前已合成 ${laneOrderText(done)}，未合成 ${laneOrderText(undone)}。"
        )
    }

    /** 主流程：剧本统计 → 远程命中 → 入队 → 等待/总结 */
    private suspend fun runPrep(chapterKey: String, texts: List<String>, ahead: Boolean) {
        // 1) 剧本统计（全章文本过规则层；唯一条目按轨计数）
        val items = scanItems(texts)
        val totals = HashMap<SynthLane, Int>()
        val hit = HashMap<SynthLane, Int>()
        val miss = HashMap<SynthLane, MutableList<String>>()
        items.forEach { (lane, label, resolved) ->
            totals.merge(lane, 1, Int::plus)
            if (resolved) hit.merge(lane, 1, Int::plus) else miss.getOrPut(lane) { mutableListOf() }.add(label)
        }
        AppLog.putAudio(
            "【音效与背景音·剧本统计·${chapterNo(chapterKey)}】${laneOrderText(totals)}，" +
                "已命中${laneOrderText(hit)}，未入库${laneOrderText(miss.mapValues { it.value.size })}。"
        )
        if (miss.isEmpty()) {
            if (ahead) {
                quietChapters.add(chapterKey)
                AppLog.putAudio(
                    "【音效与背景音·${chapterNo(chapterKey)}·预合成总结】本章预合成条目均在库，${laneOrderText(totals)}。"
                )
            }
            return
        }
        val prep = Prep(chapterKey, totals)
        miss.forEach { (lane, labels) -> prep.pending[lane] = mutableListOf() }
        if (!ahead) activePrep = prep

        // 2) 远程命中（逐条尝试免费下载）
        miss.forEach { (lane, labels) ->
            labels.forEach { label ->
                val fetched = runCatching { AudioRemoteAuto.tryFetch(appContext, lane, label) }.getOrNull()
                if (fetched != null) {
                    prep.remoteHit.merge(lane, 1, Int::plus)
                } else {
                    prep.pending[lane]?.add(label)
                }
            }
        }
        AppLog.putAudio(
            "【音效与背景音·远程命中·${chapterNo(chapterKey)}】${laneOrderText(prep.remoteHit)}，待合成${prep.pendingText()}。"
        )

        // 3) 入合成队列
        val q = queue()
        val missTotal = prep.pending.values.sumOf { it.size }
        if (q != null && missTotal > 0) {
            prep.pending.forEach { (lane, labels) ->
                labels.forEach { label ->
                    q.enqueue(lane.label, label, chapterKey)
                    prep.enqueued.getOrPut(lane) { mutableListOf() }.add(label)
                }
            }
        }
        if (ahead) {
            AppLog.putAudio("【音效与背景音·${chapterNo(chapterKey)}】预合成 $missTotal 条")
        }
        if (q == null || missTotal == 0) {
            if (ahead) {
                quietChapters.add(chapterKey)
                printAheadSummary(prep)
            }
            return
        }

        // 4) 等待全部到终态
        val finished = q.awaitChapterIdle(chapterKey, AWAIT_TIMEOUT_MS)
        if (ahead) {
            if (finished) quietChapters.add(chapterKey)
            printAheadSummary(prep)
        } else if (finished) {
            // 当前章：播完前全部就绪 → 完毕总结（已合成 = 远程命中 + 队列完成）
            val done = HashMap<SynthLane, Int>()
            (prep.remoteHit.keys + prep.enqueued.keys).toSet().forEach { lane ->
                val aiDone = prep.enqueued[lane]?.count { q.entryStatus(lane, it) == "done" } ?: 0
                done[lane] = (prep.remoteHit[lane] ?: 0) + aiDone
            }
            AppLog.putAudio(
                "【音效与背景音·合成总结·${chapterNo(chapterKey)}】本章已合成完毕，共计${laneOrderText(done)}。"
            )
            activePrep = null
        }
        // 当前章未 finished：保持 activePrep，章尾 finishChapter() 打部分总结
    }

    private fun printAheadSummary(prep: Prep) {
        val q = queue() ?: return
        var undone = 0
        prep.enqueued.forEach { (lane, labels) ->
            labels.forEach { if (q.entryStatus(lane, it) != "done") undone++ }
        }
        val extra = if (undone > 0) "（未完成 $undone 条）" else ""
        AppLog.putAudio(
            "【音效与背景音·${chapterNo(prep.key)}·预合成总结】本章预合成条目均在库，${laneOrderText(prep.totals)}$extra。"
        )
    }

    /** 全章文本 → 规则层候选（唯一条目；resolved=本地可解析） */
    private suspend fun scanItems(texts: List<String>): List<Triple<SynthLane, String, Boolean>> =
        withContext(Dispatchers.Default) {
            buildList {
                texts.forEach { raw ->
                    val text = raw.trim()
                    if (text.length < 2) return@forEach
                    for (lane in listOf(DemoLanes.Lane.BGM, DemoLanes.Lane.AMBIENCE, DemoLanes.Lane.SFX)) {
                        val pick = AudioRuleEngine.pick(appContext, lane, text) ?: continue
                        add(Triple(toSynth(lane), pick.hit.label, pick.resolved != null))
                    }
                }
            }.distinctBy { it.first to it.second }
        }

    private fun toSynth(lane: DemoLanes.Lane): SynthLane = when (lane) {
        DemoLanes.Lane.AMBIENCE -> SynthLane.AMB
        DemoLanes.Lane.BGM -> SynthLane.BGM
        else -> SynthLane.SFX
    }

    private companion object {
        /** 等待本章全部条目到终态的上限 */
        const val AWAIT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
