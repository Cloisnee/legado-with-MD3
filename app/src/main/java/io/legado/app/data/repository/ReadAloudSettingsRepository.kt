package io.legado.app.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.legado.app.constant.PreferKey
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.PlaybackTimer
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.compatDsInt
import io.legado.app.help.config.compatDsString
import io.legado.app.help.config.compatDsValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ReadAloudSettingsRepository : ReadAloudSettingsGateway {

    private val migrateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // B33.3c：旧「音效密度」一次性迁移（读侧另有回退兜底，双保险）
        migrateScope.launch { runCatching { migrateLegacySfxDensity() } }
    }

    override val currentSettings: ReadAloudSettings
        get() = AppConfigStore.preferences.toReadAloudSettings()

    override val settings: Flow<ReadAloudSettings> = AppConfigStore.preferencesFlow
        .map { preferences ->
            preferences.toReadAloudSettings()
        }
    val preferences: Flow<ReadAloudSettings> = settings

    override suspend fun update(transform: (ReadAloudSettings) -> ReadAloudSettings) {
        AppConfigStore.atomicUpdate(
            read = Preferences::toReadAloudSettings,
            toPrefMap = ReadAloudSettings::toPrefMap,
            transform = transform,
        )
    }

    companion object {
        const val DEFAULT_INTERFACE_CLASSIC = "classic"
        const val DEFAULT_INTERFACE_PLAYER = "player"
        val AVAILABLE_INTERFACES = setOf(DEFAULT_INTERFACE_CLASSIC, DEFAULT_INTERFACE_PLAYER)

        /** B33.3c：旧「音效密度」一次性迁移（写新键 + 清旧键；失败静默，读侧回退兜底） */
        private suspend fun migrateLegacySfxDensity() {
            val prefs = AppConfigStore.preferences
            val legacy = prefs.compatDsString(LEGACY_KEY_AL_SFX_DENSITY) ?: return
            if (prefs.compatDsInt(PreferKey.alSfxMinGapS) == null) {
                val mapped = densityMappedValues(legacy)
                AppConfigStore.putAll(
                    mapOf(
                        PreferKey.alSfxMinGapS to mapped[0],
                        PreferKey.alSfxCooldownS to mapped[1],
                        PreferKey.alBgmCooldownS to mapped[2],
                        PreferKey.alAmbDwellS to mapped[3],
                    )
                )
            }
            AppConfigStore.remove(LEGACY_KEY_AL_SFX_DENSITY)
        }
    }
}

