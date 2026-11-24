package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
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

    companion object {
        private const val BOOK = "bk_merge"
    }
}
