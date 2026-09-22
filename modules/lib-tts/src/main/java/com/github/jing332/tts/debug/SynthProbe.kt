package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.common.audio.AudioSniffer
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.store.TtsConfigStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 试听合成诊断器：逐步执行并返回完整报告（失败时定位到具体环节）。
 * 供 管理中心试听 / _ops cmd_synth 共用。
 */
object SynthProbe {

    data class Result(val bytes: ByteArray?, val report: String)

    fun run(
        context: Context,
        engineId: String,
        tag: String,
        text: String,
        timeoutMs: Long = 30_000L,
    ): Result {
        val sb = StringBuilder()
        sb.appendLine("engineId=$engineId  tag=$tag")
        sb.appendLine("text=${text.take(60)}")

        val found = try {
            TtsConfigStore.findConfig(context, engineId, tag)
        } catch (t: Throwable) {
            sb.appendLine("✗ findConfig 异常: $t")
            return Result(null, sb.toString())
        }
        if (found == null) {
            sb.appendLine("✗ 未找到配置（tagRuleId=$engineId + tag=$tag 不匹配）")
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 配置: ${found.displayName} | plugin=${found.pluginId} | locale=${found.locale} | voice=${found.voice} | sr=${found.sampleRate}")

        val pj = TtsConfigStore.pluginById(context, found.pluginId)
        if (pj == null) {
            sb.appendLine("✗ 插件不存在: ${found.pluginId}")
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 插件: ${pj.optString("name")} | enabled=${pj.optBoolean("isEnabled", true)}")

        val engine = try {
            TtsPluginEngineManager.get(context, TtsConfigStore.toEnginePlugin(pj))
        } catch (t: Throwable) {
            sb.appendLine("✗ 引擎加载(eval) 失败: ${t.javaClass.name}: ${t.message}")
            sb.appendLine(t.stackTraceToString().take(6000))
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 引擎加载完成")

        engine.source = PluginTtsSource(
            locale = found.locale,
            voice = found.voice,
            pluginId = found.pluginId,
            speed = found.speed,
            volume = found.volume,
            pitch = found.pitch,
            data = found.sourceData,
        )
        sb.appendLine("✓ 已注入 source.data=${found.sourceData}")

        val rate = found.speed.takeIf { it > 0f } ?: 1f
        val volume = found.volume.takeIf { it > 0f } ?: 1f
        val pitch = found.pitch.takeIf { it > 0f } ?: 1f

        val bytes = try {
            runBlocking {
                withTimeout(timeoutMs) {
                    engine.getAudio(text, found.locale, found.voice, rate, volume, pitch).readBytes()
                }
            }
        } catch (t: Throwable) {
            sb.appendLine("✗ 合成异常: ${t.javaClass.name}: ${t.message}")
            sb.appendLine(t.stackTraceToString().take(6000))
            return Result(null, sb.toString())
        }
        if (bytes.isEmpty()) {
            sb.appendLine("✗ 合成结果为空(0B)")
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 合成字节=${bytes.size}  首12字节=${bytes.take(12).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")

        val sniffed = AudioSniffer.normalizeForPlayback(bytes, found.sampleRate)
        sb.appendLine(
            if (sniffed.wrappedPcm) {
                "→ 未识别格式：按裸 PCM 包 WAV 头(${found.sampleRate} Hz) total=${sniffed.bytes.size}"
            } else {
                "→ 容器格式直通(${sniffed.kind.name}) total=${sniffed.bytes.size}"
            }
        )
        return Result(sniffed.bytes, sb.toString())
    }

    /** 直连试听（不依赖配置列表条目）：按 插件 + locale + voice 合成 */
    fun runDirect(
        context: Context,
        pluginId: String,
        locale: String,
        voice: String,
        text: String,
        speed: Float = 1f,
        volume: Float = 1f,
        pitch: Float = 1f,
        data: Map<String, String> = emptyMap(),
        sampleRate: Int = 24000,
        timeoutMs: Long = 30_000L,
    ): Result {
        val sb = StringBuilder()
        sb.appendLine("pluginId=$pluginId  locale=$locale  voice=$voice")
        sb.appendLine("text=${text.take(60)}")
        sb.appendLine("speed=$speed volume=$volume pitch=$pitch data=$data")

        val pj = TtsConfigStore.pluginById(context, pluginId)
        if (pj == null) {
            sb.appendLine("✗ 插件不存在: $pluginId")
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 插件: ${pj.optString("name")} | enabled=${pj.optBoolean("isEnabled", true)}")

        val engine = try {
            TtsPluginEngineManager.get(context, TtsConfigStore.toEnginePlugin(pj))
        } catch (t: Throwable) {
            sb.appendLine("✗ 引擎加载(eval) 失败: ${t.javaClass.name}: ${t.message}")
            sb.appendLine(t.stackTraceToString().take(6000))
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 引擎加载完成")

        engine.source = PluginTtsSource(
            locale = locale,
            voice = voice,
            pluginId = pluginId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            data = data,
        )

        val bytes = try {
            runBlocking {
                withTimeout(timeoutMs) {
                    engine.getAudio(text, locale, voice, speed, volume, pitch).readBytes()
                }
            }
        } catch (t: Throwable) {
            sb.appendLine("✗ 合成异常: ${t.javaClass.name}: ${t.message}")
            sb.appendLine(t.stackTraceToString().take(6000))
            return Result(null, sb.toString())
        }
        if (bytes.isEmpty()) {
            sb.appendLine("✗ 合成结果为空(0B)")
            return Result(null, sb.toString())
        }
        sb.appendLine("✓ 合成字节=${bytes.size}  首12字节=${bytes.take(12).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")

        val sniffed = AudioSniffer.normalizeForPlayback(bytes, sampleRate)
        sb.appendLine(
            if (sniffed.wrappedPcm) {
                "→ 未识别格式：按裸 PCM 包 WAV 头(${sampleRate} Hz) total=${sniffed.bytes.size}"
            } else {
                "→ 容器格式直通(${sniffed.kind.name}) total=${sniffed.bytes.size}"
            }
        )
        return Result(sniffed.bytes, sb.toString())
    }
}
