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

/** 同一批次跨阶段共用：前台接管后后续请求也保持前台优先级。 */
class AiRequestControl {
    @Volatile var priority: AiPriority? = null
        private set
    @Volatile var protected: Boolean = false
        private set
    @Volatile var running: Boolean = false
        private set
    @Volatile private var owner: Job? = null
    @Volatile private var takeQueued: Boolean = false

    @Synchronized fun bind(job: Job?) {
        owner = job
        if (takeQueued) job?.cancel(CancellationException("foreground takes queued batch"))
    }
    @Synchronized fun started() { running = true }
    @Synchronized fun finished() { running = false }
    @Synchronized fun foreground() {
        priority = AiPriority.P0_CURRENT
        if (protected) return
        protected = true
        // 还未发网的批次让出目标章，前台重取；已经读流的批次继续逐章提交。
        if (!running) {
            takeQueued = true
            owner?.cancel(CancellationException("foreground takes queued batch"))
        }
    }
}

/** 一次 AI 请求的调度上下文（阶段、优先级、书籍/章节范围、是否可被前台抢占）。 */
data class AiSchedulerContext(
    val kind: AiTaskKind,
    val priority: AiPriority,
    val bookKey: String,
    val chapters: List<Int>,
    val preemptible: Boolean,
    val control: AiRequestControl? = null,
) {
    val effectivePriority: AiPriority get() = control?.priority ?: priority
    val canPreempt: Boolean get() = preemptible && control?.protected != true
}

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
        val waiting = ArrayList<Waiter>()
        val running = HashMap<Long, Running>()
    }

    private class Waiter(
        val id: Long,
        val context: AiSchedulerContext,
        val seq: Long,
        val job: Job?,
    ) {
        val wake = Channel<Unit>(Channel.CONFLATED)
        @Volatile
        var cancelled = false

        @Volatile
        var granted = false
    }

    private class Running(
        val context: AiSchedulerContext,
        val startedAt: Long,
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
            context.control?.finished()
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
        val waiter = Waiter(id, context, seq.incrementAndGet(), currentCoroutineContext()[Job])
        context.control?.bind(waiter.job)
        try {
            lane.lock.withLock {
                lane.limit = provider.maxConcurrency.coerceIn(1, 8)
                // 暂停期也入队，恢复时优先级/FIFO 不受唤醒时序影响。
                lane.waiting.add(waiter)
                grantWaitersLocked(lane)
                if (!waiter.granted && context.effectivePriority.foreground) {
                    lane.running.values.filter { it.context.canPreempt }
                        .maxWithOrNull(compareBy<Running>(
                            { it.context.effectivePriority.level }, { it.startedAt },
                        ))?.job?.cancel(CancellationException("preempted by foreground"))
                }
            }
            waiter.wake.receive()
            currentCoroutineContext().ensureActive()
            context.control?.started()
        } catch (e: CancellationException) {
            waiter.cancelled = true
            withContext(NonCancellable) {
                lane.lock.withLock {
                    lane.waiting.remove(waiter)
                    if (lane.running.remove(waiter.id) != null) lane.active--
                    grantWaitersLocked(lane)
                }
            }
            throw e
        }
    }

    private suspend fun release(lane: Lane, id: Long) {
        // 取消中的协程也必须能归还许可：释放路径不可取消。
        withContext(NonCancellable) {
            lane.lock.withLock {
                if (lane.running.remove(id) != null) lane.active--
                grantWaitersLocked(lane)
            }
        }
    }

    /** 在持有 lane.lock 时调用：把空闲许可直接分配给队首（按优先级/FIFO）。 */
    private fun grantWaitersLocked(lane: Lane) {
        if (lane.pausedUntil > clock()) return
        while (lane.active < lane.limit && lane.waiting.isNotEmpty()) {
            val waiter = lane.waiting.minWithOrNull(
                compareBy<Waiter>({ it.context.effectivePriority.level }, { it.seq }),
            ) ?: break
            lane.waiting.remove(waiter)
            if (waiter.cancelled) continue
            waiter.granted = true
            lane.active++
            lane.running[waiter.id] = Running(
                context = waiter.context,
                startedAt = clock(),
                job = waiter.job,
            )
            waiter.wake.trySend(Unit)
        }
    }
}