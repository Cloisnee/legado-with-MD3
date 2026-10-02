package io.legado.app.help.readaloud.audio

import android.util.Base64
import io.legado.app.data.repository.AiModelEntry
import io.legado.app.data.repository.AiProvider
import io.legado.app.data.repository.AudioSynthPlatforms
import io.legado.app.help.http.await
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B33.3c · 音频合成平台生成适配器（缺失补缺用）。
 *
 * 契约（工作区实测 + 官方文档，见《B33 施工方案》§8.7/§8.8）：
 *  - 阶跃 Gen  ：`POST /v1/audio/generate`（同步直出 mp3；sample_rate=48000；`return_url` 模式为 JSON `data.url`）
 *  - 阶跃 Music：`POST /v1/audio/music/submit` + `POST /v1/audio/music/query` 轮询（SUCCESS 的 `audio` 为 base64 mp3）
 *  - SenseAudio SFX  ：`POST /v1/sound-effects/generations`（同步 1~4 变体 → `items[0].audio_url`）
 *  - SenseAudio A1   ：`POST /v1/audio/generate`（`prompt` 编排出片 → 顶层 `audio_url`）
 *  - SenseAudio Music：`POST /v2/music/song/create` + `GET /v1/music/song/pending/{task_id}` 轮询（SUCCESS → `response.data[0].audio_url`）
 *  - ElevenLabs：`POST /v1/sound-generation`（直出音频字节）
 *  - 端点统一经 [AudioSynthPlatforms.endpoint] 归一：BaseUrl 带/不带 `/v1` 均可，防 `v1/v1` 双段 404。
 *
 * 派单失败语义：任何一步失败 → 抛异常，由 [AudioSynthQueue] 换下一个模型继续尝试。
 */
object AudioSynthClients {

    data class GenResult(val bytes: ByteArray, val detail: String)

    private const val MUSIC_POLL_MS = 6_000L
    private const val MUSIC_TIMEOUT_MS = 10 * 60 * 1000L

    private val JSON_TYPE = "application/json".toMediaType()

    /** 长超时客户端（音乐异步轮询/生成等待；连接池复用宿主） */
    private val http: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    /** 按平台生成一条音频；成功返回 mp3 字节。desc=Ai 导演生成描述（提示词 desc 优先，见 [AudioPrompts]） */
    suspend fun generate(
        provider: AiProvider,
        model: AiModelEntry,
        lane: SynthLane,
        keyword: String,
        desc: String = "",
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val platform = AudioSynthPlatforms.effectivePlatform(provider)
            val base = provider.baseUrl.trim().trimEnd('/').ifBlank {
                AudioSynthPlatforms.templateOf(platform)?.baseUrl.orEmpty()
            }
            require(base.isNotBlank()) { "BaseUrl 为空" }
            require(provider.apiKey.isNotBlank()) { "未填写 API Key" }
            val prompt = AudioPrompts.of(lane, keyword, desc)
            when (platform) {
                AudioSynthPlatforms.PLATFORM_STEPFUN ->
                    if (isMusicModel(model.modelId)) {
                        stepfunMusic(base, provider.apiKey, model, prompt)
                    } else {
                        stepfunGen(base, provider.apiKey, model, prompt)
                    }

                AudioSynthPlatforms.PLATFORM_SENSEAUDIO -> when {
                    isMusicModel(model.modelId) ->
                        senseMusic(base, provider.apiKey, model, prompt)

                    isAudioGenModel(model.modelId) ->
                        senseAudioGenerate(base, provider.apiKey, model, prompt)

                    else -> senseSfx(base, provider.apiKey, model, lane, keyword, desc)
                }

                AudioSynthPlatforms.PLATFORM_ELEVENLABS ->
                    elevenLabsSfx(base, provider.apiKey, model, prompt)

                else -> error("自定义平台暂不支持自动生成")
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
        }
    }

