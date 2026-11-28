package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.sourceKeyOf
import io.legado.app.domain.gateway.RaBookIdentityGateway
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaBuildInput
import io.legado.app.domain.gateway.RaBuildPublication
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisOrigin
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterBuild
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaPipelineResult
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.readaloud.analysis.v4.BatchAnalysisRunner
import io.legado.app.utils.ChapterLabels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 朗读建库总调度（02 §1/§6/§7）：统一前后台建库。
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

    private class Session(
        val id: Long,
        val owner: Any?,
        val book: Book,
        val bookKey: String,
        val sourceKey: String,
        var window: Window,
        val buildGeneration: Long,
        var budgetLeft: Int,
        var paused: Boolean,
    ) {
        var stopping = false
        val foregroundWake = Channel<Unit>(Channel.CONFLATED)
        val pendingForeground = LinkedHashMap<Int, ForegroundTask>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var worker: Job? = null
        var activeJob: Job? = null
        var activeChapter: Int? = null
        var background: Job? = null
        var watcher: Job? = null
        // 只由后台 worker 访问。保留核验结果，不改变 READY 记录的发布时间。
        val verifiedCache = HashMap<Int, VerifiedCache>()
        val changedCache = ConcurrentHashMap<Int, Any>()
    }

    private data class VerifiedCache(val build: RaChapterBuild, val modifiedAt: Long)

    private data class Window(val anchor: Int, val chapters: IntRange, val generation: Long = 0)

    private data class ForegroundTask(
        val book: Book,
        val bookKey: String,
        val sourceKey: String,
        val chapterIndex: Int,
        val priority: AiPriority,
        val force: Boolean,
        val buildGeneration: Long,
        val sessionId: Long? = null,
        val cacheRefresh: Boolean = false,
    ) {
        val bookUrl: String get() = book.bookUrl
    }

    private class StartIntent(
        val id: Long,
        val owner: Any?,
        val book: Book,
        val sourceKey: String,
        var anchor: Int,
        var chapter: Int,
        var paused: Boolean,
        val buildGeneration: Long,
    ) {
        var job: Job? = null
    }

    private var pendingStart: StartIntent? = null
    private var session: Session? = null
    private var sessionRequest = 0L

    // ---------------- 生命周期 ----------------

    fun startSession(
        book: Book,
        chapterIndex: Int,
        paused: Boolean = false,
        owner: Any? = null,
        buildGeneration: Long = raBuild.generation(),
    ) {
        val snapshot = book.copy()
        val sourceKey = sourceKeyOf(snapshot)
        synchronized(lock) {
            if (!raBuild.canResolveBookUrl(snapshot.bookUrl, buildGeneration)) return
            val request = ++sessionRequest
            pendingStart?.job?.cancel()
            pendingStart = null
            val existing = session
            if (existing != null && !existing.stopping && existing.owner === owner &&
                existing.book.bookUrl == snapshot.bookUrl && existing.sourceKey == sourceKey &&
                raBuild.canUseGeneration(existing.bookKey, chapterIndex, existing.buildGeneration)
            ) {
                existing.paused = paused
                moveWindow(existing, chapterIndex)
                existing.wake.trySend(Unit)
                setStatus(BackgroundState.RUNNING, existing)
                return
            }
            val intent = StartIntent(request, owner, snapshot, sourceKey, chapterIndex, chapterIndex, paused, buildGeneration)
            pendingStart = intent
            intent.job = scope.launch { beginSession(intent) }
        }
    }

    private suspend fun beginSession(intent: StartIntent) {
        val bookKey = bookIdentity.keyOf(intent.book.bookUrl, intent.sourceKey, intent.buildGeneration)
        if (bookKey == null) {
            synchronized(lock) { if (pendingStart === intent) pendingStart = null }
            return
        }
        val cfg = runCatching { analysisConfig.load() }.getOrElse {
            if (it is CancellationException) throw it
            AnalysisConfigStore.Config()
        }
        synchronized(lock) {
            if (pendingStart !== intent) return
            pendingStart = null
            if (!raBuild.canUseGeneration(bookKey, intent.chapter, intent.buildGeneration)) return
            val old = session
            old?.background?.cancel()
            old?.watcher?.cancel()
            if (old != null && !old.stopping) {
                old.pendingForeground.clear()
                old.foregroundWake.cancel()
                old.worker?.cancel()
            }
            val s = Session(
                intent.id, intent.owner, intent.book, bookKey, intent.sourceKey,
                Window(intent.anchor, intent.chapter..intent.chapter + preloadCount()),
                intent.buildGeneration, cfg.bgSessionBudget, intent.paused,
            )
            session = s
            startForegroundWorker(s)
            enqueueWindow(s)
            startContentWatcher(s)
            if (s.budgetLeft > 0) startBackgroundWorker(s)
            setStatus(BackgroundState.RUNNING, s)
            if (old != null && old.book.bookUrl != s.book.bookUrl) {
                AppLog.putAnalysis("【V4·后台】切换书籍：${s.book.name}")
            }
        }
    }

    fun onChapterChanged(bookUrl: String, chapterIndex: Int, owner: Any? = null, sourceKey: String? = null) = synchronized(lock) {
        pendingStart?.takeIf { it.owner === owner && it.book.bookUrl == bookUrl &&
            (sourceKey == null || it.sourceKey == sourceKey) }?.let { intent ->
            if (chapterIndex != intent.chapter && chapterIndex != intent.chapter + 1) intent.anchor = chapterIndex
            intent.chapter = chapterIndex
            return@synchronized
        }
        session?.takeIf { it.owner === owner && !it.stopping && it.book.bookUrl == bookUrl &&
            (sourceKey == null || it.sourceKey == sourceKey) }
            ?.let { moveWindow(it, chapterIndex) }
        Unit
    }

    private fun preloadCount(): Int = runCatching { readAloudSettings.currentSettings.audioPreDownloadNum }
        .getOrDefault(2).coerceIn(0, 10)

    private fun moveWindow(s: Session, chapterIndex: Int) {
        val old = s.window
        val previous = old.chapters.first
        if (chapterIndex == previous) return
        val chapters = chapterIndex..chapterIndex + preloadCount()
        s.window = Window(if (chapterIndex == previous + 1) old.anchor else chapterIndex, chapters, old.generation + 1)
        if (s.activeChapter !in chapters) s.activeJob?.cancel()
        s.pendingForeground.clear()
        enqueueWindow(s)
        s.wake.trySend(Unit)
        setStatus(BackgroundState.RUNNING, s)
    }

    fun pauseBackground(owner: Any? = null) = setPaused(true, owner)

    fun resumeBackground(owner: Any? = null) = setPaused(false, owner)

    private fun setPaused(paused: Boolean, owner: Any?) = synchronized(lock) {
        pendingStart?.takeIf { it.owner === owner }?.paused = paused
        session?.takeIf { it.owner === owner && !it.stopping }?.let { s ->
            s.paused = paused
            s.wake.trySend(Unit)
            setStatus(BackgroundState.RUNNING, s)
        }
        Unit
    }

    /** 停止领取新任务；返回仍需收尾的任务。 */
    fun stopSession(owner: Any? = null): List<Job> = synchronized(lock) {
        val starting = pendingStart?.takeIf { it.owner === owner }?.let {
            pendingStart = null
            it.job?.also { job -> job.cancel() }
        }
        val current = session?.takeIf { it.owner === owner } ?: return@synchronized listOfNotNull(starting)
        current.stopping = true
        current.background?.cancel()
        current.watcher?.cancel()
        // 已入队的前台预加载继续跑完。
        current.foregroundWake.close()
        setStatus(BackgroundState.STOPPED, current)
        listOfNotNull(starting, current.worker, current.background, current.watcher)
    }

    fun reanalyze(bookUrl: String, chapterIndex: Int) {
        val buildGeneration = raBuild.generation()
        scope.launch {
            val book = bookDao.getBook(bookUrl) ?: return@launch
            val sourceKey = sourceKeyOf(book)
            val bookKey = bookIdentity.keyOf(bookUrl, sourceKey, buildGeneration) ?: return@launch
            if (!raBuild.canUseGeneration(bookKey, chapterIndex, buildGeneration)) return@launch
            val task = ForegroundTask(book, bookKey, sourceKey, chapterIndex,
                AiPriority.P0_CURRENT, force = true, buildGeneration = buildGeneration)
            val queued = synchronized(lock) {
                val s = session?.takeIf { !it.stopping && it.book.bookUrl == bookUrl &&
                    it.sourceKey == sourceKey && it.bookKey == bookKey } ?: return@synchronized false
                s.pendingForeground[chapterIndex] = task.copy(sessionId = s.id)
                s.foregroundWake.trySend(Unit)
                true
            }
            if (!queued) processForeground(task)
        }
    }

    // ---------------- 前台 ----------------

    private fun startForegroundWorker(s: Session) {
        s.worker = scope.launch {
            while (isActive) {
                val job = synchronized(lock) {
                    if (session !== s && !s.stopping) return@synchronized null
                    // Pick by priority when the worker is free, not when a cache event arrives.
                    val task = s.pendingForeground.values.minByOrNull { it.priority.level }
                        ?: return@synchronized null
                    s.pendingForeground.remove(task.chapterIndex)
                    launch(start = CoroutineStart.LAZY) { processForeground(task) }.also {
                        s.activeJob = it
                        s.activeChapter = task.chapterIndex
                    }
                }
                if (job == null) {
                    if (s.foregroundWake.receiveCatching().isClosed) break
                    continue
                }
                job.start()
                job.join()
                synchronized(lock) {
                    s.activeJob = null
                    s.activeChapter = null
                    session?.wake?.trySend(Unit)
                }
            }
        }
    }

    private fun enqueueWindow(s: Session) {
        s.window.chapters.forEach { chapter ->
            // LAZY 任务领到后还可能没 start；取消中的任务则不能再为新窗口交付。
            if (chapter == s.activeChapter && s.activeJob?.let { !it.isCancelled && !it.isCompleted } == true) {
                return@forEach
            }
            enqueueForeground(s, chapter, cacheRefresh = false)
        }
    }

    private fun priorityInWindow(window: Window, chapter: Int): AiPriority = when (chapter - window.chapters.first) {
        0 -> AiPriority.P0_CURRENT
        1 -> AiPriority.P1_NEXT
        else -> AiPriority.P2_PRELOAD
    }

    // Called under lock. Each queued task reads the latest body on acquisition.
    private fun enqueueForeground(s: Session, chapter: Int, cacheRefresh: Boolean) {
        if (chapter in s.pendingForeground) return
        s.pendingForeground[chapter] = ForegroundTask(
            s.book, s.bookKey, s.sourceKey, chapter, priorityInWindow(s.window, chapter),
            force = false, buildGeneration = s.buildGeneration, sessionId = s.id, cacheRefresh = cacheRefresh,
        )
        s.foregroundWake.trySend(Unit)
    }

    private fun refreshPriority(task: ForegroundTask): AiPriority? = synchronized(lock) {
        val s = session ?: return@synchronized null
        if (s.id != task.sessionId || s.stopping || task.chapterIndex !in s.window.chapters ||
            !raBuild.canUseGeneration(task.bookKey, task.chapterIndex, task.buildGeneration)
        ) return@synchronized null
        priorityInWindow(s.window, task.chapterIndex)
    }

    private suspend fun processForeground(task: ForegroundTask) {
        var current = task
        while (raBuild.canUseGeneration(task.bookKey, task.chapterIndex, task.buildGeneration)) {
            if (current.cacheRefresh && refreshPriority(current) == null) return
            val beforeLoad = raBuild.getBuild(task.bookKey, task.sourceKey, task.chapterIndex)
            val text = textProvider.load(task.book, task.chapterIndex, allowDownload = !current.cacheRefresh)
            if (text == null) {
                if (!current.cacheRefresh) markNoContent(task.bookUrl, task.bookKey, task.sourceKey,
                    task.chapterIndex, beforeLoad, task.buildGeneration)
                return
            }
            val flight = ChapterJobRegistry.Key(task.bookKey, task.sourceKey, task.chapterIndex, text.contentHash,
                SpeechAnalysisPipelineV4.RESOLVER_VERSION, stage = "EXTRACT")
            val key = if (current.force) flight.copy(stage = "REANALYZE-${System.nanoTime()}") else flight
            jobs.withForeground(flight) { finishForeground(current, text, key) }
            currentCoroutineContext().ensureActive()
            if (text.matchesContent()) return
            // Only a changed body in the live window is eligible for automatic reacquisition.
            current = current.copy(priority = refreshPriority(current) ?: return, cacheRefresh = true)
        }
    }

    private suspend fun finishForeground(
        task: ForegroundTask,
        text: ChapterTextProvider.Text,
        key: ChapterJobRegistry.Key,
    ) {
        val input = RaBuildInput(task.buildGeneration, text.matchesContent)
        repeat(3) { attempt ->
            if (task.cacheRefresh && refreshPriority(task) == null) return
            if (!raBuild.canUseGeneration(task.bookKey, task.chapterIndex, input.generation) || !input.matchesContent()) return
            if (!task.force) {
                val build = raBuild.getBuild(task.bookKey, task.sourceKey, task.chapterIndex)
                val analysis = build?.analysisId?.let { raBuild.analysisById(it) }
                if (build?.contentHash == text.contentHash && analysis?.resolverVersion == SpeechAnalysisPipelineV4.RESOLVER_VERSION) {
                    if (build.state == RaChapterState.IDENTITY_READY && analysis.status == RaAnalysisStatus.RESOLVED) {
                        if (!task.cacheRefresh) emitReady(task, text, message = "已就绪", usedAi = true)
                        return
                    }
                    if (task.cacheRefresh && build.state == RaChapterState.FAILED && analysis.status == RaAnalysisStatus.FAILED) {
                        return // Cache events do not retry a failed analysis of identical text.
                    }
                    if (build.state == RaChapterState.EXTRACTED) {
                        val local = batch.convergeExisting(task.bookKey, task.sourceKey,
                            listOf(text.toBatchText(input)), task.priority)
                        if (task.chapterIndex in local.ready) {
                            emitReady(task, text, message = "已就绪", usedAi = true)
                            return
                        }
                    }
                }
                if (attempt == 0 && tryReuseFromOtherSource(task.bookKey, task.sourceKey, text, input)) {
                    emitReady(task, text, message = "换源复用", usedAi = false)
                    return
                }
            }
            when (val acquire = jobs.acquire(key)) {
                is ChapterJobRegistry.Acquire.Join -> {
                    // 重新核对当前指针，接管期间可能已经提交观察包或切换了正文版本。
                    acquire.result.await()
                }
                is ChapterJobRegistry.Acquire.Owner -> {
                    try {
                        val result = pipeline.run(
                            bookUrl = task.bookUrl,
                            bookName = task.book.name,
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
                                sourceKey = task.sourceKey,
                            ),
                            taskStartedAt = text.loadedAt,
                            input = input,
                        )
                        acquire.ticket.complete(when (result?.analysis?.status) {
                            null -> ChapterJobRegistry.Outcome.CANCELLED
                            RaAnalysisStatus.FAILED -> ChapterJobRegistry.Outcome.FAILED
                            else -> ChapterJobRegistry.Outcome.SUCCESS
                        })
                        if (result != null && result.analysis.status != RaAnalysisStatus.FAILED) {
                            emitReady(
                                task,
                                text,
                                message = result.analysis.status.storageValue,
                                usedAi = result.segments.any { it.source.storageValue == "ai" },
                            )
                        }
                    } catch (e: CancellationException) {
                        acquire.ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
                        throw e
                    } catch (e: Exception) {
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
        input: RaBuildInput,
    ): Boolean = runCatching {
        val chapterIndex = text.chapterIndex
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
        val published = raBuild.saveAnalysisBundle(
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
                observationJson = old.observationJson,
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
                updatedAt = text.loadedAt,
            ),
            input = input,
        )
        if (published != RaBuildPublication.PUBLISHED) return@runCatching false
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
        expected: RaChapterBuild?,
        buildGeneration: Long,
    ) {
        val chapter = bookChapterDao.getChapter(bookUrl, chapterIndex) ?: return
        val existing = expected
        val state = if (chapter.isVolume) RaChapterState.SKIPPED_EMPTY else RaChapterState.NO_CONTENT
        val now = System.currentTimeMillis()
        raBuild.transitionBuild(
            existing,
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
            input = RaBuildInput(buildGeneration),
        )
    }

    private fun emitReady(task: ForegroundTask, text: ChapterTextProvider.Text, message: String, usedAi: Boolean) {
        if (!text.matchesContent()) return
        synchronized(lock) {
            if (task.sessionId != null && session?.id != task.sessionId) return
            if (!raBuild.canUseGeneration(task.bookKey, task.chapterIndex, task.buildGeneration)) return
            if (task.cacheRefresh && refreshPriority(task) == null) return
            session?.wake?.trySend(Unit)
            _events.tryEmit(ReadyEvent(task.bookUrl, task.chapterIndex, usedAi, message))
        }
    }

    private fun label(task: ForegroundTask): String =
        ChapterLabels.of("", task.chapterIndex)

    // ---------------- 后台 ----------------

    private fun startContentWatcher(s: Session) {
        s.watcher = scope.launch {
            ChapterContentEvents.events.collect { saved ->
                if (saved.bookUrl != s.book.bookUrl) return@collect
                val build = raBuild.getBuild(s.bookKey, s.sourceKey, saved.chapterIndex)
                if (build?.state == RaChapterState.NO_CONTENT) {
                    raBuild.transitionBuild(build, build.copy(
                        state = RaChapterState.CACHED,
                    ), input = RaBuildInput(s.buildGeneration))
                } else if (build?.state == RaChapterState.IDENTITY_READY) {
                    s.changedCache[saved.chapterIndex] = Any()
                }
                synchronized(lock) {
                    val chapter = saved.chapterIndex
                    if (session === s && !s.stopping && chapter in s.window.chapters &&
                        raBuild.canUseGeneration(s.bookKey, chapter, s.buildGeneration)) enqueueForeground(s, chapter, cacheRefresh = true)
                }
                s.wake.trySend(Unit)
            }
        }
    }

    private fun startBackgroundWorker(s: Session) {
        s.background = scope.launch {
            val deferredRecovery = HashSet<Int>()
            var observedGeneration = -1L
            while (isActive) {
                val selection = synchronized(lock) {
                    if (session !== s || s.stopping) return@launch
                    if (s.paused || s.budgetLeft <= 0) {
                        setStatus(BackgroundState.RUNNING, s)
                        null
                    } else s.window to s.budgetLeft
                }
                if (selection == null) {
                    s.wake.receive()
                    continue
                }
                val (window, budgetLeft) = selection
                val cfg = runCatching { analysisConfig.load() }.getOrElse {
                    if (it is CancellationException) throw it
                    AnalysisConfigStore.Config()
                }
                val signalled = s.wake.tryReceive().isSuccess
                if (observedGeneration != window.generation || signalled) {
                    deferredRecovery.clear()
                    observedGeneration = window.generation
                }
                val states = computeStates(s)
                val recoveryOccupied = jobs.occupiedChapters(s.bookKey, s.sourceKey)
                val extracted = states.filterValues { it == RaChapterState.EXTRACTED }.keys
                    .filterNot { it in window.chapters || it in recoveryOccupied || it in deferredRecovery }
                    .take(minOf(budgetLeft, cfg.bgBatchMaxChapters))
                    .toSet()
                if (extracted.isNotEmpty()) {
                    val texts = extracted.mapNotNull { chapter ->
                        val beforeLoad = raBuild.getBuild(s.bookKey, s.sourceKey, chapter)
                        val text = textProvider.load(s.book, chapter, allowDownload = false)
                        if (text == null) markNoContent(s.book.bookUrl, s.bookKey, s.sourceKey, chapter, beforeLoad, s.buildGeneration)
                        text?.toBatchText(RaBuildInput(s.buildGeneration, text.matchesContent))
                    }
                    if (texts.isEmpty()) continue
                    if (!canStartBackground(s, window)) continue
                    val outcome = runBatchOnce(s, window, texts, recovery = true)
                    setStatus(BackgroundState.RUNNING, s, computeStates(s))
                    deferredRecovery.addAll(texts.map { it.chapterIndex }.filterNot { it in outcome.ready })
                    continue
                }
                val occupied = jobs.occupiedChapters(s.bookKey, s.sourceKey)
                val chapters = bookChapterDao.getChapterList(s.book.bookUrl)
                val tokenEstimates = chapters.associate { chapter ->
                    chapter.index to estimateChapterTokens(s.book, chapter)
                }
                val batchIndices = BatchSelector.nextBatch(
                    states = states,
                    anchor = window.anchor,
                    fgWindow = window.chapters,
                    budgetLeft = budgetLeft,
                    maxBatch = cfg.bgBatchMaxChapters,
                    chapterOutputTokens = tokenEstimates,
                    outputTokenCap = (cfg.maxOutputTokens * 0.8).toInt().coerceAtLeast(1),
                    occupied = occupied,
                )
                if (batchIndices == null) {
                    setStatus(BackgroundState.WAITING_CACHE, s, states)
                    s.wake.receive()
                    deferredRecovery.clear()
                    continue
                }
                val metadata = chapters.associateBy { it.index }
                val texts = ArrayList<BatchAnalysisRunner.ChapterText>()
                var reselect = false
                for (chapter in batchIndices) {
                    val beforeLoad = raBuild.getBuild(s.bookKey, s.sourceKey, chapter)
                    val change = s.changedCache[chapter]
                    val chapterInfo = metadata[chapter]
                    if (chapterInfo == null) {
                        reselect = true
                        continue
                    }
                    val modifiedAt = cachedModifiedAt(s.book, chapterInfo)
                    val text = textProvider.load(s.book, chapter, allowDownload = false)
                    if (s.changedCache[chapter] !== change || cachedModifiedAt(s.book, chapterInfo) != modifiedAt) {
                        reselect = true
                    } else if (text == null) {
                        markNoContent(s.book.bookUrl, s.bookKey, s.sourceKey, chapter, beforeLoad, s.buildGeneration)
                        reselect = true
                    } else if (reuseCachedIdentity(s, text, modifiedAt)) {
                        if (change != null) s.changedCache.remove(chapter, change)
                        reselect = true
                    } else {
                        texts.add(text.toBatchText(RaBuildInput(s.buildGeneration, text.matchesContent)))
                    }
                }
                // 已就绪章和正文缺口都断开批次，按最新状态重新选连续章。
                if (reselect) continue
                if (!canStartBackground(s, window)) continue
                val started = System.currentTimeMillis()
                val outcome = runBatchDegraded(s, window, texts)
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

    private fun canStartBackground(s: Session, window: Window): Boolean = synchronized(lock) {
        session === s && !s.paused && !s.stopping && s.budgetLeft > 0 && s.window == window
    }

    private suspend fun runBatchDegraded(
        s: Session,
        window: Window,
        texts: List<BatchAnalysisRunner.ChapterText>,
    ): BatchAnalysisRunner.Outcome {
        val first = runBatchOnce(s, window, texts)
        val ready = first.ready.toMutableList()
        val extracted = first.extracted.toMutableList()
        val failed = first.failed.toMutableSet()
        if (failed.size > 1) {
            val failedTexts = texts.filter { it.chapterIndex in failed }
            for (half in failedTexts.chunked((failedTexts.size + 1) / 2)) {
                if (!canStartBackground(s, window)) break
                val outcome = runBatchOnce(s, window, half)
                ready.addAll(outcome.ready)
                extracted.addAll(outcome.extracted)
                half.forEach { failed.remove(it.chapterIndex) }
                failed.addAll(outcome.failed)
            }
        }
        val terminalFailures = ArrayList<Int>()
        for (text in texts.filter { it.chapterIndex in failed }) {
            if (!canStartBackground(s, window)) break
            when (runSingle(s, window, text)) {
                true -> ready.add(text.chapterIndex)
                false -> terminalFailures.add(text.chapterIndex)
                null -> Unit
            }
        }
        return BatchAnalysisRunner.Outcome(ready.distinct(), terminalFailures, extracted.distinct())
    }

    private suspend fun runBatchOnce(
        s: Session,
        window: Window,
        texts: List<BatchAnalysisRunner.ChapterText>,
        recovery: Boolean = false,
    ): BatchAnalysisRunner.Outcome {
        val owned = java.util.concurrent.ConcurrentHashMap<Int, ChapterJobRegistry.Ticket>()
        val earlyReady = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val earlyExtracted = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val runnable = ArrayList<BatchAnalysisRunner.ChapterText>()
        val control = AiRequestControl(incremental = !recovery)
        val controlKeys = ArrayList<ChapterJobRegistry.Key>()
        val onChapterReady: suspend (Int, Boolean) -> Unit = { chapter, identityReady ->
            if (identityReady) {
                earlyReady.add(chapter)
                charge(s)
            } else earlyExtracted.add(chapter)
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
            texts.forEach { text ->
                val key = ChapterJobRegistry.Key(
                    bookKey = s.bookKey,
                    sourceKey = s.sourceKey,
                    chapterIndex = text.chapterIndex,
                    contentHash = text.contentHash,
                    resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
                    stage = "EXTRACT",
                )
                val ticket = jobs.acquireBackground(key) ?: return@forEach
                controlKeys.add(key)
                owned[text.chapterIndex] = ticket
                ticket.bindControl(control)
                runnable.add(text)
            }
            if (runnable.isEmpty() || !canStartBackground(s, window)) return BatchAnalysisRunner.Outcome()
            // 前台只取消本批子任务，背景循环可继续按最新窗口领章。
            outcome = coroutineScope {
                async {
                    control.bind(currentCoroutineContext()[Job])
                    if (recovery) {
                        batch.convergeExisting(s.bookKey, s.sourceKey, runnable,
                            onChapterReady = onChapterReady, control = control)
                    } else {
                        batch.run(s.bookKey, s.sourceKey, runnable, "bg_${System.currentTimeMillis()}",
                            onChapterReady = onChapterReady, control = control)
                    }
                }.await()
            }
        } catch (e: CancellationException) {
            owned.values.forEach { ticket ->
                if (!ticket.result.isCompleted) ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
            }
            if (!currentCoroutineContext().isActive) throw e
            AppLog.putAnalysis("【V4·后台】批次被前台抢占，已提交章节保留")
            return BatchAnalysisRunner.Outcome(
                ready = earlyReady.toList().distinct(),
                extracted = earlyExtracted.toList().distinct().filterNot { it in earlyReady },
            )
        } catch (e: Exception) {
            AppLog.putAnalysis("【V4·后台】批次异常：${e.localizedMessage}", e)
            outcome = BatchAnalysisRunner.Outcome(failed = runnable.map { it.chapterIndex })
        } finally {
            controlKeys.forEach { jobs.releaseControl(it, control) }
            owned.forEach { (chapter, ticket) ->
                if (!ticket.result.isCompleted) {
                    val state = when {
                        chapter in outcome.ready || chapter in earlyReady ->
                            ChapterJobRegistry.Outcome.SUCCESS
                        chapter in outcome.extracted || chapter in earlyExtracted ->
                            ChapterJobRegistry.Outcome.EXTRACTED
                        chapter in outcome.failed -> ChapterJobRegistry.Outcome.FAILED
                        else -> ChapterJobRegistry.Outcome.CANCELLED
                    }
                    ticket.complete(state)
                }
            }
        }
        return BatchAnalysisRunner.Outcome(
            ready = (earlyReady.toList() + outcome.ready).distinct(),
            failed = outcome.failed.filterNot {
                it in earlyReady || it in earlyExtracted || it in outcome.ready || it in outcome.extracted
            },
            extracted = (earlyExtracted.toList() + outcome.extracted).distinct()
                .filterNot { it in earlyReady || it in outcome.ready || it in outcome.failed },
        )
    }

    private fun charge(s: Session) = synchronized(lock) {
        check(s.budgetLeft > 0) { "后台结果超出已领取额度" }
        s.budgetLeft--
    }

    private suspend fun markFailed(s: Session, text: BatchAnalysisRunner.ChapterText): Boolean {
        val existing = raBuild.getBuild(s.bookKey, s.sourceKey, text.chapterIndex)
        if (existing != null && (existing.updatedAt > text.loadedAt ||
                existing.state == RaChapterState.EXTRACTED ||
                (existing.state == RaChapterState.IDENTITY_READY && existing.contentHash == text.contentHash))
        ) return false
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            raBuild.transitionBuild(existing, RaChapterBuild(
                bookKey = s.bookKey,
                sourceKey = s.sourceKey,
                chapterIndex = text.chapterIndex,
                contentHash = text.contentHash,
                state = RaChapterState.FAILED,
                failReason = "后台分析失败",
                attempts = (existing?.attempts ?: 0) + 1,
                createdAt = existing?.createdAt ?: text.loadedAt,
                updatedAt = text.loadedAt,
            ), input = text.input).also { if (it) charge(s) }
        }
    }

    /** true=成功，false=失败，null=已有前台任务（不重复计数）。 */
    private suspend fun runSingle(
        s: Session,
        window: Window,
        text: BatchAnalysisRunner.ChapterText,
    ): Boolean? {
        val input = requireNotNull(text.input)
        if (!raBuild.canUseGeneration(s.bookKey, text.chapterIndex, input.generation) || !input.matchesContent()) return null
        val key = ChapterJobRegistry.Key(
            bookKey = s.bookKey,
            sourceKey = s.sourceKey,
            chapterIndex = text.chapterIndex,
            contentHash = text.contentHash,
            resolverVersion = SpeechAnalysisPipelineV4.RESOLVER_VERSION,
            stage = "EXTRACT",
        )
        val ticket = jobs.acquireBackground(key) ?: return null
        val control = AiRequestControl(incremental = false)
        var published: RaAnalysisStatus? = null
        var outcome = ChapterJobRegistry.Outcome.CANCELLED
        return try {
            ticket.bindControl(control)
            if (!canStartBackground(s, window)) return null
            val result = coroutineScope {
                async {
                    control.bind(currentCoroutineContext()[Job])
                    pipeline.run(
                        bookUrl = s.book.bookUrl,
                        bookName = s.book.name,
                        bookKey = s.bookKey,
                        sourceKey = s.sourceKey,
                        chapterIndex = text.chapterIndex,
                        paragraphs = text.paragraphs,
                        prevChapterText = text.prevText,
                        nextChapterText = text.nextText,
                        aiContext = AiSchedulerContext(AiTaskKind.STAGE1, AiPriority.P3_BACKGROUND,
                            s.bookKey, listOf(text.chapterIndex), preemptible = true, control = control, sourceKey = s.sourceKey),
                        taskStartedAt = text.loadedAt,
                        skipLegacyAudio = true,
                        onPublished = { status -> published = status; charge(s) },
                        input = input,
                    )
                }.await()
            }
            when (result?.analysis?.status) {
                RaAnalysisStatus.RESOLVED -> { outcome = ChapterJobRegistry.Outcome.SUCCESS; true }
                RaAnalysisStatus.FAILED -> { outcome = ChapterJobRegistry.Outcome.FAILED; false }
                else -> null // 旧版本仅留历史，或已由前台发布。
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            null
        } catch (e: Exception) {
            AppLog.putAnalysis("【V4·后台】单章重试失败：${e.localizedMessage}", e)
            if (published == RaAnalysisStatus.FAILED || (published == null && markFailed(s, text))) {
                outcome = ChapterJobRegistry.Outcome.FAILED
                false
            } else null
        } finally {
            if (published == RaAnalysisStatus.RESOLVED) outcome = ChapterJobRegistry.Outcome.SUCCESS
            else if (published == RaAnalysisStatus.FAILED) outcome = ChapterJobRegistry.Outcome.FAILED
            jobs.releaseControl(key, control)
            ticket.complete(outcome)
        }
    }

    // ---------------- 状态 ----------------

    private fun estimateChapterTokens(book: Book, chapter: BookChapter): Int {
        val bytes = runCatching {
            BookHelp.getChapterContentFile(book, chapter).length()
        }.getOrDefault(0L)
        val chars = if (bytes > 0L) (bytes / 3L).coerceAtLeast(1L) else 2_000L
        val dialogues = (chars / 40L).toInt().coerceAtLeast(1)
        return (dialogues * 60 + 800).coerceIn(400, 30_000)
    }

    private fun cachedModifiedAt(book: Book, chapter: BookChapter): Long =
        runCatching { BookHelp.getChapterContentFile(book, chapter).lastModified() }.getOrDefault(0L)

    /** 同一份已解析正文不提交给模型，也不占用本次建库额度。 */
    private suspend fun reuseCachedIdentity(s: Session, text: ChapterTextProvider.Text, modifiedAt: Long): Boolean {
        val current = raBuild.getBuild(s.bookKey, s.sourceKey, text.chapterIndex) ?: return false
        if (current.contentHash != text.contentHash ||
            (current.state != RaChapterState.IDENTITY_READY && current.state != RaChapterState.STALE)
        ) return false
        val analysis = current.analysisId?.let { raBuild.analysisById(it) } ?: return false
        if (analysis.status != RaAnalysisStatus.RESOLVED || analysis.contentHash != text.contentHash ||
            analysis.resolverVersion != SpeechAnalysisPipelineV4.RESOLVER_VERSION
        ) return false
        val ready = if (current.state == RaChapterState.IDENTITY_READY) current else {
            // 早期会话可能只根据缓存时间写过 STALE；正文未变时恢复同一指针。
            current.copy(state = RaChapterState.IDENTITY_READY, failReason = "").also {
                if (!raBuild.transitionBuild(current, it, input = RaBuildInput(s.buildGeneration, text.matchesContent))) return true // 指针已变，交回选批重读。
            }
        }
        s.verifiedCache[text.chapterIndex] = VerifiedCache(ready, modifiedAt)
        return true
    }

    private suspend fun computeStates(s: Session): Map<Int, RaChapterState> {
        val chapters = bookChapterDao.getChapterList(s.book.bookUrl)
        val builds = raBuild.builds(s.bookKey, s.sourceKey).associateBy { it.chapterIndex }
        return chapters.associate { chapter ->
            val build = builds[chapter.index]
            if (!raBuild.canUseGeneration(s.bookKey, chapter.index, s.buildGeneration)) {
                // 本轮会话不能重新领取已删除的章。用户重析、重开可用新的代次。
                val ready = build?.state == RaChapterState.IDENTITY_READY
                return@associate chapter.index to if (ready) RaChapterState.IDENTITY_READY else RaChapterState.NO_CONTENT
            }
            val hasContent = s.book.isLocal || BookHelp.hasContent(s.book, chapter)
            val fileUpdated = cachedModifiedAt(s.book, chapter)
            val state = when {
                build?.state == RaChapterState.IDENTITY_READY && hasContent &&
                    (s.changedCache.containsKey(chapter.index) || (fileUpdated > build.updatedAt &&
                        s.verifiedCache[chapter.index] != VerifiedCache(build, fileUpdated))) -> RaChapterState.STALE
                build == null -> if (hasContent) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                build.state == RaChapterState.IDENTITY_READY || build.state == RaChapterState.SKIPPED_EMPTY ||
                    build.state == RaChapterState.EXTRACTED || build.state == RaChapterState.STALE -> build.state
                build.state == RaChapterState.FAILED && build.attempts >= 3 -> build.state
                build.state == RaChapterState.FAILED ->
                    if (hasContent) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                build.state == RaChapterState.NO_CONTENT ->
                    if (hasContent && fileUpdated > build.updatedAt) RaChapterState.CACHED else RaChapterState.NO_CONTENT
                else -> RaChapterState.CACHED
            }
            chapter.index to state
        }
    }

    private fun setStatus(
        state: BackgroundState,
        s: Session?,
        states: Map<Int, RaChapterState>? = null,
    ) = synchronized(lock) {
        if (s != null && session !== s) return@synchronized
        val previous = _status.value
        val sameBook = s?.book?.bookUrl == previous.bookUrl
        val built = states?.values?.count { it == RaChapterState.IDENTITY_READY }
            ?: if (sameBook) previous.builtChapters else 0
        val pending = states?.values?.count {
            it == RaChapterState.CACHED || it == RaChapterState.STALE
        } ?: if (sameBook) previous.cachedPending else 0
        val actualState = when {
            state == BackgroundState.STOPPED || s?.stopping == true -> BackgroundState.STOPPED
            s != null && s.budgetLeft <= 0 -> BackgroundState.BUDGET_REACHED
            s?.paused == true -> BackgroundState.PAUSED
            else -> state
        }
        val next = BuildStatus(
            state = actualState,
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

    private fun ChapterTextProvider.Text.toBatchText(input: RaBuildInput): BatchAnalysisRunner.ChapterText =
        BatchAnalysisRunner.ChapterText(
            chapterIndex = chapterIndex,
            chapterLabel = ChapterLabels.of(title, chapterIndex),
            paragraphs = paragraphs,
            prevText = prevText,
            nextText = nextText,
            contentHash = contentHash,
            loadedAt = loadedAt,
            input = input,
            cachedBody = cachedBody,
        )
}
