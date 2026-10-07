package io.legado.app.domain.model.settings


/**
 * 朗读定时模式。两者互斥：同时只有一个倒计时在跑。
 *
 * [Minute] 到点即停（或按 [ReadAloudSettings.ttsTimer] 归零收尾）；
 * [Chapter] 读满 [ReadAloudSettings.timerChapters] 章后停在章末。
 */
enum class ReadAloudTimerMode(val storageValue: String) {
    Minute("minute"),
    Chapter("chapter");

    companion object {
        fun fromStorage(value: String): ReadAloudTimerMode =
            entries.firstOrNull { it.storageValue == value } ?: Minute
    }
}

data class ReadAloudSettings(
    val ttsEngine: String? = null,
    val ttsParagraphInterval: Int = 0,
    val audioCacheCleanTime: Int = 0,
    val ignoreAudioFocus: Boolean = false,
    val mediaButtonOnExit: Boolean = false,
    val readAloudByMediaButton: Boolean = false,
    val pauseReadAloudWhilePhoneCalls: Boolean = false,
    val readAloudWakeLock: Boolean = false,
    /** 退出阅读时继续朗读（默认开）：关闭阅读界面后不停朗读，继续在后台播放 */
    val keepReadAloudOnExit: Boolean = true,
    val showReadAloudCapsule: Boolean = true,
    val capsuleAutoCollapse: Boolean = true,
    val capsuleOffsetX: Float = 0f,
    val capsuleOffsetY: Float = 0f,
    val mediaButtonPerNext: Boolean = false,
    val readAloudByPage: Boolean = false,
    val androidMediaControlEnabled: Boolean = false,
    val systemMediaControlCompatibilityChange: Boolean = false,
    val streamReadAloudAudio: Boolean = false,
    val ttsTimer: Int = 0,
    val finishCurrentChapterAfterTimer: Boolean = false,
    val timerMode: String = ReadAloudTimerMode.Minute.storageValue,
    /** 章节定时：还剩几章；0 表示未开启。 */
    val timerChapters: Int = 0,
    val ttsFollowSys: Boolean = true,
    val ttsSpeechRate: Int = 5,
    val useMultiSpeaker: Boolean = true,
    val defaultInterface: String = "classic",
    val contentSelectSpeakMode: Int = 0,
    val audioPreDownloadNum: Int = 2,
    val ttsPreSynthesisConcurrency: Int = 1,
    /** 单次 TTS 合成请求超时（秒；B8.6 可调，默认 75） */
    val ttsSynthTimeoutSec: Int = 75,
    /** 请求失败后的最大重试次数（B8.6 可调，默认 5；0=不重试） */
    val ttsMaxRetry: Int = 5,
    /** M4：待命启动（防误触，默认开）——点「开始朗读」先进入待命（不合成/不分析/不播放），再点一次才开始 */
    val standbyStart: Boolean = true,
    // ---- B33 音效/BGM/环境·四轨（2026-09-30 立项；音频小闭环）----
    /** 四轨总开关（在朗读人声之上叠加 音效/BGM/环境 三条音频轨） */
    val alEnabled: Boolean = true,
    /** 音效轨音量（0..100） */
    val alSfxVolume: Int = 80,
    /** 环境轨音量（0..100） */
    val alAmbVolume: Int = 35,
    /** BGM 轨音量（0..100） */
    val alBgmVolume: Int = 25,
    // ---- B33.3c：补缺闸门「滑条」（替代旧「音效密度」低/中/高；旧值一次性迁移）----
    /** 音效最小间隔（秒，0–30） */
    val alSfxMinGapS: Int = 6,
    /** 音效同素材冷却（秒，0–300） */
    val alSfxCooldownS: Int = 60,
    /** BGM 同素材冷却（秒，0–600） */
    val alBgmCooldownS: Int = 150,
    /** 环境底噪最短驻留（秒，0–120） */
    val alAmbDwellS: Int = 25,
    /** 每章自动补缺上限（条，0–50；0=关闭自动补缺） */
    val alChapterSynthCap: Int = 10,
)
