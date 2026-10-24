package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import androidx.annotation.Keep
import com.github.jing332.common.audio.AudioSniffer
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.compat.log.KLog
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.simple.SimpleScriptEngine
import com.github.jing332.tts.debug.PipelineProbe
import com.github.jing332.script.simple.ext.JsExtensions
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.store.TtsConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.jvm.Transient

/**
 * 插件运行时上下文（JS 中通过 `ttsrv` 访问）。
 * 管理型 API 与补丁版 1.26.08220706 对齐（存储侧换为 TtsConfigStore）：
 *   getAudioByTag / getVoiceByTag / getVoiceNamesByTags / deleteConfigByTag /
 *   updateConfigDisplayName / getSpeechRuleList / runSpeechRule
 *
 * @param tts 在JS中用 `ttsrv.tts` 访问
 */
@Keep
data class TtsEngineContext(
    var tts: PluginTtsSource,
    val userVars: Map<String, String> = mutableMapOf(),
    override val context: Context,
    override val engineId: String,
    /** 单次合成请求超时（毫秒；朗读侧按设置注入，默认 30s） */
    val synthTimeoutMs: Long = AUDITION_TIMEOUT
) : JsExtensions(context, engineId) {

    /**
     * 宿主注入的后台执行器：在带 Rhino Context 的线程里执行 JS 函数。
     * 插件侧 `ttsrv.runAsync(fn)` 用它做「票据预取」这类不阻塞当前合成的后台任务。
     * 直接在插件里 `new java.lang.Thread(...)` 调 JS 函数会缺 Context（Rhino 抛
     * "no Context associated with current thread"），异常又被插件吞掉 → 预取静默失效。
     */
    @Transient
    var asyncRunner: ((org.mozilla.javascript.Function) -> Unit)? = null

    /** 插件侧异步执行入口：`ttsrv.runAsync(function(){ ... })` */
    fun runAsync(fn: Any?) {
        val f = fn as? org.mozilla.javascript.Function
            ?: throw IllegalArgumentException("runAsync 需要传入函数")
        val runner = asyncRunner ?: throw IllegalStateException("runAsync 未初始化")
        runner(f)
    }

    companion object {
        private const val TAG = "TtsEngineContext"
        const val AUDITION_TIMEOUT = 120_000L
    }

    /** 合成结果：成功给音频字节，失败给可读原因（供朗读日志「音频缓存」分区展示） */
    data class SynthOutcome(
        val bytes: ByteArray? = null,
        val reason: String? = null,
        /** 这次失败是「流读完但 0 字节」——冷启动认证窗口的特征，值得重试一次 */
        val emptyBytes: Boolean = false,
    ) {
        val ok: Boolean get() = bytes != null
    }

    // ==================== 管理型 API（插件 UI 桥） ====================

    /**
     * 按标签合成音频（试听）。
     * 成功返回生成的音频文件绝对路径（落盘于 <数据根>/_audition/）；失败返回 null。
     * 注意：与原版不同，文件持久保留（便于手机侧用 MT 直接播放/检查）。
     */
    fun getAudioByTag(tag: String, text: String): String? = tryImpl("getAudioByTag") {
        if (tag.isBlank()) return@tryImpl null
        val outcome = synthesizeByTag(tag, text)
        val bytes = outcome.bytes ?: run {
            KLog.logger(TAG).debug { "getAudioByTag 失败: ${outcome.reason}" }
            return@tryImpl null
        }
        val dir = File(TtsDirProvider.baseDir(context), "_audition").apply { mkdirs() }
        val ext = AudioSniffer.extName(AudioSniffer.sniff(bytes))
        val f = File(dir, "audition_${tag}_${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        f.absolutePath
    }

    /** 查标签当前绑定的声线显示名 */
    fun getVoiceByTag(tag: String): String? = tryImpl("getVoiceByTag") {
        TtsConfigStore.findConfig(context, engineId, tag)?.displayName
    }

    /** 批量查标签绑定的声线显示名（输入 JSON 数组字符串，输出 JSON 数组字符串） */
    fun getVoiceNamesByTags(tagsJson: String): String? = tryImpl("getVoiceNamesByTags") {
        val input = JSONArray(tagsJson)
        val out = JSONArray()
        for (i in 0 until input.length()) {
            val name = TtsConfigStore.findConfig(context, engineId, input.optString(i))?.displayName
            out.put(name ?: "")
        }
        out.toString()
    }

    /** 删除标签对应配置。返回 ""(成功以 OK 表示) / 错误信息 */
    fun deleteConfigByTag(tag: String): String? = tryImpl("deleteConfigByTag") {
        if (tag.isBlank()) return@tryImpl "tag为空"
        if (TtsConfigStore.deleteConfig(context, engineId, tag)) "OK" else "未找到配置项"
    }

    /** 修改标签对应配置的显示名 */
    fun updateConfigDisplayName(tag: String, newName: String): String? = tryImpl("updateConfigDisplayName") {
        if (tag.isBlank()) return@tryImpl "tag为空"
        if (newName.isBlank()) return@tryImpl "新名称为空"
        if (TtsConfigStore.updateDisplayName(context, engineId, tag, newName)) "OK" else "未找到配置项"
    }

    /** 朗读规则列表 JSON（[{name, ruleId}]） */
    fun getSpeechRuleList(): String? = tryImpl("getSpeechRuleList") {
        TtsConfigStore.speechRuleListJson(context)
    }

    /** 执行朗读规则脚本（基础支撑；找不到规则时返回可读错误） */
    fun runSpeechRule(ruleId: String): String? = tryImpl("runSpeechRule") {
        val rule = TtsConfigStore.findSpeechRule(context, ruleId) ?: return@tryImpl "未找到 ruleId=$ruleId"
        try {
            SimpleScriptEngine(context, ruleId).execute(rule.optString("code").toScriptSource(ruleId))
            "OK"
        } catch (t: Throwable) {
            "runSpeechRule failed: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    // ==================== 内部实现 ====================

    private fun <T> tryImpl(op: String, block: () -> T?): T? =
        try {
            block()
        } catch (t: Throwable) {
            KLog.logger(TAG).debug { "$op failed: ${t.message ?: t.javaClass.simpleName}" }
            null
        }

    /** 按 tag → 配置 → 插件 合成（音频字节）；internal：供 TtsServerSynthesizer 复用 */
    internal fun synthesizeByTag(tag: String, text: String): SynthOutcome {
        val found = TtsConfigStore.findConfig(context, engineId, tag)
            ?: return SynthOutcome(null, "标签未找到配置(engineId=$engineId, tag=$tag)")
        val pluginJson = TtsConfigStore.pluginById(context, found.pluginId)
            ?: return SynthOutcome(null, "插件不存在(${found.pluginId})")
        val engine = TtsPluginEngineManager.get(context, TtsConfigStore.toEnginePlugin(pluginJson))
        // 同一插件引擎串行合成；tryLock 防止一次卡死把整条朗读链永久占锁。
        return withSynthesisLock(engine, { reason -> SynthOutcome(null, reason) }) {
            synthesizeWithEmptyRetry(engine, found, text)
        }
    }

    /**
     * 批212c · 按「精确条目 config」合成（朗读链专用）：
     * 同名标签在多池间共用 tagRuleId（如同为 mingwuyan）——(engineId, tag) 二次查找会命中旧池条目；
     * 本入口直接使用条目自身的 config（source/audioParams/audioFormat），从根上杜绝二义。
     * configJson 来自声线目录条目的 traitsJson（= `_store/voices.json` 条目的 config）。
     */
    internal fun synthesizeByConfig(
        configJson: String,
        text: String,
        /** 阅读朗读情绪（空 = 无）；桥给插件 `ttsrv.tts.emotion` */
        emotion: String = "",
    ): SynthOutcome {
        val found = runCatching {
            val cfg = JSONObject(configJson)
            val src = cfg.optJSONObject("source")
                ?: error("条目配置缺少 source")
            val ap = cfg.optJSONObject("audioParams")
            val data = src.optJSONObject("data")?.let { d ->
                buildMap {
                    val ks = d.keys()
                    while (ks.hasNext()) {
                        val k = ks.next()
                        put(k, d.optString(k))
                    }
                }
            } ?: emptyMap()
            TtsConfigStore.FoundConfig(
                displayName = "",
                tag = "",
                tagName = "",
                pluginId = src.optString("pluginId"),
                locale = src.optString("locale"),
                voice = src.optString("voice"),
                speed = (ap?.opt("speed") as? Number)?.toFloat() ?: 1f,
                volume = (ap?.opt("volume") as? Number)?.toFloat() ?: 1f,
                pitch = (ap?.opt("pitch") as? Number)?.toFloat() ?: 1f,
                sampleRate = cfg.optJSONObject("audioFormat")?.optInt("sampleRate", 24000) ?: 24000,
                sourceData = data,
            )
        }.getOrElse { return SynthOutcome(null, "条目配置解析失败: ${it.localizedMessage}") }
        if (found.pluginId.isBlank() || (found.voice.isBlank() && found.locale.isBlank())) {
            return SynthOutcome(null, "条目配置缺少插件/声线参数")
        }
        val pluginJson = TtsConfigStore.pluginById(context, found.pluginId)
            ?: return SynthOutcome(null, "插件不存在(${found.pluginId})")
        val engine = TtsPluginEngineManager.get(context, TtsConfigStore.toEnginePlugin(pluginJson))
        return withSynthesisLock(engine, { reason -> SynthOutcome(null, reason) }) {
            synthesizeWithEmptyRetry(engine, found, text, emotion)
        }
    }

    /**
     * 引擎锁 + 5s 等锁的统一封装。
     * 超时/中断返回 [onUnavailable]；正常路径无论异常都 unlock（历史红线：不能 withLock 死等）。
     */
    private inline fun <T> withSynthesisLock(
        engine: TtsPluginEngineV2,
        onUnavailable: (String) -> T,
        block: () -> T,
    ): T = try {
        if (!engine.synthesisLock.tryLock(5, TimeUnit.SECONDS)) {
            PipelineProbe.log(context, "synth.busy", "pluginId" to engine.plugin.pluginId)
            onUnavailable("合成忙（等待引擎锁超时）")
        } else {
            try {
                block()
            } finally {
                engine.synthesisLock.unlock()
            }
        }
    } catch (t: InterruptedException) {
        Thread.currentThread().interrupt()
        onUnavailable("合成被中断")
    }

    /**
     * 单次合成 + 0B 一次性重试（每个引擎实例只放行一次，避免永久坏插件每句两次）。
     * 必须由 [withSynthesisLock] 包住：重试要复用同一把锁和同一份 engine.source。
     */
    private fun synthesizeWithEmptyRetry(
        engine: TtsPluginEngineV2,
        found: TtsConfigStore.FoundConfig,
        text: String,
        emotion: String = "",
    ): SynthOutcome {
        val first = synthesizeLocked(engine, found, text, emotion)
        if (!first.emptyBytes || !engine.emptyRetried.compareAndSet(false, true)) return first
        PipelineProbe.log(
            context, "synth.empty.retry",
            "pluginId" to found.pluginId, "voice" to found.voice
        )
        runCatching { engine.cancelAudio() }
        Thread.sleep(EMPTY_RETRY_DELAY_MS)
        return synthesizeLocked(engine, found, text, emotion)
    }

    private fun synthesizeLocked(
        engine: TtsPluginEngineV2,
        found: TtsConfigStore.FoundConfig,
        text: String,
        emotion: String = "",
    ): SynthOutcome {
        // 注入插件特殊参数（source.data）、音频参数与情绪，插件侧经 ttsrv.tts.data / .speed / .emotion 读取
        engine.source = PluginTtsSource(
            locale = found.locale,
            voice = found.voice,
            pluginId = found.pluginId,
            speed = found.speed,
            volume = found.volume,
            pitch = found.pitch,
            data = found.sourceData,
            emotion = emotion,
        )
        val rate = found.speed.takeIf { it > 0f } ?: 1f
        val volume = found.volume.takeIf { it > 0f } ?: 1f
        val pitch = found.pitch.takeIf { it > 0f } ?: 1f
        val sampleRate = found.sampleRate.takeIf { it > 0 } ?: 24000
        // 启动超时只包 JS 调用；读流用独立 watchdog（close 触发 readBytes 返回），避免 30s 把长音频拦腰截断。
        val t0 = System.currentTimeMillis()
        PipelineProbe.log(
            context, "synth.start",
            "pluginId" to found.pluginId,
            "voice" to found.voice,
            "textLen" to text.length,
            "timeoutMs" to synthTimeoutMs
        )
        val cancelSeq = engine.currentCancelSeq
        val stream = try {
            runBlocking {
                engine.getAudio(
                    text, found.locale, found.voice, rate, volume, pitch,
                    timeoutMs = synthTimeoutMs.coerceAtMost(90_000L)
                )
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            runCatching { engine.cancelAudio() }
            PipelineProbe.log(
                context, "synth.error",
                "pluginId" to found.pluginId,
                "stage" to "startup",
                "ms" to (System.currentTimeMillis() - t0),
                "err" to (t.message ?: t.javaClass.simpleName)
            )
            return SynthOutcome(null, "合成启动异常: ${t.message ?: t.javaClass.simpleName}")
        }
        var bytes: ByteArray? = null
        var timedOut = false
        var readError: Throwable? = null
        try {
            val r = readAllWithTimeout(stream, synthTimeoutMs)
            bytes = r.first
            timedOut = r.second
        } catch (t: CancellationException) {
            runCatching { engine.cancelAudio() }
            throw t
        } catch (t: Throwable) {
            readError = t
        } finally {
            runCatching { stream.close() }
        }
        // 外部 cancelAudio 关闭流时 readBytes 返回空数组而不是异常，必须和冷启动 0B 分开。
        val cancelled = engine.currentCancelSeq != cancelSeq
        if (timedOut) {
            runCatching { engine.cancelAudio() }
            PipelineProbe.log(
                context, "synth.timeout",
                "pluginId" to found.pluginId,
                "ms" to (System.currentTimeMillis() - t0),
                "timeoutMs" to synthTimeoutMs
            )
            return SynthOutcome(null, "合成超时(${synthTimeoutMs}ms)")
        }
        if (cancelled) {
            runCatching { engine.cancelAudio() }
            PipelineProbe.log(context, "synth.cancelled", "pluginId" to found.pluginId)
            return SynthOutcome(null, "已取消（音频流被关闭）")
        }
        val err = readError
        if (err != null) {
            val noData = err.isNoDataWrittenError()
            runCatching { engine.cancelAudio() }
            PipelineProbe.log(
                context, "synth.error",
                "pluginId" to found.pluginId,
                "stage" to "read",
                "emptyBytes" to noData,
                "ms" to (System.currentTimeMillis() - t0),
                "err" to (err.message ?: err.javaClass.simpleName)
            )
            return SynthOutcome(
                null,
                "合成异常: ${err.message ?: err.javaClass.simpleName}",
                emptyBytes = noData,
            )
        }
        val data = bytes ?: ByteArray(0)
        if (data.isEmpty()) return SynthOutcome(null, "合成返回空音频", emptyBytes = true)
        PipelineProbe.log(
            context, "synth.end",
            "pluginId" to found.pluginId,
            "voice" to found.voice,
            "ms" to (System.currentTimeMillis() - t0),
            "bytes" to data.size
        )
        // 音频格式归一化（白噪音修复）：webm/ogg/amr 等容器直通播放器解码；仅未识别格式按裸 PCM 包 WAV 头
        val sniffed = AudioSniffer.normalizeForPlayback(data, sampleRate)
        if (sniffed.wrappedPcm) {
            KLog.logger(TAG).debug {
                "未识别音频格式(kind=${sniffed.kind.name}, 首8字节=${AudioSniffer.hexPreview(data)})" +
                        "，按裸 PCM 包 WAV 头(${sampleRate} Hz)"
            }
        }
        return SynthOutcome(sniffed.bytes)
    }
}
