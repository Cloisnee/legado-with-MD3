package io.legado.app.ui.ttssrv

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.readaloud.audio.AudioLibrary
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

/**
 * B33.2 · 音频库管理页（浏览 / 搜索 / 分类 / 试听 / 重新扫描 / 删除）。
 * 后续批次在此页扩展：zip 直读导入、远程下载、缺失清单（B33.2b/2c）。
 */
@Composable
fun AudioLibraryRouteScreen(onBackClick: () -> Unit) {
    AudioLibraryScreen(onBack = onBackClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var assets by remember { mutableStateOf<List<AudioLibrary.AudioAsset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var rescanning by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<AudioLibrary.AudioAsset?>(null) }
    var preview by remember { mutableStateOf<ExoPlayer?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            assets = AudioLibrary.assets(context.applicationContext)
            loading = false
        }
    }

    fun stopPreview() {
        preview?.let { p ->
            runCatching {
                p.stop()
                p.clearMediaItems()
            }
        }
        playingId = null
    }

    fun togglePreview(asset: AudioLibrary.AudioAsset) {
        if (playingId == asset.id) {
            stopPreview()
            return
        }
        val f = AudioLibrary.fileOf(context, asset)
        if (!f.isFile) {
            context.toastOnUi("文件不在库中（可重新扫描）")
            return
        }
        val player = preview ?: ExoPlayer.Builder(context).build().also {
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) playingId = null
                }
            })
            preview = it
        }
        runCatching {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            player.prepare()
            player.play()
        }.onFailure { context.toastOnUi("试听失败：${it.localizedMessage}") }
        playingId = asset.id
    }

    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) {
        onDispose {
            preview?.release()
            preview = null
        }
    }

    val tabTitles = remember(assets) {
        listOf("全部") + assets.map { it.category }.distinct().sorted()
    }
    val safeTab = tab.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
    val shown = remember(assets, query, safeTab, tabTitles) {
        val q = query.trim()
        val cat = tabTitles.getOrNull(safeTab)
        assets.filter { a ->
            (safeTab == 0 || a.category == cat) &&
                (q.isEmpty() ||
                    a.name.contains(q, ignoreCase = true) ||
                    a.aliases.any { it.contains(q, ignoreCase = true) })
        }
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "音频库管理",
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
                SplicedColumnGroup(title = "素材库") {
                    TinyClickableSettingItem(
                        title = if (rescanning) "正在扫描…" else "重新扫描素材库",
                        description = "共 ${assets.size} 条 · ${assets.sumOf { it.size } / 1024 / 1024} MB",
                        onClick = {
                            if (!rescanning) {
                                rescanning = true
                                scope.launch {
                                    val r = runCatching {
                                        AudioLibrary.rescan(context.applicationContext)
                                    }.getOrElse {
                                        rescanning = false
                                        context.toastOnUi("扫描失败：${it.localizedMessage}")
                                        return@launch
                                    }
                                    rescanning = false
                                    reload()
                                    context.toastOnUi(
                                        "扫描完成：共 ${r.total} 条（新增 ${r.added} · 移除 ${r.removed}）"
                                    )
                                }
                            }
                        },
                    )
                }
            }
            item {
                SearchBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "搜索素材名 / 别名",
                    autoFocus = false,
                )
            }
            item {
                AppTabRow(
                    tabTitles = tabTitles,
                    selectedTabIndex = safeTab,
                    onTabSelected = { tab = it },
                )
            }
            when {
                loading -> item {
                    TinyClickableSettingItem(title = "加载中…", onClick = {})
                }

                shown.isEmpty() -> item {
                    TinyClickableSettingItem(
                        title = "没有匹配的素材",
                        description = "可用上方「重新扫描」，或到朗读设置「准备示例素材」",
                        onClick = {},
                    )
                }

                else -> items(shown, key = { it.id }) { a ->
                    LibraryRow(
                        asset = a,
                        playing = playingId == a.id,
                        onPlay = { togglePreview(a) },
                        onDelete = { deleteTarget = a },
                    )
                }
            }
        }
    }

    AppAlertDialog(
        show = deleteTarget != null,
        onDismissRequest = { deleteTarget = null },
        title = "删除素材",
        text = "将从库中移除「${deleteTarget?.name.orEmpty()}」及其元数据，确定吗？",
        confirmText = "删除",
        onConfirm = {
            val t = deleteTarget
            deleteTarget = null
            if (t != null) {
                scope.launch {
                    val ok = AudioLibrary.removeAsset(context.applicationContext, t)
                    if (playingId == t.id) stopPreview()
                    context.toastOnUi(if (ok) "已删除" else "删除未完成")
                    reload()
                }
            }
        },
        onDismiss = { deleteTarget = null },
    )
}

@Composable
private fun LibraryRow(
    asset: AudioLibrary.AudioAsset,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = asset.name,
                style = LegadoTheme.typography.bodyMedium,
            )
            AppText(
                text = buildString {
                    append(asset.category)
                    if (asset.size > 0) {
                        append(" · ")
                        if (asset.size >= 1024 * 1024) {
                            append("${asset.size / 1024 / 1024} MB")
                        } else {
                            append("${asset.size / 1024} KB")
                        }
                    }
                    if (asset.source == AudioLibrary.SOURCE_GENERATED) append(" · 合成")
                },
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                imageVector = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = "试听",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "删除",
            )
        }
    }
}
