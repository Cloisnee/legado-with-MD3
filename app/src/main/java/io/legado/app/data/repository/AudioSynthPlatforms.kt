package io.legado.app.data.repository

import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * B33 · 音频合成平台（内置模板 + 鉴权探测）。
 *
 * 用于：
 *  - 模型库「音频合成平台」区内置卡片的种子数据（[AiModelRepository] 首次加载播种）；
 *  - 「保存并拉取模型」：按平台模板生成合成模型条目（ai_models.json 的 models）；
 *  - 「测试」：音频专用鉴权探测（不产生生成费用；401=Key 无效，其余=连通）。
 *
 * 平台口径（2026-09-30 实探）：
 *  - stepfun    阶跃：Gen（音效/环境）限免 + Music（BGM）限免
 *  - senseaudio 商汤：SFX 0.08 元/组（1~4 条变体）+ Music 0.5 元/首
 *  - elevenlabs 海外：SFX 50 次/月、≤30s
 */
object AudioSynthPlatforms {

    const val PLATFORM_STEPFUN = "stepfun"
    const val PLATFORM_SENSEAUDIO = "senseaudio"
    const val PLATFORM_ELEVENLABS = "elevenlabs"
    const val PLATFORM_CUSTOM = "custom"

    data class TemplateModel(
        val modelId: String,
        val name: String,
        val note: String,
    )

    data class Template(
        val platform: String,
        val name: String,
        val baseUrl: String,
        val models: List<TemplateModel>,
        val note: String,
    )

    val TEMPLATES: List<Template> = listOf(
        Template(
            platform = PLATFORM_STEPFUN,
            name = "阶跃星辰 StepAudio",
            baseUrl = "https://api.stepfun.com",
            models = listOf(
                TemplateModel("stepaudio-3-gen-preview", "StepAudio 3 Gen（音效/环境）", "限时免费"),
                TemplateModel("stepaudio-3-music-preview", "StepAudio 3 Music（BGM）", "限时免费"),
            ),
            note = "音效/环境→Gen、BGM→Music；双模型限时免费（限速较低，批量补缺慢慢来）",
        ),
        Template(
            platform = PLATFORM_SENSEAUDIO,
            name = "SenseAudio（商汤）",
            baseUrl = "https://api.senseaudio.cn",
            models = listOf(
                TemplateModel("senseaudio-sfx-1.0-260626", "SenseAudio SFX（音效/环境）", "0.08 元/组"),
                TemplateModel("senseaudio-music-2.0-260626", "SenseAudio Music（BGM）", "0.5 元/首"),
            ),
            note = "音效 0.08 元/组（一组 1~4 条）、BGM 0.5 元/首；代金券可抵",
        ),
        Template(
            platform = PLATFORM_ELEVENLABS,
            name = "ElevenLabs（海外备选）",
            baseUrl = "https://api.elevenlabs.io",
            models = listOf(
                TemplateModel("eleven_text_to_sound_v2", "ElevenLabs SFX（音效）", "50 次/月、≤30s"),
            ),
            note = "50 次/月、≤30s；适合个别难词补缺",
        ),
    )

    fun templateOf(platform: String): Template? =
        TEMPLATES.firstOrNull { it.platform == platform }

    /** 运行时判定平台：显式 platform 优先；缺失/自定义时按 BaseUrl 推断（兼容现有种子数据） */
    fun effectivePlatform(provider: AiProvider): String {
        val p = provider.platform.trim()
        if (p.isNotBlank() && p != PLATFORM_CUSTOM) return p
        val u = provider.baseUrl.lowercase()
        return when {
            "stepfun" in u -> PLATFORM_STEPFUN
            "senseaudio" in u -> PLATFORM_SENSEAUDIO
            "elevenlabs" in u -> PLATFORM_ELEVENLABS
            else -> PLATFORM_CUSTOM
        }
    }

    data class ProbeResult(
        val ok: Boolean,
        val latencyMs: Long,
        val message: String,
    )

    private val JSON_TYPE = "application/json".toMediaType()

