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
import io.legado.app.domain.model.readaloud.v4.RaCharacterAlias
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaResolution
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
                RaBookIdentityRepository(db, db.raBuildDao, db.bookDao, builds), builds, characters,
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

    @Test fun `conflicting same response key respects each chapter evidence in all convergence orders`() = runBlocking {
        val first = chapter(0, "张三说：“到了。”")
        val second = chapter(1, "蒙面客正是李四。蒙面客说：“请进。”")
        val evidence = JSONObject().put("type", "ALIAS_EQUAL").put("subject", "B1").put("object", "李四")
            .put("ch", 2).put("p", 1).put("quote", "蒙面客正是李四")
        val response = batchResponse(entry(1, "张三", "1" to "B1"),
            entry(2, "蒙面客", "1" to "B1").put("evidence", JSONArray().put(evidence)))
        fixture(List(4) { response }) { f ->
            for (path in 0..3) {
                val book = "conflict-$path"
                suspend fun character(name: String, voice: String) = f.characters.ensureCharacter(
                    book, name, "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "user", "default",
                ).also { f.characters.setDefaultVoice(it.id, voice, voice) }
                val a = character("张三", "张三的声线")
                val b = character("李四", "李四的声线")
                f.characters.saveCannotLink(book, a.id, b.id, "user")
                val cannot = f.characters.linksOfBook(book).single()
                val stop = IllegalStateException("模拟断流后重启")
                val outcome = runCatching {
                    f.runner().run(book, "src", listOf(first, second), "conflict", onChapterReady = { ch, ready ->
                        if (path == 1 && ch == 0 && !ready) {
                            assertEquals(listOf(0), f.runner().convergeExisting(book, "src", listOf(first), AiPriority.P0_CURRENT).ready)
                        }
                        if (path >= 2 && ch == 1 && !ready) throw stop
                    })
                }
                if (path >= 2) {
                    // 协程恢复堆栈时可能复制异常实例。
                    val interruption = outcome.exceptionOrNull()
                    assertEquals(stop.javaClass, interruption?.javaClass)
                    assertEquals(stop.message, interruption?.message)
                    val order = if (path == 2) listOf(first, second) else listOf(second, first)
                    for (input in order) {
                        assertEquals(RaChapterState.EXTRACTED, f.builds.getBuild(book, "src", input.chapterIndex)?.state)
                        assertEquals(listOf(input.chapterIndex), f.runner().convergeExisting(book, "src", listOf(input)).ready)
                    }
                } else {
                    assertEquals(listOf(0, 1), outcome.getOrThrow().ready)
                }
                val dialogue = (0..1).map { ch -> f.builds.segmentsOfChapter(book, "src", ch).single { it.seq == 1 } }
                assertEquals(listOf(a.id, b.id), dialogue.map { it.characterId })
                assertEquals(listOf("张三的声线", "李四的声线"),
                    BuildSpeechPlanUseCase(f.characters, voices)(book, dialogue).map { it.voice?.displayName })
                assertEquals(setOf("张三"), f.characters.aliasesOf(a.id).map { it.surface }.toSet())
                assertEquals(setOf("李四", "蒙面客"), f.characters.aliasesOf(b.id).map { it.surface }.toSet())
                assertEquals(listOf(cannot), f.characters.linksOfBook(book))
                assertEquals(2, f.characters.voiceProfiles(book).size)
                val storedEvidence = f.builds.evidence(f.builds.getBuild(book, "src", 1)!!.analysisId!!).single()
                assertTrue(storedEvidence.verified)
                assertEquals("蒙面客正是李四", storedEvidence.excerpt)
                println("R3-2 path=$path: 张三/李四, distinct playback voices, aliases and user CANNOT verified")
            }
            assertEquals(4, f.requests.get())
        }
    }

    @Test fun `late evidence revises only automatic batch bindings without republishing chapters`() = runBlocking {
        val texts = listOf(
            chapter(0, "张三说：“我先走了。”"),
            chapter(1, "蒙面客是守门人。蒙面客说：“请进。”", "蒙面客说：“等一下。”", "蒙面客说：“别着急。”"),
            chapter(2, "陆七就是蒙面客，但陆七绝不是张三。陆七说：“坐吧。”"),
        )
        fun proof(ch: Int, type: String, other: String, quote: String) = JSONObject()
            .put("type", type).put("subject", "B1").put("object", other).put("ch", ch).put("p", 1).put("quote", quote)
        val response = batchResponse(
            entry(1, "张三", "1" to "B1"),
            entry(2, "蒙面客", "1" to "B1", "2" to "B1", "3" to "B1").put("evidence", JSONArray()
                .put(proof(2, "ATTRIBUTE", "守门人", "蒙面客是守门人"))),
            entry(3, "陆七", "1" to "B1").put("evidence", JSONArray()
                .put(proof(3, "ALIAS_EQUAL", "蒙面客", "陆七就是蒙面客"))
                .put(proof(3, "CONTRADICTION", "张三", "陆七绝不是张三"))),
        )
        fixture(List(2) { response }) { f ->
            for (order in listOf(listOf(0, 1), listOf(1, 0))) {
                val book = "late-proof-$order"
                val runner = f.runner()
                val foreground = ArrayList<Int>()
                val background = ArrayList<Int>()
                val earlyBuilds = ArrayList<io.legado.app.domain.model.readaloud.v4.RaChapterBuild>()
                val seedChapter = order.first()
                var seedId = ""
                val outcome = runner.run(book, "src", texts, "late-proof", onChapterReady = { chapter, ready ->
                    if (ready) background.add(chapter)
                    if (ready || chapter != 1) return@run
                    for (index in order) {
                        assertNull(f.builds.getBuild(book, "src", 2))
                        assertEquals(listOf(index), runner.convergeExisting(book, "src", listOf(texts[index]), AiPriority.P0_CURRENT,
                            onChapterReady = { c, done -> if (done) foreground.add(c) }).ready)
                    }
                    earlyBuilds.addAll(f.builds.builds(book, "src"))
                    seedId = f.characters.charactersRaw(book).single().id
                    val weak = f.builds.localSpeakers(requireNotNull(earlyBuilds[order.last()].analysisId)).single()
                    assertEquals(seedId, weak.characterId)
                    assertEquals(RaResolution.BATCH_LINKED, weak.resolution)
                    assertEquals(RaCharacterAlias.BATCH_LINK_SOURCE,
                        f.characters.aliasesOf(seedId).single { it.surface == weak.primaryName }.source)
                    f.characters.setDefaultVoice(seedId, "locked-voice", "locked-entry")
                    f.characters.setVoiceProfileLocked(f.characters.voiceProfilesOf(seedId).single().id, true)
                    f.characters.attachAliases(book, seedId, listOf("手动别名"), seedChapter, "user")
                    if (seedChapter == 0) {
                        val lines = f.builds.segmentsOfChapter(book, "src", 1).filter { it.seq > 0 }
                        for ((position, narrator) in listOf(1 to false, 2 to true)) {
                            val line = lines[position]
                            f.builds.saveOverride(book, 1, line.paragraphIndex, MD5Utils.md5Encode16(line.text),
                                seedId.takeUnless { narrator }, narrator)
                        }
                    }
                })
                assertEquals(listOf(0, 1, 2), outcome.ready)
                assertEquals(order, foreground)
                assertEquals(listOf(2), background)
                val builds = f.builds.builds(book, "src")
                assertEquals(earlyBuilds, builds.take(2))
                val ids = builds.map { build -> f.builds.localSpeakers(requireNotNull(build.analysisId)).single().characterId }
                assertNotEquals(ids[0], ids[1])
                assertEquals(ids[1], ids[2])
                assertEquals(seedId, ids[seedChapter])
                val actors = f.characters.charactersRaw(book)
                assertEquals(ids.toSet(), actors.map { it.id }.toSet())
                val correctedActor = requireNotNull(ids[1])
                assertFalse(f.characters.aliasesOf(requireNotNull(ids[0])).any { it.surface == "蒙面客" })
                assertEquals(setOf("蒙面客", "陆七"), f.characters.aliasesOf(correctedActor)
                    .map { it.surface }.filterNot { it == "手动别名" }.toSet())
                assertEquals("user", f.characters.aliasesOf(seedId).single { it.surface == "手动别名" }.source)
                assertFalse(f.characters.attributesOf(requireNotNull(ids[0])).any { it.value == "守门人" })
                assertEquals(1, f.characters.attributesOf(correctedActor).count { it.value == "守门人" })
                val locked = f.characters.voiceProfilesOf(seedId).single()
                assertEquals("locked-voice", locked.voiceTag)
                assertTrue(locked.locked)
                val newId = requireNotNull((ids.toSet() - seedId).single())
                f.characters.setDefaultVoice(newId, "revised-voice", "revised-entry")
                val playback = texts.map { input ->
                    val lines = f.builds.segmentsOfChapter(book, "src", input.chapterIndex)
                    BuildSpeechPlanUseCase(f.characters, voices)(book, lines)
                }
                assertEquals(ids, playback.map { it.single { cue -> cue.segment.seq == 1 }.segment.characterId })
                val selectedVoices = playback.map { it.single { cue -> cue.segment.seq == 1 }.voice?.id }
                assertNotEquals(selectedVoices[0], selectedVoices[1])
                assertEquals(selectedVoices[1], selectedVoices[2])
                if (seedChapter == 0) {
                    assertEquals(2, f.builds.overrides(book, 1).size)
                    val assigned = playback[1].single { it.segment.text.contains("等一下") }
                    assertEquals(seedId, assigned.segment.characterId)
                    assertEquals("locked-voice", assigned.voice?.id)
                    val narrator = playback[1].single { it.segment.text.contains("别着急") }
                    assertEquals(SpeechRoleType.Narrator, narrator.segment.roleType)
                    assertNull(narrator.segment.characterId)
                }
            }
        }
    }

    @Test fun `verified alias confirms even a published weak binding and is usable by the next chapter`() = runBlocking {
        val texts = listOf(
            chapter(0, "张三说：“先走吧。”"),
            chapter(1, "蒙面客说：“请进。”"),
            chapter(2, "张三就是蒙面客。张三说：“坐吧。”"),
            chapter(3, "青衫客说：“你好。”"),
            chapter(4, "蒙面客说：“又见面了。”"),
        )
        val proof = JSONObject().put("type", "ALIAS_EQUAL").put("subject", "B1")
            .put("object", "蒙面客").put("ch", 3).put("p", 1).put("quote", "张三就是蒙面客")
        val response = batchResponse(entry(1, "张三", "1" to "B1"), entry(2, "蒙面客", "1" to "B1"),
            entry(3, "张三", "1" to "B1").put("evidence", JSONArray().put(proof)), entry(4, "青衫客", "1" to "B1"))
        val following = """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"蒙面客","roletype":"核心"}]}"""
        fixture(listOf(response, following, response, following)) { f ->
            for (early in listOf(false, true)) {
                val book = "confirmed-$early"
                val runner = f.runner()
                val ready = runner.run(book, "src", texts.take(4), "confirmation", onChapterReady = { index, published ->
                    if (early && index == 1 && !published) {
                        for (i in 0..1) assertEquals(listOf(i), runner.convergeExisting(book, "src", listOf(texts[i])).ready)
                        val second = requireNotNull(f.builds.getBuild(book, "src", 1)?.analysisId)
                        assertEquals(RaResolution.BATCH_LINKED, f.builds.localSpeakers(second).single().resolution)
                    }
                })
                assertEquals((0..3).toList(), ready.ready)
                val actor = f.characters.charactersRaw(book).single().id
                f.characters.setDefaultVoice(actor, "original-voice", "test")
                val aliases = f.characters.aliasesOf(actor).associate { it.surface to it.source }
                assertEquals(RaCharacterAlias.BATCH_PROOF_SOURCE, aliases["蒙面客"])
                assertEquals(RaCharacterAlias.BATCH_LINK_SOURCE, aliases["青衫客"])
                val second = requireNotNull(f.builds.getBuild(book, "src", 1)?.analysisId)
                assertEquals(RaResolution.RESOLVED, f.builds.localSpeakers(second).single().resolution)
                assertNotNull(f.pipeline.run("fixture", book, book, "src", 4, texts[4].paragraphs, "", ""))
                val lines = (0..4).map { f.builds.segmentsOfChapter(book, "src", it).single { segment -> segment.seq == 1 } }
                assertTrue(lines.all { it.characterId == actor })
                assertEquals(List(5) { "original-voice" }, BuildSpeechPlanUseCase(f.characters, voices)(book, lines).map { it.voice?.id })
            }
            assertEquals(4, f.requests.get())
        }
    }

    @Test fun `alias object needs no second speaker or duplicate alias field`() = runBlocking {
        val texts = listOf(chapter(0, "张三说：“出发。”"),
            chapter(1, "张三就是蒙面客。张三说：“正是我。”"), chapter(2, "蒙面客说：“再会。”"))
        val reveal = proof(2, "蒙面客", "张三就是蒙面客")
        for (duplicate in listOf(false, true)) {
            val row = entry(2, "张三", "1" to "B1").put("evidence", JSONArray().put(reveal))
            if (duplicate) row.getJSONObject("localSpeakers").getJSONObject("B1").getJSONArray("aliases").put("蒙面客")
            fixture(listOf(batchResponse(entry(1, "张三", "1" to "B1"), row), single("蒙面客"))) { f ->
                val book = "first-text-alias-$duplicate"
                assertEquals(listOf(0, 1), f.runner().run(book, "src", texts.take(2), "reveal").ready)
                val actor = f.characters.charactersRaw(book).single().id
                assertEquals(RaCharacterAlias.BATCH_PROOF_SOURCE, f.characters.aliasesOf(actor).single { it.surface == "蒙面客" }.source)
                f.characters.setDefaultVoice(actor, "original", "fixture")
                assertNotNull(f.pipeline.run("fixture", book, book, "src", 2, texts[2].paragraphs, "", ""))
                val lines = texts.indices.map { f.line(book, it) }
                assertEquals(List(3) { actor }, lines.map { it.characterId })
                assertEquals(List(3) { "original" }, BuildSpeechPlanUseCase(f.characters, voices)(book, lines).map { it.voice?.id })
                assertEquals(2, f.requests.get())
            }
        }
    }

    @Test fun `a duplicate optional alias cannot bypass ambiguity denial or the generic name filter`() = runBlocking {
        val source = chapter(0, "张三就是蒙面客。张三绝不是蒙面客。张三又称师兄。张三说：“走吧。”")
        val ambiguous = proof(1, "蒙面客", "张三就是蒙面客")
        val denied = JSONObject().put("type", "CONTRADICTION").put("subject", "B1").put("object", "蒙面客")
            .put("ch", 1).put("p", 1).put("quote", "张三绝不是蒙面客")
        val generic = proof(1, "师兄", "张三又称师兄")
        val responses = listOf(
            batchResponse(entry(1, "张三", "1" to "B1").put("evidence", JSONArray().put(ambiguous))),
            batchResponse(entry(1, "张三", "1" to "B1").put("evidence", JSONArray().put(ambiguous).put(denied).put(generic)).also {
                it.getJSONObject("localSpeakers").getJSONObject("B1").put("aliases", JSONArray(listOf("蒙面客", "师兄")))
            }),
        )
        fixture(responses) { f ->
            val ambiguousBook = "ambiguous-text-object"
            for (name in listOf("李四", "王五")) {
                f.characters.ensureCharacter(ambiguousBook, name, "核心", "男", "男青年", 0, listOf("蒙面客"),
                    "ACTIVE", "user", "default")
            }
            for (book in listOf(ambiguousBook, "denied-text-object")) {
                assertEquals(listOf(0), f.runner().run(book, "src", listOf(source), "guarded").ready)
                val actor = requireNotNull(f.line(book, 0).characterId)
                assertEquals(setOf("张三"), f.characters.aliasesOf(actor).map { it.surface }.toSet())
            }
            assertEquals(2, f.requests.get())
        }
    }

    @Test fun `remaining independent text proof keeps the binding when either proof chapter is deleted`() = runBlocking {
        val texts = listOf(chapter(0, "张三说：“出发。”"), chapter(1, "蒙面客说：“你好。”"),
            chapter(2, "张三就是蒙面客。张三说：“没错。”"),
            chapter(3, "众人记得张三就是蒙面客。张三说：“回来吧。”"), chapter(4, "蒙面客说：“再会。”"))
        val quote = "张三就是蒙面客"
        val response = batchResponse(entry(1, "张三", "1" to "B1"), entry(2, "蒙面客", "1" to "B1"),
            entry(3, "张三", "1" to "B1").put("evidence", JSONArray().put(proof(3, "蒙面客", quote))),
            entry(4, "张三", "1" to "B1").put("evidence", JSONArray().put(proof(4, "蒙面客", quote))))
        fixture(listOf(response, single("蒙面客"), response, single("蒙面客"))) { f ->
            for (deleted in listOf(2, 3)) {
                val book = "alternative-proof-$deleted"
                assertEquals((0..3).toList(), f.runner().run(book, "src", texts.take(4), "reveal").ready)
                val actor = requireNotNull(f.line(book, 0).characterId)
                f.characters.setDefaultVoice(actor, "original", "fixture")
                f.builds.deleteChapters(book, "src", listOf(deleted))
                val second = requireNotNull(f.builds.getBuild(book, "src", 1)?.analysisId)
                assertEquals(RaResolution.RESOLVED, f.builds.localSpeakers(second).single().resolution)
                assertEquals(RaCharacterAlias.BATCH_PROOF_SOURCE, f.characters.aliasesOf(actor).single { it.surface == "蒙面客" }.source)
                assertNotNull(f.pipeline.run("fixture", book, book, "src", 4, texts[4].paragraphs, "", ""))
                val lines = listOf(f.line(book, 1), f.line(book, 4))
                assertEquals(listOf(actor, actor), lines.map { it.characterId })
                assertEquals(listOf("original", "original"), BuildSpeechPlanUseCase(f.characters, voices)(book, lines).map { it.voice?.id })
            }
            assertEquals(4, f.requests.get())
        }
    }

    @Test fun `losing a necessary link in a real batch proof revokes its alias`() = runBlocking {
        val texts = listOf(chapter(0, "张三说：“出发。”"), chapter(1, "蒙面客说：“你好。”"),
            chapter(2, "张三就是白衣客。张三说：“没错。”"),
            chapter(3, "白衣客就是蒙面客。白衣客说：“回来吧。”"), chapter(4, "蒙面客说：“再会。”"))
        val response = batchResponse(entry(1, "张三", "1" to "B1"), entry(2, "蒙面客", "1" to "B1"),
            entry(3, "张三", "1" to "B1").put("evidence", JSONArray().put(proof(3, "白衣客", "张三就是白衣客"))),
            entry(4, "白衣客", "1" to "B1").put("evidence", JSONArray().put(proof(4, "蒙面客", "白衣客就是蒙面客"))))
        fixture(listOf(response, single("蒙面客"))) { f ->
            val book = "deleted-chain-link"
            assertEquals((0..3).toList(), f.runner().run(book, "src", texts.take(4), "chain").ready)
            val actor = requireNotNull(f.line(book, 0).characterId)
            val speaker = f.builds.localSpeakers(requireNotNull(f.builds.getBuild(book, "src", 1)?.analysisId)).single()
            assertEquals(RaResolution.RESOLVED, speaker.resolution)
            assertEquals(listOf(2), f.characters.decisionsForCharacter(actor).filter { it.localSpeakerId == speaker.id }
                .map { it.evidenceIds.size })
            f.builds.deleteChapters(book, "src", listOf(2))
            val after = f.builds.localSpeakers(speaker.analysisId).single()
            assertEquals(actor, after.characterId)
            assertEquals(RaResolution.BATCH_LINKED, after.resolution)
            assertEquals(RaCharacterAlias.BATCH_LINK_SOURCE, f.characters.aliasesOf(actor).single { it.surface == "蒙面客" }.source)
            assertNotNull(f.pipeline.run("fixture", book, book, "src", 4, texts[4].paragraphs, "", ""))
            assertNotEquals(actor, f.line(book, 4).characterId)
            assertEquals(2, f.requests.get())
        }
    }

    @Test fun `deleting proof and binding owners does not let later occurrences prove an alias`() = runBlocking {
        val texts = listOf(chapter(0, "张三说：“出发。”"), chapter(1, "蒙面客说：“你好。”"),
            chapter(2, "张三就是蒙面客。张三说：“没错。”"), chapter(3, "蒙面客说：“后来。”"),
            chapter(4, "蒙面客说：“再会。”"))
        val response = batchResponse(entry(1, "张三", "1" to "B1"), entry(2, "蒙面客", "1" to "B1"),
            entry(3, "张三", "1" to "B1").put("evidence", JSONArray().put(proof(3, "蒙面客", "张三就是蒙面客"))))
        fixture(listOf(response, single("蒙面客"), single("蒙面客"), response, single("蒙面客"), single("蒙面客"))) { f ->
            for (manual in listOf(false, true)) {
                val book = "deleted-owner-$manual"
                assertEquals((0..2).toList(), f.runner().run(book, "src", texts.take(3), "reveal").ready)
                val actor = requireNotNull(f.line(book, 0).characterId)
                assertNotNull(f.pipeline.run("fixture", book, book, "src", 3, texts[3].paragraphs, "", ""))
                val existing = f.line(book, 3)
                assertEquals(actor, existing.characterId)
                if (manual) f.characters.attachAliases(book, actor, listOf("蒙面客"), 2, "user")
                f.builds.deleteChapters(book, "src", listOf(1, 2))
                assertEquals(existing, f.line(book, 3))
                assertEquals(if (manual) "user" else RaCharacterAlias.BATCH_LINK_SOURCE,
                    f.characters.aliasesOf(actor).single { it.surface == "蒙面客" }.source)
                assertTrue(f.characters.decisionsForCharacter(actor).isEmpty())
                assertNotNull(f.pipeline.run("fixture", book, book, "src", 4, texts[4].paragraphs, "", ""))
                assertEquals(manual, actor == f.line(book, 4).characterId)
            }
            assertEquals(6, f.requests.get())
        }
    }

    private fun proof(ch: Int, alias: String, quote: String) = JSONObject().put("type", "ALIAS_EQUAL")
        .put("subject", "B1").put("object", alias).put("ch", ch).put("p", 1).put("quote", quote)

    private fun single(name: String) =
        """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"$name","roletype":"核心"}]}"""

    private suspend fun Fixture.line(book: String, chapter: Int) =
        builds.segmentsOfChapter(book, "src", chapter).single { it.seq == 1 }

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
        val pipeline = SpeechAnalysisPipelineV4(RaBookIdentityRepository(db, db.raBuildDao, db.bookDao, builds),
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
