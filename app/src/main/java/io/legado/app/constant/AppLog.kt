package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import org.koin.core.context.GlobalContext
import splitties.init.appCtx

object AppLog {

    data class LogEntry(
        val timestamp: Long,
        val message: String,
        val throwable: Throwable?,
        val verbose: Boolean,
    )

    private val otherGateway by lazy { GlobalContext.get().get<OtherSettingsGateway>() }

    private val mLogs = arrayListOf<LogEntry>()

    val logs get() = mLogs.toList()

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putInternal(message, throwable, toast = toast, verbose = false)
    }

    /** 详细日志（文本分析各阶段流程/结果等）：日志页「简」模式隐藏，「详」模式可见 */
    @Synchronized
    fun putVerbose(message: String?, throwable: Throwable? = null) {
        putInternal(message, throwable, toast = false, verbose = true)
    }

    @Synchronized
    private fun putInternal(message: String?, throwable: Throwable?, toast: Boolean, verbose: Boolean) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 400) {
            mLogs.removeLastOrNull()
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), message, throwable, verbose))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun putNotSave(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (mLogs.size > 400) {
            mLogs.removeLastOrNull()
        }
        mLogs.add(0, LogEntry(System.currentTimeMillis(), message, throwable, verbose = false))
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (otherGateway.currentSettings.recordLog) {
            putVerbose(message, throwable)
        }
    }
}
