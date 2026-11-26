package io.legado.app.help.readaloud.analysis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 章节分析 SingleFlight（02 §5）。
 *
 * 键 = bookKey|sourceKey|chapterIndex|contentHash；同键只允许一个运行中的 Job，
 * 后到者等待同一个 Deferred。批量为每章登记一个 ticket，逐章提交时完成对应 ticket，
 * 前台因此可以在批次流式进行中只等自己需要的章。
 */
class ChapterJobRegistry {

    enum class Outcome {
        SUCCESS,
        EXTRACTED,
        FAILED,
        CANCELLED,
    }

    data class Key(
        val bookKey: String,
        val sourceKey: String,
        val chapterIndex: Int,
        val contentHash: String,
        val resolverVersion: String = "",
        val stage: String = "EXTRACT",
    )

    sealed class Acquire {
        data class Owner(val ticket: Ticket) : Acquire()
        data class Join(val result: Deferred<Outcome>) : Acquire()
    }

    class Ticket internal constructor(
        internal val key: Key,
        private val registry: ChapterJobRegistry,
    ) {
        internal val result = CompletableDeferred<Outcome>()

        suspend fun complete(outcome: Outcome) {
            result.complete(outcome)
            val ticket = this
            withContext(NonCancellable) { registry.finish(ticket) }
        }
    }

    private val lock = Mutex()
    private val jobs = HashMap<Key, Ticket>()

    suspend fun acquire(key: Key): Acquire = lock.withLock {
        val existing = jobs[key]
        if (existing != null) {
            Acquire.Join(existing.result)
        } else {
            val ticket = Ticket(key, this)
            jobs[key] = ticket
            Acquire.Owner(ticket)
        }
    }

    suspend fun occupiedChapters(bookKey: String, sourceKey: String): Set<Int> = lock.withLock {
        jobs.keys
            .filter { it.bookKey == bookKey && it.sourceKey == sourceKey }
            .map { it.chapterIndex }
            .toSet()
    }

    private suspend fun finish(ticket: Ticket) {
        lock.withLock {
            if (jobs[ticket.key] === ticket) jobs.remove(ticket.key)
        }
    }
}
