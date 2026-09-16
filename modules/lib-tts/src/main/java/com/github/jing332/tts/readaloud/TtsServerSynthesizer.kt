package com.github.jing332.tts.readaloud

import android.content.Context
import com.github.jing332.database.entities.systts.source.PluginTtsSource
import com.github.jing332.tts.speech.plugin.engine.TtsEngineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 阅读朗读管线 · TTS-Server 引擎合成器（engineType = "tts_server"）。
 *
 * 由 apply_patch 注入到 `HttpReadAloudService` 使用：
 *  - engineId  = 标签规则 ID（如 mingwuyan，对应配置列表 speechRule.tagRuleId）
 *  - speakerId = 标签（如 女童01，对应 speechRule.tag）
 *  - 合成结果写入朗读缓存文件（与系统/云 TTS 合成器同一管线）
 */
class TtsServerSynthesizer(context: Context) {

    private val appContext = context.applicationContext

    suspend fun synthesize(
        engineId: String,
        speakerId: String,
        text: String,
        output: File,
    ): Boolean = withContext(Dispatchers.IO) {
        if (engineId.isBlank() || speakerId.isBlank() || text.isBlank()) return@withContext false
        val bytes = runCatching {
            TtsEngineContext(
                tts = PluginTtsSource(),
                userVars = emptyMap(),
                context = appContext,
                engineId = engineId,
            ).synthesizeByTag(speakerId, text)
        }.getOrNull() ?: return@withContext false
        runCatching {
            output.parentFile?.mkdirs()
            output.writeBytes(bytes)
        }.isSuccess && output.length() > 0
    }
}
