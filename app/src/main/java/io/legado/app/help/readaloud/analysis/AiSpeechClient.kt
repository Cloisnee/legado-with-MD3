package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * W3 分析管线 · AI 调用统一入口（模型队列超时轮换，复刻自研插件的多模型容错）。
 *
 * 队列语义（按「模型管理」的阶段队列顺序逐个尝试）：
 *  - 每模型 requestAttempts 次响应尝试（超时/HTTP 错/内容校验失败 各记一次）；
 *  - 全部失败 → 换下一个模型；队列耗尽 → 返回 null（调用方走兜底）。
 *
 * B10.4 改造（A2/A3/A6）：
 *  - 流式：一律 `stream=true` 走 SSE 逐行读取（复刻原脚本 readChatStream）；
 *    服务端忽略 stream 返回普通 JSON 时原样回退解析；
 *  - 超时：统一取「AI 分析设置」timeoutSec（30..600，默认 120s），作为流式分片的
 *    间隔读取超时（每片到达即重置计时；不再使用整段 callTimeout），覆盖模型级 timeoutMs；
 *  - 关闭思考：模型开启「关闭思考」时按协议附加关闭字段
 *    （openai = enable_thinking:false + thinking{type:disabled}；claude = thinking{type:disabled}；
 *    google = generationConfig.thinkingConfig.thinkingBudget=0）。
 *
 * B10.5（Q2）：日志统一携带调用来源 [logTag]（如「第1章·第2阶段」「第1章·情绪」），
 *  渲染为 `【AI调用·<logTag>】…`，便于按章节+阶段定位；同步移除无调用方的旧 `complete` 入口。
 */
