package io.legado.app.help.readaloud.audio

import android.app.Application
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.domain.model.settings.ReadAloudSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * B33 · 四轨音频引擎（音频小闭环版）。
 *
 * 职责：在朗读人声（HttpReadAloudService 既有 dialogue 轨）之上叠加——
 *   · ambience 环境底噪（loop）              → 1 个 ExoPlayer
 *   · bgm 背景音乐（loop + 行数到期淡出；BGM 起乐时环境暂停）→ 1 个 ExoPlayer
 *   · sfx 音效（点状，低延迟池）              → ExoPlayer 复用池（P1.6.2：可挂 LoudnessEnhancer）
 *
 * 设计要点：
 * - 引擎自主驱动：外部只喂「当前剧本行」(onCue) 与少量生命周期事件；ticker(200ms) 负责
 *   随播放状态起停、音量渐变、淡入淡出、BGM 持续行数收尾 —— 保证四条轨「联动」而非各播各的。
 * - 不抢音频焦点（音频焦点仍由人声播放器统一管理）。
 * - 「自研混音器扩展位」：上层只依赖本类接口；将来升级为 PCM 混音器时替换实现即可，
 *   HttpReadAloudService / 设置 / UI 零改动（见 B33 施工方案 §3）。
 *
 * 匹配层（B33.3d）：[AudioRuleEngine] —— 条目规则（含命中回填） > 内置规则包（音效/环境/BGM词典） > CNB 意图规则（AC）；
 * 素材解析 = AudioLibrary registry 索引（soundId 直连 / 名称链；未加载时回退目录直扫）。
 * B33.3c：缺失 → onMissing 回调（自动合成补缺）；闸门（间隔/冷却/驻留）滑条化。
 * B33.4b：本章有 Ai 计划（[AudioPlanStore]）→ 计划层驱动（环境切换/BGM/音效）；无计划 → 规则层（兜底四条件）。
 */
class AudioLaneEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    /** 服务处于「播放」状态（未暂停） */
    private val serviceActive: () -> Boolean,
    /** B33.3c：素材缺失上报（kind=音效/环境/BGM + 生成描述；接自动合成补缺队列） */
    private val onMissing: (String, String, String) -> Unit = { _, _, _ -> },
) {

    data class CueInfo(
        val text: String,
        val isChapterTitle: Boolean = false,
        /** B33.4b：段序号（=剧本行/计划锚点；标题行为 0） */
        val para: Int = 0,
    )

    data class LaneConfig(
        val enabled: Boolean = true,
        val sfxVolume: Float = 0.8f,
        val ambVolume: Float = 0.35f,
        val bgmVolume: Float = 0.25f,
        /** B33.3c 闸门（秒 → ms，applySettings 换算） */
        val sfxMinGapMs: Long = 6_000L,
        val sfxCooldownMs: Long = 60_000L,
        val bgmCooldownMs: Long = 150_000L,
        val ambMinDwellMs: Long = 25_000L,
        /** B34.2·⑨：读速估算（字/秒；句内位置→延迟换算用） */
        val charsPerSec: Float = 4.2f,
    )

    /** media3 版音频属性（ExoPlayer 轨用） */
    private val media3AudioAttributes = Media3AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private var config = LaneConfig()

    private val bgmLane = LoopLane()
    private val ambLane = LoopLane()

    /** P1.6.2：点状音效引擎——ExoPlayer 一次性复用池（≤6，LRU；可挂 LoudnessEnhancer） */
    private val sfxPool = SfxPool()

    /** P1.6.2：响度均衡开关（与人声/循环轨同一开关；10s 节流刷新） */
    private var loudnessOn = false
    private var loudnessCheckedAt = 0L
    private val loudnessRepo: TtsServerCenterRepository? by lazy {
        (appContext.applicationContext as? Application)?.let { TtsServerCenterRepository(it) }
    }

    private var desiredAmbience: String? = null
    private var ambienceDwellAt: Long = 0L
    private var desiredBgm: String? = null
    private var bgmHoldRemaining: Int = 0

    private var lastSfxAt: Long = 0L
    private val lastSfxByKeyword = HashMap<String, Long>()
    private val lastBgmByKeyword = HashMap<String, Long>()
    private val missingLogged = HashSet<String>()

    /** P1.5 · 跨栏忽略日志去重（轨+词，每条一次） */
    private val crossLaneLogged = HashSet<String>()

    private var lastCueIndex: Int = -1

    /** B33.4-前置：当前章「第N章」标签（日志前缀）与静默标志（预合成完成的章播放时静默） */
    private var chapterLabel: String = ""
    private var quietChapter: Boolean = false

    /** B33.4b：当前章音频计划（Ai 驱动；null=规则兜底） */
    @Volatile private var audioPlan: AudioPlan? = null
    private var planAmbByPara: Map<Int, AudioPlanItem> = emptyMap()
    private var planBgmByPara: Map<Int, AudioPlanItem> = emptyMap()
    private var planSfxByPara: Map<Int, List<AudioPlanItem>> = emptyMap()

    /** 循环轨解析失败的重试闸门（防每 tick 扫描；文件落库后自动接上） */
    private var ambMissRetryAt: Long = 0L
    private var bgmMissRetryAt: Long = 0L

    init {
        // 素材库索引预热
        runCatching { AudioLibrary.warmUp(appContext) }
        // P1：词网预热（索引 + 本地加词；顺带清理旧链缓存）
        runCatching { AudioNetStore.warmUp(appContext) }
        // B33.4b：音频计划存储上下文（播放侧读取计划用）
        runCatching { AudioPlanStore.remember(appContext) }
        // P1.6.2：声效响度均衡上下文（响度测量/查询用）
        runCatching { SfxLoudnessNormalizer.remember(appContext) }
    }

    private val ticker: Job = scope.launch {
        while (isActive) {
            runCatching { tick() }
            delay(200)
        }
    }

    fun applySettings(settings: ReadAloudSettings) {
        config = LaneConfig(
            enabled = settings.alEnabled,
            sfxVolume = settings.alSfxVolume.coerceIn(0, 100) / 100f,
            ambVolume = settings.alAmbVolume.coerceIn(0, 100) / 100f,
            bgmVolume = settings.alBgmVolume.coerceIn(0, 100) / 100f,
            sfxMinGapMs = settings.alSfxMinGapS.coerceIn(0, 30) * 1000L,
            sfxCooldownMs = settings.alSfxCooldownS.coerceIn(0, 300) * 1000L,
            bgmCooldownMs = settings.alBgmCooldownS.coerceIn(0, 600) * 1000L,
            ambMinDwellMs = settings.alAmbDwellS.coerceIn(0, 120) * 1000L,
            charsPerSec = estimateCharsPerSec(settings),
        )
        // P1.6.2：响度均衡开关（与「朗读设置→响度均衡」同源；设置变化即时刷新）
        refreshLoudnessFlag(force = true)
        if (!config.enabled) resetAll()
    }

    /** P1.6.2：响度均衡开关刷新（10s 节流；tick 兜底——运行中改开关最多 10s 生效） */
    private fun refreshLoudnessFlag(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - loudnessCheckedAt < LOUDNESS_CHECK_MS) return
        loudnessCheckedAt = now
        loudnessOn = runCatching { loudnessRepo?.readLoudnessBalanceNow() ?: false }.getOrDefault(false)
    }

    /** B34.2·⑨：读速估算（字/秒）——基准 × 语速档；v1 估算（实际音频时长校准留 v2） */
    private fun estimateCharsPerSec(settings: ReadAloudSettings): Float {
        val rate = settings.ttsSpeechRate.coerceIn(0, 10)
        val factor = if (settings.ttsFollowSys) 1f else 0.5f + rate / 10f
        return BASE_CHARS_PER_SEC * factor
    }

    /** 换章/重新播放：全轨淡出重置（新章的行会在 onCue 里重新驱动） */
    fun onChapterStarted(label: String? = null, quiet: Boolean = false) {
        resetAll()
        lastCueIndex = -1
        if (label != null) chapterLabel = label
        quietChapter = quiet
        audioPlan = null
        planAmbByPara = emptyMap()
        planBgmByPara = emptyMap()
        planSfxByPara = emptyMap()
    }

    /** B33.4b：装载本章音频计划（服务异步推送；null=回退规则层） */
    fun onChapterPlan(plan: AudioPlan?) {
        audioPlan = plan
        planAmbByPara = plan?.ambience?.associateBy { it.para }.orEmpty()
        planBgmByPara = plan?.bgm?.associateBy { it.para }.orEmpty()
        planSfxByPara = plan?.sfx?.groupBy { it.para }.orEmpty()
    }

    /** 暂停——ticker 会依据 serviceActive 自动压低；此处仅加速一次对账 */
    fun onPaused() {
        runCatching { tick() }
    }

    fun onResumed() {
        runCatching { tick() }
    }

    /** 服务停止 */
    fun onStopped() {
        resetAll()
    }

    /** 当前剧本行（段）开始。index 与播放队列 / 音频缓存同一套序号；同一 index 幂等。 */
    fun onCue(index: Int, cue: CueInfo?) {
        if (!config.enabled || cue == null) return
        if (cue.isChapterTitle) {
            // B33.3e：章标题行只走「应用于标题」的自定规则（不占正文行游标）
            if (cue.text.isNotBlank()) driveCue(index, cue.text, isTitle = true)
            return
        }
        if (index == lastCueIndex) return
        lastCueIndex = index
        val text = cue.text
        if (text.isBlank()) return
        driveCue(index, text, isTitle = false, para = cue.para)
    }

    /** 命中驱动（正文行 / 章标题共用）。P1.6.2+：本地层只驱动音效（环境/BGM 交由 AI 导演判定） */
    private fun driveCue(index: Int, text: String, isTitle: Boolean, para: Int = 0) {
        // B33.4b：本章有 Ai 计划 → 计划层接管（无计划=规则层；「四条件兜底」天然成立）
        val plan = audioPlan
        if (plan != null && !isTitle && para > 0) {
            driveCueByPlan(index, para, text)
            return
        }
        // P1.6.2+：本地扫描/规则层只服务音效（环境/BGM 由 AI 判定；本地不再兜底）
        AudioRuleEngine.pick(appContext, DemoLanes.Lane.SFX, text, isTitle)?.let { pick ->
            val keyword = pick.resolved?.asset?.name ?: pick.hit.label
            if (allowSfx(keyword)) {
                val now = System.currentTimeMillis()
                lastSfxAt = now
                lastSfxByKeyword[keyword] = now
                if (playSfx(pick, text.length)) {
                    laneLog(index, "音效=$keyword（${pick.hit.source.label}）")
                }
            } else {
                laneLog(index, "音效=$keyword（闸门跳过）")
            }
        }
    }

    /** B33.4b：Ai 计划驱动（环境切换 / BGM 起止 / 音效点事件；闸门与规则层同款，保证联动与防轰炸） */
    private fun driveCueByPlan(index: Int, para: Int, text: String) {
        // 1) 环境：计划切换点（最短驻留防抖与规则层一致）
        planAmbByPara[para]?.let { item ->
            val keyword = item.tag
            if (keyword.isNotBlank() && keyword != desiredAmbience) {
                val now = System.currentTimeMillis()
                if (desiredAmbience == null || now - ambienceDwellAt >= config.ambMinDwellMs) {
                    desiredAmbience = keyword
                    ambienceDwellAt = now
                    ambMissRetryAt = 0L
                    laneLog(index, "环境→$keyword（Ai导演）")
                } else {
                    laneLog(index, "环境=$keyword（驻留未到，跳过）")
                }
            }
        }

        // 2) BGM：起乐 / 刷新持续；无触发 → 行数倒计时，归零淡出
        val bgmItem = planBgmByPara[para]
        if (bgmItem != null) {
            // P1.6.1：四段关键词选曲——本地库（严格→宽松）→ 远程词网 BGM 池（命中即异步下载）
            // → tag 直连兜底（rules 计划/旧格式）→ 缺失合成
            val q = AudioBgmPicker.queryOf(bgmItem)
            val local = AudioBgmPicker.pickLocal(appContext, q)
            val net = if (local == null) AudioBgmPicker.pickNet(q) else null
            if (local == null && net != null) {
                scope.launch { runCatching { AudioNetStore.fetchAsset(appContext, net) } }
            }
            val keyword = local?.asset?.name
                ?: AudioLibrary.resolve(appContext, bgmItem.tag)?.asset?.name
                ?: net?.let { n -> AudioLibrary.resolve(appContext, n.name)?.asset?.name ?: n.name }
                ?: AudioNetStore.lookup(bgmItem.tag)?.let { n ->
                    AudioLibrary.resolve(appContext, n.name)?.asset?.name ?: n.name
                }
                ?: bgmItem.displayName
            if (keyword != desiredBgm) {
                val now = System.currentTimeMillis()
                if (now - (lastBgmByKeyword[keyword] ?: 0L) >= config.bgmCooldownMs) {
                    desiredBgm = keyword
                    bgmHoldRemaining = bgmItem.hold.coerceAtLeast(1)
                    lastBgmByKeyword[keyword] = now
                    bgmMissRetryAt = 0L
                    laneLog(index, "BGM=$keyword（持续 $bgmHoldRemaining 行 · Ai导演）")
                } else {
                    laneLog(index, "BGM=$keyword（冷却中，跳过）")
                }
            } else {
                bgmHoldRemaining = maxOf(bgmHoldRemaining, bgmItem.hold.coerceAtLeast(1))
            }
        } else if (desiredBgm != null) {
            bgmHoldRemaining--
            if (bgmHoldRemaining <= 0) {
                laneLog(index, "BGM 到期淡出（$desiredBgm）")
                desiredBgm = null
            }
        }

        // 3) 音效：点事件逐条（同款闸门：全局间隔 + 同素材冷却）
        planSfxByPara[para]?.forEach { item ->
            // P1：词网归一——自由说法拉回库内规范名；词网有货则异步下载（落库后自动接上）
            // P1.6.2+：本地优先——本地（主名/别名/用户挂词）先于远程词网；词网仅同栏
            val localFirst = resolveLocalFirst(item.tag, SynthLane.SFX)
            val net = localFirst.second
            if (net == null && localFirst.first == null) logCrossLaneIgnore(SynthLane.SFX, item.tag)
            val keyword = localFirst.first?.asset?.name ?: net?.name ?: item.tag
            if (!allowSfx(keyword)) {
                laneLog(index, "音效=$keyword（闸门跳过）")
                return@forEach
            }
            var resolved = localFirst.first ?: net?.let {
                AudioLibrary.resolveForLane(appContext, it.name, SynthLane.SFX)
            }
            if (resolved == null && net != null) {
                scope.launch { runCatching { AudioNetStore.fetchAsset(appContext, net) } }
            }
            if (resolved == null) {
                markMissing("音效", keyword, item.desc)
                return@forEach
            }
            val now = System.currentTimeMillis()
            lastSfxAt = now
            lastSfxByKeyword[keyword] = now
            // B34.2·⑨：显式延迟优先；否则按句内位置（AI 前/中/后或规则命中位）换算
            val delayMs = item.delayMs.takeIf { it > 0 }
                ?: AudioPositions.delayMs(text.length, item.posRatio, config.charsPerSec)
            if (fireSfx(resolved, gain = 1.0f, delayMs = delayMs)) {
                laneLog(index, "音效=$keyword（Ai导演）")
            }
        }
    }

    fun release() {
        ticker.cancel()
        bgmLane.release()
        ambLane.release()
        sfxPool.release()
    }

    // ---------------------------------------------------------------- tick

    private fun tick() {
        if (!config.enabled) return
        refreshLoudnessFlag()
        val active = serviceActive()

        // 环境轨：目标 = 轨音量；暂停/停播 → 0；BGM 起乐时固定暂停环境（结束后恢复；B34 已拔除选项）
        val bgmAudible = desiredBgm != null && (bgmLane.player?.volume ?: 0f) > 0.02f
        driveLane(
            lane = ambLane,
            desired = desiredAmbience,
            target = if (active && !bgmAudible) config.ambVolume else 0f,
            active = active,
            kind = "环境",
        )

        // BGM 轨：轨音量
        val bgmTarget = if (active) config.bgmVolume else 0f
        driveLane(
            lane = bgmLane,
            desired = desiredBgm,
            target = bgmTarget,
            active = active,
            kind = "BGM",
        )
    }

    private fun driveLane(
        lane: LoopLane,
        desired: String?,
        target: Float,
        active: Boolean,
        kind: String,
    ) {
        val player = lane.player
        if (desired == null) {
            if (player != null) {
                lane.forEachPlayer { approach(it, 0f) }
                if ((lane.player?.volume ?: 0f) <= 0.015f) lane.stopAndClear()
            }
            return
        }
        if (active && lane.keyword != desired) {
            val now = System.currentTimeMillis()
            val retryAt = if (kind == "BGM") bgmMissRetryAt else ambMissRetryAt
            if (now >= retryAt) {
                // P1.5：环境轨同栏严格（异栏素材忽略 → 转补缺）；BGM 维持既有链
                // P1.6.2+：本地优先——本地（主名/别名 → 用户挂词桥）先于词网
                var resolved = if (kind == "环境") {
                    resolveLocalFirst(desired, SynthLane.AMB).first
                } else {
                    AudioLibrary.resolve(appContext, desired)
                        ?: AudioNetStore.localAssetName(desired)?.let { AudioLibrary.resolve(appContext, it) }
                }
                if (resolved == null) {
                    // P1：词网归一——有货则异步下载（落库后由 10s 重试自动接上）
                    val net = if (kind == "环境") {
                        AudioNetStore.lookupForLane(desired, SynthLane.AMB).also {
                            if (it == null) logCrossLaneIgnore(SynthLane.AMB, desired)
                        }
                    } else {
                        AudioNetStore.lookup(desired)
                    }
                    if (net != null) {
                        scope.launch { runCatching { AudioNetStore.fetchAsset(appContext, net) } }
                        resolved = if (kind == "环境") {
                            AudioLibrary.resolveForLane(appContext, net.name, SynthLane.AMB)
                        } else {
                            AudioLibrary.resolve(appContext, net.name)
                        }
                    }
                }
                if (resolved != null) {
                    lane.playKeyword(desired, resolved.file, resolved.asset)
                    // P1.6.2：响度均衡——未见样本则后台补测（本次按当前档播，测完自动接上）
                    runCatching { SfxLoudnessNormalizer.ensureMeasured(appContext, resolved.file) }
                    if (kind == "BGM") bgmMissRetryAt = 0L else ambMissRetryAt = 0L
                } else {
                    // 解析失败：10s 内不重复扫描；文件（合成/下载）落库后自动接上
                    if (kind == "BGM") bgmMissRetryAt = now + MISS_RETRY_MS else ambMissRetryAt = now + MISS_RETRY_MS
                    markMissing(kind, desired)
                }
            }
        }
        val p = lane.player ?: return
        if (active && p.currentMediaItem != null && !p.isPlaying) {
            runCatching { p.play() }
        }
        // 条目参数（音量/音速/音高）：实时读取（编辑后下一 tick 起生效）；双播放器同步
        val meta = lane.asset?.relPath?.let { rel -> AudioLibrary.metaByRelPath(rel) }
        val speed = (meta?.speed ?: 1f).coerceIn(0.5f, 2.0f)
        val pitch = (meta?.pitch ?: 1f).coerceIn(0.5f, 2.0f)
        lane.forEachPlayer { pl ->
            val cur = pl.playbackParameters
            if (cur.speed != speed || cur.pitch != pitch) {
                pl.playbackParameters = PlaybackParameters(speed, pitch)
            }
        }
        val volume = (if (active) target else 0f) * (meta?.volume ?: 1f)
        // P1.6.2：声效响度均衡——trim 并入总增益（正增益走增强器、负增益走 volume）；
        // 随「响度均衡」开关（同一体系）；未学习时 trim=0，后台补测后自动接上。
        val trimDb = loudnessTrimDb(lane.asset?.relPath)
        val gainTotal = volume * trimLinear(trimDb)
        val base = gainTotal.coerceIn(0f, 1f)
        val boostDb = boostDbOf(gainTotal)

        // B33.4 前置：循环交叉淡化（双播放器无缝衔接；时长未知/过短时退化为单曲循环）
        // P1.6·⑩：先推进「切换」交叉淡化（旧轨→新轨 1.2s 等功率交接），再走「循环内」交叉
        val fade = lane.advanceSwitchCrossfade(allow = active && base > 0.02f)
            ?: lane.advanceCrossfade(allow = active && base > 0.02f)
        if (fade != null) {
            lane.setVolumesDirect(base * (1f - fade), base * fade)
            applyLoopBoost(lane, p, boostDb)
            return
        }
        approach(p, base)
        applyLoopBoost(lane, p, boostDb)
        // 暂停 / 互斥让位到静音时挂起播放器（恢复由上方 play() 逻辑拉起）
        if (base <= 0.015f && p.volume <= 0.015f && p.isPlaying) {
            runCatching { p.pause() }
        }
    }

    /** 每 tick 向目标音量逼近一步（步长 0.08/200ms ≈ 0.4/s，淡入淡出 0.5~1s 级） */
    private fun approach(player: ExoPlayer, target: Float) {
        val current = player.volume
        if (abs(target - current) < 0.005f) {
            player.volume = target
            return
        }
        val step = if (target > current) 0.08f else -0.08f
        player.volume = (current + step).coerceIn(0f, 1f)
    }

    /** 超 100% 增益（含响度 trim 正部分）：ExoPlayer 上限 1.0，用 LoudnessEnhancer 补增益（≤ +12dB；按播放器各持一个） */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun applyLoopBoost(lane: LoopLane, player: ExoPlayer, boostDb: Float) {
        runCatching {
            val e = lane.enhancerFor(player)
            if (boostDb <= 0.05f) {
                e.enabled = false
                return
            }
            e.setTargetGain((boostDb * 100f).toInt().coerceIn(0, 1200))
            e.enabled = true
        }
    }

    /** P1.6.2：该素材当前响度 trim（dB；未开「响度均衡」/未学习 → 0） */
    private fun loudnessTrimDb(relPath: String?): Float {
        if (!loudnessOn || relPath.isNullOrBlank()) return 0f
        return runCatching { SfxLoudnessNormalizer.trimDbFor(relPath) }.getOrDefault(0f)
    }

    /** dB → 线性增益 */
    private fun trimLinear(db: Float): Float =
        if (db > -0.001f && db < 0.001f) 1f else 10.0.pow(db / 20.0).toFloat()

    /** 总线性增益中超过 1.0 的部分 → 增强器 dB（≤12） */
    private fun boostDbOf(total: Float): Float =
        if (total > 1.0001f) (20.0 * log10(total.toDouble())).coerceIn(0.0, 12.0).toFloat() else 0f

    // ---------------------------------------------------------------- sfx

    /** P1.6.2+：本地优先解析——本地（主名/别名 → 用户挂词桥）优先；未命中再查同栏词网 */
    private fun resolveLocalFirst(
        word: String,
        lane: SynthLane,
    ): Pair<AudioLibrary.ResolvedAsset?, AudioNetStore.NetAsset?> {
        AudioLibrary.resolveForLane(appContext, word, lane)?.let { return it to null }
        AudioNetStore.localAssetName(word)?.let { name ->
            AudioLibrary.resolveForLane(appContext, name, lane)?.let { return it to null }
        }
        return null to AudioNetStore.lookupForLane(word, lane)
    }

    /** B33.3c：闸门滑条化（全局最小间隔 + 同素材冷却；0=不限） */
    private fun allowSfx(keyword: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSfxAt < config.sfxMinGapMs) return false
        if (now - (lastSfxByKeyword[keyword] ?: 0L) < config.sfxCooldownMs) return false
        return true
    }

    private fun playSfx(pick: AudioRuleEngine.Picked, textLen: Int): Boolean {
        val resolved = pick.resolved ?: AudioRuleEngine.resolveHit(appContext, pick.hit)
        if (resolved == null) {
            markMissing("音效", pick.hit.label)
            return false
        }
        // B34.2·⑨：显式延迟优先；否则按句内命中位置换算
        val delayMs = pick.hit.delayMs.takeIf { it > 0 }
            ?: AudioPositions.delayMs(textLen, pick.hit.posRatio, config.charsPerSec)
        return fireSfx(resolved, pick.hit.gain, delayMs)
    }

    /**
     * B33.4b：播放体（规则层 pick 与计划条目共用）。
     * P1.6.2：SoundPool → ExoPlayer 复用池（可挂 LoudnessEnhancer，供响度均衡）。
     */
    private fun fireSfx(resolved: AudioLibrary.ResolvedAsset, gain: Float, delayMs: Long): Boolean {
        val file = resolved.file
        if (!runCatching { file.isFile && file.length() > 0L }.getOrDefault(false)) return false
        // P1.6.2：响度均衡——未见样本则后台补测（本次按当前档播，测完自动接上；不阻塞）
        runCatching { SfxLoudnessNormalizer.ensureMeasured(appContext, file) }
        val fire: () -> Unit = { playSfxNow(resolved, gain) }
        if (delayMs > 0) {
            scope.launch {
                delay(delayMs)
                fire()
            }
        } else {
            fire()
        }
        return true
    }

    /** 点状音效实际发声：取池实例 → 体积/速率/响度增益 → 起播（失败静默） */
    private fun playSfxNow(resolved: AudioLibrary.ResolvedAsset, gain: Float) {
        runCatching {
            // 条目参数实时读取（音量/音速/音高；速率≈原 SoundPool rate=speed×pitch 听感）
            val meta = AudioLibrary.metaByRelPath(resolved.asset.relPath) ?: resolved.asset
            val rate = (meta.speed * meta.pitch).coerceIn(0.5f, 2.0f)
            val trimDb = loudnessTrimDb(resolved.asset.relPath)
            val total = (config.sfxVolume * gain * meta.volume) * trimLinear(trimDb)
            val player = sfxPool.acquire()
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(resolved.file)))
            player.volume = total.coerceIn(0f, 1f)
            player.playbackParameters = PlaybackParameters(rate, rate)
            sfxPool.setBoost(player, boostDbOf(total))
            player.prepare()
            player.play()
        }
    }

    // ---------------------------------------------------------------- misc

    private fun markMissing(kind: String, keyword: String, desc: String = "") {
        val key = "$kind|$keyword"
        if (missingLogged.add(key)) {
            if (!quietChapter) {
                AppLog.putAudio("【音效与背景音${chapterSuffix()}】缺失 $kind「$keyword」")
            }
            runCatching { onMissing(kind, keyword, desc) }
        }
    }

    private fun chapterSuffix(): String = if (chapterLabel.isBlank()) "" else "·$chapterLabel"

    /**
     * P1.5 · 跨栏素材忽略日志（同轨未命中、异轨有材 → 转补缺合成）。
     * 每条（轨+词）只记一次；预合成完成的章静默（预合成侧另有日志）。
     */
    private fun logCrossLaneIgnore(lane: SynthLane, keyword: String) {
        if (keyword.isBlank()) return
        val key = "${lane.name}|$keyword"
        if (crossLaneLogged.contains(key)) return
        val msg = AudioNetStore.crossLaneMessage(lane, keyword) ?: return
        crossLaneLogged.add(key)
        if (quietChapter) return
        AppLog.putAudio("【音效与背景音${chapterSuffix()}】$msg")
    }

    /** 章级四轨日志（预合成完成的章静默；格式：【音效与背景音·第N章】#i ×××） */
    private fun laneLog(index: Int, msg: String) {
        if (quietChapter) return
        AppLog.putAudio("【音效与背景音${chapterSuffix()}】#$index $msg")
    }

    private fun resetAll() {
        desiredAmbience = null
        desiredBgm = null
        bgmHoldRemaining = 0
        ambMissRetryAt = 0L
        bgmMissRetryAt = 0L
        bgmLane.stopAndClear()
        ambLane.stopAndClear()
    }

    /** 单条 loop 轨（环境/BGM 共用）：双播放器交叉淡化循环（≈1.2s 重叠），音量由 ticker 逼近 */
    private inner class LoopLane {
        var keyword: String? = null
            private set
        var asset: AudioLibrary.AudioAsset? = null
            private set
        private var playerA: ExoPlayer? = null
        private var playerB: ExoPlayer? = null
        private var activeIsA = true
        private var crossfading = false
        private var fileUri: Uri? = null
        private var lastCrossPos = -1L

        /** P1.6·⑩：切换交叉淡化状态（旧轨→新轨 1.2s 等功率交接） */
        private var switching = false
        private var switchAt = 0L
        private val enhancers = HashMap<ExoPlayer, LoudnessEnhancer>()

        /** 当前出声（主）播放器 */
        val player: ExoPlayer? get() = if (activeIsA) playerA else playerB

        private val standby: ExoPlayer? get() = if (activeIsA) playerB else playerA

        fun forEachPlayer(block: (ExoPlayer) -> Unit) {
            playerA?.let { runCatching { block(it) } }
            playerB?.let { runCatching { block(it) } }
        }

        fun setVolumesDirect(activeVol: Float, standbyVol: Float) {
            player?.let { runCatching { it.volume = activeVol.coerceIn(0f, 1f) } }
            standby?.let { runCatching { it.volume = standbyVol.coerceIn(0f, 1f) } }
        }

        @androidx.annotation.OptIn(UnstableApi::class)
        fun enhancerFor(pl: ExoPlayer): LoudnessEnhancer =
            enhancers[pl] ?: LoudnessEnhancer(pl.audioSessionId).also { enhancers[pl] = it }

        fun playKeyword(kw: String, file: File, asset: AudioLibrary.AudioAsset?) {
            this.asset = asset
            if (playerA == null) playerA = buildPlayer()
            if (playerB == null) playerB = buildPlayer()
            val a = playerA ?: return
            if (keyword == kw && a.currentMediaItem != null) {
                val p = player ?: return
                if (!p.isPlaying) runCatching { p.play() }
                return
            }
            // P1.6·⑩：切换交叉淡化——旧轨留主位淡出、新轨上备胎淡入（1.2s，与「循环内交叉」同款映射）；
            // 章末/停止（stopAndClear）维持硬切；连切时先把上一轮交接即时收尾。
            val cur = player
            if (!switching && keyword != null && cur?.currentMediaItem != null) {
                val nxt = standby ?: return
                crossfading = false
                lastCrossPos = -1L
                fileUri = Uri.fromFile(file)
                runCatching {
                    nxt.stop()
                    nxt.clearMediaItems()
                    nxt.setMediaItem(MediaItem.fromUri(fileUri!!))
                    nxt.volume = 0f
                    nxt.prepare()
                    nxt.play()
                }
                switching = true
                switchAt = System.currentTimeMillis()
                keyword = kw
                return
            }
            if (switching) {
                // 连切/异常残留：状态复位（随后硬重启接管）
                switching = false
            }
            runCatching {
                playerA?.stop()
                playerA?.clearMediaItems()
                playerB?.stop()
                playerB?.clearMediaItems()
            }
            activeIsA = true
            crossfading = false
            lastCrossPos = -1L
            fileUri = Uri.fromFile(file)
            runCatching {
                a.setMediaItem(MediaItem.fromUri(fileUri!!))
                a.volume = 0f
                a.prepare()
            }
            keyword = kw
            val p = player ?: return
            if (!p.isPlaying) runCatching { p.play() }
        }

        /**
         * P1.6·⑩：切换交叉淡化推进；返回 0..1 交接进度（非 null=正在交接）。
         * 映射与「循环内交叉」一致：主位（旧轨）fade-out、备胎（新轨）fade-in；完成时新轨升主、旧轨停止。
         * allow=false（暂停/静音/拆场）→ 即时收尾（章末/停止维持硬切口径）。
         */
        fun advanceSwitchCrossfade(allow: Boolean): Float? {
            if (!switching) return null
            if (!allow) {
                activeIsA = !activeIsA
                runCatching { standby?.stop(); standby?.clearMediaItems() }
                switching = false
                return null
            }
            val fade = ((System.currentTimeMillis() - switchAt).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
            if (fade >= 1f) {
                activeIsA = !activeIsA // 新轨升任主位（standby 变为旧轨）
                runCatching { standby?.stop(); standby?.clearMediaItems() }
                switching = false
                return null
            }
            return fade
        }

        /**
         * 交叉淡化推进；返回 0..1 的交接进度（非 null=正在交叉）。
         * allow=false（暂停/静音）时取消交叉；时长未知或过短（<2×淡化窗）时退化为单曲循环。
         */
        fun advanceCrossfade(allow: Boolean): Float? {
            val a = player ?: return null
            val b = standby ?: return null
            if (!allow || keyword == null || fileUri == null) {
                if (crossfading) {
                    crossfading = false
                    runCatching { b.stop(); b.clearMediaItems() }
                }
                lastCrossPos = -1L
                return null
            }
            val dur = runCatching { a.duration }.getOrDefault(C.TIME_UNSET)
            if (dur == C.TIME_UNSET || dur < CROSSFADE_MS * 2) return null
            val pos = runCatching { a.currentPosition }.getOrDefault(0L)
            if (!crossfading) {
                if (pos >= dur - CROSSFADE_MS) {
                    val ok = runCatching {
                        b.stop()
                        b.clearMediaItems()
                        b.setMediaItem(MediaItem.fromUri(fileUri!!))
                        b.seekTo(0)
                        b.volume = 0f
                        b.prepare()
                        b.play()
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        crossfading = true
                        lastCrossPos = pos
                    }
                }
                return null
            }
            // 交叉中：主播放器（REPEAT_ONE）循环回绕 → 交接完成，备胎升任主播放器
            if (pos < lastCrossPos) {
                runCatching { a.stop(); a.clearMediaItems() }
                activeIsA = !activeIsA
                crossfading = false
                lastCrossPos = -1L
                return null
            }
            lastCrossPos = pos
            return ((pos - (dur - CROSSFADE_MS)).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
        }

        private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(appContext).build().also {
            // 不抢音频焦点：焦点由人声播放器统一管理
            it.setAudioAttributes(media3AudioAttributes, false)
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.volume = 0f
        }

        fun stopAndClear() {
            keyword = null
            asset = null
            crossfading = false
            lastCrossPos = -1L
            fileUri = null
            switching = false
            forEachPlayer { p ->
                runCatching {
                    p.stop()
                    p.clearMediaItems()
                    p.volume = 0f
                }
            }
            enhancers.values.forEach { runCatching { it.enabled = false } }
        }

        fun release() {
            forEachPlayer { p -> runCatching { p.release() } }
            playerA = null
            playerB = null
            keyword = null
            asset = null
            crossfading = false
            switching = false
            enhancers.values.forEach { runCatching { it.release() } }
            enhancers.clear()
        }
    }

    /**
     * P1.6.2 · 点状音效引擎（替换 SoundPool）：ExoPlayer 一次性复用池（≤[SFX_POOL_MAX]，LRU）。
     * 每实例可挂 LoudnessEnhancer（响度均衡）+ PlaybackParameters(speed/pitch) + volume；
     * 不抢音频焦点（与人声/循环轨纪律一致）；复用常驻，stop/clear 后即换曲。
     */
    private inner class SfxPool {
        private val players = LinkedHashMap<ExoPlayer, Long>() // 实例 → 最近使用时间
        private val enhancers = HashMap<ExoPlayer, LoudnessEnhancer>()
        private val boostDb = HashMap<ExoPlayer, Float>()

        /** 取实例：优先空闲（IDLE/ENDED）；不足新建；满 → LRU 复用 */
        fun acquire(): ExoPlayer {
            players.entries.firstOrNull { (p, _) -> isIdle(p) }?.let { (p, _) ->
                players[p] = System.currentTimeMillis()
                return p
            }
            if (players.size < SFX_POOL_MAX) {
                val p = buildPlayer()
                players[p] = System.currentTimeMillis()
                return p
            }
            val lru = players.entries.minByOrNull { it.value }!!.key
            players[lru] = System.currentTimeMillis()
            return lru
        }

        /** 设置该实例的增强器增益（dB；audioSession 未就绪时先记录，就绪回调后应用） */
        fun setBoost(player: ExoPlayer, db: Float) {
            boostDb[player] = db
            applyBoostNow(player)
        }

        private fun isIdle(p: ExoPlayer): Boolean = runCatching {
            val st = p.playbackState
            st == Player.STATE_IDLE || st == Player.STATE_ENDED
        }.getOrDefault(true)

        @androidx.annotation.OptIn(UnstableApi::class)
        private fun applyBoostNow(player: ExoPlayer) {
            runCatching {
                val db = boostDb[player] ?: 0f
                val e = enhancerFor(player) ?: return
                if (db <= 0.05f) {
                    e.enabled = false
                    return
                }
                e.setTargetGain((db * 100f).toInt().coerceIn(0, 1200))
                e.enabled = true
            }
        }

        /** 增强器（懒建；audioSessionId 未分配=0 → null，等 onAudioSessionIdChanged 重建） */
        @androidx.annotation.OptIn(UnstableApi::class)
        private fun enhancerFor(player: ExoPlayer): LoudnessEnhancer? =
            enhancers[player] ?: runCatching {
                val sid = player.audioSessionId
                if (sid == 0) null else LoudnessEnhancer(sid).also { enhancers[player] = it }
            }.getOrNull()

        @androidx.annotation.OptIn(UnstableApi::class)
        private fun buildPlayer(): ExoPlayer {
            val p = ExoPlayer.Builder(appContext).build()
            p.setAudioAttributes(media3AudioAttributes, false)
            p.volume = 0f
            p.addListener(object : Player.Listener {
                @androidx.annotation.OptIn(UnstableApi::class)
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    runCatching {
                        enhancers.remove(p)?.release()
                        if (audioSessionId != 0) {
                            enhancers[p] = LoudnessEnhancer(audioSessionId)
                        }
                    }
                    applyBoostNow(p)
                }
            })
            return p
        }

        fun release() {
            players.keys.forEach { p -> runCatching { p.release() } }
            players.clear()
            enhancers.values.forEach { e -> runCatching { e.release() } }
            enhancers.clear()
            boostDb.clear()
        }
    }

    private companion object {
        /** B34.2·⑨：中文朗读基准读速（字/秒，估算） */
        const val BASE_CHARS_PER_SEC = 4.2f

        /** 循环轨解析失败重试间隔（ms） */
        const val MISS_RETRY_MS = 10_000L

        /** 循环交叉淡化时长（ms） */
        const val CROSSFADE_MS = 1_200L

        /** P1.6.2：点状音效复用池容量（拍板 ×4~6，取 6） */
        const val SFX_POOL_MAX = 6

        /** P1.6.2：响度均衡开关刷新节流（ms） */
        const val LOUDNESS_CHECK_MS = 10_000L
    }
}