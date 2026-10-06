package io.legado.app.ui.ttssrv

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.help.readaloud.audio.AudioBuiltinSfxRules
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.help.readaloud.audio.AudioSeries
import io.legado.app.help.readaloud.audio.splitWordList
import io.legado.app.ui.replace.edit.QuickInputBar
import io.legado.app.ui.replace.edit.keyboardAsState
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * B33.2c · 音频编辑（完整二级页）——复刻「替换净化」编辑页：
 * 顶栏 ⋮（拷贝规则 / 粘贴规则）+ 音频名称 / 分组（下拉 + 齿轮→音频参数）/ 匹配规则 / 标签描述 /
 * 「标题 · 正文 · 使用正则」标签按钮 + 正则快捷输入条 + 键盘联动动画。
 * （按要求不再包含 特定范围 / 排除范围 / 超时 三项。）
 */
@Composable
fun AudioEditRouteScreen(assetId: String, onBackClick: () -> Unit) {
    val context = LocalContext.current
    AudioEditScreen(
        app = context.applicationContext as Application,
        assetId = assetId,
        onBack = onBackClick,
    )
}

private enum class EditField { Name, Pattern, TagDesc }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioEditScreen(app: Application, assetId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asset by remember { mutableStateOf<AudioLibrary.AudioAsset?>(null) }
    var ready by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("音效") }
    var pattern by remember { mutableStateOf("") }
    var tagDesc by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(true) }
    var scopeTitle by remember { mutableStateOf(false) }
    var scopeContent by remember { mutableStateOf(true) }

    var activeField by remember { mutableStateOf(EditField.Pattern) }
    var showMenu by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
    // 第四刀：跨系列词冲突（硬拦截弹窗）
    var wordConflicts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    val isKeyboardVisible by keyboardAsState()

    LaunchedEffect(assetId) {
        val list = runCatching { AudioLibrary.assets(app) }.getOrDefault(emptyList())
        val a = list.firstOrNull { it.id == assetId }
        if (a == null) {
            app.toastOnUi("音频条目不存在")
            onBack()
            return@LaunchedEffect
        }
        asset = a
        name = a.name
        category = a.category
        pattern = a.pattern
        tagDesc = a.tagDesc
        isRegex = a.isRegex
        scopeTitle = a.scopeTitle
        scopeContent = a.scopeContent
        ready = true
    }

    fun save() {
        val base = asset ?: return
        scope.launch {
            val oldWords = if (!base.isRegex) splitWordList(base.pattern) else emptyList()
            val inputWords = if (!isRegex) splitWordList(pattern.trim()) else emptyList()
            val added = inputWords.filter { it !in oldWords }
            // 第四刀·①：跨系列词硬拦截——新增词撞「同栏、非同系列」条目 → 弹窗并中止（先改对方或换词）
            val hits = AudioSeries.crossSeriesConflicts(base, added)
            if (hits.isNotEmpty()) {
                wordConflicts = hits
                return@launch
            }
            var tip = "已保存"
            runCatching {
                val newName = name.trim().ifBlank { base.name }
                var newPattern = pattern.trim()
                // 第四刀·②：改名脱离系列 → 清掉「旧系列其他成员共享的词」（非同系列自留词保留）
                if (AudioSeries.seriesKey(base.name) != AudioSeries.seriesKey(newName)) {
                    val shared = AudioSeries.members(app, base).flatMap { AudioSeries.wordsOf(it) }.toSet()
                    val cur = splitWordList(newPattern)
                    val kept = cur.filter { it !in shared }
                    if (kept.size != cur.size) newPattern = AudioSeries.joinWords(kept)
                }
                AudioLibrary.updateAsset(
                    app,
                    base.copy(
                        name = newName,
                        pattern = newPattern,
                        tagDesc = tagDesc.trim(),
                        isRegex = isRegex,
                        scopeTitle = scopeTitle,
                        scopeContent = scopeContent,
                    ),
                )
                // 第三刀：改名联动——本地规则「旧名→新名」重定向（防内置规则/离线名表找不到条目）
                if (newName != base.name) {
                    AudioBuiltinSfxRules.rememberRedirect(app, base.name, newName)
                }
                // 第四刀·③：同系列词同步——本次新增的词 → 同步给同系列其他成员
                val after = AudioLibrary.snapshot().firstOrNull { it.id == base.id }
                val syncN = if (after != null && !after.isRegex) {
                    val addedFinal = splitWordList(after.pattern).filter { it !in oldWords }
                    AudioSeries.syncWordsToSeries(app, after, addedFinal)
                } else {
                    0
                }
                if (category != base.category) {
                    AudioLibrary.setCategory(app, setOf(base.id), category)
                }
                if (syncN > 0) tip = "已保存（同系列同步 $syncN 条）"
            }
            app.toastOnUi(tip)
            onBack()
        }
    }

    fun copyRule() {
        val json = JSONObject().apply {
            put("name", name)
            put("category", category)
            put("pattern", pattern)
            put("tagDesc", tagDesc)
            put("isRegex", isRegex)
            put("scopeTitle", scopeTitle)
            put("scopeContent", scopeContent)
        }.toString()
        app.sendToClip(json)
        app.toastOnUi("规则已复制到剪贴板")
    }

    fun pasteRule() {
        val text = app.getClipText()
        val o = runCatching { JSONObject(text.orEmpty()) }.getOrNull()
        if (o == null) {
            app.toastOnUi("剪贴板为空或格式不对")
            return
        }
        name = o.optString("name", name)
        category = o.optString("category", o.optString("group", category))
        pattern = o.optString("pattern", pattern)
        tagDesc = o.optString("tagDesc", tagDesc)
        isRegex = o.optBoolean("isRegex", isRegex)
        scopeTitle = o.optBoolean("scopeTitle", scopeTitle)
        scopeContent = o.optBoolean("scopeContent", scopeContent)
        app.toastOnUi("已粘贴规则")
    }

    if (!ready) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AppText(text = "加载中…")
        }
        return
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "编辑音频",
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    AnimatedVisibility(
                        visible = isKeyboardVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TopBarActionButton(
                            onClick = { save() },
                            imageVector = Icons.Default.Save,
                            contentDescription = "保存",
                        )
                    }
                    TopBarActionButton(
                        onClick = { showMenu = true },
                        imageVector = AppIcons.MoreVert,
                        contentDescription = "更多",
                    )
                    RoundDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        RoundDropdownMenuItem(
                            text = "拷贝规则",
                            onClick = {
                                showMenu = false
                                copyRule()
                            },
                        )
                        RoundDropdownMenuItem(
                            text = "粘贴规则",
                            onClick = {
                                showMenu = false
                                pasteRule()
                            },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AppFloatingActionButton(
                modifier = Modifier
                    .navigationBarsPadding()
                    .animateFloatingActionButton(
                        visible = !isKeyboardVisible,
                        alignment = Alignment.BottomEnd,
                    ),
                onClick = { save() },
                tooltipText = "保存",
                icon = Icons.Default.Save,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AnimatedVisibility(
                visible = isKeyboardVisible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1f),
            ) {
                QuickInputBar(
                    onInsert = { text ->
                        when (activeField) {
                            EditField.Name -> name += text
                            EditField.Pattern -> pattern += text
                            EditField.TagDesc -> tagDesc += text
                        }
                    },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "音频名称",
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) activeField = EditField.Name },
                    singleLine = true,
                )
                FixedGroupSelector(
                    current = category,
                    options = listOf("音效", "BGM", "环境声"),
                    onSelect = { category = it },
                    onParamsClick = { paramsOpen = true },
                )
                AppTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = "匹配规则",
                    placeholder = { AppText("留空=由库级规则驱动；关正则=加词（多词用 | 、; 换行）") },
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) activeField = EditField.Pattern },
                )
                AppTextField(
                    value = tagDesc,
                    onValueChange = { tagDesc = it },
                    label = "标签描述",
                    placeholder = { AppText("插入标签的说明 / 合成描述（默认同素材名）") },
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.isFocused) activeField = EditField.TagDesc },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    ToggleChip(
                        label = "标题",
                        selected = scopeTitle,
                        checkedContentDescription = "应用于标题",
                        onToggle = { scopeTitle = !scopeTitle },
                    )
                    Spacer(Modifier.width(8.dp))
                    ToggleChip(
                        label = "正文",
                        selected = scopeContent,
                        checkedContentDescription = "应用于正文",
                        onToggle = { scopeContent = !scopeContent },
                    )
                    Spacer(Modifier.weight(1f))
                    ToggleChip(
                        label = "使用正则",
                        selected = isRegex,
                        checkedContentDescription = "使用正则",
                        onToggle = { isRegex = !isRegex },
                    )
                }
                Spacer(Modifier.height(120.dp))
            }
        }
    }

    AudioParamsSheet(
        show = paramsOpen,
        asset = asset,
        onDismiss = { paramsOpen = false },
        onSave = { updated ->
            paramsOpen = false
            asset = updated
            scope.launch {
                AudioLibrary.updateAsset(app, updated)
                app.toastOnUi("参数已保存（播放中实时生效）")
            }
        },
    )

    // 第四刀：跨系列词冲突提示（硬拦截——同系列共享词从任一条添加即可，会自动同步）
    AppAlertDialog(
        show = wordConflicts.isNotEmpty(),
        onDismissRequest = { wordConflicts = emptyList() },
        title = "词被其他系列占用",
        text = buildString {
            append("以下词已被同栏的其他条目使用（跨系列唯一）：\n\n")
            wordConflicts.forEach { (w, owner) ->
                append("・「").append(w).append("」→ ").append(owner).append("\n")
            }
            append("\n请先修改对方条目，或改用别的词；\n同系列共享词只需给其中一条添加，会自动同步到全系列。")
        },
        confirmText = "知道了",
        onConfirm = { wordConflicts = emptyList() },
    )
}

/** B33.3c-附3 · 固有分组选择（音效 / BGM / 环境声；下拉 + 齿轮→音频参数，不含新建） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedGroupSelector(
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onParamsClick: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            AppTextField(
                value = current,
                onValueChange = {},
                readOnly = true,
                label = "分组",
                backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(
                        ExposedDropdownMenuAnchorType.PrimaryEditable,
                        true,
                    ),
            )
            RoundDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { g ->
                    RoundDropdownMenuItem(
                        text = g,
                        onClick = {
                            onSelect(g)
                            expanded = false
                        },
                    )
                }
            }
        }
        MediumPlainButton(
            onClick = onParamsClick,
            icon = Icons.Default.Settings,
            contentDescription = "音频参数",
        )
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
