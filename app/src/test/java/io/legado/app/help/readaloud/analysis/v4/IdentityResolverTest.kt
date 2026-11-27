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
import kotlinx.coroutines.async
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
        roleType: String = "核心",
        batchEntityId: String? = null,
    ) = RaLocalSpeaker(
        id = "$analysisId#$localKey",
        analysisId = analysisId,
        localKey = localKey,
        surfaceNames = (names + primary).distinct(),
        primaryName = primary,
        roleType = roleType,
        gender = "男",
        ageBand = "男青年",
        batchEntityId = batchEntityId,
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

    @Test
    fun `cannot pair appearing in same chapter links each original character`() = runBlocking {
        val bookKey = "bk_cannot_same_chapter"
        val a = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val b = characters.ensureCharacter(
            bookKey, "林雪儿", "核心", "女", "少女", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        characters.saveCannotLink(bookKey, a.id, b.id, "user")
        val result = resolver.resolveChapter(
            bookKey, 2, "第3章",
            listOf(speaker("a2", "L1", "沈孤鸿", emptyList(), 2), speaker("a2", "L2", "林雪儿", emptyList(), 2)),
            emptyList(), emptyList(),
        )
        assertEquals(a.id, result.resolutions["L1"]?.characterId)
        assertEquals(b.id, result.resolutions["L2"]?.characterId)
    }

    @Test
    fun `second same-name speaker in one chapter stays provisional`() = runBlocking {
        val bookKey = "bk_same_chapter_provisional"
        val existing = characters.ensureCharacter(
            bookKey, "沈七", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val result = resolver.resolveChapter(
            bookKey, 1, "第2章",
            listOf(speaker("a1", "L1", "沈七", emptyList(), 1), speaker("a1", "L2", "沈七", emptyList(), 1)),
            emptyList(), emptyList(),
        )
        assertEquals(existing.id, result.resolutions["L1"]?.characterId)
        assertEquals(RaResolution.PROVISIONAL, result.resolutions["L2"]?.resolution)
    }

    @Test
    fun `unknown same-name group does not grow after recheck limit`() = runBlocking {
        val bookKey = "bk_unknown_group"
        characters.ensureCharacter(
            bookKey, "沈七", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        characters.ensureCharacter(
            bookKey, "沈七", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val arbiter = fakeArbiter { key ->
            IdentityAnswer(key, RaVerdict.UNKNOWN, null, 0.3f, "不确定", emptyList())
        }
        val withArbiter = IdentityResolver(
            characters,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            arbiter,
        )
        repeat(8) { chapter ->
            withArbiter.resolveChapter(
                bookKey, chapter + 2, "第${chapter + 3}章",
                listOf(speaker("a${chapter + 2}", "L1", "沈七", emptyList(), chapter + 2)),
                emptyList(), emptyList(),
            )
        }
        assertTrue(characters.provisionalCharacters(bookKey).size <= 1)
    }

    @Test
    fun `passerby with same name in two chapters creates two characters`() = runBlocking {
        val bookKey = "bk_passersby"
        val first = resolver.resolve(
            bookKey, 0, speaker("p1", "L1", "弟子A", emptyList(), 0, roleType = "路人"),
        )
        val second = resolver.resolve(
            bookKey, 1, speaker("p2", "L1", "弟子A", emptyList(), 1, roleType = "路人"),
        )
        assertTrue(first.characterId != second.characterId)
        assertEquals(2, characters.characters(bookKey).size)
    }

    @Test
    fun `passerby still links on explicit alias equal evidence`() = runBlocking {
        val bookKey = "bk_passersby_alias"
        val existing = characters.ensureCharacter(
            bookKey, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val passerby = speaker("p1", "L1", "店伙计", emptyList(), 99, roleType = "路人")
        val result = resolver.resolveChapter(
            bookKey = bookKey,
            chapterIndex = 99,
            chapterLabel = "第100章",
            speakers = listOf(passerby),
            mentions = emptyList(),
            evidence = listOf(evidence(bookKey, passerby, RaEvidenceType.ALIAS_EQUAL, "青衫客")),
        )
        assertEquals(existing.id, result.resolutions["L1"]?.characterId)
    }

    @Test
    fun `batch entity links across chapters as one character`() = runBlocking {
        val bookKey = "bk_batch_entity"
        val first = speaker("c1", "B1", "青衫客", emptyList(), 92, batchEntityId = "B1")
        val second = speaker("c2", "B1", "沈孤鸿", emptyList(), 93, batchEntityId = "B1")
        val out = resolver.resolveBatch(
            bookKey,
            listOf(
                IdentityResolver.BatchChapterInput(92, "第93章", listOf(first), emptyList(), batchId = "batch-1"),
                IdentityResolver.BatchChapterInput(93, "第94章", listOf(second), emptyList(), batchId = "batch-1"),
            ),
        )
        assertEquals(1, characters.characters(bookKey).size)
        assertEquals(out[first.id]?.characterId, out[second.id]?.characterId)
    }

    @Test
    fun `ambiguous batch peer cannot bypass a user cannot link`() = runBlocking {
        val book = "bk_batch_cannot"
        suspend fun person(name: String) = characters.ensureCharacter(
            book, name, "核心", "男", "男青年", 0, listOf("蒙面客"), "ACTIVE", "user", "default",
        )
        val a = person("张三")
        val b = person("李四")
        characters.saveCannotLink(book, a.id, b.id, "user")
        val first = speaker("c1", "B1", "张三", emptyList(), 0, batchEntityId = "B1").copy(
            characterId = a.id, resolution = RaResolution.RESOLVED,
        )
        val second = speaker("c2", "B1", "蒙面客", emptyList(), 1, batchEntityId = "B1")
        val resolved = resolver.resolveBatch(book, listOf(
            IdentityResolver.BatchChapterInput(0, "第1章", listOf(first), emptyList(), batchId = "response", publish = false),
            IdentityResolver.BatchChapterInput(1, "第2章", listOf(second), emptyList(), batchId = "response"),
        ))
        assertEquals(a.id, resolved.getValue(first.id).characterId)
        assertTrue(resolved.getValue(second.id).characterId != a.id)
        assertEquals("user", characters.linksOfBook(book).single().source)
    }

    @Test
    fun `batch passersby with same name get separate characters`() = runBlocking {
        val bookKey = "bk_batch_passersby"
        val first = speaker("p1", "B1", "弟子A", emptyList(), 0, roleType = "路人", batchEntityId = "B1")
        val second = speaker("p2", "B2", "弟子A", emptyList(), 1, roleType = "路人", batchEntityId = "B2")
        val out = resolver.resolveBatch(
            bookKey,
            listOf(
                IdentityResolver.BatchChapterInput(0, "第1章", listOf(first), emptyList()),
                IdentityResolver.BatchChapterInput(1, "第2章", listOf(second), emptyList()),
            ),
        )
        assertTrue(out[first.id]?.characterId != out[second.id]?.characterId)
        assertEquals(2, characters.characters(bookKey).size)
    }
    @Test
    fun `parallel chapters publish one core identity and one default profile`() = runBlocking {
        val bookKey = "bk_concurrent"
        val results = kotlinx.coroutines.coroutineScope {
            (0..3).map { chapter ->
                async(kotlinx.coroutines.Dispatchers.Default) {
                    resolver.resolve(bookKey, chapter,
                        speaker("parallel$chapter", "L1", "沈孤鸿", emptyList(), chapter))
                }
            }.map { it.await() }
        }
        assertEquals(1, results.map { it.characterId }.distinct().size)
        assertEquals(1, characters.characters(bookKey).size)
        val casting = VoiceCastingService(
            io.legado.app.data.repository.ReadAloudVoicePoolRepository(ApplicationProvider.getApplicationContext()),
            characters,
        )
        kotlinx.coroutines.coroutineScope {
            results.map { result ->
                async(kotlinx.coroutines.Dispatchers.Default) {
                    casting.ensureDefaultProfiles(bookKey, listOf(characters.character(result.characterId)!!), "并发章")
                }
            }.forEach { it.await() }
        }
        assertEquals(1, characters.voiceProfiles(bookKey).size)
    }

    @Test
    fun `same bkey in same response never merges passersby across chapters`() = runBlocking {
        val first = speaker("p1", "B1", "弟子A", emptyList(), 2, "路人", "response#B1")
        val second = speaker("p2", "B1", "弟子A", emptyList(), 5, "路人", "response#B1")
        val out = resolver.resolveBatch("bk_road_same_key", listOf(
            IdentityResolver.BatchChapterInput(2, "第3章", listOf(first), emptyList()),
            IdentityResolver.BatchChapterInput(5, "第6章", listOf(second), emptyList()),
        ))
        assertTrue(out[first.id]?.characterId != out[second.id]?.characterId)
        val names = characters.charactersRaw("bk_road_same_key").map { it.displayName }.toSet()
        assertEquals(setOf("弟子A【第3章】", "弟子A【第6章】"), names)
    }

    @Test
    fun `identity published between fast lookup and recall links before entering unknown arbitration`() = runBlocking {
        val bookKey = "bk_publish_during_recall"
        var aliasLookups = 0
        var publishedId: String? = null
        val gateway = object : io.legado.app.domain.gateway.RaCharacterGateway by characters {
            override suspend fun charactersByAliases(
                bookKey: String,
                normalizedNames: Collection<String>,
            ): List<io.legado.app.domain.model.readaloud.v4.RaCharacterAlias> {
                aliasLookups++
                if (aliasLookups == 2) {
                    // 第1次快链查询为空，第2次（召回）前模拟另一章刚完成原子发布。
                    publishedId = characters.ensureCharacter(
                        bookKey, "沈孤鸿", "核心", "男", "男青年", 0,
                        emptyList(), "ACTIVE", "ai", "default",
                    ).id
                }
                return characters.charactersByAliases(bookKey, normalizedNames)
            }
        }
        val localResolver = IdentityResolver(
            gateway, WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
        )
        val result = localResolver.resolve(bookKey, 1,
            speaker("published-second", "L1", "沈孤鸿", emptyList(), 1))
        assertEquals(publishedId, result.characterId)
        assertEquals(RaResolution.RESOLVED, result.resolution)
        assertEquals(1, characters.charactersRaw(bookKey).size)
        assertTrue(characters.provisionalCharacters(bookKey).isEmpty())
    }

}
