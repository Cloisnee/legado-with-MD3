package com.github.jing332.compat.log

import android.util.Log

/**
 * 轻量日志垫片：替代 kotlin-logging（io.github.oshai），支持原调用风格：
 *   KLog.logger("tag").debug { "..." }
 *   KLog.logger("tag").error(e) { "..." }
 *   KLog.logger("tag").atDebug { message = "..." }
 */
object KLog {
    fun logger(name: String): KLogger = KLogger(name)
}

class KLogger(private val tag: String) {
    fun trace(message: () -> String) { Log.v(tag, message()) }
    fun debug(message: () -> String) { Log.d(tag, message()) }
    fun info(message: () -> String) { Log.i(tag, message()) }
    fun warn(message: () -> String) { Log.w(tag, message()) }
    fun error(message: () -> String) { Log.e(tag, message()) }
    fun error(t: Throwable, message: () -> String) { Log.e(tag, message(), t) }
    fun error(t: Throwable) { Log.e(tag, t.message, t) }

    fun atTrace(block: MessageBuilder.() -> Unit) { Log.v(tag, MessageBuilder().apply(block).message) }
    fun atDebug(block: MessageBuilder.() -> Unit) { Log.d(tag, MessageBuilder().apply(block).message) }
    fun atInfo(block: MessageBuilder.() -> Unit) { Log.i(tag, MessageBuilder().apply(block).message) }
    fun atWarn(block: MessageBuilder.() -> Unit) { Log.w(tag, MessageBuilder().apply(block).message) }
    fun atError(block: MessageBuilder.() -> Unit) { Log.e(tag, MessageBuilder().apply(block).message) }

    class MessageBuilder { var message: String = "" }
}
