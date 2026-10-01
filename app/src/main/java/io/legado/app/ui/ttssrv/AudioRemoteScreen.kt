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
    var selectedTab by remember { mutableStateOf(0) }
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

    // 18+ 等 defaultEnabled=false 的包默认不出现（后续「分类开关」批次再放开）
    val visiblePacks = remember(packs) { packs.filter { it.defaultEnabled } }

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

    LaunchedEffect(visiblePacks, selectedTab) {
        val pack = visiblePacks.getOrNull(selectedTab) ?: return@LaunchedEffect
        if (loadedPackId != pack.id) loadIndex(pack)
    }

    val doneCount = jobStates.values.count { it is AudioRemoteDownloader.State.Done }
    LaunchedEffect(doneCount) {
        if (doneCount > 0) reloadLocalNames()
    }

    val shown = remember(sounds, query) {
        AudioRemoteCatalog.search(sounds, query)
    }

    val uiState = RemoteLibUiState(
        items = shown,
        selectedIds = selectedIds,
        searchKey = query,
        isSearch = isSearch,
        isLoading = loadingIndex,
    )

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
                    visiblePacks.getOrNull(selectedTab)?.let { loadIndex(it, force = true) }
                    loadManifest(force = true)
                },
                imageVector = AppIcons.Replay,
                contentDescription = "刷新远程目录",
            )
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
            if (visiblePacks.size > 1) {
                AppTabRow(
                    modifier = Modifier.adaptiveHorizontalPadding(),
                    tabTitles = visiblePacks.map { it.label },
                    selectedTabIndex = selectedTab.coerceIn(0, visiblePacks.size - 1),
                    onTabSelected = { selectedTab = it },
                )
            }
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
                        loadedPackId != null -> "索引就绪：共 ${sounds.size} 条 · 点卡片进多选，点右侧按钮下载"
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