internal fun Preferences.toReadAloudSettings(): ReadAloudSettings = ReadAloudSettings(
    ttsEngine = compatDsString(PreferKey.ttsEngine)
        ?: TtsServerCenterRepository.BUILTIN_ENGINE_JSON,
    ttsParagraphInterval = compatDsValue(ReadAloudKeys.TtsParagraphInterval, 0),
    audioCacheCleanTime = compatDsValue(ReadAloudKeys.AudioCacheCleanTime, 0),
    ignoreAudioFocus = compatDsValue(ReadAloudKeys.IgnoreAudioFocus, false),
    mediaButtonOnExit = compatDsValue(ReadAloudKeys.MediaButtonOnExit, false),
    readAloudByMediaButton = compatDsValue(ReadAloudKeys.ReadAloudByMediaButton, false),
    pauseReadAloudWhilePhoneCalls =
        compatDsValue(ReadAloudKeys.PauseReadAloudWhilePhoneCalls, false),
    readAloudWakeLock = compatDsValue(ReadAloudKeys.ReadAloudWakeLock, false),
    showReadAloudCapsule = compatDsValue(ReadAloudKeys.ShowReadAloudCapsule, true),
    capsuleAutoCollapse = compatDsValue(ReadAloudKeys.CapsuleAutoCollapse, true),
    capsuleOffsetX = compatDsValue(ReadAloudKeys.CapsuleOffsetX, 0f),
    capsuleOffsetY = compatDsValue(ReadAloudKeys.CapsuleOffsetY, 0f),
    mediaButtonPerNext = compatDsValue(ReadAloudKeys.MediaButtonPerNext, false),
    readAloudByPage = compatDsValue(ReadAloudKeys.ReadAloudByPage, false),
    keepReadAloudOnExit = compatDsValue(ReadAloudKeys.KeepReadAloudOnExit, true),
    androidMediaControlEnabled = compatDsValue(ReadAloudKeys.AndroidMediaControlEnabled, false),
    systemMediaControlCompatibilityChange =
        compatDsValue(ReadAloudKeys.SystemMediaControlCompatibilityChange, false),
    streamReadAloudAudio = compatDsValue(ReadAloudKeys.StreamReadAloudAudio, false),
    ttsTimer = PlaybackTimer.normalize(compatDsValue(ReadAloudKeys.TtsTimer, 0)),
    finishCurrentChapterAfterTimer =
        compatDsValue(ReadAloudKeys.FinishCurrentChapterAfterTimer, false),
    timerMode = compatDsValue(
        ReadAloudKeys.TimerMode,
        ReadAloudTimerMode.Minute.storageValue,
    ),
    timerChapters = PlaybackTimer.normalizeChapters(
        compatDsValue(ReadAloudKeys.TimerChapters, 0)
    ),
    ttsFollowSys = compatDsValue(ReadAloudKeys.TtsFollowSys, true),
    ttsSpeechRate = compatDsValue(ReadAloudKeys.TtsSpeechRate, 5),
    useMultiSpeaker = compatDsValue(ReadAloudKeys.UseMultiSpeaker, true),
    defaultInterface = compatDsValue(
        ReadAloudKeys.DefaultInterface,
        ReadAloudSettingsRepository.DEFAULT_INTERFACE_CLASSIC,
    ),
    contentSelectSpeakMode = compatDsValue(ReadAloudKeys.ContentSelectSpeakMode, 0),
    audioPreDownloadNum = compatDsValue(ReadAloudKeys.AudioPreDownloadNum, 2),
    ttsPreSynthesisConcurrency = compatDsValue(ReadAloudKeys.PreSynthesisConcurrency, 1),
    ttsSynthTimeoutSec = compatDsValue(ReadAloudKeys.TtsSynthTimeoutSec, 75),
    ttsMaxRetry = compatDsValue(ReadAloudKeys.TtsMaxRetry, 5),
    standbyStart = compatDsValue(ReadAloudKeys.StandbyStart, true),
    alEnabled = compatDsValue(ReadAloudKeys.AlEnabled, true),
    alSfxVolume = compatDsValue(ReadAloudKeys.AlSfxVolume, 80).coerceIn(0, 100),
    alAmbVolume = compatDsValue(ReadAloudKeys.AlAmbVolume, 35).coerceIn(0, 100),
    alBgmVolume = compatDsValue(ReadAloudKeys.AlBgmVolume, 25).coerceIn(0, 100),
    // B33.3c：新闸门键未写过时，回退映射旧「音效密度」（low/mid/high → 数值档）
    alSfxMinGapS = (compatDsInt(PreferKey.alSfxMinGapS)
        ?: densityMappedValues(compatDsString(LEGACY_KEY_AL_SFX_DENSITY))[0]).coerceIn(0, 30),
    alSfxCooldownS = (compatDsInt(PreferKey.alSfxCooldownS)
        ?: densityMappedValues(compatDsString(LEGACY_KEY_AL_SFX_DENSITY))[1]).coerceIn(0, 300),
    alBgmCooldownS = (compatDsInt(PreferKey.alBgmCooldownS)
        ?: densityMappedValues(compatDsString(LEGACY_KEY_AL_SFX_DENSITY))[2]).coerceIn(0, 600),
    alAmbDwellS = (compatDsInt(PreferKey.alAmbDwellS)
        ?: densityMappedValues(compatDsString(LEGACY_KEY_AL_SFX_DENSITY))[3]).coerceIn(0, 120),
    alChapterSynthCap = compatDsValue(ReadAloudKeys.AlChapterSynthCap, 10).coerceIn(0, 50),
)

