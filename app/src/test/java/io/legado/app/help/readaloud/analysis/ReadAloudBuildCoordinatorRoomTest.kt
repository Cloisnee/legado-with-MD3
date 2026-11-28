package io.legado.app.help.readaloud.analysis

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
import io.legado.app.domain.gateway.RaBookIdentityGateway
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.settings.OtherSettings
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.readaloud.analysis.v4.BatchAnalysisRunner
import io.legado.app.help.readaloud.analysis.v4.IdentityResolver
import io.legado.app.help.readaloud.analysis.v4.ObservationAssembly
import io.legado.app.help.readaloud.analysis.v4.VoiceCastingService
import io.legado.app.support.InMemoryAppDatabaseFixture
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReadAloudBuildCoordinatorRoomTest {
    @Test fun `same cached content without saved event skips both stages`() = cachedRewrite(false, false)
    @Test fun `same cached content with saved event skips both stages`() = cachedRewrite(true, false)
    @Test fun `changed cached content without saved event is rebuilt`() = cachedRewrite(false, true)
    @Test fun `saved event checks changed content even with unchanged file time`() = cachedRewrite(true, true)

    private fun cachedRewrite(event: Boolean, change: Boolean) = runBlocking {
        fixture("cached-$event-$change") { f ->
            val text = f.texts.load(f.book, 1, allowDownload = false)!!
            // Give the cold-cache path an unambiguous file timestamp change.
            val seed = text.asBatch().copy(loadedAt = text.loadedAt - 2_000)
            assertEquals(listOf(1), f.runner.run(f.bookKey, f.sourceKey, listOf(seed), "seed").ready)
            val original = f.builds.getBuild(f.bookKey, f.sourceKey, 1)!!
            val line = f.builds.segments(original.analysisId!!).single { it.seq == 1 }
            val originalActor = requireNotNull(line.characterId)
            f.people.setDefaultVoice(originalActor, "manual-voice", "manual-entry")
            val profiles = f.people.voiceProfiles(f.bookKey)
            f.builds.saveOverride(f.bookKey, 1, line.paragraphIndex, MD5Utils.md5Encode16(line.text), null, true)
            val overrides = f.builds.overrides(f.bookKey, 1)
            f.write(1, if (change) "张三说：“明天见。”" else TEXTS[1])
            if (event) {
                assertTrue(f.file(1).setLastModified(original.updatedAt))
                ChapterContentEvents.saved(f.book.bookUrl, 1)
            } else {
                assertTrue(f.file(1).lastModified() > original.updatedAt)
            }
            f.configure(budget = 1, stage1 = true)
            f.calls.clear()
            val coordinator = f.coordinator()
            try {
                coordinator.startSession(f.book, 0)
                val state = withTimeout(25_000) {
                    coordinator.status.first { it.state == ReadAloudBuildCoordinator.BackgroundState.BUDGET_REACHED }
                }
                assertEquals(0, state.budgetLeft)
                val reloaded = f.builds.getBuild(f.bookKey, f.sourceKey, 1)!!
                assertEquals(RaChapterState.IDENTITY_READY, reloaded.state)
                val expected = if (change) 2 else 3
                assertEquals(listOf("stage1" to expected, "stage2" to expected), f.calls.filter { it.second > 1 })
                assertEquals(overrides, f.builds.overrides(f.bookKey, 1))
                assertEquals(profiles, f.people.voiceProfiles(f.bookKey))
                assertEquals(listOf(originalActor), f.people.charactersRaw(f.bookKey).map { it.id })
                if (change) {
                    assertNotEquals(original.analysisId, reloaded.analysisId)
                    assertEquals(originalActor, f.builds.segments(reloaded.analysisId!!).single { it.seq == 1 }.characterId)
                    assertNull(f.builds.getBuild(f.bookKey, f.sourceKey, 2))
                } else {
                    assertEquals(original, reloaded)
                    assertEquals(RaChapterState.IDENTITY_READY, f.builds.getBuild(f.bookKey, f.sourceKey, 2)?.state)
                    val retained = f.builds.segments(original.analysisId).single { it.id == line.id }
                    assertEquals(SpeechRoleType.Narrator, retained.roleType)
                    assertNull(retained.characterId)
                    assertEquals(originalActor, f.builds.localSpeakers(original.analysisId).single().characterId)
                }
                println("R3-5 event=$event changed=$change requests=${f.calls.filter { it.second > 1 }} budget=${state.budgetLeft}")
            } finally { withContext(NonCancellable) { coordinator.stopSession().joinAll() } }
        }
    }

    @Test fun `changed body during stage2 leaves the last budget for the new hash`() = runBlocking {
        val oldReceived = CompletableDeferred<Unit>()
        val newReceived = CompletableDeferred<Unit>()
        val replyOld = CountDownLatch(1)
        val replyNew = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        fixture("content-in-flight", onStage2 = { exchange, body ->
            val name = if ("一切顺利" in body) "李四" else {
                check("来了" in body)
                "张三"
            }
            requests.add(name)
            if (name == "张三") {
                oldReceived.complete(Unit)
                check(replyOld.await(75, TimeUnit.SECONDS))
            } else {
                newReceived.complete(Unit)
                check(replyNew.await(75, TimeUnit.SECONDS))
            }
            exchange.reply(batchResponse(batchRow(2, name)))
        }) { f ->
            val coordinator = f.coordinator()
            try {
                val old = requireNotNull(f.texts.load(f.book, 1, false))
                val mtime = f.file(1).lastModified()
                coordinator.startSession(f.book, 0)
                withTimeout(25_000) { oldReceived.await() }
                assertNull(f.builds.getBuild(f.bookKey, f.sourceKey, 1))
                f.save(1, "李四说：“一切顺利。”")
                assertTrue(f.file(1).setLastModified(mtime))
                val fresh = requireNotNull(f.texts.load(f.book, 1, false))
                assertNotEquals(old.contentHash, fresh.contentHash)
                replyOld.countDown()
                withTimeout(25_000) { newReceived.await() }
                assertEquals(1, coordinator.status.value.budgetLeft)
                assertNull(f.builds.getBuild(f.bookKey, f.sourceKey, 1))
                assertTrue(f.people.charactersRaw(f.bookKey).isEmpty())
                replyNew.countDown()
                val settled = withTimeout(25_000) { coordinator.status.first { it.state == ReadAloudBuildCoordinator.BackgroundState.BUDGET_REACHED } }
                val current = requireNotNull(f.builds.getBuild(f.bookKey, f.sourceKey, 1))
                val resolved = f.builds.getAnalysis(f.bookKey, f.sourceKey, 1, fresh.contentHash, SpeechAnalysisPipelineV4.RESOLVER_VERSION)
                val actor = f.people.charactersRaw(f.bookKey).single()
                assertEquals(fresh.contentHash, current.contentHash)
                assertEquals(current.analysisId, requireNotNull(resolved).id)
                assertEquals(actor.id, f.builds.segments(resolved.id).single { it.seq == 1 }.characterId)
                assertEquals(listOf("李四"), f.people.aliasesOfBook(f.bookKey).map { it.surface })
                assertEquals(actor.id, f.people.voiceProfiles(f.bookKey).single().characterId)
                assertEquals("李四", actor.displayName)
                assertEquals(listOf("张三", "李四"), requests.toList())
                assertEquals(0, settled.budgetLeft)
                assertNull(f.builds.getBuild(f.bookKey, f.sourceKey, 2))
                println("R4-4 old=${old.contentHash} new=${fresh.contentHash} current=${current.contentHash}/${current.state} actor=李四 budget=1→0 requests=$requests")
            } finally { replyOld.countDown(); replyNew.countDown(); withContext(NonCancellable) { coordinator.stopSession().joinAll() } }
        }
    }

    @Test fun `jump outside adopted batch gets provider before the old stream ends`() = runBlocking {
        val tail = CountDownLatch(1)
        val jumpRequest = CompletableDeferred<Unit>()
        val continueBuild = CompletableDeferred<Unit>()
        fixture("jump-after-takeover", chapterCount = 9, onStage2 = { exchange, body ->
            when {
                "=== ch=2 ===" in body && "=== ch=3 ===" in body -> {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        val prefix = batchResponse(batchRow(2, "张三")).substringBefore("{\"type\":\"end\"}")
                        val delta = JSONObject().put("delta", JSONObject().put("content", prefix))
                        output.write("data: ${JSONObject().put("choices", JSONArray().put(delta))}\n\n".toByteArray())
                        output.flush()
                        check(tail.await(80, TimeUnit.SECONDS))
                    }
                }
                "【本章正文】ch=9" in body -> {
                    assertEquals(1L, tail.count)
                    jumpRequest.complete(Unit)
                    exchange.reply("""{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"李四","roletype":"核心"}]}""")
                }
                "=== ch=3 ===" in body -> {
                    continueBuild.complete(Unit)
                    exchange.reply(batchResponse(batchRow(3, "张三")))
                }
                else -> error("unexpected chapter in stage2 fixture: $body")
            }
        }) { f -> coroutineScope {
            f.write(3, "庭院空荡荡。")
            f.write(8, "李四说：“我来了。”")
            f.configure(budget = 2, maxBatch = 2)
            val coordinator = f.coordinator()
            val secondReady = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 1 } }
            val fourthReady = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 3 } }
            val ninthReady = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 8 } }
            try {
                coordinator.startSession(f.book, 0)
                val extracted = withTimeout(25_000) {
                    var state = f.builds.getBuild(f.bookKey, f.sourceKey, 1)
                    while (state?.state != RaChapterState.EXTRACTED) {
                        delay(20)
                        state = f.builds.getBuild(f.bookKey, f.sourceKey, 1)
                    }
                    state
                }
                coordinator.onChapterChanged(f.book.bookUrl, 1)
                withTimeout(25_000) { secondReady.await() }
                assertEquals(extracted.analysisId, f.builds.getBuild(f.bookKey, f.sourceKey, 1)?.analysisId)
                assertEquals(2, coordinator.status.value.budgetLeft)
                coordinator.onChapterChanged(f.book.bookUrl, 3)
                withTimeout(25_000) { fourthReady.await() }
                assertFalse(continueBuild.isCompleted)
                assertNull(f.builds.getBuild(f.bookKey, f.sourceKey, 2))
                assertEquals(listOf("stage2" to 2), f.calls.toList())
                coordinator.onChapterChanged(f.book.bookUrl, 8)
                withTimeout(25_000) { jumpRequest.await(); ninthReady.await(); continueBuild.await() }
                val settled = withTimeout(25_000) { coordinator.status.first { it.state == ReadAloudBuildCoordinator.BackgroundState.WAITING_CACHE } }
                assertEquals(1L, tail.count)
                assertEquals(RaChapterState.IDENTITY_READY, f.builds.getBuild(f.bookKey, f.sourceKey, 8)?.state)
                assertEquals(RaChapterState.IDENTITY_READY, f.builds.getBuild(f.bookKey, f.sourceKey, 2)?.state)
                assertEquals(extracted.analysisId, f.builds.getBuild(f.bookKey, f.sourceKey, 1)?.analysisId)
                assertEquals(1, settled.budgetLeft)
                println("R4-2 held tail: chapter 9 request+READY, previous pointer retained, chapter 3 rebuilt, budget=2→1")
            } finally {
                tail.countDown()
                withContext(NonCancellable) { coordinator.stopSession().joinAll() }
                listOf(secondReady, fourthReady, ninthReady).forEach { it.cancelAndJoin() }
            }
        } }
    }

    @Test fun `return during cancellation queues one replacement that emits READY`() = runBlocking {
        fixture("return-cancel") { f -> coroutineScope {
            f.configure(budget = 0)
            val first = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val gateway = object : RaBuildGateway by f.builds {
                override suspend fun getAnalysis(
                    bookKey: String, sourceKey: String, chapterIndex: Int, contentHash: String, resolverVersion: String,
                ): RaAnalysis? {
                    if (chapterIndex == 0 && calls.incrementAndGet() == 1) {
                        first.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { cancelling.complete(Unit); release.await() }
                        }
                    }
                    return f.builds.getAnalysis(bookKey, sourceKey, chapterIndex, contentHash, resolverVersion)
                }
            }
            val coordinator = f.coordinator(gateway)
            val ready = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 0 } }
            try {
                coordinator.startSession(f.book, 0)
                withTimeout(10_000) { first.await() }
                coordinator.onChapterChanged(f.book.bookUrl, 1)
                withTimeout(10_000) { cancelling.await() }
                coordinator.onChapterChanged(f.book.bookUrl, 0)
                release.complete(Unit)
                assertEquals(0, withTimeout(10_000) { ready.await() }.chapterIndex)
                assertEquals(2, calls.get())
                assertEquals(RaChapterState.IDENTITY_READY, f.builds.getBuild(f.bookKey, f.sourceKey, 0)?.state)
                assertTrue(f.calls.isEmpty())
                println("R3-4 1→2→1: two entries including cancelled original, current chapter emitted READY")
            } finally { release.complete(Unit); ready.cancelAndJoin(); withContext(NonCancellable) { coordinator.stopSession().joinAll() } }
        } }
    }

    @Test fun `current cache refresh resolves only the new body with no background budget`() = runBlocking {
        val sent = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        fixture("current-rewrite", onStage2 = { exchange, body ->
            val who = if ("新句" in body) "李四" else "张三"
            requests.add(who)
            if (who == "张三") { sent.complete(Unit); check(release.await(60, TimeUnit.SECONDS)) }
            exchange.reply("""{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"$who","roletype":"核心"}]}""")
        }) { f -> coroutineScope {
            f.configure(budget = 0)
            f.preload = 1
            val oldBody = "张三说：“旧句。”"
            f.write(0, oldBody)
            f.write(1, "走廊里没有声音。")
            val oldText = requireNotNull(f.texts.load(f.book, 0, false))
            val coordinator = f.coordinator()
            val notices = CopyOnWriteArrayList<Int>()
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.events.collect { notices.add(it.chapterIndex) } }
            val preloadReady = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 1 } }
            try {
                coordinator.startSession(f.book, 0)
                withTimeout(20_000) { sent.await() }
                f.save(0, oldBody)
                f.save(0, "李四说：“新句。”")
                f.save(0, "李四说：“新句。”")
                val fresh = requireNotNull(f.texts.load(f.book, 0, false))
                release.countDown()
                withTimeout(20_000) { preloadReady.await() }
                val found = requireNotNull(f.builds.getAnalysis(f.bookKey, f.sourceKey, 0, fresh.contentHash,
                    SpeechAnalysisPipelineV4.RESOLVER_VERSION))
                val id = requireNotNull(f.builds.segments(found.id).single { it.seq == 1 }.characterId)
                assertEquals("李四", f.people.character(id)?.displayName)
                assertNull(f.builds.getAnalysis(f.bookKey, f.sourceKey, 0, oldText.contentHash,
                    SpeechAnalysisPipelineV4.RESOLVER_VERSION))
                assertEquals(listOf("张三", "李四"), requests.toList())
                assertEquals(1, notices.count { it == 0 })
                assertEquals(0, coordinator.status.value.budgetLeft)
            } finally { release.countDown(); watcher.cancelAndJoin(); preloadReady.cancelAndJoin(); withContext(NonCancellable) { coordinator.stopSession().joinAll() } }
        } }
    }

    @Test fun `a refreshed current chapter runs before an unstarted preload`() = runBlocking {
        val secondStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requested = CopyOnWriteArrayList<String>()
        fixture("refresh-priority", onStage2 = { exchange, body ->
            val name = when {
                "新版" in body -> "李四"
                "第一" in body -> "张三"
                "第二" in body -> "王五"
                "第三" in body -> "赵六"
                else -> error("Unexpected text: $body")
            }
            requested.add(name)
            if (name == "王五") {
                secondStarted.complete(Unit)
                check(release.await(75, TimeUnit.SECONDS))
            }
            exchange.reply("""{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"$name","roletype":"核心"}]}""")
        }) { f -> coroutineScope {
            f.configure(budget = 0)
            f.preload = 2
            val third = "赵六说：“第三章。”"
            f.write(0, "张三说：“第一章。”")
            f.write(1, "王五说：“第二章。”")
            f.write(2, third)
            val watching = AtomicBoolean()
            val fence = CompletableDeferred<Unit>()
            val observed = object : RaBuildGateway by f.builds {
                override suspend fun getBuild(bookKey: String, sourceKey: String, chapterIndex: Int) =
                    f.builds.getBuild(bookKey, sourceKey, chapterIndex).also {
                        if (watching.get() && chapterIndex == 2) fence.complete(Unit)
                    }
            }
            val coordinator = f.coordinator(coordinatorBuilds = observed)
            try {
                coordinator.startSession(f.book, 0)
                withTimeout(20_000) { secondStarted.await() }
                watching.set(true)
                f.save(0, "李四说：“新版。”")
                f.save(0, "李四说：“新版。”")
                f.save(2, third)
                // While chapter 2 holds the worker, only the cache watcher can visit chapter 3.
                withTimeout(15_000) { fence.await() }
                val last = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 2 } }
                release.countDown()
                withTimeout(35_000) { last.await() }
                assertEquals(listOf("张三", "王五", "李四", "赵六"), requested.toList())
                val current = f.builds.segmentsOfChapter(f.bookKey, f.sourceKey, 0).single { it.seq == 1 }
                assertEquals("李四", f.people.character(requireNotNull(current.characterId))?.displayName)
            } finally {
                release.countDown()
                withContext(NonCancellable) { coordinator.stopSession().joinAll() }
            }
        } }
    }

    @Test fun `stored sibling observations are excluded when their cached body changes`() = runBlocking {
        fixture("stale-batch-peer", onStage2 = { exchange, _ ->
            exchange.reply(batchResponse(batchRow(2, "张三"), batchRow(3, "张三")))
        }) { f ->
            val inputs = (1..2).map { index -> requireNotNull(f.texts.load(f.book, index, false)).asBatch() }
            val interrupted = runCatching {
                f.runner.run(f.bookKey, f.sourceKey, inputs, "cache-peers", onChapterReady = { chapter, ready ->
                    if (chapter == 2 && !ready) error("observations saved")
                })
            }
            assertEquals("observations saved", interrupted.exceptionOrNull()?.message)
            val analysis = requireNotNull(f.builds.getBuild(f.bookKey, f.sourceKey, 1)?.analysisId?.let {
                f.builds.analysisById(it)
            })
            val response = setOf(requireNotNull(analysis.batchId))
            suspend fun peers() = f.builds.currentBatchAnalyses(f.bookKey, f.sourceKey, response, setOf(1), f.builds.generation())
            assertEquals(listOf(2), peers().map { it.chapterIndex })
            val previousTime = f.file(2).lastModified()
            f.save(2, "李四说：“换人了。”")
            assertTrue(f.file(2).setLastModified(previousTime))
            assertEquals(RaChapterState.EXTRACTED, f.builds.getBuild(f.bookKey, f.sourceKey, 2)?.state)
            assertTrue(peers().isEmpty())
        }
    }

    @Test fun `queued manual analysis cannot allocate a replacement book`() = queuedAfterDelete(reanalyze = true)
    @Test fun `queued session cannot allocate a replacement book`() = queuedAfterDelete(reanalyze = false)

    private fun queuedAfterDelete(reanalyze: Boolean) = runBlocking {
        fixture("key-lookup-$reanalyze") { f -> coroutineScope {
            f.configure(budget = 0)
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val result = CompletableDeferred<String?>()
            val identities = object : RaBookIdentityGateway by f.identity {
                override suspend fun keyOf(bookUrl: String, sourceKey: String, generation: Long): String? {
                    reached.complete(Unit)
                    release.await()
                    return f.identity.keyOf(bookUrl, sourceKey, generation).also { result.complete(it) }
                }
            }
            val coordinator = f.coordinator(identities = identities)
            try {
                if (reanalyze) coordinator.reanalyze(f.book.bookUrl, 0) else coordinator.startSession(f.book, 0)
                withTimeout(20_000) { reached.await() }
                f.builds.deleteBook(f.bookKey)
                release.complete(Unit)
                assertNull(withTimeout(20_000) { result.await() })
                assertNull(f.identity.bookKeyOf(f.book.bookUrl))
                assertTrue(f.builds.builds(f.bookKey, f.sourceKey).isEmpty())
                assertTrue(f.calls.isEmpty())
                val ready = async(start = CoroutineStart.UNDISPATCHED) { coordinator.events.first { it.chapterIndex == 0 } }
                coordinator.startSession(f.book, 0)
                withTimeout(20_000) { ready.await() }
                assertNotEquals(f.bookKey, f.identity.bookKeyOf(f.book.bookUrl))
            } finally { release.complete(Unit); withContext(NonCancellable) { coordinator.stopSession().joinAll() } }
        } }
    }

    private class Fixture(
        app: Application, val book: Book, private val chapters: List<BookChapter>,
        private val db: AppDatabase, read: ReadSettingsGateway, private val port: Int,
        val calls: CopyOnWriteArrayList<Pair<String, Int>>,
    ) {
        val sourceKey = sourceKeyOf(book)
        lateinit var bookKey: String
        val builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        val identity = RaBookIdentityRepository(db, db.raBuildDao, db.bookDao, builds)
        private val words = WordLibraryRepository(app)
        val people = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, words)
        private val resolver = IdentityResolver(people, words)
        private val casting = VoiceCastingService(ReadAloudVoicePoolRepository(app), people)
        private val models = AiModelRepository(app)
        private val config = AnalysisConfigStore(app)
        private val ai = AiSpeechClient(models, config, AiRequestScheduler())
        private val direction = RaDirectionRepository(db.raDirectionDao)
        private val catalog = ReadAloudCatalogRepository(db.bookDao, db.bookChapterDao, db.raBuildDao, builds)
        private fun pipeline(gateway: RaBuildGateway) = SpeechAnalysisPipelineV4(identity, gateway, people,
            direction, catalog, resolver, casting, words, models, ai, config)
        val runner = BatchAnalysisRunner(builds, people, resolver, casting, models, ai, config, words, pipeline(builds))
        val texts = ChapterTextProvider(db.bookChapterDao, read)
        var preload = 0
        private val settings = object : ReadAloudSettingsGateway {
            override val currentSettings get() = ReadAloudSettings(audioPreDownloadNum = preload)
            override val settings = flowOf(currentSettings)
            override suspend fun update(transform: (ReadAloudSettings) -> ReadAloudSettings) = Unit
        }

        suspend fun configure(budget: Int, stage1: Boolean = false, maxBatch: Int = 1) {
            bookKey = requireNotNull(identity.keyOf(book.bookUrl, sourceKey, builds.generation()))
            val provider = AiProvider("local", "fixture", "http://127.0.0.1:$port", "fixture-key")
            val entries = listOf("stage1", "stage2").map { AiModelEntry(it, provider.id, it, it, requestAttempts = 1) }
            assertTrue(models.save(AiModelsConfig(listOf(provider), entries, AiStageAssignments(
                stage1 = if (stage1) listOf("stage1") else emptyList(), stage2 = listOf("stage2"),
            ))))
            assertTrue(config.save(AnalysisConfigStore.Config(bgSessionBudget = budget, bgBatchMaxChapters = maxBatch,
                prevLimit = 0, nextLimit = 0)))
        }

        fun save(index: Int, text: String) = BookHelp.saveText(book, chapters[index], text)
        fun file(index: Int) = BookHelp.getChapterContentFile(book, chapters[index])
        fun write(index: Int, text: String) { file(index).apply { parentFile!!.mkdirs(); writeText(text) } }
        fun coordinator(
            pipelineBuilds: RaBuildGateway = builds,
            identities: RaBookIdentityGateway = identity,
            coordinatorBuilds: RaBuildGateway = builds,
        ) = ReadAloudBuildCoordinator(
            pipeline(pipelineBuilds), runner, texts, coordinatorBuilds, identities, db.bookDao, db.bookChapterDao,
            settings, config, ChapterJobRegistry(),
        )
    }

    private suspend fun fixture(
        key: String,
        chapterCount: Int = TEXTS.size,
        onStage2: ((HttpExchange, String) -> Unit)? = null,
        test: suspend (Fixture) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.injectAsAppCtx()
        val read = object : ReadSettingsGateway {
            override val currentSettings = ReadSettings(tocCountWords = false)
            override val settings = flowOf(currentSettings)
            override suspend fun update(transform: (ReadSettings) -> ReadSettings) = Unit
        }
        val other = object : OtherSettingsGateway {
            override val currentSettings = OtherSettings()
            override val settings = flowOf(currentSettings)
            override suspend fun update(transform: (OtherSettings) -> OtherSettings) = Unit
        }
        startKoin { modules(module { single<ReadSettingsGateway> { read }; single<OtherSettingsGateway> { other } }) }
        try {
            InMemoryAppDatabaseFixture(app).use { storage ->
                val db = storage.database
                val style = ReadStyleConfigStore(ReadStyleRepository(HighlightRuleRepository(db.highlightRuleDao, app)))
                ReadBookConfig.initialize(style, read)
                val book = Book(bookUrl = "https://fixture.invalid/$key", name = key, origin = "fixture-source")
                val chapters = (0 until chapterCount).map { BookChapter(url = "${book.bookUrl}/$it", bookUrl = book.bookUrl,
                    index = it, title = "目录${it + 1}") }
                db.bookDao.insert(book)
                db.bookChapterDao.insert(*chapters.toTypedArray())
                val calls = CopyOnWriteArrayList<Pair<String, Int>>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val pool = Executors.newCachedThreadPool()
                server.executor = pool
                val fixture = Fixture(app, book, chapters, db, read, server.address.port, calls)
                server.createContext("/chat/completions") { exchange ->
                    try {
                        val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                        val model = request.getString("model")
                        val prompt = request.getJSONArray("messages").getJSONObject(1).getString("content")
                        val body = prompt.substringAfterLast("=== 待分析文本 ===").substringBefore("【已知人物参考】")
                        val chapter = Regex("(?:=== ch=|【本章正文】ch=)(\\d+)").find(body)?.groupValues?.get(1)?.toInt() ?: 1
                        calls.add(model to chapter)
                        if (model == "stage2" && onStage2 != null) {
                            onStage2(exchange, body)
                            return@createContext
                        }
                        val payload = if (model == "stage1") {
                            val units = ObservationAssembly.buildParaUnits(listOf(CanonicalSpeechParagraph(0,
                                fixture.file(chapter - 1).readText(), 0))).single().units
                            val selection = JSONObject().put("段号", 1).put("判定", JSONArray(units.map {
                                if (it.text.contains('“')) "T" else "F"
                            }))
                            val paragraphs = JSONArray().put(selection)
                            if (chapter == 1) JSONObject().put("段落", paragraphs).toString()
                            else JSONObject().put("章节", JSONArray().put(JSONObject().put("ch", chapter).put("段落", paragraphs))).toString()
                        } else batchResponse(batchRow(chapter, "张三"))
                        exchange.reply(payload)
                    } finally { exchange.close() }
                }
                try {
                    TEXTS.forEachIndexed { index, text -> fixture.write(index, text) }
                    fixture.configure(budget = 1)
                    server.start()
                    test(fixture)
                } finally { server.stop(0); pool.shutdownNow(); BookHelp.clearCache(book) }
            }
        } finally { stopKoin() }
    }

    private fun batchRow(chapter: Int, name: String) = JSONObject()
        .put("type", "chapter").put("ch", chapter).put("seqmap", JSONObject().put("1", "B1"))
        .put("localSpeakers", JSONObject().put("B1", JSONObject().put("name", name).put("aliases", JSONArray())))

    private fun batchResponse(vararg rows: JSONObject) = listOf(
        """{"type":"entities","entities":[{"bkey":"B1","name":"张三","roletype":"核心"}]}""",
        *rows.map(JSONObject::toString).toTypedArray(), """{"type":"end"}""",
    ).joinToString("\n", postfix = "\n")

    private fun HttpExchange.reply(payload: String) {
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", payload)))).toString().toByteArray()
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun ChapterTextProvider.Text.asBatch() = BatchAnalysisRunner.ChapterText(
        chapterIndex, title, paragraphs, prevText, nextText, contentHash, loadedAt,
        cachedBody = cachedBody,
    )

    private companion object {
        val TEXTS = listOf("室内一片安静。", "张三说：“来了。”", "张三说：“请进。”")
    }
}
