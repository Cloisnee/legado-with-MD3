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
        suspend fun bindControl(value: AiRequestControl) = registry.lock.withLock {
            val previous = registry.controls.put(key, value)
            if (previous !== value && key in registry.foreground) {
                previous?.leaveForeground()
                value.foreground()
            }
        }

        suspend fun complete(outcome: Outcome) {
            withContext(NonCancellable) { registry.finish(this@Ticket, outcome) }
        }
    }

    private val lock = Mutex()
    private val jobs = HashMap<Key, Ticket>()
    private val controls = HashMap<Key, AiRequestControl>()
    private val foreground = HashMap<Key, Int>()

    suspend fun acquire(key: Key): Acquire = lock.withLock {
        jobs[key]?.let { Acquire.Join(it.result) }
            ?: Acquire.Owner(Ticket(key, this).also { jobs[key] = it })
    }

    suspend fun acquireBackground(key: Key): Ticket? = lock.withLock {
        if (key in jobs || key in foreground) return@withLock null
        Ticket(key, this).also { jobs[key] = it }
    }

    suspend fun <T> withForeground(key: Key, block: suspend () -> T): T {
        lock.withLock {
            val count = foreground.getOrDefault(key, 0)
            foreground[key] = count + 1
            if (count == 0) controls[key]?.foreground()
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    val count = foreground.getValue(key)
                    if (count == 1) {
                        foreground.remove(key)
                        controls[key]?.leaveForeground()
                    } else {
                        foreground[key] = count - 1
                    }
                }
            }
        }
    }

    suspend fun releaseControl(key: Key, control: AiRequestControl) = withContext(NonCancellable) {
        lock.withLock {
            if (controls[key] === control) {
                controls.remove(key)
                if (key in foreground) control.leaveForeground()
            }
        }
    }

    suspend fun occupiedChapters(bookKey: String, sourceKey: String): Set<Int> = lock.withLock {
        (jobs.keys + foreground.keys)
            .filter { it.bookKey == bookKey && it.sourceKey == sourceKey }
            .map { it.chapterIndex }
            .toSet()
    }

    private suspend fun finish(ticket: Ticket, outcome: Outcome) = lock.withLock {
        if (jobs[ticket.key] === ticket) jobs.remove(ticket.key)
        ticket.result.complete(outcome)
    }
}
