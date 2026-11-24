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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RaBookIdentityRepository(db.raBuildDao, db.bookDao)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `same url keeps same key`() = runBlocking {
        val first = repository.keyOf("u1", "source")
        val second = repository.keyOf("u1", "source")
        assertEquals(first, second)
    }

    @Test
    fun `source change keeps key and new url inherits it`() = runBlocking {
        db.bookDao.insert(Book(bookUrl = "u1", name = "书", author = "作者"))
        val old = repository.keyOf("u1", "source-a")
        RaBookIdentitySync.migrate(db, "u1", "u2", "source-b")
        assertEquals(old, repository.bookKeyOf("u2"))
    }

    @Test
    fun `same name and author falls back to existing key`() = runBlocking {
        db.bookDao.insert(Book(bookUrl = "u1", name = "书", author = "作者"))
        db.bookDao.insert(Book(bookUrl = "u3", name = "书", author = "作者"))
        val old = repository.keyOf("u1", "source-a")
        val fallback = repository.keyOf("u3", "source-c")
        assertEquals(old, fallback)
    }

    @Test
    fun `different book gets different key`() = runBlocking {
        val one = repository.keyOf("u1", "s")
        val two = repository.keyOf("u2", "s")
        assertNotEquals(one, two)
    }
}
