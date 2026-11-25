package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AiRequestSchedulerTest {

    private fun ref(maxConcurrency: Int): AiSpeechClient.ModelRef {
        val provider = AiProvider(
            id = "p_test",
            name = "test",
            baseUrl = "http://127.0.0.1",
            apiKey = "k",
            maxConcurrency = maxConcurrency,
        )
        return AiSpeechClient.ModelRef(
            AiModelEntry(id = "m1", providerId = provider.id, name = "m", modelId = "m"),
            provider,
        )
    }

    private fun ctx(
        priority: AiPriority,
        preemptible: Boolean = false,
    ) = AiSchedulerContext(
        kind = AiTaskKind.STAGE2,
        priority = priority,
        bookKey = "bk",
        chapters = listOf(0),
        preemptible = preemptible,
    )

    @Test
    fun `provider concurrency is respected`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val current = AtomicInteger()
        val max = AtomicInteger()
        val tasks = (1..4).map {
            launch {
                scheduler.withPermit(ref(2), ctx(AiPriority.P3_BACKGROUND, preemptible = true)) {
                    val now = current.incrementAndGet()
                    max.updateAndGet { old -> maxOf(old, now) }
                    delay(60)
                    current.decrementAndGet()
                }
            }
        }
        tasks.forEach { it.join() }
        assertTrue(max.get() <= 2)
    }

    @Test
    fun `foreground preempts running background`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val bgStarted = CompletableDeferred<Unit>()
        val bg = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND, preemptible = true)) {
                bgStarted.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(1_000) { bgStarted.await() }
        var foregroundRan = false
        withTimeout(2_000) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT)) {
                foregroundRan = true
            }
        }
        assertTrue(foregroundRan)
        withTimeout(1_000) { bg.join() }
        assertTrue(bg.isCancelled)
    }

    @Test
    fun `higher priority waiter runs first`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val blocker = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) {
                gate.await()
            }
        }
        delay(50)
        val low = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P4_ENRICH)) { order.add("low") }
        }
        delay(50)
        val high = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT)) { order.add("high") }
        }
        delay(50)
        gate.complete(Unit)
        blocker.join()
        high.join()
        low.join()
        assertEquals(listOf("high", "low"), order)
    }

    @Test
    fun `cancelled waiter does not run`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val gate = CompletableDeferred<Unit>()
        val blocker = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) { gate.await() }
        }
        delay(50)
        val ran = AtomicInteger()
        val waiter: Job = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) { ran.incrementAndGet() }
        }
        delay(50)
        waiter.cancel()
        gate.complete(Unit)
        blocker.join()
        waiter.join()
        delay(50)
        assertEquals(0, ran.get())
    }

    @Test
    fun `429 pauses provider lane`() = runBlocking {
        val scheduler = AiRequestScheduler()
        scheduler.reportRateLimited("p_test", 1)
        val started = AtomicInteger()
        val job = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) {
                started.incrementAndGet()
            }
        }
        delay(200)
        assertEquals(0, started.get())
        job.cancel()
        job.join()
    }

    @Test
    fun `permit is released after cancellation`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val started = CompletableDeferred<Unit>()
        val first = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND, preemptible = true)) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(1_000) { started.await() }
        first.cancel()
        first.join()
        var secondRan = false
        withTimeout(1_000) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT)) { secondRan = true }
        }
        assertTrue(secondRan)
        assertFalse(first.isActive)
    }
}
