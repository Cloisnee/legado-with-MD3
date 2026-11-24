package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
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

        RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
            .deleteChapters(BOOK, "s", listOf(2))

        assertEquals(from.id, db.raBuildDao.segments("a3").single().characterId)
        assertEquals(from.id, db.raBuildDao.localSpeakers("a3").single().characterId)
        assertEquals("ACTIVE", db.raCharacterDao.character(from.id)?.status)
        assertTrue(db.raCharacterDao.aliasesOf(from.id).any { it.surface == "青衫客" })
    }

    companion object {
        private const val BOOK = "bk_merge"
    }
}
