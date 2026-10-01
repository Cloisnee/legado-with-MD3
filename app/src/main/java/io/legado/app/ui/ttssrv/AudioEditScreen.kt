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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.ui.replace.edit.GroupSelector
import io.legado.app.ui.replace.edit.QuickInputBar
import io.legado.app.ui.replace.edit.keyboardAsState
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.ToggleChip
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

private enum class EditField { Name, Group, Pattern, TagDesc }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioEditScreen(app: Application, assetId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asset by remember { mutableStateOf<AudioLibrary.AudioAsset?>(null) }
    var allGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }

    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf("") }
    var tagDesc by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(true) }
    var scopeTitle by remember { mutableStateOf(false) }
    var scopeContent by remember { mutableStateOf(true) }

    var activeField by remember { mutableStateOf(EditField.Pattern) }
    var showMenu by remember { mutableStateOf(false) }
    var paramsOpen by remember { mutableStateOf(false) }
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
        group = a.group
        pattern = a.pattern
        tagDesc = a.tagDesc
        isRegex = a.isRegex
        scopeTitle = a.scopeTitle
        scopeContent = a.scopeContent
        allGroups = list.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()
        ready = true
    }

    fun save() {
        val base = asset ?: return
        scope.launch {
            runCatching {
                AudioLibrary.updateAsset(
                    app,
                    base.copy(
                        name = name.trim().ifBlank { base.name },
                        group = group.trim(),
                        pattern = pattern.trim(),
                        tagDesc = tagDesc.trim(),
                        isRegex = isRegex,
                        scopeTitle = scopeTitle,
                        scopeContent = scopeContent,
                    ),
                )
            }
            app.toastOnUi("已保存")
            onBack()
        }
    }

    fun copyRule() {
        val json = JSONObject().apply {
            put("name", name)
            put("group", group)
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
        group = o.optString("group", group)
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
                            EditField.Group -> group += text
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
                GroupSelector(
                    currentGroup = group,
                    allGroups = allGroups,
                    onGroupChange = { group = it },
                    onManageClick = { paramsOpen = true },
                    backgroundColor = LegadoTheme.colorScheme.surfaceInput,
                )
                AppTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = "匹配规则",
                    placeholder = { AppText("留空=由库级规则驱动；输入关键词或正则") },
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
