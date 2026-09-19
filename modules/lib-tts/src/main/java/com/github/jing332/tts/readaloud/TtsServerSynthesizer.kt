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
 * 由 apply_patch 注入到 `HttpReadAloudService` 使用：
 *  - engineId  = 标签规则 ID（如 local，对应配置列表 speechRule.tagRuleId）
 *  - speakerId = 标签（如 女童01，对应 speechRule.tag）
 *  - 合成结果写入朗读缓存文件（与系统/云 TTS 合成器同一管线）
 *
 * 失败时通过 [Outcome.reason] 回传可读原因（标签未找到 / 超时 / 空音频 / 写入失败），
 * 由调用方写入朗读日志「音频缓存」分区，便于定位「条目被快速跳过」的根因。
 */
class TtsServerSynthesizer(context: Context) {

    private val appContext = context.applicationContext

    class Outcome(val ok: Boolean, val reason: String? = null, val bytes: Int = 0)

    suspend fun synthesize(
        engineId: String,
        speakerId: String,
        text: String,
        output: File,
    ): Outcome = withContext(Dispatchers.IO) {
        if (engineId.isBlank() || speakerId.isBlank() || text.isBlank()) {
            return@withContext Outcome(false, "参数为空(engineId/speakerId/text)")
        }
        val outcome = try {
            TtsEngineContext(
                tts = PluginTtsSource(),
                userVars = emptyMap(),
                context = appContext,
                engineId = engineId,
            ).synthesizeByTag(speakerId, text)
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
