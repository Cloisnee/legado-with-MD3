package io.legado.app.ui.ttssrv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.help.readaloud.audio.AudioRemoteCatalog
import io.legado.app.help.readaloud.audio.AudioRemoteDownloader
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.SelectionActions
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.ListScaffold
import io.legado.app.ui.widget.components.list.ListUiState
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import kotlinx.coroutines.launch

/**
 * B33.2b · 远程素材库（CNB 墨听/JRead 索引包：核心音效 / 恐怖惊悚 / matrix24）。
 * 浏览与搜索索引；点击卡片进入多选（原版选中动画 + 左侧滑动多选），下载走按钮 / 「下载选中」。
 */
@Composable
fun AudioRemoteRouteScreen(onBackClick: () -> Unit) {
    AudioRemoteScreen(onBack = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRemoteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var packs by remember { mutableStateOf<List<AudioRemoteCatalog.RemotePack>>(emptyList()) }
    var packError by remember { mutableStateOf<String?>(null) }
    var laneTab by remember { mutableStateOf(0) }
    var loadingIndex by remember { mutableStateOf(false) }
    var indexStatus by remember { mutableStateOf("") }
    var indexError by remember { mutableStateOf<String?>(null) }
    var sounds by remember { mutableStateOf<List<AudioRemoteCatalog.RemoteSound>>(emptyList()) }
    var loadedPackId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var isSearch by remember { mutableStateOf(false) }
    var localNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedIds by remember { mutableStateOf<Set<Any>>(emptySet()) }
    val jobStates by AudioRemoteDownloader.states.collectAsState()

    val inSelectionMode = selectedIds.isNotEmpty()

    // B33.2c：18+ 等 defaultEnabled=false 的包受「18+ 内容」开关控制（默认关闭）
    // B33.4a-附2：开关入口移至顶栏右上角「⋮」菜单（下拉弹窗）
    var adultMenuOpen by remember { mutableStateOf(false) }
    var adultEnabled by remember {
        mutableStateOf(runCatching { AppConfigStore.getBoolean(PreferKey.audioAdultEnabled) == true }.getOrDefault(false))
    }
    fun reloadLocalNames() {
        scope.launch {
            localNames = AudioLibrary.assets(context.applicationContext).map { it.name }.toSet()
        }
    }

    fun loadManifest(force: Boolean = false) {
        scope.launch {
            packError = null
            runCatching { AudioRemoteCatalog.manifest(context.applicationContext, force) }
                .onSuccess { packs = it }
                .onFailure {
                    packError = "远程目录加载失败：${it.localizedMessage}"
                }
        }
    }

    fun loadIndex(pack: AudioRemoteCatalog.RemotePack, force: Boolean = false) {
        loadingIndex = true
        indexError = null
        scope.launch {
            runCatching {
                AudioRemoteCatalog.sounds(context.applicationContext, pack, force) { line ->
                    indexStatus = line
                }
            }.onSuccess {
                sounds = it
                loadedPackId = pack.id
            }.onFailure {
                indexError = "索引加载失败：${it.localizedMessage}"
            }
            loadingIndex = false
            indexStatus = ""
        }
    }

    fun enqueue(sound: AudioRemoteCatalog.RemoteSound) {
        AudioRemoteDownloader.enqueue(context.applicationContext, sound)
    }

    LaunchedEffect(Unit) {
        loadManifest()
        reloadLocalNames()
    }

    LaunchedEffect(packs) {
        val pack = packs.firstOrNull() ?: return@LaunchedEffect
        if (loadedPackId != pack.id) loadIndex(pack)
    }

    val doneCount = jobStates.values.count { it is AudioRemoteDownloader.State.Done }
    LaunchedEffect(doneCount) {
        if (doneCount > 0) reloadLocalNames()
    }

    // M5：分区筛选（音效/环境声/BGM/ADULT；ADULT 跟随 18+ 开关显示）
    val laneTabs = remember(adultEnabled) {
        buildList {
            add("sfx" to "音效")
            add("amb" to "环境声")
            add("bgm" to "BGM")
            if (adultEnabled) add("adult" to "ADULT")
        }
    }
    val laneTabSafe = laneTab.coerceIn(0, laneTabs.size - 1)
    val laneSounds = remember(sounds, adultEnabled, laneTabSafe, laneTabs.size) {
        val lane = laneTabs[laneTabSafe].first
        sounds.filter { it.category == lane && (adultEnabled || it.category != "adult") }
    }
    val shown = remember(laneSounds, query) {
        AudioRemoteCatalog.search(laneSounds, query)
    }

    val uiState = RemoteLibUiState(
        items = shown,
        selectedIds = selectedIds,
        searchKey = query,
        isSearch = isSearch,
        isLoading = loadingIndex,
    )

    // M5：18+ 开关在顶栏「⋮」菜单；决定 ADULT 分区是否显示
    val adultSoundCount = remember(sounds) { sounds.count { it.category == "adult" } }

    ListScaffold(
        title = "远程素材库",
        state = uiState,
        onBackClick = onBack,
        onSearchToggle = { isSearch = it },
        onSearchQueryChange = { query = it },
        searchPlaceholder = "搜索音效名 / 别名（如：万箭齐发）",
        topBarActions = {
            TopBarActionButton(
                onClick = {
                    packs.firstOrNull()?.let { loadIndex(it, force = true) }
                    loadManifest(force = true)
                },
                imageVector = AppIcons.Replay,
                contentDescription = "刷新远程目录",
            )
            Box {
                TopBarActionButton(
                    onClick = { adultMenuOpen = true },
                    imageVector = AppIcons.MoreVert,
                    contentDescription = "更多",
                )
                RoundDropdownMenu(expanded = adultMenuOpen, onDismissRequest = { adultMenuOpen = false }) { dismiss ->
                    RoundDropdownMenuItem(
                        text = if (adultEnabled) {
                            "关闭 18+ 内容（隐藏 ADULT 分区）"
                        } else {
                            "开启 18+ 内容（显示 ADULT 分区 $adultSoundCount 条）"
                        },
                        isSelected = adultEnabled,
                        onClick = {
                            dismiss()
                            val v = !adultEnabled
                            adultEnabled = v
                            AppConfigStore.putBoolean(PreferKey.audioAdultEnabled, v)
                            if (!v) laneTab = 0
                        },
                    )
                }
            }
        },
        selectionActions = SelectionActions(
            onClearSelection = { selectedIds = emptySet() },
            onSelectAll = { selectedIds = shown.map { it.soundId }.toSet() },
            onSelectInvert = {
                selectedIds = shown.map { it.soundId }.toSet() - selectedIds
            },
            primaryAction = ActionItem(text = "下载选中", icon = Icons.Default.Download) {
                val picked = shown.filter { it.soundId in selectedIds }
                picked.forEach { enqueue(it) }
                scope.launch {
                    snackbarHostState.showSnackbar("已加入下载队列：${picked.size} 条")
                }
                selectedIds = emptySet()
            },
            secondaryActions = emptyList(),
        ),
        bottomContent = {
            // M5：恢复 音效/环境声/BGM/ADULT 分栏（ADULT 跟随开关）
            AppTabRow(
                modifier = Modifier.adaptiveHorizontalPadding(),
                tabTitles = laneTabs.map { it.second },
                selectedTabIndex = laneTabSafe,
                onTabSelected = { laneTab = it },
            )
        },
        snackbarHostState = snackbarHostState,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            FastScrollLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = adaptiveContentPadding(
                    top = padding.calculateTopPadding(),
                    bottom = 120.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    val status = when {
                        packError != null -> packError
                        loadingIndex -> indexStatus.ifBlank { "正在载入索引…" }
                        indexError != null -> indexError
                        loadedPackId != null -> "索引就绪：${laneTabs[laneTabSafe].second} ${laneSounds.size} 条 · 点卡片进多选，点右侧按钮下载"
                        else -> "正在准备…"
                    }
                    AppText(
                        text = status.toString(),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                items(shown, key = { it.soundId }) { s ->
                    SelectionItemCard(
                        title = s.name,
                        subtitle = buildString {
                            if (s.categoryName.isNotBlank()) {
                                append(s.categoryName)
                            } else {
                                append(s.category.ifBlank { s.pack })
                            }
                            if (s.subType.isNotBlank()) {
                                append(" · ").append(s.subType)
                            }
                        },
                        isSelected = selectedIds.contains(s.soundId),
                        inSelectionMode = inSelectionMode,
                        onToggleSelection = {
                            selectedIds = if (selectedIds.contains(s.soundId)) {
                                selectedIds - s.soundId
                            } else {
                                selectedIds + s.soundId
                            }
                        },
                        trailingAction = if (inSelectionMode) {
                            null
                        } else {
                            {
                                RemoteDownloadAction(
                                    state = jobStates[s.soundId],
                                    inLibrary = localNames.contains(s.name),
                                    onDownload = { enqueue(s) },
                                )
                            }
                        },
                        contentDescription = s.name,
                    )
                }
            }
            if (inSelectionMode) {
                DraggableSelectionHandler(
                    listState = listState,
                    items = shown,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    idProvider = { it.soundId },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }
    }
}

@Composable
private fun RemoteDownloadAction(
    state: AudioRemoteDownloader.State?,
    inLibrary: Boolean,
    onDownload: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            state is AudioRemoteDownloader.State.Failed -> {
                AppText(
                    text = "失败",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.error,
                )
                SmallPlainButton(
                    onClick = onDownload,
                    icon = Icons.Default.Refresh,
                    contentDescription = "重试",
                )
            }

            state is AudioRemoteDownloader.State.Done || (inLibrary && state == null) -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已在库",
                    tint = LegadoTheme.colorScheme.primary,
                )
            }

            state is AudioRemoteDownloader.State.Running -> {
                AppText(
                    text = "${state.bytes / 1024} KB…",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }

            state is AudioRemoteDownloader.State.Queued -> {
                AppText(
                    text = "排队中…",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                SmallPlainButton(
                    onClick = onDownload,
                    icon = Icons.Default.Download,
                    contentDescription = "下载",
                )
            }
        }
    }
}

private data class RemoteLibUiState(
    override val items: List<AudioRemoteCatalog.RemoteSound> = emptyList(),
    override val selectedIds: Set<Any> = emptySet(),
    override val searchKey: String = "",
    override val isSearch: Boolean = false,
    override val isLoading: Boolean = false,
) : ListUiState<AudioRemoteCatalog.RemoteSound>
