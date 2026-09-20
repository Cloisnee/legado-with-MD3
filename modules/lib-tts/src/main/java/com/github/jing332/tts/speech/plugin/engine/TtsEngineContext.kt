package com.github.jing332.tts.speech.plugin.engine

import android.content.Context
import androidx.annotation.Keep
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.compat.log.KLog
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.script.simple.SimpleScriptEngine
import com.github.jing332.script.simple.ext.JsExtensions
import com.github.jing332.script.source.toScriptSource
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.store.TtsConfigStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    companion object {
        private const val TAG = "TtsEngineContext"
        const val AUDITION_TIMEOUT = 30_000L
    }

    /** 合成结果：成功给音频字节，失败给可读原因（供朗读日志「音频缓存」分区展示） */
    data class SynthOutcome(val bytes: ByteArray?, val reason: String? = null) {
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
        val f = File(dir, "audition_${tag}_${System.currentTimeMillis()}.${sniffExt(bytes)}")
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
        // 同一插件引擎串行合成：并发会互相覆盖 engine.source / 抢占异步流（实测 duihuaA01 偶发 No data written）
        return engine.synthesisLock.withLock { synthesizeLocked(engine, found, text) }
    }

    private fun synthesizeLocked(
        engine: TtsPluginEngineV2,
        found: TtsConfigStore.FoundConfig,
        text: String,
    ): SynthOutcome {
        // 注入插件特殊参数（source.data）与音频参数，插件侧经 ttsrv.tts.data / .speed 等读取
        engine.source = PluginTtsSource(
            locale = found.locale,
            voice = found.voice,
            pluginId = found.pluginId,
            speed = found.speed,
            volume = found.volume,
            pitch = found.pitch,
            data = found.sourceData,
        )
        val rate = found.speed.takeIf { it > 0f } ?: 1f
        val volume = found.volume.takeIf { it > 0f } ?: 1f
        val pitch = found.pitch.takeIf { it > 0f } ?: 1f
        val bytes = try {
            runBlocking {
                withTimeout(synthTimeoutMs) {
                    engine.getAudio(text, found.locale, found.voice, rate, volume, pitch).readBytes()
                }
            }
        } catch (t: TimeoutCancellationException) {
            return SynthOutcome(null, "合成超时(${synthTimeoutMs}ms)")
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            return SynthOutcome(null, "合成异常: ${t.message ?: t.javaClass.simpleName}")
        }
        if (bytes.isEmpty()) return SynthOutcome(null, "合成返回空音频")
        return SynthOutcome(if (needsWavWrap(bytes)) wrapPcmInWav(bytes, found.sampleRate) else bytes)
    }

    private fun sniffExt(b: ByteArray): String = when {
        b.size >= 4 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() -> "wav"
        b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0 -> "mp3"
        b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> "mp3"
        else -> "bin"
    }

    private fun needsWavWrap(b: ByteArray): Boolean = sniffExt(b) == "bin"

    /** PCM(16bit 单声道) 包 WAV 头（对齐补丁版 wrapPcmInWav 语义） */
    fun wrapPcmInWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val sr = sampleRate.takeIf { it > 0 } ?: 24000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sr)
        header.putInt(sr * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray())
        header.putInt(pcm.size)
        return header.array() + pcm
    }
}
