package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.readaloud.RaAttributeObsEntity
import io.legado.app.data.entities.readaloud.RaCharacterAliasEntity
import io.legado.app.data.entities.readaloud.RaIdentityLinkEntity
import io.legado.app.data.entities.readaloud.RaRelationObsEntity
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaCharacterEntity
import io.legado.app.data.entities.readaloud.RaCharacterRedirectEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaDirectionLineEntity
import io.legado.app.data.entities.readaloud.RaDirectionPlanEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaIdentityDecisionEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RaBuildDeletionTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RaBuildRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        runBlocking { seed() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        for (chapter in listOf(1, 2)) {
            val analysisId = "a$chapter"
            db.raBuildDao.upsertBuild(
                RaChapterBuildEntity(
                    bookKey = BOOK, sourceKey = SOURCE, chapterIndex = chapter,
                    contentHash = "h$chapter", state = "IDENTITY_READY", analysisId = analysisId,
                ),
            )
            db.raBuildDao.upsertAnalysis(
                RaChapterAnalysisEntity(
                    id = analysisId, bookKey = BOOK, sourceKey = SOURCE, chapterIndex = chapter,
                    contentHash = "h$chapter", resolverVersion = "v4.1", status = "RESOLVED",
                ),
            )
            db.raBuildDao.upsertSegments(
                listOf(
                    RaSegmentEntity(
                        id = "s$chapter", analysisId = analysisId, bookKey = BOOK, chapterIndex = chapter,
                        paragraphIndex = 0, start = 0, end = 1, chapterPosition = 0, text = "x",
                        roleType = "character",
                    ),
                ),
            )
            db.raBuildDao.upsertLocalSpeakers(
                listOf(
                    RaLocalSpeakerEntity(
                        id = "l$chapter", analysisId = analysisId, localKey = "L1",
                        surfaceNames = if (chapter == 2) "[\"甲\",\"沈七\"]" else "[\"甲\"]",
                        primaryName = "甲", resolution = "RESOLVED",
                        characterId = "chr_test",
                    ),
                ),
            )
            db.raBuildDao.upsertEvidence(
                listOf(
                    RaEvidenceEntity(
                        id = "e$chapter", bookKey = BOOK, analysisId = analysisId,
                        sourceChapter = chapter, paragraphIndex = 0, start = 0, end = 1,
                        excerpt = "x", type = "ATTRIBUTE",
                    ),
                ),
            )
            db.raBuildDao.upsertOverride(
                RaSegmentOverrideEntity(
                    bookKey = BOOK, chapterIndex = chapter, paragraphIndex = 0, textHash = "h$chapter",
                ),
            )
            db.raBuildDao.upsertLocalMentions(
                listOf(
                    RaLocalMentionEntity(
                        id = "m$chapter", analysisId = analysisId, localKey = "M1",
                        surfaceNames = "[]", primaryName = "提及", resolution = "PENDING",
                    ),
                ),
            )
            db.raCharacterDao.insertDecision(
                RaIdentityDecisionEntity(
                    id = "d$chapter", bookKey = BOOK, localSpeakerId = "l$chapter",
                    verdict = "UNKNOWN",
                ),
            )
            db.raDirectionDao.savePlan(
                RaDirectionPlanEntity(
                    id = "p$chapter", analysisId = analysisId, bookKey = BOOK,
                    chapterIndex = chapter, module = "PLAYBACK_BASE", status = "READY",
                ),
                listOf(RaDirectionLineEntity(planId = "p$chapter", segmentId = "s$chapter", emotionTag = "平静")),
                emptyList(),
                emptyList(),
            )
        }
        // 同一章在另一个 sourceKey 下的分析，也必须一并清掉
        db.raBuildDao.upsertBuild(
            RaChapterBuildEntity(
                bookKey = BOOK, sourceKey = "source-2", chapterIndex = 1,
                contentHash = "h1b", state = "IDENTITY_READY", analysisId = "a1b",
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a1b", bookKey = BOOK, sourceKey = "source-2", chapterIndex = 1,
                contentHash = "h1b", resolverVersion = "v4.1", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertSegments(
            listOf(
                RaSegmentEntity(
                    id = "s1b", analysisId = "a1b", bookKey = BOOK, chapterIndex = 1,
                    paragraphIndex = 0, start = 0, end = 1, chapterPosition = 0, text = "x",
                    roleType = "character",
                ),
            ),
        )
        db.raCharacterDao.upsertCharacter(
            RaCharacterEntity(
                id = "chr_test", bookKey = BOOK, displayName = "测试人物", status = "ACTIVE",
            ),
        )
        db.raCharacterDao.insertAttribute(
            RaAttributeObsEntity(
                id = "att_ai_1", bookKey = BOOK, characterId = "chr_test", key = "gender",
                value = "男", fromChapter = 1, source = "inferred",
            ),
        )
        db.raCharacterDao.insertAttribute(
            RaAttributeObsEntity(
                id = "att_user_1", bookKey = BOOK, characterId = "chr_test", key = "ageBand",
                value = "男青年", fromChapter = 1, source = "user",
            ),
        )
        db.raCharacterDao.insertAttribute(
            RaAttributeObsEntity(
                id = "att_ai_2", bookKey = BOOK, characterId = "chr_test", key = "identity",
                value = "弟子", fromChapter = 2, source = "explicit",
            ),
        )
        db.raCharacterDao.insertRelation(
            RaRelationObsEntity(
                id = "rel_1", bookKey = BOOK, characterId = "chr_test", relation = "师兄妹",
                targetText = "师妹", fromChapter = 1,
            ),
        )
        db.raCharacterDao.insertRelation(
            RaRelationObsEntity(
                id = "rel_2", bookKey = BOOK, characterId = "chr_test", relation = "师徒",
                targetText = "师父", fromChapter = 2,
            ),
        )
        db.raCharacterDao.upsertAliases(
            listOf(
                RaCharacterAliasEntity(
                    bookKey = BOOK, normalized = "ai-alias", characterId = "chr_test",
                    surface = "AI别名", source = "ai", firstChapter = 1,
                ),
                RaCharacterAliasEntity(
                    bookKey = BOOK, normalized = "user-alias", characterId = "chr_test",
                    surface = "用户别名", source = "user", firstChapter = 1,
                ),
                RaCharacterAliasEntity(
                    bookKey = BOOK, normalized = "沈七", characterId = "chr_test",
                    surface = "沈七", source = "ai", firstChapter = 1,
                ),
            ),
        )
        db.raCharacterDao.upsertLink(
            RaIdentityLinkEntity(
                id = "link_e1", bookKey = BOOK, aCharacterId = "chr_test",
                bCharacterId = "chr_test_from", kind = "CANNOT", source = "ai",
                evidenceIds = "[\"e1\"]",
            ),
        )
        db.raCharacterDao.upsertLink(
            RaIdentityLinkEntity(
                id = "link_e2", bookKey = BOOK, aCharacterId = "chr_test",
                bCharacterId = "chr_test_from", kind = "CANNOT", source = "ai",
                evidenceIds = "[\"e2\"]",
            ),
        )
        db.raCharacterDao.upsertCharacter(
            RaCharacterEntity(
                id = "chr_test_from", bookKey = BOOK, displayName = "被合并人物",
                status = "MERGED",
            ),
        )
        db.raCharacterDao.upsertRedirect(
            RaCharacterRedirectEntity(
                fromCharacterId = "chr_test_from", toCharacterId = "chr_test",
            ),
        )
        db.raCharacterDao.upsertLink(
            RaIdentityLinkEntity(
                id = "link_same", bookKey = BOOK, aCharacterId = "chr_test",
                bCharacterId = "chr_test_from", kind = "SAME", source = "ai",
                evidenceIds = "[\"e1\"]",
            ),
        )
    }

    @Test
    fun `delete chapter removes all analysis-owned rows but keeps other chapters`() = runBlocking {
        repository.deleteChapters(BOOK, SOURCE, listOf(1))

        assertNull(db.raBuildDao.getBuild(BOOK, SOURCE, 1))
        assertNull(db.raBuildDao.getAnalysisById("a1"))
        assertEquals(0, db.raBuildDao.segments("a1").size)
        assertEquals(0, db.raBuildDao.localSpeakers("a1").size)
        assertEquals(0, db.raBuildDao.evidence("a1").size)
        assertEquals(0, db.raBuildDao.overrides(BOOK, 1).size)
        assertNull(db.raDirectionDao.plan("a1", "PLAYBACK_BASE"))
        assertEquals(0, db.raBuildDao.localMentions("a1").size)
        assertEquals(0, db.raCharacterDao.decisionsOf("l1").size)
        assertNull(db.raBuildDao.getAnalysisById("a1b"))
        assertEquals(0, db.raBuildDao.segments("a1b").size)

        assertNull(db.raCharacterDao.attributesOfBook(BOOK).firstOrNull { it.id == "att_ai_1" })
        assertNotNull(db.raCharacterDao.attributesOfBook(BOOK).firstOrNull { it.id == "att_user_1" })
        assertNotNull(db.raCharacterDao.attributesOfBook(BOOK).firstOrNull { it.id == "att_ai_2" })
        assertNull(db.raCharacterDao.relationsOfBook(BOOK).firstOrNull { it.id == "rel_1" })
        assertNotNull(db.raCharacterDao.relationsOfBook(BOOK).firstOrNull { it.id == "rel_2" })
        assertNull(db.raCharacterDao.aliasesOfBook(BOOK).firstOrNull { it.surface == "AI别名" })
        assertNotNull(db.raCharacterDao.aliasesOfBook(BOOK).firstOrNull { it.surface == "用户别名" })
        assertNotNull(db.raCharacterDao.aliasesOfBook(BOOK).firstOrNull { it.surface == "沈七" })
        assertNull(db.raCharacterDao.linksOfBook(BOOK).firstOrNull { it.id == "link_e1" })
        assertNotNull(db.raCharacterDao.linksOfBook(BOOK).firstOrNull { it.id == "link_e2" })
        assertNull(db.raCharacterDao.linksOfBook(BOOK).firstOrNull { it.id == "link_same" })
        assertNull(db.raCharacterDao.redirect("chr_test_from"))
        assertEquals("ACTIVE", db.raCharacterDao.character("chr_test_from")?.status)

        assertNotNull(db.raBuildDao.getBuild(BOOK, SOURCE, 2))
        assertEquals(1, db.raBuildDao.segments("a2").size)
        assertNotNull(db.raDirectionDao.plan("a2", "PLAYBACK_BASE"))
    }

    @Test
    fun `delete book clears speakers mentions evidence decisions and redirects`() = runBlocking {
        repository.deleteBook(BOOK)
        assertTrue(db.raBuildDao.allBuildsOf(BOOK).isEmpty())
        assertTrue(db.raBuildDao.allAnalysesOf(BOOK).isEmpty())
        assertTrue(db.raBuildDao.localSpeakersOfBook(BOOK).isEmpty())
        assertTrue(db.raBuildDao.evidenceOfBook(BOOK).isEmpty())
        assertTrue(db.raCharacterDao.decisionsOfBook(BOOK).isEmpty())
        assertTrue(db.raCharacterDao.allCharacters(BOOK).isEmpty())
        assertNull(db.raCharacterDao.redirect("chr_test_from"))
    }

    companion object {
        private const val BOOK = "bk_delete"
        private const val SOURCE = "source"
    }
}
