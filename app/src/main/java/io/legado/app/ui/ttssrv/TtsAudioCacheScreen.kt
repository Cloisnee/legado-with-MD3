package io.legado.app.ui.ttssrv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.book.readaloud.cache.AudioBookUi
import io.legado.app.ui.book.readaloud.cache.AudioChapterUi
import io.legado.app.ui.book.readaloud.cache.AudioJobUi
import io.legado.app.ui.book.readaloud.cache.TtsCacheDialog
import io.legado.app.ui.book.readaloud.cache.TtsCacheEffect
import io.legado.app.ui.book.readaloud.cache.TtsCacheIntent
import io.legado.app.ui.book.readaloud.cache.TtsCacheUiState
import io.legado.app.ui.book.readaloud.cache.TtsCacheViewModel
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

/**
 * 音频管理：书籍大卡片（复刻书籍缓存管理页）+ 章节行 x/y（已合成/总数）。
 *
 *  - 左侧箭头展开按章节顺序排列的章节音频；
 *  - 书籍卡片右侧：下载=按本地剧本批量合成整本缺失音频；删除=删除本书全部音频；
 *  - 章节行右侧：下载=只合成该章缺失条目；删除=删除该章音频。
 */
@Composable
fun TtsAudioCacheRouteScreen(
    onBackClick: () -> Unit,
    viewModel: TtsCacheViewModel = koinViewModel(),
) {
    LaunchedEffect(Unit) {
        viewModel.onIntent(TtsCacheIntent.LoadAudioCache)
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TtsCacheEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }
    TtsAudioCacheScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
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
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val totalSize = state.books.sumOf { it.sizeBytes }
    val totalCached = state.books.sumOf { it.cached }
    val totalAll = state.books.sumOf { it.total }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.tts_audio_manage),
                subtitle = if (state.books.isEmpty()) {
                    null
                } else {
                    "${TtsCacheViewModel.formatSize(totalSize)} · $totalCached/$totalAll"
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding() + 4.dp,
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item(key = "books-header") {
                AppText(
                    text = stringResource(R.string.tts_audio_books_section),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = LegadoTheme.typography.titleSmallEmphasized,
                    color = LegadoTheme.colorScheme.primary,
                )
            }
            state.job?.let { job ->
                item(key = "job") {
                    AudioJobCard(job = job, onStop = { onIntent(TtsCacheIntent.StopJob) })
                }
            }
            if (state.books.isEmpty() && !state.loading) {
                item(key = "books-empty") {
                    TextCard(
                        text = stringResource(R.string.tts_audio_books_empty),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalPadding = 12.dp,
                        horizontalPadding = 12.dp,
                    )
                }
                item(key = "books-empty-summary") {
                    AppText(
                        text = stringResource(R.string.tts_audio_books_empty_summary),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.books.forEach { book ->
                val expanded = book.book in state.expandedBooks
                item(key = "book-${book.book}") {
                    AudioBookCard(
                        item = book,
                        expanded = expanded,
                        onToggleExpanded = {
                            onIntent(TtsCacheIntent.ToggleBookExpanded(book.book))
                        },
                        onCacheBook = { onIntent(TtsCacheIntent.CacheBook(book.book)) },
                        onDeleteBook = {
                            onIntent(TtsCacheIntent.ShowDeleteBookDialog(book.book))
                        },
                    )
                }
                if (expanded) {
                    book.chapters.forEach { chapter ->
                        item(key = "chapter-${book.book}-${chapter.chapterIndex}") {
                            AudioChapterRow(
                                item = chapter,
                                onCache = {
                                    onIntent(
                                        TtsCacheIntent.CacheChapter(
                                            book.book,
                                            chapter.chapterIndex,
                                        )
                                    )
                                },
                                onDelete = {
                                    onIntent(
                                        TtsCacheIntent.ShowDeleteChapterDialog(
                                            book.book,
                                            chapter.chapterIndex,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val dialog = state.activeDialog
    AppAlertDialog(
        show = dialog is TtsCacheDialog.DeleteBookAudio,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = stringResource(R.string.delete),
        text = stringResource(
            R.string.tts_audio_delete_book_message,
            (dialog as? TtsCacheDialog.DeleteBookAudio)?.book.orEmpty(),
        ),
        onConfirm = {
            (dialog as? TtsCacheDialog.DeleteBookAudio)?.let {
                onIntent(TtsCacheIntent.DeleteBookAudio(it.book))
            }
        },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )
    AppAlertDialog(
        show = dialog is TtsCacheDialog.DeleteChapterAudio,
        onDismissRequest = { onIntent(TtsCacheIntent.DismissDialog) },
        title = stringResource(R.string.delete),
        text = stringResource(
            R.string.tts_audio_delete_chapter_message,
            ((dialog as? TtsCacheDialog.DeleteChapterAudio)?.chapterIndex ?: 0) + 1,
        ),
        onConfirm = {
            (dialog as? TtsCacheDialog.DeleteChapterAudio)?.let {
                onIntent(TtsCacheIntent.DeleteChapterAudio(it.book, it.chapterIndex))
            }
        },
        onDismiss = { onIntent(TtsCacheIntent.DismissDialog) },
    )
}

@Composable
private fun AudioJobCard(job: AudioJobUi, onStop: () -> Unit) {
    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        containerColor = LegadoTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = if (job.chapterCount > 1) {
                    stringResource(
                        R.string.tts_audio_job_book,
                        job.chapterPosition,
                        job.chapterCount,
                        job.chapterDone,
                        job.chapterTotal,
                    )
                } else {
                    stringResource(
                        R.string.tts_audio_job_single,
                        job.chapterIndex + 1,
                        job.chapterDone,
                        job.chapterTotal,
                    )
                },
                modifier = Modifier.weight(1f),
                style = LegadoTheme.typography.labelMediumEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SmallTonalButton(
                onClick = onStop,
                icon = Icons.Default.Stop,
                contentDescription = stringResource(R.string.tts_audio_stop),
            )
        }
    }
}

@Composable
private fun AudioBookCard(
    item: AudioBookUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCacheBook: () -> Unit,
    onDeleteBook: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "AudioBookExpandArrow",
    )
    val progress = if (item.total <= 0) 0f else item.cached.toFloat() / item.total
    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        onClick = onToggleExpanded,
        containerColor = LegadoTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer(rotationZ = arrowRotation),
                )
                Column(modifier = Modifier.weight(1f)) {
                    AppText(
                        text = item.book,
                        style = LegadoTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AppText(
                        text = TtsCacheViewModel.formatSize(item.sizeBytes),
                        style = LegadoTheme.typography.labelSmallEmphasized,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextCard(
                    text = "${item.cached}/${item.total}",
                    backgroundColor = LegadoTheme.colorScheme.cardContainer,
                )
            }
            AppLinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = stringResource(
                        R.string.tts_audio_chapter_count,
                        item.chapters.size,
                    ),
                    modifier = Modifier.weight(1f),
                    style = LegadoTheme.typography.labelMediumEmphasized,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                SmallTonalButton(
                    onClick = onCacheBook,
                    icon = Icons.Default.Download,
                    contentDescription = stringResource(R.string.tts_audio_cache_book),
                )
                SmallTonalButton(
                    onClick = onDeleteBook,
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.tts_audio_delete_book),
                )
            }
        }
    }
}

@Composable
private fun AudioChapterRow(
    item: AudioChapterUi,
    onCache: () -> Unit,
    onDelete: () -> Unit,
) {
    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp),
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = stringResource(R.string.tts_audio_chapter, item.chapterIndex + 1),
                style = LegadoTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = "${item.cached}/${item.total}",
                modifier = Modifier.weight(1f),
                style = LegadoTheme.typography.labelMediumEmphasized,
                color = if (item.missing == 0) {
                    LegadoTheme.colorScheme.primary
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant
                },
            )
            if (item.missing > 0) {
                SmallTonalButton(
                    onClick = onCache,
                    icon = Icons.Default.Download,
                    contentDescription = stringResource(R.string.tts_audio_cache_missing),
                )
            }
            SmallTonalButton(
                onClick = onDelete,
                icon = Icons.Default.Delete,
                contentDescription = stringResource(R.string.tts_audio_delete_chapter),
            )
        }
    }
}
