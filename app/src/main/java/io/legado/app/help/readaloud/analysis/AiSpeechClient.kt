package io.legado.app.help.readaloud.analysis

import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiProvider
import io.legado.app.data.repository.AiModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * W3 分析管线 · AI 调用统一入口（模型队列超时轮换，复刻自研插件的多模型容错）。
 *
 * 队列语义（按「模型管理」的阶段队列顺序逐个尝试）：
 *  - 每模型 requestAttempts 次响应尝试（超时/HTTP 错/内容校验失败 各记一次）；
 *  - 全部失败 → 换下一个模型；队列耗尽 → 返回 null（调用方走兜底）；
 *  - 每模型独立 timeoutMs（okhttp callTimeout）。
 */
class AiSpeechClient(private val aiModels: AiModelRepository) {

    data class ModelRef(val model: AiModelEntry, val provider: AiProvider)

    private fun client(timeoutMs: Long): OkHttpClient =
        aiModels.http().newBuilder()
            .callTimeout(timeoutMs.coerceIn(5_000L, 600_000L), TimeUnit.MILLISECONDS)
            .build()

    /**
     * @param validate 内容校验（返回 null = 校验失败，记一次尝试后重试）
     * @return 校验通过的结果；队列耗尽返回 null
     */
    suspend fun <T> complete(
        refs: List<ModelRef>,
        system: String,
        user: String,
        maxTokens: Int = 4096,
        validate: (String) -> T?,
    ): T? = withContext(Dispatchers.IO) {
        for (ref in refs) {
            val attempts = ref.model.requestAttempts.coerceIn(1, 5)
            repeat(attempts) { attempt ->
                val raw = callOnce(ref, system, user, maxTokens)
                if (raw == null) {
                    io.legado.app.constant.AppLog.put(
                        "分析AI请求失败（${ref.model.name} 第${attempt + 1}次尝试，超时/HTTP错），换下次尝试或下个模型",
                    )
                    return@repeat
                }
                val parsed = runCatching { validate(raw) }.getOrNull()
                if (parsed != null) return@withContext parsed
                io.legado.app.constant.AppLog.put(
                    "分析AI内容校验失败（${ref.model.name} 第${attempt + 1}次尝试），重试或换模型",
                )
            }
        }
        null
    }

    private fun callOnce(ref: ModelRef, system: String, user: String, maxTokens: Int): String? {
        return runCatching {
            val base = ref.provider.baseUrl.trim().trimEnd('/')
            require(base.isNotBlank()) { "BaseUrl 为空" }
            val jsonType = "application/json; charset=utf-8".toMediaType()
            val request = when (ref.provider.protocol.lowercase()) {
                "google" -> Request.Builder()
                    .url("$base/models/${ref.model.modelId}:generateContent?key=${ref.provider.apiKey}")
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
                                JSONObject().put("temperature", 0).put("maxOutputTokens", maxTokens),
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
                    .post(
                        JSONObject().apply {
                            put("model", ref.model.modelId)
                            put(
                                "messages",
                                JSONArray()
                                    .put(JSONObject().put("role", "system").put("content", system))
                                    .put(JSONObject().put("role", "user").put("content", user)),
                            )
                            put("temperature", 0)
                            put("max_tokens", maxTokens)
                        }.toString().toRequestBody(jsonType),
                    )
                    .build()
            }
            client(ref.model.timeoutMs).newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                require(resp.isSuccessful) { "HTTP ${resp.code}: ${body.take(160)}" }
                extractContent(ref.provider.protocol, body)
            }
        }.getOrElse {
            io.legado.app.constant.AppLog.put(
                "分析AI调用异常（${ref.model.name}）: ${it.localizedMessage ?: it.javaClass.simpleName}",
            )
            null
        }
    }

    private fun extractContent(protocol: String, body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val text = when (protocol.lowercase()) {
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
