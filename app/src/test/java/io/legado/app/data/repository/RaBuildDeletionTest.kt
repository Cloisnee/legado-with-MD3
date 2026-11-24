package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaDirectionLineEntity
import io.legado.app.data.entities.readaloud.RaDirectionPlanEntity
import io.legado.app.data.entities.readaloud.RaEvidenceEntity
import io.legado.app.data.entities.readaloud.RaLocalSpeakerEntity
import io.legado.app.data.entities.readaloud.RaSegmentEntity
import io.legado.app.data.entities.readaloud.RaSegmentOverrideEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                        surfaceNames = "[\"甲\"]", primaryName = "甲", resolution = "RESOLVED",
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
                    bookKey = BOOK, chapterIndex = chapter, paragraphIndex = 0, textHash = "h",
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

        assertNotNull(db.raBuildDao.getBuild(BOOK, SOURCE, 2))
        assertEquals(1, db.raBuildDao.segments("a2").size)
        assertNotNull(db.raDirectionDao.plan("a2", "PLAYBACK_BASE"))
    }

    companion object {
        private const val BOOK = "bk_delete"
        private const val SOURCE = "source"
    }
}
