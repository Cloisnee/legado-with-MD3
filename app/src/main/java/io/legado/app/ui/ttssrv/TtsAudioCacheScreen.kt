package io.legado.app.ui.ttssrv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.ui.book.readaloud.cache.TtsCacheDialog
import io.legado.app.ui.book.readaloud.cache.TtsCacheEffect
import io.legado.app.ui.book.readaloud.cache.TtsCacheFileUi
import io.legado.app.ui.book.readaloud.cache.TtsCacheIntent
import io.legado.app.ui.book.readaloud.cache.TtsCacheUiState
import io.legado.app.ui.book.readaloud.cache.TtsCacheViewModel
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.log.LogDetailSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CacheMeta(val book: String, val bookUrl: String, val chapter: String)

private data class CacheChapterGroup(
    val chapter: String,
    val files: List<TtsCacheFileUi>,
    val totalSize: Long,
)

private data class CacheBookGroup(
    val book: String,
    val chapters: List<CacheChapterGroup>,
    val totalSize: Long,
    val count: Int,
)

/**
 * 朗读音频缓存（书籍 / 章节级视图）：
 *  - 依赖 HttpReadAloudService 写入的 tts_cache_meta.jsonl（文件名→书/章节）
 *  - 支持：删除本书 / 删除本章 / 单文件删除 / 清空全部
 *  - 未记录到书籍信息的缓存归入「未归属」
 */
@Composable
fun TtsAudioCacheRouteScreen(
    onBackClick: () -> Unit,
    viewModel: TtsCacheViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.onIntent(TtsCacheIntent.LoadCache)
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TtsCacheEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    TtsAudioCacheScreen(
        state = state,
        onIntent = viewModel::onIntent,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsAudioCacheScreen(
    state: TtsCacheUiState,
    onIntent: (TtsCacheIntent) -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    var metaMap by remember { mutableStateOf<Map<String, CacheMeta>>(emptyMap()) }

    fun metaFile(): File {
        val base = context.externalCacheDir ?: context.cacheDir
        return File(base, "httpTTS/tts_cache_meta.jsonl")
    }

    fun loadMeta() {
        scope.launch {
            metaMap = withContext(Dispatchers.IO) {
                runCatching {
                    val f = metaFile()
                    if (!f.exists()) return@runCatching emptyMap<String, CacheMeta>()
                    buildMap<String, CacheMeta> {
                        f.readLines().forEach { line ->
                            runCatching {
                                val o = JSONObject(line)
                                val name = o.optString("f")
                                if (name.isNotBlank()) {
                                    put(
                                        name,
                                        CacheMeta(
                                            book = o.optString("b"),
                                            bookUrl = o.optString("u"),
                                            chapter = o.optString("c"),
                                        )
                                    )
                                }
                            }
                        }
                    }
                }.getOrDefault(emptyMap())
            }
        }
    }

    LaunchedEffect(Unit) { loadMeta() }

    fun pruneMeta(names: Set<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = metaFile()
                    if (!f.exists()) return@runCatching
                    val kept = f.readLines().filter { line ->
                        val name =
                            runCatching { JSONObject(line).optString("f") }.getOrDefault("")
                        name.isNotBlank() && name !in names
                    }
                    f.writeText(
                        if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n")
                    )
                }
            }
            loadMeta()
        }
    }

    fun deleteFiles(files: List<TtsCacheFileUi>) {
        files.forEach { onIntent(TtsCacheIntent.DeleteFile(it.name)) }
        pruneMeta(files.map { it.name }.toSet())
    }

    val bookGroups: List<CacheBookGroup> = remember(state.files, metaMap) {
        state.files
            .groupBy { f -> metaMap[f.name]?.book?.takeIf { it.isNotBlank() } ?: "未归属（无书籍信息）" }
            .map { (book, files) ->
                val chapters = files
                    .groupBy { f -> metaMap[f.name]?.chapter?.takeIf { it.isNotBlank() } ?: "未知章节" }
                    .map { (ch, cf) -> CacheChapterGroup(ch, cf, cf.sumOf { it.sizeBytes }) }
                    .sortedBy { it.chapter }
                CacheBookGroup(book, chapters, files.sumOf { it.sizeBytes }, files.size)
            }
            .sortedBy { it.book }
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "朗读音频缓存",
                subtitle = "${TtsCacheViewModel.formatSize(state.totalSizeBytes)} · ${state.files.size} 个文件",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                },
            )
        },
        floatingActionButton = {
            if (state.files.isNotEmpty()) {
                AppFloatingActionButton(
                    onClick = { onIntent(TtsCacheIntent.ShowClearAllDialog) },
                    icon = Icons.Default.DeleteSweep,
                    tooltipText = "清空全部",
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding() + 4.dp,
                bottom = paddingValues.calculateBottomPadding() + 96.dp,
            ),
        ) {
            if (state.files.isEmpty() && !state.loading) {
                item {
                    TinyClickableSettingItem(
                        title = "暂无朗读音频缓存",
                        description = "播放朗读内容后会在此处生成缓存（含书籍/章节信息）",
                        onClick = {},
                    )
                }
            }
            bookGroups.forEach { bg ->
                item(key = "b_${bg.book}") {
                    TinyClickableSettingItem(
                        title = "📖 ${bg.book}",
                        description = "${TtsCacheViewModel.formatSize(bg.totalSize)} · ${bg.count} 个文件",
                        trailingContent = {
                            SmallPlainButton(
                                icon = Icons.Default.Delete,
                                contentDescription = "删除本书",
                                onClick = {
                                    deleteFiles(bg.chapters.flatMap { it.files })
                                },
                            )
                        },
                        onClick = {},
                    )
                }
                bg.chapters.forEach { cg ->
                    item(key = "c_${bg.book}|${cg.chapter}") {
                        TinyClickableSettingItem(
                            title = "§ ${cg.chapter}",
                            description = "${TtsCacheViewModel.formatSize(cg.totalSize)} · ${cg.files.size} 个文件",
                            trailingContent = {
                                SmallPlainButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "删除本章",
                                    onClick = { deleteFiles(cg.files) },
                                )
                            },
                            onClick = {},
                        )
                    }
                    items(cg.files, key = { it.name }) { file ->
                        val sizeText = TtsCacheViewModel.formatSize(file.sizeBytes)
                        val dateText = dateFormat.format(Date(file.lastModified))
                        val displayText = file.text.ifEmpty { file.name }
                        TinyClickableSettingItem(
                            title = "   $displayText",
                            description = "$sizeText · $dateText",
                            trailingContent = {
                                SmallPlainButton(
                                    icon = Icons.Default.Delete,
                                    contentDescription = "删除",
                                    onClick = { deleteFiles(listOf(file)) },
                                )
                            },
                            onClick = {
                                onIntent(
                                    TtsCacheIntent.ShowFileDetail(
                                        name = file.name,
                                        text = file.text,
                                        sizeBytes = file.sizeBytes,
                                        lastModified = file.lastModified,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    AppAlertDialog(
        show = state.activeDialog == TtsCacheDialog.ClearAll,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = "清空朗读音频缓存",
        text = "确认清空全部朗读音频缓存文件？",
        onConfirm = {
            onIntent(TtsCacheIntent.ClearAll)
            pruneMeta(state.files.map { it.name }.toSet())
        },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )

    LogDetailSheet(
        show = state.showDetail,
        title = state.detailTitle,
        content = state.detailContent,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDetail) },
    )
}
