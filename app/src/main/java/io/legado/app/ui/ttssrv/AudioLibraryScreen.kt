package io.legado.app.ui.ttssrv

import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.ReorderableSelectionItem
import io.legado.app.ui.widget.components.divider.PillDivider
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.ListUiState
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.rules.RuleListScaffold
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import kotlin.math.log10
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * B33.2 · 音频库管理（对齐原版「替换净化」范式）：
 * 顶栏：搜索 / 重新扫描 / ⋮（导入 · 排序）；分组多栏；卡片（试听 · 编辑 · 开关）；
 * 点击多选 + 顶栏选中动画 + 底部操作条（开启/禁用/置顶/置底/导出选中/删除）。
 */
@Composable
fun AudioLibraryRouteScreen(onBackClick: () -> Unit, onNavigateToRemote: () -> Unit) {
    AudioLibraryScreen(onBack = onBackClick, onNavigateToRemote = onNavigateToRemote)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AudioLibraryScreen(onBack: () -> Unit, onNavigateToRemote: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val hapticFeedback = LocalHapticFeedback.current

    var allAssets by remember { mutableStateOf<List<AudioLibrary.AudioAsset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rescanning by remember { mutableStateOf(false) }
    var sortMode by remember {
        mutableStateOf(AppConfigStore.getString(PreferKey.audioLibSortMode) ?: "desc")
    }
    var isSearch by remember { mutableStateOf(false) }
    var searchKey by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<Any>>(emptySet()) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var localOrder by remember { mutableStateOf<List<AudioLibrary.AudioAsset>?>(null) }
    var editTarget by remember { mutableStateOf<AudioLibrary.AudioAsset?>(null) }
    var paramsOpen by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var previewEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var showImportPicker by remember { mutableStateOf(false) }

    val inSelectionMode = selectedIds.isNotEmpty()

    fun reload() {
        scope.launch {
            loading = true
            allAssets = AudioLibrary.assets(context.applicationContext)
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) {
        onDispose {
            previewPlayer?.release()
            previewPlayer = null
            previewEnhancer?.let { runCatching { it.release() } }
            previewEnhancer = null
        }
    }

    fun stopPreview() {
        previewPlayer?.let { p ->
            runCatching {
                p.stop()
                p.clearMediaItems()
            }
        }
        playingId = null
    }

    fun togglePreview(asset: AudioLibrary.AudioAsset) {
        if (playingId == asset.id) {
            stopPreview()
            return
        }
        val f = AudioLibrary.fileOf(context, asset)
        if (!f.isFile) {
            scope.launch { snackbarHostState.showSnackbar("文件不在库中（可重新扫描）") }
            return
        }
        val player = previewPlayer ?: ExoPlayer.Builder(context).build().also {
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) playingId = null
                }
            })
            previewPlayer = it
        }
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            player.volume = asset.volume.coerceIn(0f, 1f)
            player.playbackParameters = PlaybackParameters(
                asset.speed.coerceIn(0.5f, 2.0f),
                asset.pitch.coerceIn(0.5f, 2.0f),
            )
            // 超 100%：LoudnessEnhancer 补增益（≤ +12dB）
            val e = previewEnhancer
                ?: LoudnessEnhancer(player.audioSessionId).also { previewEnhancer = it }
            if (asset.volume > 1.001f) {
                val gainDb = (20.0 * log10(asset.volume.toDouble())).coerceIn(0.0, 12.0)
                e.setTargetGain((gainDb * 100).toInt())
                e.enabled = true
            } else {
                e.enabled = false
            }
            player.prepare()
            player.play()
        }
        playingId = asset.id
    }

    fun setSort(mode: String) {
        sortMode = mode
        AppConfigStore.putString(PreferKey.audioLibSortMode, mode)
    }

    fun rescanNow() {
        if (rescanning) return
        rescanning = true
        scope.launch {
            val r = runCatching { AudioLibrary.rescan(context.applicationContext) }
            rescanning = false
            r.onSuccess { s ->
                reload()
                snackbarHostState.showSnackbar("扫描完成：共 ${s.total} 条（新增 ${s.added} · 移除 ${s.removed}）")
            }.onFailure {
                snackbarHostState.showSnackbar("扫描失败：${it.localizedMessage}")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val r = runCatching {
                        AudioLibrary.importAudio(context.applicationContext, uris)
                    }
                    r.onSuccess { s ->
                        reload()
                        snackbarHostState.showSnackbar(
                            "导入完成：成功 ${s.ok} · 跳过 ${s.skipped} · 失败 ${s.fail}"
                        )
                    }.onFailure {
                        snackbarHostState.showSnackbar("导入失败：${it.localizedMessage}")
                    }
                }
            }
        },
    )

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri ->
            if (uri != null) {
                val selected = allAssets.filter { it.id in selectedIds }
                scope.launch {
                    val r = runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            AudioLibrary.exportZip(context.applicationContext, selected, out)
                        } ?: error("无法写入")
                    }
                    r.onSuccess { n ->
                        snackbarHostState.showSnackbar("已导出 $n 条（含规则与音量参数）")
                    }.onFailure {
                        snackbarHostState.showSnackbar("导出失败：${it.localizedMessage}")
                    }
                }
            }
        },
    )

    val tabGroups = remember(allAssets) { allAssets.map { it.groupLabel }.distinct().sorted() }
    val tabTitles = remember(tabGroups) { listOf("全部") + tabGroups }
    val selectedTabIndex = selectedGroup?.let(tabTitles::indexOf)?.takeIf { it >= 0 } ?: 0

    LaunchedEffect(tabGroups, selectedGroup) {
        if (selectedGroup != null && selectedGroup !in tabGroups) {
            selectedGroup = null
        }
    }
    LaunchedEffect(selectedGroup, searchKey, sortMode) {
        localOrder = null
    }

    val shownItems = remember(allAssets, selectedGroup, searchKey, sortMode, localOrder) {
        val local = localOrder
        if (local != null) {
            local
        } else {
            var list = allAssets
            selectedGroup?.let { g -> list = list.filter { it.groupLabel == g } }
            val q = searchKey.trim()
            if (q.isNotEmpty()) {
                list = list.filter { a ->
                    a.name.contains(q, ignoreCase = true) ||
                        a.pattern.contains(q, ignoreCase = true) ||
                        a.replacement.contains(q, ignoreCase = true) ||
                        a.groupLabel.contains(q, ignoreCase = true)
                }
            }
            when (sortMode) {
                "asc" -> list
                "desc" -> list.reversed()
                "name_asc" -> list.sortedBy { it.name.lowercase() }
                "name_desc" -> list.sortedByDescending { it.name.lowercase() }
                else -> list
            }
        }
    }

    fun moveLocal(from: Int, to: Int) {
        val current = localOrder ?: shownItems
        if (from !in current.indices || to !in current.indices || from == to) return
        val list = current.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        localOrder = list
    }

    val canReorder = sortMode == "asc" || sortMode == "desc"
    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        moveLocal(from.index, to.index)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            val current = localOrder ?: return@LaunchedEffect
            val canonical = if (sortMode == "desc") current.reversed() else current
            scope.launch {
                AudioLibrary.reorder(context.applicationContext, canonical.map { it.id })
                localOrder = null
                allAssets = AudioLibrary.assets(context.applicationContext)
            }
        }
    }

    val uiState = AudioLibUiState(
        items = shownItems,
        selectedIds = selectedIds,
        searchKey = searchKey,
        isSearch = isSearch,
        isLoading = loading,
    )
    val dragDisabledMsg = stringResource(R.string.drag_disabled_in_sort_mode)

    RuleListScaffold(
        title = "音频库管理",
        state = uiState,
        onBackClick = onBack,
        onSearchToggle = { isSearch = it },
        onSearchQueryChange = { searchKey = it },
        searchPlaceholder = "搜索音频 / 分组 / 规则",
        topBarActions = {
            TopBarActionButton(
                onClick = { rescanNow() },
                imageVector = AppIcons.Replay,
                contentDescription = "重新扫描素材库",
            )
        },
        onClearSelection = { selectedIds = emptySet() },
        onSelectAll = { selectedIds = shownItems.map { it.id }.toSet() },
        onSelectInvert = {
            selectedIds = shownItems.map { it.id }.toSet() - selectedIds
        },
        selectionSecondaryActions = listOf(
            ActionItem(
                text = "开启选中",
                onClick = {
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    scope.launch {
                        AudioLibrary.setEnabled(context.applicationContext, ids, true)
                        reload()
                    }
                },
            ),
            ActionItem(
                text = "禁用选中",
                onClick = {
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    scope.launch {
                        AudioLibrary.setEnabled(context.applicationContext, ids, false)
                        reload()
                    }
                },
            ),
            ActionItem(
                text = "置顶",
                onClick = {
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    scope.launch {
                        AudioLibrary.moveTop(context.applicationContext, ids)
                        reload()
                    }
                },
            ),
            ActionItem(
                text = "置底",
                onClick = {
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    scope.launch {
                        AudioLibrary.moveBottom(context.applicationContext, ids)
                        reload()
                    }
                },
            ),
            ActionItem(
                text = "导出选中",
                onClick = { exportLauncher.launch("audio_lib_export.zip") },
            ),
        ),
        onDeleteSelected = { ids ->
            scope.launch {
                AudioLibrary.removeAssets(
                    context.applicationContext,
                    ids.filterIsInstance<String>().toSet(),
                )
                selectedIds = emptySet()
                reload()
            }
        },
        bottomContent = {
            if (tabTitles.size > 1) {
                AppTabRow(
                    modifier = Modifier.adaptiveHorizontalPadding(),
                    tabTitles = tabTitles,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index ->
                        selectedGroup = if (index == 0) null else tabTitles.getOrNull(index)
                    },
                )
            }
        },
        dropDownMenuContent = { dismiss ->
            RoundDropdownMenuItem(
                text = stringResource(R.string.import_str),
                onClick = {
                    showImportPicker = true
                    dismiss()
                },
            )
            RoundDropdownMenuItem(
                text = "远程下载",
                onClick = {
                    onNavigateToRemote()
                    dismiss()
                },
            )
            PillDivider()
            RoundDropdownMenuItem(
                text = stringResource(R.string.sort_old_first),
                onClick = {
                    setSort("asc")
                    dismiss()
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.sort_new_first),
                onClick = {
                    setSort("desc")
                    dismiss()
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.sort_name_asc),
                onClick = {
                    setSort("name_asc")
                    dismiss()
                    scope.launch {
                        snackbarHostState.showSnackbar(dragDisabledMsg)
                    }
                },
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.sort_name_desc),
                onClick = {
                    setSort("name_desc")
                    dismiss()
                    scope.launch {
                        snackbarHostState.showSnackbar(dragDisabledMsg)
                    }
                },
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
                if (loading) {
                    item { TinyClickableSettingItem(title = "加载中…", onClick = {}) }
                } else if (shownItems.isEmpty()) {
                    item {
                        TinyClickableSettingItem(
                            title = "还没有音频条目",
                            description = "右上角 ⋮ →「导入」音频或 zip 包",
                            onClick = {},
                        )
                    }
                }
                items(shownItems, key = { it.id }) { ui ->
                    val reorderHint = if (canReorder && !inSelectionMode) "长按拖动排序" else null
                    val itemDescription = listOfNotNull(
                        ui.name,
                        ui.pattern.takeIf { it.isNotBlank() },
                        if (ui.enabled) "已启用" else "已停用",
                        reorderHint,
                    ).joinToString()
                    ReorderableSelectionItem(
                        state = reorderableState,
                        key = ui.id,
                        reorderIndex = shownItems.indexOf(ui),
                        reorderItemCount = shownItems.size,
                        onMoveItem = { from, to -> moveLocal(from, to) },
                        title = ui.name,
                        subtitle = buildString {
                            append(ui.groupLabel)
                            if (ui.pattern.isNotBlank()) {
                                append(" · ").append(ui.pattern)
                            }
                            when (ui.source) {
                                AudioLibrary.SOURCE_GENERATED -> append(" · 合成")
                                AudioLibrary.SOURCE_REMOTE -> append(" · 远程")
                            }
                        },
                        isEnabled = ui.enabled,
                        isSelected = selectedIds.contains(ui.id),
                        inSelectionMode = inSelectionMode,
                        canReorder = canReorder,
                        onToggleSelection = {
                            selectedIds = if (selectedIds.contains(ui.id)) {
                                selectedIds - ui.id
                            } else {
                                selectedIds + ui.id
                            }
                        },
                        onEnabledChange = { enabled ->
                            scope.launch {
                                AudioLibrary.setEnabled(
                                    context.applicationContext,
                                    setOf(ui.id),
                                    enabled,
                                )
                                reload()
                            }
                        },
                        onClickEdit = { editTarget = ui },
                        trailingAction = {
                            SmallPlainButton(
                                onClick = { togglePreview(ui) },
                                icon = if (playingId == ui.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = "试听",
                            )
                        },
                        contentDescription = itemDescription,
                        enableSwitchContentDescription = "启用开关：${ui.name}",
                        editContentDescription = "编辑：${ui.name}",
                    )
                }
            }
            if (inSelectionMode) {
                DraggableSelectionHandler(
                    listState = listState,
                    items = shownItems,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    idProvider = { it.id },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }
    }

    FilePickerSheet(
        show = showImportPicker,
        onDismissRequest = { showImportPicker = false },
        title = "导入音频",
        onSelectSysFiles = { types ->
            importLauncher.launch(types)
            showImportPicker = false
        },
        allowExtensions = arrayOf("zip", "mp3", "m4a", "wav", "ogg", "flac", "aac"),
    )

    AudioEditSheet(
        show = editTarget != null,
        asset = editTarget,
        onDismiss = { editTarget = null },
        onParamsClick = { paramsOpen = true },
        onSave = { updated ->
            editTarget = null
            scope.launch {
                AudioLibrary.updateAsset(context.applicationContext, updated)
                reload()
                snackbarHostState.showSnackbar("已保存")
            }
        },
    )

    AudioParamsSheet(
        show = paramsOpen,
        asset = editTarget,
        onDismiss = { paramsOpen = false },
        onSave = { updated ->
            paramsOpen = false
            editTarget = updated // 防止随后「编辑保存」用旧对象覆盖参数
            scope.launch {
                AudioLibrary.updateAsset(context.applicationContext, updated)
                reload()
                snackbarHostState.showSnackbar("参数已保存（播放中实时生效）")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioEditSheet(
    show: Boolean,
    asset: AudioLibrary.AudioAsset?,
    onDismiss: () -> Unit,
    onParamsClick: () -> Unit,
    onSave: (AudioLibrary.AudioAsset) -> Unit,
) {
    var name by remember(show, asset) { mutableStateOf(asset?.name.orEmpty()) }
    var group by remember(show, asset) { mutableStateOf(asset?.group.orEmpty()) }
    var pattern by remember(show, asset) { mutableStateOf(asset?.pattern.orEmpty()) }
    var replacement by remember(show, asset) { mutableStateOf(asset?.replacement.orEmpty()) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "编辑音频",
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 120.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "名称",
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = group,
                        onValueChange = { group = it },
                        label = "分组",
                        placeholder = { AppText("默认（同分类）") },
                        backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    MediumPlainButton(
                        onClick = onParamsClick,
                        icon = Icons.Default.Settings,
                        contentDescription = "音频参数",
                    )
                }
                AppTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = "匹配规则",
                    placeholder = { AppText("关键词或正则（后续批次接入规则引擎）") },
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = "替换为",
                    placeholder = { AppText("预留字段") },
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AppFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = {
                    asset?.let { base ->
                        onSave(
                            base.copy(
                                name = name.trim().ifBlank { base.name },
                                group = group.trim(),
                                pattern = pattern.trim(),
                                replacement = replacement.trim(),
                            )
                        )
                    }
                },
                tooltipText = "保存",
                icon = Icons.Default.Save,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioParamsSheet(
    show: Boolean,
    asset: AudioLibrary.AudioAsset?,
    onDismiss: () -> Unit,
    onSave: (AudioLibrary.AudioAsset) -> Unit,
) {
    var volume by remember(show, asset) { mutableFloatStateOf(asset?.volume ?: 1f) }
    var speed by remember(show, asset) { mutableFloatStateOf(asset?.speed ?: 1f) }
    var pitch by remember(show, asset) { mutableFloatStateOf(asset?.pitch ?: 1f) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "音频参数 · ${asset?.name.orEmpty()}",
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .padding(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ParamSlider(
                    title = "音量",
                    valueText = "${(volume * 100).toInt()}%",
                    value = volume,
                    range = 0f..2f,
                ) { volume = it }
                ParamSlider(
                    title = "音速",
                    valueText = "%.2f".format(speed),
                    value = speed,
                    range = 0.5f..2f,
                ) { speed = it }
                ParamSlider(
                    title = "音高",
                    valueText = "%.2f".format(pitch),
                    value = pitch,
                    range = 0.5f..2f,
                ) { pitch = it }
                AppText(
                    text = "只影响该条素材（朗读设置里的轨道音量仍照常生效）；播放中实时生效。",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppFloatingActionButton(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                onClick = {
                    asset?.let { base ->
                        onSave(base.copy(volume = volume, speed = speed, pitch = pitch))
                    }
                },
                tooltipText = "保存",
                icon = Icons.Default.Save,
            )
        }
    }
}

@Composable
private fun ParamSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppText(text = title, style = LegadoTheme.typography.bodyMedium)
            AppText(
                text = valueText,
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private data class AudioLibUiState(
    override val items: List<AudioLibrary.AudioAsset> = emptyList(),
    override val selectedIds: Set<Any> = emptySet(),
    override val searchKey: String = "",
    override val isSearch: Boolean = false,
    override val isLoading: Boolean = false,
) : ListUiState<AudioLibrary.AudioAsset>
