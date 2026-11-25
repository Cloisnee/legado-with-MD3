package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.sourceKeyOf
import io.legado.app.domain.gateway.RaBookIdentityGateway
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaPipelineResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.readaloud.analysis.v4.BatchAnalysisRunner
import io.legado.app.utils.ChapterLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 朗读建库总调度（02 §1/§6/§7）：替代 AnalysisSchedulerV3。
 *
 * 前台：当前章 + 预加载章，允许触发阅读器既有下载；P0/P1/P2。
 * 后台：只消费已缓存正文，按锚点选批、有会话额度；缓存落盘事件唤醒。
 * 生命周期挂在 [io.legado.app.service.BaseReadAloudService]。
 */
class ReadAloudBuildCoordinator(
    private val pipeline: SpeechAnalysisPipelineV4,
    private val batch: BatchAnalysisRunner,
    private val textProvider: ChapterTextProvider,
    private val raBuild: RaBuildGateway,
    private val bookIdentity: RaBookIdentityGateway,
    private val bookDao: BookDao,
    private val bookChapterDao: BookChapterDao,
    private val readAloudSettings: ReadAloudSettingsGateway,
    private val analysisConfig: AnalysisConfigStore,
    private val jobs: ChapterJobRegistry,
) {

    data class ReadyEvent(
        val bookUrl: String,
        val chapterIndex: Int,
        val usedAi: Boolean,
        val message: String,
    )

    enum class BackgroundState {
        RUNNING,
        WAITING_CACHE,
        BUDGET_REACHED,
        PAUSED,
        STOPPED,
    }

    data class BuildStatus(
        val state: BackgroundState = BackgroundState.STOPPED,
        val budgetLeft: Int = 0,
        val builtChapters: Int = 0,
        val cachedPending: Int = 0,
        val bookUrl: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _events = MutableSharedFlow<ReadyEvent>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()
    private val _status = MutableStateFlow(BuildStatus())
    val status = _status.asStateFlow()

    private data class Session(
        val book: Book,
        val bookKey: String,
        val sourceKey: String,
        var anchor: Int,
        var lastChapter: Int,
        var budgetLeft: Int,
        var paused: Boolean,
        var fgFrom: Int,
        var fgCount: Int,
        var generation: Long,
    )

    private data class ForegroundTask(
        val bookUrl: String,
        val bookKey: String,
        val sourceKey: String,
        val chapterIndex: Int,
        val priority: AiPriority,
        val force: Boolean,
        val generation: Long,
    )

    private var session: Session? = null
    private var sessionRequest = 0L
    private val foregroundQueue = Channel<ForegroundTask>(Channel.UNLIMITED)
    private val queued = HashSet<String>()
    private var foregroundWorker: Job? = null
    private var activeForegroundJob: Job? = null
    private var backgroundWorker: Job? = null
    private var contentWatcher: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // ---------------- 生命周期 ----------------

    fun startSession(book: Book, chapterIndex: Int) {
        val snapshot = book.copy()
        val request = synchronized(lock) { ++sessionRequest }
        scope.launch {
            val sourceKey = sourceKeyOf(snapshot)
            val bookKey = bookIdentity.keyOf(snapshot.bookUrl, sourceKey)
            synchronized(lock) {
                if (request != sessionRequest) return@launch
            }
            beginSession(snapshot, bookKey, sourceKey, chapterIndex)
        }
    }

    private suspend fun beginSession(
        book: Book,
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ) {
        val cfg = runCatching { analysisConfig.load() }.getOrDefault(AnalysisConfigStore.Config())
        val preload = runCatching { readAloudSettings.currentSettings.audioPreDownloadNum }
            .getOrDefault(2).coerceIn(0, 10)
        val s = Session(
            book = book,
            bookKey = bookKey,
            sourceKey = sourceKey,
            anchor = chapterIndex,
            lastChapter = chapterIndex,
            budgetLeft = cfg.bgSessionBudget,
            paused = false,
            fgFrom = chapterIndex,
            fgCount = preload + 1,
            generation = 0L,
        )
        val old: Session?
        synchronized(lock) {
            old = session
            session = s
        }
        backgroundWorker?.cancel()
        contentWatcher?.cancel()
        foregroundWorker?.cancel()
        activeForegroundJob?.cancel()
        drainForeground()
        resetInterruptedStates(s)
        startContentWatcher(s)
        startForegroundWorker(s)
        enqueueWindow(s)
        if (cfg.bgSessionBudget > 0) startBackgroundWorker(s)
        setStatus(BackgroundState.RUNNING, s)
        if (old != null && old.book.bookUrl != book.bookUrl) {
            AppLog.putAnalysis("【V4·后台】切换书籍：${book.name}")
        }
    }

    fun onChapterChanged(bookUrl: String, chapterIndex: Int) {
        val s = synchronized(lock) { session }
        if (s == null || s.book.bookUrl != bookUrl) {
            scope.launch { bookDao.getBook(bookUrl)?.let { startSession(it, chapterIndex) } }
            return
        }
        val preload = runCatching { readAloudSettings.currentSettings.audioPreDownloadNum }
            .getOrDefault(2).coerceIn(0, 10)
        synchronized(lock) {
            val jump = chapterIndex != s.lastChapter + 1 && chapterIndex != s.lastChapter
            s.lastChapter = chapterIndex
            if (jump) s.anchor = chapterIndex
            s.fgFrom = chapterIndex
            s.fgCount = preload + 1
            s.generation++
        }
        activeForegroundJob?.cancel()
        drainForeground()
        enqueueWindow(s)
        wake.trySend(Unit)
        setStatus(BackgroundState.RUNNING, s)
    }

    fun pauseBackground() {
        val s = synchronized(lock) {
            session?.also { it.paused = true }
        } ?: return
        setStatus(BackgroundState.PAUSED, s)
    }

    fun resumeBackground() {
        val s = synchronized(lock) {
            session?.also { it.paused = false }
        } ?: return
        wake.trySend(Unit)
        setStatus(BackgroundState.RUNNING, s)
    }

    fun stopSession() {
        val s = synchronized(lock) {
            sessionRequest++
            val current = session
            session = null
            current?.generation = (current?.generation ?: 0L) + 1
            current
        }
        backgroundWorker?.cancel()
        backgroundWorker = null
        contentWatcher?.cancel()
        contentWatcher = null
        // 前台已入队的预加载章按现有行为继续完成；停止后不再接收新任务。
        setStatus(BackgroundState.STOPPED, s)
    }

    fun reanalyze(bookUrl: String, chapterIndex: Int) {
        scope.launch {
            val book = bookDao.getBook(bookUrl) ?: return@launch
            val sourceKey = sourceKeyOf(book)
            val bookKey = bookIdentity.keyOf(bookUrl, sourceKey)
            val s = synchronized(lock) { session }
            if (s != null && s.book.bookUrl == bookUrl) {
                enqueue(s, chapterIndex, AiPriority.P0_CURRENT, force = true)
            } else {
                processForeground(
                    ForegroundTask(
                        bookUrl = bookUrl,
                        bookKey = bookKey,
                        sourceKey = sourceKey,
                        chapterIndex = chapterIndex,
                        priority = AiPriority.P0_CURRENT,
                        force = true,
                        generation = -1L,
                    ),
                )
            }
        }
    }

    // ---------------- 前台 ----------------

    private fun startForegroundWorker(s: Session) {
        foregroundWorker = scope.launch {
            for (task in foregroundQueue) {
                val current = synchronized(lock) { session }
                if (current == null || current.book.bookUrl != task.bookUrl) continue
                if (task.generation >= 0 && task.generation != current.generation) continue
                val job = launch { processForeground(task) }
                activeForegroundJob = job
                job.join()
                activeForegroundJob = null
            }
        }
    }

    private fun enqueueWindow(s: Session) {
        val from = s.fgFrom
        for (offset in 0 until s.fgCount.coerceAtMost(11)) {
            val priority = when (offset) {
                0 -> AiPriority.P0_CURRENT
                1 -> AiPriority.P1_NEXT
                else -> AiPriority.P2_PRELOAD
            }
            enqueue(s, from + offset, priority, force = false)
        }
    }

    private fun enqueue(s: Session, chapterIndex: Int, priority: AiPriority, force: Boolean) {
        if (chapterIndex < 0) return
        val key = "${s.generation}|$chapterIndex|$force"
        synchronized(lock) {
            if (!force && !queued.add(key)) return
        }
        foregroundQueue.trySend(
            ForegroundTask(
                bookUrl = s.book.bookUrl,
                bookKey = s.bookKey,
                sourceKey = s.sourceKey,
                chapterIndex = chapterIndex,
                priority = priority,
                force = force,
                generation = s.generation,
            ),
        )
    }

    private fun drainForeground() {
        synchronized(lock) { queued.clear() }
        while (foregroundQueue.tryReceive().isSuccess) Unit
    }

    private suspend fun processForeground(task: ForegroundTask) {
        val book = bookDao.getBook(task.bookUrl) ?: return
        val text = textProvider.load(book, task.chapterIndex, allowDownload = true)
        if (text == null) {
            markNoContent(task.bookUrl, task.bookKey, task.sourceKey, task.chapterIndex)
            return
        }
        val build = raBuild.getBuild(task.bookKey, task.sourceKey, task.chapterIndex)
        if (!task.force && build?.state == RaChapterState.IDENTITY_READY &&
            build.contentHash == text.contentHash
        ) {
            emitReady(task, message = "已就绪", usedAi = true)
            return
        }
        val key = ChapterJobRegistry.Key(
            bookKey = task.bookKey,
            sourceKey = task.sourceKey,
            chapterIndex = task.chapterIndex,
            contentHash = text.contentHash,
        )
        repeat(2) {
            when (val acquire = jobs.acquire(key)) {
                is ChapterJobRegistry.Acquire.Join -> {
                    val outcome = acquire.result.await()
                    if (outcome == ChapterJobRegistry.Outcome.SUCCESS) {
                        emitReady(task, message = "已就绪", usedAi = true)
                        return
                    }
                    // 后台任务被抢占/失败：循环重试，第二次通常由自己执行。
                }
                is ChapterJobRegistry.Acquire.Owner -> {
                    try {
                        val result = pipeline.run(
                            bookUrl = task.bookUrl,
                            bookName = book.name,
                            bookKey = task.bookKey,
                            sourceKey = task.sourceKey,
                            chapterIndex = task.chapterIndex,
                            paragraphs = text.paragraphs,
                            prevChapterText = text.prevText,
                            nextChapterText = text.nextText,
                            force = task.force,
                            aiContext = AiSchedulerContext(
                                kind = AiTaskKind.STAGE1,
                                priority = task.priority,
                                bookKey = task.bookKey,
                                chapters = listOf(task.chapterIndex),
                                preemptible = false,
                            ),
                        )
                        val ok = result != null && result.analysis.status != RaAnalysisStatus.FAILED
                        acquire.ticket.complete(
                            if (ok) ChapterJobRegistry.Outcome.SUCCESS
                            else ChapterJobRegistry.Outcome.FAILED,
                        )
                        if (ok) {
                            emitReady(
                                task,
                                message = result!!.analysis.status.storageValue,
                                usedAi = result.segments.any { it.source.storageValue == "ai" },
                            )
                        }
                    } catch (e: CancellationException) {
                        acquire.ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
                        throw e
                    } catch (e: Throwable) {
                        acquire.ticket.complete(ChapterJobRegistry.Outcome.FAILED)
                        AppLog.putAnalysis("【V4·${label(task)}·前台】失败：${e.localizedMessage}", e)
                    }
                    return
                }
            }
        }
    }

    private suspend fun markNoContent(
        bookUrl: String,
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
    ) {
        val chapter = bookChapterDao.getChapter(bookUrl, chapterIndex) ?: return
        val existing = raBuild.getBuild(bookKey, sourceKey, chapterIndex)
        if (existing?.state == RaChapterState.IDENTITY_READY) return
        val state = if (chapter.isVolume) RaChapterState.SKIPPED_EMPTY else RaChapterState.NO_CONTENT
        val now = System.currentTimeMillis()
        raBuild.upsertBuild(
            RaChapterBuild(
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = chapterIndex,
                contentHash = existing?.contentHash ?: "",
                state = state,
                analysisId = existing?.analysisId,
                failReason = "",
                attempts = existing?.attempts ?: 0,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    private fun emitReady(task: ForegroundTask, message: String, usedAi: Boolean) {
        _events.tryEmit(
            ReadyEvent(
                bookUrl = task.bookUrl,
                chapterIndex = task.chapterIndex,
                usedAi = usedAi,
                message = message,
            ),
        )
    }

    private fun label(task: ForegroundTask): String =
        ChapterLabels.of("", task.chapterIndex)

    // ---------------- 后台 ----------------

    private suspend fun resetInterruptedStates(s: Session) {
        runCatching { raBuild.builds(s.bookKey, s.sourceKey) }
            .getOrDefault(emptyList())
            .filter { it.state == RaChapterState.EXTRACTING }
            .forEach { build ->
                raBuild.upsertBuild(
                    build.copy(
                        state = RaChapterState.CACHED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
    }

    private fun startContentWatcher(s: Session) {
        contentWatcher = scope.launch {
            ChapterContentEvents.events.collect { saved ->
                if (saved.bookUrl == s.book.bookUrl) wake.trySend(Unit)
            }
        }
    }

    private fun startBackgroundWorker(s: Session) {
        backgroundWorker = scope.launch {
            while (isActive) {
                val current = synchronized(lock) { session } ?: break
                if (current !== s) break
                if (s.paused) {
                    setStatus(BackgroundState.PAUSED, s)
                    wake.receive()
                    continue
                }
                if (s.budgetLeft <= 0) {
                    setStatus(BackgroundState.BUDGET_REACHED, s)
                    wake.receive()
                    continue
                }
                val cfg = runCatching { analysisConfig.load() }
                    .getOrDefault(AnalysisConfigStore.Config())
                val states = computeStates(s)
                val extracted = states.filterValues { it == RaChapterState.EXTRACTED }.keys
                if (extracted.isNotEmpty()) {
                    val texts = extracted.mapNotNull { chapter ->
                        textProvider.load(s.book, chapter, allowDownload = false)?.toBatchText(chapter)
                    }
                    val outcome = try {
                        kotlinx.coroutines.coroutineScope {
                            this.async {
                                batch.convergeExisting(s.bookKey, s.sourceKey, texts)
                            }.await()
                        }
                    } catch (e: CancellationException) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                        BatchAnalysisRunner.Outcome()
                    } catch (e: Throwable) {
                        AppLog.putAnalysis("【V4·后台】收敛恢复失败：${e.localizedMessage}", e)
                        BatchAnalysisRunner.Outcome()
                    }
                    s.budgetLeft -= outcome.ready.size + outcome.failed.size
                    setStatus(BackgroundState.RUNNING, s, states)
                    if (outcome.extracted.isNotEmpty() && outcome.ready.isEmpty()) {
                        kotlinx.coroutines.delay(15_000L)
                    }
                    continue
                }
                val occupied = jobs.occupiedChapters(s.bookKey, s.sourceKey)
                val fgWindow = s.fgFrom until (s.fgFrom + s.fgCount)
                val tokenEstimates = states.keys.associateWith { 2000 }
                val batchIndices = BatchSelector.nextBatch(
                    states = states,
                    anchor = s.anchor,
                    fgWindow = fgWindow,
                    budgetLeft = s.budgetLeft,
                    maxBatch = cfg.bgBatchMaxChapters,
                    chapterOutputTokens = tokenEstimates,
                    outputTokenCap = (cfg.maxOutputTokens * 0.8).toInt().coerceAtLeast(1024),
                    occupied = occupied,
                )
                if (batchIndices == null) {
                    setStatus(BackgroundState.WAITING_CACHE, s, states)
                    wake.receive()
                    continue
                }
                val texts = ArrayList<BatchAnalysisRunner.ChapterText>()
                batchIndices.forEach { chapter ->
                    val text = textProvider.load(s.book, chapter, allowDownload = false)
                    if (text == null) {
                        markNoContent(s.book.bookUrl, s.bookKey, s.sourceKey, chapter)
                    } else {
                        texts.add(text.toBatchText(chapter))
                    }
                }
                if (texts.isEmpty()) {
                    setStatus(BackgroundState.RUNNING, s)
                    continue
                }
                val started = System.currentTimeMillis()
                val outcome = runBatchDegraded(s, texts)
                s.budgetLeft -= outcome.ready.size + outcome.failed.size
                outcome.failed.forEach { chapter ->
                    markFailed(
                        s,
                        chapter,
                        texts.firstOrNull { it.chapterIndex == chapter }?.contentHash.orEmpty(),
                    )
                }
                setStatus(BackgroundState.RUNNING, s, computeStates(s))
                AppLog.putAnalysis(
                    "【V4·后台】批次 ${texts.first().chapterIndex + 1}~${texts.last().chapterIndex + 1} " +
                        "完成：就绪 ${outcome.ready.size}、失败 ${outcome.failed.size}、" +
                        "待收敛 ${outcome.extracted.size}，${(System.currentTimeMillis() - started) / 1000}s，" +
                        "额度剩余 ${s.budgetLeft}",
                )
            }
        }
    }

    private suspend fun runBatchDegraded(
        s: Session,
        texts: List<BatchAnalysisRunner.ChapterText>,
        depth: Int = 0,
    ): BatchAnalysisRunner.Outcome {
        val first = runBatchOnce(s, texts)
        val ready = first.ready.toMutableList()
        val extracted = first.extracted.toMutableList()
        var failed = first.failed.toMutableSet()
        if (depth == 0 && failed.isNotEmpty() && texts.size > 1) {
            val failedTexts = texts.filter { it.chapterIndex in failed }
            if (failedTexts.size > 1) {
                val mid = (failedTexts.size + 1) / 2
                failedTexts.chunked(mid).forEach { half ->
                    val halfIds = half.map { it.chapterIndex }.toSet()
                    failed.removeAll(halfIds)
                    val outcome = runBatchDegraded(s, half, depth + 1)
                    ready.addAll(outcome.ready)
                    extracted.addAll(outcome.extracted)
                    failed.removeAll(outcome.ready.toSet())
                    failed.removeAll(outcome.extracted.toSet())
                    failed.addAll(outcome.failed)
                }
            }
        }
        val remaining = failed.toList().sorted()
        remaining.forEach { chapter ->
            val text = texts.firstOrNull { it.chapterIndex == chapter } ?: return@forEach
            when (runSingle(s, text)) {
                true -> {
                    ready.add(chapter)
                    failed.remove(chapter)
                }
                null -> failed.remove(chapter)
                false -> Unit
            }
        }
        return BatchAnalysisRunner.Outcome(ready.distinct(), failed.toList(), extracted.distinct())
    }

    private suspend fun runBatchOnce(
        s: Session,
        texts: List<BatchAnalysisRunner.ChapterText>,
    ): BatchAnalysisRunner.Outcome {
        val owned = HashMap<Int, ChapterJobRegistry.Ticket>()
        val runnable = ArrayList<BatchAnalysisRunner.ChapterText>()
        texts.forEach { text ->
            val key = ChapterJobRegistry.Key(
                bookKey = s.bookKey,
                sourceKey = s.sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
            )
            when (val acquire = jobs.acquire(key)) {
                is ChapterJobRegistry.Acquire.Owner -> {
                    owned[text.chapterIndex] = acquire.ticket
                    runnable.add(text)
                }
                is ChapterJobRegistry.Acquire.Join -> Unit
            }
        }
        if (runnable.isEmpty()) return BatchAnalysisRunner.Outcome()
        var outcome = BatchAnalysisRunner.Outcome()
        try {
            // 用子协程承载批次：前台抢占只会取消这个子 Job，不会杀死后台 worker。
            outcome = kotlinx.coroutines.coroutineScope {
                this.async {
                    batch.run(
                        bookKey = s.bookKey,
                        sourceKey = s.sourceKey,
                        chapters = runnable,
                        batchId = "bg_${System.currentTimeMillis()}",
                        priority = AiPriority.P3_BACKGROUND,
                    )
                }.await()
            }
        } catch (e: CancellationException) {
            owned.values.forEach { ticket ->
                if (!ticket.result.isCompleted) ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            // 前台抢占：未提交章节保持 CACHED/EXTRACTED，下一轮继续。
            AppLog.putAnalysis("【V4·后台】批次被前台抢占，已提交章节保留")
            return BatchAnalysisRunner.Outcome()
        } catch (e: Throwable) {
            AppLog.putAnalysis("【V4·后台】批次异常：${e.localizedMessage}", e)
            outcome = BatchAnalysisRunner.Outcome(failed = runnable.map { it.chapterIndex })
        } finally {
            owned.forEach { (chapter, ticket) ->
                if (!ticket.result.isCompleted) {
                    val success = chapter in outcome.ready || chapter in outcome.extracted
                    ticket.complete(
                        if (success) ChapterJobRegistry.Outcome.SUCCESS
                        else ChapterJobRegistry.Outcome.FAILED,
                    )
                }
            }
        }
        return outcome
    }

    private suspend fun markFailed(s: Session, chapterIndex: Int, contentHash: String) {
        val existing = raBuild.getBuild(s.bookKey, s.sourceKey, chapterIndex)
        val now = System.currentTimeMillis()
        raBuild.upsertBuild(
            RaChapterBuild(
                bookKey = s.bookKey,
                sourceKey = s.sourceKey,
                chapterIndex = chapterIndex,
                contentHash = contentHash.ifBlank { existing?.contentHash.orEmpty() },
                state = RaChapterState.FAILED,
                analysisId = existing?.analysisId,
                failReason = "后台分析失败",
                attempts = (existing?.attempts ?: 0) + 1,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    /** true=成功，false=失败，null=已有前台任务（不重复计数）。 */
    private suspend fun runSingle(
        s: Session,
        text: BatchAnalysisRunner.ChapterText,
    ): Boolean? {
        val key = ChapterJobRegistry.Key(
            bookKey = s.bookKey,
            sourceKey = s.sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
        )
        val acquire = jobs.acquire(key)
        if (acquire is ChapterJobRegistry.Acquire.Join) {
            acquire.result.await()
            return null
        }
        val ticket = (acquire as ChapterJobRegistry.Acquire.Owner).ticket
        val book = bookDao.getBook(s.book.bookUrl) ?: run {
            ticket.complete(ChapterJobRegistry.Outcome.FAILED)
            return false
        }
        return try {
            val result: RaPipelineResult? = kotlinx.coroutines.coroutineScope {
                this.async {
                    pipeline.run(
                        bookUrl = s.book.bookUrl,
                        bookName = book.name,
                        bookKey = s.bookKey,
                        sourceKey = s.sourceKey,
                        chapterIndex = text.chapterIndex,
                        paragraphs = text.paragraphs,
                        prevChapterText = text.prevText,
                        nextChapterText = text.nextText,
                        aiContext = AiSchedulerContext(
                            kind = AiTaskKind.STAGE1,
                            priority = AiPriority.P3_BACKGROUND,
                            bookKey = s.bookKey,
                            chapters = listOf(text.chapterIndex),
                            preemptible = true,
                        ),
                    )
                }.await()
            }
            val ok = result != null && result.analysis.status != RaAnalysisStatus.FAILED
            ticket.complete(
                if (ok) ChapterJobRegistry.Outcome.SUCCESS
                else ChapterJobRegistry.Outcome.FAILED,
            )
            ok
        } catch (e: CancellationException) {
            if (!ticket.result.isCompleted) ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            null
        } catch (e: Throwable) {
            AppLog.putAnalysis("【V4·后台】单章重试失败：${e.localizedMessage}", e)
            ticket.complete(ChapterJobRegistry.Outcome.FAILED)
            false
        }
    }

    // ---------------- 状态 ----------------

    private suspend fun computeStates(s: Session): Map<Int, RaChapterState> {
        val chapters = bookChapterDao.getChapterList(s.book.bookUrl)
        val builds = runCatching { raBuild.builds(s.bookKey, s.sourceKey) }
            .getOrDefault(emptyList())
            .associateBy { it.chapterIndex }
        return chapters.associate { chapter ->
            val build = builds[chapter.index]
            val hasContent = BookHelp.hasContent(s.book, chapter)
            val state = when {
                build == null -> if (hasContent) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                build.state == RaChapterState.IDENTITY_READY ||
                    build.state == RaChapterState.SKIPPED_EMPTY -> build.state
                build.state == RaChapterState.EXTRACTED ||
                    build.state == RaChapterState.STALE -> build.state
                build.state == RaChapterState.FAILED && build.attempts >= 3 -> build.state
                build.state == RaChapterState.FAILED -> RaChapterState.CACHED
                build.state == RaChapterState.NO_CONTENT ->
                    if (hasContent) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                else -> RaChapterState.CACHED
            }
            chapter.index to state
        }
    }

    private fun setStatus(
        state: BackgroundState,
        s: Session?,
        states: Map<Int, RaChapterState>? = null,
    ) {
        val previous = _status.value
        val built = states?.values?.count { it == RaChapterState.IDENTITY_READY }
            ?: previous.builtChapters
        val pending = states?.values?.count {
            it == RaChapterState.CACHED || it == RaChapterState.STALE
        } ?: previous.cachedPending
        val next = BuildStatus(
            state = state,
            budgetLeft = s?.budgetLeft ?: 0,
            builtChapters = built,
            cachedPending = pending,
            bookUrl = s?.book?.bookUrl.orEmpty(),
        )
        if (next != previous) {
            if (next.state != previous.state) {
                AppLog.putAnalysis("【V4·后台】状态 ${previous.state} → ${next.state}")
            }
            _status.value = next
        }
    }

    private fun ChapterTextProvider.Text.toBatchText(chapterIndex: Int): BatchAnalysisRunner.ChapterText =
        BatchAnalysisRunner.ChapterText(
            chapterIndex = chapterIndex,
            chapterLabel = ChapterLabels.of(title, chapterIndex),
            paragraphs = paragraphs,
            prevText = prevText,
            nextText = nextText,
            contentHash = contentHash,
        )
}
