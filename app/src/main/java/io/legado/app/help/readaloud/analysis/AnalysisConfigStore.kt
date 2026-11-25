package io.legado.app.help.readaloud.analysis

import android.app.Application
import com.github.jing332.compat.fs.TtsDirProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 分析配置（提示词/上下文参数）· 暴露给用户随时修改：`_store/analysis_config.json`。
 *  - stage1Prompt/stage2Prompt/resolvePrompt/emotionPrompt/audioDirectorPrompt：提示词（默认=内置提示词；留空 = 用内置默认，即朗读脚本同款）；
 *  - timeoutSec：AI 分析全局超时上限（B10.4·A3，30..600s，默认 120；与模型级 timeoutMs 取更短者）；
 *  - waitAnalysisSec：等分析就绪最长等待（B10.4·A4，30..600s，默认 600）；
 *  - fallbackDefaultVoice：分析未就绪先用默认声线出声（B10.4·S1，默认 false；false=等待分析就绪后再出声）；
 *  - prevLimit/nextLimit：前情提要/后续剧情取文上限（0..3000 字；按完整段落取，不截断段落）；
 *  - emotionJoinTimeoutMs：第4阶段完成后最多再等情绪结果多久（默认 120000，超时先落库）；
 *  - audioDirectorJoinTimeoutMs：第4阶段完成后最多再等音频导演计划多久（默认 120000；超时/失败走本地兜底）；
 *  - maxOutputTokens：单次 AI 输出上限（默认 32768）；
 *  - bgSessionBudget：后台建库每次朗读会话的额度（0..200，默认 20；0=关闭）；
 *  - bgBatchMaxChapters：后台单批最大章数（1..30，默认 10）。
 */
class AnalysisConfigStore(private val app: Application) {

    data class Config(
        val stage1Prompt: String = "",
        val stage2Prompt: String = "",
        val resolvePrompt: String = "",
        val emotionPrompt: String = "",
        val audioDirectorPrompt: String = SpeechAnalysisPipelineV4.defaultPrompt("audioDirector"),
        val prevLimit: Int = 600,
        val nextLimit: Int = 400,
        val emotionJoinTimeoutMs: Long = 120_000L,
        val audioDirectorJoinTimeoutMs: Long = 120_000L,
        val maxOutputTokens: Int = 32768,
        val timeoutSec: Int = 120,
        val waitAnalysisSec: Int = 600,
        val fallbackDefaultVoice: Boolean = false,
        /** 后台建库总会话章数（一次朗读会话内的额度；0=关闭后台建库） */
        val bgSessionBudget: Int = 20,
        /** 后台单批最大章数（上限，不要求凑满） */
        val bgBatchMaxChapters: Int = 10,
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
                resolvePrompt = o.optString("resolvePrompt"),
                emotionPrompt = o.optString("emotionPrompt"),
                audioDirectorPrompt = o.optString("audioDirectorPrompt"),
                prevLimit = o.optInt("prevLimit", 600).coerceIn(0, 3000),
                nextLimit = o.optInt("nextLimit", 400).coerceIn(0, 3000),
                emotionJoinTimeoutMs = o.optLong("emotionJoinTimeoutMs", 120_000L).coerceIn(5_000L, 1_800_000L),
                audioDirectorJoinTimeoutMs = o.optLong("audioDirectorJoinTimeoutMs", 120_000L).coerceIn(5_000L, 1_800_000L),
                maxOutputTokens = o.optInt("maxOutputTokens", 32768).coerceIn(256, 32768),
                timeoutSec = o.optInt("timeoutSec", 120).coerceIn(30, 600),
                waitAnalysisSec = o.optInt("waitAnalysisSec", 600).coerceIn(30, 600),
                fallbackDefaultVoice = o.optBoolean("fallbackDefaultVoice", false),
                bgSessionBudget = o.optInt("bgSessionBudget", 20).coerceIn(0, 200),
                bgBatchMaxChapters = o.optInt("bgBatchMaxChapters", 10).coerceIn(1, 30),
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
                    put("resolvePrompt", cfg.resolvePrompt)
                    put("emotionPrompt", cfg.emotionPrompt)
                    put("audioDirectorPrompt", cfg.audioDirectorPrompt)
                    put("prevLimit", cfg.prevLimit)
                    put("nextLimit", cfg.nextLimit)
                    put("emotionJoinTimeoutMs", cfg.emotionJoinTimeoutMs)
                    put("audioDirectorJoinTimeoutMs", cfg.audioDirectorJoinTimeoutMs)
                    put("maxOutputTokens", cfg.maxOutputTokens)
                    put("timeoutSec", cfg.timeoutSec)
                    put("waitAnalysisSec", cfg.waitAnalysisSec)
                    put("fallbackDefaultVoice", cfg.fallbackDefaultVoice)
                    put("bgSessionBudget", cfg.bgSessionBudget)
                    put("bgBatchMaxChapters", cfg.bgBatchMaxChapters)
                }.toString(),
            )
            true
        }.getOrDefault(false)
    }
}
