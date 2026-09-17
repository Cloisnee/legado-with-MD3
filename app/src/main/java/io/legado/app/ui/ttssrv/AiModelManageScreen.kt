package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiModelsConfig
import io.legado.app.data.repository.AiProvider
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * AI 服务 · 模型管理（复刻「引擎与音色」交互）：
 *  - 四标签页（可左右滑动）：模型库 / 文本分析 / 图像生成 / 背景音乐与音效
 *  - 模型库：厂商卡片（x/y 已选中标签）+ 展开搜索 + 模型行（测试三态/延迟/滑动开关）+ 多级多选 + ⋮
 *  - 文本分析：四卡（话语分析/话语归属与人物/历史人物比对/情绪分析），点卡=连续添加模型，展开=队列，点模型=调次数
 */
@Composable
fun AiModelManageRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    AiModelManageScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

private data class LibSelection(
    val vendors: Set<String> = emptySet(),
    val models: Set<String> = emptySet(),
)

private sealed interface TestUi {
    data object Untested : TestUi
    data object Testing : TestUi
    data class Pass(val latencyMs: Long) : TestUi
    data class Fail(val message: String) : TestUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelManageScreen(app: Application, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(app) { AiModelRepository(app) }
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<AiModelsConfig?>(null) }
    var tab by remember { mutableStateOf(0) }

    // 模型库
    var libSel by remember { mutableStateOf(LibSelection()) }
    val expandedVendors = remember { mutableStateMapOf<String, Boolean>() }
    val vendorQueries = remember { mutableStateMapOf<String, String>() }
    val testStates = remember { mutableStateMapOf<String, TestUi>() }
    var menuOpen by remember { mutableStateOf(false) }

    // 厂商编辑
    var vendorSheet by remember { mutableStateOf(false) }
    var veId by remember { mutableStateOf("") }
    var veName by remember { mutableStateOf("") }
    var veBase by remember { mutableStateOf("") }
    var veKey by remember { mutableStateOf("") }
    var veProtocol by remember { mutableStateOf("openai") }

