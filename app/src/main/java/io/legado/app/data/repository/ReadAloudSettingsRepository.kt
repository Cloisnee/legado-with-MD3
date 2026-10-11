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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReadAloudSettingsRepository : ReadAloudSettingsGateway {

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

    /** 悬浮胶囊位置；两个分量同批写入，避免只落一个导致胶囊跳位。 */
    suspend fun putCapsulePosition(x: Float, y: Float) {
        AppConfigStore.putAllAndAwait(
            mapOf(
                ReadAloudKeys.CapsuleOffsetX.name to x,
                ReadAloudKeys.CapsuleOffsetY.name to y,
            )
        )
    }

    companion object {
        const val DEFAULT_INTERFACE_CLASSIC = "classic"
        const val DEFAULT_INTERFACE_PLAYER = "player"
        val AVAILABLE_INTERFACES = setOf(DEFAULT_INTERFACE_CLASSIC, DEFAULT_INTERFACE_PLAYER)

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
    keepReadAloudOnExit = compatDsValue(ReadAloudKeys.KeepReadAloudOnExit, true),
    showReadAloudCapsule = compatDsValue(ReadAloudKeys.ShowReadAloudCapsule, true),
    capsuleAutoCollapse = compatDsValue(ReadAloudKeys.CapsuleAutoCollapse, true),
    capsuleOffsetX = compatDsValue(ReadAloudKeys.CapsuleOffsetX, 0f),
    capsuleOffsetY = compatDsValue(ReadAloudKeys.CapsuleOffsetY, 0f),
    mediaButtonPerNext = compatDsValue(ReadAloudKeys.MediaButtonPerNext, false),
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
    alAmbVolume = compatDsValue(ReadAloudKeys.AlAmbVolume, 20).coerceIn(0, 100),
    alBgmVolume = compatDsValue(ReadAloudKeys.AlBgmVolume, 20).coerceIn(0, 100),
    alSfxMinGapS = (compatDsInt(PreferKey.alSfxMinGapS) ?: 3).coerceIn(0, 30),
    alSfxCooldownS = (compatDsInt(PreferKey.alSfxCooldownS) ?: 0).coerceIn(0, 300),
    alBgmCooldownS = (compatDsInt(PreferKey.alBgmCooldownS) ?: 60).coerceIn(0, 600),
    alAmbDwellS = (compatDsInt(PreferKey.alAmbDwellS) ?: 15).coerceIn(0, 120),
    alChapterSynthCap = compatDsValue(ReadAloudKeys.AlChapterSynthCap, 50).coerceIn(0, 50),
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
    PreferKey.keepReadAloudOnExit to keepReadAloudOnExit,
    PreferKey.showReadAloudCapsule to showReadAloudCapsule,
    PreferKey.capsuleAutoCollapse to capsuleAutoCollapse,
    ReadAloudKeys.CapsuleOffsetX.name to capsuleOffsetX,
    ReadAloudKeys.CapsuleOffsetY.name to capsuleOffsetY,
    PreferKey.mediaButtonPerNext to mediaButtonPerNext,
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
    val KeepReadAloudOnExit = booleanPreferencesKey(PreferKey.keepReadAloudOnExit)
    val ShowReadAloudCapsule = booleanPreferencesKey(PreferKey.showReadAloudCapsule)
    val CapsuleAutoCollapse = booleanPreferencesKey(PreferKey.capsuleAutoCollapse)
    val CapsuleOffsetX = floatPreferencesKey("read_aloud_capsule_offset_x")
    val CapsuleOffsetY = floatPreferencesKey("read_aloud_capsule_offset_y")
    val MediaButtonPerNext = booleanPreferencesKey(PreferKey.mediaButtonPerNext)
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


