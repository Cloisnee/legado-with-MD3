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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.Locale

class TtsCacheViewModel : ViewModel() {

    private data class QueueItem(val book: String, val chapterIndex: Int)

    private val _uiState = MutableStateFlow(TtsCacheUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<TtsCacheEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private val dataRepository by lazy { GlobalContext.get().get<ReadAloudDataRepository>() }
    private val audioCache by lazy { GlobalContext.get().get<ReadAloudAudioCacheRepository>() }
    private val synthesizeChapter by lazy {
        GlobalContext.get().get<SynthesizeChapterAudioUseCase>()
    }

    /** 待合成队列（顺序 = 卡片上的等待顺序）；暂停的条目留在队列里等恢复 */
    private val queue = mutableListOf<QueueItem>()
    private var current: QueueItem? = null
    private var worker: Job? = null

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

            is TtsCacheIntent.CacheChapter ->
                enqueue(listOf(QueueItem(intent.book, intent.chapterIndex)))

            is TtsCacheIntent.StopChapter -> {
                val item = QueueItem(intent.book, intent.chapterIndex)
                if (current == item) {
                    worker?.cancel()
                } else if (chapterState(item) == AudioChapterState.Waiting) {
                    setChapterState(item, AudioChapterState.Paused, null)
                }
                updateSummary()
            }

            is TtsCacheIntent.CacheBook -> {
                val chapters = _uiState.value.books.firstOrNull { it.book == intent.book }
                    ?.chapters
                    ?.filter { !it.isCached || it.isPaused }
                    ?.map { QueueItem(intent.book, it.chapterIndex) }
                    .orEmpty()
                if (chapters.isEmpty()) {
                    _effects.tryEmit(TtsCacheEffect.ShowToast("本书音频已全部缓存"))
                } else {
                    enqueue(chapters)
                }
            }

            is TtsCacheIntent.StopBook -> {
                if (current?.book == intent.book) {
                    worker?.cancel()
                }
                _uiState.value.books.firstOrNull { it.book == intent.book }
                    ?.chapters
                    ?.forEach { ch ->
                        val item = QueueItem(intent.book, ch.chapterIndex)
                        if (chapterState(item) == AudioChapterState.Waiting) {
                            setChapterState(item, AudioChapterState.Paused, null)
                        }
                    }
                updateSummary()
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

    // ---------------- 音频缓存数据 ----------------

    /** 扫描持久化缓存目录 + 本地剧本行数 + Room 章节标题/作者 → 书籍/章节两级统计 */
    private fun loadAudioCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val books = audioCache.listBooks().map { book ->
                val author = dataRepository.loadBookAuthor(book)
                val titles = dataRepository.loadChapterTitles(book)
                val lineCounts = dataRepository.loadChapterLineCounts(book)
                val chapters = lineCounts.entries
                    .sortedBy { it.key }
                    .map { (chapterIndex, total) ->
                        val stats = audioCache.chapterStats(book, chapterIndex, total)
                        AudioChapterUi(
                            chapterIndex = chapterIndex,
                            title = titles[chapterIndex].orEmpty(),
                            cached = stats.cached,
                            total = stats.total,
                            sizeBytes = stats.sizeBytes,
                        )
                    }
                AudioBookUi(
                    book = book,
                    author = author,
                    cached = chapters.sumOf { it.cached },
                    total = chapters.sumOf { it.total },
                    sizeBytes = chapters.sumOf { it.sizeBytes },
                    chapters = chapters.toImmutableList(),
                )
            }
            _uiState.update { it.copy(loading = false, books = books.toImmutableList()) }
        }
    }

    private suspend fun refreshChapter(item: QueueItem) {
        val total = dataRepository.loadChapterLineCounts(item.book)[item.chapterIndex] ?: 0
        val stats = audioCache.chapterStats(item.book, item.chapterIndex, total)
        _uiState.update { state ->
            state.copy(
                books = state.books.map { book ->
                    if (book.book != item.book) {
                        book
                    } else {
                        book.copy(
                            chapters = book.chapters.map { ch ->
                                if (ch.chapterIndex != item.chapterIndex) {
                                    ch
                                } else {
                                    ch.copy(
                                        cached = stats.cached,
                                        total = stats.total,
                                        sizeBytes = stats.sizeBytes,
                                    )
                                }
                            }.toImmutableList()
                        ).recalc()
                    }
                }.toImmutableList()
            )
        }
    }

