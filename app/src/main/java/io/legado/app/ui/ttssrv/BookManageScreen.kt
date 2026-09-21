package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.CharacterRecord
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.data.repository.ScriptLineRow
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV3
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.SelectionBottomBar
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 书籍管理（对照「角色管理」v36 插件书籍管理 + MD3 真身元素复刻）：
 *  - 搜索（书源式展开，搜本章剧本内容）· 书籍卡（切换）· 章节卡（章节列表）
 *  - 剧本卡片：前=标签（点=换角色：旁白 / 本章人物 / 历史核心·特殊 / ＋新增人物），后=文本
 *  - 多选换角色（长按进入多选）· 保存=重写剧本+缓存同步+剔除本章合并凭据
 *  - 章节删除：仅删剧本+状态 / 尾部连续章可选【回滚】（人物与合并账本逆向）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookManageScreen(
    app: Application,
    onBack: () -> Unit,
    embedded: Boolean = false,
    initialChapter: Int? = null,
    // 宿主（剧本审查）提供的 bookUrl；null = 按当前书解析（二合一面）
    externalBookUrl: String? = null,
    refreshKey: Int = 0,
    hostTab: Int = 0,
    onHostTabSelected: (Int) -> Unit = {},
    bookTabLabel: String = "剧本",
) {
    val context = LocalContext.current
    val repo = remember(app) { ReadAloudDataRepository(app) }
    val scope = rememberCoroutineScope()

    var bookList by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentBook by remember { mutableStateOf("默认") }
    var records by remember { mutableStateOf<List<CharacterRecord>>(emptyList()) }
    var chapters by remember { mutableStateOf<List<Int>>(emptyList()) }
    var selectedChapter by remember { mutableStateOf<Int?>(null) }
    var lines by remember { mutableStateOf<List<ScriptLineRow>>(emptyList()) }

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var showBookMenu by remember { mutableStateOf(false) }
    var showChapterMenu by remember { mutableStateOf(false) }
    var showChapterSheet by remember { mutableStateOf(false) }
    var tagSearch by remember { mutableStateOf("") }

    val pending = remember { mutableStateMapOf<Int, String>() }
    var selLines by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var tagPanelFor by remember { mutableStateOf<List<Int>?>(null) }

    var newCharDialog by remember { mutableStateOf(false) }
    var ncName by remember { mutableStateOf("") }
    var ncRole by remember { mutableStateOf("核心") }
    var ncGender by remember { mutableStateOf("男") }
    var ncVoice by remember { mutableStateOf("") }
    var bmPicker by remember { mutableStateOf<VoiceTagPickRequest?>(null) }

    var deleteChapterTarget by remember { mutableStateOf<Int?>(null) }
    var showDeleteBookSheet by remember { mutableStateOf(false) }
    var pendingDeleteBook by remember { mutableStateOf<String?>(null) }
    var showAssetMenu by remember { mutableStateOf(false) }
    var showExportAssetSheet by remember { mutableStateOf(false) }
    var exportSel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingExportBooks by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmImportAssets by remember { mutableStateOf(false) }

    fun reloadLines() {
        scope.launch {
            val ch = selectedChapter
            lines = if (ch == null) emptyList() else repo.loadChapterScript(currentBook, ch)
            pending.clear()
            selLines = emptySet()
        }
    }

    fun reload() {
        scope.launch {
            val st = repo.loadState()
            bookList = st.bookList
            currentBook = st.currentBook
            records = st.records
            chapters = repo.loadChapters(currentBook)
            if (embedded && initialChapter != null) {
                selectedChapter = initialChapter
            } else if (selectedChapter == null || selectedChapter !in chapters) {
                selectedChapter = chapters.lastOrNull()
            }
            reloadLines()
        }
    }

    fun reloadRecordsOnly() {
        scope.launch {
            records = repo.loadState().records
        }
    }

    /** 重析当前章：优先宿主提供的 bookUrl（剧本审查），否则按当前书解析（二合一面） */
    fun enqueueReanalyze() {
        val ch = selectedChapter ?: return
        scope.launch {
            val url = externalBookUrl ?: repo.loadBookUrl(currentBook)
            if (url.isBlank()) {
                context.toastOnUi("无法定位该书数据（先朗读一次以建立章节缓存）")
                return@launch
            }
            runCatching {
                val scheduler: AnalysisSchedulerV3 = GlobalContext.get().get()
                scheduler.enqueueChapter(url, ch, force = true)
            }.onFailure { AppLog.put("重析入队失败: ${it.localizedMessage}", it) }
            context.toastOnUi("已提交重新分析（第${ch + 1}章）")
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    fun applySpeakerTo(targets: List<Int>, speaker: String) {
        targets.forEach { pending[it] = speaker }
        tagPanelFor = null
        // 保留所选：多选底栏随即提供「主题色 ✓ 保存」（B10.3·U8）
    }

    fun savePending() {
        val ch = selectedChapter ?: return
        if (pending.isEmpty()) return
        val changes = pending.toMap()
        scope.launch {
            val ok = repo.rewriteChapterSpeakers(currentBook, ch, changes)
            context.toastOnUi(
                if (ok) "已保存：标记改动生效（角色记录未变动；本章合并凭据已清除）"
                else "保存失败：剧本不存在或改动无效"
            )
            reloadLines()
        }
    }

    // 尾部连续章判断（回滚适用性）
    fun tailMin(): Int {
        val sorted = chapters.sorted()
        if (sorted.isEmpty()) return 0
        var tail = sorted.last()
        for (i in sorted.size - 2 downTo 0) {
            if (sorted[i] == sorted[i + 1] - 1) tail = sorted[i] else break
        }
        return tail
    }

    val shown = lines.filter {
        query.isBlank() ||
            it.text.contains(query, ignoreCase = true) ||
            it.speaker.contains(query, ignoreCase = true)
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val listState = rememberLazyListState()

    // B10.4.2·U10：书籍资产导出 / 导入（zip · SAF）
    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val books = pendingExportBooks
                val n = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            repo.exportBooksZip(books, out)
                        } ?: -1
                    }
                }.getOrDefault(-1)
                context.toastOnUi(
                    when {
                        n < 0 -> "导出失败"
                        n == 0 -> "没有可导出的数据"
                        else -> "已导出 $n 本书资产"
                    }
                )
            }
        }
    }
    val importZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val (ok, msg) = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { repo.importBooksZip(it) }
                            ?: (false to "读取文件失败")
                    }
                }.getOrElse { false to "导入失败：${it.localizedMessage ?: it.javaClass.simpleName}" }
                context.toastOnUi(msg)
                if (ok) reload()
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = if (selLines.isNotEmpty()) {
                    stringResource(R.string.list_selected_count, selLines.size, shown.size)
                } else {
                    bookTabLabel
                },
                useCharMode = selLines.isNotEmpty(),
                subtitle = if (embedded) {
                    "当前书：$currentBook · 第${selectedChapter ?: "-"}章"
                } else {
                    "当前书：$currentBook · ${chapters.size} 章剧本"
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (selLines.isNotEmpty()) {
                        TopBarNavigationButton(
                            onClick = { selLines = emptySet() },
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_select),
                        )
                    } else {
                        TopBarNavigationButton(onClick = onBack)
                    }
                },
                actions = {
                    TopBarActionButton(
                        onClick = {
                            searchMode = !searchMode
                            if (!searchMode) query = ""
                        },
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                    )
                    // 独立入口（二合一面）顶栏只保留搜索：重析仅剧本审查（嵌入）提供
                    if (embedded) {
                        TopBarActionButton(
                            onClick = { enqueueReanalyze() },
                            imageVector = Icons.Default.FindReplace,
                            contentDescription = "重新分析本章",
                        )
                    }
                    // B10.4.2·U10：书籍资产导出 / 导入（仅二合一面）
                    if (!embedded) {
                        Box {
                            TopBarActionButton(
                                onClick = { showAssetMenu = true },
                                imageVector = AppIcons.MoreVert,
                                contentDescription = "导出 / 导入",
                            )
                            RoundDropdownMenu(
                                expanded = showAssetMenu,
                                onDismissRequest = { showAssetMenu = false },
                            ) {
                                RoundDropdownMenuItem(
                                    text = "导出书籍资产",
                                    onClick = {
                                        showAssetMenu = false
                                        exportSel = emptySet()
                                        showExportAssetSheet = true
                                    },
                                )
                                RoundDropdownMenuItem(
                                    text = "导入书籍资产",
                                    onClick = {
                                        showAssetMenu = false
                                        confirmImportAssets = true
                                    },
                                )
                            }
                        }
                    }
                },
                bottomContent = {
                    RoleScriptTabRow(
                        selectedTabIndex = hostTab,
                        onTabSelected = onHostTabSelected,
                        bookTabLabel = bookTabLabel,
                    )
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = adaptiveContentPadding(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 140.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!embedded) {
                    item(key = "cards") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(modifier = Modifier.weight(2f)) {
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 12.dp,
                                    containerColor = LegadoTheme.colorScheme.surfaceContainer,
                                    onClick = { showBookMenu = true },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            AppText(
                                                text = currentBook,
                                                style = LegadoTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            AppText(
                                                text = "当前书籍（点按切换）",
                                                style = LegadoTheme.typography.bodySmall,
                                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换书籍")
                                    }
                                }
                                RoundDropdownMenu(
                                    expanded = showBookMenu,
                                    onDismissRequest = { showBookMenu = false },
                                ) { dismiss ->
                                    bookList.forEach { b ->
                                        RoundDropdownMenuItem(
                                            text = b,
                                            onClick = {
                                                dismiss()
                                                if (b != currentBook) {
                                                    scope.launch {
                                                        val (_, msg) = repo.switchBook(b)
                                                        context.toastOnUi(msg)
                                                        selectedChapter = null
                                                        reload()
                                                    }
                                                }
                                            },
                                            trailingIcon = if (b == currentBook) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            } else null,
                                        )
                                    }
                                    RoundDropdownMenuItem(
                                        text = "删除书籍",
                                        onClick = {
                                            dismiss()
                                            showDeleteBookSheet = true
                                        },
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    cornerRadius = 12.dp,
                                    containerColor = LegadoTheme.colorScheme.surfaceContainer,
                                    onClick = { showChapterMenu = true },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            AppText(
                                                text = selectedChapter?.let { "第${it}章" } ?: "未选择",
                                                style = LegadoTheme.typography.titleSmall,
                                                maxLines = 1,
                                            )
                                            AppText(
                                                text = "章节",
                                                style = LegadoTheme.typography.bodySmall,
                                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "章节列表")
                                    }
                                }
                                RoundDropdownMenu(
                                    expanded = showChapterMenu,
                                    onDismissRequest = { showChapterMenu = false },
                                ) { dismiss ->
                                    chapters.sorted().forEach { ch ->
                                        RoundDropdownMenuItem(
                                            text = "第${ch}章",
                                            onClick = {
                                                dismiss()
                                                selectedChapter = ch
                                                reloadLines()
                                            },
                                            trailingIcon = if (ch == selectedChapter) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            } else null,
                                        )
                                    }
                                    RoundDropdownMenuItem(
                                        text = "管理章节（删除 / 回滚）",
                                        onClick = {
                                            dismiss()
                                            showChapterSheet = true
                                        },
                                    )
                                }
                            }
                        }
                    }

                }
                item(key = "search") {
                    AnimatedVisibility(
                        visible = searchMode,
                        enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
                    ) {
                        SearchBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "搜索本章剧本内容 / 标签",
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }

                if (chapters.isEmpty()) {
                    item(key = "empty") {
                        TinyClickableSettingItem(
                            title = "暂无剧本",
                            description = "朗读分析产出后自动出现（当前书：$currentBook）",
                            onClick = {},
                        )
                    }
                } else if (selectedChapter == null) {
                    item(key = "noselect") {
                        TinyClickableSettingItem(
                            title = "请选择章节",
                            description = "点上方章节卡选择要查看/修改的剧本",
                            onClick = { showChapterSheet = true },
                        )
                    }
                } else {
                    items(shown, key = { "ln_${it.absIndex}" }) { row ->
                        val displayedSpeaker = pending[row.absIndex] ?: row.speaker
                        val selActive = selLines.isNotEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            // 卡片上方独立一行小字：旁白 / 人物名（+情绪异色）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AnimatedVisibility(
                                    visible = selActive,
                                    enter = fadeIn() + expandHorizontally(),
                                    exit = fadeOut() + shrinkHorizontally(),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        AppCheckbox(
                                            checked = row.absIndex in selLines,
                                            onCheckedChange = null,
                                            includeStateSemantics = false,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                }
                                AppText(
                                    text = displayedSpeaker.ifBlank { "旁白" },
                                    style = if (displayedSpeaker.isBlank()) {
                                        LegadoTheme.typography.labelSmall
                                    } else {
                                        LegadoTheme.typography.labelSmallEmphasized
                                    },
                                    color = if (displayedSpeaker.isBlank()) {
                                        LegadoTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        LegadoTheme.colorScheme.primary
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable(
                                        enabled = row.speaker.isNotEmpty(),
                                    ) {
                                        tagSearch = ""
                                        tagPanelFor = listOf(row.absIndex)
                                    },
                                )
                                if (row.emotion.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    AppText(
                                        text = row.emotion,
                                        style = LegadoTheme.typography.labelSmall,
                                        color = LegadoTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 10.dp,
                                containerColor = if (row.absIndex in selLines) {
                                    LegadoTheme.colorScheme.secondaryContainer
                                } else {
                                    LegadoTheme.colorScheme.surfaceContainer
                                },
                                onClick = {
                                    if (row.speaker.isNotEmpty()) {
                                        selLines = if (row.absIndex in selLines) {
                                            selLines - row.absIndex
                                        } else {
                                            selLines + row.absIndex
                                        }
                                    }
                                },
                                onLongClick = null,
                            ) {
                                AppText(
                                    // B10.6：旁白/对话统一从卡片边界起始——显示层裁剪行首空白（全角空格等）
                                    text = row.text.trimStart { it.isWhitespace() || it == '\u3000' },
                                    style = LegadoTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 底部操作条
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AnimatedVisibility(
                    visible = selLines.isNotEmpty() || pending.isNotEmpty(),
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) {
                    SelectionBottomBar(
                        onSelectAll = { selLines = shown.map { it.absIndex }.toSet() },
                        onSelectInvert = {
                            val all = shown.map { it.absIndex }.toSet()
                            selLines = all - selLines
                        },
                        primaryAction = ActionItem("换角色", Icons.Default.Edit) {
                            if (selLines.isNotEmpty()) {
                                tagSearch = ""
                                tagPanelFor = selLines.toList()
                            }
                        },
                        secondaryActions = if (pending.isNotEmpty()) {
                            listOf(
                                ActionItem("放弃修改", Icons.Default.Close) {
                                    pending.clear()
                                    selLines = emptySet()
                                }
                            )
                        } else emptyList(),
                        confirmAction = if (pending.isNotEmpty()) {
                            ActionItem("保存", Icons.Default.Check) { savePending() }
                        } else null,
                    )
                }
            }
            if (selLines.isNotEmpty()) {
                DraggableSelectionHandler(
                    listState = listState,
                    items = shown,
                    selectedIds = selLines.map { "ln_$it" }.toSet(),
                    onSelectionChange = { ids ->
                        selLines = ids.mapNotNull { it.removePrefix("ln_").toIntOrNull() }.toSet()
                    },
                    idProvider = { "ln_${it.absIndex}" },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }
    }

    // ---------------- 章节列表（点选 / 删除） ----------------
    AppModalBottomSheet(
        show = showChapterSheet,
        onDismissRequest = { showChapterSheet = false },
        title = "章节列表",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (chapters.isEmpty()) {
                TinyClickableSettingItem(title = "（暂无已落库章节）", onClick = {})
            }
            chapters.sorted().forEach { ch ->
                TinyClickableSettingItem(
                    title = "第${ch}章",
                    description = if (ch == selectedChapter) "当前显示中" else null,
                    trailingContent = {
                        SmallPlainButton(
                            onClick = { deleteChapterTarget = ch },
                            icon = Icons.Default.Delete,
                            contentDescription = "删除章节",
                        )
                    },
                    onClick = {
                        selectedChapter = ch
                        showChapterSheet = false
                        reloadLines()
                    },
                )
            }
        }
    }

    // ---------------- 删除章节 ----------------
    AppModalBottomSheet(
        show = deleteChapterTarget != null,
        onDismissRequest = { deleteChapterTarget = null },
        title = "删除第${deleteChapterTarget ?: ""}章",
    ) {
        val target = deleteChapterTarget
        if (target != null) {
            val eligible = target >= tailMin()
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TinyClickableSettingItem(
                    title = "仅删剧本+状态",
                    description = "删除该章剧本与缓存；人物与合并日志不动",
                    onClick = {
                        deleteChapterTarget = null
                        scope.launch {
                            repo.deleteChapterScripts(currentBook, setOf(target), false)
                            context.toastOnUi("已删除第${target}章剧本（轻量）")
                            selectedChapter = null
                            reload()
                        }
                    },
                )
                if (eligible) {
                    TinyClickableSettingItem(
                        title = "回滚（恢复本章分析之前）",
                        description = "删除第${target}章起（连续尾章）的剧本/缓存/合并账本，并逆向人物出场与合并",
                        onClick = {
                            val set = chapters.filter { it >= target }.toSet()
                            deleteChapterTarget = null
                            scope.launch {
                                repo.deleteChapterScripts(currentBook, set, true)
                                context.toastOnUi("已回滚：第${target}章起全部撤销")
                                selectedChapter = null
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }

    // ---------------- 删除书籍（全套资产清零） ----------------
    AppModalBottomSheet(
        show = showDeleteBookSheet,
        onDismissRequest = { showDeleteBookSheet = false },
        title = "删除书籍",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            val deletable = bookList.filter { it != ReadAloudDataRepository.DEFAULT_BOOK }
            if (deletable.isEmpty()) {
                TinyClickableSettingItem(title = "（暂无可删除的书籍）", onClick = {})
            }
            deletable.forEach { b ->
                TinyClickableSettingItem(
                    title = b,
                    description = if (b == currentBook) "点按删除（当前书将回退「默认」）" else "点按删除该书全部数据",
                    onClick = { pendingDeleteBook = b },
                )
            }
        }
    }
    AppAlertDialog(
        show = pendingDeleteBook != null,
        onDismissRequest = { pendingDeleteBook = null },
        title = "删除书籍",
        text = "将删除「${pendingDeleteBook ?: ""}」的剧本、音频缓存、分析/绑定数据与书籍索引（不动书架）；此操作不可恢复。",
        confirmText = "删除",
        onConfirm = {
            val b = pendingDeleteBook
            pendingDeleteBook = null
            showDeleteBookSheet = false
            if (b != null) scope.launch {
                val (ok, msg) = repo.deleteBookAssets(b)
                context.toastOnUi(msg)
                if (ok) {
                    selectedChapter = null
                    reload()
                }
            }
        },
        dismissText = "取消",
        onDismiss = { pendingDeleteBook = null },
    )

    // ---------------- 导出书籍资产（zip，不含音频） ----------------
    AppModalBottomSheet(
        show = showExportAssetSheet,
        onDismissRequest = { showExportAssetSheet = false },
        title = "导出书籍资产",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            val exportable = bookList.filter { it != ReadAloudDataRepository.DEFAULT_BOOK }
            if (exportable.isEmpty()) {
                TinyClickableSettingItem(title = "（暂无可导出的书籍）", onClick = {})
            } else {
                TinyClickableSettingItem(
                    title = "导出全部书籍（${exportable.size} 本）",
                    description = "含角色记录 / 剧本 / 章节缓存 / 合并账本（不含音频）",
                    onClick = {
                        showExportAssetSheet = false
                        pendingExportBooks = exportable
                        exportZipLauncher.launch("legado-book-assets.zip")
                    },
                )
            }
            exportable.forEach { b ->
                TinyClickableSettingItem(
                    title = b,
                    trailingContent = {
                        AppCheckbox(
                            checked = b in exportSel,
                            onCheckedChange = null,
                            includeStateSemantics = false,
                        )
                    },
                    onClick = {
                        exportSel = if (b in exportSel) exportSel - b else exportSel + b
                    },
                )
            }
            if (exportSel.isNotEmpty()) {
                TinyClickableSettingItem(
                    title = "导出所选（${exportSel.size} 本）",
                    onClick = {
                        showExportAssetSheet = false
                        pendingExportBooks = exportSel.toList()
                        exportZipLauncher.launch("legado-book-assets.zip")
                    },
                )
            }
        }
    }

    // ---------------- 导入确认 ----------------
    AppAlertDialog(
        show = confirmImportAssets,
        onDismissRequest = { confirmImportAssets = false },
        title = "导入书籍资产",
        text = "将把压缩包内的书籍数据写入本地（覆盖同名文件），确认？",
        confirmText = "选择文件",
        onConfirm = {
            confirmImportAssets = false
            importZipLauncher.launch(arrayOf("application/zip", "*/*"))
        },
        dismissText = "取消",
        onDismiss = { confirmImportAssets = false },
    )

    // ---------------- 换角色面板 ----------------
    AppModalBottomSheet(
        show = tagPanelFor != null,
        onDismissRequest = { tagPanelFor = null },
        title = "换角色 · ${tagPanelFor?.size ?: 0} 行",
        endAction = {
            MediumTonalButton(
                onClick = {
                    ncName = ""
                    ncRole = "核心"
                    ncGender = "男"
                    ncVoice = ""
                    newCharDialog = true
                },
                icon = Icons.Default.Add,
                contentDescription = "新增人物",
            )
        },
    ) {
        val targets = tagPanelFor
        val ch = selectedChapter
        if (targets != null && ch != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SearchBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    query = tagSearch,
                    onQueryChange = { tagSearch = it },
                    placeholder = "搜索人物（本章 / 历史）",
                    shape = RoundedCornerShape(12.dp),
                    autoFocus = false,
                )
                TinyClickableSettingItem(
                    title = "旁白",
                    description = "改为旁白发声（只改标记，不动文本）",
                    onClick = { applySpeakerTo(targets, "旁白") },
                )
                val thisChapter = records.filter {
                    ch in it.appearanceChapters || it.lastAppearanceChapter == ch
                }.filter { tagSearch.isBlank() || it.name.contains(tagSearch, ignoreCase = true) }
                val history = records.filter {
                    it !in thisChapter && it.roletype in listOf("核心", "特殊")
                }.filter { tagSearch.isBlank() || it.name.contains(tagSearch, ignoreCase = true) }
                if (thisChapter.isNotEmpty()) {
                    AppText(
                        text = "— 本章人物 —",
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    thisChapter.forEach { r ->
                        TinyClickableSettingItem(
                            title = r.name,
                            description = "${r.roletype} · ${r.gender} · ${r.age}",
                            onClick = { applySpeakerTo(targets, r.name) },
                        )
                    }
                }
                if (history.isNotEmpty()) {
                    AppText(
                        text = "— 历史（核心/特殊） —",
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    history.forEach { r ->
                        TinyClickableSettingItem(
                            title = r.name,
                            description = "${r.roletype} · ${r.gender} · ${r.age}",
                            onClick = { applySpeakerTo(targets, r.name) },
                        )
                    }
                }
            }
        }
    }

    VoiceTagPickerSheet(
        show = bmPicker != null,
        request = bmPicker,
        repo = repo,
        onDismiss = { bmPicker = null },
    )

    // ---------------- 新增人物 ----------------
    AppModalBottomSheet(
        show = newCharDialog,
        onDismissRequest = { newCharDialog = false },
        title = "新增人物",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                AppTextField(
                    value = ncName,
                    onValueChange = { ncName = it },
                    label = "主名",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            SmallChoiceRow(
                label = "类型",
                options = listOf("核心", "特殊", "路人"),
                selected = ncRole,
                onSelect = { ncRole = it },
            )
            SmallChoiceRow(
                label = "性别",
                options = listOf("男", "女"),
                selected = ncGender,
                onSelect = { ncGender = it },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = "声线",
                    style = LegadoTheme.typography.labelSmall,
                    modifier = Modifier.width(52.dp),
                )
                BmVoiceChip(ncVoice) {
                    val age = when {
                        ncRole == "特殊" -> "系统"
                        ncGender == "女" -> "女青年"
                        else -> "男青年"
                    }
                    bmPicker = VoiceTagPickRequest(ncRole, ncGender, age, "选择声线") {
                        ncVoice = it
                    }
                }
            }
            TinyClickableSettingItem(
                title = "创建并标记",
                onClick = {
                    val ch = selectedChapter
                    val nm = ncName.trim()
                    if (ch == null || nm.isEmpty()) {
                        context.toastOnUi("主名不能为空")
                        return@TinyClickableSettingItem
                    }
                    val finalName = if (ncRole == "路人" && !nm.contains("【第")) {
                        "$nm【第${ch}章】"
                    } else {
                        nm
                    }
                    val targets = (tagPanelFor ?: emptyList()).toList()
                    newCharDialog = false
                    scope.launch {
                        val rec = CharacterRecord(
                            name = finalName,
                            roletype = ncRole,
                            gender = ncGender,
                            age = when {
                                ncRole == "特殊" -> "系统"
                                ncGender == "女" -> "女青年"
                                else -> "男青年"
                            },
                            voice = ncVoice,
                            appearanceChapters = mutableListOf(ch),
                            lastAppearanceChapter = ch,
                            appearanceCount = 1,
                        )
                        repo.saveRecords(currentBook, records + rec)
                        context.toastOnUi("已新增人物：$finalName")
                        reloadRecordsOnly()
                        applySpeakerTo(targets, finalName)
                    }
                },
            )
        }
    }
}

// ---------------- 组件 ----------------

@Composable
private fun SmallChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = label,
            style = LegadoTheme.typography.labelSmall,
            modifier = Modifier.width(52.dp),
        )
        options.forEach { opt ->
            SmallChoiceTag(
                text = opt,
                selected = opt == selected,
                onClick = { onSelect(opt) },
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
private fun BmVoiceChip(voice: String, onClick: () -> Unit) {
    val active = voice.isNotBlank()
    val bg = if (active) {
        LegadoTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        LegadoTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) {
        LegadoTheme.colorScheme.primary
    } else {
        LegadoTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        AppText(
            text = voice.ifBlank { "未设置" },
            style = LegadoTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun SmallChoiceTag(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        LegadoTheme.colorScheme.primary
    } else {
        LegadoTheme.colorScheme.surfaceVariant
    }
    val fg = if (selected) {
        LegadoTheme.colorScheme.onPrimary
    } else {
        LegadoTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        AppText(
            text = text,
            style = LegadoTheme.typography.labelSmall,
            color = fg,
        )
    }
}