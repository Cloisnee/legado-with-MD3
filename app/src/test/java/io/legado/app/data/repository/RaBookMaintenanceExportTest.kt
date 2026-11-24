package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.readaloud.RaBookIdentityEntity
import io.legado.app.data.entities.readaloud.RaChapterAnalysisEntity
import io.legado.app.data.entities.readaloud.RaChapterBuildEntity
import io.legado.app.data.entities.readaloud.RaLocalMentionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RaBookMaintenanceExportTest {

    private lateinit var db: AppDatabase
    private lateinit var maintenance: RaBookMaintenanceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val buildRepository = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        val catalog = ReadAloudCatalogRepository(
            db.bookDao,
            db.bookChapterDao,
            db.raBuildDao,
            buildRepository,
        )
        maintenance = RaBookMaintenanceRepository(
            db,
            db.raBuildDao,
            db.raCharacterDao,
            ReadAloudAudioCacheRepository(context),
            catalog,
            buildRepository,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `export then import keeps non-empty local mentions`() = runBlocking {
        db.bookDao.insert(Book(bookUrl = BOOK_URL, name = BOOK_NAME, author = "a"))
        db.raBuildDao.upsertIdentity(
            RaBookIdentityEntity(bookUrl = BOOK_URL, bookKey = BOOK, sourceKey = SOURCE),
        )
        db.raBuildDao.upsertBuild(
            RaChapterBuildEntity(
                bookKey = BOOK, sourceKey = SOURCE, chapterIndex = 0,
                contentHash = "h", state = "IDENTITY_READY", analysisId = "a1",
            ),
        )
        db.raBuildDao.upsertAnalysis(
            RaChapterAnalysisEntity(
                id = "a1", bookKey = BOOK, sourceKey = SOURCE, chapterIndex = 0,
                contentHash = "h", resolverVersion = "v4.2", status = "RESOLVED",
            ),
        )
        db.raBuildDao.upsertLocalMentions(
            listOf(
                RaLocalMentionEntity(
                    id = "a1#M1",
                    analysisId = "a1",
                    localKey = "M1",
                    surfaceNames = "[\"沈公子\"]",
                    primaryName = "沈公子",
                    resolution = "PENDING",
                ),
            ),
        )

        val out = ByteArrayOutputStream()
        val count = maintenance.exportBooksJson(listOf(BOOK_NAME), out)
        assertEquals(1, count)

        db.raBuildDao.deleteLocalMentionsOfBook(BOOK)
        assertTrue(db.raBuildDao.localMentions("a1").isEmpty())

        val (ok, message) = maintenance.importBooksJson(ByteArrayInputStream(out.toByteArray()))
        assertTrue(message, ok)
        assertEquals("沈公子", db.raBuildDao.localMentions("a1").single().primaryName)
    }

    companion object {
        private const val BOOK = "bk_export"
        private const val BOOK_URL = "https://example.com/book"
        private const val BOOK_NAME = "测试书"
        private const val SOURCE = "source"
    }
}
