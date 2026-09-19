package io.legado.app.constant

import android.util.Log
import io.legado.app.BuildConfig
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.utils.LogUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.context.GlobalContext
import splitties.init.appCtx

object AppLog {

    /**
     * 日志分区。朗读日志页按分区展示：
     *  - [ANALYSIS] → 「朗读分析流程」分区
     *  - [AUDIO] → 「音频缓存」分区
     *  - [GENERAL] 不进入朗读日志页，仅在全局日志弹层可见
     */
    enum class Category {
        GENERAL,
        ANALYSIS,
        AUDIO,
    }

    data class LogEntry(
        /** 单调递增序号：列表稳定 key（时间戳会重复） */
        val id: Long,
        val timestamp: Long,
        val message: String,
        val throwable: Throwable?,
        val category: Category,
    )

    /** 日志缓冲区上限（合成日志按条记录，留出足够回溯空间） */
    private const val MAX_LOGS = 800

    private val otherGateway by lazy { GlobalContext.get().get<OtherSettingsGateway>() }

    /** 按时间升序存储：索引 0 = 最旧，末尾 = 最新 */
    private val mLogs = arrayListOf<LogEntry>()
    private var nextId = 0L

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 实时日志流（升序），供朗读日志页边播边刷 */
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    /** 全量日志快照（升序） */
    val logs: List<LogEntry> get() = mLogs.toList()

    @Synchronized
    fun put(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putInternal(message, throwable, toast = toast, category = Category.GENERAL)
    }

    /** 朗读分析流程日志（话语分析 / 归属 / 历史对比 / 情绪等阶段进度与结果） */
    @Synchronized
    fun putAnalysis(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putInternal(message, throwable, toast = toast, category = Category.ANALYSIS)
    }

    /** 音频合成 / 缓存日志（合成结果、失败原因、静音占位、跳过原因等） */
    @Synchronized
    fun putAudio(message: String?, throwable: Throwable? = null, toast: Boolean = false) {
        putInternal(message, throwable, toast = toast, category = Category.AUDIO)
    }

    @Synchronized
    private fun putInternal(
        message: String?,
        throwable: Throwable?,
        toast: Boolean,
        category: Category,
    ) {
        message ?: return
        if (toast) {
            appCtx.toastOnUi(message)
        }
        if (throwable == null) {
            LogUtils.d("AppLog", message)
        } else {
            LogUtils.d("AppLog", "$message\n${throwable.stackTraceToString()}")
        }
        if (mLogs.size >= MAX_LOGS) {
            repeat(mLogs.size - MAX_LOGS + 1) { mLogs.removeAt(0) }
        }
        mLogs.add(LogEntry(++nextId, System.currentTimeMillis(), message, throwable, category))
        _logsFlow.value = mLogs.toList()
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
        if (mLogs.size >= MAX_LOGS) {
            repeat(mLogs.size - MAX_LOGS + 1) { mLogs.removeAt(0) }
        }
        mLogs.add(LogEntry(++nextId, System.currentTimeMillis(), message, throwable, Category.GENERAL))
        _logsFlow.value = mLogs.toList()
        if (BuildConfig.DEBUG) {
            val stackTrace = Thread.currentThread().stackTrace
            Log.e(stackTrace[3].className, message, throwable)
        }
    }

    @Synchronized
    fun clear() {
        mLogs.clear()
        _logsFlow.value = emptyList()
    }

    fun putDebug(message: String?, throwable: Throwable? = null) {
        if (otherGateway.currentSettings.recordLog) {
            put(message, throwable)
        }
    }
}
