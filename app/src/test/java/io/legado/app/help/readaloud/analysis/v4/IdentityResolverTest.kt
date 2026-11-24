package io.legado.app.help.readaloud.analysis.v4

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.repository.RaCharacterRepository
import io.legado.app.data.repository.WordLibraryRepository
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalMention
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaResolution
import io.legado.app.domain.model.readaloud.v4.RaVerdict
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class IdentityResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var characters: RaCharacterRepository
    private lateinit var resolver: IdentityResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val wordLibrary = WordLibraryRepository(context)
        characters = RaCharacterRepository(db, db.raCharacterDao, db.raBuildDao, wordLibrary)
        resolver = IdentityResolver(characters, wordLibrary)
    }

    @After
    fun tearDown() = db.close()

    private fun speaker(
        analysisId: String,
        localKey: String,
        primary: String,
        names: List<String>,
        chapter: Int,
    ) = RaLocalSpeaker(
        id = "$analysisId#$localKey",
        analysisId = analysisId,
        localKey = localKey,
        surfaceNames = (names + primary).distinct(),
        primaryName = primary,
        roleType = "核心",
        gender = "男",
        ageBand = "男青年",
    )

    @Test
    fun `linked speaker writes new alias so next chapter links back`() = runBlocking {
        val bookKey = "bk_test"
        val first = resolver.resolve(bookKey, 0, speaker("a1", "L1", "沈孤鸿", emptyList(), 0))
        val second = resolver.resolve(
            bookKey,
            4,
            speaker("a2", "L1", "沈孤鸿", listOf("沈七"), 4),
        )
        assertEquals(first.characterId, second.characterId)
        assertTrue(characters.aliasesOf(first.characterId).any { it.surface == "沈七" })

        val third = resolver.resolve(bookKey, 8, speaker("a3", "L1", "沈七", emptyList(), 8))
        assertEquals(first.characterId, third.characterId)
        assertEquals(1, characters.characters(bookKey).size)
    }

    @Test
    fun `generic names never trigger a link by themselves`() = runBlocking {
        val bookKey = "bk_generic"
        resolver.resolve(bookKey, 0, speaker("a1", "L1", "师兄", emptyList(), 0))
        resolver.resolve(bookKey, 1, speaker("a2", "L1", "师兄", emptyList(), 1))
        // 泛称不参与链接；V4-1 每章各自新建（路人），但不会因“泛称命中”误并到核心人物
        assertEquals(2, characters.characters(bookKey).size)
    }

    @Test
    fun `multiple candidates without arbiter create independent provisional`() = runBlocking {
        val bookKey = "bk_multi"
        val a = characters.ensureCharacter(
            bookKey, "甲", "核心", "男", "男青年", 0, listOf("老三"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "乙", "核心", "男", "男青年", 1, listOf("老三"), "ACTIVE", "ai", "inferred",
        )
        repeat(5) { chapter ->
            val result = resolver.resolve(
                bookKey,
                chapter + 10,
                speaker("a${chapter + 10}", "L1", "老三", emptyList(), chapter + 10),
            )
            // A 策略：多候选且无裁决能力时不把未知者吸附到已有 ACTIVE
            assertTrue(result.characterId != a.id)
            assertEquals(RaResolution.PROVISIONAL, result.resolution)
        }
    }

    private fun evidence(
        bookKey: String,
        speaker: RaLocalSpeaker,
        type: RaEvidenceType,
        objectText: String,
    ) = RaEvidence(
        id = "ev_${type.storageValue}_${speaker.localKey}",
        bookKey = bookKey,
        analysisId = speaker.analysisId,
        sourceChapter = 99,
        paragraphIndex = 0,
        start = 0,
        end = 1,
        excerpt = "原文",
        type = type,
        subjectLocalId = speaker.id,
        objectText = objectText,
        scope = RaEvidenceScope.MAIN_TEXT,
        verified = true,
    )

    private fun fakeArbiter(answer: (String) -> IdentityAnswer) = object : IdentityArbiter {
        override suspend fun decide(
            bookKey: String,
            questions: List<IdentityQuestion>,
        ): List<IdentityAnswer> = questions.map { answer(it.key) }
    }

    @Test
    fun `alias equal evidence links existing character`() = runBlocking {
        val bookKey = "bk_evidence"
        val existing = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val speaker = speaker("a1", "L1", "沈七", emptyList(), 99)
        val result = resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = listOf(evidence(bookKey, speaker, RaEvidenceType.ALIAS_EQUAL, "青衫客")),
        )
        assertEquals(existing.id, result.resolutions["L1"]?.characterId)
    }

    @Test
    fun `contradiction blocks fast link`() = runBlocking {
        val bookKey = "bk_contra"
        val existing = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("沈七"), "ACTIVE", "ai", "inferred",
        )
        val speaker = speaker("a1", "L1", "沈七", emptyList(), 99)
        val result = resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = listOf(evidence(bookKey, speaker, RaEvidenceType.CONTRADICTION, "沈孤鸿")),
        )
        assertTrue(result.resolutions["L1"]?.characterId != existing.id)
    }

    @Test
    fun `same verdict links the chosen candidate`() = runBlocking {
        val bookKey = "bk_same"
        val target = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "林雪儿", "核心", "女", "少女", 1, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val resolverWithArbiter = IdentityResolver(
            characters,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            fakeArbiter { key ->
                IdentityAnswer(key, RaVerdict.SAME, target.id, 0.9f, "证据充分", emptyList())
            },
        )
        val speaker = speaker("a1", "L1", "青衫客", emptyList(), 99)
        val result = resolverWithArbiter.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = emptyList(),
        )
        assertEquals(target.id, result.resolutions["L1"]?.characterId)
    }

    @Test
    fun `different verdict creates new character and cannot links`() = runBlocking {
        val bookKey = "bk_diff"
        val a = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "林雪儿", "核心", "女", "少女", 1, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val resolverWithArbiter = IdentityResolver(
            characters,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            fakeArbiter { key ->
                IdentityAnswer(key, RaVerdict.DIFFERENT, null, 0.9f, "明确不是同一人", emptyList())
            },
        )
        val speaker = speaker("a1", "L1", "青衫客", emptyList(), 99)
        val result = resolverWithArbiter.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = emptyList(),
        )
        val created = result.resolutions["L1"]?.characterId
        assertTrue(created != null && created != a.id)
        assertTrue(
            characters.linksOfBook(bookKey).any {
                it.kind == io.legado.app.domain.model.readaloud.v4.RaLinkKind.CANNOT &&
                    (it.aCharacterId == created || it.bCharacterId == created)
            },
        )
    }

    @Test
    fun `unknown verdict creates a provisional character`() = runBlocking {
        val bookKey = "bk_unknown"
        characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "林雪儿", "核心", "女", "少女", 1, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val resolverWithArbiter = IdentityResolver(
            characters,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            fakeArbiter { key ->
                IdentityAnswer(key, RaVerdict.UNKNOWN, null, 0.4f, "证据不足", emptyList())
            },
        )
        val speaker = speaker("a1", "L1", "青衫客", emptyList(), 99)
        val result = resolverWithArbiter.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = emptyList(),
        )
        assertEquals(RaResolution.PROVISIONAL, result.resolutions["L1"]?.resolution)
        assertEquals("PROVISIONAL", characters.character(result.resolutions["L1"]!!.characterId)?.status?.storageValue)
    }

    @Test
    fun `two same-name speakers in one chapter stay two characters`() = runBlocking {
        val bookKey = "bk_same_chapter"
        val existing = characters.ensureCharacter(
            bookKey, "沈七", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val result = resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 1,
            chapterLabel = "第2章",
            speakers = listOf(
                speaker("a1", "L1", "沈七", emptyList(), 1),
                speaker("a1", "L2", "沈七", emptyList(), 1),
            ),
            mentions = emptyList(),
            evidence = emptyList(),
        )
        assertEquals(existing.id, result.resolutions["L1"]?.characterId)
        assertTrue(result.resolutions["L2"]?.characterId != existing.id)
    }

    @Test
    fun `relation target is not a candidate for the speaker`() = runBlocking {
        val bookKey = "bk_relation"
        val target = characters.ensureCharacter(
            bookKey, "林雪儿", "核心", "女", "少女", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val speaker = speaker("a1", "L1", "师兄", emptyList(), 1)
        val result = resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 1,
            chapterLabel = "第2章",
            speakers = listOf(speaker),
            mentions = emptyList(),
            evidence = listOf(evidence(bookKey, speaker, RaEvidenceType.RELATION, "林雪儿|师兄妹")),
        )
        assertTrue(result.resolutions["L1"]?.characterId != target.id)
    }

    @Test
    fun `ambiguous mention does not attach aliases to an arbitrary character`() = runBlocking {
        val bookKey = "bk_mention"
        characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("沈公子"), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "沈孤雁", "核心", "男", "男青年", 0, listOf("沈公子"), "ACTIVE", "ai", "inferred",
        )
        val mention = RaLocalMention(
            id = "a5#M1",
            analysisId = "a5",
            localKey = "M1",
            surfaceNames = listOf("沈公子", "沈大侠"),
            primaryName = "沈公子",
        )
        resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 5,
            chapterLabel = "第6章",
            speakers = listOf(speaker("a5", "L1", "路人甲", emptyList(), 5)),
            mentions = listOf(mention),
            evidence = emptyList(),
        )
        assertTrue(characters.charactersRaw(bookKey).none { c ->
            characters.aliasesOf(c.id).any { it.surface == "沈大侠" }
        })
    }

    @Test
    fun `different verdict writes cannot and stops repeated arbitration`() = runBlocking {
        val bookKey = "bk_cannot"
        val a = characters.ensureCharacter(
            bookKey, "老三", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "老三", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        var asked = 0
        val arbiter = fakeArbiter {
            asked++
            IdentityAnswer(it, RaVerdict.DIFFERENT, null, 0.9f, "不同人", emptyList())
        }
        val withArbiter = IdentityResolver(
            characters,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            arbiter,
        )
        withArbiter.resolveChapter(
            bookKey, 2, "第3章", listOf(speaker("a2", "L1", "老三", emptyList(), 2)),
            emptyList(), emptyList(),
        )
        withArbiter.resolveChapter(
            bookKey, 3, "第4章", listOf(speaker("a3", "L1", "老三", emptyList(), 3)),
            emptyList(), emptyList(),
        )
        assertEquals(1, asked)
        assertTrue(characters.linksOfBook(bookKey).any { it.kind == io.legado.app.domain.model.readaloud.v4.RaLinkKind.CANNOT && (it.aCharacterId == a.id || it.bCharacterId == a.id) })
    }
}
