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
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.help.readaloud.analysis.*
import io.legado.app.help.readaloud.playback.SpeechVoiceSource
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
            val result = withTimeout(10_000) { runner.run("bk", "src", inputs, "fixture-response") }
            assertEquals(listOf(0, 1), result.ready)
            assertTrue(result.failed.isEmpty())
            val actors = characters.charactersRaw("bk")
            assertEquals(setOf("张三", "李四"), actors.map { it.displayName }.toSet())
            assertEquals(2, characters.voiceProfiles("bk").size)
            for (actor in actors) {
                val profile = characters.voiceProfiles("bk").single { it.characterId == actor.id }
                characters.saveVoiceProfile(profile.copy(voiceTag = actor.displayName, source = "user"))
            }
            val voices = object : SpeechVoiceSource {
                override suspend fun byTag(tag: String) = ReadAloudVoice(tag, "tts_server", "e", tag, tag)
                override suspend fun narrator() = ReadAloudVoice("n", "tts_server", "e", "n", "旁白")
                override suspend fun defaultDialog(gender: String, seed: Int) = ReadAloudVoice("d", "tts_server", "e", "d", "默认")
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
}
