package io.legado.app.ui.book.readaloud.cache

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.SelectionBottomBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.tabRow.CardTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TtsCacheRouteScreen(
    onBackClick: () -> Unit,
    viewModel: TtsCacheViewModel = koinViewModel(),
) {
    TtsCacheScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onIntent = viewModel::onIntent,
        effects = viewModel.effects,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsCacheScreen(
    state: TtsCacheUiState,
    onIntent: (TtsCacheIntent) -> Unit,
    effects: kotlinx.coroutines.flow.Flow<TtsCacheEffect>,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    val selectionActive = state.selectedIds.isNotEmpty()
    val displayed = remember(state.logs, state.activeTab, state.searchKey) {
        state.logs.filter { entry ->
            val tabMatched = when (state.activeTab) {
                TtsCacheTab.Analysis -> entry.category == AppLog.Category.ANALYSIS
                TtsCacheTab.Audio -> entry.category == AppLog.Category.AUDIO
            }
            val searchMatched = state.searchKey.isBlank() ||
                entry.fullContent.contains(state.searchKey, ignoreCase = true)
            tabMatched && searchMatched
        }
    }

    fun listAtBottom(): Boolean {
        val info = listState.layoutInfo
        if (info.totalItemsCount <= 0) return true
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
        return lastVisible.index >= info.totalItemsCount - 1
    }

    val atBottom by remember { derivedStateOf { listAtBottom() } }
    // 默认停在最早一条，不自动跳到最新；只有用户自己滚到底部后才跟随新日志
    var followTail by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1) // 跳过首帧初始值：进入页面时保持停在最早一条
            .collect { scrolling ->
                if (!scrolling) followTail = listAtBottom()
            }
    }
    LaunchedEffect(displayed.size, state.activeTab, state.searchKey) {
        if (followTail && displayed.isNotEmpty()) {
            listState.scrollToItem(displayed.lastIndex)
        }
    }
    // 切换分区：回到最早一条（与「不默认看最新」口径一致）
    LaunchedEffect(state.activeTab) {
        followTail = false
        listState.scrollToItem(0)
    }

    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is TtsCacheEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    fun copySelected() {
        val selected = displayed.filter { it.id in state.selectedIds }
        if (selected.isEmpty()) return
        val text = selected.joinToString("\n\n") { entry ->
            "[${timeFormat.format(Date(entry.timestamp))}] ${entry.fullContent}"
        }
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("readAloudLogs", text)))
        context.toastOnUi(context.getString(R.string.tts_log_copied_count, selected.size))
        onIntent(TtsCacheIntent.SetSelection(emptySet()))
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = if (selectionActive) {
                    stringResource(
                        R.string.list_selected_count,
                        state.selectedIds.size,
                        displayed.size,
                    )
                } else {
                    stringResource(R.string.tts_cache_manage)
                },
                useCharMode = selectionActive,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selectionActive) {
                        TopBarNavigationButton(
                            onClick = { onIntent(TtsCacheIntent.SetSelection(emptySet())) },
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.cancel_select),
                        )
                    } else {
                        TopBarNavigationButton(onClick = onBackClick)
                    }
                },
                actions = {
                    if (!selectionActive) {
                        TopBarActionButton(
                            onClick = { onIntent(TtsCacheIntent.SetSearchMode(!state.isSearch)) },
                            imageVector = AppIcons.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                        TopBarActionButton(
                            onClick = { onIntent(TtsCacheIntent.ShowClearLogsDialog) },
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = stringResource(R.string.tts_log_clear),
                        )
                    }
                },
                bottomContent = {
                    AnimatedVisibility(
                        visible = state.isSearch && !selectionActive,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SearchBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            query = state.searchKey,
                            onQueryChange = { onIntent(TtsCacheIntent.SetSearchKey(it)) },
                            placeholder = stringResource(R.string.tts_log_search_hint),
                        )
                    }
                    CardTabRow(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        tabTitles = listOf(
                            stringResource(R.string.tts_log_tab_analysis),
                            stringResource(R.string.tts_log_tab_audio),
                        ),
                        selectedTabIndex = if (state.activeTab == TtsCacheTab.Analysis) 0 else 1,
                        onTabSelected = { index ->
                            onIntent(
                                TtsCacheIntent.SelectTab(
                                    if (index == 0) TtsCacheTab.Analysis else TtsCacheTab.Audio
                                )
                            )
                        },
                    )
                },
            )
        },
        floatingActionButton = {
            if (!selectionActive && displayed.isNotEmpty() && !atBottom) {
                AppFloatingActionButton(
                    onClick = {
                        followTail = true
                        scope.launch { listState.animateScrollToItem(displayed.lastIndex) }
                    },
                    icon = Icons.Default.KeyboardDoubleArrowDown,
                    tooltipText = stringResource(R.string.tts_log_scroll_to_latest),
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = adaptiveContentPadding(
                    top = paddingValues.calculateTopPadding() + 4.dp,
                    bottom = paddingValues.calculateBottomPadding() + 96.dp,
                ),
            ) {
                if (displayed.isEmpty()) {
                    item(key = "empty") {
                        val (title, summary) = when (state.activeTab) {
                            TtsCacheTab.Analysis -> stringResource(R.string.tts_log_empty_analysis) to
                                    stringResource(R.string.tts_log_empty_analysis_summary)

                            TtsCacheTab.Audio -> stringResource(R.string.tts_log_empty_audio) to
                                    stringResource(R.string.tts_log_empty_audio_summary)
                        }
                        TinyClickableSettingItem(
                            title = title,
                            description = summary,
                            onClick = {},
                        )
                    }
                }
                items(displayed, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        timeText = timeFormat.format(Date(entry.timestamp)),
                        selectionActive = selectionActive,
                        selected = entry.id in state.selectedIds,
                        expanded = entry.id in state.expandedIds,
                        onToggleSelection = { onIntent(TtsCacheIntent.ToggleSelection(entry.id)) },
                        onToggleExpand = { onIntent(TtsCacheIntent.ToggleExpand(entry.id)) },
                    )
                }
            }

            if (selectionActive) {
                DraggableSelectionHandler(
                    listState = listState,
                    items = displayed,
                    selectedIds = state.selectedIds,
                    onSelectionChange = { onIntent(TtsCacheIntent.SetSelection(it)) },
                    idProvider = { it.id },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }

            AnimatedVisibility(
                visible = selectionActive,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .zIndex(1f),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SelectionBottomBar(
                    onSelectAll = {
                        onIntent(TtsCacheIntent.SetSelection(displayed.map { it.id }.toSet()))
                    },
                    onSelectInvert = {
                        onIntent(
                            TtsCacheIntent.SetSelection(
                                displayed.map { it.id }.toSet() - state.selectedIds
                            )
                        )
                    },
                    primaryAction = ActionItem(
                        text = stringResource(R.string.tts_log_copy),
                        icon = Icons.Default.ContentCopy,
                    ) { copySelected() },
                    secondaryActions = emptyList(),
                )
            }
        }
    }

    AppAlertDialog(
        show = state.activeDialog == TtsCacheDialog.ClearLogs,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = "清空朗读日志",
        text = "确认清空应用日志缓冲区？（会同时影响全局日志列表）",
        onConfirm = { onIntent(TtsCacheIntent.ClearLogs) },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )
}

/**
 * 单条朗读日志卡片：点击展开详情（含堆栈），长按进入多选。
 */
@Composable
private fun LogEntryCard(
    entry: TtsLogEntryUi,
    timeText: String,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleSelection: () -> Unit,
    onToggleExpand: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp),
        cornerRadius = 10.dp,
        containerColor = if (selected) {
            LegadoTheme.colorScheme.secondaryContainer
        } else {
            LegadoTheme.colorScheme.surfaceContainerLow
        },
        onClick = { if (selectionActive) onToggleSelection() else onToggleExpand() },
        onLongClick = onToggleSelection,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AnimatedVisibility(
                visible = selectionActive,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    AppCheckbox(
                        checked = selected,
                        onCheckedChange = null,
                        includeStateSemantics = false,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (entry.hasError) Color(0xFFE53935) else Color(0xFF43A047)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                AppText(
                    text = timeText,
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                AppText(
                    text = if (expanded) entry.fullContent else entry.message,
                    style = LegadoTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
