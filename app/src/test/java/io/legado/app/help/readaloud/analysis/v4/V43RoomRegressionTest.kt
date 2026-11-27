package io.legado.app.help.readaloud.analysis.v4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.*
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaIdentityCommit
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.help.readaloud.analysis.*
import io.legado.app.help.readaloud.playback.SpeechVoiceSource
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class V43RoomRegressionTest {
    @Test fun `director runs before same provider stream ends and chapters can recover`() = playbackBeforeStreamEnds("audioDirector")
    @Test fun `emotion runs before same provider stream ends and chapters can recover`() = playbackBeforeStreamEnds("emotion")
    @Test fun `remote identity verdict runs before same provider stream ends and plan returns`() = playbackBeforeStreamEnds("resolve")

    private fun playbackBeforeStreamEnds(stage: String) = runBlocking {
        val name = if (stage == "resolve") "青衣客" else "张三"
        val first = chapter(0, "${name}推门说：“到了。”")
        val second = chapter(1, "陆无双说：“请进。”")
        val third = chapter(2, "陆无双说：“坐吧。”")
        val firstRow = row(1, name).put("evidence", JSONArray().put(JSONObject()
            .put("type", "ATTRIBUTE").put("subject", "B1").put("object", "开门者")
            .put("ch", 1).put("p", 1).put("quote", "${name}推门")))
        val firstTwo = batch(firstRow, row(2, "陆无双", "B2"))
        val tail = CountDownLatch(1)
        val extractionCalls = AtomicInteger()
        val foregroundCalls = AtomicInteger()
        fixture(stage, handler = { request, exchange ->
            when (request.getString("model")) {
                "extract" -> if (extractionCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        val prefix = firstTwo.lineSequence().take(3).joinToString("\n", postfix = "\n")
                        val delta = JSONObject().put("delta", JSONObject().put("content", prefix))
                        output.write("data: ${JSONObject().put("choices", JSONArray().put(delta))}\n\n".toByteArray())
                        output.flush()
                        check(tail.await(90, TimeUnit.SECONDS))
                    }
                } else exchange.reply(batch(row(3, "陆无双", "B2")))
                stage -> {
                    foregroundCalls.incrementAndGet()
                    check(tail.count == 1L)
                    val result = when (stage) {
                        "audioDirector" -> """{"items":[{"para":1,"frag":1,"type":"sfx","tag":"开门","pos":"前"}]}"""
                        "emotion" -> """{"emotions":{"1":"温柔"}}"""
                        else -> """{"decisions":[{"q":"Q1","verdict":"UNKNOWN","confidence":0.4}]}"""
                    }
                    exchange.reply(result)
                }
                else -> error("Unexpected test model")
            }
        }) { f -> coroutineScope {
            val book = "playback-$stage"
            if (stage == "resolve") {
                f.person(book, "张三", listOf(name))
                f.person(book, "李四", listOf(name))
            }
            val registry = ChapterJobRegistry()
            val key = ChapterJobRegistry.Key(book, "src", 0, first.contentHash, SpeechAnalysisPipelineV4.RESOLVER_VERSION)
            val control = AiRequestControl()
            val ticket = checkNotNull(registry.acquireBackground(key))
            ticket.bindControl(control)
            val committedPrefix = CompletableDeferred<Unit>()
            val charged = mutableListOf<Int>()
            val runner = f.runner()
            val background = launch(Dispatchers.IO) {
                control.bind(currentCoroutineContext()[Job])
                runner.run(book, "src", listOf(first, second, third), "held-stream", control = control,
                    onChapterReady = { ch, ready ->
                        if (ready) charged.add(ch)
                        if (!ready && ch == 0) ticket.complete(ChapterJobRegistry.Outcome.EXTRACTED)
                        if (!ready && ch == 1) committedPrefix.complete(Unit)
                    })
            }
            try {
                withTimeout(20_000) { committedPrefix.await() }
                val originals = f.builds.builds(book, "src").associateBy { it.chapterIndex }
                assertEquals(setOf(0, 1), originals.keys)
                registry.withForeground(key) {
                    assertTrue(control.protected && control.running)
                    assertEquals(listOf(0), withTimeout(20_000) {
                        runner.convergeExisting(book, "src", listOf(first), AiPriority.P0_CURRENT)
                    }.ready)
                }
                val plan = withTimeout(20_000) { f.prepare("fixture", book, "src", 0, first.paragraphs) }
                assertNotNull(plan.single { it.segment.seq == 1 }.segment.characterId)
                assertEquals(1, foregroundCalls.get())
                assertEquals(1L, tail.count)
                withTimeout(5_000) { background.join() }
                assertTrue(background.isCancelled)
                assertTrue(charged.isEmpty())
                val current = requireNotNull(f.builds.getBuild(book, "src", 0))
                assertEquals(originals.getValue(0).analysisId, current.analysisId)
                assertEquals(RaChapterState.IDENTITY_READY, current.state)
                assertEquals(originals.getValue(1), f.builds.getBuild(book, "src", 1))
                assertNull(f.builds.getBuild(book, "src", 2))
                if (stage == "emotion") assertEquals("温柔", plan.single { it.segment.seq == 1 }.emotionTag)
                if (stage == "audioDirector") {
                    val direction = requireNotNull(f.direction.plan(current.analysisId!!, "PLAYBACK_BASE"))
                    assertEquals("开门", f.direction.audio(direction.id).single().tag)
                }
                tail.countDown()
                val countCommitted: suspend (Int, Boolean) -> Unit = { ch, ready -> if (ready) charged.add(ch) }
                assertEquals(listOf(1), runner.convergeExisting(book, "src", listOf(second), onChapterReady = countCommitted).ready)
                assertEquals(1, extractionCalls.get())
                assertEquals(listOf(2), runner.run(book, "src", listOf(third), "new-batch", onChapterReady = countCommitted).ready)
                assertEquals(listOf(1, 2), charged)
                assertEquals(2, extractionCalls.get())
                assertEquals(1, (1..2).map { index -> f.builds.segmentsOfChapter(book, "src", index)
                    .single { it.seq == 1 }.characterId }.distinct().size)
                println("R3-1 $stage: before tail=1 foreground request and playback plan, retained 2 extracted chapters, recovered and published background=$charged")
            } finally {
                tail.countDown()
                background.cancelAndJoin()
                registry.releaseControl(key, control)
            }
        } }
    }

    @Test fun `late batch and single chapters cannot create global identities`() = runBlocking {
        val old = chapter(0, "沈孤鸿说：“你好。”")
        val fresh = chapter(0, "庭院只余风声。")
        fixture(handler = { request, exchange ->
            val prompt = request.getJSONArray("messages").getJSONObject(1).getString("content")
            if ("=== ch=1 ===" in prompt) exchange.reply(batch(row(1, "沈孤鸿")))
            else exchange.reply("""{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"沈孤鸿","roletype":"核心"}]}""")
        }) { f ->
            for (single in listOf(false, true)) coroutineScope {
                val book = "late-single-$single"
                val gate = BeforeIdentityCommit(f.builds)
                val callbacks = AtomicInteger()
                val late = async(Dispatchers.IO) {
                    if (single) f.pipeline(gate).run("url", "fixture", book, "src", 0, old.paragraphs, "", "",
                        taskStartedAt = old.loadedAt, skipLegacyAudio = true, onPublished = { callbacks.incrementAndGet() }) != null
                    else f.runner(gate).run(book, "src", listOf(old), "old", onChapterReady = { _, ready ->
                        if (ready) callbacks.incrementAndGet()
                    }).ready.isNotEmpty()
                }
                try {
                    val originalTarget = withTimeout(20_000) { gate.reached.await() }.single()
                    assertEquals(old.contentHash, originalTarget.analysis.contentHash)
                    assertTrue(f.people.charactersRaw(book).isEmpty())
                    assertEquals(listOf(0), f.runner().run(book, "src", listOf(fresh.copy(loadedAt = old.loadedAt + 1)), "fresh").ready)
                    val current = requireNotNull(f.builds.getBuild(book, "src", 0))
                    gate.release.complete(Unit)
                    assertFalse(withTimeout(10_000) { late.await() })
                    assertEquals(0, callbacks.get())
                    assertEquals(current, f.builds.getBuild(book, "src", 0))
                    assertEquals(fresh.contentHash, current.contentHash)
                    assertTrue(f.people.charactersRaw(book).isEmpty())
                    assertTrue(f.people.aliasesOfBook(book).isEmpty())
                    assertTrue(f.people.voiceProfiles(book).isEmpty())
                    assertEquals(RaAnalysisStatus.EXTRACTED, f.builds.analysisById(originalTarget.analysis.id)?.status)
                    assertNull(f.prepare("url", book, "src", 0, fresh.paragraphs).single().segment.characterId)
                    println("R3-3 single=$single: obsolete callbacks=0, characters/aliases/defaults=0, old snapshot retained")
                } finally { gate.release.complete(Unit); late.cancelAndJoin() }
            }
        }
    }

    @Test fun `partially superseded batch cannot attach old names or replace a shared manual voice`() = runBlocking {
        val seed = chapter(2, "张三说：“之前来过。”")
        val old = chapter(0, "沈孤鸿说：“你好。”")
        val current = chapter(1, "张三说：“请进。”")
        val narration = chapter(0, "庭院只余风声。")
        fixture(handler = { request, exchange ->
            val prompt = request.getJSONArray("messages").getJSONObject(1).getString("content")
            exchange.reply(if ("=== ch=3 ===" in prompt) batch(row(3, "张三"))
                else batch(row(1, "沈孤鸿"), row(2, "张三")))
        }) { f -> coroutineScope {
            val book = "partial-release"
            assertEquals(listOf(2), f.runner().run(book, "src", listOf(seed), "seed").ready)
            val person = f.people.charactersRaw(book).single()
            f.people.setDefaultVoice(person.id, "manual-voice", "manual")
            val voice = f.people.voiceProfiles(book).single()
            f.people.setVoiceProfileLocked(voice.id, true)
            val locked = f.people.voiceProfiles(book).single()
            val aliases = f.people.aliasesOfBook(book)
            val gate = BeforeIdentityCommit(f.builds)
            val callbacks = mutableListOf<Int>()
            val late = async(Dispatchers.IO) {
                f.runner(gate).run(book, "src", listOf(old, current), "partial", onChapterReady = { ch, ready ->
                    if (ready) callbacks.add(ch)
                })
            }
            try {
                assertEquals(setOf(0, 1), withTimeout(20_000) { gate.reached.await() }.map { it.build.chapterIndex }.toSet())
                assertEquals(listOf(0), f.runner().run(book, "src", listOf(narration.copy(loadedAt = old.loadedAt + 1)), "new").ready)
                gate.release.complete(Unit)
                assertEquals(listOf(1), withTimeout(10_000) { late.await() }.ready)
                assertEquals(listOf(1), callbacks)
                assertEquals(listOf(person.id), f.people.charactersRaw(book).map { it.id })
                assertEquals(aliases, f.people.aliasesOfBook(book))
                assertEquals(locked, f.people.voiceProfiles(book).single())
                assertEquals(2, f.people.character(person.id)?.appearanceCount)
                for (text in listOf(current, seed)) {
                    val line = f.prepare("url", book, "src", text.chapterIndex, text.paragraphs).single { it.segment.seq == 1 }
                    assertEquals(person.id, line.segment.characterId)
                    assertEquals("manual-voice", line.voice?.displayName)
                }
                println("R3-3 partial: callbacks=$callbacks, old alias absent, shared locked voice retained, appearances=2")
            } finally { gate.release.complete(Unit); late.cancelAndJoin() }
        } }
    }

    private class BeforeIdentityCommit(private val builds: RaBuildGateway) : RaBuildGateway by builds {
        val reached = CompletableDeferred<List<RaIdentityCommit>>()
        val release = CompletableDeferred<Unit>()
        override suspend fun <T> commitIdentity(
            targets: List<RaIdentityCommit>, write: suspend (List<RaIdentityCommit>) -> T,
        ): T {
            reached.complete(targets)
            release.await()
            return builds.commitIdentity(targets, write)
        }
    }

    private class Fixture(app: Application, db: AppDatabase) {
        val builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        val direction = RaDirectionRepository(db.raDirectionDao)
        private val words = WordLibraryRepository(app)
        val people = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, words)
        private val casting = VoiceCastingService(ReadAloudVoicePoolRepository(app), people)
        private val identity = RaBookIdentityRepository(db.raBuildDao, db.bookDao)
        private val catalog = ReadAloudCatalogRepository(db.bookDao, db.bookChapterDao, db.raBuildDao, builds)
        private val models = AiModelRepository(app)
        private val config = AnalysisConfigStore(app)
        private val ai = AiSpeechClient(models, config, AiRequestScheduler())
        private val cards = CharacterCardProjector(people, builds)
        private val resolver = IdentityResolver(people, words, cards, AiIdentityArbiter(models, ai, config, cards))
        fun pipeline(gateway: RaBuildGateway = builds) = SpeechAnalysisPipelineV4(identity, gateway, people,
            direction, catalog, resolver, casting, words, models, ai, config)
        fun runner(gateway: RaBuildGateway = builds) = BatchAnalysisRunner(gateway, people, resolver,
            casting, models, ai, config, words, pipeline(gateway))
        val prepare = PrepareChapterSpeechPlanUseCase(BuildSpeechPlanUseCase(people, object : SpeechVoiceSource {
            override suspend fun byTag(tag: String) = ReadAloudVoice(tag, "tts_server", "e", tag, tag)
            override suspend fun narrator() = ReadAloudVoice("n", "tts_server", "e", "n", "旁白")
            override suspend fun defaultDialog(gender: String, seed: Int) = ReadAloudVoice("d", "tts_server", "e", "d", "默认")
        }), builds, direction, catalog, pipeline(), config)

        suspend fun configure(port: Int, stage: String?) {
            val provider = AiProvider("fixture", "fixture", "http://127.0.0.1:$port", "fixture-key", maxConcurrency = 1)
            fun model(id: String) = AiModelEntry(id, provider.id, id, id, requestAttempts = 1, validateRetries = 1, timeoutMs = 90_000)
            val primary = model("extract")
            val extra = stage?.let { listOf(model(it)) }.orEmpty()
            assertTrue(models.save(AiModelsConfig(listOf(provider), listOf(primary) + extra, AiStageAssignments(
                stage2 = listOf(primary.id), resolve = listOfNotNull(stage?.takeIf { it == "resolve" }),
                audioDirector = listOfNotNull(stage?.takeIf { it == "audioDirector" }),
                emotion = listOfNotNull(stage?.takeIf { it == "emotion" }),
            ))))
            assertTrue(config.save(AnalysisConfigStore.Config(prevLimit = 0, nextLimit = 0, timeoutSec = 90)))
        }
        suspend fun person(book: String, name: String, aliases: List<String>) = people.ensureCharacter(
            book, name, "核心", "男", "男青年", 0, aliases, "ACTIVE", "user", "default",
        )
    }

    private suspend fun fixture(stage: String? = null, handler: (JSONObject, HttpExchange) -> Unit, test: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/chat/completions") { exchange ->
                try { handler(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }), exchange) }
                finally { exchange.close() }
            }
        }
        try {
            val fixture = Fixture(app, db)
            fixture.configure(server.address.port, stage)
            server.start()
            withTimeout(90_000) { test(fixture) }
        } finally { server.stop(0); pool.shutdownNow(); db.close() }
    }

    private fun HttpExchange.reply(payload: String) {
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", payload))))
            .toString().toByteArray()
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun chapter(index: Int, text: String): BatchAnalysisRunner.ChapterText {
        val paragraphs = listOf(CanonicalSpeechParagraph(0, text, 0))
        return BatchAnalysisRunner.ChapterText(index, "第${index + 1}章", paragraphs, "", "", SpeechIdentity.chapterContentHash(paragraphs))
    }

    private fun row(ch: Int, name: String, key: String = "B1") = JSONObject()
        .put("type", "chapter").put("ch", ch).put("seqmap", JSONObject().put("1", key))
        .put("localSpeakers", JSONObject().put(key, JSONObject().put("name", name).put("aliases", JSONArray())))

    private fun batch(vararg rows: JSONObject): String = listOf(
        """{"type":"entities","entities":[{"bkey":"B1","name":"张三","roletype":"核心"},{"bkey":"B2","name":"陆无双","roletype":"核心"}]}""",
        *rows.map(JSONObject::toString).toTypedArray(), """{"type":"end"}""",
    ).joinToString("\n", postfix = "\n")
}
