package io.legado.app.help.readaloud.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterJobRegistryTest {

    private fun key(chapter: Int = 3) = ChapterJobRegistry.Key(
        bookKey = "bk",
        sourceKey = "src",
        chapterIndex = chapter,
        contentHash = "hash",
    )

    @Test
    fun `same key joins running job and owner completes it`() = runBlocking {
        val registry = ChapterJobRegistry()
        val owner = registry.acquire(key()) as ChapterJobRegistry.Acquire.Owner
        val join = registry.acquire(key()) as ChapterJobRegistry.Acquire.Join
        assertTrue(registry.occupiedChapters("bk", "src").contains(3))
        owner.ticket.complete(ChapterJobRegistry.Outcome.SUCCESS)
        assertEquals(ChapterJobRegistry.Outcome.SUCCESS, join.result.await())
        assertTrue(registry.occupiedChapters("bk", "src").isEmpty())
    }

    @Test
    fun `after completion a new acquire becomes owner`() = runBlocking {
        val registry = ChapterJobRegistry()
        val first = registry.acquire(key()) as ChapterJobRegistry.Acquire.Owner
        first.ticket.complete(ChapterJobRegistry.Outcome.FAILED)
        val second = registry.acquire(key()) as ChapterJobRegistry.Acquire.Owner
        second.ticket.complete(ChapterJobRegistry.Outcome.CANCELLED)
    }

    @Test
    fun `different content hash is a different job`() = runBlocking {
        val registry = ChapterJobRegistry()
        val a = registry.acquire(key()) as ChapterJobRegistry.Acquire.Owner
        val b = registry.acquire(key().copy(contentHash = "hash2")) as ChapterJobRegistry.Acquire.Owner
        a.ticket.complete(ChapterJobRegistry.Outcome.SUCCESS)
        b.ticket.complete(ChapterJobRegistry.Outcome.SUCCESS)
    }
}