    // ---------------- 队列 / 批量合成 ----------------

    private fun chapterState(item: QueueItem): AudioChapterState? =
        _uiState.value.books.firstOrNull { it.book == item.book }
            ?.chapters?.firstOrNull { it.chapterIndex == item.chapterIndex }
            ?.state

    private fun setChapterState(
        item: QueueItem,
        state: AudioChapterState,
        label: String?,
        progress: Float = 0f,
    ) {
        _uiState.update { s ->
            s.copy(
                books = s.books.map { book ->
                    if (book.book != item.book) {
                        book
                    } else {
                        book.copy(
                            chapters = book.chapters.map { ch ->
                                if (ch.chapterIndex != item.chapterIndex) {
                                    ch
                                } else {
                                    ch.copy(
                                        state = state,
                                        progressLabel = label,
                                        progress = progress,
                                    )
                                }
                            }.toImmutableList()
                        )
                    }
                }.toImmutableList()
            )
        }
        updateSummary()
    }

    private fun updateChapterProgress(item: QueueItem, processed: Int, total: Int) {
        val progress = if (total <= 0) 0f else processed.toFloat() / total
        setChapterState(
            item = item,
            state = AudioChapterState.Downloading,
            label = "$processed/$total",
            progress = progress,
        )
    }

    private fun enqueue(items: List<QueueItem>) {
        var added = false
        items.forEach { item ->
            val state = chapterState(item)
            if (state == AudioChapterState.Waiting || state == AudioChapterState.Downloading) {
                return@forEach
            }
            if (item !in queue) queue.add(item)
            setChapterState(item, AudioChapterState.Waiting, null)
            added = true
        }
        if (added) startWorker()
    }

    private fun nextReady(): QueueItem? {
        val index = queue.indexOfFirst { chapterState(it) == AudioChapterState.Waiting }
        return if (index < 0) null else queue.removeAt(index)
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = viewModelScope.launch {
            var done = 0
            var failed = 0
            var skipped = 0
            while (isActive) {
                val item = nextReady() ?: break
                current = item
                setChapterState(item, AudioChapterState.Downloading, "0/0")
                try {
                    val result = synthesizeChapter(
                        book = item.book,
                        chapterIndex = item.chapterIndex,
                        onProgress = { processed, total ->
                            updateChapterProgress(item, processed, total)
                        },
                    )
                    done += result.done
                    failed += result.failed
                    skipped += result.skipped
                    refreshChapter(item)
                    setChapterState(
                        item = item,
                        state = if (result.failed > 0) {
                            AudioChapterState.Error
                        } else {
                            AudioChapterState.Idle
                        },
                        label = if (result.failed > 0) "失败 ${result.failed} 条" else null,
                    )
                } catch (e: CancellationException) {
                    setChapterState(item, AudioChapterState.Paused, null)
                    current = null
                    throw e
                } catch (e: Exception) {
                    AppLog.putAudio(
                        "【音频缓存】批量合成异常 第${item.chapterIndex + 1}章: ${e.localizedMessage}",
                        e,
                    )
                    setChapterState(item, AudioChapterState.Error, "异常")
                }
                current = null
            }
            worker = null
            updateSummary()
            if (done + failed + skipped > 0) {
                _effects.tryEmit(
                    TtsCacheEffect.ShowToast(
                        String.format(
                            Locale.getDefault(),
                            "批量合成完成：成功 %d · 失败 %d · 跳过 %d",
                            done,
                            failed,
                            skipped,
                        )
                    )
                )
            }
        }
    }

    private fun updateSummary() {
        val books = _uiState.value.books
        val cur = current
        val summary = when {
            cur != null -> {
                val label = books.firstOrNull { it.book == cur.book }
                    ?.chapters?.firstOrNull { it.chapterIndex == cur.chapterIndex }
                    ?.progressLabel
                    .orEmpty()
                "音频缓存 第${cur.chapterIndex + 1}章 $label".trim()
            }

            books.sumOf { it.waitingCount } > 0 ->
                "音频缓存 等待 ${books.sumOf { it.waitingCount }} 章"

            books.sumOf { it.pausedCount } > 0 ->
                "音频缓存 已暂停 ${books.sumOf { it.pausedCount }} 章"

            else -> ""
        }
        _uiState.update { it.copy(audioQueueSummary = summary) }
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
