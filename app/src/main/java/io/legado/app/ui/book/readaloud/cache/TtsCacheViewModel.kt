package io.legado.app.ui.book.readaloud.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.AppLog
import io.legado.app.data.repository.ReadAloudAudioCacheRepository
import io.legado.app.data.repository.ReadAloudDataRepository
import io.legado.app.domain.usecase.SynthesizeChapterAudioUseCase
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.Locale

class TtsCacheViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TtsCacheUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TtsCacheEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private val dataRepository by lazy { GlobalContext.get().get<ReadAloudDataRepository>() }
    private val audioCache by lazy { GlobalContext.get().get<ReadAloudAudioCacheRepository>() }
    private val synthesizeChapter by lazy {
        GlobalContext.get().get<SynthesizeChapterAudioUseCase>()
    }

    private var cacheJob: Job? = null

    init {
        loadAudioCache()
        // 实时日志：AppLog 每次写入都会推送新快照（升序），日志页边播边刷
        viewModelScope.launch {
            AppLog.logsFlow.collect { entries ->
                val mapped = entries.map { entry ->
                    TtsLogEntryUi(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        message = entry.message,
                        hasError = entry.throwable != null,
                        fullContent = entry.throwable
                            ?.let { t -> "${entry.message}\n${t.stackTraceToString()}" }
                            ?: entry.message,
                        category = entry.category,
                    )
                }
                _uiState.update { it.copy(logs = mapped.toImmutableList()) }
            }
        }
    }

    fun onIntent(intent: TtsCacheIntent) {
        when (intent) {
            TtsCacheIntent.LoadAudioCache -> loadAudioCache()
            is TtsCacheIntent.ToggleBookExpanded -> _uiState.update { state ->
                state.copy(
                    expandedBooks = (if (intent.book in state.expandedBooks) {
                        state.expandedBooks - intent.book
                    } else {
                        state.expandedBooks + intent.book
                    }).toImmutableSet()
                )
            }

            is TtsCacheIntent.CacheChapter -> cacheChapters(intent.book, listOf(intent.chapterIndex))
            is TtsCacheIntent.CacheBook -> {
                val chapters = _uiState.value.books.firstOrNull { it.book == intent.book }
                    ?.chapters
                    ?.filter { it.missing > 0 }
                    ?.map { it.chapterIndex }
                    .orEmpty()
                if (chapters.isEmpty()) {
                    _effects.tryEmit(TtsCacheEffect.ShowToast("本书音频已全部缓存"))
                } else {
                    cacheChapters(intent.book, chapters)
                }
            }

            TtsCacheIntent.StopJob -> {
                cacheJob?.cancel()
                cacheJob = null
                _uiState.update { it.copy(job = null) }
                _effects.tryEmit(TtsCacheEffect.ShowToast("已停止批量合成"))
            }

            is TtsCacheIntent.ShowDeleteBookDialog ->
                _uiState.update { it.copy(activeDialog = TtsCacheDialog.DeleteBookAudio(intent.book)) }

            is TtsCacheIntent.ShowDeleteChapterDialog -> _uiState.update {
                it.copy(
                    activeDialog = TtsCacheDialog.DeleteChapterAudio(intent.book, intent.chapterIndex)
                )
            }

            is TtsCacheIntent.DeleteBookAudio -> viewModelScope.launch {
                _uiState.update { it.copy(activeDialog = null) }
                audioCache.deleteBook(intent.book)
                _effects.tryEmit(TtsCacheEffect.ShowToast("已删除《${intent.book}》的音频缓存"))
                loadAudioCache()
            }

            is TtsCacheIntent.DeleteChapterAudio -> viewModelScope.launch {
                _uiState.update { it.copy(activeDialog = null) }
                audioCache.deleteChapter(intent.book, intent.chapterIndex)
                _effects.tryEmit(TtsCacheEffect.ShowToast("已删除第${intent.chapterIndex + 1}章音频缓存"))
                loadAudioCache()
            }

            TtsCacheIntent.ShowClearLogsDialog ->
                _uiState.update { it.copy(activeDialog = TtsCacheDialog.ClearLogs) }

            TtsCacheIntent.ClearLogs -> {
                AppLog.clear()
                _effects.tryEmit(TtsCacheEffect.ShowToast("朗读日志已清空"))
            }

            TtsCacheIntent.DismissDialog ->
                _uiState.update { it.copy(activeDialog = null) }

            is TtsCacheIntent.SelectTab -> _uiState.update {
                it.copy(
                    activeTab = intent.tab,
                    selectedIds = persistentSetOf(),
                    expandedIds = persistentSetOf(),
                )
            }

            is TtsCacheIntent.SetSearchMode -> _uiState.update { it.copy(isSearch = intent.isSearch) }
            is TtsCacheIntent.SetSearchKey -> _uiState.update { it.copy(searchKey = intent.key) }
            is TtsCacheIntent.ToggleSelection -> _uiState.update { state ->
                state.copy(
                    selectedIds = (if (intent.id in state.selectedIds) {
                        state.selectedIds - intent.id
                    } else {
                        state.selectedIds + intent.id
                    }).toImmutableSet()
                )
            }

            is TtsCacheIntent.SetSelection ->
                _uiState.update { it.copy(selectedIds = intent.ids.toImmutableSet()) }

            is TtsCacheIntent.ToggleExpand -> _uiState.update { state ->
                state.copy(
                    expandedIds = (if (intent.id in state.expandedIds) {
                        state.expandedIds - intent.id
                    } else {
                        state.expandedIds + intent.id
                    }).toImmutableSet()
                )
            }
        }
    }

    /** 扫描持久化缓存目录 + 本地剧本行数 → 书籍/章节两级统计 */
    private fun loadAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val books = audioCache.listBooks().map { book ->
                val lineCounts = dataRepository.loadChapterLineCounts(book)
                val chapters = lineCounts.entries
                    .sortedBy { it.key }
                    .map { (chapterIndex, total) ->
                        val stats = audioCache.chapterStats(book, chapterIndex, total)
                        AudioChapterUi(
                            chapterIndex = chapterIndex,
                            cached = stats.cached,
                            total = stats.total,
                            sizeBytes = stats.sizeBytes,
                        )
                    }
                AudioBookUi(
                    book = book,
                    cached = chapters.sumOf { it.cached },
                    total = chapters.sumOf { it.total },
                    sizeBytes = chapters.sumOf { it.sizeBytes },
                    chapters = chapters.toImmutableList(),
                )
            }
            _uiState.update { it.copy(loading = false, books = books.toImmutableList()) }
        }
    }

    private fun cacheChapters(book: String, chapterIndexes: List<Int>) {
        if (chapterIndexes.isEmpty()) return
        cacheJob?.cancel()
        cacheJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    job = AudioJobUi(
                        book = book,
                        chapterIndex = chapterIndexes.first(),
                        chapterDone = 0,
                        chapterTotal = 0,
                        chapterPosition = 1,
                        chapterCount = chapterIndexes.size,
                    )
                )
            }
            var done = 0
            var failed = 0
            chapterIndexes.forEachIndexed { position, chapterIndex ->
                val result = runCatching {
                    synthesizeChapter(
                        book = book,
                        chapterIndex = chapterIndex,
                        onProgress = { processed, total ->
                            _uiState.update { state ->
                                state.copy(
                                    job = state.job?.copy(
                                        chapterIndex = chapterIndex,
                                        chapterDone = processed,
                                        chapterTotal = total,
                                        chapterPosition = position + 1,
                                        chapterCount = chapterIndexes.size,
                                    )
                                )
                            }
                        },
                    )
                }.getOrNull() ?: SynthesizeChapterAudioUseCase.Result(0, 0, 0)
                done += result.done
                failed += result.failed
                loadAudioCache()
            }
            _uiState.update { it.copy(job = null) }
            cacheJob = null
            _effects.tryEmit(
                TtsCacheEffect.ShowToast(
                    String.format(Locale.getDefault(), "批量合成完成：成功 %d，失败 %d", done, failed)
                )
            )
        }
    }

    companion object {
        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            }
        }
    }
}

private fun <T> List<T>.toImmutableList(): ImmutableList<T> =
    persistentListOf<T>().builder().apply { addAll(this@toImmutableList) }.build()

private fun <T> Set<T>.toImmutableSet(): ImmutableSet<T> =
    persistentSetOf<T>().builder().apply { addAll(this@toImmutableSet) }.build()
