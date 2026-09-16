package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.store.TtsConfigStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        sb.appendLine("✓ 合成字节=${bytes.size}  首12字节=${bytes.take(12).joinToString(" ") { "%02X".format(it) }}")

        val out = if (needsWavWrap(bytes)) {
            val wrapped = wrapPcmInWav(bytes, found.sampleRate)
            sb.appendLine("→ 已包 WAV 头 total=${wrapped.size}")
            wrapped
        } else bytes
        return Result(out, sb.toString())
    }

    private fun needsWavWrap(b: ByteArray): Boolean {
        if (b.size >= 4 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) return false
        if (b.size >= 2 && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xE0) == 0xE0) return false
        if (b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) return false
        return true
    }

    /** 直连试听（不依赖配置列表条目）：按 插件 + locale + voice 合成 */
    fun runDirect(
        context: Context,
        pluginId: String,
        locale: String,
        voice: String,
        text: String,
        timeoutMs: Long = 30_000L,
    ): Result {
        val sb = StringBuilder()
        sb.appendLine("pluginId=$pluginId  locale=$locale  voice=$voice")
        sb.appendLine("text=${text.take(60)}")

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

        val bytes = try {
            runBlocking {
                withTimeout(timeoutMs) {
                    engine.getAudio(text, locale, voice, 1f, 1f, 1f).readBytes()
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
        sb.appendLine("✓ 合成字节=${bytes.size}  首12字节=${bytes.take(12).joinToString(" ") { "%02X".format(it) }}")

        val out = if (needsWavWrap(bytes)) {
            val wrapped = wrapPcmInWav(bytes, 24000)
            sb.appendLine("→ 已包 WAV 头(24000) total=${wrapped.size}")
            wrapped
        } else bytes
        return Result(out, sb.toString())
    }

    private fun wrapPcmInWav(pcm: ByteArray, sampleRate: Int): ByteArray {
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