    private fun isMusicModel(modelId: String): Boolean =
        modelId.contains("music", ignoreCase = true)

    private fun isAudioGenModel(modelId: String): Boolean =
        modelId.trim().lowercase().startsWith("senseaudio-a1")

    // ------------------------------------------------------------ 阶跃

    private suspend fun stepfunGen(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("task", "text_to_audio")
            put("scripts", JSONArray().put(JSONObject().put("text", prompt)))
            put("response_format", "mp3")
            put("sample_rate", 48000)
        }.toString()
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/audio/generate"))
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val ctype = resp.header("Content-Type").orEmpty()
        if (ctype.contains("json", ignoreCase = true)) {
            val text = resp.body?.string().orEmpty()
            require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(160)}" }
            val url = JSONObject(text).let { o ->
                o.optJSONObject("data")?.optString("url").orEmpty()
                    .ifBlank { o.optString("url") }
            }
            require(url.isNotBlank()) { "返回 JSON 但无音频 URL：${text.take(160)}" }
            return downloadAudio(url)
        }
        if (!resp.isSuccessful) {
            val text = resp.body?.string().orEmpty()
            error("HTTP ${resp.code}: ${text.take(160)}")
        }
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        require(bytes.isNotEmpty()) { "空响应" }
        return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
    }

    private suspend fun stepfunMusic(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val started = System.currentTimeMillis()
        val submitBody = JSONObject().apply {
            put("task", "text_to_music")
            put("model_id", model.modelId)
            put("caption", prompt)
            put("instrumental", true)
            put("response_format", "mp3")
        }.toString()
        val submitReq = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/audio/music/submit"))
            .addHeader("Authorization", "Bearer $key")
            .post(submitBody.toRequestBody(JSON_TYPE))
            .build()
        val submitResp = http.newCall(submitReq).await()
        val submitText = submitResp.body?.string().orEmpty()
        require(submitResp.isSuccessful) { "submit HTTP ${submitResp.code}: ${submitText.take(160)}" }
        val taskId = JSONObject(submitText).let { o ->
            o.optString("task_id").ifBlank { o.optJSONObject("data")?.optString("task_id").orEmpty() }
        }
        require(taskId.isNotBlank()) { "submit 无 task_id：${submitText.take(160)}" }

        val deadline = System.currentTimeMillis() + MUSIC_TIMEOUT_MS
        var consecutiveFails = 0
        while (System.currentTimeMillis() < deadline) {
            delay(MUSIC_POLL_MS)
            val poll = try {
                queryMusicOnce(base, key, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFails++
                if (consecutiveFails >= 8) error("轮询连续失败：${e.localizedMessage}")
                continue
            }
            consecutiveFails = 0
            if (poll.audioB64.isNotBlank()) {
                val bytes = decodeBase64Audio(poll.audioB64)
                return GenResult(bytes, "异步 ${(System.currentTimeMillis() - started) / 1000}s · mp3 ${bytes.size / 1024}KB")
            }
            if (poll.status.equals("FAILED", true) || poll.status.equals("ERROR", true)) {
                error("任务失败：${poll.raw.take(160)}")
            }
        }
        error("等待超时（${MUSIC_TIMEOUT_MS / 60_000} 分钟）")
    }

    private data class MusicPoll(val status: String, val audioB64: String, val raw: String)

    private suspend fun queryMusicOnce(base: String, key: String, taskId: String): MusicPoll {
        val body = JSONObject().put("task_id", taskId).toString()
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/audio/music/query"))
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "query HTTP ${resp.code}: ${text.take(160)}" }
        val o = JSONObject(text)
        val data = o.optJSONObject("data")
        val status = o.optString("status").ifBlank { data?.optString("status").orEmpty() }
        val audio = o.optString("audio").ifBlank { data?.optString("audio").orEmpty() }
        return MusicPoll(status, audio, text)
    }

    // ------------------------------------------------------------ SenseAudio / ElevenLabs

    /** SenseAudio 音效（/v1/sound-effects/generations）：同步出 1~4 条变体，取第一条可用 audio_url 下载 */
    private suspend fun senseSfx(
        base: String,
        key: String,
        model: AiModelEntry,
        lane: SynthLane,
        keyword: String,
        desc: String = "",
    ): GenResult {
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("text", AudioPrompts.senseText(lane, keyword, desc))
            if (lane == SynthLane.SFX) {
                // 音效＝短频快：固定 5 秒（1~10s；智能时长会到 ~11s 偏长）
                put("smart_duration", false)
                put("duration_seconds", 5)
            }
        }.toString()
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/sound-effects/generations"))
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val ctype = resp.header("Content-Type").orEmpty()
        if (ctype.contains("audio", ignoreCase = true)) {
            require(resp.isSuccessful) { "HTTP ${resp.code}" }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            require(bytes.isNotEmpty()) { "空响应" }
            return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
        }
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(160)}" }
        return extractAudioFromJson(JSONObject(text), text)
    }

    /** SenseAudio A1（/v1/audio/generate）：prompt 全轨编排，强制 mp3 输出；计费 ≈0.017 元/秒 */
    private suspend fun senseAudioGenerate(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("model", model.modelId)
            put("prompt", prompt)
            put("references", JSONArray())
            put("audio_config", JSONObject().apply { put("format", "mp3") })
        }.toString()
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/audio/generate"))
            .addHeader("Authorization", "Bearer $key")
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(160)}" }
        return extractAudioFromJson(JSONObject(text), text)
    }

    /** SenseAudio Music 2.0（异步）：/v2/music/song/create → 轮询 /v1/music/song/pending；instrumental 纯器乐 */
    private suspend fun senseMusic(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val started = System.currentTimeMillis()
        val submitBody = JSONObject().apply {
            put("model", model.modelId)
            put("prompt", prompt)
            put("mode", "instrumental")
            put("audio_settings", JSONObject().apply { put("format", "mp3") })
        }.toString()
        val submitReq = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v2/music/song/create"))
            .addHeader("Authorization", "Bearer $key")
            .post(submitBody.toRequestBody(JSON_TYPE))
            .build()
        val submitResp = http.newCall(submitReq).await()
        val submitText = submitResp.body?.string().orEmpty()
        require(submitResp.isSuccessful) { "submit HTTP ${submitResp.code}: ${submitText.take(160)}" }
        val taskId = JSONObject(submitText).let { o ->
            o.optString("task_id").ifBlank { o.optJSONObject("data")?.optString("task_id").orEmpty() }
        }
        require(taskId.isNotBlank()) { "submit 无 task_id：${submitText.take(160)}" }

        val deadline = System.currentTimeMillis() + MUSIC_TIMEOUT_MS
        var consecutiveFails = 0
        while (System.currentTimeMillis() < deadline) {
            delay(MUSIC_POLL_MS)
            val poll = try {
                querySenseMusicOnce(base, key, taskId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFails++
                if (consecutiveFails >= 8) error("轮询连续失败：${e.localizedMessage}")
                continue
            }
            consecutiveFails = 0
            if (poll.url.isNotBlank()) {
                val gen = downloadAudio(poll.url)
                return GenResult(gen.bytes, "异步 ${(System.currentTimeMillis() - started) / 1000}s · ${gen.detail}")
            }
            if (poll.status.equals("FAILED", true) || poll.status.equals("ERROR", true)) {
                error("任务失败：${poll.failReason.ifBlank { poll.raw.take(160) }}")
            }
        }
        error("等待超时（${MUSIC_TIMEOUT_MS / 60_000} 分钟）")
    }

    private data class SenseMusicPoll(val status: String, val url: String, val failReason: String, val raw: String)

    private suspend fun querySenseMusicOnce(base: String, key: String, taskId: String): SenseMusicPoll {
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/music/song/pending/$taskId"))
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val resp = http.newCall(req).await()
        val text = resp.body?.string().orEmpty()
        require(resp.isSuccessful) { "query HTTP ${resp.code}: ${text.take(160)}" }
        val o = JSONObject(text)
        val item = o.optJSONObject("response")?.optJSONArray("data")?.optJSONObject(0)
        val url = item?.let { d ->
            d.optString("audio_url").ifBlank { d.optJSONObject("derived_audio")?.optString("mp3").orEmpty() }
        }.orEmpty()
        return SenseMusicPoll(
            status = o.optString("status"),
            url = url,
            failReason = o.optString("fail_reason"),
            raw = text,
        )
    }

    private suspend fun elevenLabsSfx(
        base: String,
        key: String,
        model: AiModelEntry,
        prompt: String,
    ): GenResult {
        val body = JSONObject().apply {
            put("text", prompt)
            put("model_id", model.modelId.ifBlank { "eleven_text_to_sound_v2" })
        }.toString()
        val req = Request.Builder()
            .url(AudioSynthPlatforms.endpoint(base, "/v1/sound-generation"))
            .addHeader("xi-api-key", key)
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        val resp = http.newCall(req).await()
        if (!resp.isSuccessful) {
            error("HTTP ${resp.code}: ${resp.body?.string()?.take(160).orEmpty()}")
        }
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        require(bytes.isNotEmpty()) { "空响应" }
        return GenResult(bytes, "mp3 ${bytes.size / 1024}KB")
    }

    // ------------------------------------------------------------ 工具

    /** 兜底解析：从 JSON 响应中尽量提取音频（base64 字段 / 下载 URL；兼容 items[]、response.data[] 等结构） */
    private suspend fun extractAudioFromJson(root: JSONObject, raw: String): GenResult {
        val data = root.optJSONObject("data")
        val candidates = listOfNotNull(
            root,
            data,
            root.optJSONArray("items")?.firstAudioItem(),
            data?.optJSONArray("items")?.firstAudioItem(),
            root.optJSONObject("response")?.optJSONArray("data")?.optJSONObject(0),
            data?.optJSONArray("audios")?.optJSONObject(0),
            root.optJSONArray("data")?.optJSONObject(0),
        )
        val b64 = candidates.firstNotNullOfOrNull { obj ->
            listOf("audio", "audio_base64", "audioBase64", "base64").firstNotNullOfOrNull { field ->
                obj.optString(field).takeIf { it.isNotBlank() }
            }
        }
        if (!b64.isNullOrBlank()) {
            return GenResult(decodeBase64Audio(b64), "base64 解码")
        }
        val url = candidates.firstNotNullOfOrNull { obj ->
            listOf("url", "audio_url", "audioUrl").firstNotNullOfOrNull { field ->
                obj.optString(field).takeIf { it.isNotBlank() }
            }
        }
        if (!url.isNullOrBlank()) {
            return downloadAudio(url)
        }
        error("无法解析音频响应：${raw.take(200)}")
    }

    /** items[] 中挑第一条可用对象（优先带 audio_url/url 的变体，否则取第一条） */
    private fun JSONArray.firstAudioItem(): JSONObject? {
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            if (o.optString("audio_url").isNotBlank() || o.optString("url").isNotBlank()) return o
        }
        return optJSONObject(0)
    }

    private suspend fun downloadAudio(url: String): GenResult {
        val resp = http.newCall(Request.Builder().url(url).build()).await()
        require(resp.isSuccessful) { "音频下载失败 HTTP ${resp.code}" }
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        require(bytes.isNotEmpty()) { "音频下载为空" }
        return GenResult(bytes, "url 下载 ${bytes.size / 1024}KB")
    }

    private fun decodeBase64Audio(b64: String): ByteArray {
        val raw = b64.substringAfter(',', b64).trim()
        val bytes = Base64.decode(raw, Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "audio 解码为空" }
        return bytes
    }
}
