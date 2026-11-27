package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
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
    @Test
    fun `new low priority request cannot barge ahead of queued current chapter`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) { gate.await() }
            scheduler.withPermit(ref(1), ctx(AiPriority.P4_ENRICH)) { order.add("P4") }
        }
        val current = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT)) { order.add("P0") }
        }
        gate.complete(Unit)
        withTimeout(1_000) { first.join(); current.join() }
        assertEquals(listOf("P0", "P4"), order)
    }

    @Test
    fun `different providers have independent permits`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val started = CompletableDeferred<Unit>()
        val first = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) {
                started.complete(Unit); awaitCancellation()
            }
        }
        started.await()
        val other = ref(1).let { it.copy(provider = it.provider.copy(id = "other")) }
        withTimeout(500) { scheduler.withPermit(other, ctx(AiPriority.P3_BACKGROUND)) { } }
        first.cancel(); first.join()
    }

    @Test
    fun `foreground takes queued batch without cancelling unrelated running request`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val holder = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) { started.complete(Unit); gate.await() }
        }
        started.await()
        val control = AiRequestControl()
        val queued = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND, true).copy(control = control)) {
                error("queued batch should have yielded")
            }
        }
        control.foreground()
        withTimeout(1_000) { queued.join() }
        assertTrue(queued.isCancelled)
        assertTrue(holder.isActive)
        gate.complete(Unit)
        holder.join()
    }

    @Test
    fun `promoted running batch keeps next phase foreground and protected`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val control = AiRequestControl()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val context = ctx(AiPriority.P3_BACKGROUND, true).copy(control = control)
        val batch = launch {
            scheduler.withPermit(ref(1), context) { started.complete(Unit); gate.await() }
            scheduler.withPermit(ref(1), context.copy(kind = AiTaskKind.RESOLVE)) { }
        }
        started.await()
        control.foreground()
        gate.complete(Unit)
        withTimeout(1_000) { batch.join() }
        assertFalse(batch.isCancelled)
        assertTrue(control.protected)
        assertEquals(AiPriority.P0_CURRENT, context.effectivePriority)
    }

    @Test
    fun `last foreground claim releases a protected stream to an already queued jump`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val registry = ChapterJobRegistry()
        val keys = (0..1).map { ChapterJobRegistry.Key("bk", "src", it, "hash") }
        val control = AiRequestControl()
        val tickets = keys.map { checkNotNull(registry.acquireBackground(it)).also { ticket -> ticket.bindControl(control) } }
        val started = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val context = ctx(AiPriority.P3_BACKGROUND, true).copy(control = control, chapters = listOf(0, 1))
        val background = launch {
            scheduler.withPermit(ref(1), context) {
                active.incrementAndGet()
                started.complete(Unit)
                try { awaitCancellation() } finally { active.decrementAndGet() }
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            lateinit var jump: Job
            registry.withForeground(keys[0]) {
                registry.withForeground(keys[1]) {
                    jump = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT).copy(chapters = listOf(8))) {
                            assertEquals(0, active.get())
                            entered.complete(Unit)
                        }
                    }
                    assertTrue(control.protected)
                    assertFalse(entered.isCompleted)
                }
                assertTrue(control.protected)
                assertEquals(AiPriority.P0_CURRENT, context.effectivePriority)
                assertTrue(background.isActive)
                assertFalse(entered.isCompleted)
            }
            withTimeout(2_000) { entered.await(); jump.join(); background.join() }
            assertFalse(control.protected)
            assertEquals(AiPriority.P3_BACKGROUND, context.effectivePriority)
            assertTrue(background.isCancelled)
        } finally {
            background.cancel()
            keys.forEach { registry.releaseControl(it, control) }
            tickets.forEach { it.complete(ChapterJobRegistry.Outcome.CANCELLED) }
        }
    }

    @Test
    fun `same provider foreground resolve releases only the joined protected stream permit`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val control = AiRequestControl()
        val reference = ref(1)
        val running = CompletableDeferred<Unit>()
        val inNetwork = AtomicInteger()
        val background = launch {
            scheduler.withPermit(reference, ctx(AiPriority.P3_BACKGROUND, true).copy(control = control)) {
                inNetwork.incrementAndGet()
                running.complete(Unit)
                try { awaitCancellation() } finally { inNetwork.decrementAndGet() }
            }
        }
        try {
            withTimeout(1_000) { running.await() }
            control.foreground()
            assertTrue(background.isActive)
            withTimeout(1_000) {
                scheduler.withPermit(reference, ctx(AiPriority.P0_CURRENT).copy(kind = AiTaskKind.RESOLVE)) {
                    assertEquals(0, inNetwork.get())
                }
                background.join()
            }
            assertTrue(background.isCancelled)
        } finally {
            background.cancel()
        }
    }

    @Test
    fun `foreground director releases joined protected stream permit`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val control = AiRequestControl()
        val reference = ref(1)
        val running = CompletableDeferred<Unit>()
        val network = AtomicInteger()
        val background = launch {
            scheduler.withPermit(reference, ctx(AiPriority.P3_BACKGROUND, true).copy(
                control = control, sourceKey = "src",
            )) {
                network.incrementAndGet()
                running.complete(Unit)
                try { awaitCancellation() } finally { network.decrementAndGet() }
            }
        }
        try {
            withTimeout(1_000) { running.await() }
            control.foreground()
            withTimeout(1_000) {
                scheduler.withPermit(reference, ctx(AiPriority.P0_CURRENT).copy(
                    kind = AiTaskKind.DIRECTOR, sourceKey = "src",
                )) { assertEquals(0, network.get()) }
                background.join()
            }
            assertTrue(background.isCancelled)
        } finally {
            background.cancel()
        }
    }

    @Test
    fun `foreground joining a running nonincremental chapter cancels it`() = runBlocking {
        val scheduler = AiRequestScheduler()
        val control = AiRequestControl(incremental = false)
        val started = CompletableDeferred<Unit>()
        val holder = launch {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND, true).copy(control = control)) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            withTimeout(1_000) { started.await() }
            control.foreground()
            withTimeout(1_000) { holder.join() }
            assertTrue(holder.isCancelled)
            withTimeout(1_000) { scheduler.withPermit(ref(1), ctx(AiPriority.P0_CURRENT)) { } }
        } finally {
            holder.cancel()
        }
    }

    @Test
    fun `cancelled owner returns permit when lane lock is contended`() = runBlocking {
        val blockNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val scheduler = AiRequestScheduler(clock = {
            if (blockNext.compareAndSet(true, false)) {
                locked.countDown()
                check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
            System.currentTimeMillis()
        })
        val started = CompletableDeferred<Unit>()
        val owner = launch(kotlinx.coroutines.Dispatchers.Default) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) {
                started.complete(Unit); awaitCancellation()
            }
        }
        started.await()
        blockNext.set(true)
        val next = async(kotlinx.coroutines.Dispatchers.IO) {
            scheduler.withPermit(ref(1), ctx(AiPriority.P3_BACKGROUND)) { "granted" }
        }
        try {
            assertTrue(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                locked.await(1, java.util.concurrent.TimeUnit.SECONDS)
            })
            owner.cancel()
        } finally {
            release.countDown()
        }
        withTimeout(1_000) { owner.join() }
        assertEquals("granted", withTimeout(1_000) { next.await() })
    }

}
