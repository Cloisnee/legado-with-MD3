package io.legado.app.help.readaloud.analysis.v4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.*
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.help.readaloud.analysis.*
import io.legado.app.help.readaloud.playback.SpeechVoiceSource
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 生产阶段1/阶段2/收敛 → Room → 播放计划；只替换外部模型为 loopback HTTP。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BatchRoomPipelineTest {
    private val voices = object : SpeechVoiceSource {
        override suspend fun byTag(tag: String) = ReadAloudVoice(tag, "tts_server", "e", tag, tag)
        override suspend fun narrator() = ReadAloudVoice("n", "tts_server", "e", "n", "旁白")
        override suspend fun defaultDialog(gender: String, seed: Int) = ReadAloudVoice("d", "tts_server", "e", "d", "默认")
    }

    @Test fun `display chapters one and two preserve actors narrator hashes evidence and playback voices`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        val serverError = AtomicReference<Throwable?>()
        val paragraphs = listOf(
            listOf("张三说：“你好。”", "李四说：“再见。”", "叙述者写道：“这是旁白。”"),
            listOf("李四说：“回来了。”"),
        ).map { lines -> lines.mapIndexed { n, line -> CanonicalSpeechParagraph(n, line, n * 30) } }
        val stage1 = JSONObject().put("章节", JSONArray().apply {
            paragraphs.forEachIndexed { chapter, lines ->
                put(JSONObject().put("ch", chapter + 1).put("段落", JSONArray().apply {
                    ObservationAssembly.buildParaUnits(lines).forEach { paragraph ->
                        put(JSONObject().put("段号", paragraph.paraIndex + 1).put("判定", JSONArray().apply {
                            paragraph.units.forEach { unit -> put(if (unit.text.contains('“')) "T" else "F") }
                        }))
                    }
                }))
            }
        }).toString()
        val stage2 = listOf(
            """{"type":"entities","entities":[{"bkey":"B1","name":"张三","roletype":"核心","gender":"男","age":"男青年","genderSource":"explicit","ageSource":"inferred"},{"bkey":"B2","name":"李四","roletype":"核心","gender":"女","age":"女青年","genderSource":"explicit","ageSource":"inferred"}]}""",
            """{"type":"chapter","ch":1,"seqmap":{"1":"B1","2":"B2","3":"N"},"localSpeakers":{"B1":{"name":"张三","aliases":[]},"B2":{"name":"李四","aliases":[]}},"mentions":[],"evidence":[{"type":"ATTRIBUTE","subject":"B1","object":"说话者","ch":1,"p":1,"quote":"张三说"}]}""",
            """{"type":"chapter","ch":2,"seqmap":{"1":"B2"},"localSpeakers":{"B2":{"name":"李四","aliases":[]}},"mentions":[],"evidence":[]}""",
            """{"type":"end","chapters":[1,2]}""",
        ).joinToString("\n", postfix = "\n")
        val thread = Thread({
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream()
                        val headers = StringBuilder()
                        while (!headers.endsWith("\r\n\r\n")) {
                            val byte = input.read(); check(byte >= 0); headers.append(byte.toChar())
                        }
                        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                            .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                        repeat(length) { check(input.read() >= 0) }
                        val content = if (requests.incrementAndGet() == 1) stage1 else stage2
                        val bytes = JSONObject().put("choices", JSONArray().put(
                            JSONObject().put("message", JSONObject().put("content", content)),
                        )).toString().toByteArray()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (error: Throwable) { serverError.set(error) }
        }, "batch-room-model").apply { isDaemon = true; start() }
        try {
            val builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
            val words = WordLibraryRepository(app)
            val characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, words)
            val casting = VoiceCastingService(ReadAloudVoicePoolRepository(app), characters)
            val resolver = IdentityResolver(characters, words)
            val models = AiModelRepository(app)
            val provider = AiProvider("fixture", "fixture", "http://127.0.0.1:${server.localPort}", "fixture-key")
            val model = AiModelEntry("fixture", provider.id, "fixture", "fixture", requestAttempts = 1, validateRetries = 1)
            assertTrue(models.save(AiModelsConfig(listOf(provider), listOf(model),
                AiStageAssignments(stage1 = listOf(model.id), stage2 = listOf(model.id)))))
            val config = AnalysisConfigStore(app)
            assertTrue(config.save(AnalysisConfigStore.Config(prevLimit = 0, nextLimit = 0)))
            val ai = AiSpeechClient(models, config, AiRequestScheduler())
            val pipeline = SpeechAnalysisPipelineV4(
                RaBookIdentityRepository(db.raBuildDao, db.bookDao), builds, characters,
                RaDirectionRepository(db.raDirectionDao),
                ReadAloudCatalogRepository(db.bookDao, db.bookChapterDao, db.raBuildDao, builds),
                resolver, casting, words, models, ai, config,
            )
            val runner = BatchAnalysisRunner(builds, characters, resolver, casting, models, ai, config, words, pipeline)
            val inputs = paragraphs.mapIndexed { chapter, lines ->
                BatchAnalysisRunner.ChapterText(chapter, "第${chapter + 1}章", lines, "", "",
                    SpeechIdentity.chapterContentHash(lines))
            }
            val result = withTimeout(60_000) { runner.run("bk", "src", inputs, "fixture-response") }
            assertEquals(listOf(0, 1), result.ready)
            assertTrue(result.failed.isEmpty())
            val actors = characters.charactersRaw("bk")
            assertEquals(setOf("张三", "李四"), actors.map { it.displayName }.toSet())
            assertEquals(2, characters.voiceProfiles("bk").size)
            for (actor in actors) {
                val profile = characters.voiceProfiles("bk").single { it.characterId == actor.id }
                characters.saveVoiceProfile(profile.copy(voiceTag = actor.displayName, source = "user"))
            }
            val first = builds.segmentsOfChapter("bk", "src", 0)
            val firstPlan = BuildSpeechPlanUseCase(characters, voices)("bk", first)
            assertEquals(listOf("张三", "李四"), firstPlan.filter { it.segment.roleType != SpeechRoleType.Narrator }.map { it.voice?.displayName })
            assertTrue(first.any { it.text.contains("这是旁白") && it.roleType == SpeechRoleType.Narrator })
            assertEquals(listOf(1, 2), first.filter { it.roleType != SpeechRoleType.Narrator }.map { it.seq })
            val second = builds.segmentsOfChapter("bk", "src", 1)
            assertEquals(actors.single { it.displayName == "李四" }.id, second.single { it.seq == 1 }.characterId)
            for (input in inputs) {
                val build = builds.getBuild("bk", "src", input.chapterIndex)!!
                assertEquals(input.contentHash, build.contentHash)
                assertEquals(RaChapterState.IDENTITY_READY, build.state)
                assertEquals(0, build.attempts)
                val analysis = builds.analysisById(build.analysisId!!)!!
                assertTrue(analysis.observationJson.contains("explicit"))
                assertEquals(model.id, analysis.modelId)
            }
            assertEquals(1, builds.evidence(builds.getBuild("bk", "src", 0)!!.analysisId!!).size)
            assertEquals(2, requests.get())
            assertNull(serverError.get())
        } finally {
            server.close()
            db.close()
            thread.join(1_000)
        }
    }

    @Test fun `same response converges into one identity and default voice across persisted recovery`() = runBlocking {
        val first = chapter(0, "张三说：“在这里。”")
        val second = chapter(1, "青衣客说：“跟我来。”")
        val response = batchResponse(
            entry(1, "张三", "1" to "B1"), entry(2, "青衣客", "1" to "B1"),
        )
        fixture(listOf(response, response, response)) { f ->
            for (path in 0..2) {
                val bookKey = "route-$path"
                val stop = IllegalStateException("模拟批次中断")
                val run = runCatching {
                    f.runner().run(bookKey, "src", listOf(first, second), "response-$path", onChapterReady = { ch, ready ->
                        if (path == 1 && ch == 0 && !ready) {
                            val current = f.runner().convergeExisting(bookKey, "src", listOf(first), AiPriority.P0_CURRENT)
                            assertEquals(listOf(0), current.ready)
                        }
                        if (path == 2 && ch == 1 && !ready) throw stop
                    })
                }
                if (path == 2) {
                    assertEquals(stop.message, run.exceptionOrNull()?.message)
                    for ((input, index) in listOf(first to 0, second to 1)) {
                        assertEquals(RaChapterState.EXTRACTED, f.builds.getBuild(bookKey, "src", index)?.state)
                        val recovered = f.runner().convergeExisting(bookKey, "src", listOf(input))
                        assertEquals(listOf(index), recovered.ready)
                    }
                } else assertEquals(listOf(0, 1), run.getOrThrow().ready)
                val identity = f.characters.charactersRaw(bookKey).single()
                val profile = f.characters.voiceProfiles(bookKey).single()
                assertEquals(RaVoiceProfileKind.DEFAULT, profile.kind)
                assertEquals(identity.id, profile.characterId)
                assertTrue(f.characters.aliasesOf(identity.id).any { it.surface == "青衣客" })
                val scenes = (0..1).map { index ->
                    f.builds.segmentsOfChapter(bookKey, "src", index).single { it.seq == 1 }
                }
                assertEquals(listOf(identity.id, identity.id), scenes.map { it.characterId })
                f.characters.saveVoiceProfile(profile.copy(voiceTag = "single-default"))
                assertEquals(listOf("single-default", "single-default"),
                    BuildSpeechPlanUseCase(f.characters, voices)(bookKey, scenes).map { it.voice?.displayName })
            }
            assertEquals(3, f.requests.get())
        }
    }

    @Test fun `batch rebuild and recovery apply matching user overrides before playback`() = runBlocking {
        val old = chapter(0, "张三说：“你好。”", "张三说：“我来。”", "张三说：“旧句。”")
        val new = chapter(0, "张三说：“你好。”", "张三说：“我来。”", "张三说：“新句。”")
        val response = batchResponse(entry(1, "张三", "1" to "B1", "2" to "B1", "3" to "B1"))
        fixture(List(4) { response }) { f ->
            for (recover in listOf(false, true)) {
                val bookKey = "override-$recover"
                val b = f.characters.ensureCharacter(bookKey, "李四", "核心", "男", "男青年", 0,
                    emptyList(), "ACTIVE", "ai", "default")
                assertEquals(listOf(0), f.runner().run(bookKey, "src", listOf(old), "old").ready)
                val oldPointer = f.builds.getBuild(bookKey, "src", 0)!!.analysisId
                val actor = f.characters.charactersRaw(bookKey).single { it.displayName == "张三" }
                val oldLines = f.builds.segmentsOfChapter(bookKey, "src", 0).filter { it.seq > 0 }
                for ((segment, person, narrator) in listOf(
                    Triple(oldLines[0], null, true), Triple(oldLines[1], b.id, false), Triple(oldLines[2], b.id, false),
                )) {
                    f.builds.saveOverride(bookKey, 0, segment.paragraphIndex,
                        MD5Utils.md5Encode16(segment.text), person, narrator)
                }
                assertTrue(f.builds.segmentsOfChapter(bookKey, "src", 0)
                    .first { it.text == oldLines[0].text }.roleType == SpeechRoleType.Narrator)
                val stop = IllegalStateException("模拟 EXTRACTED 后中断")
                val rebuild = runCatching {
                    f.runner().run(bookKey, "src", listOf(new.copy(loadedAt = System.currentTimeMillis() + 1)),
                        "new", onChapterReady = { _, ready -> if (recover && !ready) throw stop })
                }
                if (recover) {
                    assertEquals(stop.message, rebuild.exceptionOrNull()?.message)
                    assertEquals(RaChapterState.EXTRACTED, f.builds.getBuild(bookKey, "src", 0)?.state)
                    assertEquals(listOf(0), f.runner().convergeExisting(bookKey, "src", listOf(new)).ready)
                } else assertEquals(listOf(0), rebuild.getOrThrow().ready)
                val current = requireNotNull(f.builds.getAnalysis(bookKey, "src", 0, new.contentHash,
                    SpeechAnalysisPipelineV4.RESOLVER_VERSION))
                assertNotEquals(oldPointer, current.id)
                assertEquals(RaAnalysisStatus.RESOLVED, current.status)
                f.casting.ensureDefaultProfiles(bookKey, listOf(b), "测试")
                f.characters.voiceProfiles(bookKey).forEach { profile ->
                    f.characters.saveVoiceProfile(profile.copy(voiceTag = if (profile.characterId == b.id) "other" else "original"))
                }
                val items = BuildSpeechPlanUseCase(f.characters, voices)(bookKey, f.builds.segments(current.id))
                val changedNarrator = items.single { it.segment.text.contains("你好") }
                val changedPerson = items.single { it.segment.text.contains("我来") }
                val revised = items.single { it.segment.text.contains("新句") }
                assertEquals(listOf("旁白", "other", "original"),
                    listOf(changedNarrator, changedPerson, revised).map { it.voice?.displayName })
                assertEquals(listOf(null, b.id, actor.id),
                    listOf(changedNarrator, changedPerson, revised).map { it.segment.characterId })
                assertEquals(SpeechResolutionSource.User, changedNarrator.segment.source)
                assertEquals(0, changedNarrator.segment.seq)
                assertEquals(SpeechResolutionSource.User, changedPerson.segment.source)
                assertEquals(3, f.builds.overrides(bookKey, 0).size)
            }
            assertEquals(4, f.requests.get())
        }
    }

    @Test fun `batch evidence uses sent boundary excerpts and names use own chapter`() = runBlocking {
        val first = chapter(1, "张三说：“你好。”").copy(prevText = "前情的甲")
        val middle = chapter(2, "青衣客说：“我来。”").copy(prevText = "未发送的乙", nextText = "未发送的丙")
        val last = chapter(3, "张三说：“请进。”").copy(nextText = "后续的丁")
        val response = batchResponse(entry(2, "张三", "1" to "B1"),
            entry(3, "青衣客", "1" to "B1").put("evidence", JSONArray()
                .put(JSONObject("""{"type":"ATTRIBUTE","subject":"B1","object":"甲","ch":1,"p":1,"quote":"前情的甲"}"""))
                .put(JSONObject("""{"type":"ATTRIBUTE","subject":"B1","object":"乙","ch":2,"p":1,"quote":"未发送的乙"}"""))),
            entry(4, "张三", "1" to "B1"))
        val invalid = batchResponse(entry(3, "张三", "1" to "B1"))
        fixture(listOf(response, invalid, invalid)) { f ->
            val result = f.runner().run("clean", "src", listOf(first, middle, last), "good")
            assertEquals(listOf(1, 2, 3), result.ready)
            val analysis = f.builds.getBuild("clean", "src", 2)!!.analysisId!!
            val evidence = f.builds.evidence(analysis).single()
            assertEquals("前情的甲", evidence.excerpt)
            assertEquals(RaEvidenceScope.CONTEXT_CLUE, evidence.scope)
            assertFalse(evidence.verified)
            val bad = f.runner().run("invalid", "src", listOf(middle), "bad-name")
            assertEquals(listOf(2), bad.failed)
            assertNull(f.builds.getBuild("invalid", "src", 2))
        }
    }

    @Test fun `foreground consumption of cached batch chapter restores legacy emotion once`() = runBlocking {
        val text = chapter(0, "张三说：“你好。”", "张三说：“我来。”")
        fixture(listOf(batchResponse(entry(1, "张三", "1" to "B1", "2" to "B1")),
            """{"emotions":{"1":"温柔"}}"""), emotion = true) { f ->
            assertEquals(listOf(0), f.runner().run("bk", "src", listOf(text), "one").ready)
            val analysisId = f.builds.getBuild("bk", "src", 0)!!.analysisId!!
            assertNull(f.direction.plan(analysisId, PrepareChapterSpeechPlanUseCase.MODULE))
            val narrator = f.builds.segments(analysisId).single { it.seq == 1 }
            f.builds.saveOverride("bk", 0, narrator.paragraphIndex, MD5Utils.md5Encode16(narrator.text), null, true)
            val prepare = PrepareChapterSpeechPlanUseCase(BuildSpeechPlanUseCase(f.characters, voices), f.builds,
                f.direction, f.catalog, f.pipeline, f.config)
            repeat(2) {
                val plan = prepare("fixture", "bk", "src", 0, text.paragraphs)
                assertEquals("温柔", plan.single { it.segment.seq == 2 }.emotionTag)
                assertEquals("", plan.single { it.segment.text == narrator.text }.emotionTag)
            }
            assertEquals(2, f.requests.get())
        }
    }

    private fun chapter(index: Int, vararg lines: String): BatchAnalysisRunner.ChapterText {
        val paragraphs = lines.mapIndexed { i, line -> CanonicalSpeechParagraph(i, line, i * 30) }
        return BatchAnalysisRunner.ChapterText(index, "第${index + 1}章", paragraphs, "", "",
            SpeechIdentity.chapterContentHash(paragraphs))
    }

    private fun entry(ch: Int, name: String, vararg seq: Pair<String, String>) = JSONObject()
        .put("type", "chapter").put("ch", ch).put("seqmap", JSONObject(seq.toMap()))
        .put("localSpeakers", JSONObject().put("B1", JSONObject().put("name", name).put("aliases", JSONArray())))

    private fun batchResponse(vararg chapters: JSONObject): String = listOf(
        """{"type":"entities","entities":[{"bkey":"B1","name":"张三","roletype":"核心"}]}""",
        *chapters.map(JSONObject::toString).toTypedArray(),
        """{"type":"end"}""",
    ).joinToString("\n", postfix = "\n")

    private class Fixture(app: Application, db: AppDatabase) {
        val requests = AtomicInteger()
        val builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        private val words = WordLibraryRepository(app)
        val characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, words)
        val casting = VoiceCastingService(ReadAloudVoicePoolRepository(app), characters)
        private val resolver = IdentityResolver(characters, words)
        private val models = AiModelRepository(app)
        val config = AnalysisConfigStore(app)
        private val ai = AiSpeechClient(models, config, AiRequestScheduler())
        val direction = RaDirectionRepository(db.raDirectionDao)
        val catalog = ReadAloudCatalogRepository(db.bookDao, db.bookChapterDao, db.raBuildDao, builds)
        val pipeline = SpeechAnalysisPipelineV4(RaBookIdentityRepository(db.raBuildDao, db.bookDao),
            builds, characters, direction, catalog, resolver, casting, words, models, ai, config)

        suspend fun configure(port: Int, emotion: Boolean) {
            val provider = AiProvider("fixture", "fixture", "http://127.0.0.1:$port", "fixture-key")
            val model = AiModelEntry("fixture", provider.id, "fixture", "fixture", requestAttempts = 1)
            assertTrue(models.save(AiModelsConfig(listOf(provider), listOf(model), AiStageAssignments(
                stage2 = listOf(model.id), emotion = if (emotion) listOf(model.id) else emptyList(),
            ))))
            assertTrue(config.save(AnalysisConfigStore.Config(prevLimit = 200, nextLimit = 200)))
        }

        fun runner() = BatchAnalysisRunner(builds, characters, resolver, casting, models, ai, config, words, pipeline)
    }

    private suspend fun fixture(replies: List<String>, emotion: Boolean = false, test: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val fixture = Fixture(app, db)
            fixture.configure(server.address.port, emotion)
            server.createContext("/chat/completions") { exchange ->
                try {
                    exchange.requestBody.use { it.readBytes() }
                    val i = fixture.requests.getAndIncrement()
                    val payload = replies.getOrNull(i) ?: error("额外的模型请求 $i")
                    val bytes = JSONObject().put("choices", JSONArray().put(
                        JSONObject().put("message", JSONObject().put("content", payload)),
                    )).toString().toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
            withTimeout(60_000) { test(fixture) }
        } finally {
            server.stop(0)
            db.close()
        }
    }
}
