package com.github.jing332.tts.readaloud

import android.content.Context
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读朗读管线 · TTS-Server 引擎合成器（engineType = "tts_server"）。
 *
 * 批212d：按「精确条目」合成——traitsJson = 声线目录条目的 traitsJson（= voices.json 条目 config）；
 * 无配置**不允许合成**（不回退 (engineId,tag) 全局查找——连根拔除多池同名二义）。
 * 合成结果写入朗读缓存文件（与系统/云 TTS 合成器同一管线）。
 *
 * 失败时通过 [Outcome.reason] 回传可读原因（缺配置 / 超时 / 空音频 / 写入失败），
 * 由调用方写入朗读日志「音频缓存」分区，便于定位「条目被快速跳过」的根因。
 */
class TtsServerSynthesizer(context: Context) {

    private val appContext = context.applicationContext

    class Outcome(val ok: Boolean, val reason: String? = null, val bytes: Int = 0)

    suspend fun synthesize(
        /** 「精确条目」配置（声线目录 traitsJson；无配置不允许合成——不回退全局查找） */
        traitsJson: String,
        text: String,
        output: File,
        timeoutMs: Long = TtsEngineContext.AUDITION_TIMEOUT,
        /** 阅读朗读情绪（空 = 无）；桥给插件 `ttsrv.tts.emotion` */
        emotion: String = "",
    ): Outcome = withContext(Dispatchers.IO) {
        if (traitsJson.isBlank() || traitsJson.trim() == "[]") {
            return@withContext Outcome(false, "声线缺少条目配置（按条目合成，不回退全局查找）")
        }
        if (text.isBlank()) {
            return@withContext Outcome(false, "参数为空(text)")
        }
        val outcome = try {
            TtsEngineContext(
                tts = PluginTtsSource(),
                userVars = emptyMap(),
                context = appContext,
                engineId = "",
                synthTimeoutMs = timeoutMs,
            ).synthesizeByConfig(traitsJson, text, emotion)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            return@withContext Outcome(false, "合成异常: ${t.message ?: t.javaClass.simpleName}")
        }
        val bytes = outcome.bytes
            ?: return@withContext Outcome(false, outcome.reason ?: "未知原因")
        val written = runCatching {
            output.parentFile?.mkdirs()
            output.writeBytes(bytes)
        }.isSuccess && output.length() > 0
        if (!written) return@withContext Outcome(false, "音频写入失败")
        Outcome(true, null, bytes.size)
    }
}
