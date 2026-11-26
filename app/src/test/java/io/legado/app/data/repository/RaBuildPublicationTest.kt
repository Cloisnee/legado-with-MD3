package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.domain.gateway.RaBuildPublication
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.v4.*
import io.legado.app.help.readaloud.analysis.v4.ObservationAssembly
import io.legado.app.help.readaloud.analysis.v4.SpeakerBinder
import io.legado.app.help.readaloud.analysis.v4.Stage2Payload
import io.legado.app.help.readaloud.analysis.v4.Stage2Speaker
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.playback.SpeechVoiceSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RaBuildPublicationTest {
    private lateinit var db: AppDatabase
    private lateinit var builds: RaBuildRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
    }
    @After fun close() = db.close()

    private fun analysis(id: String, hash: String, status: RaAnalysisStatus = RaAnalysisStatus.EXTRACTED) =
        RaAnalysis(id, "bk", "src", 0, hash, "v4.2", status,
            observationJson = "{\"speakers\":[{\"genderSource\":\"explicit\"}],\"mentions\":[],\"contexts\":{\"B1\":\"原文\"}}")
    private fun build(analysis: RaAnalysis, epoch: Long) = RaChapterBuild(
        "bk", "src", 0, analysis.contentHash,
        if (analysis.status == RaAnalysisStatus.RESOLVED) RaChapterState.IDENTITY_READY else RaChapterState.EXTRACTED,
        analysis.id, updatedAt = epoch,
    )
    private suspend fun save(a: RaAnalysis, epoch: Long) = builds.saveAnalysisBundle(
        a, emptyList(), emptyList(), emptyList(), emptyList(), build(a, epoch),
    )

    @Test fun `old content completing late remains historical and cannot replace current pointer`() = runBlocking {
        val newest = analysis("new", "newhash", RaAnalysisStatus.RESOLVED)
        save(newest, 200)
        save(analysis("old", "oldhash"), 100)
        assertEquals("new", builds.getBuild("bk", "src", 0)?.analysisId)
        assertEquals("newhash", builds.getBuild("bk", "src", 0)?.contentHash)
        assertNotNull(builds.analysisById("old"))
    }
    @Test fun `late status transitions and bundle cannot republish an older chapter in Room`() = runBlocking {
        val old = analysis("old", "oldhash")
        assertEquals(RaBuildPublication.PUBLISHED, save(old, 100))
        val suspended = CompletableDeferred<RaChapterBuild>()
        val resume = CompletableDeferred<Unit>()
        val late = async {
            val expected = builds.getBuild("bk", "src", 0)!!
            suspended.complete(expected)
            resume.await()
            val stale = builds.transitionBuild(expected, expected.copy(state = RaChapterState.STALE, updatedAt = 300))
            val missing = builds.transitionBuild(expected, expected.copy(state = RaChapterState.NO_CONTENT, updatedAt = 301))
            val oldResolution = builds.saveAnalysisBundle(
                old.copy(status = RaAnalysisStatus.RESOLVED), emptyList(), emptyList(), emptyList(), emptyList(),
                expected.copy(state = RaChapterState.IDENTITY_READY),
                attributes = listOf(RaAttributeObservation("attr", "bk", "chr", "gender", "男", 0, source = "explicit")),
                relations = listOf(RaRelationObservation("rel", "bk", "chr", "旧关系")),
                expectedExtraction = expected,
            )
            Triple(stale, missing, oldResolution)
        }
        val snapshot = withTimeout(2_000) { suspended.await() }
        try {
            val noContent = snapshot.copy(state = RaChapterState.NO_CONTENT, updatedAt = 110)
            assertTrue(builds.transitionBuild(snapshot, noContent))
            val new = analysis("new", "newhash", RaAnalysisStatus.RESOLVED)
            val current = build(new, 200)
            val voices = object : SpeechVoiceSource {
                override suspend fun byTag(tag: String) = ReadAloudVoice(tag, "tts_server", "e", tag, tag)
                override suspend fun narrator() = ReadAloudVoice("n", "tts_server", "e", "n", "旁白")
                override suspend fun defaultDialog(gender: String, seed: Int) = null
            }
            val characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao,
                WordLibraryRepository(ApplicationProvider.getApplicationContext()))
            val chosen = characters.ensureCharacter("bk", "张三", "核心", "女", "女青年", 0,
                emptyList(), "ACTIVE", "ai", "default")
            characters.saveVoiceProfile(RaVoiceProfile(RaIds.voiceProfile(), "bk", chosen.id,
                RaVoiceProfileKind.DEFAULT, "默认", voiceTag = "new-voice"))
            val line = RaSegment("s-new", new.id, "bk", 0, 0, 0, 2, 0, "新文", SpeechRoleType.Character,
                seq = 1, characterId = chosen.id)
            val attr = RaAttributeObservation("attr", "bk", chosen.id, "gender", "女", 0, source = "explicit")
            val relation = RaRelationObservation("rel", "bk", chosen.id, "新关系")
            assertEquals(RaBuildPublication.PUBLISHED, builds.saveAnalysisBundle(new, listOf(line), emptyList(),
                emptyList(), emptyList(), current, listOf(attr), listOf(relation)))
            assertFalse(builds.transitionBuild(noContent, noContent.copy(state = RaChapterState.CACHED, updatedAt = 310)))
            resume.complete(Unit)
            assertEquals(Triple(false, false, RaBuildPublication.IGNORED), withTimeout(2_000) { late.await() })
            val historical = analysis("historical", "older", RaAnalysisStatus.RESOLVED)
            assertEquals(RaBuildPublication.HISTORY_ONLY, builds.saveAnalysisBundle(historical, emptyList(),
                emptyList(), emptyList(), emptyList(), build(historical, 90),
                listOf(attr.copy(value = "男")), listOf(relation.copy(relation = "更早关系"))))
            assertEquals(current, builds.getBuild("bk", "src", 0))
            assertNull(builds.getAnalysis("bk", "src", 0, "oldhash", "v4.2"))
            assertEquals(new.id, builds.getAnalysis("bk", "src", 0, "newhash", "v4.2")?.id)
            assertNotNull(builds.analysisById(old.id))
            assertNotNull(builds.analysisById(historical.id))
            assertEquals("女", db.raCharacterDao.attributesOfBook("bk").single { it.id == "attr" }.value)
            assertEquals("新关系", db.raCharacterDao.relationsOfBook("bk").single { it.id == "rel" }.relation)
            assertEquals("new-voice", BuildSpeechPlanUseCase(characters, voices)("bk",
                builds.segmentsOfChapter("bk", "src", 0)).single().voice?.displayName)
        } finally {
            resume.complete(Unit)
        }
    }

    @Test fun `exact snapshot survives database reopen-style read`() = runBlocking {
        val a = analysis("a", "h")
        save(a, 100)
        val restored = builds.analysisById("a")!!
        assertEquals(a.observationJson, restored.observationJson)
        assertTrue(restored.observationJson.contains("explicit"))
        assertTrue(restored.observationJson.contains("原文"))
    }
    @Test fun `duplicate extracted result cannot downgrade resolved foreign keys`() = runBlocking {
        val a = analysis("a", "h", RaAnalysisStatus.RESOLVED)
        builds.saveAnalysisBundle(a, listOf(RaSegment(
            "s", "a", "bk", 0, 0, 0, 2, 0, "你好", SpeechRoleType.Character,
            seq = 1, localSpeakerId = "a#B1", characterId = "chr",
        )), emptyList(), emptyList(), emptyList(), build(a, 200))
        save(a.copy(status = RaAnalysisStatus.EXTRACTED), 100)
        assertEquals(RaAnalysisStatus.RESOLVED, builds.analysisById("a")?.status)
        assertEquals("chr", builds.segments("a").single().characterId)
    }
    @Test fun `single and batch assembly persist two voices and narrator with same identities`() = runBlocking {
        val words = listOf(CanonicalSpeechParagraph(0, "甲乙丙", 0))
        val raw = ObservationAssembly.assemble(words, listOf(
            ObservationAssembly.SpRange(0, 0, 1), ObservationAssembly.SpRange(0, 1, 2),
            ObservationAssembly.SpRange(0, 2, 3),
        ))
        val payload = Stage2Payload(mapOf(1 to "B1", 2 to "B2", 3 to "N"), listOf(
            Stage2Speaker("B1", "张三"), Stage2Speaker("B2", "李四"),
        ), emptyList(), emptyList())
        val characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao,
            WordLibraryRepository(ApplicationProvider.getApplicationContext()))
        val a = characters.ensureCharacter("bk", "张三", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "default")
        val b = characters.ensureCharacter("bk", "李四", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "default")
        for ((c, tag) in listOf(a to "voiceA", b to "voiceB")) characters.saveVoiceProfile(
            RaVoiceProfile(RaIds.voiceProfile(), "bk", c.id, RaVoiceProfileKind.DEFAULT, "默认", voiceTag = tag),
        )
        val speakers = ObservationAssembly.buildLocalSpeakers("a", payload.speakers, true, "response")
        val assembled = SpeakerBinder.bind(ObservationAssembly.applyStage2(raw, payload.seqMap), speakers,
            mapOf("B1" to a.id, "B2" to b.id), payload.seqMap)
        val resolved = analysis("a", "h", RaAnalysisStatus.RESOLVED)
        builds.saveAnalysisBundle(resolved, assembled.mapIndexed { n, s -> s.copy(
            id = "s$n", analysisId = "a", bookKey = "bk", chapterIndex = 0,
        ) }, speakers, emptyList(), emptyList(), build(resolved, 200))
        val voices = object : SpeechVoiceSource {
            override suspend fun byTag(tag: String) = ReadAloudVoice(tag, "tts_server", "e", tag, tag)
            override suspend fun narrator() = ReadAloudVoice("n", "tts_server", "e", "n", "旁白")
            override suspend fun defaultDialog(gender: String, seed: Int) = ReadAloudVoice("d", "tts_server", "e", "d", "默认")
        }
        val stored = builds.segmentsOfChapter("bk", "src", 0)
        val plan = BuildSpeechPlanUseCase(characters, voices)("bk", stored)
        assertEquals(listOf("voiceA", "voiceB", "旁白"), plan.map { it.voice?.displayName })
        assertEquals(listOf(1, 2, 0), stored.map { it.seq })
        assertTrue(stored.take(2).all { it.characterId != null && it.localSpeakerId != null })
        assertEquals(SpeechRoleType.Narrator, stored.last().roleType)
    }
}
