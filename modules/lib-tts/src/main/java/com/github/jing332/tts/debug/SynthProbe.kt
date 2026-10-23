package com.github.jing332.tts.debug

import android.content.Context
import com.github.jing332.common.audio.AudioSniffer
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.TtsPluginEngineManager
import com.github.jing332.tts.speech.plugin.engine.TtsPluginEngineV2
import com.github.jing332.tts.speech.plugin.engine.readAllWithTimeout
import com.github.jing332.tts.store.TtsConfigStore
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 试听合成诊断器：逐步执行并返回完整报告（失败时定位到具体环节）。
 * 供 管理中心试听 / _ops cmd_synth 共用。
 *
 * v1.6（2026-10-23）：
 *  - 与朗读链共用引擎串行锁 [TtsPluginEngineV2.synthesisLock]：试听和朗读/并列试听不再互相覆盖
 *    `engine.source`（症状：点 A 播 B、换声线后仍是旧声线、偶发 0B）。
 *  - 0 字节结果自动重试一次：千问等插件在「新进程首次请求 + 票据/ident 刚重建」的窗口会先返回
 *    空流（`last_synth_result.txt` 里的 `合成结果为空(0B)`），第二次请求通常 1~3 秒成功。
 *  - 记录流类型 / available / 次数，落到 `_audition/pipeline_v2.log`（`audition.empty` / `audition.retry`）。
 *  - speed/volume/pitch <= 0 一律按 1 处理（条目里存 0 时不再把 0 传给插件）。
 */
object SynthProbe {

    data class Result(val bytes: ByteArray?, val report: String)

    /** 等引擎锁的上限：超时给可读报告，而不是无声卡死。 */
    private const val LOCK_WAIT_SECONDS = 30L

    /** 0B 重试前的短暂冷却，给插件的认证/票据落盘一点时间。 */
    private const val RETRY_DELAY_MS = 450L

    fun run(
        context: Context,
        engineId: String,
        tag: String,
        text: String,
        timeoutMs: Long = 120_000L,
        startupTimeoutMs: Long = 90_000L,
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

        val data = withEngineLock(context, sb, engine, found.pluginId) {
            // 必须在锁内写 source：试听与朗读/并列试听共用同一引擎实例，source 是可变字段。
            engine.source = PluginTtsSource(
                locale = found.locale,
                voice = found.voice,
                pluginId = found.pluginId,
                speed = rate,
                volume = volume,
                pitch = pitch,
                data = found.sourceData,
            )
            sb.appendLine("✓ 已注入 source.data=${found.sourceData}")
            synthWithRetry(context, sb, engine, found.pluginId, timeoutMs, startupTimeoutMs) {
                engine.getAudio(
                    text, found.locale, found.voice, rate, volume, pitch,
                    timeoutMs = startupTimeoutMs
                )
            }
        } ?: return Result(null, sb.toString())

        val sniffed = AudioSniffer.normalizeForPlayback(data, found.sampleRate)
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
        timeoutMs: Long = 120_000L,
        startupTimeoutMs: Long = 90_000L,
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

        val t0 = System.currentTimeMillis()
        PipelineProbe.log(
            context, "audition.start",
            "pluginId" to pluginId,
            "voice" to voice,
            "textLen" to text.length
        )

        val rate = speed.takeIf { it > 0f } ?: 1f
        val vol = volume.takeIf { it > 0f } ?: 1f
        val pit = pitch.takeIf { it > 0f } ?: 1f
        val sr = sampleRate.takeIf { it > 0 } ?: 24000

        val dataBytes = withEngineLock(context, sb, engine, pluginId) {
            engine.source = PluginTtsSource(
                locale = locale,
                voice = voice,
                pluginId = pluginId,
                speed = rate,
                volume = vol,
                pitch = pit,
                data = data,
            )
            synthWithRetry(context, sb, engine, pluginId, timeoutMs, startupTimeoutMs) {
                engine.getAudio(text, locale, voice, rate, vol, pit, timeoutMs = startupTimeoutMs)
            }
        } ?: return Result(null, sb.toString())

        PipelineProbe.log(
            context, "audition.end",
            "pluginId" to pluginId,
            "ms" to (System.currentTimeMillis() - t0),
            "bytes" to dataBytes.size
        )
        sb.appendLine("✓ 合成字节=${dataBytes.size}  首12字节=${dataBytes.take(12).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}")

        val sniffed = AudioSniffer.normalizeForPlayback(dataBytes, sr)
        sb.appendLine(
            if (sniffed.wrappedPcm) {
                "→ 未识别格式：按裸 PCM 包 WAV 头(${sr} Hz) total=${sniffed.bytes.size}"
            } else {
                "→ 容器格式直通(${sniffed.kind.name}) total=${sniffed.bytes.size}"
            }
        )
        return Result(sniffed.bytes, sb.toString())
    }

