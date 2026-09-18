package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * W3 · 分析调度器（预加载窗口驱动，独立于播放线）。
 *
 *  - 播放启动时入队「当前章 + 预加载窗口（≤10 章）」；仅处理**已缓存正文**的章节；
 *  - 串行 worker（省 token、防限流）；换书时清空队列；
 *  - 完成/失败 → events（审查页监听刷新；播放线不打断）。
 */
class AnalysisSchedulerV2(
    private val pipeline: SpeechAnalysisPipelineV2,
    private val bookChapterDao: BookChapterDao,
    private val bookDao: BookDao,
) {

    data class ReadyEvent(
        val bookUrl: String,
        val chapterIndex: Int,
        val usedAi: Boolean,
        val message: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private val queueMutex = Mutex()
    private val queued = HashSet<String>()
    private var worker: kotlinx.coroutines.Job? = null
    private var currentBookUrl: String? = null

    private val _events = MutableSharedFlow<ReadyEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private data class Task(val bookUrl: String, val chapterIndex: Int, val force: Boolean)

    /** 预加载窗口入队：当前章起 ≤10 章（窗口大小跟随「预下载数量」，上限 10） */
    fun enqueueWindow(bookUrl: String, fromIndex: Int, force: Boolean = false) {
        if (bookUrl.isBlank()) return
        val window = AppConfig.preDownloadNum.coerceIn(1, 10)
        enqueueRange(bookUrl, fromIndex, window, force)
    }

    fun enqueueChapter(bookUrl: String, index: Int, force: Boolean = false) {
        enqueueRange(bookUrl, index, 1, force)
    }

    private fun enqueueRange(bookUrl: String, fromIndex: Int, count: Int, force: Boolean) {
        if (fromIndex < 0) return
        scope.launch {
            queueMutex.withLock {
                // 换书 → 丢弃旧书任务
                if (currentBookUrl != null && currentBookUrl != bookUrl) {
                    while (queue.tryReceive().isSuccess) Unit
                    queued.clear()
                }
                currentBookUrl = bookUrl
                for (i in fromIndex until fromIndex + count) {
                    val key = "$bookUrl|$i"
                    if (!force && key in queued) continue
                    queued.add(key)
                    queue.trySend(Task(bookUrl, i, force))
                }
            }
            startWorkerIfNeeded()
        }
    }

    private fun startWorkerIfNeeded() {
        if (worker?.isActive != true) {
            worker = scope.launch { workerLoop() }
        }
    }

    private suspend fun workerLoop() {
        for (task in queue) {
            if (!scope.isActive) break
            val key = "${task.bookUrl}|${task.chapterIndex}"
            runCatching { process(task) }
                .onFailure { AppLog.put("分析调度·第${task.chapterIndex + 1}章失败: ${it.localizedMessage}", it) }
            queueMutex.withLock { queued.remove(key) }
        }
    }

    private suspend fun process(task: Task) {
        val book = bookDao.getBook(task.bookUrl) ?: return
        val index = task.chapterIndex
        val chapter = bookChapterDao.getChapterList(book.bookUrl, index, index).firstOrNull()
            ?: return
        val content = runCatching { BookHelp.getContent(book, chapter) }.getOrNull()
            ?: return // 未缓存章节跳过（窗口内按缓存进度自然推进）
        val paragraphs = buildParagraphs(content)
        if (paragraphs.isEmpty()) return
        val prevText = loadChapterText(book, index - 1)
        val nextText = loadChapterText(book, index + 1)
        val result = pipeline.run(
            bookUrl = book.bookUrl,
            chapterIndex = index,
            paragraphs = paragraphs,
            prevText = prevText,
            nextText = nextText,
            force = task.force,
        ) ?: return
        val usedAi = result.segments.any { it.source.storageValue == "ai" }
        _events.tryEmit(
            ReadyEvent(
                bookUrl = book.bookUrl,
                chapterIndex = index,
                usedAi = usedAi,
                message = result.analysis.status.storageValue,
            ),
        )
    }

    private suspend fun loadChapterText(book: Book, index: Int): String {
        if (index < 0) return ""
        val chapter = bookChapterDao.getChapterList(book.bookUrl, index, index).firstOrNull()
            ?: return ""
        return runCatching { BookHelp.getContent(book, chapter) }.getOrNull().orEmpty()
    }

    private fun buildParagraphs(content: String): List<CanonicalSpeechParagraph> {
        val out = ArrayList<CanonicalSpeechParagraph>()
        var position = 0
        content.split('\n').forEach { rawLine ->
            val line = rawLine.replace(Regex("[袮祢꧁\\uFFFC]"), " ")
            if (line.isNotBlank()) {
                out.add(CanonicalSpeechParagraph(index = out.size, text = line, chapterPosition = position))
            }
            position += rawLine.length + 1
        }
        return out
    }

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        while (queue.tryReceive().isSuccess) Unit
        scope.launch { queueMutex.withLock { queued.clear() } }
        currentBookUrl = null
    }
}
