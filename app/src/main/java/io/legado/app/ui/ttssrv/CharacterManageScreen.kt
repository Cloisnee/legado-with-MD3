package io.legado.app.ui.ttssrv

import android.app.Application
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.zIndex
import io.legado.app.R
import io.legado.app.data.repository.ReadAloudVoicePoolRepository
import io.legado.app.domain.model.readaloud.v4.RaBookSummary
import io.legado.app.domain.model.readaloud.v4.RaCharacterView
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.DraggableSelectionHandler
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.SelectionBottomBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.checkBox.AppCheckbox
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.TinyDropdownSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.AliasTokens
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collect
import org.koin.core.context.GlobalContext
import org.koin.androidx.compose.koinViewModel

private data class JoinLibRequest(
    val words: List<String>,
    val aliasToRemove: String? = null,
    /** 从「主名」旁的入库触发：保存角色信息时联动转池 / 章节后缀 / 剧本同步。 */
    val linkRole: Boolean = false,
)

private val ROLES = listOf("全部", "路人", "核心", "特殊")
private val MALE_AGES = listOf("男童", "少年", "男青年", "男中年", "男老年")
private val FEMALE_AGES = listOf("女童", "少女", "女青年", "女中年", "女老年")

/**
 * 角色管理（对照「角色管理」v36 插件、MD3 真身元素复刻）：
 *  - 搜索（书源式展开）· 书籍卡 · 类型卡（全部/路人/核心/特殊）
 *  - 角色卡：点声线标签=试听+更换；点选+长按=修改/删除/合并+跟随
 *  - 编辑：主名/别名(管理)/类型(自动【第N章】)/性别/年龄/声线
 *  - 别名管理：修改 / 入库（裸属/特殊词库）/ 释放并固定（含剧本回放）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterManageScreen(
    app: Application,
    onBack: () -> Unit,
    embedded: Boolean = false,
    refreshKey: Int = 0,
    hostTab: Int = 0,
    onHostTabSelected: (Int) -> Unit = {},
    bookTabLabel: String = "剧本",
) {
    val context = LocalContext.current
    val vm: CharacterManageViewModel = koinViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val voicePool = remember { GlobalContext.get().get<ReadAloudVoicePoolRepository>() }

    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var sel by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var mergeTargetPick by remember { mutableStateOf(false) }
    var showBookMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var auditionTextInput by remember { mutableStateOf("") }
    var auditionTextDialog by remember { mutableStateOf(false) }

    // 编辑弹窗（纯 UI 草稿；写库走 ViewModel）
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var edName by remember { mutableStateOf("") }
    var edAliases by remember { mutableStateOf("") }
    var edRole by remember { mutableStateOf("核心") }
    var edGender by remember { mutableStateOf("男") }
    var edAge by remember { mutableStateOf("男青年") }
    var edVoice by remember { mutableStateOf("") }
    var edNameDialog by remember { mutableStateOf(false) }
    var edNameInput by remember { mutableStateOf("") }
    var mismatchConfirm by remember { mutableStateOf(false) }

    // 别名管理
    var aliasSheet by remember { mutableStateOf(false) }
    var aliasInputDialog by remember { mutableStateOf(false) }
    var aliasInput by remember { mutableStateOf("") }
    var aliasRenameTarget by remember { mutableStateOf<String?>(null) }
    var aliasRenameInput by remember { mutableStateOf("") }

    // 声线选择 / 试听 / 入库
    var picker by remember { mutableStateOf<VoiceTagPickRequest?>(null) }
    var auditionIdx by remember { mutableStateOf<Int?>(null) }
    var auditionText by remember { mutableStateOf("你好，这是一段试听语音。") }
    var joinLib by remember { mutableStateOf<JoinLibRequest?>(null) }
    var pendingJoinKind by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vm.effects.collect { context.toastOnUi(it) }
    }
    LaunchedEffect(refreshKey) {
        vm.load()
    }

    fun openEdit(idx: Int) {
        val r = state.records.getOrNull(idx) ?: return
        editIdx = idx
        pendingJoinKind = null
        edName = r.name
        edAliases = r.aliases
        edRole = r.roletype.ifBlank { "核心" }
        edGender = if (r.gender == "女") "女" else "男"
        edAge = if (edRole == "特殊") {
            "系统"
        } else {
            r.age.ifBlank { if (edGender == "女") "女青年" else "男青年" }
        }
        edVoice = r.voice
    }

    fun draftFinalRole(): String = when (pendingJoinKind) {
        "裸属" -> "路人"
        "特殊" -> "特殊"
        else -> edRole
    }

    fun draftFinalAge(role: String = draftFinalRole()): String =
        if (role == "特殊") "系统" else edAge

    fun commitEdit() {
        val idx = editIdx ?: return
        val target = state.records.getOrNull(idx) ?: return
        val newName = edName.trim()
        if (newName.isBlank()) {
            context.toastOnUi("主名不能为空")
            return
        }
        val joinKind = pendingJoinKind
        var finalName = newName
        val finalRole = when (joinKind) {
            "裸属" -> "路人"
            "特殊" -> "特殊"
            else -> edRole
        }
        if (joinKind == "裸属" && !newName.contains("【第")) {
            val first = target.appearanceChapters.minOrNull()
            if (first != null) finalName = newName + "【第${first + 1}章】"
        }
        vm.saveEdit(
            CharacterEditInput(
                characterId = target.id,
                name = finalName,
                aliases = edAliases,
                roleType = finalRole,
                gender = edGender,
                ageBand = draftFinalAge(finalRole),
                voice = edVoice,
            ),
        )
        pendingJoinKind = null
        editIdx = null
        mismatchConfirm = false
        sel = emptySet()
    }

    fun saveEditWithCheck() {
        val idx = editIdx ?: return
        val target = state.records.getOrNull(idx) ?: return
        val newName = edName.trim()
        if (newName.isBlank()) {
            context.toastOnUi("主名不能为空")
            return
        }
        val finalRole = draftFinalRole()
        val oldType = target.roletype.ifBlank { "核心" }
        var name = newName
        if (oldType != "路人" && finalRole == "路人") {
            if (!name.contains("【第")) {
                var lc = target.lastAppearanceChapter
                if (lc < 0 && target.appearanceChapters.isNotEmpty()) {
                    lc = target.appearanceChapters.last()
                }
                name = name + "【第" + (if (lc >= 0) lc + 1 else 1) + "章】"
            }
        } else if (oldType == "路人" && finalRole != "路人") {
            if (name.length > 3 && name.endsWith("】") && name.contains("【第")) {
                val mark = name.lastIndexOf("【第")
                if (mark > 0) name = name.substring(0, mark)
            }
        }
        edName = name
        val finalAge = draftFinalAge(finalRole)
        val expected = voicePool.expectedVoicePrefix(finalRole, edGender, finalAge)
        val prefix = voicePool.voiceAgePrefix(edVoice)
        if (edVoice.isNotEmpty() && prefix != expected) {
            mismatchConfirm = true
            return
        }
        commitEdit()
    }

    fun toggleSel(idx: Int) {
        sel = if (idx in sel) sel - idx else sel + idx
    }

    fun performDelete() {
        val ids = sel.mapNotNull { state.records.getOrNull(it)?.id }
        vm.deleteCharacters(ids)
        confirmDelete = false
        sel = emptySet()
    }

    fun performMerge(targetIdx: Int) {
        val target = state.records.getOrNull(targetIdx) ?: return
        val followers = sel.filter { it != targetIdx }.mapNotNull { state.records.getOrNull(it) }
        vm.mergeCharacters(followers.map { it.id }, target.id)
        sel = emptySet()
    }

    fun releaseAlias(alias: String) {
        val idx = editIdx ?: return
        val target = state.records.getOrNull(idx) ?: return
        val ageStore = if (target.roletype == "特殊") "系统" else target.age.ifBlank { "男青年" }
        picker = VoiceTagPickRequest(
            roletype = "核心",
            gender = edGender,
            age = ageStore,
            title = "释放并固定：选声线（$alias）",
        ) { selectedVoice ->
            vm.releaseAlias(
                parentId = target.id,
                alias = alias,
                selectedVoice = selectedVoice,
                gender = edGender,
                roleType = "核心",
                ageBand = ageStore,
            )
            aliasSheet = false
            editIdx = null
        }
    }

    fun doAudition(tag: String) {
        vm.audition(tag, auditionText)
    }

    // ---------------- 界面 ----------------

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val listState = rememberLazyListState()
    val filtered = state.records.withIndex().filter { (_, r) ->
        (state.roleFilter == "全部" || r.roletype == state.roleFilter) && run {
            if (query.isBlank()) {
                true
            } else {
                val q = query.lowercase()
                r.name.lowercase().contains(q) ||
                    r.aliases.lowercase().contains(q) ||
                    r.gender.lowercase().contains(q) ||
                    r.age.lowercase().contains(q) ||
                    r.voice.lowercase().contains(q)
            }
        }
    }.sortedByDescending { (_, r) -> r.appearanceCount }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = if (sel.isNotEmpty()) {
                    stringResource(R.string.list_selected_count, sel.size, filtered.size)
                } else {
                    "角色管理"
                },
                useCharMode = sel.isNotEmpty(),
                subtitle = "当前书：${state.currentBookName} · ${state.records.size} 个角色",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (sel.isNotEmpty()) {
                        TopBarNavigationButton(
                            onClick = { sel = emptySet() },
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
                    // 类型切换由「类型卡」承担：独立入口（二合一面）顶栏不重复，嵌入（剧本审查）无卡片才上顶栏
                    if (embedded) {
                        Box {
                            TopBarActionButton(
                                onClick = { showTypeMenu = true },
                                imageVector = Icons.Default.FindReplace,
                                contentDescription = "筛选：全部/特殊/路人/核心",
                            )
                            RoundDropdownMenu(
                                expanded = showTypeMenu,
                                onDismissRequest = { showTypeMenu = false },
                            ) { dismiss ->
                                ROLES.forEach { t ->
                                    RoundDropdownMenuItem(
                                        text = t,
                                        onClick = {
                                            dismiss()
                                            vm.setRoleFilter(t)
                                            sel = emptySet()
                                        },
                                        trailingIcon = if (t == state.roleFilter) {
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
                            }
                        }
                    }
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = searchMode,
                        enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                        exit = fadeOut(tween(180)) + shrinkVertically(tween(180)),
                    ) {
                        SearchBar(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "搜索：标签 / 名字 / 性别 / 年龄 / 声线",
                        )
                    }
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
                bottom = padding.calculateBottomPadding() + 32.dp,
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
                                            text = state.currentBookName,
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
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "切换书籍",
                                    )
                                }
                            }
                            RoundDropdownMenu(
                                expanded = showBookMenu,
                                onDismissRequest = { showBookMenu = false },
                            ) { dismiss ->
                                state.books.forEach { b ->
                                    RoundDropdownMenuItem(
                                        text = b.name,
                                        onClick = {
                                            dismiss()
                                            if (b.bookKey != state.currentBookKey) {
                                                sel = emptySet()
                                                vm.selectBook(b.bookKey)
                                            }
                                        },
                                        trailingIcon = if (b.bookKey == state.currentBookKey) {
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
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                cornerRadius = 12.dp,
                                containerColor = LegadoTheme.colorScheme.surfaceContainer,
                                onClick = { showTypeMenu = true },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        AppText(
                                            text = state.roleFilter,
                                            style = LegadoTheme.typography.titleSmall,
                                            maxLines = 1,
                                        )
                                        AppText(
                                            text = "类型",
                                            style = LegadoTheme.typography.bodySmall,
                                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "类型筛选",
                                    )
                                }
                            }
                            RoundDropdownMenu(
                                expanded = showTypeMenu,
                                onDismissRequest = { showTypeMenu = false },
                            ) { dismiss ->
                                ROLES.forEach { t ->
                                    RoundDropdownMenuItem(
                                        text = t,
                                        onClick = {
                                            dismiss()
                                            vm.setRoleFilter(t)
                                            sel = emptySet()
                                        },
                                        trailingIcon = if (t == state.roleFilter) {
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
                            }
                        }
                    }
                }

            }
            if (state.records.isEmpty()) {
                item(key = "empty") {
                    TinyClickableSettingItem(
                        title = "暂无角色数据",
                        description = "朗读分析产出后自动出现（当前书：${state.currentBookName}）",
                        onClick = {},
                    )
                }
            }

            items(filtered, key = { "c_${it.index}" }) { (idx, r) ->
                CharacterCardRow(
                    record = r,
                    selectionActive = sel.isNotEmpty(),
                    selected = idx in sel,
                    onClick = { toggleSel(idx) },
                    onLongClick = null,
                    onVoiceClick = { auditionIdx = idx },
                )
            }
        }
            AnimatedVisibility(
                visible = sel.isNotEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .zIndex(1f),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                SelectionBottomBar(
                    onSelectAll = { sel = filtered.map { it.index }.toSet() },
                    onSelectInvert = {
                        val all = filtered.map { it.index }.toSet()
                        sel = all - sel
                    },
                    primaryAction = ActionItem("删除", Icons.Default.Delete) {
                        confirmDelete = true
                    },
                    secondaryActions = buildList {
                        if (sel.size == 1) {
                            add(ActionItem("修改人物信息", Icons.Default.Edit) {
                                openEdit(sel.first())
                            })
                        } else if (sel.size == 2) {
                            add(ActionItem("合并与跟随", Icons.Default.Share) {
                                mergeTargetPick = true
                            })
                        }
                    },
                )
            }
            if (sel.isNotEmpty()) {
                DraggableSelectionHandler(
                    listState = listState,
                    items = filtered,
                    selectedIds = sel.map { "c_$it" }.toSet(),
                    onSelectionChange = { ids ->
                        sel = ids.mapNotNull { it.removePrefix("c_").toIntOrNull() }.toSet()
                    },
                    idProvider = { "c_${it.index}" },
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(60.dp)
                        .align(Alignment.TopStart),
                )
            }
        }
    }

    // ---------------- 合并与跟随（选择跟随角色） ----------------
    AppModalBottomSheet(
        animateContentSize = false,
        show = mergeTargetPick,
        onDismissRequest = { mergeTargetPick = false },
        title = "选择跟随角色",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            sel.sorted().forEach { idx ->
                val r = state.records.getOrNull(idx) ?: return@forEach
                TinyClickableSettingItem(
                    title = r.name,
                    description = "其余所选角色并入 ta（剧本同步 · 可撤销）",
                    onClick = {
                        mergeTargetPick = false
                        performMerge(idx)
                    },
                )
            }
        }
    }

    // ---------------- 编辑弹窗 ----------------
    AppModalBottomSheet(
        animateContentSize = false,
        show = editIdx != null,
        onDismissRequest = { editIdx = null },
        title = "角色信息",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TinyClickableSettingItem(
                title = "主名",
                description = edName,
                trailingContent = {
                    SmallPlainButton(
                        onClick = { joinLib = JoinLibRequest(listOf(edName.trim()), linkRole = true) },
                        icon = Icons.Default.FileDownload,
                        contentDescription = "入库",
                    )
                },
                onClick = {
                    edNameInput = edName
                    edNameDialog = true
                },
            )
            TinyClickableSettingItem(
                title = "别名",
                description = run {
                    val a = AliasTokens.of(edAliases)
                    if (a.isEmpty()) "无" else a.joinToString("、") + "（${a.size}个）"
                },
                trailingContent = {
                    SmallPlainButton(
                        onClick = { aliasSheet = true },
                        icon = Icons.Default.Edit,
                        contentDescription = "管理别名",
                    )
                },
                onClick = { aliasSheet = true },
            )
            TinyDropdownSettingItem(
                title = "类型",
                selectedValue = edRole,
                displayEntries = arrayOf("核心", "特殊", "路人"),
                entryValues = arrayOf("核心", "特殊", "路人"),
                onValueChange = {
                    edRole = it
                    if (it == "特殊") {
                        edAge = "系统"
                    } else if (edAge == "系统") {
                        edAge = if (edGender == "女") "女青年" else "男青年"
                    }
                },
            )
            TinyDropdownSettingItem(
                title = "性别",
                selectedValue = edGender,
                displayEntries = arrayOf("男", "女"),
                entryValues = arrayOf("男", "女"),
                onValueChange = { g ->
                    edGender = g
                    val ages = if (g == "女") FEMALE_AGES else MALE_AGES
                    if (edRole != "特殊" && edAge !in ages) {
                        edAge = if (g == "女") "女青年" else "男青年"
                    }
                },
            )
            TinyDropdownSettingItem(
                title = "年龄",
                selectedValue = edAge,
                displayEntries = (if (edRole == "特殊") listOf("系统") else {
                    if (edGender == "女") FEMALE_AGES else MALE_AGES
                }).toTypedArray(),
                entryValues = (if (edRole == "特殊") listOf("系统") else {
                    if (edGender == "女") FEMALE_AGES else MALE_AGES
                }).toTypedArray(),
                onValueChange = { edAge = it },
            )
            TinyClickableSettingItem(
                title = "声线",
                description = edVoice.ifBlank { "未设置（点按选择）" },
                onClick = {
                    val role = draftFinalRole()
                    picker = VoiceTagPickRequest(role, edGender, draftFinalAge(role), "选择声线") {
                        edVoice = it
                    }
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            TinyClickableSettingItem(
                title = "保存",
                onClick = { saveEditWithCheck() },
            )
        }
    }

    // ---------------- 别名管理 ----------------
    AppModalBottomSheet(
        animateContentSize = false,
        show = aliasSheet,
        onDismissRequest = { aliasSheet = false },
        title = "别名管理",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TinyClickableSettingItem(
                title = "＋ 新增别名",
                onClick = {
                    aliasInput = ""
                    aliasInputDialog = true
                },
            )
            val aliases = AliasTokens.of(edAliases)
            if (aliases.isEmpty()) {
                TinyClickableSettingItem(title = "（暂无别名）", onClick = {})
            }
            aliases.forEach { a ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    cornerRadius = 10.dp,
                    containerColor = LegadoTheme.colorScheme.surfaceContainer,
                    onClick = {
                        aliasRenameTarget = a
                        aliasRenameInput = a
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(
                            text = a,
                            style = LegadoTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SmallPlainButton(
                            onClick = {
                                joinLib = JoinLibRequest(listOf(a), aliasToRemove = a)
                            },
                            icon = Icons.Default.FileDownload,
                            contentDescription = "入库",
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        SmallPlainButton(
                            onClick = { releaseAlias(a) },
                            icon = Icons.Default.Person,
                            contentDescription = "释放并固定",
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        SmallPlainButton(
                            onClick = {
                                edAliases = AliasTokens.of(edAliases)
                                    .filterNot { it == a }.joinToString("|")
                            },
                            icon = Icons.Default.Delete,
                            contentDescription = "删除别名",
                        )
                    }
                }
            }
        }
    }

    // 别名 新增/修改 输入
    AppAlertDialog(
        show = edNameDialog,
        onDismissRequest = { edNameDialog = false },
        title = "修改主名",
        content = {
            AppTextField(
                value = edNameInput,
                onValueChange = { edNameInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = "主名",
            )
        },
        confirmText = "保存",
        onConfirm = {
            edNameDialog = false
            val t = edNameInput.trim()
            if (t.isNotEmpty()) edName = t
        },
        dismissText = "取消",
        onDismiss = { edNameDialog = false },
    )

    AppAlertDialog(
        show = aliasInputDialog,
        onDismissRequest = { aliasInputDialog = false },
        title = "新增别名",
        content = {
            AppTextField(
                value = aliasInput,
                onValueChange = { aliasInput = it },
                label = "别名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = "添加",
        onConfirm = {
            val t = aliasInput.trim()
            aliasInputDialog = false
            if (t.isNotEmpty()) {
                val cur = AliasTokens.of(edAliases)
                if (t !in cur) edAliases = (cur + t).joinToString("|")
            }
        },
        dismissText = "取消",
        onDismiss = { aliasInputDialog = false },
    )

    AppAlertDialog(
        show = aliasRenameTarget != null,
        onDismissRequest = { aliasRenameTarget = null },
        title = "修改别名",
        content = {
            AppTextField(
                value = aliasRenameInput,
                onValueChange = { aliasRenameInput = it },
                label = "别名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmText = "保存",
        onConfirm = {
            val from = aliasRenameTarget
            val to = aliasRenameInput.trim()
            aliasRenameTarget = null
            if (from != null && to.isNotEmpty() && to != from) {
                edAliases = AliasTokens.of(edAliases)
                    .map { if (it == from) to else it }
                    .distinct()
                    .joinToString("|")
            }
        },
        dismissText = "取消",
        onDismiss = { aliasRenameTarget = null },
    )

    // ---------------- 声线选择（共享组件） ----------------
    VoiceTagPickerSheet(
        show = picker != null,
        request = picker,
        repo = voicePool,
        onDismiss = { picker = null },
    )

    // ---------------- 发音人（试听 / 更换声线） ----------------
    AppModalBottomSheet(
        animateContentSize = false,
        show = auditionIdx != null,
        onDismissRequest = { auditionIdx = null },
        title = "发音人",
    ) {
        val r = state.records.getOrNull(auditionIdx ?: -1)
        if (r != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TinyClickableSettingItem(
                    title = "角色：${r.name}",
                    description = "当前声线：${r.voice.ifBlank { "未设置" }}",
                    onClick = {},
                )
                TinyClickableSettingItem(
                    title = "试听文本",
                    description = auditionText,
                    onClick = {
                        auditionTextInput = auditionText
                        auditionTextDialog = true
                    },
                )
                TinyClickableSettingItem(
                    title = "试听",
                    description = "用当前声线合成并播放",
                    onClick = { doAudition(r.voice) },
                )
                TinyClickableSettingItem(
                    title = "更换声线",
                    description = "按 类型/性别/年龄 筛选标签",
                    onClick = {
                        val ageStore = if (r.roletype == "特殊") "系统" else r.age
                        picker = VoiceTagPickRequest(r.roletype, r.gender, ageStore, "更换声线") { v ->
                            vm.setVoice(r.id, v)
                        }
                    },
                )
            }
        }
    }

    // ---------------- 入库（裸属/特殊词库） ----------------
    AppModalBottomSheet(
        animateContentSize = false,
        show = joinLib != null,
        onDismissRequest = { joinLib = null },
        title = "加入词库",
    ) {
        val req = joinLib
        if (req != null) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TinyClickableSettingItem(
                    title = "待入库：${req.words.joinToString("、")}",
                    description = "裸属类=职业/身份/称谓等泛指词；特殊类=系统/广播等非人信息源词",
                    onClick = {},
                )
                TinyClickableSettingItem(
                    title = "裸属类",
                    onClick = {
                        vm.appendBareWords(req.words)
                        req.aliasToRemove?.let { rm ->
                            edAliases = AliasTokens.of(edAliases)
                                .filterNot { it in req.words }.joinToString("|")
                        }
                        if (req.linkRole) pendingJoinKind = "裸属"
                        joinLib = null
                    },
                )
                TinyClickableSettingItem(
                    title = "特殊类",
                    onClick = {
                        vm.appendSpecialWords(req.words)
                        req.aliasToRemove?.let { rm ->
                            edAliases = AliasTokens.of(edAliases)
                                .filterNot { it in req.words }.joinToString("|")
                        }
                        if (req.linkRole) pendingJoinKind = "特殊"
                        joinLib = null
                    },
                )
            }
        }
    }

    // ---------------- 音色不符确认 ----------------
    AppAlertDialog(
        show = mismatchConfirm,
        onDismissRequest = { mismatchConfirm = false },
        title = "音色与性别/年龄不符",
        text = "当前音色「$edVoice」与类型/性别/年龄（期望前缀「${
            voicePool.expectedVoicePrefix(
                draftFinalRole(),
                edGender,
                draftFinalAge(draftFinalRole()),
            )
        }」）不一致。\n\n确认：保留该音色直接保存\n取消：重新选择匹配的音色",
        confirmText = "确认",
        onConfirm = { commitEdit() },
        dismissText = "取消",
        onDismiss = {
            mismatchConfirm = false
            val role = draftFinalRole()
            picker = VoiceTagPickRequest(role, edGender, draftFinalAge(role), "选择声线") {
                edVoice = it
            }
        },
    )

    // ---------------- 试听文本编辑 ----------------
    AppAlertDialog(
        show = auditionTextDialog,
        onDismissRequest = { auditionTextDialog = false },
        title = "试听文本",
        content = {
            AppTextField(
                value = auditionTextInput,
                onValueChange = { auditionTextInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = "文本",
            )
        },
        confirmText = "保存",
        onConfirm = {
            auditionTextDialog = false
            val t = auditionTextInput.trim()
            if (t.isNotEmpty()) auditionText = t
        },
        dismissText = "取消",
        onDismiss = { auditionTextDialog = false },
    )

    // ---------------- 删除确认 ----------------
    AppAlertDialog(
        show = confirmDelete,
        onDismissRequest = { confirmDelete = false },
        title = "删除角色",
        text = "确认删除所选 ${sel.size} 个角色？（不改动剧本与账本）",
        confirmText = "删除",
        onConfirm = { performDelete() },
        dismissText = "取消",
        onDismiss = { confirmDelete = false },
    )
}

// ---------------- 组件 ----------------

@Composable
private fun CharacterCardRow(
    record: RaCharacterView,
    selectionActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onVoiceClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                visible = selectionActive,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppCheckbox(
                        checked = selected,
                        onCheckedChange = null,
                        includeStateSemantics = false,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = record.name,
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val aliasLine = record.aliases.split("|").map { it.trim() }
                    .filter { it.isNotEmpty() && !isNoiseAlias(it, record) }
                    .joinToString("｜")
                if (aliasLine.isNotEmpty()) {
                    AppText(
                        text = aliasLine,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            VoiceChip(record.voice, onClick = onVoiceClick)
        }
    }
}

private fun isNoiseAlias(alias: String, record: RaCharacterView): Boolean =
    alias == record.gender || alias == record.age || alias in setOf(
        "男", "女", "系统", "旁白",
        "男童", "女童", "少年", "少女",
        "男青年", "女青年", "男中年", "女中年", "男老年", "女老年",
    )

@Composable
private fun VoiceChip(voice: String, onClick: () -> Unit) {
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