class AiSpeechClient(
    private val aiModels: AiModelRepository,
    private val analysisConfig: AnalysisConfigStore,
) {

    data class ModelRef(val model: AiModelEntry, val provider: AiProvider)

    /** 统一分析超时（毫秒）：AI 分析设置 timeoutSec（30..600，默认 120） */
    private suspend fun unifiedTimeoutMs(): Long =
        runCatching { analysisConfig.load().timeoutSec }
            .getOrDefault(120)
            .coerceIn(30, 600) * 1000L

    /** 流式客户端：read/write 按「每次读写」计（分片到达即重置计时），无整段 callTimeout */
    private fun streamClient(timeoutMs: Long): OkHttpClient =
        aiModels.http().newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(0L, TimeUnit.MILLISECONDS)
            .build()

    /**
     * 两级重试（复刻脚本 callAIValidated）：
     *  - 第一级「响应问题」：超时/HTTP错/非JSON → 每模型 requestAttempts 次；
     *  - 第二级「内容校验」：JSON 合法但字段不符 → 每模型 validateRetries 次，失败原因作为 failHint 顺延进下一次提示词；
     *  - 两计数器独立；全部耗尽 → 换下一模型；队列耗尽返回 null（调用方走兜底）。
     */
    suspend fun <T> completeValidated(
        refs: List<ModelRef>,
        system: String,
        promptFactory: (failHint: String) -> String,
        maxTokens: Int = 4096,
        logTag: String,
        validate: (String) -> ValidateOutcome<T>,
    ): T? = withContext(Dispatchers.IO) {
        val timeoutMs = unifiedTimeoutMs()
        for (ref in refs) {
            val respMax = ref.model.requestAttempts.coerceIn(1, 5)
            val validMax = ref.model.validateRetries.coerceIn(0, 5)
            var respFails = 0
            var validFails = 0
            var failHint = ""
            while (true) {
                val raw = callOnce(ref, system, promptFactory(failHint), maxTokens, timeoutMs, logTag)
                if (raw == null) {
                    respFails++
                    AppLog.putAnalysis(
                        "【AI调用·$logTag】模型 ${ref.model.name} 响应失败（$respFails/$respMax）",
                    )
                    if (respFails >= respMax) break
                    delay(2000L * respFails)
                    continue
                }
                val outcome = runCatching { validate(raw) }.getOrElse {
                    ValidateOutcome(null, "解析异常：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
                if (outcome.data != null) {
                    AppLog.putAnalysis("【AI调用·$logTag】模型 ${ref.model.name} 校验通过")
                    return@withContext outcome.data
                }
                validFails++
                AppLog.putAnalysis(
                    "【AI调用·$logTag】模型 ${ref.model.name} 校验失败（$validFails/$validMax）：${outcome.failReason}",
                )
                if (validFails >= validMax) break
                failHint = outcome.failReason
                delay(1000)
            }
            AppLog.putAnalysis("【AI调用·$logTag】模型 ${ref.model.name} 额度耗尽，切换下一模型")
        }
        AppLog.putAnalysis("【AI调用·$logTag】所有模型响应/校验额度均已耗尽")
        null
    }

    /** 单次调用：stream=true 请求 + 逐行读取（SSE 优先，普通 JSON 回退） */
    private fun callOnce(
        ref: ModelRef,
        system: String,
        user: String,
        maxTokens: Int,
        timeoutMs: Long,
        logTag: String,
    ): String? {
        return runCatching {
            val base = ref.provider.baseUrl.trim().trimEnd('/')
            require(base.isNotBlank()) { "BaseUrl 为空" }
            val protocol = ref.provider.protocol.lowercase()
            val jsonType = "application/json; charset=utf-8".toMediaType()
            val request = when (protocol) {
                "google" -> Request.Builder()
                    .url("$base/models/${ref.model.modelId}:streamGenerateContent?alt=sse&key=${ref.provider.apiKey}")
                    .post(
                        JSONObject().apply {
                            put(
                                "contents",
                                JSONArray().put(
                                    JSONObject().put(
                                        "parts",
                                        JSONArray().put(JSONObject().put("text", "$system\n\n$user")),
                                    ),
                                ),
                            )
                            put(
                                "generationConfig",
                                JSONObject().apply {
                                    put("temperature", 0)
                                    put("maxOutputTokens", maxTokens)
                                    if (ref.model.disableThinking) {
                                        put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                                    }
                                },
                            )
                        }.toString().toRequestBody(jsonType),
                    )
                    .build()

                "claude" -> Request.Builder()
                    .url("$base/messages")
                    .header("x-api-key", ref.provider.apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .post(
                        JSONObject().apply {
                            put("model", ref.model.modelId)
                            put("max_tokens", maxTokens)
                            put("temperature", 0)
                            put("system", system)
                            put("stream", true)
                            if (ref.model.disableThinking) {
                                put("thinking", JSONObject().put("type", "disabled"))
                            }
                            put(
                                "messages",
                                JSONArray().put(
                                    JSONObject().put("role", "user").put("content", user),
                                ),
                            )
                        }.toString().toRequestBody(jsonType),
                    )
                    .build()

                else -> Request.Builder()
                    .url("$base/chat/completions")
                    .header("Authorization", "Bearer ${ref.provider.apiKey}")
                    .header("Connection", "keep-alive")
                    .post(
                        JSONObject().apply {
                            put("model", ref.model.modelId)
                            put(
                                "messages",
                                JSONArray()
                                    .put(JSONObject().put("role", "system").put("content", system))
                                    .put(JSONObject().put("role", "user").put("content", user)),
                            )
                            put("stream", true)
                            if (ref.model.disableThinking) {
                                put("enable_thinking", false)
                                put("thinking", JSONObject().put("type", "disabled"))
                            }
                            put("temperature", 0)
                            put("max_tokens", maxTokens)
                        }.toString().toRequestBody(jsonType),
                    )
                    .build()
            }
            streamClient(timeoutMs).newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    error("HTTP ${resp.code}: ${errBody.take(160)}")
                }
                readStreamBody(protocol, resp)
            }
        }.getOrElse {
            AppLog.putAnalysis(
                "【AI调用·$logTag】异常（${ref.model.name}）: ${it.localizedMessage ?: it.javaClass.simpleName}",
            )
            null
        }
    }

    /**
     * 逐行读取响应体（复刻原脚本 readChatStream）：
     *  - SSE：`data:` 分片逐行解析（openai=choices.delta.content；claude=content_block_delta；
     *    google=候选 parts 文本），分片持续到达即不断读（超时按片重置）；
     *  - 服务端忽略 stream 返回普通 JSON：整段文本走 [extractContent] 解析。
     */
    private fun readStreamBody(protocol: String, resp: Response): String? {
        val source = resp.body?.source() ?: return null
        var mode = -1 // -1 未知 / 0 普通JSON / 1 SSE
        val plain = StringBuilder()
        val sse = StringBuilder()
        var errMsg: String? = null
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isBlank()) continue
            when {
                line.startsWith("data:") -> {
                    if (mode == -1) mode = 1
                    if (mode != 1) continue
                    val payload = line.substring(5).trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    if (chunk.has("error")) {
                        errMsg = chunk.optJSONObject("error")?.optString("message")
                            ?.takeIf { it.isNotBlank() } ?: chunk.opt("error").toString()
                        continue
                    }
                    when (protocol) {
                        "google" -> appendGoogleChunk(sse, chunk)
                        "claude" -> {
                            when (chunk.optString("type")) {
                                "content_block_delta" -> {
                                    val t = chunk.optJSONObject("delta")?.optString("text").orEmpty()
                                    if (t.isNotEmpty()) sse.append(t)
                                }
                                "error" -> errMsg = chunk.optJSONObject("error")?.optString("message")
                                    ?.takeIf { it.isNotBlank() } ?: "stream error"
                            }
                        }
                        else -> appendOpenAiChunk(sse, chunk)
                    }
                }
                line.startsWith("event:") -> Unit // SSE 事件名行；类型以 data JSON 内字段为准
                else -> {
                    if (mode == -1) mode = 0
                    if (mode == 0) plain.append(line).append('\n')
                }
            }
        }
        return if (mode == 1) {
            if (errMsg != null) {
                AppLog.putAnalysis("分析AI流式错误（$protocol）：${errMsg.take(160)}")
                null
            } else {
                sse.toString().takeIf { it.isNotBlank() }
            }
        } else {
            extractContent(protocol, plain.toString())
        }
    }

    /** openai 风格分片：choices[0].delta.content 累加；个别网关发整段 message.content 时替换 */
    private fun appendOpenAiChunk(acc: StringBuilder, chunk: JSONObject) {
        val choices = chunk.optJSONArray("choices") ?: return
        if (choices.length() == 0) return
        val ch0 = choices.optJSONObject(0) ?: return
        val msgContent = ch0.optJSONObject("message")?.optString("content").orEmpty()
        if (msgContent.isNotEmpty()) {
            acc.setLength(0)
            acc.append(msgContent)
        }
        val delta = ch0.optJSONObject("delta")?.optString("content").orEmpty()
        if (delta.isNotEmpty()) acc.append(delta)
    }

    /** google 流式分片：candidates[0].content.parts[].text 逐段拼接 */
    private fun appendGoogleChunk(acc: StringBuilder, chunk: JSONObject) {
        val parts = chunk.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotEmpty()) acc.append(t)
        }
    }

    /** 普通（非流式）响应解析：兜底路径共用 */
    private fun extractContent(protocol: String, body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val text = when (protocol) {
            "google" -> root.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                ?.optString("text")
            "claude" -> root.optJSONArray("content")?.optJSONObject(0)?.optString("text")
            else -> root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.let { msg ->
                    val content = msg.optString("content").orEmpty()
                    if (content.isBlank()) msg.optString("reasoning_content").orEmpty() else content
                }
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /** 提取模型输出中的 JSON（剥 ```json 围栏 → 首{ 末}） */
    fun extractJson(raw: String): JSONObject? {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```")
            val fenceEnd = s.lastIndexOf("```")
            if (fenceEnd >= 0) s = s.substring(0, fenceEnd)
            s = s.trim()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull()
    }
}

/** 两级重试的校验结果：data=null 表示校验失败，failReason 会顺延给下一次尝试（复刻脚本 failHint） */
class ValidateOutcome<T>(val data: T?, val failReason: String = "")

/** 阶段队列 → 模型引用（队列顺序，过滤禁用模型/服务商） */
suspend fun AiModelRepository.queueRefs(key: String): List<AiSpeechClient.ModelRef> {
    val cfg = load()
    val ids = when (key) {
        "stage1" -> cfg.stages.stage1
        "stage2" -> cfg.stages.stage2
        "stage4" -> cfg.stages.stage4
        else -> cfg.stages.emotion
    }
    return ids.mapNotNull { id -> cfg.models.firstOrNull { it.id == id && it.enabled } }
        .mapNotNull { m ->
            val p = cfg.providers.firstOrNull { it.id == m.providerId && it.enabled }
            if (p == null) null else AiSpeechClient.ModelRef(m, p)
        }
}

/** 宽松 JSON 数组解析兜底 */
internal fun JSONArray.objects(): List<JSONObject> = buildList {
    for (i in 0 until length()) optJSONObject(i)?.let { add(it) }
}
