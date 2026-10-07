package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.sheet.ReadAloudNumberConfigSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.help.IntentHelp
import io.legado.app.help.readaloud.audio.CloudWordnetClient
import io.legado.app.utils.TTSCacheUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 朗读设置独立页（从阅读内提取到「我的 → 朗读」，阅读内仅保留播放界面）。
 */
@Composable
fun ReadAloudSettingsRouteScreen(onBackClick: () -> Unit) {
    ReadAloudSettingsScreen(onBack = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { GlobalContext.get().get<ReadAloudSettingsRepository>() }
    val extRepo = remember {
        TtsServerCenterRepository(context.applicationContext as Application)
    }

    var st by remember { mutableStateOf(repo.currentSettings) }
    var preDownloadNum by remember { mutableStateOf(st.audioPreDownloadNum) }
    var bgMode by remember {
        mutableStateOf(AppConfigStore.getInt(PreferKey.readAloudPlayerBgMode) ?: ReadAloudBgMode.Blur)
    }
    var loudness by remember { mutableStateOf(false) }

    fun update(transform: (ReadAloudSettings) -> ReadAloudSettings) {
        st = transform(st)
        scope.launch { repo.update { transform(it) } }
    }

    LaunchedEffect(Unit) {
        st = repo.currentSettings
        preDownloadNum = st.audioPreDownloadNum
        bgMode = AppConfigStore.getInt(PreferKey.readAloudPlayerBgMode) ?: ReadAloudBgMode.Blur
        loudness = extRepo.getLoudnessBalance()
        // 多角色开关已移除：默认全开（新分析管线接管）
        if (!st.useMultiSpeaker) {
            st = st.copy(useMultiSpeaker = true)
            repo.update { it.copy(useMultiSpeaker = true) }
        }
    }

    var showPreDownload by remember { mutableStateOf(false) }
    var showConcurrency by remember { mutableStateOf(false) }
    var showInterval by remember { mutableStateOf(false) }
    var showCleanTime by remember { mutableStateOf(false) }
    var showSynthTimeout by remember { mutableStateOf(false) }
    var showMaxRetry by remember { mutableStateOf(false) }
    var showAlSfx by remember { mutableStateOf(false) }
    var showAlAmb by remember { mutableStateOf(false) }
    var showAlBgm by remember { mutableStateOf(false) }
    var showAlMinGap by remember { mutableStateOf(false) }
    var showAlSfxCd by remember { mutableStateOf(false) }
    var showAlBgmCd by remember { mutableStateOf(false) }
    var showAlAmbDwell by remember { mutableStateOf(false) }
    var showAlChapCap by remember { mutableStateOf(false) }
    var showCloudWord by remember { mutableStateOf(false) }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.aloud_config),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = padding.calculateTopPadding(),
                bottom = 120.dp,
            ),
        ) {
            item {
                SplicedColumnGroup(title = "界面") {
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.default_read_aloud_interface),
                        selectedValue = st.defaultInterface,
                        displayEntries = arrayOf(
                            stringResource(R.string.read_aloud_interface_classic),
                            stringResource(R.string.read_aloud_interface_player),
                        ),
                        entryValues = arrayOf(
                            ReadAloudSettingsRepository.DEFAULT_INTERFACE_CLASSIC,
                            ReadAloudSettingsRepository.DEFAULT_INTERFACE_PLAYER,
                        ),
                        description = stringResource(R.string.default_read_aloud_interface_summary),
                        onValueChange = { value -> update { it.copy(defaultInterface = value) } },
                    )
                    TinyDropdownSettingItem(
                        title = stringResource(R.string.read_aloud_player_background),
                        selectedValue = bgMode.toString(),
                        displayEntries = arrayOf(
                            stringResource(R.string.read_aloud_bg_solid),
                            stringResource(R.string.read_aloud_bg_blur),
                            stringResource(R.string.read_aloud_bg_flowing_light),
                            stringResource(R.string.read_aloud_bg_transparent),
                        ),
                        entryValues = arrayOf(
                            ReadAloudBgMode.Solid.toString(),
                            ReadAloudBgMode.Blur.toString(),
                            ReadAloudBgMode.FlowingLight.toString(),
                            ReadAloudBgMode.Transparent.toString(),
                        ),
                        onValueChange = { value ->
                            val v = value.toIntOrNull() ?: ReadAloudBgMode.Blur
                            bgMode = v
                            AppConfigStore.putInt(PreferKey.readAloudPlayerBgMode, v)
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.show_read_aloud_capsule),
                        description = stringResource(R.string.show_read_aloud_capsule_summary),
                        checked = st.showReadAloudCapsule,
                        onCheckedChange = { v -> update { it.copy(showReadAloudCapsule = v) } },
                    )
                    if (st.showReadAloudCapsule) {
                        TinySwitchSettingItem(
                            title = stringResource(R.string.capsule_auto_collapse),
                            description = stringResource(R.string.capsule_auto_collapse_summary),
                            checked = st.capsuleAutoCollapse,
                            onCheckedChange = { v -> update { it.copy(capsuleAutoCollapse = v) } },
                        )
                    }
                    TinyClickableSettingItem(
                        title = stringResource(R.string.reset_read_aloud_capsule_position),
                        description = stringResource(R.string.reset_read_aloud_capsule_position_summary),
                        onClick = {
                            update { it.copy(capsuleOffsetX = 0f, capsuleOffsetY = 0f) }
                            context.toastOnUi("已重置朗读胶囊位置")
                        },
                    )
                }
            }
            item {
                SplicedColumnGroup(title = "播放行为") {
                    TinySwitchSettingItem(
                        title = stringResource(R.string.ignore_audio_focus_title),
                        description = stringResource(R.string.ignore_audio_focus_summary),
                        checked = st.ignoreAudioFocus,
                        onCheckedChange = { v -> update { it.copy(ignoreAudioFocus = v) } },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.pause_read_aloud_while_phone_calls_title),
                        description = stringResource(R.string.pause_read_aloud_while_phone_calls_summary),
                        checked = st.pauseReadAloudWhilePhoneCalls,
                        enabled = st.ignoreAudioFocus,
                        onCheckedChange = { v ->
                            update { it.copy(pauseReadAloudWhilePhoneCalls = v) }
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_wake_lock),
                        description = stringResource(R.string.read_aloud_wake_lock_summary),
                        checked = st.readAloudWakeLock,
                        onCheckedChange = { v -> update { it.copy(readAloudWakeLock = v) } },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_keep_on_exit),
                        description = stringResource(R.string.read_aloud_keep_on_exit_summary),
                        checked = st.keepReadAloudOnExit,
                        onCheckedChange = { v -> update { it.copy(keepReadAloudOnExit = v) } },
                    )
                    TinySwitchSettingItem(
                        title = "待命启动（防误触）",
                        description = "点「开始朗读」先进入待命（不发声、不合成、不分析）；再点一次（界面/通知）才真正开始",
                        checked = st.standbyStart,
                        onCheckedChange = { v -> update { it.copy(standbyStart = v) } },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.pref_media_button_per_next),
                        description = stringResource(R.string.pref_media_button_per_next_summary),
                        checked = st.mediaButtonPerNext,
                        onCheckedChange = { v -> update { it.copy(mediaButtonPerNext = v) } },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_by_page),
                        description = stringResource(R.string.read_aloud_by_page_summary),
                        checked = st.readAloudByPage,
                        onCheckedChange = { v ->
                            update { it.copy(readAloudByPage = v) }
                            if (v) postEvent(EventBus.MEDIA_BUTTON, false)
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.read_aloud_android_media_control),
                        description = stringResource(R.string.read_aloud_android_media_control_summary),
                        checked = st.androidMediaControlEnabled,
                        onCheckedChange = { v -> update { it.copy(androidMediaControlEnabled = v) } },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.system_media_control_compatibility_change),
                        description = stringResource(R.string.system_media_control_compatibility_change_summary),
                        checked = st.systemMediaControlCompatibilityChange,
                        onCheckedChange = { v ->
                            update { it.copy(systemMediaControlCompatibilityChange = v) }
                        },
                    )
                    TinySwitchSettingItem(
                        title = stringResource(R.string.stream_read_aloud_audio),
                        description = stringResource(R.string.stream_read_aloud_audio_summary),
                        checked = st.streamReadAloudAudio,
                        onCheckedChange = { v ->
                            update { it.copy(streamReadAloudAudio = v) }
                            if (v) postEvent(EventBus.MEDIA_BUTTON, false)
                        },
                    )
                    TinySwitchSettingItem(
                        title = "响度均衡",
                        description = "自动学习各声线平均音量，播放时拉平不同插件声线的响度（±6dB 内）",
                        checked = loudness,
                        onCheckedChange = { v ->
                            loudness = v
                            scope.launch { extRepo.setLoudnessBalance(v) }
                        },
                    )
                    TinyClickableSettingItem(
                        title = "重置响度数据",
                        description = "清除已收集的响度学习数据",
                        onClick = {
                            scope.launch {
                                extRepo.resetLoudnessData()
                                context.toastOnUi("已重置响度数据")
                            }
                        },
                    )
                }
            }
            item {
                SplicedColumnGroup(title = "音效与音乐（四轨）") {
                    TinySwitchSettingItem(
                        title = "四轨音效",
                        description = "在朗读人声之上叠加 BGM / 环境底噪 / 音效 三条音轨（独立音量、场景联动）",
                        checked = st.alEnabled,
                        onCheckedChange = { v -> update { it.copy(alEnabled = v) } },
                    )
                    if (st.alEnabled) {
                        TinyClickableSettingItem(
                            title = "音效音量",
                            description = "${st.alSfxVolume}%",
                            onClick = { showAlSfx = true },
                        )
                        TinyClickableSettingItem(
                            title = "环境音量",
                            description = "${st.alAmbVolume}%",
                            onClick = { showAlAmb = true },
                        )
                        TinyClickableSettingItem(
                            title = "BGM 音量",
                            description = "${st.alBgmVolume}%",
                            onClick = { showAlBgm = true },
                        )
                        TinyClickableSettingItem(
                            title = "音效最小间隔",
                            description = "${st.alSfxMinGapS} 秒",
                            onClick = { showAlMinGap = true },
                        )
                        TinyClickableSettingItem(
                            title = "音效同素材冷却",
                            description = "${st.alSfxCooldownS} 秒",
                            onClick = { showAlSfxCd = true },
                        )
                        TinyClickableSettingItem(
                            title = "BGM 冷却",
                            description = "${st.alBgmCooldownS} 秒",
                            onClick = { showAlBgmCd = true },
                        )
                        TinyClickableSettingItem(
                            title = "环境驻留",
                            description = "${st.alAmbDwellS} 秒",
                            onClick = { showAlAmbDwell = true },
                        )
                        TinyClickableSettingItem(
                            title = "每章自动补缺上限",
                            description = if (st.alChapterSynthCap <= 0) "已关闭" else "${st.alChapterSynthCap} 条",
                            onClick = { showAlChapCap = true },
                        )
                    }
                }
            }
            item {
                SplicedColumnGroup(title = "其他") {
                    TinyClickableSettingItem(
                        title = stringResource(R.string.sys_tts_config),
                        onClick = { IntentHelp.openTTSSetting() },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.read_aloud_preload),
                        description = stringResource(R.string.read_aloud_preload_summary, preDownloadNum),
                        onClick = { showPreDownload = true },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_pre_synthesis_concurrency),
                        description = stringResource(
                            R.string.tts_pre_synthesis_concurrency_summary,
                            st.ttsPreSynthesisConcurrency,
                        ),
                        onClick = { showConcurrency = true },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_synth_timeout),
                        description = stringResource(
                            R.string.tts_synth_timeout_summary,
                            st.ttsSynthTimeoutSec,
                        ),
                        onClick = { showSynthTimeout = true },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_max_retry),
                        description = stringResource(
                            R.string.tts_max_retry_summary,
                            st.ttsMaxRetry,
                        ),
                        onClick = { showMaxRetry = true },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_paragraph_interval),
                        description = stringResource(
                            R.string.tts_paragraph_interval_summary,
                            st.ttsParagraphInterval,
                        ),
                        onClick = { showInterval = true },
                    )
                    TinyClickableSettingItem(
                        title = stringResource(R.string.audio_cache_clean_time),
                        description = stringResource(
                            R.string.audio_cache_clean_time_summary,
                            st.audioCacheCleanTime,
                        ),
                        onClick = { showCleanTime = true },
                    )
                    TinyClickableSettingItem(
                        title = "云端词网",
                        description = run {
                            val en = AppConfigStore.getBoolean(PreferKey.cloudWordEnabled) == true
                            val rp = AppConfigStore.getString(PreferKey.cloudWordRepo).orEmpty()
                            if (en && rp.isNotBlank()) "已配置：$rp" else "未配置（点此填写仓库与令牌）"
                        },
                        onClick = { showCloudWord = true },
                    )
                }
            }
        }
    }

    ReadAloudNumberConfigSheet(
        show = showPreDownload,
        title = stringResource(R.string.read_aloud_preload),
        description = stringResource(R.string.read_aloud_preload_summary, preDownloadNum),
        value = preDownloadNum,
        defaultValue = 2,
        valueRange = 0f..100f,
        onValueChange = { v ->
            preDownloadNum = v
            update { it.copy(audioPreDownloadNum = v) }
        },
        onDismissRequest = { showPreDownload = false },
    )
    ReadAloudNumberConfigSheet(
        show = showConcurrency,
        title = stringResource(R.string.tts_pre_synthesis_concurrency),
        description = stringResource(
            R.string.tts_pre_synthesis_concurrency_summary,
            st.ttsPreSynthesisConcurrency,
        ),
        value = st.ttsPreSynthesisConcurrency,
        defaultValue = 3,
        valueRange = 1f..8f,
        onValueChange = { v ->
            update { it.copy(ttsPreSynthesisConcurrency = v.coerceIn(1, 8)) }
        },
        onDismissRequest = { showConcurrency = false },
    )
    ReadAloudNumberConfigSheet(
        show = showInterval,
        title = stringResource(R.string.tts_paragraph_interval),
        description = stringResource(R.string.tts_paragraph_interval_summary, st.ttsParagraphInterval),
        value = st.ttsParagraphInterval,
        defaultValue = 0,
        valueRange = 0f..5000f,
        onValueChange = { v -> update { it.copy(ttsParagraphInterval = v) } },
        onDismissRequest = { showInterval = false },
    )
    ReadAloudNumberConfigSheet(
        show = showCleanTime,
        title = stringResource(R.string.audio_cache_clean_time),
        description = stringResource(R.string.audio_cache_clean_time_summary, st.audioCacheCleanTime),
        value = st.audioCacheCleanTime,
        defaultValue = 0,
        valueRange = 0f..10080f,
        onValueChange = { v -> update { it.copy(audioCacheCleanTime = v) } },
        onDismissRequest = { showCleanTime = false },
    )
    ReadAloudNumberConfigSheet(
        show = showSynthTimeout,
        title = stringResource(R.string.tts_synth_timeout),
        description = stringResource(R.string.tts_synth_timeout_summary, st.ttsSynthTimeoutSec),
        value = st.ttsSynthTimeoutSec,
        defaultValue = 30,
        valueRange = 5f..120f,
        onValueChange = { v -> update { it.copy(ttsSynthTimeoutSec = v.coerceIn(5, 120)) } },
        onDismissRequest = { showSynthTimeout = false },
    )
    ReadAloudNumberConfigSheet(
        show = showMaxRetry,
        title = stringResource(R.string.tts_max_retry),
        description = stringResource(R.string.tts_max_retry_summary, st.ttsMaxRetry),
        value = st.ttsMaxRetry,
        defaultValue = 5,
        valueRange = 0f..10f,
        onValueChange = { v -> update { it.copy(ttsMaxRetry = v.coerceIn(0, 10)) } },
        onDismissRequest = { showMaxRetry = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlSfx,
        title = "音效音量",
        description = "音效轨音量（0–100%）",
        value = st.alSfxVolume,
        defaultValue = 80,
        valueRange = 0f..100f,
        onValueChange = { v -> update { it.copy(alSfxVolume = v.coerceIn(0, 100)) } },
        onDismissRequest = { showAlSfx = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlAmb,
        title = "环境音量",
        description = "环境底噪轨音量（0–100%）",
        value = st.alAmbVolume,
        defaultValue = 35,
        valueRange = 0f..100f,
        onValueChange = { v -> update { it.copy(alAmbVolume = v.coerceIn(0, 100)) } },
        onDismissRequest = { showAlAmb = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlBgm,
        title = "BGM 音量",
        description = "背景音乐轨音量（0–100%）",
        value = st.alBgmVolume,
        defaultValue = 25,
        valueRange = 0f..100f,
        onValueChange = { v -> update { it.copy(alBgmVolume = v.coerceIn(0, 100)) } },
        onDismissRequest = { showAlBgm = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlMinGap,
        title = "音效最小间隔",
        description = "两条音效之间的全局最小间隔（0–30 秒）",
        value = st.alSfxMinGapS,
        defaultValue = 6,
        valueRange = 0f..30f,
        onValueChange = { v -> update { it.copy(alSfxMinGapS = v.coerceIn(0, 30)) } },
        onDismissRequest = { showAlMinGap = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlSfxCd,
        title = "音效同素材冷却",
        description = "同一音效再次触发的静默窗口（0–300 秒）",
        value = st.alSfxCooldownS,
        defaultValue = 60,
        valueRange = 0f..300f,
        onValueChange = { v -> update { it.copy(alSfxCooldownS = v.coerceIn(0, 300)) } },
        onDismissRequest = { showAlSfxCd = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlBgmCd,
        title = "BGM 冷却",
        description = "同一 BGM 再次起乐的冷却窗口（0–600 秒）",
        value = st.alBgmCooldownS,
        defaultValue = 150,
        valueRange = 0f..600f,
        onValueChange = { v -> update { it.copy(alBgmCooldownS = v.coerceIn(0, 600)) } },
        onDismissRequest = { showAlBgmCd = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlAmbDwell,
        title = "环境驻留",
        description = "环境底噪切换的最短驻留（0–120 秒）",
        value = st.alAmbDwellS,
        defaultValue = 25,
        valueRange = 0f..120f,
        onValueChange = { v -> update { it.copy(alAmbDwellS = v.coerceIn(0, 120)) } },
        onDismissRequest = { showAlAmbDwell = false },
    )
    ReadAloudNumberConfigSheet(
        show = showAlChapCap,
        title = "每章自动补缺上限",
        description = "每章自动合成补缺的最大条数（0=关闭，0–50）",
        value = st.alChapterSynthCap,
        defaultValue = 10,
        valueRange = 0f..50f,
        onValueChange = { v -> update { it.copy(alChapterSynthCap = v.coerceIn(0, 50)) } },
        onDismissRequest = { showAlChapCap = false },
    )
    CloudWordnetConfigSheet(
        show = showCloudWord,
        onDismissRequest = { showCloudWord = false },
    )
}

/**
 * P1.4 · 「云端词网」配置（仓库 + 专用令牌 + 启用 + 测试连接）。
 * P1.5：套用套件 RuleEditSheet 模板——顶左 ✕ / 顶右 ⋮（测试连接）/ 右下浮动保存；字段平铺去 Tiny 卡片。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudWordnetConfigSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    var enabled by remember(show) {
        mutableStateOf(AppConfigStore.getBoolean(PreferKey.cloudWordEnabled) == true)
    }
    var repo by remember(show) {
        mutableStateOf(AppConfigStore.getString(PreferKey.cloudWordRepo).orEmpty())
    }
    var token by remember(show) {
        mutableStateOf(AppConfigStore.getString(PreferKey.cloudWordToken).orEmpty())
    }
    var testing by remember(show) { mutableStateOf(false) }
    var testMsg by remember(show) { mutableStateOf<String?>(null) }
    var menuOpen by remember(show) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        AppConfigStore.putBoolean(PreferKey.cloudWordEnabled, enabled)
        AppConfigStore.putString(PreferKey.cloudWordRepo, repo.trim())
        AppConfigStore.putString(PreferKey.cloudWordToken, token.trim())
        onDismissRequest()
    }

    fun runTest() {
        testing = true
        testMsg = null
        scope.launch {
            testMsg = runCatching {
                CloudWordnetClient.testConnection(repo, token)
            }.getOrElse { "失败：${it.localizedMessage}" }
            testing = false
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "云端词网",
        startAction = {
            MediumTonalButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.close),
            )
        },
        endAction = {
            Box {
                MediumTonalButton(
                    onClick = { menuOpen = true },
                    icon = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_menu),
                )
                RoundDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) { dismiss ->
                    RoundDropdownMenuItem(
                        text = if (testing) "测试中…" else "测试连接",
                        enabled = !testing && repo.isNotBlank() && token.isNotBlank(),
                        onClick = {
                            dismiss()
                            runTest()
                        },
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(
                    text = "把音频库选中的条目（新音频 + 词/别名）推送到 CNB 仓库，由云端流水线合并进词网；" +
                        "处理完成后弹窗提示并自动刷新（下次听书生效）。",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                TinySwitchSettingItem(
                    title = "启用",
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                AppTextField(
                    value = repo,
                    onValueChange = { repo = it; testMsg = null },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = LegadoTheme.colorScheme.surface,
                    label = "仓库（如 Cloisnee/yinpin）",
                )
                AppTextField(
                    value = token,
                    onValueChange = { token = it; testMsg = null },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = LegadoTheme.colorScheme.surface,
                    label = "访问令牌（需 repo-code:rw + repo-cnb-trigger:rw）",
                )
                testMsg?.let {
                    AppText(
                        text = it,
                        style = LegadoTheme.typography.labelSmall,
                        color = if (it.startsWith("连接正常")) {
                            LegadoTheme.colorScheme.primary
                        } else {
                            LegadoTheme.colorScheme.error
                        },
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                AppFloatingActionButton(
                    onClick = { save() },
                    tooltipText = stringResource(R.string.action_save),
                    icon = Icons.Default.Save,
                )
            }
        }
    }
}
