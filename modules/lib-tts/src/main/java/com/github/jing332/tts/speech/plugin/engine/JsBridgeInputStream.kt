package com.github.jing332.tts.speech.plugin.engine

import androidx.annotation.Keep
import com.github.jing332.script.exception.ScriptException
import com.github.jing332.compat.log.KLog
import kotlinx.coroutines.sync.Mutex
import org.mozilla.javascript.Context
import org.mozilla.javascript.Wrapper
import org.mozilla.javascript.typedarrays.NativeArrayBuffer
import org.mozilla.javascript.typedarrays.NativeUint8Array
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class JsBridgeInputStream : InputStream() {
    companion object {
        private const val TAG = "JsBridgeInputStream"
        private val logger = KLog.logger(TAG)
    }

    private val pis: PipedInputStream = PipedInputStream()
    private val pos: PipedOutputStream = PipedOutputStream(pis)
    private var isClosed = false
    private var errorCause: Exception? = null
    private val hasError: Boolean
        get() = errorCause != null

    /** 宿主侧 close/cancel 兜底解锁：插件不一定走到 callback.close()。 */
    @Volatile
    private var callbackMutex: Mutex? = null
    private val lockReleased = AtomicBoolean(false)

    private fun releaseLock() {
        val mutex = callbackMutex ?: return
        if (lockReleased.compareAndSet(false, true)) {
            runCatching { if (mutex.isLocked) mutex.unlock() }
        }
    }

    private fun checkError() {
        errorCause?.let {
            throw it
        }
    }

    override fun read(): Int {
        checkError()
        if (isClosed) {
            val available = runCatching { pis.available() }.getOrDefault(0)
            if (available == 0) return -1 // Signal end of stream
        }

        try {
            val byte = pis.read() // Reads a single byte
            checkError()
            return byte
        } catch (e: IOException) {
            if (isClosed) return -1
            errorCause = e
            throw e
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkError()
        if (isClosed) {
            val available = runCatching { pis.available() }.getOrDefault(0)
            if (available == 0) return -1 // Check for EOF *before* blocking read. Crucial!
        }
        if (off < 0 || len < 0 || len > b.size - off) {
            throw IndexOutOfBoundsException()
        } else if (len == 0) {
            return 0
        }

        try {
            val byte = pis.read(b, off, len) // Reads up to 'len' bytes into the buffer
            checkError()
            return byte
        } catch (e: IOException) {
            if (isClosed) return -1
            errorCause = e
            throw e
        }
    }

    override fun available(): Int {
        checkError()
        return runCatching { pis.available() }.getOrDefault(0)
    }

    @Synchronized
    override fun close() {
        if (!isClosed) {
            isClosed = true
            try {
                pos.close() // Close output end first!  Very important.
            } catch (_: IOException) {
            } finally {
                try {
                    pis.close() // Then close the input end.
                } catch (_: IOException) {
                }
            }
        }
        // 宿主主动关闭/取消时，必须释放流锁；否则下一次 getCallback 永远卡在 lock()。
        releaseLock()
    }

    /**
     * 接口 for JavaScript to interact with the OutputStream.  The names
     * and signatures MUST match your Kotlin definitions.
     */
    @Keep
    interface Callback {
        fun write(data: Any?)
        fun close()
        fun error(data: Any?)
    }

    suspend fun getCallback(mutex: Mutex): Callback {
        callbackMutex = mutex
        mutex.lock()
        return object : Callback {
            private var length = 0
            private fun writeBytes(data: ByteArray) {
                length += data.size
                logger.debug { "write(${data.size}) byteWritten: $length" }

                if (isClosed || hasError) return

                try {
                    pos.write(data)
                    pos.flush()
                } catch (e: IOException) {
                    errorCause = e
                    try {
                        close()
                    } catch (ignored: IOException) {
                    }
                }
            }

            override fun write(data: Any?) {
                when (data) {
                    is ByteArray -> writeBytes(data)
                    is String -> writeBytes(data.toByteArray())
                    is NativeUint8Array -> write(data.buffer.buffer)
                    is NativeArrayBuffer -> write(data.buffer)
                    // 防御性解包：兼容 Java 互操作对象（NativeJavaObject/NativeJavaArray 等 Wrapper）
                    is Wrapper -> when (val unwrapped = data.unwrap()) {
                        is ByteArray -> writeBytes(unwrapped)
                        is String -> writeBytes(unwrapped.toByteArray())
                    }
                }

            }

            override fun close() {
                logger.debug { "close" }

                try {
                    if (length <= 0) errorCause = IOException("No data written")
                    this@JsBridgeInputStream.close()
                } catch (e: IOException) {
                    errorCause = e
                }
            }

            override fun error(data: Any?) {
                logger.debug { "error(${data})" }

                errorCause = Context.reportRuntimeError(data.toString()).run {
                    ScriptException(
                        sourceName = sourceName(),
                        lineNumber = lineNumber(),
                        columnNumber = columnNumber(),
                        message = message,
                        cause = this
                    )
                }
                try {
                    close()
                } catch (ignored: IOException) {
                }
            }
        }
    }
}
