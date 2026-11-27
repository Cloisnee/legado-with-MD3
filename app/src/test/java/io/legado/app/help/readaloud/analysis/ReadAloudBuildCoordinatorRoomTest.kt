package io.legado.app.help.readaloud.analysis

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.repository.*
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
            } finally { coordinator.stopSession() }
        }
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
            } finally { release.complete(Unit); ready.cancelAndJoin(); coordinator.stopSession() }
        } }
    }

    private class Fixture(
        app: Application, val book: Book, private val chapters: List<BookChapter>,
        private val db: AppDatabase, read: ReadSettingsGateway, private val port: Int,
        val calls: CopyOnWriteArrayList<Pair<String, Int>>,
    ) {
        val sourceKey = sourceKeyOf(book)
        private val identity = RaBookIdentityRepository(db.raBuildDao, db.bookDao)
        lateinit var bookKey: String
        val builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
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
        private val settings = object : ReadAloudSettingsGateway {
            override val currentSettings = ReadAloudSettings(audioPreDownloadNum = 0)
            override val settings = flowOf(currentSettings)
            override suspend fun update(transform: (ReadAloudSettings) -> ReadAloudSettings) = Unit
        }

        suspend fun configure(budget: Int, stage1: Boolean = false) {
            bookKey = identity.keyOf(book.bookUrl, sourceKey)
            val provider = AiProvider("local", "fixture", "http://127.0.0.1:$port", "fixture-key")
            val entries = listOf("stage1", "stage2").map { AiModelEntry(it, provider.id, it, it, requestAttempts = 1) }
            assertTrue(models.save(AiModelsConfig(listOf(provider), entries, AiStageAssignments(
                stage1 = if (stage1) listOf("stage1") else emptyList(), stage2 = listOf("stage2"),
            ))))
            assertTrue(config.save(AnalysisConfigStore.Config(bgSessionBudget = budget, bgBatchMaxChapters = 1,
                prevLimit = 0, nextLimit = 0)))
        }

        fun file(index: Int) = BookHelp.getChapterContentFile(book, chapters[index])
        fun write(index: Int, text: String) { file(index).apply { parentFile!!.mkdirs(); writeText(text) } }
        fun coordinator(pipelineBuilds: RaBuildGateway = builds) = ReadAloudBuildCoordinator(
            pipeline(pipelineBuilds), runner, texts, builds, identity, db.bookDao, db.bookChapterDao,
            settings, config, ChapterJobRegistry(),
        )
    }

    private suspend fun fixture(key: String, test: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.injectAsAppCtx()
        val read = object : ReadSettingsGateway {
            override val currentSettings = ReadSettings()
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
                val chapters = TEXTS.indices.map { BookChapter(url = "${book.bookUrl}/$it", bookUrl = book.bookUrl,
                    index = it, title = "目录${it + 1}") }
                db.bookDao.insert(book)
                db.bookChapterDao.insert(*chapters.toTypedArray())
                val calls = CopyOnWriteArrayList<Pair<String, Int>>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val fixture = Fixture(app, book, chapters, db, read, server.address.port, calls)
                server.createContext("/chat/completions") { exchange ->
                    try {
                        val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                        val model = request.getString("model")
                        val prompt = request.getJSONArray("messages").getJSONObject(1).getString("content")
                        val chapter = Regex("=== ch=(\\d+) ===").find(prompt)?.groupValues?.get(1)?.toInt() ?: 1
                        calls.add(model to chapter)
                        val payload = if (model == "stage1") {
                            val units = ObservationAssembly.buildParaUnits(listOf(CanonicalSpeechParagraph(0,
                                fixture.file(chapter - 1).readText(), 0))).single().units
                            val selection = JSONObject().put("段号", 1).put("判定", JSONArray(units.map {
                                if (it.text.contains('“')) "T" else "F"
                            }))
                            val paragraphs = JSONArray().put(selection)
                            if (chapter == 1) JSONObject().put("段落", paragraphs).toString()
                            else JSONObject().put("章节", JSONArray().put(JSONObject().put("ch", chapter).put("段落", paragraphs))).toString()
                        } else {
                            """{"type":"entities","entities":[{"bkey":"B1","name":"张三","roletype":"核心"}]}
{"type":"chapter","ch":$chapter,"seqmap":{"1":"B1"},"localSpeakers":{"B1":{"name":"张三","aliases":[]}}}
{"type":"end"}
"""
                        }
                        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                            JSONObject().put("content", payload)))).toString().toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    } finally { exchange.close() }
                }
                try {
                    TEXTS.forEachIndexed { index, text -> fixture.write(index, text) }
                    fixture.configure(budget = 1)
                    server.start()
                    test(fixture)
                } finally { server.stop(0); BookHelp.clearCache(book) }
            }
        } finally { stopKoin() }
    }

    private fun ChapterTextProvider.Text.asBatch() = BatchAnalysisRunner.ChapterText(
        chapterIndex, title, paragraphs, prevText, nextText, contentHash, loadedAt,
    )

    private companion object {
        val TEXTS = listOf("室内一片安静。", "张三说：“来了。”", "张三说：“请进。”")
    }
}