    // 文本分析
    var stageSheetExpanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addStageKey by remember { mutableStateOf<String?>(null) }
    var quotaTarget by remember { mutableStateOf<AiModelEntry?>(null) }
    var qAttempts by remember { mutableStateOf("2") }
    var qValidate by remember { mutableStateOf("2") }
    var qTimeoutSec by remember { mutableStateOf("120") }
    var queueSelStage by remember { mutableStateOf<String?>(null) }
    var selQueue by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 删除确认
    var confirmDelVendor by remember { mutableStateOf<AiProvider?>(null) }
    var confirmDelModel by remember { mutableStateOf<AiModelEntry?>(null) }
    var confirmBulkDel by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { cfg = repo.load() }
    }

    LaunchedEffect(Unit) { reload() }

    val libSelActive = libSel.vendors.isNotEmpty() || libSel.models.isNotEmpty()
    val queueSelActive = queueSelStage != null && selQueue.isNotEmpty()
    val selActive = libSelActive || queueSelActive
    val selCount = libSel.vendors.size + libSel.models.size + selQueue.size

    fun clearSel() {
        libSel = LibSelection()
        queueSelStage = null
        selQueue = emptySet()
    }

    fun switchTab(t: Int) {
        tab = t
        clearSel()
        menuOpen = false
    }

    // ---------------- 选择操作 ----------------

    fun stageIds(c: AiModelsConfig, key: String): List<String> = when (key) {
        "stage1" -> c.stages.stage1
        "stage2" -> c.stages.stage2
        "stage4" -> c.stages.stage4
        "emotion" -> c.stages.emotion
        else -> emptyList()
    }

    fun selectAllCurrent() {
        val c = cfg ?: return
        when (tab) {
            0 -> libSel = LibSelection(
                vendors = c.providers.map { it.id }.toSet(),
                models = c.models.map { it.id }.toSet(),
            )
            1 -> {
                val key = queueSelStage ?: return
                selQueue = stageIds(c, key).toSet()
            }
        }
    }

    fun invertCurrent() {
        val c = cfg ?: return
        when (tab) {
            0 -> libSel = LibSelection(
                vendors = c.providers.map { it.id }.toSet() - libSel.vendors,
                models = c.models.map { it.id }.toSet() - libSel.models,
            )
            1 -> {
                val key = queueSelStage ?: return
                selQueue = stageIds(c, key).toSet() - selQueue
            }
        }
    }

    fun deleteSelected() {
        val c = cfg ?: return
        when (tab) {
            0 -> confirmBulkDel = true
            1 -> {
                val key = queueSelStage ?: return
                val newQ = stageIds(c, key).filterNot { it in selQueue }
                scope.launch {
                    repo.updateStage(key, newQ)
                    selQueue = emptySet()
                    queueSelStage = null
                    reload()
                }
            }
        }
    }

    fun setEnabledForSelected(enabled: Boolean) {
        val c = cfg ?: return
        val target = selModels.toMutableSet()
        c.models.filter { it.providerId in libSel.vendors }.forEach { target += it.id }
        if (target.isEmpty()) return
        scope.launch {
            repo.setModelsEnabled(target, enabled)
            reload()
        }
    }

    fun moveSelectedTopBottom(toTop: Boolean) {
        val c = cfg ?: return
        scope.launch {
            if (tab == 0) {
                val vendors = c.providers.filter { it.id in libSel.vendors }
                (if (toTop) vendors.reversed() else vendors).forEach {
                    repo.moveProvider(it.id, toTop)
                }
                val models = c.models.filter { it.id in libSel.models }
                (if (toTop) models.reversed() else models).forEach {
                    repo.moveModel(it.id, toTop)
                }
                reload()
            } else {
                val key = queueSelStage ?: return@launch
                val queue = stageIds(c, key)
                val sel = queue.filter { it in selQueue }
                val rest = queue.filterNot { it in selQueue }
                repo.updateStage(key, if (toTop) sel + rest else rest + sel)
                reload()
            }
        }
    }

    fun openVendorEdit(p: AiProvider?) {
        veId = p?.id.orEmpty()
        veName = p?.name.orEmpty()
        veBase = p?.baseUrl.orEmpty()
        veKey = p?.apiKey.orEmpty()
        veProtocol = p?.protocol ?: "openai"
        vendorSheet = true
    }

    fun saveVendor() {
        if (veName.isBlank() || veBase.isBlank()) {
            context.toastOnUi("名称 / BaseUrl 不能为空")
            return
        }
        val isNew = veId.isBlank()
        val draft = AiProvider(
            id = veId,
            name = veName.trim(),
            baseUrl = veBase.trim(),
            apiKey = veKey.trim(),
            protocol = veProtocol,
        )
        vendorSheet = false
        scope.launch {
            val id = repo.upsertProvider(draft)
            if (id == null) {
                context.toastOnUi("保存失败")
                return@launch
            }
            reload()
            if (isNew) {
                context.toastOnUi("已保存，正在拉取模型…")
                repo.fetchProviderModels(draft.copy(id = id))
                    .onSuccess { list ->
                        val n = repo.addModelsFromProvider(id, list)
                        context.toastOnUi(
                            if (n > 0) "拉取到 ${list.size} 个模型（新增 $n）"
                            else "拉取成功：无新增（共 ${list.size} 个）"
                        )
                        reload()
                    }
                    .onFailure { context.toastOnUi("拉取失败：${it.localizedMessage}") }
            } else {
                context.toastOnUi("已保存")
            }
        }
    }

    // ---------------- 界面 ----------------

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var swipeAccum by remember { mutableStateOf(0f) }
    val swipeState = rememberDraggableState { delta -> swipeAccum += delta }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = if (selActive) "已选 $selCount" else "模型管理",
                subtitle = if (selActive) null else "服务商 / 模型 / 文本分析阶段配",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    if (selActive) {
                        TopBarActionButton(
                            onClick = { clearSel() },
                            imageVector = Icons.Default.Close,
                            contentDescription = "取消选择",
                        )
                        TopBarActionButton(
                            onClick = { selectAllCurrent() },
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "全选",
                        )
                        TopBarActionButton(
                            onClick = { invertCurrent() },
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "反选",
                        )
                        TopBarActionButton(
                            onClick = { deleteSelected() },
                            imageVector = Icons.Default.Delete,
                            contentDescription = if (tab == 0) "删除" else "移除",
                        )
                        Box {
                            TopBarActionButton(
                                onClick = { menuOpen = true },
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多",
                            )
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                if (tab == 0) {
                                    DropdownMenuItem(
                                        text = { AppText("启用所有") },
                                        onClick = {
                                            menuOpen = false
                                            setEnabledForSelected(true)
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { AppText("关闭所有") },
                                        onClick = {
                                            menuOpen = false
                                            setEnabledForSelected(false)
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { AppText("置顶") },
                                    onClick = {
                                        menuOpen = false
                                        moveSelectedTopBottom(true)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { AppText("置底") },
                                    onClick = {
                                        menuOpen = false
                                        moveSelectedTopBottom(false)
                                    },
                                )
                            }
                        }
                    }
                },
                bottomContent = { _ ->
                    AppTabRow(
                        tabTitles = listOf("模型库", "文本分析", "图像生成", "背景音乐与音效"),
                        selectedTabIndex = tab,
                        onTabSelected = { t -> switchTab(t) },
                        isScrollable = true,
                    )
                },
            )
        },
        floatingActionButton = {
            if (tab == 0 && !selActive) {
                AppFloatingActionButton(
                    onClick = { openVendorEdit(null) },
                    icon = Icons.Default.Add,
                    tooltipText = "添加服务商",
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = swipeState,
                    onDragStarted = { swipeAccum = 0f },
                    onDragStopped = {
                        when {
                            swipeAccum <= -swipeThresholdPx && tab < 3 -> switchTab(tab + 1)
                            swipeAccum >= swipeThresholdPx && tab > 0 -> switchTab(tab - 1)
                        }
                        swipeAccum = 0f
                    },
                ),
        ) {
            when (tab) {
                0 -> ModelLibraryPage(
                    config = cfg,
                    selActive = libSelActive,
                    libSel = libSel,
                    expandedVendors = expandedVendors,
                    vendorQueries = vendorQueries,
                    testStates = testStates,
                    contentPaddingTop = padding.calculateTopPadding(),
                    contentPaddingBottom = padding.calculateBottomPadding(),
                    onToggleVendorSel = { id ->
                        libSel = if (id in libSel.vendors) {
                            libSel.copy(vendors = libSel.vendors - id)
                        } else {
                            libSel.copy(vendors = libSel.vendors + id)
                        }
                    },
                    onToggleModelSel = { id ->
                        libSel = if (id in libSel.models) {
                            libSel.copy(models = libSel.models - id)
                        } else {
                            libSel.copy(models = libSel.models + id)
                        }
                    },
                    onVendorLongClick = { p ->
                        if (!libSelActive) libSel = libSel.copy(vendors = setOf(p.id))
                    },
                    onModelLongClick = { m ->
                        if (!libSelActive) libSel = libSel.copy(models = setOf(m.id))
                    },
                    onToggleExpand = { id ->
                        val now = expandedVendors[id] ?: false
                        expandedVendors[id] = !now
                        if (now) vendorQueries[id] = ""
                    },
                    onQueryChange = { id, q -> vendorQueries[id] = q },
                    onToggleModelEnabled = { m, enabled ->
                        scope.launch {
                            repo.upsertModel(m.copy(enabled = enabled))
                            reload()
                        }
                    },
                    onTestModel = { m, p ->
                        testStates[m.id] = TestUi.Testing
                        scope.launch {
                            context.toastOnUi("测试中：${m.name}")
                            val r = repo.testModel(m, p)
                            testStates[m.id] = if (r.ok) {
                                TestUi.Pass(r.latencyMs)
                            } else {
                                TestUi.Fail(r.message)
                            }
                            context.toastOnUi(
                                if (r.ok) "✅ ${m.name} 通过 · ${r.latencyMs}ms"
                                else "❌ ${m.name} 异常：${r.message}"
                            )
                        }
                    },
                    onEditVendor = { p -> openVendorEdit(p) },
                    onDeleteVendor = { p -> confirmDelVendor = p },
                    onRefetchVendor = { p ->
                        scope.launch {
                            context.toastOnUi("正在重新拉取…")
                            repo.fetchProviderModels(p)
                                .onSuccess { list ->
                                    val n = repo.addModelsFromProvider(p.id, list)
                                    context.toastOnUi("拉取完成，新增 $n 个")
                                    reload()
                                }
                                .onFailure { context.toastOnUi("拉取失败：${it.localizedMessage}") }
                        }
                    },
                )

                1 -> TextAnalysisPage(
                    config = cfg,
                    expandedStages = stageSheetExpanded,
                    selStage = queueSelStage,
                    selQueue = selQueue,
                    contentPaddingTop = padding.calculateTopPadding(),
                    contentPaddingBottom = padding.calculateBottomPadding(),
                    onToggleExpand = { key ->
                        stageSheetExpanded = if (key in stageSheetExpanded) {
                            stageSheetExpanded - key
                        } else {
                            stageSheetExpanded + key
                        }
                    },
                    onOpenAddSheet = { key -> addStageKey = key },
                    onToggleQueueSel = { key, id ->
                        if (queueSelStage == key) {
                            selQueue = if (id in selQueue) selQueue - id else selQueue + id
                        } else {
                            queueSelStage = key
                            selQueue = setOf(id)
                        }
                    },
                    onQuotaEdit = { m ->
                        quotaTarget = m
                        qAttempts = m.requestAttempts.toString()
                        qValidate = m.validateRetries.toString()
                        qTimeoutSec = (m.timeoutMs / 1000L).toString()
                    },
                )

                else -> PlaceholderPage(tab = tab)
            }
        }
    }

    // ---------------- 厂商添加/编辑 ----------------
    AppModalBottomSheet(
        show = vendorSheet,
        onDismissRequest = { vendorSheet = false },
        title = if (veId.isBlank()) "添加服务商" else "编辑服务商",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SheetField("名称", veName) { veName = it }
            SheetField("BaseUrl（如 https://api.xxx.com/v1）", veBase) { veBase = it }
            SheetField("API Key", veKey) { veKey = it }
            TinyDropdownSettingItem(
                title = "协议",
                selectedValue = veProtocol,
                displayEntries = arrayOf("OpenAI 兼容", "Google", "Claude"),
                entryValues = arrayOf("openai", "google", "claude"),
                description = "决定 拉取模型 / 测试 的请求方式",
                onValueChange = { veProtocol = it },
            )
            Spacer(modifier = Modifier.height(8.dp))
            TinyClickableSettingItem(
                title = if (veId.isBlank()) "保存并拉取模型" else "保存",
                onClick = { saveVendor() },
            )
        }
    }

    // ---------------- 文本分析 · 添加模型 ----------------
    val addKey = addStageKey
    AppModalBottomSheet(
        show = addKey != null,
        onDismissRequest = { addStageKey = null },
        title = "添加模型 · ${stageTitle(addKey)}",
    ) {
        val c = cfg
        if (c != null && addKey != null) {
            val queue = stageIds(c, addKey)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                AppText(
                    text = "点击顺序 = 该阶段失败后的轮换顺序（可连续添加）",
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                var any = false
                c.providers.forEach { p ->
                    val models = c.models.filter { it.providerId == p.id && it.enabled }
                    if (models.isNotEmpty()) {
                        any = true
                        AppText(
                            text = "— ${p.name} —",
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        models.forEach { m ->
                            val added = m.id in queue
                            TinyClickableSettingItem(
                                title = m.name + if (added) "  ✓" else "",
                                description = m.modelId,
                                onClick = {
                                    if (!added) {
                                        scope.launch {
                                            repo.updateStage(addKey, queue + m.id)
                                            reload()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                if (!any) {
                    TinyClickableSettingItem(
                        title = "没有已选中的模型（先去模型库打开开关）",
                        onClick = {},
                    )
                }
            }
        }
    }

    // ---------------- 文本分析 · 次数设置 ----------------
    AppModalBottomSheet(
        show = quotaTarget != null,
        onDismissRequest = { quotaTarget = null },
        title = "模型次数：${quotaTarget?.name.orEmpty()}",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SheetField("响应尝试次数（1=只试一次）", qAttempts) { qAttempts = it }
            SheetField("校验重试次数（0=不重试）", qValidate) { qValidate = it }
            SheetField("超时（秒）", qTimeoutSec) { qTimeoutSec = it }
            TinyClickableSettingItem(
                title = "保存",
                onClick = {
                    val m = quotaTarget ?: return@TinyClickableSettingItem
                    quotaTarget = null
                    scope.launch {
                        repo.upsertModel(
                            m.copy(
                                requestAttempts = (qAttempts.toIntOrNull() ?: 2).coerceIn(1, 5),
                                validateRetries = (qValidate.toIntOrNull() ?: 2).coerceIn(0, 5),
                                timeoutMs = ((qTimeoutSec.toLongOrNull() ?: 120L)
                                    .coerceIn(5L, 600L)) * 1000L,
                            )
                        )
                        context.toastOnUi("已保存")
                        reload()
                    }
                },
            )
        }
    }

    // ---------------- 删除确认 ----------------
    AppAlertDialog(
        show = confirmDelVendor != null,
        onDismissRequest = { confirmDelVendor = null },
        title = "删除服务商",
        text = "确认删除「${confirmDelVendor?.name.orEmpty()}」及其全部模型？",
        confirmText = "删除",
        onConfirm = {
            val p = confirmDelVendor ?: return@AppAlertDialog
            confirmDelVendor = null
            scope.launch {
                repo.deleteProvider(p.id)
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { confirmDelVendor = null },
    )

    AppAlertDialog(
        show = confirmDelModel != null,
        onDismissRequest = { confirmDelModel = null },
        title = "删除模型",
        text = "确认删除「${confirmDelModel?.name.orEmpty()}」？（同时从各阶段队列移除）",
        confirmText = "删除",
        onConfirm = {
            val m = confirmDelModel ?: return@AppAlertDialog
            confirmDelModel = null
            scope.launch {
                repo.deleteModel(m.id)
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { confirmDelModel = null },
    )

    AppAlertDialog(
        show = confirmBulkDel,
        onDismissRequest = { confirmBulkDel = false },
        title = "删除所选",
        text = "将删除所选服务商（含其模型）与所选模型，确认？",
        confirmText = "删除",
        onConfirm = {
            confirmBulkDel = false
            scope.launch {
                libSel.vendors.forEach { repo.deleteProvider(it) }
                libSel.models.forEach { repo.deleteModel(it) }
                libSel = LibSelection()
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { confirmBulkDel = false },
    )
}

// ============================================================
// 模型库页
// ============================================================

@Composable
private fun ModelLibraryPage(
    config: AiModelsConfig?,
    selActive: Boolean,
    libSel: LibSelection,
    expandedVendors: MutableMap<String, Boolean>,
    vendorQueries: MutableMap<String, String>,
    testStates: MutableMap<String, TestUi>,
    contentPaddingTop: androidx.compose.ui.unit.Dp,
    contentPaddingBottom: androidx.compose.ui.unit.Dp,
    onToggleVendorSel: (String) -> Unit,
    onToggleModelSel: (String) -> Unit,
    onVendorLongClick: (AiProvider) -> Unit,
    onModelLongClick: (AiModelEntry) -> Unit,
    onToggleExpand: (String) -> Unit,
    onQueryChange: (String, String) -> Unit,
    onToggleModelEnabled: (AiModelEntry, Boolean) -> Unit,
    onTestModel: (AiModelEntry, AiProvider) -> Unit,
    onEditVendor: (AiProvider) -> Unit,
    onDeleteVendor: (AiProvider) -> Unit,
    onRefetchVendor: (AiProvider) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = adaptiveContentPadding(
            top = contentPaddingTop + 8.dp,
            bottom = contentPaddingBottom + 120.dp,
        ),
    ) {
        if (config == null) {
            item { TinyClickableSettingItem(title = "加载中…", onClick = {}) }
            return@LazyColumn
        }
        if (config.providers.isEmpty()) {
            item {
                TinyClickableSettingItem(
                    title = "还没有服务商",
                    description = "点右下角 ＋ 添加（保存后自动拉取模型列表）",
                    onClick = {},
                )
            }
        }
        config.providers.forEach { p ->
            item(key = "vendor_${p.id}") {
                val models = config.models.filter { it.providerId == p.id }
                val enabledCount = models.count { it.enabled }
                val expanded = expandedVendors[p.id] == true
                LevelCardRow(
                    title = p.name,
                    subtitle = "共 ${models.size} 个模型 · ${p.protocol}",
                    arrowExpanded = expanded,
                    selActive = false,
                    selected = false,
                    trailing = { CountTag(enabledCount, models.size) },
                    onClick = { if (!selActive) onToggleExpand(p.id) },
                    onLongClick = { onVendorLongClick(p) },
                )
            }
            if (expandedVendors[p.id] == true) {
                item(key = "vendor_search_${p.id}") {
                    SearchBar(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        query = vendorQueries[p.id].orEmpty(),
                        onQueryChange = { q -> onQueryChange(p.id, q) },
                        placeholder = "搜索 ${p.name} 的模型",
                    )
                }
                val all = config.models.filter { it.providerId == p.id }
                val q = vendorQueries[p.id].orEmpty()
                val filtered = if (q.isBlank()) {
                    all
                } else {
                    all.filter {
                        it.name.contains(q, ignoreCase = true) ||
                            it.modelId.contains(q, ignoreCase = true)
                    }
                }
                items(filtered, key = { "model_${it.id}" }) { m ->
                    val testState = testStates[m.id] ?: TestUi.Untested
                    ModelLine(
                        model = m,
                        testState = testState,
                        selActive = selActive,
                        selected = m.id in libSel.models,
                        onClick = {
                            when {
                                selActive -> onToggleModelSel(m.id)
                                else -> onTestModel(m, p)
                            }
                        },
                        onLongClick = { onModelLongClick(m) },
                        onToggleEnabled = { v -> onToggleModelEnabled(m, v) },
                    )
                }
                item(key = "vendor_actions_${p.id}") {
                    Column(modifier = Modifier.padding(top = 2.dp)) {
                        TinyClickableSettingItem(
                            title = "重新拉取模型",
                            onClick = { onRefetchVendor(p) },
                        )
                        TinyClickableSettingItem(
                            title = "编辑服务商",
                            onClick = { onEditVendor(p) },
                        )
                        TinyClickableSettingItem(
                            title = "删除服务商（含其模型）",
                            onClick = { onDeleteVendor(p) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountTag(enabled: Int, total: Int) {
    val active = enabled > 0
    val bg = if (active) {
        LegadoTheme.colorScheme.primary.copy(alpha = 0.16f)
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
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        AppText(
            text = "$enabled/$total 已选中",
            style = LegadoTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun ModelLine(
    model: AiModelEntry,
    testState: TestUi,
    selActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        cornerRadius = 10.dp,
        containerColor = if (selected) {
            LegadoTheme.colorScheme.secondaryContainer
        } else {
            LegadoTheme.colorScheme.surfaceContainer
        },
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selActive) {
                AppCheckbox(
                    checked = selected,
                    onCheckedChange = null,
                    includeStateSemantics = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = model.name + when (val s = testState) {
                        is TestUi.Pass -> " · ${s.latencyMs}ms"
                        else -> ""
                    },
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AppText(
                    text = model.modelId,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!selActive) {
                TestChip(testState)
                Spacer(modifier = Modifier.width(6.dp))
                Switch(
                    checked = model.enabled,
                    onCheckedChange = { v -> onToggleEnabled(v) },
                )
            }
        }
    }
}

@Composable
private fun TestChip(state: TestUi) {
    val (icon, tint, label) = when (state) {
        is TestUi.Untested -> Triple(
            Icons.Default.RadioButtonUnchecked,
            LegadoTheme.colorScheme.onSurfaceVariant,
            "测试",
        )

        is TestUi.Testing -> Triple(
            Icons.Default.RadioButtonUnchecked,
            LegadoTheme.colorScheme.primary,
            "测试中",
        )

        is TestUi.Pass -> Triple(
            Icons.Default.CheckCircle,
            Color(0xFF2E7D32),
            "${state.latencyMs}ms",
        )

        is TestUi.Fail -> Triple(
            Icons.Default.ErrorOutline,
            Color(0xFFC62828),
            "异常",
        )
    }
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        AppText(
            text = label,
            style = LegadoTheme.typography.labelSmall,
            color = tint,
        )
    }
}

// ============================================================
// 文本分析页
// ============================================================

private val STAGE_CARDS = listOf(
    "stage1" to "话语分析",
    "stage2" to "话语归属与人物",
    "stage4" to "历史人物比对",
    "emotion" to "情绪分析",
)

private fun stageTitle(key: String?): String =
    STAGE_CARDS.firstOrNull { it.first == key }?.second ?: "阶段"

@Composable
private fun TextAnalysisPage(
    config: AiModelsConfig?,
    expandedStages: Set<String>,
    selStage: String?,
    selQueue: Set<String>,
    contentPaddingTop: androidx.compose.ui.unit.Dp,
    contentPaddingBottom: androidx.compose.ui.unit.Dp,
    onToggleExpand: (String) -> Unit,
    onOpenAddSheet: (String) -> Unit,
    onToggleQueueSel: (String, String) -> Unit,
    onQuotaEdit: (AiModelEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = adaptiveContentPadding(
            top = contentPaddingTop + 8.dp,
            bottom = contentPaddingBottom + 120.dp,
        ),
    ) {
        if (config == null) {
            item { TinyClickableSettingItem(title = "加载中…", onClick = {}) }
            return@LazyColumn
        }
        val byId = config.models.associateBy { it.id }
        STAGE_CARDS.forEach { (key, title) ->
            val queue = when (key) {
                "stage1" -> config.stages.stage1
                "stage2" -> config.stages.stage2
                "stage4" -> config.stages.stage4
                else -> config.stages.emotion
            }
            item(key = "stage_$key") {
                LevelCardRow(
                    title = title,
                    subtitle = if (queue.isEmpty()) {
                        "未配置模型（点卡片添加；失败时走兜底策略）"
                    } else {
                        queue.map { byId[it]?.name ?: it }.joinToString(" → ")
                    },
                    arrowExpanded = key in expandedStages,
                    selActive = false,
                    selected = false,
                    trailing = {
                        if (queue.isNotEmpty()) {
                            CountTag(queue.size, queue.size)
                        }
                    },
                    onClick = { onOpenAddSheet(key) },
                    onLongClick = null,
                    onArrowClick = { onToggleExpand(key) },
                )
            }
            if (key in expandedStages) {
                items(queue, key = { "q_${key}_$it" }) { modelId ->
                    val m = byId[modelId]
                    val selActiveHere = selStage == key
                    QueueLine(
                        index = queue.indexOf(modelId) + 1,
                        model = m,
                        modelId = modelId,
                        selActive = selActiveHere,
                        selected = selActiveHere && modelId in selQueue,
                        onClick = {
                            if (selActiveHere) {
                                onToggleQueueSel(key, modelId)
                            } else if (m != null) {
                                onQuotaEdit(m)
                            }
                        },
                        onLongClick = { onToggleQueueSel(key, modelId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueLine(
    index: Int,
    model: AiModelEntry?,
    modelId: String,
    selActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        cornerRadius = 10.dp,
        containerColor = if (selected) {
            LegadoTheme.colorScheme.secondaryContainer
        } else {
            LegadoTheme.colorScheme.surfaceContainer
        },
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selActive) {
                AppCheckbox(
                    checked = selected,
                    onCheckedChange = null,
                    includeStateSemantics = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = "$index. ${model?.name ?: modelId}",
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                model?.let {
                    AppText(
                        text = "${it.modelId} · 尝试${it.requestAttempts}/校验${it.validateRetries} · ${it.timeoutMs / 1000}s",
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderPage(tab: Int) {
    val name = if (tab == 2) "图像生成" else "背景音乐与音效"
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AppText(
            text = "$name · 敬请期待\n后续版本开放 🐋",
            style = LegadoTheme.typography.bodyMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// 通用卡片行（箭头/勾选/长按，复刻配置列表）
// ============================================================

@Composable
private fun LevelCardRow(
    title: String,
    subtitle: String? = null,
    arrowExpanded: Boolean? = null,
    selActive: Boolean,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onArrowClick: (() -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (arrowExpanded == true) 0f else -90f,
        label = "modelLevelArrow",
    )
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        cornerRadius = 12.dp,
        containerColor = if (selected) {
            LegadoTheme.colorScheme.secondaryContainer
        } else {
            LegadoTheme.colorScheme.surfaceContainer
        },
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selActive) {
                AppCheckbox(
                    checked = selected,
                    onCheckedChange = null,
                    includeStateSemantics = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = title,
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    AppText(
                        text = it,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            if (arrowExpanded != null) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (arrowExpanded) "收起" else "展开",
                    modifier = Modifier
                        .rotate(rotation)
                        .then(
                            if (onArrowClick != null) {
                                Modifier.clickable(onClick = onArrowClick)
                            } else {
                                Modifier
                            }
                        )
                        .padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetField(label: String, value: String, onChange: (String) -> Unit) {
    AppTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = label,
        singleLine = true,
    )
    Spacer(modifier = Modifier.height(8.dp))
}