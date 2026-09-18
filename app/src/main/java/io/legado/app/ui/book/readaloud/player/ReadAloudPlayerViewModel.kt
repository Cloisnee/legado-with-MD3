package io.legado.app.ui.book.readaloud.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.compatDsInt
import io.legado.app.help.readaloud.analysis.AnalysisSchedulerV2
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV2
import io.legado.app.ui.widget.components.player.PlayerChapterUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadAloudPlayerViewModel(
    private val coordinator: ReadAloudPlayerCoordinator,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val scheduler: AnalysisSchedulerV2,
) : ViewModel() {

    private val activeSheet = MutableStateFlow<ReadAloudPlayerSheet?>(null)
    private val scriptState = MutableStateFlow(ScriptReviewUi())

    val uiState = combine(
        coordinator.state,
        AppConfigStore.observeInt(PreferKey.readAloudPlayerBgMode),
        activeSheet,
        scriptState,
    ) { source, bgMode, sheet, script ->
        toUiState(source, bgMode ?: ReadAloudBgMode.Blur, sheet, script)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toUiState(coordinator.snapshot(), readBgMode(), null, null),
    )

    private val _effects = MutableSharedFlow<ReadAloudPlayerEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    init {
        // 分析调度完成 → 审查页打开中且章节匹配时自动刷新
        viewModelScope.launch {
            scheduler.events.collect { ev ->
                val cur = uiState.value
                if (ev.bookUrl == cur.bookUrl &&
                    ev.chapterIndex == cur.chapterIndex &&
                    cur.activeSheet == ReadAloudPlayerSheet.ScriptReview
                ) {
                    loadScriptReview()
                }
            }
        }
    }

    fun onIntent(intent: ReadAloudPlayerIntent) {
        when (intent) {
            ReadAloudPlayerIntent.Refresh -> coordinator.refresh()
            ReadAloudPlayerIntent.TogglePause -> coordinator.togglePause()
            ReadAloudPlayerIntent.PreviousParagraph -> coordinator.previousParagraph()
            ReadAloudPlayerIntent.NextParagraph -> coordinator.nextParagraph()
            ReadAloudPlayerIntent.PreviousChapter -> coordinator.previousChapter()
            ReadAloudPlayerIntent.NextChapter -> coordinator.nextChapter()
            ReadAloudPlayerIntent.OpenSettings -> effect(ReadAloudPlayerEffect.ReturnToReaderSettings)
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
            is ReadAloudPlayerIntent.SetFinishCurrentChapterAfterTimer ->
                viewModelScope.launch {
                    coordinator.setFinishCurrentChapterAfterTimer(intent.value)
                }
            is ReadAloudPlayerIntent.OpenSheet -> {
                activeSheet.value = intent.sheet
                if (intent.sheet == ReadAloudPlayerSheet.ScriptReview) loadScriptReview()
            }
            ReadAloudPlayerIntent.DismissSheet -> activeSheet.value = null
            is ReadAloudPlayerIntent.SeekTo -> coordinator.seekTo(
                chapterPosition = intent.chapterPosition,
                chapterLength = uiState.value.chapterLength,
            )
            // 批次C · 审查页
            ReadAloudPlayerIntent.LoadScriptReview -> loadScriptReview()
            is ReadAloudPlayerIntent.ToggleScriptRow -> {
                val cur = scriptState.value
                val next = if (intent.id in cur.selectedIds) {
                    cur.selectedIds - intent.id
                } else {
                    cur.selectedIds + intent.id
                }
                scriptState.value = updateScriptSelection(cur, next)
            }
            ReadAloudPlayerIntent.ClearScriptSelection ->
                scriptState.value = updateScriptSelection(scriptState.value, emptySet())
            is ReadAloudPlayerIntent.SetScriptNarration -> viewModelScope.launch {
                applyScriptEdit(intent.ids) {
                    it.copy(
                        roleType = SpeechRoleType.Narrator,
                        characterId = null,
                        characterName = "",
                        source = SpeechResolutionSource.User,
                        userLocked = true,
                    )
                }
            }
            is ReadAloudPlayerIntent.AssignScriptSpeaker -> viewModelScope.launch {
                val profile = intent.profileId?.let { pid ->
                    bookKnowledgeGateway.getCharacterProfiles(currentBookUrl(), 200)
                        .firstOrNull { it.id == pid }
                }
                val name = profile?.name ?: intent.customName.trim()
                if (name.isBlank()) return@launch
                applyScriptEdit(intent.ids) {
                    it.copy(
                        roleType = SpeechRoleType.Character,
                        characterId = profile?.id,
                        characterName = name,
                        source = SpeechResolutionSource.User,
                        userLocked = true,
                    )
                }
            }
            is ReadAloudPlayerIntent.ToggleScriptLock -> viewModelScope.launch {
                applyScriptEdit(setOf(intent.id)) {
                    it.copy(userLocked = !it.userLocked)
                }
            }
            ReadAloudPlayerIntent.ReanalyzeScript -> viewModelScope.launch {
                val bookUrl = currentBookUrl()
                val index = uiState.value.chapterIndex
                if (bookUrl.isBlank() || index < 0) return@launch
                scriptState.value = scriptState.value.copy(
                    statusLine = "后台重新分析中（AI 管线）…",
                )
                scheduler.enqueueChapter(bookUrl, index, force = true)
            }
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

    private fun toUiState(
        source: ReadAloudPlayerSourceState,
        bgMode: Int,
        sheet: ReadAloudPlayerSheet?,
        script: ScriptReviewUi?,
    ): ReadAloudPlayerUiState {
        val activeIndex = source.textLines.indexOfLast {
            it.chapterPosition <= source.chapterPosition
        }
        val chapters = source.chapters.map { chapter ->
            PlayerChapterUi(
                index = chapter.index,
                title = chapter.title,
                isVolume = chapter.isVolume,
                tocLevel = chapter.tocLevel,
            )
        }.toImmutableList()
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
            speed = source.speed,
            timerMinutes = source.timerMinutes,
            finishCurrentChapterAfterTimer = source.finishCurrentChapterAfterTimer,
            bgMode = bgMode,
            activeSheet = sheet,
            scriptReview = script,
        )
    }

    private fun effect(value: ReadAloudPlayerEffect) {
        _effects.tryEmit(value)
    }

    private fun readBgMode(): Int {
        return AppConfigStore.preferences.compatDsInt(PreferKey.readAloudPlayerBgMode)
            ?: ReadAloudBgMode.Blur
    }

    // ---------------- 批次C · 审查页 ----------------

    private companion object {
        const val RULE_VERSION_PREFIX = "rule-segmenter"
    }

    private fun currentBookUrl(): String = uiState.value.bookUrl.ifBlank {
        coordinator.snapshot().bookUrl
    }

    private fun roleLabelOf(role: SpeechRoleType): String = when (role) {
        SpeechRoleType.Narrator -> "旁白"
        SpeechRoleType.Character -> "台词"
        SpeechRoleType.Thought -> "独白"
        SpeechRoleType.Unknown -> "未知"
    }

    private fun sourceLabelOf(version: String): String = when {
        version.startsWith(SpeechAnalysisPipelineV2.RESOLVER_VERSION) -> "AI 管线"
        version.startsWith(RULE_VERSION_PREFIX) -> "本地规则"
        else -> version
    }

    private fun updateScriptSelection(
        cur: ScriptReviewUi,
        selected: Set<String>,
    ): ScriptReviewUi = cur.copy(
        selectedIds = selected,
        rows = cur.rows.map { it.copy(selected = it.id in selected) }.toImmutableList(),
    )

    private fun loadScriptReview() {
        viewModelScope.launch {
            val bookUrl = currentBookUrl()
            val chapterIndex = uiState.value.chapterIndex
            if (bookUrl.isBlank() || chapterIndex < 0) return@launch
            scriptState.value = ScriptReviewUi(loading = true)
            val analysis = runCatching {
                chapterSpeechGateway.getLatestAnalysis(bookUrl, chapterIndex)
            }.getOrNull()
            val segments = analysis?.let {
                runCatching { chapterSpeechGateway.getSegments(it.id) }.getOrDefault(emptyList())
            }.orEmpty()
            val profiles = runCatching {
                bookKnowledgeGateway.getCharacterProfiles(bookUrl, 200)
                    .filter { it.status == BookCharacterProfile.STATUS_ACTIVE }
            }.getOrDefault(emptyList())
            scriptState.value = ScriptReviewUi(
                loading = false,
                hasData = segments.isNotEmpty(),
                sourceLabel = analysis?.let { sourceLabelOf(it.resolverVersion) } ?: "未分析",
                statusLine = if (analysis == null) {
                    "本章还没有分析数据，开始朗读或点右上角重析"
                } else {
                    "${analysis.status.storageValue} · ${segments.size} 段 · ${sourceLabelOf(analysis.resolverVersion)}"
                },
                rows = segments.mapIndexed { i, seg ->
                    ScriptRowUi(
                        id = seg.id,
                        indexLabel = (i + 1).toString(),
                        text = seg.text,
                        speakerName = seg.characterName,
                        roleLabel = roleLabelOf(seg.roleType),
                        isCharacter = seg.roleType != SpeechRoleType.Narrator,
                        emotion = seg.emotion,
                        locked = seg.userLocked,
                        selected = false,
                    )
                }.toImmutableList(),
                profiles = profiles.map { p ->
                    ScriptProfileUi(
                        id = p.id,
                        name = p.name,
                        aliasLine = runCatching {
                            io.legado.app.utils.GSON.fromJsonArray<String>(p.aliasesJson)
                                .getOrNull().orEmpty()
                                .joinToString("/")
                        }.getOrDefault(""),
                    )
                }.toImmutableList(),
                selectedIds = emptySet(),
            )
        }
    }

    /** 批量应用剧本编辑并落库（userLocked=true，重析不覆盖） */
    private suspend fun applyScriptEdit(
        ids: Set<String>,
        transform: (io.legado.app.domain.model.readaloud.ChapterSpeechSegment)
            -> io.legado.app.domain.model.readaloud.ChapterSpeechSegment,
    ) {
        val bookUrl = currentBookUrl()
        val chapterIndex = uiState.value.chapterIndex
        if (bookUrl.isBlank() || chapterIndex < 0) return
        val analysis = runCatching {
            chapterSpeechGateway.getLatestAnalysis(bookUrl, chapterIndex)
        }.getOrNull() ?: return
        val segments = runCatching {
            chapterSpeechGateway.getSegments(analysis.id)
        }.getOrDefault(emptyList())
        if (segments.isEmpty()) return
        val updated = segments.map { seg -> if (seg.id in ids) transform(seg) else seg }
        val ok = runCatching {
            chapterSpeechGateway.replaceSegments(analysis.id, updated)
            true
        }.getOrDefault(false)
        if (ok) {
            loadScriptReview()
        } else {
            scriptState.value = scriptState.value.copy(statusLine = "保存失败")
        }
    }
}