    // ==================== 内部 ====================

    /** 引擎串行锁：与朗读链 `TtsEngineContext` 用的是同一把锁。 */
    private fun <T> withEngineLock(
        context: Context,
        sb: StringBuilder,
        engine: TtsPluginEngineV2,
        pluginId: String,
        block: () -> T,
    ): T? = try {
        if (!engine.synthesisLock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
            PipelineProbe.log(context, "audition.busy", "pluginId" to pluginId)
            sb.appendLine("✗ 引擎忙：${LOCK_WAIT_SECONDS}s 内未拿到合成锁（朗读/另一次试听仍在合成）")
            null
        } else {
            try {
                block()
            } finally {
                engine.synthesisLock.unlock()
            }
        }
    } catch (t: InterruptedException) {
        Thread.currentThread().interrupt()
        sb.appendLine("✗ 合成被中断")
        null
    }

    /**
     * 启动 + 读流，0 字节重试一次。
     * 超时/异常直接返回 null（不重试，避免把 2×120s 的等待灌给用户）。
     */
    private fun synthWithRetry(
        context: Context,
        sb: StringBuilder,
        engine: TtsPluginEngineV2,
        pluginId: String,
        timeoutMs: Long,
        startupTimeoutMs: Long,
        start: suspend () -> InputStream,
    ): ByteArray? {
        var attempt = 0
        while (true) {
            attempt++
            val t0 = System.currentTimeMillis()
            val stream = try {
                runBlocking { start() }
            } catch (t: Throwable) {
                runCatching { engine.cancelAudio() }
                sb.appendLine("✗ 合成启动异常: ${t.javaClass.name}: ${t.message}")
                sb.appendLine(t.stackTraceToString().take(6000))
                PipelineProbe.log(
                    context, "audition.error",
                    "pluginId" to pluginId,
                    "stage" to "startup",
                    "attempt" to attempt,
                    "ms" to (System.currentTimeMillis() - t0),
                    "err" to (t.message ?: t.javaClass.simpleName)
                )
                return null
            }

            sb.appendLine(
                "→ 流类型=${stream.javaClass.simpleName}" +
                        " available=${runCatching { stream.available() }.getOrDefault(-1)}" +
                        " 启动耗时=${System.currentTimeMillis() - t0}ms" +
                        if (attempt > 1) "（第 $attempt 次）" else ""
            )

            val (bytes, timedOut) = try {
                readAllWithTimeout(stream, timeoutMs)
            } catch (t: Throwable) {
                runCatching { engine.cancelAudio() }
                sb.appendLine("✗ 合成异常: ${t.javaClass.name}: ${t.message}")
                sb.appendLine(t.stackTraceToString().take(6000))
                PipelineProbe.log(
                    context, "audition.error",
                    "pluginId" to pluginId,
                    "stage" to "read",
                    "attempt" to attempt,
                    "ms" to (System.currentTimeMillis() - t0),
                    "err" to (t.message ?: t.javaClass.simpleName)
                )
                return null
            } finally {
                runCatching { stream.close() }
            }

            if (timedOut) {
                runCatching { engine.cancelAudio() }
                sb.appendLine("✗ 合成超时(${timeoutMs}ms)")
                PipelineProbe.log(
                    context, "audition.timeout",
                    "pluginId" to pluginId,
                    "attempt" to attempt,
                    "ms" to (System.currentTimeMillis() - t0),
                    "timeoutMs" to timeoutMs
                )
                return null
            }
            if (bytes != null && bytes.isNotEmpty()) {
                if (attempt > 1) sb.appendLine("✓ 第 $attempt 次拿到音频")
                return bytes
            }

            // 0 字节：冷启动首次请求（票据/ident/ws 刚重建）最常见，清一次再试。
            if (attempt >= 2) {
                sb.appendLine("✗ 合成结果为空(0B)（已重试 $attempt 次）")
                PipelineProbe.log(
                    context, "audition.empty",
                    "pluginId" to pluginId,
                    "attempt" to attempt,
                    "ms" to (System.currentTimeMillis() - t0)
                )
                runCatching { engine.cancelAudio() }
                return null
            }
            sb.appendLine("→ 第 $attempt 次返回 0B，清理后重试一次（冷启动认证窗口）")
            PipelineProbe.log(
                context, "audition.retry",
                "pluginId" to pluginId,
                "attempt" to attempt,
                "ms" to (System.currentTimeMillis() - t0)
            )
            runCatching { engine.cancelAudio() }
            Thread.sleep(RETRY_DELAY_MS)
        }
    }
}