package io.legado.app.help.readaloud.analysis

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiModelRepository
import io.legado.app.data.repository.AiProvider
import io.legado.app.data.repository.AiThinkingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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
 *  - 超时：模型级 timeoutMs 与「AI 分析设置」timeoutSec 取更短者，既作分片读超时，
 *    也作整次请求 callTimeout（防止 SSE keep-alive 让总时长无限拖长）；
 *  - 关闭思考：按模型 thinkingMode 发送（auto / reasoning_effort / reasoning.effort /
 *    enable_thinking / thinking / chat_template_kwargs / thinking_budget / 不发送）。
 *
 * B10.5（Q2）：日志统一携带调用来源 [logTag]（如「第1章·第2阶段」「第1章·情绪」），
 *  渲染为 `【AI调用·<logTag>】…`，便于按章节+阶段定位；同步移除无调用方的旧 `complete` 入口。
 */
class AiSpeechClient(
    private val aiModels: AiModelRepository,
    private val analysisConfig: AnalysisConfigStore,
    private val scheduler: AiRequestScheduler,
) {

    data class ModelRef(val model: AiModelEntry, val provider: AiProvider)

    /** 单次调用结果：raw=null 时 failReason 为可读失败原因（供阶段日志一行输出） */
    private data class CallOutcome(
        val raw: String?,
        val failReason: String = "",
        val rateLimited: Boolean = false,
    )

    private class RateLimitedException(message: String) : Exception(message)

    /** 统一分析超时（毫秒）：AI 分析设置 timeoutSec（30..600，默认 120） */
    private suspend fun unifiedTimeoutMs(): Long =
        runCatching { analysisConfig.load().timeoutSec }
            .getOrDefault(120)
            .coerceIn(30, 600) * 1000L

    /** 流式客户端：read/write 按「每次读写」计；callTimeout 按「整次请求」计（总超时） */
    private fun streamClient(timeoutMs: Long, totalTimeoutMs: Long = timeoutMs): OkHttpClient =
        aiModels.http().newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS)
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
        context: AiSchedulerContext? = null,
        onChunk: ((String) -> Unit)? = null,
        onAttemptStart: (() -> Unit)? = null,
        totalTimeoutMs: Long? = null,
        onModelUsed: ((String) -> Unit)? = null,
        validate: (String) -> ValidateOutcome<T>,
    ): T? = withContext(Dispatchers.IO) {
        val globalTimeoutMs = unifiedTimeoutMs()
        for (ref in refs) {
            // 模型设置里的「超时」与全局「统一超时」取更短者：模型级真正生效，全局仍作硬上限。
            val timeoutMs = minOf(
                ref.model.timeoutMs.coerceIn(5_000L, 600_000L),
                globalTimeoutMs,
            )
            val respMax = ref.model.requestAttempts.coerceIn(1, 5)
            val validMax = ref.model.validateRetries.coerceIn(0, 5)
            var respFails = 0
            var validFails = 0
            var failHint = ""
            var consecutive429 = 0
            while (true) {
                onAttemptStart?.invoke()
                val call = callOnce(
                    ref = ref,
                    system = system,
                    user = promptFactory(failHint),
                    maxTokens = maxTokens,
                    timeoutMs = timeoutMs,
                    totalTimeoutMs = totalTimeoutMs ?: timeoutMs,
                    context = context,
                    onChunk = onChunk,
                )
                val raw = call.raw
                if (raw == null) {
                    if (call.rateLimited) {
                        consecutive429++
                        runCatching { scheduler.reportRateLimited(ref.provider.id, consecutive429) }
                        AppLog.putAnalysis(
                            "【AI调用·$logTag】模型 ${ref.model.name} 限流 429（连续 $consecutive429 次）",
                        )
                        // 前两次原模型重试（服务商暂停期内排队等待）；第三次按响应失败处理
                        if (consecutive429 < 3) continue
                        respFails++
                        consecutive429 = 0
                        if (respFails >= respMax) break
                        continue
                    }
                    consecutive429 = 0
                    respFails++
                    AppLog.putAnalysis(
                        "【AI调用·$logTag】模型 ${ref.model.name} 响应失败（$respFails/$respMax，${call.failReason.ifBlank { "未知原因" }}）",
                    )
                    if (respFails >= respMax) break
                    delay(2000L * respFails)
                    continue
                }
                consecutive429 = 0
                val outcome = runCatching { validate(raw) }.getOrElse {
                    ValidateOutcome(null, "解析异常：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
                if (outcome.data != null) {
                    AppLog.putAnalysis("【AI调用·$logTag】模型 ${ref.model.name} 校验通过")
                    onModelUsed?.invoke(ref.model.name)
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
    private suspend fun callOnce(
        ref: ModelRef,
        system: String,
        user: String,
        maxTokens: Int,
        timeoutMs: Long,
        totalTimeoutMs: Long,
        context: AiSchedulerContext?,
        onChunk: ((String) -> Unit)?,
    ): CallOutcome {
        return try {
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
                                    if (ref.model.effectiveThinkingMode != AiThinkingMode.NONE) {
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
                            if (ref.model.effectiveThinkingMode != AiThinkingMode.NONE) {
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
                            applyOpenAiThinking(this, ref.model)
                            put("temperature", 0)
                            put("max_tokens", maxTokens)
                        }.toString().toRequestBody(jsonType),
                    )
                    .build()
            }
            val text = executeCall(
                ref = ref,
                request = request,
                protocol = protocol,
                timeoutMs = timeoutMs,
                totalTimeoutMs = totalTimeoutMs,
                context = context,
                onChunk = onChunk,
            )
            CallOutcome(text)
        } catch (e: RateLimitedException) {
            CallOutcome(null, e.message ?: "HTTP 429", rateLimited = true)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // 协程取消时 OkHttp 抛出 IOException("Canceled")：转成取消，不当作失败。
            currentCoroutineContext().ensureActive()
            val reason = if (e.isTimeoutError()) "超时 ${totalTimeoutMs / 1000}s"
            else (e.localizedMessage ?: e.javaClass.simpleName)
            CallOutcome(null, reason)
        }
    }

    /** 许可 + 取消联动 + 读取；真正的网络出口。 */
    private suspend fun executeCall(
        ref: ModelRef,
        request: Request,
        protocol: String,
        timeoutMs: Long,
        totalTimeoutMs: Long,
        context: AiSchedulerContext?,
        onChunk: ((String) -> Unit)?,
    ): String {
        val call = streamClient(timeoutMs, totalTimeoutMs).newCall(request)
        val perform: suspend () -> String = {
            // 阻塞 execute/读流放到独立线程：协程取消时立刻 call.cancel()，不必等 Job 结束回调
            // （旧实现把 cancel 挂在 invokeOnCompletion 上，读流卡住时回调永远不会触发）。
            suspendCancellableCoroutine { cont ->
                val thread = Thread({
                    try {
                        val text = call.execute().use { resp ->
                            if (!resp.isSuccessful) {
                                val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                                if (resp.code == 429) {
                                    // 先暂停服务商许可池，再释放本请求的许可，避免下一笔同商请求插空。
                                    runCatching { scheduler.reportRateLimited(ref.provider.id, 1) }
                                    throw RateLimitedException("HTTP 429: ${errBody.take(160)}")
                                }
                                error("HTTP ${resp.code}: ${errBody.take(160)}")
                            }
                            val text = readStreamBody(protocol, resp, totalTimeoutMs, onChunk)
                            if (text.isNullOrBlank()) error("空响应")
                            text
                        }
                        if (cont.isActive) cont.resume(text)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }, "ai-http-${ref.provider.id}")
                cont.invokeOnCancellation { call.cancel() }
                thread.start()
            }
        }
        return if (context != null) scheduler.withPermit(ref, context) { perform() } else perform()
    }

    /** OpenAI 兼容协议的关闭思考字段：按模型设置发送，避免固定发 thinking 被新网关 400。 */
    private fun applyOpenAiThinking(body: JSONObject, model: AiModelEntry) {
        when (model.effectiveThinkingMode) {
            AiThinkingMode.AUTO -> body.put("enable_thinking", false)
            AiThinkingMode.REASONING_EFFORT_NONE -> body.put("reasoning_effort", "none")
            AiThinkingMode.REASONING_EFFORT_MINIMAL -> body.put("reasoning_effort", "minimal")
            AiThinkingMode.REASONING_OBJECT_NONE ->
                body.put("reasoning", JSONObject().put("effort", "none"))
            AiThinkingMode.ENABLE_THINKING_FALSE -> body.put("enable_thinking", false)
            AiThinkingMode.THINKING_DISABLED ->
                body.put("thinking", JSONObject().put("type", "disabled"))
            AiThinkingMode.CHAT_TEMPLATE_KWARGS ->
                body.put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            AiThinkingMode.THINKING_BUDGET_ZERO -> body.put("thinking_budget", 0)
            AiThinkingMode.NONE -> Unit
        }
    }

    private fun Throwable.isTimeoutError(): Boolean =
        this is java.io.InterruptedIOException ||
            this is java.net.SocketTimeoutException ||
            this is java.util.concurrent.TimeoutException

    /**
     * 逐行读取响应体（复刻原脚本 readChatStream）：
     *  - SSE：`data:` 分片逐行解析（openai=choices.delta.content；claude=content_block_delta；
     *    google=候选 parts 文本），分片持续到达即不断读（超时按片重置）；
     *  - 服务端忽略 stream 返回普通 JSON：整段文本走 [extractContent] 解析。
     */
    private fun readStreamBody(
        protocol: String,
        resp: Response,
        totalTimeoutMs: Long,
        onChunk: ((String) -> Unit)? = null,
    ): String? {
        val source = resp.body?.source() ?: return null
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        var mode = -1 // -1 未知 / 0 普通JSON / 1 SSE
        val plain = StringBuilder()
        val sse = StringBuilder()
        var errMsg: String? = null
        while (true) {
            // callTimeout 之外的兜底：SSE keep-alive 持续来行时也要有总时限。
            if (System.currentTimeMillis() > deadline) {
                throw java.io.InterruptedIOException("timeout")
            }
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
                        "google" -> {
                            val t = appendGoogleChunk(sse, chunk)
                            if (t.isNotEmpty()) onChunk?.invoke(t)
                        }
                        "claude" -> {
                            when (chunk.optString("type")) {
                                "content_block_delta" -> {
                                    val t = chunk.optJSONObject("delta")?.optString("text").orEmpty()
                                    if (t.isNotEmpty()) {
                                        sse.append(t)
                                        onChunk?.invoke(t)
                                    }
                                }
                                "error" -> errMsg = chunk.optJSONObject("error")?.optString("message")
                                    ?.takeIf { it.isNotBlank() } ?: "stream error"
                            }
                        }
                        else -> {
                            val t = appendOpenAiChunk(sse, chunk)
                            if (t.isNotEmpty()) onChunk?.invoke(t)
                        }
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
                error("流式错误: ${errMsg.take(120)}")
            }
            sse.toString().takeIf { it.isNotBlank() }
        } else {
            extractContent(protocol, plain.toString())?.also { text ->
                onChunk?.invoke(text)
            }
        }
    }

    /** openai 风格分片：choices[0].delta.content 累加；个别网关发整段 message.content 时替换 */
    private fun appendOpenAiChunk(acc: StringBuilder, chunk: JSONObject): String {
        val choices = chunk.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val ch0 = choices.optJSONObject(0) ?: return ""
        var appended = ""
        val msgContent = ch0.optJSONObject("message")?.optString("content").orEmpty()
        if (msgContent.isNotEmpty()) {
            acc.setLength(0)
            acc.append(msgContent)
            appended = msgContent
        }
        val delta = ch0.optJSONObject("delta")?.optString("content").orEmpty()
        if (delta.isNotEmpty()) {
            acc.append(delta)
            appended += delta
        }
        return appended
    }

    /** google 流式分片：candidates[0].content.parts[].text 逐段拼接 */
    private fun appendGoogleChunk(acc: StringBuilder, chunk: JSONObject): String {
        val parts = chunk.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text").orEmpty()
            if (t.isNotEmpty()) {
                acc.append(t)
                out.append(t)
            }
        }
        return out.toString()
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
        "resolve" -> cfg.stages.resolve
        "emotion" -> cfg.stages.emotion
        "audioDirector" -> cfg.stages.audioDirector
        "synthSfx" -> cfg.stages.synthSfx
        "synthBgm" -> cfg.stages.synthBgm
        "synthAmb" -> cfg.stages.synthAmb
        else -> emptyList()
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
