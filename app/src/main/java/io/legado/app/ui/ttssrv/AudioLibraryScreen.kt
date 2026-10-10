package io.legado.app.ui.ttssrv

import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.help.readaloud.audio.AudioMissingRow
import io.legado.app.help.readaloud.audio.AudioNetStore
import io.legado.app.help.readaloud.audio.AudioRemoteCatalog
import io.legado.app.help.readaloud.audio.AudioSynthQueue
import io.legado.app.help.readaloud.audio.CloudUploadLedger
import io.legado.app.help.readaloud.audio.CloudWordnetClient
import io.legado.app.help.readaloud.audio.CloudWordnetReceiptWatcher
import io.legado.app.help.readaloud.audio.splitWordList
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.ReorderableSelectionItem
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.divider.PillDivider
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.ListUiState
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.rules.RuleListScaffold
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import kotlin.math.log10
import kotlinx.coroutines.launch
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * B33.2 · 音频库管理（对齐原版「替换净化」范式）：
 * 顶栏：搜索 / 重新扫描 / ⋮（导入 · 排序）；四栏（全部 / BGM / 环境声 / 音效）；卡片（试听 · 编辑 · 开关）；
 * 点击多选 + 顶栏选中动画 + 底部操作条（开启/禁用/置顶/置底/导出选中/删除）。
 */
