package io.legado.app.ui.book.readaloud.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.log.LogDetailSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount <= 0) {
                true
            } else {
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
            }
        }
    }
    LaunchedEffect(state.logs.size) {
        if (isAtBottom && state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }
    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is TtsCacheEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.tts_cache_manage),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(TtsCacheIntent.ShowClearLogsDialog) },
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "清空日志",
                    )
                },
            )
        },
        floatingActionButton = {
            if (!isAtBottom && state.logs.isNotEmpty()) {
                AppFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem((state.logs.size - 1).coerceAtLeast(0))
                        }
                    },
                    icon = Icons.Default.KeyboardDoubleArrowDown,
                    tooltipText = "滚动到底部",
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 96.dp,
            ),
        ) {
            if (false) {
                item {
                    val sizeText = TtsCacheViewModel.formatSize(state.totalSizeBytes)
                    val countText =
                        stringResource(R.string.tts_cache_file_count, state.files.size)
                    TinyClickableSettingItem(
                        title = stringResource(R.string.tts_cache_total),
                        description = "$sizeText · $countText",
                        onClick = {},
                    )
                }
                if (state.files.isEmpty() && !state.loading) {
                    item {
                        TinyClickableSettingItem(
                            title = stringResource(R.string.tts_cache_empty),
                            description = stringResource(R.string.tts_cache_empty_summary),
                            onClick = {},
                        )
                    }
                }
                items(state.files, key = { it.name }) { file ->
                    val sizeText = TtsCacheViewModel.formatSize(file.sizeBytes)
                    val dateText = dateFormat.format(Date(file.lastModified))
                    val displayText = file.text.ifEmpty {
                        stringResource(R.string.tts_cache_unknown_text)
                    }
                    TinyClickableSettingItem(
                        title = displayText,
                        description = "$sizeText · $dateText",
                        onClick = {
                            onIntent(
                                TtsCacheIntent.ShowFileDetail(
                                    name = file.name,
                                    text = file.text,
                                    sizeBytes = file.sizeBytes,
                                    lastModified = file.lastModified,
                                )
                            )
                        },
                    )
                }
            } else {
                if (state.logs.isEmpty()) {
                    item {
                        TinyClickableSettingItem(
                            title = stringResource(R.string.tts_cache_logs_empty),
                            description = stringResource(R.string.tts_cache_logs_empty_summary),
                            onClick = {},
                        )
                    }
                }
                items(state.logs, key = { "${it.timestamp}:${it.message.hashCode()}" }) { entry ->
                    val timeText = dateFormat.format(Date(entry.timestamp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (entry.hasError) Color(0xFFE53935) else Color(0xFF43A047)
                                ),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                AppText(
                                    text = timeText,
                                    style = LegadoTheme.typography.labelSmall,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                                )
                                AppText(
                                    text = entry.message,
                                    style = LegadoTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = LegadoTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }

    AppAlertDialog(
        show = state.activeDialog == TtsCacheDialog.ClearAll,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = stringResource(R.string.clear_all_tts_cache),
        text = stringResource(R.string.sure_del),
        onConfirm = { onIntent(TtsCacheIntent.ClearAll) },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )

    AppAlertDialog(
        show = state.activeDialog == TtsCacheDialog.ClearLogs,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = "清空朗读日志",
        text = "确认清空应用日志缓冲区？（会同时影响全局日志列表）",
        onConfirm = { onIntent(TtsCacheIntent.ClearLogs) },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )

    LogDetailSheet(
        show = state.showDetail,
        title = state.detailTitle,
        content = state.detailContent,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDetail) },
    )
}
