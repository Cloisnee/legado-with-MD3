package io.legado.app.ui.book.readaloud.cache

import androidx.compose.runtime.Stable
import io.legado.app.constant.AppLog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** 朗读日志页分区：朗读分析流程 / 音频缓存 */
enum class TtsCacheTab { Analysis, Audio }

/** 章节音频任务状态（与「书架书籍」缓存页同构：等待/缓存中/暂停/失败） */
enum class AudioChapterState { Idle, Waiting, Downloading, Paused, Error }

@Stable
data class AudioChapterUi(
    val chapterIndex: Int,
    val title: String,
    val cached: Int,
    val total: Int,
    val sizeBytes: Long,
    val state: AudioChapterState = AudioChapterState.Idle,
    val progressLabel: String? = null,
    val progress: Float = 0f,
) {
    val missing: Int get() = (total - cached).coerceAtLeast(0)
    val isCached: Boolean get() = total > 0 && cached >= total
    val isDownloading: Boolean get() = state == AudioChapterState.Downloading
    val isWaiting: Boolean get() = state == AudioChapterState.Waiting
    val isPaused: Boolean get() = state == AudioChapterState.Paused
    val isError: Boolean get() = state == AudioChapterState.Error
}

@Stable
data class AudioBookUi(
    val book: String,
    val author: String,
    val cached: Int,
    val total: Int,
    val sizeBytes: Long,
    val chapters: ImmutableList<AudioChapterUi>,
) {
    val progress: Float get() = if (total <= 0) 0f else cached.toFloat() / total
    val cachedCount: Int get() = chapters.count { it.isCached }
    val totalCount: Int get() = chapters.size
    val waitingCount: Int get() = chapters.count { it.isWaiting }
    val downloadingCount: Int get() = chapters.count { it.isDownloading }
    val pausedCount: Int get() = chapters.count { it.isPaused }
    val errorCount: Int get() = chapters.count { it.isError }
    val hasActiveDownload: Boolean get() = waitingCount > 0 || downloadingCount > 0
    val isPaused: Boolean get() = pausedCount > 0
    val hasDownloadTask: Boolean get() = hasActiveDownload || isPaused

    fun recalc(): AudioBookUi = copy(
        cached = chapters.sumOf { it.cached },
        total = chapters.sumOf { it.total },
        sizeBytes = chapters.sumOf { it.sizeBytes },
    )
}

@Stable
data class TtsCacheUiState(
    val loading: Boolean = true,
    // ---- 音频缓存（书籍/章节视图） ----
    val books: ImmutableList<AudioBookUi> = persistentListOf(),
    val expandedBooks: ImmutableSet<String> = persistentSetOf(),
    /** 顶栏/卡片用进度摘要（空 = 无任务） */
    val audioQueueSummary: String = "",
    // ---- 朗读日志（按时间升序，实时刷新） ----
    val logs: ImmutableList<TtsLogEntryUi> = persistentListOf(),
    val activeTab: TtsCacheTab = TtsCacheTab.Analysis,
    val isSearch: Boolean = false,
    val searchKey: String = "",
    val selectedIds: ImmutableSet<Long> = persistentSetOf(),
    val expandedIds: ImmutableSet<Long> = persistentSetOf(),
    val activeDialog: TtsCacheDialog? = null,
)

@Stable
data class TtsLogEntryUi(
    val id: Long,
    val timestamp: Long,
    val message: String,
    val hasError: Boolean,
    val fullContent: String,
    val category: AppLog.Category,
)

sealed interface TtsCacheIntent {
    // ---- 音频缓存 ----
    data object LoadAudioCache : TtsCacheIntent
    data class ToggleBookExpanded(val book: String) : TtsCacheIntent
    data class CacheChapter(val book: String, val chapterIndex: Int) : TtsCacheIntent
    data class StopChapter(val book: String, val chapterIndex: Int) : TtsCacheIntent
    data class CacheBook(val book: String) : TtsCacheIntent
    data class StopBook(val book: String) : TtsCacheIntent
    data class ShowDeleteBookDialog(val book: String) : TtsCacheIntent
    data class ShowDeleteChapterDialog(val book: String, val chapterIndex: Int) : TtsCacheIntent
    data class DeleteBookAudio(val book: String) : TtsCacheIntent
    data class DeleteChapterAudio(val book: String, val chapterIndex: Int) : TtsCacheIntent

    // ---- 朗读日志页 ----
    data object ShowClearLogsDialog : TtsCacheIntent
    data object ClearLogs : TtsCacheIntent
    data object DismissDialog : TtsCacheIntent
    data class SelectTab(val tab: TtsCacheTab) : TtsCacheIntent
    data class SetSearchMode(val isSearch: Boolean) : TtsCacheIntent
    data class SetSearchKey(val key: String) : TtsCacheIntent
    data class ToggleSelection(val id: Long) : TtsCacheIntent
    data class SetSelection(val ids: Set<Long>) : TtsCacheIntent
    data class ToggleExpand(val id: Long) : TtsCacheIntent
}

sealed interface TtsCacheEffect {
    data class ShowToast(val message: String) : TtsCacheEffect
}

sealed interface TtsCacheDialog {
    data object ClearLogs : TtsCacheDialog
    data class DeleteBookAudio(val book: String) : TtsCacheDialog
    data class DeleteChapterAudio(val book: String, val chapterIndex: Int) : TtsCacheDialog
}
