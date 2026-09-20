package io.legado.app.ui.book.cache.manage

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.readaloud.cache.AudioBookUi
import io.legado.app.ui.book.readaloud.cache.AudioChapterUi
import io.legado.app.ui.book.readaloud.cache.TtsCacheViewModel
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.text.AppText

/**
 * 「书籍音频」分区：与「书架书籍」缓存分区同款 UI（卡片 + 章节行 + 状态/动画）。
 *
 * 数据来源：持久化音频目录（已合成）+ 本地剧本（总条数）+ Room（章节标题/作者）。
 */
fun LazyListScope.audioCacheSection(
    books: List<AudioBookUi>,
    expandedBooks: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onCacheBook: (AudioBookUi) -> Unit,
    onStopBook: (AudioBookUi) -> Unit,
    onDeleteBook: (AudioBookUi) -> Unit,
    onCacheChapter: (String, Int) -> Unit,
    onStopChapter: (String, Int) -> Unit,
    onDeleteChapter: (String, Int) -> Unit,
) {
    item(key = "audio-header") {
        AppText(
            text = stringResource(R.string.tts_audio_books_section),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = LegadoTheme.typography.titleSmallEmphasized,
            color = LegadoTheme.colorScheme.primary,
        )
    }
    if (books.isEmpty()) {
        item(key = "audio-empty") {
            TextCard(
                text = stringResource(R.string.tts_audio_books_empty),
                modifier = Modifier.fillMaxWidth(),
                verticalPadding = 12.dp,
                horizontalPadding = 12.dp,
            )
        }
    } else {
        books.forEach { item ->
            item(key = "audio-book-${item.book}") {
                AudioCacheBookCard(
                    item = item,
                    expanded = item.book in expandedBooks,
                    onToggleExpanded = { onToggleExpanded(item.book) },
                    onCacheBook = { onCacheBook(item) },
                    onStopBook = { onStopBook(item) },
                    onDeleteBook = { onDeleteBook(item) },
                )
            }
            if (item.book in expandedBooks) {
                items(
                    items = item.chapters,
                    key = { chapter -> "audio-chapter-${item.book}-${chapter.chapterIndex}" },
                ) { chapter ->
                    AudioCacheChapterRow(
                        item = chapter,
                        onCache = { onCacheChapter(item.book, chapter.chapterIndex) },
                        onStop = { onStopChapter(item.book, chapter.chapterIndex) },
                        onDelete = { onDeleteChapter(item.book, chapter.chapterIndex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioCacheBookCard(
    item: AudioBookUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCacheBook: () -> Unit,
    onStopBook: () -> Unit,
    onDeleteBook: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "AudioBookExpandArrow",
    )
    val progressPercent = (item.progress * 100).toInt().coerceIn(0, 100)
    val statusSummary = stringResource(
        R.string.cache_download_status_summary,
        item.downloadingCount,
        item.waitingCount,
        item.pausedCount,
        item.errorCount,
    )
    val progressDescription = stringResource(
        R.string.cache_progress_description,
        item.cachedCount,
        item.totalCount,
        progressPercent,
    )
    val expandedState = stringResource(
        if (expanded) R.string.a11y_expanded else R.string.a11y_collapsed
    )
    val bookDescription = stringResource(
        R.string.a11y_cache_book_item,
        item.book,
        item.author,
        progressDescription,
        statusSummary,
    )
    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = bookDescription
                stateDescription = expandedState
                role = Role.Button
            },
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
                        text = item.author.ifBlank { TtsCacheViewModel.formatSize(item.sizeBytes) },
                        style = LegadoTheme.typography.labelSmallEmphasized,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextCard(
                    text = "${item.cachedCount}/${item.totalCount}",
                    backgroundColor = LegadoTheme.colorScheme.cardContainer,
                )
            }
            AppLinearProgressIndicator(
                progress = item.progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = statusSummary,
                    modifier = Modifier.weight(1f),
                    style = LegadoTheme.typography.labelMediumEmphasized,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                if (item.hasDownloadTask || item.cachedCount < item.totalCount) {
                    SmallTonalButton(
                        onClick = { if (item.hasActiveDownload) onStopBook() else onCacheBook() },
                        icon = if (item.hasActiveDownload) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = when {
                            item.hasActiveDownload -> stringResource(
                                R.string.pause_book_download,
                                item.book,
                            )

                            item.isPaused -> stringResource(
                                R.string.resume_book_download,
                                item.book,
                            )

                            else -> stringResource(R.string.start_book_download, item.book)
                        },
                    )
                }
                SmallTonalButton(
                    onClick = onDeleteBook,
                    icon = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete_book_cache, item.book),
                )
            }
        }
    }
}

@Composable
private fun AudioCacheChapterRow(
    item: AudioChapterUi,
    onCache: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    val chapterTitle = item.title.ifBlank { stringResource(R.string.tts_audio_chapter, item.chapterIndex + 1) }
    val statusText = audioChapterStatusText(item)
    val chapterDescription = stringResource(
        R.string.a11y_cache_chapter_item,
        chapterTitle,
        statusText,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = chapterDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = chapterTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = LegadoTheme.typography.titleSmallEmphasized,
            )
            AppText(
                text = statusText,
                maxLines = 1,
                style = LegadoTheme.typography.labelSmall,
                color = when {
                    item.isError -> LegadoTheme.colorScheme.error
                    item.isCached -> LegadoTheme.colorScheme.primary
                    else -> LegadoTheme.colorScheme.onSurfaceVariant
                },
            )
            if (item.isDownloading) {
                AppLinearProgressIndicator(
                    progress = item.progress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
        }
        if (item.isWaiting || item.isDownloading) {
            SmallTonalButton(
                onClick = onStop,
                icon = Icons.Default.Stop,
                contentDescription = stringResource(R.string.pause_chapter_download, chapterTitle),
            )
        } else if (!item.isCached) {
            SmallTonalButton(
                onClick = onCache,
                icon = Icons.Default.Download,
                contentDescription = if (item.isPaused) {
                    stringResource(R.string.resume_chapter_download, chapterTitle)
                } else {
                    stringResource(R.string.download_chapter, chapterTitle)
                },
            )
        }
        SmallTonalButton(
            onClick = onDelete,
            icon = Icons.Default.Delete,
            contentDescription = stringResource(R.string.delete_chapter_cache, chapterTitle),
        )
    }
}

@Composable
private fun audioChapterStatusText(item: AudioChapterUi): String = when {
    item.isDownloading -> item.progressLabel ?: stringResource(R.string.downloading)
    item.isWaiting -> stringResource(R.string.wait_download)
    item.isPaused -> stringResource(R.string.download_paused)
    item.isError -> item.progressLabel ?: stringResource(R.string.download_error)
    item.isCached -> stringResource(R.string.download_success)
    else -> stringResource(R.string.not_cached)
}
