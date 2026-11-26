package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.sourceKeyOf
import io.legado.app.domain.gateway.RaBookIdentityGateway
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaPipelineResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.readaloud.analysis.v4.BatchAnalysisRunner
import io.legado.app.help.readaloud.analysis.SpeechAnalysisPipelineV4
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
        var stopping: Boolean = false,
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
        val loadedAt: Long = System.currentTimeMillis(),
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
        val existing = synchronized(lock) { session }
        if (existing != null && !existing.stopping && existing.book.bookUrl == book.bookUrl) {
            // 同一朗读会话内的重新准备（curPageChanged/newReadAloud）不重置额度和锚点。
            onChapterChanged(book.bookUrl, chapterIndex)
            return
        }
        val request = synchronized(lock) { ++sessionRequest }
        scope.launch {
            val sourceKey = sourceKeyOf(snapshot)
            val bookKey = bookIdentity.keyOf(snapshot.bookUrl, sourceKey)
            synchronized(lock) {
                if (request != sessionRequest) return@launch
            }
            beginSession(snapshot, bookKey, sourceKey, chapterIndex, request)
        }
    }

    private suspend fun beginSession(
        book: Book,
        bookKey: String,
        sourceKey: String,
        chapterIndex: Int,
        request: Long,
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
            stopping = false,
            fgFrom = chapterIndex,
            fgCount = preload + 1,
            generation = 0L,
        )
        val old: Session?
        synchronized(lock) {
            // 读配置期间可能已经停止/换书：旧启动不得发布状态。
            if (request != sessionRequest) return
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
        startForegroundWorker()
        enqueueWindow(s)
        if (cfg.bgSessionBudget > 0) {
            startBackgroundWorker(s)
            setStatus(BackgroundState.RUNNING, s)
        } else {
            setStatus(BackgroundState.BUDGET_REACHED, s)
        }
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
        if (s.stopping) return
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
            current?.stopping = true
            current?.generation = (current?.generation ?: 0L) + 1
            current
        }
        backgroundWorker?.cancel()
        backgroundWorker = null
        contentWatcher?.cancel()
        contentWatcher = null
        // 已入队的前台预加载继续跑完；不再领新的后台批次。
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
                        loadedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    // ---------------- 前台 ----------------

    private fun startForegroundWorker() {
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
                loadedAt = System.currentTimeMillis(),
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
        // 正文读取完成后的时刻作为本任务版本：旧任务晚归时不会覆盖新指针。
        val taskStartedAt = System.currentTimeMillis()
        val build = raBuild.getBuild(task.bookKey, task.sourceKey, task.chapterIndex)
        if (!task.force && build?.state == RaChapterState.IDENTITY_READY &&
            build.contentHash == text.contentHash
        ) {
            emitReady(task, message = "已就绪", usedAi = true)
            return
        }
        if (!task.force && build?.state == RaChapterState.EXTRACTED &&
            build.contentHash == text.contentHash
        ) {
            // 批次已逐章提交但尚未收敛（或 ticket 刚被移除）：直接做本章局部收敛。
            val local = batch.convergeExisting(
                bookKey = task.bookKey,
                sourceKey = task.sourceKey,
                chapters = listOf(text.toBatchText(task.chapterIndex)),
                priority = AiPriority.P0_CURRENT,
            )
            if (local.ready.contains(task.chapterIndex)) {
                emitReady(task, message = "已就绪", usedAi = true)
                return
            }
        }
        if (!task.force && tryReuseFromOtherSource(task.bookKey, task.sourceKey, text, task.chapterIndex)) {
            emitReady(task, message = "换源复用", usedAi = false)
            return
        }
        val key = ChapterJobRegistry.Key(
            bookKey = task.bookKey,
            sourceKey = task.sourceKey,
            chapterIndex = task.chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
            stage = if (task.force) "REANALYZE-${System.nanoTime()}" else "EXTRACT",
        )
        repeat(2) {
            when (val acquire = jobs.acquire(key)) {
                is ChapterJobRegistry.Acquire.Join -> {
                    val outcome = acquire.result.await()
                    when (outcome) {
                        ChapterJobRegistry.Outcome.SUCCESS -> {
                            emitReady(task, message = "已就绪", usedAi = true)
                            return
                        }
                        ChapterJobRegistry.Outcome.EXTRACTED -> {
                            // 批次已逐章提交观察包：前台只对本章做局部收敛，不等整批。
                            val local = batch.convergeExisting(
                                bookKey = task.bookKey,
                                sourceKey = task.sourceKey,
                                chapters = listOf(text.toBatchText(task.chapterIndex)),
                                priority = AiPriority.P0_CURRENT,
                            )
                            if (local.ready.contains(task.chapterIndex)) {
                                emitReady(task, message = "已就绪", usedAi = true)
                                return
                            }
                        }
                        else -> Unit
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
                            taskStartedAt = taskStartedAt,
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

    /**
     * 换源复用（02 §8）：同 bookKey、同 chapterIndex、同 contentHash 已有其他 sourceKey 的 RESOLVED 分析时，
     * 复制段落/章内说话人/提及/证据到当前 sourceKey，不调用 AI。
     */
    private suspend fun tryReuseFromOtherSource(
        bookKey: String,
        sourceKey: String,
        text: ChapterTextProvider.Text,
        chapterIndex: Int,
    ): Boolean = runCatching {
        val old = raBuild.analysisByContentHash(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
        ) ?: return@runCatching false
        if (old.sourceKey == sourceKey) return@runCatching false
        val oldSegments = raBuild.segments(old.id)
        if (oldSegments.isEmpty()) return@runCatching false
        val oldSpeakers = raBuild.localSpeakers(old.id)
        val oldMentions = raBuild.localMentions(old.id)
        val oldEvidence = raBuild.evidence(old.id)
        val newAnalysisId = SpeechIdentity.analysisId(
            bookKey = bookKey,
            sourceKey = sourceKey,
            chapterIndex = chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
        )
        val speakerIds = HashMap<String, String>()
        oldSpeakers.forEach { speaker -> speakerIds[speaker.id] = "$newAnalysisId#${speaker.localKey}" }
        oldMentions.forEach { mention -> speakerIds[mention.id] = "$newAnalysisId#${mention.localKey}" }
        val now = System.currentTimeMillis()
        val newSpeakers = oldSpeakers.map { speaker ->
            speaker.copy(id = speakerIds.getValue(speaker.id), analysisId = newAnalysisId)
        }
        val newMentions = oldMentions.map { mention ->
            mention.copy(id = speakerIds.getValue(mention.id), analysisId = newAnalysisId)
        }
        val newSegments = oldSegments.map { segment ->
            segment.copy(
                id = SpeechIdentity.segmentId(
                    newAnalysisId,
                    segment.paragraphIndex,
                    segment.start,
                    segment.end,
                ),
                analysisId = newAnalysisId,
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                localSpeakerId = segment.localSpeakerId?.let(speakerIds::get),
                createdAt = now,
                updatedAt = now,
            )
        }
        val newEvidence = oldEvidence.map { evidence ->
            val subject = speakerIds[evidence.subjectLocalId] ?: evidence.subjectLocalId
            evidence.copy(
                id = SpeechIdentity.evidenceId(
                    analysisId = newAnalysisId,
                    sourceChapter = evidence.sourceChapter,
                    paragraphIndex = evidence.paragraphIndex,
                    start = evidence.start,
                    type = evidence.type.storageValue,
                    subjectLocalId = subject,
                    objectText = evidence.objectText,
                    quote = evidence.excerpt,
                ),
                analysisId = newAnalysisId,
                subjectLocalId = subject,
                createdAt = now,
                updatedAt = now,
            )
        }
        val older = raBuild.getBuild(bookKey, sourceKey, chapterIndex)
        raBuild.saveAnalysisBundle(
            analysis = RaAnalysis(
                id = newAnalysisId,
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = chapterIndex,
                contentHash = text.contentHash,
                resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                status = RaAnalysisStatus.RESOLVED,
                origin = RaAnalysisOrigin.FOREGROUND,
                modelId = old.modelId,
                createdAt = now,
                updatedAt = now,
            ),
            segments = newSegments,
            localSpeakers = newSpeakers,
            localMentions = newMentions,
            evidence = newEvidence,
            build = RaChapterBuild(
                bookKey = bookKey,
                sourceKey = sourceKey,
                chapterIndex = chapterIndex,
                contentHash = text.contentHash,
                state = RaChapterState.IDENTITY_READY,
                analysisId = newAnalysisId,
                failReason = "",
                attempts = older?.attempts ?: 0,
                createdAt = older?.createdAt ?: now,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        AppLog.putAnalysis("【V4·${labelFor(text, chapterIndex)}·换源复用】同正文分析来自 ${old.sourceKey}")
        true
    }.getOrDefault(false)

    private fun labelFor(text: ChapterTextProvider.Text, chapterIndex: Int): String =
        ChapterLabels.of(text.title, chapterIndex)

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
                if (saved.bookUrl == s.book.bookUrl) {
                    val build = raBuild.getBuild(s.bookKey, s.sourceKey, saved.chapterIndex)
                    val fileUpdated = runCatching {
                        val chapter = bookChapterDao.getChapter(s.book.bookUrl, saved.chapterIndex)
                        if (chapter == null) 0L else {
                            BookHelp.getChapterContentFile(s.book, chapter).lastModified()
                        }
                    }.getOrDefault(0L)
                    if (build != null) {
                        when {
                            build.state == RaChapterState.NO_CONTENT -> raBuild.upsertBuild(
                                build.copy(
                                    state = RaChapterState.CACHED,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                            build.state == RaChapterState.IDENTITY_READY &&
                                fileUpdated > build.updatedAt -> raBuild.upsertBuild(
                                build.copy(
                                    state = RaChapterState.STALE,
                                    failReason = "正文已更新",
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                    wake.trySend(Unit)
                }
            }
        }
    }

    private fun startBackgroundWorker(s: Session) {
        backgroundWorker = scope.launch {
            while (isActive) {
                val current = synchronized(lock) { session } ?: break
                if (current !== s) break
                if (s.stopping) break
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
                    .take(s.budgetLeft.coerceAtLeast(0))
                    .toSet()
                if (extracted.isNotEmpty()) {
                    val texts = extracted.mapNotNull { chapter ->
                        textProvider.load(s.book, chapter, allowDownload = false)?.toBatchText(chapter)
                    }
                    if (texts.isEmpty()) {
                        // 正文已不可读：不要把 EXTRACTED 留在选择集里空转；等缓存事件再恢复。
                        extracted.forEach { chapter ->
                            markNoContent(s.book.bookUrl, s.bookKey, s.sourceKey, chapter)
                        }
                        setStatus(BackgroundState.WAITING_CACHE, s, computeStates(s))
                        wake.receive()
                        continue
                    }
                    val outcome: BatchAnalysisRunner.Outcome = try {
                        kotlinx.coroutines.coroutineScope {
                            this.async {
                                batch.convergeExisting(
                                    s.bookKey,
                                    s.sourceKey,
                                    texts,
                                    AiPriority.P3_BACKGROUND,
                                )
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
                    setStatus(BackgroundState.RUNNING, s, computeStates(s))
                    if (outcome.ready.isEmpty() && outcome.failed.isEmpty() &&
                        outcome.extracted.isEmpty()
                    ) {
                        // 恢复异常或正文版本不符：受控退避，避免满速重扫。
                        kotlinx.coroutines.delay(15_000L)
                    }
                    continue
                }
                val occupied = jobs.occupiedChapters(s.bookKey, s.sourceKey)
                val fgWindow = s.fgFrom until (s.fgFrom + s.fgCount)
                val chapters = bookChapterDao.getChapterList(s.book.bookUrl)
                val tokenEstimates = chapters.associate { chapter ->
                    chapter.index to estimateChapterTokens(s.book, chapter)
                }
                val batchIndices = BatchSelector.nextBatch(
                    states = states,
                    anchor = s.anchor,
                    fgWindow = fgWindow,
                    budgetLeft = s.budgetLeft,
                    maxBatch = cfg.bgBatchMaxChapters,
                    chapterOutputTokens = tokenEstimates,
                    outputTokenCap = (cfg.maxOutputTokens * 0.8).toInt().coerceAtLeast(1),
                    occupied = occupied,
                )
                if (batchIndices == null) {
                    setStatus(BackgroundState.WAITING_CACHE, s, states)
                    wake.receive()
                    continue
                }
                val texts = ArrayList<BatchAnalysisRunner.ChapterText>()
                val missing = ArrayList<Int>()
                batchIndices.forEach { chapter ->
                    val text = textProvider.load(s.book, chapter, allowDownload = false)
                    if (text == null) {
                        missing.add(chapter)
                        markNoContent(s.book.bookUrl, s.bookKey, s.sourceKey, chapter)
                    } else {
                        texts.add(text.toBatchText(chapter))
                    }
                }
                if (missing.isNotEmpty()) {
                    // D7：缺口断开批次，不把缺口两侧拼成一批；下一轮从另一侧继续。
                    setStatus(BackgroundState.RUNNING, s, computeStates(s))
                    continue
                }
                if (texts.isEmpty()) {
                    setStatus(BackgroundState.WAITING_CACHE, s, states)
                    wake.receive()
                    continue
                }
                val started = System.currentTimeMillis()
                val outcome = runBatchDegraded(s, texts)
                s.budgetLeft -= outcome.ready.size + outcome.failed.size
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
        val failed = first.failed.toMutableSet()
        if (depth == 0 && failed.size > 1 && texts.size > 1) {
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
        if (depth == 0) {
            val remaining = failed.toList().sorted()
            val singleAttempted = HashSet<Int>()
            remaining.forEach { chapter ->
                val text = texts.firstOrNull { it.chapterIndex == chapter } ?: return@forEach
                singleAttempted.add(chapter)
                when (runSingle(s, text)) {
                    true -> {
                        ready.add(chapter)
                        failed.remove(chapter)
                    }
                    null -> failed.remove(chapter)
                    false -> Unit
                }
            }
            // 只给“没经过单章管线”的批次失败补 FAILED 计数；单章失败已由管线自己 +1。
            failed.filter { it !in singleAttempted }.forEach { chapter ->
                markFailed(
                    s,
                    chapter,
                    texts.firstOrNull { it.chapterIndex == chapter }?.contentHash.orEmpty(),
                )
            }
        }
        return BatchAnalysisRunner.Outcome(ready.distinct(), failed.toList(), extracted.distinct())
    }

    private suspend fun runBatchOnce(
        s: Session,
        texts: List<BatchAnalysisRunner.ChapterText>,
    ): BatchAnalysisRunner.Outcome {
        val owned = java.util.concurrent.ConcurrentHashMap<Int, ChapterJobRegistry.Ticket>()
        val earlyReady = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val earlyExtracted = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val runnable = ArrayList<BatchAnalysisRunner.ChapterText>()
        texts.forEach { text ->
            val key = ChapterJobRegistry.Key(
                bookKey = s.bookKey,
                sourceKey = s.sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                stage = "EXTRACT",
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
        val onChapterReady: suspend (Int, Boolean) -> Unit = { chapter, identityReady ->
            if (identityReady) earlyReady.add(chapter) else earlyExtracted.add(chapter)
            owned.remove(chapter)?.let { ticket ->
                if (!ticket.result.isCompleted) {
                    ticket.complete(
                        if (identityReady) ChapterJobRegistry.Outcome.SUCCESS
                        else ChapterJobRegistry.Outcome.EXTRACTED,
                    )
                }
            }
        }
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
                        onChapterReady = onChapterReady,
                    )
                }.await()
            }
        } catch (e: CancellationException) {
            owned.values.forEach { ticket ->
                if (!ticket.result.isCompleted) ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
            }
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw e
            AppLog.putAnalysis("【V4·后台】批次被前台抢占，已提交章节保留")
            return BatchAnalysisRunner.Outcome(
                ready = earlyReady.toList().distinct(),
                extracted = earlyExtracted.toList().distinct(),
            )
        } catch (e: Throwable) {
            AppLog.putAnalysis("【V4·后台】批次异常：${e.localizedMessage}", e)
            outcome = BatchAnalysisRunner.Outcome(failed = runnable.map { it.chapterIndex })
        } finally {
            owned.forEach { (chapter, ticket) ->
                if (!ticket.result.isCompleted) {
                    val state = when {
                        chapter in outcome.ready || chapter in earlyReady ->
                            ChapterJobRegistry.Outcome.SUCCESS
                        chapter in outcome.extracted || chapter in earlyExtracted ->
                            ChapterJobRegistry.Outcome.EXTRACTED
                        else -> ChapterJobRegistry.Outcome.FAILED
                    }
                    ticket.complete(state)
                }
            }
        }
        return BatchAnalysisRunner.Outcome(
            ready = (earlyReady.toList() + outcome.ready).distinct(),
            failed = outcome.failed,
            extracted = (earlyExtracted.toList() + outcome.extracted).distinct(),
        )
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
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
            stage = "EXTRACT",
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
                        taskStartedAt = System.currentTimeMillis(),
                        skipLegacyAudio = true,
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

    private fun estimateChapterTokens(book: Book, chapter: io.legado.app.data.entities.BookChapter): Int {
        val bytes = runCatching {
            BookHelp.getChapterContentFile(book, chapter).length()
        }.getOrDefault(0L)
        val chars = if (bytes > 0L) (bytes / 3L).coerceAtLeast(1L) else 2_000L
        val dialogues = (chars / 40L).toInt().coerceAtLeast(1)
        return (dialogues * 60 + 800).coerceIn(400, 30_000)
    }

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
                build.state == RaChapterState.FAILED ->
                    if (hasContent) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                build.state == RaChapterState.NO_CONTENT -> {
                    // 跨会话补缓存：文件比 NO_CONTENT 记录新才重新纳入；同文件不反复重试。
                    val fileUpdated = runCatching {
                        BookHelp.getChapterContentFile(s.book, chapter).lastModified()
                    }.getOrDefault(0L)
                    if (hasContent && fileUpdated > build.updatedAt) RaChapterState.CACHED
                    else RaChapterState.NO_CONTENT
                }
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
        val sameBook = s?.book?.bookUrl == previous.bookUrl
        val built = states?.values?.count { it == RaChapterState.IDENTITY_READY }
            ?: if (sameBook) previous.builtChapters else 0
        val pending = states?.values?.count {
            it == RaChapterState.CACHED || it == RaChapterState.STALE
        } ?: if (sameBook) previous.cachedPending else 0
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
            loadedAt = System.currentTimeMillis(),
        )
}