    /**
     * 鉴权探测（不产生生成费用）：
     *  - HTTP 401 / 响应含 authentication_error|invalid_api_key|unauthorized → Key 无效；
     *  - 其余任何响应（含 400/404 等参数类错误）→ 视为连通（Key 探测通过）。
     */
    suspend fun probe(platform: String, baseUrl: String, apiKey: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val key = apiKey.trim()
            if (key.isEmpty()) {
                return@withContext ProbeResult(false, 0L, "未填写 API Key")
            }
            val base = baseUrl.trim().trimEnd('/').ifBlank {
                templateOf(platform)?.baseUrl.orEmpty()
            }
            if (base.isBlank()) {
                return@withContext ProbeResult(false, 0L, "BaseUrl 为空")
            }
            runCatching {
                when (platform) {
                    PLATFORM_STEPFUN -> {
                        // 查询一个不存在的任务：不会创建任务、不产生费用
                        val req = Request.Builder()
                            .url("$base/v1/audio/music/query")
                            .addHeader("Authorization", "Bearer $key")
                            .post("{\"task_id\":\"probe-not-exist\"}".toRequestBody(JSON_TYPE))
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty(), start)
                    }

                    PLATFORM_SENSEAUDIO -> {
                        val req = Request.Builder()
                            .url("$base/v1/sound-effects/generations")
                            .addHeader("Authorization", "Bearer $key")
                            .post("{}".toRequestBody(JSON_TYPE))
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty(), start)
                    }

                    PLATFORM_ELEVENLABS -> {
                        val req = Request.Builder()
                            .url("$base/v1/user")
                            .addHeader("xi-api-key", key)
                            .get()
                            .build()
                        val resp = okHttpClient.newCall(req).await()
                        classify(resp.code, resp.body?.string().orEmpty(), start)
                    }

                    else -> ProbeResult(
                        false,
                        System.currentTimeMillis() - start,
                        "自定义平台暂不支持一键探测（生成时以实际返回为准）",
                    )
                }
            }.getOrElse {
                ProbeResult(
                    false,
                    System.currentTimeMillis() - start,
                    "连接失败：${it.localizedMessage ?: it.javaClass.simpleName}",
                )
            }
        }

    /** 音频模型「测试」：不产生费用的鉴权探测（内置平台走专属端点；其余按协议探测） */
    suspend fun probeProvider(provider: AiProvider): ProbeResult {
        val platform = effectivePlatform(provider)
        if (platform != PLATFORM_CUSTOM) {
            return probe(platform, provider.baseUrl, provider.apiKey)
        }
        return probeByProtocol(provider.protocol, provider.baseUrl, provider.apiKey)
    }

    private suspend fun probeByProtocol(
        protocol: String,
        baseUrl: String,
        apiKey: String,
    ): ProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val key = apiKey.trim()
        if (key.isEmpty()) return@withContext ProbeResult(false, 0L, "未填写 API Key")
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return@withContext ProbeResult(false, 0L, "BaseUrl 为空")
        runCatching {
            val req = when (protocol.lowercase()) {
                "google" -> Request.Builder()
                    .url("$base/models?key=$key")
                    .get()
                    .build()

                "claude" -> Request.Builder()
                    .url("$base/models")
                    .header("x-api-key", key)
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()

                else -> Request.Builder()
                    .url("$base/models")
                    .header("Authorization", "Bearer $key")
                    .get()
                    .build()
            }
            val resp = okHttpClient.newCall(req).await()
            classify(resp.code, resp.body?.string().orEmpty(), start)
        }.getOrElse {
            ProbeResult(
                false,
                System.currentTimeMillis() - start,
                "连接失败：${it.localizedMessage ?: it.javaClass.simpleName}",
            )
        }
    }

    private fun classify(code: Int, body: String, start: Long): ProbeResult {
        val latency = System.currentTimeMillis() - start
        val lower = body.lowercase()
        return when {
            code == 401 -> ProbeResult(false, latency, "Key 无效（HTTP 401）")
            lower.contains("authentication_error") ||
                lower.contains("invalid_api_key") ||
                lower.contains("unauthorized") -> ProbeResult(false, latency, "Key 无效（HTTP $code）")

            code in 200..299 -> ProbeResult(true, latency, "Key 探测通过（HTTP $code）")
            code in 400..499 -> ProbeResult(
                true,
                latency,
                "Key 探测通过（HTTP $code；正式生成以实际返回为准）",
            )

            else -> ProbeResult(false, latency, "响应异常：HTTP $code")
        }
    }
}