package io.legado.app.ui.ttssrv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import io.legado.app.help.readaloud.analysis.AnalysisConfigStore
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV3
import io.legado.app.ui.book.read.sheet.ReadAloudNumberConfigSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 「AI 分析设置」独立页（B10.4·A1-A5）——我的 → 朗读 →「AI 分析设置」：
 *  - 请求：统一超时（覆盖模型级 timeoutMs）· 等分析就绪最长等待；
 *  - 提示词：stage1 话语选号 / stage2 角色归并 / stage4 同名判定 / 情绪（留空 = 内置默认）；
 *  - 取文与输出：前情提要 / 后续剧情字数 · 情绪等待 · 单次输出上限。
 * 全部落盘 `_store/analysis_config.json`，保存即生效（下次分析）。
 */
@Composable
fun AiAnalysisSettingsRouteScreen(onBackClick: () -> Unit) {
    AiAnalysisSettingsScreen(onBack = onBackClick)
}

private enum class PromptTarget(val title: String, val key: String) {
    Stage1("stage1 话语选号 提示词", "stage1"),
    Stage2("stage2 角色归并 提示词", "stage2"),
    Stage4("stage4 同名判定 提示词", "stage4"),
    Emotion("情绪分析 提示词", "emotion"),
}

private fun AnalysisConfigStore.Config.withPrompt(key: String, value: String): AnalysisConfigStore.Config =
    when (key) {
        "stage1" -> copy(stage1Prompt = value)
        "stage2" -> copy(stage2Prompt = value)
        "stage4" -> copy(stage4Prompt = value)
        else -> copy(emotionPrompt = value)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAnalysisSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GlobalContext.get().get<AnalysisConfigStore>() }

    var cfg by remember { mutableStateOf(AnalysisConfigStore.Config()) }

    fun update(transform: (AnalysisConfigStore.Config) -> AnalysisConfigStore.Config) {
        cfg = transform(cfg)
        scope.launch { store.save(cfg) }
    }

    LaunchedEffect(Unit) {
        cfg = store.load()
    }

    var showTimeout by remember { mutableStateOf(false) }
    var showWait by remember { mutableStateOf(false) }
    var showPrev by remember { mutableStateOf(false) }
    var showNext by remember { mutableStateOf(false) }
    var showEmotionJoin by remember { mutableStateOf(false) }
    var showMaxTokens by remember { mutableStateOf(false) }
    var promptTarget by remember { mutableStateOf<PromptTarget?>(null) }
    var promptDraft by remember { mutableStateOf("") }

    fun promptDesc(text: String): String =
        if (text.isBlank()) "内置默认（点击编辑）" else "已自定义 · ${text.length} 字（点击编辑）"

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "AI 分析设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = padding.calculateTopPadding(),
                bottom = 120.dp,
            ),
        ) {
            item {
                SplicedColumnGroup(title = "请求") {
                    TinyClickableSettingItem(
                        title = "统一超时",
                        description = "所有阶段 AI 分析请求统一使用（覆盖模型级超时）· ${cfg.timeoutSec} 秒",
                        onClick = { showTimeout = true },
                    )
                    TinyClickableSettingItem(
                        title = "等分析就绪最长等待",
                        description = "预合成前等待本章分析就绪 · ${cfg.waitAnalysisSec} 秒",
                        onClick = { showWait = true },
                    )
                    TinySwitchSettingItem(
                        title = "先用默认声线出声",
                        description = "开：分析未就绪先出声（默认声线；重进本章即切换）；关：等待分析就绪再出声（上限=上方等待秒数）",
                        checked = cfg.fallbackDefaultVoice,
                        onCheckedChange = { v -> update { it.copy(fallbackDefaultVoice = v) } },
                    )
                }
            }
            item {
                SplicedColumnGroup(title = "提示词") {
                    TinyClickableSettingItem(
                        title = PromptTarget.Stage1.title,
                        description = promptDesc(cfg.stage1Prompt),
                        onClick = {
                            promptDraft = cfg.stage1Prompt.ifBlank {
                                SpeechAnalysisPipelineV3.defaultPrompt(PromptTarget.Stage1.key)
                            }
                            promptTarget = PromptTarget.Stage1
                        },
                    )
                    TinyClickableSettingItem(
                        title = PromptTarget.Stage2.title,
                        description = promptDesc(cfg.stage2Prompt),
                        onClick = {
                            promptDraft = cfg.stage2Prompt.ifBlank {
                                SpeechAnalysisPipelineV3.defaultPrompt(PromptTarget.Stage2.key)
                            }
                            promptTarget = PromptTarget.Stage2
                        },
                    )
                    TinyClickableSettingItem(
                        title = PromptTarget.Stage4.title,
                        description = promptDesc(cfg.stage4Prompt),
                        onClick = {
                            promptDraft = cfg.stage4Prompt.ifBlank {
                                SpeechAnalysisPipelineV3.defaultPrompt(PromptTarget.Stage4.key)
                            }
                            promptTarget = PromptTarget.Stage4
                        },
                    )
                    TinyClickableSettingItem(
                        title = PromptTarget.Emotion.title,
                        description = promptDesc(cfg.emotionPrompt),
                        onClick = {
                            promptDraft = cfg.emotionPrompt.ifBlank {
                                SpeechAnalysisPipelineV3.defaultPrompt(PromptTarget.Emotion.key)
                            }
                            promptTarget = PromptTarget.Emotion
                        },
                    )
                }
            }
            item {
                SplicedColumnGroup(title = "取文与输出") {
                    TinyClickableSettingItem(
                        title = "前情提要字数",
                        description = "stage2 取上一章结尾作前情 · ${cfg.prevLimit} 字（0=不取）",
                        onClick = { showPrev = true },
                    )
                    TinyClickableSettingItem(
                        title = "后续剧情字数",
                        description = "stage2 取下一章开头作后续 · ${cfg.nextLimit} 字（0=不取）",
                        onClick = { showNext = true },
                    )
                    TinyClickableSettingItem(
                        title = "情绪等待时间",
                        description = "第4阶段完成后最多再等情绪结果 · ${cfg.emotionJoinTimeoutMs / 1000} 秒",
                        onClick = { showEmotionJoin = true },
                    )
                    TinyClickableSettingItem(
                        title = "单次输出上限",
                        description = "单次 AI 输出上限 · ${cfg.maxOutputTokens} tokens",
                        onClick = { showMaxTokens = true },
                    )
                }
            }
        }
    }

    // ---------------- 提示词编辑 ----------------
    val target = promptTarget
    AppModalBottomSheet(
        show = target != null,
        onDismissRequest = { promptTarget = null },
        title = target?.title.orEmpty(),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            AppText(
                text = when (target) {
                    PromptTarget.Stage4 ->
                        "已载入内置默认文本，可直接编辑；支持 %ROLE% 占位符（自动替换为角色名）；保存后下次分析生效"
                    PromptTarget.Emotion ->
                        "已载入内置默认文本，可直接编辑；支持 %VOCAB% 占位符（自动替换为情绪词表）；保存后下次分析生效"
                    else -> "已载入内置默认文本，可直接编辑；保存后下次分析生效"
                },
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            AppTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                label = "提示词",
                minLines = 8,
                maxLines = 16,
            )
            TinyClickableSettingItem(
                title = "保存",
                onClick = {
                    val k = promptTarget ?: return@TinyClickableSettingItem
                    val def = SpeechAnalysisPipelineV3.defaultPrompt(k.key).trim()
                    // 与内置默认一致时存空串（保持「内置默认」态，便于后续随内置更新）
                    val v = if (promptDraft.trim() == def) "" else promptDraft
                    promptTarget = null
                    update { it.withPrompt(k.key, v) }
                    context.toastOnUi("已保存提示词")
                },
            )
            TinyClickableSettingItem(
                title = "恢复内置默认",
                description = "将上方文本重置为内置默认提示词（可继续编辑）",
                onClick = {
                    promptTarget?.let { promptDraft = SpeechAnalysisPipelineV3.defaultPrompt(it.key) }
                },
            )
        }
    }

    // ---------------- 数值配置 ----------------
    ReadAloudNumberConfigSheet(
        show = showTimeout,
        title = "统一超时",
        description = "所有阶段的 AI 分析请求统一使用（覆盖模型级超时）",
        value = cfg.timeoutSec,
        defaultValue = 120,
        valueRange = 30f..600f,
        onValueChange = { v -> update { it.copy(timeoutSec = v.coerceIn(30, 600)) } },
        onDismissRequest = { showTimeout = false },
    )
    ReadAloudNumberConfigSheet(
        show = showWait,
        title = "等分析就绪最长等待",
        description = "预合成前等待本章分析就绪",
        value = cfg.waitAnalysisSec,
        defaultValue = 180,
        valueRange = 30f..600f,
        onValueChange = { v -> update { it.copy(waitAnalysisSec = v.coerceIn(30, 600)) } },
        onDismissRequest = { showWait = false },
    )
    ReadAloudNumberConfigSheet(
        show = showPrev,
        title = "前情提要字数",
        description = "stage2 取上一章结尾作前情（0=不取）",
        value = cfg.prevLimit,
        defaultValue = 600,
        valueRange = 0f..3000f,
        onValueChange = { v -> update { it.copy(prevLimit = v.coerceIn(0, 3000)) } },
        onDismissRequest = { showPrev = false },
    )
    ReadAloudNumberConfigSheet(
        show = showNext,
        title = "后续剧情字数",
        description = "stage2 取下一章开头作后续（0=不取）",
        value = cfg.nextLimit,
        defaultValue = 400,
        valueRange = 0f..3000f,
        onValueChange = { v -> update { it.copy(nextLimit = v.coerceIn(0, 3000)) } },
        onDismissRequest = { showNext = false },
    )
    ReadAloudNumberConfigSheet(
        show = showEmotionJoin,
        title = "情绪等待时间",
        description = "第4阶段完成后最多再等情绪结果（超时先落库）",
        value = (cfg.emotionJoinTimeoutMs / 1000L).toInt(),
        defaultValue = 120,
        valueRange = 5f..1800f,
        onValueChange = { v -> update { it.copy(emotionJoinTimeoutMs = v.coerceIn(5, 1800) * 1000L) } },
        onDismissRequest = { showEmotionJoin = false },
    )
    ReadAloudNumberConfigSheet(
        show = showMaxTokens,
        title = "单次输出上限",
        description = "单次 AI 输出上限（tokens）",
        value = cfg.maxOutputTokens,
        defaultValue = 8192,
        valueRange = 256f..32768f,
        onValueChange = { v -> update { it.copy(maxOutputTokens = v.coerceIn(256, 32768)) } },
        onDismissRequest = { showMaxTokens = false },
    )
}
