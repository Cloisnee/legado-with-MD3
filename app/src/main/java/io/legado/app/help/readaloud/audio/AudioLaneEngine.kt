package io.legado.app.help.readaloud.audio

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.SoundPool
import android.net.Uri
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.domain.model.settings.ReadAloudSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * B33 · 四轨音频引擎（音频小闭环版）。
 *
 * 职责：在朗读人声（HttpReadAloudService 既有 dialogue 轨）之上叠加——
 *   · ambience 环境底噪（loop）              → 1 个 ExoPlayer
 *   · bgm 背景音乐（loop + 闪避 + 行数到期淡出）→ 1 个 ExoPlayer
 *   · sfx 音效（点状，低延迟池）              → SoundPool
 *
 * 设计要点：
 * - 引擎自主驱动：外部只喂「当前剧本行」(onCue) 与少量生命周期事件；ticker(200ms) 负责
 *   随播放状态起停、闪避压/放、淡入淡出、BGM 持续行数收尾 —— 保证四条轨「联动」而非各播各的。
 * - 不抢音频焦点（音频焦点仍由人声播放器统一管理）。
 * - 「自研混音器扩展位」：上层只依赖本类接口；将来升级为 PCM 混音器时替换实现即可，
 *   HttpReadAloudService / 设置 / UI 零改动（见 B33 施工方案 §3）。
 *
 * ⚠ 本批为小闭环：匹配规则 = DemoLanes 少量示例；素材解析 = 文件名关键字（B33.2 起换 registry）。
 */
