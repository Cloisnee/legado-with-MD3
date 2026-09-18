package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Stable
import io.legado.app.ui.widget.components.player.PlayerChapterUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class ReadAloudTextLineUi(
    val text: String,
    val chapterPosition: Int,
)

@Stable
data class ReadAloudPlayerUiState(
    val bookUrl: String = "",
    val bookName: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val sourceOrigin: String? = null,
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val chapters: ImmutableList<PlayerChapterUi> = persistentListOf(),
    val chapterText: String = "",
    val textLines: ImmutableList<ReadAloudTextLineUi> = persistentListOf(),
    val activeTextLine: Int = -1,
    val currentText: String = "",
    val nextText: String = "",
    val chapterPosition: Int = 0,
    val chapterLength: Int = 1,
    val engineName: String = "",
    val speakerName: String = "",
    val isPaused: Boolean = false,
    val speed: Int = 10,
    val timerMinutes: Int = 0,
    val finishCurrentChapterAfterTimer: Boolean = false,
    val bgMode: Int = 0,
    val activeSheet: ReadAloudPlayerSheet? = null,
    val scriptReview: ScriptReviewUi? = null,
)

sealed interface ReadAloudPlayerSheet {
    data object Speed : ReadAloudPlayerSheet
    data object Timer : ReadAloudPlayerSheet
    data object ScriptReview : ReadAloudPlayerSheet
}

/** 批次C · 二合一审查页数据 */
@Stable
data class ScriptProfileUi(
    val id: String,
    val name: String,
    val aliasLine: String,
)

@Stable
data class ScriptRowUi(
    val id: String,
    val indexLabel: String,
    val text: String,
    val speakerName: String,
    val roleLabel: String,
    val isCharacter: Boolean,
    val emotion: String,
    val locked: Boolean,
    val selected: Boolean,
)

@Stable
data class ScriptReviewUi(
    val loading: Boolean = false,
    val hasData: Boolean = false,
    val sourceLabel: String = "",
    val statusLine: String = "",
    val rows: ImmutableList<ScriptRowUi> = persistentListOf(),
    val profiles: ImmutableList<ScriptProfileUi> = persistentListOf(),
    val selectedIds: Set<String> = emptySet(),
)

sealed interface ReadAloudPlayerIntent {
    data object Refresh : ReadAloudPlayerIntent
    data object TogglePause : ReadAloudPlayerIntent
    data object PreviousChapter : ReadAloudPlayerIntent
    data object NextChapter : ReadAloudPlayerIntent
    data object PreviousParagraph : ReadAloudPlayerIntent
    data object NextParagraph : ReadAloudPlayerIntent
    data object OpenSettings : ReadAloudPlayerIntent
    data object OpenReadAloudLogs : ReadAloudPlayerIntent
    data object SwitchToClassic : ReadAloudPlayerIntent
    data object CycleBgMode : ReadAloudPlayerIntent
    data class SelectChapter(val index: Int) : ReadAloudPlayerIntent
    data class SetBgMode(val value: Int) : ReadAloudPlayerIntent
    data class SetSpeed(val value: Int) : ReadAloudPlayerIntent
    data class SetTimer(val minutes: Int) : ReadAloudPlayerIntent
    data class SetFinishCurrentChapterAfterTimer(val value: Boolean) : ReadAloudPlayerIntent
    data class OpenSheet(val sheet: ReadAloudPlayerSheet) : ReadAloudPlayerIntent
    data object DismissSheet : ReadAloudPlayerIntent
    data class SeekTo(val chapterPosition: Int) : ReadAloudPlayerIntent
    // 批次C · 审查页
    data object LoadScriptReview : ReadAloudPlayerIntent
    data class ToggleScriptRow(val id: String) : ReadAloudPlayerIntent
    data object ClearScriptSelection : ReadAloudPlayerIntent
    data class SetScriptNarration(val ids: Set<String>) : ReadAloudPlayerIntent
    data class AssignScriptSpeaker(
        val ids: Set<String>,
        val profileId: String?,
        val customName: String,
    ) : ReadAloudPlayerIntent
    data class ToggleScriptLock(val id: String) : ReadAloudPlayerIntent
    data object ReanalyzeScript : ReadAloudPlayerIntent
}

sealed interface ReadAloudPlayerEffect {
    data object ReturnToReaderSettings : ReadAloudPlayerEffect
    data object OpenReadAloudLogs : ReadAloudPlayerEffect
    data object ReturnToClassic : ReadAloudPlayerEffect
}
