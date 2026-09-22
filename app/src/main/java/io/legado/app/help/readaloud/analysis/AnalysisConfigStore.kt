package io.legado.app.help.readaloud.analysis

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 分析配置（提示词/上下文参数）· 暴露给用户随时修改：`_store/analysis_config.json`。
 *  - stage1Prompt/stage2Prompt/stage4Prompt/emotionPrompt：四阶段提示词（留空 = 用内置默认，即朗读脚本同款）；
 *  - timeoutSec：AI 分析统一超时（B10.4·A3，30..600s，默认 120；覆盖模型级 timeoutMs）；
 *  - waitAnalysisSec：等分析就绪最长等待（B10.4·A4，30..600s，默认 600）；
 *  - fallbackDefaultVoice：分析未就绪先用默认声线出声（B10.4·S1，默认 false；false=等待分析就绪后再出声）；
 *  - prevLimit/nextLimit：前情提要/后续剧情取文上限（0..3000 字；按完整段落取，不截断段落）；
 *  - emotionJoinTimeoutMs：第4阶段完成后最多再等情绪结果多久（默认 120000，超时先落库）；
 *  - maxOutputTokens：单次 AI 输出上限（默认 32768）。
 */
class AnalysisConfigStore(private val app: Application) {

    data class Config(
        val stage1Prompt: String = "",
        val stage2Prompt: String = "",
        val stage4Prompt: String = "",
        val emotionPrompt: String = "",
        val prevLimit: Int = 600,
        val nextLimit: Int = 400,
        val emotionJoinTimeoutMs: Long = 120_000L,
        val maxOutputTokens: Int = 32768,
        val timeoutSec: Int = 120,
        val waitAnalysisSec: Int = 600,
        val fallbackDefaultVoice: Boolean = false,
    )

    private fun file(): File = File(TtsDirProvider.baseDir(app), "_store/analysis_config.json")

    suspend fun load(): Config = withContext(Dispatchers.IO) {
        runCatching {
            val f = file()
            if (!f.exists()) return@runCatching Config()
            val o = JSONObject(f.readText().removePrefix("\uFEFF"))
            Config(
                stage1Prompt = o.optString("stage1Prompt"),
                stage2Prompt = o.optString("stage2Prompt"),
                stage4Prompt = o.optString("stage4Prompt"),
                emotionPrompt = o.optString("emotionPrompt"),
                prevLimit = o.optInt("prevLimit", 600).coerceIn(0, 3000),
                nextLimit = o.optInt("nextLimit", 400).coerceIn(0, 3000),
                emotionJoinTimeoutMs = o.optLong("emotionJoinTimeoutMs", 120_000L).coerceIn(5_000L, 1_800_000L),
                maxOutputTokens = o.optInt("maxOutputTokens", 32768).coerceIn(256, 32768),
                timeoutSec = o.optInt("timeoutSec", 120).coerceIn(30, 600),
                waitAnalysisSec = o.optInt("waitAnalysisSec", 600).coerceIn(30, 600),
                fallbackDefaultVoice = o.optBoolean("fallbackDefaultVoice", false),
            )
        }.getOrDefault(Config())
    }

    suspend fun save(cfg: Config): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val f = file()
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject().apply {
                    put("stage1Prompt", cfg.stage1Prompt)
                    put("stage2Prompt", cfg.stage2Prompt)
                    put("stage4Prompt", cfg.stage4Prompt)
                    put("emotionPrompt", cfg.emotionPrompt)
                    put("prevLimit", cfg.prevLimit)
                    put("nextLimit", cfg.nextLimit)
                    put("emotionJoinTimeoutMs", cfg.emotionJoinTimeoutMs)
                    put("maxOutputTokens", cfg.maxOutputTokens)
                    put("timeoutSec", cfg.timeoutSec)
                    put("waitAnalysisSec", cfg.waitAnalysisSec)
                    put("fallbackDefaultVoice", cfg.fallbackDefaultVoice)
                }.toString(),
            )
            true
        }.getOrDefault(false)
    }
}
