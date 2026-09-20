package io.legado.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestChapterTaskSchedulerTest {

    @Test
    fun `same chapter keeps running task and only latest pending task`() = runBlocking {
        val scheduler = LatestChapterTaskScheduler<String>(this)
        val gate = CompletableDeferred<Unit>()
        val runningStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        scheduler.submit("chapter") {
            events += "running-start"
            runningStarted.complete(Unit)
            gate.await()
            events += "running-end"
        }
        withTimeout(2_000) { runningStarted.await() }

        val replaced = scheduler.submit("chapter") { events += "replaced" }
        val latest = scheduler.submit("chapter") { events += "latest" }
        assertTrue(replaced.isCancelled)
        assertEquals(
            LatestChapterTaskScheduler.TaskState(running = true, pending = true),
            scheduler.stateOf("chapter"),
        )

        gate.complete(Unit)
        withTimeout(2_000) { latest.await() }

        assertEquals(listOf("running-start", "running-end", "latest"), events)
        // 任务状态清理发生在被调任务的 finally（onTaskFinished）中，与 await 恢复存在竞态：
        // 有界等待状态收敛，避免 CI 上偶发失败（断言意图不变）
        withTimeout(2_000) {
            while (scheduler.stateOf("chapter").running) delay(10)
        }
        assertEquals(LatestChapterTaskScheduler.TaskState(), scheduler.stateOf("chapter"))
    }

    @Test
    fun `different chapters can run independently`() = runBlocking {
        val scheduler = LatestChapterTaskScheduler<String>(this)
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        scheduler.submit("first") {
            firstStarted.complete(Unit)
            awaitCancellation()
        }
        scheduler.submit("second") {
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        withTimeout(2_000) {
            firstStarted.await()
            secondStarted.await()
        }

        assertTrue(firstStarted.isCompleted)
        assertTrue(secondStarted.isCompleted)
        assertTrue(scheduler.stateOf("first").running)
        assertTrue(scheduler.stateOf("second").running)

        scheduler.cancelAll()
        assertFalse(scheduler.stateOf("first").running)
        assertFalse(scheduler.stateOf("second").running)
    }
}
