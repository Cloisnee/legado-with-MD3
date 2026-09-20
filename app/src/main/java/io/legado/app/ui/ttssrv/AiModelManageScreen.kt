package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiModelsConfig
import io.legado.app.data.repository.AiProvider
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AdaptiveSwitch
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.SelectionBottomBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.SelectionItemCardContent
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * AI 服务 · 模型管理（对齐真身 MD3 / 书源管理交互）：
 *  - 两标签页（可左右滑动）：模型库 / 模型分配
 *  - 长按卡片 → 底部 SelectionBottomBar（书源管理同款：全选/反选/删除/⋮）
 *  - 选择上下文：厂商 / 模型 / 阶段队列，互不越界
 *  - 测试标签与延迟数据持久化；添加模型默认关闭
 */
@Composable
fun AiModelManageRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    AiModelManageScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

private sealed interface TestUi {
    data object Untested : TestUi
    data object Testing : TestUi
    data class Pass(val latencyMs: Long) : TestUi
    data class Fail(val message: String) : TestUi
}

private val STAGE_CARDS = listOf(
    "stage1" to "话语分析",
    "stage2" to "话语归属与人物",
    "stage4" to "历史人物比对",
    "emotion" to "情绪分析",
)

private fun stageTitle(key: String?): String =
    STAGE_CARDS.firstOrNull { it.first == key }?.second ?: "阶段"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelManageScreen(app: Application, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(app) { AiModelRepository(app) }
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<AiModelsConfig?>(null) }
    var tab by remember { mutableStateOf(0) }

    // 模型库：选择上下文（vendor / model）
    var libCtx by remember { mutableStateOf<String?>(null) }
    var selVendors by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selModels by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelScopeVendor by remember { mutableStateOf<String?>(null) }
    val expandedVendors = remember { mutableStateMapOf<String, Boolean>() }
    val vendorQueries = remember { mutableStateMapOf<String, String>() }
    val testInflight = remember { mutableStateMapOf<String, Boolean>() }

    // 厂商编辑
    var vendorSheet by remember { mutableStateOf(false) }
    var veId by remember { mutableStateOf("") }
    var veName by remember { mutableStateOf("") }
    var veBase by remember { mutableStateOf("") }
    var veKey by remember { mutableStateOf("") }
    var veProtocol by remember { mutableStateOf("openai") }

    // 模型分配：阶段队列选择上下文
    var expandedStages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var addStageKey by remember { mutableStateOf<String?>(null) }
    var quotaTarget by remember { mutableStateOf<AiModelEntry?>(null) }
    var qAttempts by remember { mutableStateOf("2") }
    var qValidate by remember { mutableStateOf("2") }
    var qTimeoutSec by remember { mutableStateOf("120") }
    var queueCtx by remember { mutableStateOf<String?>(null) }
    var selQueue by remember { mutableStateOf<Set<String>>(emptySet()) }

    var confirmBulkDel by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { cfg = repo.load() }
    }

    LaunchedEffect(Unit) { reload() }

    val libActive = selVendors.isNotEmpty() || selModels.isNotEmpty()
    val queueActive = queueCtx != null && selQueue.isNotEmpty()
    val selActive = libActive || queueActive

    fun clearSel() {
        libCtx = null
        selVendors = emptySet()
        selModels = emptySet()
        modelScopeVendor = null
        queueCtx = null
        selQueue = emptySet()
    }

    fun switchTab(t: Int) {
        tab = t
        clearSel()
    }

    fun stageIds(c: AiModelsConfig, key: String): List<String> = when (key) {
        "stage1" -> c.stages.stage1
        "stage2" -> c.stages.stage2
        "stage4" -> c.stages.stage4
        "emotion" -> c.stages.emotion
        else -> emptyList()
    }

    // ---------------- 选择操作（上下文隔离） ----------------

    fun selectAllCurrent() {
        val c = cfg ?: return
        when (tab) {
            0 -> when (libCtx) {
                "vendor" -> selVendors = c.providers.map { it.id }.toSet()
                "model" -> {
                    val pid = modelScopeVendor
                    val scope = c.models
                        .filter { pid == null || it.providerId == pid }
                        .map { it.id }
                        .toSet()
                    selModels = selModels + scope
                }
            }

            1 -> {
                val key = queueCtx ?: return
                selQueue = stageIds(c, key).toSet()
            }
        }
    }

    fun invertCurrent() {
        val c = cfg ?: return
        when (tab) {
            0 -> when (libCtx) {
                "vendor" -> selVendors = c.providers.map { it.id }.toSet() - selVendors
                "model" -> {
                    val pid = modelScopeVendor
                    val scope = c.models
                        .filter { pid == null || it.providerId == pid }
                        .map { it.id }
                        .toSet()
                    selModels = (selModels - scope) + (scope - selModels)
                }
            }

            1 -> {
                val key = queueCtx ?: return
                selQueue = stageIds(c, key).toSet() - selQueue
            }
        }
    }

    fun deleteSelected() {
        val c = cfg ?: return
        when (tab) {
            0 -> confirmBulkDel = true
            1 -> {
                val key = queueCtx ?: return
                val newQ = stageIds(c, key).filterNot { it in selQueue }
                scope.launch {
                    repo.updateStage(key, newQ)
                    selQueue = emptySet()
                    queueCtx = null
                    reload()
                }
            }
        }
    }

    fun setEnabledForSelected(enabled: Boolean) {
        val c = cfg ?: return
        val target = when (libCtx) {
            "vendor" -> c.models
                .filter { it.providerId in selVendors }
                .map { it.id }
                .toSet()

            "model" -> selModels
            else -> return
        }
        if (target.isEmpty()) return
        scope.launch {
            repo.setModelsEnabled(target, enabled)
            reload()
        }
    }

    fun moveSelectedTopBottom(toTop: Boolean) {
        val c = cfg ?: return
        scope.launch {
            when (tab) {
                0 -> when (libCtx) {
                    "vendor" -> {
                        val vendors = c.providers.filter { it.id in selVendors }
                        (if (toTop) vendors.reversed() else vendors).forEach {
                            repo.moveProvider(it.id, toTop)
                        }
                    }

                    "model" -> {
                        val models = c.models.filter { it.id in selModels }
                        (if (toTop) models.reversed() else models).forEach {
                            repo.moveModel(it.id, toTop)
                        }
                    }
                }

                1 -> {
                    val key = queueCtx ?: return@launch
                    val queue = stageIds(c, key)
                    val sel = queue.filter { it in selQueue }
                    val rest = queue.filterNot { it in selQueue }
                    repo.updateStage(key, if (toTop) sel + rest else rest + sel)
                }
            }
            reload()
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
                            if (n > 0) "拉取到 ${list.size} 个模型（默认关闭，按需开启）"
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

    fun refetchModels(p: AiProvider) {
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
    }

    fun testOne(m: AiModelEntry, p: AiProvider) {
        testInflight[m.id] = true
        scope.launch {
            val r = repo.testModel(m, p)
            testInflight.remove(m.id)
            repo.updateModelTest(m.id, r.ok, r.latencyMs, r.message)
            context.toastOnUi(
                if (r.ok) "✅ ${m.name} 通过 · ${r.latencyMs}ms"
                else "❌ ${m.name} 异常：${r.message}"
            )
            reload()
        }
    }

    // ---------------- 界面 ----------------

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "模型管理",
                subtitle = "模型库 / 模型分配",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                bottomContent = {
                    AppTabRow(
                        tabTitles = listOf("模型库", "模型分配"),
                        selectedTabIndex = tab,
                        onTabSelected = { t -> switchTab(t) },
                        isScrollable = false,
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
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                0 -> ModelLibraryPage(
                    config = cfg,
                    libActive = libActive,
                    libCtx = libCtx,
                    selVendors = selVendors,
                    selModels = selModels,
                    expandedVendors = expandedVendors,
                    vendorQueries = vendorQueries,
                    testInflight = testInflight,
                    contentPaddingTop = padding.calculateTopPadding(),
                    contentPaddingBottom = padding.calculateBottomPadding(),
                    onVendorClick = { p ->
                        if (libActive) {
                            if (libCtx == "vendor") {
                                selVendors = if (p.id in selVendors) {
                                    selVendors - p.id
                                } else {
                                    selVendors + p.id
                                }
                            }
                        } else {
                            expandedVendors[p.id] = !(expandedVendors[p.id] ?: false)
                            if (expandedVendors[p.id] == false) vendorQueries[p.id] = ""
                        }
                    },
                    onVendorLongClick = { p ->
                        if (!libActive) {
                            libCtx = "vendor"
                            selVendors = setOf(p.id)
                            selModels = emptySet()
                        } else if (libCtx == "vendor") {
                            selVendors = if (p.id in selVendors) selVendors - p.id else selVendors + p.id
                        }
                    },
                    onToggleExpand = { id ->
                        expandedVendors[id] = !(expandedVendors[id] ?: false)
                        if (expandedVendors[id] == false) vendorQueries[id] = ""
                    },
                    onModelClick = { m ->
                        if (libActive) {
                            if (libCtx == "model") {
                                selModels = if (m.id in selModels) {
                                    selModels - m.id
                                } else {
                                    selModels + m.id
                                }
                            }
                        }
                    },
                    onModelLongClick = { m ->
                        if (!libActive) {
                            libCtx = "model"
                            selModels = setOf(m.id)
                            selVendors = emptySet()
                            modelScopeVendor = m.providerId
                        } else if (libCtx == "model") {
                            selModels = if (m.id in selModels) selModels - m.id else selModels + m.id
                        }
                    },
                    onQueryChange = { id, q -> vendorQueries[id] = q },
                    onToggleModelEnabled = { m, enabled ->
                        scope.launch {
                            repo.upsertModel(m.copy(enabled = enabled))
                            reload()
                        }
                    },
                    onTestModel = { m, p -> testOne(m, p) },
                )

                1 -> AllocationPage(
                    config = cfg,
                    expandedStages = expandedStages,
                    queueCtx = queueCtx,
                    selQueue = selQueue,
                    contentPaddingTop = padding.calculateTopPadding(),
                    contentPaddingBottom = padding.calculateBottomPadding(),
                    onToggleExpand = { key ->
                        expandedStages = if (key in expandedStages) {
                            expandedStages - key
                        } else {
                            expandedStages + key
                        }
                    },
                    onOpenAddSheet = { key -> addStageKey = key },
                    onQueueItemClick = { key, id ->
                        if (queueCtx == key) {
                            selQueue = if (id in selQueue) selQueue - id else selQueue + id
                        } else {
                            val c = cfg
                            val m = c?.models?.firstOrNull { it.id == id }
                            if (m != null) {
                                quotaTarget = m
                                qAttempts = m.requestAttempts.toString()
                                qValidate = m.validateRetries.toString()
                                qTimeoutSec = (m.timeoutMs / 1000L).toString()
                            }
                        }
                    },
                    onQueueItemLongClick = { key, id ->
                        if (queueCtx != key) {
                            queueCtx = key
                            selQueue = setOf(id)
                        } else {
                            selQueue = if (id in selQueue) selQueue - id else selQueue + id
                        }
                    },
                )
            }

            // 书源管理同款底部操作条（长按后出现）
            if (selActive) {
                val primaryText = if (tab == 0) "删除" else "移除"
                val secondary = buildList {
                    if (tab == 0 && libCtx == "vendor") {
                        add(ActionItem("开启所有", Icons.Default.Check) { setEnabledForSelected(true) })
                        add(ActionItem("关闭所有", Icons.Default.Close) { setEnabledForSelected(false) })
                        val target = cfg?.providers?.firstOrNull { it.id in selVendors }
                        add(ActionItem("编辑服务商", Icons.Default.Edit) {
                            if (selVendors.size == 1 && target != null) {
                                openVendorEdit(target)
                            } else {
                                context.toastOnUi("请只选择一个服务商再编辑")
                            }
                        })
                        add(ActionItem("重新拉取模型", Icons.Default.Refresh) {
                            if (selVendors.size == 1 && target != null) {
                                refetchModels(target)
                            } else {
                                context.toastOnUi("请只选择一个服务商再拉取")
                            }
                        })
                    }
                    if (tab == 0 && libCtx == "model") {
                        add(ActionItem("开启", Icons.Default.Check) { setEnabledForSelected(true) })
                        add(ActionItem("关闭", Icons.Default.Close) { setEnabledForSelected(false) })
                    }
                    add(ActionItem("置顶", Icons.Default.VerticalAlignTop) { moveSelectedTopBottom(true) })
                    add(ActionItem("置底", Icons.Default.VerticalAlignBottom) { moveSelectedTopBottom(false) })
                }
                SelectionBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    onSelectAll = { selectAllCurrent() },
                    onSelectInvert = { invertCurrent() },
                    primaryAction = ActionItem(primaryText, Icons.Default.Delete) { deleteSelected() },
                    secondaryActions = secondary,
                )
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

    // ---------------- 模型分配 · 添加模型（数字顺序 + 二次点击取消） ----------------
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
                    text = "点击顺序 = 失败后的轮换顺序；再点一次取消",
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
                            val idx = queue.indexOf(m.id)
                            TinyClickableSettingItem(
                                title = m.name,
                                description = m.modelId,
                                trailingContent = {
                                    if (idx >= 0) NumberBadge(idx + 1)
                                },
                                onClick = {
                                    scope.launch {
                                        val newQ = if (idx >= 0) {
                                            queue.filterNot { it == m.id }
                                        } else {
                                            queue + m.id
                                        }
                                        repo.updateStage(addKey, newQ)
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
                if (!any) {
                    TinyClickableSettingItem(
                        title = "没有已开启的模型（先去模型库打开开关）",
                        onClick = {},
                    )
                }
            }
        }
    }

    // ---------------- 模型分配 · 次数设置 ----------------
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
        show = confirmBulkDel,
        onDismissRequest = { confirmBulkDel = false },
        title = "删除所选",
        text = if (libCtx == "vendor") {
            "将删除所选服务商（含其全部模型），确认？"
        } else {
            "将删除所选模型，确认？"
        },
        confirmText = "删除",
        onConfirm = {
            confirmBulkDel = false
            scope.launch {
                if (libCtx == "vendor") {
                    selVendors.forEach { repo.deleteProvider(it) }
                } else {
                    selModels.forEach { repo.deleteModel(it) }
                }
                clearSel()
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
    libActive: Boolean,
    libCtx: String?,
    selVendors: Set<String>,
    selModels: Set<String>,
    expandedVendors: MutableMap<String, Boolean>,
    vendorQueries: MutableMap<String, String>,
    testInflight: MutableMap<String, Boolean>,
    contentPaddingTop: androidx.compose.ui.unit.Dp,
    contentPaddingBottom: androidx.compose.ui.unit.Dp,
    onVendorClick: (AiProvider) -> Unit,
    onVendorLongClick: (AiProvider) -> Unit,
    onToggleExpand: (String) -> Unit,
    onModelClick: (AiModelEntry) -> Unit,
    onModelLongClick: (AiModelEntry) -> Unit,
    onQueryChange: (String, String) -> Unit,
    onToggleModelEnabled: (AiModelEntry, Boolean) -> Unit,
    onTestModel: (AiModelEntry, AiProvider) -> Unit,
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
                val vSelActive = libActive && libCtx == "vendor"
                val vSelected = p.id in selVendors
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    label = "vendorArrow",
                )
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    cornerRadius = 12.dp,
                    containerColor = if (vSelected) {
                        LegadoTheme.colorScheme.secondaryContainer
                    } else {
                        LegadoTheme.colorScheme.surfaceContainer
                    },
                    onClick = { onVendorClick(p) },
                    onLongClick = { onVendorLongClick(p) },
                ) {
                    SelectionItemCardContent(
                        title = p.name,
                        subtitle = "共 ${models.size} 个模型 · ${p.protocol}",
                        inSelectionMode = vSelActive,
                        isSelected = vSelected,
                        trailingAction = {
                            CountTag(enabledCount, models.size)
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (expanded) "收起" else "展开",
                                modifier = Modifier
                                    .rotate(rotation)
                                    .clickable { onToggleExpand(p.id) }
                                    .padding(4.dp),
                            )
                        },
                    )
                }
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
                        shape = RoundedCornerShape(12.dp),
                        autoFocus = false,
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
                    val testState = when {
                        testInflight[m.id] == true -> TestUi.Testing
                        m.testOk == true -> TestUi.Pass(m.testLatencyMs ?: 0L)
                        m.testOk == false -> TestUi.Fail(m.testMessage.orEmpty())
                        else -> TestUi.Untested
                    }
                    val mSelActive = libActive && libCtx == "model"
                    val mSelected = m.id in selModels
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        cornerRadius = 12.dp,
                        containerColor = if (mSelected) {
                            LegadoTheme.colorScheme.secondaryContainer
                        } else {
                            LegadoTheme.colorScheme.surfaceContainer
                        },
                        onClick = { onModelClick(m) },
                        onLongClick = { onModelLongClick(m) },
                    ) {
                        SelectionItemCardContent(
                            title = m.name + when (val s = testState) {
                                is TestUi.Pass -> " · ${s.latencyMs}ms"
                                else -> ""
                            },
                            subtitle = m.modelId,
                            isEnabled = m.enabled,
                            inSelectionMode = mSelActive,
                            isSelected = mSelected,
                            trailingAction = if (libActive) null else {
                                {
                                    TestChip(testState) { onTestModel(m, p) }
                                    AdaptiveSwitch(
                                        checked = m.enabled,
                                        onCheckedChange = { v -> onToggleModelEnabled(m, v) },
                                        modifier = Modifier.scale(0.8f),
                                    )
                                }
                            },
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
            .padding(end = 4.dp)
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
private fun NumberBadge(number: Int) {
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(LegadoTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        AppText(
            text = "$number",
            style = LegadoTheme.typography.labelSmall,
            color = LegadoTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun TestChip(state: TestUi, onClick: () -> Unit) {
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
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "测试",
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
// 模型分配页（文本分析 / 图像生成 / 背景音乐与音效 合页）
// ============================================================

@Composable
private fun AllocationPage(
    config: AiModelsConfig?,
    expandedStages: Set<String>,
    queueCtx: String?,
    selQueue: Set<String>,
    contentPaddingTop: androidx.compose.ui.unit.Dp,
    contentPaddingBottom: androidx.compose.ui.unit.Dp,
    onToggleExpand: (String) -> Unit,
    onOpenAddSheet: (String) -> Unit,
    onQueueItemClick: (String, String) -> Unit,
    onQueueItemLongClick: (String, String) -> Unit,
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

        item(key = "section_text") {
            SectionTitle("文本分析")
        }
        STAGE_CARDS.forEach { (key, title) ->
            val queue = when (key) {
                "stage1" -> config.stages.stage1
                "stage2" -> config.stages.stage2
                "stage4" -> config.stages.stage4
                else -> config.stages.emotion
            }
            item(key = "stage_$key") {
                val expanded = key in expandedStages
                val rotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    label = "stageArrow",
                )
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    cornerRadius = 12.dp,
                    containerColor = LegadoTheme.colorScheme.surfaceContainer,
                    onClick = { onOpenAddSheet(key) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AppText(
                                text = title,
                                style = LegadoTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AppText(
                                text = if (queue.isEmpty()) {
                                    "未配置模型（点卡片添加；失败时走兜底策略）"
                                } else {
                                    queue.map { byId[it]?.name ?: it }.joinToString(" → ")
                                },
                                style = LegadoTheme.typography.bodySmall,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (queue.isNotEmpty()) {
                            CountTag(queue.size, queue.size)
                        }
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起" else "展开",
                            modifier = Modifier
                                .rotate(rotation)
                                .clickable { onToggleExpand(key) }
                                .padding(4.dp),
                        )
                    }
                }
            }
            if (key in expandedStages) {
                items(queue, key = { "q_${key}_$it" }) { modelId ->
                    val m = byId[modelId]
                    val thisCtx = queueCtx == key
                    val selected = thisCtx && modelId in selQueue
                    val index = queue.indexOf(modelId) + 1
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                        cornerRadius = 12.dp,
                        containerColor = if (selected) {
                            LegadoTheme.colorScheme.secondaryContainer
                        } else {
                            LegadoTheme.colorScheme.surfaceContainer
                        },
                        onClick = { onQueueItemClick(key, modelId) },
                        onLongClick = { onQueueItemLongClick(key, modelId) },
                    ) {
                        SelectionItemCardContent(
                            title = "$index. ${m?.name ?: modelId}",
                            subtitle = m?.let {
                                "${it.modelId} · 尝试${it.requestAttempts}/校验${it.validateRetries} · ${it.timeoutMs / 1000}s"
                            },
                            inSelectionMode = thisCtx,
                            isSelected = selected,
                        )
                    }
                }
            }
        }

        item(key = "section_image") {
            SectionTitle("图像生成")
        }
        item(key = "image_placeholder") {
            TinyClickableSettingItem(
                title = "暂未开放",
                description = "预留板块，后续版本接入",
                onClick = {},
            )
        }

        item(key = "section_bgm") {
            SectionTitle("背景音乐与音效")
        }
        item(key = "bgm_placeholder") {
            TinyClickableSettingItem(
                title = "暂未开放",
                description = "预留板块，后续版本接入",
                onClick = {},
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    AppText(
        text = text,
        style = LegadoTheme.typography.titleSmall,
        color = LegadoTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

// ============================================================
// 通用
// ============================================================

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