@Composable
fun AudioLibraryRouteScreen(
    onBackClick: () -> Unit,
    onNavigateToRemote: () -> Unit,
    onNavigateToEdit: (String) -> Unit,
) {
    AudioLibraryScreen(
        onBack = onBackClick,
        onNavigateToRemote = onNavigateToRemote,
        onNavigateToEdit = onNavigateToEdit,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
private fun sourceTag(a: AudioLibrary.AudioAsset): String = when (a.source) {
    AudioLibrary.SOURCE_GENERATED -> "合成"
    AudioLibrary.SOURCE_REMOTE -> "远程"
    else -> "本地"
}

private fun ruleTag(a: AudioLibrary.AudioAsset): String = when {
    a.pattern.isBlank() -> "未填"
    a.isRegex -> "正则"
    else -> "词林"
}

private fun modifiedTag(a: AudioLibrary.AudioAsset): String = if (a.modified) "已修改" else "未修改"

private fun isUploadedNow(context: android.content.Context, a: AudioLibrary.AudioAsset): Boolean {
    if (a.source == AudioLibrary.SOURCE_REMOTE) return true
    if (a.modified) return false
    val lf = AudioLibrary.fileOf(context.applicationContext, a)
    if (!lf.isFile) return false
    return CloudUploadLedger.statusText(
        context.applicationContext,
        a.name,
        if (!a.isRegex && a.pattern.isNotBlank()) splitWordList(a.pattern) else emptyList(),
        lf.length(),
        lf.lastModified(),
    ).isNotEmpty()
}

private fun uploadTag(context: android.content.Context, a: AudioLibrary.AudioAsset): String =
    if (isUploadedNow(context, a)) "已上传" else "未上传"

@Composable
private fun FilterChipRow(
    title: String,
    options: List<Pair<String, String?>>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        AppText(
            text = title,
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (label, value) ->
                val sel = selected == value
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (sel) LegadoTheme.colorScheme.primaryContainer
                            else LegadoTheme.colorScheme.onSheetContent,
                        )
                        .selectable(
                            selected = sel,
                            role = Role.RadioButton,
                            onClick = { onSelect(value) },
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AppText(
                        text = label,
                        style = LegadoTheme.typography.labelMedium,
                        color = if (sel) LegadoTheme.colorScheme.onPrimaryContainer
                        else LegadoTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
fun AudioLibraryScreen(
    onBack: () -> Unit,
    onNavigateToRemote: () -> Unit = {},
    onNavigateToEdit: (String) -> Unit = {},
) {
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
    var isSearch by rememberSaveable { mutableStateOf(false) }
    var searchKey by rememberSaveable { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf<Set<Any>>(emptySet()) }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var localOrder by remember { mutableStateOf<List<AudioLibrary.AudioAsset>?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var previewPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var previewEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var showImportPicker by remember { mutableStateOf(false) }
    var moveSheet by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<String?>(null) }
    var mergeSheet by remember { mutableStateOf(false) }
    var mergeTargetId by remember { mutableStateOf<String?>(null) }
    var cloudPushing by remember { mutableStateOf(false) }
    var generatedOnly by rememberSaveable { mutableStateOf(false) }
    // 组合筛选（作用于当前栏目）：来源 / 规则 / 修改 / 上传；每排单选、跨排 AND
    var fltSource by rememberSaveable { mutableStateOf<String?>(null) }  // null=全部 / "generated"
    var fltUpload by rememberSaveable { mutableStateOf<String?>(null) }  // null=全部 / "uploaded" / "not"
    var fltRule by rememberSaveable { mutableStateOf<String?>(null) }    // null=全部 / "regex" / "net" / "unset"
    var fltMod by rememberSaveable { mutableStateOf<String?>(null) }     // null=全部 / "modified" / "unmodified"
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var missingSheet by remember { mutableStateOf(false) }
    var missingRows by remember { mutableStateOf<List<AudioMissingRow>>(emptyList()) }

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
            previewEnhancer = ensurePreviewEnhancer(player, asset.volume, previewEnhancer)
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
                val extra = if (s.merged > 0) " · 合并重复 ${s.merged}" else ""
                snackbarHostState.showSnackbar("扫描完成：共 ${s.total} 条（新增 ${s.added} · 移除 ${s.removed}$extra）")
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

    // B33.3e：固定四栏（全部 / BGM / 环境声 / 音效）
    val tabTitles = listOf("全部", "BGM", "环境声", "音效")
    val selectedTabIndex = selectedCategory?.let(tabTitles::indexOf)?.takeIf { it >= 0 } ?: 0

    LaunchedEffect(selectedCategory, searchKey, sortMode) {
        localOrder = null
    }

    val shownItems = remember(
        allAssets, selectedCategory, searchKey, sortMode, localOrder, generatedOnly,
        fltSource, fltUpload, fltRule, fltMod,
    ) {
        val local = localOrder
        if (local != null) {
            local
        } else {
            var list = allAssets
            if (generatedOnly) {
                list = list.filter { it.source == AudioLibrary.SOURCE_GENERATED }
            }
            selectedCategory?.let { c -> list = list.filter { it.category == c } }
            when (fltSource) {
                "generated" -> list = list.filter { it.source == AudioLibrary.SOURCE_GENERATED }
                "remote" -> list = list.filter { it.source == AudioLibrary.SOURCE_REMOTE }
                "local" -> list = list.filter {
                    it.source != AudioLibrary.SOURCE_GENERATED &&
                        it.source != AudioLibrary.SOURCE_REMOTE
                }
            }
            when (fltUpload) {
                "uploaded" -> list = list.filter { isUploadedNow(context, it) }
                "not" -> list = list.filterNot { isUploadedNow(context, it) }
            }
            when (fltRule) {
                "regex" -> list = list.filter { it.isRegex && it.pattern.isNotBlank() }
                "net" -> list = list.filter { !it.isRegex && it.pattern.isNotBlank() }
                "unset" -> list = list.filter { it.pattern.isBlank() }
            }
            when (fltMod) {
                "modified" -> list = list.filter { it.modified }
                "unmodified" -> list = list.filterNot { it.modified }
            }
            val q = searchKey.trim()
            if (q.isNotEmpty()) {
                list = list.filter { a ->
                    a.name.contains(q, ignoreCase = true) ||
                        a.pattern.contains(q, ignoreCase = true) ||
                        a.tagDesc.contains(q, ignoreCase = true) ||
                        a.category.contains(q, ignoreCase = true)
                }
            }
            // B34：排序全表生效——「全部」= 全表时间序（新在前/旧在前直接作用于全表）；
            // 分类栏保持入库序/拖排语义（asc=入库序、desc=倒序）
            when (sortMode) {
                "asc" -> if (selectedCategory == null) list.sortedBy { it.mtime } else list
                "desc" -> if (selectedCategory == null) {
                    list.sortedByDescending { it.mtime }
                } else {
                    list.reversed()
                }
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

    // B34：全表时间序下不提供拖排（跨类自定义序无持久化语义）；分类栏照旧
    val canReorder = (sortMode == "asc" || sortMode == "desc") && selectedCategory != null
    val listState = rememberLazyListState()

    // B34.2·⑪ + P1.4 修复：返回保位——先恢复、后跟录；加载期与恢复前一律不记录
    // （旧实现里滚动监听在返回重建的加载期把 (0,0) 写回记忆值，恢复读取时已被冲掉 → 回顶部）
    var savedScrollIndex by rememberSaveable { mutableIntStateOf(0) }
    var savedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var scrollRestored by remember { mutableStateOf(false) }
    LaunchedEffect(loading, shownItems.size) {
        if (loading || shownItems.isEmpty()) return@LaunchedEffect
        if (!scrollRestored) {
            scrollRestored = true
            if (savedScrollIndex > 0) {
                listState.scrollToItem(
                    savedScrollIndex.coerceAtMost(shownItems.lastIndex),
                    savedScrollOffset,
                )
            }
        }
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                savedScrollIndex = idx
                savedScrollOffset = off
            }
    }
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

    // 筛选面板（四排小标签；每排单选、跨排 AND；作用于当前栏目）
    AppModalBottomSheet(
        animateContentSize = false,
        show = filterSheet,
        onDismissRequest = { filterSheet = false },
        title = "筛选",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            FilterChipRow(
                title = "来源",
                options = listOf(
                    "全部" to null, "远程" to "remote", "合成" to "generated", "本地" to "local",
                ),
                selected = fltSource,
                onSelect = { fltSource = it },
            )
            FilterChipRow(
                title = "规则",
                options = listOf(
                    "全部" to null, "正则" to "regex", "词林" to "net", "未填" to "unset",
                ),
                selected = fltRule,
                onSelect = { fltRule = it },
            )
            FilterChipRow(
                title = "修改",
                options = listOf("全部" to null, "已修改" to "modified", "未修改" to "unmodified"),
                selected = fltMod,
                onSelect = { fltMod = it },
            )
            FilterChipRow(
                title = "上传",
                options = listOf("全部" to null, "已上传" to "uploaded", "未上传" to "not"),
                selected = fltUpload,
                onSelect = { fltUpload = it },
            )
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
                onClick = { filterSheet = true },
                imageVector = AppIcons.Filter,
                contentDescription = "筛选",
            )
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
                text = "移动音频",
                onClick = {
                    moveTarget = null
                    moveSheet = true
                },
            ),
            ActionItem(
                text = "合并跟随",
                onClick = {
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    val picked = allAssets.filter { it.id in ids }
                    val lanes = picked.map { AudioLibrary.laneOf(it) }.distinct()
                    when {
                        picked.size < 2 -> scope.launch {
                            snackbarHostState.showSnackbar("至少选择两个音频才能合并")
                        }
                        lanes.size > 1 -> scope.launch {
                            snackbarHostState.showSnackbar("仅支持同一栏目内合并（当前选中跨栏目）")
                        }
                        else -> {
                            mergeTargetId = null
                            mergeSheet = true
                        }
                    }
                },
            ),
            ActionItem(
                text = "补充云端词网",
                onClick = {
                    val enabled = AppConfigStore.getBoolean(PreferKey.cloudWordEnabled) == true
                    val repoCfg = AppConfigStore.getString(PreferKey.cloudWordRepo).orEmpty()
                    val tokenCfg = AppConfigStore.getString(PreferKey.cloudWordToken).orEmpty()
                    val ids = selectedIds.filterIsInstance<String>().toSet()
                    val picked = allAssets.filter { it.id in ids }
                    when {
                        cloudPushing -> scope.launch {
                            snackbarHostState.showSnackbar("正在提交中，请稍候…")
                        }
                        !enabled || repoCfg.isBlank() || tokenCfg.isBlank() -> scope.launch {
                            snackbarHostState.showSnackbar("请先在 朗读设置 → 云端词网 配置仓库与令牌")
                        }
                        picked.isEmpty() -> scope.launch {
                            snackbarHostState.showSnackbar("请先选择要补充的条目")
                        }
                        picked.size > 20 -> scope.launch {
                            snackbarHostState.showSnackbar("单批最多 20 条，请分批提交")
                        }
                        else -> {
                            scope.launch {
                                // P1.5：提交防连击——上一批回执未回时提示可能竞态（可仍要提交）
                                if (CloudWordnetReceiptWatcher.isWatching()) {
                                    val r = snackbarHostState.showSnackbar(
                                        message = "上一批仍在处理（约 1 分钟），连续提交可能相互竞态",
                                        actionLabel = "仍要提交",
                                        withDismissAction = true,
                                    )
                                    if (r != SnackbarResult.ActionPerformed) return@launch
                                }
                                cloudPushing = true
                                val msg = pushToCloud(context.applicationContext, picked)
                                cloudPushing = false
                                selectedIds = emptySet()
                                snackbarHostState.showSnackbar(msg)
                            }
                        }
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
                        selectedCategory = if (index == 0) null else tabTitles.getOrNull(index)
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
            RoundDropdownMenuItem(
                text = "缺失清单",
                onClick = {
                    dismiss()
                    scope.launch {
                        missingRows = AudioSynthQueue.missingRows(context.applicationContext)
                        missingSheet = true
                    }
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
                            append(ui.category)
                            append("·").append(sourceTag(ui))
                            append("·").append(ruleTag(ui))
                            append("·").append(modifiedTag(ui))
                            append("·").append(uploadTag(context, ui))
                        },
                        subtitleMaxLines = 2,
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
                        onClickEdit = { onNavigateToEdit(ui.id) },
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

    AudioMissingSheet(
        show = missingSheet,
        rows = missingRows,
        onRetry = { row ->
            scope.launch {
                AudioSynthQueue.retryMissing(context.applicationContext, row.lane, row.keyword)
                missingRows = AudioSynthQueue.missingRows(context.applicationContext)
                snackbarHostState.showSnackbar("已重置：${row.keyword}（回放命中章节即可再补缺）")
            }
        },
        onRemove = { row ->
            scope.launch {
                AudioSynthQueue.removeMissing(context.applicationContext, row.lane, row.keyword)
                missingRows = AudioSynthQueue.missingRows(context.applicationContext)
            }
        },
        onDismiss = { missingSheet = false },
    )

    AudioMoveSheet(
        show = moveSheet,
        count = selectedIds.size,
        target = moveTarget,
        onTargetChange = { moveTarget = it },
        onDismiss = { moveSheet = false },
        onConfirm = {
            val dest = moveTarget
            if (dest == null) {
                scope.launch { snackbarHostState.showSnackbar("请先选择目标分组") }
            } else {
                val ids = selectedIds.filterIsInstance<String>().toSet()
                moveSheet = false
                scope.launch {
                    val ok = runCatching {
                        AudioLibrary.setCategory(context.applicationContext, ids, dest)
                    }.getOrDefault(false)
                    selectedIds = emptySet()
                    reload()
                    snackbarHostState.showSnackbar(if (ok) "已移动到：$dest" else "未发生变更")
                }
            }
        },
    )

    AudioMergeFollowSheet(
        show = mergeSheet,
        items = allAssets.filter { it.id in selectedIds.filterIsInstance<String>() },
        targetId = mergeTargetId,
        playingId = playingId,
        onPreview = { a -> togglePreview(a) },
        onTargetChange = { mergeTargetId = it },
        onDismiss = {
            mergeSheet = false
            stopPreview()
        },
        onConfirm = {
            val dest = mergeTargetId
            if (dest == null) {
                scope.launch { snackbarHostState.showSnackbar("请先选择跟随目标") }
            } else {
                val ids = selectedIds.filterIsInstance<String>().toSet()
                mergeSheet = false
                stopPreview()
                scope.launch {
                    val res = runCatching {
                        AudioLibrary.mergeFollow(context.applicationContext, dest, ids)
                    }.getOrDefault(AudioLibrary.MergeFollowResult(0, 0, "合并异常"))
                    if (res.error == null) {
                        // 本地词网即时生效（播放匹配 / 建议 / 归一层三处）
                        runCatching { AudioNetStore.rebuildLocalWords(context.applicationContext) }
                    }
                    selectedIds = emptySet()
                    reload()
                    snackbarHostState.showSnackbar(
                        if (res.error == null) {
                            "已合并跟随：并入 ${res.mergedWords} 个词、删除 ${res.removed} 个音频"
                        } else {
                            "未合并：${res.error}"
                        }
                    )
                }
            }
        },
    )

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

}

@androidx.annotation.OptIn(UnstableApi::class)
private fun ensurePreviewEnhancer(
    player: ExoPlayer,
    volume: Float,
    current: LoudnessEnhancer?,
): LoudnessEnhancer {
    val e = current ?: LoudnessEnhancer(player.audioSessionId)
    if (volume > 1.001f) {
        val gainDb = (20.0 * log10(volume.toDouble())).coerceIn(0.0, 12.0)
        e.setTargetGain((gainDb * 100).toInt())
        e.enabled = true
    } else {
        e.enabled = false
    }
    return e
}

private data class AudioLibUiState(
    override val items: List<AudioLibrary.AudioAsset> = emptyList(),
    override val selectedIds: Set<Any> = emptySet(),
    override val searchKey: String = "",
    override val isSearch: Boolean = false,
    override val isLoading: Boolean = false,
) : ListUiState<AudioLibrary.AudioAsset>

/** B33.2c · 缺失清单（读取 _store/audio_missing.json；「重试」=重置冷却） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioMissingSheet(
    show: Boolean,
    rows: List<AudioMissingRow>,
    onRetry: (AudioMissingRow) -> Unit,
    onRemove: (AudioMissingRow) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "缺失清单：${rows.size} 条",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rows.isEmpty()) {
                AppText(
                    text = "暂无缺失记录（播放中解析不到素材、且触发过补缺的会出现在这里）",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.sortedByDescending { it.updatedAt }.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AppText(text = row.keyword, style = LegadoTheme.typography.bodyMedium)
                        AppText(
                            text = buildString {
                                append(
                                    when (row.lane) {
                                        "AMB" -> "环境"
                                        "BGM" -> "BGM"
                                        else -> "音效"
                                    }
                                )
                                append(" · ").append(row.status)
                                if (row.source.isNotBlank()) append(" · ").append(row.source)
                                if (row.lastError.isNotBlank()) {
                                    append(" · ").append(row.lastError.take(24))
                                }
                            },
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    SmallPlainButton(
                        onClick = { onRetry(row) },
                        icon = AppIcons.Replay,
                        contentDescription = "重试（清冷却）",
                    )
                    SmallPlainButton(
                        onClick = { onRemove(row) },
                        icon = Icons.Default.Delete,
                        contentDescription = "删除记录",
                    )
                }
            }
        }
    }
}
/** B33.3c-附3 · 批量「移动音频」到固有分组（复刻「移动声线·一级分组」卡片：卡片+右侧标签+点击下拉） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioMoveSheet(
    show: Boolean,
    count: Int,
    target: String?,
    onTargetChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var open by remember(show) { mutableStateOf(false) }
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "移动音频：$count 条",
        endAction = {
            MediumTonalButton(
                onClick = onConfirm,
                icon = Icons.Default.Check,
                contentDescription = "移动",
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TinySettingItem(
                    title = "目标分组",
                    trailingContent = {
                        TextCard(
                            text = target ?: "请选择",
                            backgroundColor = LegadoTheme.colorScheme.surfaceContainerHigh,
                            contentColor = if (target == null) {
                                LegadoTheme.colorScheme.onSurfaceVariant
                            } else {
                                LegadoTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = { open = true },
                )
                RoundDropdownMenu(expanded = open, onDismissRequest = { open = false }) { dismiss ->
                    listOf("音效", "BGM", "环境声").forEach { g ->
                        RoundDropdownMenuItem(
                            text = g,
                            isSelected = g == target,
                            onClick = {
                                dismiss()
                                onTargetChange(g)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** P1.4 · 组批提交云端词网（新音频上传；库内同名只并词）；返回用户可读结果 */
private suspend fun pushToCloud(
    appContext: android.content.Context,
    picked: List<AudioLibrary.AudioAsset>,
): String {
    val repo = AppConfigStore.getString(PreferKey.cloudWordRepo).orEmpty()
    val token = AppConfigStore.getString(PreferKey.cloudWordToken).orEmpty()
    // 先确保词网就绪（冷启动下 snapshot 为空会退化为「全部上传→服务端去重」，浪费流量）
    // P1.5：强制复核——保证「库内同名只并词」判定基于最新词网（节流窗口内的近期批也生效）
    runCatching { AudioNetStore.ensureLoaded(appContext, force = true) }
    val netKeys: Set<Pair<String, String>> = runCatching {
        AudioNetStore.snapshot()
            .map { AudioRemoteCatalog.laneNameOf(it.lane) to it.name }
            .toSet()
    }.getOrDefault(emptySet())
    var skipped = 0
    val items = ArrayList<CloudWordnetClient.Item>(picked.size)
    // P1.6.2+（第二刀）：上传账本快照（实际提交的条目 → 名字/词集/文件指纹）
    val ledgerRecords = ArrayList<CloudUploadLedger.Record>(picked.size)
    picked.forEach { a ->
        val lane = AudioLibrary.laneOf(a)
        val matched = (lane to a.name) in netKeys
        val filePath = if (matched) {
            null
        } else {
            val f = AudioLibrary.fileOf(appContext, a)
            if (a.zipRel.isBlank() && f.isFile && f.length() > 0L) f.absolutePath else null
        }
        if (!matched && filePath == null) {
            skipped++
            return@forEach
        }
        val ext = a.relPath.substringAfterLast('.', "mp3").lowercase()
            .let { if (it in setOf("mp3", "m4a", "wav", "ogg", "flac", "aac")) it else "mp3" }
        val words = if (!a.isRegex && a.pattern.isNotBlank()) splitWordList(a.pattern) else emptyList()
        val lf = AudioLibrary.fileOf(appContext, a)
        val lfSize = runCatching { lf.length() }.getOrDefault(0L)
        val lfMtime = runCatching { lf.lastModified() }.getOrDefault(0L)
        // 第三刀：改名修订——账本显示上次上传名与当前不同 → 云端重命名（而非新建/并词）
        val renamedFrom = CloudUploadLedger.lastDifferentName(appContext, a.name, lfSize, lfMtime)
        items += CloudWordnetClient.Item(
            name = a.name,
            lane = lane,
            words = words,
            // 第四刀v3：正则条目的规则不上云（别名置空）
            aliases = if (a.isRegex) emptyList() else a.aliases,
            filePath = filePath,
            ext = ext,
            renamedFrom = renamedFrom,
        )
        ledgerRecords += CloudUploadLedger.Record(
            name = a.name,
            words = words,
            size = lfSize,
            mtime = lfMtime,
        )
    }
    if (items.isEmpty()) return "没有可提交的条目（$skipped 条缺少文件）"
    val res = CloudWordnetClient.pushBatch(repo, token, items)
    return if (res.error != null) {
        "提交失败：${res.error}"
    } else {
        // P1.5：后台轮询完成回执（非页面作用域——退出页面不中断）→ 完成后弹窗 + 词网热刷新
        val slug = res.slug
        val batch = res.batch
        if (slug != null && batch != null) {
            CloudWordnetReceiptWatcher.watch(appContext, slug, batch)
            // P1.6.2+（第二刀）：上传账本——记录本批快照；回执后转「已上传」
            runCatching { CloudUploadLedger.record(appContext, batch, ledgerRecords) }
        }
        // 提交成功：修改复位（origin=当前）——「未修改·已上传」
        picked.forEach { a ->
            runCatching { AudioLibrary.resetModifiedOrigin(appContext, a.id) }
        }
        buildString {
            append("已提交云端处理：上传 ${res.uploaded}、仅并词 ${res.mergeOnly}")
            if (skipped > 0) append("、跳过 $skipped")
            append("；完成后自动刷新词网（弹窗提示）")
        }
    }
}

/**
 * P1.4 · 「合并跟随」：勾选一个目标，其余条目的名称/词模式词/别名并入其匹配规则（词模式、关正则），并连文件删除其余。
 * P1.5：行样式对齐「声音选择（多选+试听）」——左方框勾选 + 中间名称 + 右侧试听（本地预览）；
 * 警告文案合并为一处（原：逐条正则提示 + 目标正则提示两处）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioMergeFollowSheet(
    show: Boolean,
    items: List<AudioLibrary.AudioAsset>,
    targetId: String?,
    playingId: String?,
    onPreview: (AudioLibrary.AudioAsset) -> Unit,
    onTargetChange: (String?) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val regexNames = items.filter { it.isRegex && it.pattern.isNotBlank() }.map { it.name }
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = "合并跟随：${items.size} 条",
        endAction = {
            MediumTonalButton(
                onClick = onConfirm,
                icon = Icons.Default.Check,
                contentDescription = "合并",
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText(
                text = "勾选要跟随的目标（单选）。其余音频的「名称 + 词模式词 + 别名」将并入目标的匹配规则（词模式），随后连文件删除其余音频。",
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
            items.forEach { a ->
                val checked = a.id == targetId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onTargetChange(if (checked) null else a.id) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppCheckbox(
                        checked = checked,
                        onCheckedChange = null,
                        includeStateSemantics = false,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AppText(
                        text = a.name,
                        style = LegadoTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    SmallPlainButton(
                        onClick = { onPreview(a) },
                        icon = if (playingId == a.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = "试听",
                    )
                }
            }
            // P1.5：警告文案合并一处（正则关闭提示 + 删除提示）
            if (regexNames.isNotEmpty()) {
                AppText(
                    text = "⚠ 涉及正则规则（${regexNames.joinToString("、")}）：合并将关闭正则，原正则内容不保留。",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            AppText(
                text = "将删除其余 ${items.size - 1} 个音频（连文件，不可撤销）。",
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}
