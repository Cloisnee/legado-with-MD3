package io.legado.app.ui.book.readaloud.cache

import androidx.compose.runtime.Stable
import io.legado.app.constant.AppLog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** 朗读日志页分区：朗读分析流程 / 音频缓存 */
enum class TtsCacheTab { Analysis, Audio }

@Stable
data class AudioChapterUi(
    val chapterIndex: Int,
    val cached: Int,
    val total: Int,
    val sizeBytes: Long,
) {
    val missing: Int get() = (total - cached).coerceAtLeast(0)
}

@Stable
data class AudioBookUi(
    val book: String,
    val cached: Int,
    val total: Int,
    val sizeBytes: Long,
    val chapters: ImmutableList<AudioChapterUi>,
)

/** 批量合成进度（整本缓存时 chapterCount > 1） */
@Stable
data class AudioJobUi(
    val book: String,
    val chapterIndex: Int,
    val chapterDone: Int,
    val chapterTotal: Int,
    val chapterPosition: Int,
    val chapterCount: Int,
)

@Stable
data class TtsCacheUiState(
    val loading: Boolean = true,
    // ---- 音频缓存（书籍/章节视图） ----
    val books: ImmutableList<AudioBookUi> = persistentListOf(),
    val expandedBooks: ImmutableSet<String> = persistentSetOf(),
    val job: AudioJobUi? = null,
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
    data class CacheBook(val book: String) : TtsCacheIntent
    data object StopJob : TtsCacheIntent
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
