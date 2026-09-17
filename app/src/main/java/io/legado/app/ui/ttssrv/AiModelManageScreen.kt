package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiModelsConfig
import io.legado.app.data.repository.AiProvider
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 朗读分析 · AI 模型管理（复刻自研插件「厂商模式模型管理」）：
 *  - 服务商（OpenAI 兼容接口）与模型 CRUD
 *  - 每模型独立配额：响应尝试次数（1=只试一次）/ 校验重试次数（0=不重试）/ 超时
 *  - 阶段分配：①话语识别 ②归属与人物 ④历史匹配 情绪分析（有序队列，实现超时轮换）
 */
@Composable
fun AiModelManageRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    AiModelManageScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiModelManageScreen(app: Application, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(app) { AiModelRepository(app) }
    val scope = rememberCoroutineScope()

    var cfg by remember { mutableStateOf<AiModelsConfig?>(null) }

    var providerSheet by remember { mutableStateOf(false) }
    var peId by remember { mutableStateOf("") }
    var peName by remember { mutableStateOf("") }
    var peBase by remember { mutableStateOf("") }
    var peKey by remember { mutableStateOf("") }
    var peProtocol by remember { mutableStateOf("openai") }
    var peEnabled by remember { mutableStateOf(true) }

    var modelSheet by remember { mutableStateOf(false) }
    var meId by remember { mutableStateOf("") }
    var meProviderId by remember { mutableStateOf("") }
    var meName by remember { mutableStateOf("") }
    var meModelId by remember { mutableStateOf("") }
    var meAttempts by remember { mutableStateOf("2") }
    var meValidate by remember { mutableStateOf("2") }
    var meTimeoutSec by remember { mutableStateOf("120") }
    var meEnabled by remember { mutableStateOf(true) }

    var stageSheetKey by remember { mutableStateOf<String?>(null) }
    var stagePickSheet by remember { mutableStateOf(false) }

    var confirmDeleteProvider by remember { mutableStateOf<AiProvider?>(null) }
    var confirmDeleteModel by remember { mutableStateOf<AiModelEntry?>(null) }

    fun reload() {
        scope.launch { cfg = repo.load() }
    }

    LaunchedEffect(Unit) { reload() }

    fun openProviderEdit(p: AiProvider?) {
        peId = p?.id.orEmpty()
        peName = p?.name.orEmpty()
        peBase = p?.baseUrl.orEmpty()
        peKey = p?.apiKey.orEmpty()
        peProtocol = p?.protocol ?: "openai"
        peEnabled = p?.enabled ?: true
        providerSheet = true
    }

    fun openModelEdit(m: AiModelEntry?, providerId: String) {
        meId = m?.id.orEmpty()
        meProviderId = m?.providerId ?: providerId
        meName = m?.name.orEmpty()
        meModelId = m?.modelId.orEmpty()
        meAttempts = (m?.requestAttempts ?: 2).toString()
        meValidate = (m?.validateRetries ?: 2).toString()
        meTimeoutSec = ((m?.timeoutMs ?: 120_000L) / 1000L).toString()
        meEnabled = m?.enabled ?: true
        modelSheet = true
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "模型管理",
                subtitle = "朗读分析 · 服务商 / 模型 / 阶段配额",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
            )
        },
        floatingActionButton = {
            AppFloatingActionButton(
                onClick = { openProviderEdit(null) },
                icon = Icons.Default.Add,
                tooltipText = "添加服务商",
            )
        },
    ) { padding ->
        val config = cfg
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            if (config == null) {
                item { TinyClickableSettingItem(title = "加载中…", onClick = {}) }
            } else {
                if (config.providers.isEmpty()) {
                    item {
                        SplicedColumnGroup(title = "起步") {
                            TinyClickableSettingItem(
                                title = "还没有服务商",
                                description = "点右下角 ＋ 添加（OpenAI 兼容接口）",
                                onClick = {},
                            )
                        }
                    }
                }
                config.providers.forEach { p ->
                    item(key = "p_${p.id}") {
                        SplicedColumnGroup(title = "🏢 ${p.name}") {
                            val models = config.models.filter { it.providerId == p.id }
                            if (models.isEmpty()) {
                                TinyClickableSettingItem(title = "（暂无模型）", onClick = {})
                            }
                            models.forEach { m ->
                                TinyClickableSettingItem(
                                    title = (if (m.enabled) "🟢 " else "⚪️ ") + m.name,
                                    description = "${m.modelId} · 响应尝试${m.requestAttempts}次 · 校验重试${m.validateRetries}次 · 超时${m.timeoutMs / 1000}s",
                                    onClick = { openModelEdit(m, p.id) },
                                )
                            }
                            TinyClickableSettingItem(
                                title = "＋ 添加模型",
                                onClick = { openModelEdit(null, p.id) },
                            )
                            TinyClickableSettingItem(
                                title = "编辑服务商",
                                onClick = { openProviderEdit(p) },
                            )
                            TinyClickableSettingItem(
                                title = "删除服务商（含其模型）",
                                onClick = { confirmDeleteProvider = p },
                            )
                        }
                    }
                }
                item {
                    SplicedColumnGroup(title = "阶段分配（多模型 · 超时轮换顺序）") {
                        val byId = config.models.associateBy { it.id }
                        fun desc(ids: List<String>, fallback: String): String =
                            if (ids.isEmpty()) {
                                "（未配置）$fallback"
                            } else {
                                ids.map { byId[it]?.name ?: it }.joinToString(" → ")
                            }
                        TinyClickableSettingItem(
                            title = "① 第一阶段 · 话语识别",
                            description = desc(config.stages.stage1, "→ 全超时：本地规则兜底"),
                            onClick = { stageSheetKey = "stage1" },
                        )
                        TinyClickableSettingItem(
                            title = "② 第二阶段 · 归属与人物",
                            description = desc(config.stages.stage2, "→ 全超时：整章降级旁白"),
                            onClick = { stageSheetKey = "stage2" },
                        )
                        TinyClickableSettingItem(
                            title = "④ 第四阶段 · 历史匹配",
                            description = desc(config.stages.stage4, "→ 全超时：全部新建角色"),
                            onClick = { stageSheetKey = "stage4" },
                        )
                        TinyClickableSettingItem(
                            title = "♡ 情绪分析",
                            description = desc(config.stages.emotion, "→ 全失败：无情绪"),
                            onClick = { stageSheetKey = "emotion" },
                        )
                    }
                }
            }
        }
    }

    AppModalBottomSheet(
        show = providerSheet,
        onDismissRequest = { providerSheet = false },
        title = if (peId.isBlank()) "添加服务商" else "编辑服务商",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SheetField("名称", peName) { peName = it }
            SheetField("BaseUrl（如 https://api.xxx.com/v1）", peBase) { peBase = it }
            SheetField("API Key", peKey) { peKey = it }
            SheetField("协议（openai）", peProtocol) { peProtocol = it }
            TinySwitchSettingItem(
                title = "启用",
                checked = peEnabled,
                onCheckedChange = { peEnabled = it },
            )
            TinyClickableSettingItem(
                title = "保存服务商",
                onClick = {
                    if (peName.isBlank() || peBase.isBlank()) {
                        context.toastOnUi("名称 / BaseUrl 不能为空")
                        return@TinyClickableSettingItem
                    }
                    providerSheet = false
                    scope.launch {
                        repo.upsertProvider(
                            AiProvider(
                                id = peId,
                                name = peName.trim(),
                                baseUrl = peBase.trim(),
                                apiKey = peKey.trim(),
                                protocol = peProtocol.trim().ifBlank { "openai" },
                                enabled = peEnabled,
                            )
                        )
                        context.toastOnUi("已保存")
                        reload()
                    }
                },
            )
        }
    }

    AppModalBottomSheet(
        show = modelSheet,
        onDismissRequest = { modelSheet = false },
        title = if (meId.isBlank()) "添加模型" else "编辑模型：$meName",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SheetField("显示名", meName) { meName = it }
            SheetField("模型 ID（请求用，如 SenseChat-5）", meModelId) { meModelId = it }
            SheetField("超时（秒）", meTimeoutSec) { meTimeoutSec = it }
            SheetField("响应尝试次数（1=只试一次；超时/非JSON 记一次）", meAttempts) { meAttempts = it }
            SheetField("校验重试次数（0=不重试；字段校验失败记一次）", meValidate) { meValidate = it }
            TinySwitchSettingItem(
                title = "启用",
                checked = meEnabled,
                onCheckedChange = { meEnabled = it },
            )
            TinyClickableSettingItem(
                title = "保存模型",
                onClick = {
                    if (meName.isBlank() || meModelId.isBlank()) {
                        context.toastOnUi("显示名 / 模型ID 不能为空")
                        return@TinyClickableSettingItem
                    }
                    modelSheet = false
                    scope.launch {
                        repo.upsertModel(
                            AiModelEntry(
                                id = meId,
                                providerId = meProviderId,
                                name = meName.trim(),
                                modelId = meModelId.trim(),
                                enabled = meEnabled,
                                requestAttempts = (meAttempts.toIntOrNull() ?: 2).coerceIn(1, 5),
                                validateRetries = (meValidate.toIntOrNull() ?: 2).coerceIn(0, 5),
                                timeoutMs = ((meTimeoutSec.toLongOrNull() ?: 120L)
                                    .coerceIn(5L, 600L)) * 1000L,
                            )
                        )
                        context.toastOnUi("已保存")
                        reload()
                    }
                },
            )
            if (meId.isNotBlank()) {
                TinyClickableSettingItem(
                    title = "删除该模型",
                    onClick = {
                        val target = cfg?.models?.firstOrNull { it.id == meId }
                        modelSheet = false
                        if (target != null) confirmDeleteModel = target
                    },
                )
            }
        }
    }

    val stageKey = stageSheetKey
    AppModalBottomSheet(
        show = stageKey != null,
        onDismissRequest = { stageSheetKey = null },
        title = stageTitle(stageKey),
    ) {
        val config = cfg
        if (config != null && stageKey != null) {
            val current = stageIds(config, stageKey)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (current.isEmpty()) {
                    TinyClickableSettingItem(title = "队列为空（将走兜底）", onClick = {})
                }
                val byId = config.models.associateBy { it.id }
                current.forEachIndexed { index, id ->
                    TinyClickableSettingItem(
                        title = "${index + 1}. ${byId[id]?.name ?: id}",
                        description = byId[id]?.let {
                            "${it.modelId} · 尝试${it.requestAttempts}/校验${it.validateRetries}"
                        },
                        trailingContent = {
                            SmallPlainButton(
                                icon = Icons.Default.ArrowUpward,
                                contentDescription = "上移",
                                enabled = index > 0,
                                onClick = {
                                    scope.launch {
                                        val list = current.toMutableList()
                                        list.add(index - 1, list.removeAt(index))
                                        repo.updateStage(stageKey, list)
                                        reload()
                                    }
                                },
                            )
                            SmallPlainButton(
                                icon = Icons.Default.ArrowDownward,
                                contentDescription = "下移",
                                enabled = index < current.lastIndex,
                                onClick = {
                                    scope.launch {
                                        val list = current.toMutableList()
                                        list.add(index + 1, list.removeAt(index))
                                        repo.updateStage(stageKey, list)
                                        reload()
                                    }
                                },
                            )
                            SmallPlainButton(
                                icon = Icons.Default.Close,
                                contentDescription = "移除",
                                onClick = {
                                    scope.launch {
                                        repo.updateStage(stageKey, current.filterNot { it == id })
                                        reload()
                                    }
                                },
                            )
                        },
                        onClick = {},
                    )
                }
                TinyClickableSettingItem(
                    title = "＋ 添加模型到队列",
                    onClick = { stagePickSheet = true },
                )
            }
        }
    }

    AppModalBottomSheet(
        show = stagePickSheet,
        onDismissRequest = { stagePickSheet = false },
        title = "选择模型",
    ) {
        val config = cfg
        if (config != null && stageKey != null) {
            val current = stageIds(config, stageKey)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val candidates = config.models.filter { it.id !in current }
                if (candidates.isEmpty()) {
                    TinyClickableSettingItem(
                        title = "没有可添加的模型（先在上方添加模型）",
                        onClick = {},
                    )
                }
                candidates.forEach { m ->
                    TinyClickableSettingItem(
                        title = m.name,
                        description = "${m.modelId}${if (m.enabled) "" else "（未启用）"}",
                        onClick = {
                            stagePickSheet = false
                            scope.launch {
                                repo.updateStage(stageKey, current + m.id)
                                reload()
                            }
                        },
                    )
                }
            }
        }
    }

    AppAlertDialog(
        show = confirmDeleteProvider != null,
        onDismissRequest = { confirmDeleteProvider = null },
        title = "删除服务商",
        text = "确认删除「${confirmDeleteProvider?.name.orEmpty()}」及其全部模型？",
        confirmText = "删除",
        onConfirm = {
            val p = confirmDeleteProvider ?: return@AppAlertDialog
            confirmDeleteProvider = null
            scope.launch {
                repo.deleteProvider(p.id)
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { confirmDeleteProvider = null },
    )

    AppAlertDialog(
        show = confirmDeleteModel != null,
        onDismissRequest = { confirmDeleteModel = null },
        title = "删除模型",
        text = "确认删除「${confirmDeleteModel?.name.orEmpty()}」？（同时从各阶段队列移除）",
        confirmText = "删除",
        onConfirm = {
            val m = confirmDeleteModel ?: return@AppAlertDialog
            confirmDeleteModel = null
            scope.launch {
                repo.deleteModel(m.id)
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { confirmDeleteModel = null },
    )
}

private fun stageTitle(key: String?): String = when (key) {
    "stage1" -> "第一阶段 · 话语识别"
    "stage2" -> "第二阶段 · 归属与人物"
    "stage4" -> "第四阶段 · 历史匹配"
    "emotion" -> "情绪分析"
    else -> "阶段分配"
}

private fun stageIds(config: AiModelsConfig, key: String): List<String> = when (key) {
    "stage1" -> config.stages.stage1
    "stage2" -> config.stages.stage2
    "stage4" -> config.stages.stage4
    "emotion" -> config.stages.emotion
    else -> emptyList()
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