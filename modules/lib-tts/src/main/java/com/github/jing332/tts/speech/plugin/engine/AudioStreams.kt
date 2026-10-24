package com.github.jing332.tts.speech.plugin.engine

import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/** 0B/空流重试前的统一冷却（试听与朗读链共用）。 */
internal const val EMPTY_RETRY_DELAY_MS = 400L

/** 插件 callback.close() 但一个字节都没写：属于「空流」，值得按冷启动 0B 重试。 */
internal fun Throwable.isNoDataWrittenError(): Boolean =
    this is java.io.IOException && message == JsBridgeInputStream.ERROR_NO_DATA_WRITTEN

/**
 * 读流 watchdog：到点 close 流，让阻塞的 readBytes 立刻返回。
 * 返回 (bytes, timedOut)；超时返回 (null, true)，其他异常原样抛出。
 */
internal fun readAllWithTimeout(stream: InputStream, timeoutMs: Long): Pair<ByteArray?, Boolean> {
    val timedOut = AtomicBoolean(false)
    val watchdog = Thread {
        try {
            Thread.sleep(timeoutMs.coerceAtLeast(1L))
            timedOut.set(true)
            runCatching { stream.close() }
        } catch (_: InterruptedException) {
        }
    }
    watchdog.isDaemon = true
    watchdog.start()
    return try {
        stream.readBytes() to false
    } catch (t: Throwable) {
        if (timedOut.get()) null to true else throw t
    } finally {
        watchdog.interrupt()
        runCatching { stream.close() }
    }
}
