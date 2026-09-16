package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.sheet.ReadAloudNumberConfigSheet
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.help.IntentHelp
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
    val readRepo = remember { GlobalContext.get().get<ReadSettingsRepository>() }
    val aiGateway = remember { GlobalContext.get().get<AiProfileGateway>() }
    val extRepo = remember {
        TtsServerCenterRepository(context.applicationContext as Application)
    }

    var st by remember { mutableStateOf(repo.currentSettings) }
    var preDownloadNum by remember { mutableStateOf(readRepo.currentSettings.preDownloadNum) }
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
        preDownloadNum = readRepo.currentSettings.preDownloadNum
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
                        description = "统一不同插件声线的响度（处理逻辑将在后续版本生效）",
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
                }
            }
        }
    }

    ReadAloudNumberConfigSheet(
        show = showPreDownload,
        title = stringResource(R.string.read_aloud_preload),
        description = stringResource(R.string.read_aloud_preload_summary, preDownloadNum),
        value = preDownloadNum,
        defaultValue = 10,
        valueRange = 0f..100f,
        onValueChange = { v ->
            preDownloadNum = v
            scope.launch { readRepo.setPreDownloadNum(v) }
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
        defaultValue = 10,
        valueRange = 0f..10080f,
        onValueChange = { v -> update { it.copy(audioCacheCleanTime = v) } },
        onDismissRequest = { showCleanTime = false },
    )
}
