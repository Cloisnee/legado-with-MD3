package com.github.jing332.tts.readaloud

import android.content.Context
import com.github.jing332.compat.fs.TtsDirProvider
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import com.github.jing332.tts.watermark.AudioWatermarkStripper
import org.json.JSONObject
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
        /** v8：水印硬样本重抽次数（0=关闭）；试听/听书同一条 */
        maxWatermarkRetry: Int = 0,
    ): Outcome = withContext(Dispatchers.IO) {
        if (traitsJson.isBlank() || traitsJson.trim() == "[]") {
            return@withContext Outcome(false, "声线缺少条目配置（按条目合成，不回退全局查找）")
        }
        if (text.isBlank()) {
            return@withContext Outcome(false, "参数为空(text)")
        }
        // 平台试听水印剥离：只在插件声明了 watermark=true 时处理（见 TtsConfigStore.declaresWatermark）
        val src = runCatching {
            JSONObject(traitsJson).optJSONObject("source")
        }.getOrNull()
        val pluginId = src?.optString("pluginId").orEmpty()
        val voiceId = src?.optString("voice").orEmpty()
        val audDir = File(TtsDirProvider.baseDir(appContext), "_audition")

        var lastReason: String? = null
        val stripResult = try {
            // v8：硬样本重抽（试听/听书同一条逻辑）；非水印插件第一次就会以 Q_NA 直接收
            AudioWatermarkStripper.stripWithRetry(
                appContext, pluginId, audDir, maxWatermarkRetry, "$pluginId|$voiceId"
            ) {
                val r = TtsEngineContext(
                    tts = PluginTtsSource(),
                    userVars = emptyMap(),
                    context = appContext,
                    engineId = "",
                    synthTimeoutMs = timeoutMs,
                ).synthesizeByConfig(traitsJson, text, emotion)
                if (r.bytes == null) lastReason = r.reason ?: "未知原因"
                r.bytes
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            return@withContext Outcome(false, "合成异常: ${t.message ?: t.javaClass.simpleName}")
        }
        val cleaned = stripResult
            ?: return@withContext Outcome(false, lastReason ?: "合成失败（空音频）")
        val finalBytes = cleaned.bytes
        val written = runCatching {
            output.parentFile?.mkdirs()
            output.writeBytes(finalBytes)
        }.isSuccess && output.length() > 0
        if (!written) return@withContext Outcome(false, "音频写入失败")
        Outcome(true, null, finalBytes.size)
    }
}