internal fun ReadAloudSettings.toPrefMap(): Map<String, Any?> = mapOf(
    PreferKey.ttsEngine to ttsEngine,
    PreferKey.ttsParagraphInterval to ttsParagraphInterval,
    PreferKey.audioCacheCleanTime to audioCacheCleanTime,
    PreferKey.ignoreAudioFocus to ignoreAudioFocus,
    PreferKey.mediaButtonOnExit to mediaButtonOnExit,
    PreferKey.readAloudByMediaButton to readAloudByMediaButton,
    PreferKey.pauseReadAloudWhilePhoneCalls to pauseReadAloudWhilePhoneCalls,
    PreferKey.readAloudWakeLock to readAloudWakeLock,
    PreferKey.showReadAloudCapsule to showReadAloudCapsule,
    PreferKey.capsuleAutoCollapse to capsuleAutoCollapse,
    ReadAloudKeys.CapsuleOffsetX.name to capsuleOffsetX,
    ReadAloudKeys.CapsuleOffsetY.name to capsuleOffsetY,
    PreferKey.mediaButtonPerNext to mediaButtonPerNext,
    PreferKey.readAloudByPage to readAloudByPage,
    PreferKey.keepReadAloudOnExit to keepReadAloudOnExit,
    PreferKey.readAloudAndroidMediaControl to androidMediaControlEnabled,
    PreferKey.systemMediaControlCompatibilityChange to systemMediaControlCompatibilityChange,
    PreferKey.streamReadAloudAudio to streamReadAloudAudio,
    PreferKey.ttsTimer to ttsTimer,
    PreferKey.finishCurrentChapterAfterTimer to finishCurrentChapterAfterTimer,
    PreferKey.readAloudTimerMode to timerMode,
    PreferKey.readAloudTimerChapters to timerChapters,
    PreferKey.ttsFollowSys to ttsFollowSys,
    PreferKey.ttsSpeechRate to ttsSpeechRate,
    PreferKey.useMultiSpeaker to useMultiSpeaker,
    PreferKey.defaultReadAloudInterface to defaultInterface,
    PreferKey.contentSelectSpeakMod to contentSelectSpeakMode,
    PreferKey.audioPreDownloadNum to audioPreDownloadNum,
    PreferKey.ttsPreSynthesisConcurrency to ttsPreSynthesisConcurrency,
    PreferKey.ttsSynthTimeoutSec to ttsSynthTimeoutSec,
    PreferKey.ttsMaxRetry to ttsMaxRetry,
    PreferKey.readAloudStandbyStart to standbyStart,
    PreferKey.alEnabled to alEnabled,
    PreferKey.alSfxVolume to alSfxVolume,
    PreferKey.alAmbVolume to alAmbVolume,
    PreferKey.alBgmVolume to alBgmVolume,
    PreferKey.alSfxMinGapS to alSfxMinGapS,
    PreferKey.alSfxCooldownS to alSfxCooldownS,
    PreferKey.alBgmCooldownS to alBgmCooldownS,
    PreferKey.alAmbDwellS to alAmbDwellS,
    PreferKey.alChapterSynthCap to alChapterSynthCap,
)