class AudioLaneEngine(
    private val appContext: Context,
    private val scope: CoroutineScope,
    /** 服务处于「播放」状态（未暂停） */
    private val serviceActive: () -> Boolean,
    /** 人声音频当前正在出声（BGM 闪避判定用） */
    private val voiceActive: () -> Boolean,
) {

    data class CueInfo(
        val text: String,
        val isChapterTitle: Boolean = false,
        val emotion: String = "",
    )

    data class LaneConfig(
        val enabled: Boolean = true,
        val sfxVolume: Float = 0.8f,
        val ambVolume: Float = 0.35f,
        val bgmVolume: Float = 0.25f,
        val ducking: Boolean = true,
        val sfxDensity: String = "mid",
    )

    /** media3 版音频属性（ExoPlayer 轨用） */
    private val media3AudioAttributes = Media3AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** 平台版音频属性（SoundPool 用） */
    private val platformAudioAttributes = PlatformAudioAttributes.Builder()
        .setUsage(PlatformAudioAttributes.USAGE_MEDIA)
        .setContentType(PlatformAudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** 闪避系数：有人声时 BGM 压到 40%（≈ 0.12/0.30 的工业口径，见施工方案 §5） */
    private val duckFactor = 0.40f

    /** 环境切换最短驻留（防场景词抖动导致频繁换底） */
    private val ambMinDwellMs = 25_000L

    private var config = LaneConfig()

    private val bgmLane = LoopLane()
    private val ambLane = LoopLane()
    private var soundPool: SoundPool? = null

    private val sfxLoaded = HashMap<String, Int>() // path -> sampleId（0=加载失败）
    private val sfxLoading = HashSet<String>()
    private val sfxById = HashMap<Int, String>()
    private val sfxWaiters = HashMap<String, MutableList<() -> Unit>>()

    private var desiredAmbience: String? = null
    private var ambienceDwellAt: Long = 0L
    private var desiredBgm: String? = null
    private var bgmHoldRemaining: Int = 0

    private var lastSfxAt: Long = 0L
    private val lastSfxByKeyword = HashMap<String, Long>()
    private val lastBgmByKeyword = HashMap<String, Long>()
    private val missingLogged = HashSet<String>()

    private var lastCueIndex: Int = -1

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
            ducking = settings.alDucking,
            sfxDensity = settings.alSfxDensity.lowercase()
                .takeIf { it == "low" || it == "mid" || it == "high" } ?: "mid",
        )
        if (!config.enabled) resetAll()
    }

    /** 换章/重新播放：全轨淡出重置（新章的行会在 onCue 里重新驱动） */
    fun onChapterStarted() {
        resetAll()
        lastCueIndex = -1
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
        if (!config.enabled || cue == null || cue.isChapterTitle) return
        if (index == lastCueIndex) return
        lastCueIndex = index
        val text = cue.text
        if (text.isBlank()) return

        // 1) 环境：命中新场景 → 切换（最短驻留防抖）
        DemoLanes.match(DemoLanes.Lane.AMBIENCE, text)?.let { rule ->
            if (rule.keyword != desiredAmbience) {
                val now = System.currentTimeMillis()
                if (desiredAmbience == null || now - ambienceDwellAt >= ambMinDwellMs) {
                    desiredAmbience = rule.keyword
                    ambienceDwellAt = now
                    AppLog.putAudio("【四轨】#$index 环境→${rule.keyword}")
                } else {
                    AppLog.putAudio("【四轨】#$index 环境=${rule.keyword}（驻留未到，跳过）")
                }
            }
        }

        // 2) BGM：命中 → 起乐 / 刷新持续；无触发 → 行数倒计时，归零淡出
        val bgmRule = DemoLanes.match(DemoLanes.Lane.BGM, text)
        if (bgmRule != null) {
            if (bgmRule.keyword != desiredBgm) {
                val now = System.currentTimeMillis()
                if (now - (lastBgmByKeyword[bgmRule.keyword] ?: 0L) >= bgmRule.cooldownMs) {
                    desiredBgm = bgmRule.keyword
                    bgmHoldRemaining = bgmRule.holdCues.coerceAtLeast(1)
                    lastBgmByKeyword[bgmRule.keyword] = now
                    AppLog.putAudio("【四轨】#$index BGM=${bgmRule.keyword}（持续 ${bgmHoldRemaining} 行）")
                } else {
                    AppLog.putAudio("【四轨】#$index BGM=${bgmRule.keyword}（冷却中，跳过）")
                }
            } else if (bgmRule.holdCues > 0) {
                bgmHoldRemaining = maxOf(bgmHoldRemaining, bgmRule.holdCues)
            }
        } else if (desiredBgm != null) {
            bgmHoldRemaining--
            if (bgmHoldRemaining <= 0) {
                AppLog.putAudio("【四轨】#$index BGM 到期淡出（${desiredBgm}）")
                desiredBgm = null
            }
        }

        // 3) 音效：密度闸门（单条最多 1 个 + 全局间隔 + 素材冷却）
        DemoLanes.match(DemoLanes.Lane.SFX, text)?.let { rule ->
            if (allowSfx(rule)) {
                val now = System.currentTimeMillis()
                lastSfxAt = now
                lastSfxByKeyword[rule.keyword] = now
                if (playSfx(rule)) {
                    AppLog.putAudio("【四轨】#$index 音效=${rule.keyword}")
                }
            } else {
                AppLog.putAudio("【四轨】#$index 音效=${rule.keyword}（密度闸门跳过）")
            }
        }
    }

    fun release() {
        ticker.cancel()
        bgmLane.release()
        ambLane.release()
        soundPool?.release()
        soundPool = null
        sfxLoaded.clear()
        sfxLoading.clear()
        sfxById.clear()
        sfxWaiters.clear()
    }

    // ---------------------------------------------------------------- tick

    private fun tick() {
        if (!config.enabled) return
        val active = serviceActive()
        val voice = voiceActive()

        // 环境轨：目标 = 轨音量；暂停/停播 → 0
        driveLane(
            lane = ambLane,
            desired = desiredAmbience,
            target = if (active) config.ambVolume else 0f,
            active = active,
            kind = "环境",
        )

        // BGM 轨：闪避 = 轨音量 × 0.4（仅人声实际出声时压低）
        val bgmTarget = if (active) {
            config.bgmVolume * (if (config.ducking && voice) duckFactor else 1f)
        } else 0f
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
                approach(player, 0f)
                if (player.volume <= 0.015f) lane.stopAndClear()
            }
            return
        }
        if (active && lane.keyword != desired) {
            val file = TmDemoAssets.findFile(appContext, desired)
            if (file != null) {
                lane.playKeyword(desired, file)
            } else {
                markMissing(kind, desired)
            }
        }
        val p = lane.player ?: return
        if (active && p.currentMediaItem != null && !p.isPlaying) {
            runCatching { p.play() }
        }
        approach(p, if (active) target else 0f)
        if (!active && p.volume <= 0.015f && p.isPlaying) {
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

    // ---------------------------------------------------------------- sfx

    private fun allowSfx(rule: DemoLanes.Rule): Boolean {
        val now = System.currentTimeMillis()
        val minGapMs = when (config.sfxDensity) {
            "low" -> 12_000L
            "high" -> 3_000L
            else -> 6_000L
        }
        if (now - lastSfxAt < minGapMs) return false
        val cooldown = when (config.sfxDensity) {
            "low" -> (rule.cooldownMs * 1.5f).toLong()
            "high" -> (rule.cooldownMs * 0.75f).toLong()
            else -> rule.cooldownMs
        }
        if (now - (lastSfxByKeyword[rule.keyword] ?: 0L) < cooldown) return false
        return true
    }

    private fun playSfx(rule: DemoLanes.Rule): Boolean {
        val file = TmDemoAssets.findFile(appContext, rule.keyword)
        if (file == null) {
            markMissing("音效", rule.keyword)
            return false
        }
        val path = file.absolutePath
        val fire: () -> Unit = {
            val sid = sfxLoaded[path] ?: 0
            if (sid > 0) {
                runCatching {
                    val v = (config.sfxVolume * rule.gain).coerceIn(0f, 1f)
                    ensureSoundPool().play(sid, v, v, 1, 0, 1f)
                }
            }
        }
        val delayedFire: () -> Unit = {
            if (rule.delayMs > 0) {
                scope.launch {
                    delay(rule.delayMs)
                    fire()
                }
            } else {
                fire()
            }
        }
        val loaded = sfxLoaded[path]
        return when {
            loaded != null && loaded > 0 -> {
                delayedFire()
                true
            }
            loaded != null -> false // 已判定不可用（静默跳过）
            sfxLoading.contains(path) -> {
                sfxWaiters.getOrPut(path) { mutableListOf() }.add(delayedFire)
                true
            }
            else -> {
                val pool = ensureSoundPool()
                val id = runCatching { pool.load(path, 1) }.getOrDefault(0)
                if (id == 0) {
                    sfxLoaded[path] = 0
                    false
                } else {
                    sfxLoading.add(path)
                    sfxById[id] = path
                    sfxWaiters.getOrPut(path) { mutableListOf() }.add(delayedFire)
                    true
                }
            }
        }
    }

    private fun ensureSoundPool(): SoundPool = soundPool ?: SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(platformAudioAttributes)
        .build()
        .also { pool ->
            pool.setOnLoadCompleteListener { _, sampleId, status ->
                val p = sfxById.remove(sampleId) ?: return@setOnLoadCompleteListener
                sfxLoading.remove(p)
                if (status == 0) {
                    sfxLoaded[p] = sampleId
                    sfxWaiters.remove(p)?.forEach { it() }
                } else {
                    sfxLoaded[p] = 0
                    sfxWaiters.remove(p)
                }
            }
            soundPool = pool
        }

    // ---------------------------------------------------------------- misc

    private fun markMissing(kind: String, keyword: String) {
        val key = "$kind|$keyword"
        if (missingLogged.add(key)) {
            AppLog.putAudio("【四轨·缺失】$kind「$keyword」不在库中（可先“准备示例素材”；B33.3 起可自动合成补缺）")
        }
    }

    private fun resetAll() {
        desiredAmbience = null
        desiredBgm = null
        bgmHoldRemaining = 0
        bgmLane.stopAndClear()
        ambLane.stopAndClear()
    }

    /** 单条 loop 轨（环境/BGM 共用）：1 个 ExoPlayer，单曲循环，音量由 ticker 逼近 */
    private inner class LoopLane {
        var player: ExoPlayer? = null
            private set
        var keyword: String? = null
            private set

        fun playKeyword(kw: String, file: File) {
            val p = player ?: ExoPlayer.Builder(appContext).build().also {
                // 不抢音频焦点：焦点由人声播放器统一管理
                it.setAudioAttributes(media3AudioAttributes, false)
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.volume = 0f
                player = it
            }
            if (keyword != kw || p.currentMediaItem == null) {
                runCatching { p.stop() }
                p.clearMediaItems()
                p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                p.volume = 0f
                p.prepare()
                keyword = kw
            }
            if (!p.isPlaying) runCatching { p.play() }
        }

        fun stopAndClear() {
            keyword = null
            player?.let { p ->
                runCatching {
                    p.stop()
                    p.clearMediaItems()
                }
            }
        }

        fun release() {
            player?.let { runCatching { it.release() } }
            player = null
            keyword = null
        }
    }
}