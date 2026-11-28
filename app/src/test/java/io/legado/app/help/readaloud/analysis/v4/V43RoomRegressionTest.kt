package io.legado.app.help.readaloud.analysis.v4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.*
import io.legado.app.domain.gateway.RaBuildGateway
import io.legado.app.domain.gateway.RaBuildInput
import io.legado.app.domain.gateway.RaIdentityCommit
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.v4.RaAnalysis
import io.legado.app.domain.model.readaloud.v4.RaAnalysisStatus
import io.legado.app.domain.model.readaloud.v4.RaChapterState
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.help.readaloud.analysis.*
import io.legado.app.help.readaloud.audio.AudioPlanStore
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

    @Test fun `verified contradiction splits new identities in every batch recovery order`() = runBlocking {
        val first = chapter(0, "张三正是白衣客。张三说：“我来了。”")
        val second = chapter(1, "蒙面客绝不是白衣客。蒙面客说：“请进。”")
        val firstRow = row(1, "张三").put("evidence", JSONArray().put(JSONObject()
            .put("type", "ALIAS_EQUAL").put("subject", "B1").put("object", "白衣客")
            .put("ch", 1).put("p", 1).put("quote", "张三正是白衣客")))
        val secondRow = row(2, "蒙面客").put("evidence", JSONArray().put(JSONObject()
            .put("type", "CONTRADICTION").put("subject", "B1").put("object", "白衣客")
            .put("ch", 2).put("p", 1).put("quote", "蒙面客绝不是白衣客")))
        fixture(handler = { _, exchange -> exchange.reply(batch(firstRow, secondRow)) }) { f ->
            for (path in listOf("whole", "take-first", "recover-forward", "recover-reverse")) {
                val book = "fresh-contradiction-$path"
                val runner = f.runner()
                assertTrue(f.people.charactersRaw(book).isEmpty())
                val result = runCatching {
                    runner.run(book, "src", listOf(first, second), "response", onChapterReady = { ch, ready ->
                        if (!ready && ch == 0 && path == "take-first") {
                            assertEquals(listOf(0), runner.convergeExisting(book, "src", listOf(first), AiPriority.P0_CURRENT).ready)
                        }
                        if (!ready && ch == 1 && path.startsWith("recover-")) error("stream interrupted")
                    })
                }
                if (path.startsWith("recover-")) {
                    assertEquals("stream interrupted", result.exceptionOrNull()?.message)
                    val order = if (path == "recover-reverse") listOf(second, first) else listOf(first, second)
                    for (input in order) {
                        assertEquals(listOf(input.chapterIndex), runner.convergeExisting(book, "src", listOf(input)).ready)
                    }
                } else assertEquals(listOf(0, 1), result.getOrThrow().ready)
                val builds = (0..1).map { requireNotNull(f.builds.getBuild(book, "src", it)) }
                assertTrue(builds.all { it.state == RaChapterState.IDENTITY_READY })
                val evidence = f.builds.evidence(builds[1].analysisId!!).single()
                assertTrue(evidence.verified && evidence.scope == RaEvidenceScope.MAIN_TEXT)
                assertEquals(RaEvidenceType.CONTRADICTION, evidence.type)
                assertEquals("白衣客", evidence.objectText)
                val people = f.people.charactersRaw(book).associateBy { it.displayName }
                assertEquals(setOf("张三", "蒙面客"), people.keys)
                val ids = listOf(people.getValue("张三").id, people.getValue("蒙面客").id)
                assertEquals(ids, builds.map { f.builds.segments(it.analysisId!!).single { it.seq == 1 }.characterId })
                assertEquals(listOf(setOf("张三", "白衣客"), setOf("蒙面客")), ids.map { id -> f.people.aliasesOf(id).map { it.surface }.toSet() })
                assertEquals("batch_proof", f.people.aliasesOf(ids[0]).single { it.surface == "白衣客" }.source)
                val defaults = f.people.voiceProfiles(book)
                assertEquals(ids.toSet(), defaults.map { it.characterId }.toSet())
                assertEquals(listOf(RaVoiceProfileKind.DEFAULT, RaVoiceProfileKind.DEFAULT), defaults.map { it.kind })
                ids.forEachIndexed { i, id -> f.people.setDefaultVoice(id, "voice-$i", "manual") }
                val voices = listOf(first, second).map { input ->
                    f.prepare("url", book, "src", input.chapterIndex, input.paragraphs).single { it.segment.seq == 1 }.voice?.displayName
                }
                assertEquals(listOf("voice-0", "voice-1"), voices)
                println("R4-1 $path: 张三/蒙面客, ids=2, defaults=2, voices=$voices")
            }
        }
    }

    @Test fun `every order uses stored alias evidence without publishing other chapters`() = runBlocking {
        val texts = listOf(
            chapter(0, "张三说：“我先走了。”"),
            chapter(1, "蒙面客绝不是张三。蒙面客说：“请进。”"),
            chapter(2, "陆七正是昨天请客进门的蒙面客。陆七说：“坐吧。”"),
        )
        fun evidence(type: String, ch: Int, name: String, quote: String) = JSONArray().put(JSONObject()
            .put("type", type).put("subject", "B1").put("object", name)
            .put("ch", ch).put("p", 1).put("quote", quote))
        val response = batch(row(1, "张三"),
            row(2, "蒙面客").put("evidence", evidence("CONTRADICTION", 2, "张三", "蒙面客绝不是张三")),
            row(3, "陆七").put("evidence", evidence("ALIAS_EQUAL", 3, "蒙面客", "陆七正是昨天请客进门的蒙面客")))
        fixture(handler = { _, exchange -> exchange.reply(response) }) { f ->
            val orders = texts.indices.flatMap { first -> texts.indices.filter { it != first }.map { second ->
                listOf(first, second, 3 - first - second)
            } }
            for (order in listOf(emptyList<Int>()) + orders) {
                val book = "alias-conflict-$order"
                val runner = f.runner()
                val result = runCatching { runner.run(book, "src", texts, "response", onChapterReady = { ch, ready ->
                    if (order.isNotEmpty() && ch == 2 && !ready) error("interrupted after extraction")
                }) }
                if (order.isEmpty()) assertEquals(listOf(0, 1, 2), result.getOrThrow().ready)
                else {
                    assertEquals("interrupted after extraction", result.exceptionOrNull()?.message)
                    val published = ArrayList<Int>()
                    for (index in order) {
                        val recovered = runner.convergeExisting(book, "src", listOf(texts[index]), AiPriority.P0_CURRENT,
                            onChapterReady = { ch, ready -> if (ready) published.add(ch) })
                        assertEquals(listOf(index), recovered.ready)
                        assertEquals(order.take(published.size), published)
                        assertEquals(published.toSet(), f.builds.builds(book, "src")
                            .filter { it.state == RaChapterState.IDENTITY_READY }.map { it.chapterIndex }.toSet())
                    }
                    assertEquals(order, published)
                }
                val profiles = f.people.voiceProfiles(book)
                assertEquals(2, profiles.size)
                profiles.forEachIndexed { i, profile -> f.people.setDefaultVoice(profile.characterId, "voice-$i", "manual") }
                val lines = texts.map { f.prepare("url", book, "src", it.chapterIndex, it.paragraphs).single { cue -> cue.segment.seq == 1 } }
                assertEquals(lines[1].segment.characterId, lines[2].segment.characterId)
                assertNotEquals(lines[0].segment.characterId, lines[1].segment.characterId)
                val voiceIds = lines.map { requireNotNull(it.voice).id }
                assertEquals(voiceIds[1], voiceIds[2])
                assertNotEquals(voiceIds[0], voiceIds[1])
            }
        }
    }

    @Test fun `first source switch supplements playback effects without a donor plan`() = reusedPlaybackEffects(false)
    @Test fun `first source switch supplements playback effects even with a donor plan`() = reusedPlaybackEffects(true)

    private fun reusedPlaybackEffects(played: Boolean) = runBlocking {
        val text = chapter(0, "张三推开门说：“请进。”")
        val requests = AtomicInteger()
        val effects = AtomicInteger()
        fixture("audioDirector", handler = { request, exchange ->
            if (request.getString("model") == "audioDirector") {
                effects.incrementAndGet()
                exchange.reply("""{"items":[{"para":1,"frag":1,"type":"sfx","tag":"开门","pos":"前"}]}""")
            } else {
                requests.incrementAndGet()
                exchange.reply(batch(row(1, "张三")))
            }
        }) { f ->
            val name = "shared-director-$played"
            val url = "https://fixture.invalid/$name"
            val copyUrl = "$url/b"
            val book = f.keyOf(url, "src")
            val input = RaBuildInput(f.builds.generation())
            assertEquals(listOf(0), f.runner().run(book, "src", listOf(text), "donor").ready)
            val donor = requireNotNull(f.builds.getBuild(book, "src", 0))
            if (played) f.prepare(url, book, "src", 0, text.paragraphs, bookName = name)
            assertEquals(if (played) 1 else 0, effects.get())
            assertEquals("src", f.builds.reuseAnalysis(donor.copy(sourceKey = "copy", analysisId = null),
                SpeechAnalysisPipelineV4.RESOLVER_VERSION, input))
            val id = requireNotNull(f.builds.getBuild(book, "copy", 0)?.analysisId)
            assertNull(f.direction.plan(id, PrepareChapterSpeechPlanUseCase.MODULE))
            val actor = f.builds.segments(donor.analysisId!!).single { it.seq == 1 }.characterId
            repeat(2) {
                assertEquals(actor, f.prepare(copyUrl, book, "copy", 0, text.paragraphs, bookName = name)
                    .single { it.segment.seq == 1 }.segment.characterId)
                val plan = requireNotNull(f.direction.plan(id, PrepareChapterSpeechPlanUseCase.MODULE))
                assertEquals(listOf("开门"), f.direction.audio(plan.id).map { it.tag })
                assertEquals(listOf("开门"), AudioPlanStore.load(name, copyUrl, 0)?.sfx?.map { it.tag })
            }
            assertEquals(if (played) 2 else 1, effects.get())
            assertEquals(1, requests.get())
        }
    }

    @Test fun `new body with no director plan removes only its old playback file entry`() = runBlocking {
        val first = chapter(0, "张三推门说：“请进。”")
        val second = chapter(1, "张三推门说：“坐吧。”")
        fixture("audioDirector", handler = { request, exchange ->
            exchange.reply(if (request.getString("model") == "audioDirector")
                """{"items":[{"para":1,"frag":1,"type":"sfx","tag":"开门","pos":"前"}]}"""
            else """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"张三","roletype":"核心"}]}""")
        }) { f ->
            val name = "replacement-sound"
            val url = "https://fixture.invalid/$name"
            val key = f.keyOf(url, "src")
            val pipeline = f.pipeline()
            for (text in listOf(first, second)) {
                assertNotNull(pipeline.run(url, name, key, "src", text.chapterIndex, text.paragraphs, "", ""))
                assertEquals("开门", AudioPlanStore.load(name, url, text.chapterIndex)?.sfx?.single()?.tag)
            }
            val retained = AudioPlanStore.load(name, url, 1)
            f.disableDirector()
            val revised = chapter(0, "一加一等于二。")
            val result = requireNotNull(pipeline.run(url, name, key, "src", 0, revised.paragraphs, "", ""))
            val plan = requireNotNull(f.direction.plan(result.analysis.id, PrepareChapterSpeechPlanUseCase.MODULE))
            assertTrue(f.direction.audio(plan.id).isEmpty())
            assertNull(AudioPlanStore.load(name, url, 0))
            assertEquals(retained, AudioPlanStore.load(name, url, 1))
        }
    }

    @Test fun `late foreground plans are rejected and finished plans are removed on chapter deletion`() =
        directorDeletion(whole = false, background = false)

    @Test fun `late background director supplement cannot restore plans after whole book deletion`() =
        directorDeletion(whole = true, background = true)

    private fun directorDeletion(whole: Boolean, background: Boolean) = runBlocking {
        val first = chapter(0, "张三推开门说：“请进。”")
        val second = chapter(1, "张三推开门说：“坐吧。”")
        fixture("audioDirector", handler = { request, exchange ->
            val prompt = request.getJSONArray("messages").getJSONObject(1).getString("content")
            exchange.reply(when {
                request.getString("model") == "audioDirector" ->
                    """{"items":[{"para":1,"frag":1,"type":"sfx","tag":"开门","pos":"前"}]}"""
                "=== ch=1 ===" in prompt -> batch(row(1, "张三"))
                else -> """{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"张三","roletype":"核心"}]}"""
            })
        }) { f -> coroutineScope {
            val name = "director-delete-$whole"
            val url = "https://fixture.invalid/$name"
            val key = f.keyOf(url, "src")
            val regular = f.pipeline()
            val installed = requireNotNull(regular.run(url, name, key, "src", 1, second.paragraphs, "", ""))
            val retainedPlan = requireNotNull(AudioPlanStore.load(name, url, 1))
            assertEquals("开门", retainedPlan.sfx.single().tag)
            val extracted = if (background) {
                assertEquals(listOf(0), f.runner().run(key, "src", listOf(first), "bg").ready)
                requireNotNull(f.builds.getAnalysis(key, "src", 0, first.contentHash, SpeechAnalysisPipelineV4.RESOLVER_VERSION))
            } else {
                requireNotNull(regular.run(url, name, key, "src", 0, first.paragraphs, "", "")).analysis
            }
            val candidate = CompletableDeferred<RaAnalysis>()
            val release = CompletableDeferred<Unit>()
            val gated = f.pipeline(object : RaBuildGateway by f.builds {
                override suspend fun commitDirection(analysis: RaAnalysis, input: RaBuildInput, write: suspend () -> Unit): Boolean {
                    candidate.complete(analysis)
                    release.await()
                    return f.builds.commitDirection(analysis, input, write)
                }
            })
            val pending = async {
                if (background) gated.ensureLegacyForReady(url, name, extracted, first.paragraphs)
                else gated.run(url, name, key, "src", 0, first.paragraphs, "", "", force = true)
            }
            try {
                val obsolete = withTimeout(20_000) { candidate.await() }
                if (whole) f.maintenance.deleteBook(key, name)
                else f.maintenance.deleteChapters(key, "src", name, listOf(0), false)
                release.complete(Unit)
                withTimeout(20_000) { pending.await() }
                assertNull(f.direction.latestPlan(key, 0, "PLAYBACK_BASE"))
                assertTrue(f.direction.audio("dp_${obsolete.id}_PLAYBACK_BASE").isEmpty())
                assertTrue(f.direction.audio("dp_${extracted.id}_PLAYBACK_BASE").isEmpty())
                assertNull(AudioPlanStore.load(name, url, 0))
                assertEquals(if (whole) null else retainedPlan, AudioPlanStore.load(name, url, 1))
                assertEquals(!whole, f.direction.plan(installed.analysis.id, "PLAYBACK_BASE") != null)
                assertNotNull(regular.run(url, name, f.keyOf(url, "src"), "src", 0, first.paragraphs, "", "", force = true))
                assertEquals("开门", AudioPlanStore.load(name, url, 0)?.sfx?.single()?.tag)
            } finally { release.complete(Unit); pending.cancelAndJoin() }
        } }
    }

    @Test fun `deleting chapter or book invalidates an in flight force analysis including its history`() = runBlocking {
        val seed = chapter(0, "张三说：“此前来过。”")
        val fresh = chapter(0, "沈孤鸿说：“刚到。”")
        val other = chapter(1, "庭院里落着雨。")
        fixture(handler = { request, exchange ->
            val body = request.getJSONArray("messages").getJSONObject(1).getString("content").substringAfterLast("=== 待分析文本 ===")
            val name = if ("刚到" in body) "沈孤鸿" else "张三"
            exchange.reply("""{"seqmap":{"1":"L1"},"speakers":[{"key":"L1","name":"$name","roletype":"核心"}]}""")
        }) { f ->
            for (whole in listOf(false, true)) coroutineScope {
                val url = "fixture-delete-$whole"
                val source = "src"
                val book = f.keyOf(url, source)
                val pipeline = f.pipeline()
                assertNotNull(pipeline.run(url, "fixture", book, source, 0, seed.paragraphs, "", "", skipLegacyAudio = true))
                assertNotNull(pipeline.run(url, "fixture", book, source, 1, other.paragraphs, "", "", skipLegacyAudio = true))
                val retained = f.builds.getBuild(book, source, 1)
                val gate = BeforeIdentityCommit(f.builds)
                val callbacks = AtomicInteger()
                val pending = async(Dispatchers.IO) {
                    f.pipeline(gate).run(url, "fixture", book, source, 0, fresh.paragraphs, "", "", force = true,
                        skipLegacyAudio = true, onPublished = { callbacks.incrementAndGet() })
                }
                try {
                    val candidate = withTimeout(20_000) { gate.reached.await() }.single().analysis.id
                    if (whole) f.maintenance.deleteBook(book, "fixture")
                    else f.maintenance.deleteChapters(book, source, "fixture", listOf(0), false)
                    assertNull(f.builds.getBuild(book, source, 0))
                    gate.release.complete(Unit)
                    assertNull(withTimeout(20_000) { pending.await() })
                    assertNull(f.builds.getBuild(book, source, 0))
                    assertNull(f.builds.analysisById(candidate))
                    assertEquals(if (whole) null else retained, f.builds.getBuild(book, source, 1))
                    assertTrue(f.people.charactersRaw(book).isEmpty())
                    assertTrue(f.people.aliasesOfBook(book).isEmpty())
                    assertTrue(f.people.voiceProfiles(book).isEmpty())
                    assertEquals(0, callbacks.get())
                    val remaining = f.builds.builds(book, source).map { it.chapterIndex }
                    assertEquals(if (whole) emptyList<Int>() else listOf(1), remaining)
                    val newKey = f.keyOf(url, source)
                    val rebuilt = pipeline.run(url, "fixture", newKey, source, 0, fresh.paragraphs, "", "", force = true, skipLegacyAudio = true)
                    assertEquals(RaAnalysisStatus.RESOLVED, rebuilt?.analysis?.status)
                    assertEquals("沈孤鸿", f.people.charactersRaw(newKey).single().displayName)
                    println("R4-3 whole=$whole: retained=$remaining; late builds/actors/aliases/defaults/callbacks=0; new request READY")
                } finally { gate.release.complete(Unit); pending.cancelAndJoin() }
            }
        }
    }

    @Test fun `rollback invalidates a batch chapter that has no observation yet`() = runBlocking {
        val first = chapter(0, "张三说：“到了。”")
        val last = chapter(1, "李四说：“请进。”")
        val received = CompletableDeferred<Unit>()
        val tail = CountDownLatch(1)
        fixture(handler = { _, exchange ->
            received.complete(Unit)
            check(tail.await(60, TimeUnit.SECONDS))
            exchange.reply(batch(row(1, "张三"), row(2, "李四", "B2")))
        }) { f -> coroutineScope {
            val book = "rollback-inflight"
            val worker = async(Dispatchers.IO) { f.runner().run(book, "src", listOf(first, last), "before-rollback") }
            try {
                withTimeout(45_000) { received.await() }
                assertTrue(f.builds.builds(book, "src").isEmpty())
                f.maintenance.deleteChapters(book, "src", "fixture", listOf(1), true)
                tail.countDown()
                val result = withTimeout(20_000) { worker.await() }
                assertEquals(listOf(0), result.ready)
                assertTrue(result.failed.isEmpty())
                assertTrue(result.extracted.isEmpty())
                assertNull(f.builds.getBuild(book, "src", 1))
                val analysisId = SpeechIdentity.analysisId(book, "src", 1, last.contentHash, SpeechAnalysisPipelineV4.RESOLVER_VERSION)
                assertNull(f.builds.analysisById(analysisId))
                assertEquals(listOf("张三"), f.people.charactersRaw(book).map { it.displayName })
            } finally { tail.countDown(); worker.cancelAndJoin() }
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
        private val identity = RaBookIdentityRepository(db, db.raBuildDao, db.bookDao, builds)
        private val catalog = ReadAloudCatalogRepository(db.bookDao, db.bookChapterDao, db.raBuildDao, builds)
        val maintenance = RaBookMaintenanceRepository(db, db.raBuildDao, db.raCharacterDao, ReadAloudAudioCacheRepository(app), catalog, builds)
        private val models = AiModelRepository(app)
        private val config = AnalysisConfigStore(app)
        private val ai = AiSpeechClient(models, config, AiRequestScheduler())
        private val cards = CharacterCardProjector(people, builds)
        private val resolver = IdentityResolver(people, words, cards, AiIdentityArbiter(models, ai, config, cards))
        suspend fun keyOf(url: String, source: String) =
            requireNotNull(identity.keyOf(url, source, builds.generation()))

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

        suspend fun disableDirector() {
            val config = models.load()
            assertTrue(models.save(config.copy(stages = config.stages.copy(audioDirector = emptyList()))))
        }

        suspend fun person(book: String, name: String, aliases: List<String>) = people.ensureCharacter(
            book, name, "核心", "男", "男青年", 0, aliases, "ACTIVE", "user", "default",
        )
    }

    private suspend fun fixture(stage: String? = null, handler: (JSONObject, HttpExchange) -> Unit, test: suspend (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AudioPlanStore.remember(app)
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
