package io.legado.app.ui.book.readaloud.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.compatDsInt
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.widget.components.player.PlayerChapterUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadAloudPlayerViewModel(
    private val coordinator: ReadAloudPlayerCoordinator,
    private val readAloudSettingsGateway: ReadAloudSettingsGateway,
) : ViewModel() {

    private val activeSheet = MutableStateFlow<ReadAloudPlayerSheet?>(null)

    /** 悬浮胶囊显隐（全局叠层用；直读设置源）。 */
    val showReadAloudCapsule = readAloudSettingsGateway.settings
        .map { it.showReadAloudCapsule }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = readAloudSettingsGateway.currentSettings.showReadAloudCapsule,
        )

    val uiState = combine(
        coordinator.state,
        AppConfigStore.observeInt(PreferKey.readAloudPlayerBgMode),
        activeSheet,
    ) { source, bgMode, sheet ->
        toUiState(source, bgMode ?: ReadAloudBgMode.Blur, sheet)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toUiState(coordinator.snapshot(), readBgMode(), null),
    )

    private val _effects = MutableSharedFlow<ReadAloudPlayerEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    fun onIntent(intent: ReadAloudPlayerIntent) {
        when (intent) {
            ReadAloudPlayerIntent.Refresh -> coordinator.refresh()
            ReadAloudPlayerIntent.TogglePause -> coordinator.togglePause()
            ReadAloudPlayerIntent.StopReadAloud -> coordinator.stop()
            ReadAloudPlayerIntent.PreviousParagraph -> coordinator.previousParagraph()
            ReadAloudPlayerIntent.NextParagraph -> coordinator.nextParagraph()
            ReadAloudPlayerIntent.PreviousChapter -> coordinator.previousChapter()
            ReadAloudPlayerIntent.NextChapter -> coordinator.nextChapter()
            ReadAloudPlayerIntent.OpenReadAloudLogs -> effect(ReadAloudPlayerEffect.OpenReadAloudLogs)
            ReadAloudPlayerIntent.SwitchToClassic -> effect(ReadAloudPlayerEffect.ReturnToClassic)
            ReadAloudPlayerIntent.CycleBgMode -> cycleBgMode()
            is ReadAloudPlayerIntent.SelectChapter -> coordinator.selectChapter(intent.index)
            is ReadAloudPlayerIntent.SetBgMode -> AppConfigStore.putInt(
                PreferKey.readAloudPlayerBgMode,
                intent.value,
            )
            is ReadAloudPlayerIntent.SetSpeed -> viewModelScope.launch {
                coordinator.setSpeed(intent.value)
            }
            is ReadAloudPlayerIntent.SetTimer -> viewModelScope.launch {
                coordinator.setTimer(intent.minutes)
            }
            is ReadAloudPlayerIntent.SetTimerMode -> viewModelScope.launch {
                coordinator.setTimerMode(ReadAloudTimerMode.fromStorage(intent.value))
            }

            is ReadAloudPlayerIntent.SetTimerChapters -> viewModelScope.launch {
                coordinator.setTimerChapters(intent.value)
            }
            is ReadAloudPlayerIntent.SetFinishCurrentChapterAfterTimer ->
                viewModelScope.launch {
                    coordinator.setFinishCurrentChapterAfterTimer(intent.value)
                }
            is ReadAloudPlayerIntent.OpenSheet -> {
                activeSheet.value = intent.sheet
            }
            ReadAloudPlayerIntent.DismissSheet -> activeSheet.value = null
            is ReadAloudPlayerIntent.SeekTo -> coordinator.seekTo(
                chapterPosition = intent.chapterPosition,
                chapterLength = uiState.value.chapterLength,
            )
        }
    }

    private fun cycleBgMode() {
        val next = when (readBgMode()) {
            ReadAloudBgMode.Solid -> ReadAloudBgMode.Blur
            ReadAloudBgMode.Blur -> ReadAloudBgMode.FlowingLight
            ReadAloudBgMode.FlowingLight -> ReadAloudBgMode.Transparent
            else -> ReadAloudBgMode.Solid
        }
        AppConfigStore.putInt(PreferKey.readAloudPlayerBgMode, next)
    }

    /**
     * 目录列表映射缓存。
     *
     * `toUiState` 会随每个 TTS 进度事件（逐词回调）重跑，但目录只在 Room 章节流发新值时变化；
     * 长书上每次重映射几千个章节是纯浪费。
     */
    private var chaptersCacheSource: ImmutableList<ReadAloudChapterSourceState>? = null
    private var chaptersCache: ImmutableList<PlayerChapterUi> = persistentListOf()

    private fun chaptersOf(
        source: ImmutableList<ReadAloudChapterSourceState>,
    ): ImmutableList<PlayerChapterUi> {
        chaptersCacheSource?.takeIf { it == source }?.let { return chaptersCache }
        return source.map { chapter ->
            PlayerChapterUi(
                index = chapter.index,
                title = chapter.title,
                isVolume = chapter.isVolume,
                tocLevel = chapter.tocLevel,
            )
        }.toImmutableList().also {
            chaptersCacheSource = source
            chaptersCache = it
        }
    }

    private fun toUiState(
        source: ReadAloudPlayerSourceState,
        bgMode: Int,
        sheet: ReadAloudPlayerSheet?,
    ): ReadAloudPlayerUiState {
        val activeIndex = source.textLines.indexOfLast {
            it.chapterPosition <= source.chapterPosition
        }
        val chapters = chaptersOf(source.chapters)
        return ReadAloudPlayerUiState(
            bookUrl = source.bookUrl,
            bookName = source.bookName,
            author = source.author,
            coverPath = source.coverPath,
            sourceOrigin = source.sourceOrigin,
            chapterIndex = source.chapterIndex,
            chapterTitle = source.chapterTitle,
            chapters = chapters,
            chapterText = source.chapterText,
            textLines = source.textLines,
            activeTextLine = activeIndex,
            currentText = source.textLines.getOrNull(activeIndex)?.text ?: source.playbackText,
            nextText = source.textLines.getOrNull(activeIndex + 1)?.text.orEmpty(),
            chapterPosition = source.chapterPosition,
            chapterLength = source.chapterLength,
            engineName = source.engineName,
            speakerName = source.speakerName,
            isPaused = source.isPaused,
            readAloudRunning = BaseReadAloudService.isRun,
            speed = source.speed,
            timerMinutes = source.timerMinutes,
            timerMode = source.timerMode,
            timerChapters = source.timerChapters,
            finishCurrentChapterAfterTimer = source.finishCurrentChapterAfterTimer,
            bgMode = bgMode,
            activeSheet = sheet,
        )
    }

    private fun effect(value: ReadAloudPlayerEffect) {
        _effects.tryEmit(value)
    }

    private fun readBgMode(): Int {
        return AppConfigStore.preferences.compatDsInt(PreferKey.readAloudPlayerBgMode)
            ?: ReadAloudBgMode.Blur
    }


}
