package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.AiProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

/** AI 任务类型：决定日志与后续可能的差异化策略。 */
enum class AiTaskKind {
    STAGE1,
    STAGE2,
    RESOLVE,
    DIRECTOR,
    NARRATIVE,
}

/** 请求优先级（数值越小越优先）。P0~P2 视为前台，可抢占后台。 */
enum class AiPriority(val level: Int) {
    P0_CURRENT(0),
    P1_NEXT(1),
    P2_PRELOAD(2),
    P3_BACKGROUND(3),
    P4_ENRICH(4);

    val foreground: Boolean
        get() = level <= P2_PRELOAD.level
}

/** 一次 AI 请求的调度上下文（阶段、优先级、书籍/章节范围、是否可被前台抢占）。 */
data class AiSchedulerContext(
    val kind: AiTaskKind,
    val priority: AiPriority,
    val bookKey: String,
    val chapters: List<Int>,
    val preemptible: Boolean,
)

/**
 * AI 请求调度器（02 §4）：所有分析请求的唯一出口。
 *
 * - 按服务商维护独立许可池，容量 = [AiProvider.maxConcurrency]（1..8）；
 * - 许可在锁内直接交接给队首，避免新请求插队；同优先级 FIFO；
 * - 前台（P0~P2）到达且许可池已满时，取消优先级最低、启动最晚的可抢占后台请求；
 * - 429：该服务商暂停 min(60s, 2^n·5s)，期间排队等待而不是换模型；
 * - 取消联动由 [AiSpeechClient] 在协程取消时调用 OkHttp `Call.cancel()` 完成。
 */
class AiRequestScheduler(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seq = AtomicLong(0)
    private val lanes = HashMap<String, Lane>()
    private val lanesLock = Mutex()

    private class Lane {
        val lock = Mutex()
        var limit = 1
        var active = 0
        var pausedUntil = 0L
        val waiting = PriorityQueue<Waiter>(compareBy<Waiter>({ it.priority.level }, { it.seq }))
        val running = HashMap<Long, Running>()
    }

    private class Waiter(
        val id: Long,
        val priority: AiPriority,
        val seq: Long,
        val preemptible: Boolean,
        val job: Job?,
    ) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile
        var cancelled = false

        @Volatile
        var granted = false
    }

    private class Running(
        val priority: AiPriority,
        val startedAt: Long,
        val preemptible: Boolean,
        val job: Job?,
    )

    private suspend fun laneOf(providerId: String): Lane = lanesLock.withLock {
        lanes.getOrPut(providerId) { Lane() }
    }

    /** 取得服务商许可后执行 [block]；block 内是真正的网络请求。 */
    suspend fun <T> withPermit(
        ref: AiSpeechClient.ModelRef,
        context: AiSchedulerContext,
        block: suspend () -> T,
    ): T {
        val lane = laneOf(ref.provider.id)
        val id = seq.incrementAndGet()
        acquire(lane, ref.provider, context, id)
        try {
            return block()
        } finally {
            release(lane, id)
        }
    }

    /** 429 退避：同一服务商的请求在暂停期内排队等待。 */
    suspend fun reportRateLimited(providerId: String, consecutive: Int) {
        val lane = laneOf(providerId)
        val attempt = consecutive.coerceIn(1, 4)
        val backoff = minOf(60_000L, 5_000L shl (attempt - 1))
        lane.lock.withLock {
            lane.pausedUntil = maxOf(lane.pausedUntil, clock() + backoff)
        }
        scope.launch {
            delay(backoff + 50L)
            lane.lock.withLock {
                if (lane.pausedUntil <= clock()) grantWaitersLocked(lane)
            }
        }
    }

    private suspend fun acquire(
        lane: Lane,
        provider: AiProvider,
        context: AiSchedulerContext,
        id: Long,
    ) {
        val waiter = Waiter(
            id = id,
            priority = context.priority,
            seq = seq.incrementAndGet(),
            preemptible = context.preemptible,
            job = currentCoroutineContext()[Job],
        )
        try {
            while (true) {
                var pauseMs = 0L
                var acquired = false
                lane.lock.withLock {
                    lane.limit = provider.maxConcurrency.coerceIn(1, 8)
                    val now = clock()
                    if (lane.pausedUntil > now) {
                        pauseMs = lane.pausedUntil - now
                        return@withLock
                    }
                    // 先把空出的许可交给已在排队的请求，再考虑新请求。
                    grantWaitersLocked(lane)
                    if (waiter.granted) {
                        acquired = true
                        return@withLock
                    }
                    if (lane.active < lane.limit && lane.waiting.none { !it.cancelled }) {
                        lane.active++
                        lane.running[id] = Running(
                            priority = context.priority,
                            startedAt = now,
                            preemptible = context.preemptible,
                            job = currentCoroutineContext()[Job],
                        )
                        acquired = true
                        return@withLock
                    }
                    if (context.priority.foreground) {
                        val victim = lane.running.values
                            .filter { it.preemptible }
                            .maxWithOrNull(compareBy<Running>({ it.priority.level }, { it.startedAt }))
                        victim?.job?.cancel(CancellationException("preempted by foreground"))
                    }
                    if (waiter !in lane.waiting) lane.waiting.add(waiter)
                }
                if (acquired) return
                if (pauseMs > 0L) {
                    delay(pauseMs)
                    continue
                }
                try {
                    waiter.wake.receive()
                } catch (e: CancellationException) {
                    waiter.cancelled = true
                    throw e
                }
                currentCoroutineContext().ensureActive()
            }
        } catch (e: CancellationException) {
            waiter.cancelled = true
            withContext(NonCancellable) {
                lane.lock.withLock {
                    lane.waiting.remove(waiter)
                    if (waiter.granted && lane.running.remove(waiter.id) != null) {
                        lane.active = (lane.active - 1).coerceAtLeast(0)
                        grantWaitersLocked(lane)
                    }
                }
            }
            throw e
        }
    }

    private suspend fun release(lane: Lane, id: Long) {
        // 取消中的协程也必须能归还许可：释放路径不可取消。
        withContext(NonCancellable) {
            lane.lock.withLock {
                lane.running.remove(id)
                lane.active = (lane.active - 1).coerceAtLeast(0)
                grantWaitersLocked(lane)
            }
        }
    }

    /** 在持有 lane.lock 时调用：把空闲许可直接分配给队首（按优先级/FIFO）。 */
    private fun grantWaitersLocked(lane: Lane) {
        if (lane.pausedUntil > clock()) return
        while (lane.active < lane.limit && lane.waiting.isNotEmpty()) {
            val waiter = lane.waiting.poll() ?: break
            if (waiter.cancelled) continue
            waiter.granted = true
            lane.active++
            lane.running[waiter.id] = Running(
                priority = waiter.priority,
                startedAt = clock(),
                preemptible = waiter.preemptible,
                job = waiter.job,
            )
            waiter.wake.trySend(Unit)
        }
    }
}