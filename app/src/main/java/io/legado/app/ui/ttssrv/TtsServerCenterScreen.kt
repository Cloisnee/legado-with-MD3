package io.legado.app.ui.ttssrv

import android.app.Application
import android.widget.LinearLayout
import android.content.ClipData
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsPluginUiEngineV2
import io.legado.app.data.repository.AuditionOutcome
import io.legado.app.data.repository.EntryRow
import io.legado.app.data.repository.GroupRow
import io.legado.app.data.repository.LocaleOption
import io.legado.app.data.repository.PluginRow
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.data.repository.VoiceOption
import io.legado.app.data.repository.VarField
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppRadioButton
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.SectionTitle
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.ReorderableSelectionItem
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.ui.widget.components.list.ListUiState
import io.legado.app.ui.widget.components.log.LogDetailSheet
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.rules.RuleListScaffold
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import io.legado.app.ui.widget.components.settingItem.TinySwitchSettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.rememberReorderableLazyListState

private sealed interface CenterSheet {
    data class EntryActions(val entry: EntryRow) : CenterSheet
    data object ManualImport : CenterSheet
}

private sealed interface PickerPurpose {
    data object Audition : PickerPurpose
}

private val NE_MALE_AGES = listOf("男童", "少年", "男青年", "男中年", "男老年")
private val NE_FEMALE_AGES = listOf("女童", "少女", "女青年", "女中年", "女老年")

private data class PickerTarget(
    val pluginId: String,
    val pluginName: String,
    val purpose: PickerPurpose,
)


private class CenterListUiState<T>(
    override val items: List<T>,
    override val selectedIds: Set<Any>,
    override val searchKey: String,
    override val isSearch: Boolean,
    override val isLoading: Boolean = false,
) : ListUiState<T>

@Composable
fun TtsServerCenterRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    TtsServerCenterScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

