package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class RaBookIdentityRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: RaBookIdentityRepository
    private lateinit var builds: RaBuildRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        builds = RaBuildRepository(db, db.raBuildDao, db.raCharacterDao, db.raDirectionDao)
        repository = RaBookIdentityRepository(db, db.raBuildDao, db.bookDao, builds)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun keyOf(url: String, source: String) =
        requireNotNull(repository.keyOf(url, source, builds.generation()))

    @Test
    fun `whole book deletion revokes all its mapped URLs without revoking another book`() = runBlocking {
        val old = keyOf("u1", "s1")
        repository.onSourceChanged("u1", "u2", "s2")
        val generation = builds.generation()
        builds.deleteBook(old)
        assertNull(repository.keyOf("u1", "s1", generation))
        assertNull(repository.keyOf("u2", "s2", generation))
        assertNull(repository.bookKeyOf("u1"))
        assertNull(repository.bookKeyOf("u2"))
        assertNotEquals(old, repository.keyOf("other", "s1", generation))
        assertNotEquals(old, keyOf("u1", "s1"))
    }

    @Test
    fun `same url keeps same key`() = runBlocking {
        val first = keyOf("u1", "source")
        val second = keyOf("u1", "source")
        assertEquals(first, second)
    }

    @Test
    fun `source change keeps key and new url inherits it`() = runBlocking {
        db.bookDao.insert(Book(bookUrl = "u1", name = "书", author = "作者"))
        val old = keyOf("u1", "source-a")
        RaBookIdentitySync.migrate(db, "u1", "u2", "source-b")
        assertEquals(old, repository.bookKeyOf("u2"))
    }

    @Test
    fun `same name and author falls back to existing key`() = runBlocking {
        db.bookDao.insert(Book(bookUrl = "u1", name = "书", author = "作者"))
        db.bookDao.insert(Book(bookUrl = "u3", name = "书", author = "作者"))
        val old = keyOf("u1", "source-a")
        val fallback = keyOf("u3", "source-c")
        assertEquals(old, fallback)
    }

    @Test
    fun `different book gets different key`() = runBlocking {
        val one = keyOf("u1", "s")
        val two = keyOf("u2", "s")
        assertNotEquals(one, two)
    }
}
