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
data class TtsCacheUiState(
    val loading: Boolean = true,
    /** 音频缓存文件（书籍/章节视图，TtsAudioCacheScreen 使用） */
    val files: ImmutableList<TtsCacheFileUi> = persistentListOf(),
    val totalSizeBytes: Long = 0,
    /** 朗读日志（按时间升序，实时刷新） */
    val logs: ImmutableList<TtsLogEntryUi> = persistentListOf(),
    val activeTab: TtsCacheTab = TtsCacheTab.Analysis,
    val isSearch: Boolean = false,
    val searchKey: String = "",
    /** 多选中的日志 id（= AppLog.LogEntry.id） */
    val selectedIds: ImmutableSet<Long> = persistentSetOf(),
    /** 展开详情的日志 id */
    val expandedIds: ImmutableSet<Long> = persistentSetOf(),
    val activeDialog: TtsCacheDialog? = null,
    val detailTitle: String = "",
    val detailContent: String = "",
    val showDetail: Boolean = false,
)

@Stable
data class TtsCacheFileUi(
    val name: String,
    val text: String,
    val sizeBytes: Long,
    val lastModified: Long,
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
    data object LoadCache : TtsCacheIntent
    data class DeleteFile(val name: String) : TtsCacheIntent
    data object ClearAll : TtsCacheIntent
    data object ShowClearAllDialog : TtsCacheIntent
    data object ShowClearLogsDialog : TtsCacheIntent
    data object ClearLogs : TtsCacheIntent
    data object DismissDialog : TtsCacheIntent
    data class ShowFileDetail(
        val name: String,
        val text: String,
        val sizeBytes: Long,
        val lastModified: Long
    ) : TtsCacheIntent

    data object DismissDetail : TtsCacheIntent

    // ---- 朗读日志页交互 ----
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
    data object ClearAll : TtsCacheDialog
    data object ClearLogs : TtsCacheDialog
}