/**
 * TTS-Server 管理中心（书源管理式复刻版 v2）：
 *  - 顶栏：返回 / 搜索(展开) / 导入(按页面校验) / 切换引擎
 *  - 插件页：可拖动排序，点击选中；行内开关 + 编辑(元信息+变量)
 *  - 配置页：组/二级 默认收起 + 缩进 + 原版箭头；长按任一层级进入多选
 *  - 选中后：左侧 60dp 边缘滑选（复刻书源）+ 底部工具条
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TtsServerCenterScreen(app: Application, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(app) { TtsServerCenterRepository(app) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedTab by remember { mutableStateOf(0) }
    var plugins by remember { mutableStateOf<List<PluginRow>>(emptyList()) }
    var groups by remember { mutableStateOf<List<GroupRow>>(emptyList()) }
    var engineValue by remember { mutableStateOf<String?>(null) }
    var engineLoaded by remember { mutableStateOf(false) }

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
    val collapsedCats = remember { mutableStateMapOf<String, Boolean>() }

    var selPlugins by remember { mutableStateOf(setOf<String>()) }
    var selEntries by remember { mutableStateOf(setOf<String>()) }
    var selGroupNames by remember { mutableStateOf(setOf<String>()) }
    var selCatKeys by remember { mutableStateOf(setOf<String>()) }
    var selGroupContext by remember { mutableStateOf<String?>(null) }
    var activeBanks by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 插件拖动排序
    var dragOrder by remember { mutableStateOf<List<PluginRow>?>(null) }

    var sheet by remember { mutableStateOf<CenterSheet?>(null) }
    var showImportPicker by remember { mutableStateOf(false) }
    var detailTitle by remember { mutableStateOf("") }
    var detailText by remember { mutableStateOf<String?>(null) }

    var showEngineSheet by remember { mutableStateOf(false) }
    var engineList by remember { mutableStateOf<List<HttpTTS>>(emptyList()) }
    var engineEditor by remember { mutableStateOf<HttpTTS?>(null) }
    var isNewEngine by remember { mutableStateOf(false) }
    var engineMenuOpen by remember { mutableStateOf(false) }
    var showNetImport by remember { mutableStateOf(false) }
    var netImportUrl by remember { mutableStateOf("") }
    var showDeleteEngine by remember { mutableStateOf<HttpTTS?>(null) }

    var showDeleteEntry by remember { mutableStateOf<EntryRow?>(null) }
    var renameTarget by remember { mutableStateOf<EntryRow?>(null) }

    var importText by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var varsEditorPlugin by remember { mutableStateOf<PluginRow?>(null) }
    var varFields by remember { mutableStateOf<List<VarField>>(emptyList()) }
    val varValues = remember { mutableStateMapOf<String, String>() }
    var vsName by remember { mutableStateOf("") }
    var vsId by remember { mutableStateOf("") }
    var vsAuthor by remember { mutableStateOf("") }
    var vsVersion by remember { mutableStateOf("") }
    var vsIcon by remember { mutableStateOf("") }
    var vsDesc by remember { mutableStateOf("") }

    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    var pickerLocales by remember { mutableStateOf<List<LocaleOption>>(emptyList()) }
    var pickerVoices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var pickerLocale by remember { mutableStateOf<LocaleOption?>(null) }
    var pickerBusy by remember { mutableStateOf(false) }
    var newEntryGroupDefault by remember { mutableStateOf<String?>(null) }
    var neStep2 by remember { mutableStateOf(false) }
    var neGroup by remember { mutableStateOf("") }
    var nePluginId by remember { mutableStateOf("") }
    var nePluginName by remember { mutableStateOf("") }
    var neRole by remember { mutableStateOf("核心") }
    var neGender by remember { mutableStateOf("男") }
    var neAge by remember { mutableStateOf("男青年") }
    var neSpeed by remember { mutableStateOf(1f) }
    var neVolume by remember { mutableStateOf(1f) }
    var nePitch by remember { mutableStateOf(1f) }
    var neAuditionText by remember { mutableStateOf("你好，这是一段试听语音。") }
    var neLocale by remember { mutableStateOf("zh-CN") }
    var neVoiceSel by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var neLocales by remember { mutableStateOf<List<LocaleOption>>(emptyList()) }
    var pluginPickerSheet by remember { mutableStateOf(false) }
    var voicePickerSheet by remember { mutableStateOf(false) }
    var voiceQuery by remember { mutableStateOf("") }
    var voiceList by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var grpDlg by remember { mutableStateOf(false) }
    var grpInput by remember { mutableStateOf("") }
    var neCat2 by remember { mutableStateOf("") }
    var grp2Dlg by remember { mutableStateOf(false) }
    var grp2Input by remember { mutableStateOf("") }
    var pluginUiEngine by remember { mutableStateOf<TtsPluginUiEngineV2?>(null) }
    var pluginUiLayout by remember { mutableStateOf<LinearLayout?>(null) }
    var pluginUiEmpty by remember { mutableStateOf(true) }
    var pluginTempSource by remember { mutableStateOf<PluginTtsSource?>(null) }
    var neTextDlg by remember { mutableStateOf(false) }
    var neTextInput by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<EntryRow?>(null) }
    var edName by remember { mutableStateOf("") }
    var edTag by remember { mutableStateOf("") }
    var edCategory by remember { mutableStateOf("") }
    var edGroup by remember { mutableStateOf("") }
    var edSpeed by remember { mutableStateOf(1f) }
    var edVolume by remember { mutableStateOf(1f) }
    var edPitch by remember { mutableStateOf(1f) }
    var edFieldDlg by remember { mutableStateOf<String?>(null) }
    var edFieldInput by remember { mutableStateOf("") }
    var edUiEngine by remember { mutableStateOf<TtsPluginUiEngineV2?>(null) }
    var edUiLayout by remember { mutableStateOf<LinearLayout?>(null) }
    var edUiEmpty by remember { mutableStateOf(true) }
    var edTempSource by remember { mutableStateOf<PluginTtsSource?>(null) }
    var varDlgKey by remember { mutableStateOf<String?>(null) }
    var varDlgInput by remember { mutableStateOf("") }
    var renameGroupTarget by remember { mutableStateOf<String?>(null) }
    var renameGroupText by remember { mutableStateOf("") }

    val player = remember { MediaPlayer() }
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    fun reload() {
        scope.launch {
            plugins = repo.loadPlugins()
            groups = repo.loadGroups()
            engineValue = repo.currentEngineValue()
            engineList = repo.loadHttpTtsList()
            engineLoaded = true
        }
    }

    fun groupKeyOf(g: GroupRow): String = g.groupId.toString()

    fun groupNameOfKey(key: String): String =
        groups.firstOrNull { groupKeyOf(it) == key }?.name ?: key

    fun entryKeyOf(e: EntryRow): String = when {
        e.groupId != 0L && e.id != 0L -> "g${e.groupId}_e${e.id}"
        e.id != 0L -> "e_${e.id}"
        else -> "k_${e.tagRuleId}|${e.tag}"
    }

    fun uniqueEntryList(src: List<EntryRow>): List<EntryRow> {
        val seen = HashSet<String>()
        return src.filter { seen.add(entryKeyOf(it)) }
    }

    fun pluginNameOf(id: String): String =
        plugins.firstOrNull { it.pluginId == id }?.name ?: id

    fun matchedEntries(): List<EntryRow> {
        val all = groups.flatMap { it.entries }
        if (query.isBlank()) return all
        return all.filter {
            it.displayName.contains(query, true) || it.tag.contains(query, true) ||
                    it.voice.contains(query, true) || pluginNameOf(it.pluginId).contains(query, true)
        }
    }

    fun displayedPlugins(): List<PluginRow> {
        if (query.isBlank()) return plugins
        return plugins.filter {
            it.name.contains(query, true) || it.author.contains(query, true) ||
                    it.pluginId.contains(query, true)
        }
    }

    /** 选择集变化后，由 selEntries 重新推导 组/分类 级别标记（反选/全选后勾子随动） */
    fun normalizeSelection() {
        val gSet = LinkedHashSet<String>()
        groups.forEach { g ->
            val keys = g.entries.map { entryKeyOf(it) }
            if (keys.isNotEmpty() && keys.all { it in selEntries }) gSet.add(groupKeyOf(g))
        }
        selGroupNames = gSet
        val cSet = LinkedHashSet<String>()
        groups.forEach { g ->
            g.entries.groupBy { it.categoryPath.ifBlank { "未分类" } }.forEach { (cat, list) ->
                val keys = list.map { entryKeyOf(it) }
                if (keys.isNotEmpty() && keys.all { it in selEntries }) cSet.add("${groupKeyOf(g)}|$cat")
            }
        }
        selCatKeys = cSet
    }

    fun play(path: String) {
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.prepare()
            player.start()
        }.onFailure {
            context.toastOnUi("播放失败：${it.message.orEmpty()}")
        }
    }

    fun handleAuditionResult(r: AuditionOutcome) {
        if (r.ok && r.path != null) {
            play(r.path)
            context.toastOnUi("播放中")
        } else {
            detailTitle = "试听失败（诊断报告）"
            detailText = r.message
        }
    }

    /** 条目 source.data 的统一取值：插件特色UI存在时以插件写入为准 */
    fun effectiveDataParams(): Map<String, String> {
        val src = pluginTempSource
        return if (src != null && !pluginUiEmpty && src.data.isNotEmpty()) src.data else emptyMap()
    }

    fun auditionEntry(e: EntryRow) {
        scope.launch {
            context.toastOnUi("正在合成…")
            val r = if (e.id != 0L) {
                repo.auditionByEntry(e.groupId, e.id, "你好，这里是移植层试听。")
            } else {
                repo.auditionDetailed(e.tagRuleId, e.tag, "你好，这里是移植层试听。")
            }
            handleAuditionResult(r)
        }
    }

        fun openPluginEditor(p: PluginRow) {
        scope.launch {
            val fields = repo.loadVarFields(p.pluginId)
            varValues.clear()
            fields.forEach { varValues[it.key] = it.value }
            varFields = fields
            vsName = p.name
            vsId = p.pluginId
            vsAuthor = p.author
            vsVersion = p.version.toString()
            val shell = repo.loadPluginShell(p.pluginId)
            vsIcon = shell?.optString("iconUrl").orEmpty()
            vsDesc = shell?.optString("description").orEmpty()
            varsEditorPlugin = p
        }
    }

    fun switchTab(t: Int) {
        selectedTab = t
        query = ""
        searchMode = false
        selPlugins = emptySet()
        selEntries = emptySet()
        selGroupNames = emptySet()
        selCatKeys = emptySet()
        selGroupContext = null
    }

    LaunchedEffect(Unit) {
        reload()
        activeBanks = repo.getActiveVoiceBanks().toSet()
    }

    // 已选中池清理：配置列表中被删除的分组不再残留（readaloud_ext.json 同步）
    LaunchedEffect(groups, activeBanks) {
        if (groups.isEmpty() || activeBanks.isEmpty()) return@LaunchedEffect
        val valid = groups.map { it.name }.toSet()
        val pruned = activeBanks.filter { it in valid }.toSet()
        if (pruned != activeBanks) {
            repo.setActiveVoiceBanks(pruned.toList())
            activeBanks = pruned
        }
    }

    // 新建条目：进入第二步时初始化分组默认值（插件已在第一步选定）
    LaunchedEffect(neStep2) {
        if (neStep2 && neGroup.isBlank()) {
            neGroup = newEntryGroupDefault ?: groups.firstOrNull()?.name ?: "自建"
        }
    }

    // 新建条目：插件变化 → 语言列表
    LaunchedEffect(nePluginId) {
        if (nePluginId.isBlank()) return@LaunchedEffect
        neLocales = repo.loadLocales(nePluginId).ifEmpty {
            listOf(
                LocaleOption("zh-CN", "中文(简体)"),
                LocaleOption("zh-TW", "中文(繁体)"),
                LocaleOption("en-US", "English"),
                LocaleOption("ja-JP", "日本語"),
            )
        }
        if (neLocales.none { it.id == neLocale }) {
            neLocales.firstOrNull()?.let { neLocale = it.id }
        }
    }

    // 新建条目：插件 UI 会话（补丁版添加插件TTS同款：onLoadData → onLoadUI 到真实容器）
    LaunchedEffect(nePluginId) {
        pluginUiEngine = null
        pluginUiLayout = null
        pluginUiEmpty = true
        pluginTempSource = null
        if (nePluginId.isBlank()) return@LaunchedEffect
        val pair = repo.createPluginUiSession(nePluginId)
        if (pair != null) {
            val (engine, source) = pair
            val layout = withContext(Dispatchers.Main) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    runCatching { engine.onLoadUI(context, this) }
                }
            }
            pluginUiEngine = engine
            pluginTempSource = source
            pluginUiLayout = layout
            pluginUiEmpty = layout.childCount == 0
        }
    }

    // 语言/声音变化 → 通知插件自定义UI
    LaunchedEffect(neLocale, neVoiceSel) {
        val engine = pluginUiEngine ?: return@LaunchedEffect
        runCatching { engine.onVoiceChanged(neLocale, neVoiceSel.firstOrNull()?.id ?: "") }
    }

    // 编辑条目：插件 UI 会话（带原 source 种子）
    LaunchedEffect(editTarget) {
        edUiEngine = null
        edUiLayout = null
        edUiEmpty = true
        edTempSource = null
        val t = editTarget ?: return@LaunchedEffect
        if (t.pluginId.isBlank()) return@LaunchedEffect
        val seed = PluginTtsSource(
            locale = t.locale,
            voice = t.voice,
            pluginId = t.pluginId,
            speed = t.speed,
            volume = t.volume,
            pitch = t.pitch,
            data = t.sourceData.toMutableMap(),
        )
        val pair = repo.createPluginUiSession(t.pluginId, seed)
        if (pair != null) {
            val (engine, source) = pair
            val layout = withContext(Dispatchers.Main) {
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    runCatching { engine.onLoadUI(context, this) }
                }
            }
            edUiEngine = engine
            edTempSource = source
            edUiLayout = layout
            edUiEmpty = layout.childCount == 0
            runCatching { engine.onVoiceChanged(t.locale, t.voice) }
        }
    }

    // 声音列表加载
    LaunchedEffect(voicePickerSheet, nePluginId, neLocale) {
        if (voicePickerSheet && nePluginId.isNotBlank()) {
            voiceList = repo.loadVoices(nePluginId, neLocale)
        }
    }

    // 组默认收起（只对首次出现的组设置）
    LaunchedEffect(groups) {
        groups.forEach { g ->
            if (!collapsedGroups.containsKey(groupKeyOf(g))) {
                collapsedGroups[groupKeyOf(g)] = true
            }
        }
    }

    // 插件拖动排序状态
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val base = dragOrder ?: displayedPlugins()
        val moved = base.toMutableList()
        if (from.index in moved.indices && to.index in moved.indices) {
            moved.add(to.index, moved.removeAt(from.index))
            dragOrder = moved
        }
    }
    LaunchedEffect(reorderState.isAnyItemDragging, plugins) {
        if (!reorderState.isAnyItemDragging) {
            dragOrder?.let { pending ->
                val ids = pending.map { it.pluginId }
                if (plugins.map { it.pluginId } == ids) {
                    dragOrder = null
                } else {
                    scope.launch {
                        repo.setPluginsOrder(ids)
                        dragOrder = null
                        reload()
                    }
                }
            }
        }
    }

    LaunchedEffect(pickerTarget) {
        val t = pickerTarget ?: return@LaunchedEffect
        pickerBusy = true
        pickerLocales = repo.loadLocales(t.pluginId)
        pickerVoices = emptyList()
        pickerLocale = null
        pickerBusy = false
        if (pickerLocales.isEmpty()) {
            detailTitle = "读取语言列表失败"
            detailText = "插件「${t.pluginName}」未返回语言列表（检查插件是否启用 / 是否被沙箱拦截）。"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    context.toastOnUi("读取文件为空或失败")
                } else {
                    val msg =
                        if (selectedTab == 0) repo.importPlugins(text) else repo.importVoices(text)
                    context.toastOnUi(msg)
                    reload()
                }
            }
        }
    }

    val engineImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                    }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    context.toastOnUi("读取文件为空或失败")
                } else {
                    context.toastOnUi(repo.importHttpTts(text))
                    reload()
                }
            }
        }
    }

    val engineExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = repo.exportHttpTtsJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    }
                }
                context.toastOnUi("导出成功")
            }
        }
    }

    val engineSubtitle =
        if (!engineLoaded) "当前引擎：…" else "当前引擎：${repo.engineLabel(engineValue)}"

    val pluginSelActive = selPlugins.isNotEmpty()
    val entrySelActive = selEntries.isNotEmpty()

    val uiState: ListUiState<Any> = if (selectedTab == 0) {
        CenterListUiState(
            items = (dragOrder ?: displayedPlugins()).map { it as Any },
            selectedIds = selPlugins,
            searchKey = query,
            isSearch = searchMode,
        )
    } else {
        CenterListUiState(
            items = groups.flatMap { it.entries }.map { it as Any },
            selectedIds = selEntries,
            searchKey = query,
            isSearch = searchMode,
        )
    }

    val secondaryActions: List<ActionItem> = if (selectedTab == 0) {
        buildList {
            add(ActionItem("导出所选") { scope.launch {
                detailTitle = "导出完成（所选插件）"
                detailText = repo.exportPluginsByIds(selPlugins)
            } })
            add(ActionItem("启用所选") { scope.launch {
                repo.setPluginsEnabled(selPlugins, true)
                context.toastOnUi("已启用")
                reload()
            } })
            add(ActionItem("停用所选") { scope.launch {
                repo.setPluginsEnabled(selPlugins, false)
                context.toastOnUi("已停用")
                reload()
            } })
            add(ActionItem("置顶") { scope.launch {
                repo.movePluginsToEdge(selPlugins, true)
                reload()
            } })
            add(ActionItem("置底") { scope.launch {
                repo.movePluginsToEdge(selPlugins, false)
                reload()
            } })
            if (selPlugins.size == 1) {
                val pid = selPlugins.first()
                add(ActionItem("试听（选语言）") {
                    pickerTarget = PickerTarget(pid, pluginNameOf(pid), PickerPurpose.Audition)
                })
            }
        }
    } else {
        buildList {
            add(ActionItem("导出所选") { scope.launch {
                detailTitle = "导出完成（所选条目）"
                detailText = repo.exportEntries(selEntries)
            } })
            add(ActionItem("置顶") { scope.launch {
                when {
                    selGroupNames.isNotEmpty() ->
                        repo.moveGroupsToEdge(selGroupNames.mapNotNull { it.toLongOrNull() }.toSet(), true)
                    selCatKeys.isNotEmpty() -> repo.moveCategoriesToEdge(selCatKeys, true)
                    else -> repo.moveEntriesToEdge(selEntries, true)
                }
                reload()
            } })
            add(ActionItem("置底") { scope.launch {
                when {
                    selGroupNames.isNotEmpty() ->
                        repo.moveGroupsToEdge(selGroupNames.mapNotNull { it.toLongOrNull() }.toSet(), false)
                    selCatKeys.isNotEmpty() -> repo.moveCategoriesToEdge(selCatKeys, false)
                    else -> repo.moveEntriesToEdge(selEntries, false)
                }
                reload()
            } })
            val g = selGroupContext
            if (g != null) {
                add(ActionItem("重命名分组") {
                    renameGroupText = groupNameOfKey(g)
                    renameGroupTarget = g
                })
                add(ActionItem("导出分组") { scope.launch {
                    detailTitle = "导出完成（分组）"
                    detailText = repo.exportGroup(g.toLongOrNull() ?: return@launch)
                } })
                add(ActionItem("在此组新建条目") {
                    newEntryGroupDefault = groupNameOfKey(g)
                    neGroup = groupNameOfKey(g)
                    pluginPickerSheet = true
                })
            }
        }
    }

    RuleListScaffold(
        title = stringResource(R.string.read_aloud_engines_and_voices),
        state = uiState,
        subtitle = engineSubtitle,
        onBackClick = onBack,
        onSearchToggle = { searchMode = it; if (!it) query = "" },
        onSearchQueryChange = { query = it },
        searchPlaceholder = if (selectedTab == 0) {
            "搜索插件：名称 / 作者 / ID"
        } else {
            "搜索：名称 / 标签 / 音色 / 插件名"
        },
        topBarActions = {
            TopBarActionButton(
                onClick = { showImportPicker = true },
                imageVector = Icons.Default.FileDownload,
                contentDescription = "导入",
            )
            TopBarActionButton(
                onClick = { showEngineSheet = true },
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "切换朗读引擎",
            )
        },
        bottomContent = { _ ->
            AppTabRow(
                tabTitles = listOf("音色插件", "配置列表"),
                selectedTabIndex = selectedTab,
                onTabSelected = { t -> switchTab(t) },
                isScrollable = false,
            )
        },
        onClearSelection = {
            selPlugins = emptySet()
            selEntries = emptySet()
            selGroupNames = emptySet()
            selCatKeys = emptySet()
            selGroupContext = null
        },
        onSelectAll = {
            if (selectedTab == 0) {
                selPlugins = (dragOrder ?: displayedPlugins()).map { it.pluginId }.toSet()
            } else {
                selEntries = matchedEntries().map { entryKeyOf(it) }.toSet()
                normalizeSelection()
            }
        },
        onSelectInvert = {
            if (selectedTab == 0) {
                val disp = (dragOrder ?: displayedPlugins()).map { it.pluginId }.toSet()
                selPlugins = disp - selPlugins
            } else {
                val disp = matchedEntries().map { entryKeyOf(it) }.toSet()
                selEntries = disp - selEntries
                normalizeSelection()
            }
        },
        selectionSecondaryActions = secondaryActions,
        onDeleteSelected = { ids ->
            val set = ids.filterIsInstance<String>().toSet()
            if (selectedTab == 0) {
                scope.launch {
                    repo.deletePlugins(set)
                    selPlugins = emptySet()
                    reload()
                }
            } else {
                scope.launch {
                    repo.deleteEntries(set)
                    selEntries = emptySet()
                    selGroupNames = emptySet()
                    selCatKeys = emptySet()
                    selGroupContext = null
                    reload()
                }
            }
        },
        onAddClick = if (selectedTab == 1) {
            {
                newEntryGroupDefault = null
                pluginPickerSheet = true
            }
        } else null,
        snackbarHostState = remember { SnackbarHostState() },
    ) { padding ->
        val swipeThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
        var swipeAccum by remember { mutableStateOf(0f) }
        val swipeState = rememberDraggableState { delta -> swipeAccum += delta }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = swipeState,
                    onDragStarted = { swipeAccum = 0f },
                    onDragStopped = {
                        when {
                            swipeAccum <= -swipeThresholdPx && selectedTab == 0 -> switchTab(1)
                            swipeAccum >= swipeThresholdPx && selectedTab == 1 -> switchTab(0)
                        }
                        swipeAccum = 0f
                    },
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = adaptiveContentPadding(
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 120.dp,
                ),
            ) {
                if (selectedTab == 0) {
                    val pluginList = dragOrder ?: displayedPlugins()
                    if (plugins.isEmpty()) {
                        item {
                            SplicedColumnGroup(title = "音色插件") {
                                TinyClickableSettingItem(
                                    title = "暂无插件",
                                    description = "点右上角「导入」添加音色插件 JSON",
                                    onClick = {},
                                )
                            }
                        }
                    }
                    itemsIndexed(pluginList, key = { _, p -> p.pluginId }) { index, p ->
                        ReorderableSelectionItem(
                            state = reorderState,
                            key = p.pluginId,
                            title = p.name,
                            subtitle = "${p.pluginId} · v${p.version} · ${p.author}",
                            isEnabled = p.enabled,
                            onEnabledChange = { enabled ->
                                scope.launch {
                                    repo.setPluginEnabled(p.pluginId, enabled)
                                    reload()
                                }
                            },
                            isSelected = p.pluginId in selPlugins,
                            inSelectionMode = pluginSelActive,
                            onToggleSelection = {
                                if (pluginSelActive) {
                                    selPlugins = if (p.pluginId in selPlugins) {
                                        selPlugins - p.pluginId
                                    } else {
                                        selPlugins + p.pluginId
                                    }
                                } else {
                                    selPlugins = setOf(p.pluginId)
                                }
                            },
                            onClickEdit = { openPluginEditor(p) },
                            canReorder = query.isBlank(),
                            reorderIndex = index,
                            reorderItemCount = pluginList.size,
                            onMoveItem = { from, to ->
                                val moved = pluginList.toMutableList()
                                if (from in moved.indices && to in moved.indices) {
                                    moved.add(to, moved.removeAt(from))
                                    scope.launch {
                                        repo.setPluginsOrder(moved.map { it.pluginId })
                                        dragOrder = null
                                        reload()
                                    }
                                }
                            },
                        )
                    }
                } else {
                    val missingIds = groups.flatMap { it.entries }.map { it.pluginId }
                        .filter { it.isNotBlank() }.toSet() -
                            plugins.map { it.pluginId }.toSet()
                    if (missingIds.isNotEmpty()) {
                        item {
                            SplicedColumnGroup(title = "⚠ 缺失插件") {
                                TinyClickableSettingItem(
                                    title = "有 ${missingIds.size} 个插件未安装",
                                    description = missingIds.joinToString() +
                                            "（相关声线合成会失败；导入对应插件即可）",
                                    onClick = {},
                                )
                            }
                        }
                    }
                    if (groups.isEmpty()) {
                        item {
                            SplicedColumnGroup(title = "配置列表") {
                                TinyClickableSettingItem(
                                    title = "暂无配置列表",
                                    description = "点右上角「导入」，或右下角 + 新建条目",
                                    onClick = {},
                                )
                            }
                        }
                    }
                    if (query.isNotBlank()) {
                        val filtered = uniqueEntryList(matchedEntries())
                        item { SectionTitle("搜索结果（${filtered.size}）") }
                        items(filtered, key = { entryKeyOf(it) }) { e ->
                            LevelRow(
                                title = "[${e.tag}] ${e.displayName}",
                                subtitle = "${e.voice} · ${pluginNameOf(e.pluginId)}",
                                arrowExpanded = null,
                                indent = 16.dp,
                                selActive = entrySelActive,
                                selected = entryKeyOf(e) in selEntries,
                                onClick = {
                                    if (entrySelActive) {
                                        val k = entryKeyOf(e)
                                        selEntries = if (k in selEntries) selEntries - k
                                        else selEntries + k
                                    } else {
                                        sheet = CenterSheet.EntryActions(e)
                                    }
                                },
                                onLongClick = {
                                    if (!entrySelActive) {
                                        selEntries = setOf(entryKeyOf(e))
                                    }
                                },
                                trailing = {
                                    SmallPlainButton(
                                        icon = Icons.Default.PlayArrow,
                                        contentDescription = "试听",
                                        onClick = { auditionEntry(e) },
                                    )
                                },
                            )
                        }
                    } else {
                        groups.forEachIndexed { gi, g ->
                            val groupKeys = g.entries.map { entryKeyOf(it) }
                            val groupAllSel =
                                g.entries.isNotEmpty() && groupKeys.all { it in selEntries }
                            item(key = "g_${gi}_${g.name}") {
                                val gSel = groupKeyOf(g) in selGroupNames
                                LevelRow(
                                    title = g.name,
                                    subtitle = "共 ${g.entries.size} 条",
                                    arrowExpanded = collapsedGroups[groupKeyOf(g)] != true,
                                    indent = 0.dp,
                                    selActive = entrySelActive,
                                    selected = gSel,
                                    trailing = {
                                        BankTag(
                                            active = g.name in activeBanks,
                                            onClick = {
                                                scope.launch {
                                                    val next = if (g.name in activeBanks) {
                                                        activeBanks - g.name
                                                    } else {
                                                        activeBanks + g.name
                                                    }
                                                    repo.setActiveVoiceBanks(next.toList())
                                                    activeBanks = next
                                                }
                                            },
                                        )
                                    },
                                    onClick = {
                                        if (entrySelActive) {
                                            if (gSel) {
                                                selEntries = selEntries - groupKeys.toSet()
                                            } else {
                                                selEntries = selEntries + groupKeys.toSet()
                                                selGroupContext = groupKeyOf(g)
                                            }
                                            normalizeSelection()
                                        } else {
                                            collapsedGroups[groupKeyOf(g)] =
                                                !(collapsedGroups[groupKeyOf(g)] ?: false)
                                        }
                                    },
                                    onLongClick = {
                                        if (!entrySelActive) {
                                            selEntries = groupKeys.toSet()
                                            selGroupContext = groupKeyOf(g)
                                            normalizeSelection()
                                        }
                                    },
                                )
                            }
                            if (collapsedGroups[groupKeyOf(g)] != true) {
                                val byCat =
                                    g.entries.groupBy { it.categoryPath.ifBlank { "未分类" } }
                                byCat.forEach { (cat, list) ->
                                    val catKey = "${groupKeyOf(g)}|$cat"
                                    val fresh = list
                                    val catKeys = fresh.map { entryKeyOf(it) }
                                    val catAllSel =
                                        fresh.isNotEmpty() && catKeys.all { it in selEntries }
                                    item(key = "c_${gi}_$catKey") {
                                        val cSel = catKey in selCatKeys
                                        LevelRow(
                                            title = cat,
                                            subtitle = "共 ${fresh.size} 条",
                                            arrowExpanded = collapsedCats[catKey] != true,
                                            indent = 16.dp,
                                            selActive = entrySelActive,
                                            selected = cSel,
                                            onClick = {
                                                if (entrySelActive) {
                                                    if (cSel) {
                                                        selEntries = selEntries - catKeys.toSet()
                                                    } else {
                                                        selEntries = selEntries + catKeys.toSet()
                                                        selGroupContext = groupKeyOf(g)
                                                    }
                                                    normalizeSelection()
                                                } else {
                                                    collapsedCats[catKey] =
                                                        !(collapsedCats[catKey] ?: false)
                                                }
                                            },
                                            onLongClick = {
                                                if (!entrySelActive) {
                                                    selEntries = catKeys.toSet()
                                                    selGroupContext = groupKeyOf(g)
                                                    normalizeSelection()
                                                }
                                            },
                                        )
                                    }
                                    if (collapsedCats[catKey] != true) {
                                        items(fresh, key = { entryKeyOf(it) }) { e ->
                                            LevelRow(
                                                title = "[${e.tag}] ${e.displayName}",
                                                subtitle = "${e.voice} · ${pluginNameOf(e.pluginId)}",
                                                arrowExpanded = null,
                                                indent = 16.dp,
                                                selActive = entrySelActive,
                                                selected = entryKeyOf(e) in selEntries,
                                                onClick = {
                                                    if (entrySelActive) {
                                                        val k = entryKeyOf(e)
                                                        selEntries =
                                                            if (k in selEntries) selEntries - k
                                                            else selEntries + k
                                                        normalizeSelection()
                                                    } else {
                                                        sheet = CenterSheet.EntryActions(e)
                                                    }
                                                },
                                                onLongClick = {
                                                    if (!entrySelActive) {
                                                        selEntries = setOf(entryKeyOf(e))
                                                        normalizeSelection()
                                                    }
                                                },
                                                trailing = {
                                                    SmallPlainButton(
                                                        icon = Icons.Default.PlayArrow,
                                                        contentDescription = "试听",
                                                        onClick = { auditionEntry(e) },
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 边缘滑动选取（复刻书源管理的左侧 60dp 拖选条）
            if (selectedTab == 0 && pluginSelActive) {
                val pluginList = dragOrder ?: displayedPlugins()
                DraggableSelectionHandler(
                    listState = listState,
                    items = pluginList,
                    selectedIds = selPlugins,
                    onSelectionChange = { selPlugins = it },
                    idProvider = { it.pluginId },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            } else if (selectedTab == 1 && entrySelActive) {
                val flat = uniqueEntryList(groups.flatMap { it.entries })
                DraggableSelectionHandler(
                    listState = listState,
                    items = flat,
                    selectedIds = selEntries,
                    onSelectionChange = { sel ->
                        selEntries = sel.filter { it.contains("|") }.toSet()
                    },
                    idProvider = { entryKeyOf(it) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }
    }

    // ---------------- 导入选择 ----------------
    FilePickerSheet(
        show = showImportPicker,
        onDismissRequest = { showImportPicker = false },
        title = if (selectedTab == 0) "导入音色插件" else "导入配置列表",
        onSelectSysFile = { types ->
            showImportPicker = false
            importLauncher.launch(types)
        },
        onManualInput = {
            showImportPicker = false
            sheet = CenterSheet.ManualImport
        },
        allowExtensions = arrayOf("json", "txt"),
    )

    // ---------------- 手动粘贴 ----------------
    AppModalBottomSheet(
        show = sheet is CenterSheet.ManualImport,
        onDismissRequest = { sheet = null },
        title = if (selectedTab == 0) "粘贴插件 JSON" else "粘贴配置列表 JSON",
    ) {
        AppTextField(
            value = importText,
            onValueChange = { importText = it },
            modifier = Modifier.fillMaxWidth(),
            label = "粘贴 JSON 内容",
            minLines = 6,
            maxLines = 12,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TinyClickableSettingItem(
            title = "从剪贴板粘贴",
            onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                if (t.isNullOrBlank()) {
                    context.toastOnUi("剪贴板为空")
                } else {
                    importText = t
                    context.toastOnUi("已粘贴 ${t.length} 字符")
                }
            },
        )
        TinyClickableSettingItem(
            title = "确认导入",
            onClick = {
                val text = importText
                sheet = null
                importText = ""
                if (text.isBlank()) {
                    context.toastOnUi("内容为空")
                } else {
                    scope.launch {
                        val msg =
                            if (selectedTab == 0) repo.importPlugins(text) else repo.importVoices(text)
                        context.toastOnUi(msg)
                        reload()
                    }
                }
            },
        )
        TinyClickableSettingItem(title = "取消", onClick = { sheet = null })
    }

    // ---------------- 声线选择（试听 / 新建共用） ----------------
    AppModalBottomSheet(
        show = pickerTarget != null,
        onDismissRequest = { pickerTarget = null },
        title = pickerTarget?.let { "试听：" + it.pluginName } ?: "",
    ) {
        val t = pickerTarget
        if (t != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (pickerBusy) {
                    TinyClickableSettingItem(title = "加载中…", onClick = {})
                } else if (pickerLocale == null) {
                    if (pickerLocales.isEmpty()) {
                        TinyClickableSettingItem(title = "无可用语言", onClick = {})
                    }
                    pickerLocales.forEach { loc ->
                        TinyClickableSettingItem(
                            title = loc.name.ifBlank { loc.id },
                            description = loc.id,
                            onClick = {
                                scope.launch {
                                    pickerBusy = true
                                    pickerVoices = repo.loadVoices(t.pluginId, loc.id)
                                    pickerLocale = loc
                                    pickerBusy = false
                                    if (pickerVoices.isEmpty()) {
                                        context.toastOnUi("该语言下未读取到音色")
                                    }
                                }
                            },
                        )
                    }
                } else {
                    TinyClickableSettingItem(
                        title = "← 返回语言列表",
                        onClick = {
                            pickerLocale = null
                            pickerVoices = emptyList()
                        },
                    )
                    if (pickerVoices.isEmpty()) {
                        TinyClickableSettingItem(title = "无可用音色", onClick = {})
                    }
                    pickerVoices.forEach { v ->
                        TinyClickableSettingItem(
                            title = v.name.ifBlank { v.id },
                            description = v.id,
                            onClick = {
                                val loc = pickerLocale ?: return@TinyClickableSettingItem
                                when (t.purpose) {
                                    PickerPurpose.Audition -> {
                                        pickerTarget = null
                                        scope.launch {
                                            context.toastOnUi("正在合成…")
                                            val r = repo.auditionDirect(
                                                t.pluginId, loc.id, v.id, "你好，这是插件试听。"
                                            )
                                            handleAuditionResult(r)
                                        }
                                    }

                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ---------------- 新建条目 · 第二步（音色分组 → 基础信息 → 插件特色界面） ----------------
    AppModalBottomSheet(
        show = neStep2,
        onDismissRequest = { neStep2 = false },
        title = "新建条目 · ${nePluginName}",
        endAction = {
            MediumTonalButton(
                onClick = {
                    when {
                        neGroup.isBlank() -> context.toastOnUi("请填写分组名")
                        nePluginId.isBlank() -> context.toastOnUi("请选择插件")
                        neVoiceSel.isEmpty() -> context.toastOnUi("请选择至少一个声音")
                        else -> scope.launch {
                            val (ok, msg) = repo.createEntriesFromPlugin(
                                groupName = neGroup.trim(),
                                roleType = neRole,
                                gender = neGender,
                                age = if (neRole == "特殊") "系统" else neAge,
                                speed = neSpeed,
                                volume = neVolume,
                                pitch = nePitch,
                                sampleRate = 24000,
                                locale = neLocale,
                                categoryPath = neCat2.trim(),
                                pluginId = nePluginId,
                                pluginVoiceIds = neVoiceSel.map { it.id },
                                pluginVoiceNames = neVoiceSel.map { it.name },
                                dataParams = effectiveDataParams(),
                            )
                            context.toastOnUi(msg)
                            if (ok) {
                                neStep2 = false
                                neVoiceSel = emptyList()
                                neGroup = ""
                                neCat2 = ""
                                reload()
                            }
                        }
                    }
                },
                icon = Icons.Default.Check,
                contentDescription = "保存",
            )
        },
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            SectionHint("— 音色分组 —")
            SplicedColumnGroup {
                TinyClickableSettingItem(
                    title = "分组名",
                    description = neGroup.ifBlank { "点按输入（可自定义）" },
                    onClick = {
                        grpInput = neGroup
                        grpDlg = true
                    },
                )
                TinyClickableSettingItem(
                    title = "二级分组名（可选）",
                    description = if (neCat2.isBlank()) "未填 → 直接放一级分组下" else neCat2,
                    onClick = {
                        grp2Input = neCat2
                        grp2Dlg = true
                    },
                )
                TinyDropdownSettingItem(
                    title = "类型",
                    selectedValue = neRole,
                    displayEntries = arrayOf("核心", "特殊", "路人"),
                    entryValues = arrayOf("核心", "特殊", "路人"),
                    onValueChange = {
                        neRole = it
                        if (it == "特殊") {
                            neAge = "系统"
                        } else if (neAge == "系统") {
                            neAge = if (neGender == "女") "女青年" else "男青年"
                        }
                    },
                )
                TinyDropdownSettingItem(
                    title = "性别",
                    selectedValue = neGender,
                    displayEntries = arrayOf("男", "女"),
                    entryValues = arrayOf("男", "女"),
                    onValueChange = { g ->
                        neGender = g
                        val ages = if (g == "女") NE_FEMALE_AGES else NE_MALE_AGES
                        if (neRole != "特殊" && neAge !in ages) {
                            neAge = if (g == "女") "女青年" else "男青年"
                        }
                    },
                )
                TinyDropdownSettingItem(
                    title = "年龄",
                    selectedValue = neAge,
                    displayEntries = (if (neRole == "特殊") listOf("系统") else {
                        if (neGender == "女") NE_FEMALE_AGES else NE_MALE_AGES
                    }).toTypedArray(),
                    entryValues = (if (neRole == "特殊") listOf("系统") else {
                        if (neGender == "女") NE_FEMALE_AGES else NE_MALE_AGES
                    }).toTypedArray(),
                    onValueChange = { neAge = it },
                )
            }
            SectionHint("— 音色基础信息 —")
            SplicedColumnGroup {
                TinyDropdownSettingItem(
                    title = "语言",
                    selectedValue = neLocale,
                    displayEntries = neLocales.map { it.name }.toTypedArray(),
                    entryValues = neLocales.map { it.id }.toTypedArray(),
                    onValueChange = {
                        neLocale = it
                        neVoiceSel = emptyList()
                    },
                )
                TinyClickableSettingItem(
                    title = "声音",
                    description = if (neVoiceSel.isEmpty()) {
                        "未选择（点按选择：可多选 + 试听）"
                    } else {
                        "已选 ${neVoiceSel.size} 个：" + neVoiceSel.joinToString("、") { it.name }
                    },
                    onClick = {
                        voiceQuery = ""
                        voicePickerSheet = true
                    },
                )
                TinyClickableSettingItem(
                    title = "试听文本",
                    description = neAuditionText,
                    onClick = {
                        neTextInput = neAuditionText
                        neTextDlg = true
                    },
                )
                TinySliderSettingItem(
                    title = "语速",
                    value = neSpeed,
                    valueRange = 0f..2f,
                    stepSize = 0.05f,
                    showDecimal = true,
                    valueFormat = { "%.2f×".format(it) },
                    description = "1.00 = 原速（插件引擎倍率）",
                    onValueChange = { neSpeed = it },
                )
                TinySliderSettingItem(
                    title = "音量",
                    value = neVolume,
                    valueRange = 0f..2f,
                    stepSize = 0.05f,
                    showDecimal = true,
                    valueFormat = { "%.2f×".format(it) },
                    description = "1.00 = 原音量",
                    onValueChange = { neVolume = it },
                )
                TinySliderSettingItem(
                    title = "音高",
                    value = nePitch,
                    valueRange = 0.5f..2f,
                    stepSize = 0.05f,
                    showDecimal = true,
                    valueFormat = { "%.2f×".format(it) },
                    description = "1.00 = 原音高",
                    onValueChange = { nePitch = it },
                )
            }
            if (pluginUiLayout != null && !pluginUiEmpty) {
                SectionHint("— 插件特色界面 —")
                key(pluginUiLayout) {
                    AndroidView(
                        factory = { pluginUiLayout!! },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    // ---------------- 新建条目 · 第一步：选择音色插件 ----------------
    AppModalBottomSheet(
        show = pluginPickerSheet,
        onDismissRequest = { pluginPickerSheet = false },
        title = "新建条目 · 选择音色插件",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            plugins.filter { it.enabled }.forEach { p ->
                TinyClickableSettingItem(
                    title = p.name,
                    description = p.pluginId,
                    onClick = {
                        nePluginId = p.pluginId
                        nePluginName = p.name
                        neVoiceSel = emptyList()
                        pluginPickerSheet = false
                        neStep2 = true
                    },
                )
            }
            if (plugins.none { it.enabled }) {
                TinyClickableSettingItem(title = "暂无已启用插件", onClick = {})
            }
        }
    }

    // ---------------- 声音选择（多选 + 试听） ----------------
    AppModalBottomSheet(
        show = voicePickerSheet,
        onDismissRequest = { voicePickerSheet = false },
        title = "选择声音 · 已选 ${neVoiceSel.size}",
        endAction = {
            MediumTonalButton(
                onClick = { voicePickerSheet = false },
                icon = Icons.Default.Check,
                contentDescription = "完成",
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                query = voiceQuery,
                onQueryChange = { voiceQuery = it },
                placeholder = "搜索声音（名称 / ID）",
                shape = RoundedCornerShape(12.dp),
                autoFocus = false,
            )
            if (voiceList.isEmpty()) {
                TinyClickableSettingItem(title = "加载中…（或该语言无声音）", onClick = {})
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                ) {
                    items(
                        voiceList.filter {
                            voiceQuery.isBlank() ||
                                it.name.contains(voiceQuery, ignoreCase = true) ||
                                it.id.contains(voiceQuery, ignoreCase = true)
                        },
                        key = { "nv_${it.id}" },
                    ) { v ->
                        val checked = neVoiceSel.any { it.id == v.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    neVoiceSel = if (checked) {
                                        neVoiceSel.filterNot { it.id == v.id }
                                    } else {
                                        neVoiceSel + v
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheckbox(
                                checked = checked,
                                onCheckedChange = null,
                                includeStateSemantics = false,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = v.name.ifBlank { v.id },
                                    style = LegadoTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AppText(
                                    text = v.id,
                                    style = LegadoTheme.typography.bodySmall,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            SmallPlainButton(
                                onClick = {
                                    scope.launch {
                                        context.toastOnUi("正在合成…")
                                        val r = repo.auditionDirect(
                                            nePluginId, neLocale, v.id, neAuditionText,
                                            neSpeed, neVolume, nePitch, effectiveDataParams(),
                                        )
                                        handleAuditionResult(r)
                                    }
                                },
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "试听",
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 新建条目 · 输入对话框 ----------------
    AppAlertDialog(
        show = grpDlg,
        onDismissRequest = { grpDlg = false },
        title = "分组名",
        content = {
            AppTextField(
                value = grpInput,
                onValueChange = { grpInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = "自定义分组名",
            )
        },
        confirmText = "保存",
        onConfirm = {
            grpDlg = false
            val t = grpInput.trim()
            if (t.isNotEmpty()) neGroup = t
        },
        dismissText = "取消",
        onDismiss = { grpDlg = false },
    )

    AppAlertDialog(
        show = grp2Dlg,
        onDismissRequest = { grp2Dlg = false },
        title = "二级分组名",
        content = {
            AppTextField(
                value = grp2Input,
                onValueChange = { grp2Input = it },
                modifier = Modifier.fillMaxWidth(),
                label = "二级分组名（可留空）",
            )
        },
        confirmText = "保存",
        onConfirm = {
            grp2Dlg = false
            neCat2 = grp2Input.trim()
        },
        dismissText = "取消",
        onDismiss = { grp2Dlg = false },
    )

    AppAlertDialog(
        show = neTextDlg,
        onDismissRequest = { neTextDlg = false },
        title = "试听文本",
        content = {
            AppTextField(
                value = neTextInput,
                onValueChange = { neTextInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = "文本",
            )
        },
        confirmText = "保存",
        onConfirm = {
            neTextDlg = false
            val t = neTextInput.trim()
            if (t.isNotEmpty()) neAuditionText = t
        },
        dismissText = "取消",
        onDismiss = { neTextDlg = false },
    )

    // 编辑条目 · 字段输入弹窗（显示名 / 标签 / 二级分组名）
    AppAlertDialog(
        show = edFieldDlg != null,
        onDismissRequest = { edFieldDlg = null },
        title = edFieldDlg.orEmpty(),
        content = {
            AppTextField(
                value = edFieldInput,
                onValueChange = { edFieldInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = edFieldDlg.orEmpty(),
            )
        },
        confirmText = "保存",
        onConfirm = {
            val dlg = edFieldDlg
            edFieldDlg = null
            val v = edFieldInput.trim()
            when (dlg) {
                "显示名" -> edName = v
                "标签" -> edTag = v
                "二级分组名" -> edCategory = v
            }
        },
        dismissText = "取消",
        onDismiss = { edFieldDlg = null },
    )

    // 编辑插件 · 变量输入弹窗
    AppAlertDialog(
        show = varDlgKey != null,
        onDismissRequest = { varDlgKey = null },
        title = varFields.firstOrNull { it.key == varDlgKey }?.label ?: varDlgKey.orEmpty(),
        content = {
            AppTextField(
                value = varDlgInput,
                onValueChange = { varDlgInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = varDlgKey.orEmpty(),
            )
        },
        confirmText = "保存",
        onConfirm = {
            varDlgKey?.let { varValues[it] = varDlgInput }
            varDlgKey = null
        },
        dismissText = "取消",
        onDismiss = { varDlgKey = null },
    )

    // ---------------- 编辑条目（音色分组 → 音色基础信息 → 插件特色界面） ----------------
    AppModalBottomSheet(
        show = editTarget != null,
        onDismissRequest = { editTarget = null },
        title = "编辑条目：${editTarget?.displayName.orEmpty()}",
        endAction = {
            MediumTonalButton(
                onClick = {
                    val t = editTarget ?: return@MediumTonalButton
                    if (edTag.isBlank()) {
                        context.toastOnUi("标签不能为空")
                        return@MediumTonalButton
                    }
                    val targetGid = groups.firstOrNull { it.name == edGroup }?.groupId ?: 0L
                    editTarget = null
                    scope.launch {
                        val ok = repo.updateVoiceEntry(
                            groupId = t.groupId,
                            entryId = t.id,
                            tag = t.tag,
                            newName = edName.trim(),
                            newTag = edTag.trim(),
                            newCategoryPath = edCategory.trim(),
                            newGroupId = targetGid,
                            speed = edSpeed,
                            volume = edVolume,
                            pitch = edPitch,
                            newData = if (edUiLayout != null && !edUiEmpty) {
                                edTempSource?.data
                            } else {
                                null
                            },
                        )
                        context.toastOnUi(if (ok) "已保存" else "保存失败")
                        reload()
                    }
                },
                icon = Icons.Default.Check,
                contentDescription = "保存",
            )
        },
    ) {
        val t = editTarget
        if (t != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHint("— 音色分组 —")
                SplicedColumnGroup {
                    TinyDropdownSettingItem(
                        title = "分组名",
                        selectedValue = edGroup,
                        displayEntries = groups.map { it.name }.toTypedArray(),
                        entryValues = groups.map { it.name }.toTypedArray(),
                        onValueChange = { edGroup = it },
                    )
                    TinyClickableSettingItem(
                        title = "二级分组名（categoryPath）",
                        description = if (edCategory.isBlank()) {
                            "未填 → 直接放一级分组下"
                        } else {
                            edCategory
                        },
                        onClick = {
                            edFieldInput = edCategory
                            edFieldDlg = "二级分组名"
                        },
                    )
                }
                SectionHint("— 音色基础信息 —")
                SplicedColumnGroup {
                    TinyClickableSettingItem(
                        title = "显示名",
                        description = edName,
                        onClick = {
                            edFieldInput = edName
                            edFieldDlg = "显示名"
                        },
                    )
                    TinyClickableSettingItem(
                        title = "标签（tag）",
                        description = edTag,
                        onClick = {
                            edFieldInput = edTag
                            edFieldDlg = "标签"
                        },
                    )
                    TinySliderSettingItem(
                        title = "语速",
                        value = edSpeed,
                        valueRange = 0f..2f,
                        stepSize = 0.05f,
                        showDecimal = true,
                        valueFormat = { if (it < 0.026f) "跟随（0）" else "%.2f×".format(it) },
                        description = "0 = 跟随朗读设置；1.00 = 原速",
                        onValueChange = { edSpeed = it },
                    )
                    TinySliderSettingItem(
                        title = "音量",
                        value = edVolume,
                        valueRange = 0f..2f,
                        stepSize = 0.05f,
                        showDecimal = true,
                        valueFormat = { if (it < 0.026f) "跟随（0）" else "%.2f×".format(it) },
                        description = "0 = 跟随朗读设置；1.00 = 原音量",
                        onValueChange = { edVolume = it },
                    )
                    TinySliderSettingItem(
                        title = "音高",
                        value = edPitch,
                        valueRange = 0f..2f,
                        stepSize = 0.05f,
                        showDecimal = true,
                        valueFormat = { if (it < 0.026f) "跟随（0）" else "%.2f×".format(it) },
                        description = "0 = 跟随朗读设置；1.00 = 原音高",
                        onValueChange = { edPitch = it },
                    )
                }
                if (edUiLayout != null && !edUiEmpty) {
                    SectionHint("— 插件特色界面 —")
                    key(edUiLayout) {
                        AndroidView(
                            factory = { edUiLayout!! },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // ---------------- 编辑插件（元信息 + 变量，卡片化） ----------------
    AppModalBottomSheet(
        show = varsEditorPlugin != null,
        onDismissRequest = { varsEditorPlugin = null },
        title = "编辑插件：${varsEditorPlugin?.name.orEmpty()}",
        endAction = {
            MediumTonalButton(
                onClick = {
                    val p = varsEditorPlugin ?: return@MediumTonalButton
                    val id = vsId.trim().ifBlank { p.pluginId }
                    varsEditorPlugin = null
                    scope.launch {
                        val ok = repo.updatePluginMeta(
                            oldId = p.pluginId,
                            newName = vsName,
                            newId = id,
                            newAuthor = vsAuthor,
                            newVersion = vsVersion.toIntOrNull() ?: p.version,
                        )
                        val ok2 = repo.savePluginUserVars(id, varValues.toMap())
                        repo.updatePluginShell(
                            id,
                            mapOf("iconUrl" to vsIcon, "description" to vsDesc),
                        )
                        context.toastOnUi(
                            if (ok && ok2) "插件已保存" else "部分保存失败（检查 ID 是否重复）"
                        )
                        reload()
                    }
                },
                icon = Icons.Default.Check,
                contentDescription = "保存",
            )
        },
    ) {
        val p = varsEditorPlugin
        if (p != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SectionHint("— 插件信息 —")
                SplicedColumnGroup {
                    SheetField("插件名（name）", vsName) { vsName = it }
                    SheetField("插件 ID（pluginId）", vsId) { vsId = it }
                    SheetField("作者（author）", vsAuthor) { vsAuthor = it }
                    SheetField("版本号（version，数字）", vsVersion) { vsVersion = it }
                    SheetField("图标 URL（iconUrl）", vsIcon) { vsIcon = it }
                    SheetField("简介（description）", vsDesc) { vsDesc = it }
                }
                SectionHint("— 插件变量 —")
                TinyClickableSettingItem(
                    title = "来自插件 defVars",
                    description = "补丁版同款：登录/凭据等全局变量；含 loginUrl 的登录类请填登录后取到的值",
                    onClick = {},
                )
                if (varFields.isEmpty()) {
                    SplicedColumnGroup {
                        TinyClickableSettingItem(title = "此插件未定义变量", onClick = {})
                    }
                } else {
                    SplicedColumnGroup {
                        varFields.forEach { f ->
                            val cur = varValues[f.key].orEmpty()
                            TinyClickableSettingItem(
                                title = f.label,
                                description = buildString {
                                    append(if (cur.isBlank()) "未设置" else cur)
                                    if (f.description.isNotBlank()) {
                                        append(" · ")
                                        append(f.description)
                                    }
                                },
                                onClick = {
                                    varDlgInput = cur
                                    varDlgKey = f.key
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- 声线条目操作 ----------------
    AppModalBottomSheet(
        show = sheet is CenterSheet.EntryActions,
        onDismissRequest = { sheet = null },
        title = (sheet as? CenterSheet.EntryActions)?.entry?.let {
            "[${it.tag}] ${it.displayName}"
        } ?: "声线条目",
    ) {
        val e = (sheet as? CenterSheet.EntryActions)?.entry
        if (e != null) {
            TinyClickableSettingItem(
                title = "试听（合成并播放）",
                description = "走 内置引擎 → 标签 ${e.tag}",
                imageVector = Icons.Default.PlayArrow,
                onClick = {
                    sheet = null
                    auditionEntry(e)
                },
            )
            TinyClickableSettingItem(
                title = "编辑条目",
                description = "音色分组 / 基础信息 / 插件特色界面",
                onClick = {
                    sheet = null
                    edName = e.displayName
                    edTag = e.tag
                    edCategory = e.categoryPath
                    edGroup = groups.firstOrNull { it.groupId == e.groupId }?.name ?: ""
                    edSpeed = if (e.speed > 0f) e.speed else 0f
                    edVolume = if (e.volume > 0f) e.volume else 0f
                    edPitch = if (e.pitch > 0f) e.pitch else 0f
                    editTarget = e
                },
            )
            TinyClickableSettingItem(
                title = "重命名显示名",
                description = "当前：${e.displayName}",
                onClick = {
                    renameText = e.displayName
                    sheet = null
                    renameTarget = e
                },
            )
            TinyClickableSettingItem(
                title = "删除此声线配置",
                onClick = {
                    sheet = null
                    showDeleteEntry = e
                },
            )
        }
    }

    // ---------------- 引擎切换（MD3：添加/编辑/删除/导入导出/全局直选） ----------------
    AppModalBottomSheet(
        show = showEngineSheet,
        onDismissRequest = { showEngineSheet = false },
        title = "切换朗读引擎",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediumTonalButton(
                    onClick = {
                        isNewEngine = true
                        engineEditor = HttpTTS(name = "", url = "", speed = 5)
                    },
                    icon = Icons.Default.Add,
                    contentDescription = "添加引擎",
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    MediumTonalButton(
                        onClick = { engineMenuOpen = true },
                        icon = Icons.Default.MoreVert,
                        contentDescription = "更多",
                    )
                    RoundDropdownMenu(
                        expanded = engineMenuOpen,
                        onDismissRequest = { engineMenuOpen = false },
                    ) {
                        RoundDropdownMenuItem(
                            text = "本地导入",
                            onClick = {
                                engineMenuOpen = false
                                engineImportLauncher.launch(
                                    arrayOf("application/json", "text/plain", "*/*")
                                )
                            },
                        )
                        RoundDropdownMenuItem(
                            text = "网络导入",
                            onClick = {
                                engineMenuOpen = false
                                netImportUrl = ""
                                showNetImport = true
                            },
                        )
                        RoundDropdownMenuItem(
                            text = "导出",
                            onClick = {
                                engineMenuOpen = false
                                engineExportLauncher.launch("httpTTS.json")
                            },
                        )
                    }
                }
            }

            fun selectEngine(value: String?) {
                showEngineSheet = false
                scope.launch {
                    val msg = repo.applyEngine(value, forBook = false)
                    context.toastOnUi(msg)
                    reload()
                }
            }

            EngineRow(
                label = "系统 TTS",
                subtitle = null,
                current = engineValue.isNullOrBlank(),
                editable = false,
                onSelect = { selectEngine(null) },
            )
            EngineRow(
                label = "内置引擎（TTS Server）",
                subtitle = null,
                current = engineValue == TtsServerCenterRepository.BUILTIN_ENGINE_JSON,
                editable = false,
                onSelect = { selectEngine(TtsServerCenterRepository.BUILTIN_ENGINE_JSON) },
            )
            engineList.forEach { h ->
                EngineRow(
                    label = "在线朗读 · ${h.name}",
                    subtitle = h.url,
                    current = engineValue == h.id.toString(),
                    editable = true,
                    onSelect = { selectEngine(h.id.toString()) },
                    onEdit = {
                        isNewEngine = false
                        engineEditor = h
                    },
                    onDelete = { showDeleteEngine = h },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // ---------------- 引擎编辑器（复刻原版添加云 TTS 参数） ----------------
    EngineEditorSheet(
        value = engineEditor,
        isNew = isNewEngine,
        onDismiss = { engineEditor = null },
        onSave = { saved ->
            engineEditor = null
            scope.launch {
                repo.saveHttpTts(saved)
                context.toastOnUi("已保存")
                reload()
            }
        },
    )

    AppAlertDialog(
        show = showNetImport,
        onDismissRequest = { showNetImport = false },
        title = "网络导入引擎",
        content = {
            AppTextField(
                value = netImportUrl,
                onValueChange = { netImportUrl = it },
                label = "URL（httpTTS.json / 直链）",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = "导入",
        onConfirm = {
            val url = netImportUrl.trim()
            showNetImport = false
            if (url.isNotBlank()) {
                scope.launch {
                    context.toastOnUi("下载中…")
                    context.toastOnUi(repo.importHttpTts(url))
                    reload()
                }
            }
        },
        dismissText = "取消",
        onDismiss = { showNetImport = false },
    )

    AppAlertDialog(
        show = showDeleteEngine != null,
        onDismissRequest = { showDeleteEngine = null },
        title = "删除引擎",
        text = "确认删除「在线朗读 · ${showDeleteEngine?.name.orEmpty()}」？",
        confirmText = "删除",
        onConfirm = {
            val h = showDeleteEngine ?: return@AppAlertDialog
            showDeleteEngine = null
            scope.launch {
                repo.deleteHttpTts(h.id)
                if (engineValue == h.id.toString()) {
                    repo.applyEngine(null, forBook = false)
                }
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { showDeleteEngine = null },
    )

    // ---------------- 删除确认（单条） ----------------
    AppAlertDialog(
        show = showDeleteEntry != null,
        onDismissRequest = { showDeleteEntry = null },
        title = "删除声线配置",
        text = "确认删除 [${showDeleteEntry?.tag.orEmpty()}] ${showDeleteEntry?.displayName.orEmpty()} ？",
        confirmText = "删除",
        onConfirm = {
            val e = showDeleteEntry ?: return@AppAlertDialog
            showDeleteEntry = null
            scope.launch {
                repo.deleteEntry(e.tagRuleId, e.tag)
                context.toastOnUi("已删除")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { showDeleteEntry = null },
    )

    // ---------------- 重命名（条目/分组） ----------------
    AppAlertDialog(
        show = renameTarget != null,
        onDismissRequest = { renameTarget = null },
        title = "重命名声线",
        text = "当前：${renameTarget?.displayName.orEmpty()}",
        content = {
            AppTextField(
                value = renameText,
                onValueChange = { renameText = it },
                modifier = Modifier.fillMaxWidth(),
                label = "新显示名",
            )
        },
        confirmText = "保存",
        onConfirm = {
            val e = renameTarget ?: return@AppAlertDialog
            val name = renameText.trim()
            renameTarget = null
            if (name.isNotBlank()) {
                scope.launch {
                    val ok = repo.renameEntry(e.tagRuleId, e.tag, name)
                    context.toastOnUi(if (ok) "已重命名" else "未找到条目")
                    reload()
                }
            }
        },
        dismissText = "取消",
        onDismiss = { renameTarget = null },
    )

    AppAlertDialog(
        show = renameGroupTarget != null,
        onDismissRequest = { renameGroupTarget = null },
        title = "重命名分组：${groupNameOfKey(renameGroupTarget.orEmpty())}",
        content = {
            AppTextField(
                value = renameGroupText,
                onValueChange = { renameGroupText = it },
                modifier = Modifier.fillMaxWidth(),
                label = "新分组名",
            )
        },
        confirmText = "保存",
        onConfirm = {
            val old = renameGroupTarget ?: return@AppAlertDialog
            renameGroupTarget = null
            scope.launch {
                val ok = repo.renameGroup(old.toLongOrNull() ?: return@launch, renameGroupText.trim())
                context.toastOnUi(if (ok) "已重命名" else "未找到分组")
                reload()
            }
        },
        dismissText = "取消",
        onDismiss = { renameGroupTarget = null },
    )

    // ---------------- 详情/结果 ----------------
    LogDetailSheet(
        show = detailText != null,
        title = detailTitle,
        content = detailText.orEmpty(),
        onDismissRequest = { detailText = null },
    )
}

/**
 * 层级行（组 / 二级分类 / 声线）：原版箭头（KeyboardArrowDown 旋转）、方框、长按多选
 */
@Composable
private fun LevelRow(
    title: String,
    subtitle: String? = null,
    arrowExpanded: Boolean? = null,
    indent: Dp = 0.dp,
    modifier: Modifier = Modifier,
    selActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (arrowExpanded == true) 0f else -90f,
        label = "levelArrow",
    )
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = indent),
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            if (arrowExpanded != null) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}

@Composable
private fun EngineRow(
    label: String,
    subtitle: String?,
    current: Boolean,
    editable: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    SelectionItemCard(
        title = label,
        subtitle = subtitle,
        isSelected = current,
        onToggleSelection = onSelect,
        leadingContent = {
            AppRadioButton(
                selected = current,
                onClick = null,
            )
        },
        trailingAction = if (editable) {
            {
                Row {
                    SmallPlainButton(
                        onClick = { onEdit?.invoke() },
                        icon = Icons.Default.Edit,
                        contentDescription = "编辑",
                    )
                    SmallPlainButton(
                        onClick = { onDelete?.invoke() },
                        icon = Icons.Default.Delete,
                        contentDescription = "删除",
                    )
                }
            }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EngineEditorSheet(
    value: HttpTTS?,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (HttpTTS) -> Unit,
) {
    var cached by remember { mutableStateOf(value) }
    if (value != null) cached = value
    val source = cached ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var name by remember(source) { mutableStateOf(source.name) }
    var url by remember(source) { mutableStateOf(source.url) }
    var contentType by remember(source) { mutableStateOf(source.contentType.orEmpty()) }
    var concurrentRate by remember(source) { mutableStateOf(source.concurrentRate ?: "0") }
    var header by remember(source) { mutableStateOf(source.header.orEmpty()) }
    var loginUrl by remember(source) { mutableStateOf(source.loginUrl.orEmpty()) }
    var loginUi by remember(source) { mutableStateOf(source.loginUi.orEmpty()) }
    var loginCheckJs by remember(source) { mutableStateOf(source.loginCheckJs.orEmpty()) }
    var jsLib by remember(source) { mutableStateOf(source.jsLib.orEmpty()) }
    var speed by remember(source) { mutableStateOf((source.speed ?: 5).toFloat()) }

    fun current() = source.copy(
        name = name.trim(),
        url = url.trim(),
        contentType = contentType.ifBlank { null },
        concurrentRate = concurrentRate,
        header = header.ifBlank { null },
        loginUrl = loginUrl.ifBlank { null },
        loginUi = loginUi.ifBlank { null },
        loginCheckJs = loginCheckJs.ifBlank { null },
        jsLib = jsLib.ifBlank { null },
        speed = speed.toInt(),
        lastUpdateTime = System.currentTimeMillis(),
    )

    AppModalBottomSheet(
        show = value != null,
        onDismissRequest = onDismiss,
        title = if (isNew) "添加引擎" else "编辑引擎",
        startAction = {
            var expanded by remember { mutableStateOf(false) }
            Box {
                MediumTonalButton(
                    onClick = { expanded = true },
                    icon = Icons.Default.MoreVert,
                    contentDescription = "更多",
                )
                RoundDropdownMenu(expanded, { expanded = false }) {
                    RoundDropdownMenuItem(
                        text = "复制文本",
                        onClick = {
                            expanded = false
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(
                                        ClipData.newPlainText("httpTTS", GSON.toJson(current()))
                                    )
                                )
                            }
                        },
                    )
                    RoundDropdownMenuItem(
                        text = "粘贴源",
                        onClick = {
                            expanded = false
                            scope.launch {
                                val text = clipboard.getClipEntry()?.clipData
                                    ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    ?: return@launch
                                HttpTTS.fromJson(text).getOrNull()?.let { imported ->
                                    name = imported.name
                                    url = imported.url
                                    contentType = imported.contentType.orEmpty()
                                    concurrentRate = imported.concurrentRate ?: "0"
                                    header = imported.header.orEmpty()
                                    loginUrl = imported.loginUrl.orEmpty()
                                    loginUi = imported.loginUi.orEmpty()
                                    loginCheckJs = imported.loginCheckJs.orEmpty()
                                    jsLib = imported.jsLib.orEmpty()
                                    speed = (imported.speed ?: 5).toFloat()
                                }
                            }
                        },
                    )
                }
            }
        },
        endAction = {
            MediumTonalButton(
                onClick = {
                    val c = current()
                    if (c.name.isBlank() || c.url.isBlank()) {
                        context.toastOnUi("名称 / URL 不能为空")
                    } else {
                        onSave(c)
                    }
                },
                icon = Icons.Default.Check,
                contentDescription = "保存",
            )
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppTextField(
                    name,
                    { name = it },
                    label = stringResource(R.string.name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    url,
                    { url = it },
                    label = "URL",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                TinySliderSettingItem(
                    title = stringResource(R.string.read_aloud_speed),
                    description = stringResource(R.string.tts_source_speed_summary),
                    value = speed,
                    valueRange = 0f..80f,
                    steps = 79,
                    onValueChange = { speed = it },
                )
            }
            item {
                AppTextField(
                    contentType,
                    { contentType = it },
                    label = "Content-Type",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    concurrentRate,
                    { concurrentRate = it },
                    label = stringResource(R.string.concurrent_rate),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    header,
                    { header = it },
                    label = stringResource(R.string.source_http_header),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginUrl,
                    { loginUrl = it },
                    label = stringResource(R.string.login_url),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginUi,
                    { loginUi = it },
                    label = stringResource(R.string.login_ui),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    loginCheckJs,
                    { loginCheckJs = it },
                    label = stringResource(R.string.login_check_js),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                AppTextField(
                    jsLib,
                    { jsLib = it },
                    label = "jsLib",
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun BankTag(active: Boolean, onClick: () -> Unit) {
    val bg = if (active) {
        LegadoTheme.colorScheme.primary
    } else {
        LegadoTheme.colorScheme.surfaceVariant
    }
    val fg = if (active) {
        LegadoTheme.colorScheme.onPrimary
    } else {
        LegadoTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        AppText(
            text = if (active) "已选中" else "未选中",
            style = LegadoTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
/** 分组提醒头（与「插件特色界面」同款样式） */
@Composable
private fun SectionHint(text: String) {
    AppText(
        text = text,
        style = LegadoTheme.typography.labelSmall,
        color = LegadoTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

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
