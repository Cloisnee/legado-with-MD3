package io.legado.app.help.readaloud.analysis

import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import org.koin.core.context.GlobalContext
import io.legado.app.utils.ChapterLabels
import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.feature.reader.platform.AndroidReaderHtmlSemanticTextResolver
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * V4 · 分析调度器（V3 管线，窗口驱动 + 缓存追赶，独立于播放线）。
 *
 *  触点：
 *   1) 朗读开始（startSession）→ 入队「当前章 + 预加载窗口（≤10 章）」；
 *   2) 换章/推进（onChapterChanged）→ 更新锚点 + 补入窗口；
 *   3) 缓存追赶扫掠：会话期间每 30s 重扫窗口，新落盘章节自动入队（仅处理已缓存正文；本地书即时可析）；
 *   4) 手动（审查页「重析本章」→ enqueueChapter(force=true)）。
 *  - 串行 worker（省 token、防限流）；换书清队；完成/失败 → events（审查页监听刷新；播放线不打断）；
 *  - 分析前先尝试本地剧本回填（B8.3.1）：剧本能对上正文 → 复用、不再重析。
 */
class AnalysisSchedulerV3(
    private val pipeline: SpeechAnalysisPipelineV3,
    private val bookChapterDao: BookChapterDao,
    private val bookDao: BookDao,
    private val readSettingsGateway: ReadSettingsGateway,
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
    private var worker: Job? = null
    private var currentBookUrl: String? = null

    /** B19：预加载窗口大小跟随「听书预加载数量」（0..10；默认 2；B28：0=不预分析后续章——analysisWindow=1 仅当前，合成侧为空循环） */
    private val preloadWindow: Int
        get() = runCatching {
            GlobalContext.get().get<ReadAloudSettingsGateway>().currentSettings.audioPreDownloadNum
        }.getOrDefault(2).coerceIn(0, 10)

    /**
     * B27：分析窗口 = 预加载数量 + 1（当前章起）。
     *   对齐「预合成后续 N 章」（HttpReadAloudService.preDownloadAudios：当前+1 .. 当前+N）：
     *   分析覆盖到「当前+N」，保证预合成目标章必已被分析，不再出现「朗读分析未就绪」白等/跳过。
     */
    private val analysisWindow: Int
        get() = preloadWindow + 1

    // 朗读会话状态（缓存追赶扫掠）
    private var sessionBookUrl: String? = null
    private var sessionAnchor = -1
    private var sweepJob: Job? = null

    private val _events = MutableSharedFlow<ReadyEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private data class Task(val bookUrl: String, val chapterIndex: Int, val force: Boolean)

    /** 触点1：朗读开始——设定会话并预热窗口 */
    fun startSession(bookUrl: String, chapterIndex: Int) {
        if (bookUrl.isBlank()) return
        sessionBookUrl = bookUrl
        sessionAnchor = chapterIndex
        enqueueWindow(bookUrl, chapterIndex)
        startSweepIfNeeded()
    }

    /** 触点2：换章/推进——更新锚点并补入窗口 */
    fun onChapterChanged(bookUrl: String, chapterIndex: Int) {
        if (bookUrl.isBlank()) return
        sessionBookUrl = bookUrl
        sessionAnchor = chapterIndex
        enqueueWindow(bookUrl, chapterIndex)
        startSweepIfNeeded()
    }

    /** 朗读结束——停止扫掠（队列内任务照常跑完） */
    fun stopSession() {
        sessionBookUrl = null
        sessionAnchor = -1
        sweepJob?.cancel()
        sweepJob = null
    }

    /** 预加载窗口入队：当前章起 ≤11 章（=「听书预加载数量」+1；覆盖到预合成最远章，见 [analysisWindow]） */
    fun enqueueWindow(bookUrl: String, fromIndex: Int, force: Boolean = false) {
        if (bookUrl.isBlank()) return
        val window = analysisWindow
        enqueueRange(bookUrl, fromIndex, window, force)
    }

    fun enqueueChapter(bookUrl: String, index: Int, force: Boolean = false) {
        enqueueRange(bookUrl, index, 1, force)
    }

    /** 触点3：缓存追赶扫掠——会话期间每 30s 重扫窗口 */
    private fun startSweepIfNeeded() {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                val b = sessionBookUrl ?: continue
                val anchor = sessionAnchor
                if (anchor >= 0) {
                    enqueueRange(b, anchor, analysisWindow, force = false)
                }
            }
        }
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
                .onFailure {
                    val chTitle = runCatching {
                        bookChapterDao.getChapter(task.bookUrl, task.chapterIndex)?.title
                    }.getOrNull()
                    AppLog.putAnalysis(
                        "【分析V3·${ChapterLabels.of(chTitle, task.chapterIndex)}】调度失败: ${it.localizedMessage}",
                        it,
                    )
                }
            queueMutex.withLock { queued.remove(key) }
        }
    }

    private suspend fun process(task: Task) {
        val book = bookDao.getBook(task.bookUrl) ?: return
        val index = task.chapterIndex
        val chapter = bookChapterDao.getChapterList(book.bookUrl, index, index).firstOrNull() ?: return
        val content = runCatching { BookHelp.getContent(book, chapter) }.getOrNull() ?: return // 未缓存章节跳过
        val paragraphs = buildReaderParagraphs(book, chapter, content)
        if (paragraphs.isEmpty()) return
        val prevText = loadChapterText(book, index - 1)
        val nextText = loadChapterText(book, index + 1)
        val result = pipeline.run(
            bookUrl = book.bookUrl,
            bookName = book.name,
            chapterIndex = index,
            paragraphs = paragraphs,
            prevChapterText = prevText,
            nextChapterText = nextText,
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
        val chapter = bookChapterDao.getChapterList(book.bookUrl, index, index).firstOrNull() ?: return ""
        return runCatching { BookHelp.getContent(book, chapter) }.getOrNull().orEmpty()
    }

    /**
     * 正文 → 朗读段落：与阅读器（ReadBook）/预下载（HttpReadAloudService.getPreDownloadChapter）
     * 同一条构建链（ContentProcessor → ReaderChapterSourceParser → ReaderReadAloudChapter）。
     *
     * B8.3.1 修正：此前用原始正文自行按行切分，与播放侧（阅读器加工后正文）得到两套 contentHash，
     * 导致「回填成功仍被重析」与「播放侧读不到新分析」。三处构建任一改动需同步。
     */
    private fun buildReaderParagraphs(
        book: Book,
        chapter: BookChapter,
        content: String,
    ): List<CanonicalSpeechParagraph> {
        val processed = ContentProcessor.get(book)
            .getContent(book, chapter, content, includeTitle = false)
        val source = ReaderChapterSourceParser.parse(
            chapterIndex = chapter.index,
            title = "",
            paragraphs = processed.textList,
            includeTitle = false,
            adaptSpecialStyle = readSettingsGateway.currentSettings.adaptSpecialStyle,
            htmlSemanticTextResolver = AndroidReaderHtmlSemanticTextResolver,
        )
        return ReaderReadAloudChapter.create(
            chapterIndex = chapter.index,
            title = "",
            semanticContent = source.semanticContent,
            pageStarts = emptyList(),
        ).canonicalSpeechParagraphs()
    }

    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        while (queue.tryReceive().isSuccess) Unit
        scope.launch { queueMutex.withLock { queued.clear() } }
        currentBookUrl = null
        stopSession()
    }
}
