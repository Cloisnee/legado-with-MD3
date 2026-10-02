package io.legado.app.help.readaloud.audio

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B33.4a · 「音效与背景音」章节预合成闭环（日志 v3）：
 *
 * 当前章 [startChapter]：剧本统计 → 远程命中 → （Ai补缺） → 合成总结（+补缺失败）。
 * 预合成章 [prepareAhead]：同上静默执行，全部入库后打印「预合成完成」（+补缺失败）；完成后该章播放时静默。
 * 散行补缺日志全部静默（队列/远程补缺不再单条打印）。
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
    }

    @Volatile
    private var activePrep: Prep? = null
    private var activeJob: Job? = null

    /** 预合成完成的章（bookUrl|idx → 播放时静默） */
    private val quietChapters = HashSet<String>()

    /** 预合成尝试去重（每章一次；避免多轮 sweep 重复日志） */
    private val attemptedAhead = HashSet<String>()

    fun isPreparedAhead(chapterKey: String): Boolean =
        runCatching { chapterKey in quietChapters }.getOrDefault(false)

    private fun chapterNo(chapterKey: String): String {
        val idx = chapterKey.substringAfterLast('|').toIntOrNull()
        return if (idx != null && idx >= 0) "第${idx + 1}章" else chapterKey
    }

    private fun laneText(counts: Map<SynthLane, Int>): String =
        "bgm${counts[SynthLane.BGM] ?: 0}条、环境声${counts[SynthLane.AMB] ?: 0}条、音效${counts[SynthLane.SFX] ?: 0}条"

    /** 当前章：开章触发（不阻塞播放；章尾未完成则至多补一条失败汇总） */
    fun startChapter(chapterKey: String, texts: List<String>) {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        finishChapter()
        activeJob = scope.launch {
            runCatching { runPrep(chapterKey, texts, ahead = false) }
        }
    }

    /** 预合成模式：本章人声完成后调用；全部到终态才返回（串行同步闸门）；每章仅尝试一次 */
    suspend fun prepareAhead(chapterKey: String, texts: List<String>) {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        if (!attemptedAhead.add(chapterKey)) return
        runCatching { runPrep(chapterKey, texts, ahead = true) }
    }

    /** 章/服务结束：仍未终态 → 仅补一条失败汇总 */
    fun finishChapter() {
        val prep = activePrep ?: run {
            activeJob?.cancel()
            activeJob = null
            return
        }
        activePrep = null
        activeJob?.cancel()
        activeJob = null
        val q = queue() ?: return
        val undone = collectUndone(prep, q)
        if (undone.isNotEmpty()) {
            AppLog.putAudio(
                "【音效与背景音·补缺失败·${chapterNo(prep.key)}】${undone.size} 条：${undone.joinToString("、")}"
            )
        }
    }

    /** 未终态条目（failed 或超时仍未完成） */
    private fun collectUndone(prep: Prep, q: AudioSynthQueue): List<String> {
        val out = ArrayList<String>()
        prep.enqueued.forEach { (lane, labels) ->
            labels.forEach { l ->
                if (q.entryStatus(lane, l) != "done") out += l
            }
        }
        return out
    }

    /** 主流程：剧本统计 → 远程命中 → 入队 → 等待 → 总结 */
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
            "【音效与背景音·剧本统计·${chapterNo(chapterKey)}】${laneText(totals)}，" +
                "已命中${laneText(hit)}，未入库${laneText(miss.mapValues { it.value.size })}。"
        )
        val prep = Prep(chapterKey, totals)
        miss.forEach { (lane, labels) -> prep.pending[lane] = mutableListOf() }
        if (!ahead) activePrep = prep

        // 2) 远程命中（逐条尝试免费下载；散行静默）
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
            "【音效与背景音·远程命中·${chapterNo(chapterKey)}】${laneText(prep.remoteHit)}，" +
                "待合成${laneText(prep.pending.mapValues { it.value.size })}。"
        )

        // 3) 入合成队列
        val q = queue()
        val pendingTotal = prep.pending.values.sumOf { it.size }
        if (q != null && pendingTotal > 0) {
            prep.pending.forEach { (lane, labels) ->
                labels.forEach { label ->
                    q.enqueue(lane.label, label, chapterKey)
                    prep.enqueued.getOrPut(lane) { mutableListOf() }.add(label)
                }
            }
        }

        // 4) 等待终态
        if (q != null && pendingTotal > 0) {
            q.awaitChapterIdle(chapterKey, AWAIT_TIMEOUT_MS)
        }

        // 5) 总结
        val aiDone = HashMap<SynthLane, Int>()
        val undone = ArrayList<String>()
        prep.enqueued.forEach { (lane, labels) ->
            labels.forEach { l ->
                if (q != null && q.entryStatus(lane, l) == "done") aiDone.merge(lane, 1, Int::plus) else undone += l
            }
        }
        if (ahead) {
            if (undone.isEmpty()) {
                quietChapters.add(chapterKey)
                AppLog.putAudio(
                    "【音效与背景音·${chapterNo(chapterKey)}】预合成完成：${laneText(totals)}，" +
                        "远程命中${laneText(prep.remoteHit)}，Ai补缺${laneText(aiDone)}。"
                )
            } else {
                AppLog.putAudio(
                    "【音效与背景音·补缺失败·${chapterNo(chapterKey)}】${undone.size} 条：${undone.joinToString("、")}"
                )
            }
        } else {
            if (prep.enqueued.isNotEmpty()) {
                AppLog.putAudio("【音效与背景音·Ai补缺·${chapterNo(chapterKey)}】${laneText(aiDone)}。")
            }
            if (undone.isEmpty()) {
                AppLog.putAudio("【音效与背景音·合成总结·${chapterNo(chapterKey)}】${laneText(totals)}。")
                activePrep = null
            } else {
                AppLog.putAudio(
                    "【音效与背景音·补缺失败·${chapterNo(chapterKey)}】${undone.size} 条：${undone.joinToString("、")}"
                )
                activePrep = null
            }
        }
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