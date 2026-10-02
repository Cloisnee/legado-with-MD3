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
 * B33.4b：条目来源=本章音频计划（Ai）；无计划回退规则层扫描（[AudioLaneScan]）。
 */
class AudioChapterPrelude(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val queue: () -> AudioSynthQueue?,
    private val enabled: () -> Boolean = { true },
) {

    init {
        runCatching { AudioPlanStore.remember(appContext) }
    }

    private class Prep(val key: String, val totals: Map<SynthLane, Int>) {
        val remoteHit = HashMap<SynthLane, Int>()
        val pending = HashMap<SynthLane, MutableList<Item>>()
        val enqueued = HashMap<SynthLane, MutableList<String>>()
    }

    /** 待补缺条目（计划/规则同构）：label=检索名（BGM=画像·情绪·强度）；desc=生成描述 */
    private class Item(
        val lane: SynthLane,
        val label: String,
        val resolved: Boolean,
        val desc: String,
        val bgm: AudioBgmPicker.Query?,
    )

    @Volatile
    private var activePrep: Prep? = null
    private var activeJob: Job? = null

    /** 预合成完成的章（bookUrl|idx → 播放时静默） */
    private val quietChapters = HashSet<String>()

    /** 预合成尝试去重（每章一次；避免多轮 sweep 重复日志） */
    private val attemptedAhead = HashSet<String>()

    /** 已完成预合成的章 → 条目计数（朗读到达时只打一条合成总结） */
    private val preparedTotals = HashMap<String, Map<SynthLane, Int>>()

    fun isPreparedAhead(chapterKey: String): Boolean =
        runCatching { chapterKey in quietChapters }.getOrDefault(false)

    private fun chapterNo(chapterKey: String): String {
        val idx = chapterKey.substringAfterLast('|').toIntOrNull()
        return if (idx != null && idx >= 0) "第${idx + 1}章" else chapterKey
    }

    private fun laneText(counts: Map<SynthLane, Int>): String =
        "bgm${counts[SynthLane.BGM] ?: 0}条、环境声${counts[SynthLane.AMB] ?: 0}条、音效${counts[SynthLane.SFX] ?: 0}条"

    /** 当前章：开章触发（不阻塞播放；章尾未完成则至多补一条失败汇总） */
    fun startChapter(chapterKey: String, texts: List<String>, book: String = "") {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        finishChapter()
        // 已预合成完成的章：朗读到达时与「音频缓存」一致，只打一条合成总结
        preparedTotals[chapterKey]?.let { totals ->
            AppLog.putAudio("【音效与背景音·合成总结·${chapterNo(chapterKey)}】${laneText(totals)}。")
            return
        }
        activeJob = scope.launch {
            runCatching { runPrep(chapterKey, texts, book, ahead = false) }
        }
    }

    /** 预合成模式：本章人声完成后调用；全部到终态才返回（串行同步闸门）；每章仅尝试一次 */
    suspend fun prepareAhead(chapterKey: String, texts: List<String>, book: String = "") {
        if (!enabled() || chapterKey.isBlank() || texts.isEmpty()) return
        if (!attemptedAhead.add(chapterKey)) return
        runCatching { runPrep(chapterKey, texts, book, ahead = true) }
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

    /** 主流程：剧本统计 → 远程命中 → 入队 → 等待 → 总结（Ai 计划优先；无计划回退规则层） */
    private suspend fun runPrep(chapterKey: String, texts: List<String>, book: String, ahead: Boolean) {
        // 1) 剧本统计（本章计划条目 / 全章文本过规则层；唯一条目按轨计数）
        val items = scanItems(chapterKey, texts, book)
        val totals = HashMap<SynthLane, Int>()
        val hit = HashMap<SynthLane, Int>()
        val miss = HashMap<SynthLane, MutableList<Item>>()
        items.forEach { item ->
            totals.merge(item.lane, 1, Int::plus)
            if (item.resolved) hit.merge(item.lane, 1, Int::plus)
            else miss.getOrPut(item.lane) { mutableListOf() }.add(item)
        }
        if (!ahead) {
            AppLog.putAudio(
                "【音效与背景音·剧本统计·${chapterNo(chapterKey)}】${laneText(totals)}，" +
                    "已命中${laneText(hit)}，未入库${laneText(miss.mapValues { it.value.size })}。"
            )
        }
        val prep = Prep(chapterKey, totals)
        if (!ahead) activePrep = prep

        // B33.4a：当前章全命中 → 只打一条合成总结（与音频缓存同款）
        if (!ahead && miss.isEmpty()) {
            AppLog.putAudio("【音效与背景音·合成总结·${chapterNo(chapterKey)}】${laneText(totals)}。")
            activePrep = null
            return
        }

        // 2) 远程命中（逐条尝试免费下载；散行静默；BGM 走结构化选曲）
        miss.forEach { (lane, list) ->
            list.forEach { item ->
                val fetched = runCatching {
                    if (item.bgm != null) {
                        AudioBgmPicker.fetchRemote(appContext, item.bgm)
                    } else {
                        AudioRemoteAuto.tryFetch(appContext, lane, item.label)
                    }
                }.getOrNull()
                if (fetched != null) {
                    prep.remoteHit.merge(lane, 1, Int::plus)
                } else {
                    prep.pending.getOrPut(lane) { mutableListOf() }.add(item)
                }
            }
        }
        if (!ahead) {
            AppLog.putAudio(
                "【音效与背景音·远程命中·${chapterNo(chapterKey)}】${laneText(prep.remoteHit)}，" +
                    "待合成${laneText(prep.pending.mapValues { it.value.size })}。"
            )
        }

        // 3) 入合成队列（携带生成描述；合成提示词 desc 优先）
        val q = queue()
        val pendingTotal = prep.pending.values.sumOf { it.size }
        if (q != null && pendingTotal > 0) {
            prep.pending.forEach { (lane, list) ->
                list.forEach { item ->
                    q.enqueue(lane.label, item.label, chapterKey, item.desc)
                    prep.enqueued.getOrPut(lane) { mutableListOf() }.add(item.label)
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
                preparedTotals[chapterKey] = totals
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

    /** 条目来源：本章音频计划（Ai 导演）优先；无计划回退规则层扫描（[AudioLaneScan]） */
    private suspend fun scanItems(chapterKey: String, texts: List<String>, book: String): List<Item> {
        val plan = loadPlan(chapterKey, book)
        if (plan != null) {
            return withContext(Dispatchers.IO) {
                val out = ArrayList<Item>(plan.itemCount)
                plan.ambience.forEach { item ->
                    out += Item(
                        lane = SynthLane.AMB,
                        label = item.tag,
                        resolved = AudioLibrary.resolve(appContext, item.tag) != null,
                        desc = item.desc,
                        bgm = null,
                    )
                }
                plan.bgm.forEach { item ->
                    val q = AudioBgmPicker.queryOf(item)
                    out += Item(
                        lane = SynthLane.BGM,
                        label = item.displayName,
                        resolved = AudioBgmPicker.pickLocal(appContext, q) != null,
                        desc = item.desc,
                        bgm = q,
                    )
                }
                plan.sfx.forEach { item ->
                    out += Item(
                        lane = SynthLane.SFX,
                        label = item.tag,
                        resolved = AudioLibrary.resolve(appContext, item.tag) != null,
                        desc = item.desc,
                        bgm = null,
                    )
                }
                out.distinctBy { it.lane to it.label }
            }
        }
        return AudioLaneScan.scan(appContext, texts).map { (lane, label, resolved) ->
            Item(lane, label, resolved, "", null)
        }
    }

    private suspend fun loadPlan(chapterKey: String, book: String): AudioPlan? {
        if (book.isBlank() || chapterKey.isBlank()) return null
        val url = chapterKey.substringBeforeLast('|', "")
        val idx = chapterKey.substringAfterLast('|', "").toIntOrNull() ?: return null
        if (url.isBlank()) return null
        return runCatching { AudioPlanStore.load(book, url, idx) }.getOrNull()
    }

    private companion object {
        /** 等待本章全部条目到终态的上限 */
        const val AWAIT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}