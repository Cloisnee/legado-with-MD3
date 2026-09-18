package io.legado.app.ui.ttssrv

import android.app.Application
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.CharacterRecord
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.data.repository.TtsServerCenterRepository
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
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
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * 角色管理（对照「角色管理」v36 插件、MD3 真身元素复刻）：
 *  - 搜索（书源式展开）· 书籍卡 · 类型卡（全部/路人/核心/特殊）
 *  - 角色卡：点声线标签=试听+更换；点选+长按=修改/删除/合并+跟随
 *  - 编辑：主名/别名(管理)/类型(自动【第N章】)/性别/年龄/声线
 *  - 别名管理：修改 / 入库（裸属/特殊词库）/ 释放并固定（含剧本回放）
 */
@Composable
fun CharacterManageRouteScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    CharacterManageScreen(
        app = context.applicationContext as Application,
        onBack = onBackClick,
    )
}

private data class JoinLibRequest(
    val words: List<String>,
    val aliasToRemove: String? = null,
)

private val ROLES = listOf("全部", "路人", "核心", "特殊")
private val MALE_AGES = listOf("男童", "少年", "男青年", "男中年", "男老年")
private val FEMALE_AGES = listOf("女童", "少女", "女青年", "女中年", "女老年")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterManageScreen(app: Application, onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(app) { ReadAloudDataRepository(app) }
    val centerRepo = remember(app) { TtsServerCenterRepository(app) }
    val scope = rememberCoroutineScope()
    val player = remember { MediaPlayer() }

    var bookList by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentBook by remember { mutableStateOf("默认") }
    var records by remember { mutableStateOf<List<CharacterRecord>>(emptyList()) }

    var roleFilter by remember { mutableStateOf("全部") }
    var searchMode by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    var sel by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var mergeTargetPick by remember { mutableStateOf(false) }
    var showBookMenu by remember { mutableStateOf(false) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var auditionTextInput by remember { mutableStateOf("") }
    var auditionTextDialog by remember { mutableStateOf(false) }

    // 编辑弹窗
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var edName by remember { mutableStateOf("") }
    var edAliases by remember { mutableStateOf("") }
    var edRole by remember { mutableStateOf("核心") }
    var edGender by remember { mutableStateOf("男") }
    var edAge by remember { mutableStateOf("男青年") }
    var edVoice by remember { mutableStateOf("") }
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

    fun reload() {
        scope.launch {
            val st = repo.loadState()
            bookList = st.bookList
            currentBook = st.currentBook
            records = st.records
            sel = emptySet()
        }
    }

    LaunchedEffect(Unit) {
        reload()
        roleFilter = repo.loadCharacterFilter()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.release() }
        }
    }

    fun parseAliases(s: String): List<String> =
        s.split("|").map { it.trim() }.filter { it.isNotEmpty() }

    fun openEdit(idx: Int) {
        val r = records.getOrNull(idx) ?: return
        editIdx = idx
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

    fun commitEdit() {
        val idx = editIdx ?: return
        val target = records.getOrNull(idx) ?: return
        val oldName = target.name
        val newName = edName.trim()
        if (newName.isBlank()) {
            context.toastOnUi("主名不能为空")
            return
        }
        val actualAge = if (edRole == "特殊") "系统" else edAge
        scope.launch {
            target.name = newName
            target.aliases = edAliases
            target.roletype = edRole
            target.gender = edGender
            target.age = actualAge
            target.voice = edVoice
            repo.saveRecords(currentBook, records)
            var syncCount = 0
            if (oldName != newName) {
                syncCount = repo.rewriteMarkersAll(currentBook, oldName, newName, false).replaced
                repo.renameMergeLogTokens(currentBook, oldName, newName)
            }
            context.toastOnUi(
                if (syncCount > 0) "已保存角色信息（剧本同步 $syncCount 处）" else "已保存角色信息"
            )
            editIdx = null
            mismatchConfirm = false
            reload()
        }
    }

    fun saveEditWithCheck() {
        val idx = editIdx ?: return
        val target = records.getOrNull(idx) ?: return
        val newName = edName.trim()
        if (newName.isBlank()) {
            context.toastOnUi("主名不能为空")
            return
        }
        // 类型切换自动标记（核心/特殊→路人 加【第N章】；路人→核心/特殊 去【第N章】）
        val oldType = target.roletype.ifBlank { "核心" }
        var name = newName
        if (oldType != "路人" && edRole == "路人") {
            if (!name.contains("【第")) {
                var lc = target.lastAppearanceChapter
                if (lc < 0 && target.appearanceChapters.isNotEmpty()) {
                    lc = target.appearanceChapters.last()
                }
                name = name + "【第" + (if (lc >= 0) lc + 1 else 1) + "章】"
            }
        } else if (oldType == "路人" && edRole != "路人") {
            if (name.length > 3 && name.endsWith("】") && name.contains("【第")) {
                val mark = name.lastIndexOf("【第")
                if (mark > 0) name = name.substring(0, mark)
            }
        }
        edName = name
        val actualAge = if (edRole == "特殊") "系统" else edAge
        val expected = repo.expectedVoicePrefix(edRole, edGender, actualAge)
        val prefix = repo.voiceAgePrefix(edVoice)
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
        val list = records.toMutableList()
        sel.sortedDescending().forEach { if (it in list.indices) list.removeAt(it) }
        scope.launch {
            repo.saveRecords(currentBook, list)
            context.toastOnUi("已删除 ${sel.size} 个角色")
            confirmDelete = false
            reload()
        }
    }

    fun performMerge(targetIdx: Int) {
        val target = records.getOrNull(targetIdx) ?: return
        val followers = sel.filter { it != targetIdx }.mapNotNull { records.getOrNull(it) }
        if (followers.isEmpty()) {
            context.toastOnUi("请标记至少一个要合并的角色")
            return
        }
        scope.launch {
            val aliasArr = LinkedHashSet<String>()
            if (target.name.isNotBlank()) aliasArr.add(target.name)
            aliasArr.addAll(parseAliases(target.aliases))
            followers.forEach { f ->
                if (f.name.isNotBlank()) aliasArr.add(f.name)
                aliasArr.addAll(parseAliases(f.aliases))
            }
            target.aliases = aliasArr.joinToString("|")
            val newList = records.filterNot { it in followers }
            repo.saveRecords(currentBook, newList)
            var synced = 0
            followers.forEach { f ->
                val from = f.name.trim()
                if (from.isNotEmpty() && from != target.name.trim()) {
                    synced += repo.rewriteMarkersAll(currentBook, from, target.name.trim(), true).replaced
                }
            }
            context.toastOnUi(
                if (synced > 0) "角色合并成功（剧本同步 $synced 行）" else "角色合并成功"
            )
            reload()
        }
    }

    fun releaseAlias(alias: String) {
        val idx = editIdx ?: return
        val target = records.getOrNull(idx) ?: return
        val ageStore = if (target.roletype == "特殊") "系统" else target.age.ifBlank { "男青年" }
        picker = VoiceTagPickRequest(
            roletype = "核心",
            gender = edGender,
            age = ageStore,
            title = "释放并固定：选声线（$alias）",
        ) { selectedVoice ->
            scope.launch {
                val parentIndex = editIdx ?: return@launch
                val parent = records.getOrNull(parentIndex) ?: return@launch
                var curAliases = parseAliases(edAliases).filterNot { it == alias }
                var curName = edName
                if (curName == alias) {
                    if (curAliases.isNotEmpty()) {
                        curName = curAliases[0]
                        curAliases = curAliases.filterNot { it == curName }
                    } else {
                        context.toastOnUi("不能释放当前主名")
                        return@launch
                    }
                }
                val extraAliases = repo.collectMergeExtraAliases(currentBook, alias)
                val dropAll = (listOf(alias) + extraAliases).toSet()
                val remaining = curAliases.filterNot { it in dropAll }
                parent.name = curName
                parent.aliases = remaining.joinToString("|")

                val list = records.toMutableList()
                val existingIdx =
                    list.indexOfFirst { it !== parent && it.name.trim() == alias }
                if (existingIdx >= 0) {
                    val ex = list[existingIdx]
                    ex.voice = selectedVoice
                    if (extraAliases.isNotEmpty()) {
                        ex.aliases = (parseAliases(ex.aliases) + extraAliases)
                            .distinct().joinToString("|")
                    }
                } else {
                    val newRec = CharacterRecord(
                        name = alias,
                        aliases = extraAliases.joinToString("|"),
                        roletype = "核心",
                        gender = edGender,
                        voice = selectedVoice,
                    )
                    val vPrefix = repo.voiceAgePrefix(selectedVoice)
                    newRec.age = if (vPrefix.isNotEmpty() && !vPrefix.contains("特殊") &&
                        !vPrefix.contains("路人")
                    ) {
                        vPrefix
                    } else {
                        if (parent.roletype == "特殊") "系统" else parent.age
                    }
                    list.add((parentIndex + 1).coerceAtMost(list.size), newRec)
                }
                repo.saveRecords(currentBook, list)
                val replay = repo.replayMergeOps(currentBook, alias)
                var msg = "已释放并固定：$alias → $selectedVoice"
                if (replay.replaced > 0 || replay.chapters.isNotEmpty()) {
                    msg += "（剧本回放 ${replay.replaced} 行"
                    if (replay.missed > 0) msg += "，未命中 ${replay.missed}"
                    msg += "）"
                }
                context.toastOnUi(msg)
                if (replay.viaWords.isNotEmpty()) {
                    joinLib = JoinLibRequest(replay.viaWords)
                }
                aliasSheet = false
                editIdx = null
                reload()
            }
        }
    }

    fun doAudition(tag: String) {
        if (tag.isBlank()) {
            context.toastOnUi("该角色未设置声线")
            return
        }
        scope.launch {
            val groups = centerRepo.loadGroups()
            val entry = groups.flatMap { it.entries }.firstOrNull { it.tag == tag }
            if (entry == null) {
                context.toastOnUi("当前声线库中没有标签「$tag」")
                return@launch
            }
            val out = centerRepo.auditionDetailed(entry.tagRuleId, tag, auditionText)
            if (out.ok && !out.path.isNullOrBlank()) {
                runCatching {
                    player.reset()
                    player.setDataSource(out.path)
                    player.prepare()
                    player.start()
                }.onFailure {
                    context.toastOnUi("播放失败：${it.localizedMessage}")
                }
            } else {
                context.toastOnUi("试听失败：${out.message.ifBlank { "合成失败" }}")
            }
        }
    }

    // ---------------- 界面 ----------------

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val filtered = records.withIndex().filter { (_, r) ->
        (roleFilter == "全部" || r.roletype == roleFilter) && run {
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
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "角色管理",
                subtitle = "当前书：$currentBook · ${records.size} 个角色",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
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
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "cards") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
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
                            bookList.forEach { b ->
                                RoundDropdownMenuItem(
                                    text = b,
                                    onClick = {
                                        dismiss()
                                        if (b != currentBook) {
                                            scope.launch {
                                                val (_, msg) = repo.switchBook(b)
                                                context.toastOnUi(msg)
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
                                        text = roleFilter,
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
                                        roleFilter = t
                                        sel = emptySet()
                                        scope.launch { repo.saveCharacterFilter(t) }
                                    },
                                    trailingIcon = if (t == roleFilter) {
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
                        placeholder = "搜索：标签 / 名字 / 性别 / 年龄 / 声线",
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }

            if (records.isEmpty()) {
                item(key = "empty") {
                    TinyClickableSettingItem(
                        title = "暂无角色数据",
                        description = "朗读分析产出后自动出现（当前书：$currentBook）",
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
            if (sel.isNotEmpty()) {
                SelectionBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
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
        }
    }

    // ---------------- 合并与跟随（选择跟随角色） ----------------
    AppModalBottomSheet(
        show = mergeTargetPick,
        onDismissRequest = { mergeTargetPick = false },
        title = "选择跟随角色",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            sel.sorted().forEach { idx ->
                val r = records.getOrNull(idx) ?: return@forEach
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
        show = editIdx != null,
        onDismissRequest = { editIdx = null },
        title = "角色信息",
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // 主名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextField(
                    value = edName,
                    onValueChange = { edName = it },
                    label = "主名",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                SmallPlainButton(
                    onClick = { joinLib = JoinLibRequest(listOf(edName.trim())) },
                    icon = Icons.Default.FileDownload,
                    contentDescription = "入库",
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 别名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    AppText("别名", style = LegadoTheme.typography.labelSmall)
                    AppText(
                        text = run {
                            val a = parseAliases(edAliases)
                            if (a.isEmpty()) "无" else a.joinToString("、") + "（${a.size}个）"
                        },
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                SmallPlainButton(
                    onClick = { aliasSheet = true },
                    icon = Icons.Default.Edit,
                    contentDescription = "管理别名",
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            // 类型
            ChoiceRow(
                label = "类型",
                options = listOf("核心", "特殊", "路人"),
                selected = edRole,
                onSelect = {
                    edRole = it
                    if (it == "特殊") {
                        edAge = "系统"
                    } else if (edAge == "系统") {
                        edAge = if (edGender == "女") "女青年" else "男青年"
                    }
                },
            )
            // 性别
            ChoiceRow(
                label = "性别",
                options = listOf("男", "女"),
                selected = edGender,
                onSelect = { g ->
                    edGender = g
                    val ages = if (g == "女") FEMALE_AGES else MALE_AGES
                    if (edRole != "特殊" && edAge !in ages) {
                        edAge = if (g == "女") "女青年" else "男青年"
                    }
                },
            )
            // 年龄
            ChoiceRow(
                label = "年龄",
                options = if (edRole == "特殊") listOf("系统") else {
                    if (edGender == "女") FEMALE_AGES else MALE_AGES
                },
                selected = edAge,
                onSelect = { edAge = it },
            )
            // 声线
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
                VoiceChip(edVoice) {
                    val actualAge = if (edRole == "特殊") "系统" else edAge
                    picker = VoiceTagPickRequest(edRole, edGender, actualAge, "选择声线") {
                        edVoice = it
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            TinyClickableSettingItem(
                title = "保存",
                onClick = { saveEditWithCheck() },
            )
        }
    }

    // ---------------- 别名管理 ----------------
    AppModalBottomSheet(
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
            val aliases = parseAliases(edAliases)
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
                                aliasRenameTarget = a
                                aliasRenameInput = a
                            },
                            icon = Icons.Default.Edit,
                            contentDescription = "修改",
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        SmallPlainButton(
                            onClick = {
                                joinLib = JoinLibRequest(listOf(a), aliasToRemove = a)
                            },
                            icon = Icons.Default.ArrowDownward,
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
                                edAliases = parseAliases(edAliases)
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
                val cur = parseAliases(edAliases)
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
                edAliases = parseAliases(edAliases)
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
        repo = repo,
        onDismiss = { picker = null },
    )

    // ---------------- 发音人（试听 / 更换声线） ----------------
    AppModalBottomSheet(
        show = auditionIdx != null,
        onDismissRequest = { auditionIdx = null },
        title = "发音人",
    ) {
        val r = records.getOrNull(auditionIdx ?: -1)
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
                            r.voice = v
                            scope.launch {
                                repo.saveRecords(currentBook, records)
                                context.toastOnUi("声线已更新：$v")
                                reload()
                            }
                        }
                    },
                )
            }
        }
    }

    // ---------------- 入库（裸属/特殊词库） ----------------
    AppModalBottomSheet(
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
                        scope.launch {
                            val n = repo.appendWordLibrary("bare_words.json", req.words)
                            req.aliasToRemove?.let { rm ->
                                edAliases = parseAliases(edAliases)
                                    .filterNot { it in req.words }.joinToString("|")
                            }
                            context.toastOnUi("已加入裸属类词库（新增 $n 个）")
                            joinLib = null
                        }
                    },
                )
                TinyClickableSettingItem(
                    title = "特殊类",
                    onClick = {
                        scope.launch {
                            val n = repo.appendWordLibrary("special_words.json", req.words)
                            req.aliasToRemove?.let { rm ->
                                edAliases = parseAliases(edAliases)
                                    .filterNot { it in req.words }.joinToString("|")
                            }
                            context.toastOnUi("已加入特殊类词库（新增 $n 个）")
                            joinLib = null
                        }
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
            repo.expectedVoicePrefix(edRole, edGender, if (edRole == "特殊") "系统" else edAge)
        }」）不一致。\n\n确认：保留该音色直接保存\n取消：重新选择匹配的音色",
        confirmText = "确认",
        onConfirm = { commitEdit() },
        dismissText = "取消",
        onDismiss = {
            mismatchConfirm = false
            val actualAge = if (edRole == "特殊") "系统" else edAge
            picker = VoiceTagPickRequest(edRole, edGender, actualAge, "选择声线") {
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
    record: CharacterRecord,
    selectionActive: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onVoiceClick: () -> Unit,
) {
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionActive) {
                AppCheckbox(
                    checked = selected,
                    onCheckedChange = null,
                    includeStateSemantics = false,
                )
                Spacer(modifier = Modifier.width(8.dp))
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

private fun isNoiseAlias(alias: String, record: CharacterRecord): Boolean =
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

@Composable
private fun ChoiceRow(
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
            ChoiceTag(
                text = opt,
                selected = opt == selected,
                onClick = { onSelect(opt) },
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
    }
}

@Composable
private fun ChoiceTag(text: String, selected: Boolean, onClick: () -> Unit) {
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