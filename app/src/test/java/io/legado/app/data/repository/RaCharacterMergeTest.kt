package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.domain.model.readaloud.v4.RaEvidence
import io.legado.app.domain.model.readaloud.v4.RaEvidenceScope
import io.legado.app.domain.model.readaloud.v4.RaEvidenceType
import io.legado.app.domain.model.readaloud.v4.RaLocalSpeaker
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfile
import io.legado.app.domain.model.readaloud.v4.RaVoiceProfileKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RaCharacterMergeTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RaCharacterRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RaCharacterRepository(
            db, db.raCharacterDao, db.raBuildDao, WordLibraryRepository(context),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `merge moves aliases and segment foreign keys without rewriting text`() = runBlocking {
        val from = repository.ensureCharacter(
            BOOK, "沈七", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val to = repository.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp", bookKey = BOOK, characterId = to.id, kind = RaVoiceProfileKind.DEFAULT,
                label = "默认", voiceTag = "tag",
            ),
        )
        db.raBuildDao.upsertBuild(
            RaChapterBuildEntity(
                bookKey = BOOK, sourceKey = "s", chapterIndex = 2, contentHash = "h",
                state = "IDENTITY_READY", analysisId = "a",
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a", bookKey = BOOK, sourceKey = "s", chapterIndex = 2,
                contentHash = "h", resolverVersion = "v4.1", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertSegments(
            listOf(
                RaSegmentEntity(
                    id = "seg", analysisId = "a", bookKey = BOOK, chapterIndex = 2,
                    paragraphIndex = 0, start = 0, end = 2, chapterPosition = 0,
                    text = "原文", roleType = "character", surfaceName = "青衫客",
                    characterId = from.id,
                ),
            ),
        )

        repository.mergeCharacters(from.id, to.id, "test")

        val segment = db.raBuildDao.segments("a").single()
        assertEquals(to.id, segment.characterId)
        assertEquals("青衫客", segment.surfaceName)
        assertEquals("原文", segment.text)
        assertNotNull(db.raCharacterDao.redirect(from.id))
        assertEquals(
            to.id,
            db.raCharacterDao.aliasesOf(to.id).firstOrNull { it.surface == "青衫客" }?.characterId,
        )
        assertEquals(1, db.raCharacterDao.character(to.id)?.appearanceCount)
        val links = db.raCharacterDao.linksOfBook(BOOK)
        assertTrue(links.any {
            it.kind == "SAME" &&
                it.aCharacterId == minOf(from.id, to.id) &&
                it.bCharacterId == maxOf(from.id, to.id)
        })
        assertTrue(links.none { it.aCharacterId == it.bCharacterId })
    }

    @Test
    fun `ai merge snapshot restores surviving segments and aliases on rollback`() = runBlocking {
        val from = repository.ensureCharacter(
            BOOK, "沈七", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val to = repository.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp_from", bookKey = BOOK, characterId = from.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "from-tag",
                createdAt = 1L,
            ),
        )
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp_to", bookKey = BOOK, characterId = to.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "to-tag",
                createdAt = 2L,
            ),
        )
        // 保留章节 3 的台词/章内键：合并后应能按快照恢复
        db.raBuildDao.upsertBuild(
            RaChapterBuildEntity(
                bookKey = BOOK, sourceKey = "s", chapterIndex = 3, contentHash = "h3",
                state = "IDENTITY_READY", analysisId = "a3",
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a3", bookKey = BOOK, sourceKey = "s", chapterIndex = 3,
                contentHash = "h3", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertSegments(
            listOf(
                RaSegmentEntity(
                    id = "seg3", analysisId = "a3", bookKey = BOOK, chapterIndex = 3,
                    paragraphIndex = 0, start = 0, end = 2, chapterPosition = 0,
                    text = "原文", roleType = "character", characterId = from.id,
                    localSpeakerId = "ls3",
                ),
            ),
        )
        db.raBuildDao.upsertLocalSpeakers(
            listOf(
                RaLocalSpeakerEntity(
                    id = "ls3", analysisId = "a3", localKey = "L1",
                    surfaceNames = "[\"沈七\"]", primaryName = "沈七",
                    resolution = "RESOLVED", characterId = from.id,
                ),
            ),
        )
        // 回滚区证据：章节 2 的 e1 是本次 AI 合并的依据
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a2", bookKey = BOOK, sourceKey = "s", chapterIndex = 2,
                contentHash = "h2", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertEvidence(
            listOf(
                RaEvidenceEntity(
                    id = "e1", bookKey = BOOK, analysisId = "a2",
                    sourceChapter = 2, paragraphIndex = 0, start = 0, end = 1,
                    excerpt = "沈七即沈孤鸿", type = "ALIAS_EQUAL",
                ),
            ),
        )
        repository.mergeCharactersAsAi(from.id, to.id, "AI 复审确认同人", listOf("e1"), "d1")
        assertEquals(to.id, db.raBuildDao.segments("a3").single().characterId)
        val redirect = db.raCharacterDao.redirect(from.id)
        assertNotNull(redirect)
        assertTrue(redirect!!.reason.startsWith("ai_merge|"))
        assertTrue(db.raCharacterDao.linksOfBook(BOOK).any {
            it.kind == "SAME" && it.decisionId == "d1" && it.evidenceIds.contains("e1")
        })
        // 合并后新增的用户别名/声线，回滚不得删除
        repository.attachAliases(BOOK, to.id, listOf("合并后新别名"), 4, "user")
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp_user", bookKey = BOOK, characterId = to.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "用户新声线", voiceTag = "user-tag",
            ),
        )

        RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
            .deleteChapters(BOOK, "s", listOf(2))

        assertEquals(from.id, db.raBuildDao.segments("a3").single().characterId)
        assertEquals(from.id, db.raBuildDao.localSpeakers("a3").single().characterId)
        assertEquals("ACTIVE", db.raCharacterDao.character(from.id)?.status)
        assertTrue(db.raCharacterDao.aliasesOf(from.id).any { it.surface == "青衫客" })
        assertTrue(db.raCharacterDao.aliasesOf(to.id).none { it.surface == "青衫客" })
        assertEquals(
            "from-tag",
            db.raCharacterDao.voiceProfilesOf(from.id).firstOrNull { it.kind == "DEFAULT" }?.voiceTag,
        )
        assertEquals(
            "to-tag",
            db.raCharacterDao.voiceProfilesOf(to.id).firstOrNull { it.kind == "DEFAULT" }?.voiceTag,
        )
        assertTrue(db.raCharacterDao.aliasesOf(to.id).any { it.surface == "合并后新别名" })
        assertTrue(db.raCharacterDao.voiceProfilesOf(to.id).any { it.id == "vp_user" })
    }

    companion object {
        private const val BOOK = "bk_merge"
    }

    @Test
    fun `new alias evidence outside old candidates merges provisional and moves old lines`() = runBlocking {
        val a = repository.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 0, listOf("青衫客"), "ACTIVE", "ai", "inferred",
        )
        val p = repository.ensureCharacter(
            BOOK, "沈七", "核心", "男", "男青年", 3, emptyList(), "PROVISIONAL", "ai", "inferred",
        )
        db.raBuildDao.upsertBuild(
            RaChapterBuildEntity(
                bookKey = BOOK, sourceKey = "s", chapterIndex = 3, contentHash = "h3",
                state = "IDENTITY_READY", analysisId = "a3",
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a3", bookKey = BOOK, sourceKey = "s", chapterIndex = 3,
                contentHash = "h3", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertSegments(
            listOf(
                RaSegmentEntity(
                    id = "seg3", analysisId = "a3", bookKey = BOOK, chapterIndex = 3,
                    paragraphIndex = 0, start = 0, end = 2, chapterPosition = 0,
                    text = "原文", roleType = "character", characterId = p.id,
                    localSpeakerId = "ls3",
                ),
            ),
        )
        db.raBuildDao.upsertLocalSpeakers(
            listOf(
                RaLocalSpeakerEntity(
                    id = "ls3", analysisId = "a3", localKey = "L1",
                    surfaceNames = "[\"沈七\"]", primaryName = "沈七",
                    resolution = "PROVISIONAL", characterId = p.id,
                ),
            ),
        )
        db.raCharacterDao.insertDecision(
            RaIdentityDecisionEntity(
                id = "dP", bookKey = BOOK, localSpeakerId = "ls3",
                // 目标 A 不在旧候选集合中：当前 ALIAS_EQUAL 是更强的新证据
                candidateIds = "[\"chr_other\"]", verdict = "UNKNOWN",
                chosenCharacterId = p.id,
            ),
        )
        val speaker = RaLocalSpeaker(
            id = "a9#L1", analysisId = "a9", localKey = "L1",
            surfaceNames = listOf("沈七"), primaryName = "沈七",
        )
        val resolver = io.legado.app.help.readaloud.analysis.v4.IdentityResolver(
            repository,
            WordLibraryRepository(ApplicationProvider.getApplicationContext<Application>()),
            null,
            null,
        )
        val evidence = RaEvidence(
            id = "e9", bookKey = BOOK, analysisId = "a9", sourceChapter = 9,
            paragraphIndex = 0, start = 0, end = 1, excerpt = "青衫客即沈七",
            type = RaEvidenceType.ALIAS_EQUAL, subjectLocalId = "a9#L1",
            objectText = "青衫客", scope = RaEvidenceScope.MAIN_TEXT, verified = true,
        )
        val result = resolver.resolveChapter(
            BOOK, 9, "第10章", listOf(speaker), emptyList(), listOf(evidence),
        )
        assertEquals(a.id, result.resolutions["L1"]?.characterId)
        assertEquals(a.id, db.raBuildDao.segments("a3").single().characterId)
        assertEquals(a.id, db.raBuildDao.localSpeakers("a3").single().characterId)
        assertEquals("MERGED", db.raCharacterDao.character(p.id)?.status)
        assertTrue(db.raCharacterDao.aliasesOf(a.id).any { it.surface == "沈七" })
    }

    @Test
    fun `rollback restores shared alias on both sides and keeps post-merge alias`() = runBlocking {
        val from = repository.ensureCharacter(
            BOOK, "沈七", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val to = repository.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 1, listOf("沈七"), "ACTIVE", "user", "inferred",
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a2", bookKey = BOOK, sourceKey = "s", chapterIndex = 2,
                contentHash = "h2", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertEvidence(
            listOf(
                RaEvidenceEntity(
                    id = "e1", bookKey = BOOK, analysisId = "a2",
                    sourceChapter = 2, paragraphIndex = 0, start = 0, end = 1,
                    excerpt = "沈七即沈孤鸿", type = "ALIAS_EQUAL",
                ),
            ),
        )
        repository.mergeCharactersAsAi(from.id, to.id, "AI 合并", listOf("e1"), "d1")
        repository.attachAliases(BOOK, to.id, listOf("合并后新别名"), 4, "user")

        RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
            .deleteChapters(BOOK, "s", listOf(2))

        assertTrue(db.raCharacterDao.aliasesOf(from.id).any { it.normalized == "沈七" })
        assertTrue(db.raCharacterDao.aliasesOf(to.id).any { it.normalized == "沈七" })
        assertTrue(db.raCharacterDao.aliasesOf(to.id).any { it.surface == "合并后新别名" })
        assertEquals("user", db.raCharacterDao.aliasesOf(to.id).first { it.normalized == "沈七" }.source)
    }

    @Test
    fun `rollback restores locked default profile from custom merge state`() = runBlocking {
        val from = repository.ensureCharacter(
            BOOK, "沈七", "核心", "男", "男青年", 0, emptyList(), "ACTIVE", "ai", "inferred",
        )
        val to = repository.ensureCharacter(
            BOOK, "沈孤鸿", "核心", "男", "男青年", 1, emptyList(), "ACTIVE", "ai", "inferred",
        )
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp_from", bookKey = BOOK, characterId = from.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "from-tag",
                locked = true, createdAt = 1L,
            ),
        )
        repository.saveVoiceProfile(
            RaVoiceProfile(
                id = "vp_to", bookKey = BOOK, characterId = to.id,
                kind = RaVoiceProfileKind.DEFAULT, label = "默认", voiceTag = "to-tag",
                locked = true, createdAt = 2L,
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a2", bookKey = BOOK, sourceKey = "s", chapterIndex = 2,
                contentHash = "h2", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertEvidence(
            listOf(
                RaEvidenceEntity(
                    id = "e1", bookKey = BOOK, analysisId = "a2",
                    sourceChapter = 2, paragraphIndex = 0, start = 0, end = 1,
                    excerpt = "沈七即沈孤鸿", type = "ALIAS_EQUAL",
                ),
            ),
        )
        repository.mergeCharactersAsAi(from.id, to.id, "AI 合并", listOf("e1"), "d1")
        assertTrue(
            db.raCharacterDao.voiceProfilesOf(to.id).any { it.id == "vp_from" && it.kind == "CUSTOM" },
        )

        RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
            .deleteChapters(BOOK, "s", listOf(2))

        val restoredFrom = db.raCharacterDao.voiceProfilesOf(from.id).single { it.id == "vp_from" }
        assertEquals("DEFAULT", restoredFrom.kind)
        assertEquals("默认", restoredFrom.label)
        assertTrue(restoredFrom.locked)
        assertTrue(
            db.raCharacterDao.voiceProfilesOf(to.id).any {
                it.id == "vp_to" && it.kind == "DEFAULT" && it.locked
            },
        )
    }
}
