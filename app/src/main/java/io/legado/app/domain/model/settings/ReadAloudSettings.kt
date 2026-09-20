package io.legado.app.domain.model.settings


data class ReadAloudSettings(
    val ttsEngine: String? = null,
    val ttsParagraphInterval: Int = 0,
    val audioCacheCleanTime: Int = 0,
    val ignoreAudioFocus: Boolean = false,
    val mediaButtonOnExit: Boolean = true,
    val readAloudByMediaButton: Boolean = false,
    val pauseReadAloudWhilePhoneCalls: Boolean = false,
    val readAloudWakeLock: Boolean = false,
    val showReadAloudCapsule: Boolean = true,
    val capsuleAutoCollapse: Boolean = true,
    val capsuleOffsetX: Float = 0f,
    val capsuleOffsetY: Float = 0f,
    val mediaButtonPerNext: Boolean = false,
    val readAloudByPage: Boolean = false,
    val androidMediaControlEnabled: Boolean = false,
    val systemMediaControlCompatibilityChange: Boolean = true,
    val streamReadAloudAudio: Boolean = false,
    val ttsTimer: Int = 0,
    val finishCurrentChapterAfterTimer: Boolean = false,
    val ttsFollowSys: Boolean = true,
    val ttsSpeechRate: Int = 5,
    val useMultiSpeaker: Boolean = true,
    val defaultInterface: String = "classic",
    val contentSelectSpeakMode: Int = 0,
    val audioPreDownloadNum: Int = 10,
    val ttsPreSynthesisConcurrency: Int = 3,
    /** 单次 TTS 合成请求超时（秒；B8.6 可调，默认 30） */
    val ttsSynthTimeoutSec: Int = 30,
    /** 请求失败后的最大重试次数（B8.6 可调，默认 5；0=不重试） */
    val ttsMaxRetry: Int = 5,
)