private object ReadAloudKeys {
    val TtsParagraphInterval = intPreferencesKey(PreferKey.ttsParagraphInterval)
    val AudioCacheCleanTime = intPreferencesKey(PreferKey.audioCacheCleanTime)
    val IgnoreAudioFocus = booleanPreferencesKey(PreferKey.ignoreAudioFocus)
    val MediaButtonOnExit = booleanPreferencesKey(PreferKey.mediaButtonOnExit)
    val ReadAloudByMediaButton = booleanPreferencesKey(PreferKey.readAloudByMediaButton)
    val PauseReadAloudWhilePhoneCalls =
        booleanPreferencesKey(PreferKey.pauseReadAloudWhilePhoneCalls)
    val ReadAloudWakeLock = booleanPreferencesKey(PreferKey.readAloudWakeLock)
    val ShowReadAloudCapsule = booleanPreferencesKey(PreferKey.showReadAloudCapsule)
    val CapsuleAutoCollapse = booleanPreferencesKey(PreferKey.capsuleAutoCollapse)
    val CapsuleOffsetX = floatPreferencesKey("read_aloud_capsule_offset_x")
    val CapsuleOffsetY = floatPreferencesKey("read_aloud_capsule_offset_y")
    val MediaButtonPerNext = booleanPreferencesKey(PreferKey.mediaButtonPerNext)
    val ReadAloudByPage = booleanPreferencesKey(PreferKey.readAloudByPage)
    val KeepReadAloudOnExit = booleanPreferencesKey(PreferKey.keepReadAloudOnExit)
    val AndroidMediaControlEnabled =
        booleanPreferencesKey(PreferKey.readAloudAndroidMediaControl)
    val SystemMediaControlCompatibilityChange =
        booleanPreferencesKey(PreferKey.systemMediaControlCompatibilityChange)
    val StreamReadAloudAudio = booleanPreferencesKey(PreferKey.streamReadAloudAudio)
    val TtsTimer = intPreferencesKey(PreferKey.ttsTimer)
    val FinishCurrentChapterAfterTimer =
        booleanPreferencesKey(PreferKey.finishCurrentChapterAfterTimer)
    val TimerMode = stringPreferencesKey(PreferKey.readAloudTimerMode)
    val TimerChapters = intPreferencesKey(PreferKey.readAloudTimerChapters)
    val TtsFollowSys = booleanPreferencesKey(PreferKey.ttsFollowSys)
    val TtsSpeechRate = intPreferencesKey(PreferKey.ttsSpeechRate)
    val UseMultiSpeaker = booleanPreferencesKey(PreferKey.useMultiSpeaker)
    val DefaultInterface = stringPreferencesKey(PreferKey.defaultReadAloudInterface)
    val ContentSelectSpeakMode = intPreferencesKey(PreferKey.contentSelectSpeakMod)
    val AudioPreDownloadNum = intPreferencesKey(PreferKey.audioPreDownloadNum)
    val PreSynthesisConcurrency = intPreferencesKey(PreferKey.ttsPreSynthesisConcurrency)
    val TtsSynthTimeoutSec = intPreferencesKey(PreferKey.ttsSynthTimeoutSec)
    val TtsMaxRetry = intPreferencesKey(PreferKey.ttsMaxRetry)
    val StandbyStart = booleanPreferencesKey(PreferKey.readAloudStandbyStart)
    val AlEnabled = booleanPreferencesKey(PreferKey.alEnabled)
    val AlSfxVolume = intPreferencesKey(PreferKey.alSfxVolume)
    val AlAmbVolume = intPreferencesKey(PreferKey.alAmbVolume)
    val AlBgmVolume = intPreferencesKey(PreferKey.alBgmVolume)
    val AlSfxMinGapS = intPreferencesKey(PreferKey.alSfxMinGapS)
    val AlSfxCooldownS = intPreferencesKey(PreferKey.alSfxCooldownS)
    val AlBgmCooldownS = intPreferencesKey(PreferKey.alBgmCooldownS)
    val AlAmbDwellS = intPreferencesKey(PreferKey.alAmbDwellS)
    val AlChapterSynthCap = intPreferencesKey(PreferKey.alChapterSynthCap)
}

// ---- B33.3c：旧「音效密度」一次性迁移支持 ----

private const val LEGACY_KEY_AL_SFX_DENSITY = "alSfxDensity"

private val AL_DENSITY_BASE = intArrayOf(6, 60, 150, 25)

/** 旧密度档 → 新四闸门（最小间隔 / 音效冷却 / BGM 冷却 / 环境驻留） */
private fun densityMappedValues(density: String?): IntArray = when (density?.lowercase()) {
    "low" -> intArrayOf(12, 90, 225, 37)
    "high" -> intArrayOf(3, 45, 112, 18)
    else -> AL_DENSITY_BASE
}
